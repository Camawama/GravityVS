package net.camacraft.gravityunbound.capabilities;

import java.util.ArrayList;
import java.util.List;

import net.camacraft.gravityunbound.EntityTags;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.api.RotationParameters;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.init.GravityMobEffects;
import net.camacraft.gravityunbound.item.GravityAnchorItem;
import net.camacraft.gravityunbound.mob_effect.GravityDirectionMobEffect;
import net.camacraft.gravityunbound.network.GravityNetwork;
import net.camacraft.gravityunbound.network.UpdateGravityCapabilityPacket;
import net.camacraft.gravityunbound.util.GCUtil;
import net.camacraft.gravityunbound.util.QuaternionUtil;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.PacketDistributor;

/**
 * Gravity state for one entity.
 *
 * Architecture:
 * - The COLLISION BOX is always one of the six cardinal orientations
 *   ({@link #currGravityDirection}) — a Minecraft AABB cannot be tilted, so
 *   anything else corrupts collision. This is invisible scaffolding.
 * - Everything the player experiences — camera, model, aim, movement direction
 *   and the gravity pull itself — follows the VISUAL frame
 *   ({@link #visualRotation}): a continuous, rate-limited quaternion that
 *   chases the true (arbitrary-angle) field vector. It converges EXACTLY onto
 *   the canonical cardinal frame whenever the field is cardinal, so in all
 *   cardinal situations the math is bit-identical to the original mod, and
 *   during transitions / on tilted ships everything rotates smoothly at
 *   arbitrary angles.
 * - Because the visual frame never jumps, no camera animation and no yaw
 *   remapping are needed; local velocity/look simply rotate with the frame.
 */
public class GravityCapabilityImpl implements IGravityCapability {
    public static final Vec3 DOWN = new Vec3(0, -1, 0);

    // Baseline gravity acceleration (blocks/tick^2) that a strength of 1.0
    // corresponds to — vanilla's living-entity gravity, and the base value of
    // Forge's forge:entity_gravity attribute.
    public static final double BASE_GRAVITY_ACCEL = 0.08;

    // Fixed id for the transient forge:entity_gravity modifier that applies
    // this capability's gravity strength. Forge's patched travel() reads its
    // gravity from that ATTRIBUTE (the 0.08 constant it loads is immediately
    // overwritten by the attribute value), so scaling the constant — the old
    // approach — never did anything for living entities.
    private static final java.util.UUID GRAVITY_STRENGTH_MODIFIER_ID =
        java.util.UUID.fromString("7e5be1cf-3b12-4790-8b71-d9ab3b884a2e");

    // hysteresis: a new cardinal must be this much better aligned (dot product)
    // with the field vector before the collision box snaps to it
    private static final double SNAP_HYSTERESIS_DOT = 0.10;
    // opposite flips additionally require a stable target for a few ticks
    private static final int OPPOSITE_FLIP_STABLE_TICKS = 3;
    // how fast the visual frame may rotate
    private static final float VISUAL_TURN_PER_TICK = (float) Math.toRadians(15);
    // during a COMMITTED surface change (walking around a cube edge) the frame
    // turns faster: while it rotates, gravity pins the player against the edge
    // corner and movement input points half-way between the faces — the longer
    // that window, the longer the player is visibly stuck on the edge
    private static final float TRANSITION_TURN_PER_TICK = (float) Math.toRadians(30);
    // airborne inside a SHIP-mounted field the target is exact (re-derived
    // from the live transform, no packet noise), so the chase may track it
    // faster than the noisy-field cap — the render pass then needs no
    // correction of its own (see getRenderRotation)
    private static final float SHIP_FIELD_TURN_PER_TICK = (float) Math.toRadians(30);
    // creative flight lets the velocity turn WITH the frame (that is what
    // closes orbits around cores) — but only this much per tick; the rest
    // of a fast snap preserves world velocity, or the flight path kinks by
    // the whole step at every tick boundary
    private static final float FLY_DRAG_PER_TICK = (float) Math.toRadians(4);
    // after leaving a field, keep its pull for a few ticks (jumping off a plate
    // must not instantly revert gravity mid-air)
    private static final int FIELD_GRACE_TICKS = 6;

    // diagnostics: field effects queued since last heartbeat window
    private int dbgEffectsQueued = 0;
    // diagnostics: player position at the start of this tick (for the
    // render-time comparison in CameraMixin — if VS render-rides the local
    // player, the camera-time position diverges from the tick position)
    public Vec3 dbgTickPos = Vec3.ZERO;
    public volatile float dbgCamVsTickDist = 0;
    // field-pull deficit heartbeat state (see applyFieldPullDeficit)
    private double dbgDeficitApplied = 0;
    private String dbgDeficitState = "-";

    // SHIP ATTACHMENT — the one ship-riding mechanism (see applyShipAttachCarry
    // / updateShipAttachment / getRenderRotation). While the entity rides a
    // Valkyrien Skies ship (the dominant field is mounted on it, or one of its
    // faces is the held surface) the visual frame is carried by the ship's
    // FULL tick rotation, and the render frame is rebuilt SHIP-RELATIVELY
    // against the pose the ship is actually DRAWN at.
    @Nullable
    private org.valkyrienskies.core.api.ships.Ship attachShip = null;
    // the ship->world rotation the CURRENT tick frame corresponds to, and the
    // one the PREVIOUS tick frame corresponds to: frame * rot is the frame in
    // the ship's own coordinates, which is what render interpolation must
    // interpolate (a rider's ship-relative orientation is what stays put)
    private final org.joml.Quaterniond attachRot = new org.joml.Quaterniond();
    private final org.joml.Quaterniond attachRotPrev = new org.joml.Quaterniond();
    private boolean attachRotValid = false;
    // eased 0..1 engagement of the render-time reconstruction (smooth
    // snap-in when a ship field is entered, smooth release when it is left);
    // the previous tick's value is kept so the render pass can interpolate
    // the engagement per FRAME — a weight that stepped once per tick moved
    // the drawn frame in visible jumps at every tick boundary while a ship
    // field was being entered or left
    private volatile float attachWeight = 0;
    private volatile float attachWeightPrev = 0;
    // ship the render pass reconstructs against (kept through the fade-out)
    @Nullable
    private org.valkyrienskies.core.api.ships.Ship renderAttachShip = null;
    // after the ground probe stops hitting, keep the surface alignment for a
    // few ticks (jumps and probe flicker must not wobble the frame; the probe
    // re-acquires during a jump's descent, so this only needs to bridge the
    // ascent and apex)
    // sized to outlast a full jump (~13 ticks up-and-down): with the
    // descent no longer tripping the cliff early-release, a hop keeps its
    // held surface for the whole arc and re-grounds without ever unsnapping
    private static final int GROUND_NORMAL_GRACE_TICKS = 16;
    // how far past the feet the ground probe reaches, along the field's down
    private static final double GROUND_PROBE_DEPTH = 0.6;

    public boolean initialized = false;

    // not synchronized
    private Direction prevGravityDirection = Direction.DOWN;
    private double prevGravityStrength = 1.0;

    // the base gravity
    Vec3 baseGravityDirection = DOWN;
    double baseGravityStrength = 1.0;

    // applied physics state (always cardinal)
    private Direction currGravityDirection = Direction.DOWN;
    private double currGravityStrength = 1.0;

    // the true (possibly arbitrary-angle) field vector
    private @Nullable Vec3 targetGravityVector = null;
    private double targetGravityStrength = 1.0;
    private int oppositeStableTicks = 0;
    private int fieldGraceTicks = 0;

    // the visual/aim frame (world->player) and its per-tick history for render
    // interpolation; visualTarget is the frame the field vector asks for
    private final Quaternionf visualRotation = new Quaternionf();
    private final Quaternionf prevVisualRotation = new Quaternionf();
    private final Quaternionf visualTarget = new Quaternionf();
    // remote entities receive their visual target from the server
    private @Nullable Quaternionf syncedVisualTarget = null;
    private final Quaternionf lastSyncedVisualTarget = new Quaternionf();

    // capsule collision state (players under non-default gravity)
    public boolean capsuleGrounded = false;
    public @Nullable org.valkyrienskies.core.api.ships.Ship capsuleGroundShip = null;
    // world-space normal of the surface being stood on
    public @Nullable Vec3 capsuleGroundNormal = null;
    // Whether the dominant field source allows planet-walk surface snapping
    // (per-block "snap" toggle on plating/cores). Resolved per tick in
    // resolveGravityTarget; true under base gravity / no field.
    private boolean surfaceAlignAllowed = true;

    // A held/probed surface only counts as support while the field endorses
    // it at least this much (dot of face normal with field up). 0.15 keeps
    // 45-degree blend faces and every legitimate planet face, but releases
    // faces the field is near-PERPENDICULAR to — standing on the side of a
    // tall thin tower inside a core field, gravity points along the tower,
    // and the old -0.1 gate (kept anything short of active repulsion) let
    // players stand there anyway.
    private static final double SUPPORT_KEEP_DOT = 0.15;

    // the block face under the feet found by the ground probe; this — never a
    // per-tick contact normal — is what the frame aligns to
    private @Nullable Vec3 lastGroundNormal = null;
    private int groundNormalGraceTicks = 0;
    // commitment window after a face change: no further face changes until the
    // frame has finished rotating to the new face (kills every transition
    // ping-pong oscillator by construction)
    private int surfaceChangeCooldown = 0;
    private static final int SURFACE_CHANGE_COOLDOWN_TICKS = 8;

    // a face normal that was RELEASED moments ago (walked off an edge while
    // the wrap probe missed): if a new face is acquired shortly after, the
    // pair still counts as a face change so momentum rotates around the edge
    // (see rotateVelocityAcrossSurfaceChange). Without this, falling onto the
    // next face of a cube kept the built-up fall velocity TANGENTIAL to that
    // face — the player flew far along it (or straight past it) instead of
    // being pressed onto it.
    private @Nullable Vec3 recentReleasedNormal = null;
    private int recentReleasedTicks = 0;
    private static final int RECENT_RELEASE_MEMORY_TICKS = 15;

    // the ship most recently stood on, kept alive through jumps/probe misses:
    // the per-tick position delta used by the surface probes must have the
    // SHIP's own carry subtracted (VS repositions dragged entities every
    // tick), or standing still on a moving ship reads as fast tangential
    // movement — firing the edge probes spuriously — and a descending ship
    // reads as "falling away from the held face", releasing the hold early
    private @Nullable org.valkyrienskies.core.api.ships.Ship lastGroundShip = null;

    // last game time the plating artificial-gravity force (zero-g dimensions,
    // non-living entities) was applied — every plate in range tries each
    // tick; only the first succeeds, so stacked fields never multiply it
    private long artificialPullTick = Long.MIN_VALUE;

    // SURFACE CLING (enchantment.SurfaceClingEnchantment) state kept on the
    // entity: the let-go timer after a jump, the target the controlling
    // client reported to the server (the server mirrors it instead of
    // guessing from input it cannot see), and the last report sent
    public int clingReleaseTicks = 0;
    public boolean clingReportedActive = false;
    public boolean clingReportedReleased = false;
    public @Nullable Vec3 clingReportedDown = null;
    public long clingReportedShipId = -1L;
    public @Nullable Vec3 clingReportedLocalDown = null;
    public int clingReportedAge = Integer.MAX_VALUE;
    public boolean clingLastSentActive = false;
    public @Nullable Vec3 clingLastSentDown = null;
    public long clingLastSentShipId = -1L;

    /**
     * LET GO of the current field right now: no lingering grace pull, no
     * held surface. Used when Surface Cling releases on a jump — the
     * 6-tick grace exists so plate fields keep pulling through a jump, but
     * a wearer jumping off a wall must simply fall (or float, in zero-g).
     * Declined while a PRIMARY block field is pending this tick: an
     * engineered field owns the entity and keeps its own grace.
     */
    public void releaseFieldGraceNow() {
        for (GravityDirEffect pending : delayApplyDirEffects) {
            if (!pending.secondary()) {
                return;
            }
        }
        fieldGraceTicks = 0;
        lastFieldVector = null;
    }

    /** A surface probe hit: the face normal (world space) and the ship it belongs to, if any. */
    public record SurfaceHit(Vec3 normal, @Nullable org.valkyrienskies.core.api.ships.Ship ship, net.minecraft.core.BlockPos pos) {
    }

    /** See {@code GravityPlatingBlockEntity.gravityunbound$applyArtificialGravityForce}. */
    public boolean tryClaimArtificialPull(long gameTime) {
        if (artificialPullTick == gameTime) {
            return false;
        }
        artificialPullTick = gameTime;
        return true;
    }

    // ship-relative idle anchor: standing still on ship plating pins the feet
    // to a fixed SHIPYARD-space point (drift-free on rotating contraptions by
    // construction — the pinned point rotates exactly with the ship)
    private @Nullable org.joml.Vector3d shipAnchorPos = null;
    private long shipAnchorShipId = 0;

    // watchdog: capsule mode with zero gravity influence should be impossible;
    // if it persists anyway, snap out instead of leaving the player stuck with
    // sphere collision in the plain world
    private int capsuleNoInfluenceTicks = 0;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    // chase-target stability: a still target is converged on decisively, a
    // moving one is followed with smoothing
    private final Quaternionf lastChaseTarget = new Quaternionf();
    private int targetStableTicks = 0;

    // the client player only adopts the server's cardinal after a persistent
    // disagreement (single-packet flips near 45-degree field regions caused a
    // canonical-frame flip war)
    private int serverDirectionDisagreeStreak = 0;

    @Nullable RotationParameters currentRotationParameters = RotationParameters.getDefault();

    public Entity entity;

    private boolean isFiringUpdateEvent = false;

    private final List<GravityDirEffect> delayApplyDirEffects = new ArrayList<>();
    private final List<GravityDirEffect> tempEffects = new ArrayList<>();

    private double delayApplyStrengthEffect = 1.0;

    // force a sync packet on the next server tick
    public boolean needsSync = false;
    private @Nullable Direction lastSyncedDirection = null;
    private double lastSyncedStrength = 1.0;

    public boolean noAnimation = false;
    public boolean noPositionAdjust = false;

    @Override
    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    // ------------------------------------------------------------------
    // serialization
    // ------------------------------------------------------------------

    private static Direction directionFromTag(CompoundTag tag, String vecPrefix, String legacyName, Direction fallback) {
        if (tag.contains(vecPrefix + "X")) {
            return Direction.getNearest(
                tag.getDouble(vecPrefix + "X"),
                tag.getDouble(vecPrefix + "Y"),
                tag.getDouble(vecPrefix + "Z")
            );
        }
        if (tag.contains(legacyName)) {
            Direction dir = Direction.byName(tag.getString(legacyName));
            if (dir != null) {
                return dir;
            }
        }
        return fallback;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        baseGravityDirection = Vec3.atLowerCornerOf(
            directionFromTag(tag, "baseGravityDirection", "baseGravityDirection", Direction.DOWN).getNormal()
        );

        if (tag.contains("baseGravityStrength")) {
            baseGravityStrength = tag.getDouble("baseGravityStrength");
        }
        else {
            baseGravityStrength = 1.0;
        }

        // the current gravity is serialized to avoid unnecessary rotation when entering the world
        if (!initialized || shouldAcceptServerSync()) {
            currGravityDirection = directionFromTag(tag, "currentGravityDirection", "currentGravityDirection", Direction.DOWN);

            if (tag.contains("currentGravityStrength")) {
                currGravityStrength = tag.getDouble("currentGravityStrength");
            }
            else {
                currGravityStrength = 1.0;
            }
        }

        if (!initialized) {
            prevGravityDirection = currGravityDirection;
            prevGravityStrength = currGravityStrength;
            Quaternionf canonical = RotationUtil.getWorldRotationQuaternion(currGravityDirection);
            visualRotation.set(canonical);
            prevVisualRotation.set(canonical);
            visualTarget.set(canonical);
            initialized = true;
            this.needsSync = true;
            this.noAnimation = true;

            if (entity != null) {
                updateBoundingBox();
            }
        }
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString("baseGravityDirection", getBaseGravityDirection().getName());
        tag.putString("currentGravityDirection", currGravityDirection.getName());
        tag.putDouble("baseGravityStrength", baseGravityStrength);
        tag.putDouble("currentGravityStrength", currGravityStrength);
        return tag;
    }

    // ------------------------------------------------------------------
    // main tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        if (entity == null) {
            return;
        }

        // Spectators are not affected by gravity: smoothly reset to default
        // instead of freezing in whatever orientation the player last had
        // (canChangeGravity() excludes spectators, so without this the whole
        // capability just stopped ticking and the rotated frame stuck forever).
        if (entity instanceof Player player && player.isSpectator()) {
            resetForSpectator();
            if (!entity.level().isClientSide()) {
                maybeSendSync();
            }
            return;
        }

        if (!canChangeGravity()) {
            return;
        }

        applyShipIdleAnchor();
        applyShipAttachCarry();
        if (entity.tickCount % 40 == 0
            && (entity instanceof Player)
            && (dbgEffectsQueued > 0 || lastGroundNormal != null || !isVisuallyDefault())) {
            Vec3 tUp = RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), visualTarget);
            Vec3 fUp = RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), visualRotation);
            boolean vsDraggable = org.valkyrienskies.mod.common.util.EntityDragger.isDraggable(entity);
            org.valkyrienskies.mod.common.util.EntityDraggingInformation dbgDrag =
                ((org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider) entity).getDraggingInformation();
            LOGGER.info(
                "[GravityUnbound] vs-drag[{}]: draggable={} beingDragged={} shipRef={} ticksSince={} camVsTickDist={}",
                entity.level().isClientSide() ? "C" : "S",
                vsDraggable, dbgDrag.isEntityBeingDraggedByAShip(),
                dbgDrag.getLastShipStoodOn() != null, dbgDrag.getTicksSinceStoodOnShip(),
                String.format(java.util.Locale.ROOT, "%.4f", dbgCamVsTickDist));
            LOGGER.info(
                "[GravityUnbound] chain[{}]: fx/t={} grace={} held={} tgtVec={} tgtUp={} frameUp={} gapDeg={} capsule={}",
                entity.level().isClientSide() ? "C" : "S",
                dbgEffectsQueued, fieldGraceTicks,
                lastGroundNormal == null ? "null" : String.format(java.util.Locale.ROOT,
                    "(%.2f,%.2f,%.2f)", lastGroundNormal.x, lastGroundNormal.y, lastGroundNormal.z),
                targetGravityVector == null ? "null" : String.format(java.util.Locale.ROOT,
                    "(%.2f,%.2f,%.2f)", targetGravityVector.x, targetGravityVector.y, targetGravityVector.z),
                String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", tUp.x, tUp.y, tUp.z),
                String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", fUp.x, fUp.y, fUp.z),
                String.format(java.util.Locale.ROOT, "%.1f",
                    Math.toDegrees(angleBetween(visualRotation, visualTarget))),
                useCapsuleCollision());
            Vec3 dbgDm = entity.getDeltaMovement();
            LOGGER.info(
                "[GravityUnbound] pull[{}]: {} deficit={} strength={} grounded={}/{} dmLocal=({},{},{})",
                entity.level().isClientSide() ? "C" : "S",
                dbgDeficitState,
                String.format(java.util.Locale.ROOT, "%.4f", dbgDeficitApplied),
                String.format(java.util.Locale.ROOT, "%.3f", currGravityStrength),
                capsuleGrounded, entity.onGround(),
                String.format(java.util.Locale.ROOT, "%.4f", dbgDm.x),
                String.format(java.util.Locale.ROOT, "%.4f", dbgDm.y),
                String.format(java.util.Locale.ROOT, "%.4f", dbgDm.z));
        }
        dbgEffectsQueued = 0;
        dbgTickPos = entity.position();
        updateGravityStatus();
        applyGravityChange();
        applyGravityStrengthAttribute();
        applyFieldPullDeficit();
        maybeSnapFreshSpawn();
        updateSurfaceProbe();
        updateShipAttachment();
        advanceVisualRotation();
        applyTransitionPull();
        applyStaticFriction();
        capsuleExitWatchdog();

        if (!entity.level().isClientSide()) {
            maybeSendSync();
        }
    }

    /**
     * Spectator tick: drop every field influence and chase the frame smoothly
     * back to plain downward gravity. Base gravity (set by command/items) is
     * deliberately kept — it re-applies when the player leaves spectator mode.
     */
    private void resetForSpectator() {
        tempEffects.clear();
        delayApplyDirEffects.clear();
        delayApplyStrengthEffect = 1.0;
        fieldGraceTicks = 0;
        lastFieldVector = null;
        lastGroundNormal = null;
        groundNormalGraceTicks = 0;
        surfaceChangeCooldown = 0;
        recentReleasedNormal = null;
        recentReleasedTicks = 0;
        shipAnchorPos = null;
        lastGroundShip = null;
        attachShip = null;
        renderAttachShip = null;
        attachRotValid = false;
        attachWeight = 0;
        attachWeightPrev = 0;
        pendingShipDelta = null;
        fieldAnchorShip = null;
        fieldAnchorLocalDown = null;
        fieldAnchorLocalPos = null;
        fieldRegion = null;
        fieldRegionShip = null;

        targetGravityVector = DOWN;
        targetGravityStrength = 1.0;
        currGravityStrength = 1.0;
        currGravityDirection = Direction.DOWN;
        applyGravityChange();
        applyGravityStrengthAttribute();
        advanceVisualRotation();
    }

    /**
     * FIELD PULL IS AUTHORITATIVE. The field's strength reaches vanilla
     * travel through the ENTITY_GRAVITY attribute as a MULTIPLIER — which
     * works in normal dimensions but collapses in zero-gravity ones: VS
     * Genesis's Great Unknown suppresses the attribute, and any multiple
     * of (near-)zero stays (near-)zero, so a core/plating field barely
     * pulled at all and jumps sailed clean out of the field. Each tick a
     * field is active, this applies the DEFICIT between the field's
     * intended acceleration ({@code BASE_GRAVITY_ACCEL x strength}) and
     * what the attribute will actually deliver, straight to the local
     * deltaMovement along the frame's down — the exact axis vanilla
     * travel applies the attribute along, so the two compose to precisely
     * the intended pull. In normal dimensions the attribute already
     * delivers everything and the deficit is ~0: this is inert there.
     *
     * Deliberately NOT applied to: flying/fall-flying players (weightless
     * by vanilla semantics), swimmers (water gravity runs through
     * different vanilla math), slow-falling entities (their reduced
     * attribute is intentional, not suppression). NO-GRAVITY entities ARE
     * included — with actual = 0, the deficit supplies the field's whole
     * pull (see the note at the computation).
     */
    private void applyFieldPullDeficit() {
        dbgDeficitApplied = 0;
        if (!(entity instanceof LivingEntity living)) {
            dbgDeficitState = "nonliving";
            return;
        }
        boolean controlled = entity.level().isClientSide()
            ? entity.isControlledByLocalInstance()
            : !(entity instanceof Player);
        if (!controlled) {
            dbgDeficitState = "uncontrolled";
            return;
        }
        if (fieldGraceTicks <= 0) {
            dbgDeficitState = "nofield";
            return;
        }
        // passengers are placed by their vehicle every tick (rideTick zeroes
        // their velocity); nothing to pull
        if (entity.isPassenger()) {
            dbgDeficitState = "passenger";
            return;
        }
        // Climbables are deliberately NOT excluded: vanilla travel applies
        // gravity on a ladder too (the climbable clamp merely caps the
        // descent at 0.15/tick), so in a zero-g dimension a field must keep
        // pulling — without it a player on a ladder in the Great Unknown
        // hung weightless and could never climb DOWN.
        if (living.isFallFlying()
            || entity.isInWater() || entity.isInLava()
            || living.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) {
            dbgDeficitState = "excluded";
            return;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            dbgDeficitState = "flying";
            return;
        }

        // NO-GRAVITY ENTITIES ARE NOT EXCLUDED — they are the whole point.
        // VS Genesis's Great Unknown implements zero-g by setting noGravity
        // on entities: vanilla travel then skips gravity ENTIRELY (the
        // attribute value never matters), so the deficit must supply the
        // field's full pull. Measured in the field test: the core's effect
        // arrived (fx/t=1), the frame aligned perfectly (gapDeg=0.0), and
        // the old isNoGravity exclusion was the only thing between the
        // player and the pull. An active field OWNS gravity.
        net.minecraft.world.entity.ai.attributes.AttributeInstance attr =
            living.getAttribute(net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get());
        double actual = living.isNoGravity() ? 0.0
            : attr != null ? attr.getValue() : BASE_GRAVITY_ACCEL;
        double desired = BASE_GRAVITY_ACCEL * currGravityStrength;
        double deficit = desired - actual;
        if (deficit <= 1.0E-4) {
            dbgDeficitState = String.format(java.util.Locale.ROOT,
                "covered(attr=%.4f)", actual);
            return; // the attribute path already delivers (or exceeds) the pull
        }
        entity.setDeltaMovement(entity.getDeltaMovement().add(0.0, -deficit, 0.0));
        dbgDeficitApplied = deficit;
        dbgDeficitState = String.format(java.util.Locale.ROOT,
            "applied(attr=%.4f)", actual);
    }

    /**
     * Applies {@link #currGravityStrength} to the entity through Forge's
     * {@code forge:entity_gravity} ATTRIBUTE. Forge's patched
     * {@code LivingEntity.travel} takes its gravity acceleration from that
     * attribute — the 0.08 constant it loads is immediately overwritten by
     * the attribute read — so the old ModifyConstant-based scaling silently
     * did nothing for living entities: gravity strength (and with it the
     * gradual-falloff feel) never actually changed the pull. Run on the side
     * that computes this entity's gravity; travel() only applies gravity on
     * the controlled side, which is the same side.
     */
    private void applyGravityStrengthAttribute() {
        if (!(entity instanceof LivingEntity living) || shouldAcceptServerSync()) {
            return;
        }
        net.minecraft.world.entity.ai.attributes.AttributeInstance attr =
            living.getAttribute(net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get());
        if (attr == null) {
            return;
        }

        double amount = currGravityStrength - 1.0;
        net.minecraft.world.entity.ai.attributes.AttributeModifier existing =
            attr.getModifier(GRAVITY_STRENGTH_MODIFIER_ID);

        if (Math.abs(amount) < 1.0E-4) {
            if (existing != null) {
                attr.removeModifier(GRAVITY_STRENGTH_MODIFIER_ID);
            }
            return;
        }
        if (existing != null) {
            if (Math.abs(existing.getAmount() - amount) < 1.0E-4) {
                return;
            }
            attr.removeModifier(GRAVITY_STRENGTH_MODIFIER_ID);
        }
        attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            GRAVITY_STRENGTH_MODIFIER_ID, "GravityUnbound field strength", amount,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_TOTAL
        ));
    }

    /**
     * Freshly spawned non-player entities (spawn eggs, fireworks, thrown
     * items) inside a gravity field snap to the field INSTANTLY instead of
     * spawning upright and rotating a few ticks later: the first tick where
     * field effects reach a just-created entity skips the smooth chase (and
     * {@link #snapPhysicsDirection} skips its hysteresis for them, see the
     * freshSpawn bypass there).
     */
    private void maybeSnapFreshSpawn() {
        if (entity instanceof Player || entity.tickCount > 3 || shouldAcceptServerSync()) {
            return;
        }
        if (fieldGraceTicks <= 0) {
            return;
        }
        Quaternionf target = new Quaternionf();
        computeVisualTarget(target);
        if (angleBetween(visualRotation, target) < (float) Math.toRadians(1)) {
            return;
        }
        Quaternionf oldFrame = new Quaternionf(visualRotation);
        visualRotation.set(target);
        prevVisualRotation.set(target);
        visualTarget.set(target);
        // WORLD velocity survives the snap. The spawn velocity was written
        // through the identity frame (a fresh capability), so reading the
        // same local vector through the snapped frame bent a dropped item's
        // throw by the whole frame rotation — on a ship's wall face a drop
        // flew sideways or behind the thrower. Projectiles are world-frame
        // and keep their vector untouched.
        if (!(entity instanceof net.minecraft.world.entity.projectile.Projectile)) {
            Vec3 world = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), oldFrame);
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(world, visualRotation));
        }
        noAnimation = true;
        needsSync = true;
    }

    // full world-space rotation the attached ship performed since last tick,
    // composed onto the visual frame inside advanceVisualRotation (after the
    // prev-frame capture, so render interpolation sweeps with the ship)
    @Nullable
    private Quaternionf pendingShipDelta = null;

    /**
     * SHIP ATTACHMENT, tick half: THE CARRY.
     *
     * Verified against the Valkyrien Skies 2.4.11 bytecode: on the client,
     * ship tick transforms advance and the EntityDragger carries riders at
     * the TAIL of Minecraft.tick, after every entity tick. So the pose read
     * during this tick is the pose the rider's body was placed on at the end
     * of last tick, and the delta from the previous tick's pose is exactly
     * the rotation the body was carried through. That delta is composed onto
     * the frame in full — swing AND twist — and onto the held surface
     * normals, so a rider's ship-relative orientation (frame in ship
     * coordinates) stays constant by construction, at any rotation rate.
     *
     * Why the twist too: the previous design carried only the swing here and
     * delivered the twist through tick-rate yaw writes plus a per-frame
     * camera correction. Faces whose normal is parallel to the spin axis
     * (the deck under a yaw spin, the north/south walls under roll, the
     * east/west walls under pitch) are PURE twist for the rider, so they got
     * only the staircase path — the axis-dependent stutter. With the twist
     * in the frame, {@link #normalizeTwist()} moves it into yaw whenever the
     * frame is settled (invisibly: exact yaw/velocity compensation, both
     * interpolation endpoints shifted), and {@link #getRenderRotation}
     * rebuilds the render frame against the ship's DRAWN pose for swing and
     * twist alike. VS's own dragger yaw is suppressed while attached (see
     * compat.VSEntityDraggerMixin) — nothing double-turns.
     *
     * Runs first in the tick so the target/probe logic below sees surface
     * normals consistent with the pose the physics is synced to; the frame
     * itself is composed inside advanceVisualRotation, after the render
     * interpolation's prev capture.
     */
    private void applyShipAttachCarry() {
        pendingShipDelta = null;
        org.valkyrienskies.core.api.ships.Ship ship = attachShip;
        if (ship == null) {
            return;
        }

        org.joml.Quaterniond now =
            new org.joml.Quaterniond(ship.getTransform().getShipToWorldRotation());
        org.joml.Quaterniond prev =
            new org.joml.Quaterniond(ship.getPrevTickTransform().getShipToWorldRotation());
        org.joml.Quaterniond delta = now.mul(prev.conjugate(), new org.joml.Quaterniond()).normalize();
        if (delta.w < 0) {
            delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
        }
        if (delta.w > 0.99999999) {
            // < ~0.016 deg/tick: stationary ship, nothing to carry
            return;
        }

        // physical surfaces rotate by the full delta
        if (lastGroundNormal != null) {
            lastGroundNormal = gravityunbound$rotate(delta, lastGroundNormal);
        }
        if (recentReleasedNormal != null) {
            recentReleasedNormal = gravityunbound$rotate(delta, recentReleasedNormal);
        }
        if (capsuleGroundNormal != null) {
            capsuleGroundNormal = gravityunbound$rotate(delta, capsuleGroundNormal);
        }

        pendingShipDelta = new Quaternionf(
            (float) delta.x, (float) delta.y, (float) delta.z, (float) delta.w);
    }

    private static Vec3 gravityunbound$rotate(org.joml.Quaterniond rotation, Vec3 v) {
        org.joml.Vector3d out = rotation.transform(new org.joml.Vector3d(v.x, v.y, v.z));
        return new Vec3(out.x, out.y, out.z);
    }

    /**
     * SHIP ATTACHMENT, tick half: WHO WE RIDE. Decided after the field and
     * the surface probe resolved this tick: the ship the dominant field is
     * mounted on (standing, jumping or flying inside its field — kept
     * through the field grace), else the ship whose face is the held
     * surface. Seat passengers are VS's own (mounted-entity) business.
     *
     * While attached and AIRBORNE the entity is re-registered with VS's
     * dragging every tick: VS stops carrying an entity 25 ticks after it
     * last stood on the ship, which is why flying up inside a spinning
     * ship's plating field used to leave the player behind mid-field.
     * Grounded riders are registered by the collision paths (capsule:
     * EntityMixin; vanilla box: VS itself), which also handle the hand-off
     * back to world ground — this must not interfere with those.
     */
    private void updateShipAttachment() {
        org.valkyrienskies.core.api.ships.Ship ship = fieldAnchorShip;
        if (ship == null && lastGroundNormal != null) {
            ship = capsuleGrounded && capsuleGroundShip != null ? capsuleGroundShip : lastGroundShip;
        }
        // Passengers attach only through their mount: a seat under a ship
        // field anchors the rider's frame to the ship (see the vehicle
        // branch of updateGravityStatus), so the view turns with the ship
        // exactly like a standing rider's; VS positions the body itself.
        boolean passenger = entity.isPassenger();
        if ((passenger && fieldAnchorShip == null)
            || entity instanceof net.minecraft.world.entity.projectile.Projectile
            || shouldAcceptServerSync()) {
            ship = null;
        }

        if (ship != attachShip
            && (ship == null || attachShip == null || ship.getId() != attachShip.getId())) {
            // a different ship (or none): the stored poses belong to the old
            // one — re-seed the render bookkeeping
            attachRotValid = false;
        }
        attachShip = ship;

        if (ship != null) {
            renderAttachShip = ship;

            // (mounted riders are carried by VS's own mount positioning —
            // never re-register them with the standing-entity dragger)
            boolean airborne = !passenger
                && (useCapsuleCollision() ? !capsuleGrounded : !entity.onGround());
            if (airborne) {
                org.valkyrienskies.mod.common.util.EntityDraggingInformation dragInfo =
                    ((org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider) entity)
                        .getDraggingInformation();
                Long current = dragInfo.getLastShipStoodOn();
                if (current == null || current != ship.getId()) {
                    dragInfo.setLastShipStoodOn(ship.getId());
                }
                // (also clears VS's ship-change impulse flag)
                dragInfo.setTicksSinceStoodOnShip(0);
                // VS's collide wrapper wipes the standing state whenever the
                // collide result differs from its own ship-adjusted movement
                // (an airborne capsule brushing geometry does that); this is
                // VS's own escape hatch for exactly that wipe
                dragInfo.setIgnoreNextGroundStand(true);
            }
        }

        // eased engagement of the render reconstruction: ~7 ticks in, ~7 out
        // (interpolated per frame by the render pass, see attachWeightPrev)
        float targetWeight = ship != null ? 1.0f : 0.0f;
        attachWeightPrev = attachWeight;
        attachWeight = Mth.clamp(
            attachWeight + Mth.clamp(targetWeight - attachWeight, -0.15f, 0.15f), 0.0f, 1.0f);
        if (ship == null && attachWeight <= 0.001f && attachWeightPrev <= 0.001f) {
            renderAttachShip = null;
            attachRotValid = false;
        }
    }

    /**
     * Standing IDLE on a ship in capsule mode: pin the feet to a fixed
     * shipyard-space point. Valkyrien Skies' drag carries standing entities
     * with the ship, but its per-tick reposition accumulates a slow tangential
     * error on rotating contraptions — "standing still on the spinning wall
     * slowly slides me off, and sliding off carries launch velocity".
     * Anchoring in the SHIP's own coordinate space cannot drift, and stripping
     * the tangential velocity while pinned empties the reservoir that used to
     * discharge as a launch. Any input, jump, real push (knockback, piston) or
     * ground change releases the anchor immediately.
     */
    private void applyShipIdleAnchor() {
        if (!entity.level().isClientSide() || !entity.isControlledByLocalInstance()) {
            return;
        }
        org.valkyrienskies.core.api.ships.Ship ship = capsuleGroundShip;
        if (!useCapsuleCollision() || !capsuleGrounded || ship == null
            || !(entity instanceof LivingEntity living)
            || living.isFallFlying()
            || (entity instanceof Player player && player.getAbilities().flying)
            || Math.abs(living.xxa) > 0.01 || Math.abs(living.zza) > 0.01
            || entity.isInWater() || entity.isInLava()  // fluid currents must push
        ) {
            shipAnchorPos = null;
            return;
        }

        Vec3 normal = capsuleGroundNormal != null ? capsuleGroundNormal : getUpVector();
        Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), visualRotation);
        double normalVel = worldVelocity.dot(normal);
        // velocity thresholds scale with the body: a Pehkui-scaled player's
        // jump velocity is far below the full-size 0.05 release gate, which
        // would have pinned tiny players to the deck mid-jump
        double velScale = Mth.clamp(entity.getBbHeight() / 1.8, 0.05, 1.0);
        if (normalVel > 0.05 * velScale) {
            // jumping / being launched away from the surface
            shipAnchorPos = null;
            return;
        }
        Vec3 tangentialVel = worldVelocity.subtract(normal.scale(normalVel));
        if (tangentialVel.lengthSqr() > 0.2 * 0.2 * velScale * velScale) {
            // a real push is in flight: let it play out
            shipAnchorPos = null;
            return;
        }

        // DRAGGER-AWARE TRANSFORM CHOICE. Valkyrien Skies' client
        // EntityDragger runs AFTER the entity tick and carries dragged
        // entities by the ship's full tick delta (computed from their
        // tick-START position). Pinning to the CURRENT tick transform here
        // made that carry apply TWICE: the tick-end position permanently led
        // the anchored spot by one tick of ship motion, addedMovementLastTick
        // no longer matched the player's real displacement, and VS's
        // per-frame render-ride (which rewrites xo/yo/zo so the camera lands
        // on the DRAWN ship pose) was fed inconsistent inputs — a tick-rate
        // positional sawtooth. Invisible on the deck (identity frame skips
        // the anchor entirely; on the down face the residual is a twist about
        // the vertical eye arm), but on N/E/S/W wall faces the horizontal
        // 1.62-block eye arm and the hull radius amplified it into the
        // rotating-ship wall stutter. While VS is actively dragging, pin to
        // the PREV-tick pose so the dragger's own carry lands the player
        // exactly on the anchor (steady state: this setPos is a no-op and
        // VS owns the whole carry, so its render-ride math is exact).
        // CURRENT tick transform, deliberately. Verified against the VS
        // 2.4.11 bytecode: on the client, ship tick transforms update and the
        // EntityDragger runs at the TAIL of Minecraft.tick — AFTER every
        // entity tick. So during this tick getTransform() is the pose the
        // dragger already placed the player on at the end of last tick;
        // pinning to it is a no-op in steady state, and the dragger's own
        // carry (computed from the tick-START position) then lands the
        // player exactly on the anchor at the NEW pose. Pinning to the
        // prev-tick transform instead (round 75) moved the player one tick
        // BACK every tick and left them permanently one tick of ship motion
        // behind the deck.
        org.valkyrienskies.core.api.ships.properties.ShipTransform anchorTransform = ship.getTransform();

        if (shipAnchorPos == null || shipAnchorShipId != ship.getId()) {
            org.joml.Vector3d p = new org.joml.Vector3d(entity.getX(), entity.getY(), entity.getZ());
            anchorTransform.getWorldToShipMatrix().transformPosition(p);
            shipAnchorPos = p;
            shipAnchorShipId = ship.getId();
            return;
        }

        org.joml.Vector3d w = new org.joml.Vector3d(shipAnchorPos);
        anchorTransform.getShipToWorldMatrix().transformPosition(w);
        if (entity.position().distanceToSqr(w.x, w.y, w.z) > 1.0) {
            // ship teleported or badly desynced: don't yank the player around
            shipAnchorPos = null;
            return;
        }
        entity.setPos(w.x, w.y, w.z);
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(normal.scale(normalVel), visualRotation));
    }

    /**
     * Last line of defense for "the spheres never went away": capsule mode
     * active for two full seconds with NO gravity influence at all (no field,
     * no grace, no held surface, default base gravity, not riding anything)
     * cannot be a legitimate state — the frame should long have converged and
     * exited. Whatever kept it out (a server direction flip war, a
     * hemisphere-flipped quaternion, state carried through a respawn), snap to
     * exact vanilla with full look/velocity compensation and log it.
     */
    private void capsuleExitWatchdog() {
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile
            || shouldAcceptServerSync()) {
            capsuleNoInfluenceTicks = 0;
            return;
        }
        boolean influenced = fieldGraceTicks > 0
            || lastGroundNormal != null
            || !baseGravityDirection.equals(DOWN)
            || entity.getVehicle() != null;
        if (influenced || !useCapsuleCollision()) {
            capsuleNoInfluenceTicks = 0;
            return;
        }
        if (++capsuleNoInfluenceTicks < 40) {
            return;
        }

        LOGGER.warn(
            "[GravityUnbound] capsule mode stuck with no gravity influence (dir={}, w={}); forcing exit",
            currGravityDirection, visualRotation.w()
        );
        Quaternionf old = new Quaternionf(visualRotation);
        currGravityDirection = Direction.DOWN;
        prevGravityDirection = Direction.DOWN;
        serverDirectionDisagreeStreak = 0;
        Quaternionf canonical = RotationUtil.getWorldRotationQuaternion(Direction.DOWN);
        visualRotation.set(canonical);
        visualTarget.set(canonical);
        prevVisualRotation.set(canonical);
        compensateFrameChange(old, visualRotation);
        updateBoundingBox();
        needsSync = true;
        capsuleNoInfluenceTicks = 0;
    }

    /**
     * Probes the terrain under the feet along the FIELD's down direction and
     * remembers the supporting block FACE.
     *
     * This raycast — never a contact normal — is what drives surface
     * alignment. Contact normals are per-tick collision noise: at an edge the
     * deepest contact alternates between the two faces every tick, jumps drop
     * them entirely, and every hysteresis machine layered on top oscillated
     * against another one. The probe is a pure function of position and field:
     * standing still yields the identical answer every tick (nothing CAN
     * oscillate), corners resolve deterministically to whichever face is under
     * the feet, the result is always axis-pure in its grid (Valkyrien Skies
     * transforms raycasts through ships natively), and client and server
     * agree by construction. No probe hit — airborne, or gravity pointing away
     * from every surface — unlocks the frame back to the raw field.
     */
    private void updateSurfaceProbe() {
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile
            || shouldAcceptServerSync()) {
            return;
        }

        // a passenger does not stand on anything: its frame follows the
        // vehicle (or the ship it is mounted to) directly
        if (entity.isPassenger()) {
            capsuleGrounded = false;
            capsuleGroundNormal = null;
            capsuleGroundShip = null;
            lastGroundNormal = null;
            groundNormalGraceTicks = 0;
            surfaceChangeCooldown = 0;
            recentReleasedNormal = null;
            recentReleasedTicks = 0;
            contactDisagreeTicks = 0;
            return;
        }

        if (!useCapsuleCollision()) {
            // vanilla-mode bookkeeping so the capsule state cannot go stale
            capsuleGrounded = entity.onGround();
            capsuleGroundNormal = null;
            capsuleGroundShip = null;
        }

        // Surface alignment exists to serve gravity FIELDS (plating, cores) and
        // non-default BASE gravity. Under plain default gravity it must stay
        // off: otherwise standing on any tilted Valkyrien Skies ship captured
        // the deck's face normal, tilted the frame, and gravity pinned the
        // player to the ship face — with no plating or core anywhere near.
        //
        // Deliberately NOT part of this gate: currGravityDirection. The held
        // surface normal keeps the cardinal non-default, which would keep the
        // gate open, which keeps the normal held... a self-sustaining lock that
        // made gravity stay glued to a plate face after walking OFF the plating
        // (until a jump broke the probe). Field influence has a grace window of
        // its own, so releasing here the moment fields are gone is correct.
        boolean gravityActive = fieldGraceTicks > 0 || !baseGravityDirection.equals(DOWN);
        if (!gravityActive) {
            lastGroundNormal = null;
            groundNormalGraceTicks = 0;
            surfaceChangeCooldown = 0;
            recentReleasedNormal = null;
            recentReleasedTicks = 0;
            lastGroundShip = null;
            return;
        }

        // creative flight never surface-adopts: pilots follow the raw field
        // (flying past faces kept snapping the frame onto them, including a
        // hard 45-degree lock between two plate groups)
        if (entity instanceof Player flyingPlayer && flyingPlayer.getAbilities().flying) {
            lastGroundNormal = null;
            groundNormalGraceTicks = 0;
            recentReleasedNormal = null;
            recentReleasedTicks = 0;
            return;
        }

        // per-block snap toggle: the dominant source forbids planet-walk
        // surface alignment — drop any held surface and let the frame follow
        // the raw field vector only
        if (!surfaceAlignAllowed) {
            lastGroundNormal = null;
            groundNormalGraceTicks = 0;
            surfaceChangeCooldown = 0;
            recentReleasedNormal = null;
            recentReleasedTicks = 0;
            return;
        }

        if (surfaceChangeCooldown > 0) {
            surfaceChangeCooldown--;
        }
        if (recentReleasedTicks > 0 && --recentReleasedTicks == 0) {
            recentReleasedNormal = null;
        }

        Vec3 fieldDown = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6
            ? targetGravityVector.normalize()
            : getCurrGravityDirectionVec();
        Vec3 fieldUp = fieldDown.scale(-1);

        // support-first: a held surface is only released when the field
        // actively opposes it (repulsion), not when merely perpendicular
        boolean held = lastGroundNormal != null && lastGroundNormal.dot(fieldUp) > SUPPORT_KEEP_DOT;
        Vec3 heldNormal = held ? lastGroundNormal : null;

        // Probe along the HELD surface alignment while one exists, not the raw
        // field: near the edge between two plate groups the blended field is
        // diagonal, and a diagonal ray hits the NEXT face while the feet still
        // stand on the current one — the frame flipped early, wedging the
        // player into the corner (locked in place, camera ping-ponging).
        Vec3 probeDown = held ? heldNormal.scale(-1) : fieldDown;

        Vec3 feet = entity.position();
        // PROBE SCALE: every probe offset below was authored for a
        // 1.8-block player in world units — for a Pehkui-scaled player the
        // 0.2-block back-off is body-heights long and starts the ray inside
        // (or beyond) a matching-scale ship's hull, and the wall probe
        // reached many ship-blocks ahead: surface snapping simply never
        // engaged on scaled ships. Scale the probe geometry with the body.
        double probeScale = Mth.clamp(entity.getBbHeight() / 1.8, 0.05, 1.0);

        // tangential (along-surface) component of last tick's actual movement;
        // drives the edge-transition probes below
        Vec3 moved = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);

        // Standing on (or recently jumped from) a Valkyrien Skies ship: the
        // position delta includes the ship CARRYING the player (VS's drag
        // repositions dragged entities every tick). That part is not player
        // movement — subtract where the ship itself moved this point since
        // last tick, so the probes see only ship-relative motion. Without
        // this, standing idle on a moving deck read as fast tangential
        // movement (spurious convex-wrap probes, random face adoptions from
        // deck clutter) and a descending ship read as "falling away from the
        // held face", releasing the surface hold mid-ride.
        if (capsuleGrounded) {
            // null when standing on WORLD ground — landing off-ship must drop
            // the stale ship or its motion would keep being subtracted while
            // walking plain plated structures; the reference only needs to
            // survive the AIRBORNE gap of a jump on a moving ship
            lastGroundShip = capsuleGroundShip;
        }
        org.valkyrienskies.core.api.ships.Ship carryShip =
            capsuleGroundShip != null ? capsuleGroundShip : lastGroundShip;
        if (carryShip != null) {
            org.joml.Vector3d prevPoint = new org.joml.Vector3d(entity.getX(), entity.getY(), entity.getZ());
            carryShip.getTransform().getWorldToShipMatrix().transformPosition(prevPoint);
            carryShip.getPrevTickTransform().getShipToWorldMatrix().transformPosition(prevPoint);
            // the ship moved this world point by (pos - prevPoint) over the tick
            moved = moved.subtract(
                entity.getX() - prevPoint.x,
                entity.getY() - prevPoint.y,
                entity.getZ() - prevPoint.z
            );
        }
        Vec3 tangent = held ? moved.subtract(heldNormal.scale(moved.dot(heldNormal))) : Vec3.ZERO;
        double tangentSpeed = tangent.length();
        Vec3 tangentDir = tangentSpeed > 1.0E-6 ? tangent.scale(1.0 / tangentSpeed) : Vec3.ZERO;

        // The tangential direction of the player's INPUT (vanilla
        // getInputVector formula) — where they are TRYING to go. The actual
        // movement is useless exactly where the edge probes matter most:
        // pressed against a wall it is blocked to ~zero, and approaching a
        // wall at a slight angle it SLIDES along the face, aiming the tangent
        // parallel to the wall so the concave probe could never hit it.
        Vec3 inputTangentDir = held ? getInputTangentDirection(heldNormal) : null;

        // standing still with input held (e.g. walking in place against a
        // convex edge): let the input drive the wrap probe too
        if (inputTangentDir != null && tangentSpeed <= 0.02) {
            tangentDir = inputTangentDir;
            tangentSpeed = 0.1;
        }

        // RAMP CONSENSUS: standing (collision-grounded) on a plane that
        // disagrees with the held face for several consecutive ticks — a
        // stair ramp bending from one cube face toward the next produces
        // exactly this — re-adopt the plane actually being stood on, under
        // the same relative endorsement gate as the other adoptions. The
        // held-face probe below would otherwise keep confirming the old
        // face forever ("walking the stair ramp keeps snapping wrong").
        if (held && capsuleGrounded && capsuleGroundNormal != null) {
            if (capsuleGroundNormal.dot(heldNormal) < 0.8
                && capsuleGroundNormal.dot(fieldUp) > 0.2
                && capsuleGroundNormal.dot(fieldUp) > 0.5 * heldNormal.dot(fieldUp)
            ) {
                if (++contactDisagreeTicks >= 4) {
                    contactDisagreeTicks = 0;
                    adoptGroundNormal(capsuleGroundNormal);
                    return;
                }
            }
            else {
                contactDisagreeTicks = 0;
            }
        }

        Vec3 normal = probeSurfaceNormal(feet.subtract(probeDown.scale(0.2 * probeScale)), probeDown, (0.2 + GROUND_PROBE_DEPTH) * probeScale);
        if (normal != null && normal.dot(fieldUp) > SUPPORT_KEEP_DOT) {
            adoptGroundNormal(normal);

            // CONCAVE corner (walking on a plated floor into a plated wall, or
            // up a plated wall into a plated ceiling): the held normal keeps
            // the frame on the current face forever, so a wall whose field
            // wants to be our new floor must be adopted explicitly — cast a
            // short ray along where the player is going; a blocking face that
            // the FIELD accepts as up (an unplated wall never passes this
            // gate, because the blend has no component toward it at all)
            // becomes the new surface.
            //
            // INPUT direction is preferred over actual movement whenever the
            // player is steering: blocked or wall-sliding movement tangents
            // aim parallel to the wall and never hit it — which left the
            // player pressed into the corner under old-face gravity, creeping
            // ("walking through honey") until movement happened to die down
            // enough for a fallback to fire.
            if (held) {
                Vec3 probeDir = inputTangentDir != null ? inputTangentDir
                    : (tangentSpeed > 0.02 ? tangentDir : null);
                Vec3 wall = probeDir == null ? null
                    : probeSurfaceNormal(feet.add(heldNormal.scale(0.2 * probeScale)), probeDir, 0.5 * probeScale);
                // field gate 0.2, not 0.35: near the BOTTOM of a plated wall a
                // large plated floor dominates the blend (many more floor
                // plates inside the blend window), tilting fieldUp mostly
                // floor-ward — 0.35 rejected legitimate wall adoptions there.
                // Unplated walls still contribute nothing and never pass.
                //
                // RADIAL fields (gravity cores) are different: the pull has
                // a tangential component EVERYWHERE on a planet face (up to
                // 45 degrees at the face's rim), so the relative half-gate
                // still adopted stair risers, and even a wall built on the
                // face, whenever the player stood far enough from the face
                // center. Under a radial field a face is a floor only when
                // the field prefers it OVER the face being stood on — which
                // on a convex planet never happens on the face itself, only
                // past its edge (the convex wrap's territory): risers and
                // walls stay walls, as on any planet.
                double wallGate = fieldRadial
                    ? heldNormal.dot(fieldUp)
                    : 0.5 * heldNormal.dot(fieldUp);
                if (wall != null
                    && wall.dot(fieldUp) > 0.2
                    && wall.dot(fieldUp) > wallGate
                    && wall.dot(heldNormal) < 0.7
                    && wall.dot(probeDir) < -0.5
                ) {
                    // relative endorsement: under a core's radial field EVERY
                    // surface has some field component, so an absolute gate
                    // adopted stair risers and door faces; a genuine concave
                    // planet face is endorsed comparably to the held one
                    adoptGroundNormal(wall);
                }
            }
            return;
        }

        // Support acquisition relative to the CURRENT frame: when nothing is
        // held and the field probe found nothing (a player standing upright on
        // plain ground inside a plate field that points SIDEWAYS — the field
        // volume extends its whole range outward from the plate), the surface
        // actually under the feet is the support. Without this the frame had
        // nothing to hold and rotated toward the field while the player stood
        // on flat ground. Rejected only when the field opposes the surface
        // (repulsion must still launch).
        if (!held) {
            Vec3 frameUp = getUpVector();
            Vec3 frameDown = frameUp.scale(-1);
            Vec3 support = probeSurfaceNormal(feet.subtract(frameDown.scale(0.2 * probeScale)), frameDown, (0.2 + GROUND_PROBE_DEPTH) * probeScale);
            if (support != null && support.dot(frameUp) > 0.35 && support.dot(fieldUp) > SUPPORT_KEEP_DOT) {
                adoptGroundNormal(support);
                return;
            }
        }

        // CONVEX edge wrap (walking off the top face of a plated cube onto a
        // side face): the feet just left the face — before giving up, look
        // BACK toward the face we walked off, slightly below its plane. The
        // adjacent face found there is adopted directly: one clean flip,
        // instead of falling off and letting the diagonal blended field
        // ping-pong between the two faces. A running jump never triggers this
        // (the feet are above the old plane, so the ray misses), and a genuine
        // cliff edge never passes the field gate (the field there still points
        // along the old face, not around the edge).
        // gate 0.01, not 0.02: crawling (~0.015/tick) must still wrap around
        // convex edges — safe now that `moved` has the ship carry subtracted
        // (the old higher gate was partly guarding against that contamination)
        if (held && tangentSpeed > 0.01) {
            // (body-scaled like the other probes: unscaled, a 1/16 player's
            // wrap ray started two ship-blocks below its feet and looked
            // twelve blocks back — every ledge and hole rim fired it)
            Vec3 wrap = probeSurfaceNormal(
                feet.subtract(heldNormal.scale(0.15 * probeScale)), tangentDir.scale(-1), 0.75 * probeScale);
            // radial fields: the next face must be endorsed about as well as
            // the current one — true just past a convex edge (where the wrap
            // belongs), never for a stair riser walked off mid-face
            double wrapGate = fieldRadial
                ? 0.9 * heldNormal.dot(fieldUp)
                : 0.5 * heldNormal.dot(fieldUp);
            if (wrap != null
                && wrap.dot(fieldUp) > 0.35
                && wrap.dot(fieldUp) > wrapGate
                && wrap.dot(heldNormal) < 0.7
                && wrap.dot(tangentDir) > 0.1
            ) {
                adoptGroundNormal(wrap);
                return;
            }
        }

        // CONTACT CONFIRMS THE HELD FACE. The ground probe is a single ray
        // at the feet: standing at the very edge of a face with the feet a
        // hair past it, the ray misses while the collider still rests the
        // capsule on that face. Letting the hold lapse there handed the
        // frame to the raw field, which (diagonal at an edge) re-acquired
        // the NEXT face, whose contact then disagreed, which re-adopted
        // the first face — a standing-still oscillation between the two
        // faces of a cube edge until the player walked away. Physics
        // saying "you stand on it" is the definitive answer: keep it.
        // (After the wrap probe, so walking off the edge still wraps.)
        boolean contactConfirms = held && (useCapsuleCollision()
            ? capsuleGrounded && capsuleGroundNormal != null
                && capsuleGroundNormal.dot(heldNormal) > 0.8
            : entity.onGround() && heldNormal.y > 0.8);
        if (contactConfirms) {
            groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;
            contactDisagreeTicks = 0;
            return;
        }

        // during a transition the held face is kept alive even when the probe
        // misses — the feet often hover past the old face's plane while the
        // frame is still rotating onto the new one
        if (surfaceChangeCooldown > 0 && lastGroundNormal != null) {
            groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;
            return;
        }

        // FALLING away from the held face with nothing under it (walked off a
        // convex edge and the wrap probe found no adjacent face): release the
        // hold almost immediately. The full grace is sized for JUMP ascent —
        // holding a stale "up" for 10 ticks while falling kept gravity pulling
        // along the OLD face's down the whole time, so a player stepping off
        // the top of a plated cube sailed straight past the side faces before
        // the field could catch them. Jump ascent moves ALONG the held normal
        // (dot > 0) and keeps the full grace.
        if (lastGroundNormal != null && !capsuleGrounded && moved.dot(lastGroundNormal) < -0.08) {
            // ...but only when NOTHING is under the feet: an ordinary jump's
            // DESCENT also moves against the held normal, and clamping the
            // grace there unsnapped every hop mid-air ("jumping immediately
            // unsnaps with no smoothing"). A longer look along the held
            // down separates the cliff (nothing under — release fast, the
            // field must catch the fall) from the jump (the plate is still
            // right there — keep the hold through the whole arc).
            Vec3 under = probeSurfaceNormal(feet, lastGroundNormal.scale(-1), 2.5 * probeScale);
            if (under == null || under.dot(lastGroundNormal) < 0.7) {
                groundNormalGraceTicks = Math.min(groundNormalGraceTicks, 1);
            }
        }

        if (groundNormalGraceTicks > 0) {
            groundNormalGraceTicks--;
        }
        else {
            if (lastGroundNormal != null) {
                // remember what we just let go of: catching the next face a
                // few ticks later must still count as a face change
                recentReleasedNormal = lastGroundNormal;
                recentReleasedTicks = RECENT_RELEASE_MEMORY_TICKS;
            }
            lastGroundNormal = null;
        }
    }

    /**
     * Adopt a probed surface normal, with COMMITMENT: after any change of face
     * the choice is locked for {@link #SURFACE_CHANGE_COOLDOWN_TICKS} while the
     * frame finishes rotating. Without this, transitions oscillated — at a
     * corner the two faces alternately win the probe while the player is
     * pushed around by the half-rotated frame's controls, which was the
     * "camera snaps back and forth between the faces and I cannot move
     * forward" deadlock at plate-field boundaries. A same-face refresh (the
     * overwhelmingly common case) never starts a commitment window.
     */
    private void adoptGroundNormal(Vec3 normal) {
        // anti-flap: hovering at a convex edge, the hold releases and the
        // probes immediately re-find the face just left — adopt/release
        // cycled every few ticks ("snaps back and forth rapidly when moving
        // slowly between two faces"). Returning to a recently-released face
        // requires actually STANDING on it again.
        if (recentReleasedTicks > 0 && recentReleasedNormal != null
            && recentReleasedNormal.dot(normal) > 0.95 && !capsuleGrounded) {
            return;
        }
        if (lastGroundNormal != null && lastGroundNormal.dot(normal) < 0.99) {
            if (surfaceChangeCooldown > 0) {
                // mid-transition: keep the committed face, just keep it alive
                groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;
                return;
            }
            surfaceChangeCooldown = SURFACE_CHANGE_COOLDOWN_TICKS;
            rotateVelocityAcrossSurfaceChange(lastGroundNormal, normal);
        }
        else if (lastGroundNormal == null && recentReleasedNormal != null
            && recentReleasedNormal.dot(normal) < 0.99
        ) {
            // Acquisition shortly after walking off a face (the hold lapsed
            // while airborne between the two): this IS a face change, so
            // commit and carry momentum around the edge — otherwise the fall
            // velocity built between release and re-acquisition stays
            // tangential to the new face and slides the player along/past it.
            // Only when the FIELD endorses the new face as up: landing back on
            // plain ground under a lingering field must not rotate velocity.
            Vec3 fieldUp = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6
                ? targetGravityVector.normalize().scale(-1)
                : getCurrGravityDirectionVec().scale(-1);
            if (normal.dot(fieldUp) > 0.35 && surfaceChangeCooldown == 0) {
                surfaceChangeCooldown = SURFACE_CHANGE_COOLDOWN_TICKS;
                rotateVelocityAcrossSurfaceChange(recentReleasedNormal, normal);
            }
        }
        recentReleasedNormal = null;
        recentReleasedTicks = 0;
        lastGroundNormal = normal;
        groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;

        // PHYSICS FOLLOWS THE FACE THIS TICK. The cardinal normally snaps
        // at the start of the NEXT tick (updateGravityStatus runs before
        // the probe), which left a player who had just walked off the top
        // face of a plated cube — identity frame, vanilla box — in BOX mode
        // for the tick of the face change: the 0.6-wide box still overlapped
        // the top face at the edge, blocked the velocity now pointing down
        // the side, and the pull toward the side face dragged the player
        // back over the top — where the capsule then engaged lying on its
        // side, pinned to the wrong face. Snapping the cardinal here puts
        // this tick's move through the capsule already.
        if (entity instanceof Player && !useCapsuleCollision()) {
            snapPhysicsDirection();
            applyGravityChange();
        }
    }

    /**
     * API: keep the currently held surface held for another grace window
     * (and its field alive), whatever the probes find. For mechanics that
     * hold the player against a WALL away from the face they stand on — a
     * wall cling that pins the player in the air — the ground probe finds
     * nothing under the feet and the hold would lapse mid-cling, dropping
     * the frame onto the raw field. Call once per tick while the hold
     * should persist; a no-op when no surface is held.
     */
    public void sustainHeldSurface() {
        if (lastGroundNormal == null) {
            return;
        }
        groundNormalGraceTicks = Math.max(groundNormalGraceTicks, GROUND_NORMAL_GRACE_TICKS);
        if (fieldGraceTicks > 0 && lastFieldVector != null) {
            fieldGraceTicks = Math.max(fieldGraceTicks, FIELD_GRACE_TICKS);
        }
    }

    /**
     * Carry momentum AROUND a surface change instead of straight through it.
     *
     * Walking off the top face of a plated cube: by the time the side face is
     * adopted, a tick or two of falling has built velocity along the OLD down.
     * That component is TANGENTIAL to the new face, so the player slid down
     * the side face at fall speed, straight past it — new-face gravity (only
     * 0.08/tick) never had a chance against it. Rotating the velocity by the
     * old-to-new-normal arc maps "falling onto the old face" into "pressing
     * onto the new face": walking around an edge keeps walking speed, and the
     * concave floor-to-wall case maps forward motion into up-the-wall motion.
     * Opposite faces (dot < -0.5) are skipped — the arc is ambiguous there and
     * such flips only happen through fields, not surface walking.
     */
    private void rotateVelocityAcrossSurfaceChange(Vec3 oldNormal, Vec3 newNormal) {
        // Deliberately NOT gated on capsule mode: the change that takes a
        // player OFF the top face of a plated cube starts in the identity
        // frame (vanilla box, local == world velocity), and skipping the
        // rotation there kept the walking momentum pointing straight out
        // over the edge — "up" in the new frame — so the player lifted off
        // the corner and fell back onto it instead of carrying on down the
        // side: the stutter and the dead stop that only the UP face had.
        // The frame transform below is exact for the identity frame too.
        boolean controlled = entity.level().isClientSide()
            ? entity.isControlledByLocalInstance()
            : !(entity instanceof Player);
        if (!controlled) {
            return;
        }
        double dot = oldNormal.dot(newNormal);
        if (dot > 0.99 || dot < -0.5) {
            return;
        }

        Quaternionf arc = QuaternionUtil.getRotationBetween(oldNormal, newNormal);
        Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), visualRotation);
        Vec3 rotated = QuaternionUtil.rotate(worldVelocity, arc);
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(rotated, visualRotation));
    }

    /**
     * The tangential (along-surface) direction of the entity's movement
     * INPUT relative to {@code surfaceNormal} — where the player is trying
     * to go — or null without input. Client-controlled entities only: the
     * server never sees input state.
     */
    public @Nullable Vec3 getInputTangentDirection(Vec3 surfaceNormal) {
        if (!(entity instanceof LivingEntity living)
            || (Math.abs(living.xxa) <= 0.01 && Math.abs(living.zza) <= 0.01)
            || !entity.level().isClientSide() || !entity.isControlledByLocalInstance()) {
            return null;
        }
        float yawRad = living.getYRot() * ((float) Math.PI / 180.0F);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        // vanilla getInputVector: input (strafe, forward) rotated by yaw
        Vec3 inputLocal = new Vec3(
            living.xxa * cos - living.zza * sin,
            0,
            living.zza * cos + living.xxa * sin
        );
        Vec3 inputWorld = RotationUtil.vecPlayerToWorld(inputLocal, visualRotation);
        Vec3 inputTangent = inputWorld.subtract(surfaceNormal.scale(inputWorld.dot(surfaceNormal)));
        return inputTangent.lengthSqr() > 1.0E-6 ? inputTangent.normalize() : null;
    }

    /**
     * The surface the entity is standing on (or held through the jump
     * grace), as a world-space face normal — the probe-derived surface the
     * frame aligns to. Null when nothing is held.
     */
    public @Nullable Vec3 getHeldSurfaceNormal() {
        return lastGroundNormal;
    }

    /** The Valkyrien Skies ship the held surface belongs to, or null. */
    public @Nullable org.valkyrienskies.core.api.ships.Ship getHeldSurfaceShip() {
        if (lastGroundNormal == null) {
            return null;
        }
        return capsuleGrounded && capsuleGroundShip != null ? capsuleGroundShip : lastGroundShip;
    }

    /**
     * Raycast against collision shapes and return the hit face's WORLD-space
     * normal (Valkyrien Skies raycasts hit ships natively in shipyard
     * coordinates; the face normal is transformed back), or null on a miss.
     */
    public @Nullable Vec3 probeSurfaceNormal(Vec3 from, Vec3 direction, double distance) {
        SurfaceHit hit = probeSurface(from, direction, distance);
        return hit != null ? hit.normal() : null;
    }

    /** {@link #probeSurfaceNormal} with the hit's ship and block position. */
    public @Nullable SurfaceHit probeSurface(Vec3 from, Vec3 direction, double distance) {
        net.minecraft.world.phys.BlockHitResult hit = entity.level().clip(new net.minecraft.world.level.ClipContext(
            from,
            from.add(direction.scale(distance)),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            entity
        ));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return null;
        }

        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        org.valkyrienskies.core.api.ships.Ship ship =
            org.valkyrienskies.mod.common.VSGameUtilsKt.getShipManagingPos(entity.level(), hit.getBlockPos());
        if (ship != null) {
            org.joml.Vector3d n = new org.joml.Vector3d(normal.x, normal.y, normal.z);
            ship.getTransform().getShipToWorldMatrix().transformDirection(n);
            n.normalize();
            normal = new Vec3(n.x, n.y, n.z);
        }
        return new SurfaceHit(normal, ship, hit.getBlockPos());
    }

    /**
     * The up direction the player's frame (and physics cardinal) should chase:
     * SUPPORT-FIRST — the surface being stood on wins over the field, unless
     * the field actively OPPOSES that surface (repulsion pushing away from the
     * floor, dot < -0.1, must still launch).
     *
     * The old gate (dot > 0.35) also discarded the support whenever the field
     * was merely PERPENDICULAR to it — but plate fields extend sideways
     * (invisible trigger bleed) and their whole range outward, so a player
     * standing upright on plain ground inside a sideways field had their frame
     * yanked toward the plate: "spheres stay after leaving the field", getting
     * stuck on nothing while walking, clinging to walls. Standing on a surface
     * now always means standing on it; fields orient the player when airborne,
     * and walls are adopted through the explicit corner transitions.
     */
    private Vec3 effectiveTargetUp(Vec3 fieldVector) {
        Vec3 targetUp = fieldVector.scale(-1);
        if (lastGroundNormal != null && lastGroundNormal.dot(targetUp) > SUPPORT_KEEP_DOT) {
            return lastGroundNormal;
        }
        return targetUp;
    }

    /**
     * Gravity must pull toward the frame's TARGET, not along the frame itself.
     *
     * Vanilla gravity accelerates along local down, which the visual frame
     * maps onto the field vector — exact once the frame has converged, but the
     * frame takes up to a second to rotate. During that whole transition the
     * pull swept from the OLD direction to the new one, so stepping onto a
     * plated wall of a 90-degree-tilted ship kept pulling the player
     * world-down for a second: they slid down the wall and off the ship before
     * alignment finished, at a speed depending on the tilt. (Not a Valkyrien
     * Skies system — VS entity collision is fully bypassed in capsule mode;
     * the sliding force was our own lagging gravity.)
     *
     * This adds the difference so the NET pull each tick is already the target
     * direction: travel contributes frameDown·g, we add (targetDown−frameDown)·g.
     * At steady state the correction is exactly zero.
     */
    private void applyTransitionPull() {
        if (!useCapsuleCollision() || !(entity instanceof LivingEntity living)) {
            return;
        }
        boolean controlled = entity.level().isClientSide()
            ? entity.isControlledByLocalInstance()
            : !(entity instanceof Player);
        if (!controlled) {
            return;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return;
        }
        if (living.isFallFlying() || living.isNoGravity() || entity.isInWater() || entity.isInLava()
            || entity.isPassenger()) {
            return;
        }

        Vec3 field = getTargetGravityVector();
        if (field.lengthSqr() < 1.0E-6) {
            return;
        }
        // chase target down: the held surface's inward direction while locked
        // to a surface, the raw field otherwise — the same up the frame chases
        Vec3 targetDown = effectiveTargetUp(field.normalize()).scale(-1);
        Vec3 frameDown = RotationUtil.vecPlayerToWorld(new Vec3(0, -1, 0), visualRotation);
        if (targetDown.dot(frameDown) > 0.9998) {
            return; // aligned (within ~1 degree): correction is zero
        }

        // match the gravity travel() actually applies: Forge's travel reads
        // the forge:entity_gravity ATTRIBUTE (which already includes our
        // field-strength modifier and the slow-falling modifier), so read the
        // same value — the correction redirects the pull, so its magnitude
        // must equal the real pull
        double gravityAccel = BASE_GRAVITY_ACCEL * currGravityStrength;
        net.minecraft.world.entity.ai.attributes.AttributeInstance gravityAttr =
            living.getAttribute(net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get());
        if (gravityAttr != null) {
            gravityAccel = gravityAttr.getValue();
        }
        Vec3 correction = targetDown.subtract(frameDown).scale(gravityAccel);
        entity.setDeltaMovement(entity.getDeltaMovement().add(
            RotationUtil.vecWorldToPlayer(correction, visualRotation)
        ));
    }

    /**
     * Static friction: when grounded with no movement input, brake the
     * tangential velocity hard. Radial fields (gravity cores) and tilted ship
     * decks always have a small along-surface gravity component; vanilla's
     * multiplicative friction only slows that creep to a constant slide, which
     * makes it impossible to stand still (and oscillating around a core's
     * stable point wobbles the camera). Real planet-walk controllers pin the
     * character when idle — so do we.
     */
    private void applyStaticFriction() {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        // Players: capsule mode only (vanilla handles the default case).
        // NON-players too: mobs under blended fields (spawned inside a plated
        // cube, standing on a plated ship) have a permanently tilted pull
        // whose tangential component vanilla's multiplicative friction only
        // slows to a constant creep — idle mobs slid to the corners of the
        // cube and off plated platforms.
        if (isVisuallyDefault()) {
            return;
        }
        boolean grounded = useCapsuleCollision() ? capsuleGrounded : entity.onGround();
        if (!grounded) {
            return;
        }

        boolean controlled = entity.level().isClientSide()
            ? entity.isControlledByLocalInstance()
            : !(entity instanceof Player);
        if (!controlled) {
            return;
        }

        if (Math.abs(living.xxa) > 0.01 || Math.abs(living.zza) > 0.01) {
            return;
        }

        // in fluid, currents must be able to push: the brake ate the
        // ~0.005/tick flow push every tick ("flowing liquids can't push
        // capsule players") — vanilla's own fluid friction governs here
        if (entity.isInWater() || entity.isInLava()) {
            return;
        }

        // only on genuinely TILTED ground: on frame-flat ground there is no
        // gravity creep to brake, and braking there deadened normal walking
        // (turning/diagonal movement felt caught and jittery)
        if (capsuleGroundNormal != null && capsuleGroundNormal.dot(getUpVector()) > 0.999) {
            return;
        }

        // mid-transition the brake would eat genuine transition momentum
        if (isSurfaceTransitioning()) {
            return;
        }

        // slippery ground (ice, slime): vanilla lets entities glide — the
        // brake killed all sliding in capsule mode
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(
            entity.position().add(RotationUtil.vecPlayerToWorld(0.0, -0.5000001, 0.0, visualRotation))
        );
        if (entity.level().getBlockState(below).getFriction(entity.level(), below, entity) > 0.7f) {
            return;
        }

        // Brake the velocity component tangential to the SURFACE, not the
        // frame's local x/z: while the frame is still rotating toward a plated
        // ship wall, the leftover world-down slide sits on the frame's local Y
        // axis — the old local-x/z brake never touched it, so the player slid
        // straight off the ship before alignment finished. The surface-normal
        // component is left alone so jumping and landing are unaffected.
        Vec3 normal = capsuleGroundNormal != null ? capsuleGroundNormal : getUpVector();
        Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), visualRotation);
        double normalComponent = worldVelocity.dot(normal);
        Vec3 tangential = worldVelocity.subtract(normal.scale(normalComponent));
        Vec3 braked = normal.scale(normalComponent).add(tangential.scale(0.3));
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(braked, visualRotation));
    }

    // (ship yaw-follow correction for rotated frames lives in
    // compat.VSEntityDraggerMixin, wrapping the yaw writes inside VS's own
    // drag — applying it here, one tick later, produced a per-tick sawtooth)

    /**
     * Moves the visual/aim frame toward the frame requested by the field vector,
     * rate-limited so it is continuous. Converges exactly onto the canonical
     * cardinal frame when the field is cardinal.
     */
    private void advanceVisualRotation() {
        prevVisualRotation.set(visualRotation);

        // SHIP CARRY: compose the attached ship's FULL tick rotation onto the
        // frame AFTER the prev capture (render interpolation then sweeps the
        // same arc the ship's own render interpolation does) and BEFORE
        // frameBefore (the chase's world-velocity re-expression must NOT
        // treat the carry as frame motion — a rider's world velocity rotates
        // WITH the ship, which is exactly what leaving local deltaMovement
        // untouched does). Convention: the frame quaternion maps world ->
        // player, so a world rotation S composes as frame * S^-1.
        if (pendingShipDelta != null) {
            Quaternionf conjDelta = new Quaternionf(pendingShipDelta).conjugate();
            visualRotation.mul(conjDelta).normalize();
            visualTarget.mul(conjDelta).normalize();
            lastChaseTarget.mul(conjDelta).normalize();
            if (syncedVisualTarget != null) {
                syncedVisualTarget.mul(conjDelta).normalize();
            }
            pendingShipDelta = null;
        }

        // ATTACH BOOKKEEPING: remember which ship pose the frames correspond
        // to. prevVisualRotation (captured above) is last tick's final frame
        // — it belongs to the pose read last tick; the carried frame belongs
        // to the pose read now. getRenderRotation interpolates the frames in
        // SHIP coordinates (frame * pose) and re-expresses the result against
        // the DRAWN pose, which is what makes riding exact between ticks.
        org.valkyrienskies.core.api.ships.Ship bookShip = attachShip != null ? attachShip : renderAttachShip;
        if (bookShip != null) {
            org.joml.Quaterniondc now = bookShip.getTransform().getShipToWorldRotation();
            if (attachRotValid) {
                attachRotPrev.set(attachRot);
            }
            else {
                attachRotPrev.set(now);
            }
            attachRot.set(now);
            attachRotValid = true;
        }
        else {
            attachRotValid = false;
        }

        Quaternionf frameBefore = new Quaternionf(visualRotation);

        // EXTERNAL VISUAL OVERRIDE (rail-constrained vehicles): a minecart on
        // a rotated sticky rail is mechanically aligned to the TRACK BED even
        // though its gravity stays its own — the rail logic sets this every
        // ridden tick. Render-frame only: deltaMovement semantics stay with
        // the caller (the ride math works in explicit rail coordinates), so
        // the chase's world-velocity re-expression must not run.
        if (externalVisualOverride != null) {
            visualTarget.set(externalVisualOverride);
            visualRotation.set(externalVisualOverride);
            lastChaseTarget.set(externalVisualOverride);
            externalVisualOverride = null;
            return;
        }

        // Non-living remotes prefer the LOCALLY computed target whenever
        // local field effects are fresh: the client applies fields to them
        // itself (see the field BEs), and the synced target lags a tick or
        // two — mixing the two frames is exactly the item/orb rubber-band.
        boolean preferLocal = !(entity instanceof LivingEntity) && fieldGraceTicks > 0;
        if (syncedVisualTarget != null && shouldAcceptServerSync() && !preferLocal) {
            visualTarget.set(syncedVisualTarget);
        }
        else {
            computeVisualTarget(visualTarget);
        }

        if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            // WORLD-frame projectiles: no camera, so no smooth chase — the
            // frame snaps to its target instantly (deterministic on both
            // sides), and the chase's world-velocity re-expression below
            // must NEVER run for them: their deltaMovement is world-space,
            // and re-expressing it each chase tick bent the trident's real
            // velocity for the whole transit through a field (the
            // "teleports around before settling" desync).
            visualRotation.set(visualTarget);
            lastChaseTarget.set(visualTarget);
            return;
        }

        if (!(entity instanceof LivingEntity)) {
            // Other non-living entities (items, orbs, TNT, minecarts) have no
            // camera either: snap instantly so client and server frames are
            // equal given equal positions — the smooth chase's timing can
            // never match across sides and the mismatch surfaced as endless
            // small rubber-bands on drifting items. Unlike projectiles their
            // deltaMovement IS local, so fall through to the world-velocity
            // re-expression below (one exact step per target change).
            lastChaseTarget.set(visualTarget);
            visualRotation.set(visualTarget);
        }

        // target stability: once the target stops moving, converge decisively
        // (also how the frame reaches EXACT identity quickly after leaving a
        // field — lingering capsule collision in the plain world was the slow
        // 5%-per-tick deadband creep)
        if (angleBetween(visualTarget, lastChaseTarget) < (float) Math.toRadians(0.05)) {
            targetStableTicks++;
        }
        else {
            targetStableTicks = 0;
        }
        lastChaseTarget.set(visualTarget);

        float angle = angleBetween(visualRotation, visualTarget);
        if (angle < (float) Math.toRadians(0.02)) {
            visualRotation.set(visualTarget);
        }
        else {
            // CONTINUOUS response: gentle near the target (absorbs sub-degree
            // field noise), proportionally firmer with distance, hard-capped
            // per tick. The old tier structure snapped the FULL distance for
            // any lag between 3 and 15 degrees — a per-tick velocity
            // discontinuity felt as rhythmic stutter whenever the lag crossed
            // a tier boundary (e.g. while circling a gravity core).
            // SURFACE GLUE: standing on a held surface whose normal the
            // target actually points along, track at high gain — the stood-on
            // face is a clean, physically-anchored signal, and the
            // proportional lag (rate/gain) was the constant ~25-30 deg tilt
            // on rotating ships (screenshot evidence). SELF-LIMITING: the
            // gain rises ONLY while the target agrees with the surface
            // (dot > 0.95); any flicker toward a stale field vector falls
            // back to the smooth gains below and gets smeared out exactly
            // like the stable baseline did. Not 1.0: a touch of smoothing
            // still absorbs single-tick probe noise.
            boolean gluedToSurface = fieldAnchorShip != null;
            if (!gluedToSurface && lastGroundNormal != null
                && (capsuleGrounded || entity.onGround())) {
                Vec3 targetUpNow = RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), visualTarget);
                gluedToSurface = targetUpNow.dot(lastGroundNormal) > 0.95;
            }
            float smoothGain = Mth.lerp(Mth.clamp(angle / (float) Math.toRadians(3), 0.0f, 1.0f), 0.08f, 0.35f);
            // a still target is converged on decisively — RAMPED in over a
            // few ticks rather than switched: the old step from the smooth
            // gain straight to 0.5 was a visible speed-up ("animates, then
            // snaps") at the end of every chase that came to rest
            float stableBlend = Mth.clamp((targetStableTicks - 2) / 8.0f, 0.0f, 1.0f);
            float proportion = gluedToSurface
                ? 0.9f
                : Mth.lerp(stableBlend, smoothGain, 0.5f);
            float maxTurn = VISUAL_TURN_PER_TICK;
            if (fieldAnchorShip != null && lastGroundNormal == null) {
                maxTurn = SHIP_FIELD_TURN_PER_TICK;
            }
            if (surfaceChangeCooldown > 0) {
                // committed surface change: converge decisively (the player is
                // pinned against the edge until the frame reaches the new face)
                proportion = Math.max(proportion, 0.45f);
                maxTurn = TRANSITION_TURN_PER_TICK;
            }
            float step = Math.min(angle * proportion, maxTurn);
            visualRotation.slerp(visualTarget, step / angle).normalize();
        }

        // WORLD VELOCITY IS INVARIANT UNDER THE CHASE. Local velocity would
        // otherwise be dragged with the rotating frame — which fabricated
        // momentum out of thin air: releasing from a rotated surface swung the
        // built-up velocity through world space as the frame chased back
        // upright (the "slid off the spinning contraption and got LAUNCHED far
        // away" bug), and every face transition rotated momentum TWICE (once
        // implicitly here, once explicitly in rotateVelocityAcrossSurfaceChange
        // — which undid the concave up-the-wall conversion and overshot convex
        // edges). The explicit face-change rotation is now the single owner of
        // momentum redirection. Creative flight keeps the frame-dragged
        // behavior: velocity rotating with the frame is what closes orbits
        // around gravity cores (a deliberate design).
        if (!visualRotation.equals(frameBefore)) {
            boolean controlled = entity.level().isClientSide()
                ? entity.isControlledByLocalInstance()
                : !(entity instanceof Player);
            boolean flying = entity instanceof Player player && player.getAbilities().flying;
            if (controlled) {
                // the frame the stored local velocity is READ through before it
                // is re-expressed in the new frame: the old frame (world
                // velocity fully preserved), or — for creative flight — the
                // old frame advanced by the dragged share of this tick's step
                Quaternionf readFrame = frameBefore;
                if (flying) {
                    float stepAngle = angleBetween(frameBefore, visualRotation);
                    if (stepAngle <= FLY_DRAG_PER_TICK) {
                        readFrame = null; // the whole step is dragged: nothing to do
                    }
                    else {
                        Quaternionf partial = new Quaternionf(frameBefore);
                        if (partial.dot(visualRotation) < 0.0f) {
                            partial.set(-partial.x, -partial.y, -partial.z, -partial.w);
                        }
                        readFrame = partial.slerp(visualRotation, FLY_DRAG_PER_TICK / stepAngle).normalize();
                    }
                }
                if (readFrame != null) {
                    Vec3 world = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), readFrame);
                    entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(world, visualRotation));
                }
            }
        }

        normalizeTwist();

        // Rotating the frame rotates the CAPSULE in place — a rotation is not
        // a move, so nothing re-resolves contacts until the next move tick,
        // and near walls the head could spend a frame inside a block (camera
        // clipping, hitbox tip phasing). After a significant chase step,
        // depenetrate immediately at the current position.
        float stepMoved = angleBetween(frameBefore, visualRotation);
        if (stepMoved > (float) Math.toRadians(1.5)
            && useCapsuleCollision()
            && entity.level().isClientSide() && entity.isControlledByLocalInstance()
        ) {
            net.camacraft.gravityunbound.util.CapsuleCollider.Result resolved =
                net.camacraft.gravityunbound.util.CapsuleCollider.collide(
                    entity, getUpVector(), getEffectiveUpVector(), Vec3.ZERO, false
                );
            if (resolved.collidedMovement.lengthSqr() > 1.0E-10) {
                Vec3 nudged = entity.position().add(resolved.collidedMovement);
                entity.setPos(nudged.x, nudged.y, nudged.z);
            }
        }
    }

    /**
     * Parallel transport is twist-free but path-dependent: after walking around
     * a cube the frame's yaw reference has rotated. Quietly unwind ONLY the
     * twist component (the rotation about the frame's own up axis) back toward
     * the canonical cardinal frame's yaw reference, compensating yaw and local
     * velocity exactly, so the player sees and feels NOTHING.
     *
     * The tilt component (the frame's up vs the cardinal up) is deliberately
     * never touched here — that belongs to the chase in
     * {@link #advanceVisualRotation()}. The old implementation measured the
     * TOTAL angle to canonical and snapped all of it, which made this function
     * fight the chase whenever the field sat within a couple of degrees of a
     * cardinal (a near-level VS ship, the axis region of a gravity core): every
     * tick the chase tilted the frame toward the field, this snapped it back
     * and nudged yaw with a compensation that is only valid for pure twists —
     * a permanent 20 Hz limit cycle felt as camera jitter and wrong movement.
     */
    private void normalizeTwist() {
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile
            || shouldAcceptServerSync()) {
            return;
        }

        Quaternionf canonical = RotationUtil.getWorldRotationQuaternion(currGravityDirection);
        if (visualRotation.equals(canonical)) {
            return;
        }

        // only re-anchor while the chase is settled; never mid-transition
        if (angleBetween(visualRotation, visualTarget) > (float) Math.toRadians(0.3)) {
            return;
        }

        // final exact anchoring: everything within a hair of canonical converges
        // bit-exactly so cardinal behavior matches the original mod
        if (angleBetween(visualRotation, canonical) < (float) Math.toRadians(0.03)) {
            Quaternionf old = new Quaternionf(visualRotation);
            visualRotation.set(canonical);
            visualTarget.set(canonical);
            prevVisualRotation.set(canonical);
            compensateFrameChange(old, visualRotation);
            return;
        }

        Vec3 currentUp = getUpVector();
        Vec3 cardinalUp = getCurrGravityDirectionVec().scale(-1);
        double tilt = QuaternionUtil.angleBetween(cardinalUp, currentUp);
        if (tilt > Math.toRadians(90)) {
            // transport axis becomes ambiguous toward 180 degrees; don't anchor
            return;
        }

        // twist-free reference: the canonical frame parallel-transported onto
        // the frame's CURRENT up (same construction as computeVisualTarget)
        Quaternionf reference = new Quaternionf(canonical);
        if (tilt > 1.0E-6) {
            reference.mul(QuaternionUtil.getRotationBetween(cardinalUp, currentUp).conjugate()).normalize();
        }

        // both frames map currentUp onto local up, so the remaining difference
        // is a pure rotation about local Y — the twist
        Quaternionf diff = new Quaternionf(visualRotation).mul(new Quaternionf(reference).conjugate());
        if (diff.w() < 0) {
            diff.set(-diff.x(), -diff.y(), -diff.z(), -diff.w());
        }
        float twist = 2.0f * (float) Math.atan2(diff.y(), diff.w());
        if (Math.abs(twist) < 1.0E-6f) {
            return;
        }

        // remove the WHOLE twist at once: the compensation below is exact, so
        // there is nothing to smooth — a rate limit only stretches the unwind
        // into a multi-second window in which any residual imperfection shows
        // up as sustained jitter after coming to rest
        Quaternionf delta = new Quaternionf().rotationY(-twist);

        Quaternionf old = new Quaternionf(visualRotation);
        visualRotation.premul(delta).normalize();
        // apply the same twist to the interpolation start and the chase target;
        // compensateFrameChange shifts yRotO as well, so the rendered camera is
        // unchanged at EVERY partial tick (a pure local-Y twist plus its exact
        // yaw compensation cancels identically)
        prevVisualRotation.premul(delta).normalize();
        visualTarget.premul(delta).normalize();
        compensateFrameChange(old, visualRotation);
    }

    /**
     * Adjust yaw/pitch and local velocity for a frame change so that the
     * world-space look direction and world-space velocity are preserved exactly
     * — the camera and motion do not move at all.
     */
    private void compensateFrameChange(Quaternionf oldFrame, Quaternionf newFrame) {
        // A pure twist never changes pitch, so compute the yaw delta from the
        // HORIZONTAL look direction (pitch 0). Using the real pitched look would
        // degenerate when looking straight up/down and slam yaw to 0.
        Vec3 worldLookFlat = RotationUtil.vecPlayerToWorld(
            RotationUtil.rotToVec(entity.getYRot(), 0), oldFrame
        );
        var newRot = RotationUtil.vecToRot(RotationUtil.vecWorldToPlayer(worldLookFlat, newFrame));

        float deltaYaw = Mth.wrapDegrees(newRot.x - entity.getYRot());

        entity.setYRot(entity.getYRot() + deltaYaw);
        entity.yRotO += deltaYaw;
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.yBodyRot += deltaYaw;
            livingEntity.yBodyRotO += deltaYaw;
            livingEntity.yHeadRot += deltaYaw;
            livingEntity.yHeadRotO += deltaYaw;
        }
        // the first-person hand sways by (view yaw - smoothed yaw): a
        // re-parametrization must shift the smoothed copy too, or every
        // twist unwind on a spinning ship reads as the player turning and
        // the hand swings / saws (client-only state, isolated for servers)
        if (entity.level().isClientSide()) {
            net.camacraft.gravityunbound.client.ClientHandSway.shiftYaw(entity, deltaYaw);
        }

        Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), oldFrame);
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(worldVelocity, newFrame));
    }

    /**
     * The frame that maps the true field vector onto local down.
     *
     * For players this is PARALLEL TRANSPORT from the current frame: rotate only
     * by the change in the up direction, introducing no twist — so a gravity
     * flip pivots naturally instead of cartwheeling through the cardinal frames'
     * differing yaw conventions. On an exact 180 degree flip the pivot axis is
     * the player's look direction (a roll), which keeps them looking the same
     * way through the flip.
     *
     * Non-player entities keep the cardinal-anchored frame (their movement is
     * cardinal anyway and they have no camera).
     */
    private void computeVisualTarget(Quaternionf out) {
        Quaternionf canonical = RotationUtil.getWorldRotationQuaternion(currGravityDirection);
        Vec3 cardinal = getCurrGravityDirectionVec();
        Vec3 target = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6
            ? targetGravityVector.normalize() : cardinal;

        if (!(entity instanceof LivingEntity)) {
            if (QuaternionUtil.angleBetween(target, cardinal) < 1.0E-3) {
                out.set(canonical);
            }
            else {
                // F = canonical ∘ R, where R rotates the target vector onto the cardinal
                out.set(canonical).mul(QuaternionUtil.getRotationBetween(target, cardinal)).normalize();
            }
            return;
        }

        // Planet-walk rule: while standing on a surface, align to THAT surface
        // (via the stability-filtered memory — NEVER the raw per-tick contact
        // normal) instead of the raw field direction. Radial fields are never
        // exactly perpendicular to the flat faces of a blocky planet, and near
        // a small core the field swings several degrees per step — the filtered
        // normal keeps the control frame rock-steady on each face. Genuinely
        // airborne keeps the pure field direction (orbit/flight feel).
        Vec3 targetUp = effectiveTargetUp(target);

        Vec3 currentUp = getUpVector();

        double angle = QuaternionUtil.angleBetween(currentUp, targetUp);
        if (angle < 1.0E-4) {
            out.set(visualRotation);
            return;
        }

        // pivot axis for exact flips: the player's current look direction, so a
        // 180 degree gravity change rolls around where they are looking
        Vec3 lookWorld = RotationUtil.vecPlayerToWorld(
            RotationUtil.rotToVec(entity.getYRot(), 0), visualRotation
        );

        Quaternionf delta = QuaternionUtil.getRotationBetween(currentUp, targetUp, lookWorld);
        // F_new = F_old ∘ delta^-1  (delta rotates old up onto new up in world space)
        out.set(visualRotation).mul(delta.conjugate()).normalize();
    }

    private static float angleBetween(Quaternionf a, Quaternionf b) {
        float dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return (float) (2.0 * Math.acos(Mth.clamp(dot, 0.0f, 1.0f)));
    }

    private boolean shouldAcceptServerSync() {
        return entity != null && entity.level().isClientSide() && !GCUtil.isClientPlayer(entity);
    }

    /**
     * Computes the field target vector from base gravity + effects, then snaps
     * the physics direction to its nearest cardinal (with hysteresis).
     */
    public void updateGravityStatus() {
        if (shouldAcceptServerSync()) {
            // remote entities take their state from the server; effects pushed by
            // client-side block entities are meaningless — clear them so the
            // lists cannot grow unboundedly
            delayApplyDirEffects.clear();
            tempEffects.clear();
            delayApplyStrengthEffect = 1.0;
            return;
        }

        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            GravityCapabilityImpl vehicleComp = GravityChangerAPI.getGravityComponentOrNull(vehicle);
            if (vehicleComp != null) {
                if (updateMountedOnShipGravity(vehicle, vehicleComp)) {
                    return;
                }
                currGravityDirection = vehicleComp.currGravityDirection;
                currGravityStrength = vehicleComp.currGravityStrength;
                targetGravityVector = vehicleComp.targetGravityVector;
                // the vehicle owns the field state; nothing ship-anchored
                // survives from before mounting
                fieldGraceTicks = vehicleComp.fieldGraceTicks;
                lastFieldVector = vehicleComp.lastFieldVector;
                fieldAnchorShip = null;
                fieldAnchorLocalDown = null;
                fieldAnchorLocalPos = null;
                fieldRegion = null;
                fieldRegionShip = null;
                fieldRadial = false;
                return;
            }
        }

        targetGravityVector = baseGravityDirection;
        targetGravityStrength = baseGravityStrength * GravityConfig.gravityStrengthMultiplier.get();

        tempEffects.clear();
        isFiringUpdateEvent = true;
        try {
            // per-dimension ambient gravity (config / API): a LOW-priority
            // effect so any plating/core/normalizer field overrides it
            Vec3 dimensionDown = net.camacraft.gravityunbound.util.DimensionGravity.downFor(entity.level());
            if (dimensionDown != null) {
                this.applyGravityDirectionEffect(
                    dimensionDown, RotationParameters.getDefault(), 100.0, false,
                    net.camacraft.gravityunbound.util.DimensionGravity.strengthFor(entity.level()), true
                );
            }
            for (ItemStack handSlot : entity.getHandSlots()) {
                Item item = handSlot.getItem();
                if (item instanceof GravityAnchorItem anchorItem) {
                    this.applyGravityDirectionEffect(
                        Vec3.atLowerCornerOf(anchorItem.direction.getNormal()),
                        null, 1000000
                    );
                }
            }
            if (entity instanceof LivingEntity livingEntity) {
                // API showcase: enchanted boots that cling to any surface
                // (see enchantment.SurfaceClingEnchantment — it uses only the
                // public effect API and the public probe/surface accessors)
                net.camacraft.gravityunbound.enchantment.SurfaceClingEnchantment.applyTo(livingEntity, this);
                for (GravityDirectionMobEffect dirEffect : GravityDirectionMobEffect.EFFECT_MAP.values()) {
                    MobEffectInstance effectInstance = livingEntity.getEffect(dirEffect);
                    if (effectInstance != null) {
                        int amplifier = effectInstance.getAmplifier();

                        this.applyGravityDirectionEffect(
                            Vec3.atLowerCornerOf(dirEffect.gravityDirection.getNormal()),
                            null,
                            amplifier + 1.0
                        );
                    }
                }
                if (livingEntity.hasEffect(GravityMobEffects.INVERT.get())) {
                    // invert relative to the BASE direction, not the current
                    // one: inverting whatever is currently applied re-inverts
                    // the already-inverted result a few ticks later — a
                    // permanent flip-flop limit cycle
                    this.applyGravityDirectionEffect(
                        baseGravityDirection.scale(-1),
                        null, 5
                    );
                }
                GravityMobEffects.INCREASE.get().apply(livingEntity, this);
                GravityMobEffects.DECREASE.get().apply(livingEntity, this);
                GravityMobEffects.REVERSE.get().apply(livingEntity, this);
            }

            tempEffects.addAll(delayApplyDirEffects);
            delayApplyDirEffects.clear();

            targetGravityStrength *= delayApplyStrengthEffect;
            delayApplyStrengthEffect = 1.0;
        }
        finally {
            isFiringUpdateEvent = false;
        }

        // secondary (bleed) contributions only blend alongside a primary
        // field; alone they are not a field (see applyGravityDirectionEffect)
        boolean anyPrimary = false;
        for (GravityDirEffect effect : tempEffects) {
            if (!effect.secondary()) {
                anyPrimary = true;
                break;
            }
        }
        if (!anyPrimary && !tempEffects.isEmpty()) {
            // ...EXCEPT while surface-walking. The quarter-round pocket just
            // past a cube edge lies outside BOTH faces' primary columns and is
            // covered only by their bleeds — whose diagonal blend is exactly
            // what carries the player around the edge. Treating it as "no
            // field" let the field grace expire mid-transition (slow walking,
            // pausing on the edge), which reset every surface hold and snapped
            // gravity back to world-down: "sometimes gravity changes too early
            // and I fall off the first face before reaching the second". A
            // held surface / fresh release / committed change means the player
            // was inside a primary field moments ago — sustain the bleed blend
            // until the next face's primary takes over. Standing on plain
            // ground beside a plate has none of these and still gets no field.
            boolean surfaceWalking = lastGroundNormal != null
                || recentReleasedTicks > 0
                || surfaceChangeCooldown > 0;
            // BOUNDED: the pocket between two faces is crossed in a few
            // ticks. Walking along a surface OUT of the plated region kept
            // qualifying as surface-walking, so the bleed pull followed the
            // player meters past the field ("walked off the plating and was
            // still pulled upward until well away"). Sustain briefly, then
            // treat as no field.
            if (!surfaceWalking || ++secondaryOnlySustainTicks > 12) {
                tempEffects.clear();
            }
        }
        else {
            secondaryOnlySustainTicks = 0;
        }

        boolean hadFieldEffects = !tempEffects.isEmpty();
        resolveGravityTarget();

        if (hadFieldEffects) {
            fieldGraceTicks = FIELD_GRACE_TICKS;
            lastFieldVector = targetGravityVector;
        }
        else if (fieldGraceTicks > 0 && lastFieldVector != null) {
            if (lastGroundNormal != null && (capsuleGrounded || entity.onGround())
                && stillInsideLastFieldRegion()) {
                // STANDING ON A HELD SURFACE SUSTAINS ITS FIELD. Field-effect
                // delivery flickers out for SECONDS on fast-rotating ships
                // (measured: server fx=0 while the client stayed fed — the
                // server-side player position lags by packets and maps
                // outside the swept field column in ship space). The probe
                // ray under the feet is immune to that timing: while it says
                // we stand on the surface the field endorsed, hold the grace
                // open and point the sustained field ONTO that surface — so
                // it rotates WITH the ship instead of freezing stale (the
                // ~40 deg target drift in the logs). This is what collapsed
                // the server to world-down mid-ride: the source of the jump
                // fling and the periodic camera snap-arounds.
                fieldGraceTicks = FIELD_GRACE_TICKS;
                lastFieldVector = lastGroundNormal.scale(-1);
                targetGravityVector = lastFieldVector;
            }
            else {
                // just left the field with no surface underfoot: keep the
                // pull briefly (jumps, edge pockets). Grounded WITHOUT a held
                // surface = walked off onto plain ground: release in 2 ticks
                // (the "gravity takes a second to update" delay).
                if ((capsuleGrounded || entity.onGround()) && fieldGraceTicks > 2) {
                    fieldGraceTicks = 2;
                }
                fieldGraceTicks--;
                targetGravityVector = lastFieldVector;
            }
        }

        snapPhysicsDirection();

        currGravityStrength = targetGravityStrength;
    }

    private @Nullable Vec3 lastFieldVector = null;
    // consecutive ticks the field consisted of secondary bleeds only
    private int secondaryOnlySustainTicks = 0;

    /**
     * SEATED ON A SHIP UNDER A FIELD: the rider's down is the SEAT's down.
     *
     * A passenger normally inherits its vehicle's gravity. A Valkyrien
     * Skies seat (the mounting entity a helm or a seat block spawns) is a
     * plain entity: its own frame follows the raw field at its position —
     * under a gravity core that is the RADIAL pull, which points at the
     * core, not through the seat — and on the client its field target is
     * not even synced, so the rider's down was the seat's nearest cardinal
     * there and the radial direction on the server. Looking straight down
     * from a chair did not look at the chair.
     *
     * While mounted to a ship whose field covers the seat, the rider's
     * gravity is the ship's own down (shipyard -Y: seats stand upright in
     * their ship's grid), re-derived from the live transform every tick,
     * anchored to the ship so the frame turns with it exactly like a
     * standing rider's. Seats on ships without a field keep vanilla
     * (world-down) behavior, exactly as standing on such a ship does.
     * Decided identically on both sides from the block grid, so client
     * and server never disagree about a seated player's gravity.
     */
    private boolean updateMountedOnShipGravity(Entity vehicle, GravityCapabilityImpl vehicleComp) {
        org.valkyrienskies.core.api.ships.Ship ship =
            org.valkyrienskies.mod.common.VSGameUtilsKt.getShipMountedTo(entity);
        if (ship == null) {
            return false;
        }
        org.joml.Vector3d seat = new org.joml.Vector3d(vehicle.getX(), vehicle.getY(), vehicle.getZ());
        ship.getTransform().getWorldToShipMatrix().transformPosition(seat);
        boolean fielded = vehicleComp.isGravityOverridden()
            || net.camacraft.gravityunbound.util.GravityFieldLookup.hasEntityFieldAt(
                entity.level(), net.minecraft.core.BlockPos.containing(seat.x, seat.y, seat.z));
        if (!fielded) {
            return false;
        }

        org.joml.Vector3d worldDown = new org.joml.Quaterniond(
            ship.getTransform().getShipToWorldRotation()).transform(new org.joml.Vector3d(0.0, -1.0, 0.0));
        Vec3 down = new Vec3(worldDown.x, worldDown.y, worldDown.z).normalize();

        targetGravityVector = down;
        targetGravityStrength = vehicleComp.currGravityStrength > 1.0E-4
            ? vehicleComp.currGravityStrength
            : baseGravityStrength * GravityConfig.gravityStrengthMultiplier.get();
        currentRotationParameters = RotationParameters.getDefault();
        surfaceAlignAllowed = false;
        fieldAnchorShip = ship;
        fieldAnchorLocalDown = new Vec3(0.0, -1.0, 0.0);
        fieldAnchorLocalPos = null;
        fieldRegion = null;
        fieldRegionShip = ship;
        fieldRadial = false;
        fieldGraceTicks = FIELD_GRACE_TICKS;
        lastFieldVector = down;
        tempEffects.clear();
        delayApplyDirEffects.clear();
        delayApplyStrengthEffect = 1.0;

        snapPhysicsDirection();
        currGravityStrength = targetGravityStrength;
        return true;
    }

    /**
     * BOUNDED SUSTAIN. The held-surface field sustain above bridges effect
     * DROPOUTS (the server-side player position lags the ship pose by
     * packets on fast-rotating ships and can map outside the swept field
     * column for a moment) — but it is a self-sustaining loop
     * (probe -> sustain -> gravity active -> probe), and on a continuous
     * floor the probe never misses: walking OFF the plating onto the
     * unplated part of the same deck kept the player snapped forever. The
     * dominant field now reports its region (in its own block grid); the
     * sustain only bridges while the entity is still inside that region.
     * Ship regions get a tolerance for the packet lag; world regions need
     * none (nothing lags there), so they release as soon as the entity
     * leaves the field — the pre-sustain behavior.
     */
    private boolean stillInsideLastFieldRegion() {
        if (fieldRegion == null) {
            return true;
        }
        Vec3 p = entity.position();
        double tolerance = 0.0;
        if (fieldRegionShip != null) {
            org.joml.Vector3d g = new org.joml.Vector3d(p.x, p.y, p.z);
            fieldRegionShip.getTransform().getWorldToShipMatrix().transformPosition(g);
            p = new Vec3(g.x, g.y, g.z);
            tolerance = 1.0;
        }
        return fieldRegion.inflate(tolerance).contains(p);
    }
    // one-tick visual-frame override from rail-constrained vehicles
    private @Nullable Quaternionf externalVisualOverride = null;
    // consecutive ticks the collision ground plane disagreed with the held face
    private int contactDisagreeTicks = 0;

    /**
     * Whether the entity is ATTACHED to a Valkyrien Skies ship (the dominant
     * field is mounted on it, or one of its faces is the held surface). While
     * true the visual frame carries the ship's full rotation — twist
     * included — so VS's dragger must not add the ship's yaw on top; see
     * {@link #applyShipAttachCarry} and compat.VSEntityDraggerMixin.
     */
    public boolean isShipFieldAnchored() {
        return attachShip != null;
    }

    /** See {@link #isShipFieldAnchored()}. */
    public boolean isShipAttached() {
        return attachShip != null;
    }

    /**
     * True while Gravity Unbound OWNS this entity's gravity: a field (or its
     * grace window) is active, or the base gravity is not plain down. Used
     * by compatibility layers (Ad Astra) to keep other mods' planet gravity
     * from overriding a field's pull.
     */
    public boolean isGravityOverridden() {
        return fieldGraceTicks > 0 || !baseGravityDirection.equals(DOWN);
    }

    /** The ship the dominant field is mounted on, or null. */
    @Nullable
    public org.valkyrienskies.core.api.ships.Ship getFieldAnchorShip() {
        return fieldAnchorShip;
    }

    /** See the consumption site in {@link #advanceVisualRotation()}. */
    public void setExternalVisualOverride(Quaternionf frame) {
        if (externalVisualOverride == null) {
            externalVisualOverride = new Quaternionf(frame);
        }
        else {
            externalVisualOverride.set(frame);
        }
    }

    private void resolveGravityTarget() {
        if (tempEffects.isEmpty()) {
            currentRotationParameters = RotationParameters.getDefault();
            surfaceAlignAllowed = true;
            if (fieldGraceTicks <= 0) {
                fieldAnchorShip = null;
                fieldAnchorLocalDown = null;
                fieldAnchorLocalPos = null;
                fieldRegion = null;
                fieldRegionShip = null;
                fieldRadial = false;
            }
            return;
        }
        fieldAnchorShip = null;
        fieldAnchorLocalDown = null;
        fieldAnchorLocalPos = null;
        fieldRegion = null;
        fieldRegionShip = null;
        fieldRadial = false;

        double maxPriority = -Double.MAX_VALUE;
        for (GravityDirEffect effect : tempEffects) {
            if (effect.priority > maxPriority) {
                maxPriority = effect.priority;
            }
        }

        // blend effects close to the strongest one, weighted by priority
        double BLEND_RANGE = 5.0;
        Vec3 accumulatedGravity = Vec3.ZERO;
        double totalWeight = 0;
        double accumulatedStrengthScale = 0;
        RotationParameters bestParams = null;
        double bestParamPriority = -Double.MAX_VALUE;
        boolean bestAllowSurfaceAlign = true;

        for (GravityDirEffect effect : tempEffects) {
            if (effect.priority >= maxPriority - BLEND_RANGE) {
                double weight = 1.0 - (maxPriority - effect.priority) / BLEND_RANGE;
                weight = Math.max(0, weight);

                accumulatedGravity = accumulatedGravity.add(effect.direction.normalize().scale(weight));
                totalWeight += weight;
                accumulatedStrengthScale += effect.strengthScale() * weight;

                if (effect.priority > bestParamPriority) {
                    bestParamPriority = effect.priority;
                    bestAllowSurfaceAlign = effect.allowSurfaceAlign();
                    fieldAnchorShip = effect.sourceShip();
                    fieldAnchorLocalDown = effect.shipLocalDown();
                    fieldAnchorLocalPos = effect.shipLocalSourcePos();
                    fieldAnchorRadialSign = effect.radialSign();
                    fieldRegion = effect.gridRegion();
                    fieldRegionShip = effect.sourceShip();
                    fieldRadial = effect.radial();
                    if (effect.rotationParameters != null) {
                        bestParams = effect.rotationParameters;
                    }
                }
            }
        }
        // the dominant (closest/strongest) source decides whether planet-walk
        // surface snapping is allowed under this field
        surfaceAlignAllowed = bestAllowSurfaceAlign;

        double magnitude = accumulatedGravity.length();
        if (totalWeight > 0.0001 && magnitude > 0.02) {
            targetGravityVector = accumulatedGravity.normalize();
        }
        // else: near-total cancellation, keep the previous target

        // Opposing fields cancel: scale strength down so the entity floats
        // (still controllable) instead of flapping between directions.
        if (totalWeight > 0.0001) {
            double cancellation = Mth.clamp(magnitude / totalWeight, 0.0, 1.0);
            targetGravityStrength *= cancellation * cancellation;

            // per-effect force scale: gradual falloff weakens the pull with
            // distance, and per-block gravity acceleration settings scale it
            // up or down — while the orientation (the blended direction
            // above) is untouched. No upper clamp: sources may be configured
            // stronger than baseline gravity.
            targetGravityStrength *= Math.max(0.0, accumulatedStrengthScale / totalWeight);
        }

        currentRotationParameters = bestParams != null ? bestParams : RotationParameters.getDefault();
        tempEffects.clear();

        // FIELD-SHIP ANCHOR: when the dominant field is ship-mounted, derive
        // the target from the LIVE ship transform — exact at any moment,
        // grounded or airborne, immune to the one-tick staleness of the
        // queued effect. RADIAL sources anchor their CENTER (the ship-space
        // constant) and re-derive the pull from the live entity position;
        // constant-direction sources (plates, normalizers) anchor the
        // shipyard direction itself.
        if (fieldAnchorShip != null && fieldAnchorLocalPos != null) {
            org.joml.Vector3d center = new org.joml.Vector3d(
                fieldAnchorLocalPos.x, fieldAnchorLocalPos.y, fieldAnchorLocalPos.z);
            fieldAnchorShip.getTransform().getShipToWorldMatrix().transformPosition(center);
            Vec3 toCenter = new Vec3(center.x, center.y, center.z).subtract(entity.position());
            if (toCenter.lengthSqr() > 1.0E-6) {
                targetGravityVector = toCenter.normalize().scale(fieldAnchorRadialSign);
            }
        }
        else if (fieldAnchorShip != null && fieldAnchorLocalDown != null) {
            org.joml.Vector3d worldDown = new org.joml.Quaterniond(
                fieldAnchorShip.getTransform().getShipToWorldRotation())
                .transform(new org.joml.Vector3d(
                    fieldAnchorLocalDown.x, fieldAnchorLocalDown.y, fieldAnchorLocalDown.z));
            targetGravityVector = new Vec3(worldDown.x, worldDown.y, worldDown.z).normalize();
        }
    }

    // dominant ship-mounted field source this tick (survives through the
    // field grace; cleared when the field is fully gone)
    @Nullable
    private org.valkyrienskies.core.api.ships.Ship fieldAnchorShip = null;
    @Nullable
    private Vec3 fieldAnchorLocalDown = null;
    // RADIAL sources (gravity cores): the source CENTER in shipyard
    // coordinates — the true ship-space constant. The direction toward it is
    // position-dependent, so it must be re-derived from the live pose and
    // the live entity position, never sampled and held.
    @Nullable
    private Vec3 fieldAnchorLocalPos = null;
    private double fieldAnchorRadialSign = 1.0;
    // the dominant field's region in its source's block grid (shipyard
    // coordinates for ship-mounted sources, world coordinates otherwise) —
    // bounds the held-surface sustain, see stillInsideLastFieldRegion
    @Nullable
    private AABB fieldRegion = null;
    @Nullable
    private org.valkyrienskies.core.api.ships.Ship fieldRegionShip = null;
    // whether the dominant field is RADIAL (a gravity core, on a ship or in
    // the world): its pull carries a tangential component everywhere on a
    // planet face, which the surface-adoption gates must not read as an
    // endorsement of stair risers and walls (see updateSurfaceProbe)
    private boolean fieldRadial = false;

    /**
     * Physics may only sit on a cardinal direction. Snap to the target vector's
     * nearest cardinal, but only when it is decisively better than the current
     * one (hysteresis), and require stability before 180 degree flips.
     */
    private void snapPhysicsDirection() {
        if (targetGravityVector == null || targetGravityVector.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 target = targetGravityVector.normalize();

        // Players snap to the EFFECTIVE gravity (surface normal while standing
        // on a face). The raw radial field crosses 45-degree boundaries while
        // walking a planet face, flapping the cardinal — and with it the
        // canonical twist reference and the server sync — several times a
        // second, even though the player's actual up never moved.
        if (entity instanceof Player) {
            target = effectiveTargetUp(target).scale(-1);
        }

        Direction candidate = Direction.getNearest(target.x, target.y, target.z);
        if (candidate == currGravityDirection) {
            oppositeStableTicks = 0;
            return;
        }

        // freshly spawned entities adopt the field's cardinal immediately —
        // the hysteresis and opposite-flip stability exist to filter noise on
        // entities that already HAVE a meaningful direction; a spawn-egg mob
        // in an UP field waiting 3 ticks to flip was visible as spawning
        // upright and rotating moments later
        boolean freshSpawn = entity != null && entity.tickCount <= 3 && !(entity instanceof Player);

        double candidateDot = target.dot(Vec3.atLowerCornerOf(candidate.getNormal()));
        double currentDot = target.dot(Vec3.atLowerCornerOf(currGravityDirection.getNormal()));
        if (candidateDot < currentDot + SNAP_HYSTERESIS_DOT && !freshSpawn) {
            return;
        }

        if (candidate == currGravityDirection.getOpposite() && !freshSpawn) {
            // opposing fields can produce brief flips; require stability
            oppositeStableTicks++;
            if (oppositeStableTicks < OPPOSITE_FLIP_STABLE_TICKS) {
                return;
            }
        }
        oppositeStableTicks = 0;

        currGravityDirection = candidate;
    }

    @Override
    public void applyGravityChange() {
        if (currentRotationParameters == null) {
            currentRotationParameters = RotationParameters.getDefault();
        }

        if (prevGravityDirection != currGravityDirection) {
            if (entity instanceof Player || !isVisuallyDefault()
                || entity instanceof net.minecraft.world.entity.projectile.Projectile) {
                // Any entity with an ACTIVE continuous frame: the cardinal is
                // only a reference — the visual frame already carries the true
                // orientation, so a cardinal crossing needs a box update and
                // nothing else. The legacy reposition below also rewrites the
                // entity's yaw/pitch with per-cardinal conventions: orbiting
                // projectiles crossed cardinals constantly and their models
                // snap-flipped at every crossing ("trident flipping all over"),
                // while the position adjustments read as jolts/teleports.
                // The legacy path remains only for identity-frame entities
                // (an instant cardinal flip before any frame motion).
                prevGravityDirection = currGravityDirection;
                updateBoundingBox();
            }
            else {
                applyGravityDirectionChange(
                    prevGravityDirection, currGravityDirection,
                    currentRotationParameters, false
                );
                prevGravityDirection = currGravityDirection;
            }
        }

        if (Math.abs(currGravityStrength - prevGravityStrength) > 0.0001) {
            prevGravityStrength = currGravityStrength;
        }
    }

    // (the arbitrary-angle pull needs no extra force: vanilla gravity accelerates
    // along local down, and the visual frame maps local down onto the true field
    // vector, so entities are pulled at the exact field angle)

    // ------------------------------------------------------------------
    // networking
    // ------------------------------------------------------------------

    private void maybeSendSync() {
        boolean changed = needsSync
            || lastSyncedDirection != currGravityDirection
            || Math.abs(lastSyncedStrength - currGravityStrength) > 0.01
            || angleBetween(lastSyncedVisualTarget, visualTarget) > 0.05f;

        if (changed) {
            sendSyncPacketToOtherPlayers();
        }
    }

    private void sendSyncPacketToOtherPlayers() {
        if (entity != null && !this.entity.level().isClientSide()) {
            GravityNetwork.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> this.entity),
                makeSyncPacket()
            );
            lastSyncedDirection = currGravityDirection;
            lastSyncedStrength = currGravityStrength;
            lastSyncedVisualTarget.set(visualTarget);
            needsSync = false;
            noAnimation = false;
        }
    }

    public UpdateGravityCapabilityPacket makeSyncPacket() {
        return new UpdateGravityCapabilityPacket(
            this.noAnimation, this.entity.getUUID(),
            baseGravityDirection,
            Vec3.atLowerCornerOf(currGravityDirection.getNormal()),
            baseGravityStrength, currGravityStrength,
            new Quaternionf(visualTarget)
        );
    }

    @Override
    public void sync(
        boolean noAnimation,
        Vec3 baseGravityDirection, Vec3 currentGravityDirection,
        double baseGravityStrength, double currentGravityStrength,
        Quaternionf rotation
    ) {
        this.baseGravityDirection = baseGravityDirection;
        this.baseGravityStrength = baseGravityStrength;

        Direction serverDirection = Direction.getNearest(
            currentGravityDirection.x, currentGravityDirection.y, currentGravityDirection.z
        );

        if (GCUtil.isClientPlayer(entity)) {
            // The client player computes its own gravity from fields; only adopt
            // the server's direction on a PERSISTENT disagreement (true desync).
            // The server's field state lags the client by a couple of ticks, so
            // near 45-degree field regions single packets flip-flop — adopting
            // each one flipped the canonical frame back and forth (visible
            // twitching while standing near face boundaries).
            if (serverDirection != this.currGravityDirection) {
                if (++serverDirectionDisagreeStreak >= 3) {
                    this.currGravityDirection = serverDirection;
                    this.currGravityStrength = currentGravityStrength;
                    serverDirectionDisagreeStreak = 0;
                }
            }
            else {
                serverDirectionDisagreeStreak = 0;
            }
            return;
        }

        this.currGravityDirection = serverDirection;
        this.currGravityStrength = currentGravityStrength;

        // the visual frame chases the server-provided target smoothly
        if (this.syncedVisualTarget == null) {
            this.syncedVisualTarget = new Quaternionf();
        }
        this.syncedVisualTarget.set(rotation).normalize();

        if (noAnimation || !initialized) {
            // preserve WORLD velocity across the instant frame adoption: the
            // stored deltaMovement was expressed in the OLD frame (identity
            // for a freshly spawned entity whose spawn-packet velocity was
            // world-space); reinterpreting it in the new frame unconverted
            // sent remote projectiles flying the wrong way
            Quaternionf oldFrame = new Quaternionf(this.visualRotation);

            this.prevGravityDirection = serverDirection;
            this.prevGravityStrength = currentGravityStrength;
            this.initialized = true;
            this.visualRotation.set(this.syncedVisualTarget);
            this.prevVisualRotation.set(this.syncedVisualTarget);
            this.visualTarget.set(this.syncedVisualTarget);

            if (entity != null && !oldFrame.equals(this.visualRotation)
                && !(entity instanceof net.minecraft.world.entity.projectile.Projectile)) {
                // (projectiles excluded: their deltaMovement is WORLD-space —
                // raw position integration, no move() — re-expressing it here
                // rotated their real velocity)
                Vec3 world = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), oldFrame);
                entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(world, this.visualRotation));
            }
            updateBoundingBox();
        }
    }

    // ------------------------------------------------------------------
    // effect API (called by fields/plating/effects)
    // ------------------------------------------------------------------

    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        applyGravityDirectionEffect(direction, rotationParameters, priority, false, 1.0, true);
    }

    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary
    ) {
        applyGravityDirectionEffect(direction, rotationParameters, priority, secondary, 1.0, true);
    }

    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale
    ) {
        applyGravityDirectionEffect(direction, rotationParameters, priority, secondary, strengthScale, true);
    }

    /**
     * @param secondary     a supporting/blending contribution (e.g. the hidden
     *                      sideways bleed of a plate field): it participates in
     *                      blending when a PRIMARY field is also present, but a
     *                      tick with only secondary effects counts as no field at
     *                      all — standing on plain ground one block beside a plate
     *                      must not change gravity.
     * @param strengthScale per-effect FORCE multiplier (gradual-falloff
     *                      fields weaken with distance from the source, and a
     *                      source's configured gravity acceleration scales it
     *                      up or down). Scales only the pull strength — never
     *                      the orientation: an entity at the far edge of a
     *                      gradual field is still fully oriented by it, just
     *                      pulled weakly. Blended with the same weights as
     *                      the direction.
     * @param allowSurfaceAlign whether entities under this field may adopt
     *                      the surface they stand on as their "up"
     *                      (planet-walk snapping). When the dominant field
     *                      disallows it, gravity follows the raw field
     *                      vector only.
     */
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign
    ) {
        applyGravityDirectionEffect(
            direction, rotationParameters, priority, secondary, strengthScale,
            allowSurfaceAlign, null, null);
    }

    /**
     * Ship-aware variant: a field mounted on a Valkyrien Skies ship passes
     * its ship and the field direction in SHIPYARD coordinates (a block-grid
     * constant for plates/normalizers). While the dominant field is
     * ship-sourced, the entity's frame anchors to the ship — grounded,
     * jumping, or flying — because the ship-space direction can be
     * re-derived exactly from the live transform at any moment.
     */
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign,
        @Nullable org.valkyrienskies.core.api.ships.Ship sourceShip,
        @Nullable Vec3 shipLocalDown
    ) {
        applyGravityDirectionEffect(
            direction, rotationParameters, priority, secondary, strengthScale,
            allowSurfaceAlign, sourceShip, shipLocalDown, null, 1.0, null);
    }

    /**
     * Ship-aware variant that also reports the field's REGION in the source's
     * own block grid (shipyard coordinates for a ship-mounted source, world
     * coordinates otherwise). The region bounds the held-surface field
     * sustain: standing on a surface the field endorsed keeps the field alive
     * through effect dropouts only while the entity is still inside it —
     * walking off the plating releases.
     */
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign,
        @Nullable org.valkyrienskies.core.api.ships.Ship sourceShip,
        @Nullable Vec3 shipLocalDown,
        @Nullable AABB gridRegion
    ) {
        applyGravityDirectionEffect(
            direction, rotationParameters, priority, secondary, strengthScale,
            allowSurfaceAlign, sourceShip, shipLocalDown, null, 1.0, gridRegion);
    }

    /**
     * RADIAL ship-aware variant (gravity cores): the ship-space constant of
     * a radial field is its CENTER, not its direction — the pull direction
     * depends on where the entity stands, so a direction sampled once per
     * tick and held is a 20 Hz staircase to the render thread (the ship-core
     * circling jitter). Passing {@code shipLocalSourcePos} (shipyard
     * coordinates) lets both the tick target and the per-frame drawn-ship
     * alignment re-derive the exact radial direction from the live pose and
     * the live entity position. {@code radialSign} is +1 attracting, -1
     * repulsing.
     */
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign,
        @Nullable org.valkyrienskies.core.api.ships.Ship sourceShip,
        @Nullable Vec3 shipLocalDown,
        @Nullable Vec3 shipLocalSourcePos,
        double radialSign
    ) {
        applyGravityDirectionEffect(
            direction, rotationParameters, priority, secondary, strengthScale,
            allowSurfaceAlign, sourceShip, shipLocalDown, shipLocalSourcePos, radialSign, null);
    }

    /** Full variant: radial anchor AND grid region (see the overloads above). */
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign,
        @Nullable org.valkyrienskies.core.api.ships.Ship sourceShip,
        @Nullable Vec3 shipLocalDown,
        @Nullable Vec3 shipLocalSourcePos,
        double radialSign,
        @Nullable AABB gridRegion
    ) {
        applyGravityDirectionEffect(
            direction, rotationParameters, priority, secondary, strengthScale,
            allowSurfaceAlign, sourceShip, shipLocalDown, shipLocalSourcePos, radialSign,
            gridRegion, shipLocalSourcePos != null);
    }

    /**
     * Full variant with an explicit RADIAL flag: a source whose pull points
     * toward (or away from) a center rather than along a grid constant. A
     * radial pull has a tangential component everywhere on a planet's
     * faces, and the surface machinery reads such a field's endorsement of
     * candidate faces more strictly (see updateSurfaceProbe). World-space
     * cores pass {@code true} here; ship-mounted ones are radial by virtue
     * of their {@code shipLocalSourcePos}.
     */
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign,
        @Nullable org.valkyrienskies.core.api.ships.Ship sourceShip,
        @Nullable Vec3 shipLocalDown,
        @Nullable Vec3 shipLocalSourcePos,
        double radialSign,
        @Nullable AABB gridRegion,
        boolean radial
    ) {
        GravityDirEffect effect = new GravityDirEffect(
            direction, rotationParameters, priority, secondary, strengthScale,
            allowSurfaceAlign, sourceShip, shipLocalDown, shipLocalSourcePos, radialSign,
            gridRegion, radial
        );
        if (isFiringUpdateEvent) {
            tempEffects.add(effect);
        }
        else {
            delayApplyDirEffects.add(effect);
            dbgEffectsQueued++;
        }
    }

    public void applyGravityStrengthEffect(double strengthMultiplier) {
        if (isFiringUpdateEvent) {
            targetGravityStrength *= strengthMultiplier;
        }
        else {
            delayApplyStrengthEffect *= strengthMultiplier;
        }
    }

    // ------------------------------------------------------------------
    // direction change machinery (from the original working mod)
    // ------------------------------------------------------------------

    public void applyGravityDirectionChange(
        Direction oldGravity, Direction newGravity,
        RotationParameters rotationParameters, boolean isInitialization
    ) {
        if (!canChangeGravity()) {
            return;
        }

        updateBoundingBox();

        if (isInitialization) {
            return;
        }

        entity.fallDistance = 0;

        Vec3 relativeRotationCenter = getLocalRotationCenter(
            entity, oldGravity, newGravity, rotationParameters
        );
        Vec3 oldPos = entity.position();
        Vec3 oldLastTickPos = new Vec3(entity.xOld, entity.yOld, entity.zOld);
        Vec3 rotationCenter = oldPos.add(RotationUtil.vecPlayerToWorld(relativeRotationCenter, oldGravity));
        Vec3 newPos = rotationCenter.subtract(RotationUtil.vecPlayerToWorld(relativeRotationCenter, newGravity));
        Vec3 posTranslation = newPos.subtract(oldPos);
        Vec3 newLastTickPos = oldLastTickPos.add(posTranslation);

        if (!this.noPositionAdjust) {
            entity.setPos(newPos);
            entity.xo = newLastTickPos.x;
            entity.yo = newLastTickPos.y;
            entity.zo = newLastTickPos.z;
            entity.xOld = newLastTickPos.x;
            entity.yOld = newLastTickPos.y;
            entity.zOld = newLastTickPos.z;

            adjustEntityPosition(oldGravity, newGravity, entity.getBoundingBox());
        }

        // No camera animation and no yaw remap: the visual frame is continuous
        // across box snaps, and it is what interprets yaw and local velocity.

        if (entity instanceof Player) {
            // Players' local velocity is interpreted through the (continuous)
            // visual frame, so their world velocity is unaffected by the snap.
            return;
        }

        // Non-player entities use the legacy cardinal movement frame, so their
        // velocity must be handled across the snap like the original mod did.
        Vec3 realWorldVelocity = getRealWorldVelocity(entity, oldGravity);
        if (rotationParameters.rotateVelocity()) {
            // Rotate velocity with gravity, this will cause things to appear to take a sharp turn
            Vector3f worldSpaceVec = realWorldVelocity.toVector3f();
            worldSpaceVec.rotate(RotationUtil.getRotationBetween(oldGravity, newGravity));
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(new Vec3(worldSpaceVec), newGravity));
        }
        else {
            // Velocity will be conserved relative to the world, will result in more natural motion
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(realWorldVelocity, newGravity));
        }
    }

    // getDeltaMovement() does not return the actual velocity. It returns the velocity
    // plus acceleration. The real velocity is this tick position subtract last tick position.
    private static Vec3 getRealWorldVelocity(Entity entity, Direction prevGravityDirection) {
        if (entity.isControlledByLocalInstance()) {
            return new Vec3(
                entity.getX() - entity.xo,
                entity.getY() - entity.yo,
                entity.getZ() - entity.zo
            );
        }

        return RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), prevGravityDirection);
    }

    @NotNull
    private static Vec3 getLocalRotationCenter(
        Entity entity,
        Direction oldGravity, Direction newGravity, RotationParameters rotationParameters
    ) {
        if (entity instanceof EndCrystal) {
            //In the middle of the block below
            return new Vec3(0, -0.5, 0);
        }

        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        if (newGravity == oldGravity.getOpposite()) {
            // In the center of the hit-box
            return new Vec3(0, dimensions.height / 2, 0);
        }
        else {
            return Vec3.ZERO;
        }
    }

    // Adjust position to avoid suffocation in blocks when changing gravity
    private void adjustEntityPosition(Direction oldGravity, Direction newGravity, AABB entityBoundingBox) {
        if (!GravityConfig.adjustPositionAfterChangingGravity.get()) {
            return;
        }

        if (entity instanceof AreaEffectCloud || entity instanceof AbstractArrow || entity instanceof EndCrystal) {
            return;
        }

        // for example, if gravity changed from down to north, move up
        // if gravity changed from down to up, also move up
        Vec3 movingDirection = Vec3.atLowerCornerOf(oldGravity.getOpposite().getNormal());

        Iterable<VoxelShape> collisions = entity.level().getCollisions(
            entity,
            entityBoundingBox.inflate(-0.01) // shrink to avoid floating point error
        );
        AABB totalCollisionBox = null;
        for (VoxelShape collision : collisions) {
            if (!collision.isEmpty()) {
                AABB boundingBox = collision.bounds();
                if (totalCollisionBox == null) {
                    totalCollisionBox = boundingBox;
                }
                else {
                    totalCollisionBox = totalCollisionBox.minmax(boundingBox);
                }
            }
        }

        if (totalCollisionBox != null) {
            entity.setPos(entity.position().add(getPositionAdjustmentOffset(
                entityBoundingBox, totalCollisionBox, movingDirection
            )));
        }
    }

    private static Vec3 getPositionAdjustmentOffset(
        AABB entityBoundingBox, AABB nearbyCollisionUnion, Vec3 movingDirection
    ) {
        Direction nearestDir = Direction.getNearest(movingDirection.x, movingDirection.y, movingDirection.z);
        Direction.Axis axis = nearestDir.getAxis();
        double offset = 0;
        if (nearestDir.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            double pushing = nearbyCollisionUnion.max(axis);
            double pushed = entityBoundingBox.min(axis);
            if (pushing > pushed) {
                offset = pushing - pushed;
            }
        }
        else {
            double pushing = nearbyCollisionUnion.min(axis);
            double pushed = entityBoundingBox.max(axis);
            if (pushing < pushed) {
                offset = pushed - pushing;
            }
        }

        return new Vec3(nearestDir.step()).scale(offset);
    }

    private void updateBoundingBox() {
        if (entity != null) {
            // go through the vanilla (mixin-wrapped) path so there is a single
            // source of truth for the rotated bounding box
            entity.setBoundingBox(((net.camacraft.gravityunbound.mixin.EntityAccessor) entity).gc_makeBoundingBox());
        }
    }

    // ------------------------------------------------------------------
    // accessors
    // ------------------------------------------------------------------

    public double getBaseGravityStrength() {
        return baseGravityStrength;
    }

    public void setBaseGravityStrength(double strength) {
        if (!canChangeGravity()) {
            return;
        }

        baseGravityStrength = strength;
        needsSync = true;
    }

    /** The applied PHYSICS gravity direction (always cardinal). */
    public Direction getCurrGravityDirection() {
        return currGravityDirection;
    }

    /** Cardinal normal of the physics direction. */
    public Vec3 getCurrGravityDirectionVec() {
        return Vec3.atLowerCornerOf(currGravityDirection.getNormal());
    }

    /** The true (possibly arbitrary-angle) field vector, or the cardinal normal. */
    public Vec3 getTargetGravityVector() {
        return targetGravityVector != null ? targetGravityVector : getCurrGravityDirectionVec();
    }

    public double getCurrGravityStrength() {
        return currGravityStrength;
    }

    /**
     * The canonical world->player frame of the cardinal PHYSICS direction (box,
     * collision, movement axes). This is a shared instance — do not modify it.
     * Transform helpers recognize these instances and use exact cardinal math.
     */
    public Quaternionf getCurrentRotation() {
        return RotationUtil.getWorldRotationQuaternion(currGravityDirection);
    }

    /**
     * The continuous visual/aim frame (camera, model, look direction, player
     * movement). Converges exactly onto {@link #getCurrentRotation()} when the
     * field is cardinal. Do not modify the returned object.
     */
    public Quaternionf getVisualRotation() {
        return visualRotation;
    }

    /** Visual frame interpolated between the previous and current tick. */
    public Quaternionf getRenderRotation(float partialTick) {
        // must route through the two-arg variant: it carries the drawn-ship
        // alignment. This overload having its own inline slerp is exactly
        // why the CAMERA and the capsule debug lagged the (corrected) model
        // after round 49 — they were the two remaining single-arg callers.
        return getRenderRotation(partialTick, new Quaternionf());
    }

    /**
     * Allocation-free variant of {@link #getRenderRotation(float)} for render
     * hot paths: writes into {@code dest} and returns it. Render thread only.
     */
    public Quaternionf getRenderRotation(float partialTick, Quaternionf dest) {
        if (prevVisualRotation.equals(visualRotation)) {
            dest.set(visualRotation);
        }
        else {
            dest.set(prevVisualRotation);
            if (dest.dot(visualRotation) < 0.0f) {
                // q and -q are the same rotation; a slerp across hemispheres
                // sweeps the long way around
                dest.set(-dest.x, -dest.y, -dest.z, -dest.w);
            }
            dest.slerp(visualRotation, partialTick).normalize();
        }

        // SHIP ATTACHMENT, render half: SHIP-RELATIVE RECONSTRUCTION.
        // VS draws the body on the ship's RENDER transform (its per-frame
        // render-ride rewrites the interpolation base so the camera lands on
        // the drawn deck), while the two tick frames above belong to the two
        // most recent TICK poses — one tick behind the drawn pose, and
        // interpolated between poses the deck is no longer drawn at. Undo
        // that at the source: express both tick frames in the ship's own
        // coordinates (frame * pose — a rider's ship-relative orientation is
        // what actually stays put), interpolate THERE, and re-express the
        // result against the drawn pose. Standing still, the ship-relative
        // frame is constant and the render frame follows the drawn deck
        // exactly — swing and twist alike, whatever the spin axis, however
        // lumpy the network-fed tick poses are. The world interpolation is
        // blended in by the eased engagement weight so entering and leaving
        // a ship's field never snaps.
        // the engagement weight is a tick-rate quantity: interpolate it like
        // everything else the render pass draws, or the reconstruction's
        // share of the frame jumps at every tick boundary of the ease
        float weight = Mth.lerp(partialTick, attachWeightPrev, attachWeight);
        if (weight > 0.001f && attachRotValid
            && renderAttachShip instanceof org.valkyrienskies.core.api.ships.ClientShip attachedClientShip) {
            Quaternionf prevLocal = new Quaternionf(prevVisualRotation)
                .mul(new Quaternionf(attachRotPrev)).normalize();
            Quaternionf curLocal = new Quaternionf(visualRotation)
                .mul(new Quaternionf(attachRot)).normalize();
            if (prevLocal.dot(curLocal) < 0.0f) {
                prevLocal.set(-prevLocal.x, -prevLocal.y, -prevLocal.z, -prevLocal.w);
            }
            Quaternionf local = prevLocal.slerp(curLocal, partialTick).normalize();
            Quaternionf drawn = new Quaternionf(
                attachedClientShip.getRenderTransform().getShipToWorldRotation());
            Quaternionf exact = local.mul(drawn.conjugate()).normalize();
            if (exact.dot(dest) < 0.0f) {
                exact.set(-exact.x, -exact.y, -exact.z, -exact.w);
            }
            if (weight >= 0.999f) {
                dest.set(exact);
            }
            else {
                dest.slerp(exact, weight).normalize();
            }
        }

        // (No render-time radial re-alignment any more. The tick target of a
        // ship-mounted core is re-derived from the live transform every tick
        // and chased at high gain, and the ship-relative reconstruction
        // above carries it onto the drawn pose; the old per-frame pull of
        // the drawn up toward the exact radial direction was blended in and
        // out by a weight that stepped once per TICK — up to 0.15 of the
        // chase lag per step — which was the tick-rate stutter on every
        // landing, lift-off and field entry around a ship core.)
        return dest;
    }

    /** Fast-path check: PHYSICS gravity is exactly vanilla. */
    public boolean isDefault() {
        return currGravityDirection == Direction.DOWN;
    }

    /**
     * True when both physics and the visual frame are exactly vanilla — the
     * PHYSICS gate (capsule collision, movement transforms). Judged on the
     * current frame only: on a spinning ship's level deck the frame is
     * identity every tick after the twist unwind while the interpolation
     * start still carries the ship's tick rotation, and physics must stay
     * bit-vanilla there. Render code uses {@link #isRenderDefault()}.
     */
    public boolean isVisuallyDefault() {
        // |w|: q and -q are the same rotation, and long premul/mul chains can
        // legitimately converge onto the NEGATIVE identity — which would keep
        // capsule mode latched on forever in the plain world
        return currGravityDirection == Direction.DOWN
            && Math.abs(visualRotation.w()) >= 0.9999999f;
    }

    /**
     * True when nothing needs the render frame: physics is vanilla, the
     * interpolation start is vanilla too, and no ship reconstruction is
     * engaged. The RENDER gate (camera, model, nametag, eye position).
     */
    public boolean isRenderDefault() {
        return isVisuallyDefault()
            && Math.abs(prevVisualRotation.w()) >= 0.9999999f
            && attachWeight <= 0.001f
            && attachWeightPrev <= 0.001f;
    }

    /**
     * True while the drawn frame is rotating (this tick differs from last).
     * On a rotating ship the carry changes the frame every tick, so this
     * covers riding without an extra clause; a stationary plated ship must
     * NOT count as moving (the level renderer's frustum re-test hangs off
     * this every frame).
     */
    public boolean isVisuallyMoving() {
        return !prevVisualRotation.equals(visualRotation);
    }

    /**
     * Players under any non-default gravity collide as a gravity-aligned capsule
     * (see {@link net.camacraft.gravityunbound.util.CapsuleCollider}) — a hitbox that
     * genuinely rotates. Non-player entities keep the cardinal box system.
     */
    /**
     * Grounded as the FRAME sees it: capsule entities' vanilla onGround flag
     * is unreliable under rotated gravity (it tracks world-down collisions);
     * field sources must use this to decide grounded-vs-airborne behavior.
     */
    public boolean isGroundedInFrame() {
        return useCapsuleCollision() ? capsuleGrounded : entity.onGround();
    }

    public boolean useCapsuleCollision() {
        // ALL entities except projectiles: mobs, boats, modded vehicles
        // (planes), items — anything that collides through move() gets the
        // capsule + continuous frame under rotated gravity. Projectiles are
        // the sole exception: they are WORLD-frame (raw position
        // integration, raycast hit detection) and keep vanilla boxes.
        return !(entity instanceof net.minecraft.world.entity.projectile.Projectile)
            && !isVisuallyDefault();
    }

    /**
     * True while a committed face change is in flight or the frame is still
     * visibly chasing its target. Anti-creep pins and brakes must stand down
     * here: their input frame is half-rotated and the "creep" they remove is
     * the genuine transition momentum.
     */
    public boolean isSurfaceTransitioning() {
        return surfaceChangeCooldown > 0
            || angleBetween(visualRotation, visualTarget) > (float) Math.toRadians(3);
    }

    /** World-space up direction of the entity's visual frame (unit vector). */
    public Vec3 getUpVector() {
        return RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), visualRotation);
    }

    /**
     * The up direction gravity is CHASING right now: the adopted surface
     * normal while one is held (support-first), otherwise the raw field's up.
     * This — not the raw blended field — is the correct "which way is down
     * meant to be" reference for collision/grounding during transitions.
     */
    public Vec3 getEffectiveUpVector() {
        Vec3 field = getTargetGravityVector();
        Vec3 fieldNormalized = field.lengthSqr() > 1.0E-6
            ? field.normalize()
            : getCurrGravityDirectionVec();
        return effectiveTargetUp(fieldNormalized);
    }

    /**
     * When the visual frame has fully converged onto the canonical cardinal
     * frame, that direction; otherwise null. Non-player movement keeps the
     * original mod's exact cardinal code path when settled.
     */
    @Nullable
    public Direction getSettledCardinal() {
        Quaternionf canonical = RotationUtil.getWorldRotationQuaternion(currGravityDirection);
        return visualRotation.equals(canonical) ? currGravityDirection : null;
    }

    private boolean canChangeGravity() {
        return entity != null && EntityTags.canChangeGravity(entity);
    }

    public Direction getPrevGravityDirection() {
        return prevGravityDirection;
    }

    public Direction getBaseGravityDirection() {
        return Direction.getNearest(baseGravityDirection.x, baseGravityDirection.y, baseGravityDirection.z);
    }

    public void setBaseGravityDirection(Direction gravityDirection) {
        setBaseGravityDirection(Vec3.atLowerCornerOf(gravityDirection.getNormal()));
    }

    public void setBaseGravityDirection(Vec3 gravityDirection) {
        if (!canChangeGravity()) {
            return;
        }

        if (!baseGravityDirection.equals(gravityDirection)) {
            baseGravityDirection = gravityDirection;
            needsSync = true;
        }
    }

    public void reset() {
        baseGravityDirection = DOWN;
        baseGravityStrength = 1.0;
        needsSync = true;
    }


    /**
     * Snap the applied state without animation.
     * Used by {@link GravityChangerAPI#instantlySetClientBaseGravityDirection}.
     */
    public void forceApplyGravityChange() {
        prevGravityDirection = currGravityDirection;
        prevGravityStrength = currGravityStrength;
        computeVisualTarget(visualTarget);
        visualRotation.set(visualTarget);
        prevVisualRotation.set(visualTarget);
        updateBoundingBox();
    }

    private record GravityDirEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority,
        boolean secondary,
        double strengthScale,
        boolean allowSurfaceAlign,
        @Nullable org.valkyrienskies.core.api.ships.Ship sourceShip,
        @Nullable Vec3 shipLocalDown,
        @Nullable Vec3 shipLocalSourcePos,
        double radialSign,
        @Nullable AABB gridRegion,
        boolean radial
    ) {
    }

    // ------------------------------------------------------------------
    // zero-gravity pull for NON-LIVING entities (items, carts, TNT)
    // ------------------------------------------------------------------

    /**
     * In configured zero-g dimensions a field also accelerates NON-LIVING
     * entities (items, minecarts, TNT): their hardcoded gravity paths are
     * the ones a zero-g dimension suppresses, and the living-entity deficit
     * ({@link #applyFieldPullDeficit}) deliberately does not cover them.
     * One force per entity per tick however many sources claim it (the
     * capability's claim), scale-invariant, along the WORLD direction the
     * caller resolved (a plate's face direction, a core's radial pull).
     *
     * Applied on the CONTROLLING side — and, on the client, to every
     * non-living entity in the field, controlled or not. Non-living
     * remotes are client-PREDICTED physics objects: the client integrates
     * their velocity itself between the server's position packets, and
     * without the pull that prediction floated weightless until the next
     * packet — a dropped item on a level ship in zero-g hung in the air
     * for a second (vanilla's 20-tick item sync cadence) before jumping to
     * where the server had it. A pull the client applies identically keeps
     * the prediction on the server's path, exactly as the client already
     * computes such entities' frames from its own field sources.
     */
    public static void applyZeroGravityFieldForce(
        net.minecraft.world.level.Level world, Entity entity,
        GravityCapabilityImpl comp, Vec3 worldDown
    ) {
        if (entity instanceof LivingEntity) {
            return;
        }
        boolean applies = world.isClientSide()
            ? true   // client-predicted physics for every non-living entity
            : !(entity instanceof Player);
        if (!applies) {
            return;
        }
        String currentDim = world.dimension().location().toString();
        if (!GravityConfig.artificialGravityDimensions.get().contains(currentDim)) {
            return;
        }
        // Ad Astra dimensions: the compat layer already makes Ad Astra treat
        // fielded entities as Earth gravity, so their own gravity path works
        // again — this extra force would stack on top of it
        if (net.camacraft.gravityunbound.compat.AdAstraCompat.restoresGravityFor(entity)) {
            return;
        }
        if (!comp.tryClaimArtificialPull(world.getGameTime())) {
            return;
        }
        if (worldDown.lengthSqr() < 1.0E-8) {
            return;
        }
        Vec3 accel = worldDown.normalize()
            .scale(GravityConfig.artificialGravityAcceleration.get() * comp.getCurrGravityStrength());

        // projectiles are WORLD-frame (raw position integration, world-space
        // velocity): the pull adds to their velocity directly — never
        // through the frame transform, which would bend it by the frame
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            Vec3 velocity = entity.getDeltaMovement();
            if (velocity.dot(worldDown) < 3.0) {
                entity.setDeltaMovement(velocity.add(accel));
            }
            return;
        }

        Vec3 currentVel = GravityChangerAPI.getWorldVelocity(entity);
        // crude terminal velocity so stacked fields cannot accelerate indefinitely
        if (currentVel.dot(comp.getCurrGravityDirectionVec()) < 3.0) {
            GravityChangerAPI.setWorldVelocity(entity, currentVel.add(accel));
        }
    }
}

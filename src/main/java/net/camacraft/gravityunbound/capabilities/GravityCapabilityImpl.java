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
    // after leaving a field, keep its pull for a few ticks (jumping off a plate
    // must not instantly revert gravity mid-air)
    private static final int FIELD_GRACE_TICKS = 6;
    // after the ground probe stops hitting, keep the surface alignment for a
    // few ticks (jumps and probe flicker must not wobble the frame; the probe
    // re-acquires during a jump's descent, so this only needs to bridge the
    // ascent and apex)
    private static final int GROUND_NORMAL_GRACE_TICKS = 10;
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
        updateGravityStatus();
        applyGravityChange();
        applyGravityStrengthAttribute();
        maybeSnapFreshSpawn();
        updateSurfaceProbe();
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

        targetGravityVector = DOWN;
        targetGravityStrength = 1.0;
        currGravityStrength = 1.0;
        currGravityDirection = Direction.DOWN;
        applyGravityChange();
        applyGravityStrengthAttribute();
        advanceVisualRotation();
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
        visualRotation.set(target);
        prevVisualRotation.set(target);
        visualTarget.set(target);
        noAnimation = true;
        needsSync = true;
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
        if (normalVel > 0.05) {
            // jumping / being launched away from the surface
            shipAnchorPos = null;
            return;
        }
        Vec3 tangentialVel = worldVelocity.subtract(normal.scale(normalVel));
        if (tangentialVel.lengthSqr() > 0.2 * 0.2) {
            // a real push is in flight: let it play out
            shipAnchorPos = null;
            return;
        }

        if (shipAnchorPos == null || shipAnchorShipId != ship.getId()) {
            org.joml.Vector3d p = new org.joml.Vector3d(entity.getX(), entity.getY(), entity.getZ());
            ship.getTransform().getWorldToShipMatrix().transformPosition(p);
            shipAnchorPos = p;
            shipAnchorShipId = ship.getId();
            return;
        }

        org.joml.Vector3d w = new org.joml.Vector3d(shipAnchorPos);
        ship.getTransform().getShipToWorldMatrix().transformPosition(w);
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
        Vec3 inputTangentDir = null;
        if (held
            && entity instanceof LivingEntity living
            && (Math.abs(living.xxa) > 0.01 || Math.abs(living.zza) > 0.01)
            && entity.level().isClientSide() && entity.isControlledByLocalInstance()
        ) {
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
            Vec3 inputTangent = inputWorld.subtract(heldNormal.scale(inputWorld.dot(heldNormal)));
            if (inputTangent.lengthSqr() > 1.0E-6) {
                inputTangentDir = inputTangent.normalize();
            }
        }

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

        Vec3 normal = probeSurfaceNormal(feet.subtract(probeDown.scale(0.2)), probeDown, 0.2 + GROUND_PROBE_DEPTH);
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
                    : probeSurfaceNormal(feet.add(heldNormal.scale(0.2)), probeDir, 0.5);
                // field gate 0.2, not 0.35: near the BOTTOM of a plated wall a
                // large plated floor dominates the blend (many more floor
                // plates inside the blend window), tilting fieldUp mostly
                // floor-ward — 0.35 rejected legitimate wall adoptions there.
                // Unplated walls still contribute nothing and never pass.
                if (wall != null
                    && wall.dot(fieldUp) > 0.2
                    && wall.dot(fieldUp) > 0.5 * heldNormal.dot(fieldUp)
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
            Vec3 support = probeSurfaceNormal(feet.subtract(frameDown.scale(0.2)), frameDown, 0.2 + GROUND_PROBE_DEPTH);
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
            Vec3 wrap = probeSurfaceNormal(feet.subtract(heldNormal.scale(0.15)), tangentDir.scale(-1), 0.75);
            if (wrap != null
                && wrap.dot(fieldUp) > 0.35
                && wrap.dot(fieldUp) > 0.5 * heldNormal.dot(fieldUp)
                && wrap.dot(heldNormal) < 0.7
                && wrap.dot(tangentDir) > 0.1
            ) {
                adoptGroundNormal(wrap);
                return;
            }
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
            groundNormalGraceTicks = Math.min(groundNormalGraceTicks, 1);
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
        if (!useCapsuleCollision()) {
            return;
        }
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
     * Raycast against collision shapes and return the hit face's WORLD-space
     * normal (Valkyrien Skies raycasts hit ships natively in shipyard
     * coordinates; the face normal is transformed back), or null on a miss.
     */
    private @Nullable Vec3 probeSurfaceNormal(Vec3 from, Vec3 direction, double distance) {
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
        return normal;
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
        if (living.isFallFlying() || living.isNoGravity() || entity.isInWater() || entity.isInLava()) {
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
            float proportion = targetStableTicks >= 5
                ? 0.5f
                : Mth.lerp(Mth.clamp(angle / (float) Math.toRadians(3), 0.0f, 1.0f), 0.08f, 0.35f);
            float maxTurn = VISUAL_TURN_PER_TICK;
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
            if (controlled && !flying) {
                Vec3 world = RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), frameBefore);
                entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(world, visualRotation));
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
                currGravityDirection = vehicleComp.currGravityDirection;
                currGravityStrength = vehicleComp.currGravityStrength;
                targetGravityVector = vehicleComp.targetGravityVector;
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
            // just left a field (e.g. jumped off a plate): keep its pull briefly
            // instead of instantly reverting to base gravity mid-air
            fieldGraceTicks--;
            targetGravityVector = lastFieldVector;
        }

        snapPhysicsDirection();

        currGravityStrength = targetGravityStrength;
    }

    private @Nullable Vec3 lastFieldVector = null;
    // consecutive ticks the field consisted of secondary bleeds only
    private int secondaryOnlySustainTicks = 0;
    // one-tick visual-frame override from rail-constrained vehicles
    private @Nullable Quaternionf externalVisualOverride = null;
    // consecutive ticks the collision ground plane disagreed with the held face
    private int contactDisagreeTicks = 0;

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
            return;
        }

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
    }

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
        GravityDirEffect effect = new GravityDirEffect(
            direction, rotationParameters, priority, secondary, strengthScale, allowSurfaceAlign
        );
        if (isFiringUpdateEvent) {
            tempEffects.add(effect);
        }
        else {
            delayApplyDirEffects.add(effect);
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
        if (prevVisualRotation.equals(visualRotation)) {
            return visualRotation;
        }
        return new Quaternionf(prevVisualRotation).slerp(visualRotation, partialTick).normalize();
    }

    /**
     * Allocation-free variant of {@link #getRenderRotation(float)} for render
     * hot paths: writes into {@code dest} and returns it. Render thread only.
     */
    public Quaternionf getRenderRotation(float partialTick, Quaternionf dest) {
        if (prevVisualRotation.equals(visualRotation)) {
            return dest.set(visualRotation);
        }
        return dest.set(prevVisualRotation).slerp(visualRotation, partialTick).normalize();
    }

    /** Fast-path check: PHYSICS gravity is exactly vanilla. */
    public boolean isDefault() {
        return currGravityDirection == Direction.DOWN;
    }

    /** True when both physics and the visual frame are exactly vanilla. */
    public boolean isVisuallyDefault() {
        // |w|: q and -q are the same rotation, and long premul/mul chains can
        // legitimately converge onto the NEGATIVE identity — which would keep
        // capsule mode latched on forever in the plain world
        return currGravityDirection == Direction.DOWN
            && Math.abs(visualRotation.w()) >= 0.9999999f
            && Math.abs(prevVisualRotation.w()) >= 0.9999999f;
    }

    /** True while the visual frame is rotating (this tick differs from last). */
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
        boolean allowSurfaceAlign
    ) {
    }
}

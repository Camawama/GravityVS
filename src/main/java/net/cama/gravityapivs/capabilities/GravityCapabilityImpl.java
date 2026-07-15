package net.cama.gravityapivs.capabilities;

import java.util.ArrayList;
import java.util.List;

import net.cama.gravityapivs.EntityTags;
import net.cama.gravityapivs.RotationAnimation;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.api.RotationParameters;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.init.GravityMobEffects;
import net.cama.gravityapivs.item.GravityAnchorItem;
import net.cama.gravityapivs.mob_effect.GravityDirectionMobEffect;
import net.cama.gravityapivs.network.GravityNetwork;
import net.cama.gravityapivs.network.UpdateGravityCapabilityPacket;
import net.cama.gravityapivs.util.GCUtil;
import net.cama.gravityapivs.util.QuaternionUtil;
import net.cama.gravityapivs.util.RotationUtil;
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

    // hysteresis: a new cardinal must be this much better aligned (dot product)
    // with the field vector before the collision box snaps to it
    private static final double SNAP_HYSTERESIS_DOT = 0.10;
    // opposite flips additionally require a stable target for a few ticks
    private static final int OPPOSITE_FLIP_STABLE_TICKS = 3;
    // how fast the visual frame may rotate
    private static final float VISUAL_TURN_PER_TICK = (float) Math.toRadians(15);
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
    // the block face under the feet found by the ground probe; this — never a
    // per-tick contact normal — is what the frame aligns to
    private @Nullable Vec3 lastGroundNormal = null;
    private int groundNormalGraceTicks = 0;
    // commitment window after a face change: no further face changes until the
    // frame has finished rotating to the new face (kills every transition
    // ping-pong oscillator by construction)
    private int surfaceChangeCooldown = 0;
    private static final int SURFACE_CHANGE_COOLDOWN_TICKS = 8;

    // chase-target stability: a still target is converged on decisively, a
    // moving one is followed with smoothing
    private final Quaternionf lastChaseTarget = new Quaternionf();
    private int targetStableTicks = 0;

    // the client player only adopts the server's cardinal after a persistent
    // disagreement (single-packet flips near 45-degree field regions caused a
    // canonical-frame flip war)
    private int serverDirectionDisagreeStreak = 0;

    @Nullable RotationParameters currentRotationParameters = RotationParameters.getDefault();

    // Only used on client, not synchronized.
    @Nullable
    public RotationAnimation animation;

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
        if (entity.level().isClientSide()) {
            animation = new RotationAnimation();
        }
        else {
            animation = null;
        }
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

        updateGravityStatus();
        applyGravityChange();
        updateSurfaceProbe();
        advanceVisualRotation();
        applyTransitionPull();
        applyStaticFriction();

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

        targetGravityVector = DOWN;
        targetGravityStrength = 1.0;
        currGravityStrength = 1.0;
        currGravityDirection = Direction.DOWN;
        applyGravityChange();
        advanceVisualRotation();
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
        if (!(entity instanceof Player) || shouldAcceptServerSync()) {
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
            return;
        }

        if (surfaceChangeCooldown > 0) {
            surfaceChangeCooldown--;
        }

        Vec3 fieldDown = targetGravityVector != null && targetGravityVector.lengthSqr() > 1.0E-6
            ? targetGravityVector.normalize()
            : getCurrGravityDirectionVec();
        Vec3 fieldUp = fieldDown.scale(-1);

        // support-first: a held surface is only released when the field
        // actively opposes it (repulsion), not when merely perpendicular
        boolean held = lastGroundNormal != null && lastGroundNormal.dot(fieldUp) > -0.1;
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
        Vec3 tangent = held ? moved.subtract(heldNormal.scale(moved.dot(heldNormal))) : Vec3.ZERO;
        double tangentSpeed = tangent.length();
        Vec3 tangentDir = tangentSpeed > 1.0E-6 ? tangent.scale(1.0 / tangentSpeed) : Vec3.ZERO;

        Vec3 normal = probeSurfaceNormal(feet.subtract(probeDown.scale(0.2)), probeDown, 0.2 + GROUND_PROBE_DEPTH);
        if (normal != null && normal.dot(fieldUp) > -0.1) {
            adoptGroundNormal(normal);

            // CONCAVE corner (walking on a plated floor into a plated wall, or
            // up a plated wall into a plated ceiling): the held normal keeps
            // the frame on the current face forever, so a wall whose field
            // wants to be our new floor must be adopted explicitly — cast a
            // short ray along the movement; a blocking face that the FIELD
            // accepts as up (an unplated wall never passes this gate, because
            // the blend has no component toward it) becomes the new surface.
            if (held && tangentSpeed > 0.02) {
                Vec3 wall = probeSurfaceNormal(feet.add(heldNormal.scale(0.2)), tangentDir, 0.5);
                if (wall != null
                    && wall.dot(fieldUp) > 0.35
                    && wall.dot(heldNormal) < 0.7
                    && wall.dot(tangentDir) < -0.5
                ) {
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
            if (support != null && support.dot(frameUp) > 0.35 && support.dot(fieldUp) > -0.1) {
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
        if (held && tangentSpeed > 0.02) {
            Vec3 wrap = probeSurfaceNormal(feet.subtract(heldNormal.scale(0.15)), tangentDir.scale(-1), 0.75);
            if (wrap != null
                && wrap.dot(fieldUp) > 0.35
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

        if (groundNormalGraceTicks > 0) {
            groundNormalGraceTicks--;
        }
        else {
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
        if (lastGroundNormal != null && lastGroundNormal.dot(normal) < 0.99) {
            if (surfaceChangeCooldown > 0) {
                // mid-transition: keep the committed face, just keep it alive
                groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;
                return;
            }
            surfaceChangeCooldown = SURFACE_CHANGE_COOLDOWN_TICKS;
        }
        lastGroundNormal = normal;
        groundNormalGraceTicks = GROUND_NORMAL_GRACE_TICKS;
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
        if (lastGroundNormal != null && lastGroundNormal.dot(targetUp) > -0.1) {
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

        // 0.08 is the standard living-entity gravity that travel() applies
        Vec3 correction = targetDown.subtract(frameDown).scale(0.08 * currGravityStrength);
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
        if (!useCapsuleCollision() || !capsuleGrounded) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
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

    /**
     * Moves the visual/aim frame toward the frame requested by the field vector,
     * rate-limited so it is continuous. Converges exactly onto the canonical
     * cardinal frame when the field is cardinal.
     */
    private void advanceVisualRotation() {
        prevVisualRotation.set(visualRotation);

        if (syncedVisualTarget != null && shouldAcceptServerSync()) {
            visualTarget.set(syncedVisualTarget);
        }
        else {
            computeVisualTarget(visualTarget);
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
            float step = Math.min(angle * proportion, VISUAL_TURN_PER_TICK);
            visualRotation.slerp(visualTarget, step / angle).normalize();
        }

        normalizeTwist();
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
        if (!(entity instanceof Player) || shouldAcceptServerSync()) {
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

        if (!(entity instanceof Player)) {
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
                    this.applyGravityDirectionEffect(
                        Vec3.atLowerCornerOf(currGravityDirection.getOpposite().getNormal()),
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

    private void resolveGravityTarget() {
        if (tempEffects.isEmpty()) {
            currentRotationParameters = RotationParameters.getDefault();
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
        RotationParameters bestParams = null;
        double bestParamPriority = -Double.MAX_VALUE;

        for (GravityDirEffect effect : tempEffects) {
            if (effect.priority >= maxPriority - BLEND_RANGE) {
                double weight = 1.0 - (maxPriority - effect.priority) / BLEND_RANGE;
                weight = Math.max(0, weight);

                accumulatedGravity = accumulatedGravity.add(effect.direction.normalize().scale(weight));
                totalWeight += weight;

                if (effect.priority > bestParamPriority) {
                    bestParamPriority = effect.priority;
                    if (effect.rotationParameters != null) {
                        bestParams = effect.rotationParameters;
                    }
                }
            }
        }

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

        double candidateDot = target.dot(Vec3.atLowerCornerOf(candidate.getNormal()));
        double currentDot = target.dot(Vec3.atLowerCornerOf(currGravityDirection.getNormal()));
        if (candidateDot < currentDot + SNAP_HYSTERESIS_DOT) {
            return;
        }

        if (candidate == currGravityDirection.getOpposite()) {
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
            if (entity instanceof Player) {
                // capsule players: position is a continuous feet point and the
                // frame is continuous; the cardinal change is only a reference
                // update, so no repositioning/velocity machinery is needed
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
            this.prevGravityDirection = serverDirection;
            this.prevGravityStrength = currentGravityStrength;
            this.initialized = true;
            this.visualRotation.set(this.syncedVisualTarget);
            this.prevVisualRotation.set(this.syncedVisualTarget);
            this.visualTarget.set(this.syncedVisualTarget);
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
        GravityDirEffect effect = new GravityDirEffect(direction, rotationParameters, priority);
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
            entity.setBoundingBox(((net.cama.gravityapivs.mixin.EntityAccessor) entity).gc_makeBoundingBox());
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

    /** Fast-path check: PHYSICS gravity is exactly vanilla. */
    public boolean isDefault() {
        return currGravityDirection == Direction.DOWN;
    }

    /** True when both physics and the visual frame are exactly vanilla. */
    public boolean isVisuallyDefault() {
        return currGravityDirection == Direction.DOWN
            && visualRotation.w() >= 0.9999999f
            && prevVisualRotation.w() >= 0.9999999f;
    }

    /** True while the visual frame is rotating (this tick differs from last). */
    public boolean isVisuallyMoving() {
        return !prevVisualRotation.equals(visualRotation);
    }

    /**
     * Players under any non-default gravity collide as a gravity-aligned capsule
     * (see {@link net.cama.gravityapivs.util.CapsuleCollider}) — a hitbox that
     * genuinely rotates. Non-player entities keep the cardinal box system.
     */
    public boolean useCapsuleCollision() {
        return entity instanceof Player && !isVisuallyDefault();
    }

    /** World-space up direction of the entity's visual frame (unit vector). */
    public Vec3 getUpVector() {
        return RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), visualRotation);
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

    public RotationAnimation getRotationAnimation() {
        return animation;
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
        double priority
    ) {
    }
}

package net.camacraft.gravityunbound.api;

import net.camacraft.gravityunbound.EntityTags;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import net.camacraft.gravityunbound.capabilities.GravityCapabilities;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.capabilities.IGravityCapability;
import net.camacraft.gravityunbound.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public abstract class GravityChangerAPI {
    private static final Quaternionf IDENTITY = new Quaternionf();

    /**
     * Returns the applied gravity direction, snapped to the nearest cardinal.
     * For arbitrary-angle transforms use {@link #getGravityRotation(Entity)}.
     */
    public static Direction getGravityDirection(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return Direction.DOWN;
        }

        return comp.getCurrGravityDirection();
    }

    public static Vec3 getGravityDirectionVec(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return GravityCapabilityImpl.DOWN;
        }

        return comp.getCurrGravityDirectionVec();
    }

    /**
     * The entity's cardinal PHYSICS frame (collision box, movement axes).
     * Identity when gravity is default. Do not modify the returned quaternion.
     */
    public static Quaternionf getGravityRotation(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return IDENTITY;
        }
        return comp.getCurrentRotation();
    }

    /**
     * The frame the entity MOVES in: players use the continuous visual frame
     * (capsule collision), other entities the cardinal physics frame.
     * Do not modify the returned quaternion.
     */
    public static Quaternionf getMovementRotation(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return IDENTITY;
        }
        return entity instanceof net.minecraft.world.entity.player.Player
            ? comp.getVisualRotation() : comp.getCurrentRotation();
    }

    /**
     * The entity's continuous visual/aim frame (camera, model, look direction,
     * player movement). Follows the true field vector at arbitrary angles and
     * converges exactly onto the physics frame when the field is cardinal.
     * Do not modify the returned quaternion.
     */
    public static Quaternionf getAimRotation(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return IDENTITY;
        }
        return comp.getVisualRotation();
    }

    /**
     * True when the entity's PHYSICS gravity is exactly vanilla; fast path for
     * collision/box mixins.
     */
    public static boolean isGravityDefault(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        return comp == null || comp.isDefault();
    }

    /**
     * True when the entity's visual/aim frame is exactly vanilla; fast path for
     * look/aim/camera mixins.
     */
    public static boolean isAimDefault(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        return comp == null || comp.isVisuallyDefault();
    }

    public static double getGravityStrength(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return 1.0;
        }
        return comp.getCurrGravityStrength();
    }

    public static double getBaseGravityStrength(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return 1.0;
        }
        return comp.getBaseGravityStrength();
    }

    public static void setBaseGravityStrength(Entity entity, double strength) {
        GravityCapabilityImpl component = getGravityComponentOrNull(entity);
        if (component != null) {
            component.setBaseGravityStrength(strength);
        }
    }

    public static void resetGravity(Entity entity) {
        if (!EntityTags.canChangeGravity(entity)) {return;}

        GravityCapabilityImpl component = getGravityComponentOrNull(entity);
        if (component != null) {
            component.reset();
        }
    }

    /**
     * Returns the main gravity direction for the given entity
     * This may not be the applied gravity direction for the player, see GravityChangerAPI#getAppliedGravityDirection
     */
    public static Direction getBaseGravityDirection(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return Direction.DOWN;
        }
        return comp.getBaseGravityDirection();
    }

    public static void setBaseGravityDirection(
        Entity entity, Direction gravityDirection
    ) {
        setBaseGravityDirection(entity, Vec3.atLowerCornerOf(gravityDirection.getNormal()));
    }

    public static void setBaseGravityDirection(
        Entity entity, Vec3 gravityDirection
    ) {
        GravityCapabilityImpl component = getGravityComponentOrNull(entity);
        if (component != null) {
            component.setBaseGravityDirection(gravityDirection);
        }
    }

    @Nullable

    /**
     * Instantly set gravity direction on client side without performing animation.
     * Not needed in normal cases.
     * (Used by iPortal)
     */
    public static void instantlySetClientBaseGravityDirection(Entity entity, Direction direction) {
        instantlySetClientBaseGravityDirection(entity, Vec3.atLowerCornerOf(direction.getNormal()));
    }

    public static void instantlySetClientBaseGravityDirection(Entity entity, Vec3 direction) {
        Validate.isTrue(entity.level().isClientSide(), "should only be used on client");

        GravityCapabilityImpl component = getGravityComponentOrNull(entity);
        if (component == null) {
            return;
        }

        component.setBaseGravityDirection(direction);
        component.updateGravityStatus();
        component.forceApplyGravityChange();
    }

    /**
     * @deprecated may return a detached dummy when the entity has no capability;
     * prefer {@link #getGravityComponentOrNull(Entity)}.
     */
    @Deprecated
    public static GravityCapabilityImpl getGravityComponent(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        return comp != null ? comp : new GravityCapabilityImpl();
    }

    @Nullable
    public static GravityCapabilityImpl getGravityComponentOrNull(Entity entity) {
        if (entity == null) {
            return null;
        }
        IGravityCapability cap = entity.getCapability(GravityCapabilities.GRAVITY).orElse(null);
        return cap instanceof GravityCapabilityImpl impl ? impl : null;
    }

    /**
     * @deprecated identical to {@link #getGravityComponentOrNull(Entity)}.
     */
    @Deprecated
    public static @Nullable GravityCapabilityImpl getGravityComponentEarly(Entity entity) {
        return getGravityComponentOrNull(entity);
    }

    /**
     * World-space up direction of the entity's continuous visual frame (unit
     * vector; {@code (0, 1, 0)} under default gravity). This is the frame the
     * entity moves, collides and looks in, so it is the "which way is down"
     * every other mod should consult for falls, landings and vertical speed —
     * FullStop's kinetic damage measures its impacts against it.
     */
    public static Vec3 getUpVector(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null || comp.isVisuallyDefault()) {
            return WORLD_UP;
        }
        return comp.getUpVector();
    }

    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);

    /**
     * World-space unit direction the entity's gravity pulls along right now:
     * the continuous field vector when one is known, else the visual frame's
     * down (on a remote client the frame is synced and tracks the server's
     * pull), else the cardinal. The one source projectiles integrate their
     * own gravity against (arrows, throwables, the fishing bobber) — the old
     * per-mixin fallback used the CARDINAL frame's down, so a remote client
     * pulled every projectile toward a snapped axis while the server pulled
     * along the true field, and the per-tick corrections read as a stutter.
     */
    public static Vec3 getFieldPullDirection(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null) {
            return GravityCapabilityImpl.DOWN;
        }
        Vec3 field = comp.getTargetGravityVector();
        if (field.lengthSqr() > 1.0E-6) {
            return field.normalize();
        }
        if (!comp.isVisuallyDefault()) {
            return RotationUtil.vecPlayerToWorld(new Vec3(0, -1, 0), comp.getVisualRotation());
        }
        return comp.getCurrGravityDirectionVec();
    }

    /**
     * Rotates a vector stored in the entity's {@code deltaMovement} convention
     * into the world, with the EXACT convention {@code Entity.move} applies
     * (see EntityMixin's HEAD transform): players and any entity whose frame
     * is mid-motion use the continuous visual frame; a non-player settled on
     * a cardinal uses the original mod's entity convention, which differs
     * from the player convention for UP gravity by a half turn about the
     * vertical. Reading a settled mob's velocity through the player
     * convention mirrored its horizontal motion.
     */
    public static Vec3 movementToWorld(Entity entity, Vec3 localVelocity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        // projectiles are WORLD-frame throughout (see EntityMixin's move transforms)
        if (comp == null || entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            return localVelocity;
        }
        if (!(entity instanceof net.minecraft.world.entity.player.Player)) {
            Direction settled = comp.getSettledCardinal();
            if (settled != null) {
                return RotationUtil.vecEntityToWorld(localVelocity, settled);
            }
        }
        return RotationUtil.vecPlayerToWorld(localVelocity, comp.getVisualRotation());
    }

    /** Exact inverse of {@link #movementToWorld}: a world vector into the entity's {@code deltaMovement} convention. */
    public static Vec3 worldToMovement(Entity entity, Vec3 worldVelocity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp == null || entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            return worldVelocity;
        }
        if (!(entity instanceof net.minecraft.world.entity.player.Player)) {
            Direction settled = comp.getSettledCardinal();
            if (settled != null) {
                return RotationUtil.vecWorldToEntity(worldVelocity, settled);
            }
        }
        return RotationUtil.vecWorldToPlayer(worldVelocity, comp.getVisualRotation());
    }

    /**
     * Returns the world relative velocity for the given entity: its
     * {@code deltaMovement} rotated with the convention {@code Entity.move}
     * applies to it ({@link #movementToWorld}).
     */
    public static Vec3 getWorldVelocity(Entity entity) {
        return movementToWorld(entity, entity.getDeltaMovement());
    }

    /**
     * Sets the world relative velocity for the given entity.
     */
    public static void setWorldVelocity(Entity entity, Vec3 worldVelocity) {
        entity.setDeltaMovement(worldToMovement(entity, worldVelocity));
    }

    /**
     * Returns eye position offset from feet position for the given entity
     */
    public static Vec3 getEyeOffset(Entity entity) {
        return RotationUtil.vecPlayerToWorld(0, (double) entity.getEyeHeight(), 0, getGravityRotation(entity));
    }

    public static boolean canChangeGravity(Entity entity) {
        return EntityTags.canChangeGravity(entity);
    }

    /**
     * Keeps the surface the entity currently stands on (planet-walk snap)
     * held for another grace window, and its field alive, regardless of
     * what the ground probes find this tick. For mechanics that hold a
     * player against a wall in mid-air (wall clinging): without this the
     * hold lapses during the cling and gravity falls back to the raw
     * field. Call once per tick while the hold should persist; a no-op when
     * no surface is held.
     */
    public static void sustainHeldSurface(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentOrNull(entity);
        if (comp != null) {
            comp.sustainHeldSurface();
        }
    }

}

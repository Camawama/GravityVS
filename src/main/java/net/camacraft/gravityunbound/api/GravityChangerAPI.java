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
     * Returns the world relative velocity for the given entity.
     * Local velocity is interpreted through the visual/aim frame (which equals
     * the physics frame whenever the field is cardinal).
     */
    public static Vec3 getWorldVelocity(Entity entity) {
        return RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), getAimRotation(entity));
    }

    /**
     * Sets the world relative velocity for the given entity.
     */
    public static void setWorldVelocity(Entity entity, Vec3 worldVelocity) {
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(worldVelocity, getAimRotation(entity)));
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

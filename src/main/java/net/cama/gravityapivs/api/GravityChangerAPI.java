package net.cama.gravityapivs.api;

import net.cama.gravityapivs.EntityTags;
import net.cama.gravityapivs.RotationAnimation;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import net.cama.gravityapivs.capabilities.GravityCapabilities;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import net.cama.gravityapivs.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public abstract class GravityChangerAPI {
    /**
     * Returns the applied gravity direction for the given entity
     */
    public static Direction getGravityDirection(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp == null) {
            return Direction.DOWN;
        }
        
        return comp.getCurrGravityDirection();
    }
    
    public static double getGravityStrength(Entity entity) {
        return getGravityComponent(entity).getCurrGravityStrength();
    }
    
    public static double getBaseGravityStrength(Entity entity) {
        return getGravityComponent(entity).getBaseGravityStrength();
    }
    
    public static void setBaseGravityStrength(Entity entity, double strength) {
        GravityCapabilityImpl component = getGravityComponent(entity);
        
        component.setBaseGravityStrength(strength);
    }
    
    public static void resetGravity(Entity entity) {
        if (!EntityTags.canChangeGravity(entity)) {return;}
        
        getGravityComponent(entity).reset();
    }
    
    /**
     * Returns the main gravity direction for the given entity
     * This may not be the applied gravity direction for the player, see GravityChangerAPI#getAppliedGravityDirection
     */
    public static Direction getBaseGravityDirection(Entity entity) {
        return getGravityComponent(entity).getBaseGravityDirection();
    }
    
    public static void setBaseGravityDirection(
        Entity entity, Direction gravityDirection
    ) {
        GravityCapabilityImpl component = getGravityComponent(entity);
        component.setBaseGravityDirection(gravityDirection);
    }
    
    @Nullable
    @OnlyIn(Dist.CLIENT)
    public static RotationAnimation getRotationAnimation(Entity entity) {
        return getGravityComponent(entity).getRotationAnimation();
    }
    
    /**
     * Instantly set gravity direction on client side without performing animation.
     * Not needed in normal cases.
     * (Used by iPortal)
     */
    public static void instantlySetClientBaseGravityDirection(Entity entity, Direction direction) {
        Validate.isTrue(entity.level().isClientSide(), "should only be used on client");
        
        GravityCapabilityImpl component = getGravityComponent(entity);
        
        component.setBaseGravityDirection(direction);
        
        component.updateGravityStatus(false);
        
        component.forceApplyGravityChange();
    }
    
    public static GravityCapabilityImpl getGravityComponent(Entity entity) {
    	GravityCapabilityImpl cap = (GravityCapabilityImpl) entity.getCapability(GravityCapabilities.GRAVITY).orElse(new GravityCapabilityImpl());
        return cap;
    }
    
    public static @Nullable GravityCapabilityImpl getGravityComponentEarly(Entity entity) {
        return getGravityComponent(entity);
    }
    
    /**
     * Returns the world relative velocity for the given entity
     * Using minecraft's methods to get the velocity will return entity local velocity
     */
    public static Vec3 getWorldVelocity(Entity entity) {
        return RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), getGravityDirection(entity));
    }
    
    /**
     * Sets the world relative velocity for the given player
     * Using minecraft's methods to set the velocity of an entity will set player relative velocity
     */
    public static void setWorldVelocity(Entity entity, Vec3 worldVelocity) {
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(worldVelocity, getGravityDirection(entity)));
    }
    
    /**
     * Returns eye position offset from feet position for the given entity
     */
    public static Vec3 getEyeOffset(Entity entity) {
        return RotationUtil.vecPlayerToWorld(0, (double) entity.getEyeHeight(), 0, getGravityDirection(entity));
    }
    
    public static boolean canChangeGravity(Entity entity) {
        return EntityTags.canChangeGravity(entity);
    }
    
}

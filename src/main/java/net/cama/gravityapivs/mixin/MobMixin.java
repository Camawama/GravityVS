package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.api.GravityPathNavigation;
import net.cama.gravityapivs.util.RotationUtil;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(Mob.class)
public abstract class MobMixin {
	@Shadow
	protected PathNavigation navigation;
    
    @Inject(at = @At("HEAD"), method = "tick", cancellable = true)
    private void tick(CallbackInfo ci)
    {
    	Mob mob = Mob.class.cast(this);
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(mob);
    	if(gravityDirection != Direction.DOWN)
    	{
			if(!(this.navigation instanceof GravityPathNavigation))
			{
				if(this.navigation instanceof GroundPathNavigation)
				{
	    			this.navigation = new GravityPathNavigation(mob, mob.level);
				}
			}
    	}
    	else if(this.navigation instanceof GravityPathNavigation)
    	{
    		this.navigation = this.createNavigation(mob.level);
    	}
    }
    
    @Shadow
    protected PathNavigation createNavigation(Level p_21480_)
    {
    	throw new IllegalStateException();
    }
    
    @WrapOperation(
        method = "doHurtTarget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;getYRot()F"
        )
    )
    private float wrapOperation_tryAttack_getYaw_0(Mob attacker, Operation<Float> original, Entity target) {
        Quaternionf targetRotation = GravityChangerAPI.getAimRotation(target);
        Quaternionf attackerRotation = GravityChangerAPI.getAimRotation(attacker);
        float localYaw = original.call(attacker);
        if (targetRotation.equals(attackerRotation)) {
            return localYaw;
        }

        // The mob's yaw is in its own LOCAL frame; the knockback direction must be expressed
        // in the TARGET's frame: local rot -> look vector -> world -> target frame -> rot.
        Vec3 worldLook = RotationUtil.vecPlayerToWorld(
            RotationUtil.rotToVec(localYaw, attacker.getXRot()), attackerRotation
        );
        return RotationUtil.vecToRot(RotationUtil.vecWorldToPlayer(worldLook, targetRotation)).x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_lookAtEntity_getEyeY_0(LivingEntity livingEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(livingEntity);
        if (gravityDirection == Direction.DOWN) {
            return livingEntity.getEyeY();
        }
        
        return livingEntity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_lookAtEntity_getX_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getX();
        }
        
        return entity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_lookAtEntity_getZ_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getZ();
        }
        
        return entity.getEyePosition().z;
    }
}

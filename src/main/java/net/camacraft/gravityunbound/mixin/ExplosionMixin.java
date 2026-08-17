package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Explosion.class, priority = 1001)
public abstract class ExplosionMixin {
    @Redirect(
        method = "Lnet/minecraft/world/level/Explosion;explode()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_collectBlocksAndDamageEntities_getEyeY_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getEyeY();
        }
        
        return entity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/level/Explosion;explode()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_collectBlocksAndDamageEntities_getX_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getX();
        }
        
        return entity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/level/Explosion;explode()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_collectBlocksAndDamageEntities_getZ_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getZ();
        }
        
        return entity.getEyePosition().z;
    }
    
    @WrapOperation(
        method = "explode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;",
            ordinal = 0
        )
    )
    private Vec3 wrapOperation_collectBlocksAndDamageEntities_getVelocity_0(Entity entity, Operation<Vec3> original) {
        // Use the movement frame (players: visual quaternion, mobs: cardinal) for the
        // local->world knockback round-trip.
        if (GravityChangerAPI.isAimDefault(entity)) {
            return original.call(entity);
        }

        return RotationUtil.vecPlayerToWorld(original.call(entity), GravityChangerAPI.getMovementRotation(entity));
    }
    
    @WrapOperation(
        method = "explode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
            ordinal = 0
        )
    )
    private void wrapOperation_collectBlocksAndDamageEntities_setVelocity_0(Entity entity, Vec3 vec3d, Operation<Void> original) {
        // World knockback -> local velocity via the movement frame (matches the wrap above).
        if (GravityChangerAPI.isAimDefault(entity)) {
            original.call(entity, vec3d);
            return;
        }

        original.call(entity, RotationUtil.vecWorldToPlayer(vec3d, GravityChangerAPI.getMovementRotation(entity)));
    }
}

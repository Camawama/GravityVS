package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.phys.Vec3;

@Mixin(SnowGolem.class)
public abstract class SnowGolemMixin {
    @Redirect(
        method = "Lnet/minecraft/world/entity/animal/SnowGolem;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_attack_getX_0(LivingEntity target) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(target);
        if (gravityDirection == Direction.DOWN) {
            return target.getX();
        }
        
        return target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getEyeHeight() - 1.100000023841858D, 0.0D, gravityDirection)).x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/animal/SnowGolem;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_attack_getEyeY_0(LivingEntity target) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(target);
        if (gravityDirection == Direction.DOWN) {
            return target.getEyeY();
        }
        
        return target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getEyeHeight() - 1.100000023841858D, 0.0D, gravityDirection)).y + 1.100000023841858D;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/animal/SnowGolem;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_attack_getZ_0(LivingEntity target) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(target);
        if (gravityDirection == Direction.DOWN) {
            return target.getZ();
        }
        
        return target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getEyeHeight() - 1.100000023841858D, 0.0D, gravityDirection)).z;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/animal/SnowGolem;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Math;sqrt(D)D"
        )
    )
    private double redirect_attack_sqrt_0(double value, LivingEntity target, float pullProgress,
                                          @Local(ordinal = 1) double dx, @Local(ordinal = 2) double dy, @Local(ordinal = 3) double dz) {
        // SnowGolem locals: d0 = aim eye Y (ordinal 0), d1 = dx, d2 = dy, d3 = dz.
        // The drop-compensation distance must follow the SHOOTER's gravity, not the target's.
        Direction shooterGravity = GravityChangerAPI.getGravityDirection((SnowGolem) (Object) this);
        if (shooterGravity == Direction.DOWN) {
            return Math.sqrt(value);
        }

        // Distance in the plane perpendicular to the shooter's gravity axis.
        Vec3 local = RotationUtil.vecWorldToPlayer(new Vec3(dx, dy, dz), shooterGravity);
        return Math.sqrt(local.x * local.x + local.z * local.z);
    }
}

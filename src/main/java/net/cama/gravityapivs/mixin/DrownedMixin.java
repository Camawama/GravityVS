package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.phys.Vec3;

@Mixin(Drowned.class)
public abstract class DrownedMixin {
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/Drowned;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
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
        
        return target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getBbHeight() * 0.3333333333333333D, 0.0D, gravityDirection)).x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/Drowned;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getY(D)D",
            ordinal = 0
        )
    )
    private double redirect_attack_getBodyY_0(LivingEntity target, double heightScale) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(target);
        if (gravityDirection == Direction.DOWN) {
            return target.getY(heightScale);
        }
        
        return target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getBbHeight() * 0.3333333333333333D, 0.0D, gravityDirection)).y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/Drowned;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
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
        
        return target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getBbHeight() * 0.3333333333333333D, 0.0D, gravityDirection)).z;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/Drowned;performRangedAttack(Lnet/minecraft/world/entity/LivingEntity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Math;sqrt(D)D"
        )
    )
    private double redirect_attack_sqrt_0(double value, LivingEntity target, float pullProgress,
                                          @Local(ordinal = 0) double dx, @Local(ordinal = 1) double dy, @Local(ordinal = 2) double dz) {
        // The drop-compensation distance must follow the SHOOTER's gravity, not the target's.
        Direction shooterGravity = GravityChangerAPI.getGravityDirection((Drowned) (Object) this);
        if (shooterGravity == Direction.DOWN) {
            return Math.sqrt(value);
        }

        // Distance in the plane perpendicular to the shooter's gravity axis.
        Vec3 local = RotationUtil.vecWorldToPlayer(new Vec3(dx, dy, dz), shooterGravity);
        return Math.sqrt(local.x * local.x + local.z * local.z);
    }
}

package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

@Mixin(Piglin.class)
public abstract class PiglinMixin implements CrossbowAttackMob {
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/piglin/Piglin;shootCrossbowProjectile(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/projectile/Projectile;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/piglin/Piglin;shootCrossbowProjectile(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/projectile/Projectile;FF)V",
            ordinal = 0
        )
    )
    private void redirect_shoot_shoot_0(Piglin piglinEntity, LivingEntity entity, LivingEntity target, Projectile projectile, float multishotSpray, float speed) {
        Direction targetGravity = GravityChangerAPI.getGravityDirection(target);
        Direction shooterGravity = GravityChangerAPI.getGravityDirection(entity);
        if (targetGravity == Direction.DOWN && shooterGravity == Direction.DOWN) {
            this.shootCrossbowProjectile(entity, target, projectile, multishotSpray, speed);
            return;
        }

        // Aim point: 1/3 body height above the target's feet, along the TARGET's gravity.
        Vec3 targetPos = targetGravity == Direction.DOWN
            ? new Vec3(target.getX(), target.getY(0.3333333333333333D), target.getZ())
            : target.position().add(RotationUtil.vecPlayerToWorld(0.0D, target.getBbHeight() * 0.3333333333333333D, 0.0D, targetGravity));

        double d = targetPos.x - entity.getX();
        double dy = targetPos.y - projectile.getY();
        double e = targetPos.z - entity.getZ();
        // The drop-compensation distance follows the SHOOTER's gravity: measure the offset
        // in the plane perpendicular to the shooter's gravity axis.
        double f;
        if (shooterGravity == Direction.DOWN) {
            f = Math.sqrt(d * d + e * e);
        }
        else {
            Vec3 local = RotationUtil.vecWorldToPlayer(new Vec3(d, dy, e), shooterGravity);
            f = Math.sqrt(local.x * local.x + local.z * local.z);
        }
        double g = dy + f * 0.20000000298023224D;
        Vector3f vec3f = this.getProjectileShotVector(entity, new Vec3(d, g, e), multishotSpray);
        projectile.shoot((double) vec3f.x(), (double) vec3f.y(), (double) vec3f.z(), speed, (float) (14 - entity.level().getDifficulty().getId() * 4));
        entity.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.0F / (entity.getRandom().nextFloat() * 0.4F + 0.8F));
    }
}

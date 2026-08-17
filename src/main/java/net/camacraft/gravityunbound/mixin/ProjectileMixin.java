package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/Projectile;shootFromRotation(Lnet/minecraft/world/entity/Entity;FFFFF)V",
        at = @At("HEAD"),
        ordinal = 0
    )
    private float modify_setProperties_pitch(float value, Entity user, float yaw, float roll, float speed, float divergence) {
        if (GravityChangerAPI.isAimDefault(user)) {
            return value;
        }

        return RotationUtil.rotPlayerToWorld(user.getYRot(), user.getXRot(), GravityChangerAPI.getAimRotation(user)).y;
    }
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/Projectile;shootFromRotation(Lnet/minecraft/world/entity/Entity;FFFFF)V",
        at = @At("HEAD"),
        ordinal = 1
    )
    private float modify_setProperties_yaw(float value, Entity user, float pitch, float roll, float speed, float divergence) {
        if (GravityChangerAPI.isAimDefault(user)) {
            return value;
        }

        return RotationUtil.rotPlayerToWorld(user.getYRot(), user.getXRot(), GravityChangerAPI.getAimRotation(user)).x;
    }

    // Vanilla shootFromRotation ends with:
    //   Vec3 vec3 = shooter.getDeltaMovement();
    //   this.setDeltaMovement(this.getDeltaMovement().add(vec3.x, shooter.onGround() ? 0.0 : vec3.y, vec3.z));
    // The shooter's deltaMovement is LOCAL for gravity-enabled entities, but vanilla adds it
    // along raw world axes. Convert it to world space here.
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/projectile/Projectile;shootFromRotation(Lnet/minecraft/world/entity/Entity;FFFFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 wrapOperation_shootFromRotation_getDeltaMovement(Entity shooter, Operation<Vec3> original) {
        if (GravityChangerAPI.isAimDefault(shooter)) {
            return original.call(shooter);
        }

        Vec3 local = original.call(shooter);
        if (shooter.onGround()) {
            // Vanilla's ternary zeroes the vertical component for grounded shooters. A grounded
            // shooter can't move along its own up axis, so zero the LOCAL y before converting.
            local = new Vec3(local.x, 0.0D, local.z);
        }
        // Residual: for grounded shooters vanilla still drops the WORLD y of the returned vector
        // (the "? 0.0 :" branch), discarding the world-y contribution of the shooter's local
        // horizontal motion. That parallels vanilla semantics (grounded shooters lose vertical
        // inheritance) and is accepted here.
        return RotationUtil.vecPlayerToWorld(local, GravityChangerAPI.getMovementRotation(shooter));
    }
}

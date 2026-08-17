package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

@Mixin(ExperienceOrb.class)
public class ExperienceOrbMixin {
    @ModifyArg(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"
        ),
        index = 1
    )
    private double multiplyGravity(double x) {
        return x * GravityChangerAPI.getGravityStrength(((Entity) (Object) this));
    }

    // Vanilla ExperienceOrb.tick adds the world-space offset-to-player straight onto the orb's
    // LOCAL deltaMovement: getDeltaMovement().add(vec3.normalize().scale(...)).
    // This is the only Vec3.add(Vec3) call in tick(); convert the attraction vector into the
    // orb's local frame before it is added.
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 wrapOperation_tick_addAttraction(Vec3 deltaMovement, Vec3 attraction, Operation<Vec3> original) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection((Entity) (Object) this);
        if (gravityDirection == Direction.DOWN) {
            return original.call(deltaMovement, attraction);
        }

        return original.call(deltaMovement, RotationUtil.vecWorldToPlayer(attraction, gravityDirection));
    }
}

package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Shulker.class, priority = 1001)
public abstract class ShulkerMixin {
    @WrapOperation(
        method = "onPeekAmountChange",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            ordinal = 0
        )
    )
    private void wrapOperation_pushEntities_move_0(Entity entity, MoverType movementType, Vec3 vec3d, Operation<Void> original) {
        // World push vector -> the pushed entity's local movement frame
        // (players: visual quaternion, mobs: cardinal).
        if (GravityChangerAPI.isAimDefault(entity)) {
            original.call(entity, movementType, vec3d);
            return;
        }

        original.call(entity, movementType, RotationUtil.vecWorldToPlayer(vec3d, GravityChangerAPI.getMovementRotation(entity)));
    }
}

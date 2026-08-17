package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin {
    @Redirect(
        method = "Lnet/minecraft/world/level/block/piston/PistonMovingBlockEntity;moveEntityByPiston(Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/Entity;DLnet/minecraft/core/Direction;)V",
        at = @At(
            value = "NEW",
            target = "(DDD)Lnet/minecraft/world/phys/Vec3;",
            ordinal = 0
        )
    )
    private static Vec3 redirect_moveEntity_Vec3d_0(double x, double y, double z, Direction direction, Entity entity, double d, Direction direction2) {
        // World push vector -> the pushed entity's local movement frame
        // (players: visual quaternion, mobs: cardinal).
        if (GravityChangerAPI.isAimDefault(entity)) {
            return new Vec3(x, y, z);
        }

        return RotationUtil.vecWorldToPlayer(x, y, z, GravityChangerAPI.getMovementRotation(entity));
    }
}

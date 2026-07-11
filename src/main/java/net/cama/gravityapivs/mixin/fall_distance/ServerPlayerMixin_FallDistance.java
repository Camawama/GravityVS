package net.cama.gravityapivs.mixin.fall_distance;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin_FallDistance {
    
    // make sure fall distance is correct on server side of the player
    @WrapOperation(
        method = "doCheckFallDamage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"
        )
    )
    private void wrapCheckFallDamage(ServerPlayer instance, double v, boolean b, BlockState blockState, BlockPos blockPos, Operation<Void> original, @Local(ordinal = 0) double dx, @Local(ordinal = 1) double dy, @Local(ordinal = 2) double dz) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;

        Vec3 localVec = RotationUtil.vecWorldToPlayer(dx, dy, dz, GravityChangerAPI.getMovementRotation(this_));
        original.call(instance, localVec.y(), b, blockState, blockPos);
    }
    
}

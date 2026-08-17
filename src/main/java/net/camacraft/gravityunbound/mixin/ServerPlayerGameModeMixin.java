package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.phys.Vec3;

/**
 * Fixes gravity plating on Valkyrien Skies ships surviving its own breaking.
 *
 * The vanilla/Forge break handler starts with a reach check comparing the
 * SERVER's idea of the player's eye position against the block. For blocks on
 * a ship, Valkyrien Skies substitutes a ship-aware distance — but on a MOVING
 * ship the server-side player position lags behind the ship (and the rotated
 * gravity frame shifts the eye point as well), so the check intermittently
 * fails. Crucially, Forge's "too far" branch is a SILENT drop: no corrective
 * block update is sent, so the client's block-break prediction gets rolled
 * back by the ack into a FRESH plating block whose block entity has default
 * side data (attracting, level 1, visual off). Result: the plate looks broken
 * (or reappears as a thin, easily-missed panel), yet an invisible gravity
 * field keeps applying — and it keeps the capsule collision mode alive.
 *
 * Fix: for gravity plating that sits on a ship, widen ONLY the reach decision
 * with a generous ship-aware distance. Everything else about the break
 * (LeftClickBlock event, mayInteract, creative/insta-mine flow) runs
 * unchanged.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    // slack on top of the normal block reach: covers the server-side position
    // lag on a fast-moving ship plus the rotated-frame eye offset
    private static final double GRAVITYAPIVS$SHIP_REACH_SLACK = 8.0;

    @WrapOperation(
        method = "handleBlockBreakAction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;canReach(Lnet/minecraft/core/BlockPos;D)Z",
            remap = false
        )
    )
    private boolean gravityunbound$shipPlatingCanReach(
        ServerPlayer instance, BlockPos pos, double padding, Operation<Boolean> original
    ) {
        if (gravityunbound$isReachableShipPlating(pos, padding)) {
            return true;
        }
        return original.call(instance, pos, padding);
    }

    @WrapOperation(
        method = "handleBlockBreakAction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;canReachRaw(Lnet/minecraft/core/BlockPos;D)Z",
            remap = false
        )
    )
    private boolean gravityunbound$shipPlatingCanReachRaw(
        ServerPlayer instance, BlockPos pos, double padding, Operation<Boolean> original
    ) {
        if (gravityunbound$isReachableShipPlating(pos, padding)) {
            return true;
        }
        return original.call(instance, pos, padding);
    }

    private boolean gravityunbound$isReachableShipPlating(BlockPos pos, double padding) {
        if (!(this.level.getBlockState(pos).getBlock() instanceof GravityPlatingBlock)) {
            return false;
        }
        if (VSGameUtilsKt.getShipManagingPos(this.level, pos) == null) {
            return false;
        }

        Vec3 eye = this.player.getEyePosition();
        double maxDistance = this.player.getBlockReach() + padding + GRAVITYAPIVS$SHIP_REACH_SLACK;
        double distSq = VSGameUtilsKt.squaredDistanceBetweenInclShips(
            this.level,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            eye.x, eye.y, eye.z
        );
        return distSq < maxDistance * maxDistance;
    }
}

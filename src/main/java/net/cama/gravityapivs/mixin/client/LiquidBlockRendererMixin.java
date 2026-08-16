package net.cama.gravityapivs.mixin.client;

import net.cama.gravityapivs.client.GravityLiquidRenderer;
import net.cama.gravityapivs.util.GravityFieldLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Routes fluid cells inside a rotated gravity field to
 * {@link GravityLiquidRenderer}, which renders them as vanilla would if
 * gravity pointed along the field's cardinal down.
 *
 * Vanilla-framed cells (down == DOWN) with at least one rotated-frame face
 * neighbor are ALSO routed through the port, on the identity basis (which
 * renders exactly like vanilla), so its cross-frame isolation applies: each
 * side of a frame boundary treats the other side's fluid as empty and
 * renders a complete closed surface, instead of culling faces the other
 * side never covers (the cause of the see-through gaps at boundaries).
 * Cells with down == DOWN and no rotated neighbors fall through to vanilla
 * untouched.
 *
 * Runs on chunk-building worker threads; {@link GravityFieldLookup} and the
 * renderer are both thread-safe (no shared mutable state).
 */
@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidBlockRendererMixin {

    @Unique
    private static final Direction[] gravityapivs$DIRECTIONS = Direction.values();

    @Inject(
        method = "tesselate(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gravityapivs$rotatedTesselate(
        BlockAndTintGetter level, BlockPos pos, VertexConsumer consumer,
        BlockState blockState, FluidState fluidState, CallbackInfo ci
    ) {
        Direction down = GravityFieldLookup.fluidDownAt(level, pos);
        if (down != Direction.DOWN) {
            GravityLiquidRenderer.tesselate(level, pos, consumer, blockState, fluidState, down);
            ci.cancel();
            return;
        }
        // Boundary stitching: a vanilla-framed cell touching a rotated-frame
        // neighbor goes through the port on the identity basis so the
        // cross-frame isolation applies to it too.
        for (Direction neighbor : gravityapivs$DIRECTIONS) {
            if (GravityFieldLookup.fluidDownAt(level, pos.relative(neighbor)) != Direction.DOWN) {
                GravityLiquidRenderer.tesselate(level, pos, consumer, blockState, fluidState, Direction.DOWN);
                ci.cancel();
                return;
            }
        }
    }
}

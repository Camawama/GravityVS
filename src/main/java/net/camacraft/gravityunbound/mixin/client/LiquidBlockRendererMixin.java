package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.client.GravityLiquidRenderer;
import net.camacraft.gravityunbound.util.GravityFieldLookup;
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
 * Vanilla-framed cells (down == DOWN) whose rendering consults at least one
 * rotated-frame cell are ALSO routed through the port, on the identity
 * basis (which renders exactly like vanilla), so its asymmetric cross-frame
 * isolation applies to them too: culling treats the other frame's fluid as
 * empty (no see-through holes) while height shaping treats it as a full
 * column exactly where a source/falling column pours toward the cell (the
 * surface ramps up to meet the stream) and as empty elsewhere (cliff
 * edge, no bulging).
 *
 * The scan covers every cell whose FLUID can influence vanilla tesselate's
 * output — the cell below, the four sides, the four horizontal diagonals,
 * and the full 3x3 ring of cells above (corner averaging reads the
 * above-cell of each side/diagonal sample; the backward-up-face check reads
 * the same ring). An unrouted cell therefore provably consults only
 * vanilla-framed cells, so the two paths can never disagree about a shared
 * corner height — no slits or steps along the boundary strip. Cells with no
 * rotated cell in that set fall through to vanilla untouched.
 *
 * Runs on chunk-building worker threads; {@link GravityFieldLookup} and the
 * renderer are both thread-safe (no shared mutable state).
 */
@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidBlockRendererMixin {

    /**
     * Offsets of every cell whose fluid enters vanilla tesselate's decisions
     * for the cell at the origin: below, the 4 sides, the 4 horizontal
     * diagonals, and the 3x3 ring above.
     */
    @Unique
    private static final int[][] gravityunbound$STITCH_SCAN = {
        {0, -1, 0},
        {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1},
        {-1, 0, -1}, {-1, 0, 1}, {1, 0, -1}, {1, 0, 1},
        {-1, 1, -1}, {-1, 1, 0}, {-1, 1, 1},
        {0, 1, -1}, {0, 1, 0}, {0, 1, 1},
        {1, 1, -1}, {1, 1, 0}, {1, 1, 1},
    };

    @Inject(
        method = "tesselate(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gravityunbound$rotatedTesselate(
        BlockAndTintGetter level, BlockPos pos, VertexConsumer consumer,
        BlockState blockState, FluidState fluidState, CallbackInfo ci
    ) {
        Direction down = GravityFieldLookup.fluidDownAt(level, pos);
        if (down != Direction.DOWN) {
            GravityLiquidRenderer.tesselate(level, pos, consumer, blockState, fluidState, down);
            ci.cancel();
            return;
        }
        // Boundary stitching: a vanilla-framed cell that consults any
        // rotated-frame cell goes through the port on the identity basis so
        // the asymmetric cross-frame isolation applies to it too.
        for (int[] o : gravityunbound$STITCH_SCAN) {
            if (GravityFieldLookup.fluidDownAt(level, pos.offset(o[0], o[1], o[2])) != Direction.DOWN) {
                GravityLiquidRenderer.tesselate(level, pos, consumer, blockState, fluidState, Direction.DOWN);
                ci.cancel();
                return;
            }
        }
    }
}

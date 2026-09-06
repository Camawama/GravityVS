package net.camacraft.gravityunbound.mixin.block;

import net.camacraft.gravityunbound.util.PlacementFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/** Stairs take the half the PLACING PLAYER'S frame means (see PlacementFrame). */
@Mixin(StairBlock.class)
public abstract class StairBlockMixin {
    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void gravityunbound$halfInPlayerFrame(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null || !state.hasProperty(StairBlock.HALF)) {
            return;
        }
        PlacementFrame.bottomHalf(context).ifPresent(bottom -> {
            Half half = bottom ? Half.BOTTOM : Half.TOP;
            if (state.getValue(StairBlock.HALF) == half) {
                return;
            }
            BlockState updated = state.setValue(StairBlock.HALF, half);
            cir.setReturnValue(updated.setValue(StairBlock.SHAPE,
                StairBlockAccessor.gravityunbound$getStairsShape(updated, context.getLevel(), context.getClickedPos())));
        });
    }
}

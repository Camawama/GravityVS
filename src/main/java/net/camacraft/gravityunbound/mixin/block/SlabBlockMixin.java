package net.camacraft.gravityunbound.mixin.block;

import net.camacraft.gravityunbound.util.PlacementFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Slabs take the half the PLACING PLAYER'S frame means (see PlacementFrame). */
@Mixin(SlabBlock.class)
public abstract class SlabBlockMixin {
    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void gravityunbound$halfInPlayerFrame(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null || !state.hasProperty(SlabBlock.TYPE) || state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return;
        }
        PlacementFrame.bottomHalf(context).ifPresent(bottom ->
            cir.setReturnValue(state.setValue(SlabBlock.TYPE, bottom ? SlabType.BOTTOM : SlabType.TOP)));
    }
}

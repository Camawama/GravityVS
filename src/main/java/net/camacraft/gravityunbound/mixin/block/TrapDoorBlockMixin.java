package net.camacraft.gravityunbound.mixin.block;

import net.camacraft.gravityunbound.util.PlacementFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/** Trapdoors take the half the PLACING PLAYER'S frame means (see PlacementFrame). */
@Mixin(TrapDoorBlock.class)
public abstract class TrapDoorBlockMixin {
    @Inject(method = "getStateForPlacement", at = @At("RETURN"), cancellable = true)
    private void gravityunbound$halfInPlayerFrame(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = cir.getReturnValue();
        if (state == null || !state.hasProperty(TrapDoorBlock.HALF)) {
            return;
        }
        PlacementFrame.trapdoorTopHalf(context).ifPresent(top ->
            cir.setReturnValue(state.setValue(TrapDoorBlock.HALF, top ? Half.TOP : Half.BOTTOM)));
    }
}

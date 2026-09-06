package net.camacraft.gravityunbound.mixin.block;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StairsShape;

@Mixin(StairBlock.class)
public interface StairBlockAccessor {
    @Invoker("getStairsShape")
    static StairsShape gravityunbound$getStairsShape(BlockState state, BlockGetter level, BlockPos pos) {
        throw new AssertionError();
    }
}

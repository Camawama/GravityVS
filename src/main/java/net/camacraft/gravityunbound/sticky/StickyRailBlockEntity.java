package net.camacraft.gravityunbound.sticky;

import net.camacraft.gravityunbound.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Block entity of the {@link StickyRailBlock}. Holds NO data of its own —
 * orientation ({@code BOTTOM}/{@code SPIN}) and track shape all live in the
 * blockstate — it exists purely so the client can attach a
 * {@code BlockEntityRenderer} that draws the vanilla rail model rotated by
 * the {@link Rotation24} orientation quaternion (the same pattern as the
 * sticky mimic; blockstate JSON x/y rotations cannot express all 24
 * orientations). A baked-model approach could drop this BE later.
 */
public class StickyRailBlockEntity extends BlockEntity {

    public StickyRailBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.STICKY_RAIL_BLOCK_ENTITY.get(), pos, state);
    }

    /** The orientation's bottom face, read from the blockstate. */
    public Direction getBottom() {
        BlockState state = getBlockState();
        return state.hasProperty(StickyRailBlock.BOTTOM)
            ? state.getValue(StickyRailBlock.BOTTOM)
            : Direction.DOWN;
    }

    /** The orientation's spin, read from the blockstate. */
    public int getSpin() {
        BlockState state = getBlockState();
        return state.hasProperty(StickyRailBlock.SPIN)
            ? state.getValue(StickyRailBlock.SPIN)
            : 0;
    }

    /** The local-frame track shape, read from the blockstate. */
    public RailShape getShape() {
        BlockState state = getBlockState();
        return state.hasProperty(StickyRailBlock.SHAPE)
            ? state.getValue(StickyRailBlock.SHAPE)
            : RailShape.NORTH_SOUTH;
    }
}

package net.cama.gravityapivs.sticky;

import org.jetbrains.annotations.Nullable;

import net.cama.gravityapivs.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sticky Chest — the first full reference implementation of the Gravity Block
 * Framework: a chest placeable in any of the 24 grid orientations (upside
 * down, sideways on walls, any spin) that still opens, animates and stores
 * items. Orientation is {@link #BOTTOM} (world direction of the block's local
 * down) x {@link #SPIN} (quarter turns about the local vertical axis) — see
 * {@link Rotation24}. Placement follows the placer's gravity frame.
 */
public class StickyChestBlock extends BaseEntityBlock {

    /** World direction the chest's LOCAL DOWN points. */
    public static final DirectionProperty BOTTOM = DirectionProperty.create("bottom");
    /** Quarter turns about the local vertical axis (picks the front). */
    public static final IntegerProperty SPIN = IntegerProperty.create("spin", 0, 3);

    /** Vanilla single-chest box, rotated into all 24 orientations. */
    private static final VoxelShape[] SHAPES = new VoxelShape[Rotation24.COUNT];

    static {
        VoxelShape base = Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);
        for (Direction bottom : Direction.values()) {
            for (int spin = 0; spin < 4; spin++) {
                SHAPES[Rotation24.index(bottom, spin)] = Rotation24.rotateShape(base, bottom, spin);
            }
        }
    }

    public StickyChestBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
        );
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(BOTTOM, Direction.DOWN)
            .setValue(SPIN, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BOTTOM, SPIN);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // null-safe: fromPlacement defaults to (DOWN, 0) without a placer
        Rotation24.Orientation orientation = Rotation24.fromPlacement(context.getPlayer());
        return this.defaultBlockState()
            .setValue(BOTTOM, orientation.bottom())
            .setValue(SPIN, orientation.spin());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[Rotation24.index(state.getValue(BOTTOM), state.getValue(SPIN))];
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // drawn entirely by StickyChestRenderer
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StickyChestBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
            ? createTickerHelper(type, GravityBlocks.STICKY_CHEST_BLOCK_ENTITY.get(), StickyChestBlockEntity::clientTick)
            : createTickerHelper(type, GravityBlocks.STICKY_CHEST_BLOCK_ENTITY.get(), StickyChestBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof StickyChestBlockEntity chest) {
            player.openMenu(chest);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof StickyChestBlockEntity chest) {
                Containers.dropContents(level, pos, chest);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // ContainerOpenersCounter schedules 5-tick block rechecks while open
        if (level.getBlockEntity(pos) instanceof StickyChestBlockEntity chest) {
            chest.recheckOpen();
        }
    }

    @Override
    public boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        // delegate block events to the BE — the lid animation (event id 1)
        // reaches the client-side ChestLidController through this path
        super.triggerEvent(state, level, pos, id, param);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity != null && blockEntity.triggerEvent(id, param);
    }
}

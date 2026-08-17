package net.camacraft.gravityunbound.sticky;

import java.util.Optional;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.floats.Float2FloatFunction;

import net.camacraft.gravityunbound.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
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
 *
 * Two sticky chests with the SAME orientation combine into a vanilla-style
 * double chest ({@link #TYPE}). All double-chest geometry lives in the
 * chest's LOCAL frame: the model faces local {@link Rotation24#LOCAL_FRONT}
 * (south), so — exactly like a vanilla south-facing chest — a LEFT half's
 * partner sits at local WEST and a RIGHT half's at local EAST, and those
 * local directions are mapped through {@link Rotation24#localToWorld} to find
 * the real grid neighbor. Pairing on placement, un-pairing on removal, the
 * combined 6x9 menu and the shared lid animation all mirror vanilla
 * {@code ChestBlock}.
 */
public class StickyChestBlock extends BaseEntityBlock {

    /** World direction the chest's LOCAL DOWN points. */
    public static final DirectionProperty BOTTOM = DirectionProperty.create("bottom");
    /** Quarter turns about the local vertical axis (picks the front). */
    public static final IntegerProperty SPIN = IntegerProperty.create("spin", 0, 3);
    /** Single chest, or which half of a double chest (vanilla property). */
    public static final EnumProperty<ChestType> TYPE = BlockStateProperties.CHEST_TYPE;

    /**
     * The LOCAL direction toward a double-chest half's partner. With the
     * model facing local SOUTH this is vanilla's
     * {@code facing.getClockWise()} / {@code getCounterClockWise()} for
     * LEFT / RIGHT evaluated at facing = SOUTH.
     */
    private static final Direction LOCAL_LEFT_PARTNER = Direction.WEST;
    private static final Direction LOCAL_RIGHT_PARTNER = Direction.EAST;

    /** Vanilla chest boxes (single + both double halves), rotated into all 24 orientations. */
    private static final VoxelShape[] SHAPES_SINGLE = new VoxelShape[Rotation24.COUNT];
    private static final VoxelShape[] SHAPES_LEFT = new VoxelShape[Rotation24.COUNT];
    private static final VoxelShape[] SHAPES_RIGHT = new VoxelShape[Rotation24.COUNT];

    static {
        // local-frame boxes for a chest facing local SOUTH; the double halves
        // extend toward their partner (LEFT -> local west, RIGHT -> local east)
        VoxelShape single = Block.box(1.0, 0.0, 1.0, 15.0, 14.0, 15.0);
        VoxelShape left = Block.box(0.0, 0.0, 1.0, 15.0, 14.0, 15.0);
        VoxelShape right = Block.box(1.0, 0.0, 1.0, 16.0, 14.0, 15.0);
        for (Direction bottom : Direction.values()) {
            for (int spin = 0; spin < 4; spin++) {
                int idx = Rotation24.index(bottom, spin);
                SHAPES_SINGLE[idx] = Rotation24.rotateShape(single, bottom, spin);
                SHAPES_LEFT[idx] = Rotation24.rotateShape(left, bottom, spin);
                SHAPES_RIGHT[idx] = Rotation24.rotateShape(right, bottom, spin);
            }
        }
    }

    /** Vanilla MENU_PROVIDER_COMBINER, typed to the sticky chest BE. */
    private static final DoubleBlockCombiner.Combiner<StickyChestBlockEntity, Optional<MenuProvider>> MENU_PROVIDER_COMBINER =
        new DoubleBlockCombiner.Combiner<>() {
            @Override
            public Optional<MenuProvider> acceptDouble(final StickyChestBlockEntity first, final StickyChestBlockEntity second) {
                final Container container = new CompoundContainer(first, second);
                return Optional.of(new MenuProvider() {
                    @Nullable
                    @Override
                    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                        if (first.canOpen(player) && second.canOpen(player)) {
                            first.unpackLootTable(inventory.player);
                            second.unpackLootTable(inventory.player);
                            return ChestMenu.sixRows(containerId, inventory, container);
                        }
                        return null;
                    }

                    @Override
                    public Component getDisplayName() {
                        if (first.hasCustomName()) {
                            return first.getDisplayName();
                        }
                        return second.hasCustomName() ? second.getDisplayName() : Component.translatable("container.chestDouble");
                    }
                });
            }

            @Override
            public Optional<MenuProvider> acceptSingle(StickyChestBlockEntity chest) {
                return Optional.of(chest);
            }

            @Override
            public Optional<MenuProvider> acceptNone() {
                return Optional.empty();
            }
        };

    public StickyChestBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
        );
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(BOTTOM, Direction.DOWN)
            .setValue(SPIN, 0)
            .setValue(TYPE, ChestType.SINGLE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BOTTOM, SPIN, TYPE);
    }

    // ---- orientation-frame helpers ----

    /**
     * The GRID direction from a double-chest half toward its partner
     * (only meaningful for LEFT/RIGHT states).
     */
    public static Direction getConnectedDirection(BlockState state) {
        Direction local = state.getValue(TYPE) == ChestType.LEFT ? LOCAL_LEFT_PARTNER : LOCAL_RIGHT_PARTNER;
        return Rotation24.localToWorld(local, state.getValue(BOTTOM), state.getValue(SPIN));
    }

    /** Vanilla's chest-type-to-combiner-role mapping. */
    public static DoubleBlockCombiner.BlockType getBlockType(BlockState state) {
        ChestType type = state.getValue(TYPE);
        if (type == ChestType.SINGLE) {
            return DoubleBlockCombiner.BlockType.SINGLE;
        }
        return type == ChestType.RIGHT ? DoubleBlockCombiner.BlockType.FIRST : DoubleBlockCombiner.BlockType.SECOND;
    }

    /**
     * Vanilla's "chest blocked" test, in the LOCAL frame: a solid block
     * against the chest's LOCAL top (the face the lid opens through) blocks
     * it. (The vanilla sitting-cat check is not replicated — cats only sit
     * on world-up surfaces, which for most orientations is not the lid.)
     */
    public static boolean isStickyChestBlockedAt(LevelAccessor level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof StickyChestBlock)) {
            return false;
        }
        Direction localUp = Rotation24.localToWorld(Direction.UP, state.getValue(BOTTOM), state.getValue(SPIN));
        BlockPos abovePos = pos.relative(localUp);
        return level.getBlockState(abovePos).isRedstoneConductor(level, abovePos);
    }

    private boolean isMatchingSingleChest(Level level, BlockPos pos, Direction bottom, int spin) {
        BlockState state = level.getBlockState(pos);
        return state.is(this)
            && state.getValue(TYPE) == ChestType.SINGLE
            && state.getValue(BOTTOM) == bottom
            && state.getValue(SPIN) == spin;
    }

    // ---- placement / pairing ----

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // null-safe: fromPlacement defaults to (DOWN, 0) without a placer.
        // Level+pos matter: on a Valkyrien Skies ship the orientation is
        // computed in SHIPYARD grid space, not world space.
        Rotation24.Orientation orientation = Rotation24.fromPlacement(
            context.getPlayer(), context.getLevel(), context.getClickedPos()
        );
        Direction bottom = orientation.bottom();
        int spin = orientation.spin();

        // vanilla non-sneak auto-merge: a matching SINGLE chest along the
        // local left/right axis pairs up (sneaking always places a single)
        ChestType type = ChestType.SINGLE;
        if (!context.isSecondaryUseActive()) {
            BlockPos pos = context.getClickedPos();
            Level level = context.getLevel();
            if (isMatchingSingleChest(level, pos.relative(Rotation24.localToWorld(LOCAL_LEFT_PARTNER, bottom, spin)), bottom, spin)) {
                type = ChestType.LEFT;
            } else if (isMatchingSingleChest(level, pos.relative(Rotation24.localToWorld(LOCAL_RIGHT_PARTNER, bottom, spin)), bottom, spin)) {
                type = ChestType.RIGHT;
            }
        }

        return this.defaultBlockState()
            .setValue(BOTTOM, bottom)
            .setValue(SPIN, spin)
            .setValue(TYPE, type);
    }

    @Override
    public BlockState updateShape(
        BlockState state, Direction dir, BlockState neighborState,
        LevelAccessor level, BlockPos pos, BlockPos neighborPos
    ) {
        // vanilla ChestBlock.updateShape in the rotated frame: pair with a
        // matching neighbor that just became a double half pointing at us;
        // revert to SINGLE when the partner is gone
        Direction bottom = state.getValue(BOTTOM);
        int spin = state.getValue(SPIN);
        boolean matchingChest = neighborState.is(this)
            && neighborState.getValue(BOTTOM) == bottom
            && neighborState.getValue(SPIN) == spin;

        if (matchingChest && Rotation24.worldToLocal(dir, bottom, spin).getAxis().isHorizontal()) {
            ChestType neighborType = neighborState.getValue(TYPE);
            if (state.getValue(TYPE) == ChestType.SINGLE
                && neighborType != ChestType.SINGLE
                && getConnectedDirection(neighborState) == dir.getOpposite()) {
                return state.setValue(TYPE, neighborType.getOpposite());
            }
        } else if (state.getValue(TYPE) != ChestType.SINGLE && getConnectedDirection(state) == dir) {
            return state.setValue(TYPE, ChestType.SINGLE);
        }
        return super.updateShape(state, dir, neighborState, level, pos, neighborPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int idx = Rotation24.index(state.getValue(BOTTOM), state.getValue(SPIN));
        return switch (state.getValue(TYPE)) {
            case LEFT -> SHAPES_LEFT[idx];
            case RIGHT -> SHAPES_RIGHT[idx];
            default -> SHAPES_SINGLE[idx];
        };
    }

    // ---- inventory combining (vanilla DoubleBlockCombiner flow) ----

    /**
     * Resolves this chest's single/double neighborhood. Unlike vanilla's
     * {@code DoubleBlockCombiner.combineWithNeigbour} (which compares one
     * DirectionProperty), the partner must match BOTH orientation properties,
     * so the check is done here and the result classes are reused.
     */
    public DoubleBlockCombiner.NeighborCombineResult<StickyChestBlockEntity> combine(
        BlockState state, Level level, BlockPos pos, boolean overrideBlocked
    ) {
        BiPredicate<LevelAccessor, BlockPos> blocked = overrideBlocked
            ? (l, p) -> false
            : StickyChestBlock::isStickyChestBlockedAt;

        if (!(level.getBlockEntity(pos) instanceof StickyChestBlockEntity chest) || blocked.test(level, pos)) {
            return DoubleBlockCombiner.Combiner::acceptNone;
        }
        ChestType type = state.getValue(TYPE);
        if (type == ChestType.SINGLE) {
            return new DoubleBlockCombiner.NeighborCombineResult.Single<>(chest);
        }

        BlockPos partnerPos = pos.relative(getConnectedDirection(state));
        BlockState partnerState = level.getBlockState(partnerPos);
        if (partnerState.is(this)
            && partnerState.getValue(TYPE) == type.getOpposite()
            && partnerState.getValue(BOTTOM) == state.getValue(BOTTOM)
            && partnerState.getValue(SPIN) == state.getValue(SPIN)) {
            if (blocked.test(level, partnerPos)) {
                return DoubleBlockCombiner.Combiner::acceptNone;
            }
            if (level.getBlockEntity(partnerPos) instanceof StickyChestBlockEntity partner) {
                // vanilla slot order: the RIGHT half is FIRST (top 27 slots)
                StickyChestBlockEntity first = type == ChestType.RIGHT ? chest : partner;
                StickyChestBlockEntity second = type == ChestType.RIGHT ? partner : chest;
                return new DoubleBlockCombiner.NeighborCombineResult.Double<>(first, second);
            }
        }
        return new DoubleBlockCombiner.NeighborCombineResult.Single<>(chest);
    }

    @Nullable
    @Override
    public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return this.combine(state, level, pos, false).apply(MENU_PROVIDER_COMBINER).orElse(null);
    }

    /**
     * Lid-openness combiner for the renderer: a double chest animates with
     * the max openness of both halves (vanilla ChestBlock.opennessCombiner).
     */
    public static DoubleBlockCombiner.Combiner<StickyChestBlockEntity, Float2FloatFunction> opennessCombiner(final LidBlockEntity lid) {
        return new DoubleBlockCombiner.Combiner<>() {
            @Override
            public Float2FloatFunction acceptDouble(StickyChestBlockEntity first, StickyChestBlockEntity second) {
                return partialTick -> Math.max(first.getOpenNess(partialTick), second.getOpenNess(partialTick));
            }

            @Override
            public Float2FloatFunction acceptSingle(StickyChestBlockEntity chest) {
                return chest::getOpenNess;
            }

            @Override
            public Float2FloatFunction acceptNone() {
                return lid::getOpenNess;
            }
        };
    }

    // ---- the rest of the block ----

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
        MenuProvider menuProvider = this.getMenuProvider(state, level, pos);
        if (menuProvider != null) {
            player.openMenu(menuProvider);
        }
        return InteractionResult.CONSUME;
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

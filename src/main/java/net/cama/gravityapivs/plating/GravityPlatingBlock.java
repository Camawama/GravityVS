package net.cama.gravityapivs.plating;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.cama.gravityapivs.init.GravityBlocks;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Based on code from AmethystGravity (by CyborgCabbage)
 */
public class GravityPlatingBlock extends BaseEntityBlock
    implements net.minecraft.world.level.block.LiquidBlockContainer, net.minecraft.world.level.block.BucketPickup {
    // in a corner, multiple faces of plates can occupy the same block
    
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    // Plates are thin panels sharing their cell with fluid, and the plate
    // cells are exactly where face-hugging water lives — so unlike vanilla
    // waterlogging (source-only boolean), plating stores the FULL fluid
    // level: 0 = dry, 1-7 flowing, 8 = source (or full falling when
    // WATER_FALLING). FlowingFluid's tick updates these through the
    // container-preservation hook in FlowingFluidMixin instead of replacing
    // the plate.
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty WATER_LEVEL =
        net.minecraft.world.level.block.state.properties.IntegerProperty.create("water_level", 0, 8);
    public static final BooleanProperty WATER_FALLING = BooleanProperty.create("water_falling");
    
    protected static final VoxelShape DOWN_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);
    protected static final VoxelShape UP_SHAPE = Block.box(0.0, 15.0, 0.0, 16.0, 16.0, 16.0);
    protected static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 15.0, 16.0, 16.0, 16.0);
    protected static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 1.0);
    protected static final VoxelShape WEST_SHAPE = Block.box(15.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    protected static final VoxelShape EAST_SHAPE = Block.box(0.0, 0.0, 0.0, 1.0, 16.0, 16.0);
    private final Map<BlockState, VoxelShape> shapesByState;
    
    public GravityPlatingBlock() {
        super(BlockBehaviour.Properties.of().noOcclusion().noCollission().instabreak());
        registerDefaultState(getStateDefinition().any()
            .setValue(NORTH, false)
            .setValue(EAST, false)
            .setValue(SOUTH, false)
            .setValue(WEST, false)
            .setValue(UP, false)
            .setValue(DOWN, false)
            .setValue(WATER_LEVEL, 0)
            .setValue(WATER_FALLING, false)
        );
        this.shapesByState =
            ImmutableMap.copyOf(
                this.stateDefinition.getPossibleStates().stream()
                    .collect(Collectors.toMap(Function.identity(), GravityPlatingBlock::getShapeForState))
            );
    }
    
    private static VoxelShape getShapeForState(BlockState state) {
        VoxelShape voxelShape = Shapes.empty();
        if (state.getValue(UP)) {
            voxelShape = UP_SHAPE;
        }
        if (state.getValue(NORTH)) {
            voxelShape = Shapes.or(voxelShape, SOUTH_SHAPE);
        }
        if (state.getValue(SOUTH)) {
            voxelShape = Shapes.or(voxelShape, NORTH_SHAPE);
        }
        if (state.getValue(EAST)) {
            voxelShape = Shapes.or(voxelShape, WEST_SHAPE);
        }
        if (state.getValue(WEST)) {
            voxelShape = Shapes.or(voxelShape, EAST_SHAPE);
        }
        if (state.getValue(DOWN)) {
            voxelShape = Shapes.or(voxelShape, DOWN_SHAPE);
        }
        return voxelShape.isEmpty() ? Shapes.block() : voxelShape;
    }
    
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return this.shapesByState.get(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }
    
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> stateManager) {
        stateManager.add(UP, DOWN, NORTH, SOUTH, EAST, WEST, WATER_LEVEL, WATER_FALLING);
    }
    
    @Override
    public net.minecraft.world.level.material.FluidState getFluidState(BlockState state) {
        int waterLevel = state.getValue(WATER_LEVEL);
        if (waterLevel == 0) {
            return super.getFluidState(state);
        }
        boolean falling = state.getValue(WATER_FALLING);
        if (waterLevel == 8 && !falling) {
            return net.minecraft.world.level.material.Fluids.WATER.getSource(false);
        }
        return net.minecraft.world.level.material.Fluids.WATER.getFlowing(waterLevel, falling);
    }

    /** Encodes a fluid state into the plate's water properties. */
    public static BlockState withFluid(BlockState state, net.minecraft.world.level.material.FluidState fluid) {
        if (fluid.isEmpty() || !fluid.getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
            return state.setValue(WATER_LEVEL, 0).setValue(WATER_FALLING, false);
        }
        int amount = fluid.isSource() ? 8 : fluid.getAmount();
        boolean falling = !fluid.isSource()
            && fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING);
        return state.setValue(WATER_LEVEL, amount).setValue(WATER_FALLING, falling);
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state,
                                  net.minecraft.world.level.material.Fluid fluid) {
        // water in any form — SOURCE OR FLOWING: face-hugging flow must be
        // able to pass through plate cells (vanilla waterlogging is
        // source-only, which walled the whole face off from flowing water)
        return fluid.isSame(net.minecraft.world.level.material.Fluids.WATER);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state,
                               net.minecraft.world.level.material.FluidState fluid) {
        if (!fluid.getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
            return false;
        }
        BlockState updated = withFluid(state, fluid);
        if (updated != state) {
            level.setBlock(pos, updated, 3);
        }
        // schedule the ACTUAL stored fluid type: the fluid ticker validates
        // scheduled ticks with an exact type match (fluidState.is(fluid)),
        // so scheduling the source singleton for FLOWING content silently
        // discards the tick — water entered a plate and froze forever
        net.minecraft.world.level.material.Fluid stored = updated.getFluidState().getType();
        if (stored != net.minecraft.world.level.material.Fluids.EMPTY) {
            level.scheduleTick(pos, stored, stored.getTickDelay(level));
        }
        return true;
    }

    @Override
    public ItemStack pickupBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        // buckets pick up sources only, like vanilla
        if (state.getValue(WATER_LEVEL) == 8 && !state.getValue(WATER_FALLING)) {
            level.setBlock(pos, state.setValue(WATER_LEVEL, 0).setValue(WATER_FALLING, false), 3);
            return new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public java.util.Optional<net.minecraft.sounds.SoundEvent> getPickupSound() {
        return net.minecraft.world.level.material.Fluids.WATER.getPickupSound();
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor world, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATER_LEVEL) > 0) {
            // the actual stored type — see placeLiquid: exact-type-matched
            net.minecraft.world.level.material.Fluid stored = getFluidState(state).getType();
            world.scheduleTick(pos, stored, stored.getTickDelay(world));
        }
        if (hasDir(state, direction) && !canPlaceOn(world, pos.relative(direction), direction.getOpposite())) {
            state = state.setValue(directionToProperty(direction), false);
            if (getDirections(state).size() == 0) {
                return Blocks.AIR.defaultBlockState();
            }
            else {
                return state;
            }
        }
        else {
            return super.updateShape(state, direction, neighborState, world, pos, neighborPos);
        }
    }
    
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        switch (rotation) {
            case CLOCKWISE_180 -> {
                return (((state.setValue(NORTH, state.getValue(SOUTH))).setValue(EAST, state.getValue(WEST))).setValue(SOUTH, state.getValue(NORTH))).setValue(WEST, state.getValue(EAST));
            }
            case COUNTERCLOCKWISE_90 -> {
                return (((state.setValue(NORTH, state.getValue(EAST))).setValue(EAST, state.getValue(SOUTH))).setValue(SOUTH, state.getValue(WEST))).setValue(WEST, state.getValue(NORTH));
            }
            case CLOCKWISE_90 -> {
                return (((state.setValue(NORTH, state.getValue(WEST))).setValue(EAST, state.getValue(NORTH))).setValue(SOUTH, state.getValue(EAST))).setValue(WEST, state.getValue(SOUTH));
            }
        }
        return state;
    }
    
    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        switch (mirror) {
            case LEFT_RIGHT -> {
                return (state.setValue(NORTH, state.getValue(SOUTH))).setValue(SOUTH, state.getValue(NORTH));
            }
            case FRONT_BACK -> {
                return (state.setValue(EAST, state.getValue(WEST))).setValue(WEST, state.getValue(EAST));
            }
        }
        return super.mirror(state, mirror);
    }
    
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GravityPlatingBlockEntity(pos, state);
    }
    
    @Override
    public RenderShape getRenderShape(BlockState state) {
        // With inheriting from BlockWithEntity this defaults to INVISIBLE, so we need to change that!
        return RenderShape.MODEL;
    }
    
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        // ticks on both sides: the server is authoritative for non-player entities,
        // the client computes the locally controlled player's gravity
        return createTickerHelper(type, GravityBlocks.GRAVITY_PLATING_BLOCK_ENTITY.get(), GravityPlatingBlockEntity::tick);
    }
    
    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (!context.isSecondaryUseActive() && context.getItemInHand().getItem() == this.asItem()) {
            return !hasDir(state, context.getClickedFace().getOpposite());
        }
        return super.canBeReplaced(state, context);
    }
    
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState blockState = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (blockState.is(this)) {
            return blockState.setValue(directionToProperty(ctx.getClickedFace().getOpposite()), true);
        }
        return withFluid(
            defaultBlockState().setValue(directionToProperty(ctx.getClickedFace().getOpposite()), true),
            ctx.getLevel().getFluidState(ctx.getClickedPos())
        );
    }
    
    private boolean canPlaceOn(BlockGetter world, BlockPos pos, Direction side) {
        BlockState blockState = world.getBlockState(pos);
        return blockState.isFaceSturdy(world, pos, side);
    }
    
    @Override
    public boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
        ArrayList<Direction> directions = getDirections(state);
        if (directions.size() == 1) {
            return canPlaceOn(world, pos.relative(directions.get(0)), directions.get(0).getOpposite());
        }
        //Placing inside an existing plating
        if (directions.size() > 1) {
            for (Direction dir : getDirections(world.getBlockState(pos))) {
                directions.remove(dir);
            }
            if (directions.isEmpty()) {
                return false;
            }
            return canPlaceOn(world, pos.relative(directions.get(0)), directions.get(0).getOpposite());
        }
        return false;
    }
    
    public static BooleanProperty directionToProperty(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }
    
    // Note: the direction is gravity field direction. the facing is the opposite
    public static boolean hasDir(BlockState blockState, Direction dir) {
        if (blockState.getBlock() instanceof GravityPlatingBlock) {
            return blockState.getValue(directionToProperty(dir));
        }
        return false;
    }
    
    public static ArrayList<Direction> getDirections(BlockState blockState) {
        ArrayList<Direction> list = new ArrayList<>();
        if (!(blockState.getBlock() instanceof GravityPlatingBlock)) {
            return list;
        }
        //Iterate directions
        for (int directionId = 0; directionId < 6; directionId++) {
            //Convert ID to Direction
            Direction direction = Direction.from3DDataValue(directionId);
            //If the plate has this direction
            if (hasDir(blockState, direction)) {
                list.add(direction);
            }
        }
        return list;
    }
    

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide()) {
            boolean fullyRemoved = !state.is(newState.getBlock());
            // a side was removed while the block remains (multi-side plates)
            boolean sidesChanged = !fullyRemoved
                && !getDirections(state).equals(getDirections(newState));
            if (fullyRemoved || sidesChanged) {
                int radius = level.getBlockEntity(pos)
                    instanceof net.cama.gravityapivs.util.GravityFieldLookup.Source source
                    ? source.sourceMaxRange() : 8;
                if (fullyRemoved) {
                    net.cama.gravityapivs.util.GravityFieldLookup.unregister(level, pos);
                }
                net.cama.gravityapivs.util.GravityFieldLookup.resettleFluidsAround(level, pos, radius);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
        ItemStack handItem = player.getItemInHand(hand);

        Direction hitDir = hit.getDirection();
        Direction plateDir = hitDir.getOpposite();

        // empty hand opens the settings GUI (client side only); the settings
        // change travels back via UpdateGravityBlockSettingsPacket. The
        // opener class is client-only and is never touched on the server —
        // level.isClientSide() is always false on a dedicated server.
        if (handItem.isEmpty()) {
            if (!hasDir(state, plateDir)) {
                return InteractionResult.FAIL;
            }
            if (level.isClientSide() && net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                net.cama.gravityapivs.client.gui.GravityBlockSettingsScreenOpener.openPlating(pos, plateDir);
            }
            return InteractionResult.SUCCESS;
        }

        // only amethyst cluster, glow ink sac and echo shard interact with
        // the plate; anything else (spawn eggs, blocks, tools...) passes
        // through to normal item behavior with no message
        if (!handItem.is(net.minecraft.world.item.Items.AMETHYST_CLUSTER)
            && !handItem.is(net.minecraft.world.item.Items.GLOW_INK_SAC)
            && !handItem.is(net.minecraft.world.item.Items.ECHO_SHARD)
        ) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (!(blockEntity instanceof GravityPlatingBlockEntity be)) {
            return InteractionResult.FAIL;
        }

        return be.interact(level, pos, plateDir, player, hand);
    }
    
    // breaking in creative intentionally drops nothing (like vanilla blocks);
    // survival drops come from getDrops below
    @Override
    public List<ItemStack> getDrops(BlockState blockState, LootParams.Builder builder) {
        BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (blockEntity instanceof GravityPlatingBlockEntity be) {
            return be.getDrops();
        }
        
        return List.of();
    }
}
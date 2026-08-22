package net.camacraft.gravityunbound.sticky;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sticky Rail — a minecart rail placeable in any of the 24 grid orientations
 * of the Gravity Block Framework ({@link #BOTTOM} x {@link #SPIN}, see
 * {@link Rotation24}): on walls, ceilings, any cube face.
 *
 * <p><b>It IS a {@link BaseRailBlock}</b> (and is in the {@code minecraft:rails}
 * block tag), so vanilla's rail checks pass: minecarts can be placed on it by
 * right-click ({@code MinecartItem.useOn} requires the tag), and rails whose
 * {@link #BOTTOM} is {@code DOWN} behave as REAL vanilla rails — vanilla
 * handles their minecart movement, shape auto-connection ({@code RailState}),
 * and interconnection with vanilla rails, natively. To make that sound, DOWN
 * rails are always normalized to {@code SPIN 0} at placement, so their local
 * frame IS the world frame and the {@link #SHAPE} property reads identically
 * to a vanilla rail's.
 *
 * <p>For every other bottom, the state-update entry points
 * ({@link #onPlace}, {@link #neighborChanged}, {@link #updateDir}) route to
 * {@link StickyRailState} — the local-frame port of vanilla's connection
 * logic — and vanilla {@code RailState} never runs for them from OUR entry
 * points. Minecart movement on non-DOWN rails is taken over by
 * {@code mixin.AbstractMinecartMixin} (a local-frame port of the vanilla
 * on-track tick), which cancels the vanilla tick before vanilla's own
 * world-frame rail detection can misinterpret a rotated rail.
 *
 * <p>The {@link #SHAPE} property is the vanilla {@link RailShape} (ALL ten
 * values, ascending included), interpreted in the rail's LOCAL frame (a
 * NORTH_SOUTH rail on a wall runs along the wall's local north-south).
 * Ascending shapes climb along the rail's local UP — away from the mounting
 * surface — mirroring vanilla slopes one cell off the surface plane.
 *
 * <p><b>Known interop edge (documented, guarded):</b> vanilla {@code RailState}
 * identifies rails per-BLOCK ({@code isRail} = tag + instanceof), so a vanilla
 * rail placed world-adjacent to a NON-DOWN sticky rail can try to "connect"
 * to it and write a world-frame shape into its local-frame SHAPE property.
 * {@link #onPlace} detects such foreign SHAPE writes (any write that did not
 * come from {@link StickyRailState}, tracked by a reentrancy depth counter)
 * and immediately re-settles the rail from its own local-frame neighborhood,
 * so the corruption is transient (within the same block update).
 *
 * <p>CROSS-FRAME JUNCTIONS (rails connecting between axes): rails whose
 * frames meet at a cube edge or corner link through three patterns — see
 * the cross-frame section in {@link StickyRailState}. Concave corners
 * produce an ascending ramp on the side whose frame-up holds the partner
 * (floor ramps up into a wall track; a ceiling track ramps down toward a
 * wall), convex edges and wall bases connect flat, folding around the
 * edge. DOWN rails participate via {@link #crossFrameTouchUp} after
 * vanilla settles them. Junctions require sticky rails on BOTH sides
 * (plain vanilla rails have no frame to fold into).
 *
 * <p>Still out of scope: powered/activator/detector variants, rails on
 * Valkyrien Skies ships.
 */
public class StickyRailBlock extends BaseRailBlock implements EntityBlock {

    /** World direction the rail's LOCAL DOWN points (shared with the chest). */
    public static final DirectionProperty BOTTOM = StickyChestBlock.BOTTOM;
    /** Quarter turns about the local vertical axis (shared with the chest). */
    public static final IntegerProperty SPIN = StickyChestBlock.SPIN;
    /**
     * Track shape in the rail's LOCAL frame — the full vanilla property
     * (ascending values included; they climb along the rail's local UP).
     * Same property instance as vanilla {@code RailBlock.SHAPE} so the value
     * set matches vanilla exactly.
     */
    public static final EnumProperty<RailShape> SHAPE = BlockStateProperties.RAIL_SHAPE;

    /** Vanilla flat rail box, rotated into all 24 orientations. */
    private static final VoxelShape[] SHAPES_FLAT = new VoxelShape[Rotation24.COUNT];
    /** Vanilla ascending-rail half-block box, rotated into all 24 orientations. */
    private static final VoxelShape[] SHAPES_ASCENDING = new VoxelShape[Rotation24.COUNT];

    /**
     * Depth counter of LOCAL-frame state writes ({@link StickyRailState})
     * on this thread; used by {@link #onPlace} to tell our own SHAPE writes
     * apart from foreign (world-frame vanilla {@code RailState}) writes.
     */
    static final ThreadLocal<int[]> LOCAL_UPDATE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    static {
        VoxelShape flat = Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0);
        VoxelShape half = Block.box(0.0, 0.0, 0.0, 16.0, 8.0, 16.0);
        for (Direction bottom : Direction.values()) {
            for (int spin = 0; spin < 4; spin++) {
                SHAPES_FLAT[Rotation24.index(bottom, spin)] = Rotation24.rotateShape(flat, bottom, spin);
                SHAPES_ASCENDING[Rotation24.index(bottom, spin)] = Rotation24.rotateShape(half, bottom, spin);
            }
        }
    }

    public StickyRailBlock() {
        // isStraight = false: flexible like the plain vanilla rail (can curve)
        super(false, BlockBehaviour.Properties.of()
            .mapColor(MapColor.NONE)
            .noCollission()
            .noOcclusion()
            .strength(0.7f)
            .sound(SoundType.METAL)
        );
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(BOTTOM, Direction.DOWN)
            .setValue(SPIN, 0)
            .setValue(SHAPE, RailShape.NORTH_SOUTH)
            .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BOTTOM, SPIN, SHAPE, WATERLOGGED);
    }

    @Override
    public Property<RailShape> getShapeProperty() {
        return SHAPE;
    }

    // ---- orientation helpers ----

    public static boolean isStickyRail(BlockState state) {
        return state.getBlock() instanceof StickyRailBlock;
    }

    /** A sticky rail sharing the given frame (same BOTTOM; spin is free). */
    public static boolean isSameFrameRail(BlockState state, Direction bottom) {
        return isStickyRail(state) && state.getValue(BOTTOM) == bottom;
    }

    /** This rail's grid neighbor position along one of ITS local directions. */
    public static BlockPos localNeighbor(BlockPos pos, BlockState state, Direction local) {
        return pos.relative(Rotation24.localToWorld(local, state.getValue(BOTTOM), state.getValue(SPIN)));
    }

    // ---- placement / connection ----

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // rails attach to the surface that was clicked: bottom = clicked face
        // opposite. Spin from the placer's look. MUST stay inside
        // getStateForPlacement (the only spot Valkyrien Skies wraps with its
        // temporary ship-grid player transform).
        Rotation24.Orientation orientation = Rotation24.fromClickedFace(
            context.getPlayer(), context.getLevel(), context.getClickedPos(), context.getClickedFace()
        );
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        BlockState state = this.defaultBlockState()
            .setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
        if (orientation.bottom() == Direction.DOWN) {
            // DOWN rails are REAL vanilla rails: normalize to SPIN 0 so the
            // local frame IS the world frame (vanilla RailState reads/writes
            // SHAPE in the world frame). The placer's facing — encoded in the
            // spin fromClickedFace picked — becomes the initial shape instead,
            // exactly like vanilla BaseRailBlock.getStateForPlacement.
            boolean eastWest = (orientation.spin() & 1) == 1;
            return state
                .setValue(BOTTOM, Direction.DOWN)
                .setValue(SPIN, 0)
                .setValue(SHAPE, eastWest ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
        }
        // non-DOWN: the spin already turned the local frame toward the placer,
        // so the initial local NORTH_SOUTH mirrors vanilla's facing pick; the
        // StickyRailState pass on place corrects it from neighbors
        return state
            .setValue(BOTTOM, orientation.bottom())
            .setValue(SPIN, orientation.spin());
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // the supporting block sits at the BOTTOM direction and must offer a
        // sturdy face toward the rail (vanilla: canSupportRigidBlock(below);
        // identical for BOTTOM == DOWN)
        Direction bottom = state.getValue(BOTTOM);
        return sturdyToward(level, pos.relative(bottom), bottom.getOpposite());
    }

    private static boolean sturdyToward(LevelReader level, BlockPos supportPos, Direction face) {
        return level.getBlockState(supportPos).isFaceSturdy(level, supportPos, face);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        Direction bottom = state.getValue(BOTTOM);
        if (!oldState.is(this)) {
            if (bottom == Direction.DOWN) {
                // vanilla path: BaseRailBlock.onPlace -> updateState ->
                // updateDir(-> vanilla RailState, see updateDir routing below)
                super.onPlace(state, level, pos, oldState, isMoving);
            } else {
                this.updateDir(level, pos, state, true);
            }
            return;
        }
        // Same-block state change. Guard against foreign SHAPE writes on
        // non-DOWN rails: vanilla RailState (via an adjacent vanilla rail)
        // identifies rails per-block and can write a WORLD-frame shape into a
        // rotated rail's LOCAL-frame property. Our own StickyRailState writes
        // are marked by LOCAL_UPDATE_DEPTH; anything else re-settles the rail
        // from its local neighborhood immediately. (With the BaseRailBlock
        // isolation mixin, vanilla can no longer even see rotated rails —
        // this guard remains as defense in depth against other mods.)
        if (!level.isClientSide
            && bottom != Direction.DOWN
            && LOCAL_UPDATE_DEPTH.get()[0] == 0
            && oldState.getValue(SHAPE) != state.getValue(SHAPE)) {
            this.updateDir(level, pos, state, false);
        }
        // DOWN rails: a vanilla RailState cascade may legitimately reshape
        // us (it owns the plane) — but it knows nothing about cross-frame
        // junctions, so re-apply the conservative touch-up (idempotent; only
        // acts when a junction partner exists).
        if (!level.isClientSide
            && bottom == Direction.DOWN
            && LOCAL_UPDATE_DEPTH.get()[0] == 0
            && oldState.getValue(SHAPE) != state.getValue(SHAPE)) {
            crossFrameTouchUp(level, pos, state);
        }
    }

    /**
     * Vanilla {@code BaseRailBlock.updateDir}, routed by frame: DOWN rails run
     * the REAL vanilla {@code RailState} (connecting with vanilla rails both
     * ways); every other bottom runs the local-frame {@link StickyRailState}.
     */
    @Override
    public BlockState updateDir(Level level, BlockPos pos, BlockState state, boolean alwaysPlace) {
        if (level.isClientSide) {
            return state;
        }
        if (state.getValue(BOTTOM) == Direction.DOWN) {
            BlockState settled = super.updateDir(level, pos, state, alwaysPlace);
            return crossFrameTouchUp(level, pos, settled);
        }
        RailShape shape = state.getValue(SHAPE);
        return new StickyRailState(level, pos, state)
            .place(level.hasNeighborSignal(pos), alwaysPlace, shape)
            .getState();
    }

    /**
     * Cross-frame junction pass for DOWN rails. DOWN sticky rails are real
     * vanilla rails (vanilla RailState settles them), and vanilla knows
     * nothing about rails on walls — so after vanilla has had its say, a
     * DOWN rail with a cross-frame partner orients toward the junction:
     * ASCENDING toward a wall rail directly above (the concave ramp that
     * also carries carts up into the wall rail's cell), or the flat axis
     * toward a wall-base/convex partner. Deliberately conservative: the
     * touch-up only acts when vanilla found NO same-plane rails of its own
     * (with the isolation mixin, rotated rails no longer count), or when it
     * would only upgrade the straight axis vanilla already chose into the
     * matching ascending — vanilla layouts are never re-routed.
     */
    private BlockState crossFrameTouchUp(Level level, BlockPos pos, BlockState state) {
        RailShape shape = state.getValue(SHAPE);
        if (shape != RailShape.NORTH_SOUTH && shape != RailShape.EAST_WEST) {
            return state;
        }
        boolean lone = vanillaPotentialConnections(level, pos) == 0;
        RailShape upgraded = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean axisMatches = (d.getAxis() == Direction.Axis.Z) == (shape == RailShape.NORTH_SOUTH);
            if (!lone && !axisMatches) {
                continue; // never re-route an axis vanilla chose from its own rails
            }
            // CONCAVE: wall rail directly above, mounted on the wall at d
            if (isSameFrameRail(level.getBlockState(pos.above()), d)) {
                upgraded = ascendingToward(d);
                break;
            }
            // WALL-BASE: wall rail beside us whose plane base meets our cell
            if (isSameFrameRail(level.getBlockState(pos.relative(d)), d)
                || isSameFrameRail(level.getBlockState(pos.relative(d).below()), d.getOpposite())) {
                // flat toward d (the second probe is the CONVEX edge partner)
                upgraded = d.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
                if (!lone) {
                    upgraded = null; // axis already correct; nothing to change
                }
                if (upgraded != null) {
                    break;
                }
            }
        }
        if (upgraded == null || upgraded == shape) {
            return state;
        }
        BlockState updated = state.setValue(SHAPE, upgraded);
        int[] depth = LOCAL_UPDATE_DEPTH.get();
        depth[0]++;
        try {
            level.setBlock(pos, updated, 3);
        } finally {
            depth[0]--;
        }
        return updated;
    }

    /** The ascending shape climbing toward world direction {@code d}. */
    private static RailShape ascendingToward(Direction d) {
        return switch (d) {
            case NORTH -> RailShape.ASCENDING_NORTH;
            case SOUTH -> RailShape.ASCENDING_SOUTH;
            case EAST -> RailShape.ASCENDING_EAST;
            case WEST -> RailShape.ASCENDING_WEST;
            default -> RailShape.NORTH_SOUTH;
        };
    }

    @SuppressWarnings("deprecation")
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean isMoving) {
        if (level.isClientSide || !level.getBlockState(pos).is(this)) {
            return;
        }
        Direction bottom = state.getValue(BOTTOM);
        if (bottom == Direction.DOWN) {
            // real vanilla rail behavior: removal check + updateState (the
            // 4-arg overload below re-evaluates switchable junctions)
            super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving);
            return;
        }
        // local-frame port of vanilla shouldBeRemoved: bottom support, plus
        // the raised end of an ascending rail needs support one cell along
        // the local ascent direction
        if (shouldBeRemovedLocal(state, level, pos, bottom)) {
            dropResources(state, level, pos);
            level.removeBlock(pos, isMoving);
            return;
        }
        // vanilla RailBlock.updateState: a redstone component next to a
        // 3-way junction re-evaluates the curve (switchable junctions)
        if (neighborBlock.defaultBlockState().isSignalSource()
            && new StickyRailState(level, pos, state).countPotentialConnections() == 3) {
            this.updateDir(level, pos, state, false);
        }
    }

    /** Local-frame port of vanilla {@code BaseRailBlock.shouldBeRemoved}. */
    private boolean shouldBeRemovedLocal(BlockState state, Level level, BlockPos pos, Direction bottom) {
        if (!this.canSurvive(state, level, pos)) {
            return true;
        }
        Direction ascent = switch (state.getValue(SHAPE)) {
            case ASCENDING_EAST -> Direction.EAST;
            case ASCENDING_WEST -> Direction.WEST;
            case ASCENDING_NORTH -> Direction.NORTH;
            case ASCENDING_SOUTH -> Direction.SOUTH;
            default -> null;
        };
        if (ascent == null) {
            return false;
        }
        // vanilla: the block at pos.<ascent>() must support a rigid block on
        // top; local frame: that block must be sturdy toward local UP
        BlockPos sidePos = localNeighbor(pos, state, ascent);
        return !sturdyToward(level, sidePos, bottom);
    }

    /**
     * Vanilla {@code RailBlock.updateState} (the redstone junction hook called
     * from {@code BaseRailBlock.neighborChanged} on the DOWN/vanilla path).
     * {@code RailState.countPotentialConnections} is package-private to
     * vanilla, so the counting (its {@code hasRail} probe per horizontal
     * side) is inlined here verbatim.
     */
    @Override
    protected void updateState(BlockState state, Level level, BlockPos pos, Block neighborBlock) {
        if (neighborBlock.defaultBlockState().isSignalSource()
            && vanillaPotentialConnections(level, pos) == 3) {
            this.updateDir(level, pos, state, false);
        }
    }

    /** Vanilla {@code RailState.countPotentialConnections}, inlined. */
    private static int vanillaPotentialConnections(Level level, BlockPos pos) {
        int count = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(dir);
            if (BaseRailBlock.isRail(level, side)
                || BaseRailBlock.isRail(level, side.above())
                || BaseRailBlock.isRail(level, side.below())) {
                ++count;
            }
        }
        return count;
    }

    // ---- presentation ----

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int index = Rotation24.index(state.getValue(BOTTOM), state.getValue(SPIN));
        return state.getValue(SHAPE).isAscending() ? SHAPES_ASCENDING[index] : SHAPES_FLAT[index];
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // drawn entirely by StickyRailRenderer (vanilla rail model rotated by
        // the orientation quaternion)
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StickyRailBlockEntity(pos, state);
    }
}

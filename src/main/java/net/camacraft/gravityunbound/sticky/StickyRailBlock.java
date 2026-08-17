package net.camacraft.gravityunbound.sticky;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sticky Rail — a minecart rail placeable in any of the 24 grid orientations
 * of the Gravity Block Framework ({@link #BOTTOM} x {@link #SPIN}, see
 * {@link Rotation24}): on walls, ceilings, any cube face. Minecarts that
 * reach a sticky rail have their gravity pinned to the rail's frame and ride
 * it with the vanilla track math re-run in the rail's LOCAL coordinates (see
 * {@code StickyRailMinecartLogic} and {@code AbstractMinecartMixin}).
 *
 * <p>The {@link #SHAPE} property is the vanilla {@link RailShape}, interpreted
 * in the rail's LOCAL frame (a NORTH_SOUTH rail on a wall runs along the
 * wall's local north-south, i.e. {@code Rotation24.localToWorld(NORTH,
 * bottom, spin)} in the world). Placement attaches the rail to the CLICKED
 * surface: bottom = clicked face's opposite; the spin comes from the placer's
 * look via {@link Rotation24#fromClickedFace}. Neighbor discovery and shape
 * auto-connection mirror vanilla {@code RailState}, run per-rail in that
 * rail's local frame ({@link StickyRailState}); only sticky rails with the
 * SAME {@code BOTTOM} connect (spins may differ — connections are compared as
 * grid positions, so differently-spun rails on one face link up fine).
 *
 * <p><b>Scope (this milestone):</b> rails within ONE shared orientation — a
 * whole face/wall of rails with the same BOTTOM. Straight rails and the four
 * curves are supported. Explicitly OUT of scope, deferred to the NEXT
 * milestone:
 * <ul>
 *   <li><b>Cross-frame linking around cube edges</b> — a track that runs off
 *       the edge of one face and continues on an adjacent face (different
 *       BOTTOM). Needs edge-adjacency neighbor discovery (the neighbor cell
 *       of the last rail is around the corner), a handoff in the minecart
 *       logic (re-pin gravity to the next frame mid-transition), and shape
 *       connection across frames.</li>
 *   <li>Ascending (slope) shapes — the movement/track math is ported
 *       generically (exit vectors carry the vertical component), but the
 *       SHAPE property excludes ascending values and {@link StickyRailState}
 *       never promotes to them, pending slope models and support checks.</li>
 *   <li>Powered/activator/detector variants (redstone in rotated frames).</li>
 *   <li>Carts on Valkyrien Skies ships (the rail frame is computed in world
 *       grid space; ship-grid rails need the shipyard transform threaded
 *       through the minecart logic).</li>
 * </ul>
 *
 * Collision: none (like vanilla rails); the outline is the flat rail box
 * rotated by the orientation. The rail pops off (drops itself) when its
 * supporting block — the neighbor at the BOTTOM direction — no longer offers
 * a sturdy face, mirroring vanilla rails popping off broken ground.
 */
public class StickyRailBlock extends BaseEntityBlock {

    /** World direction the rail's LOCAL DOWN points (shared with the chest). */
    public static final DirectionProperty BOTTOM = StickyChestBlock.BOTTOM;
    /** Quarter turns about the local vertical axis (shared with the chest). */
    public static final IntegerProperty SPIN = StickyChestBlock.SPIN;
    /**
     * Track shape in the rail's LOCAL frame. Ascending values are excluded
     * this milestone (see class javadoc).
     */
    public static final EnumProperty<RailShape> SHAPE = EnumProperty.create(
        "shape", RailShape.class,
        RailShape.NORTH_SOUTH, RailShape.EAST_WEST,
        RailShape.SOUTH_EAST, RailShape.SOUTH_WEST,
        RailShape.NORTH_WEST, RailShape.NORTH_EAST
    );

    /** Vanilla flat rail box, rotated into all 24 orientations. */
    private static final VoxelShape[] SHAPES = new VoxelShape[Rotation24.COUNT];

    static {
        VoxelShape flat = Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0);
        for (Direction bottom : Direction.values()) {
            for (int spin = 0; spin < 4; spin++) {
                SHAPES[Rotation24.index(bottom, spin)] = Rotation24.rotateShape(flat, bottom, spin);
            }
        }
    }

    public StickyRailBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.NONE)
            .noCollission()
            .noOcclusion()
            .strength(0.7f)
            .sound(SoundType.METAL)
        );
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(BOTTOM, Direction.DOWN)
            .setValue(SPIN, 0)
            .setValue(SHAPE, RailShape.NORTH_SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BOTTOM, SPIN, SHAPE);
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
        // initial shape: local NORTH_SOUTH (the spin already turned the local
        // frame toward the placer, mirroring vanilla's facing-derived pick);
        // the StickyRailState pass on place corrects it from neighbors
        return this.defaultBlockState()
            .setValue(BOTTOM, orientation.bottom())
            .setValue(SPIN, orientation.spin());
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // the supporting block sits at the BOTTOM direction and must offer a
        // sturdy face toward the rail (vanilla: canSupportRigidBlock(below))
        Direction bottom = state.getValue(BOTTOM);
        BlockPos supportPos = pos.relative(bottom);
        return level.getBlockState(supportPos).isFaceSturdy(level, supportPos, bottom.getOpposite());
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!oldState.is(this)) {
            // vanilla BaseRailBlock.onPlace -> updateState -> updateDir
            this.updateDir(level, pos, state, true);
        }
    }

    /**
     * Vanilla {@code BaseRailBlock.updateDir}: re-runs the local-frame
     * connection pass and writes the corrected shape (server only).
     */
    public BlockState updateDir(Level level, BlockPos pos, BlockState state, boolean alwaysPlace) {
        if (level.isClientSide) {
            return state;
        }
        RailShape shape = state.getValue(SHAPE);
        return new StickyRailState(level, pos, state)
            .place(level.hasNeighborSignal(pos), alwaysPlace, shape)
            .getState();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean isMoving) {
        if (level.isClientSide || !level.getBlockState(pos).is(this)) {
            return;
        }
        // pop off when the supporting block is gone (vanilla shouldBeRemoved;
        // no ascending shapes, so the bottom support is the only requirement)
        if (!this.canSurvive(state, level, pos)) {
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

    // ---- presentation ----

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[Rotation24.index(state.getValue(BOTTOM), state.getValue(SPIN))];
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

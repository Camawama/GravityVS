package net.camacraft.gravityunbound.sticky;

import java.util.EnumMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Pair;

import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * Frame math and rail geometry for minecarts on {@link StickyRailBlock}s —
 * the pure-static support for the on-track tick port in
 * {@code mixin.AbstractMinecartMixin} (which owns the parts that need
 * protected {@code AbstractMinecart} access).
 *
 * <p><b>Physics model (no gravity pin).</b> The cart KEEPS ITS OWN gravity —
 * whatever fields or normal gravity give it — and the rail CONSTRAINS it:
 * the position clamp onto the track chord holds the cart laterally, and the
 * only accelerating influence of gravity on a riding cart is its projection
 * onto the track line ({@code slopeAdjustment * (gravityUnit . trackDir)},
 * the generalization of vanilla's hardcoded per-slope thrust — see the mixin).
 * A cart on a wall rail under normal world gravity sits still on a
 * world-horizontal track (gravity perpendicular to the track) and rolls along
 * a track that has a component along gravity.
 *
 * <p><b>Coordinate systems.</b> Three frames are involved:
 * <ul>
 *   <li><b>World</b> — absolute grid coordinates.</li>
 *   <li><b>Rail frame</b> — the cardinal frame whose local DOWN is the rail's
 *       BOTTOM, using the mod's ENTITY convention
 *       ({@link RotationUtil#vecWorldToEntity}). Positions map through
 *       {@link #worldPosToRail}/{@link #railPosToWorld}, a rotation about the
 *       rail cell's center: quarter turns about a cell center map the block
 *       lattice onto itself, so the rail's own cell keeps its integer
 *       coordinates and {@code Mth.floor} cell logic ports verbatim.</li>
 *   <li><b>Cart-local</b> — the frame the cart's {@code deltaMovement} lives
 *       in (this mod keeps minecart dm LOCAL; {@code Entity.move} transforms
 *       it). Mirrors {@code EntityMixin}'s convention exactly: the entity
 *       convention at the settled cardinal, else the visual-rotation frame.
 *       With the gravity pin gone this is NOT generally the rail frame — all
 *       track math converts cart-local &lt;-&gt; rail frame explicitly.</li>
 * </ul>
 *
 * <p>The {@link RailShape} of a rail is interpreted in that rail's OWN
 * {@link Rotation24} frame (bottom + spin); {@link #exitsInRailFrame} maps
 * the vanilla per-shape exit vectors rail-local -> world -> rail frame with
 * exact integer math, so rails of different spin on one face agree on the
 * world-space track chords.
 */
public final class StickyRailMinecartLogic {

    /** The sticky rail a cart is on. */
    public record RailHit(BlockPos pos, BlockState state, Direction bottom) {}

    /** What the minecart tick should do this tick (see {@link #decide}). */
    public enum Decision {
        /** No sticky involvement — let the vanilla tick run untouched. */
        VANILLA,
        /** Ride {@code hit} with the local-frame track port. */
        RIDE,
        /**
         * Vanilla's world-frame detection would misread a NON-DOWN sticky
         * rail as a flat rail: cancel the vanilla tick and run the ported
         * off-track branch instead.
         */
        OFF_TRACK
    }

    /** A {@link Decision} plus the rail it applies to (RIDE only). */
    public record DetectResult(Decision decision, @Nullable RailHit hit) {
        static final DetectResult VANILLA = new DetectResult(Decision.VANILLA, null);
        static final DetectResult OFF_TRACK = new DetectResult(Decision.OFF_TRACK, null);

        static DetectResult ride(BlockPos pos, BlockState state) {
            return new DetectResult(Decision.RIDE, new RailHit(pos, state, state.getValue(StickyRailBlock.BOTTOM)));
        }
    }

    /**
     * Vanilla {@code AbstractMinecart.EXITS}: the two cell offsets a cart can
     * leave each rail shape through, in the RAIL's local frame (the vertical
     * component is -1 on the low end of ascending shapes).
     */
    private static final Map<RailShape, Pair<Vec3i, Vec3i>> EXITS = new EnumMap<>(RailShape.class);

    static {
        Vec3i west = Direction.WEST.getNormal();
        Vec3i east = Direction.EAST.getNormal();
        Vec3i north = Direction.NORTH.getNormal();
        Vec3i south = Direction.SOUTH.getNormal();
        Vec3i westBelow = west.below();
        Vec3i eastBelow = east.below();
        Vec3i northBelow = north.below();
        Vec3i southBelow = south.below();
        EXITS.put(RailShape.NORTH_SOUTH, Pair.of(north, south));
        EXITS.put(RailShape.EAST_WEST, Pair.of(west, east));
        EXITS.put(RailShape.ASCENDING_EAST, Pair.of(westBelow, east));
        EXITS.put(RailShape.ASCENDING_WEST, Pair.of(west, eastBelow));
        EXITS.put(RailShape.ASCENDING_NORTH, Pair.of(north, southBelow));
        EXITS.put(RailShape.ASCENDING_SOUTH, Pair.of(northBelow, south));
        EXITS.put(RailShape.SOUTH_EAST, Pair.of(south, east));
        EXITS.put(RailShape.SOUTH_WEST, Pair.of(south, west));
        EXITS.put(RailShape.NORTH_WEST, Pair.of(north, west));
        EXITS.put(RailShape.NORTH_EAST, Pair.of(north, east));
    }

    // ------------------------------------------------------------------
    // rail detection
    // ------------------------------------------------------------------

    /**
     * Decides how this server tick should treat the cart with respect to
     * sticky rails. Mirrors the vanilla detection order first (step down when
     * the world-below cell is tagged {@code minecraft:rails}, else the cart's
     * own cell) so DOWN sticky rails and vanilla rails keep exact vanilla
     * behavior, then adds the rotated-frame attach rules:
     * <ul>
     *   <li>own cell holds a non-DOWN sticky rail — ride it (this is also how
     *       a cart of any gravity first attaches: overlap the rail cell);</li>
     *   <li>the cell one step toward the CART's cardinal gravity holds a
     *       sticky rail whose BOTTOM is that direction — the rotated
     *       "falling onto the rail below" step-down, full-cell like
     *       vanilla's;</li>
     *   <li>the cell one step in direction {@code d} holds a sticky rail with
     *       {@code BOTTOM == d} and the cart hugs its track plane (within
     *       half a block of the cell boundary) — keeps carts attached while
     *       climbing an ascending rail into the local-above cell, without
     *       letting a wall rail snatch carts a full block off its plane.</li>
     * </ul>
     * {@code OFF_TRACK} is returned when vanilla's world-frame detection
     * would misread a non-DOWN sticky rail as an ordinary flat rail (it IS a
     * {@code BaseRailBlock} in the rails tag) and none of the attach rules
     * apply — the mixin then runs the ported off-track branch instead of
     * letting vanilla run world-frame track math on a rotated rail.
     */
    public static DetectResult decide(Level level, Vec3 cartPos, Direction cartGravityDown) {
        BlockPos cell = BlockPos.containing(cartPos);
        BlockPos below = cell.below();
        BlockState belowState = level.getBlockState(below);
        boolean steppedDown = belowState.is(BlockTags.RAILS);
        BlockPos stepped = steppedDown ? below : cell;
        BlockState steppedState = steppedDown ? belowState : level.getBlockState(cell);

        if (steppedState.getBlock() instanceof StickyRailBlock) {
            Direction bottom = steppedState.getValue(StickyRailBlock.BOTTOM);
            if (bottom == Direction.DOWN) {
                return DetectResult.VANILLA; // a real vanilla rail
            }
            if (stepped.equals(cell)) {
                return DetectResult.ride(cell, steppedState);
            }
            // vanilla stepped down onto a rotated rail it would misread; if
            // the cart's own cell holds a rotated rail, prefer riding that
            BlockState cellState = level.getBlockState(cell);
            if (cellState.getBlock() instanceof StickyRailBlock
                && cellState.getValue(StickyRailBlock.BOTTOM) != Direction.DOWN) {
                return DetectResult.ride(cell, cellState);
            }
            return DetectResult.OFF_TRACK;
        }
        if (BaseRailBlock.isRail(steppedState)) {
            return DetectResult.VANILLA; // an actual vanilla rail; vanilla handles
        }

        // rotated step-down: falling (by the cart's own gravity) onto a rail
        // mounted on the surface the cart is falling toward
        if (cartGravityDown != Direction.DOWN) {
            BlockPos gravityCell = cell.relative(cartGravityDown);
            BlockState gravityState = level.getBlockState(gravityCell);
            if (StickyRailBlock.isSameFrameRail(gravityState, cartGravityDown)) {
                return DetectResult.ride(gravityCell, gravityState);
            }
        }

        // plane-hug: adjacent rail whose local UP points at the cart's cell,
        // with the cart within half a block of the track plane (covers the
        // top of an ascending climb, where the cart briefly enters the
        // local-above cell while hugging the plane)
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN || d == cartGravityDown) {
                continue;
            }
            BlockPos neighbor = cell.relative(d);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!StickyRailBlock.isSameFrameRail(neighborState, d)) {
                continue;
            }
            Vec3 railLocal = worldPosToRail(cartPos, Vec3.atCenterOf(neighbor), d);
            if (railLocal.y - neighbor.getY() <= 1.5) {
                return DetectResult.ride(neighbor, neighborState);
            }
        }

        return DetectResult.VANILLA;
    }

    // ------------------------------------------------------------------
    // frame transforms
    // ------------------------------------------------------------------

    /**
     * Cart-local -> world, using the SAME convention {@code EntityMixin}
     * applies to {@code Entity.move} for non-players: the exact entity
     * convention at the settled cardinal, else the visual-rotation frame.
     */
    public static Vec3 cartLocalToWorld(GravityCapabilityImpl comp, Vec3 v) {
        Direction settled = comp.getSettledCardinal();
        if (settled != null) {
            return RotationUtil.vecEntityToWorld(v, settled);
        }
        return RotationUtil.vecPlayerToWorld(v, comp.getVisualRotation());
    }

    /** Exact inverse of {@link #cartLocalToWorld}. */
    public static Vec3 worldToCartLocal(GravityCapabilityImpl comp, Vec3 v) {
        Direction settled = comp.getSettledCardinal();
        if (settled != null) {
            return RotationUtil.vecWorldToEntity(v, settled);
        }
        return RotationUtil.vecWorldToPlayer(v, comp.getVisualRotation());
    }

    /**
     * World -> the cart's VISUAL/render frame (player convention). This — not
     * {@link #worldToCartLocal}'s entity convention — is the frame the render
     * dispatcher poses the cart in, so YAW must be computed here. (The two
     * conventions differ for UP gravity, where the entity convention is a
     * reflection; deriving yaw from it mirrored the cart on ceiling tracks.)
     */
    public static Vec3 worldToCartVisual(GravityCapabilityImpl comp, Vec3 v) {
        return RotationUtil.vecWorldToPlayer(v, comp.getVisualRotation());
    }

    /** Cart-local vector -> rail frame. */
    public static Vec3 cartToRail(GravityCapabilityImpl comp, Vec3 v, Direction bottom) {
        return RotationUtil.vecWorldToEntity(cartLocalToWorld(comp, v), bottom);
    }

    /** Rail-frame vector -> cart-local. */
    public static Vec3 railToCart(GravityCapabilityImpl comp, Vec3 v, Direction bottom) {
        return worldToCartLocal(comp, RotationUtil.vecEntityToWorld(v, bottom));
    }

    /**
     * The cart's own gravity direction as a world-frame UNIT vector: the true
     * (possibly arbitrary-angle) field vector when one is active, else the
     * cardinal physics direction. This is what the rail projects — the cart
     * keeps its own gravity; the rail only constrains it.
     */
    public static Vec3 cartGravityUnit(GravityCapabilityImpl comp) {
        Vec3 target = comp.getTargetGravityVector();
        if (target.lengthSqr() > 1.0E-8) {
            return target.normalize();
        }
        return comp.getCurrGravityDirectionVec();
    }

    /**
     * World position -> rail-frame position, rotating about the rail cell's
     * center {@code center} (= {@code Vec3.atCenterOf(railPos)}). Maps the
     * block lattice onto itself; the rail's own cell keeps its integer
     * coordinates.
     */
    public static Vec3 worldPosToRail(Vec3 worldPos, Vec3 center, Direction bottom) {
        return RotationUtil.vecWorldToEntity(worldPos.subtract(center), bottom).add(center);
    }

    /** Exact inverse of {@link #worldPosToRail}. */
    public static Vec3 railPosToWorld(Vec3 railPos, Vec3 center, Direction bottom) {
        return RotationUtil.vecEntityToWorld(railPos.subtract(center), bottom).add(center);
    }

    /**
     * The world cell corresponding to rail-frame cell {@code (i, j, k)}.
     * Cell centers map to cell centers (exact *.5 coordinates under signed
     * permutations), so the floor recovers the world cell exactly.
     */
    public static BlockPos railCellToWorldCell(int i, int j, int k, Vec3 center, Direction bottom) {
        Vec3 worldCenter = railPosToWorld(new Vec3(i + 0.5, j + 0.5, k + 0.5), center, bottom);
        return BlockPos.containing(worldCenter);
    }

    /**
     * The vanilla exit pair of {@code shape}, mapped from the given rail's
     * OWN {@link Rotation24} local frame into the shared rail cardinal frame.
     * Exact integer math throughout (direction-table mapping, then a signed
     * permutation).
     */
    public static Pair<Vec3i, Vec3i> exitsInRailFrame(RailShape shape, Direction bottom, int spin) {
        Pair<Vec3i, Vec3i> raw = EXITS.get(shape);
        return Pair.of(
            railLocalExitToRailFrame(raw.getFirst(), bottom, spin),
            railLocalExitToRailFrame(raw.getSecond(), bottom, spin)
        );
    }

    private static Vec3i railLocalExitToRailFrame(Vec3i exit, Direction bottom, int spin) {
        // rail-local integer vector -> world integer vector via the exact
        // Rotation24 direction tables (decomposed per axis)
        int wx = 0;
        int wy = 0;
        int wz = 0;
        if (exit.getX() != 0) {
            Vec3i axis = Rotation24.localToWorld(Direction.EAST, bottom, spin).getNormal();
            wx += exit.getX() * axis.getX();
            wy += exit.getX() * axis.getY();
            wz += exit.getX() * axis.getZ();
        }
        if (exit.getY() != 0) {
            Vec3i axis = Rotation24.localToWorld(Direction.UP, bottom, spin).getNormal();
            wx += exit.getY() * axis.getX();
            wy += exit.getY() * axis.getY();
            wz += exit.getY() * axis.getZ();
        }
        if (exit.getZ() != 0) {
            Vec3i axis = Rotation24.localToWorld(Direction.SOUTH, bottom, spin).getNormal();
            wx += exit.getZ() * axis.getX();
            wy += exit.getZ() * axis.getY();
            wz += exit.getZ() * axis.getZ();
        }
        // world -> rail cardinal frame: exact signed permutation of integers
        Vec3 frame = RotationUtil.vecWorldToEntity(wx, wy, wz, bottom);
        return new Vec3i((int) Math.round(frame.x), (int) Math.round(frame.y), (int) Math.round(frame.z));
    }

    // ------------------------------------------------------------------
    // track position (vanilla AbstractMinecart.getPos, local frame)
    // ------------------------------------------------------------------

    /**
     * Port of vanilla {@code AbstractMinecart.getPos(x, y, z)}: the exact
     * on-track position for a cart at rail-frame coordinates
     * {@code (x, y, z)} — clamped to the track chord, lifted 1/16 above the
     * rail plane — or null when the cell (or the cell local-below it) holds
     * no sticky rail of this frame. Input and output are rail-frame
     * coordinates relative to the SAME {@code center}/{@code bottom} mapping.
     */
    @Nullable
    public static Vec3 railTrackPos(Level level, double x, double y, double z, Vec3 center, Direction bottom) {
        int i = Mth.floor(x);
        int j = Mth.floor(y);
        int k = Mth.floor(z);
        // vanilla: "if the block below is a rail, step down"
        BlockPos belowCell = railCellToWorldCell(i, j - 1, k, center, bottom);
        if (StickyRailBlock.isSameFrameRail(level.getBlockState(belowCell), bottom)) {
            --j;
        }

        BlockPos worldCell = railCellToWorldCell(i, j, k, center, bottom);
        BlockState railState = level.getBlockState(worldCell);
        if (!StickyRailBlock.isSameFrameRail(railState, bottom)) {
            return null;
        }
        RailShape shape = railState.getValue(StickyRailBlock.SHAPE);
        Pair<Vec3i, Vec3i> exits = exitsInRailFrame(shape, bottom, railState.getValue(StickyRailBlock.SPIN));
        Vec3i exitA = exits.getFirst();
        Vec3i exitB = exits.getSecond();
        double ax = i + 0.5 + exitA.getX() * 0.5;
        double ay = j + 0.0625 + exitA.getY() * 0.5;
        double az = k + 0.5 + exitA.getZ() * 0.5;
        double bx = i + 0.5 + exitB.getX() * 0.5;
        double by = j + 0.0625 + exitB.getY() * 0.5;
        double bz = k + 0.5 + exitB.getZ() * 0.5;
        double dx = bx - ax;
        double dy = (by - ay) * 2.0;
        double dz = bz - az;
        double t;
        if (dx == 0.0) {
            t = z - k;
        } else if (dz == 0.0) {
            t = x - i;
        } else {
            double ox = x - ax;
            double oz = z - az;
            t = (ox * dx + oz * dz) * 2.0;
        }

        x = ax + dx * t;
        y = ay + dy * t;
        z = az + dz * t;
        if (dy < 0.0) {
            ++y;
        } else if (dy > 0.0) {
            y += 0.5;
        }

        return new Vec3(x, y, z);
    }

    private StickyRailMinecartLogic() {}
}

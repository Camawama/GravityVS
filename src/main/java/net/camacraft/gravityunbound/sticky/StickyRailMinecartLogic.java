package net.camacraft.gravityunbound.sticky;

import java.util.EnumMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Pair;

import net.camacraft.gravityunbound.api.RotationParameters;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * Frame math and rail geometry for minecarts on {@link StickyRailBlock}s —
 * the pure-static support for the on-track tick port in
 * {@code mixin.AbstractMinecartMixin} (which owns the parts that need
 * protected {@code AbstractMinecart} access).
 *
 * <p><b>Coordinate systems.</b> Three frames are involved:
 * <ul>
 *   <li><b>World</b> — absolute grid coordinates.</li>
 *   <li><b>Rail frame</b> — the cardinal frame whose local DOWN is the rail's
 *       BOTTOM, using the mod's ENTITY convention
 *       ({@link RotationUtil#vecWorldToEntity}) — the exact signed-permutation
 *       math non-player entities move in when settled. Positions map through
 *       {@link #worldPosToRail}/{@link #railPosToWorld}, a rotation about the
 *       rail cell's center: quarter turns about a cell center map the block
 *       lattice onto itself, so the rail's own cell keeps its integer
 *       coordinates and {@code Mth.floor} cell logic ports verbatim.</li>
 *   <li><b>Cart-local</b> — the frame the cart's {@code deltaMovement} lives
 *       in (this mod keeps minecart dm LOCAL; {@code Entity.move} transforms
 *       it). Mirrors {@code EntityMixin}'s convention exactly: the entity
 *       convention at the settled cardinal, else the visual-rotation frame.
 *       Once the gravity pin has settled on the rail's bottom, cart-local ==
 *       rail frame and all conversions here are BIT-EXACT identities (signed
 *       permutations are exact in doubles).</li>
 * </ul>
 *
 * <p>The {@link RailShape} of a rail is interpreted in that rail's OWN
 * {@link Rotation24} frame (bottom + spin); {@link #exitsInRailFrame} maps
 * the vanilla per-shape exit vectors rail-local -> world -> rail frame with
 * exact integer math, so rails of different spin on one face agree on the
 * world-space track chords.
 */
public final class StickyRailMinecartLogic {

    /**
     * Priority of the on-rail gravity pin: far above plating fields
     * (~1000 minus distance) and outside their 5.0 blend range, so no field
     * can bend a cart off its track.
     */
    public static final double PIN_PRIORITY = 10000.0;

    /**
     * rotateVelocity=false like the other field sources: crossing onto a
     * rail conserves world momentum and gravity only redirects future
     * acceleration; the rotation time applies to the discrete frame flip.
     */
    private static final RotationParameters PIN_ROTATION_PARAMS = new RotationParameters(false, true, 300);

    /** The sticky rail a cart is on. */
    public record RailHit(BlockPos pos, BlockState state, Direction bottom) {}

    /**
     * Vanilla {@code AbstractMinecart.EXITS}: the two cell offsets a cart can
     * leave each rail shape through, in the RAIL's local frame. The vertical
     * component (-1 on the low end of ascending shapes) is carried so slope
     * support can slot in later; the flat shapes never set it.
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
    // rail lookup
    // ------------------------------------------------------------------

    /**
     * The sticky rail the cart is riding: the cell one step toward
     * {@code gravityDown} first (the local-frame port of vanilla's "the block
     * below is a rail" step-down — a cart sits a hair above the rail plane
     * and can drift into the next cell up), then the cart's own cell (this is
     * also how a cart first ATTACHES to a rail of a different frame: overlap
     * its cell and the pin takes over). Null when neither holds a sticky
     * rail.
     */
    @Nullable
    public static RailHit findStickyRail(Level level, Vec3 cartPos, Direction gravityDown) {
        BlockPos cell = BlockPos.containing(cartPos);
        BlockPos stepped = cell.relative(gravityDown);
        BlockState state = level.getBlockState(stepped);
        if (StickyRailBlock.isStickyRail(state)) {
            return new RailHit(stepped, state, state.getValue(StickyRailBlock.BOTTOM));
        }
        state = level.getBlockState(cell);
        if (StickyRailBlock.isStickyRail(state)) {
            return new RailHit(cell, state, state.getValue(StickyRailBlock.BOTTOM));
        }
        return null;
    }

    /**
     * Pins the cart's gravity to the rail's frame for this tick: a
     * high-priority primary direction effect along the rail's local-down
     * world vector, with surface alignment off (the raw rail direction is the
     * whole point — no planet-walk snapping). Re-applied every on-rail tick,
     * ages out through the capability's normal field machinery when the cart
     * leaves the track.
     */
    public static void applyGravityPin(GravityCapabilityImpl comp, Direction bottom) {
        comp.applyGravityDirectionEffect(
            Vec3.atLowerCornerOf(bottom.getNormal()),
            PIN_ROTATION_PARAMS, PIN_PRIORITY, false, 1.0, false
        );
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

    /** Cart-local vector -> rail frame (identity once the pin has settled). */
    public static Vec3 cartToRail(GravityCapabilityImpl comp, Vec3 v, Direction bottom) {
        return RotationUtil.vecWorldToEntity(cartLocalToWorld(comp, v), bottom);
    }

    /** Rail-frame vector -> cart-local (identity once the pin has settled). */
    public static Vec3 railToCart(GravityCapabilityImpl comp, Vec3 v, Direction bottom) {
        return worldToCartLocal(comp, RotationUtil.vecEntityToWorld(v, bottom));
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

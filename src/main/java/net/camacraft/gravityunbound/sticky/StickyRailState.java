package net.camacraft.gravityunbound.sticky;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Local-frame port of vanilla {@code net.minecraft.world.level.block.RailState}
 * (1.20.1) for NON-DOWN {@link StickyRailBlock}s (DOWN rails run the real
 * vanilla {@code RailState}): the neighbor discovery and shape auto-connection
 * logic, with every "north/south/east/west/above/below" evaluated in THIS
 * rail's LOCAL frame ({@code Rotation24.localToWorld(local, bottom, spin)})
 * and every connection stored/compared as a real GRID position. Because
 * positions — not local directions — are what rails exchange, two rails with
 * the same BOTTOM but different SPIN connect correctly: each interprets the
 * shared grid offset in its own frame.
 *
 * <p>Slopes ARE supported within one frame ({@code canMakeSlopes} = true,
 * matching the flexible vanilla rail): ascending shapes climb along the
 * rail's local UP — away from the mounting surface — and the above/below
 * probing of vanilla's {@code hasRail}/{@code getRail} is ported along the
 * local vertical axis.
 *
 * <p>Deviations from vanilla, deliberate:
 * <ul>
 *   <li>Only sticky rails with the SAME BOTTOM count as neighbors (one
 *       shared orientation per track). Cross-frame linking around cube
 *       edges/concave corners is deferred — a floor rail meeting a wall rail
 *       at an inside corner does not yet bridge via an ascending shape.</li>
 *   <li>All shapes a flexible rail can take are valid (vanilla's Forge
 *       {@code isValidRailShape} hook always passes here).</li>
 * </ul>
 *
 * <p>All {@code level.setBlock} writes are wrapped in
 * {@link StickyRailBlock#LOCAL_UPDATE_DEPTH} so {@code onPlace} can tell them
 * apart from foreign (world-frame) writes and self-heal only the latter.
 */
public class StickyRailState {

    private final Level level;
    private final BlockPos pos;
    private final StickyRailBlock block;
    private BlockState state;
    private final Direction bottom;
    /** Vanilla: false for the plain rail (flexible, can curve). */
    private final boolean isStraight = false;
    /** Vanilla Forge hook default: flexible rails can make slopes. */
    private final boolean canMakeSlopes = true;
    private final List<BlockPos> connections = new ArrayList<>();

    public StickyRailState(Level level, BlockPos pos, BlockState state) {
        this.level = level;
        this.pos = pos;
        this.state = state;
        this.block = (StickyRailBlock) state.getBlock();
        this.bottom = state.getValue(StickyRailBlock.BOTTOM);
        this.updateConnections(state.getValue(StickyRailBlock.SHAPE));
    }

    public List<BlockPos> getConnections() {
        return this.connections;
    }

    // ---- local-frame helpers ----

    /** Grid neighbor along one of THIS rail's local directions. */
    private BlockPos local(Direction localDir) {
        return StickyRailBlock.localNeighbor(this.pos, this.state, localDir);
    }

    /** {@code base} shifted one cell along THIS rail's local UP. */
    private BlockPos aboveLocal(BlockPos base) {
        return base.relative(this.bottom.getOpposite());
    }

    /** {@code base} shifted one cell along THIS rail's local DOWN. */
    private BlockPos belowLocal(BlockPos base) {
        return base.relative(this.bottom);
    }

    private boolean isSameFrameRailAt(BlockPos checkPos) {
        return StickyRailBlock.isSameFrameRail(this.level.getBlockState(checkPos), this.bottom);
    }

    /** World direction of one of THIS rail's local directions. */
    private Direction worldDir(Direction localDir) {
        return Rotation24.localToWorld(
            localDir, this.bottom, this.state.getValue(StickyRailBlock.SPIN));
    }

    // ---- CROSS-FRAME LINKING (the between-axes connections) ----
    //
    // A track continuing in local direction d can leave this rail's frame in
    // exactly three ways, each identified by the partner's position AND its
    // BOTTOM (the frame "rolls" across the junction line):
    //
    //   CONCAVE (into a corner): the wall rail directly local-ABOVE this
    //   cell, mounted on the wall ahead — its bottom IS the world travel
    //   direction. This rail becomes an ASCENDING ramp toward the wall
    //   (the slope the junction needs, and the physical climb path that
    //   carries a cart up into the wall rail's cell).
    //
    //   WALL-BASE (the same corner seen from the wall side): the LATERAL
    //   neighbor whose bottom is the world travel direction (the floor rail
    //   at the base of this wall). Flat — the wall rail's plane already
    //   meets the floor at the fold line.
    //
    //   CONVEX (over an edge): one step out and one local-DOWN, mounted on
    //   the far face of the edge — its bottom is OPPOSITE the world travel
    //   direction. Flat on both sides; the two rail planes meet at the
    //   shared edge.
    //
    // Partners count for SHAPE resolution (axis choice, curve suppression,
    // the concave ascent) but are not stored in the connections list — each
    // side of a junction independently discovers the other through the
    // complementary pattern, so shapes converge without cross-frame writes.

    /** The cross-frame partner continuing local direction {@code d}, if any. */
    private boolean hasCrossFramePartner(Direction localDir) {
        return this.crossFramePartnerPos(localDir) != null;
    }

    /** Position of the cross-frame partner in local direction {@code d}, or null. */
    @Nullable
    private BlockPos crossFramePartnerPos(Direction localDir) {
        if (this.hasConcavePartner(localDir)) {
            return this.aboveLocal(this.pos);
        }
        if (this.hasWallBasePartner(localDir)) {
            return this.local(localDir);
        }
        if (this.hasConvexPartner(localDir)) {
            return this.belowLocal(this.local(localDir));
        }
        return null;
    }

    private boolean hasConcavePartner(Direction localDir) {
        return StickyRailBlock.isSameFrameRail(
            this.level.getBlockState(this.aboveLocal(this.pos)), this.worldDir(localDir));
    }

    private boolean hasWallBasePartner(Direction localDir) {
        return StickyRailBlock.isSameFrameRail(
            this.level.getBlockState(this.local(localDir)), this.worldDir(localDir));
    }

    private boolean hasConvexPartner(Direction localDir) {
        return StickyRailBlock.isSameFrameRail(
            this.level.getBlockState(this.belowLocal(this.local(localDir))),
            this.worldDir(localDir).getOpposite());
    }

    /**
     * Vanilla compares connections by X and Z only (ignoring the vertical for
     * slopes). The local-frame equivalent ignores the coordinate along the
     * BOTTOM axis.
     */
    private boolean matchesIgnoringVertical(BlockPos a, BlockPos b) {
        return switch (this.bottom.getAxis()) {
            case X -> a.getY() == b.getY() && a.getZ() == b.getZ();
            case Y -> a.getX() == b.getX() && a.getZ() == b.getZ();
            case Z -> a.getX() == b.getX() && a.getY() == b.getY();
        };
    }

    /** Local-guarded {@code setBlock} (see class javadoc). */
    private void writeState() {
        int[] depth = StickyRailBlock.LOCAL_UPDATE_DEPTH.get();
        depth[0]++;
        try {
            this.level.setBlock(this.pos, this.state, 3);
        } finally {
            depth[0]--;
        }
    }

    // ---- vanilla RailState logic, transliterated ----

    private void updateConnections(RailShape shape) {
        this.connections.clear();
        switch (shape) {
            case NORTH_SOUTH -> {
                this.connections.add(this.local(Direction.NORTH));
                this.connections.add(this.local(Direction.SOUTH));
            }
            case EAST_WEST -> {
                this.connections.add(this.local(Direction.WEST));
                this.connections.add(this.local(Direction.EAST));
            }
            case ASCENDING_EAST -> {
                this.connections.add(this.local(Direction.WEST));
                this.connections.add(this.aboveLocal(this.local(Direction.EAST)));
            }
            case ASCENDING_WEST -> {
                this.connections.add(this.aboveLocal(this.local(Direction.WEST)));
                this.connections.add(this.local(Direction.EAST));
            }
            case ASCENDING_NORTH -> {
                this.connections.add(this.aboveLocal(this.local(Direction.NORTH)));
                this.connections.add(this.local(Direction.SOUTH));
            }
            case ASCENDING_SOUTH -> {
                this.connections.add(this.local(Direction.NORTH));
                this.connections.add(this.aboveLocal(this.local(Direction.SOUTH)));
            }
            case SOUTH_EAST -> {
                this.connections.add(this.local(Direction.EAST));
                this.connections.add(this.local(Direction.SOUTH));
            }
            case SOUTH_WEST -> {
                this.connections.add(this.local(Direction.WEST));
                this.connections.add(this.local(Direction.SOUTH));
            }
            case NORTH_WEST -> {
                this.connections.add(this.local(Direction.WEST));
                this.connections.add(this.local(Direction.NORTH));
            }
            case NORTH_EAST -> {
                this.connections.add(this.local(Direction.EAST));
                this.connections.add(this.local(Direction.NORTH));
            }
        }
    }

    private void removeSoftConnections() {
        for (int i = 0; i < this.connections.size(); ++i) {
            StickyRailState railState = this.getRail(this.connections.get(i));
            if (railState != null && railState.connectsTo(this)) {
                this.connections.set(i, railState.pos);
            } else {
                this.connections.remove(i--);
            }
        }
    }

    /** Vanilla probes the cell and one local-above/local-below for slopes. */
    private boolean hasRail(BlockPos checkPos) {
        return this.isSameFrameRailAt(checkPos)
            || this.isSameFrameRailAt(this.aboveLocal(checkPos))
            || this.isSameFrameRailAt(this.belowLocal(checkPos));
    }

    @Nullable
    private StickyRailState getRail(BlockPos checkPos) {
        BlockState checkState = this.level.getBlockState(checkPos);
        if (StickyRailBlock.isSameFrameRail(checkState, this.bottom)) {
            return new StickyRailState(this.level, checkPos, checkState);
        }
        BlockPos probe = this.aboveLocal(checkPos);
        checkState = this.level.getBlockState(probe);
        if (StickyRailBlock.isSameFrameRail(checkState, this.bottom)) {
            return new StickyRailState(this.level, probe, checkState);
        }
        probe = this.belowLocal(checkPos);
        checkState = this.level.getBlockState(probe);
        if (StickyRailBlock.isSameFrameRail(checkState, this.bottom)) {
            return new StickyRailState(this.level, probe, checkState);
        }
        return null;
    }

    private boolean connectsTo(StickyRailState other) {
        return this.hasConnection(other.pos);
    }

    private boolean hasConnection(BlockPos connectionPos) {
        for (int i = 0; i < this.connections.size(); ++i) {
            if (this.matchesIgnoringVertical(this.connections.get(i), connectionPos)) {
                return true;
            }
        }
        return false;
    }

    public int countPotentialConnections() {
        int count = 0;
        for (Direction localDir : Direction.Plane.HORIZONTAL) {
            if (this.hasRail(this.local(localDir)) || this.hasCrossFramePartner(localDir)) {
                ++count;
            }
        }
        return count;
    }

    private boolean canConnectTo(StickyRailState other) {
        return this.connectsTo(other) || this.connections.size() != 2;
    }

    private void connectTo(StickyRailState other) {
        this.connections.add(other.pos);
        BlockPos northPos = this.local(Direction.NORTH);
        BlockPos southPos = this.local(Direction.SOUTH);
        BlockPos westPos = this.local(Direction.WEST);
        BlockPos eastPos = this.local(Direction.EAST);
        boolean north = this.hasConnection(northPos);
        boolean south = this.hasConnection(southPos);
        boolean west = this.hasConnection(westPos);
        boolean east = this.hasConnection(eastPos);
        RailShape shape = null;
        if (north || south) {
            shape = RailShape.NORTH_SOUTH;
        }
        if (west || east) {
            shape = RailShape.EAST_WEST;
        }
        if (!this.isStraight) {
            if (south && east && !north && !west) {
                shape = RailShape.SOUTH_EAST;
            }
            if (south && west && !north && !east) {
                shape = RailShape.SOUTH_WEST;
            }
            if (north && west && !south && !east) {
                shape = RailShape.NORTH_WEST;
            }
            if (north && east && !south && !west) {
                shape = RailShape.NORTH_EAST;
            }
        }
        if (shape == RailShape.NORTH_SOUTH && this.canMakeSlopes) {
            if (this.isSameFrameRailAt(this.aboveLocal(northPos))
                || this.hasConcavePartner(Direction.NORTH)) {
                shape = RailShape.ASCENDING_NORTH;
            }
            if (this.isSameFrameRailAt(this.aboveLocal(southPos))
                || this.hasConcavePartner(Direction.SOUTH)) {
                shape = RailShape.ASCENDING_SOUTH;
            }
        }
        if (shape == RailShape.EAST_WEST && this.canMakeSlopes) {
            if (this.isSameFrameRailAt(this.aboveLocal(eastPos))
                || this.hasConcavePartner(Direction.EAST)) {
                shape = RailShape.ASCENDING_EAST;
            }
            if (this.isSameFrameRailAt(this.aboveLocal(westPos))
                || this.hasConcavePartner(Direction.WEST)) {
                shape = RailShape.ASCENDING_WEST;
            }
        }
        if (shape == null) {
            shape = RailShape.NORTH_SOUTH;
        }
        this.state = this.state.setValue(StickyRailBlock.SHAPE, shape);
        this.writeState();
    }

    private boolean hasNeighborRail(BlockPos neighborPos) {
        StickyRailState railState = this.getRail(neighborPos);
        if (railState == null) {
            return false;
        }
        railState.removeSoftConnections();
        return railState.canConnectTo(this);
    }

    /**
     * Vanilla {@code RailState.place}: settles this rail's shape from its
     * neighborhood, writes the state, then lets connectable neighbors curve
     * toward it.
     *
     * @param powered      whether the rail is receiving a redstone signal
     *                     (flips the curve preference order, like vanilla)
     * @param alwaysPlace  force the setBlock even when the state is unchanged
     * @param currentShape the shape to keep when nothing decides otherwise
     */
    public StickyRailState place(boolean powered, boolean alwaysPlace, RailShape currentShape) {
        BlockPos northPos = this.local(Direction.NORTH);
        BlockPos southPos = this.local(Direction.SOUTH);
        BlockPos westPos = this.local(Direction.WEST);
        BlockPos eastPos = this.local(Direction.EAST);
        // cross-frame partners count exactly like same-frame neighbors for
        // the axis/curve decisions — the track continues around the fold
        boolean north = this.hasNeighborRail(northPos) || this.hasCrossFramePartner(Direction.NORTH);
        boolean south = this.hasNeighborRail(southPos) || this.hasCrossFramePartner(Direction.SOUTH);
        boolean west = this.hasNeighborRail(westPos) || this.hasCrossFramePartner(Direction.WEST);
        boolean east = this.hasNeighborRail(eastPos) || this.hasCrossFramePartner(Direction.EAST);
        RailShape shape = null;
        boolean northSouth = north || south;
        boolean eastWest = west || east;
        if (northSouth && !eastWest) {
            shape = RailShape.NORTH_SOUTH;
        }
        if (eastWest && !northSouth) {
            shape = RailShape.EAST_WEST;
        }

        boolean southEast = south && east;
        boolean southWest = south && west;
        boolean northEast = north && east;
        boolean northWest = north && west;
        if (!this.isStraight) {
            if (southEast && !north && !west) {
                shape = RailShape.SOUTH_EAST;
            }
            if (southWest && !north && !east) {
                shape = RailShape.SOUTH_WEST;
            }
            if (northWest && !south && !east) {
                shape = RailShape.NORTH_WEST;
            }
            if (northEast && !south && !west) {
                shape = RailShape.NORTH_EAST;
            }
        }

        if (shape == null) {
            if (northSouth && eastWest) {
                shape = currentShape;
            } else if (northSouth) {
                shape = RailShape.NORTH_SOUTH;
            } else if (eastWest) {
                shape = RailShape.EAST_WEST;
            }

            if (!this.isStraight) {
                if (powered) {
                    if (southEast) {
                        shape = RailShape.SOUTH_EAST;
                    }
                    if (southWest) {
                        shape = RailShape.SOUTH_WEST;
                    }
                    if (northEast) {
                        shape = RailShape.NORTH_EAST;
                    }
                    if (northWest) {
                        shape = RailShape.NORTH_WEST;
                    }
                } else {
                    if (northWest) {
                        shape = RailShape.NORTH_WEST;
                    }
                    if (northEast) {
                        shape = RailShape.NORTH_EAST;
                    }
                    if (southWest) {
                        shape = RailShape.SOUTH_WEST;
                    }
                    if (southEast) {
                        shape = RailShape.SOUTH_EAST;
                    }
                }
            }
        }

        if (shape == RailShape.NORTH_SOUTH && this.canMakeSlopes) {
            if (this.isSameFrameRailAt(this.aboveLocal(northPos))
                || this.hasConcavePartner(Direction.NORTH)) {
                shape = RailShape.ASCENDING_NORTH;
            }
            if (this.isSameFrameRailAt(this.aboveLocal(southPos))
                || this.hasConcavePartner(Direction.SOUTH)) {
                shape = RailShape.ASCENDING_SOUTH;
            }
        }
        if (shape == RailShape.EAST_WEST && this.canMakeSlopes) {
            if (this.isSameFrameRailAt(this.aboveLocal(eastPos))
                || this.hasConcavePartner(Direction.EAST)) {
                shape = RailShape.ASCENDING_EAST;
            }
            if (this.isSameFrameRailAt(this.aboveLocal(westPos))
                || this.hasConcavePartner(Direction.WEST)) {
                shape = RailShape.ASCENDING_WEST;
            }
        }

        if (shape == null) {
            shape = currentShape;
        }

        this.updateConnections(shape);
        this.state = this.state.setValue(StickyRailBlock.SHAPE, shape);
        if (alwaysPlace || this.level.getBlockState(this.pos) != this.state) {
            this.writeState();
            for (int i = 0; i < this.connections.size(); ++i) {
                StickyRailState railState = this.getRail(this.connections.get(i));
                if (railState != null) {
                    railState.removeSoftConnections();
                    if (railState.canConnectTo(this)) {
                        railState.connectTo(this);
                    }
                }
            }
            // Wake cross-frame partners so a junction settles from EITHER
            // placement order (a convex partner is diagonal — vanilla
            // neighbor updates never reach it). Terminates: partner probes
            // read only our POSITION and BOTTOM, never our shape, so a
            // partner's re-settle cannot change what we would compute, and
            // an unchanged re-settle does not write (or wake) again.
            for (Direction localDir : Direction.Plane.HORIZONTAL) {
                BlockPos partnerPos = this.crossFramePartnerPos(localDir);
                if (partnerPos == null) {
                    continue;
                }
                BlockState partnerState = this.level.getBlockState(partnerPos);
                if (partnerState.getBlock() instanceof StickyRailBlock partnerBlock) {
                    partnerBlock.updateDir(this.level, partnerPos, partnerState, false);
                }
            }
        }

        return this;
    }

    public BlockState getState() {
        return this.state;
    }
}

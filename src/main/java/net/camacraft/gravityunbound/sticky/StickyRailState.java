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
 * (1.20.1) for {@link StickyRailBlock}: the neighbor discovery and shape
 * auto-connection logic, with every "north/south/east/west" evaluated in THIS
 * rail's LOCAL frame ({@code Rotation24.localToWorld(local, bottom, spin)})
 * and every connection stored/compared as a real GRID position. Because
 * positions — not local directions — are what rails exchange, two rails with
 * the same BOTTOM but different SPIN connect correctly: each interprets the
 * shared grid offset in its own frame.
 *
 * Deviations from vanilla, all deliberate for this milestone:
 * <ul>
 *   <li>Only sticky rails with the SAME BOTTOM count as neighbors (one
 *       shared orientation per track; cross-frame linking around cube edges
 *       is the next milestone — see {@link StickyRailBlock}).</li>
 *   <li>No slopes: {@code canMakeSlopes} is hard false, the above/below rail
 *       probing of vanilla's {@code hasRail}/{@code getRail} is omitted
 *       (with slopes off, rails one cell off the surface plane are a
 *       different track), and ascending promotion is skipped.</li>
 *   <li>All shapes a flexible rail can take are valid (vanilla's Forge
 *       {@code isValidRailShape} hook always passes here).</li>
 * </ul>
 */
public class StickyRailState {

    private final Level level;
    private final BlockPos pos;
    private final StickyRailBlock block;
    private BlockState state;
    private final Direction bottom;
    /** Vanilla: false for the plain rail (flexible, can curve). */
    private final boolean isStraight = false;
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

    private boolean isSameFrameRailAt(BlockPos checkPos) {
        return StickyRailBlock.isSameFrameRail(this.level.getBlockState(checkPos), this.bottom);
    }

    /**
     * Vanilla compares connections by X and Z only (ignoring the vertical for
     * slopes). The local-frame equivalent ignores the coordinate along the
     * BOTTOM axis. Slopes are off this milestone, but the port keeps the
     * vanilla comparison so slope support can slot in later.
     */
    private boolean matchesIgnoringVertical(BlockPos a, BlockPos b) {
        return switch (this.bottom.getAxis()) {
            case X -> a.getY() == b.getY() && a.getZ() == b.getZ();
            case Y -> a.getX() == b.getX() && a.getZ() == b.getZ();
            case Z -> a.getX() == b.getX() && a.getY() == b.getY();
        };
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
            default -> {
                // ascending shapes are excluded from the SHAPE property
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

    private boolean hasRail(BlockPos checkPos) {
        // vanilla also probes above/below for slopes; same-plane only here
        return this.isSameFrameRailAt(checkPos);
    }

    @Nullable
    private StickyRailState getRail(BlockPos checkPos) {
        BlockState checkState = this.level.getBlockState(checkPos);
        if (StickyRailBlock.isSameFrameRail(checkState, this.bottom)) {
            return new StickyRailState(this.level, checkPos, checkState);
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
            if (this.hasRail(this.local(localDir))) {
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
        // (vanilla promotes NORTH_SOUTH/EAST_WEST to ascending here when a
        // rail sits one cell up — slopes are off this milestone)
        if (shape == null) {
            shape = RailShape.NORTH_SOUTH;
        }
        this.state = this.state.setValue(StickyRailBlock.SHAPE, shape);
        this.level.setBlock(this.pos, this.state, 3);
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
        boolean north = this.hasNeighborRail(northPos);
        boolean south = this.hasNeighborRail(southPos);
        boolean west = this.hasNeighborRail(westPos);
        boolean east = this.hasNeighborRail(eastPos);
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

        // (vanilla ascending promotion skipped — slopes off this milestone)

        if (shape == null) {
            shape = currentShape;
        }

        this.updateConnections(shape);
        this.state = this.state.setValue(StickyRailBlock.SHAPE, shape);
        if (alwaysPlace || this.level.getBlockState(this.pos) != this.state) {
            this.level.setBlock(this.pos, this.state, 3);
            for (int i = 0; i < this.connections.size(); ++i) {
                StickyRailState railState = this.getRail(this.connections.get(i));
                if (railState != null) {
                    railState.removeSoftConnections();
                    if (railState.canConnectTo(this)) {
                        railState.connectTo(this);
                    }
                }
            }
        }

        return this;
    }

    public BlockState getState() {
        return this.state;
    }
}

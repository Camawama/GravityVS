package net.cama.gravityapivs.sticky;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.cama.gravityapivs.api.GravityBlockHelper;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Utility for the 24 grid orientations of the Gravity Block Framework
 * (see docs/GravityBlockFramework_Design.md).
 *
 * An orientation is encoded as {@code (Direction bottomFace, int spin)}:
 *
 * <ul>
 *   <li>{@code bottomFace} — the WORLD direction the block's LOCAL DOWN
 *       points (a chest on the ceiling has {@code bottomFace == UP}).</li>
 *   <li>{@code spin} (0-3) — quarter turns about the block's local vertical
 *       axis, applied IN LOCAL SPACE before the bottom rotation.</li>
 * </ul>
 *
 * Quaternion convention: {@code Q(bottom, spin) = BASE(bottom) * R_localY(spin * 90deg)}
 * where {@code BASE(bottom)} is the canonical single-axis rotation taking
 * local DOWN onto {@code bottomFace} (identity for DOWN, rotX(180) for UP,
 * rotX(+-90) for NORTH/SOUTH, rotZ(+-90) for EAST/WEST) and the spin
 * post-multiplies (JOML {@code rotateY}), i.e. when transforming a vector the
 * spin is applied first, in local space, then the bottom rotation. {@code Q}
 * rotates LOCAL space into WORLD space. Renderers should apply the whole
 * quaternion about the block center and nothing else — the front-facing is
 * baked into the spin.
 *
 * All rotations are exact quarter turns, so mapped directions and rotated
 * {@link VoxelShape}s remain axis-aligned.
 */
public final class Rotation24 {

    /**
     * The local direction a "front-facing" block model looks toward in its
     * unrotated (identity) pose. Vanilla BE models (chest, etc.) are authored
     * facing SOUTH — the chest lid hinge sits at the local north edge.
     */
    public static final Direction LOCAL_FRONT = Direction.SOUTH;

    public static final int COUNT = 24;

    /** Orientation quaternions, indexed by {@link #index(Direction, int)}. */
    private static final Quaternionf[] QUATERNIONS = new Quaternionf[COUNT];
    /** localToWorld direction table: [orientation index][local ordinal]. */
    private static final Direction[][] LOCAL_TO_WORLD = new Direction[COUNT][6];

    static {
        for (Direction bottom : Direction.values()) {
            Quaternionf base = baseRotation(bottom);
            for (int spin = 0; spin < 4; spin++) {
                // spin about the LOCAL Y axis first, then rotate local DOWN
                // onto the bottom face (JOML rotateY post-multiplies)
                Quaternionf q = new Quaternionf(base).rotateY(spin * Mth.HALF_PI);
                int idx = index(bottom, spin);
                QUATERNIONS[idx] = q;
                for (Direction local : Direction.values()) {
                    Vector3f v = q.transform(local.step());
                    LOCAL_TO_WORLD[idx][local.ordinal()] = Direction.getNearest(v.x(), v.y(), v.z());
                }
            }
        }
    }

    /** Canonical single-axis rotation taking local DOWN onto {@code bottom}. */
    private static Quaternionf baseRotation(Direction bottom) {
        return switch (bottom) {
            case DOWN -> new Quaternionf();
            case UP -> new Quaternionf().rotationX(Mth.PI);
            case NORTH -> new Quaternionf().rotationX(Mth.HALF_PI);
            case SOUTH -> new Quaternionf().rotationX(-Mth.HALF_PI);
            case EAST -> new Quaternionf().rotationZ(Mth.HALF_PI);
            case WEST -> new Quaternionf().rotationZ(-Mth.HALF_PI);
        };
    }

    /** Dense 0-23 index for an orientation — handy for lookup tables. */
    public static int index(Direction bottom, int spin) {
        return bottom.ordinal() * 4 + (spin & 3);
    }

    /**
     * The quaternion rotating LOCAL space into WORLD space for this
     * orientation (a defensive copy — safe to mutate).
     */
    public static Quaternionf quaternion(Direction bottom, int spin) {
        return new Quaternionf(QUATERNIONS[index(bottom, spin)]);
    }

    /** Maps a block-local grid direction to its world direction. */
    public static Direction localToWorld(Direction local, Direction bottom, int spin) {
        return LOCAL_TO_WORLD[index(bottom, spin)][local.ordinal()];
    }

    /** Maps a world grid direction back to block-local space. */
    public static Direction worldToLocal(Direction world, Direction bottom, int spin) {
        Direction[] table = LOCAL_TO_WORLD[index(bottom, spin)];
        for (Direction local : Direction.values()) {
            if (table[local.ordinal()] == world) {
                return local;
            }
        }
        throw new IllegalStateException("unreachable: LOCAL_TO_WORLD is a bijection");
    }

    /** An orientation picked for a placement. */
    public record Orientation(Direction bottom, int spin) {}

    /**
     * The spin (0-3) whose front direction points most along
     * {@code gridFrontVec} for the given bottom face. Vector-based (not
     * snapped-direction equality) so it stays robust when the desired front
     * is not axis-aligned in the target grid — e.g. placement on a rotated
     * Valkyrien Skies ship, where the placer's facing lands between the
     * ship-grid cardinals.
     */
    public static int spinForFront(Direction bottom, net.minecraft.world.phys.Vec3 gridFrontVec) {
        int best = 0;
        double bestDot = -Double.MAX_VALUE;
        for (int spin = 0; spin < 4; spin++) {
            Direction front = localToWorld(LOCAL_FRONT, bottom, spin);
            double dot = front.getStepX() * gridFrontVec.x
                + front.getStepY() * gridFrontVec.y
                + front.getStepZ() * gridFrontVec.z;
            if (dot > bestDot) {
                bestDot = dot;
                best = spin;
            }
        }
        return best;
    }

    /**
     * Picks the orientation for a block being placed by {@code placer} at
     * {@code pos}: the bottom face is the placer's gravity down and the spin
     * turns the block's front toward the placer, both in the BLOCK GRID of
     * the placement position (shipyard space on Valkyrien Skies ships).
     *
     * MUST be called from within the block-placement flow
     * ({@code BlockItem.place} / {@code getStateForPlacement}). Valkyrien
     * Skies' block_placement mixins wrap that flow in
     * {@code PlayerUtil.transformPlayerTemporarily}: while it runs, a
     * ship-placement player's ROTATION FIELDS have been rewritten to
     * ship-grid look angles (derived from {@code getLookAngle()}, which
     * already includes this mod's gravity frame) and their POSITION to
     * shipyard coordinates. So on ships the raw yaw/pitch ARE the grid-space
     * look and must be used verbatim — re-applying the gravity frame
     * ({@code getLookAngle()} does) or the world-to-ship transform (a
     * position-based derivation does) double-rotates, which is exactly what
     * broke the two previous attempts. Off ships the VS wrap is inactive and
     * the true world look is the grid look.
     *
     * The full (pitched) look is used; {@link #spinForFront}'s candidates
     * are perpendicular to the bottom face, so dotting inherently projects
     * out the bottom component — standing on a wall and looking "up the
     * wall" picks the vertical-in-grid front correctly.
     *
     * Null-safe: defaults to (DOWN, 0).
     */
    public static Orientation fromPlacement(
        @Nullable Entity placer,
        net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos pos
    ) {
        if (placer == null) {
            return new Orientation(Direction.DOWN, 0);
        }
        net.minecraft.world.phys.Vec3 gridDown = GravityBlockHelper.worldDirectionToGrid(
            level, pos, GravityBlockHelper.gravityDownVector(placer)
        );
        Direction bottom = Direction.getNearest(gridDown.x, gridDown.y, gridDown.z);

        boolean onShip =
            org.valkyrienskies.mod.common.VSGameUtilsKt.getShipManagingPos(level, pos) != null;
        net.minecraft.world.phys.Vec3 gridLook = onShip
            // VS's temporary placement transform: raw angles are already the
            // ship-grid look — use them verbatim (see javadoc)
            ? net.cama.gravityapivs.util.RotationUtil.rotToVec(placer.getYRot(), placer.getXRot())
            // off-ship: grid == world; the true world look (gravity-frame aware)
            : placer.getLookAngle();

        // front faces TOWARD the placer, i.e. opposite the look
        return new Orientation(bottom, spinForFront(bottom, gridLook.scale(-1)));
    }

    /**
     * Rotates an axis-aligned {@link VoxelShape} by the orientation, about
     * the block center. Quarter turns keep every box axis-aligned, so the
     * result is exact up to float error, which is snapped away.
     */
    public static VoxelShape rotateShape(VoxelShape shape, Direction bottom, int spin) {
        if (bottom == Direction.DOWN && (spin & 3) == 0) {
            return shape;
        }
        Quaternionf q = QUATERNIONS[index(bottom, spin)];
        VoxelShape result = Shapes.empty();
        for (AABB box : shape.toAabbs()) {
            // rotate two opposite corners about the center; min/max restores
            // the axis-aligned box (valid because rotations are quarter turns)
            Vector3f lo = q.transform(new Vector3f(
                (float) (box.minX - 0.5), (float) (box.minY - 0.5), (float) (box.minZ - 0.5)));
            Vector3f hi = q.transform(new Vector3f(
                (float) (box.maxX - 0.5), (float) (box.maxY - 0.5), (float) (box.maxZ - 0.5)));
            result = Shapes.or(result, Shapes.box(
                snap(Math.min(lo.x(), hi.x()) + 0.5),
                snap(Math.min(lo.y(), hi.y()) + 0.5),
                snap(Math.min(lo.z(), hi.z()) + 0.5),
                snap(Math.max(lo.x(), hi.x()) + 0.5),
                snap(Math.max(lo.y(), hi.y()) + 0.5),
                snap(Math.max(lo.z(), hi.z()) + 0.5)
            ));
        }
        return result.optimize();
    }

    /** Snaps a coordinate to the nearest 1/16 when within float rounding error. */
    private static double snap(double value) {
        double scaled = value * 16.0;
        double rounded = Math.round(scaled);
        return Math.abs(scaled - rounded) < 1.0E-3 ? rounded / 16.0 : value;
    }

    private Rotation24() {}
}

package net.cama.gravityapivs.util;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Rotation-free collision for entities under arbitrary-angle gravity.
 *
 * A Minecraft AABB cannot be tilted, so instead the entity is represented as a
 * stack of spheres (a capsule) aligned to its gravity frame. Spheres are the
 * same shape at every rotation, so this hitbox genuinely rotates with gravity.
 * They also transform cleanly into a Valkyrien Skies ship's coordinate space,
 * so collision against rotated/moving ships is exact (computed against the
 * ship's actual blocks in shipyard space) instead of approximated.
 *
 * Movement is integrated in substeps; after each substep, penetrating contacts
 * push the capsule out along the contact normal, which produces natural,
 * smooth sliding along surfaces at any angle.
 */
public final class CapsuleCollider {

    private static final double SKIN = 1.0E-4;
    private static final double STEP_HEIGHT = 0.6;
    private static final double GROUND_NORMAL_DOT = 0.55;

    public static final class Result {
        public final Vec3 collidedMovement;
        public final boolean grounded;
        public final @Nullable Ship groundShip;

        Result(Vec3 collidedMovement, boolean grounded, @Nullable Ship groundShip) {
            this.collidedMovement = collidedMovement;
            this.grounded = grounded;
            this.groundShip = groundShip;
        }
    }

    /** An obstacle box; when ship != null, the box is in shipyard coordinates. */
    private record Obstacle(AABB box, @Nullable Ship ship) {}

    private static final class ResolveState {
        boolean grounded = false;
        boolean touched = false;
        @Nullable Ship groundShip = null;
    }

    /**
     * Collide a capsule-shaped entity.
     *
     * @param up       world-space up direction of the entity (unit, opposite of gravity)
     * @param movement world-space intended movement this tick
     * @param wasGrounded whether the entity was grounded last tick (enables step-up)
     * @return collided movement plus ground contact info
     */
    public static Result collide(Entity entity, Vec3 up, Vec3 movement, boolean wasGrounded) {
        return collide(entity, entity.position(), up, movement, wasGrounded);
    }

    public static Result collide(Entity entity, Vec3 start, Vec3 up, Vec3 movement, boolean wasGrounded) {
        double radius = Math.max(0.1, entity.getBbWidth() / 2.0 - 0.02);
        double height = Math.max(entity.getBbHeight(), radius * 2.0 + 0.02);
        double[] sphereOffsets = sphereOffsets(height, radius);

        List<Obstacle> obstacles = gatherObstacles(entity.level(), start, movement, up, radius, height);

        ResolveState state = new ResolveState();
        Vec3 end = sweep(start, movement, up, radius, sphereOffsets, obstacles, state);

        if (!state.touched) {
            // Nothing was hit: return the movement BIT-EXACTLY. Vanilla decides
            // onGround/collision flags with exact float comparisons, so even a
            // 1e-8 substep-summation error would register as a phantom collision
            // (which manifests as jumping on air, elytra/flight cancelling, and
            // ground friction flickering).
            return new Result(movement, false, null);
        }

        // step assist: when walking along the ground into a low obstacle, retry the
        // move lifted by the step height, then settle back down
        if (wasGrounded) {
            Vec3 achieved = end.subtract(start);
            Vec3 tangentIntended = rejectFrom(movement, up);
            Vec3 tangentAchieved = rejectFrom(achieved, up);
            double intendedLen = tangentIntended.length();
            if (intendedLen > 1.0E-5 && tangentAchieved.length() < intendedLen * 0.7) {
                ResolveState stepState = new ResolveState();
                Vec3 lifted = sweep(start, up.scale(STEP_HEIGHT), up, radius, sphereOffsets, obstacles, stepState);
                Vec3 movedUp = sweep(lifted, movement, up, radius, sphereOffsets, obstacles, stepState);
                Vec3 settled = sweep(movedUp, up.scale(-(STEP_HEIGHT + SKIN * 2)), up, radius, sphereOffsets, obstacles, stepState);

                Vec3 stepTangent = rejectFrom(settled.subtract(start), up);
                if (stepTangent.length() > tangentAchieved.length() + 0.01) {
                    end = settled;
                    state.grounded = state.grounded || stepState.grounded;
                    if (state.groundShip == null) {
                        state.groundShip = stepState.groundShip;
                    }
                }
            }
        }

        return new Result(end.subtract(start), state.grounded, state.groundShip);
    }

    /**
     * World-axis-aligned envelope of the capsule; used as the entity's stored
     * AABB (for rendering/other systems — block collision does not use it).
     * Never extends below the feet, and matches the exact rotated box at
     * cardinal orientations.
     */
    public static AABB makeEnvelope(Vec3 feetPos, Vec3 up, double width, double height) {
        double radius = width / 2.0;
        double[] offsets = sphereOffsets(height, radius);
        Vec3 low = feetPos.add(up.scale(offsets[0]));
        Vec3 high = feetPos.add(up.scale(offsets[offsets.length - 1]));

        return new AABB(
            Math.min(low.x, high.x) - radius, Math.min(low.y, high.y) - radius, Math.min(low.z, high.z) - radius,
            Math.max(low.x, high.x) + radius, Math.max(low.y, high.y) + radius, Math.max(low.z, high.z) + radius
        );
    }

    // ------------------------------------------------------------------

    private static double[] sphereOffsets(double height, double radius) {
        double bottom = radius;
        double top = Math.max(height - radius, radius);
        if (top - bottom < radius) {
            // short entity: two spheres (or effectively one)
            return top - bottom < 1.0E-4 ? new double[]{bottom} : new double[]{bottom, top};
        }
        return new double[]{bottom, (bottom + top) / 2.0, top};
    }

    private static Vec3 rejectFrom(Vec3 v, Vec3 unit) {
        return v.subtract(unit.scale(v.dot(unit)));
    }

    /** Substepped move-and-depenetrate. */
    private static Vec3 sweep(
        Vec3 start, Vec3 movement, Vec3 up, double radius,
        double[] sphereOffsets, List<Obstacle> obstacles, ResolveState state
    ) {
        Vec3 pos = depenetrate(start, up, radius, sphereOffsets, obstacles, state);

        double length = movement.length();
        if (length < 1.0E-7) {
            return pos;
        }

        int substeps = Mth.clamp((int) Math.ceil(length / (radius * 0.5)), 1, 16);
        Vec3 step = movement.scale(1.0 / substeps);

        for (int i = 0; i < substeps; i++) {
            pos = pos.add(step);
            pos = depenetrate(pos, up, radius, sphereOffsets, obstacles, state);
        }
        return pos;
    }

    /**
     * Iteratively push the capsule out of any obstacle it penetrates, deepest
     * contact first. Contact normals facing (roughly) up mark the entity grounded.
     */
    private static Vec3 depenetrate(
        Vec3 pos, Vec3 up, double radius,
        double[] sphereOffsets, List<Obstacle> obstacles, ResolveState state
    ) {
        double r2 = radius * radius;

        for (int iteration = 0; iteration < 8; iteration++) {
            double worstDepth = 0;
            Vec3 worstNormal = null;
            Obstacle worstObstacle = null;

            for (double offset : sphereOffsets) {
                Vec3 center = pos.add(up.scale(offset));

                for (Obstacle obstacle : obstacles) {
                    Vec3 c = center;
                    if (obstacle.ship != null) {
                        Vector3d local = new Vector3d(center.x, center.y, center.z);
                        obstacle.ship.getTransform().getWorldToShipMatrix().transformPosition(local);
                        c = new Vec3(local.x, local.y, local.z);
                    }

                    AABB box = obstacle.box;
                    double cx = Mth.clamp(c.x, box.minX, box.maxX);
                    double cy = Mth.clamp(c.y, box.minY, box.maxY);
                    double cz = Mth.clamp(c.z, box.minZ, box.maxZ);
                    double dx = c.x - cx, dy = c.y - cy, dz = c.z - cz;
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq >= r2 || distSq < 1.0E-12) {
                        if (distSq < 1.0E-12 && box.contains(c)) {
                            // sphere center inside the box: push out along the
                            // nearest face
                            Vec3 normalLocal = nearestFaceNormal(c, box);
                            double depth = radius + nearestFaceDistance(c, box);
                            if (depth > worstDepth) {
                                worstDepth = depth;
                                worstNormal = toWorldDirection(normalLocal, obstacle.ship);
                                worstObstacle = obstacle;
                            }
                        }
                        continue;
                    }

                    double dist = Math.sqrt(distSq);
                    double depth = radius - dist;
                    if (depth > worstDepth) {
                        worstDepth = depth;
                        worstNormal = toWorldDirection(new Vec3(dx / dist, dy / dist, dz / dist), obstacle.ship);
                        worstObstacle = obstacle;
                    }
                }
            }

            if (worstNormal == null) {
                break;
            }

            state.touched = true;
            pos = pos.add(worstNormal.scale(worstDepth + SKIN));

            if (worstNormal.dot(up) > GROUND_NORMAL_DOT) {
                state.grounded = true;
                if (worstObstacle.ship != null) {
                    state.groundShip = worstObstacle.ship;
                }
            }
        }

        return pos;
    }

    private static Vec3 toWorldDirection(Vec3 v, @Nullable Ship ship) {
        if (ship == null) {
            return v;
        }
        Vector3d d = new Vector3d(v.x, v.y, v.z);
        ship.getTransform().getShipToWorldMatrix().transformDirection(d);
        d.normalize();
        return new Vec3(d.x, d.y, d.z);
    }

    private static Vec3 nearestFaceNormal(Vec3 c, AABB box) {
        double[] d = faceDistances(c, box);
        int best = 0;
        for (int i = 1; i < 6; i++) {
            if (d[i] < d[best]) best = i;
        }
        return switch (best) {
            case 0 -> new Vec3(-1, 0, 0);
            case 1 -> new Vec3(1, 0, 0);
            case 2 -> new Vec3(0, -1, 0);
            case 3 -> new Vec3(0, 1, 0);
            case 4 -> new Vec3(0, 0, -1);
            default -> new Vec3(0, 0, 1);
        };
    }

    private static double nearestFaceDistance(Vec3 c, AABB box) {
        double[] d = faceDistances(c, box);
        double min = d[0];
        for (int i = 1; i < 6; i++) {
            min = Math.min(min, d[i]);
        }
        return min;
    }

    private static double[] faceDistances(Vec3 c, AABB box) {
        return new double[]{
            c.x - box.minX, box.maxX - c.x,
            c.y - box.minY, box.maxY - c.y,
            c.z - box.minZ, box.maxZ - c.z
        };
    }

    // ------------------------------------------------------------------
    // obstacle gathering
    // ------------------------------------------------------------------

    private static List<Obstacle> gatherObstacles(
        Level level, Vec3 start, Vec3 movement, Vec3 up, double radius, double height
    ) {
        AABB reach = makeEnvelope(start, up, radius * 2, height)
            .minmax(makeEnvelope(start.add(movement), up, radius * 2, height))
            .inflate(radius + STEP_HEIGHT + 0.3);

        List<Obstacle> out = new ArrayList<>();

        // real world blocks, queried directly from block states (this bypasses
        // Valkyrien Skies' approximated world-space ship shapes — ships are
        // handled exactly, in shipyard space, below)
        collectBlocks(level, reach, null, out);

        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(level, reach)) {
            AABB shipBox = transformBoxWorldToShip(ship, reach);
            collectBlocks(level, shipBox, ship, out);
        }

        return out;
    }

    private static void collectBlocks(Level level, AABB box, @Nullable Ship ship, List<Obstacle> out) {
        int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        int minY = Mth.floor(box.minY), maxY = Mth.floor(box.maxY);
        int minZ = Mth.floor(box.minZ), maxZ = Mth.floor(box.maxZ);

        // hard cap in case something hands us a huge box
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 4096) {
            return;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState blockState = level.getBlockState(cursor);
                    if (blockState.isAir()) {
                        continue;
                    }
                    VoxelShape shape = blockState.getCollisionShape(level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    for (AABB aabb : shape.toAabbs()) {
                        out.add(new Obstacle(aabb.move(x, y, z), ship));
                    }
                }
            }
        }
    }

    private static AABB transformBoxWorldToShip(Ship ship, AABB box) {
        Vector3d[] corners = new Vector3d[]{
            new Vector3d(box.minX, box.minY, box.minZ), new Vector3d(box.maxX, box.minY, box.minZ),
            new Vector3d(box.minX, box.maxY, box.minZ), new Vector3d(box.maxX, box.maxY, box.minZ),
            new Vector3d(box.minX, box.minY, box.maxZ), new Vector3d(box.maxX, box.minY, box.maxZ),
            new Vector3d(box.minX, box.maxY, box.maxZ), new Vector3d(box.maxX, box.maxY, box.maxZ)
        };
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vector3d corner : corners) {
            ship.getTransform().getWorldToShipMatrix().transformPosition(corner);
            minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Velocity of a ship's surface at a world position (linear + angular). */
    public static Vec3 shipSurfaceVelocity(Ship ship, Vec3 worldPos) {
        Vector3d r = new Vector3d(worldPos.x, worldPos.y, worldPos.z)
            .sub(ship.getTransform().getPositionInWorld());
        Vector3d vel = new Vector3d(ship.getOmega()).cross(r).add(ship.getVelocity());
        return new Vec3(vel.x, vel.y, vel.z);
    }

    private CapsuleCollider() {}
}

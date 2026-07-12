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
        /** world-space normal of the strongest up-facing contact, when grounded */
        public final @Nullable Vec3 groundNormal;

        Result(Vec3 collidedMovement, boolean grounded, @Nullable Ship groundShip, @Nullable Vec3 groundNormal) {
            this.collidedMovement = collidedMovement;
            this.grounded = grounded;
            this.groundShip = groundShip;
            this.groundNormal = groundNormal;
        }
    }

    /** An obstacle box; when ship != null, the box is in shipyard coordinates. */
    private record Obstacle(AABB box, @Nullable Ship ship) {}

    private static final class ResolveState {
        boolean grounded = false;
        boolean touched = false;
        @Nullable Ship groundShip = null;
        double bestGroundDot = 0;
        @Nullable Vec3 groundNormal = null;
        // second "down is that way" reference for ground classification: the
        // FIELD's up. During a landing on a steep surface the frame's up still
        // points the old way, so contacts opposing the field must also count as
        // ground — otherwise the planet-walk alignment can never engage
        // (visible as being dragged along the surface with pulsating tilt).
        Vec3 gravityUp = new Vec3(0, 1, 0);
    }

    /**
     * Collide a capsule-shaped entity.
     *
     * @param up       world-space up direction of the entity (unit, opposite of gravity)
     * @param movement world-space intended movement this tick
     * @param wasGrounded whether the entity was grounded last tick (enables step-up)
     * @return collided movement plus ground contact info
     */
    public static Result collide(Entity entity, Vec3 up, Vec3 gravityUp, Vec3 movement, boolean wasGrounded) {
        return collide(entity, entity.position(), up, gravityUp, movement, wasGrounded);
    }

    public static Result collide(Entity entity, Vec3 start, Vec3 up, Vec3 gravityUp, Vec3 movement, boolean wasGrounded) {
        double radius = capsuleRadius(entity);
        double height = capsuleHeight(entity, radius);
        double[] sphereOffsets = sphereOffsets(height, radius);

        List<Obstacle> obstacles = gatherObstacles(entity.level(), start, movement, up, radius, height);

        ResolveState state = new ResolveState();
        state.gravityUp = gravityUp;
        Vec3 correction = sweep(start, movement, up, radius, sphereOffsets, obstacles, state);

        if (!state.touched) {
            // Nothing was hit: return the movement BIT-EXACTLY. Vanilla decides
            // onGround/collision flags with exact float comparisons, so even a
            // 1e-8 substep-summation error would register as a phantom collision
            // (which manifests as jumping on air, elytra/flight cancelling, and
            // ground friction flickering).
            return new Result(movement, false, null, null);
        }

        // movement plus the summed contact corrections: axes the contacts never
        // touched come back BIT-IDENTICAL to the input (see sweep)
        Vec3 collided = movement.add(correction);

        // step assist: when walking along the ground into a low obstacle, retry the
        // move lifted by the step height, then settle back down
        if (wasGrounded) {
            Vec3 tangentIntended = rejectFrom(movement, up);
            Vec3 tangentAchieved = rejectFrom(collided, up);
            double intendedLen = tangentIntended.length();
            if (intendedLen > 1.0E-5 && tangentAchieved.length() < intendedLen * 0.7) {
                ResolveState stepState = new ResolveState();
                stepState.gravityUp = gravityUp;
                Vec3 lift = up.scale(STEP_HEIGHT);
                Vec3 lifted = start.add(lift)
                    .add(sweep(start, lift, up, radius, sphereOffsets, obstacles, stepState));
                Vec3 movedUp = lifted.add(movement)
                    .add(sweep(lifted, movement, up, radius, sphereOffsets, obstacles, stepState));
                Vec3 drop = up.scale(-(STEP_HEIGHT + SKIN * 2));
                Vec3 settled = movedUp.add(drop)
                    .add(sweep(movedUp, drop, up, radius, sphereOffsets, obstacles, stepState));

                Vec3 stepMovement = settled.subtract(start);
                Vec3 stepTangent = rejectFrom(stepMovement, up);
                if (stepTangent.length() > tangentAchieved.length() + 0.01) {
                    collided = stepMovement;
                    state.grounded = state.grounded || stepState.grounded;
                    if (state.groundShip == null) {
                        state.groundShip = stepState.groundShip;
                    }
                    if (stepState.bestGroundDot > state.bestGroundDot) {
                        state.bestGroundDot = stepState.bestGroundDot;
                        state.groundNormal = stepState.groundNormal;
                    }
                }
            }
        }

        return new Result(collided, state.grounded, state.groundShip, state.groundNormal);
    }

    /** Radius of the capsule's spheres for this entity. */
    public static double capsuleRadius(Entity entity) {
        return Math.max(0.1, entity.getBbWidth() / 2.0 - 0.02);
    }

    /** Height of the capsule for this entity. */
    public static double capsuleHeight(Entity entity, double radius) {
        return Math.max(entity.getBbHeight(), radius * 2.0 + 0.02);
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

    /** Offsets of the sphere centers along the up axis, from the feet point. */
    public static double[] sphereOffsets(double height, double radius) {
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

    /**
     * Substepped move-and-depenetrate.
     *
     * Returns the total contact CORRECTION; the final position is
     * {@code start + movement + correction}. Substep positions are computed as
     * {@code start + movement * (i/n) + correction} rather than by summing
     * increments, so an axis that no contact ever pushes accumulates EXACTLY
     * zero error. This matters because vanilla decides verticalCollision (and
     * with it onGround and the vertical-velocity zeroing in
     * updateEntityAfterFallOn) by comparing the movement with an exact double
     * {@code !=} — summing {@code movement/3} three times is already enough to
     * kill a jump made while brushing a wall.
     */
    private static Vec3 sweep(
        Vec3 start, Vec3 movement, Vec3 up, double radius,
        double[] sphereOffsets, List<Obstacle> obstacles, ResolveState state
    ) {
        Vec3 correction = depenetrate(start, up, radius, sphereOffsets, obstacles, state).subtract(start);

        double length = movement.length();
        if (length < 1.0E-7) {
            return correction;
        }

        int substeps = Mth.clamp((int) Math.ceil(length / (radius * 0.5)), 1, 16);
        for (int i = 1; i <= substeps; i++) {
            Vec3 target = start.add(movement.scale((double) i / substeps)).add(correction);
            Vec3 resolved = depenetrate(target, up, radius, sphereOffsets, obstacles, state);
            correction = correction.add(resolved.subtract(target));
        }
        return correction;
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

        for (int iteration = 0; iteration < 12; iteration++) {
            double worstDepth = 0;
            Vec3 worstNormal = null;
            Obstacle worstObstacle = null;
            Vec3 worstLocalCenter = null;

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
                                worstLocalCenter = c;
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
                        worstLocalCenter = c;
                    }
                }
            }

            if (worstNormal == null) {
                break;
            }

            state.touched = true;

            // Standing contacts against block edges/corners resolve along the
            // FACE being stood on, not the diagonal sphere-to-edge normal.
            // Raw edge normals make every internal edge and convex corner a
            // separate contact plane: the player floats on corner points, gets
            // dragged tangentially every tick, and the edge-diagonal normals
            // leak into the planet-walk frame (visible as stutter and weird
            // sliding near edges). Walls and ceilings (support face not up-ish)
            // keep the exact contact normal.
            Vec3 pushDir = worstNormal;
            double pushLen = worstDepth + SKIN;
            double upDot = Math.max(worstNormal.dot(up), worstNormal.dot(state.gravityUp));

            Vec3 support = supportFaceNormal(worstLocalCenter, worstObstacle.box, worstObstacle.ship, up, state.gravityUp);
            if (support != null) {
                double supportUpDot = Math.max(support.dot(up), support.dot(state.gravityUp));
                double align = worstNormal.dot(support);
                if (supportUpDot > GROUND_NORMAL_DOT && align > 0.4) {
                    pushDir = support;
                    pushLen = worstDepth / align + SKIN;
                    upDot = supportUpDot;
                }
            }

            pos = pos.add(pushDir.scale(pushLen));

            if (upDot > GROUND_NORMAL_DOT) {
                state.grounded = true;
                if (worstObstacle.ship != null) {
                    state.groundShip = worstObstacle.ship;
                }
                if (upDot > state.bestGroundDot) {
                    state.bestGroundDot = upDot;
                    state.groundNormal = pushDir;
                }
            }
        }

        return pos;
    }

    /**
     * The face of the obstacle box that supports the capsule for this contact:
     * among the faces the sphere center lies beyond, the one whose world-space
     * outward normal points most along up. Null when the center is inside the
     * box (deep penetration keeps the nearest-face push).
     */
    private static @Nullable Vec3 supportFaceNormal(Vec3 localCenter, AABB box, @Nullable Ship ship, Vec3 up, Vec3 gravityUp) {
        Vec3 best = null;
        double bestDot = 0;

        for (int i = 0; i < 6; i++) {
            double beyond = switch (i) {
                case 0 -> box.minX - localCenter.x;
                case 1 -> localCenter.x - box.maxX;
                case 2 -> box.minY - localCenter.y;
                case 3 -> localCenter.y - box.maxY;
                case 4 -> box.minZ - localCenter.z;
                default -> localCenter.z - box.maxZ;
            };
            if (beyond <= 0) {
                continue;
            }
            Vec3 normal = toWorldDirection(switch (i) {
                case 0 -> new Vec3(-1, 0, 0);
                case 1 -> new Vec3(1, 0, 0);
                case 2 -> new Vec3(0, -1, 0);
                case 3 -> new Vec3(0, 1, 0);
                case 4 -> new Vec3(0, 0, -1);
                default -> new Vec3(0, 0, 1);
            }, ship);
            double dot = Math.max(normal.dot(up), normal.dot(gravityUp));
            if (dot > bestDot) {
                bestDot = dot;
                best = normal;
            }
        }

        return best;
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

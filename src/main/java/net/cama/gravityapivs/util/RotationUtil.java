package net.cama.gravityapivs.util;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import com.mojang.math.Axis;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public abstract class RotationUtil {
    private static final Direction[][] DIR_WORLD_TO_PLAYER = new Direction[6][];
    
    static {
        for (Direction gravityDirection : Direction.values()) {
            DIR_WORLD_TO_PLAYER[gravityDirection.get3DDataValue()] = new Direction[6];
            for (Direction direction : Direction.values()) {
                Vec3 directionVector = Vec3.atLowerCornerOf(direction.getNormal());
                directionVector = RotationUtil.vecWorldToPlayer(directionVector, gravityDirection);
                DIR_WORLD_TO_PLAYER[gravityDirection.get3DDataValue()][direction.get3DDataValue()] =
                    Direction.getNearest(directionVector.x, directionVector.y, directionVector.z);
            }
        }
    }
    
    public static Direction dirWorldToPlayer(Direction direction, Direction gravityDirection) {
        return DIR_WORLD_TO_PLAYER[gravityDirection.get3DDataValue()][direction.get3DDataValue()];
    }
    
    private static final Direction[][] DIR_PLAYER_TO_WORLD = new Direction[6][];
    
    static {
        for (Direction gravityDirection : Direction.values()) {
            DIR_PLAYER_TO_WORLD[gravityDirection.get3DDataValue()] = new Direction[6];
            for (Direction direction : Direction.values()) {
                Vec3 directionVector = Vec3.atLowerCornerOf(direction.getNormal());
                directionVector = RotationUtil.vecPlayerToWorld(directionVector, gravityDirection);
                DIR_PLAYER_TO_WORLD[gravityDirection.get3DDataValue()][direction.get3DDataValue()] =
                    Direction.getNearest(directionVector.x, directionVector.y, directionVector.z);
            }
        }
    }
    
    public static Direction dirPlayerToWorld(Direction direction, Direction gravityDirection) {
        return DIR_PLAYER_TO_WORLD[gravityDirection.get3DDataValue()][direction.get3DDataValue()];
    }
    
    public static Vec3 vecWorldToPlayer(double x, double y, double z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN -> new Vec3(x, y, z);
            case UP -> new Vec3(-x, -y, z);
            case NORTH -> new Vec3(x, z, -y);
            case SOUTH -> new Vec3(-x, -z, -y);
            case WEST -> new Vec3(-z, x, -y);
            case EAST -> new Vec3(z, -x, -y);
        };
    }
    
    public static Vec3 vecWorldToPlayer(Vec3 vec3d, Direction gravityDirection) {
        return vecWorldToPlayer(vec3d.x, vec3d.y, vec3d.z, gravityDirection);
    }

    /**
     * @deprecated the frame derived statelessly from a gravity vector has an
     * unstable twist; use the entity's persistent frame quaternion instead
     * (see {@code GravityChangerAPI.getGravityRotation}).
     */
    @Deprecated
    public static Vec3 vecWorldToPlayer(Vec3 vec3d, Vec3 gravityVector) {
        Quaternionf rotation = getWorldRotationQuaternion(gravityVector);
        return QuaternionUtil.rotate(vec3d, rotation);
    }

    @Deprecated
    public static Vec3 vecWorldToPlayer(double x, double y, double z, Vec3 gravityVector) {
        return vecWorldToPlayer(new Vec3(x, y, z), gravityVector);
    }

    // ---- frame (quaternion) transforms ----
    // `frame` is the world->player rotation (the rotation that maps the entity's
    // gravity direction onto local down). In the cardinal-physics architecture
    // the frame handed out by the capability is always one of the six canonical
    // cardinal frames; these helpers recognize those instances and route to the
    // exact switch-based cardinal math (no floating point drift).

    /**
     * If the frame is one of the canonical cardinal frames, its direction.
     */
    @org.jetbrains.annotations.Nullable
    public static Direction canonicalDirectionOf(Quaternionf frame) {
        for (int i = 0; i < 6; i++) {
            if (WORLD_ROTATION_QUATERNIONS[i] == frame || WORLD_ROTATION_QUATERNIONS[i].equals(frame)) {
                return Direction.from3DDataValue(i);
            }
        }
        return null;
    }

    public static Vec3 vecWorldToPlayer(Vec3 vec3d, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return vecWorldToPlayer(vec3d, dir);
        }
        return QuaternionUtil.rotate(vec3d, frame);
    }

    public static Vec3 vecWorldToPlayer(double x, double y, double z, Quaternionf frame) {
        return vecWorldToPlayer(new Vec3(x, y, z), frame);
    }

    public static Vec3 vecPlayerToWorld(Vec3 vec3d, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return vecPlayerToWorld(vec3d, dir);
        }
        return QuaternionUtil.rotate(vec3d, new Quaternionf(frame).conjugate());
    }

    public static Vec3 vecPlayerToWorld(double x, double y, double z, Quaternionf frame) {
        return vecPlayerToWorld(new Vec3(x, y, z), frame);
    }

    public static Vec3 maskWorldToPlayer(Vec3 vec3d, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return maskWorldToPlayer(vec3d, dir);
        }
        Vec3 rotated = QuaternionUtil.rotate(vec3d, frame);
        return new Vec3(Math.abs(rotated.x), Math.abs(rotated.y), Math.abs(rotated.z));
    }

    public static Vec3 maskPlayerToWorld(Vec3 vec3d, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return maskPlayerToWorld(vec3d, dir);
        }
        Vec3 rotated = QuaternionUtil.rotate(vec3d, new Quaternionf(frame).conjugate());
        return new Vec3(Math.abs(rotated.x), Math.abs(rotated.y), Math.abs(rotated.z));
    }

    public static Vec2 rotWorldToPlayer(float yaw, float pitch, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return rotWorldToPlayer(yaw, pitch, dir);
        }
        Vec3 vec3d = QuaternionUtil.rotate(rotToVec(yaw, pitch), frame);
        return vecToRot(vec3d.x, vec3d.y, vec3d.z);
    }

    public static Vec2 rotPlayerToWorld(float yaw, float pitch, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return rotPlayerToWorld(yaw, pitch, dir);
        }
        Vec3 vec3d = QuaternionUtil.rotate(rotToVec(yaw, pitch), new Quaternionf(frame).conjugate());
        return vecToRot(vec3d.x, vec3d.y, vec3d.z);
    }

    public static AABB boxWorldToPlayer(AABB box, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return boxWorldToPlayer(box, dir);
        }
        return rotatedBoxEnvelope(box, frame, true);
    }

    public static AABB boxPlayerToWorld(AABB box, Quaternionf frame) {
        Direction dir = canonicalDirectionOf(frame);
        if (dir != null) {
            return boxPlayerToWorld(box, dir);
        }
        return rotatedBoxEnvelope(box, frame, false);
    }

    public static AABB makeBoxFromDimensions(
        EntityDimensions dimensions, Quaternionf frame, Vec3 pos
    ) {
        AABB rawBox = dimensions.makeBoundingBox(0, 0, 0);
        return boxPlayerToWorld(rawBox, frame).move(pos);
    }

    // Axis-aligned envelope of the rotated box. Note: for non-cardinal frames
    // this necessarily over-approximates the entity's true extent.
    private static AABB rotatedBoxEnvelope(AABB box, Quaternionf frame, boolean worldToPlayer) {
        Vec3[] corners = new Vec3[] {
            new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
            new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
            new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.minY, box.maxZ),
            new Vec3(box.minX, box.maxY, box.maxZ), new Vec3(box.maxX, box.maxY, box.maxZ)
        };

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Vec3 corner : corners) {
            Vec3 rotated = worldToPlayer ? vecWorldToPlayer(corner, frame) : vecPlayerToWorld(corner, frame);
            if (rotated.x < minX) minX = rotated.x;
            if (rotated.y < minY) minY = rotated.y;
            if (rotated.z < minZ) minZ = rotated.z;
            if (rotated.x > maxX) maxX = rotated.x;
            if (rotated.y > maxY) maxY = rotated.y;
            if (rotated.z > maxZ) maxZ = rotated.z;
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    public static Vec3 vecEntityToWorld(double x, double y, double z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN -> new Vec3(x, y, z);
            case UP -> new Vec3(x, -y, z);
            case NORTH -> new Vec3(x, -z, y);
            case SOUTH -> new Vec3(-x, -z, -y);
            case WEST -> new Vec3(y, -z, -x);
            case EAST -> new Vec3(-y, -z, x);
        };
    }
    
    public static Vec3 vecEntityToWorld(Vec3 vec3d, Direction gravityDirection) {
        return vecEntityToWorld(vec3d.x, vec3d.y, vec3d.z, gravityDirection);
    }
    
    public static Vec3 vecPlayerToWorld(double x, double y, double z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN -> new Vec3(x, y, z);
            case UP -> new Vec3(-x, -y, z);
            case NORTH -> new Vec3(x, -z, y);
            case SOUTH -> new Vec3(-x, -z, -y);
            case WEST -> new Vec3(y, -z, -x);
            case EAST -> new Vec3(-y, -z, x);
        };
    }
    
    public static Vec3 vecPlayerToWorld(Vec3 vec3d, Direction gravityDirection) {
        return vecPlayerToWorld(vec3d.x, vec3d.y, vec3d.z, gravityDirection);
    }

    public static Vec3 vecPlayerToWorld(Vec3 vec3d, Vec3 gravityVector) {
        Quaternionf rotation = getWorldRotationQuaternion(gravityVector);
        // The rotation rotates World -> Player. So Player -> World is the inverse (conjugate).
        rotation.conjugate();
        return QuaternionUtil.rotate(vec3d, rotation);
    }

    public static Vec3 vecPlayerToWorld(double x, double y, double z, Vec3 gravityVector) {
        return vecPlayerToWorld(new Vec3(x, y, z), gravityVector);
    }
    
    public static Vector3f vecWorldToPlayer(float x, float y, float z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN -> new Vector3f(x, y, z);
            case UP -> new Vector3f(-x, -y, z);
            case NORTH -> new Vector3f(x, z, -y);
            case SOUTH -> new Vector3f(-x, -z, -y);
            case WEST -> new Vector3f(-z, x, -y);
            case EAST -> new Vector3f(z, -x, -y);
        };
    }
    
    public static Vector3f vecWorldToPlayer(Vector3f vector3F, Direction gravityDirection) {
        return vecWorldToPlayer(vector3F.x(), vector3F.y(), vector3F.z(), gravityDirection);
    }
    
    public static Vector3f vecPlayerToWorld(float x, float y, float z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN -> new Vector3f(x, y, z);
            case UP -> new Vector3f(-x, -y, z);
            case NORTH -> new Vector3f(x, -z, y);
            case SOUTH -> new Vector3f(-x, -z, -y);
            case WEST -> new Vector3f(y, -z, -x);
            case EAST -> new Vector3f(-y, -z, x);
        };
    }
    
    public static Vector3f vecPlayerToWorld(Vector3f vector3F, Direction gravityDirection) {
        return vecPlayerToWorld(vector3F.x(), vector3F.y(), vector3F.z(), gravityDirection);
    }
    
    public static Vec3 maskWorldToPlayer(double x, double y, double z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN, UP -> new Vec3(x, y, z);
            case NORTH, SOUTH -> new Vec3(x, z, y);
            case WEST, EAST -> new Vec3(z, x, y);
        };
    }
    
    public static Vec3 maskWorldToPlayer(Vec3 vec3d, Direction gravityDirection) {
        return maskWorldToPlayer(vec3d.x, vec3d.y, vec3d.z, gravityDirection);
    }
    
    public static Vec3 maskPlayerToWorld(double x, double y, double z, Direction gravityDirection) {
        return switch (gravityDirection) {
            case DOWN, UP -> new Vec3(x, y, z);
            case NORTH, SOUTH -> new Vec3(x, z, y);
            case WEST, EAST -> new Vec3(y, z, x);
        };
    }
    
    public static Vec3 maskPlayerToWorld(Vec3 vec3d, Direction gravityDirection) {
        return maskPlayerToWorld(vec3d.x, vec3d.y, vec3d.z, gravityDirection);
    }
    
    public static AABB boxWorldToPlayer(AABB box, Direction gravityDirection) {
        return new AABB(
            RotationUtil.vecWorldToPlayer(box.minX, box.minY, box.minZ, gravityDirection),
            RotationUtil.vecWorldToPlayer(box.maxX, box.maxY, box.maxZ, gravityDirection)
        );
    }

    public static AABB boxWorldToPlayer(AABB box, Vec3 gravityVector) {
        // AABB rotation is tricky because it stays axis-aligned.
        // For arbitrary gravity, the AABB in player space might not be axis aligned if we just rotate corners.
        // However, Minecraft requires AABBs to be axis aligned.
        // Usually we rotate the center and dimensions?
        // Or we rotate all corners and find min/max.
        
        Vec3 min = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 max = new Vec3(box.maxX, box.maxY, box.maxZ);
        
        Vec3[] corners = new Vec3[] {
            new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z),
            new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z),
            new Vec3(min.x, min.y, max.z), new Vec3(max.x, min.y, max.z),
            new Vec3(min.x, max.y, max.z), new Vec3(max.x, max.y, max.z)
        };
        
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        
        for (Vec3 corner : corners) {
            Vec3 rotated = vecWorldToPlayer(corner, gravityVector);
            if (rotated.x < minX) minX = rotated.x;
            if (rotated.y < minY) minY = rotated.y;
            if (rotated.z < minZ) minZ = rotated.z;
            if (rotated.x > maxX) maxX = rotated.x;
            if (rotated.y > maxY) maxY = rotated.y;
            if (rotated.z > maxZ) maxZ = rotated.z;
        }
        
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    public static AABB boxPlayerToWorld(AABB box, Direction gravityDirection) {
        return new AABB(
            RotationUtil.vecPlayerToWorld(box.minX, box.minY, box.minZ, gravityDirection),
            RotationUtil.vecPlayerToWorld(box.maxX, box.maxY, box.maxZ, gravityDirection)
        );
    }

    public static AABB boxPlayerToWorld(AABB box, Vec3 gravityVector) {
        Vec3 min = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 max = new Vec3(box.maxX, box.maxY, box.maxZ);
        
        Vec3[] corners = new Vec3[] {
            new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z),
            new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z),
            new Vec3(min.x, min.y, max.z), new Vec3(max.x, min.y, max.z),
            new Vec3(min.x, max.y, max.z), new Vec3(max.x, max.y, max.z)
        };
        
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        
        for (Vec3 corner : corners) {
            Vec3 rotated = vecPlayerToWorld(corner, gravityVector);
            if (rotated.x < minX) minX = rotated.x;
            if (rotated.y < minY) minY = rotated.y;
            if (rotated.z < minZ) minZ = rotated.z;
            if (rotated.x > maxX) maxX = rotated.x;
            if (rotated.y > maxY) maxY = rotated.y;
            if (rotated.z > maxZ) maxZ = rotated.z;
        }
        
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
    
    public static Vec2 rotWorldToPlayer(float yaw, float pitch, Direction gravityDirection) {
        Vec3 vec3d = RotationUtil.vecWorldToPlayer(rotToVec(yaw, pitch), gravityDirection);
        return vecToRot(vec3d.x, vec3d.y, vec3d.z);
    }
    
    public static Vec2 rotWorldToPlayer(Vec2 vec2f, Direction gravityDirection) {
        return rotWorldToPlayer(vec2f.x, vec2f.y, gravityDirection);
    }
    
    public static Vec2 rotPlayerToWorld(float yaw, float pitch, Direction gravityDirection) {
        Vec3 vec3d = RotationUtil.vecPlayerToWorld(rotToVec(yaw, pitch), gravityDirection);
        return vecToRot(vec3d.x, vec3d.y, vec3d.z);
    }
    
    public static Vec2 rotPlayerToWorld(Vec2 vec2f, Direction gravityDirection) {
        return rotPlayerToWorld(vec2f.x, vec2f.y, gravityDirection);
    }
    
    public static Vec3 rotToVec(float yaw, float pitch) {
        double radPitch = pitch * 0.017453292;
        double radNegYaw = -yaw * 0.017453292;
        double cosNegYaw = Math.cos(radNegYaw);
        double sinNegYaw = Math.sin(radNegYaw);
        double cosPitch = Math.cos(radPitch);
        double sinPitch = Math.sin(radPitch);
        return new Vec3(sinNegYaw * cosPitch, -sinPitch, cosNegYaw * cosPitch);
    }
    
    public static Vec2 vecToRot(double x, double y, double z) {
        double sinPitch = Mth.clamp(-y, -1.0, 1.0);
        double radPitch = Math.asin(sinPitch);
        double cosPitch = Math.cos(radPitch);
        if (cosPitch < 1.0E-7) {
            // looking straight along the gravity axis; yaw is degenerate, keep 0
            return new Vec2(0.0F, (float) (radPitch) / 0.017453292F);
        }
        double sinNegYaw = x / cosPitch;
        double cosNegYaw = Mth.clamp(z / cosPitch, -1, 1);
        double radNegYaw = Math.acos(cosNegYaw);
        if (sinNegYaw < 0) radNegYaw = Math.PI * 2 - radNegYaw;
        
        return new Vec2(Mth.wrapDegrees((float) (-radNegYaw) / 0.017453292F), (float) (radPitch) / 0.017453292F);
    }
    
    public static Vec2 vecToRot(Vec3 vec3d) {
        return vecToRot(vec3d.x, vec3d.y, vec3d.z);
    }
    
    private static final Quaternionf[] WORLD_ROTATION_QUATERNIONS = new Quaternionf[6];
    
    static {
        WORLD_ROTATION_QUATERNIONS[0] = new Quaternionf();
        
        WORLD_ROTATION_QUATERNIONS[1] = Axis.ZP.rotationDegrees(-180);
        
        WORLD_ROTATION_QUATERNIONS[2] = Axis.XP.rotationDegrees(-90);
        
        WORLD_ROTATION_QUATERNIONS[3] = Axis.XP.rotationDegrees(-90);
        WORLD_ROTATION_QUATERNIONS[3].mul(Axis.YP.rotationDegrees(-180));
        
        WORLD_ROTATION_QUATERNIONS[4] = Axis.XP.rotationDegrees(-90);
        WORLD_ROTATION_QUATERNIONS[4].mul(Axis.YP.rotationDegrees(-90));
        
        WORLD_ROTATION_QUATERNIONS[5] = Axis.XP.rotationDegrees(-90);
        WORLD_ROTATION_QUATERNIONS[5].mul(Axis.YP.rotationDegrees(-270));
    }
    
    /**
     * Note: this is the rotation that rotates the world for rendering, not the entity.
     * Note: don't modify the quaternion object in-place.
     * TODO change return value to {@link Quaternionfc}
     */
    public static Quaternionf getWorldRotationQuaternion(Direction gravityDirection) {
        return WORLD_ROTATION_QUATERNIONS[gravityDirection.get3DDataValue()];
    }

    /**
     * @deprecated stateless shortest-arc frame; its twist is arbitrary and unstable
     * (especially near-antiparallel to down). Use the entity's persistent frame
     * quaternion from the gravity capability instead.
     */
    @Deprecated
    public static Quaternionf getWorldRotationQuaternion(Vec3 gravityVector) {
        // We want a rotation that transforms gravityVector to DOWN (0, -1, 0)
        // Because "World Rotation" means rotating the world so that gravity points down for the player.
        return QuaternionUtil.getRotationBetween(gravityVector, new Vec3(0, -1, 0));
    }
    
    private static final Quaternionf[] ENTITY_ROTATION_QUATERNIONS = new Quaternionf[6];
    
    static {
        for (int i = 0; i < 6; i++) {
            ENTITY_ROTATION_QUATERNIONS[i] = new Quaternionf().set(WORLD_ROTATION_QUATERNIONS[i]).conjugate();
        }
    }
    
    /**
     * Note: this is the rotation that rotates the entity, not the world.
     * Note: don't modify the quaternion object in-place
     */
    public static Quaternionf getCameraRotationQuaternion(Direction gravityDirection) {
        return ENTITY_ROTATION_QUATERNIONS[gravityDirection.get3DDataValue()];
    }
    
    public static Quaternionf getRotationBetween(Direction d1, Direction d2) {
        Vec3 start = new Vec3(d1.step());
        Vec3 end = new Vec3(d2.step());
        if (d1.getOpposite() == d2) {
            return new Quaternionf().fromAxisAngleDeg(new Vector3f(0, 0, -1), 180.0f);
        }
        else {
            return QuaternionUtil.getRotationBetween(start, end);
        }
    }
    
    public static Quaternionf interpolate(Quaternionf startGravityRotation, Quaternionf endGravityRotation, float progress) {
        return new Quaternionf().set(startGravityRotation).slerp(endGravityRotation, progress);
    }
    
    public static AABB makeBoxFromDimensions(
        EntityDimensions dimensions, Direction gravityDir, Vec3 pos
    ) {
        AABB rawBox = dimensions.makeBoundingBox(0, 0, 0);
        return boxPlayerToWorld(rawBox, gravityDir).move(pos);
    }

    public static AABB makeBoxFromDimensions(
        EntityDimensions dimensions, Vec3 gravityDir, Vec3 pos
    ) {
        AABB rawBox = dimensions.makeBoundingBox(0, 0, 0);
        return boxPlayerToWorld(rawBox, gravityDir).move(pos);
    }
}
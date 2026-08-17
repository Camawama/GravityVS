package net.camacraft.gravityunbound.util;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public abstract class QuaternionUtil {
    public static Quaternionf getViewRotation(float pitch, float yaw) {
        Quaternionf r1 = new Quaternionf().fromAxisAngleDeg(new Vector3f(1, 0, 0), pitch);
        Quaternionf r2 = new Quaternionf().fromAxisAngleDeg(new Vector3f(0, 1, 0), yaw + 180);
        r1.mul(r2);
        return r1;
    }

    /**
     * Shortest-arc rotation from {@code from} to {@code to}.
     * When the vectors are (nearly) antiparallel the rotation axis is ambiguous;
     * an arbitrary perpendicular axis is chosen. Prefer
     * {@link #getRotationBetween(Vec3, Vec3, Vec3)} when a stable axis is available
     * (e.g. the entity's current roll axis) so 180 degree flips stay deterministic.
     */
    public static Quaternionf getRotationBetween(Vec3 from, Vec3 to) {
        return getRotationBetween(from, to, null);
    }

    /**
     * Shortest-arc rotation from {@code from} to {@code to}, using
     * {@code antiparallelAxis} as the rotation axis when the vectors are opposite.
     */
    public static Quaternionf getRotationBetween(Vec3 from, Vec3 to, Vec3 antiparallelAxis) {
        from = from.normalize();
        to = to.normalize();

        double dot = from.dot(to);

        if (dot >= 0.999999) {
            return new Quaternionf();
        }

        if (dot <= -0.999999) {
            // 180 degree rotation; axis is ambiguous, use the provided one when possible
            Vec3 axis = antiparallelAxis;
            if (axis == null || axis.lengthSqr() < 0.000001) {
                axis = from.cross(new Vec3(1, 0, 0));
                if (axis.lengthSqr() < 0.000001) {
                    axis = from.cross(new Vec3(0, 1, 0));
                }
            }
            else {
                // remove any component along `from` so the axis is perpendicular
                axis = axis.subtract(from.scale(axis.dot(from)));
                if (axis.lengthSqr() < 0.000001) {
                    axis = from.cross(new Vec3(1, 0, 0));
                    if (axis.lengthSqr() < 0.000001) {
                        axis = from.cross(new Vec3(0, 1, 0));
                    }
                }
            }
            axis = axis.normalize();
            return new Quaternionf().fromAxisAngleRad(
                new Vector3f((float) axis.x, (float) axis.y, (float) axis.z),
                (float) Math.PI
            );
        }

        Vec3 axis = from.cross(to).normalize();
        double angle = Math.acos(Mth.clamp(dot, -1.0, 1.0));
        return new Quaternionf().fromAxisAngleRad(
            new Vector3f((float) axis.x, (float) axis.y, (float) axis.z),
            (float) angle
        );
    }

    /**
     * Angle in radians between two direction vectors.
     */
    public static double angleBetween(Vec3 a, Vec3 b) {
        double dot = a.normalize().dot(b.normalize());
        return Math.acos(Mth.clamp(dot, -1.0, 1.0));
    }

    /**
     * Rotate direction {@code from} towards {@code to} by at most {@code maxRadians}.
     * Returns a normalized vector. Uses {@code antiparallelAxis} to disambiguate
     * 180 degree targets.
     */
    public static Vec3 rotateTowards(Vec3 from, Vec3 to, double maxRadians, Vec3 antiparallelAxis) {
        double angle = angleBetween(from, to);
        if (angle <= maxRadians) {
            return to.normalize();
        }
        Quaternionf full = getRotationBetween(from, to, antiparallelAxis);
        Quaternionf partial = new Quaternionf().slerp(full, (float) (maxRadians / angle));
        return rotate(from.normalize(), partial);
    }

    // using mutable objects could easily cause bugs if forget to copy
    public static Vec3 rotate(Vec3 vec, Quaternionf quaternionf) {
        Vector3f vector3f = vec.toVector3f();
        vector3f.rotate(quaternionf);
        return new Vec3(vector3f);
    }
}

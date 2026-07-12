package net.cama.gravityapivs.util;

import org.joml.Vector3f;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared pieces of the glow-ink-sac gravity field visualization.
 *
 * Design: the field EXTENT is drawn with large, long-lived colored dust
 * (boundary wireframe/rings), and the field DIRECTION with flame particles on
 * FIXED streamlines — repeated spawns on the same line read as dashed flow
 * lines instead of uncorrelated sparks. Blue = attract, orange = repulse.
 * Everything is spawned "always visible" so vanilla's 32-block particle cull
 * cannot eat the far side of a large field.
 */
public final class FieldVisuals {

    /** Boundary marker colors (dust is big, bright and does not fade fast). */
    public static final DustParticleOptions BOUNDARY_ATTRACT =
        new DustParticleOptions(new Vector3f(0.35f, 0.55f, 1.0f), 1.4f);
    public static final DustParticleOptions BOUNDARY_REPULSE =
        new DustParticleOptions(new Vector3f(1.0f, 0.55f, 0.2f), 1.4f);

    public static DustParticleOptions boundary(boolean attracting) {
        return attracting ? BOUNDARY_ATTRACT : BOUNDARY_REPULSE;
    }

    /** Flow marker: blue soul flame for attract, orange flame for repulse. */
    public static ParticleOptions flow(boolean attracting) {
        return attracting ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME;
    }

    /** A uniformly random point on one of the 12 edges of a box (wireframe). */
    public static Vec3 randomPointOnBoxEdge(AABB box, RandomSource random) {
        int edge = random.nextInt(12);
        int alongAxis = edge / 4;   // the axis the edge runs along
        int corner = edge % 4;      // min/max combination of the other two axes
        double t = random.nextDouble();

        return switch (alongAxis) {
            case 0 -> new Vec3(
                box.minX + t * (box.maxX - box.minX),
                (corner & 1) == 0 ? box.minY : box.maxY,
                (corner & 2) == 0 ? box.minZ : box.maxZ
            );
            case 1 -> new Vec3(
                (corner & 1) == 0 ? box.minX : box.maxX,
                box.minY + t * (box.maxY - box.minY),
                (corner & 2) == 0 ? box.minZ : box.maxZ
            );
            default -> new Vec3(
                (corner & 1) == 0 ? box.minX : box.maxX,
                (corner & 2) == 0 ? box.minY : box.maxY,
                box.minZ + t * (box.maxZ - box.minZ)
            );
        };
    }

    /**
     * The 26 fixed radial spoke directions (all sign combinations, normalized).
     * Core flow particles ride these so they trace readable dashed rays.
     */
    public static final Vec3[] SPOKES = buildSpokes();

    private static Vec3[] buildSpokes() {
        java.util.List<Vec3> spokes = new java.util.ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    spokes.add(new Vec3(x, y, z).normalize());
                }
            }
        }
        return spokes.toArray(new Vec3[0]);
    }

    private FieldVisuals() {}
}

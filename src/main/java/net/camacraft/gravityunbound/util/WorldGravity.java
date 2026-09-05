package net.camacraft.gravityunbound.util;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.Ship;

import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.compat.AdAstraCompat;

import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The dimension's AMBIENT gravity for entities, particles and fluids, and
 * how a field BLENDS with it.
 *
 * A field normally REPLACES gravity where it reaches. A field set to blend
 * instead ADDS its own vector to the ambient one: a sideways plate in the
 * overworld tilts gravity diagonally, a ceiling plate at 1 g cancels it
 * into a weightless zone, a core adds its pull to the planet's. Entities
 * take the summed vector's direction and magnitude (the mod's continuous
 * frames handle any angle); fluids and particles, which only know cardinal
 * downs, take the summed vector's dominant axis.
 */
public final class WorldGravity {

    private static final double BASE = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;

    /** The level's ambient entity gravity as a WORLD vector, blocks/tick^2. */
    public static Vec3 ambient(Level level) {
        Vec3 down = DimensionGravity.downFor(level);
        if (down == null) {
            down = new Vec3(0.0, -1.0, 0.0);
        }
        double accel = BASE * DimensionGravity.strengthFor(level) * AdAstraCompat.planetGravity(level);
        return down.scale(accel);
    }

    /** The same vector expressed in a ship's block grid (rotation only). */
    public static Vec3 toGrid(Vec3 world, @Nullable Ship ship) {
        if (ship == null) {
            return world;
        }
        Vector3d v = new Quaterniond(ship.getTransform().getShipToWorldRotation()).conjugate()
            .transform(new Vector3d(world.x, world.y, world.z));
        return new Vec3(v.x, v.y, v.z);
    }

    /** A blended entity gravity: unit direction and strength as a multiple of vanilla. */
    public record Blend(Vec3 direction, double strengthScale) {}

    /**
     * Field (unit direction, strength as a multiple of vanilla 0.08) plus
     * ambient (blocks/tick^2, SAME frame). A vanishing sum keeps the field's
     * direction at zero strength — a weightless zone.
     */
    public static Blend blend(Vec3 fieldDirection, double fieldStrengthScale, Vec3 ambient) {
        Vec3 sum = fieldDirection.scale(fieldStrengthScale * BASE).add(ambient);
        double length = sum.length();
        if (length < 1.0E-7) {
            return new Blend(fieldDirection, 0.0);
        }
        return new Blend(sum.scale(1.0 / length), length / BASE);
    }

    /**
     * Cardinal down for fluids/particles: field cardinal x its acceleration
     * plus the ambient vector (same grid), dominant axis with the fluid
     * sector tie rule (down > x > z > up). A vanishing sum keeps the
     * field's own cardinal.
     */
    public static Direction blendCardinal(Direction fieldDown, double fieldAccel, Vec3 ambientInGrid) {
        Vec3 sum = Vec3.atLowerCornerOf(fieldDown.getNormal()).scale(fieldAccel).add(ambientInGrid);
        if (sum.lengthSqr() < 1.0E-14) {
            return fieldDown;
        }
        return nearestCardinal(sum);
    }

    /** Dominant-axis cardinal, ties resolved down > x > z > up. */
    public static Direction nearestCardinal(Vec3 v) {
        double ax = Math.abs(v.x);
        double ay = Math.abs(v.y);
        double az = Math.abs(v.z);
        double dominant = Math.max(ax, Math.max(ay, az));
        if (ay == dominant && v.y < 0.0) {
            return Direction.DOWN;
        }
        if (ax == dominant && ax > 0.0) {
            return v.x < 0.0 ? Direction.WEST : Direction.EAST;
        }
        if (az == dominant && az > 0.0) {
            return v.z < 0.0 ? Direction.NORTH : Direction.SOUTH;
        }
        return Direction.UP;
    }

    private WorldGravity() {}
}

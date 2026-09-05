package net.camacraft.gravityunbound.util;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.DimensionParametersResolver;
import org.valkyrienskies.mod.util.McMathUtilKt;

import net.camacraft.gravityunbound.compat.GenesisCompat;
import net.camacraft.gravityunbound.compat.VLibCompat;
import net.camacraft.gravityunbound.compat.VModCompat;

import net.minecraft.server.level.ServerLevel;

/**
 * The gravity Valkyrien Skies (and the mods riding on it) actually apply
 * to a ship, in m/s^2 on the world axes — what a field that REPLACES
 * gravity has to cancel. Every writer of ship gravity in the supported
 * stack is consulted, most specific first:
 * <ol>
 * <li>VMod's per-ship custom vector, when the player set one;</li>
 * <li>Genesis mini-scale dimensions, whose ship gravity Genesis zeroes
 *     directly on the physics world (bypassing VS's parameter table);</li>
 * <li>VS's per-dimension parameters (the table VMod's dimensional gravity
 *     also writes), or VS's default when a dimension has none;</li>
 * </ol>
 * plus VLib's per-ship correction where its dimension multiplier applies.
 */
public final class ShipGravity {

    /** The dimension's gravity vector as VS's parameter table has it. */
    public static Vector3d dimension(ServerLevel level) {
        DimensionParametersResolver.Parameters parameters =
            DimensionParametersResolver.INSTANCE.getDimensionMap().get(VSGameUtilsKt.getDimensionId(level));
        Vector3dc gravity = parameters != null ? parameters.getGravity() : null;
        return new Vector3d(gravity != null ? gravity : McMathUtilKt.getDEFAULT_WORLD_GRAVITY());
    }

    /** The gravity acting on this particular ship right now. */
    public static Vector3d actingOn(ServerLevel level, LoadedServerShip ship) {
        Vector3dc custom = VModCompat.customGravity(ship);
        if (custom != null) {
            return new Vector3d(custom);
        }
        Vector3d gravity = GenesisCompat.isMiniScale(level) ? new Vector3d() : dimension(level);
        gravity.add(VLibCompat.shipGravityAdjustment(VSGameUtilsKt.getDimensionId(level)));
        return gravity;
    }

    private ShipGravity() {}
}

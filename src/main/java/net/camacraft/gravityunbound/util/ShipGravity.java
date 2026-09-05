package net.camacraft.gravityunbound.util;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.DimensionParametersResolver;
import org.valkyrienskies.mod.util.McMathUtilKt;

import net.camacraft.gravityunbound.compat.VModCompat;

import net.minecraft.server.level.ServerLevel;

/**
 * The gravity Valkyrien Skies (and the mods riding on it) actually apply
 * to a ship, in m/s^2 on the world axes — what a field that REPLACES
 * gravity has to cancel. The dimension's vector comes from VS's own
 * per-dimension parameters (the same table VS registers with its physics
 * world; VS's default when a dimension has none), overridden by VMod's
 * per-ship custom vector when the player set one.
 */
public final class ShipGravity {

    /** The dimension's gravity vector as VS registers it for its physics. */
    public static Vector3d dimension(ServerLevel level) {
        DimensionParametersResolver.Parameters parameters =
            DimensionParametersResolver.INSTANCE.getDimensionMap().get(VSGameUtilsKt.getDimensionId(level));
        Vector3dc gravity = parameters != null ? parameters.getGravity() : null;
        return new Vector3d(gravity != null ? gravity : McMathUtilKt.getDEFAULT_WORLD_GRAVITY());
    }

    /** The gravity acting on this particular ship right now. */
    public static Vector3d actingOn(ServerLevel level, LoadedServerShip ship) {
        Vector3dc custom = VModCompat.customGravity(ship);
        return custom != null ? new Vector3d(custom) : dimension(level);
    }

    private ShipGravity() {}
}

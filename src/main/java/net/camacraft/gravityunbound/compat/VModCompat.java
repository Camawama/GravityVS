package net.camacraft.gravityunbound.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import net.minecraftforge.fml.ModList;

/**
 * VMod ("the_vmod") lets players give a single ship its own gravity vector
 * — its {@code GravityController} attachment (a physics listener) applies
 * {@code (custom - dimension) x mass} every physics tick. A field that
 * REPLACES gravity for a held ship must cancel the gravity actually acting
 * on it, i.e. VMod's custom vector when one is set, not the dimension's:
 * cancelling the dimension's gravity on a ship VMod had already zeroed
 * left a net 1 g UPWARD and two core ships floated to the ceiling.
 * Reflective; nothing here loads without VMod.
 */
public final class VModCompat {

    private static final boolean ACTIVE;
    @Nullable
    private static final Class<?> CONTROLLER;
    @Nullable
    private static final MethodHandle USE_DIMENSION_GRAVITY;
    @Nullable
    private static final MethodHandle GRAVITY_VECTOR;

    static {
        boolean active = false;
        Class<?> controller = null;
        MethodHandle useDimension = null;
        MethodHandle vector = null;
        if (ModList.get().isLoaded("the_vmod")) {
            try {
                controller = Class.forName("net.spaceeye.vmod.shipAttachments.GravityController");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                useDimension = lookup.findVirtual(controller, "getUseDimensionGravity", MethodType.methodType(boolean.class));
                vector = lookup.findVirtual(controller, "getGravityVector", MethodType.methodType(Vector3dc.class));
                active = true;
            }
            catch (Throwable t) {
                controller = null;
                useDimension = null;
                vector = null;
            }
        }
        ACTIVE = active;
        CONTROLLER = controller;
        USE_DIMENSION_GRAVITY = useDimension;
        GRAVITY_VECTOR = vector;
    }

    /**
     * VMod's custom gravity vector (m/s^2, world axes) for the ship, or
     * null when VMod is absent, the ship has no controller, or the
     * controller defers to the dimension's gravity.
     */
    @Nullable
    public static Vector3dc customGravity(LoadedServerShip ship) {
        if (!ACTIVE || CONTROLLER == null) {
            return null;
        }
        try {
            Object controller = ship.getAttachment(CONTROLLER);
            if (controller == null || (boolean) USE_DIMENSION_GRAVITY.invoke(controller)) {
                return null;
            }
            return (Vector3dc) GRAVITY_VECTOR.invoke(controller);
        }
        catch (Throwable t) {
            return null;
        }
    }

    private VModCompat() {}
}

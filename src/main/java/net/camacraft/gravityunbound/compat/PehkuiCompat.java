package net.camacraft.gravityunbound.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

/**
 * Soft Pehkui support (reflective; no build dependency; nothing is touched
 * when Pehkui is absent). VS Genesis bundles Pehkui to scale players and
 * ships down in its Great Unknown.
 *
 * Pehkui scales the walk animation's limb distance with a {@code ModifyArg}
 * on the call vanilla makes INSIDE {@code calculateEntityAnimation}. This
 * mod replaces that method body (the distance must be measured in the
 * gravity frame, not world-horizontally), so the hook never runs there and a
 * scaled player's limbs froze on every face but the deck. The replacement
 * applies Pehkui's own scaling itself through this class.
 */
public final class PehkuiCompat {
    private static final boolean LOADED = ModList.get().isLoaded("pehkui");
    private static boolean resolved;
    private static MethodHandle modifyLimbDistance;

    private PehkuiCompat() {
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        if (!LOADED) {
            return;
        }
        try {
            Class<?> utils = Class.forName("virtuoel.pehkui.util.ScaleUtils");
            modifyLimbDistance = MethodHandles.publicLookup().findStatic(
                utils, "modifyLimbDistance", MethodType.methodType(float.class, float.class, Entity.class));
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            modifyLimbDistance = null;
        }
    }

    /** Pehkui's limb-distance scaling for {@code entity}; identity without Pehkui. */
    public static float modifyLimbDistance(float distance, Entity entity) {
        resolve();
        if (modifyLimbDistance == null) {
            return distance;
        }
        try {
            return (float) modifyLimbDistance.invokeExact(distance, entity);
        }
        catch (Throwable t) {
            return distance;
        }
    }
}

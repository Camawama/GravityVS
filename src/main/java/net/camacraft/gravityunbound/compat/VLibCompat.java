package net.camacraft.gravityunbound.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import net.minecraftforge.fml.ModList;

/**
 * VLib (the Genesis/VS helper library) gives dimensions a ship-gravity
 * multiplier: its per-ship physics listener applies
 * {@code (1 - multiplier) x 10 x mass} straight UP whenever the multiplier
 * is not one and the dimension's "apply gravity" flag is set — i.e. it
 * corrects VS's 10 m/s^2 down to the planet's. A field that REPLACES
 * gravity has to cancel that adjusted total, not VS's raw value.
 * Reflective; nothing here loads without VLib.
 */
public final class VLibCompat {

    @Nullable
    private static final Object MANAGER;
    @Nullable
    private static final MethodHandle SETTINGS_FOR_LEVEL;
    @Nullable
    private static final MethodHandle GRAVITY;
    @Nullable
    private static final MethodHandle SHOULD_APPLY;

    static {
        Object manager = null;
        MethodHandle settingsFor = null;
        MethodHandle gravity = null;
        MethodHandle shouldApply = null;
        if (ModList.get().isLoaded("vlib")) {
            try {
                Class<?> managerClass = Class.forName("g_mungus.vlib.dimension.DimensionSettingsManager");
                Class<?> settingsClass = Class.forName("g_mungus.vlib.data.DimensionSettings");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                manager = managerClass.getField("INSTANCE").get(null);
                settingsFor = lookup.findVirtual(managerClass, "getSettingsForLevel",
                    MethodType.methodType(settingsClass, String.class));
                gravity = lookup.findVirtual(settingsClass, "getGravity", MethodType.methodType(double.class));
                shouldApply = lookup.findVirtual(settingsClass, "getShouldApplyGravity", MethodType.methodType(boolean.class));
            }
            catch (Throwable t) {
                manager = null;
                settingsFor = null;
                gravity = null;
                shouldApply = null;
            }
        }
        MANAGER = manager;
        SETTINGS_FOR_LEVEL = settingsFor;
        GRAVITY = gravity;
        SHOULD_APPLY = shouldApply;
    }

    /**
     * The force-per-mass VLib's listener adds to ships in this VS dimension
     * (m/s^2, world axes), zero when VLib is absent or inactive there.
     */
    public static Vector3d shipGravityAdjustment(String vsDimensionId) {
        if (MANAGER == null || SETTINGS_FOR_LEVEL == null || GRAVITY == null || SHOULD_APPLY == null) {
            return new Vector3d();
        }
        try {
            Object settings = SETTINGS_FOR_LEVEL.invoke(MANAGER, vsDimensionId);
            if (settings == null) {
                return new Vector3d();
            }
            double multiplier = (double) GRAVITY.invoke(settings);
            boolean apply = (boolean) SHOULD_APPLY.invoke(settings);
            if (multiplier == 1.0 || !apply) {
                return new Vector3d();
            }
            return new Vector3d(0.0, (1.0 - multiplier) * 10.0, 0.0);
        }
        catch (Throwable t) {
            return new Vector3d();
        }
    }

    private VLibCompat() {}
}

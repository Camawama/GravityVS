package net.camacraft.gravityunbound.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/**
 * VS Genesis runs its space dimensions ("the Great Unknown", subspace) at
 * MINI SCALE — ships there are 1/16 size — and sets those dimensions'
 * ship gravity to ZERO directly on Valkyrien Skies' physics world (its
 * server mixin calls {@code updateDimension} with a zero vector), which
 * bypasses VS's per-dimension parameter table. A field that REPLACES
 * gravity must therefore know a mini-scale dimension has none to cancel,
 * or it adds a full g upward that VS never applied: a 1/16 ship under a
 * floor plate floated, and beside a wall plate was flung diagonally.
 * Reflective; nothing here loads without Genesis.
 */
public final class GenesisCompat {

    @Nullable
    private static final MethodHandle IS_MINI_SCALE;

    static {
        MethodHandle handle = null;
        if (ModList.get().isLoaded("genesis")) {
            try {
                Class<?> mod = Class.forName("shipwrights.genesis.GenesisMod");
                handle = MethodHandles.publicLookup().findStatic(
                    mod, "isMiniScale", MethodType.methodType(boolean.class, Level.class));
            }
            catch (Throwable t) {
                handle = null;
            }
        }
        IS_MINI_SCALE = handle;
    }

    /** True in a Genesis mini-scale (zero ship gravity) dimension. */
    public static boolean isMiniScale(Level level) {
        MethodHandle handle = IS_MINI_SCALE;
        if (handle == null) {
            return false;
        }
        try {
            return (boolean) handle.invoke(level);
        }
        catch (Throwable t) {
            return false;
        }
    }

    private GenesisCompat() {}
}

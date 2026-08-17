package net.camacraft.gravityunbound.util;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.config.GravityConfig;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Per-dimension ambient gravity.
 *
 * Sources, in override order: runtime API registrations (mods calling
 * {@link #set}) win over the {@code dimensionGravity} server config entries.
 * Applied inside the gravity capability as a LOW-priority direction effect
 * (priority 100), so every plating/core/normalizer field overrides it, and
 * entities leaving all fields settle onto the dimension's gravity instead of
 * plain world-down.
 */
public final class DimensionGravity {

    public record Entry(Vec3 down, double strength) {}

    // runtime API registrations (win over config)
    private static final Map<ResourceLocation, Entry> RUNTIME = new ConcurrentHashMap<>();

    // parsed config cache, rebuilt whenever the backing list instance changes
    // (config load/reload swaps the list)
    private static volatile List<? extends String> parsedFrom = null;
    private static volatile Map<ResourceLocation, Entry> configEntries = Map.of();

    /** API: set ambient gravity for a dimension (pass null entry via {@link #clear}). */
    public static void set(ResourceLocation dimension, Direction down, double strength) {
        RUNTIME.put(dimension, new Entry(Vec3.atLowerCornerOf(down.getNormal()), strength));
    }

    public static void clear(ResourceLocation dimension) {
        RUNTIME.remove(dimension);
    }

    /** The ambient gravity direction for this level, or null for vanilla. */
    @Nullable
    public static Vec3 downFor(Level level) {
        Entry entry = entryFor(level);
        return entry != null ? entry.down() : null;
    }

    public static double strengthFor(Level level) {
        Entry entry = entryFor(level);
        return entry != null ? entry.strength() : 1.0;
    }

    @Nullable
    private static Entry entryFor(Level level) {
        ResourceLocation dim = level.dimension().location();
        Entry runtime = RUNTIME.get(dim);
        if (runtime != null) {
            return runtime;
        }
        List<? extends String> raw;
        try {
            raw = GravityConfig.dimensionGravity.get();
        }
        catch (IllegalStateException e) {
            return null; // config not loaded yet
        }
        if (raw != parsedFrom) {
            configEntries = parse(raw);
            parsedFrom = raw;
        }
        return configEntries.get(dim);
    }

    private static Map<ResourceLocation, Entry> parse(List<? extends String> raw) {
        Map<ResourceLocation, Entry> out = new HashMap<>();
        for (String line : raw) {
            try {
                String[] kv = line.split("=", 2);
                ResourceLocation dim = new ResourceLocation(kv[0].trim());
                String[] parts = kv[1].split(",");
                Direction dir = Direction.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
                double strength = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 1.0;
                out.put(dim, new Entry(Vec3.atLowerCornerOf(dir.getNormal()), strength));
            }
            catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(DimensionGravity.class)
                    .warn("[GravityUnbound] bad dimensionGravity entry '{}': {}", line, e.toString());
            }
        }
        return Map.copyOf(out);
    }

    private DimensionGravity() {}
}

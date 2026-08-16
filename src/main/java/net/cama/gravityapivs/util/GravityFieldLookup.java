package net.cama.gravityapivs.util;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.cama.gravityapivs.config.GravityConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Block-position gravity queries for non-entity systems (fluids).
 *
 * Field block entities (plating, cores, normalizers) re-register themselves
 * every tick; entries expire automatically when a source stops ticking
 * (broken, chunk unloaded), so there is no lifecycle bookkeeping to get
 * wrong. Queries return the CARDINAL grid-space down direction the dominant
 * field enforces at a block position, or plain DOWN outside all fields.
 *
 * SAME-GRID ONLY: a source affects positions in its own block grid (a ship
 * plating affects ship fluids, in shipyard space — which is also the space
 * ship fluids simulate and render in). Cross-grid influence (a world core
 * pulling ship fluids) is deliberately out of scope.
 *
 * Thread-safety: registrations happen on the game threads; queries also
 * come from chunk-building threads (fluid rendering asks for flow vectors),
 * so the maps are concurrent and sources are read via a narrow interface.
 */
public final class GravityFieldLookup {

    /** How a field block entity answers position queries. */
    public interface Source {
        /** Grid-space cardinal down at {@code pos}, or null when out of range. */
        @Nullable Direction fluidDownAt(BlockPos pos);

        /** Chebyshev-distance prefilter radius around {@link #sourcePos()}. */
        int sourceMaxRange();

        /** The source's own position (registry key + prefilter center). */
        BlockPos sourcePos();

        /** Higher wins where sources overlap (normalizer 3, plating 2, core 1). */
        int sourcePriority();
    }

    private record Entry(Source source, long registeredAt) {}

    // ticks without re-registration after which an entry is ignored/pruned
    private static final long EXPIRY_TICKS = 40;

    private static final Map<Level, ConcurrentHashMap<BlockPos, Entry>> SOURCES =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** Called by field block entities every tick they are active. */
    public static void register(Level level, BlockPos pos, Source source) {
        SOURCES.computeIfAbsent(level, l -> new ConcurrentHashMap<>())
            .put(pos.immutable(), new Entry(source, level.getGameTime()));
    }

    /**
     * Immediately drops a source (block broken / settings changed) instead of
     * waiting for the tick-expiry — fluids re-settling after a field change
     * must already see the new field state.
     */
    public static void unregister(Level level, BlockPos pos) {
        ConcurrentHashMap<BlockPos, Entry> map = SOURCES.get(level);
        if (map != null) {
            map.remove(pos);
        }
    }

    /**
     * Wakes every fluid block in {@code volume} so it re-evaluates under the
     * CURRENT gravity rules. Settled fluid has no pending ticks — without
     * this, a wall-pinned puddle simply froze in its impossible shape when
     * its field was removed or re-pointed, instead of flowing back to
     * normal. Skips unloaded chunks (never force-loads).
     */
    public static void resettleFluids(Level level, net.minecraft.world.phys.AABB volume) {
        if (level.isClientSide()) {
            return;
        }
        int minX = net.minecraft.util.Mth.floor(volume.minX);
        int minY = Math.max(level.getMinBuildHeight(), net.minecraft.util.Mth.floor(volume.minY));
        int minZ = net.minecraft.util.Mth.floor(volume.minZ);
        int maxX = net.minecraft.util.Mth.floor(volume.maxX);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, net.minecraft.util.Mth.floor(volume.maxY));
        int maxZ = net.minecraft.util.Mth.floor(volume.maxZ);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                cursor.set(x, minY, z);
                if (!level.hasChunkAt(cursor)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    net.minecraft.world.level.material.FluidState fluid = level.getFluidState(cursor);
                    if (!fluid.isEmpty()) {
                        level.scheduleTick(cursor.immutable(), fluid.getType(), fluid.getType().getTickDelay(level));
                    }
                }
            }
        }
    }

    /** Convenience: re-settle a cubic radius around a removed/changed source. */
    public static void resettleFluidsAround(Level level, BlockPos center, int radius) {
        resettleFluids(level, new net.minecraft.world.phys.AABB(
            center.getX() - radius, center.getY() - radius, center.getZ() - radius,
            center.getX() + 1 + radius, center.getY() + 1 + radius, center.getZ() + 1 + radius
        ));
    }

    /**
     * The gravity down direction fluids at {@code pos} should use. Plain
     * {@link Direction#DOWN} outside all fields, when the feature is
     * disabled, or when {@code getter} cannot be resolved to a level.
     */
    public static Direction fluidDownAt(@Nullable BlockGetter getter, BlockPos pos) {
        Level level = resolveLevel(getter);
        if (level == null) {
            return Direction.DOWN;
        }
        ConcurrentHashMap<BlockPos, Entry> map = SOURCES.get(level);
        if (map == null || map.isEmpty()) {
            return Direction.DOWN;
        }
        if (!GravityConfig.gravityAffectsFluids.get()) {
            return Direction.DOWN;
        }

        long now = level.getGameTime();
        Direction best = null;
        int bestPriority = Integer.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;

        for (Map.Entry<BlockPos, Entry> mapEntry : map.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (now - entry.registeredAt() > EXPIRY_TICKS || now < entry.registeredAt()) {
                // stale (source stopped ticking) or from an older world time
                map.remove(mapEntry.getKey(), entry);
                continue;
            }
            Source source = entry.source();
            BlockPos sourcePos = source.sourcePos();
            int distance = Math.max(
                Math.abs(pos.getX() - sourcePos.getX()),
                Math.max(Math.abs(pos.getY() - sourcePos.getY()), Math.abs(pos.getZ() - sourcePos.getZ()))
            );
            if (distance > source.sourceMaxRange()) {
                continue;
            }
            int priority = source.sourcePriority();
            if (priority < bestPriority || (priority == bestPriority && distance >= bestDistance)) {
                continue;
            }
            Direction down = source.fluidDownAt(pos);
            if (down != null) {
                best = down;
                bestPriority = priority;
                bestDistance = distance;
            }
        }
        return best != null ? best : Direction.DOWN;
    }

    @Nullable
    private static Level resolveLevel(@Nullable BlockGetter getter) {
        if (getter instanceof Level level) {
            return level;
        }
        // chunk-building / render regions wrap the client level
        if (getter != null && FMLEnvironment.dist == Dist.CLIENT) {
            return Client.currentLevel();
        }
        return null;
    }

    /** Client-only accessor, isolated for dedicated-server safety. */
    private static final class Client {
        @Nullable
        static Level currentLevel() {
            return net.minecraft.client.Minecraft.getInstance().level;
        }
    }

    private GravityFieldLookup() {}
}

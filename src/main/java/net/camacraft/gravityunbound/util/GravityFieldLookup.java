package net.camacraft.gravityunbound.util;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.config.GravityConfig;

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

    /**
     * WHO is asking a block-grid position query: fluids and particles can be
     * switched off per field block (see {@code util.FieldTargets}); ANY is
     * the plain "is there a field here" question (seat detection, the
     * server's movement acceptance).
     */
    public enum Kind {
        FLUID, PARTICLE, ANY
    }

    /** How a field block entity answers position queries. */
    public interface Source {
        /**
         * Grid-space cardinal down at {@code pos} for {@code kind}, or null
         * when out of range or when the block's settings exclude that kind.
         */
        @Nullable Direction downAt(BlockPos pos, Kind kind);

        /** Chebyshev-distance prefilter radius around {@link #sourcePos()}. */
        int sourceMaxRange();

        /** The source's own position (registry key + prefilter center). */
        BlockPos sourcePos();

        /** Higher wins where sources overlap (normalizer 3, plating 2, core 1). */
        int sourcePriority();

        /**
         * RADIAL fields (gravity cores) render settled, supported fluid as
         * a FLUSH full-height skin hugging the mass ("planet skin") —
         * planar fields (plates, normalizers) keep vanilla level-height
         * surfaces. Purely a rendering hint; flow is unaffected.
         */
        default boolean radialSkin() {
            return false;
        }

        /**
         * Drop any cached entity query so a just-spawned entity is picked up
         * on the source's very next tick instead of after the cache expires
         * (spawn-egg mobs must adopt field gravity instantly).
         */
        default void invalidateEntityCache() {}
    }

    private record Entry(Source source, long registeredAt) {}

    // ticks without re-registration after which an entry is ignored/pruned
    private static final long EXPIRY_TICKS = 40;

    /**
     * Per-level source registry with a CHUNK-BUCKET spatial index. The
     * profiler showed field queries iterating EVERY registered source in
     * the level (ConcurrentHashMap traversal + sourceMaxRange totalling
     * ~35s of a capture) — a world full of test plates made every fluid
     * tick pay for all of them. Queries now scan only the buckets within
     * the level's largest source range of the position. {@code maxRange}
     * is monotonic (a removed large source leaves a slightly wider scan
     * ring of null bucket lookups — nanoseconds); the bucket key ignores Y
     * (a column of sources shares a bucket).
     */
    private static final class LevelIndex {
        final ConcurrentHashMap<BlockPos, Entry> byPos = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, ConcurrentHashMap<BlockPos, Entry>> byChunk =
            new ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger maxRange =
            new java.util.concurrent.atomic.AtomicInteger(1);
        // sources living in a ship's grid: only when any exist do WORLD
        // fluid queries look across grids (see fluidDownAt)
        final java.util.Set<BlockPos> shipSources = ConcurrentHashMap.newKeySet();

        static long chunkKey(int chunkX, int chunkZ) {
            return net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ);
        }

        void put(BlockPos pos, Entry entry, boolean onShip) {
            byPos.put(pos, entry);
            byChunk.computeIfAbsent(chunkKey(pos.getX() >> 4, pos.getZ() >> 4),
                k -> new ConcurrentHashMap<>()).put(pos, entry);
            maxRange.accumulateAndGet(entry.source().sourceMaxRange(), Math::max);
            if (onShip) {
                shipSources.add(pos);
            }
        }

        void remove(BlockPos pos) {
            byPos.remove(pos);
            shipSources.remove(pos);
            long key = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
            ConcurrentHashMap<BlockPos, Entry> bucket = byChunk.get(key);
            if (bucket != null) {
                bucket.remove(pos);
                if (bucket.isEmpty()) {
                    byChunk.remove(key, bucket);
                }
            }
        }
    }

    private static final Map<Level, LevelIndex> SOURCES =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** Called by field block entities every tick they are active. */
    public static void register(Level level, BlockPos pos, Source source) {
        BlockPos key = pos.immutable();
        boolean onShip = org.valkyrienskies.mod.common.VSGameUtilsKt.isBlockInShipyard(level, key);
        SOURCES.computeIfAbsent(level, l -> new LevelIndex())
            .put(key, new Entry(source, level.getGameTime()), onShip);
    }

    /**
     * Immediately drops a source (block broken / settings changed) instead of
     * waiting for the tick-expiry — fluids re-settling after a field change
     * must already see the new field state.
     */
    public static void unregister(Level level, BlockPos pos) {
        LevelIndex index = SOURCES.get(level);
        if (index != null) {
            index.remove(pos);
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

    /**
     * Wakes the entity caches of every source in range of {@code pos} — called
     * when an entity joins the level, so field effects (and the fresh-spawn
     * instant snap) reach it on the same tick instead of after the staggered
     * entity-query cache expires.
     */
    public static void invalidateEntityCachesNear(Level level, BlockPos pos) {
        LevelIndex index = SOURCES.get(level);
        if (index == null || index.byPos.isEmpty()) {
            return;
        }
        for (Map.Entry<BlockPos, Entry> mapEntry : index.byPos.entrySet()) {
            Source source = mapEntry.getValue().source();
            BlockPos sourcePos = source.sourcePos();
            int distance = Math.max(
                Math.abs(pos.getX() - sourcePos.getX()),
                Math.max(Math.abs(pos.getY() - sourcePos.getY()), Math.abs(pos.getZ() - sourcePos.getZ()))
            );
            if (distance <= source.sourceMaxRange()) {
                source.invalidateEntityCache();
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
     * Cheap fast-path test: does this level have ANY registered field
     * sources? Lets fluid hooks skip per-neighbor scans entirely in worlds
     * (or regions) with no gravity fields.
     */
    public static boolean hasSources(@Nullable BlockGetter getter) {
        Level level = resolveLevel(getter);
        if (level == null) {
            return false;
        }
        LevelIndex index = SOURCES.get(level);
        return index != null && !index.byPos.isEmpty() && GravityConfig.gravityAffectsFluids.get();
    }

    /**
     * Whether ANY field source claims {@code pos} — including fields whose
     * down is plain DOWN (a floor plate). Distinct from
     * {@code fluidDownAt(...) != DOWN}: source-conversion suppression must
     * cover DOWN-pointing field regions too, or plates over solid ground
     * could still mint permanent sources.
     */
    public static boolean hasFieldAt(@Nullable BlockGetter getter, BlockPos pos) {
        return bestFluidFieldAt(getter, pos) != null;
    }

    /**
     * The gravity down direction fluids at {@code pos} should use. Plain
     * {@link Direction#DOWN} outside all fields, when the feature is
     * disabled, or when {@code getter} cannot be resolved to a level.
     */
    public static Direction fluidDownAt(@Nullable BlockGetter getter, BlockPos pos) {
        Best best = bestFluidFieldAt(getter, pos);
        return best != null ? best.down() : Direction.DOWN;
    }

    /**
     * Whether the dominant field at {@code pos} is a RADIAL source (a
     * gravity core) — the renderer draws supported fluid in such fields as
     * a flush full-height skin. False outside fields and for planar
     * sources (plates, normalizers).
     */
    public static boolean isRadialFieldAt(@Nullable BlockGetter getter, BlockPos pos) {
        Best best = bestFluidFieldAt(getter, pos);
        return best != null && best.source().radialSkin();
    }

    /**
     * The fluid query: the position's own grid first; then — for WORLD
     * positions, on the game threads — the grids of ships whose fields reach
     * the point, so a ship carrying a field pulls the water it flies over
     * (the grid cardinal rotated to the world and snapped to the nearest
     * world cardinal, which world fluid can flow along). Ship grids never
     * consult the world in return, and chunk-building threads (render
     * regions) never consult ships at all — the ship index is not theirs to
     * read concurrently; the server's fluid states carry the shape anyway.
     */
    @Nullable
    private static Best bestFluidFieldAt(@Nullable BlockGetter getter, BlockPos pos) {
        Best own = bestFieldAt(getter, pos, GravityConfig.gravityAffectsFluids.get(), Kind.FLUID);
        if (own != null || !(getter instanceof Level level)) {
            return own;
        }
        if (!GravityConfig.gravityAffectsFluids.get() || !GravityConfig.shipFieldsAffectWorldFluids.get()) {
            return null;
        }
        LevelIndex index = SOURCES.get(level);
        if (index == null || index.shipSources.isEmpty()) {
            return null;
        }
        if (org.valkyrienskies.mod.common.VSGameUtilsKt.isBlockInShipyard(level, pos)) {
            return null;
        }
        return shipFieldAt(level, index, pos, Kind.FLUID);
    }

    private record Best(Direction down, Source source) {}

    /**
     * The best ship-mounted field covering a WORLD block position, answered
     * as the grid cardinal snapped to the nearest WORLD cardinal (fluids can
     * only flow along the world's axes). Cached per world cell per tick.
     */
    @Nullable
    private static Best shipFieldAt(Level level, LevelIndex index, BlockPos worldPos, Kind kind) {
        KindCache cache = kindCache(level, kind);
        Long key = worldPos.asLong();
        Object cached = cache.ship.get(key);
        if (cached != null) {
            return cached == NULL_BEST ? null : (Best) cached;
        }
        Best result = null;
        double reach = index.maxRange.get() + 1.0;
        double cx = worldPos.getX() + 0.5;
        double cy = worldPos.getY() + 0.5;
        double cz = worldPos.getZ() + 0.5;
        net.minecraft.world.phys.AABB probe = new net.minecraft.world.phys.AABB(
            cx - reach, cy - reach, cz - reach, cx + reach, cy + reach, cz + reach);
        for (org.valkyrienskies.core.api.ships.Ship ship
            : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipsIntersecting(level, probe)) {
            org.joml.Vector3d local = new org.joml.Vector3d(cx, cy, cz);
            ship.getTransform().getWorldToShipMatrix().transformPosition(local);
            Best onShip = computeBestFieldAt(level, index, BlockPos.containing(local.x, local.y, local.z), kind);
            if (onShip == null) {
                continue;
            }
            org.joml.Vector3d dir = new org.joml.Vector3d(
                onShip.down().getStepX(), onShip.down().getStepY(), onShip.down().getStepZ());
            ship.getTransform().getShipToWorldMatrix().transformDirection(dir);
            if (dir.lengthSquared() < 1.0E-12) {
                continue;
            }
            result = new Best(Direction.getNearest(dir.x, dir.y, dir.z), onShip.source());
            break;
        }
        cache.ship.put(key, result == null ? NULL_BEST : result);
        return result;
    }

    /**
     * PER-TICK QUERY MEMO. The fluid hooks ask for the field at the same
     * positions many times within one tick (a cell's own tick queries its
     * whole neighborhood, and each neighbor's tick re-queries it), and every
     * uncached query iterates all registered sources with distance math —
     * multiplied across tens of thousands of fluid ticks during a planet
     * re-settle, this was a real slice of the mass-update lag spikes. One
     * cache per THREAD (the server thread and each chunk-build render
     * thread), invalidated whenever the game time or level changes.
     * Staleness bound: registrations mutate in the block-entity phase, after
     * the scheduled fluid ticks — at worst a position answers one tick stale,
     * which the 40-tick registration expiry semantics already tolerate.
     */
    private static final Object NULL_BEST = new Object();

    /** One memo per consumer kind: a source may answer fluids but not particles. */
    private static final class KindCache {
        // grid cell -> best source in that cell's own grid
        final java.util.HashMap<Long, Object> grid = new java.util.HashMap<>(256);
        // world cell -> best ship-mounted source reaching it (cross-grid)
        final java.util.HashMap<Long, Object> ship = new java.util.HashMap<>(64);

        void clear() {
            grid.clear();
            ship.clear();
        }
    }

    private static final class QueryCache {
        @Nullable Level level;
        long time = Long.MIN_VALUE;
        final KindCache[] kinds = {new KindCache(), new KindCache(), new KindCache()};
    }

    private static final ThreadLocal<QueryCache> QUERY_CACHE = ThreadLocal.withInitial(QueryCache::new);

    /** The current tick's memo for {@code kind} on this thread. */
    private static KindCache kindCache(Level level, Kind kind) {
        QueryCache cache = QUERY_CACHE.get();
        long time = level.getGameTime();
        if (cache.level != level || cache.time != time) {
            cache.level = level;
            cache.time = time;
            for (KindCache k : cache.kinds) {
                k.clear();
            }
        }
        return cache.kinds[kind.ordinal()];
    }

    /**
     * Whether any field source claims the block {@code pos} for ENTITIES —
     * the same-grid block query, ungated by the fluid/particle feature
     * toggles. Used where both sides must agree from the block grid alone
     * (a seat on a ship: the client never receives the seat entity's field
     * state, so "is this seat under a field" is answered by the grid).
     */
    public static boolean hasEntityFieldAt(@Nullable BlockGetter getter, BlockPos pos) {
        return bestFieldAt(getter, pos, true, Kind.ANY) != null;
    }

    /**
     * Whether the world position lies within the Chebyshev reach of ANY
     * registered field source — in the world grid, or in the grid of a ship
     * whose bounds (inflated by the level's largest source reach) hold the
     * point. The server's movement acceptance uses it for the position a
     * CLIENT reports: a player approaching a plated face from outside its
     * field has a client frame the server has not caught up with yet, and
     * the server's own gravity state cannot know that.
     */
    public static boolean isWithinAnySourceRange(@Nullable BlockGetter getter, net.minecraft.world.phys.Vec3 worldPos) {
        Level level = resolveLevel(getter);
        if (level == null) {
            return false;
        }
        LevelIndex index = SOURCES.get(level);
        if (index == null || index.byPos.isEmpty()) {
            return false;
        }
        if (anySourceInRange(level, index, BlockPos.containing(worldPos.x, worldPos.y, worldPos.z))) {
            return true;
        }
        double reach = index.maxRange.get() + 1.0;
        net.minecraft.world.phys.AABB probe = new net.minecraft.world.phys.AABB(
            worldPos.x - reach, worldPos.y - reach, worldPos.z - reach,
            worldPos.x + reach, worldPos.y + reach, worldPos.z + reach);
        for (org.valkyrienskies.core.api.ships.Ship ship
            : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipsIntersecting(level, probe)) {
            org.joml.Vector3d local = new org.joml.Vector3d(worldPos.x, worldPos.y, worldPos.z);
            ship.getTransform().getWorldToShipMatrix().transformPosition(local);
            if (anySourceInRange(level, index, BlockPos.containing(local.x, local.y, local.z))) {
                return true;
            }
        }
        return false;
    }

    private static boolean anySourceInRange(Level level, LevelIndex index, BlockPos pos) {
        long now = level.getGameTime();
        int ring = (index.maxRange.get() + 15) >> 4;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                ConcurrentHashMap<BlockPos, Entry> bucket =
                    index.byChunk.get(LevelIndex.chunkKey(chunkX + dx, chunkZ + dz));
                if (bucket == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, Entry> mapEntry : bucket.entrySet()) {
                    Entry entry = mapEntry.getValue();
                    if (now - entry.registeredAt() > EXPIRY_TICKS || now < entry.registeredAt()) {
                        continue;
                    }
                    BlockPos sourcePos = entry.source().sourcePos();
                    int distance = Math.max(
                        Math.abs(pos.getX() - sourcePos.getX()),
                        Math.max(Math.abs(pos.getY() - sourcePos.getY()), Math.abs(pos.getZ() - sourcePos.getZ()))
                    );
                    if (distance <= entry.source().sourceMaxRange()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The gravity FRAME for a particle at a world position — the rotation
     * mapping world directions into a frame whose -Y is the field's down —
     * or null outside every field (or with the feature disabled). Particles
     * inside a field keep their velocity in this frame (see the client
     * ParticleMixin), so every particle class's own physics — vanilla's
     * gravity, a leaf's sway, a smoke column's rise, a mod's extra pull —
     * happens along the field's axes without knowing about it.
     *
     * Valkyrien Skies moves particles spawned on a ship into WORLD
     * coordinates (its transform_particles mixins), while a ship's field
     * sources are registered in the ship's own block grid — so a world
     * position query never found a ship's fields. The world grid is queried
     * first; then every ship whose bounds, inflated by the level's largest
     * source reach (a plate at range 16 projects its field far outside the
     * hull), hold the point is asked in its own grid, and the frame is the
     * grid cardinal's canonical frame composed with the ship's rotation.
     * World answers are the shared canonical frames (exact axis math); ship
     * answers cache per world block cell per tick.
     */
    @Nullable
    public static org.joml.Quaternionf particleFrameAt(@Nullable BlockGetter getter, double x, double y, double z) {
        if (!GravityConfig.gravityAffectsParticles.get()) {
            return null;
        }
        Level level = resolveLevel(getter);
        if (level == null) {
            return null;
        }
        LevelIndex index = SOURCES.get(level);
        if (index == null || index.byPos.isEmpty()) {
            return null;
        }

        BlockPos worldPos = BlockPos.containing(x, y, z);
        Best world = bestFieldAt(level, worldPos, true, Kind.PARTICLE);
        if (world != null) {
            return net.camacraft.gravityunbound.util.RotationUtil.getWorldRotationQuaternion(world.down());
        }
        if (index.shipSources.isEmpty()) {
            return null;
        }

        // (the particle cache holds frames, not Best records: its own slot)
        KindCache cache = kindCache(level, Kind.PARTICLE);
        Long key = worldPos.asLong();
        Object cached = cache.ship.get(key);
        if (cached != null) {
            return cached == NULL_BEST ? null : (org.joml.Quaternionf) cached;
        }

        org.joml.Quaternionf result = null;
        double reach = index.maxRange.get() + 1.0;
        net.minecraft.world.phys.AABB probe = new net.minecraft.world.phys.AABB(
            x - reach, y - reach, z - reach, x + reach, y + reach, z + reach);
        for (org.valkyrienskies.core.api.ships.Ship ship
            : org.valkyrienskies.mod.common.VSGameUtilsKt.getShipsIntersecting(level, probe)) {
            org.joml.Vector3d local = new org.joml.Vector3d(x, y, z);
            ship.getTransform().getWorldToShipMatrix().transformPosition(local);
            Best onShip = computeBestFieldAt(level, index, BlockPos.containing(local.x, local.y, local.z), Kind.PARTICLE);
            if (onShip == null) {
                continue;
            }
            // frame = canonical(gridDown) o shipRotation^-1: maps the world
            // direction the grid cardinal is drawn along onto local -Y
            org.joml.Quaterniondc shipRot = ship.getTransform().getShipToWorldRotation();
            org.joml.Quaternionf frame = new org.joml.Quaternionf(
                net.camacraft.gravityunbound.util.RotationUtil.getWorldRotationQuaternion(onShip.down()));
            frame.mul(new org.joml.Quaternionf(
                (float) shipRot.x(), (float) shipRot.y(), (float) shipRot.z(), (float) shipRot.w()).conjugate())
                .normalize();
            result = frame;
            break;
        }
        cache.ship.put(key, result == null ? NULL_BEST : result);
        return result;
    }

    @Nullable
    private static Best bestFieldAt(@Nullable BlockGetter getter, BlockPos pos, boolean enabled, Kind kind) {
        if (!enabled) {
            return null;
        }
        Level level = resolveLevel(getter);
        if (level == null) {
            return null;
        }
        LevelIndex index = SOURCES.get(level);
        if (index == null || index.byPos.isEmpty()) {
            return null;
        }

        KindCache cache = kindCache(level, kind);
        Long key = pos.asLong();
        Object cached = cache.grid.get(key);
        if (cached != null) {
            return cached == NULL_BEST ? null : (Best) cached;
        }
        Best best = computeBestFieldAt(level, index, pos, kind);
        cache.grid.put(key, best == null ? NULL_BEST : best);
        return best;
    }

    @Nullable
    private static Best computeBestFieldAt(Level level, LevelIndex index, BlockPos pos, Kind kind) {
        long now = level.getGameTime();
        Direction best = null;
        Source bestSource = null;
        int bestPriority = Integer.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;

        // spatial prune: only buckets within the level's largest source
        // range can possibly reach this position
        int ring = (index.maxRange.get() + 15) >> 4;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                ConcurrentHashMap<BlockPos, Entry> bucket =
                    index.byChunk.get(LevelIndex.chunkKey(chunkX + dx, chunkZ + dz));
                if (bucket == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, Entry> mapEntry : bucket.entrySet()) {
                    Entry entry = mapEntry.getValue();
                    if (now - entry.registeredAt() > EXPIRY_TICKS || now < entry.registeredAt()) {
                        // stale (source stopped ticking) or from an older world time
                        index.remove(mapEntry.getKey());
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
                    if (priority < bestPriority || (priority == bestPriority && distance > bestDistance)) {
                        continue;
                    }
                    Direction down = source.downAt(pos, kind);
                    if (down == null) {
                        continue;
                    }
                    // Exact ties (same priority, same distance — e.g. a cube edge
                    // cell equidistant from two plates) resolve by the same fixed
                    // direction priority the core's sector frames use, DOWN > X >
                    // Z > UP, instead of map iteration order. This keeps convex
                    // edges of plated builds pour-over lips of the upstream face;
                    // an arbitrary tie could hand a top-edge cell to a side plate,
                    // making the rim's pour into it a forbidden up-entry (a wrap
                    // stall at that edge).
                    if (priority == bestPriority && distance == bestDistance && best != null
                        && directionRank(down) >= directionRank(best)) {
                        continue;
                    }
                    best = down;
                    bestSource = source;
                    bestPriority = priority;
                    bestDistance = distance;
                }
            }
        }
        return best != null ? new Best(best, bestSource) : null;
    }

    /** Fixed frame priority for tie-breaking: DOWN > X > Z > UP. */
    private static int directionRank(Direction down) {
        return switch (down) {
            case DOWN -> 0;
            case WEST, EAST -> 1;
            case NORTH, SOUTH -> 2;
            case UP -> 3;
        };
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

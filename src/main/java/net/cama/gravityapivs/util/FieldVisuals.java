package net.cama.gravityapivs.util;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.core.api.ships.Ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Registry of active gravity field visuals (glow-ink-sac toggle).
 *
 * Block entities SUBMIT their field geometry every client tick; the client
 * renderer ({@code client.FieldVisualsRenderer}) draws all submitted fields as
 * animated line geometry in the level render pass. Entries expire a few ticks
 * after the last submission, so unloading, breaking or toggling a block cleans
 * itself up automatically.
 *
 * Geometry is in GRID coordinates (shipyard space for blocks on ships, world
 * space otherwise) together with the owning ship; the renderer applies the
 * ship's per-frame render transform, so visuals stick to moving ships
 * smoothly.
 *
 * This class is dist-neutral (no client imports) so common block entity code
 * can call it safely; only the renderer is client-only.
 */
public final class FieldVisuals {

    private static final int TTL_TICKS = 10;

    public record PlateKey(BlockPos pos, Direction side) {}

    /** A plate field: a box with flow along {@code flowDir} (the pull direction). */
    public record PlateField(
        AABB box, Direction flowDir, boolean attracting, @Nullable Ship ship, long expiresAt
    ) {}

    /** A core field: a sphere with radial flow. */
    public record CoreField(
        Vec3 center, double range, boolean attracting, @Nullable Ship ship, long expiresAt
    ) {}

    public static final Map<PlateKey, PlateField> PLATES = new HashMap<>();
    public static final Map<BlockPos, CoreField> CORES = new HashMap<>();

    public static void submitPlate(
        Level level, BlockPos pos, Direction side,
        AABB box, Direction flowDir, boolean attracting, @Nullable Ship ship
    ) {
        PLATES.put(
            new PlateKey(pos.immutable(), side),
            new PlateField(box, flowDir, attracting, ship, level.getGameTime() + TTL_TICKS)
        );
    }

    public static void submitCore(
        Level level, BlockPos pos, Vec3 center, double range, boolean attracting, @Nullable Ship ship
    ) {
        CORES.put(
            pos.immutable(),
            new CoreField(center, range, attracting, ship, level.getGameTime() + TTL_TICKS)
        );
    }

    public static void prune(long gameTime) {
        PLATES.values().removeIf(field -> gameTime > field.expiresAt());
        CORES.values().removeIf(field -> gameTime > field.expiresAt());
    }

    private FieldVisuals() {}
}

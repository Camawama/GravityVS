package net.cama.gravityapivs.util;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.core.api.ships.Ship;

import net.cama.gravityapivs.GravityAPI;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Registry of active gravity field visuals (glow-ink-sac toggle).
 *
 * Block entities SUBMIT their field geometry every client tick; the client
 * renderer ({@code client.FieldVisualsRenderer}) draws all submitted fields as
 * animated line geometry in the level render pass. Entries expire a few ticks
 * after the last submission, so unloading, breaking or toggling a block cleans
 * itself up automatically. Each entry also remembers the {@link Level} it was
 * submitted from: the prune pass drops entries from any other level (game time
 * alone is not a safe expiry across world changes — a new world's game time
 * can be smaller than the old one's, which would otherwise keep stale entries
 * and their Ship references pinned forever), and {@link #clearAll()} is called
 * when the client disconnects.
 *
 * Geometry is in GRID coordinates (shipyard space for blocks on ships, world
 * space otherwise) together with the owning ship; the renderer applies the
 * ship's per-frame render transform, so visuals stick to moving ships
 * smoothly.
 *
 * This class is dist-neutral (no client imports in any signature used from
 * common code) so common block entity code can call it safely; the client-only
 * bits live in guarded nested classes.
 */
public final class FieldVisuals {

    private static final int TTL_TICKS = 10;

    public record PlateKey(BlockPos pos, Direction side) {}

    /** A plate field: a box with flow along {@code flowDir} (the pull direction). */
    public record PlateField(
        AABB box, Direction flowDir, boolean attracting, @Nullable Ship ship, long expiresAt,
        Level level
    ) {}

    /** A core field: a sphere with radial flow. */
    public record CoreField(
        Vec3 center, double range, boolean attracting, @Nullable Ship ship, long expiresAt,
        Level level
    ) {}

    public static final Map<PlateKey, PlateField> PLATES = new HashMap<>();
    public static final Map<BlockPos, CoreField> CORES = new HashMap<>();

    public static void submitPlate(
        Level level, BlockPos pos, Direction side,
        AABB box, Direction flowDir, boolean attracting, @Nullable Ship ship
    ) {
        PLATES.put(
            new PlateKey(pos.immutable(), side),
            new PlateField(box, flowDir, attracting, ship, level.getGameTime() + TTL_TICKS, level)
        );
    }

    public static void submitCore(
        Level level, BlockPos pos, Vec3 center, double range, boolean attracting, @Nullable Ship ship
    ) {
        CORES.put(
            pos.immutable(),
            new CoreField(center, range, attracting, ship, level.getGameTime() + TTL_TICKS, level)
        );
    }

    public static void prune(long gameTime) {
        Level current = currentClientLevel();
        PLATES.values().removeIf(field ->
            gameTime > field.expiresAt() || (current != null && field.level() != current));
        CORES.values().removeIf(field ->
            gameTime > field.expiresAt() || (current != null && field.level() != current));
    }

    /** Drops every submitted field (and the Ship references they pin). */
    public static void clearAll() {
        PLATES.clear();
        CORES.clear();
    }

    /**
     * The level currently being rendered, or null when unavailable (e.g. on a
     * dedicated server). Client classes are only touched behind the dist
     * check so this class stays loadable on the server.
     */
    @Nullable
    private static Level currentClientLevel() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return null;
        }
        return Client.currentLevel();
    }

    /** Client-only accessor, isolated in its own class for dedicated-server safety. */
    private static final class Client {
        @Nullable
        static Level currentLevel() {
            return net.minecraft.client.Minecraft.getInstance().level;
        }
    }

    /**
     * Clears all visuals when the client disconnects, so entries (and their
     * Ship references) never survive into another world or session.
     * Registration mirrors {@code client.FieldVisualsRenderer}.
     */
    @Mod.EventBusSubscriber(modid = GravityAPI.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            clearAll();
        }

        private ClientEvents() {}
    }

    private FieldVisuals() {}
}

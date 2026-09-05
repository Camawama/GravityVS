package net.camacraft.gravityunbound.util;

import java.util.List;

import org.joml.primitives.AABBi;
import org.valkyrienskies.core.api.physics.blockstates.SolidState;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.api.events.RegisterBlockStateEvent;
import org.valkyrienskies.mod.common.config.VSGameConfig;

import net.camacraft.gravityunbound.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Tells Valkyrien Skies what a ship collides with when it meets gravity
 * plating: the plate's actual 1/16-thick PANELS.
 *
 * VS builds a ship-collision shape for every block state at startup —
 * solid blocks from their outline, non-solid ones from their COLLISION
 * shape. Plating has no collision shape at all (it is a field emitter
 * entities walk through), and VS's generator answers an empty shape with
 * nothing; the state builder then fell back to a shape VS never meant for
 * real blocks, and a ship set on plating rocked on it. Declaring the cell
 * non-colliding was no better: a ship pulled into the cell by the plate's
 * own field started into the wall behind it and VS pushed it back out,
 * over and over. Partial shapes are the path VS itself uses for stairs
 * and slabs in terrain, so the plate's panels — the geometry the player
 * sees — are registered as its collision boxes: a ship rests ON a floor
 * plate and AGAINST a wall plate, never inside the cell.
 *
 * Registered through VS's block-state event, which VS runs after its own
 * defaults each time it (re)builds block states, so these win.
 */
public final class ShipBlockShapes {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** Call once from common setup, before any world loads. */
    public static void register() {
        try {
            ValkyrienSkies.api().getRegisterBlockStateEvent().on(
                (java.util.function.Consumer<RegisterBlockStateEvent>) ShipBlockShapes::registerPlating);
        }
        catch (Throwable t) {
            LOGGER.warn("Gravity Unbound could not register plating collision shapes with Valkyrien Skies", t);
        }
    }

    private static void registerPlating(RegisterBlockStateEvent event) {
        if (!net.camacraft.gravityunbound.config.GravityConfig.shipsCollideWithPlatingPanels.get()) {
            LOGGER.info("Gravity Unbound: plating panel collision for ships is disabled by config; VS keeps its default shape");
            return;
        }
        Block plating = GravityBlocks.GRAVITY_PLATING.get();
        VSGameConfig.Server defaults = VSGameConfig.SERVER;
        // ONE shape object per distinct panel combination (the water/lava
        // variants of a state share its panels): the physics core interns
        // block types, and handing it thousands of distinct-but-equal
        // shapes is exactly the kind of thing a native library resents
        java.util.Map<String, SolidState> byPanels = new java.util.HashMap<>();
        int registered = 0;
        int failed = 0;
        for (BlockState state : plating.getStateDefinition().getPossibleStates()) {
            // the outline shape is the union of the state's panels (a full
            // cube for the transient no-side state). VS boxes are INCLUSIVE
            // voxel indices, 0..15 per axis (a full block is 0..15, a 1/16
            // panel on the floor is y 0..0) — not 0..16 edges.
            VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            List<AABBi> boxes = new java.util.ArrayList<>();
            for (AABB box : outline.toAabbs()) {
                int minX = firstVoxel(box.minX), minY = firstVoxel(box.minY), minZ = firstVoxel(box.minZ);
                int maxX = lastVoxel(box.maxX), maxY = lastVoxel(box.maxY), maxZ = lastVoxel(box.maxZ);
                if (maxX < minX || maxY < minY || maxZ < minZ) {
                    continue; // thinner than a voxel: nothing to collide with
                }
                boxes.add(new AABBi(minX, minY, minZ, maxX, maxY, maxZ));
            }
            if (boxes.isEmpty()) {
                continue;
            }
            String key = boxes.toString();
            SolidState solid = byPanels.get(key);
            if (solid == null && !byPanels.containsKey(key)) {
                solid = buildPanelState(event, defaults, boxes);
                byPanels.put(key, solid);
            }
            if (solid == null) {
                failed++;
                continue;
            }
            try {
                event.register(state, solid);
                registered++;
            }
            catch (RuntimeException e) {
                failed++;
                LOGGER.warn("Gravity Unbound could not register a plating collision shape for {}: {}", state, e.toString());
            }
        }
        LOGGER.info("Gravity Unbound registered {} plating collision shapes with Valkyrien Skies ({} distinct panel layouts, {} left on VS defaults)",
            registered, byPanels.size(), failed);
    }

    /**
     * ONLY Valkyrien Skies' own shape generator builds the shape (boxes plus
     * the collision spheres it derives, the same way it handles carpets,
     * slabs and stairs) — nothing hand-rolled goes to the native physics.
     * Should it refuse a panel this thin, the panel is thickened one voxel
     * at a time INTO the cell (a ship then rests a voxel or two further off
     * the wall, which is invisible) until it accepts; a layout it never
     * accepts keeps VS's default shape, logged once.
     */
    @org.jetbrains.annotations.Nullable
    private static SolidState buildPanelState(RegisterBlockStateEvent event, VSGameConfig.Server defaults, List<AABBi> panels) {
        for (int grow = 0; grow <= 3; grow++) {
            List<org.joml.primitives.AABBic> boxes = new java.util.ArrayList<>(panels.size());
            for (AABBi panel : panels) {
                boxes.add(thickened(panel, grow));
            }
            try {
                org.valkyrienskies.core.api.physics.blockstates.BoxesBlockShape shape =
                    org.valkyrienskies.mod.common.VSGameUtilsKt.getVsCore().getSolidShapeUtils().generateShapeFromBoxes(boxes);
                if (shape == null) {
                    continue;
                }
                SolidState solid = event.buildSolidState(builder -> {
                    builder.shape(shape);
                    builder.friction(defaults.getDefaultBlockFriction());
                    builder.elasticity(defaults.getDefaultBlockElasticity());
                    builder.hardness(defaults.getDefaultBlockHardness());
                    return kotlin.Unit.INSTANCE;
                });
                if (grow > 0) {
                    LOGGER.info("Gravity Unbound: plating panels {} registered thickened by {} voxel(s) for VS's shape generator", panels, grow);
                }
                return solid;
            }
            catch (RuntimeException e) {
                if (grow == 3) {
                    LOGGER.warn("Gravity Unbound could not build a VS collision shape for plating panels {}: {}", panels, e.toString());
                }
            }
        }
        return null;
    }

    /**
     * A panel grown {@code grow} voxels along its thin axis, away from the
     * face it sits on (a floor panel grows upward, a wall panel inward).
     */
    private static AABBi thickened(AABBi panel, int grow) {
        if (grow == 0) {
            return new AABBi(panel);
        }
        int sx = panel.maxX - panel.minX, sy = panel.maxY - panel.minY, sz = panel.maxZ - panel.minZ;
        AABBi out = new AABBi(panel);
        if (sy <= sx && sy <= sz) {
            if (panel.minY == 0) out.maxY = Math.min(15, out.maxY + grow); else out.minY = Math.max(0, out.minY - grow);
        }
        else if (sx <= sz) {
            if (panel.minX == 0) out.maxX = Math.min(15, out.maxX + grow); else out.minX = Math.max(0, out.minX - grow);
        }
        else {
            if (panel.minZ == 0) out.maxZ = Math.min(15, out.maxZ + grow); else out.minZ = Math.max(0, out.minZ - grow);
        }
        return out;
    }

    /** Index of the first voxel a box edge at {@code blocks} covers (0..15). */
    private static int firstVoxel(double blocks) {
        return Math.max(0, Math.min(15, (int) Math.round(blocks * 16.0)));
    }

    /** Index of the last voxel a box edge at {@code blocks} covers (0..15, inclusive). */
    private static int lastVoxel(double blocks) {
        return Math.max(-1, Math.min(15, (int) Math.round(blocks * 16.0) - 1));
    }

    private ShipBlockShapes() {}
}

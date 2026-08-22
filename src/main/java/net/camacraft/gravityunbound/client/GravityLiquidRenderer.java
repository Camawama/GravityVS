package net.camacraft.gravityunbound.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.camacraft.gravityunbound.sticky.Rotation24;
import net.camacraft.gravityunbound.util.GravityFieldLookup;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Rotated port of vanilla {@code LiquidBlockRenderer.tesselate} (Forge
 * 1.20.1-47.4.16 patched source): renders a fluid cell exactly as vanilla
 * would if gravity pointed along {@code down} instead of {@link Direction#DOWN}.
 *
 * BASIS: the deterministic cardinal frame is {@link Rotation24} orientation
 * {@code (bottom = down, spin = 0)}. The vanilla body is kept verbatim in
 * BLOCK-LOCAL coordinates (local +Y = "up" in the fluid's own gravity) and
 * only the boundary crossings are generalized:
 *
 * <ul>
 *   <li>neighbor queries / face directions go through
 *       {@code Rotation24.localToWorld(local, down, 0)};</li>
 *   <li>vertex positions are rotated about the block center by the same
 *       orientation — done exactly (no float error) by expressing the
 *       quaternion through its column vectors, i.e. the grid images of the
 *       local EAST/UP/SOUTH axes, which is what {@code localToWorld} returns
 *       (see {@link #vertex});</li>
 *   <li>the flow vector from {@code FluidState.getFlow} (grid space — the
 *       FlowingFluid mixin already makes it gravity-aware) is inverse-rotated
 *       into local space before vanilla's atan2 flow-texture rotation;</li>
 *   <li>occlusion tests rotate the fluid's partial-height box into grid space
 *       ({@link Rotation24#rotateShape}) so it meets the neighbor's grid-space
 *       occlusion shape in the correct arrangement;</li>
 *   <li>light-texture sampling uses the mapped grid positions/directions,
 *       but the directional shade multiplier stays LOCAL — each face gets
 *       the shade vanilla gives its role in the fluid's own frame (frame-up
 *       is as bright as a vanilla top surface), not the shade of the grid
 *       direction it happens to point along after rotation.</li>
 * </ul>
 *
 * The rotation is orientation-preserving (a pure quarter-turn rotation), so
 * vanilla's per-face vertex winding is kept unchanged. Forge extension points
 * ({@code ForgeHooksClient.getFluidSprites}, {@code IClientFluidTypeExtensions}
 * tint, {@code shouldDisplayFluidOverlay} overlay sprites) are preserved.
 *
 * CROSS-FRAME ISOLATION (asymmetric): neighbor fluid living in a different
 * gravity frame (per {@link GravityFieldLookup#fluidDownAt}) renders its
 * surface on different axes, so the two frames' geometry never stitches
 * face-to-face. Fluid-derived decisions split into two classes:
 *
 * <ul>
 *   <li>CULLING (same-fluid face culls via {@code isNeighborSameFluid} /
 *       {@code shouldRenderFace}, and the backward-up-face ring): cross-frame
 *       fluid counts as EMPTY — it never actually covers a face it would
 *       cull, so culling must ignore it or boundaries open see-through
 *       holes;</li>
 *   <li>HEIGHT SHAPING (the corner-height sampling, including diagonal
 *       samples, and the "fluid above me" full-column check): cross-frame
 *       same-type fluid counts as a FULL COLUMN (height 1.0) only where
 *       water genuinely crosses the seam, which happens in exactly two
 *       forms. INBOUND POUR: a source/FALLING neighbor whose own
 *       frame-down carries its water strictly toward the queried cell (a
 *       stream falling across the boundary into a rotated cell, or
 *       sideways out of a field into vanilla space) — the receiving
 *       surface ramps up to meet it ({@link #poursToward}). THROUGH-FALL:
 *       same-type cross-frame water at the cell's own frame-up whose down
 *       axis is PERPENDICULAR to the cell's — its spread plane contains
 *       the cell's down, so its water feeds through the seam (water
 *       dropping out the back of a waterlogged plate into vanilla
 *       gravity) and the cell renders full to meet it; an axis-equal
 *       opposite frame (the mutual pit) stays a cliff. Every other cross-frame
 *       neighbor (the side-by-side sector sheets of a wrapped cube, thin
 *       edge cells whose down merely happens to point at the next face)
 *       shapes as EMPTY, giving each frame its own closed vanilla cliff
 *       edge instead of bulging to full height (the round-30 fix,
 *       preserved).</li>
 * </ul>
 *
 * Both sides of a pour boundary then render complete, visually connected
 * surfaces (slight overdraw; the 0.001 face insets prevent z-fighting).
 * Solid-BLOCK occlusion is frame-independent and deliberately unaffected.
 * When every consulted cell shares the cell's frame, output is identical to
 * the plain port — which, on the identity basis (down == DOWN), is
 * identical to vanilla.
 *
 * Thread-safety: called from chunk-building worker threads — no mutable
 * static state; the direction table is written once in the class initializer.
 */
public final class GravityLiquidRenderer {

    private static final float MAX_FLUID_HEIGHT = 0.8888889F;
    private static final float EPS = 0.001F;

    /** Mask for cross-frame neighbor fluid (see {@link #effectiveFluid}). */
    private static final FluidState EMPTY_FLUID = Fluids.EMPTY.defaultFluidState();

    /** Grid image of each local direction, per down: [down ordinal][local ordinal]. */
    private static final Direction[][] LOCAL_TO_GRID = new Direction[6][6];

    static {
        for (Direction down : Direction.values()) {
            for (Direction local : Direction.values()) {
                LOCAL_TO_GRID[down.ordinal()][local.ordinal()] =
                    Rotation24.localToWorld(local, down, 0);
            }
        }
    }

    public static void tesselate(
        BlockAndTintGetter level, BlockPos pos, VertexConsumer consumer,
        BlockState blockState, FluidState fluidState, Direction down
    ) {
        Direction[] map = LOCAL_TO_GRID[down.ordinal()];
        Direction gDown = map[Direction.DOWN.ordinal()];   // == down
        Direction gUp = map[Direction.UP.ordinal()];
        Direction gNorth = map[Direction.NORTH.ordinal()];
        Direction gSouth = map[Direction.SOUTH.ordinal()];
        Direction gWest = map[Direction.WEST.ordinal()];
        Direction gEast = map[Direction.EAST.ordinal()];

        TextureAtlasSprite[] sprites =
            net.minecraftforge.client.ForgeHooksClient.getFluidSprites(level, pos, fluidState);
        int tint = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
            .of(fluidState).getTintColor(fluidState, level, pos);
        float alpha = (float) (tint >> 24 & 255) / 255.0F;
        float red = (float) (tint >> 16 & 255) / 255.0F;
        float green = (float) (tint >> 8 & 255) / 255.0F;
        float blue = (float) (tint & 255) / 255.0F;

        BlockPos downPos = pos.relative(gDown);
        BlockState downState = level.getBlockState(downPos);
        FluidState downFluid = downState.getFluidState();
        BlockPos upPos = pos.relative(gUp);
        BlockState upState = level.getBlockState(upPos);
        FluidState upFluid = upState.getFluidState();
        BlockPos northPos = pos.relative(gNorth);
        BlockState northState = level.getBlockState(northPos);
        FluidState northFluid = northState.getFluidState();
        BlockPos southPos = pos.relative(gSouth);
        BlockState southState = level.getBlockState(southPos);
        FluidState southFluid = southState.getFluidState();
        BlockPos westPos = pos.relative(gWest);
        BlockState westState = level.getBlockState(westPos);
        FluidState westFluid = westState.getFluidState();
        BlockPos eastPos = pos.relative(gEast);
        BlockState eastState = level.getBlockState(eastPos);
        FluidState eastFluid = eastState.getFluidState();

        // Frame checks for the 6 face neighbors: one fluidDownAt query each
        // (skipped when the neighbor holds no fluid — an empty cell behaves
        // identically in either frame), cached for the whole call. CULLING
        // sees cross-frame fluid as EMPTY (the ...Eff states); HEIGHT
        // SHAPING sees it as FULL only where it actually POURS toward this
        // cell, EMPTY otherwise — see getHeight and the class doc.
        boolean downSameFrame = downFluid.isEmpty() || sameFrame(level, down, downPos);
        boolean upSameFrame = upFluid.isEmpty() || sameFrame(level, down, upPos);
        boolean northSameFrame = northFluid.isEmpty() || sameFrame(level, down, northPos);
        boolean southSameFrame = southFluid.isEmpty() || sameFrame(level, down, southPos);
        boolean westSameFrame = westFluid.isEmpty() || sameFrame(level, down, westPos);
        boolean eastSameFrame = eastFluid.isEmpty() || sameFrame(level, down, eastPos);
        FluidState downFluidEff = downSameFrame ? downFluid : EMPTY_FLUID;
        FluidState upFluidEff = upSameFrame ? upFluid : EMPTY_FLUID;
        FluidState northFluidEff = northSameFrame ? northFluid : EMPTY_FLUID;
        FluidState southFluidEff = southSameFrame ? southFluid : EMPTY_FLUID;
        FluidState westFluidEff = westSameFrame ? westFluid : EMPTY_FLUID;
        FluidState eastFluidEff = eastSameFrame ? eastFluid : EMPTY_FLUID;

        boolean renderUp = !isNeighborSameFluid(fluidState, upFluidEff);
        boolean renderDown =
            shouldRenderFace(level, pos, fluidState, blockState, Direction.DOWN, downFluidEff, down)
            && !isFaceOccludedByNeighbor(level, pos, Direction.DOWN, MAX_FLUID_HEIGHT, downState, down);
        boolean renderNorth =
            shouldRenderFace(level, pos, fluidState, blockState, Direction.NORTH, northFluidEff, down);
        boolean renderSouth =
            shouldRenderFace(level, pos, fluidState, blockState, Direction.SOUTH, southFluidEff, down);
        boolean renderWest =
            shouldRenderFace(level, pos, fluidState, blockState, Direction.WEST, westFluidEff, down);
        boolean renderEast =
            shouldRenderFace(level, pos, fluidState, blockState, Direction.EAST, eastFluidEff, down);

        if (!(renderUp || renderDown || renderEast || renderWest || renderNorth || renderSouth)) {
            return;
        }

        // Shade is keyed to the face's LOCAL role (its direction in the
        // fluid's own frame), not the rotated grid direction: the frame-up
        // surface must get exactly vanilla's up-face brightness (1.0), the
        // frame-north face vanilla's 0.8, etc. Sampling the rotated direction
        // made sideways fluid render visibly darker than vanilla.
        float shadeDown = level.getShade(Direction.DOWN, true);
        float shadeUp = level.getShade(Direction.UP, true);
        Fluid fluid = fluidState.getType();
        // getHeight specialized to the cell itself (the cell's fluid always
        // matches its own type). Full column when same-type water sits at
        // the frame-up cell and can actually FEED this cell: same frame
        // (the vanilla column), or — THROUGH-FALL — a cross-frame neighbor
        // on a PERPENDICULAR down axis, whose spread plane contains our
        // down, so its water passes through the seam into this cell (the
        // stream dropping out the back of a waterlogged plate into vanilla
        // gravity; that cell is laterally fed, NOT falling, so no FALLING
        // test can catch it). Only an axis-equal opposite frame at the
        // frame-up (the mutual-pit seam, where neither feeds the other)
        // keeps the cliff edge.
        boolean upColumn = fluid.isSame(upFluidEff.getType())
            || (fluid.isSame(upFluid.getType())
                && GravityFieldLookup.fluidDownAt(level, upPos).getAxis() != down.getAxis());
        float ownHeight = upColumn ? 1.0F : fluidState.getOwnHeight();
        float hNE;
        float hNW;
        float hSE;
        float hSW;
        if (ownHeight >= 1.0F) {
            hNE = 1.0F;
            hNW = 1.0F;
            hSE = 1.0F;
            hSW = 1.0F;
        } else {
            float hN = getHeight(level, fluid, northPos, northState, northFluid, down, northSameFrame, pos);
            float hS = getHeight(level, fluid, southPos, southState, southFluid, down, southSameFrame, pos);
            float hE = getHeight(level, fluid, eastPos, eastState, eastFluid, down, eastSameFrame, pos);
            float hW = getHeight(level, fluid, westPos, westState, westFluid, down, westSameFrame, pos);
            hNE = calculateAverageHeight(level, fluid, ownHeight, hN, hE,
                northPos.relative(gEast), down, pos);
            hNW = calculateAverageHeight(level, fluid, ownHeight, hN, hW,
                northPos.relative(gWest), down, pos);
            hSE = calculateAverageHeight(level, fluid, ownHeight, hS, hE,
                southPos.relative(gEast), down, pos);
            hSW = calculateAverageHeight(level, fluid, ownHeight, hS, hW,
                southPos.relative(gWest), down, pos);
        }

        double ox = (double) (pos.getX() & 15);
        double oy = (double) (pos.getY() & 15);
        double oz = (double) (pos.getZ() & 15);
        float bottomEps = renderDown ? EPS : 0.0F;

        if (renderUp && !isFaceOccludedByNeighbor(level, pos, Direction.UP,
                Math.min(Math.min(hNW, hSW), Math.min(hSE, hNE)), upState, down)) {
            hNW -= EPS;
            hSW -= EPS;
            hSE -= EPS;
            hNE -= EPS;
            Vec3 flow = fluidState.getFlow(level, pos);
            // grid -> local (inverse rotation = dot with the basis columns)
            double flowX = flow.x * gEast.getStepX() + flow.y * gEast.getStepY() + flow.z * gEast.getStepZ();
            double flowZ = flow.x * gSouth.getStepX() + flow.y * gSouth.getStepY() + flow.z * gSouth.getStepZ();
            float uNW;
            float uSW;
            float uSE;
            float uNE;
            float vNW;
            float vSW;
            float vSE;
            float vNE;
            if (flowX == 0.0D && flowZ == 0.0D) {
                TextureAtlasSprite still = sprites[0];
                uNW = still.getU(0.0D);
                vNW = still.getV(0.0D);
                uSW = uNW;
                vSW = still.getV(16.0D);
                uSE = still.getU(16.0D);
                vSE = vSW;
                uNE = uSE;
                vNE = vNW;
            } else {
                TextureAtlasSprite flowing = sprites[1];
                float angle = (float) Mth.atan2(flowZ, flowX) - ((float) Math.PI / 2F);
                float sin = Mth.sin(angle) * 0.25F;
                float cos = Mth.cos(angle) * 0.25F;
                uNW = flowing.getU((double) (8.0F + (-cos - sin) * 16.0F));
                vNW = flowing.getV((double) (8.0F + (-cos + sin) * 16.0F));
                uSW = flowing.getU((double) (8.0F + (-cos + sin) * 16.0F));
                vSW = flowing.getV((double) (8.0F + (cos + sin) * 16.0F));
                uSE = flowing.getU((double) (8.0F + (cos + sin) * 16.0F));
                vSE = flowing.getV((double) (8.0F + (cos - sin) * 16.0F));
                uNE = flowing.getU((double) (8.0F + (cos - sin) * 16.0F));
                vNE = flowing.getV((double) (8.0F + (-cos - sin) * 16.0F));
            }

            float uCenter = (uNW + uSW + uSE + uNE) / 4.0F;
            float vCenter = (vNW + vSW + vSE + vNE) / 4.0F;
            float shrink = sprites[0].uvShrinkRatio();
            uNW = Mth.lerp(shrink, uNW, uCenter);
            uSW = Mth.lerp(shrink, uSW, uCenter);
            uSE = Mth.lerp(shrink, uSE, uCenter);
            uNE = Mth.lerp(shrink, uNE, uCenter);
            vNW = Mth.lerp(shrink, vNW, vCenter);
            vSW = Mth.lerp(shrink, vSW, vCenter);
            vSE = Mth.lerp(shrink, vSE, vCenter);
            vNE = Mth.lerp(shrink, vNE, vCenter);
            int light = getLightColor(level, pos, gUp);
            float r = shadeUp * red;
            float g = shadeUp * green;
            float b = shadeUp * blue;
            vertex(consumer, ox, oy, oz, 0.0D, (double) hNW, 0.0D, r, g, b, alpha, uNW, vNW, light, gEast, gUp, gSouth);
            vertex(consumer, ox, oy, oz, 0.0D, (double) hSW, 1.0D, r, g, b, alpha, uSW, vSW, light, gEast, gUp, gSouth);
            vertex(consumer, ox, oy, oz, 1.0D, (double) hSE, 1.0D, r, g, b, alpha, uSE, vSE, light, gEast, gUp, gSouth);
            vertex(consumer, ox, oy, oz, 1.0D, (double) hNE, 0.0D, r, g, b, alpha, uNE, vNE, light, gEast, gUp, gSouth);
            if (shouldRenderBackwardUpFace(level, fluidState, upPos, down)) {
                vertex(consumer, ox, oy, oz, 0.0D, (double) hNW, 0.0D, r, g, b, alpha, uNW, vNW, light, gEast, gUp, gSouth);
                vertex(consumer, ox, oy, oz, 1.0D, (double) hNE, 0.0D, r, g, b, alpha, uNE, vNE, light, gEast, gUp, gSouth);
                vertex(consumer, ox, oy, oz, 1.0D, (double) hSE, 1.0D, r, g, b, alpha, uSE, vSE, light, gEast, gUp, gSouth);
                vertex(consumer, ox, oy, oz, 0.0D, (double) hSW, 1.0D, r, g, b, alpha, uSW, vSW, light, gEast, gUp, gSouth);
            }
        }

        if (renderDown) {
            float u0 = sprites[0].getU0();
            float u1 = sprites[0].getU1();
            float v0 = sprites[0].getV0();
            float v1 = sprites[0].getV1();
            int light = getLightColor(level, pos.relative(gDown), gUp);
            float r = shadeDown * red;
            float g = shadeDown * green;
            float b = shadeDown * blue;
            vertex(consumer, ox, oy, oz, 0.0D, (double) bottomEps, 1.0D, r, g, b, alpha, u0, v1, light, gEast, gUp, gSouth);
            vertex(consumer, ox, oy, oz, 0.0D, (double) bottomEps, 0.0D, r, g, b, alpha, u0, v0, light, gEast, gUp, gSouth);
            vertex(consumer, ox, oy, oz, 1.0D, (double) bottomEps, 0.0D, r, g, b, alpha, u1, v0, light, gEast, gUp, gSouth);
            vertex(consumer, ox, oy, oz, 1.0D, (double) bottomEps, 1.0D, r, g, b, alpha, u1, v1, light, gEast, gUp, gSouth);
        }

        int sideLight = getLightColor(level, pos, gUp);
        TextureAtlasSprite waterOverlay = ModelBakery.WATER_OVERLAY.sprite();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            float h0;
            float h1;
            double x0;
            double z0;
            double x1;
            double z1;
            boolean shouldRender;
            switch (direction) {
                case NORTH:
                    h0 = hNW;
                    h1 = hNE;
                    x0 = 0.0D;
                    x1 = 1.0D;
                    z0 = (double) EPS;
                    z1 = (double) EPS;
                    shouldRender = renderNorth;
                    break;
                case SOUTH:
                    h0 = hSE;
                    h1 = hSW;
                    x0 = 1.0D;
                    x1 = 0.0D;
                    z0 = 1.0D - (double) EPS;
                    z1 = 1.0D - (double) EPS;
                    shouldRender = renderSouth;
                    break;
                case WEST:
                    h0 = hSW;
                    h1 = hNW;
                    x0 = (double) EPS;
                    x1 = (double) EPS;
                    z0 = 1.0D;
                    z1 = 0.0D;
                    shouldRender = renderWest;
                    break;
                default:
                    h0 = hNE;
                    h1 = hSE;
                    x0 = 1.0D - (double) EPS;
                    x1 = 1.0D - (double) EPS;
                    z0 = 0.0D;
                    z1 = 1.0D;
                    shouldRender = renderEast;
            }

            Direction gridDir = map[direction.ordinal()];
            if (shouldRender && !isFaceOccludedByNeighbor(level, pos, direction, Math.max(h0, h1),
                    level.getBlockState(pos.relative(gridDir)), down)) {
                BlockPos neighborPos = pos.relative(gridDir);
                TextureAtlasSprite side = sprites[1];
                if (sprites[2] != null) {
                    if (level.getBlockState(neighborPos).shouldDisplayFluidOverlay(level, neighborPos, fluidState)) {
                        side = sprites[2];
                    }
                }

                float sideU0 = side.getU(0.0D);
                float sideU1 = side.getU(8.0D);
                float sideV0 = side.getV((double) ((1.0F - h0) * 16.0F * 0.5F));
                float sideV1 = side.getV((double) ((1.0F - h1) * 16.0F * 0.5F));
                float sideVBottom = side.getV(8.0D);
                // LOCAL direction (see shadeDown/shadeUp above): vanilla picks
                // 0.8 for Z-axis faces, 0.6 for X-axis faces, by the face's
                // role in the fluid's frame — not the rotated grid direction.
                float sideShade = level.getShade(direction, true);
                float r = shadeUp * sideShade * red;
                float g = shadeUp * sideShade * green;
                float b = shadeUp * sideShade * blue;
                vertex(consumer, ox, oy, oz, x0, (double) h0, z0, r, g, b, alpha, sideU0, sideV0, sideLight, gEast, gUp, gSouth);
                vertex(consumer, ox, oy, oz, x1, (double) h1, z1, r, g, b, alpha, sideU1, sideV1, sideLight, gEast, gUp, gSouth);
                vertex(consumer, ox, oy, oz, x1, (double) bottomEps, z1, r, g, b, alpha, sideU1, sideVBottom, sideLight, gEast, gUp, gSouth);
                vertex(consumer, ox, oy, oz, x0, (double) bottomEps, z0, r, g, b, alpha, sideU0, sideVBottom, sideLight, gEast, gUp, gSouth);
                if (side != waterOverlay) {
                    vertex(consumer, ox, oy, oz, x0, (double) bottomEps, z0, r, g, b, alpha, sideU0, sideVBottom, sideLight, gEast, gUp, gSouth);
                    vertex(consumer, ox, oy, oz, x1, (double) bottomEps, z1, r, g, b, alpha, sideU1, sideVBottom, sideLight, gEast, gUp, gSouth);
                    vertex(consumer, ox, oy, oz, x1, (double) h1, z1, r, g, b, alpha, sideU1, sideV1, sideLight, gEast, gUp, gSouth);
                    vertex(consumer, ox, oy, oz, x0, (double) h0, z0, r, g, b, alpha, sideU0, sideV0, sideLight, gEast, gUp, gSouth);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // occlusion / face checks (vanilla logic, directions mapped through
    // the basis; the fluid's partial box is rotated into grid space)
    // ------------------------------------------------------------------

    private static boolean isNeighborSameFluid(FluidState state, FluidState neighbor) {
        return neighbor.getType().isSame(state.getType());
    }

    /** True when the fluid frame at {@code neighborPos} matches this cell's. */
    private static boolean sameFrame(BlockGetter level, Direction cellDown, BlockPos neighborPos) {
        return GravityFieldLookup.fluidDownAt(level, neighborPos) == cellDown;
    }

    /**
     * CULLING mask: a neighbor's fluid as this cell's cull decisions may see
     * it. Cross-frame fluid renders on different axes and never actually
     * covers a face it would cull here, so it is masked to empty — it never
     * merges and never culls our faces. Same-frame (and empty) fluid passes
     * through untouched, keeping all-same-frame output identical to the
     * unisolated port. Used per ring cell by
     * {@link #shouldRenderBackwardUpFace}; the six face neighbors get the
     * same masking inline in {@code tesselate} from the cached frame flags.
     * Height shaping deliberately does NOT use this — see the class doc.
     */
    private static FluidState effectiveFluid(
        BlockGetter level, Direction cellDown, BlockPos neighborPos, FluidState actual
    ) {
        return actual.isEmpty() || sameFrame(level, cellDown, neighborPos) ? actual : EMPTY_FLUID;
    }

    /**
     * Vanilla {@code FluidState.shouldRenderBackwardUpFace} (scan the 3x3
     * ring of cells in the plane "above"; any cell that is neither this
     * fluid nor a solid renderer exposes the surface from below) with
     * cross-frame isolation applied per ring cell: cross-frame fluid counts
     * as empty, i.e. behaves like an exposed edge. The ring offsets stay the
     * grid-plane offsets the vanilla method the port previously called here
     * used, so all-same-frame scenes are unchanged.
     */
    private static boolean shouldRenderBackwardUpFace(
        BlockAndTintGetter level, FluidState fluidState, BlockPos abovePos, Direction down
    ) {
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                BlockPos ringPos = abovePos.offset(i, 0, j);
                FluidState ringFluid = effectiveFluid(level, down, ringPos, level.getFluidState(ringPos));
                if (!ringFluid.getType().isSame(fluidState.getType())
                        && !level.getBlockState(ringPos).isSolidRender(level, ringPos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isFaceOccludedByState(
        BlockGetter level, Direction gridDir, float height,
        BlockPos gridPos, BlockState state, Direction down
    ) {
        if (state.canOcclude()) {
            VoxelShape fluidShape = Rotation24.rotateShape(
                Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, (double) height, 1.0D), down, 0);
            VoxelShape occlusion = state.getOcclusionShape(level, gridPos);
            return Shapes.blockOccudes(fluidShape, occlusion, gridDir);
        }
        return false;
    }

    private static boolean isFaceOccludedByNeighbor(
        BlockGetter level, BlockPos pos, Direction localDir, float height,
        BlockState neighborState, Direction down
    ) {
        Direction gridDir = LOCAL_TO_GRID[down.ordinal()][localDir.ordinal()];
        return isFaceOccludedByState(level, gridDir, height, pos.relative(gridDir), neighborState, down);
    }

    private static boolean isFaceOccludedBySelf(
        BlockGetter level, BlockPos pos, BlockState state, Direction localDir, Direction down
    ) {
        Direction gridDir = LOCAL_TO_GRID[down.ordinal()][localDir.ordinal()];
        return isFaceOccludedByState(level, gridDir.getOpposite(), 1.0F, pos, state, down);
    }

    private static boolean shouldRenderFace(
        BlockAndTintGetter level, BlockPos pos, FluidState fluidState,
        BlockState blockState, Direction localDir, FluidState neighborFluid, Direction down
    ) {
        return !isFaceOccludedBySelf(level, pos, blockState, localDir, down)
            && !isNeighborSameFluid(fluidState, neighborFluid);
    }

    // ------------------------------------------------------------------
    // heights (vanilla corner averaging; "above" mapped through the basis;
    // cross-frame same-type fluid shapes as FULL where it pours toward the
    // cell, EMPTY everywhere else — ramp at real crossings, cliff edge at
    // side-by-side sector boundaries)
    // ------------------------------------------------------------------

    /**
     * POUR TEST: does the cross-frame fluid at {@code neighborPos} arrive
     * at the cell being shaped as a full column? Two conditions:
     * <ul>
     *   <li>one step along the neighbor's own frame-down brings its water
     *       strictly closer to {@code cellPos} — onto the cell itself for
     *       face neighbors, onto a corner-adjacent cell for diagonal
     *       samples (the directional half of the flow engine's crossFeeds
     *       relation);</li>
     *   <li>the neighbor is genuinely COLUMN-shaped in its own frame — a
     *       source or FALLING flowing water. A stream crossing the
     *       boundary is a full-width column, so the receiving surface must
     *       ramp up to meet it. A thin rim-fed edge sheet (the wrapped
     *       cube's sector-boundary cells, whose down also happens to point
     *       at the neighboring face's rim) is NOT — counting those full
     *       was the round-30 bulge.</li>
     * </ul>
     */
    private static boolean poursToward(
        BlockGetter level, BlockPos neighborPos, FluidState neighbor, BlockPos cellPos
    ) {
        if (!neighbor.isSource() && !isFalling(neighbor)) {
            return false;
        }
        Direction neighborDown = GravityFieldLookup.fluidDownAt(level, neighborPos);
        return cellPos.distManhattan(neighborPos.relative(neighborDown))
            < cellPos.distManhattan(neighborPos);
    }

    /** FALLING flowing water (a full column in its own frame). */
    private static boolean isFalling(FluidState state) {
        return state.hasProperty(FlowingFluid.FALLING) && state.getValue(FlowingFluid.FALLING);
    }

    private static float calculateAverageHeight(
        BlockAndTintGetter level, Fluid fluid, float ownHeight,
        float heightA, float heightB, BlockPos diagonalPos, Direction down, BlockPos cellPos
    ) {
        if (!(heightB >= 1.0F) && !(heightA >= 1.0F)) {
            float[] weighted = new float[2];
            if (heightB > 0.0F || heightA > 0.0F) {
                float diagonal = getHeight(level, fluid, diagonalPos, down, cellPos);
                if (diagonal >= 1.0F) {
                    return 1.0F;
                }

                addWeightedHeight(weighted, diagonal);
            }

            addWeightedHeight(weighted, ownHeight);
            addWeightedHeight(weighted, heightB);
            addWeightedHeight(weighted, heightA);
            return weighted[0] / weighted[1];
        } else {
            return 1.0F;
        }
    }

    private static void addWeightedHeight(float[] weighted, float height) {
        if (height >= 0.8F) {
            weighted[0] += height * 10.0F;
            weighted[1] += 10.0F;
        } else if (height >= 0.0F) {
            weighted[0] += height;
            weighted[1] += 1.0F;
        }
    }

    private static float getHeight(
        BlockAndTintGetter level, Fluid fluid, BlockPos pos, Direction down, BlockPos cellPos
    ) {
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = state.getFluidState();
        // diagonal corner sample: query the frame here (outside the 6-cache)
        boolean posSameFrame = fluidState.isEmpty() || sameFrame(level, down, pos);
        return getHeight(level, fluid, pos, state, fluidState, down, posSameFrame, cellPos);
    }

    private static float getHeight(
        BlockAndTintGetter level, Fluid fluid, BlockPos pos,
        BlockState blockState, FluidState fluidState, Direction down, boolean posSameFrame,
        BlockPos cellPos
    ) {
        if (fluid.isSame(fluidState.getType())) {
            if (!posSameFrame) {
                // cross-frame fluid: its level lives on another axis. Where
                // it POURS toward this cell as a column it reads FULL, so
                // the surface ramps up to meet the incoming stream;
                // everywhere else it shapes as passable air — the
                // bulge-free cliff edge at side-by-side sector boundaries.
                return poursToward(level, pos, fluidState, cellPos) ? 1.0F : 0.0F;
            }
            // frame-aware above-check: a SAME-frame column counts full — or
            // THROUGH-FALL, a perpendicular-axis cross-frame feeder at the
            // frame-up (mirroring the ownHeight rule so same-frame
            // neighbors agree about shared corners)
            BlockPos abovePos = pos.relative(down.getOpposite());
            BlockState above = level.getBlockState(abovePos);
            FluidState aboveFluid = above.getFluidState();
            boolean fullColumn = fluid.isSame(aboveFluid.getType())
                && (aboveFluid.isEmpty() || sameFrame(level, down, abovePos)
                    || GravityFieldLookup.fluidDownAt(level, abovePos).getAxis() != down.getAxis());
            return fullColumn ? 1.0F : fluidState.getOwnHeight();
        } else {
            return !blockState.isSolid() ? 0.0F : -1.0F;
        }
    }

    // ------------------------------------------------------------------
    // emission
    // ------------------------------------------------------------------

    /**
     * Emits a vertex at LOCAL in-block position (x, y, z), rotated about the
     * block center into grid space by the Rotation24 orientation
     * {@code (down, 0)}. The rotation is applied exactly through the grid
     * images of the local EAST/UP/SOUTH axes (the quaternion's column
     * vectors), avoiding any float rounding: grid = center + dx*E + dy*U + dz*S.
     * The vertex normal is the grid image of local up, matching vanilla's
     * constant (0, 1, 0).
     */
    private static void vertex(
        VertexConsumer consumer, double ox, double oy, double oz,
        double x, double y, double z,
        float r, float g, float b, float alpha, float u, float v, int light,
        Direction gEast, Direction gUp, Direction gSouth
    ) {
        double dx = x - 0.5D;
        double dy = y - 0.5D;
        double dz = z - 0.5D;
        double gx = 0.5D + dx * gEast.getStepX() + dy * gUp.getStepX() + dz * gSouth.getStepX();
        double gy = 0.5D + dx * gEast.getStepY() + dy * gUp.getStepY() + dz * gSouth.getStepY();
        double gz = 0.5D + dx * gEast.getStepZ() + dy * gUp.getStepZ() + dz * gSouth.getStepZ();
        consumer.vertex(ox + gx, oy + gy, oz + gz)
            .color(r, g, b, alpha)
            .uv(u, v)
            .uv2(light)
            .normal((float) gUp.getStepX(), (float) gUp.getStepY(), (float) gUp.getStepZ())
            .endVertex();
    }

    /** Vanilla getLightColor: max-combines light at pos and the cell "above" (mapped). */
    private static int getLightColor(BlockAndTintGetter level, BlockPos pos, Direction gridUp) {
        int here = LevelRenderer.getLightColor(level, pos);
        int above = LevelRenderer.getLightColor(level, pos.relative(gridUp));
        int blockHere = here & 255;
        int blockAbove = above & 255;
        int skyHere = here >> 16 & 255;
        int skyAbove = above >> 16 & 255;
        return Math.max(blockHere, blockAbove) | Math.max(skyHere, skyAbove) << 16;
    }

    private GravityLiquidRenderer() {}
}

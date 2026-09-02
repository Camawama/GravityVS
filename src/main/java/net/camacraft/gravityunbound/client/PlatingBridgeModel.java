package net.camacraft.gravityunbound.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.plating.GravityPlatingBlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Wraps the gravity plating block model and appends CONNECTING PANELS across
 * bridged doorways (see {@code GravityPlatingBlockEntity.isBridgedToward}):
 * for every (plate side, tangent) bit in the block entity's
 * {@code BRIDGES} model data, a half-cell panel is drawn from this plate's
 * edge to the middle of the doorway cell, in the plate's own plane, with the
 * plate texture. The plate on the far side draws the other half, so the two
 * meet in the middle and the plating reads as one continuous surface
 * through the door. Quads live in the "unculled" list because they extend
 * outside the block's own cell.
 */
public class PlatingBridgeModel extends BakedModelWrapper<BakedModel> {
    private static final ResourceLocation MODEL_LOCATION = new ResourceLocation(GravityAPI.MODID, "block/plating_bridge");
    private static final FaceBakery FACE_BAKERY = new FaceBakery();

    // chunk meshing is multi-threaded
    private final Map<Long, List<BakedQuad>> bridgeQuadCache = new ConcurrentHashMap<>();

    public PlatingBridgeModel(BakedModel original) {
        super(original);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> base = super.getQuads(state, side, rand, data, renderType);
        if (side != null || state == null) {
            return base;
        }
        Long mask = data.get(GravityPlatingBlockEntity.BRIDGES);
        if (mask == null || mask == 0L) {
            return base;
        }
        if (renderType != null && !super.getRenderTypes(state, rand, data).contains(renderType)) {
            return base;
        }
        List<BakedQuad> bridges = bridgeQuadCache.computeIfAbsent(mask, this::buildBridgeQuads);
        List<BakedQuad> out = new ArrayList<>(base.size() + bridges.size());
        out.addAll(base);
        out.addAll(bridges);
        return out;
    }

    private List<BakedQuad> buildBridgeQuads(long mask) {
        TextureAtlasSprite sprite = originalModel.getParticleIcon();
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction plateDir : Direction.values()) {
            for (Direction tangent : Direction.values()) {
                if (tangent.getAxis() == plateDir.getAxis()) {
                    continue;
                }
                if ((mask & (1L << GravityPlatingBlockEntity.bridgeBit(plateDir, tangent))) == 0L) {
                    continue;
                }
                appendPanel(quads, sprite, plateDir, tangent);
            }
        }
        return quads;
    }

    /**
     * Half-cell panel in model units (16 per block): in the plate's plane
     * (0.5 from the attached face, like the plate model), spanning the full
     * cell along the third axis and the NEAR half of the doorway cell along
     * the tangent — 16..24 for a positive tangent, -8..0 for a negative one.
     */
    private static void appendPanel(List<BakedQuad> out, TextureAtlasSprite sprite, Direction plateDir, Direction tangent) {
        Direction.Axis sAxis = plateDir.getAxis();
        Direction.Axis tAxis = tangent.getAxis();
        float plane = plateDir.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? 0.5f : 15.5f;
        boolean positive = tangent.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float t0 = positive ? 16.0f : -8.0f;
        float t1 = t0 + 8.0f;

        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (Direction.Axis axis : Direction.Axis.values()) {
            float lo;
            float hi;
            if (axis == sAxis) {
                lo = plane;
                hi = plane;
            }
            else if (axis == tAxis) {
                lo = t0;
                hi = t1;
            }
            else {
                lo = 0.0f;
                hi = 16.0f;
            }
            from.setComponent(axis.ordinal(), lo);
            to.setComponent(axis.ordinal(), hi);
        }

        // UVs as the model loader would derive them for this box, computed on
        // the box shifted back into the 0..16 cell so the texture continues
        // seamlessly from the plate into the doorway (a positive-tangent
        // half samples texels 0..8, a negative one 8..16)
        float shift = positive ? -16.0f : 16.0f;
        Vector3f uvFrom = new Vector3f(from);
        Vector3f uvTo = new Vector3f(to);
        uvFrom.setComponent(tAxis.ordinal(), from.get(tAxis.ordinal()) + shift);
        uvTo.setComponent(tAxis.ordinal(), to.get(tAxis.ordinal()) + shift);

        for (Direction face : new Direction[] { plateDir, plateDir.getOpposite() }) {
            BlockElementFace elementFace = new BlockElementFace(
                null, -1, "", new BlockFaceUV(uvsByFace(uvFrom, uvTo, face), 0));
            out.add(FACE_BAKERY.bakeQuad(
                from, to, elementFace, sprite, face, BlockModelRotation.X0_Y0, null, false, MODEL_LOCATION));
        }
    }

    /** Vanilla {@code BlockElement.uvsByFace}. */
    private static float[] uvsByFace(Vector3f from, Vector3f to, Direction face) {
        return switch (face) {
            case DOWN -> new float[] { from.x(), 16.0f - to.z(), to.x(), 16.0f - from.z() };
            case UP -> new float[] { from.x(), from.z(), to.x(), to.z() };
            case NORTH -> new float[] { 16.0f - to.x(), 16.0f - to.y(), 16.0f - from.x(), 16.0f - from.y() };
            case SOUTH -> new float[] { from.x(), 16.0f - to.y(), to.x(), 16.0f - from.y() };
            case WEST -> new float[] { from.z(), 16.0f - to.y(), to.z(), 16.0f - from.y() };
            case EAST -> new float[] { 16.0f - to.z(), 16.0f - to.y(), 16.0f - from.z(), 16.0f - from.y() };
        };
    }
}

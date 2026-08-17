package net.camacraft.gravityunbound.sticky.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.camacraft.gravityunbound.sticky.Rotation24;
import net.camacraft.gravityunbound.sticky.StickyChestBlock;
import net.camacraft.gravityunbound.sticky.StickyChestBlockEntity;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BrightnessCombiner;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Renders the vanilla chest models (single and both double-chest halves) in
 * any of the 24 grid orientations.
 *
 * The whole vanilla model (authored facing local SOUTH, lid hinge at the
 * local north edge) is rotated about the block center by the orientation
 * quaternion from {@link Rotation24}. Vanilla's ChestRenderer applies a
 * {@code -facing.toYRot()} spin about Y here instead; for the sticky chest
 * that front-facing is baked into the SPIN blockstate property, which
 * {@link Rotation24} composes as a local-space Y rotation BEFORE the
 * bottom-face rotation — so a single {@code mulPose} is the entire transform
 * and the lid hinge always ends up at the local back. Because both halves of
 * a double chest share the same orientation, the vanilla CHEST_LEFT /
 * CHEST_RIGHT half-models line up across the seam exactly as they do for a
 * vanilla double chest, just rotated. Lid openness and brightness are
 * combined across both halves like vanilla (max of the pair).
 */
public class StickyChestRenderer implements BlockEntityRenderer<StickyChestBlockEntity> {

    private final ModelPart lid;
    private final ModelPart bottom;
    private final ModelPart lock;
    private final ModelPart doubleLeftLid;
    private final ModelPart doubleLeftBottom;
    private final ModelPart doubleLeftLock;
    private final ModelPart doubleRightLid;
    private final ModelPart doubleRightBottom;
    private final ModelPart doubleRightLock;

    public StickyChestRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart single = context.bakeLayer(ModelLayers.CHEST);
        this.bottom = single.getChild("bottom");
        this.lid = single.getChild("lid");
        this.lock = single.getChild("lock");
        ModelPart left = context.bakeLayer(ModelLayers.DOUBLE_CHEST_LEFT);
        this.doubleLeftBottom = left.getChild("bottom");
        this.doubleLeftLid = left.getChild("lid");
        this.doubleLeftLock = left.getChild("lock");
        ModelPart right = context.bakeLayer(ModelLayers.DOUBLE_CHEST_RIGHT);
        this.doubleRightBottom = right.getChild("bottom");
        this.doubleRightLid = right.getChild("lid");
        this.doubleRightLock = right.getChild("lock");
    }

    @Override
    public void render(
        StickyChestBlockEntity chest, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay
    ) {
        Direction bottomFace = Direction.DOWN;
        int spin = 0;
        ChestType type = ChestType.SINGLE;
        BlockState state = chest.getBlockState();
        StickyChestBlock block = state.getBlock() instanceof StickyChestBlock sticky ? sticky : null;
        if (block != null) {
            bottomFace = state.getValue(StickyChestBlock.BOTTOM);
            spin = state.getValue(StickyChestBlock.SPIN);
            type = state.getValue(StickyChestBlock.TYPE);
        }

        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(Rotation24.quaternion(bottomFace, spin));
        poseStack.translate(-0.5f, -0.5f, -0.5f);

        // resolve the partner half (if any) for shared lid/brightness; the
        // level is null for the item-renderer dummy -> render standalone
        Level level = chest.getLevel();
        DoubleBlockCombiner.NeighborCombineResult<StickyChestBlockEntity> combineResult =
            block != null && level != null
                ? block.combine(state, level, chest.getBlockPos(), true)
                : DoubleBlockCombiner.Combiner::acceptNone;

        // vanilla ChestRenderer lid easing, on the pair's combined openness
        float openness = combineResult.apply(StickyChestBlock.opennessCombiner(chest)).get(partialTick);
        openness = 1.0f - openness;
        openness = 1.0f - openness * openness * openness;
        float lidAngle = -(openness * (Mth.PI / 2.0f));
        int light = combineResult.apply(new BrightnessCombiner<>()).applyAsInt(packedLight);

        Material material = switch (type) {
            case LEFT -> Sheets.CHEST_LOCATION_LEFT;
            case RIGHT -> Sheets.CHEST_LOCATION_RIGHT;
            default -> Sheets.CHEST_LOCATION;
        };
        VertexConsumer buffer = material.buffer(bufferSource, RenderType::entityCutout);

        switch (type) {
            case LEFT -> renderParts(poseStack, buffer, this.doubleLeftLid, this.doubleLeftLock, this.doubleLeftBottom, lidAngle, light, packedOverlay);
            case RIGHT -> renderParts(poseStack, buffer, this.doubleRightLid, this.doubleRightLock, this.doubleRightBottom, lidAngle, light, packedOverlay);
            default -> renderParts(poseStack, buffer, this.lid, this.lock, this.bottom, lidAngle, light, packedOverlay);
        }

        poseStack.popPose();
    }

    private static void renderParts(
        PoseStack poseStack, VertexConsumer buffer,
        ModelPart lid, ModelPart lock, ModelPart bottom,
        float lidAngle, int packedLight, int packedOverlay
    ) {
        lid.xRot = lidAngle;
        lock.xRot = lidAngle;
        lid.render(poseStack, buffer, packedLight, packedOverlay);
        lock.render(poseStack, buffer, packedLight, packedOverlay);
        bottom.render(poseStack, buffer, packedLight, packedOverlay);
    }
}

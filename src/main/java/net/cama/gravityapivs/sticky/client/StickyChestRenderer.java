package net.cama.gravityapivs.sticky.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.cama.gravityapivs.sticky.Rotation24;
import net.cama.gravityapivs.sticky.StickyChestBlock;
import net.cama.gravityapivs.sticky.StickyChestBlockEntity;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders the vanilla single-chest model in any of the 24 grid orientations.
 *
 * The whole vanilla model (authored facing local SOUTH, lid hinge at the
 * local north edge) is rotated about the block center by the orientation
 * quaternion from {@link Rotation24}. Vanilla's ChestRenderer applies a
 * {@code -facing.toYRot()} spin about Y here instead; for the sticky chest
 * that front-facing is baked into the SPIN blockstate property, which
 * {@link Rotation24} composes as a local-space Y rotation BEFORE the
 * bottom-face rotation — so a single {@code mulPose} is the entire transform
 * and the lid hinge always ends up at the local back.
 */
public class StickyChestRenderer implements BlockEntityRenderer<StickyChestBlockEntity> {

    private final ModelPart lid;
    private final ModelPart bottom;
    private final ModelPart lock;

    public StickyChestRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart root = context.bakeLayer(ModelLayers.CHEST);
        this.bottom = root.getChild("bottom");
        this.lid = root.getChild("lid");
        this.lock = root.getChild("lock");
    }

    @Override
    public void render(
        StickyChestBlockEntity chest, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay
    ) {
        Direction bottomFace = Direction.DOWN;
        int spin = 0;
        BlockState state = chest.getBlockState();
        if (state.getBlock() instanceof StickyChestBlock) {
            bottomFace = state.getValue(StickyChestBlock.BOTTOM);
            spin = state.getValue(StickyChestBlock.SPIN);
        }

        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(Rotation24.quaternion(bottomFace, spin));
        poseStack.translate(-0.5f, -0.5f, -0.5f);

        // vanilla ChestRenderer lid easing
        float openness = chest.getOpenNess(partialTick);
        openness = 1.0f - openness;
        openness = 1.0f - openness * openness * openness;
        this.lid.xRot = -(openness * (Mth.PI / 2.0f));
        this.lock.xRot = this.lid.xRot;

        VertexConsumer buffer = Sheets.CHEST_LOCATION.buffer(bufferSource, RenderType::entityCutout);
        this.lid.render(poseStack, buffer, packedLight, packedOverlay);
        this.lock.render(poseStack, buffer, packedLight, packedOverlay);
        this.bottom.render(poseStack, buffer, packedLight, packedOverlay);

        poseStack.popPose();
    }
}

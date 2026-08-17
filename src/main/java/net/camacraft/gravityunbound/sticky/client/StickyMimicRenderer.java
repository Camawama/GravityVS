package net.camacraft.gravityunbound.sticky.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.camacraft.gravityunbound.sticky.Rotation24;
import net.camacraft.gravityunbound.sticky.StickyMimicBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Draws the {@code StickyMimicBlock}'s captured state rotated into its
 * {@link Rotation24} orientation: translate to the block center, apply the
 * orientation quaternion, translate back, then let the block renderer draw
 * the mimicked state's baked model as a single block. The shell block itself
 * is {@code RenderShape.INVISIBLE}, so this is the mimic's entire visual.
 */
public class StickyMimicRenderer implements BlockEntityRenderer<StickyMimicBlockEntity> {

    public StickyMimicRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
        StickyMimicBlockEntity mimic, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay
    ) {
        BlockState mimicked = mimic.getMimickedState();
        if (mimicked == null || mimicked.isAir()) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(Rotation24.quaternion(mimic.getBottom(), mimic.getSpin()));
        poseStack.translate(-0.5f, -0.5f, -0.5f);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
            mimicked, poseStack, bufferSource, packedLight, packedOverlay, ModelData.EMPTY, null
        );
        poseStack.popPose();
    }
}

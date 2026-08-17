package net.camacraft.gravityunbound.sticky.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.camacraft.gravityunbound.sticky.Rotation24;
import net.camacraft.gravityunbound.sticky.StickyRailBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Draws the {@code StickyRailBlock} as the corresponding VANILLA rail
 * blockstate (same {@code RailShape}, straight or curved) rotated into the
 * rail's {@link Rotation24} orientation — translate to the block center,
 * apply the orientation quaternion, translate back, render the baked model
 * (the sticky mimic's proven approach). The sticky rail block itself is
 * {@code RenderShape.INVISIBLE}, so this is its entire visual.
 */
public class StickyRailRenderer implements BlockEntityRenderer<StickyRailBlockEntity> {

    public StickyRailRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
        StickyRailBlockEntity rail, float partialTick, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay
    ) {
        BlockState visual = Blocks.RAIL.defaultBlockState()
            .setValue(RailBlock.SHAPE, rail.getShape());
        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(Rotation24.quaternion(rail.getBottom(), rail.getSpin()));
        poseStack.translate(-0.5f, -0.5f, -0.5f);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
            visual, poseStack, bufferSource, packedLight, packedOverlay, ModelData.EMPTY, null
        );
        poseStack.popPose();
    }
}

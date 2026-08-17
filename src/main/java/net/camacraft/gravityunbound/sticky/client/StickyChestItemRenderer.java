package net.camacraft.gravityunbound.sticky.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.sticky.StickyChestBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Item renderer for the sticky chest: like the vanilla chest item, the
 * geometry is drawn by rendering a dummy block entity through the block
 * entity render dispatcher (which routes to {@link StickyChestRenderer} at
 * the default DOWN/0 orientation — an upright closed chest).
 */
public class StickyChestItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static StickyChestItemRenderer instance;

    /** Level-free display dummy; created lazily so registries are ready. */
    private StickyChestBlockEntity dummy;

    private StickyChestItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    public static StickyChestItemRenderer getInstance() {
        if (instance == null) {
            instance = new StickyChestItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(
        ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
        MultiBufferSource bufferSource, int packedLight, int packedOverlay
    ) {
        if (dummy == null) {
            dummy = new StickyChestBlockEntity(
                BlockPos.ZERO, GravityBlocks.STICKY_CHEST.get().defaultBlockState()
            );
        }
        Minecraft.getInstance().getBlockEntityRenderDispatcher()
            .renderItem(dummy, poseStack, bufferSource, packedLight, packedOverlay);
    }
}

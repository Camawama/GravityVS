package net.camacraft.gravityunbound.sticky;

import java.util.function.Consumer;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/**
 * Block item for the sticky chest. Chest geometry lives in entity textures
 * (not the block atlas), so — exactly like the vanilla chest item — the item
 * model is {@code builtin/entity} and the geometry is drawn by a custom item
 * renderer ({@code sticky.client.StickyChestItemRenderer}).
 */
public class StickyChestItem extends BlockItem {

    public StickyChestItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // the anonymous class body only loads when this executes, which
        // Forge guarantees happens on the client dist only
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return net.camacraft.gravityunbound.sticky.client.StickyChestItemRenderer.getInstance();
            }
        });
    }
}

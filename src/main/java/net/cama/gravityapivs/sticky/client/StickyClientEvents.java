package net.cama.gravityapivs.sticky.client;

import net.cama.gravityapivs.GravityAPI;
import net.cama.gravityapivs.init.GravityBlocks;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only registrations for the sticky block framework. */
@Mod.EventBusSubscriber(modid = GravityAPI.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class StickyClientEvents {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(GravityBlocks.STICKY_CHEST_BLOCK_ENTITY.get(), StickyChestRenderer::new);
    }

    private StickyClientEvents() {}
}

package net.camacraft.gravityunbound.client;

import java.util.IdentityHashMap;
import java.util.Map;

import net.camacraft.gravityunbound.GravityAPI;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = GravityAPI.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModelEvents {
    private ClientModelEvents() {
    }

    /**
     * Wrap every block-state variant of the plating model so it can draw the
     * connecting panels across bridged doorways (one wrapper per distinct
     * baked model instance — the multipart model is shared across states).
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        IdentityHashMap<BakedModel, BakedModel> wrapped = new IdentityHashMap<>();
        for (Map.Entry<ResourceLocation, BakedModel> entry : models.entrySet()) {
            if (!(entry.getKey() instanceof ModelResourceLocation location)) {
                continue;
            }
            if (!location.getNamespace().equals(GravityAPI.MODID)
                || !location.getPath().equals("plating")
                || location.getVariant().equals("inventory")) {
                continue;
            }
            BakedModel original = entry.getValue();
            if (original instanceof PlatingBridgeModel) {
                continue;
            }
            entry.setValue(wrapped.computeIfAbsent(original, PlatingBridgeModel::new));
        }
    }
}

package net.cama.gravityapivs;

import net.cama.gravityapivs.api.RotationParameters;
import net.cama.gravityapivs.capabilities.GravityCapabilities;
import net.cama.gravityapivs.event.RegisterArgumentTypes;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.init.GravityBlocks;
import net.cama.gravityapivs.init.GravityCreativeTabs;
import net.cama.gravityapivs.init.GravityItems;
import net.cama.gravityapivs.init.GravityMobEffects;
import net.cama.gravityapivs.network.GravityNetwork;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(GravityAPI.MODID)
public class GravityAPI
{
	public static final String MODID = "gravityapivs";

	public GravityAPI()
	{
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		ModLoadingContext ctx = ModLoadingContext.get();

		GravityItems.ITEMS.register(bus);
		GravityBlocks.BLOCKS.register(bus);
		GravityBlocks.BLOCK_ENTITIES.register(bus);
		GravityMobEffects.EFFECTS.register(bus);
		GravityMobEffects.POTIONS.register(bus);
		GravityCreativeTabs.CREATIVE_MODE_TAB.register(bus);
		RegisterArgumentTypes.ARGUMENT_TYPES.register(bus);

		GravityNetwork.registerMessages();
		MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, GravityCapabilities::attachEntityCapability);
		ctx.registerConfig(Type.COMMON, GravityConfig.CONFIG_SPEC, "gravity-apivs.toml");

		// keep RotationParameters' defaults in sync with the config
		bus.addListener((ModConfigEvent.Loading e) -> RotationParameters.updateDefault());
		bus.addListener((ModConfigEvent.Reloading e) -> RotationParameters.updateDefault());
	}
}

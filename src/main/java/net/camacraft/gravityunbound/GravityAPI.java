package net.camacraft.gravityunbound;

import net.camacraft.gravityunbound.api.RotationParameters;
import net.camacraft.gravityunbound.capabilities.GravityCapabilities;
import net.camacraft.gravityunbound.event.RegisterArgumentTypes;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.init.GravityCreativeTabs;
import net.camacraft.gravityunbound.init.GravityItems;
import net.camacraft.gravityunbound.init.GravityMobEffects;
import net.camacraft.gravityunbound.network.GravityNetwork;

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
	public static final String MODID = "gravityunbound";

	public GravityAPI()
	{
		IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
		ModLoadingContext ctx = ModLoadingContext.get();

		GravityItems.ITEMS.register(bus);
		GravityBlocks.BLOCKS.register(bus);
		GravityBlocks.BLOCK_ENTITIES.register(bus);
		GravityMobEffects.EFFECTS.register(bus);
		GravityMobEffects.POTIONS.register(bus);
		net.camacraft.gravityunbound.init.GravityEnchantments.ENCHANTMENTS.register(bus);
		GravityCreativeTabs.CREATIVE_MODE_TAB.register(bus);
		RegisterArgumentTypes.ARGUMENT_TYPES.register(bus);

		GravityNetwork.registerMessages();
		MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, GravityCapabilities::attachEntityCapability);
		// SERVER type, not COMMON: these values feed gravity computation that
		// runs on BOTH sides (the local player computes its own gravity from
		// fields client-side) — COMMON configs are never synced, so any
		// client/server mismatch was a genuine physics desync. SERVER configs
		// are synced to clients on login.
		ctx.registerConfig(Type.SERVER, GravityConfig.CONFIG_SPEC, "gravity-apivs.toml");

		// keep RotationParameters' defaults in sync with the config
		bus.addListener((ModConfigEvent.Loading e) -> RotationParameters.updateDefault());
		bus.addListener((ModConfigEvent.Reloading e) -> RotationParameters.updateDefault());
	}
}

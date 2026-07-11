package net.cama.gravityapivs.event;

import net.cama.gravityapivs.GravityAPI;
import net.cama.gravityapivs.command.DirectionArgumentType;
import net.cama.gravityapivs.command.LocalDirectionArgumentType;

import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Custom Brigadier argument types must be registered in the
 * COMMAND_ARGUMENT_TYPES registry (and linked via ArgumentTypeInfos.registerByClass),
 * otherwise the server cannot serialize the command tree to clients and every
 * player is kicked on login with "Invalid player data".
 */
public class RegisterArgumentTypes
{
	public static final DeferredRegister<ArgumentTypeInfo<?, ?>> ARGUMENT_TYPES =
		DeferredRegister.create(ForgeRegistries.Keys.COMMAND_ARGUMENT_TYPES, GravityAPI.MODID);

	public static final RegistryObject<SingletonArgumentInfo<DirectionArgumentType>> DIRECTION =
		ARGUMENT_TYPES.register("direction", () ->
			ArgumentTypeInfos.registerByClass(
				DirectionArgumentType.class,
				SingletonArgumentInfo.contextFree(DirectionArgumentType::new)
			)
		);

	public static final RegistryObject<SingletonArgumentInfo<LocalDirectionArgumentType>> LOCAL_DIRECTION =
		ARGUMENT_TYPES.register("local_direction", () ->
			ArgumentTypeInfos.registerByClass(
				LocalDirectionArgumentType.class,
				SingletonArgumentInfo.contextFree(LocalDirectionArgumentType::new)
			)
		);
}

package net.camacraft.gravityunbound.init;

import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.enchantment.SurfaceClingEnchantment;

import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class GravityEnchantments {
    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
        DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, GravityAPI.MODID);

    /** Boots that cling to any block surface — see {@link SurfaceClingEnchantment}. */
    public static final RegistryObject<SurfaceClingEnchantment> SURFACE_CLING =
        ENCHANTMENTS.register("surface_cling", SurfaceClingEnchantment::new);
}

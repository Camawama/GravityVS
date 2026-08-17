package net.camacraft.gravityunbound.init;

import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.core.GravityCoreBlock;
import net.camacraft.gravityunbound.core.GravityCoreBlockEntity;
import net.camacraft.gravityunbound.normalizer.GravityNormalizerBlock;
import net.camacraft.gravityunbound.normalizer.GravityNormalizerBlockEntity;
import net.camacraft.gravityunbound.plating.GravityPlatingBlock;
import net.camacraft.gravityunbound.plating.GravityPlatingBlockEntity;
import net.camacraft.gravityunbound.sticky.StickyChestBlock;
import net.camacraft.gravityunbound.sticky.StickyChestBlockEntity;
import net.camacraft.gravityunbound.sticky.StickyMimicBlock;
import net.camacraft.gravityunbound.sticky.StickyMimicBlockEntity;
import net.camacraft.gravityunbound.sticky.StickyRailBlock;
import net.camacraft.gravityunbound.sticky.StickyRailBlockEntity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class GravityBlocks
{
	public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GravityAPI.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GravityAPI.MODID);

    public static final RegistryObject<Block> GRAVITY_PLATING = BLOCKS.register("plating", () -> new GravityPlatingBlock());
    public static final RegistryObject<Block> GRAVITY_CORE = BLOCKS.register("gravity_core", () -> new GravityCoreBlock());
    public static final RegistryObject<Block> GRAVITY_NORMALIZER = BLOCKS.register("gravity_normalizer", () -> new GravityNormalizerBlock());
    public static final RegistryObject<Block> STICKY_CHEST = BLOCKS.register("sticky_chest", () -> new StickyChestBlock());
    public static final RegistryObject<Block> STICKY_MIMIC = BLOCKS.register("sticky_mimic", () -> new StickyMimicBlock());
    public static final RegistryObject<Block> STICKY_RAIL = BLOCKS.register("sticky_rail", () -> new StickyRailBlock());

    public static final RegistryObject<BlockEntityType<GravityPlatingBlockEntity>> GRAVITY_PLATING_BLOCK_ENTITY = BLOCK_ENTITIES.register("plating", () -> BlockEntityType.Builder.of(GravityPlatingBlockEntity::new, GravityBlocks.GRAVITY_PLATING.get()).build(null));
    public static final RegistryObject<BlockEntityType<GravityCoreBlockEntity>> GRAVITY_CORE_BLOCK_ENTITY = BLOCK_ENTITIES.register("gravity_core", () -> BlockEntityType.Builder.of(GravityCoreBlockEntity::new, GravityBlocks.GRAVITY_CORE.get()).build(null));
    public static final RegistryObject<BlockEntityType<GravityNormalizerBlockEntity>> GRAVITY_NORMALIZER_BLOCK_ENTITY = BLOCK_ENTITIES.register("gravity_normalizer", () -> BlockEntityType.Builder.of(GravityNormalizerBlockEntity::new, GravityBlocks.GRAVITY_NORMALIZER.get()).build(null));
    public static final RegistryObject<BlockEntityType<StickyChestBlockEntity>> STICKY_CHEST_BLOCK_ENTITY = BLOCK_ENTITIES.register("sticky_chest", () -> BlockEntityType.Builder.of(StickyChestBlockEntity::new, GravityBlocks.STICKY_CHEST.get()).build(null));
    public static final RegistryObject<BlockEntityType<StickyMimicBlockEntity>> STICKY_MIMIC_BLOCK_ENTITY = BLOCK_ENTITIES.register("sticky_mimic", () -> BlockEntityType.Builder.of(StickyMimicBlockEntity::new, GravityBlocks.STICKY_MIMIC.get()).build(null));
    public static final RegistryObject<BlockEntityType<StickyRailBlockEntity>> STICKY_RAIL_BLOCK_ENTITY = BLOCK_ENTITIES.register("sticky_rail", () -> BlockEntityType.Builder.of(StickyRailBlockEntity::new, GravityBlocks.STICKY_RAIL.get()).build(null));
}

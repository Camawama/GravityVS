package net.cama.gravityapivs.init;

import net.cama.gravityapivs.GravityAPI;
import net.cama.gravityapivs.core.GravityCoreBlock;
import net.cama.gravityapivs.core.GravityCoreBlockEntity;
import net.cama.gravityapivs.normalizer.GravityNormalizerBlock;
import net.cama.gravityapivs.normalizer.GravityNormalizerBlockEntity;
import net.cama.gravityapivs.plating.GravityPlatingBlock;
import net.cama.gravityapivs.plating.GravityPlatingBlockEntity;
import net.cama.gravityapivs.sticky.StickyChestBlock;
import net.cama.gravityapivs.sticky.StickyChestBlockEntity;

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

    public static final RegistryObject<BlockEntityType<GravityPlatingBlockEntity>> GRAVITY_PLATING_BLOCK_ENTITY = BLOCK_ENTITIES.register("plating", () -> BlockEntityType.Builder.of(GravityPlatingBlockEntity::new, GravityBlocks.GRAVITY_PLATING.get()).build(null));
    public static final RegistryObject<BlockEntityType<GravityCoreBlockEntity>> GRAVITY_CORE_BLOCK_ENTITY = BLOCK_ENTITIES.register("gravity_core", () -> BlockEntityType.Builder.of(GravityCoreBlockEntity::new, GravityBlocks.GRAVITY_CORE.get()).build(null));
    public static final RegistryObject<BlockEntityType<GravityNormalizerBlockEntity>> GRAVITY_NORMALIZER_BLOCK_ENTITY = BLOCK_ENTITIES.register("gravity_normalizer", () -> BlockEntityType.Builder.of(GravityNormalizerBlockEntity::new, GravityBlocks.GRAVITY_NORMALIZER.get()).build(null));
    public static final RegistryObject<BlockEntityType<StickyChestBlockEntity>> STICKY_CHEST_BLOCK_ENTITY = BLOCK_ENTITIES.register("sticky_chest", () -> BlockEntityType.Builder.of(StickyChestBlockEntity::new, GravityBlocks.STICKY_CHEST.get()).build(null));
}

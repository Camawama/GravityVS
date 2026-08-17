package net.camacraft.gravityunbound.core;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Generates a spherical gravity field around itself: entities (and, on the
 * server, VS ships) inside the field are pulled toward — or pushed away from —
 * the core. Hide one in the middle of a stone sphere and you have a small planet.
 */
public class GravityCoreBlock extends BaseEntityBlock {

    public GravityCoreBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_PURPLE)
            .strength(3.0f, 12.0f)
            .sound(SoundType.AMETHYST)
        );
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GravityCoreBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        // ticks on both sides: the server is authoritative for non-player entities
        // and ships, the client computes the locally controlled player's gravity
        return createTickerHelper(type, GravityBlocks.GRAVITY_CORE_BLOCK_ENTITY.get(), GravityCoreBlockEntity::tick);
    }


    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // breaking the field source: drop it from the fluid-gravity registry
        // IMMEDIATELY (the tick-expiry is too slow — re-settling fluids must
        // see the field gone) and wake all fluids it was holding so they
        // flow back under normal gravity instead of freezing in place
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            int radius = level.getBlockEntity(pos)
                instanceof net.camacraft.gravityunbound.util.GravityFieldLookup.Source source
                ? source.sourceMaxRange() : 8;
            net.camacraft.gravityunbound.util.GravityFieldLookup.unregister(level, pos);
            net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluidsAround(level, pos, radius);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
        // configuration is GUI-only and creative-only (like command blocks):
        // an empty-handed creative player opens the settings screen; anything
        // else passes through to normal behavior. The settings change travels
        // back via UpdateGravityBlockSettingsPacket. The opener class is
        // client-only and is never touched on the server —
        // level.isClientSide() is always false on a dedicated server.
        if (!player.getItemInHand(hand).isEmpty() || !player.isCreative()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide() && net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            net.camacraft.gravityunbound.client.gui.GravityBlockSettingsScreenOpener.openCore(pos);
        }
        return InteractionResult.SUCCESS;
    }
}

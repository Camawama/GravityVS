package net.cama.gravityapivs.core;

import org.jetbrains.annotations.Nullable;

import net.cama.gravityapivs.init.GravityBlocks;

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
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
        // only empty hand, amethyst cluster and glow ink sac interact with the
        // core; anything else passes through to normal item behavior, no message
        net.minecraft.world.item.ItemStack handItem = player.getItemInHand(hand);
        if (!handItem.isEmpty()
            && !handItem.is(net.minecraft.world.item.Items.AMETHYST_CLUSTER)
            && !handItem.is(net.minecraft.world.item.Items.GLOW_INK_SAC)
        ) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof GravityCoreBlockEntity be)) {
            return InteractionResult.FAIL;
        }

        return be.interact(player, hand);
    }
}

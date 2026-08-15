package net.cama.gravityapivs.normalizer;

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
 * Gravity Normalizer — defines what "down" means inside a zone of a Valkyrien
 * Skies ship (or a static structure). The chosen direction is GRID-local:
 * as the ship rotates or maneuvers, gravity inside the zone rotates with it,
 * so crews experience completely natural ship-relative gravity regardless of
 * the ship's orientation in the world.
 *
 * Interactions: empty hand cycles the local "down" direction; sneak + empty
 * hand shrinks the zone (amethyst refunded); amethyst cluster grows the zone;
 * glow ink sac toggles the field visualization.
 */
public class GravityNormalizerBlock extends BaseEntityBlock {

    public GravityNormalizerBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_CYAN)
            .strength(3.0f, 12.0f)
            .sound(SoundType.AMETHYST)
        );
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GravityNormalizerBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
        // ticks on both sides: the server is authoritative for non-player
        // entities, the client computes the locally controlled player's gravity
        return createTickerHelper(type, GravityBlocks.GRAVITY_NORMALIZER_BLOCK_ENTITY.get(), GravityNormalizerBlockEntity::tick);
    }

    @Override
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
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
        if (!(blockEntity instanceof GravityNormalizerBlockEntity be)) {
            return InteractionResult.FAIL;
        }

        return be.interact(player, hand);
    }
}

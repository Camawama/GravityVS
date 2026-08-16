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
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // breaking the field source: drop it from the fluid-gravity registry
        // IMMEDIATELY (the tick-expiry is too slow — re-settling fluids must
        // see the field gone) and wake all fluids it was holding so they
        // flow back under normal gravity instead of freezing in place
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            int radius = level.getBlockEntity(pos)
                instanceof net.cama.gravityapivs.util.GravityFieldLookup.Source source
                ? source.sourceMaxRange() : 8;
            net.cama.gravityapivs.util.GravityFieldLookup.unregister(level, pos);
            net.cama.gravityapivs.util.GravityFieldLookup.resettleFluidsAround(level, pos, radius);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
        net.minecraft.world.item.ItemStack handItem = player.getItemInHand(hand);

        // empty hand opens the settings GUI (client side only); the settings
        // change travels back via UpdateGravityBlockSettingsPacket. The
        // opener class is client-only and is never touched on the server —
        // level.isClientSide() is always false on a dedicated server.
        if (handItem.isEmpty()) {
            if (level.isClientSide() && net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                net.cama.gravityapivs.client.gui.GravityBlockSettingsScreenOpener.openNormalizer(pos);
            }
            return InteractionResult.SUCCESS;
        }

        if (!handItem.is(net.minecraft.world.item.Items.AMETHYST_CLUSTER)
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

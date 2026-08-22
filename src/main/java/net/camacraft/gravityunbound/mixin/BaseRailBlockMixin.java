package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.sticky.StickyRailBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * VANILLA-RAIL ISOLATION for rotated sticky rails. A non-DOWN
 * {@link StickyRailBlock} is a {@code BaseRailBlock} in the
 * {@code minecraft:rails} tag (so minecart placement works), which made
 * vanilla's {@code isRail} — the identity check behind ALL of vanilla's
 * world-frame rail logic — treat a WALL rail as an ordinary flat rail:
 * <ul>
 *   <li>vanilla {@code RailState} (from any adjacent vanilla/DOWN rail)
 *       "connected" to it, curving real rails toward a rail on a wall and
 *       writing world-frame shapes into its local-frame SHAPE property
 *       (the self-heal guard reverts the write, but the neighbors keep
 *       their spurious curves — the S-bends and mis-curves);</li>
 *   <li>vanilla {@code AbstractMinecart} track detection read it as a flat
 *       floor rail whenever the sticky ride path was not engaged and ran
 *       world-frame track math on it — the cart clamped onto a
 *       nonsensical world-frame chord, misaligned and sunk into the
 *       ground.</li>
 * </ul>
 * Vanilla rail logic is WORLD-frame by construction and can never handle a
 * rotated rail; returning false here makes every vanilla consumer see
 * plain "not a rail", while the sticky-rail systems (which check the block
 * and BOTTOM themselves, never {@code isRail}) are unaffected. DOWN sticky
 * rails keep returning true — they genuinely are vanilla rails.
 * (MinecartItem placement checks the block TAG, not isRail, so placing
 * carts on rotated rails still works.)
 */
@Mixin(BaseRailBlock.class)
public abstract class BaseRailBlockMixin {

    @Inject(
        method = "isRail(Lnet/minecraft/world/level/block/state/BlockState;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void gravityunbound$hideRotatedRails(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof StickyRailBlock
            && state.getValue(StickyRailBlock.BOTTOM) != Direction.DOWN) {
            cir.setReturnValue(false);
        }
    }
}

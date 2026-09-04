package net.camacraft.gravityunbound.mixin.compat;

import net.camacraft.gravityunbound.util.GravityFieldLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bawnorton.mixinsquared.TargetHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Ad Astra freezes every liquid in its zero-gravity dimensions: its
 * {@code FlowingFluidMixin} cancels {@code FlowingFluid.spread} at the head
 * whenever the planet gravity at the block is below 0.05, so a liquid
 * inside a gravity FIELD in orbit sat in its single block instead of
 * flowing along the field's down. A gravity field owns gravity where it
 * reaches (the same rule the entity compat layer applies): this targets
 * Ad Astra's handler itself (MixinSquared) and stops it from cancelling the
 * spread for blocks a field covers. Outside fields Ad Astra's space stays
 * frozen exactly as before. Only applied when Ad Astra is present.
 */
@Mixin(value = FlowingFluid.class, priority = 1500)
public abstract class AdAstraFluidMixin {
    @TargetHandler(
        mixin = "earth.terrarium.adastra.mixins.common.environment.FlowingFluidMixin",
        name = "adastra$spread"
    )
    @Inject(method = "@MixinSquared:Handler", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$keepFieldLiquidsFlowing(
        Level level, BlockPos pos, FluidState state, CallbackInfo adAstraCallback, CallbackInfo ci
    ) {
        if (GravityFieldLookup.hasFieldAt(level, pos)) {
            ci.cancel();
        }
    }
}

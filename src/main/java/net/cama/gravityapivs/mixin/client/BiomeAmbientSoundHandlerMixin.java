package net.cama.gravityapivs.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;

//method_26271 refers to a lambda which is why this class may cause mixin warnings/errors
@Mixin(BiomeAmbientSoundsHandler.class)
public abstract class BiomeAmbientSoundHandlerMixin {
    //m_274008_
    //lambda$tick$3
    @Redirect(
        method = "lambda$tick$3",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getEyeY()D"
        )
    )
    private double redirect_method_26271_getEyeY_0(LocalPlayer clientPlayerEntity) {
        return clientPlayerEntity.getEyePosition().y;
    }
    
    @Redirect(
        method = "lambda$tick$3",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"
        )
    )
    private double redirect_method_26271_getX_0(LocalPlayer clientPlayerEntity) {
        return clientPlayerEntity.getEyePosition().x;
    }
    
    @Redirect(
        method = "lambda$tick$3",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"
        )
    )
    private double redirect_method_26271_getZ_0(LocalPlayer clientPlayerEntity) {
        return clientPlayerEntity.getEyePosition().z;
    }
}

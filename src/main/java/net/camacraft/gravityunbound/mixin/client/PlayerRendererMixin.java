package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.phys.Vec3;

@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {
    // Elytra model roll: vanilla compares the (world-space) view vector with
    // the (local-frame) delta movement, so the view vector must be brought
    // into the SAME smooth visual frame the velocity lives in — the snapped
    // cardinal frame was wrong by the tilt while gliding under tilted frames
    @Redirect(
        method = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/AbstractClientPlayer;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 modify_setupTransforms_Vec3d_0(AbstractClientPlayer instance, float partialTick) {
        Vec3 viewVector = instance.getViewVector(partialTick);

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(instance);
        if (comp == null || comp.isVisuallyDefault()) {
            return viewVector;
        }

        return RotationUtil.vecWorldToPlayer(viewVector, comp.getRenderRotation(partialTick));
    }
}

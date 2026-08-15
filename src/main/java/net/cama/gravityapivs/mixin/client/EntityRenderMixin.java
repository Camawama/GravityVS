package net.cama.gravityapivs.mixin.client;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

@Mixin(EntityRenderer.class)
public abstract class EntityRenderMixin {
    // The nametag renders inside the pose that EntityRenderDispatcherMixin
    // rotated by conj(renderRotation) — so to stay camera-facing the
    // billboard orientation must be renderRotation * cameraOrientation, using
    // the SAME smooth interpolated frame as the model. The old version used
    // the snapped cardinal frame (and skipped entirely when the cardinal was
    // DOWN), which tilted nametags on tilted ships and made them wobble
    // through every smooth transition.
    @ModifyExpressionValue(
        method = "renderNameTag",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;cameraOrientation()Lorg/joml/Quaternionf;",
            ordinal = 0
        )
    )
    private Quaternionf modifyExpressionValue_renderLabelIfPresent_getRotation_0(Quaternionf originalRotation, Entity entity) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isVisuallyDefault()) {
            return originalRotation;
        }
        float partialTick = Minecraft.getInstance().getPartialTick();
        return new Quaternionf(comp.getRenderRotation(partialTick)).mul(originalRotation);
    }
}

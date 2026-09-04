package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;

/**
 * Applies the gravity rotation to the level's model-view matrix.
 *
 * Injected right before vanilla derives the inverse view-rotation matrix from
 * the pose stack, so everything downstream (inverse view rotation, frustum,
 * level rendering, hand rendering, other mods' hooks) sees the rotated stack
 * without replacing any vanilla code.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Camera mainCamera;

    @Shadow
    @Final
    Minecraft minecraft;

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "NEW",
            target = "(Lorg/joml/Matrix3fc;)Lorg/joml/Matrix3f;",
            remap = false
        )
    )
    private void gravityunbound$applyGravityRotation(float partialTicks, long nanos, PoseStack poseStack, CallbackInfo ci) {
        Entity focusedEntity = this.mainCamera.getEntity();
        if (focusedEntity == null) {
            return;
        }

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(focusedEntity);
        if (comp == null || comp.isRenderDefault()) {
            return;
        }

        poseStack.mulPose(comp.getRenderRotation(partialTicks));

        if (comp.isVisuallyMoving()) {
            // The level renderer re-tests chunk visibility against the
            // frustum only when the camera's own pitch/yaw changed — a
            // rotating gravity frame turns the view without touching them,
            // so the frustum result went stale mid-transition. Request the
            // frustum re-test alone. The earlier fix requested a FULL render
            // chunk update (needsUpdate) every frame of every rotation: the
            // asynchronous breadth-first rebuild of the whole render chunk
            // graph, re-queued frame after frame — the frame-rate collapse
            // that made every surface snap and every spinning ship look
            // like it was running at a lower frame rate.
            ((LevelRendererAccessor) this.minecraft.levelRenderer).gravityunbound$needsFrustumUpdate().set(true);
        }
    }
}

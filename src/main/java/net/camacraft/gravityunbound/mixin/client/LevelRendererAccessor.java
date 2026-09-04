package net.camacraft.gravityunbound.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.LevelRenderer;

/**
 * Reaches the level renderer's "re-test the frustum next frame" flag: the
 * cheap request a rotating gravity frame needs (vanilla raises it itself
 * when the camera's pitch/yaw change), as opposed to the full render chunk
 * graph rebuild {@code needsUpdate()} schedules. See GameRendererMixin.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("needsFrustumUpdate")
    AtomicBoolean gravityunbound$needsFrustumUpdate();
}

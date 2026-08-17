package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;

/**
 * Entities under an ACTIVE gravity frame sync position and velocity EVERY
 * tick instead of on vanilla's per-type cadence (items: every 20 ticks).
 *
 * Vanilla's sparse sync works because plain gravity is a constant vector —
 * the client's own simulation reproduces the server's exactly, so rare
 * corrections are invisible. Inside a radial/rotating field the pull depends
 * on position, so client prediction inevitably drifts, and a correction
 * every 20 ticks arrives as a visible TELEPORT (items, XP orbs, arrows all
 * "skipping around"). Per-tick corrections are each tiny — smooth motion —
 * and only entities inside fields pay the bandwidth.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    @Unique
    private boolean gravityunbound$activeFrame() {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this.entity);
        return comp != null && !comp.isVisuallyDefault();
    }

    @ModifyExpressionValue(
        method = "sendChanges",
        at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerEntity;updateInterval:I")
    )
    private int gravityunbound$everyTickInField(int original) {
        return gravityunbound$activeFrame() ? 1 : original;
    }

    @ModifyExpressionValue(
        method = "sendChanges",
        at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerEntity;trackDelta:Z", ordinal = 0)
    )
    private boolean gravityunbound$sendVelocityInField(boolean original) {
        return original || gravityunbound$activeFrame();
    }
}

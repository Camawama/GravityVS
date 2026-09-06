package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
 *
 * <p>VELOCITY PACKETS CARRY WORLD-SPACE MOTION. The entity's stored
 * deltaMovement lives in its LOCAL gravity frame; the client's lerpMotion
 * (see EntityMixin) converts an incoming packet from world space into the
 * receiver's local frame. Sending the raw local value therefore rotated it
 * TWICE for every entity under a rotated frame — knockback, explosion
 * pushes and FullStop's slime rebounds arrived on the client pointing the
 * wrong way (a player on a plated wall slid off the slime instead of
 * bouncing). Every packet construction now converts with the exact
 * convention Entity.move applies (projectiles are world-frame already).
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

    /**
     * The periodic velocity sync and the pairing snapshot read the entity's
     * LOCAL deltaMovement only to send it: wrap that read so the packet (and
     * the "did it change?" comparison against the last sent value) sees the
     * world-space motion. The hurtMarked push builds its packet from the
     * entity directly; that constructor converts itself
     * (ClientboundSetEntityMotionPacketMixin).
     */
    @WrapOperation(
        method = {"sendChanges", "sendPairingData"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 gravityunbound$worldMotion(Entity tracked, Operation<Vec3> original) {
        return GravityChangerAPI.movementToWorld(tracked, original.call(tracked));
    }
}

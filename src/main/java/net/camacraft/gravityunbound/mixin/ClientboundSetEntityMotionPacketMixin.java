package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The entity-reading constructor (ServerEntity's hurtMarked push, the
 * sprint-knockback sent to a hit player) packs the entity's LOCAL
 * deltaMovement; the client's lerpMotion converts an incoming packet from
 * WORLD space into its own frame, so under a rotated frame the push arrived
 * rotated twice. The packed motion is rebuilt from the world-space velocity
 * after vanilla's constructor ran, with vanilla's own clamp and scale.
 */
@Mixin(ClientboundSetEntityMotionPacket.class)
public abstract class ClientboundSetEntityMotionPacketMixin {

    @Shadow
    @Final
    @Mutable
    private int xa;

    @Shadow
    @Final
    @Mutable
    private int ya;

    @Shadow
    @Final
    @Mutable
    private int za;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void gravityunbound$worldMotion(Entity entity, CallbackInfo ci) {
        if (GravityChangerAPI.isAimDefault(entity)) {
            return;
        }
        Vec3 world = GravityChangerAPI.movementToWorld(entity, entity.getDeltaMovement());
        this.xa = (int) (Mth.clamp(world.x, -3.9D, 3.9D) * 8000.0D);
        this.ya = (int) (Mth.clamp(world.y, -3.9D, 3.9D) * 8000.0D);
        this.za = (int) (Mth.clamp(world.z, -3.9D, 3.9D) * 8000.0D);
    }
}

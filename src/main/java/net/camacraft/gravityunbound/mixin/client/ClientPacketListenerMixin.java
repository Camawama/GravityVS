package net.camacraft.gravityunbound.mixin.client;

import java.util.Map;
import java.util.UUID;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

@Mixin(value = ClientPacketListener.class, priority = 1001)
public abstract class ClientPacketListenerMixin {
    @Shadow
    @Final
    private Map<UUID, PlayerInfo> playerInfoMap;
    
    @Redirect(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleGameEvent(Lnet/minecraft/network/protocol/game/ClientboundGameEventPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_onGameStateChange_getEyeY_0(Player playerEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(playerEntity);
        if (gravityDirection == Direction.DOWN) {
            return playerEntity.getEyeY();
        }
        
        return playerEntity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleGameEvent(Lnet/minecraft/network/protocol/game/ClientboundGameEventPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getX()D",
            ordinal = 0
        )
    )
    private double redirect_onGameStateChange_getX_0(Player playerEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(playerEntity);
        if (gravityDirection == Direction.DOWN) {
            return playerEntity.getX();
        }
        
        return playerEntity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/client/multiplayer/ClientPacketListener;handleGameEvent(Lnet/minecraft/network/protocol/game/ClientboundGameEventPacket;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_onGameStateChange_getZ_0(Player playerEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(playerEntity);
        if (gravityDirection == Direction.DOWN) {
            return playerEntity.getZ();
        }
        
        return playerEntity.getEyePosition().z;
    }
    
    @WrapOperation(
        method = "handleExplosion",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;",
            ordinal = 0
        )
    )
    private Vec3 wrapOperation_onExplosion_add_0(
        Vec3 vec3d, double x, double y, double z, Operation<Vec3> original
    ) {
        // the knockback is added to deltaMovement, which lives in the VISUAL
        // frame — converting with the snapped cardinal (or skipping when the
        // cardinal was DOWN but the frame tilted, e.g. on a tilted ship) put
        // explosion knockback off by the full tilt angle
        Player player = Minecraft.getInstance().player;
        if (GravityChangerAPI.isAimDefault(player)) {
            return original.call(vec3d, x, y, z);
        }

        Vec3 local = RotationUtil.vecWorldToPlayer(x, y, z, GravityChangerAPI.getAimRotation(player));
        return original.call(vec3d, local.x, local.y, local.z);
    }
}

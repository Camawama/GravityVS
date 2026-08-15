package net.cama.gravityapivs.mixin.fall_distance;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

	@Shadow
	public ServerPlayer player;

	@Shadow
	private double lastGoodX, lastGoodY, lastGoodZ;

	// Replicas of the private static ServerGamePacketListenerImpl.clampHorizontal/clampVertical.
	private static double gravityapivs$clampHorizontal(double value) {
		return Mth.clamp(value, -3.0E7D, 3.0E7D);
	}

	private static double gravityapivs$clampVertical(double value) {
		return Mth.clamp(value, -2.0E7D, 2.0E7D);
	}

	// Vanilla computes the "rising" flag as (clamped packet Y) - lastGoodY > 0 and uses it to
	// trigger jumpFromGround. Recompute the same delta, but measure "up" along the player's
	// movement frame (visual quaternion for players) instead of world Y.
	@ModifyVariable(
			method = "handleMovePlayer",
			at = @At(value = "STORE", ordinal = 0),
			ordinal = 0,
			name = "flag"
	)
	private boolean modifyFlagBasedOnGravity(boolean originalFlag, ServerboundMovePlayerPacket packet) {
		double dx = gravityapivs$clampHorizontal(packet.getX(player.getX())) - lastGoodX;
		double dy = gravityapivs$clampVertical(packet.getY(player.getY())) - lastGoodY;
		double dz = gravityapivs$clampHorizontal(packet.getZ(player.getZ())) - lastGoodZ;

		Vec3 localVec = RotationUtil.vecWorldToPlayer(dx, dy, dz, GravityChangerAPI.getMovementRotation(player));
		return localVec.y > 0.0;
	}
}

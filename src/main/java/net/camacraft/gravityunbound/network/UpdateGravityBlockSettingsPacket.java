package net.camacraft.gravityunbound.network;

import java.util.function.Supplier;

import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.camacraft.gravityunbound.core.GravityCoreBlockEntity;
import net.camacraft.gravityunbound.normalizer.GravityNormalizerBlockEntity;
import net.camacraft.gravityunbound.plating.GravityPlatingBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: apply the settings chosen in the gravity block settings GUI
 * ({@code client.gui.GravityBlockSettingsScreen}) to the targeted block
 * entity.
 *
 * The payload travels as a {@link CompoundTag}; the server treats every value
 * as untrusted — the block entities' {@code applySettingsFromGui} methods
 * clamp everything to its legal range before writing.
 */
public class UpdateGravityBlockSettingsPacket
{
	/** Which of the three gravity field blocks the settings target. */
	public enum TargetType
	{
		PLATING, CORE, NORMALIZER;

		public static TargetType fromIndex(int index)
		{
			TargetType[] values = values();
			return values[Mth.clamp(index, 0, values.length - 1)];
		}
	}

	private final BlockPos pos;
	private final TargetType targetType;
	private final CompoundTag payload;

	public UpdateGravityBlockSettingsPacket(BlockPos pos, TargetType targetType, CompoundTag payload)
	{
		this.pos = pos;
		this.targetType = targetType;
		this.payload = payload;
	}

	public UpdateGravityBlockSettingsPacket(FriendlyByteBuf buf)
	{
		this.pos = buf.readBlockPos();
		this.targetType = TargetType.fromIndex(buf.readVarInt());
		CompoundTag tag = buf.readNbt();
		this.payload = tag != null ? tag : new CompoundTag();
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBlockPos(this.pos);
		buf.writeVarInt(this.targetType.ordinal());
		buf.writeNbt(this.payload);
	}

	public static class Handler
	{
		// generous squared reach check: on Valkyrien Skies ships the BLOCK
		// coordinates live in the shipyard, far from the player's world
		// position — the ship check below covers that case
		private static final double MAX_DISTANCE_SQ = 4096.0;

		/** Target-category mask; a payload from an older client means "everything". */
		private static int readTargets(CompoundTag tag)
		{
			return tag.contains("targets")
				? net.camacraft.gravityunbound.util.FieldTargets.sanitize(tag.getInt("targets"))
				: net.camacraft.gravityunbound.util.FieldTargets.ALL;
		}

		public static boolean onMessage(UpdateGravityBlockSettingsPacket message, Supplier<NetworkEvent.Context> ctx)
		{
			ctx.get().enqueueWork(() ->
			{
				if (!ctx.get().getDirection().getReceptionSide().isServer())
				{
					return;
				}
				ServerPlayer player = ctx.get().getSender();
				if (player == null)
				{
					return;
				}
				// the settings GUI is creative-only (like command blocks);
				// enforce it server-side so a modified survival client
				// cannot apply settings either
				if (!player.isCreative())
				{
					return;
				}
				ServerLevel level = player.serverLevel();
				BlockPos pos = message.pos;
				if (!level.hasChunkAt(pos))
				{
					return;
				}
				if (player.distanceToSqr(Vec3.atCenterOf(pos)) >= MAX_DISTANCE_SQ
					&& VSGameUtilsKt.getShipManagingPos(level, pos) == null)
				{
					return;
				}

				CompoundTag tag = message.payload;
				switch (message.targetType)
				{
					case PLATING ->
					{
						if (level.getBlockEntity(pos) instanceof GravityPlatingBlockEntity be)
						{
							Direction side = Direction.byName(tag.getString("side"));
							if (side != null)
							{
								int oldRange = be.sourceMaxRange();
								be.applySettingsFromGui(
									side,
									tag.getInt("level"),
									tag.getBoolean("isAttracting"),
									tag.getBoolean("gradualFalloff"),
									tag.getDouble("gravityAccel"),
									tag.getBoolean("surfaceSnap"),
									tag.getBoolean("showParticles"),
									!tag.contains("affectsShips") || tag.getBoolean("affectsShips"),
									readTargets(tag),
									tag.getBoolean("applyToConnected")
								);
								// wake fluids so they re-settle under the new field state;
								// applyToConnected may have changed distant plates too
								net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluidsAround(
									level, pos, Math.max(oldRange, be.sourceMaxRange())
										+ (tag.getBoolean("applyToConnected") ? 20 : 0));
							}
						}
					}
					case CORE ->
					{
						if (level.getBlockEntity(pos) instanceof GravityCoreBlockEntity be)
						{
							int oldRange = be.sourceMaxRange();
							be.applySettingsFromGui(
								tag.getInt("range"),
								tag.getBoolean("attracting"),
								tag.getBoolean("gradualFalloff"),
								tag.getDouble("gravityAccel"),
								tag.getBoolean("surfaceSnap"),
								tag.getBoolean("showParticles"),
								!tag.contains("affectsShips") || tag.getBoolean("affectsShips"),
								readTargets(tag)
							);
							net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluidsAround(
								level, pos, Math.max(oldRange, be.sourceMaxRange()));
						}
					}
					case NORMALIZER ->
					{
						if (level.getBlockEntity(pos) instanceof GravityNormalizerBlockEntity be)
						{
							Direction localDown = Direction.byName(tag.getString("localDown"));
							if (localDown != null)
							{
								int oldRange = be.sourceMaxRange();
								be.applySettingsFromGui(
									localDown,
									tag.getInt("range"),
									tag.getDouble("gravityAccel"),
									tag.getBoolean("showParticles"),
									readTargets(tag)
								);
								net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluidsAround(
									level, pos, Math.max(oldRange, be.sourceMaxRange()));
							}
						}
					}
				}
			});

			ctx.get().setPacketHandled(true);
			return true;
		}
	}
}

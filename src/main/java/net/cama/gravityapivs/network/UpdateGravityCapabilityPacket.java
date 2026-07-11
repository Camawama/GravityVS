package net.cama.gravityapivs.network;

import java.util.UUID;
import java.util.function.Supplier;

import org.joml.Quaternionf;

import net.cama.gravityapivs.capabilities.GravityCapabilities;
import net.cama.gravityapivs.util.GCUtil;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

public class UpdateGravityCapabilityPacket
{
	private final UUID entityUUID;
	private final boolean noAnimation;
	private final Vec3 baseGravityDirection;
	private final Vec3 currentGravityDirection;
	private final double baseGravityStrength;
	private final double currentGravityStrength;
	private final Quaternionf rotation;

	public UpdateGravityCapabilityPacket(boolean noAnimation, UUID entityUUID, Vec3 baseGravityDirection, Vec3 currentGravityDirection, double baseGravityStrength, double currentGravityStrength, Quaternionf rotation)
	{
		this.noAnimation = noAnimation;
		this.entityUUID = entityUUID;
		this.baseGravityDirection = baseGravityDirection;
		this.currentGravityDirection = currentGravityDirection;
		this.baseGravityStrength = baseGravityStrength;
		this.currentGravityStrength = currentGravityStrength;
		this.rotation = rotation;
	}

	public UpdateGravityCapabilityPacket(FriendlyByteBuf buf)
	{
		this.noAnimation = buf.readBoolean();
		this.entityUUID = buf.readUUID();
		this.baseGravityDirection = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		this.currentGravityDirection = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		this.baseGravityStrength = buf.readDouble();
		this.currentGravityStrength = buf.readDouble();
		this.rotation = new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeBoolean(this.noAnimation);
		buf.writeUUID(this.entityUUID);
		buf.writeDouble(this.baseGravityDirection.x);
		buf.writeDouble(this.baseGravityDirection.y);
		buf.writeDouble(this.baseGravityDirection.z);
		buf.writeDouble(this.currentGravityDirection.x);
		buf.writeDouble(this.currentGravityDirection.y);
		buf.writeDouble(this.currentGravityDirection.z);
		buf.writeDouble(this.baseGravityStrength);
		buf.writeDouble(this.currentGravityStrength);
		buf.writeFloat(this.rotation.x());
		buf.writeFloat(this.rotation.y());
		buf.writeFloat(this.rotation.z());
		buf.writeFloat(this.rotation.w());
	}

	public static class Handler
	{
		public static boolean onMessage(UpdateGravityCapabilityPacket message, Supplier<NetworkEvent.Context> ctx)
		{
			ctx.get().enqueueWork(() ->
			{
				if(ctx.get().getDirection().getReceptionSide().isClient())
				{
					GCUtil.getClientLevel(level ->
					{
						Entity entity = GCUtil.getEntityByUUID(level, message.entityUUID);
						if(entity == null)
						{
							// the entity has not been spawned on this client yet;
							// it will receive its state from the start-tracking sync
							return;
						}
						entity.getCapability(GravityCapabilities.GRAVITY).ifPresent(cap ->
						{
							cap.sync(message.noAnimation, message.baseGravityDirection, message.currentGravityDirection, message.baseGravityStrength, message.currentGravityStrength, message.rotation);
						});
					});
				}
			});

			ctx.get().setPacketHandled(true);
			return true;
		}
	}
}

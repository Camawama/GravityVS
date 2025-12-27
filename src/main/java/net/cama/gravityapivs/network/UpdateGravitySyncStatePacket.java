package net.cama.gravityapivs.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import net.cama.gravityapivs.util.GCUtil;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

public class UpdateGravitySyncStatePacket 
{
	private final UUID entityUUID;
	
	public UpdateGravitySyncStatePacket(UUID entityUUID) 
	{
		this.entityUUID = entityUUID;
	}

	public UpdateGravitySyncStatePacket(FriendlyByteBuf buf)
	{
		this.entityUUID = buf.readUUID();
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeUUID(this.entityUUID);
	}
	
	public static class Handler 
	{
		public static boolean onMessage(UpdateGravitySyncStatePacket message, Supplier<NetworkEvent.Context> ctx) 
		{
			ctx.get().enqueueWork(() ->
			{
				if(ctx.get().getDirection().getReceptionSide().isServer())
				{
					Entity entity = GCUtil.getEntityByUUID(ctx.get().getSender().level, message.entityUUID);
					GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(entity);
					cap.needsSync = false;
					cap.noAnimation = false;
				}
			});

			ctx.get().setPacketHandled(true);
			return true;
		}
	}
}

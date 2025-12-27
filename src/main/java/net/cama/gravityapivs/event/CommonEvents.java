package net.cama.gravityapivs.event;

import net.cama.gravityapivs.GravityAPI;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.util.GCUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = GravityAPI.MODID)
public class CommonEvents
{
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
    	//GravityCommand.register(event.getDispatcher());
    }
    
	@SubscribeEvent
	public static void onEntityJoinLevel(EntityJoinLevelEvent event)
	{
		Entity entity = event.getEntity();
		GCUtil.ENTITY_MAP.put(entity.getClass().hashCode(), entity);
		GCUtil.ENTITY_MAP2.put(entity.getClass().getSuperclass().hashCode(), entity);
	}
	
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event)
    {
    	Player player = event.getEntity();
    	if(event.isWasDeath() && !GravityConfig.resetGravityOnRespawn.get())
    	{
        	Player original = event.getOriginal();
        	original.revive();
        	GravityChangerAPI.setBaseGravityDirection(player, GravityChangerAPI.getBaseGravityDirection(original));
    	}
		for(Entity entity : GCUtil.getAllEntities(player.level))
		{
			if(!entity.level.isClientSide)
			{
    			if(GravityChangerAPI.getBaseGravityDirection(entity) == Direction.DOWN)
    			{
    				continue;
    			}
    			GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponent(entity);
    			cap.initialized = false;
				cap.deserializeNBT(cap.serializeNBT());
			}
		}
    }
}

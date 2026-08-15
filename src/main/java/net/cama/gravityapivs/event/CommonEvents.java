package net.cama.gravityapivs.event;

import net.cama.gravityapivs.GravityAPI;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import net.cama.gravityapivs.command.GravityCommand;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.network.GravityNetwork;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE, modid = GravityAPI.MODID)
public class CommonEvents
{
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        GravityCommand.register(event.getDispatcher());
    }

    /**
     * Initial sync: a player that starts tracking an entity needs its current
     * gravity state, otherwise the entity renders with default gravity until the
     * next change.
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event)
    {
        if (event.getEntity() instanceof ServerPlayer serverPlayer)
        {
            GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponentOrNull(event.getTarget());
            if (cap != null && cap.entity != null)
            {
                boolean wasNoAnimation = cap.noAnimation;
                cap.noAnimation = true;
                GravityNetwork.sendNonLocal(cap.makeSyncPacket(), serverPlayer);
                cap.noAnimation = wasNoAnimation;
            }
        }
    }

    /**
     * The local player also needs its own state on login/respawn/dimension change.
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        sendSelfSync(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        sendSelfSync(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event)
    {
        sendSelfSync(event.getEntity());
    }

    private static void sendSelfSync(Player player)
    {
        if (player instanceof ServerPlayer serverPlayer)
        {
            GravityCapabilityImpl cap = GravityChangerAPI.getGravityComponentOrNull(serverPlayer);
            if (cap != null && cap.entity != null)
            {
                // save/restore so the player's next genuine gravity change is
                // not accidentally broadcast without animation
                boolean wasNoAnimation = cap.noAnimation;
                cap.noAnimation = true;
                GravityNetwork.sendNonLocal(cap.makeSyncPacket(), serverPlayer);
                cap.noAnimation = wasNoAnimation;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event)
    {
        Player player = event.getEntity();
        if (event.isWasDeath() && !GravityConfig.resetGravityOnRespawn.get())
        {
            Player original = event.getOriginal();
            original.revive();
            GravityChangerAPI.setBaseGravityDirection(player, GravityChangerAPI.getBaseGravityDirection(original));
            GravityChangerAPI.setBaseGravityStrength(player, GravityChangerAPI.getBaseGravityStrength(original));
        }
    }
}

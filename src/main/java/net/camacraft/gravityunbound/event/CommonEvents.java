package net.camacraft.gravityunbound.event;

import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.command.GravityCommand;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.network.GravityNetwork;

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
     * A just-spawned entity must be seen by nearby field sources THIS tick:
     * their entity-query caches are staggered (1-2 ticks), which left
     * spawn-egg mobs unaffected by gravity for a few visible ticks before the
     * fresh-spawn snap could fire.
     */
    @SubscribeEvent
    public static void onEntityJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event)
    {
        net.camacraft.gravityunbound.util.GravityFieldLookup.invalidateEntityCachesNear(
            event.getLevel(), event.getEntity().blockPosition()
        );
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

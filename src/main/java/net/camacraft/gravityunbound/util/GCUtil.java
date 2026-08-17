package net.camacraft.gravityunbound.util;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.LogicalSidedProvider;
import net.minecraftforge.fml.LogicalSide;

public class GCUtil {

	public static void getClientLevel(Consumer<Level> consumer)
	{
		LogicalSidedProvider.CLIENTWORLD.get(LogicalSide.CLIENT).filter(ClientLevel.class::isInstance).ifPresent(level ->
		{
			consumer.accept(level);
		});
	}

	// Level#getEntities() (SRG m_142646_) is protected in 1.20.1, so the O(1)
	// client-side UUID lookup goes through a cached reflective accessor;
	// ObfuscationReflectionHelper resolves the SRG name in both dev and prod.
	private static java.lang.reflect.Method levelGetEntities;

	@SuppressWarnings("unchecked")
	public static Entity getEntityByUUID(Level level, UUID uuid)
	{
		if (level instanceof ServerLevel serverLevel)
		{
			return serverLevel.getEntity(uuid);
		}
		if (level instanceof ClientLevel clientLevel)
		{
			try
			{
				if (levelGetEntities == null)
				{
					levelGetEntities = net.minecraftforge.fml.util.ObfuscationReflectionHelper
						.findMethod(Level.class, "m_142646_");
				}
				return ((net.minecraft.world.level.entity.LevelEntityGetter<Entity>)
					levelGetEntities.invoke(clientLevel)).get(uuid);
			}
			catch (ReflectiveOperationException | RuntimeException e)
			{
				// fall back to the linear scan if the accessor is unavailable
				for (Entity entity : clientLevel.entitiesForRendering())
				{
					if (entity.getUUID().equals(uuid))
					{
						return entity;
					}
				}
			}
		}
		return null;
	}

    public static MutableComponent getLinkText(String link) {
        return Component.literal(link).withStyle(
            style -> style.withClickEvent(new ClickEvent(
                ClickEvent.Action.OPEN_URL, link
            )).withUnderlined(true)
        );
    }

    public static MutableComponent getDirectionText(Direction gravityDirection) {
        return Component.translatable("direction." + gravityDirection.getName());
    }

    public static double distanceToRange(double value, double rangeStart, double rangeEnd) {
        if (value < rangeStart) {
            return rangeStart - value;
        }

        if (value > rangeEnd) {
            return value - rangeEnd;
        }

        return 0;
    }

    // Note: implemented via Player#isLocalPlayer so no client-only classes are
    // referenced from common code (dedicated-server safety).
    public static boolean isClientPlayer(Entity entity) {
        return entity.level().isClientSide()
            && entity instanceof net.minecraft.world.entity.player.Player player
            && player.isLocalPlayer();
    }

    public static boolean isRemotePlayer(Entity entity) {
        return entity.level().isClientSide()
            && entity instanceof net.minecraft.world.entity.player.Player player
            && !player.isLocalPlayer();
    }
}

package net.cama.gravityapivs;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

public class EntityTags {
    /**
     * Entities in this tag are never affected by gravity fields or gravity items.
     */
    public static final TagKey<EntityType<?>> GRAVITY_IMMUNE =
        TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(GravityAPI.MODID, "gravity_immune"));

    public static boolean canChangeGravity(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof Player player && player.isSpectator()) {
            return false;
        }
        return !entity.getType().is(GRAVITY_IMMUNE);
    }

    public static boolean allowGravityTransformationInRendering(Entity entity) {
        return true;
    }
}

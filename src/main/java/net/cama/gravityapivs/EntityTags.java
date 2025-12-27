package net.cama.gravityapivs;

import net.minecraft.world.entity.Entity;

public class EntityTags {
    public static boolean canChangeGravity(Entity entity) {
        return true;
    }
    
    public static boolean allowGravityTransformationInRendering(Entity entity) {
        return true;
    }
}

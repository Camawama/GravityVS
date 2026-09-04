package net.camacraft.gravityunbound.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * WHAT a gravity field acts on — a per-block (per plate side) bit mask set
 * from the settings GUI. Players, mobs (every other living entity), objects
 * (items, minecarts, boats, TNT, projectiles — anything not living),
 * particles and fluids can each be switched off independently: a field
 * that pulls dropped items and particles but leaves every living thing
 * alone, a purely decorative field that only bends particles, and so on.
 */
public final class FieldTargets {
    public static final int PLAYERS = 1;
    public static final int MOBS = 2;
    public static final int OBJECTS = 4;
    public static final int PARTICLES = 8;
    public static final int FLUIDS = 16;
    public static final int ALL = PLAYERS | MOBS | OBJECTS | PARTICLES | FLUIDS;

    private FieldTargets() {
    }

    /** Clamp an untrusted mask to the defined bits. */
    public static int sanitize(int mask) {
        return mask & ALL;
    }

    /** Whether the mask includes the entity's category. */
    public static boolean appliesTo(int mask, Entity entity) {
        if (entity instanceof Player) {
            return (mask & PLAYERS) != 0;
        }
        if (entity instanceof LivingEntity) {
            return (mask & MOBS) != 0;
        }
        return (mask & OBJECTS) != 0;
    }

    /** Whether the mask includes a block-grid consumer kind. */
    public static boolean has(int mask, GravityFieldLookup.Kind kind) {
        return switch (kind) {
            case FLUID -> (mask & FLUIDS) != 0;
            case PARTICLE -> (mask & PARTICLES) != 0;
            case ANY -> true;
        };
    }

    /** Whether the mask still affects anything that lives in the block grid. */
    public static boolean anyGridKind(int mask) {
        return (mask & (FLUIDS | PARTICLES)) != 0;
    }
}

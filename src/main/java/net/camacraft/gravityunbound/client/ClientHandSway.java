package net.camacraft.gravityunbound.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Client-only helper (never loaded on a dedicated server — the caller
 * guards on the level side and this class is the only reference to
 * {@link LocalPlayer}).
 *
 * The first-person hand sways by {@code (view yaw - smoothed yaw) * 0.1}
 * where the smoothed yaw ({@code LocalPlayer.yBob}) follows the real yaw at
 * half the gap per tick. The gravity frame re-parametrizes yaw whenever it
 * unwinds a twist (every tick on a spinning ship, ~90 degrees at each
 * cardinal flip on its walls) with exact camera compensation — invisible
 * to the view, but the smoothed copy still saw a "turn" and the hand
 * swung after it: a per-tick sawtooth while riding and a big swing at every
 * quarter turn. Shifting the smoothed copy by the same amount keeps the
 * sway what it should be: a reaction to the player actually turning.
 */
public final class ClientHandSway {
    private ClientHandSway() {
    }

    public static void shiftYaw(Entity entity, float deltaYaw) {
        if (entity instanceof LocalPlayer player) {
            player.yBob += deltaYaw;
            player.yBobO += deltaYaw;
        }
    }
}

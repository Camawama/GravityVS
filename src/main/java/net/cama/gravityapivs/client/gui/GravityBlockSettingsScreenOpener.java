package net.cama.gravityapivs.client.gui;

import net.cama.gravityapivs.network.UpdateGravityBlockSettingsPacket.TargetType;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Client-only entry point for opening the gravity block settings screen.
 *
 * The three block classes call these static methods only behind a
 * {@code level.isClientSide()} (plus dist) check, so this class — and the
 * client classes it references — never loads on a dedicated server. Mirrors
 * the isolation pattern of {@code util.FieldVisuals.Client}: the method
 * signatures contain only common types.
 */
public final class GravityBlockSettingsScreenOpener {

    public static void openPlating(BlockPos pos, Direction plateSide) {
        Minecraft.getInstance().setScreen(new GravityBlockSettingsScreen(TargetType.PLATING, pos, plateSide));
    }

    public static void openCore(BlockPos pos) {
        Minecraft.getInstance().setScreen(new GravityBlockSettingsScreen(TargetType.CORE, pos, null));
    }

    public static void openNormalizer(BlockPos pos) {
        Minecraft.getInstance().setScreen(new GravityBlockSettingsScreen(TargetType.NORMALIZER, pos, null));
    }

    private GravityBlockSettingsScreenOpener() {}
}

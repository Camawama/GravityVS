package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.client.GuiRenderState;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

/**
 * Marks GUI entity rendering (the inventory paper doll and every other
 * screen that reuses this helper) so the gravity model rotation stands down
 * — see {@link GuiRenderState}. Without this, a player under rotated
 * gravity rendered tilted/upside-down in the inventory, sticking out of the
 * portrait box and over other GUI elements.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {

    @Inject(
        method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;IIILorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V",
        at = @At("HEAD")
    )
    private static void gravityunbound$beginGuiEntity(
        GuiGraphics graphics, int x, int y, int scale,
        Quaternionf pose, Quaternionf cameraOrientation, LivingEntity entity,
        CallbackInfo ci
    ) {
        GuiRenderState.renderingGuiEntity = true;
    }

    @Inject(
        method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;IIILorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V",
        at = @At("RETURN")
    )
    private static void gravityunbound$endGuiEntity(
        GuiGraphics graphics, int x, int y, int scale,
        Quaternionf pose, Quaternionf cameraOrientation, LivingEntity entity,
        CallbackInfo ci
    ) {
        GuiRenderState.renderingGuiEntity = false;
    }
}

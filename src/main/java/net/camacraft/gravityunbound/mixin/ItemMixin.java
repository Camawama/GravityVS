package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

@Mixin(value = Item.class, priority = 1001)
public class ItemMixin {
    @WrapOperation(
        method = "getPlayerPOVHitResult",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F",
            ordinal = 0
        )
    )
    private static float wrapOperation_raycast_getYaw(Player player, Operation<Float> original) {
        // Player aim angles live in the visual aim frame, not the snapped cardinal frame.
        if (GravityChangerAPI.isAimDefault(player)) return original.call(player);
        return RotationUtil.rotPlayerToWorld(original.call(player), player.getXRot(), GravityChangerAPI.getAimRotation(player)).x;
    }
    
    @WrapOperation(
        method = "getPlayerPOVHitResult",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getXRot()F",
            ordinal = 0
        )
    )
    private static float wrapOperation_raycast_getPitch(Player player, Operation<Float> original) {
        // Player aim angles live in the visual aim frame, not the snapped cardinal frame.
        if (GravityChangerAPI.isAimDefault(player)) return original.call(player);
        return RotationUtil.rotPlayerToWorld(player.getYRot(), original.call(player), GravityChangerAPI.getAimRotation(player)).y;
    }
}

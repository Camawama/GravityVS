package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;

@Mixin(UseOnContext.class)
public abstract class UseOnContextMixin {
    @WrapOperation(
        method = "getRotation",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F",
            ordinal = 0
        )
    )
    private float wrapOperation_getPlayerYaw_getYaw_0(Player entity, Operation<Float> original) {
        // Player aim angles live in the visual aim frame, not the snapped cardinal frame.
        if (GravityChangerAPI.isAimDefault(entity)) {
            return original.call(entity);
        }

        return RotationUtil.rotPlayerToWorld(original.call(entity), entity.getXRot(), GravityChangerAPI.getAimRotation(entity)).x;
    }
}

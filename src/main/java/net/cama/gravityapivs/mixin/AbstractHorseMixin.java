package net.cama.gravityapivs.mixin;

import net.cama.gravityapivs.api.GravityChangerAPI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

@Mixin(AbstractHorse.class)
public class AbstractHorseMixin {
    @ModifyVariable(method = "Lnet/minecraft/world/entity/animal/horse/AbstractHorse;calculateFallDamage(FF)I", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float diminishFallDamage(float value) {
        return value * (float) Math.sqrt(GravityChangerAPI.getGravityStrength(((Entity) (Object) this)));
    }
}

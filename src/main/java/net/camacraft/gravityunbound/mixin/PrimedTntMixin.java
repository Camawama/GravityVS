package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;

@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin extends Entity {
    public PrimedTntMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    

    // PrimedTnt/FallingBlockEntity override tick() WITHOUT calling
    // super.tick(), so the gravity capability's per-tick driver (injected
    // into Entity.tick) never ran for them — TNT ignored fields entirely.
    @org.spongepowered.asm.mixin.injection.Inject(method = "tick", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void gravityunbound$tickCapability(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        this.getCapability(net.camacraft.gravityunbound.capabilities.GravityCapabilities.GRAVITY)
            .ifPresent(net.camacraft.gravityunbound.capabilities.IGravityCapability::tick);
    }

    @ModifyArg(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"
        ),
        index = 1
    )
    private double multiplyGravity(double x) {
        return x * GravityChangerAPI.getGravityStrength(this);
    }
}

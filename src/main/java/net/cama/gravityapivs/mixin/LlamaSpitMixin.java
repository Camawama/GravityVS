package net.cama.gravityapivs.mixin;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

@Mixin(LlamaSpit.class)
public class LlamaSpitMixin {
    // Vanilla LlamaSpit.tick applies gravity as getDeltaMovement().add(0, -0.06, 0), i.e.
    // always along world down ((double)-0.06F is the 1.20.1 llama spit gravity constant,
    // verified against the decompiled source). This is the only Vec3.add(DDD) call in tick().
    // Scale it by the gravity strength and, for non-DOWN gravity, apply it along the spit's
    // local down axis instead (same axis-swap idea as AbstractArrowMixin's tick compensation,
    // done directly at the add call).
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 wrapOperation_tick_addGravity(Vec3 instance, double x, double y, double z, Operation<Vec3> original) {
        Entity self = (Entity) (Object) this;
        double g = 0.05999999865889549D * GravityChangerAPI.getGravityStrength(self);
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(self);
        if (gravityDirection == Direction.DOWN) {
            return original.call(instance, x, -g, z);
        }

        Vec3 gravity = RotationUtil.vecPlayerToWorld(0.0D, -g, 0.0D, gravityDirection);
        return original.call(instance, gravity.x, gravity.y, gravity.z);
    }
}

package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileMixin {
    
    @Shadow
    protected abstract float getGravity();

    /*@Override
    public Direction gravitychanger$getAppliedGravityDirection() {
        return GravityChangerAPI.getGravityDirection((ThrownEntity)(Object)this);
    }*/
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;tick()V",
        at = @At(
            value = "STORE"
        )
        , ordinal = 0
    )
    public Vec3 tick(Vec3 modify) {
        ThrowableProjectile self = (ThrowableProjectile) (Object) this;
        net.minecraft.core.Direction cardinal = GravityChangerAPI.getGravityDirection(self);
        if (cardinal == net.minecraft.core.Direction.DOWN) {
            return modify;
        }
        // pre-cancel vanilla's world-down gravity, re-apply along the
        // CONTINUOUS field vector (cardinal fallback) for smooth orbits
        double g = this.getGravity();
        Vec3 pull;
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(self);
        Vec3 field = comp != null ? comp.getTargetGravityVector() : Vec3.ZERO;
        if (field.lengthSqr() > 1.0E-6) {
            pull = field.normalize();
        }
        else if (comp != null && !comp.isVisuallyDefault()) {
            // remote client fallback: the synced visual frame's down tracks
            // the server's continuous pull (the target vector isn't synced)
            pull = RotationUtil.vecPlayerToWorld(new Vec3(0, -1, 0), comp.getCurrentRotation());
        }
        else {
            pull = Vec3.atLowerCornerOf(cardinal.getNormal());
        }
        return modify.add(0.0, g, 0.0).add(pull.scale(g));
    }
    
    // Vanilla's (EntityType, LivingEntity, Level) constructor spawns at
    // (ownerX, ownerEyeY - 0.1, ownerZ), which assumes world-down gravity.
    // Recreate the eye-relative offset in the owner's aim frame (same pattern as
    // CrossbowItemMixin's eye-based spawn correction).
    @Inject(
        method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;)V",
        at = @At("TAIL")
    )
    private void inject_init_repositionForRotatedOwner(EntityType<? extends ThrowableProjectile> type, LivingEntity owner, Level level, CallbackInfo ci) {
        if (GravityChangerAPI.isAimDefault(owner)) {
            return;
        }

        Vec3 pos = owner.getEyePosition().subtract(
            RotationUtil.vecPlayerToWorld(0.0D, 0.10000000149011612D, 0.0D, GravityChangerAPI.getAimRotation(owner))
        );
        ((ThrowableProjectile) (Object) this).setPos(pos.x, pos.y, pos.z);
    }
    
    @ModifyReturnValue(method = "getGravity", at = @At("RETURN"))
    private float multiplyGravity(float original) {
        return original * (float) GravityChangerAPI.getGravityStrength(((Entity) (Object) this));
    }
}

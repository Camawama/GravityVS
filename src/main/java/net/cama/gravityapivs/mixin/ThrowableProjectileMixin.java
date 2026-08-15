package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
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
        //if(this instanceof RotatableEntityAccessor) {
        modify = new Vec3(modify.x, modify.y + this.getGravity(), modify.z);
        modify = RotationUtil.vecWorldToPlayer(modify, GravityChangerAPI.getGravityDirection((ThrowableProjectile) (Object) this));
        modify = new Vec3(modify.x, modify.y - this.getGravity(), modify.z);
        modify = RotationUtil.vecPlayerToWorld(modify, GravityChangerAPI.getGravityDirection((ThrowableProjectile) (Object) this));
        // }
        return modify;
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

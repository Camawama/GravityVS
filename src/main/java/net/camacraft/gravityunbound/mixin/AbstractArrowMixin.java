package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin extends Entity {
    
    public AbstractArrowMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/AbstractArrow;tick()V",
        at = @At(
            value = "STORE"
        )
        , ordinal = 0
    )
    public Vec3 tick(Vec3 modify) {
        // Vanilla only applies gravity when the arrow has physics and gravity enabled
        // (returning Loyalty tridents are noPhysics) - skip the compensation otherwise.
        if (this.isNoGravity() || ((AbstractArrow) (Object) this).isNoPhysics()) {
            return modify;
        }

        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this);
        if (gravityDirection == Direction.DOWN) {
            return modify;
        }

        // Vanilla later subtracts 0.05 * gravityStrength from world y (the ModifyConstant
        // below scales the vanilla constant). Pre-cancel that world-down pull and re-apply
        // the same magnitude along the CONTINUOUS field vector (falling back to the
        // cardinal): orbits around a core curve smoothly instead of stepping through
        // the six cardinal pulls.
        double g = 0.05000000074505806D * GravityChangerAPI.getGravityStrength(this);
        Vec3 pull = gravityunbound$fieldPull(gravityDirection);
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
    private void inject_init_repositionForRotatedOwner(EntityType<? extends AbstractArrow> type, LivingEntity owner, Level level, CallbackInfo ci) {
        if (GravityChangerAPI.isAimDefault(owner)) {
            return;
        }

        Vec3 pos = owner.getEyePosition().subtract(
            RotationUtil.vecPlayerToWorld(0.0D, 0.10000000149011612D, 0.0D, GravityChangerAPI.getAimRotation(owner))
        );
        this.setPos(pos.x, pos.y, pos.z);
    }
    
    @ModifyConstant(method = "Lnet/minecraft/world/entity/projectile/AbstractArrow;tick()V", constant = @Constant(doubleValue = 0.05000000074505806))
    private double multiplyGravity(double constant) {
        return constant * GravityChangerAPI.getGravityStrength(this);
    }

    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityunbound$fieldPull(Direction cardinal) {
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(this);
        if (comp != null) {
            Vec3 field = comp.getTargetGravityVector();
            if (field.lengthSqr() > 1.0E-6) {
                return field.normalize();
            }
            // remote client: the field target isn't synced, but the visual
            // frame is — frame-down tracks the server's continuous pull far
            // closer than the snapping cardinal
            if (!comp.isVisuallyDefault()) {
                return RotationUtil.vecPlayerToWorld(new Vec3(0, -1, 0), comp.getCurrentRotation());
            }
        }
        return Vec3.atLowerCornerOf(cardinal.getNormal());
    }
}

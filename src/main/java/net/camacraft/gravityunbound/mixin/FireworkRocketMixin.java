package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketMixin extends Entity {
    
    @Shadow
    private @Nullable LivingEntity attachedToEntity;
    
    
    public FireworkRocketMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    /*@Override
    public Direction gravitychanger$getAppliedGravityDirection() {
        Entity vehicle = this.getVehicle();
        if(vehicle != null) {
            return GravityChangerAPI.getGravityDirection(vehicle);
        }

        return GravityChangerAPI.getGravityDirection((FireworkRocketEntity)(Object)this);
    }*/
    // The elytra boost mixes the rider's WORLD-space look angle directly into
    // their velocity — which lives in the rider's movement frame. Convert the
    // look into that frame: the VISUAL (arbitrary-angle) frame for players,
    // the cardinal frame for mobs. Using the snapped cardinal direction here
    // boosted players sideways whenever their frame was off-cardinal (radial
    // fields, tilted ships, mid-transition).
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;tick()V",
        at = @At(
            value = "STORE"
        )
        , ordinal = 0
    )
    public Vec3 tick(Vec3 value) {
        if (attachedToEntity != null) {
            value = RotationUtil.vecWorldToPlayer(value, GravityChangerAPI.getMovementRotation(attachedToEntity));
        }
        return value;
    }
    
    // @ModifyVariable(
    //         method = "tick",
    //         at = @At(
    //                 value = "INVOKE_ASSIGN",
    //                 target = "Lnet/minecraft/entity/LivingEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;",
    //                 ordinal = 0
    //         )
    // )
    // private Vec3d modify_tick_Vec3d_0(Vec3d vec3d) {
    //     assert this.shooter != null;
    //     Direction gravityDirection = ((EntityAccessor) this.shooter).gravitychanger$getAppliedGravityDirection();
    //     if(gravityDirection == Direction.DOWN) {
    //         return vec3d;
    //     }
//
    //     return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
    // }
}

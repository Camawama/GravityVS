package net.cama.gravityapivs.mixin;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Player.class, priority = 1001)
public abstract class PlayerMixin extends LivingEntity {
    @Shadow
    @Final
    private Abilities abilities;
    
    @Shadow
    public abstract EntityDimensions getDimensions(Pose pose);
    
    @Shadow
    protected abstract boolean isStayingOnGroundSurface();
    
    @Shadow
    protected abstract boolean isAboveGround();
    
    protected PlayerMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }
    
    @WrapOperation(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getLookAngle()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 wrapOperation_travel_getRotationVector_0(Player playerEntity, Operation<Vec3> original) {
        if (GravityChangerAPI.isAimDefault(playerEntity)) {
            return original.call(playerEntity);
        }

        return RotationUtil.vecWorldToPlayer(original.call(playerEntity), GravityChangerAPI.getAimRotation(playerEntity));
    }


    @WrapOperation(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos modify_move_multiply_0(double x, double y, double z, Operation<BlockPos> original) {
        Vec3 rotate = RotationUtil.vecPlayerToWorld(
            new Vec3(0.0D, 1.0D - 0.1D, 0.0D), GravityChangerAPI.getGravityRotation(this)
        );
        return original.call((double) x - rotate.x, (double) y - rotate.y + (1.0D - 0.1D), (double) z - rotate.z);
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/item/ItemEntity;",
            ordinal = 0
        )
    )
    private ItemEntity redirect_dropItem_new_0(
        Level world, double x, double y, double z, ItemStack stack
    ) {
        if (GravityChangerAPI.isGravityDefault(this)) {
            return new ItemEntity(world, x, y, z, stack);
        }

        Vec3 vec3d = this.getEyePosition()
            .subtract(RotationUtil.vecPlayerToWorld(0.0D, 0.3D, 0.0D, GravityChangerAPI.getGravityRotation(this)));
        
        ItemEntity itemEntity = new ItemEntity(world, vec3d.x, vec3d.y, vec3d.z, stack);
        
//        // change the gravity of the thrown item
//        GravityChangerAPI.setBaseGravityDirection(
//            itemEntity, gravityDirection
//        );
        // the item entity calculates position both on client and server separately
        // if gravity is not down, the client and server will desync (the reason is not yet known)
        // don't let item change gravity for now
        
        return itemEntity;
    }
    
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/ItemEntity;setDeltaMovement(DDD)V"
        )
    )
    private void wrapOperation_dropItem_setVelocity(ItemEntity itemEntity, double x, double y, double z, Operation<Void> original) {
        if (GravityChangerAPI.isGravityDefault(this)) {
            original.call(itemEntity, x, y, z);
            return;
        }

        Vec3 world = RotationUtil.vecPlayerToWorld(x, y, z, GravityChangerAPI.getGravityRotation(this));
        GravityChangerAPI.setWorldVelocity(itemEntity, world);
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/player/Player;maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_adjustMovementForSneaking(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        Entity this_ = (Entity) (Object) this;
        if (GravityChangerAPI.isGravityDefault(this_)) return;
        // capsule players: the box-based sneak edge-backoff does not apply
        cir.setReturnValue(movement);
        if (true) return;
        org.joml.Quaternionf gravityRotation = GravityChangerAPI.getGravityRotation(this_);

        Vec3 playerMovement = RotationUtil.vecWorldToPlayer(movement, gravityRotation);

        if (!this.abilities.flying && (type == MoverType.SELF || type == MoverType.PLAYER) && this.isStayingOnGroundSurface() && this.isAboveGround()) {
            double d = playerMovement.x;
            double e = playerMovement.z;
            double var7 = 0.05D;

            while (d != 0.0D && this_.level().noCollision(this, this.getBoundingBox().move(RotationUtil.vecPlayerToWorld(d, (double) (-this.maxUpStep()), 0.0D, gravityRotation)))) {
                if (d < 0.05D && d >= -0.05D) {
                    d = 0.0D;
                }
                else if (d > 0.0D) {
                    d -= 0.05D;
                }
                else {
                    d += 0.05D;
                }
            }
            
            while (e != 0.0D && this_.level().noCollision(this, this.getBoundingBox().move(RotationUtil.vecPlayerToWorld(0.0D, (double) (-this.maxUpStep()), e, gravityRotation)))) {
                if (e < 0.05D && e >= -0.05D) {
                    e = 0.0D;
                }
                else if (e > 0.0D) {
                    e -= 0.05D;
                }
                else {
                    e += 0.05D;
                }
            }
            
            while (d != 0.0D && e != 0.0D && this_.level().noCollision(this, this.getBoundingBox().move(RotationUtil.vecPlayerToWorld(d, (double) (-this.maxUpStep()), e, gravityRotation)))) {
                if (d < 0.05D && d >= -0.05D) {
                    d = 0.0D;
                }
                else if (d > 0.0D) {
                    d -= 0.05D;
                }
                else {
                    d += 0.05D;
                }
                
                if (e < 0.05D && e >= -0.05D) {
                    e = 0.0D;
                }
                else if (e > 0.0D) {
                    e -= 0.05D;
                }
                else {
                    e += 0.05D;
                }
            }
            
            cir.setReturnValue(RotationUtil.vecPlayerToWorld(d, playerMovement.y, e, gravityRotation));
        }
        else {
            cir.setReturnValue(movement);
        }
    }
    
    @WrapOperation(
        method = "isAboveGround",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;"
        )
    )
    private AABB wrapOperation_method_30263_offset_0(AABB box, double x, double y, double z, Operation<AABB> original) {
        if (GravityChangerAPI.isGravityDefault(this)) {
            return original.call(box, x, y, z);
        }

        Vec3 world = RotationUtil.vecPlayerToWorld(x, y, z, GravityChangerAPI.getGravityRotation(this));
        return original.call(box, world.x, world.y, world.z);
    }
    
    @WrapOperation(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F",
            ordinal = 0
        )
    )
    private float wrapOperation_attack_getYaw_0(Player attacker, Operation<Float> original, Entity target) {
        org.joml.Quaternionf targetRotation = GravityChangerAPI.getAimRotation(target);
        org.joml.Quaternionf attackerRotation = GravityChangerAPI.getAimRotation(attacker);
        if (targetRotation.equals(attackerRotation)) {
            return original.call(attacker);
        }

        Vec3 worldLook = RotationUtil.vecPlayerToWorld(
            RotationUtil.rotToVec(original.call(attacker), attacker.getXRot()), attackerRotation
        );
        return RotationUtil.vecToRot(RotationUtil.vecWorldToPlayer(worldLook, targetRotation)).x;
    }
    
    @WrapOperation(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F",
            ordinal = 1
        )
    )
    private float wrapOperation_attack_getYaw_1(Player attacker, Operation<Float> original, Entity target) {
        org.joml.Quaternionf targetRotation = GravityChangerAPI.getAimRotation(target);
        org.joml.Quaternionf attackerRotation = GravityChangerAPI.getAimRotation(attacker);
        if (targetRotation.equals(attackerRotation)) {
            return original.call(attacker);
        }

        Vec3 worldLook = RotationUtil.vecPlayerToWorld(
            RotationUtil.rotToVec(original.call(attacker), attacker.getXRot()), attackerRotation
        );
        return RotationUtil.vecToRot(RotationUtil.vecWorldToPlayer(worldLook, targetRotation)).x;
    }
    
    @WrapOperation(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F",
            ordinal = 2
        )
    )
    private float wrapOperation_attack_getYaw_2(Player attacker, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(attacker)) {
            return original.call(attacker);
        }

        return RotationUtil.rotPlayerToWorld(original.call(attacker), attacker.getXRot(), GravityChangerAPI.getAimRotation(attacker)).x;
    }
    
    @WrapOperation(
        method = "attack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getYRot()F",
            ordinal = 3
        )
    )
    private float wrapOperation_attack_getYaw_3(Player attacker, Operation<Float> original) {
        if (GravityChangerAPI.isAimDefault(attacker)) {
            return original.call(attacker);
        }

        return RotationUtil.rotPlayerToWorld(original.call(attacker), attacker.getXRot(), GravityChangerAPI.getAimRotation(attacker)).x;
    }
    
    @WrapOperation(
        method = "addParticlesAroundSelf",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
        )
    )
    private void modify_addDeathParticless_addParticle_0(Level instance, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz, Operation<Void> original) {
        if (GravityChangerAPI.isGravityDefault(this)) {
            original.call(instance, particle, x, y, z, dx, dy, dz);
        }
        else {
            Vec3 vec3d = this.position().subtract(RotationUtil.vecPlayerToWorld(this.position().subtract(x, y, z), GravityChangerAPI.getGravityRotation(this)));
            original.call(instance, particle, vec3d.x, vec3d.y, vec3d.z, dx, dy, dz);
        }
    }
    
    @WrapOperation(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;inflate(DDD)Lnet/minecraft/world/phys/AABB;"
        )
    )
    private AABB modify_tickMovement_expand_0(AABB instance, double x, double y, double z, Operation<AABB> original) {
        if (GravityChangerAPI.isGravityDefault(this)) return original.call(instance, x, y, z);

        Vec3 vec3d = RotationUtil.maskPlayerToWorld(new Vec3(x, y, z), GravityChangerAPI.getGravityRotation(this));
        return original.call(instance, vec3d.x, vec3d.y, vec3d.z);
    }
}

package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
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


    // Swim-surface probe: vanilla samples the block at feet + 0.9 WORLD-up to
    // decide whether the surface-swim boost applies. Re-express the 0.9
    // offset along the player's LOCAL up. (x, y, z) arrives as
    // (getX(), getY() + 0.9, getZ()), so remove the world-up 0.9 and add the
    // rotated offset instead.
    @WrapOperation(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos modify_move_multiply_0(double x, double y, double z, Operation<BlockPos> original) {
        if (GravityChangerAPI.isAimDefault(this)) {
            return original.call(x, y, z);
        }
        Vec3 rotate = RotationUtil.vecPlayerToWorld(
            new Vec3(0.0D, 1.0D - 0.1D, 0.0D), GravityChangerAPI.getMovementRotation(this)
        );
        return original.call(x + rotate.x, y - (1.0D - 0.1D) + rotate.y, z + rotate.z);
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
        if (GravityChangerAPI.isAimDefault(this)) {
            return new ItemEntity(world, x, y, z, stack);
        }

        Vec3 vec3d = gravityunbound$dropPosition();

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
    
    /**
     * The player's height scale (1 for a vanilla-sized player): the current
     * pose's dimensions against the unscaled pose dimensions — which is
     * exactly what Pehkui scales.
     */
    @Shadow
    @Final
    private static java.util.Map<Pose, EntityDimensions> POSES;

    /**
     * Where a dropped item spawns: vanilla's 0.3 below the eyes, along the
     * FRAME's down (the eyes themselves follow the frame), body-scaled for a
     * scaled player — unscaled, a 1/16 player's drop spawned several body
     * heights below its eyes, through the floor of a matching-scale ship.
     */
    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityunbound$dropPosition() {
        return this.getEyePosition().subtract(RotationUtil.vecPlayerToWorld(
            0.0D, 0.3D * gravityunbound$heightScale(), 0.0D, GravityChangerAPI.getAimRotation(this)));
    }

    /**
     * Pehkui repositions the drop AFTER drop() returns, by a world-Y offset
     * that assumes vanilla's straight-down placement — wrong by that offset
     * under a rotated frame. Applied at the very end (this mixin runs after
     * Pehkui's, priority 1001 over 1000), the frame-relative position wins
     * whether or not any other mod moved the item in between.
     */
    @Inject(
        method = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At("RETURN")
    )
    private void gravityunbound$placeDrop(
        ItemStack stack, boolean dropAround, boolean includeThrowerName,
        CallbackInfoReturnable<ItemEntity> cir
    ) {
        ItemEntity item = cir.getReturnValue();
        if (item == null || GravityChangerAPI.isAimDefault(this)) {
            return;
        }
        Vec3 pos = gravityunbound$dropPosition();
        item.setPos(pos.x, pos.y, pos.z);
    }

    @org.spongepowered.asm.mixin.Unique
    private double gravityunbound$heightScale() {
        Pose pose = this.getPose();
        float unscaled = POSES.getOrDefault(pose, Player.STANDING_DIMENSIONS).height;
        float scaled = this.getDimensions(pose).height;
        if (unscaled <= 1.0E-4F || scaled <= 1.0E-4F) {
            return 1.0D;
        }
        return net.minecraft.util.Mth.clamp(scaled / unscaled, 0.001D, 1000.0D);
    }

    @WrapOperation(
        method = "Lnet/minecraft/world/entity/player/Player;drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/item/ItemEntity;setDeltaMovement(DDD)V"
        )
    )
    private void wrapOperation_dropItem_setVelocity(ItemEntity itemEntity, double x, double y, double z, Operation<Void> original) {
        if (GravityChangerAPI.isAimDefault(this)) {
            original.call(itemEntity, x, y, z);
            return;
        }

        // the throw direction was built from local yaw/pitch — visual-frame
        // quantities — so the conversion must use the aim frame, not the
        // snapped cardinal (tilted-ship drops flew off by the tilt angle)
        Vec3 world = RotationUtil.vecPlayerToWorld(x, y, z, GravityChangerAPI.getAimRotation(this));
        GravityChangerAPI.setWorldVelocity(itemEntity, world);
    }
    
    @Inject(
        method = "Lnet/minecraft/world/entity/player/Player;maybeBackOffFromEdge(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/MoverType;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_adjustMovementForSneaking(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        Entity this_ = (Entity) (Object) this;
        // Capsule players (ANY non-default visual frame, including tilted
        // frames whose cardinal is still DOWN): the box-based sneak
        // edge-backoff tests the loose world-aligned envelope along world
        // axes — meaningless against the rotated capsule, and its arbitrary
        // clamps were felt as movement hitches while sneaking on rotated
        // surfaces. The capsule handles its own ground support.
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(this_);
        if (comp == null || comp.isVisuallyDefault()) {
            return;
        }
        cir.setReturnValue(movement);
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

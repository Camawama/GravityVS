package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    private static double gravitychanger$onPlayerMove_playerMovementY;
    
    @Shadow
    public ServerPlayer player;
    
    @Shadow
    private static double clampHorizontal(double d) {return 0;}
    
    ;
    
    @Shadow
    private static double clampVertical(double d) {return 0;}
    
    ;
    
    @Shadow
    private double lastGoodX;
    
    @Shadow
    private double lastGoodY;
    
    @Shadow
    private double lastGoodZ;

//    @Redirect(
//            method = "onPlayerMove",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;getY()D",
//                    ordinal = 3
//            )
//    )
//    private double redirect_onPlayerMove_getY_3(ServerPlayerEntity serverPlayerEntity) {
//        Direction gravityDirection = GravityChangerAPI.getGravityDirection(serverPlayerEntity);
//        if(gravityDirection == Direction.DOWN) {
//            return serverPlayerEntity.getY();
//        }
//
//        return RotationUtil.vecWorldToPlayer(serverPlayerEntity.getPos(), gravityDirection).y;
//    }
//
//    @Redirect(
//            method = "onPlayerMove",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;getY()D",
//                    ordinal = 7
//            )
//    )
//    private double redirect_onPlayerMove_getY_7(ServerPlayerEntity serverPlayerEntity) {
//        Direction gravityDirection = GravityChangerAPI.getGravityDirection(serverPlayerEntity);
//        if(gravityDirection == Direction.DOWN) {
//            return serverPlayerEntity.getY();
//        }
//
//        return RotationUtil.vecWorldToPlayer(serverPlayerEntity.getPos(), gravityDirection).y;
//    }
//
//    @ModifyVariable(
//            method = "onPlayerMove",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;isOnGround()Z",
//                    ordinal = 0
//            ),
//            ordinal = 0
//    )
//    private boolean modify_onPlayerMove_boolean_0(boolean value, PlayerMoveC2SPacket packet) {
//        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
//        if(gravityDirection == Direction.DOWN) {
//            return value;
//        }
//
//        gravitychanger$onPlayerMove_playerMovementY = RotationUtil.vecWorldToPlayer(
//                clampHorizontal(packet.getX(this.player.getX())) - this.updatedX,
//                clampVertical(packet.getY(this.player.getY())) - this.updatedY,
//                clampHorizontal(packet.getZ(this.player.getZ())) - this.updatedZ,
//                gravityDirection
//        ).y;
//        return gravitychanger$onPlayerMove_playerMovementY > 0.0D;
//    }
//
//    @ModifyVariable(
//            method = "onPlayerMove",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;getX()D",
//                    ordinal = 5
//            ),
//            ordinal = 10
//    )
//    private double modify_onPlayerMove_double_12(double value) {
//        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
//        if(gravityDirection == Direction.DOWN) {
//            return value;
//        }
//
//        return gravitychanger$onPlayerMove_playerMovementY;
//    }
//
//    @ModifyArg(
//            method = "onPlayerMove",
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V",
//                    ordinal = 0
//            ),
//            index = 1
//    )
//    private Vec3d modify_onPlayerMove_move_0(Vec3d vec3d) {
//        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
//        if(gravityDirection == Direction.DOWN) {
//            return vec3d;
//        }
//
//        return RotationUtil.vecWorldToPlayer(vec3d, gravityDirection);
//    }
    
    @ModifyArg(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
        )
    )
    private Vec3 modify_onPlayerMove_move_1(Vec3 vec3d) {
        if (GravityChangerAPI.isAimDefault(this.player)) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, GravityChangerAPI.getAimRotation(this.player));
    }
    
    //@Redirect(
    //        method = "onVehicleMove",
    //        at = @At(
    //                value = "INVOKE",
    //                target = "Lnet/minecraft/entity/Entity;getY()D",
    //                ordinal = 0
    //        )
    //)
    //private double redirect_onVehicleMove_getY_0(Entity instance) {
    //    Direction gravityDirection = ((EntityAccessor) instance).gravitychanger$getAppliedGravityDirection();
    //    if(gravityDirection == Direction.DOWN) {
    //        return instance.getY();
    //    }
//
    //    return RotationUtil.vecWorldToPlayer(instance.getPos(), gravityDirection).y;
    //}
//
    //@Redirect(
    //        method = "onVehicleMove",
    //        at = @At(
    //                value = "INVOKE",
    //                target = "Lnet/minecraft/entity/Entity;getY()D",
    //                ordinal = 2
    //        )
    //)
    //private double redirect_onVehicleMove_getY_2(Entity instance) {
    //    Direction gravityDirection = ((EntityAccessor) instance).gravitychanger$getAppliedGravityDirection();
    //    if(gravityDirection == Direction.DOWN) {
    //        return instance.getY();
    //    }
//
    //    return RotationUtil.vecWorldToPlayer(instance.getPos(), gravityDirection).y;
    //}
    
    @ModifyArg(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
        ),
        index = 1
    )
    private Vec3 modify_onVehicleMove_move_0(Vec3 vec3d) {
        if (GravityChangerAPI.isAimDefault(this.player)) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, GravityChangerAPI.getAimRotation(this.player));
    }
    
    //@ModifyVariable(
    //        method = "onVehicleMove",
    //        at = @At(
    //                value = "INVOKE",
    //                target = "Lnet/minecraft/entity/Entity;getX()D",
    //                ordinal = 1
    //        ),ordinal = 0
    //)
    //private double modify_onVehicleMove_double_12(double value) {
    //    Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.player);
    //    if(gravityDirection == Direction.DOWN) {
    //        return value;
    //    }
//
    //    return gravitychanger$onPlayerMove_playerMovementY;
    //}
    
    
    @WrapOperation(
        method = "noBlocksAround",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;expandTowards(DDD)Lnet/minecraft/world/phys/AABB;"
        )
    )
    private AABB modify_onVehicleMove_move_0(AABB instance, double x, double y, double z, Operation<AABB> original) {
        Vec3 argVec = RotationUtil.vecWorldToPlayer(
            new Vec3(x, y, z), GravityChangerAPI.getGravityRotation(this.player)
        );
        return original.call(instance, argVec.x, argVec.y, argVec.z);
    }

    /**
     * Movement acceptance for capsule players cannot use the stored AABB.
     *
     * After replaying a client movement, the server only accepts the packet
     * position if moving the player's bounding box there collides with
     * nothing it wasn't already colliding with. In capsule mode the stored
     * box is a loose world-aligned ENVELOPE of the rotated capsule — it
     * legitimately overlaps geometry the spheres are nowhere near (that is
     * the entire point of the capsule), and Valkyrien Skies feeds its ships'
     * world-space shapes into getCollisions. So a player standing on a
     * rotated/plated ship failed this check on every packet, was teleported
     * back each time, and the SERVER-side position froze: other clients saw
     * them floating in place while the ship moved on (their own client kept
     * looking normal, because the local ship drag instantly re-applied).
     * The capsule replay in ServerPlayer.move() has already resolved real
     * collisions at this point, so the envelope test adds nothing but false
     * positives — skip it for capsule players.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
        method = "isPlayerCollidingWithAnythingNew",
        at = @At("HEAD"),
        cancellable = true
    )
    private void gravityapivs$capsuleSkipCollidingWithAnythingNew(
        net.minecraft.world.level.LevelReader level, AABB box, double x, double y, double z,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir
    ) {
        net.cama.gravityapivs.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(this.player);
        if (comp != null && comp.useCapsuleCollision()) {
            cir.setReturnValue(false);
        }
    }

}

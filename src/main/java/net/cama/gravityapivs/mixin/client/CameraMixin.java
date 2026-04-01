package net.cama.gravityapivs.mixin.client;

import net.cama.gravityapivs.RotationAnimation;
import net.cama.gravityapivs.api.GravityChangerAPI;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Camera;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Camera.class, priority = 1001)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    private Entity entity;

    @Shadow
    @Final
    private Quaternionf rotation;

    @Shadow
    private float eyeHeightOld;

    @Shadow
    private float eyeHeight;

    /**
     * Store the tickDelta passed to Camera#setup so all camera-related mixins use the same partial tick.
     */
    @Unique
    private float gravityapivs$tickDelta;

    @Inject(
            method = "setup",
            at = @At("HEAD")
    )
    private void gravityapivs$storeTickDelta(
            BlockGetter area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickDelta,
            CallbackInfo ci
    ) {
        this.gravityapivs$tickDelta = tickDelta;
    }

    @WrapOperation(
            method = "setup",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
                    ordinal = 0
            )
    )
    private void wrapOperation_update_setPos_0(
            Camera camera, double x, double y, double z,
            Operation<Void> original, BlockGetter area, Entity focusedEntity,
            boolean thirdPerson, boolean inverseView, float tickDelta
    ) {
        Vec3 gravityDirection = GravityChangerAPI.getGravityDirectionVec(focusedEntity);
        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(focusedEntity);

        if (animation == null) {
            original.call(this, x, y, z);
            return;
        }

        long timeMs = focusedEntity.level().getGameTime() * 50L + (long) (tickDelta * 50.0f);
        animation.update(timeMs);

        if (gravityDirection.equals(new Vec3(0, -1, 0)) && !animation.isInAnimation()) {
            original.call(this, x, y, z);
            return;
        }

        Quaternionf gravityRotation = animation.getCurrentGravityRotation(gravityDirection, timeMs);

        double entityX = Mth.lerp((double) tickDelta, focusedEntity.xo, focusedEntity.getX());
        double entityY = Mth.lerp((double) tickDelta, focusedEntity.yo, focusedEntity.getY());
        double entityZ = Mth.lerp((double) tickDelta, focusedEntity.zo, focusedEntity.getZ());

        double currentCameraY = Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight);

        Vec3 eyeOffset = animation.getEyeOffset(
                gravityRotation,
                new Vec3(0, currentCameraY, 0),
                gravityDirection
        );

        original.call(
                this,
                entityX + eyeOffset.x(),
                entityY + eyeOffset.y(),
                entityZ + eyeOffset.z()
        );
    }

    @Inject(
            method = "Lnet/minecraft/client/Camera;setRotation(FF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;",
                    shift = At.Shift.AFTER,
                    remap = false
            )
    )
    private void inject_setRotation(CallbackInfo ci) {
        if (this.entity == null) {
            return;
        }

        Vec3 gravityDirection = GravityChangerAPI.getGravityDirectionVec(this.entity);
        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(this.entity);
        if (animation == null) {
            return;
        }
        if (gravityDirection.equals(new Vec3(0, -1, 0)) && !animation.isInAnimation()) {
            return;
        }

        // Use the same tickDelta provided to Camera#setup for this render pass.
        float partialTick = this.gravityapivs$tickDelta;

        long timeMs = this.entity.level().getGameTime() * 50L + (long) (partialTick * 50.0f);

        Quaternionf rotation = new Quaternionf(animation.getCurrentGravityRotation(gravityDirection, timeMs));
        rotation.conjugate();
        rotation.mul(this.rotation);
        this.rotation.set(rotation.x(), rotation.y(), rotation.z(), rotation.w());
    }
}
package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.QuaternionUtil;
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
    private float gravityunbound$tickDelta;

    @Inject(
            method = "setup",
            at = @At("HEAD")
    )
    private void gravityunbound$storeTickDelta(
            BlockGetter area,
            Entity focusedEntity,
            boolean thirdPerson,
            boolean inverseView,
            float tickDelta,
            CallbackInfo ci
    ) {
        this.gravityunbound$tickDelta = tickDelta;
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
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(focusedEntity);

        if (comp == null || comp.isRenderDefault()) {
            original.call(this, x, y, z);
            return;
        }

        Quaternionf gravityRotation = comp.getRenderRotation(tickDelta);

        double entityX = Mth.lerp((double) tickDelta, focusedEntity.xo, focusedEntity.getX());
        double entityY = Mth.lerp((double) tickDelta, focusedEntity.yo, focusedEntity.getY());
        double entityZ = Mth.lerp((double) tickDelta, focusedEntity.zo, focusedEntity.getZ());

        // diagnostics: how far the camera-time position sits from the tick
        // position — nonzero means VS's render-ride is moving this entity
        // between ticks (see the vs-drag heartbeat)
        comp.dbgCamVsTickDist = (float) new Vec3(entityX, entityY, entityZ).distanceTo(comp.dbgTickPos);

        double currentCameraY = Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight);

        Vec3 eyeOffset = QuaternionUtil.rotate(
                new Vec3(0, currentCameraY, 0),
                new Quaternionf(gravityRotation).conjugate()
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

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this.entity);
        if (comp == null) {
            return;
        }

        // the render frame carries everything — including, while riding a
        // Valkyrien Skies ship, the sub-tick lead of the ship's DRAWN pose
        // over its tick pose (swing and twist alike; see the ship-relative
        // reconstruction in GravityCapabilityImpl.getRenderRotation). No
        // separate camera-side ship correction exists any more.
        if (!comp.isRenderDefault()) {
            Quaternionf gravityRotation = comp.getRenderRotation(this.gravityunbound$tickDelta);

            Quaternionf rotation = new Quaternionf(gravityRotation);
            rotation.conjugate();
            rotation.mul(this.rotation);
            this.rotation.set(rotation.x(), rotation.y(), rotation.z(), rotation.w());
        }
    }
}

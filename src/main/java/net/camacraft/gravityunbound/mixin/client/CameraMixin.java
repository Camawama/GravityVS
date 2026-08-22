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

        if (comp == null || comp.isVisuallyDefault()) {
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

        if (!comp.isVisuallyDefault()) {
            Quaternionf gravityRotation = comp.getRenderRotation(this.gravityunbound$tickDelta);

            Quaternionf rotation = new Quaternionf(gravityRotation);
            rotation.conjugate();
            rotation.mul(this.rotation);
            this.rotation.set(rotation.x(), rotation.y(), rotation.z(), rotation.w());
        }

        // SUB-TICK SHIP TWIST. The spin-follow applies the ship's twist to
        // yaw at TICK rate, but the ship is DRAWN at its per-frame render
        // transform — that gap alone reads as tick-rate stepping while
        // standing on a spinning ship. Rotate the CAMERA by the twist
        // (about the frame's up) of renderTransform o tickTransform^-1 so
        // the view tracks the drawn pose between ticks. Identity on
        // stationary ships; purely visual — no entity state is touched.
        if (comp.isShipFieldAnchored()
            && comp.getFieldAnchorShip()
                instanceof org.valkyrienskies.core.api.ships.ClientShip clientShip) {
            org.joml.Quaterniond drawn = new org.joml.Quaterniond(
                clientShip.getRenderTransform().getShipToWorldRotation());
            org.joml.Quaterniond tick = new org.joml.Quaterniond(
                clientShip.getTransform().getShipToWorldRotation());
            org.joml.Quaterniond delta = drawn.mul(tick.conjugate(), new org.joml.Quaterniond()).normalize();
            if (delta.w < 0) {
                delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
            }
            if (delta.w < 0.9999999999) {
                Vec3 up = comp.getUpVector();
                double s = delta.x * up.x + delta.y * up.y + delta.z * up.z;
                double angle = 2.0 * Math.atan2(s, delta.w);
                // SUBTRACT the fraction vanilla's yRotO lerp already shows:
                // the spin-follow's tick twist is interpolated by the yaw
                // lerp, so the camera only needs the REMAINDER of the
                // drawn-vs-tick delta (the render transform's lead). Adding
                // the full delta double-counted the intra-tick rotation —
                // a one-tick sawtooth felt as spinning-ship jitter.
                angle -= this.gravityunbound$tickDelta * comp.getLastSpinFollowTwist();
                if (Math.abs(angle) > 1.0E-6) {
                    Quaternionf twist = new Quaternionf().rotationAxis(
                        (float) angle, (float) up.x, (float) up.y, (float) up.z);
                    this.rotation.premul(twist);
                }
            }
        }
    }
}

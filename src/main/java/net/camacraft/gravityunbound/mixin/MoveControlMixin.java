package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla MoveControl decides "the next path point is above me, jump" by
 * comparing WORLD Y ({@code wantedY - mob.getY() > stepHeight}). Under a
 * rotated gravity frame, walking ALONG a wall changes world Y constantly, so
 * the condition fired every tick and mobs hopped nonstop while moving.
 * Re-evaluate the jump decision in the mob's LOCAL frame: jump only when the
 * wanted point is genuinely above the mob along its own up axis.
 */
@Mixin(MoveControl.class)
public abstract class MoveControlMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Shadow
    protected double wantedX;
    @Shadow
    protected double wantedY;
    @Shadow
    protected double wantedZ;

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/control/JumpControl;jump()V"
        )
    )
    private void gravityunbound$frameAwareJump(JumpControl jumpControl, Operation<Void> original) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this.mob);
        if (comp == null || comp.isVisuallyDefault()) {
            original.call(jumpControl);
            return;
        }

        Vec3 worldDelta = new Vec3(
            this.wantedX - this.mob.getX(),
            this.wantedY - this.mob.getY(),
            this.wantedZ - this.mob.getZ()
        );
        Vec3 local = RotationUtil.vecWorldToPlayer(worldDelta, comp.getCurrentRotation());
        double horizontalSq = local.x * local.x + local.z * local.z;
        if (local.y > (double) this.mob.getStepHeight()
            && horizontalSq < (double) Math.max(1.0F, this.mob.getBbWidth())) {
            original.call(jumpControl);
        }
        // otherwise: the "height difference" was an artifact of world-Y math
        // on a rotated surface — no jump
    }
}

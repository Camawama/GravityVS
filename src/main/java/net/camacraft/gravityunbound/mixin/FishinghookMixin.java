package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(FishingHook.class)
public abstract class FishinghookMixin extends Entity {
    
    
    public FishinghookMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    // Vanilla positions the hook at (x - f3*0.3, eyeY, z - f2*0.3): the player's eye height with
    // a 0.3-block horizontal offset derived from the local yaw, all applied on world axes.
    // For rotated players, rebuild the same offset in the aim frame around the true (gravity
    // aware) eye position. Only the spawn position is corrected here; the initial fling
    // velocity keeps its vanilla angular offsets.
    @Inject(
        method = "<init>(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;II)V",
        at = @At("TAIL")
    )
    private void inject_init_repositionForRotatedOwner(Player player, Level level, int luck, int lureSpeed, CallbackInfo ci) {
        if (GravityChangerAPI.isAimDefault(player)) {
            return;
        }

        float yaw = player.getYRot();
        float f2 = Mth.cos(-yaw * ((float) Math.PI / 180F) - (float) Math.PI);
        float f3 = Mth.sin(-yaw * ((float) Math.PI / 180F) - (float) Math.PI);
        Vec3 pos = player.getEyePosition().add(RotationUtil.vecPlayerToWorld(
            (double) (-f3) * 0.3D, 0.0D, (double) (-f2) * 0.3D, GravityChangerAPI.getAimRotation(player)
        ));
        this.setPos(pos.x, pos.y, pos.z);
    }


    @ModifyConstant(method = "Lnet/minecraft/world/entity/projectile/FishingHook;tick()V", constant = @Constant(doubleValue = -0.03))
    private double multiplyGravity(double constant) {
        return constant * GravityChangerAPI.getGravityStrength(this);
    }

    /**
     * The bobber's gravity, along the FIELD. Vanilla adds its -0.03 on world
     * Y just before move(); the hook never had a field-aware pull of its own
     * — it only ever "fell into" a core because move() wrongly rotated its
     * world-space velocity through the hook's turning frame, which is also
     * what made it appear to bounce off the field's edge on the way in. With
     * projectiles world-frame in move() (EntityMixin), the pull is re-aimed
     * here: cancel the world-down add and apply the same magnitude along the
     * field, exactly like arrows and throwables.
     */
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/projectile/FishingHook;tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 gravityunbound$gravityAlongField(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        // only the gravity add (pure -y); anything else in tick passes through
        if (x != 0.0 || z != 0.0 || y >= 0.0 || GravityChangerAPI.isGravityDefault(this)) {
            return original.call(velocity, x, y, z);
        }
        return velocity.add(GravityChangerAPI.getFieldPullDirection(this).scale(-y));
    }
}

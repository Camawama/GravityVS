package net.cama.gravityapivs.mixin;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(AreaEffectCloud.class)
public abstract class AreaEffectCloudMixin extends Entity {

    @Shadow
    public abstract boolean isWaiting();

    @Shadow
    public abstract float getRadius();

    public AreaEffectCloudMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
        )
    )
    private void modify_move_multiply_0(Level instance, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz, Operation<Void> original) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this);
        // Fast path: default gravity needs no adjustment.
        if (gravityDirection == Direction.DOWN) {
            original.call(instance, particle, x, y, z, dx, dy, dz);
            return;
        }

        // Re-sample the particle position in the cloud's local horizontal plane
        // (vanilla samples a disc in the world XZ plane around the cloud).
        boolean waiting = this.isWaiting();
        float g = waiting ? 0.2F : this.getRadius();
        float h = this.random.nextFloat() * 6.2831855F;
        float k = Mth.sqrt(this.random.nextFloat()) * g;

        Vec3 local = RotationUtil.vecWorldToPlayer(this.getX(), this.getY(), this.getZ(), gravityDirection);
        Vec3 pos = RotationUtil.vecPlayerToWorld(
            local.x + (double) (Mth.cos(h) * k),
            local.y,
            local.z + (double) (Mth.sin(h) * k),
            gravityDirection
        );

        // For ENTITY_EFFECT particles (dx, dy, dz) are RGB colour channels and must NOT be
        // rotated. For everything else they are a drift velocity (0.01 up + random horizontal
        // when active) that should follow the cloud's local up instead of world up.
        double vx = dx;
        double vy = dy;
        double vz = dz;
        if (particle.getType() != ParticleTypes.ENTITY_EFFECT) {
            Vec3 drift = RotationUtil.vecPlayerToWorld(dx, dy, dz, gravityDirection);
            vx = drift.x;
            vy = drift.y;
            vz = drift.z;
        }

        original.call(instance, particle, pos.x, pos.y, pos.z, vx, vy, vz);
    }
}

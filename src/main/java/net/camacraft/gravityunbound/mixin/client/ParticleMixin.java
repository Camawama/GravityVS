package net.camacraft.gravityunbound.mixin.client;

import java.util.List;

import net.camacraft.gravityunbound.client.GravityParticle;
import net.camacraft.gravityunbound.util.GravityFieldLookup;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Particles inside a gravity field live in the FIELD'S FRAME.
 *
 * A particle's velocity ({@code xd, yd, zd}) is, while the particle is in a
 * field, expressed in a frame whose -Y is the field's down. Every particle
 * class then does its own physics unchanged — vanilla's gravity, a cherry
 * leaf's sway, a campfire column's rise, a mod's extra pull — and all of it
 * happens along the field's axes, because the ONE place that turns velocity
 * into displacement, {@link Particle#move}, converts through the frame:
 * the world displacement is collided and applied, the result comes back
 * into the frame for the on-ground / blocked-axis bookkeeping. Spawn
 * velocities (world-space, "up" meaning world up) are rotated into the
 * frame when the particle is added, and re-expressed whenever the frame at
 * the particle's position changes (leaving the field, a rotating ship), so
 * the world velocity itself never jumps.
 *
 * The earlier approach re-aimed only the base class's gravity store: every
 * class that replaces {@code tick()} (leaves, drips, smoke) or adds its own
 * pull on top kept falling world-down, or blended both.
 */
@Mixin(Particle.class)
public abstract class ParticleMixin implements GravityParticle {
    @Shadow
    protected double x;
    @Shadow
    protected double y;
    @Shadow
    protected double z;
    @Shadow
    protected double xd;
    @Shadow
    protected double yd;
    @Shadow
    protected double zd;
    @Shadow
    protected boolean onGround;
    @Shadow
    protected boolean hasPhysics;
    @Shadow
    private boolean stoppedByCollision;
    @Shadow
    @Final
    protected ClientLevel level;
    @Shadow
    @Final
    private static double MAXIMUM_COLLISION_VELOCITY_SQUARED;

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public abstract void setBoundingBox(AABB box);

    @Shadow
    protected abstract void setLocationFromBoundingbox();

    // the frame the velocity is currently expressed in; null = world
    @Unique
    @Nullable
    private Quaternionf gravityunbound$frame = null;

    // blocked-axis tolerance: exact for the world grid's canonical frames
    // (axis permutations), a hair for ship frames' float rotations
    @Unique
    private static final double BLOCKED_EPSILON = 1.0E-7;

    @Override
    public void gravityunbound$adoptFieldFrame() {
        gravityunbound$adoptFrame(GravityFieldLookup.particleFrameAt(this.level, this.x, this.y, this.z));
    }

    /**
     * Switch the stored velocity into {@code frame} (null for world),
     * preserving the WORLD velocity exactly. No-op when unchanged.
     */
    @Unique
    private void gravityunbound$adoptFrame(@Nullable Quaternionf frame) {
        Quaternionf old = this.gravityunbound$frame;
        if (old == frame || (old != null && frame != null && old.equals(frame))) {
            return;
        }
        Vec3 velocity = new Vec3(this.xd, this.yd, this.zd);
        if (old != null) {
            velocity = RotationUtil.vecPlayerToWorld(velocity, old);
        }
        if (frame != null) {
            velocity = RotationUtil.vecWorldToPlayer(velocity, frame);
        }
        this.xd = velocity.x;
        this.yd = velocity.y;
        this.zd = velocity.z;
        this.gravityunbound$frame = frame;
    }

    /**
     * The frame-aware move. Injected at the head of {@link Particle#move},
     * which every physics particle calls each tick (including the many
     * classes that replace {@code tick()} outright), so the frame is also
     * refreshed here. The arguments are the caller's velocity in the frame
     * the velocity was in BEFORE this refresh, so they follow the same
     * re-expression.
     */
    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$moveInFrame(double dx, double dy, double dz, CallbackInfo ci) {
        Quaternionf before = this.gravityunbound$frame;
        Quaternionf frame = GravityFieldLookup.particleFrameAt(this.level, this.x, this.y, this.z);
        boolean changed = !(before == frame || (before != null && frame != null && before.equals(frame)));
        if (changed) {
            gravityunbound$adoptFrame(frame);
            Vec3 arg = new Vec3(dx, dy, dz);
            if (before != null) {
                arg = RotationUtil.vecPlayerToWorld(arg, before);
            }
            if (frame != null) {
                arg = RotationUtil.vecWorldToPlayer(arg, frame);
            }
            dx = arg.x;
            dy = arg.y;
            dz = arg.z;
        }
        if (frame == null) {
            if (changed) {
                // back in the world: run vanilla's move with the re-expressed
                // (now world-space) arguments
                ci.cancel();
                gravityunbound$vanillaMove(dx, dy, dz);
            }
            return;
        }
        ci.cancel();

        if (this.stoppedByCollision) {
            return;
        }
        Vec3 world = RotationUtil.vecPlayerToWorld(new Vec3(dx, dy, dz), frame);
        double wx = world.x;
        double wy = world.y;
        double wz = world.z;
        if (this.hasPhysics && (wx != 0.0 || wy != 0.0 || wz != 0.0)
            && wx * wx + wy * wy + wz * wz < MAXIMUM_COLLISION_VELOCITY_SQUARED) {
            Vec3 collided = Entity.collideBoundingBox(null, world, this.getBoundingBox(), this.level, List.of());
            wx = collided.x;
            wy = collided.y;
            wz = collided.z;
        }
        if (wx != 0.0 || wy != 0.0 || wz != 0.0) {
            this.setBoundingBox(this.getBoundingBox().move(wx, wy, wz));
            this.setLocationFromBoundingbox();
        }
        // vanilla's bookkeeping, in the frame: "did the vertical (down the
        // field) motion get stopped", "am I on the ground", "was an axis
        // blocked" — each compares the intended local delta with what was
        // actually achieved
        Vec3 achieved = RotationUtil.vecWorldToPlayer(new Vec3(wx, wy, wz), frame);
        if (Math.abs(dy) >= 1.0E-5 && Math.abs(achieved.y) < 1.0E-5) {
            this.stoppedByCollision = true;
        }
        this.onGround = Math.abs(dy - achieved.y) > BLOCKED_EPSILON && dy < 0.0;
        if (Math.abs(dx - achieved.x) > BLOCKED_EPSILON) {
            this.xd = 0.0;
        }
        if (Math.abs(dz - achieved.z) > BLOCKED_EPSILON) {
            this.zd = 0.0;
        }
    }

    /** Vanilla's {@code move} body (1.20.1), for the tick a particle leaves a field. */
    @Unique
    private void gravityunbound$vanillaMove(double dx, double dy, double dz) {
        if (this.stoppedByCollision) {
            return;
        }
        double d0 = dx;
        double d1 = dy;
        double d2 = dz;
        if (this.hasPhysics && (dx != 0.0 || dy != 0.0 || dz != 0.0)
            && dx * dx + dy * dy + dz * dz < MAXIMUM_COLLISION_VELOCITY_SQUARED) {
            Vec3 collided = Entity.collideBoundingBox(null, new Vec3(dx, dy, dz), this.getBoundingBox(), this.level, List.of());
            dx = collided.x;
            dy = collided.y;
            dz = collided.z;
        }
        if (dx != 0.0 || dy != 0.0 || dz != 0.0) {
            this.setBoundingBox(this.getBoundingBox().move(dx, dy, dz));
            this.setLocationFromBoundingbox();
        }
        if (Math.abs(d1) >= 1.0E-5 && Math.abs(dy) < 1.0E-5) {
            this.stoppedByCollision = true;
        }
        this.onGround = d1 != dy && d1 < 0.0;
        if (d0 != dx) {
            this.xd = 0.0;
        }
        if (d2 != dz) {
            this.zd = 0.0;
        }
    }
}

package net.camacraft.gravityunbound.mixin;

import java.util.List;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.camacraft.gravityunbound.capabilities.GravityCapabilities;
import net.camacraft.gravityunbound.capabilities.IGravityCapability;
import net.camacraft.gravityunbound.config.GravityConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    private Vec3 position;

    @Shadow
    private EntityDimensions dimensions;

    @Shadow
    private float eyeHeight;

    @Shadow
    public double xo;

    @Shadow
    public double yo;

    @Shadow
    public double zo;

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract Vec3 getEyePosition();

    @Shadow
    public abstract double getY();

    @Shadow
    public abstract double getZ();

    @Shadow
    public Level level;

    @Shadow
    public abstract int getBlockX();

    @Shadow
    public abstract int getBlockZ();

    @Shadow
    public boolean noPhysics;

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract boolean isVehicle();

    @Shadow
    public abstract AABB getBoundingBox();

    @Shadow
    public static Vec3 collideWithShapes(Vec3 movement, AABB entityBoundingBox, List<VoxelShape> collisions) {
        return null;
    }

    @Shadow
    public abstract Vec3 position();

    @Shadow
    protected abstract BlockPos getBlockPosBelowThatAffectsMyMovement();


    @Shadow
    public abstract boolean isPassengerOfSameVehicle(Entity entity);

    @Shadow
    public abstract void push(double deltaX, double deltaY, double deltaZ);

    @Shadow
    protected abstract void onBelowWorld();

    @Shadow
    public abstract double getEyeY();

    @Shadow
    public abstract float getViewYRot(float tickDelta);

    @Shadow
    public abstract float getYRot();

    @Shadow
    public abstract float getXRot();

    @Shadow
    @Final
    protected RandomSource random;

    @Shadow
    public float fallDistance;

    // convenience: the capability, or null when gravity is fully default (fast path)
    private GravityCapabilityImpl gravityunbound$comp() {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null || comp.isVisuallyDefault()) {
            return null;
        }
        return comp;
    }

    // players move/measure in the continuous visual frame; other entities keep
    // the cardinal physics frame
    private org.joml.Quaternionf gravityunbound$movementRotation(GravityCapabilityImpl comp) {
        return (Object) this instanceof Player ? comp.getVisualRotation() : comp.getCurrentRotation();
    }

    /**
     * Players under non-default gravity collide as a gravity-aligned capsule —
     * a hitbox that genuinely rotates with gravity and collides with Valkyrien
     * Skies ships exactly, in shipyard space. Replaces vanilla box collision.
     */
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_capsuleCollide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null || !comp.useCapsuleCollision()) {
            return;
        }

        Entity self = (Entity) (Object) this;
        Vec3 up = comp.getUpVector();

        // second ground reference: where gravity WANTS up to be — during a
        // landing on a steep face the frame's up still points the old way, and
        // contacts opposing the target must count as ground or alignment never
        // engages. Uses the EFFECTIVE target (the adopted surface normal while
        // one is held, the raw field otherwise): during an edge transition the
        // raw blended field is diagonal and kept promoting contacts back onto
        // the OLD face, pinning the player on the corner while the frame
        // rotated — the "stuck at every cube edge for a second or two" bug.
        Vec3 gravityUp = comp.getEffectiveUpVector();

        // STATIC PIN: standing idle on a sloped surface (a tilted ship deck,
        // a blocky planet face), the tangential component of gravity
        // regenerates every tick before any post-hoc friction can see it — a
        // sphere resting on a tilted plane slowly creeps downhill forever
        // ("sliding off ships at any angle"). A vanilla box doesn't slide
        // because the collision itself blocks the motion; give the capsule
        // the same behavior by removing the small tangential part of the
        // movement while grounded with no input. Real pushes (knockback,
        // pistons) exceed the threshold and pass through; the axis comparison
        // in move() then also zeroes the corresponding velocity, so the pin
        // is stable instead of re-accelerating each tick.
        // controlled side only: on the server the replayed client movement has
        // no input state (xxa/zza are always zero there), so the pin would
        // wrongly eat genuine walking movements during the replay
        // Pin gates (all four matter):
        // - never during a surface transition: the input frame is half-rotated
        //   and the "creep" the pin removes is the genuine transition momentum
        //   — this was the stuck/honey feel at concave corners and cube edges;
        // - never on slippery ground: vanilla lets you glide on ice, the pin
        //   killed sliding entirely in capsule mode;
        // - never in fluid: the water current's ~0.005/tick push IS a small
        //   tangential movement — the pin stripped it every tick, freezing
        //   grounded players solid inside flowing water (fluid drag already
        //   damps the tilted-gravity creep the pin exists to remove).
        // SNEAK EDGE-GUARD: vanilla's maybeBackOffFromEdge is world-down
        // based and is cancelled for capsule players, which removed the
        // "crouching stops you at the edge" safety entirely. Reimplemented
        // here in the FRAME's tangent plane: while sneaking and grounded,
        // shrink the tangential movement (vanilla's 0.05 decrements, per
        // local axis, then both) until the capsule would still have ground
        // within step height below the destination.
        if (self instanceof Player sneakPlayer && sneakPlayer.isShiftKeyDown()
            && comp.capsuleGrounded
            && !sneakPlayer.getAbilities().flying
            && movement.dot(up) <= 0.0
        ) {
            movement = gravityunbound$capsuleBackOffFromEdge(self, comp, up, movement);
        }

        // - only on genuinely TILTED ground: the pin exists to stop the
        //   downhill creep a sphere stack has on a slope. On ground that is
        //   flat relative to the frame there is no creep — and the pin was
        //   eating turning momentum and diagonal movement there instead
        //   ("catches on nothing", jitter when walking diagonally, and the
        //   molasses at concave plating seams).
        if (comp.capsuleGrounded && comp.capsuleGroundNormal != null
            && comp.capsuleGroundNormal.dot(up) < 0.999
            && self.level().isClientSide() && self.isControlledByLocalInstance()
            && self instanceof net.minecraft.world.entity.LivingEntity living
            && movement.dot(up) <= 0.01
            && !comp.isSurfaceTransitioning()
            && !gravityunbound$onSlipperyGround(self)
            && !self.isInWater() && !self.isInLava()
        ) {
            Vec3 normal = comp.capsuleGroundNormal;
            Vec3 tangential = movement.subtract(normal.scale(movement.dot(normal)));
            boolean hasInput = Math.abs(living.xxa) >= 0.01 || Math.abs(living.zza) >= 0.01;
            if (!hasInput) {
                if (tangential.lengthSqr() < 0.15 * 0.15) {
                    movement = normal.scale(movement.dot(normal));
                }
            }
            else {
                // DIRECTIONAL pin while walking: on a tilted surface (a ship
                // deck at a shallow angle) the downhill gravity creep reaches
                // an equilibrium comparable to walk speed — the player slid
                // downhill no matter which way they walked. Keep only the
                // tangential component along the INPUT direction; the residue
                // (the creep) is removed exactly like the idle pin. Real
                // pushes exceed the threshold and pass through unchanged.
                float yawRad = living.getYRot() * ((float) Math.PI / 180.0F);
                double sin = Math.sin(yawRad);
                double cos = Math.cos(yawRad);
                Vec3 inputLocal = new Vec3(
                    living.xxa * cos - living.zza * sin,
                    0,
                    living.zza * cos + living.xxa * sin
                );
                Vec3 inputWorld = RotationUtil.vecPlayerToWorld(inputLocal, comp.getVisualRotation());
                Vec3 inputTangent = inputWorld.subtract(normal.scale(inputWorld.dot(normal)));
                if (inputTangent.lengthSqr() > 1.0E-6) {
                    Vec3 inputDir = inputTangent.normalize();
                    double along = tangential.dot(inputDir);
                    // reversing direction keeps its momentum: vanilla
                    // decelerates through friction — pinning here erased the
                    // opposing momentum in one tick, an abrupt "catch"
                    if (along >= 0.0) {
                        Vec3 keep = inputDir.scale(along);
                        Vec3 residue = tangential.subtract(keep);
                        if (residue.lengthSqr() < 0.15 * 0.15) {
                            movement = normal.scale(movement.dot(normal)).add(keep);
                        }
                    }
                }
            }
        }

        // step assist only while the frame's up agrees with the surface being
        // stood on: mid-transition (gravity pressing the player against a
        // surface the frame hasn't aligned to yet) the step lift points
        // diagonally away from the surface and fires every tick — the
        // "launched off the tilted plated wall" escalator
        boolean stepEligible = comp.capsuleGrounded
            && (comp.capsuleGroundNormal == null || comp.capsuleGroundNormal.dot(up) > 0.85);

        net.camacraft.gravityunbound.util.CapsuleCollider.Result result =
            net.camacraft.gravityunbound.util.CapsuleCollider.collide(self, up, gravityUp, movement, stepEligible);

        boolean grounded = result.grounded;
        org.valkyrienskies.core.api.ships.Ship groundShip = result.groundShip;

        // Standing on a ship: report it to Valkyrien Skies' own dragging system
        // (this is normally done by the VS collision hook that the capsule
        // bypasses). VS then handles surface dragging, yaw-follow, client
        // interpolation AND its anticheat exemptions — without this the server
        // rejects ship-carried movement ("moved wrongly") and rubber-bands the
        // player into the ship.
        org.valkyrienskies.mod.common.util.EntityDraggingInformation dragInfo =
            ((org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider) self)
                .getDraggingInformation();
        if (grounded && groundShip != null) {
            // setLastShipStoodOn(non-null) also resets ticksSinceStoodOnShip to 0
            dragInfo.setLastShipStoodOn(groundShip.getId());
            dragInfo.setTicksSinceStoodOnShip(0);
            // VS's collide wrapper (entity_collision.MixinEntity.collideWithShips)
            // WIPES lastShipStoodOn whenever the collide result differs from its
            // own ship-adjusted movement — and the capsule legitimately changes
            // the movement every grounded tick, so standing on a ship erased the
            // standing info the moment we set it (the ship moved away under the
            // player). ignoreNextGroundStand is VS's own escape hatch: the
            // wrapper consumes it and skips exactly that wipe.
            dragInfo.setIgnoreNextGroundStand(true);
        }
        else if (!grounded && dragInfo.isEntityBeingDraggedByAShip()) {
            // Airborne with recent ship-standing state (a jump on a moving
            // ship): the capsule may still brush geometry mid-air, which
            // changes the movement and would trigger the same wrapper wipe —
            // losing the drag mid-jump and letting the ship slide out from
            // under the player. Keep the state alive; VS's own 25-tick window
            // (ticksSinceStoodOnShip keeps counting) still ends it naturally.
            // Landing on WORLD ground takes the grounded-without-ship path
            // above, where the flag is deliberately NOT set, so VS's normal
            // hand-off wipe still happens the moment the player steps off.
            dragInfo.setIgnoreNextGroundStand(true);
        }

        comp.capsuleGrounded = grounded;
        comp.capsuleGroundShip = grounded ? groundShip : null;
        comp.capsuleGroundNormal = grounded ? result.groundNormal : null;

        cir.setReturnValue(result.collidedMovement);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tick(CallbackInfo ci)
    {
        Entity entity = Entity.class.cast(this);
        entity.getCapability(GravityCapabilities.GRAVITY).ifPresent(IGravityCapability::tick);
    }

    /**
     * Frame-aware port of vanilla's sneak edge-guard. Movement is expressed
     * in the visual frame's tangent plane; each local axis (then both) is
     * shrunk in 0.05 steps until the capsule would still find ground within
     * step height below the destination — mirroring vanilla's
     * {@code maybeBackOffFromEdge} structure exactly, with the capsule fit
     * test standing in for {@code noCollision(box.move(x, -step, z))}.
     */
    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityunbound$capsuleBackOffFromEdge(Entity self, GravityCapabilityImpl comp, Vec3 up, Vec3 movement) {
        org.joml.Quaternionf frame = comp.getVisualRotation();
        Vec3 local = RotationUtil.vecWorldToPlayer(movement, frame);
        double dx = local.x;
        double dz = local.z;

        while (dx != 0.0 && gravityunbound$edgeUnsupported(self, comp, up, frame, dx, local.y, 0.0)) {
            dx = gravityunbound$shrinkToward(dx);
        }
        while (dz != 0.0 && gravityunbound$edgeUnsupported(self, comp, up, frame, 0.0, local.y, dz)) {
            dz = gravityunbound$shrinkToward(dz);
        }
        while (dx != 0.0 && dz != 0.0 && gravityunbound$edgeUnsupported(self, comp, up, frame, dx, local.y, dz)) {
            dx = gravityunbound$shrinkToward(dx);
            dz = gravityunbound$shrinkToward(dz);
        }
        return RotationUtil.vecPlayerToWorld(new Vec3(dx, local.y, dz), frame);
    }

    @org.spongepowered.asm.mixin.Unique
    private static double gravityunbound$shrinkToward(double v) {
        if (v < 0.05 && v >= -0.05) {
            return 0.0;
        }
        return v > 0.0 ? v - 0.05 : v + 0.05;
    }

    /**
     * True when the destination has NO ground within step height below.
     * A RAYCAST from the destination feet, straight down the frame — the
     * earlier capsule-fit test kept "finding support" on the cliff's SIDE
     * face through the fat bottom sphere (supported up to ~radius past the
     * edge, by which point the grounded flag had already dropped and the
     * guard disengaged: "I still fall right off while crouching").
     */
    @org.spongepowered.asm.mixin.Unique
    private boolean gravityunbound$edgeUnsupported(
        Entity self, GravityCapabilityImpl comp, Vec3 up, org.joml.Quaternionf frame,
        double localX, double localY, double localZ
    ) {
        Vec3 offset = RotationUtil.vecPlayerToWorld(new Vec3(localX, 0.0, localZ), frame);
        Vec3 from = self.position().add(offset).add(up.scale(0.05));
        net.minecraft.world.phys.BlockHitResult hit = self.level().clip(new net.minecraft.world.level.ClipContext(
            from,
            from.subtract(up.scale(0.05 + 0.6)),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            self
        ));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK;
    }

    @WrapOperation(method = "Lnet/minecraft/world/entity/Entity;makeBoundingBox()Lnet/minecraft/world/phys/AABB;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityDimensions;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"))
    private AABB wrapOperation_makeBoundingBox(EntityDimensions dimensions, Vec3 pos, Operation<AABB> original) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return original.call(dimensions, pos);
        }

        if (comp.useCapsuleCollision()) {
            // passive envelope of the capsule (block collision does not use it)
            return net.camacraft.gravityunbound.util.CapsuleCollider.makeEnvelope(
                pos, comp.getUpVector(), dimensions.width, dimensions.height
            );
        }

        AABB box = dimensions.makeBoundingBox(0, 0, 0);
        if (comp.getCurrGravityDirection().getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            box = box.move(0.0D, -1.0E-6D, 0.0D);
        }
        return RotationUtil.boxPlayerToWorld(box, comp.getCurrGravityDirection()).move(pos);
    }

    // Pose-fit checks (can I stand up / crouch here?) test the loose
    // world-aligned envelope in capsule mode, which pokes into the very
    // floor the player stands on the moment the frame tilts — vanilla then
    // decided NO pose fits and forcibly cancelled crouching (worst near
    // block edges, where field blends tilt the frame). Test the actual
    // capsule instead.
    @Inject(method = "canEnterPose", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$capsuleCanEnterPose(Pose pose, CallbackInfoReturnable<Boolean> cir) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null || !comp.useCapsuleCollision()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        EntityDimensions dim = self.getDimensions(pose);
        cir.setReturnValue(net.camacraft.gravityunbound.util.CapsuleCollider.fits(
            self, this.position(), comp.getUpVector(), dim.width, dim.height
        ));
    }

    @Inject(method = "getBoundingBoxForPose", at = @At("RETURN"), cancellable = true)
    private void getBoundingBoxForPose(Pose pose, CallbackInfoReturnable<AABB> cir)
    {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;

        if (comp.useCapsuleCollision()) {
            EntityDimensions dim = ((Entity) (Object) this).getDimensions(pose);
            cir.setReturnValue(net.camacraft.gravityunbound.util.CapsuleCollider.makeEnvelope(
                this.position(), comp.getUpVector(), dim.width, dim.height
            ));
            return;
        }

        AABB aabb = cir.getReturnValue();
        if (comp.getCurrGravityDirection().getAxisDirection() == Direction.AxisDirection.POSITIVE)
        {
            aabb = aabb.move(0.0D, -1.0E-6D, 0.0D);
        }
        cir.setReturnValue(RotationUtil.boxPlayerToWorld(aabb, comp.getCurrGravityDirection()));
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void inject_getRotationVector(CallbackInfoReturnable<Vec3> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;

        // look/aim follows the visual frame so it always matches the camera
        cir.setReturnValue(RotationUtil.vecPlayerToWorld(cir.getReturnValue(), comp.getVisualRotation()));
    }

    /**
     * RENDER-TIME VIEW VECTOR. calculateViewVector above applies the TICK
     * frame — right for gameplay. But the crosshair pick runs every frame
     * with a partial tick, from an eye position that follows the RENDER
     * frame (getEyePosition(pt) below) and, on a Valkyrien Skies ship,
     * against blocks VS raycasts at the ship's DRAWN pose. A direction one
     * tick behind the eye and the deck sent the ray to a neighboring block
     * that shifted every tick: the block outline hopping between blocks on
     * a spinning ship, worse the faster it spun. Re-express the direction
     * through the render frame for sub-tick queries; whole-tick queries
     * (pt = 1, gameplay raycasts shared with the server) keep the tick
     * frame so both sides still agree.
     */
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void gravityunbound$renderViewVector(float tickDelta, CallbackInfoReturnable<Vec3> cir) {
        if (tickDelta >= 1.0F || !this.level.isClientSide()) {
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null || comp.isRenderDefault()) {
            return;
        }
        Vec3 local = RotationUtil.vecWorldToPlayer(cir.getReturnValue(), comp.getVisualRotation());
        cir.setReturnValue(RotationUtil.vecPlayerToWorld(local, comp.getRenderRotation(tickDelta)));
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getBlockPosBelowThatAffectsMyMovement()Lnet/minecraft/core/BlockPos;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getVelocityAffectingPos(CallbackInfoReturnable<BlockPos> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;

        cir.setReturnValue(BlockPos.containing(
            this.position.add(RotationUtil.vecPlayerToWorld(0.0D, -0.5000001D, 0.0D, gravityunbound$movementRotation(comp)))
        ));
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getEyePosition()Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getEyePos(CallbackInfoReturnable<Vec3> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;

        // eyes follow the visual frame so raycasts start where the camera is
        cir.setReturnValue(RotationUtil.vecPlayerToWorld(0.0D, this.eyeHeight, 0.0D, comp.getVisualRotation()).add(this.position));
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getCameraPosVec(float tickDelta, CallbackInfoReturnable<Vec3> cir) {
        // RENDER gate, not the physics one: on a spinning ship's level deck
        // the tick frame is vanilla while the render frame still carries the
        // drawn deck's sub-tick lead
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null || comp.isRenderDefault()) return;

        Vec3 vec3d = RotationUtil.vecPlayerToWorld(0.0D, this.eyeHeight, 0.0D, comp.getRenderRotation(tickDelta));

        double d = Mth.lerp((double) tickDelta, this.xo, this.getX()) + vec3d.x;
        double e = Mth.lerp((double) tickDelta, this.yo, this.getY()) + vec3d.y;
        double f = Mth.lerp((double) tickDelta, this.zo, this.getZ()) + vec3d.z;
        cir.setReturnValue(new Vec3(d, e, f));
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getLightLevelDependentMagicValue()F",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getBrightnessAtFEyes(CallbackInfoReturnable<Float> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;

        cir.setReturnValue(this.level.hasChunkAt(this.getBlockX(), this.getBlockZ()) ? this.level.getLightLevelDependentMagicValue(BlockPos.containing(this.getEyePosition())) : 0.0F);
    }

    // the movement passed into move() this call, in local and world form, so the
    // post-collide transforms can restore/compare BIT-EXACTLY (vanilla decides
    // onGround and collision flags with exact float comparisons)
    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityunbound$moveLocalArg = Vec3.ZERO;
    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityunbound$moveWorldArg = Vec3.ZERO;

    // transform move vector from local to world (the velocity is local)
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private Vec3 modify_move_Vec3d_0_0(Vec3 vec3d) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return vec3d;
        }

        Vec3 world;
        if (!((Entity) (Object) this instanceof Player) && comp.getSettledCardinal() != null) {
            // preserve the original mod's non-player movement convention at
            // settled cardinal directions (vecEntityToWorld differs from the
            // frame transform for UP gravity)
            world = RotationUtil.vecEntityToWorld(vec3d, comp.getSettledCardinal());
        }
        else {
            // local velocity is interpreted through the continuous visual frame,
            // so gravity pulls along the true (arbitrary-angle) field vector
            world = RotationUtil.vecPlayerToWorld(vec3d, comp.getVisualRotation());
        }

        gravityunbound$moveLocalArg = vec3d;
        gravityunbound$moveWorldArg = world;
        return world;
    }

    // transform the argument vector back to local coordinate: the argument is
    // unchanged between HEAD and here, so restore the stashed local exactly
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V",
            ordinal = 0
        ),
        ordinal = 0,
        argsOnly = true
    )
    private Vec3 modify_move_Vec3d_0_1(Vec3 vec3d) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return vec3d;
        }

        // ALL entities restore the stashed local vector BIT-EXACTLY (vanilla
        // decides the collision flags with exact float comparisons). The old
        // legacy conversion for non-players re-rotated through the frame,
        // whose noise (and Valkyrien Skies' ship adjustments) registered as
        // collisions every tick — phantom onGround, per-tick velocity
        // zeroing: mobs sliding/bouncing on plated ships.
        if (!vec3d.equals(gravityunbound$moveWorldArg)) {
            // vanilla modified the movement between HEAD and here (e.g. cobweb
            // stuck-multiplier); refresh the stash from the modified vector
            gravityunbound$moveWorldArg = vec3d;
            gravityunbound$moveLocalArg = gravityunbound$worldToLocal(vec3d, comp);
        }

        return gravityunbound$moveLocalArg;
    }

    // the block the entity stands on (frame-following) is slippery — the
    // static pins must not fight ice/slime physics
    @org.spongepowered.asm.mixin.Unique
    private boolean gravityunbound$onSlipperyGround(Entity self) {
        BlockPos below = this.getBlockPosBelowThatAffectsMyMovement();
        return self.level().getBlockState(below).getFriction(self.level(), below, self) > 0.7f;
    }

    // world->local through the SAME convention the HEAD conversion used:
    // settled non-players move in the ENTITY convention (differs from the
    // player convention for UP gravity), everyone else in the visual frame
    @org.spongepowered.asm.mixin.Unique
    private Vec3 gravityunbound$worldToLocal(Vec3 world, GravityCapabilityImpl comp) {
        if (!((Entity) (Object) this instanceof Player)) {
            Direction settled = comp.getSettledCardinal();
            if (settled != null) {
                return RotationUtil.vecWorldToEntity(world, settled);
            }
        }
        return RotationUtil.vecWorldToPlayer(world, comp.getVisualRotation());
    }

    // Transform the local variable (result from collide()) to local coordinate.
    //
    // ANCHOR IS CRITICAL: this must happen at the FIRST profiler.pop() — after
    // setPos() consumed the world-space result, but BEFORE vanilla computes
    // horizontalCollision/verticalCollision/onGround by comparing this variable
    // against the (already local) movement argument. The fork anchored this at
    // pop ordinal 1, which on 1.20.1 is after those comparisons — so vanilla
    // compared a LOCAL vector against a WORLD vector, making verticalCollision
    // (onGround!) and horizontalCollision fire almost every tick under any
    // non-down gravity: mid-air jumping, elytra/creative flight cancelling,
    // missing ground friction, and velocity being zeroed while walking.
    //
    // Computed as originalLocal + rotate(delta) so an untouched movement comes
    // back BIT-IDENTICAL to what went in (the comparisons are exact).
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;pop()V",
            ordinal = 0
        ),
        ordinal = 1
    )
    private Vec3 modify_move_Vec3d_1(Vec3 vec3d) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return vec3d;
        }

        // the collide result must come back bit-identical on untouched axes
        // and semantically per-axis on touched ones — for ALL entities (the
        // legacy full re-conversion for mobs registered Valkyrien Skies' ship
        // adjustments and round-trip noise as collisions every tick)
        if (vec3d.equals(gravityunbound$moveWorldArg)) {
            return gravityunbound$moveLocalArg;
        }

        Vec3 deltaWorld = vec3d.subtract(gravityunbound$moveWorldArg);
        Vec3 deltaLocal = gravityunbound$worldToLocal(deltaWorld, comp);
        Vec3 local = gravityunbound$moveLocalArg;
        return new Vec3(
            gravityunbound$resolveAxis(local.x, deltaLocal.x),
            gravityunbound$resolveAxis(local.y, deltaLocal.y),
            gravityunbound$resolveAxis(local.z, deltaLocal.z)
        );
    }

    // Vanilla derives horizontalCollision/verticalCollision/onGround by comparing
    // this vector against the (local) movement argument — exact double != on the
    // vertical axis (any difference also zeroes vertical velocity through
    // updateEntityAfterFallOn), 1e-7 tolerance horizontally (Mth.equal) — and
    // zeroes the velocity of every "collided" axis. So an axis may only differ
    // when the capsule genuinely held the player back along it: quaternion
    // round-trip noise and corrections pushing ALONG the movement (rolling over
    // a convex edge) must pass through bit-exactly, otherwise walking fights
    // phantom collisions every tick and jumps die against walls.
    @org.spongepowered.asm.mixin.Unique
    private static double gravityunbound$resolveAxis(double intended, double delta) {
        if (Math.abs(delta) < 1.0E-6) {
            return intended;
        }
        if (delta * intended >= 0.0) {
            return intended;
        }
        // A GLANCING clamp is not a collision. With the frame tilted a few
        // degrees off a flat surface (field blends near plate edges, ships,
        // any mid-transition frame), every walking tick has sin(tilt)*speed
        // legitimately shaved off an axis by the ground plane — reporting
        // that as a collision made vanilla zero the axis velocity every
        // tick: "catching on nothing" on flat ground, getting stuck on
        // convex edges until backing out, killed ice sliding. A real
        // obstacle eats a large fraction of the intended motion.
        if (Math.abs(delta) < 0.1 * Math.abs(intended)) {
            return intended;
        }
        return intended + delta;
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getOnPosLegacy()Lnet/minecraft/core/BlockPos;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getLandingPos(CallbackInfoReturnable<BlockPos> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;
        BlockPos blockPos = BlockPos.containing(RotationUtil.vecPlayerToWorld(0.0D, -0.20000000298023224D, 0.0D, gravityunbound$movementRotation(comp)).add(this.position));
        cir.setReturnValue(blockPos);
    }

    // The generic "block I stand on" probe must follow the gravity frame too.
    // Vanilla computes it straight world-down (via mainSupportingBlockPos or a
    // y-offset), so standing on a wall/ceiling face it lands in AIR — which
    // silenced footstep sounds on gravity-core cubes (step sounds require the
    // getOnPos() block to be non-air; plated cubes only worked by accident
    // because the feet cell contains the non-air plating block itself).
    // The vanilla epsilon offset (1e-5) is smaller than the capsule's skin
    // gap, so a minimum of 0.05 keeps the probe inside the supporting block.
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;getOnPos(F)Lnet/minecraft/core/BlockPos;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_getOnPos(float offset, CallbackInfoReturnable<BlockPos> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;
        double depth = Math.max(offset, 0.05F);
        cir.setReturnValue(BlockPos.containing(
            RotationUtil.vecPlayerToWorld(0.0D, -depth, 0.0D, gravityunbound$movementRotation(comp)).add(this.position)
        ));
    }

    // transform the argument to local coordinate
    @ModifyVariable(
        method = "collide",
        at = @At(
            value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            ordinal = 0
        ),
        ordinal = 0
    )
    private Vec3 modify_adjustMovementForCollisions_Vec3d_0(Vec3 vec3d) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return vec3d;
        }

        return RotationUtil.vecWorldToPlayer(vec3d, comp.getCurrentRotation());
    }

    // transform the result to world coordinate
    // the input to Entity.collideBoundingBox will be in local coord
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void inject_adjustMovementForCollisions(CallbackInfoReturnable<Vec3> cir) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;

        cir.setReturnValue(RotationUtil.vecPlayerToWorld(cir.getReturnValue(), comp.getCurrentRotation()));
    }

    // the argument was transformed to local coord,
    // but bounding box stretch needs world coord
    @WrapOperation(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;expandTowards(DDD)Lnet/minecraft/world/phys/AABB;"
            )
    )
    private AABB redirect_adjustMovementForCollisions_stretch_1(AABB instance, double x, double y, double z, Operation<AABB> original) {
        Vec3 rotate = RotationUtil.vecPlayerToWorld(
            new Vec3(x, y, z), GravityChangerAPI.getGravityRotation((Entity) (Object) this)
        );

        return original.call(instance, rotate.x, rotate.y, rotate.z);
    }

    // the argument was transformed to local coord,
    // but bounding box move needs world coord
    @ModifyArg(
            method = "collide",
            at = @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/phys/AABB;move(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"
            )
        )
    private Vec3 redirect_adjustMovementForCollisions_offset_0(Vec3 rotate) {
        return RotationUtil.vecPlayerToWorld(rotate, GravityChangerAPI.getGravityRotation((Entity) (Object) this));
    }

    // Entity.collideBoundingBox is inputed with local coord, transform it to world coord
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private static Vec3 modify_adjustMovementForCollisions_Vec3d_0(Vec3 vec3d, Entity entity) {
        if (entity == null) {
            return vec3d;
        }

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isDefault()) {
            return vec3d;
        }

        return RotationUtil.vecPlayerToWorld(vec3d, comp.getCurrentRotation());
    }

    // transform back to local coord
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void inject_adjustMovementForCollisions(Entity entity, Vec3 movement, AABB entityBoundingBox, Level world, List<VoxelShape> collisions, CallbackInfoReturnable<Vec3> cir) {
        if (entity == null) return;

        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isDefault()) return;

        cir.setReturnValue(RotationUtil.vecWorldToPlayer(cir.getReturnValue(), comp.getCurrentRotation()));
    }

    @Redirect(
        method = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideWithShapes(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            ordinal = 0
        )
    )
    private static Vec3 redirect_adjustMovementForCollisions_adjustMovementForCollisions_0(Vec3 movement, AABB entityBoundingBox, List<VoxelShape> collisions, Entity entity) {
        GravityCapabilityImpl comp = entity == null ? null : GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isDefault()) {
            return collideWithShapes(movement, entityBoundingBox, collisions);
        }

        // Ordered per-axis resolution is only possible when the local axes map onto
        // world axes, i.e. at settled cardinal directions.
        Direction settled = comp.getSettledCardinal();
        if (settled == null) {
            return collideWithShapes(movement, entityBoundingBox, collisions);
        }

        Quaternionf rotation = comp.getCurrentRotation();

        Vec3 playerMovement = RotationUtil.vecWorldToPlayer(movement, rotation);
        double playerMovementX = playerMovement.x;
        double playerMovementY = playerMovement.y;
        double playerMovementZ = playerMovement.z;
        Direction directionX = RotationUtil.dirPlayerToWorld(Direction.EAST, settled);
        Direction directionY = RotationUtil.dirPlayerToWorld(Direction.UP, settled);
        Direction directionZ = RotationUtil.dirPlayerToWorld(Direction.SOUTH, settled);
        if (playerMovementY != 0.0D) {
            playerMovementY = Shapes.collide(directionY.getAxis(), entityBoundingBox, collisions, playerMovementY * directionY.getAxisDirection().getStep()) * directionY.getAxisDirection().getStep();
            if (playerMovementY != 0.0D) {
                entityBoundingBox = entityBoundingBox.move(RotationUtil.vecPlayerToWorld(0.0D, playerMovementY, 0.0D, rotation));
            }
        }

        boolean isZLargerThanX = Math.abs(playerMovementX) < Math.abs(playerMovementZ);
        if (isZLargerThanX && playerMovementZ != 0.0D) {
            playerMovementZ = Shapes.collide(directionZ.getAxis(), entityBoundingBox, collisions, playerMovementZ * directionZ.getAxisDirection().getStep()) * directionZ.getAxisDirection().getStep();
            if (playerMovementZ != 0.0D) {
                entityBoundingBox = entityBoundingBox.move(RotationUtil.vecPlayerToWorld(0.0D, 0.0D, playerMovementZ, rotation));
            }
        }

        if (playerMovementX != 0.0D) {
            playerMovementX = Shapes.collide(directionX.getAxis(), entityBoundingBox, collisions, playerMovementX * directionX.getAxisDirection().getStep()) * directionX.getAxisDirection().getStep();
            if (!isZLargerThanX && playerMovementX != 0.0D) {
                entityBoundingBox = entityBoundingBox.move(RotationUtil.vecPlayerToWorld(playerMovementX, 0.0D, 0.0D, rotation));
            }
        }

        if (!isZLargerThanX && playerMovementZ != 0.0D) {
            playerMovementZ = Shapes.collide(directionZ.getAxis(), entityBoundingBox, collisions, playerMovementZ * directionZ.getAxisDirection().getStep()) * directionZ.getAxisDirection().getStep();
        }

        return RotationUtil.vecPlayerToWorld(playerMovementX, playerMovementY, playerMovementZ, rotation);
    }

    // In capsule mode the entity's stored AABB is a loose envelope, which would
    // cause false suffocation on slopes/ships; the capsule already prevents the
    // player from ending up inside blocks.
    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void inject_isInWall_capsule(CallbackInfoReturnable<Boolean> cir) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null) {
            return;
        }
        if (comp.useCapsuleCollision()) {
            cir.setReturnValue(false);
            return;
        }
        // SERVER-side players under any gravity influence: the server's
        // frame trails the client's by a packet, and an accepted position on
        // a plated face tested against a not-yet-rotated vanilla box read as
        // "inside a wall" — suffocation for standing where the client's
        // capsule legitimately stands. The client keeps vanilla's own answer.
        if (!this.level.isClientSide() && (Object) this instanceof Player
            && comp.isMovementClientAuthoritative()) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(
        method = "isInWall",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;ofSize(Lnet/minecraft/world/phys/Vec3;DDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        )
    )
    private AABB modify_isInsideWall_of_0(Vec3 vec3, double x, double y, double z, Operation<AABB> original) {
        Vec3 rotate = RotationUtil.maskPlayerToWorld(
            new Vec3(x, y, z), GravityChangerAPI.getGravityRotation((Entity) (Object) this)
        );
        return original.call(vec3, rotate.x, rotate.y, rotate.z);
    }

    @ModifyArg(
        method = "getDirection",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/Direction;fromYRot(D)Lnet/minecraft/core/Direction;"
        )
    )
    private double redirect_getHorizontalFacing_getYaw_0(double rotation) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return rotation;
        }

        return RotationUtil.rotPlayerToWorld((float) rotation, this.getXRot(), comp.getVisualRotation()).x;
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;spawnSprintParticle()V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_spawnSprintingParticles(CallbackInfo ci) {
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) return;
        Quaternionf rotation = gravityunbound$movementRotation(comp);

        ci.cancel();

        Vec3 floorPos = this.position().subtract(RotationUtil.vecPlayerToWorld(0.0D, 0.20000000298023224D, 0.0D, rotation));

        BlockPos blockPos = BlockPos.containing(floorPos);
        BlockState blockState = this.level.getBlockState(blockPos);
        if (blockState.getRenderShape() != RenderShape.INVISIBLE) {
            Vec3 particlePos = this.position().add(RotationUtil.vecPlayerToWorld((this.random.nextDouble() - 0.5D) * (double) this.dimensions.width, 0.1D, (this.random.nextDouble() - 0.5D) * (double) this.dimensions.width, rotation));
            Vec3 playerVelocity = this.getDeltaMovement();
            Vec3 particleVelocity = RotationUtil.vecPlayerToWorld(playerVelocity.x * -4.0D, 1.5D, playerVelocity.z * -4.0D, rotation);
            this.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, blockState), particlePos.x, particlePos.y, particlePos.z, particleVelocity.x, particleVelocity.y, particleVelocity.z);
        }
    }


    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_pushAwayFrom(Entity entity, CallbackInfo ci) {
        GravityCapabilityImpl selfComp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        GravityCapabilityImpl otherComp = GravityChangerAPI.getGravityComponentOrNull(entity);

        boolean selfDefault = selfComp == null || selfComp.isDefault();
        boolean otherDefault = otherComp == null || otherComp.isDefault();
        if (selfDefault && otherDefault) return;

        ci.cancel();

        Quaternionf selfRotation = GravityChangerAPI.getGravityRotation((Entity) (Object) this);
        Quaternionf otherRotation = GravityChangerAPI.getGravityRotation(entity);

        if (!this.isPassengerOfSameVehicle(entity)) {
            if (!entity.noPhysics && !this.noPhysics) {
                Vec3 entityOffset = entity.getBoundingBox().getCenter().subtract(this.getBoundingBox().getCenter());

                {
                    Vec3 playerEntityOffset = RotationUtil.vecWorldToPlayer(entityOffset, selfRotation);
                    double dx = playerEntityOffset.x;
                    double dz = playerEntityOffset.z;
                    double f = Mth.absMax(dx, dz);
                    if (f >= 0.009999999776482582D) {
                        f = Math.sqrt(f);
                        dx /= f;
                        dz /= f;
                        double g = 1.0D / f;
                        if (g > 1.0D) {
                            g = 1.0D;
                        }

                        dx *= g;
                        dz *= g;
                        dx *= 0.05000000074505806D;
                        dz *= 0.05000000074505806D;
                        if (!this.isVehicle()) {
                            this.push(-dx, 0.0D, -dz);
                        }
                    }
                }

                {
                    Vec3 entityEntityOffset = RotationUtil.vecWorldToPlayer(entityOffset, otherRotation);
                    double dx = entityEntityOffset.x;
                    double dz = entityEntityOffset.z;
                    double f = Mth.absMax(dx, dz);
                    if (f >= 0.009999999776482582D) {
                        f = Math.sqrt(f);
                        dx /= f;
                        dz /= f;
                        double g = 1.0D / f;
                        if (g > 1.0D) {
                            g = 1.0D;
                        }

                        dx *= g;
                        dz *= g;
                        dx *= 0.05000000074505806D;
                        dz *= 0.05000000074505806D;
                        if (!entity.isVehicle()) {
                            entity.push(dx, 0.0D, dz);
                        }
                    }
                }
            }
        }
    }

    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;checkBelowWorld()V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_attemptTickInVoid(CallbackInfo ci) {
        Entity this_ = (Entity) (Object) this;

        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this_);
        if (GravityConfig.voidDamageAboveWorld.get() &&
            this.getY() > (double) (this.level.getMaxBuildHeight() + 256) &&
            gravityDirection == Direction.UP
        ) {
            this.onBelowWorld();
            ci.cancel();
            return;
        }

        if (GravityConfig.voidDamageOnHorizontalFallTooFar.get() &&
            gravityDirection.getAxis() != Direction.Axis.Y &&
            fallDistance > 1024
        ) {
            this.onBelowWorld();
            ci.cancel();
            return;
        }
    }

    @WrapOperation(
        method = "isFree(DDD)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        )
    )
    private AABB redirect_doesNotCollide_offset_0(AABB instance, double x, double y, double z, Operation<AABB> original) {
        Vec3 rotate = RotationUtil.vecPlayerToWorld(
            new Vec3(x, y, z), GravityChangerAPI.getGravityRotation((Entity) (Object) this)
        );
        return original.call(instance, rotate.x, rotate.y, rotate.z);
    }


    // Fluid currents: the flow push is accumulated in WORLD space and added
    // straight onto deltaMovement — which this mod interprets in the
    // entity's LOCAL frame. Unconverted, a water current pushes a rotated
    // player along the wrong axis entirely (e.g. into the stream bed).
    // The wrapped call is `setDeltaMovement(getDeltaMovement().add(flow))`,
    // so the world-space flow is recovered as (arg - currentDm) and
    // re-expressed through the same frame move() uses.
    //
    // TARGET: Forge's fluid-API patch rewrites Entity's fluid handling — the
    // vanilla-named updateFluidHeightAndDoFluidPushing(TagKey,double) is
    // reduced to a pure query shim, and the real push logic (including the
    // setDeltaMovement call) lives in the per-fluid-type BiConsumer lambda
    // of the Forge-added updateFluidHeightAndDoFluidPushing(Predicate)
    // overload. The synthetic lambda name is stable for a given Forge build
    // (verified against 1.20.1-47.4.16 bytecode); require = 0 so a future
    // Forge bump that renumbers lambdas degrades to vanilla behavior instead
    // of crashing the launch.
    @WrapOperation(
        method = "lambda$updateFluidHeightAndDoFluidPushing$29(Lnet/minecraftforge/fluids/FluidType;Lnet/minecraft/world/entity/Entity$FluidCalcs;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"
        ),
        require = 0
    )
    private void gravityunbound$rotateFluidPush(Entity self, Vec3 newDm, Operation<Void> original) {
        // world-frame projectiles: vanilla's world-space push is already right
        if (self instanceof net.minecraft.world.entity.projectile.Projectile) {
            original.call(self, newDm);
            return;
        }
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            original.call(self, newDm);
            return;
        }
        Vec3 localDm = this.getDeltaMovement();
        Vec3 worldFlow = newDm.subtract(localDm);
        original.call(self, localDm.add(gravityunbound$worldToLocal(worldFlow, comp)));
    }

    // Server motion packets (ClientboundSetEntityMotion, spawn velocities
    // applied through lerpMotion) carry WORLD-space velocity — but this mod
    // interprets deltaMovement in the entity's LOCAL frame. Writing the
    // packet values in raw made every remote projectile under rotated
    // gravity dead-reckon its flight along the wrong axes between position
    // updates, so arrows/eggs/pearls/tridents visibly rubber-banded back and
    // forth as each position packet yanked them back to the server's truth.
    @Inject(method = "lerpMotion", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$lerpMotionToLocal(double x, double y, double z, CallbackInfo ci) {
        // PROJECTILES ARE WORLD-FRAME: they integrate position by raw world
        // addition (no move()) and their own mixins apply gravity along the
        // rotated axis in world space — their deltaMovement IS world-space.
        // Converting their packet velocity to "local" corrupted it whenever a
        // field had rotated their frame (the violent trident/arrow desync).
        if ((Object) this instanceof net.minecraft.world.entity.projectile.Projectile) {
            return;
        }
        GravityCapabilityImpl comp = gravityunbound$comp();
        if (comp == null) {
            return;
        }
        ci.cancel();
        ((Entity) (Object) this).setDeltaMovement(
            gravityunbound$worldToLocal(new Vec3(x, y, z), comp)
        );
    }

    @Shadow
    protected abstract void onInsideBlock(BlockState state);

    // In capsule mode the stored AABB is a loose world-aligned ENVELOPE of the
    // tilted capsule — up to a block larger than the body in every direction.
    // Vanilla's inside-block check (fire damage, cobwebs, berry bushes, powder
    // snow, portals) iterates that envelope, so a tilted player touched blocks
    // they were nowhere near. Re-run the same check but only visit cells the
    // capsule's spheres actually overlap.
    @Inject(method = "checkInsideBlocks", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$capsuleCheckInsideBlocks(CallbackInfo ci) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull((Entity) (Object) this);
        if (comp == null || !comp.useCapsuleCollision()) {
            return;
        }
        ci.cancel();

        Entity self = (Entity) (Object) this;
        AABB aabb = this.getBoundingBox();
        BlockPos min = BlockPos.containing(aabb.minX + 1.0E-7D, aabb.minY + 1.0E-7D, aabb.minZ + 1.0E-7D);
        BlockPos max = BlockPos.containing(aabb.maxX - 1.0E-7D, aabb.maxY - 1.0E-7D, aabb.maxZ - 1.0E-7D);
        if (!this.level.hasChunksAt(min, max)) {
            return;
        }

        double radius = net.camacraft.gravityunbound.util.CapsuleCollider.capsuleRadius(self);
        double height = net.camacraft.gravityunbound.util.CapsuleCollider.capsuleHeight(self, radius);
        double[] offsets = net.camacraft.gravityunbound.util.CapsuleCollider.sphereOffsets(height, radius);
        Vec3 up = comp.getUpVector();
        Vec3 feet = this.position();
        double radiusSq = radius * radius;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    boolean touched = false;
                    for (double offset : offsets) {
                        double cx = feet.x + up.x * offset;
                        double cy = feet.y + up.y * offset;
                        double cz = feet.z + up.z * offset;
                        double dx = cx - Mth.clamp(cx, x, x + 1.0);
                        double dy = cy - Mth.clamp(cy, y, y + 1.0);
                        double dz = cz - Mth.clamp(cz, z, z + 1.0);
                        if (dx * dx + dy * dy + dz * dz < radiusSq) {
                            touched = true;
                            break;
                        }
                    }
                    if (!touched) {
                        continue;
                    }

                    cursor.set(x, y, z);
                    BlockState blockState = this.level.getBlockState(cursor);
                    try {
                        blockState.entityInside(this.level, cursor, self);
                        this.onInsideBlock(blockState);
                    }
                    catch (Throwable throwable) {
                        net.minecraft.CrashReport crashReport =
                            net.minecraft.CrashReport.forThrowable(throwable, "Colliding entity with block");
                        net.minecraft.CrashReportCategory category =
                            crashReport.addCategory("Block being collided with");
                        net.minecraft.CrashReportCategory.populateBlockDetails(
                            category, this.level, cursor, blockState
                        );
                        throw new net.minecraft.ReportedException(crashReport);
                    }
                }
            }
        }
    }

    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;updateFluidOnEyes()V",
        at = @At(
            value = "STORE"
        ),
        ordinal = 0
    )
    private double submergedInWaterEyeFix(double d) {
        return this.getEyePosition().y();
    }

    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/Entity;updateFluidOnEyes()V",
        at = @At(
            value = "STORE"
        ),
        ordinal = 0
    )
    private BlockPos submergedInWaterPosFix(BlockPos blockpos) {
        return BlockPos.containing(this.getEyePosition());
    }


}

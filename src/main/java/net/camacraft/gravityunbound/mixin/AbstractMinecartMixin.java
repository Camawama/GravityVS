package net.camacraft.gravityunbound.mixin;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.sticky.StickyRailBlock;
import net.camacraft.gravityunbound.sticky.StickyRailMinecartLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Three jobs:
 * <ul>
 *   <li>Scale the cart's per-tick gravity by the entity's gravity strength
 *       (the original {@code @ModifyArg} below, active on the vanilla
 *       path).</li>
 *   <li>Drive the gravity capability's per-tick update: minecarts override
 *       {@code Entity.tick} without calling super, so {@code EntityMixin}'s
 *       injection never fires for them (queued gravity effects were never
 *       consumed).</li>
 *   <li>Sticky rails: when the cart stands on a {@link StickyRailBlock}, run
 *       a LOCAL-FRAME port of the vanilla 1.20.1 server tick (gravity,
 *       moveAlongTrack, yaw, pushing, fluid state) instead of vanilla's, with
 *       the cart's gravity pinned to the rail's frame each tick. The track
 *       math is vanilla's, transliterated into the rail cardinal frame via
 *       {@link StickyRailMinecartLogic}; deltaMovement stays in the cart's
 *       LOCAL frame throughout (this mod's minecart convention), which equals
 *       the rail frame once the gravity pin settles. The vanilla client
 *       branch (lerp to server state) is left untouched — minecarts are
 *       server-controlled, and the render dispatcher already poses them by
 *       their gravity frame with the local yaw computed here.</li>
 * </ul>
 * Skipped vanilla behavior on sticky rails (deferred with the powered rail
 * variants): powered boost/brake, activator handling,
 * {@code onMinecartPass}, and the furnace minecart's push (its
 * {@code moveAlongTrack} override is bypassed).
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin extends Entity {

    @Shadow private boolean flipped;
    @Shadow private boolean onRails;

    @Shadow protected abstract void applyNaturalSlowdown();

    public AbstractMinecartMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @ModifyArg(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"
        ),
        index = 1
    )
    private double multiplyGravity(double x) {
        return x * GravityChangerAPI.getGravityStrength(this);
    }

    // ------------------------------------------------------------------
    // sticky rail riding
    // ------------------------------------------------------------------

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$stickyRailTick(CallbackInfo ci) {
        // AbstractMinecart.tick overrides Entity.tick WITHOUT calling super,
        // so EntityMixin's Entity.tick HEAD injection — the only driver of
        // the gravity capability's per-tick update — never fires for
        // minecarts. Drive it here on both sides (like EntityMixin does for
        // everything else): the server consumes queued direction effects
        // (the rail pin below) and syncs; the client advances the visual
        // rotation so the rotated cart renders smoothly.
        this.getCapability(net.camacraft.gravityunbound.capabilities.GravityCapabilities.GRAVITY)
            .ifPresent(net.camacraft.gravityunbound.capabilities.IGravityCapability::tick);

        // the client branch of the vanilla tick only lerps toward server
        // state — leave it alone (carts are server-controlled)
        if (this.level().isClientSide) {
            return;
        }
        AbstractMinecart self = (AbstractMinecart) (Object) this;
        if (!self.canUseRail()) {
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(self);
        if (comp == null) {
            return;
        }
        StickyRailMinecartLogic.RailHit hit = StickyRailMinecartLogic.findStickyRail(
            this.level(), this.position(), comp.getCurrGravityDirection()
        );
        if (hit == null) {
            return;
        }
        ci.cancel();
        gravityunbound$tickOnStickyRail(self, comp, hit);
    }

    /**
     * The server branch of vanilla {@code AbstractMinecart.tick} (1.20.1,
     * decompiled Forge 47.4.16 sources), transliterated for a cart on a
     * sticky rail. Structure and constants match vanilla line for line; the
     * deltas are frame conversions and the sticky-rail moveAlongTrack.
     */
    private void gravityunbound$tickOnStickyRail(
        AbstractMinecart self, GravityCapabilityImpl comp, StickyRailMinecartLogic.RailHit hit
    ) {
        if (self.getHurtTime() > 0) {
            self.setHurtTime(self.getHurtTime() - 1);
        }
        if (self.getDamage() > 0.0F) {
            self.setDamage(self.getDamage() - 1.0F);
        }

        this.checkBelowWorld();
        this.handleNetherPortal();

        // hold the cart's gravity in the rail's frame (must out-prioritize
        // every field source so nothing yanks the cart off the track)
        StickyRailMinecartLogic.applyGravityPin(comp, hit.bottom());

        // vanilla gravity: dm is LOCAL-frame, so straight down in local
        // coordinates — pulls toward the cart's current gravity. The
        // strength scale mirrors the @ModifyArg on the vanilla path.
        if (!this.isNoGravity()) {
            double gravity = this.isInWater() ? -0.005 : -0.04;
            gravity *= GravityChangerAPI.getGravityStrength(self);
            this.setDeltaMovement(this.getDeltaMovement().add(0.0, gravity, 0.0));
        }

        this.onRails = true;
        gravityunbound$moveAlongStickyTrack(self, comp, hit.pos(), hit.state(), hit.bottom());
        // (activator rail handling deferred with the powered variants)

        this.checkInsideBlocks();
        this.setXRot(0.0F);

        // vanilla derives yaw from the world position delta; under a rotated
        // frame the yaw is LOCAL, so convert the delta into the cart's frame
        Vec3 worldDelta = new Vec3(this.xo - this.getX(), this.yo - this.getY(), this.zo - this.getZ());
        Vec3 localDelta = StickyRailMinecartLogic.worldToCartLocal(comp, worldDelta);
        if (localDelta.x * localDelta.x + localDelta.z * localDelta.z > 0.001) {
            this.setYRot((float) (Mth.atan2(localDelta.z, localDelta.x) * 180.0 / Math.PI));
            if (this.flipped) {
                this.setYRot(this.getYRot() + 180.0F);
            }
        }

        double yawJump = Mth.wrapDegrees(this.getYRot() - this.yRotO);
        if (yawJump < -170.0 || yawJump >= 170.0) {
            this.setYRot(this.getYRot() + 180.0F);
            this.flipped = !this.flipped;
        }

        this.setRot(this.getYRot(), this.getXRot());

        AABB box;
        if (self.getCollisionHandler() != null) {
            box = self.getCollisionHandler().getMinecartCollisionBox(self);
        } else {
            box = this.getBoundingBox().inflate(0.2F, 0.0, 0.2F);
        }
        if (self.canBeRidden() && this.getDeltaMovement().horizontalDistanceSqr() > 0.01) {
            List<Entity> list = this.level().getEntities(self, box, EntitySelector.pushableBy(self));
            if (!list.isEmpty()) {
                for (int l = 0; l < list.size(); ++l) {
                    Entity entity1 = list.get(l);
                    if (!(entity1 instanceof Player) && !(entity1 instanceof IronGolem)
                        && !(entity1 instanceof AbstractMinecart) && !this.isVehicle() && !entity1.isPassenger()) {
                        entity1.startRiding(self);
                    } else {
                        entity1.push(self);
                    }
                }
            }
        } else {
            for (Entity entity : this.level().getEntities(self, box)) {
                if (!this.hasPassenger(entity) && entity.isPushable() && entity instanceof AbstractMinecart) {
                    entity.push(self);
                }
            }
        }

        this.updateInWaterStateAndDoFluidPushing();
        if (this.isInLava()) {
            this.lavaHurt();
            this.fallDistance *= 0.5F;
        }

        this.firstTick = false;
    }

    /**
     * Vanilla {@code AbstractMinecart.moveAlongTrack} in the rail's cardinal
     * frame. Positions convert world &lt;-&gt; rail frame about the rail
     * cell's center (the lattice maps to itself, so vanilla's cell/floor
     * logic ports verbatim with the rail's own BlockPos); dm converts
     * cart-local &lt;-&gt; rail frame (bit-exact identity once the gravity
     * pin has settled). Powered boost/brake and {@code onMinecartPass} are
     * skipped (no powered sticky rails yet); the slope segments are ported
     * generically from the exit vectors but unreachable while the SHAPE
     * property excludes ascending values.
     */
    private void gravityunbound$moveAlongStickyTrack(
        AbstractMinecart self, GravityCapabilityImpl comp,
        BlockPos railPos, BlockState railState, Direction bottom
    ) {
        this.resetFallDistance();
        Vec3 center = Vec3.atCenterOf(railPos);

        Vec3 local = StickyRailMinecartLogic.worldPosToRail(this.position(), center, bottom);
        double d0 = local.x;
        double d1 = local.y;
        double d2 = local.z;
        Vec3 vec3 = StickyRailMinecartLogic.railTrackPos(this.level(), d0, d1, d2, center, bottom);
        d1 = railPos.getY();

        double slopeAdjustment = self.getSlopeAdjustment();
        if (this.isInWater()) {
            slopeAdjustment *= 0.2;
        }

        Vec3 vec31 = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
        RailShape shape = railState.getValue(StickyRailBlock.SHAPE);
        Pair<Vec3i, Vec3i> exits = StickyRailMinecartLogic.exitsInRailFrame(
            shape, bottom, railState.getValue(StickyRailBlock.SPIN)
        );
        Vec3i exitA = exits.getFirst();
        Vec3i exitB = exits.getSecond();

        // vanilla's per-ascending-shape switch, generalized: thrust toward
        // the descending exit and lift the track plane one cell (flat shapes
        // have no below-exit, so this never fires this milestone)
        Vec3i descending = exitA.getY() < 0 ? exitA : (exitB.getY() < 0 ? exitB : null);
        if (descending != null) {
            vec31 = vec31.add(descending.getX() * slopeAdjustment, 0.0, descending.getZ() * slopeAdjustment);
            ++d1;
        }

        // redirect the horizontal velocity along the track chord
        double d4 = exitB.getX() - exitA.getX();
        double d5 = exitB.getZ() - exitA.getZ();
        double d6 = Math.sqrt(d4 * d4 + d5 * d5);
        double d7 = vec31.x * d4 + vec31.z * d5;
        if (d7 < 0.0) {
            d4 = -d4;
            d5 = -d5;
        }

        double d8 = Math.min(2.0, vec31.horizontalDistance());
        vec31 = new Vec3(d8 * d4 / d6, vec31.y, d8 * d5 / d6);
        this.setDeltaMovement(StickyRailMinecartLogic.railToCart(comp, vec31, bottom));

        // a rider paddling a stopped cart nudges it along (vanilla; both dm
        // vectors are local and share the frame once the rider is pinned too)
        Entity passenger = this.getFirstPassenger();
        if (passenger instanceof Player) {
            Vec3 passengerDm = passenger.getDeltaMovement();
            double d9 = passengerDm.horizontalDistanceSqr();
            double d11 = this.getDeltaMovement().horizontalDistanceSqr();
            if (d9 > 1.0E-4 && d11 < 0.01) {
                this.setDeltaMovement(this.getDeltaMovement().add(passengerDm.x * 0.1, 0.0, passengerDm.z * 0.1));
            }
        }
        // (unpowered powered-rail brake skipped — no powered sticky rails)

        // clamp the position onto the track chord (all rail-frame)
        double d23 = railPos.getX() + 0.5 + exitA.getX() * 0.5;
        double d10 = railPos.getZ() + 0.5 + exitA.getZ() * 0.5;
        double d12 = railPos.getX() + 0.5 + exitB.getX() * 0.5;
        double d13 = railPos.getZ() + 0.5 + exitB.getZ() * 0.5;
        d4 = d12 - d23;
        d5 = d13 - d10;
        double d14;
        if (d4 == 0.0) {
            d14 = d2 - railPos.getZ();
        } else if (d5 == 0.0) {
            d14 = d0 - railPos.getX();
        } else {
            double d15 = d0 - d23;
            double d16 = d2 - d10;
            d14 = (d15 * d4 + d16 * d5) * 2.0;
        }

        d0 = d23 + d4 * d14;
        d2 = d10 + d5 * d14;
        gravityunbound$setPosFromRail(d0, d1, d2, center, bottom);

        // Forge's moveMinecartOnRail: clamps LOCAL dm x/z and Entity.move()s
        // (this mod's move() interprets the vector in the cart's local frame)
        self.moveMinecartOnRail(railPos);

        // climbing snap between the cells of an ascending rail (unreachable
        // while slopes are off, ported for completeness)
        Vec3 afterMove = StickyRailMinecartLogic.worldPosToRail(this.position(), center, bottom);
        if (exitA.getY() != 0
            && Mth.floor(afterMove.x) - railPos.getX() == exitA.getX()
            && Mth.floor(afterMove.z) - railPos.getZ() == exitA.getZ()) {
            gravityunbound$setPosFromRail(afterMove.x, afterMove.y + exitA.getY(), afterMove.z, center, bottom);
        } else if (exitB.getY() != 0
            && Mth.floor(afterMove.x) - railPos.getX() == exitB.getX()
            && Mth.floor(afterMove.z) - railPos.getZ() == exitB.getZ()) {
            gravityunbound$setPosFromRail(afterMove.x, afterMove.y + exitB.getY(), afterMove.z, center, bottom);
        }

        this.applyNaturalSlowdown();

        // re-seat on the track plane; trade slope height for speed (vanilla)
        Vec3 localNow = StickyRailMinecartLogic.worldPosToRail(this.position(), center, bottom);
        Vec3 trackPosNow = StickyRailMinecartLogic.railTrackPos(
            this.level(), localNow.x, localNow.y, localNow.z, center, bottom
        );
        if (trackPosNow != null && vec3 != null) {
            double d17 = (vec3.y - trackPosNow.y) * 0.05;
            Vec3 vec34 = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
            double d18 = vec34.horizontalDistance();
            if (d18 > 0.0) {
                this.setDeltaMovement(StickyRailMinecartLogic.railToCart(
                    comp, vec34.multiply((d18 + d17) / d18, 1.0, (d18 + d17) / d18), bottom
                ));
            }
            gravityunbound$setPosFromRail(localNow.x, trackPosNow.y, localNow.z, center, bottom);
        }

        // left the rail cell sideways: aim the velocity at the new cell (vanilla)
        Vec3 localEnd = StickyRailMinecartLogic.worldPosToRail(this.position(), center, bottom);
        int j = Mth.floor(localEnd.x);
        int i = Mth.floor(localEnd.z);
        if (j != railPos.getX() || i != railPos.getZ()) {
            Vec3 vec35 = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
            double d26 = vec35.horizontalDistance();
            this.setDeltaMovement(StickyRailMinecartLogic.railToCart(
                comp, new Vec3(d26 * (j - railPos.getX()), vec35.y, d26 * (i - railPos.getZ())), bottom
            ));
        }

        // (onMinecartPass + powered boost skipped — no powered sticky rails)
    }

    @org.spongepowered.asm.mixin.Unique
    private void gravityunbound$setPosFromRail(double x, double y, double z, Vec3 center, Direction bottom) {
        Vec3 world = StickyRailMinecartLogic.railPosToWorld(new Vec3(x, y, z), center, bottom);
        this.setPos(world.x, world.y, world.z);
    }
}

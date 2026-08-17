package net.camacraft.gravityunbound.mixin;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.sticky.StickyRailBlock;
import net.camacraft.gravityunbound.sticky.StickyRailMinecartLogic;
import net.camacraft.gravityunbound.util.RotationUtil;

import org.jetbrains.annotations.Nullable;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Minecart integration for the gravity mod:
 * <ul>
 *   <li>Scale the cart's per-tick gravity by the entity's gravity strength
 *       (the {@code @ModifyArg} below, active on the vanilla path).</li>
 *   <li>Drive the gravity capability's per-tick update: minecarts override
 *       {@code Entity.tick} without calling super, so {@code EntityMixin}'s
 *       injection never fires for them.</li>
 *   <li>Sticky rails: when the cart is on a NON-DOWN {@link StickyRailBlock},
 *       cancel the vanilla tick and run a LOCAL-FRAME port of the vanilla
 *       1.20.1 server tick. DOWN-bottom sticky rails are real vanilla rails
 *       (the block extends {@code BaseRailBlock} and is in the rails tag) and
 *       vanilla handles their movement ENTIRELY — this mixin only takes over
 *       when {@code BOTTOM != DOWN}, or when vanilla's world-frame detection
 *       would misread a rotated rail as a flat one (then the ported off-track
 *       branch runs instead, so vanilla track physics never double-run).</li>
 *   <li>Physics model: the cart KEEPS ITS OWN gravity (no gravity pin). The
 *       rail constrains it — the track clamp holds it laterally, and the
 *       on-track acceleration is the PROJECTION of the cart's actual gravity
 *       vector onto the track line, scaled by vanilla's slope constant
 *       ({@code getSlopeAdjustment()} = 0.0078125/tick; exactly vanilla's
 *       hardcoded slope thrust when gravity is plain DOWN and the shape
 *       ascending — verified sign-for-sign against all four ascending cases;
 *       it applies on EVERY shape, so a wall track running along gravity
 *       accelerates the cart). Vanilla's free-fall gravity add is NOT applied
 *       while riding: under plain gravity vanilla immediately discards it
 *       anyway (Forge's {@code moveMinecartOnRail} moves with y=0 and
 *       {@code applyNaturalSlowdown} zeroes dm.y), and under a rotated rail
 *       frame it would leak into the track plane — the source of the old
 *       oscillation bug. Friction and speed caps are applied in the RAIL
 *       frame (ports of {@code applyNaturalSlowdown}/{@code
 *       moveMinecartOnRail}), since the cart-local frame no longer coincides
 *       with the rail frame.</li>
 *   <li>VISUAL pin: while riding a non-DOWN rail (both sides, every tick) the
 *       capability's external visual override pins the cart's visual frame to
 *       the rail bottom's CANONICAL cardinal frame, so the model lies on the
 *       track bed instead of rendering upright half inside the wall. The
 *       canonical frame (not the rail's spin-inclusive Rotation24 quaternion)
 *       is used so neighboring rails of different SPIN share one frame — the
 *       in-plane heading is yaw's job — and so the frame helpers recognize it
 *       and use exact cardinal math. The override is consumed by
 *       {@code advanceVisualRotation} WITHOUT the chase's world-velocity
 *       re-expression, so this mixin re-expresses dm across the frame change
 *       itself on ridden ticks (see the HEAD injection). Because {@code
 *       EntityMixin} interprets move()'s dm in the settled-cardinal/visual
 *       frame, the dm frame while riding IS the pinned frame — the explicit
 *       cartToRail/railToCart conversions mirror that exactly.</li>
 *   <li>Bounding box: while riding a non-DOWN rail the box is built in the
 *       RAIL frame instead of the cart's gravity frame ({@link
 *       #makeBoundingBox()}). The gravity-frame box of e.g. a plain-gravity
 *       cart on a wall rail is embedded ~0.43 into the wall, which made
 *       move() clip ALL track travel (carts froze instead of rolling) and the
 *       pick ray hit the wall (carts "difficult to break").</li>
 *   <li>Rider position: {@link #positionRider} rotates the riding offset into
 *       the RAIL frame while riding a non-DOWN sticky rail (whatever the
 *       cart's gravity), else into the cart's gravity frame instead of the
 *       world Y axis (vanilla path untouched for a default-gravity cart off
 *       sticky rails).</li>
 *   <li>Yaw is computed from the position delta in the cart's VISUAL frame
 *       (player convention — the frame the render dispatcher poses the cart
 *       in), with vanilla's exact atan2 + flip logic.</li>
 * </ul>
 * Skipped vanilla behavior on non-DOWN sticky rails (deferred with the
 * powered rail variants): powered boost/brake, activator handling,
 * {@code onMinecartPass}, and the furnace minecart's push (its
 * {@code applyNaturalSlowdown} override is bypassed by the rail-frame
 * friction port).
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin extends Entity {

    @Shadow private boolean flipped;
    @Shadow private boolean onRails;

    @Shadow protected abstract void comeOffTrack();

    /**
     * The BOTTOM of the non-DOWN sticky rail this cart is currently riding,
     * or null when not riding one. Maintained on BOTH sides by the tick HEAD
     * injection (the client needs it for the pick/interaction box and the
     * ride-aligned passenger seat). Drives {@link #makeBoundingBox()} and the
     * per-tick visual override.
     */
    @org.spongepowered.asm.mixin.Unique
    private @Nullable Direction gravityunbound$railBottom = null;

    public AbstractMinecartMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    /**
     * While riding a non-DOWN sticky rail, build the bounding box in the RAIL
     * frame (world-aligned envelope of the box rotated so its local UP is the
     * rail's up) instead of the cart's gravity frame. Without this, a
     * plain-gravity cart on a wall rail keeps its vanilla world-frame box —
     * the position sits only 1/16 off the wall face, so the box was embedded
     * ~0.43 into the wall: move() clipped every along-track step against the
     * wall column (carts never rolled) and the pick ray hit the wall before
     * the cart (carts were nearly unbreakable). The rail-frame box extends
     * 0.7 along the rail's up (away from the wall) and +-0.49 in the track
     * plane, entirely outside the mounting wall. (Not riding: defer to
     * {@code Entity.makeBoundingBox}, where {@code EntityMixin}'s gravity
     * handling applies.)
     */
    @Override
    protected AABB makeBoundingBox() {
        Direction bottom = gravityunbound$railBottom;
        if (bottom == null || bottom == Direction.DOWN) {
            return super.makeBoundingBox();
        }
        AABB box = this.getDimensions(this.getPose()).makeBoundingBox(0.0, 0.0, 0.0);
        if (bottom.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            // mirror EntityMixin: keep the cell-boundary epsilon on the far side
            box = box.move(0.0, -1.0E-6, 0.0);
        }
        return RotationUtil.boxPlayerToWorld(box, bottom).move(this.position());
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
    // rider position (rotated frames)
    // ------------------------------------------------------------------

    /**
     * Vanilla applies the riding offset on the world Y axis, which floats the
     * rider off a rotated cart. Rotate the offset into the cart's gravity
     * frame instead; the vanilla path is untouched for default-gravity carts.
     * (AbstractMinecart does not override {@code Entity.positionRider} in
     * 1.20.1 — this adds the override.)
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        // riding a non-DOWN sticky rail: the seat offset follows the RAIL's
        // up (the cart's pinned visual frame), regardless of the cart's own
        // gravity — a plain-gravity cart on a wall rail is comp.isDefault(),
        // but its rider must still sit off the track bed, not world-up
        Direction railBottom = gravityunbound$railBottom;
        if (railBottom != null && railBottom != Direction.DOWN) {
            if (this.hasPassenger(passenger)) {
                double dy = this.getPassengersRidingOffset() + passenger.getMyRidingOffset();
                Vec3 offset = RotationUtil.vecEntityToWorld(new Vec3(0.0, dy, 0.0), railBottom);
                moveFunction.accept(passenger, this.getX() + offset.x, this.getY() + offset.y, this.getZ() + offset.z);
            }
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this);
        if (comp == null || comp.isDefault()) {
            super.positionRider(passenger, moveFunction);
            return;
        }
        if (this.hasPassenger(passenger)) {
            double dy = this.getPassengersRidingOffset() + passenger.getMyRidingOffset();
            Vec3 offset = RotationUtil.vecPlayerToWorld(new Vec3(0.0, dy, 0.0), comp.getCurrentRotation());
            moveFunction.accept(passenger, this.getX() + offset.x, this.getY() + offset.y, this.getZ() + offset.z);
        }
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
        // everything else). Riding detection runs BEFORE the capability tick
        // so the visual override set for a ridden tick is consumed by THIS
        // tick's advanceVisualRotation (it is a one-shot, consumed once per
        // capability tick).
        AbstractMinecart self = (AbstractMinecart) (Object) this;
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(self);
        if (comp == null) {
            gravityunbound$railBottom = null;
            this.getCapability(net.camacraft.gravityunbound.capabilities.GravityCapabilities.GRAVITY)
                .ifPresent(net.camacraft.gravityunbound.capabilities.IGravityCapability::tick);
            return;
        }

        StickyRailMinecartLogic.DetectResult result = self.canUseRail()
            ? StickyRailMinecartLogic.decide(this.level(), this.position(), comp.getCurrGravityDirection())
            : StickyRailMinecartLogic.DetectResult.VANILLA;

        if (result.decision() == StickyRailMinecartLogic.Decision.RIDE) {
            Direction bottom = result.hit().bottom();
            // Pin the VISUAL frame to the rail bottom's canonical cardinal
            // frame (render/pick alignment only; gravity stays the cart's
            // own). Every ridden tick, both sides — the override is consumed
            // once per capability tick. The canonical frame (not the rail's
            // spin-inclusive Rotation24 quaternion) keeps neighboring rails
            // of different SPIN in ONE shared frame, leaves the in-plane
            // heading to yaw, and is recognized by the RotationUtil helpers
            // for exact cardinal math.
            //
            // dm continuity: the override consumption intentionally skips the
            // chase's world-velocity re-expression ("deltaMovement semantics
            // stay with the caller"), but EntityMixin interprets move()'s dm
            // in the settled-cardinal/visual frame — which this override
            // CHANGES. Re-express dm across the change ourselves: capture the
            // world dm in the pre-tick frame, restore it in the post-tick
            // frame. On steady ridden ticks both frames are the same
            // canonical instance and the round trip is exact cardinal math
            // (bit-for-bit identity).
            Vec3 dmWorld = StickyRailMinecartLogic.cartLocalToWorld(comp, this.getDeltaMovement());
            comp.setExternalVisualOverride(RotationUtil.getWorldRotationQuaternion(bottom));
            gravityunbound$railBottom = bottom;
            comp.tick();
            this.setDeltaMovement(StickyRailMinecartLogic.worldToCartLocal(comp, dmWorld));
        } else {
            // not riding: drop the rail box/seat frame; the capability's own
            // chase re-expresses dm when the frame moves off the rail pin
            gravityunbound$railBottom = null;
            comp.tick();
        }

        // the client branch of the vanilla tick only lerps toward server
        // state — leave it alone (carts are server-controlled); the ride
        // state above still ran so the client box/visual frame track the rail
        if (this.level().isClientSide) {
            return;
        }
        if (result.decision() == StickyRailMinecartLogic.Decision.VANILLA) {
            return;
        }
        ci.cancel();
        gravityunbound$tickOnStickyRail(self, comp, result.hit());
    }

    /**
     * The server branch of vanilla {@code AbstractMinecart.tick} (1.20.1,
     * decompiled Forge 47.4.16 sources), transliterated for a cart on (or
     * falling past) a rotated sticky rail. Structure and constants match
     * vanilla line for line; the deltas are frame conversions, the
     * gravity-projection physics, and the off-track branch for rails vanilla
     * would misread. {@code hit == null} means "vanilla would think it is on
     * a rail but it is not": run the ported off-track branch.
     */
    private void gravityunbound$tickOnStickyRail(
        AbstractMinecart self, GravityCapabilityImpl comp, @Nullable StickyRailMinecartLogic.RailHit hit
    ) {
        if (self.getHurtTime() > 0) {
            self.setHurtTime(self.getHurtTime() - 1);
        }
        if (self.getDamage() > 0.0F) {
            self.setDamage(self.getDamage() - 1.0F);
        }

        this.checkBelowWorld();
        this.handleNetherPortal();

        if (hit != null) {
            // riding: vanilla's free-fall gravity add is intentionally NOT
            // applied (see class javadoc) — the projection of the cart's own
            // gravity onto the track inside moveAlongStickyTrack replaces
            // both it and vanilla's hardcoded slope thrust
            this.onRails = true;
            gravityunbound$moveAlongStickyTrack(self, comp, hit.pos(), hit.state(), hit.bottom());
            // (activator rail handling deferred with the powered variants)
        } else {
            // vanilla off-track branch: free gravity (cart-local down,
            // strength-scaled like the @ModifyArg on the vanilla path), then
            // comeOffTrack (component-wise on local dm; move() handles frames)
            if (!this.isNoGravity()) {
                double gravity = this.isInWater() ? -0.005 : -0.04;
                gravity *= GravityChangerAPI.getGravityStrength(self);
                this.setDeltaMovement(this.getDeltaMovement().add(0.0, gravity, 0.0));
            }
            this.onRails = false;
            this.comeOffTrack();
        }

        this.checkInsideBlocks();
        this.setXRot(0.0F);

        // vanilla derives yaw from the world position delta; under a rotated
        // frame the yaw is LOCAL. The delta is converted through the VISUAL
        // frame (player convention) — the frame the render dispatcher poses
        // the cart in — NOT the entity movement convention, whose UP-gravity
        // reflection mirrored the yaw on ceiling tracks.
        Vec3 worldDelta = new Vec3(this.xo - this.getX(), this.yo - this.getY(), this.zo - this.getZ());
        Vec3 localDelta = StickyRailMinecartLogic.worldToCartVisual(comp, worldDelta);
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
     * cart-local &lt;-&gt; rail frame explicitly (the cart keeps its own
     * gravity, so the frames need not coincide). Vanilla's hardcoded
     * per-ascending-shape thrust is replaced by the projection of the cart's
     * ACTUAL gravity vector onto the 3D track direction — identical numbers
     * to vanilla for a plain-gravity cart on a vanilla slope, and the correct
     * generalization everywhere else (a wall rail whose track is
     * world-horizontal leaves the cart at rest; a track with a component
     * along gravity lets it roll). Powered boost/brake and
     * {@code onMinecartPass} are skipped (no powered sticky rails yet).
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
        if (shape.isAscending()) {
            // vanilla lifts the track plane one cell for ascending shapes
            ++d1;
        }

        // chord geometry in the rail frame (rail-frame "horizontal" = the
        // track plane; vertical doubling matches vanilla getPos, making
        // ascending chords effectively 45 degrees)
        double d4 = exitB.getX() - exitA.getX();
        double d5 = exitB.getZ() - exitA.getZ();
        double d6 = Math.sqrt(d4 * d4 + d5 * d5);
        double chordY = (exitB.getY() - exitA.getY()) * 2.0;

        // === gravity projection (replaces the pin AND vanilla's hardcoded
        // slope gravity): along-track acceleration = slope constant x
        // (cart's own gravity unit . track direction), converted to the
        // horizontal-projection bookkeeping vanilla uses for dm. For a
        // plain-DOWN cart on a vanilla ascending shape this is EXACTLY
        // vanilla's +-slopeAdjustment on the horizontal axis.
        double chordLen = Math.sqrt(d4 * d4 + chordY * chordY + d5 * d5);
        Vec3 gravityRail = RotationUtil.vecWorldToEntity(
            StickyRailMinecartLogic.cartGravityUnit(comp), bottom
        );
        double alongTrack = (gravityRail.x * d4 + gravityRail.y * chordY + gravityRail.z * d5) / chordLen;
        double horizFrac = d6 / chordLen;
        double thrust = slopeAdjustment * GravityChangerAPI.getGravityStrength(self) * alongTrack / horizFrac;
        vec31 = vec31.add(thrust * d4 / d6, 0.0, thrust * d5 / d6);

        // redirect the horizontal velocity along the track chord (vanilla)
        double rd4 = d4;
        double rd5 = d5;
        double d7 = vec31.x * rd4 + vec31.z * rd5;
        if (d7 < 0.0) {
            rd4 = -rd4;
            rd5 = -rd5;
        }
        double d8 = Math.min(2.0, vec31.horizontalDistance());
        vec31 = new Vec3(d8 * rd4 / d6, vec31.y, d8 * rd5 / d6);
        this.setDeltaMovement(StickyRailMinecartLogic.railToCart(comp, vec31, bottom));

        // a rider paddling a stopped cart nudges it along (vanilla). The
        // passenger's dm lives in the PASSENGER's local frame — convert it
        // into the rail frame before comparing/adding.
        Entity passenger = this.getFirstPassenger();
        if (passenger instanceof Player) {
            Vec3 passengerDm = passenger.getDeltaMovement();
            GravityCapabilityImpl passengerComp = GravityChangerAPI.getGravityComponentOrNull(passenger);
            Vec3 passengerWorld = passengerComp == null
                ? passengerDm
                : RotationUtil.vecPlayerToWorld(passengerDm, passengerComp.getVisualRotation());
            Vec3 passengerRail = RotationUtil.vecWorldToEntity(passengerWorld, bottom);
            Vec3 cartRail = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
            double d9 = passengerRail.horizontalDistanceSqr();
            double d11 = cartRail.horizontalDistanceSqr();
            if (d9 > 1.0E-4 && d11 < 0.01) {
                cartRail = cartRail.add(passengerRail.x * 0.1, 0.0, passengerRail.z * 0.1);
                this.setDeltaMovement(StickyRailMinecartLogic.railToCart(comp, cartRail, bottom));
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

        // Forge's moveMinecartOnRail, ported to the rail frame: clamp the
        // RAIL-frame in-plane dm and move() with the perpendicular zeroed
        // (Forge passes y=0 the same way). The cart-local components no
        // longer coincide with the rail plane, so clamping local x/z (as the
        // Forge implementation does) would cut across the track instead.
        {
            double vehicleFactor = this.isVehicle() ? 0.75 : 1.0;
            double maxSpeed = self.getMaxSpeedWithRail();
            Vec3 moveRail = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
            Vec3 clamped = new Vec3(
                Mth.clamp(vehicleFactor * moveRail.x, -maxSpeed, maxSpeed),
                0.0,
                Mth.clamp(vehicleFactor * moveRail.z, -maxSpeed, maxSpeed)
            );
            this.move(MoverType.SELF, StickyRailMinecartLogic.railToCart(comp, clamped, bottom));
        }

        // climbing snap between the cells of an ascending rail (vanilla)
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

        // applyNaturalSlowdown, ported to the rail frame: friction acts in
        // the track plane and the perpendicular component is zeroed — in the
        // rail frame, not the cart's local frame (which vanilla could use
        // only because the two coincided under plain gravity)
        {
            double drag = this.isVehicle() ? 0.997 : 0.96;
            Vec3 slowRail = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
            slowRail = new Vec3(slowRail.x * drag, 0.0, slowRail.z * drag);
            if (this.isInWater()) {
                slowRail = slowRail.scale(0.95);
            }
            this.setDeltaMovement(StickyRailMinecartLogic.railToCart(comp, slowRail, bottom));
        }

        // re-seat on the track plane (vanilla's final height: rail cell
        // + 0.0625 along the rail's up, from railTrackPos); trade slope
        // height for speed only when the tick-start track position resolved
        // too (vanilla gates both on it, but seating must not be skipped on
        // the attach tick or the cart spends it sunk to the cell bottom)
        Vec3 localNow = StickyRailMinecartLogic.worldPosToRail(this.position(), center, bottom);
        Vec3 trackPosNow = StickyRailMinecartLogic.railTrackPos(
            this.level(), localNow.x, localNow.y, localNow.z, center, bottom
        );
        if (trackPosNow != null) {
            if (vec3 != null) {
                double d17 = (vec3.y - trackPosNow.y) * 0.05;
                Vec3 vec34 = StickyRailMinecartLogic.cartToRail(comp, this.getDeltaMovement(), bottom);
                double d18 = vec34.horizontalDistance();
                if (d18 > 0.0) {
                    this.setDeltaMovement(StickyRailMinecartLogic.railToCart(
                        comp, vec34.multiply((d18 + d17) / d18, 1.0, (d18 + d17) / d18), bottom
                    ));
                }
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

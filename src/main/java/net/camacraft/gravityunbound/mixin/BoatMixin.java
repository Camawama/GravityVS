package net.camacraft.gravityunbound.mixin;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Boats float in their gravity FRAME.
 *
 * Vanilla's boat is world-vertical through and through: whether it is in
 * water, under water, on land, where the water surface is and how hard the
 * water pushes back are all read off world Y. Inside a gravity field the
 * boat's frame turns with the field (it collides as a capsule like every
 * other entity), and its deltaMovement — where the buoyancy is applied — is
 * LOCAL, so the push went along the frame's up while the surface it was
 * measured against stayed world-up: on any face of a water cube but the top
 * the boat read "under water" and sank, or "in air" and dived. Every one of
 * those readings is re-taken here along the frame's up: a cell's water
 * surface is its furthest reach along up, the boat's feet and top are its
 * position along up, the buoyancy pushes along local +Y as before. The
 * vanilla paths are untouched in the vanilla frame.
 *
 * Riders sit in the same frame: vanilla places a passenger at a WORLD
 * horizontal offset plus a world-Y seat height, which hung them beside a
 * tilted boat; the seat offset is rotated into the boat's frame.
 */
@Mixin(Boat.class)
public abstract class BoatMixin extends Entity {

    @Shadow
    private double waterLevel;
    @Shadow
    private float landFriction;
    @Shadow
    private Boat.Status status;
    @Shadow
    private Boat.Status oldStatus;
    @Shadow
    private double lastYd;
    @Shadow
    private float invFriction;
    @Shadow
    private float deltaRotation;

    @Shadow
    public abstract float getWaterLevelAbove();

    @Shadow
    protected abstract float getSinglePassengerXOffset();

    @Shadow
    protected abstract void clampRotation(Entity entity);

    @Shadow
    protected abstract int getMaxPassengers();

    public BoatMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @ModifyConstant(method = "Lnet/minecraft/world/entity/vehicle/Boat;floatBoat()V", constant = @Constant(doubleValue = -0.03999999910593033))
    private double multiplyGravity(double constant) {
        return constant * GravityChangerAPI.getGravityStrength(this);
    }

    // ------------------------------------------------------------------
    // frame helpers
    // ------------------------------------------------------------------

    /**
     * Forge's {@code Boat.canBoatInFluid(FluidState)}, called through the
     * fluid API it delegates to. That method is a Forge PATCH addition, not a
     * vanilla member: it carries no obfuscation mapping, so a {@code @Shadow}
     * of it resolved in the dev environment and failed to apply in the
     * shipped game (mixin apply error on startup).
     */
    @Unique
    private boolean gravityunbound$canBoatIn(FluidState fluid) {
        return fluid.getFluidType().supportsBoating(fluid, (Boat) (Object) this);
    }

    /** The boat's up when its frame is not the vanilla one; null otherwise. */
    @Unique
    @Nullable
    private Vec3 gravityunbound$frameUp() {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this);
        if (comp == null || comp.isVisuallyDefault()) {
            return null;
        }
        return comp.getUpVector();
    }

    /** The lowest reach of a block cell along up. */
    @Unique
    private static double gravityunbound$cellBottom(BlockPos pos, Vec3 up) {
        return pos.getX() * up.x + pos.getY() * up.y + pos.getZ() * up.z
            + Math.min(0.0, up.x) + Math.min(0.0, up.y) + Math.min(0.0, up.z);
    }

    /** The furthest reach along up of the fluid filling a cell to {@code height} (world Y). */
    @Unique
    private static double gravityunbound$fluidTop(BlockPos pos, Vec3 up, double height) {
        return pos.getX() * up.x + pos.getY() * up.y + pos.getZ() * up.z
            + Math.max(0.0, up.x) + Math.max(0.0, up.y * height) + Math.max(0.0, up.z);
    }

    @Unique
    private static Iterable<BlockPos> gravityunbound$cells(AABB box) {
        return BlockPos.betweenClosed(
            Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
            Mth.ceil(box.maxX) - 1, Mth.ceil(box.maxY) - 1, Mth.ceil(box.maxZ) - 1);
    }

    // ------------------------------------------------------------------
    // status along the frame
    // ------------------------------------------------------------------

    @Inject(method = "getStatus", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$frameStatus(CallbackInfoReturnable<Boat.Status> cir) {
        Vec3 up = gravityunbound$frameUp();
        if (up == null) {
            return;
        }
        double feetUp = this.position().dot(up);
        double topUp = feetUp + this.getBbHeight();

        Boat.Status under = gravityunbound$underwater(up, topUp);
        if (under != null) {
            this.waterLevel = topUp;
            cir.setReturnValue(under);
            return;
        }
        if (gravityunbound$checkInWater(up, feetUp)) {
            cir.setReturnValue(Boat.Status.IN_WATER);
            return;
        }
        float friction = gravityunbound$groundFriction();
        if (friction > 0.0F) {
            this.landFriction = friction;
            cir.setReturnValue(Boat.Status.ON_LAND);
            return;
        }
        cir.setReturnValue(Boat.Status.IN_AIR);
    }

    /** Vanilla isUnderwater: a fluid surface above the boat's top, in a cell straddling that top. */
    @Unique
    @Nullable
    private Boat.Status gravityunbound$underwater(Vec3 up, double topUp) {
        double d0 = topUp + 0.001;
        boolean flag = false;
        for (BlockPos pos : gravityunbound$cells(this.getBoundingBox())) {
            FluidState fluid = this.level().getFluidState(pos);
            if (!gravityunbound$canBoatIn(fluid)) {
                continue;
            }
            if (gravityunbound$cellBottom(pos, up) >= d0) {
                continue;
            }
            if (d0 < gravityunbound$fluidTop(pos, up, fluid.getHeight(this.level(), pos))) {
                if (!fluid.isSource()) {
                    return Boat.Status.UNDER_FLOWING_WATER;
                }
                flag = true;
            }
        }
        return flag ? Boat.Status.UNDER_WATER : null;
    }

    /** Vanilla checkInWater: the water level around the feet, and whether a surface is above them. */
    @Unique
    private boolean gravityunbound$checkInWater(Vec3 up, double feetUp) {
        boolean flag = false;
        this.waterLevel = -Double.MAX_VALUE;
        for (BlockPos pos : gravityunbound$cells(this.getBoundingBox())) {
            FluidState fluid = this.level().getFluidState(pos);
            if (!gravityunbound$canBoatIn(fluid)) {
                continue;
            }
            // the layer the feet are in, or below it (vanilla scans the floor layer)
            if (gravityunbound$cellBottom(pos, up) > feetUp + 0.001) {
                continue;
            }
            double top = gravityunbound$fluidTop(pos, up, fluid.getHeight(this.level(), pos));
            this.waterLevel = Math.max(top, this.waterLevel);
            flag |= feetUp < top;
        }
        return flag;
    }

    /** Ground friction of the block under the feet along the frame's down. */
    @Unique
    private float gravityunbound$groundFriction() {
        BlockPos below = this.getBlockPosBelowThatAffectsMyMovement();
        BlockState state = this.level().getBlockState(below);
        if (state.getBlock() instanceof WaterlilyBlock || state.getCollisionShape(this.level(), below).isEmpty()) {
            return 0.0F;
        }
        return state.getFriction(this.level(), below, this);
    }

    /** The highest water surface around the boat along up (vanilla: the first non-full layer above). */
    @Inject(method = "getWaterLevelAbove", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$frameWaterLevelAbove(CallbackInfoReturnable<Float> cir) {
        Vec3 up = gravityunbound$frameUp();
        if (up == null) {
            return;
        }
        double best = -Double.MAX_VALUE;
        boolean any = false;
        for (BlockPos pos : gravityunbound$cells(this.getBoundingBox().inflate(1.0))) {
            FluidState fluid = this.level().getFluidState(pos);
            if (!gravityunbound$canBoatIn(fluid)) {
                continue;
            }
            double top = gravityunbound$fluidTop(pos, up, fluid.getHeight(this.level(), pos));
            if (top > best) {
                best = top;
                any = true;
            }
        }
        double topUp = this.position().dot(up) + this.getBbHeight();
        cir.setReturnValue((float) (any ? best : topUp + 1.0));
    }

    // ------------------------------------------------------------------
    // buoyancy along the frame (vanilla floatBoat with Y read along up)
    // ------------------------------------------------------------------

    @Inject(method = "floatBoat", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$frameFloat(CallbackInfo ci) {
        Vec3 up = gravityunbound$frameUp();
        if (up == null) {
            return;
        }
        ci.cancel();

        double feetUp = this.position().dot(up);
        double height = this.getBbHeight();
        double d1 = this.isNoGravity() ? 0.0D : -0.04D * GravityChangerAPI.getGravityStrength(this);
        double d2 = 0.0D;
        this.invFriction = 0.05F;
        if (this.oldStatus == Boat.Status.IN_AIR && this.status != Boat.Status.IN_AIR && this.status != Boat.Status.ON_LAND) {
            this.waterLevel = feetUp + height;
            double targetFeetUp = (double) this.getWaterLevelAbove() - height + 0.101D;
            this.setPos(this.position().add(up.scale(targetFeetUp - feetUp)));
            this.setDeltaMovement(this.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
            this.lastYd = 0.0D;
            this.status = Boat.Status.IN_WATER;
        }
        else {
            if (this.status == Boat.Status.IN_WATER) {
                d2 = (this.waterLevel - feetUp) / height;
                this.invFriction = 0.9F;
            }
            else if (this.status == Boat.Status.UNDER_FLOWING_WATER) {
                d1 = -7.0E-4D;
                this.invFriction = 0.9F;
            }
            else if (this.status == Boat.Status.UNDER_WATER) {
                d2 = (double) 0.01F;
                this.invFriction = 0.45F;
            }
            else if (this.status == Boat.Status.IN_AIR) {
                this.invFriction = 0.9F;
            }
            else if (this.status == Boat.Status.ON_LAND) {
                this.invFriction = this.landFriction;
                if (this.getControllingPassenger() instanceof Player) {
                    this.landFriction /= 2.0F;
                }
            }

            Vec3 vec3 = this.getDeltaMovement();
            this.setDeltaMovement(vec3.x * (double) this.invFriction, vec3.y + d1, vec3.z * (double) this.invFriction);
            this.deltaRotation *= this.invFriction;
            if (d2 > 0.0D) {
                Vec3 vec31 = this.getDeltaMovement();
                this.setDeltaMovement(vec31.x, (vec31.y + d2 * 0.06153846016296973D) * 0.75D, vec31.z);
            }
        }
    }

    // ------------------------------------------------------------------
    // riders sit in the boat's frame
    // ------------------------------------------------------------------

    @Inject(method = "positionRider", at = @At("HEAD"), cancellable = true)
    private void gravityunbound$frameRider(Entity passenger, Entity.MoveFunction callback, CallbackInfo ci) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(this);
        if (comp == null || comp.isVisuallyDefault()) {
            return;
        }
        ci.cancel();
        if (!this.hasPassenger(passenger)) {
            return;
        }
        float f = this.getSinglePassengerXOffset();
        float f1 = (float) ((this.isRemoved() ? (double) 0.01F : this.getPassengersRidingOffset()) + passenger.getMyRidingOffset());
        if (this.getPassengers().size() > 1) {
            int i = this.getPassengers().indexOf(passenger);
            f = i == 0 ? 0.2F : -0.6F;
            if (passenger instanceof Animal) {
                f += 0.2F;
            }
        }

        Vec3 seat = (new Vec3((double) f, 0.0D, 0.0D)).yRot(-this.getYRot() * ((float) Math.PI / 180F) - ((float) Math.PI / 2F));
        Vec3 offset = RotationUtil.vecPlayerToWorld(new Vec3(seat.x, (double) f1, seat.z), comp.getVisualRotation());
        callback.accept(passenger, this.getX() + offset.x, this.getY() + offset.y, this.getZ() + offset.z);
        passenger.setYRot(passenger.getYRot() + this.deltaRotation);
        passenger.setYHeadRot(passenger.getYHeadRot() + this.deltaRotation);
        this.clampRotation(passenger);
        if (passenger instanceof Animal animal && this.getPassengers().size() == this.getMaxPassengers()) {
            int j = passenger.getId() % 2 == 0 ? 90 : 270;
            passenger.setYBodyRot(animal.yBodyRot + (float) j);
            passenger.setYHeadRot(passenger.getYHeadRot() + (float) j);
        }
    }
}

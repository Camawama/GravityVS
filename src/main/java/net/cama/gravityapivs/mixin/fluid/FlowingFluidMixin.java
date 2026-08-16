package net.cama.gravityapivs.mixin.fluid;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import net.cama.gravityapivs.util.GravityFieldLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Gravity-aware fluid flow: liquids inside a gravity field treat the field's
 * cardinal down as "down" — falling along it, spreading across the plane
 * perpendicular to it, and pushing entities along the rotated flow.
 *
 * The vanilla fluid engine expresses every directional decision through
 * {@code Direction.DOWN}/{@code UP}, {@code BlockPos.below()/above()} and
 * {@code Direction.Plane.HORIZONTAL}; each such site (verified exhaustively
 * against the Forge 1.20.1-47.4.16 bytecode) is rewired through
 * {@link GravityFieldLookup#fluidDownAt}. When that returns plain DOWN — no
 * field, or the feature disabled — every wrap calls the vanilla original
 * verbatim, so behavior outside fields is bit-identical.
 *
 * {@code getFlow} (the entity-push / render flow vector) is replaced
 * wholesale for rotated gravity instead of per-site: its accumulator only
 * tracks X/Z steps, so perpendicular directions with Y components would be
 * silently dropped by direction swaps alone.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Shadow
    protected abstract boolean isSolidFace(BlockGetter level, BlockPos pos, Direction direction);

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The four directions perpendicular to each down, in a fixed order. */
    @Unique
    private static final Direction[][] gravityapivs$PERPENDICULAR = new Direction[6][];

    static {
        for (Direction down : Direction.values()) {
            gravityapivs$PERPENDICULAR[down.ordinal()] = Arrays.stream(Direction.values())
                .filter(d -> d.getAxis() != down.getAxis())
                .toArray(Direction[]::new);
        }
    }

    @Unique
    private static List<Direction> gravityapivs$perpendicular(Direction down) {
        return Arrays.asList(gravityapivs$PERPENDICULAR[down.ordinal()]);
    }

    @Unique
    private static Direction gravityapivs$down(BlockGetter getter, BlockPos pos) {
        return GravityFieldLookup.fluidDownAt(getter, pos);
    }

    // vanilla FlowingFluid.affectsFlow is private and trivial; reimplemented
    // to avoid shadowing a private method
    @Unique
    private boolean gravityapivs$affectsFlow(FluidState state) {
        return state.isEmpty() || state.getType().isSame((Fluid) (Object) this);
    }

    // ------------------------------------------------------------------
    // spread(): the fall direction
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityapivs$spreadFallPos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityapivs$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z")
    )
    private boolean gravityapivs$spreadFallCheck(
        FlowingFluid self, BlockGetter getter, BlockPos fromPos, BlockState fromState,
        Direction direction, BlockPos toPos, BlockState toState, FluidState toFluid, Fluid fluid,
        Operation<Boolean> original
    ) {
        return original.call(self, getter, fromPos, fromState, gravityapivs$down(getter, fromPos), toPos, toState, toFluid, fluid);
    }

    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V")
    )
    private void gravityapivs$spreadFallInto(
        FlowingFluid self, LevelAccessor level, BlockPos pos, BlockState state,
        Direction direction, FluidState fluidState, Operation<Void> original
    ) {
        Direction down = level instanceof Level realLevel
            ? gravityapivs$down(realLevel, pos) : Direction.DOWN;
        original.call(self, level, pos, state, down, fluidState);
    }

    // ------------------------------------------------------------------
    // getNewLiquid(): neighbor plane, column continuity, source formation
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityapivs$newLiquidPlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos
    ) {
        Direction down = gravityapivs$down(level, pos);
        return down == Direction.DOWN ? original.call(plane) : gravityapivs$perpendicular(down).iterator();
    }

    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityapivs$newLiquidBelow(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityapivs$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityapivs$newLiquidAbove(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityapivs$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down.getOpposite());
    }

    @WrapOperation(
        method = "getNewLiquid",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FlowingFluid;canPassThroughWall(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            ordinal = 1
        )
    )
    private boolean gravityapivs$newLiquidUpWall(
        FlowingFluid self, Direction direction, BlockGetter getter, BlockPos pos,
        BlockState state, BlockPos abovePos, BlockState aboveState, Operation<Boolean> original
    ) {
        return original.call(self, gravityapivs$down(getter, pos).getOpposite(), getter, pos, state, abovePos, aboveState);
    }

    // ------------------------------------------------------------------
    // getSpread()/getSlopeDistance(): the slope-hole search plane
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "getSpread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityapivs$spreadPlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos
    ) {
        Direction down = gravityapivs$down(level, pos);
        return down == Direction.DOWN ? original.call(plane) : gravityapivs$perpendicular(down).iterator();
    }

    @WrapOperation(
        method = "getSpread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityapivs$spreadHolePos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityapivs$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    @WrapOperation(
        method = "getSlopeDistance",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityapivs$slopePlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) LevelReader level, @Local(argsOnly = true, ordinal = 1) BlockPos originPos
    ) {
        Direction down = gravityapivs$down(level, originPos);
        return down == Direction.DOWN ? original.call(plane) : gravityapivs$perpendicular(down).iterator();
    }

    // The slope search caches "is there a hole below this position" through a
    // lambda; its below() must follow gravity too. Synthetic lambda names are
    // stable for a given Forge build (verified against 1.20.1-47.4.16) but
    // not across remaps — require = 0 degrades that one cache to world-down
    // hole checks instead of crashing if the name ever misses.
    @WrapOperation(
        method = "lambda$getSlopeDistance$2(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/block/state/BlockState;S)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;"),
        require = 0
    )
    private BlockPos gravityapivs$slopeHoleCachePos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) LevelReader level) {
        Direction down = gravityapivs$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    // ------------------------------------------------------------------
    // isWaterHole()/sourceNeighborCount()/hasSameAbove()
    // ------------------------------------------------------------------

    // isWaterHole tests "can fluid pass DOWN through the wall into the hole"
    // via canPassThroughWall(Direction.DOWN, level, pos, state, holePos,
    // holeState) — rewire the direction (verified against bytecode: it calls
    // canPassThroughWall, not canSpreadTo)
    @WrapOperation(
        method = "isWaterHole",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;canPassThroughWall(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z")
    )
    private boolean gravityapivs$holeCheckDirection(
        FlowingFluid self, Direction direction, BlockGetter getter, BlockPos pos,
        BlockState state, BlockPos holePos, BlockState holeState,
        Operation<Boolean> original
    ) {
        return original.call(self, gravityapivs$down(getter, pos), getter, pos, state, holePos, holeState);
    }

    // Inside a rotated field the source-neighbor count is forced to ZERO
    // (empty iteration): the infinite-water rule must never run in a rotated
    // frame, because the sources it manufactures are PERMANENT blocks that
    // outlive the field — removing the gravity source left walls studded
    // with water sources. Rotated fields move water; they never create it.
    @WrapOperation(
        method = "sourceNeighborCount",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityapivs$sourceNeighborPlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) LevelReader level, @Local(argsOnly = true) BlockPos pos
    ) {
        Direction down = gravityapivs$down(level, pos);
        return down == Direction.DOWN ? original.call(plane) : java.util.Collections.emptyIterator();
    }

    @WrapOperation(
        method = "hasSameAbove",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;")
    )
    private static BlockPos gravityapivs$sameAbovePos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) BlockGetter getter) {
        Direction down = gravityapivs$down(getter, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down.getOpposite());
    }

    // ------------------------------------------------------------------
    // getFlow(): the entity-push / render flow vector
    // ------------------------------------------------------------------

    /**
     * Generalized port of vanilla getFlow for rotated gravity. Vanilla
     * accumulates only X/Z steps of the horizontal plane, so a direction
     * swap alone would drop the Y components of a sideways spread plane —
     * the whole vector build must be 3D. Vanilla runs untouched when
     * gravity is plain DOWN.
     */
    @Inject(method = "getFlow", at = @At("HEAD"), cancellable = true)
    private void gravityapivs$rotatedGetFlow(BlockGetter getter, BlockPos pos, FluidState state, CallbackInfoReturnable<Vec3> cir) {
        Direction down = gravityapivs$down(getter, pos);
        if (down == Direction.DOWN) {
            return;
        }
        Direction up = down.getOpposite();

        Vec3 flow = Vec3.ZERO;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : gravityapivs$perpendicular(down)) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getter.getFluidState(cursor);
            if (!gravityapivs$affectsFlow(neighbor)) {
                continue;
            }
            float neighborHeight = neighbor.getOwnHeight();
            float delta = 0.0F;
            if (neighborHeight == 0.0F) {
                if (!getter.getBlockState(cursor).blocksMotion()) {
                    BlockPos underNeighbor = cursor.relative(down);
                    FluidState under = getter.getFluidState(underNeighbor);
                    if (gravityapivs$affectsFlow(under)) {
                        neighborHeight = under.getOwnHeight();
                        if (neighborHeight > 0.0F) {
                            delta = state.getOwnHeight() - (neighborHeight - 0.8888889F);
                        }
                    }
                }
            }
            else if (neighborHeight > 0.0F) {
                delta = state.getOwnHeight() - neighborHeight;
            }
            if (delta != 0.0F) {
                flow = flow.add(
                    direction.getStepX() * delta,
                    direction.getStepY() * delta,
                    direction.getStepZ() * delta
                );
            }
        }

        if (state.getValue(FlowingFluid.FALLING)) {
            for (Direction direction : gravityapivs$perpendicular(down)) {
                cursor.setWithOffset(pos, direction);
                if (isSolidFace(getter, cursor, direction) || isSolidFace(getter, cursor.relative(up), direction)) {
                    flow = flow.normalize().add(
                        down.getStepX() * 6.0, down.getStepY() * 6.0, down.getStepZ() * 6.0
                    );
                    break;
                }
            }
        }

        cir.setReturnValue(flow.normalize());
    }
}

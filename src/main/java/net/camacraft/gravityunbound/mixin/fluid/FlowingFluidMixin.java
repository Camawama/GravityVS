package net.camacraft.gravityunbound.mixin.fluid;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import net.camacraft.gravityunbound.util.GravityFieldLookup;
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
    private static final Direction[][] gravityunbound$PERPENDICULAR = new Direction[6][];

    static {
        for (Direction down : Direction.values()) {
            gravityunbound$PERPENDICULAR[down.ordinal()] = Arrays.stream(Direction.values())
                .filter(d -> d.getAxis() != down.getAxis())
                .toArray(Direction[]::new);
        }
    }

    @Unique
    private static List<Direction> gravityunbound$perpendicular(Direction down) {
        return Arrays.asList(gravityunbound$PERPENDICULAR[down.ordinal()]);
    }

    @Unique
    private static Direction gravityunbound$down(BlockGetter getter, BlockPos pos) {
        return GravityFieldLookup.fluidDownAt(getter, pos);
    }

    // vanilla FlowingFluid.affectsFlow is private and trivial; reimplemented
    // to avoid shadowing a private method
    @Unique
    private boolean gravityunbound$affectsFlow(FluidState state) {
        return state.isEmpty() || state.getType().isSame((Fluid) (Object) this);
    }

    /**
     * THE FEED ARITHMETIC. Three relations move water between frames, and
     * their costs make every cycle strictly level-decreasing (so no
     * source-free water can sustain itself, and removal always drains):
     *   - planar lateral (same or coupled frame): net -1 per hop
     *   - cross-frame POUR (neighbor's own down points at us): net 0 — the
     *     level passes THROUGH an edge crossing instead of resetting; the
     *     pour graph alone is acyclic (sector downs never loop)
     *   - solid-backed SIDE-ENTRY (the convex-corner wrap): net -1
     * Full resets to 8 exist only for SAME-frame columns — pure vanilla.
     */

    /** Does the neighbor at {@code direction} pour into {@code pos} cross-frame? */
    @Unique
    private boolean gravityunbound$crossFeeds(LevelReader level, BlockPos pos, Direction direction) {
        BlockPos neighborPos = pos.relative(direction);
        FluidState neighbor = level.getFluidState(neighborPos);
        if (neighbor.isEmpty() || !neighbor.getType().isSame((Fluid) (Object) this)) {
            return false;
        }
        Direction neighborDown = gravityunbound$down(level, neighborPos);
        if (neighborDown != direction.getOpposite()) {
            return false;
        }
        if (neighborDown == gravityunbound$down(level, pos)) {
            return false; // same frame: vanilla's above-rule handles it
        }
        // mutual-pit guard: two cells whose fields point at EACH OTHER
        // (opposing attract fields) would sustain each other forever; only
        // a true source may feed against our own down.
        return neighbor.isSource() || gravityunbound$down(level, pos) != direction;
    }

    /**
     * Solid-backed side-entry (feed side): our own frame-below neighbor may
     * feed us laterally when both cells are IN a field, it lives in a
     * different (non-opposite) frame, and it rests directly on solid in its
     * own frame — a surface cell pouring over a one-block convex lip. The
     * solid-backing keeps this on the surface: open water never layers
     * outward. The in-field requirement keeps walls inside sideways fields
     * from overflowing into normal-gravity space.
     */
    @Unique
    private boolean gravityunbound$sideEntryFeeds(LevelReader level, BlockPos pos, Direction ourDown) {
        if (!GravityFieldLookup.hasFieldAt(level, pos)) {
            return false;
        }
        BlockPos neighborPos = pos.relative(ourDown);
        if (!GravityFieldLookup.hasFieldAt(level, neighborPos)) {
            return false;
        }
        FluidState neighbor = level.getFluidState(neighborPos);
        if (neighbor.isEmpty() || !neighbor.getType().isSame((Fluid) (Object) this)) {
            return false;
        }
        Direction neighborDown = gravityunbound$down(level, neighborPos);
        if (neighborDown == ourDown || neighborDown == ourDown.getOpposite()) {
            return false;
        }
        return level.getBlockState(neighborPos.relative(neighborDown)).blocksMotion();
    }

    /** Spread-side mirror of the side-entry (src is dst's frame-below). */
    @Unique
    private static boolean gravityunbound$sideEntrySpreadOk(LevelReader level, BlockPos src, BlockPos dst) {
        if (!GravityFieldLookup.hasFieldAt(level, dst) || !GravityFieldLookup.hasFieldAt(level, src)) {
            return false;
        }
        Direction srcDown = gravityunbound$down(level, src);
        Direction dstDown = gravityunbound$down(level, dst);
        if (srcDown == dstDown || srcDown == dstDown.getOpposite()) {
            return false;
        }
        return level.getBlockState(src.relative(srcDown)).blocksMotion();
    }

    // ------------------------------------------------------------------
    // spread(): the fall direction
    // ------------------------------------------------------------------

    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityunbound$spreadFallPos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityunbound$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;canSpreadTo(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/Fluid;)Z")
    )
    private boolean gravityunbound$spreadFallCheck(
        FlowingFluid self, BlockGetter getter, BlockPos fromPos, BlockState fromState,
        Direction direction, BlockPos toPos, BlockState toState, FluidState toFluid, Fluid fluid,
        Operation<Boolean> original
    ) {
        return original.call(self, getter, fromPos, fromState, gravityunbound$down(getter, fromPos), toPos, toState, toFluid, fluid);
    }

    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V")
    )
    private void gravityunbound$spreadFallInto(
        FlowingFluid self, LevelAccessor level, BlockPos pos, BlockState state,
        Direction direction, FluidState fluidState, Operation<Void> original
    ) {
        Direction down = level instanceof Level realLevel
            ? gravityunbound$down(realLevel, pos) : Direction.DOWN;
        original.call(self, level, pos, state, down, fluidState);
    }

    /**
     * Defer-to-column, frame-aware. Vanilla suppresses side-spreading for a
     * cell sitting over a "hole" so a falling column defers to the surface
     * beneath it. FALLING water keeps that behavior verbatim — a stream
     * landing on cross-frame field water must be absorbed and redirected,
     * not splash sideways over the boundary like a sheet on glass. LATERAL
     * (non-falling) water over a cross-frame below cell splits on what the
     * below cell HOLDS:
     * <ul>
     *   <li>OPEN same-type water (air- or water-backed in its own frame:
     *       a stream, the flooded interior): defer, exactly like the
     *       falling case — the two flows COMBINE at the seam (the
     *       pour/cross-feed relations carry the level across). Without
     *       this, the spreading ring around a stream treated another
     *       sector's water surface as solid ground and sheeted outward
     *       across the whole boundary — the "huge mess of water";</li>
     *   <li>a SOLID-BACKED surface film (same-type water resting directly
     *       on solid in its own frame), or no fluid at all (the bare solid
     *       lip): side-spread — the corner wrap's continuation: the rim
     *       cell of one face must keep spreading to carry flow onto the
     *       next. Round 57 deferred over ALL cross-frame water, which
     *       killed the wrap (fluidsim: cube coverage and the 26-cell
     *       bare-core shell regressed; the solid-backing distinction — the
     *       same criterion the side-entry feed uses to stay on the
     *       surface — restores both while keeping the sheet fix).</li>
     * </ul>
     */
    @WrapOperation(
        method = "spread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FlowingFluid;isWaterHole(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z")
    )
    private boolean gravityunbound$spreadHoleGate(
        FlowingFluid self, BlockGetter getter, Fluid fluid, BlockPos pos,
        BlockState state, BlockPos belowPos, BlockState belowState,
        Operation<Boolean> original,
        @Local(argsOnly = true) FluidState spreadingState
    ) {
        Direction belowDown = gravityunbound$down(getter, belowPos);
        if (GravityFieldLookup.hasSources(getter)
            && belowDown != gravityunbound$down(getter, pos)
            && !(spreadingState.hasProperty(FlowingFluid.FALLING)
                 && spreadingState.getValue(FlowingFluid.FALLING))
            && (!belowState.getFluidState().getType().isSame(fluid)
                || getter.getBlockState(belowPos.relative(belowDown)).blocksMotion())) {
            return false;
        }
        return original.call(self, getter, fluid, pos, state, belowPos, belowState);
    }

    // ------------------------------------------------------------------
    // getNewLiquid(): neighbor plane, column continuity, source formation
    // ------------------------------------------------------------------

    /**
     * Which neighbors feed this cell LATERALLY. Vanilla iterates its own
     * horizontal plane — implicitly "directions perpendicular to the
     * NEIGHBOR's down", valid only when every neighbor shares the frame. At
     * frame boundaries the feeding relation must be judged in the FEEDER's
     * frame: a neighbor at direction d couples laterally iff d is
     * perpendicular to THAT neighbor's down. Under uniform gravity this
     * reduces exactly to the 4-direction plane (fast path: untouched
     * vanilla iteration when no field sources exist or every consulted cell
     * is world-down).
     */
    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityunbound$newLiquidPlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos
    ) {
        if (!net.camacraft.gravityunbound.util.GravityFieldLookup.hasSources(level)) {
            return original.call(plane);
        }
        Direction ourDown = gravityunbound$down(level, pos);
        boolean allDown = ourDown == Direction.DOWN;
        boolean inField = GravityFieldLookup.hasFieldAt(level, pos);
        java.util.List<Direction> feeds = new java.util.ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            Direction neighborDown = gravityunbound$down(level, neighborPos);
            if (neighborDown != Direction.DOWN) {
                allDown = false;
            }
            if (gravityunbound$crossFeeds(level, pos, direction)) {
                feeds.add(direction); // cross-frame pour (amount wrap adds +1)
                continue;
            }
            if (direction == ourDown) {
                // our own frame-below feeds us only via the corner wrap
                if (gravityunbound$sideEntryFeeds(level, pos, ourDown)) {
                    feeds.add(direction);
                }
                continue;
            }
            if (neighborDown.getAxis() == direction.getAxis()) {
                continue; // parallel: column coupling, not lateral
            }
            // FIELD-BOUNDARY CUT: out-of-field water never laterally feeds
            // in-field cells — water enters a field only by falling in.
            // Without this, water seeping sideways OUT of a field (a rain
            // halo beside a falling sheet) feeds back into the boundary row
            // where the same-frame above-rule resets it to full: a
            // generator ring that exits and re-enters the field.
            if (inField && !GravityFieldLookup.hasFieldAt(level, neighborPos)) {
                continue;
            }
            feeds.add(direction);
        }
        return allDown ? original.call(plane) : feeds.iterator();
    }

    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityunbound$newLiquidBelow(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityunbound$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    /**
     * Pour-through contribution: a cross-frame pour passes the feeder's
     * LEVEL through the crossing (amount+1 here, the dropOff cancels it)
     * instead of granting a full falling column. The full reset stays
     * exclusive to same-frame columns (the vanilla above-rule below).
     */
    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;getAmount()I")
    )
    private int gravityunbound$feedAmount(
        FluidState neighbor, Operation<Integer> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos,
        @Local Direction direction
    ) {
        int amount = original.call(neighbor);
        if (net.camacraft.gravityunbound.util.GravityFieldLookup.hasSources(level)
            && gravityunbound$crossFeeds(level, pos, direction)) {
            return Math.min(8, amount + 1);
        }
        return amount;
    }

    @WrapOperation(
        method = "getNewLiquid",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityunbound$newLiquidAbove(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityunbound$down(level, instance);
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
    private boolean gravityunbound$newLiquidUpWall(
        FlowingFluid self, Direction direction, BlockGetter getter, BlockPos pos,
        BlockState state, BlockPos abovePos, BlockState aboveState, Operation<Boolean> original
    ) {
        if (!net.camacraft.gravityunbound.util.GravityFieldLookup.hasSources(getter)) {
            return original.call(self, direction, getter, pos, state, abovePos, aboveState);
        }
        Direction ourDown = gravityunbound$down(getter, pos);
        // the full-column branch may only fire for a SAME-FRAME above cell
        // (whose fluid genuinely falls into us) — cross-frame fluid at our
        // frame-up must not turn us into a full column; cross-frame pours
        // contribute level-preserving feeds instead (gravityunbound$feedAmount)
        if (gravityunbound$down(getter, abovePos) != ourDown) {
            return false;
        }
        return original.call(self, ourDown.getOpposite(), getter, pos, state, abovePos, aboveState);
    }

    // Source conversion (the infinite-water rule) lives in getNewLiquid's
    // own neighbor loop and is gated by FluidState.canConvertToSource.
    // Originally suppressed inside ANY field ("fields move water, they
    // never create it") — but that left a water planet un-healable: flowing
    // cells starve at sector boundaries under the cross-frame feed rules,
    // and with conversion off the resulting air pockets are PERMANENT.
    // In-field conversion is vanilla's own infinite-water rule expressed in
    // the frame (sourceNeighborCount already iterates the perpendicular
    // plane, the below-check the frame-below), so flooded regions now knit
    // themselves back into sources exactly like a vanilla pool. The one
    // suppression that stays is PLATE CELLS: a source minted inside a
    // plate is undrainable and blocks flow through it.
    @WrapOperation(
        method = "getNewLiquid",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;canConvertToSource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean gravityunbound$noRotatedSourceConversion(
        FluidState fluidState, Level level, BlockPos pos, Operation<Boolean> original
    ) {
        return !(level.getBlockState(pos).getBlock()
                instanceof net.camacraft.gravityunbound.plating.GravityPlatingBlock)
            && original.call(fluidState, level, pos);
    }

    // ------------------------------------------------------------------
    // getSpread()/getSlopeDistance(): the slope-hole search plane
    // ------------------------------------------------------------------

    /**
     * THE UP-ENTRY PROHIBITION. Vanilla fluid stability rests on water
     * never moving upward; with per-cell frames, "lateral" spread judged
     * only in the spreader's frame can climb another frame's up — a face
     * cell's plane includes world-UP when its down is horizontal, and one
     * such uphill hop lets vanilla's own above-rule reset the level to
     * full, forming source-free feed loops (the runaway flood that filled
     * the whole field and would not drain). The law that restores
     * acyclicity: water may NEVER spread into a cell from that cell's own
     * frame-below. Combined with acyclically-oriented sector frames (see
     * GravityCoreBlockEntity#fluidDownAt) every transfer chain terminates,
     * so spread stays bounded and removal of the source drains everything.
     * Note no world-down fast path here: a plain DOWN-frame rim cell
     * spreading outward into a sideways-frame cell is exactly such an
     * up-entry, so the filter must run whenever any field source exists.
     */
    @WrapOperation(
        method = "getSpread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityunbound$spreadPlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos
    ) {
        if (!net.camacraft.gravityunbound.util.GravityFieldLookup.hasSources(level)) {
            return original.call(plane);
        }
        return gravityunbound$spreadDirections(level, pos, gravityunbound$down(level, pos));
    }

    /**
     * Perpendicular plane of {@code down} minus up-entry directions —
     * except the solid-backed side-entry, which is the corner wrap's
     * spread leg.
     */
    @Unique
    private static Iterator<Direction> gravityunbound$spreadDirections(LevelReader level, BlockPos pos, Direction down) {
        java.util.List<Direction> dirs = new java.util.ArrayList<>(4);
        for (Direction direction : gravityunbound$PERPENDICULAR[down.ordinal()]) {
            BlockPos target = pos.relative(direction);
            if (gravityunbound$down(level, target) != direction.getOpposite()
                || gravityunbound$sideEntrySpreadOk(level, pos, target)) {
                dirs.add(direction);
            }
        }
        return dirs.iterator();
    }

    /**
     * Uniform in-field spreading: vanilla channels flow toward the nearest
     * hole (min slope distance), which starves plateau corners — fine on
     * terrain, wrong on a planet face that should be covered uniformly.
     * Forcing the hole flag true for in-field origins makes every direction
     * tie at distance 0, so in-field water spreads all ways (and skips the
     * slope recursion entirely). Vanilla terrain is untouched.
     */
    @WrapOperation(
        method = "getSpread",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/shorts/Short2BooleanMap;computeIfAbsent(SLit/unimi/dsi/fastutil/shorts/Short2BooleanFunction;)Z")
    )
    private boolean gravityunbound$uniformInFieldSpread(
        it.unimi.dsi.fastutil.shorts.Short2BooleanMap map, short key,
        it.unimi.dsi.fastutil.shorts.Short2BooleanFunction fn, Operation<Boolean> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos
    ) {
        if (GravityFieldLookup.hasFieldAt(level, pos)) {
            return true;
        }
        return original.call(map, key, fn);
    }

    @WrapOperation(
        method = "getSpread",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos gravityunbound$spreadHolePos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) Level level) {
        Direction down = gravityunbound$down(level, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down);
    }

    // the recursive slope probe walks the ORIGIN's plane but hops from the
    // current probe position — the up-entry filter must judge each hop at
    // the probe (mirrors the getSpread filter above)
    @WrapOperation(
        method = "getSlopeDistance",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityunbound$slopePlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) LevelReader level,
        @Local(argsOnly = true, ordinal = 0) BlockPos probePos,
        @Local(argsOnly = true, ordinal = 1) BlockPos originPos
    ) {
        if (!net.camacraft.gravityunbound.util.GravityFieldLookup.hasSources(level)) {
            return original.call(plane);
        }
        return gravityunbound$spreadDirections(level, probePos, gravityunbound$down(level, originPos));
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
    private BlockPos gravityunbound$slopeHoleCachePos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) LevelReader level) {
        Direction down = gravityunbound$down(level, instance);
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
    private boolean gravityunbound$holeCheckDirection(
        FlowingFluid self, Direction direction, BlockGetter getter, BlockPos pos,
        BlockState state, BlockPos holePos, BlockState holeState,
        Operation<Boolean> original
    ) {
        return original.call(self, gravityunbound$down(getter, pos), getter, pos, state, holePos, holeState);
    }

    @WrapOperation(
        method = "sourceNeighborCount",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction$Plane;iterator()Ljava/util/Iterator;")
    )
    private Iterator<Direction> gravityunbound$sourceNeighborPlane(
        Direction.Plane plane, Operation<Iterator<Direction>> original,
        @Local(argsOnly = true) LevelReader level, @Local(argsOnly = true) BlockPos pos
    ) {
        Direction down = gravityunbound$down(level, pos);
        return down == Direction.DOWN ? original.call(plane) : gravityunbound$perpendicular(down).iterator();
    }

    @WrapOperation(
        method = "hasSameAbove",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;")
    )
    private static BlockPos gravityunbound$sameAbovePos(BlockPos instance, Operation<BlockPos> original, @Local(argsOnly = true) BlockGetter getter) {
        Direction down = gravityunbound$down(getter, instance);
        return down == Direction.DOWN ? original.call(instance) : instance.relative(down.getOpposite());
    }

    // ------------------------------------------------------------------
    // tick(): container preservation
    // ------------------------------------------------------------------

    /**
     * Vanilla tick assumes non-source fluid lives in a plain LiquidBlock: it
     * replaces the whole block when the level changes (or dries up) — which
     * would DELETE a gravity plate holding flowing water. Plates store the
     * fluid level in their state; update it in place instead.
     */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")
    )
    private boolean gravityunbound$tickPreservePlating(
        Level level, BlockPos pos, BlockState newState, int flags, Operation<Boolean> original
    ) {
        BlockState current = level.getBlockState(pos);
        if (current.getBlock() instanceof net.camacraft.gravityunbound.plating.GravityPlatingBlock) {
            return original.call(level, pos,
                net.camacraft.gravityunbound.plating.GravityPlatingBlock.withFluid(current, newState.getFluidState()),
                flags);
        }
        return original.call(level, pos, newState, flags);
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
    private void gravityunbound$rotatedGetFlow(BlockGetter getter, BlockPos pos, FluidState state, CallbackInfoReturnable<Vec3> cir) {
        Direction down = gravityunbound$down(getter, pos);
        if (down == Direction.DOWN) {
            // vanilla math is correct for uniform DOWN — but a DOWN cell
            // BORDERING rotated water (a top-face rim beside a corner
            // column) still needs the cross-frame isolation below, or the
            // raw neighbor heights bend its current toward the boundary
            if (!GravityFieldLookup.hasSources(getter)) {
                return;
            }
            boolean rotatedNeighbor = false;
            for (Direction d : gravityunbound$PERPENDICULAR[Direction.DOWN.ordinal()]) {
                if (gravityunbound$down(getter, pos.relative(d)) != Direction.DOWN) {
                    rotatedNeighbor = true;
                    break;
                }
            }
            if (!rotatedNeighbor) {
                return;
            }
        }
        Direction up = down.getOpposite();

        Vec3 flow = Vec3.ZERO;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : gravityunbound$perpendicular(down)) {
            cursor.setWithOffset(pos, direction);
            FluidState neighbor = getter.getFluidState(cursor);
            if (!gravityunbound$affectsFlow(neighbor)) {
                continue;
            }
            // cross-frame isolation, mirroring the renderer's: a neighbor in
            // another frame doesn't share our surface. If it POURS into us
            // (its frame-down points at us — the rim feeding a face) it reads
            // FULL, so the visual current points away from the pour and down
            // the face; any other cross-frame water reads empty. Without
            // this, a side-face cell compared raw heights against the rim
            // water above it and the current pointed UP the face ("water
            // appears to flow upward before switching to the next face").
            float neighborHeight;
            Direction neighborDown = gravityunbound$down(getter, cursor);
            if (neighborDown != down && !neighbor.isEmpty()) {
                if (neighborDown == direction.getOpposite()) {
                    neighborHeight = 1.0F;
                } else {
                    continue;
                }
            } else {
                neighborHeight = neighbor.getOwnHeight();
            }
            float delta = 0.0F;
            if (neighborHeight == 0.0F) {
                if (!getter.getBlockState(cursor).blocksMotion()) {
                    BlockPos underNeighbor = cursor.relative(down);
                    FluidState under = getter.getFluidState(underNeighbor);
                    if (gravityunbound$affectsFlow(under)) {
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
            for (Direction direction : gravityunbound$perpendicular(down)) {
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

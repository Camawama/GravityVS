package net.camacraft.gravityunbound.sticky;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.init.GravityItems;
import net.camacraft.gravityunbound.sticky.client.StickyMimicClientExtensions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;

/**
 * Sticky Mimic — the Gravity Block Framework's "sticky ANY block"
 * experiment: an invisible shell block whose {@link StickyMimicBlockEntity}
 * stores an arbitrary captured {@link BlockState}, drawn by a client
 * {@code BlockEntityRenderer} rotated into any of the 24 grid orientations
 * ({@link #BOTTOM} x {@link #SPIN}, see {@link Rotation24}). Outline,
 * collision and support shapes are the mimicked state's shapes run through
 * {@link Rotation24#rotateShape}, so players can stand on, bump into and
 * target the rotated silhouette. Placed with the creative-only Sticky Caster
 * ({@link StickyCasterItem}).
 *
 * <p>Orientation comes from the CLICKED FACE ({@link Rotation24#fromClickedFace}):
 * the mimic's local down points into the surface that was clicked, so
 * surface-attached captures (redstone dust, carpets, rails, ...) lie flat on
 * whatever surface the placer clicks — the placer's gravity frame is only
 * used to pick the spin from the look direction.
 *
 * <p>Two-block-tall captures (blocks with
 * {@link BlockStateProperties#DOUBLE_BLOCK_HALF}: doors, tall plants, ...)
 * place as a PAIR of mimics — the lower half at the placement cell, the upper
 * half one step along the mimic's LOCAL up. Placement is denied when the
 * second cell is not replaceable; breaking either half removes both (like a
 * vanilla door), and toggling OPEN toggles both halves.
 *
 * <p><b>KNOWN LIMITATION — mimics are STATIC visual/collision/interaction
 * experiments.</b> The mimicked block's LOGIC does not run rotated: no
 * redstone power or conduction, no rail/fence/pane connection updates, no
 * crop growth, no random ticks, no scheduled ticks — the captured state is
 * frozen exactly as it was captured. The one interactive concession: if the
 * mimicked state has {@link BlockStateProperties#OPEN} (doors, trapdoors,
 * fence gates), right-clicking toggles it so those visually open/close in the
 * rotated frame. Blocks with block entities cannot be mimicked at all. Making
 * rotated block logic actually run is a future virtual-space architecture,
 * not something this shell attempts.
 */
public class StickyMimicBlock extends BaseEntityBlock {

    /** World direction the mimic's LOCAL DOWN points (shared with the chest). */
    public static final DirectionProperty BOTTOM = StickyChestBlock.BOTTOM;
    /** Quarter turns about the local vertical axis (shared with the chest). */
    public static final IntegerProperty SPIN = StickyChestBlock.SPIN;

    public StickyMimicBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.NONE)
            .strength(0.5f)
            // shapes depend on BE data: NEVER let the blockstate shape cache
            // freeze a BE-less full cube at registration time
            .dynamicShape()
            .noOcclusion()
            // creative experiment tool: the shell drops nothing
            .noLootTable()
        );
        this.registerDefaultState(this.stateDefinition.any()
            .setValue(BOTTOM, Direction.DOWN)
            .setValue(SPIN, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BOTTOM, SPIN);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // MUST stay inside getStateForPlacement: this is the (only) call
        // Valkyrien Skies wraps in PlayerUtil.transformPlayerTemporarily, so
        // the orientation helpers see ship-grid look angles (and a ship-grid
        // clicked face) on ships. Deriving the orientation anywhere else in
        // the placement flow double-rotates.
        Rotation24.Orientation orientation = Rotation24.fromClickedFace(
            context.getPlayer(), context.getLevel(), context.getClickedPos(),
            context.getClickedFace()
        );
        BlockState state = this.defaultBlockState()
            .setValue(BOTTOM, orientation.bottom())
            .setValue(SPIN, orientation.spin());

        // two-block-tall captures (doors, tall plants) need the cell one step
        // along the mimic's LOCAL up for the second half — deny placement if
        // it isn't free
        BlockState captured = capturedStateFromStack(context.getItemInHand(), context.getLevel());
        if (captured != null && captured.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos upperPos = context.getClickedPos().relative(
                Rotation24.localToWorld(Direction.UP, orientation.bottom(), orientation.spin())
            );
            Level level = context.getLevel();
            if (!level.isInWorldBounds(upperPos) || !level.getBlockState(upperPos).canBeReplaced()) {
                Player player = context.getPlayer();
                if (player != null && !level.isClientSide) {
                    player.displayClientMessage(
                        Component.translatable("gravity_changer.caster.no_room", captured.getBlock().getName()),
                        false
                    );
                }
                return null;
            }
        }
        return state;
    }

    /**
     * Places the UPPER-half mimic for two-block-tall captures. Runs after
     * {@code BlockItem.updateCustomBlockEntityTag} merged the captured state
     * into this shell's BE, so the capture is already readable here. Mirrors
     * {@code DoorBlock.setPlacedBy}, but "above" is the mimic's LOCAL up.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic)) {
            return;
        }
        BlockState captured = mimic.getMimickedState();
        if (captured == null || !captured.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return;
        }
        // normalize: the capture may have been taken from an UPPER half
        mimic.setMimickedStateAndSync(
            captured.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        BlockPos upperPos = pos.relative(
            Rotation24.localToWorld(Direction.UP, state.getValue(BOTTOM), state.getValue(SPIN)));
        level.setBlock(upperPos, state, Block.UPDATE_ALL);
        if (level.getBlockEntity(upperPos) instanceof StickyMimicBlockEntity upperMimic) {
            upperMimic.setMimickedStateAndSync(
                captured.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
    }

    /** The captured state riding in the caster stack's BlockEntityTag, if any. */
    @Nullable
    private static BlockState capturedStateFromStack(ItemStack stack, Level level) {
        CompoundTag blockEntityTag = BlockItem.getBlockEntityData(stack);
        if (blockEntityTag == null || !blockEntityTag.contains(StickyMimicBlockEntity.TAG_MIMIC, Tag.TAG_COMPOUND)) {
            return null;
        }
        BlockState captured = NbtUtils.readBlockState(
            level.holderLookup(Registries.BLOCK), blockEntityTag.getCompound(StickyMimicBlockEntity.TAG_MIMIC));
        return captured.isAir() ? null : captured;
    }

    /**
     * The other half of a two-block-tall mimic pair, or null. The partner
     * sits one step along the mimic's LOCAL up (from the LOWER half) or LOCAL
     * down (from the UPPER half), must be a mimic shell in the SAME
     * orientation, and must mimic the complementary half of the same block.
     * The shell-state check doubles as the recursion guard for
     * {@link #onRemove}: a half that has already been replaced no longer
     * matches, so the removal chain stops after one hop.
     */
    @Nullable
    private StickyMimicBlockEntity findPairedHalf(Level level, BlockPos pos, BlockState shellState, @Nullable BlockState captured) {
        if (captured == null || !captured.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return null;
        }
        Direction bottom = shellState.getValue(BOTTOM);
        int spin = shellState.getValue(SPIN);
        Direction localUp = Rotation24.localToWorld(Direction.UP, bottom, spin);
        DoubleBlockHalf half = captured.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
        BlockPos partnerPos = pos.relative(half == DoubleBlockHalf.LOWER ? localUp : localUp.getOpposite());

        BlockState partnerShell = level.getBlockState(partnerPos);
        if (!partnerShell.is(this)
            || partnerShell.getValue(BOTTOM) != bottom
            || partnerShell.getValue(SPIN) != spin) {
            return null;
        }
        if (level.getBlockEntity(partnerPos) instanceof StickyMimicBlockEntity partner) {
            BlockState partnerCaptured = partner.getMimickedState();
            if (partnerCaptured != null
                && partnerCaptured.is(captured.getBlock())
                && partnerCaptured.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && partnerCaptured.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != half) {
                return partner;
            }
        }
        return null;
    }

    /**
     * Breaking (or otherwise replacing) either half of a two-block-tall mimic
     * pair removes both, like a vanilla door. Server-side only — the chunk
     * calls {@code onRemove} before discarding this position's BE, so the
     * capture is still readable; the client learns of both removals through
     * the ordinary block-update packets.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())
            && level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic) {
            StickyMimicBlockEntity partner = findPairedHalf(level, pos, state, mimic.getMimickedState());
            if (partner != null) {
                level.removeBlock(partner.getBlockPos(), false);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    // ---- shapes: the mimicked state's shapes, rotated ----

    private VoxelShape mimicShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, boolean collision) {
        if (level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic) {
            BlockState mimicked = mimic.getMimickedState();
            if (mimicked != null && !mimicked.isAir() && !mimicked.is(this)) {
                try {
                    // evaluated at the mimic's own pos — position-sensitive
                    // shapes may look at neighbors and disagree; any failure
                    // falls back to a full cube
                    VoxelShape shape = collision
                        ? mimicked.getCollisionShape(level, pos, context)
                        : mimicked.getShape(level, pos, context);
                    return Rotation24.rotateShape(shape, state.getValue(BOTTOM), state.getValue(SPIN));
                } catch (Exception e) {
                    return Shapes.block();
                }
            }
        }
        // no BE / nothing captured yet: a plain full cube keeps the shell
        // targetable and breakable
        return Shapes.block();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return mimicShape(state, level, pos, context, false);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return mimicShape(state, level, pos, context, true);
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return mimicShape(state, level, pos, CollisionContext.empty(), true);
    }

    // ---- presentation ----

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // drawn entirely by StickyMimicRenderer
        return RenderShape.INVISIBLE;
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        // step/break/place sounds of the mimicked block
        if (level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic) {
            BlockState mimicked = mimic.getMimickedState();
            if (mimicked != null && !mimicked.isAir() && !mimicked.is(this)) {
                return mimicked.getSoundType();
            }
        }
        return super.getSoundType(state, level, pos, entity);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        // pick-block hands back the caster, pre-loaded with this mimic's
        // captured state so it immediately places more of the same
        ItemStack stack = new ItemStack(GravityItems.STICKY_CASTER.get());
        if (level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic) {
            BlockState mimicked = mimic.getMimickedState();
            if (mimicked != null && !mimicked.isAir() && !mimicked.is(this)) {
                CompoundTag blockEntityTag = new CompoundTag();
                blockEntityTag.put(StickyMimicBlockEntity.TAG_MIMIC, NbtUtils.writeBlockState(mimicked));
                stack.addTagElement(BlockItem.BLOCK_ENTITY_TAG, blockEntityTag);
            }
        }
        return stack;
    }

    @Override
    public void initializeClient(Consumer<IClientBlockExtensions> consumer) {
        // destroy/hit particles of the CAPTURED state over the ROTATED
        // silhouette (the shell is invisible — vanilla would spawn
        // missing-texture particles, or none at all for hit cracks)
        consumer.accept(new StickyMimicClientExtensions());
    }

    // ---- interaction ----

    @Override
    public InteractionResult use(
        BlockState state, Level level, BlockPos pos, Player player,
        InteractionHand hand, BlockHitResult hit
    ) {
        if (level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic) {
            BlockState mimicked = mimic.getMimickedState();
            if (mimicked != null && mimicked.hasProperty(BlockStateProperties.OPEN)) {
                if (!level.isClientSide) {
                    boolean open = !mimicked.getValue(BlockStateProperties.OPEN);
                    mimic.setMimickedStateAndSync(mimicked.setValue(BlockStateProperties.OPEN, open));
                    // a two-tall pair (door) opens/closes as one — the partner
                    // is SET (not cycled) so desynced halves re-converge
                    StickyMimicBlockEntity partner = findPairedHalf(level, pos, state, mimicked);
                    if (partner != null && partner.getMimickedState().hasProperty(BlockStateProperties.OPEN)) {
                        partner.setMimickedStateAndSync(
                            partner.getMimickedState().setValue(BlockStateProperties.OPEN, open));
                    }
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return InteractionResult.PASS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StickyMimicBlockEntity(pos, state);
    }
}

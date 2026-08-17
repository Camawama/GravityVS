package net.camacraft.gravityunbound.sticky;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the {@link StickyMimicBlock}: stores the mimicked
 * {@link BlockState}. The {@link Rotation24} orientation of the mimic lives
 * in the shell block's BOTTOM/SPIN blockstate properties (it must be derived
 * inside {@code getStateForPlacement} — the only spot Valkyrien Skies wraps
 * with its temporary ship-grid player transform — so it cannot be a
 * BE-placement-time value); {@link #getBottom()}/{@link #getSpin()} expose it
 * from here for the renderer and shapes.
 *
 * The mimicked state is saved with {@code NbtUtils.writeBlockState} and read
 * back through the level's block {@code HolderGetter}, and synced to the
 * client via the standard update tag/packet pair like the mod's other BEs.
 */
public class StickyMimicBlockEntity extends BlockEntity {

    public static final String TAG_MIMIC = "mimic";

    private BlockState mimickedState = Blocks.AIR.defaultBlockState();

    public StickyMimicBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.STICKY_MIMIC_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(TAG_MIMIC, Tag.TAG_COMPOUND)) {
            // the level is null while chunk NBT is deserializing — fall back
            // to the built-in registry lookup (vanilla PistonMovingBlockEntity
            // does the same)
            HolderGetter<Block> blocks = this.level != null
                ? this.level.holderLookup(Registries.BLOCK)
                : BuiltInRegistries.BLOCK.asLookup();
            this.mimickedState = NbtUtils.readBlockState(blocks, tag.getCompound(TAG_MIMIC));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.mimickedState.isAir()) {
            tag.put(TAG_MIMIC, NbtUtils.writeBlockState(this.mimickedState));
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }

    public BlockState getMimickedState() {
        return this.mimickedState;
    }

    /**
     * Server-side setter: stores the state, marks dirty and pushes the BE
     * update packet to watchers (same sync path as the mod's other BEs).
     */
    public void setMimickedStateAndSync(BlockState state) {
        this.mimickedState = state;
        Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        setChanged();
        ((ServerChunkCache) level.getChunkSource()).blockChanged(getBlockPos());
    }

    /** The orientation's bottom face, read from the shell blockstate. */
    public Direction getBottom() {
        BlockState state = getBlockState();
        return state.hasProperty(StickyMimicBlock.BOTTOM)
            ? state.getValue(StickyMimicBlock.BOTTOM)
            : Direction.DOWN;
    }

    /** The orientation's spin, read from the shell blockstate. */
    public int getSpin() {
        BlockState state = getBlockState();
        return state.hasProperty(StickyMimicBlock.SPIN)
            ? state.getValue(StickyMimicBlock.SPIN)
            : 0;
    }
}

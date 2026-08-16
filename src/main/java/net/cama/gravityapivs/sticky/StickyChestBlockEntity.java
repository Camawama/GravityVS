package net.cama.gravityapivs.sticky;

import net.cama.gravityapivs.init.GravityBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestLidController;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of the {@link StickyChestBlock}: a self-contained mirror of
 * vanilla {@code ChestBlockEntity} (27 slots, lid animation, opener counting
 * with open/close sounds) without any double-chest handling. Orientation is
 * irrelevant here — the inventory and lid state are orientation-agnostic; the
 * renderer applies the {@link Rotation24} pose.
 */
public class StickyChestBlockEntity extends RandomizableContainerBlockEntity implements LidBlockEntity {

    /** Block event id driving the lid animation (vanilla chest convention). */
    public static final int EVENT_SET_OPEN_COUNT = 1;

    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
    private final ChestLidController chestLidController = new ChestLidController();
    private final ContainerOpenersCounter openersCounter = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            playSound(level, pos, SoundEvents.CHEST_OPEN);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            playSound(level, pos, SoundEvents.CHEST_CLOSE);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int oldCount, int newCount) {
            if (oldCount != newCount) {
                level.blockEvent(pos, state.getBlock(), EVENT_SET_OPEN_COUNT, newCount);
            }
        }

        @Override
        protected boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu menu
                && menu.getContainer() == StickyChestBlockEntity.this;
        }
    };

    public StickyChestBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.STICKY_CHEST_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public int getContainerSize() {
        return 27;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.gravityapivs.sticky_chest");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, this.items);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, this.items);
        }
    }

    // ---- lid animation ----

    public static void clientTick(Level level, BlockPos pos, BlockState state, StickyChestBlockEntity chest) {
        chest.chestLidController.tickLid();
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == EVENT_SET_OPEN_COUNT) {
            this.chestLidController.shouldBeOpen(param > 0);
            return true;
        }
        return super.triggerEvent(id, param);
    }

    @Override
    public float getOpenNess(float partialTick) {
        return this.chestLidController.getOpenness(partialTick);
    }

    // ---- opener counting (open/close sounds + lid events) ----

    public static void serverTick(Level level, BlockPos pos, BlockState state, StickyChestBlockEntity chest) {
        // vanilla rechecks every 5 ticks while the chest is open (via
        // scheduled block ticks); mirror that cadence from the BE ticker so
        // the counter also recovers if a scheduled tick is lost
        if (chest.openersCounter.getOpenerCount() > 0
            && Math.floorMod(level.getGameTime() + pos.asLong(), 5L) == 0L) {
            chest.recheckOpen();
        }
    }

    @Override
    public void startOpen(Player player) {
        if (!this.isRemoved() && !player.isSpectator()) {
            this.openersCounter.incrementOpeners(player, this.getLevel(), this.getBlockPos(), this.getBlockState());
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (!this.isRemoved() && !player.isSpectator()) {
            this.openersCounter.decrementOpeners(player, this.getLevel(), this.getBlockPos(), this.getBlockState());
        }
    }

    public void recheckOpen() {
        if (!this.isRemoved()) {
            this.openersCounter.recheckOpeners(this.getLevel(), this.getBlockPos(), this.getBlockState());
        }
    }

    static void playSound(Level level, BlockPos pos, SoundEvent sound) {
        // direction-agnostic: always the block center
        level.playSound(null,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            sound, SoundSource.BLOCKS,
            0.5f, level.random.nextFloat() * 0.1f + 0.9f);
    }
}

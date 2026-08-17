package net.camacraft.gravityunbound.sticky;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Sticky Caster — the creative-only experimentation tool for the
 * {@link StickyMimicBlock}:
 *
 * <ul>
 *   <li>SNEAK-right-click any block to capture its exact {@link BlockState}
 *       (blocks with block entities are politely refused).</li>
 *   <li>Right-click a surface to place a sticky mimic of the captured state,
 *       sitting ON the clicked face, spun toward the placer's look
 *       ({@code Rotation24.fromClickedFace}). Two-block-tall captures (doors,
 *       tall plants) place both halves along the mimic's local up.</li>
 * </ul>
 *
 * Placement deliberately goes through the normal {@code BlockItem.place}
 * flow: the captured state travels in the stack's {@code BlockEntityTag}
 * (vanilla merges it into the freshly created BE), and the orientation is
 * derived by {@code StickyMimicBlock.getStateForPlacement} — the one call
 * Valkyrien Skies wraps with its temporary ship-grid player transform, so
 * ship placements orient correctly. The caster is never consumed.
 *
 * <p><b>KNOWN LIMITATION</b> (also spelled out in the tooltip): mimics are
 * visual/collision/interaction (OPEN toggle) experiments — the mimicked
 * block's LOGIC (redstone power, growth, rail function, ...) does not run
 * rotated, and blocks with block entities can't be captured yet. See
 * {@link StickyMimicBlock}.
 */
public class StickyCasterItem extends BlockItem {

    public StickyCasterItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public String getDescriptionId() {
        // named as an item ("Sticky Caster"), not after the shell block
        return this.getOrCreateDescriptionId();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        // sneak-use: capture the clicked block
        if (player != null && player.isSecondaryUseActive()) {
            BlockState target = level.getBlockState(context.getClickedPos());
            if (target.isAir()) {
                return InteractionResult.PASS;
            }
            if (target.hasBlockEntity()) {
                if (!level.isClientSide) {
                    player.displayClientMessage(
                        Component.translatable("gravity_changer.caster.block_entity_denied",
                            target.getBlock().getName()), false
                    );
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            CompoundTag blockEntityTag = new CompoundTag();
            blockEntityTag.put(StickyMimicBlockEntity.TAG_MIMIC, NbtUtils.writeBlockState(target));
            stack.addTagElement(BlockItem.BLOCK_ENTITY_TAG, blockEntityTag);
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("gravity_changer.caster.captured", target.getBlock().getName()), false
                );
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // plain use: place a mimic of the captured state
        if (getCapturedStateTag(stack) == null) {
            if (player != null && !level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("gravity_changer.caster.nothing_captured"), false
                );
            }
            return InteractionResult.FAIL;
        }
        int count = stack.getCount();
        InteractionResult result = super.useOn(context);
        // creative tool: never consumed (BlockItem.place shrinks the stack
        // for non-creative players)
        stack.setCount(count);
        return result;
    }

    @Nullable
    private static CompoundTag getCapturedStateTag(ItemStack stack) {
        CompoundTag blockEntityTag = BlockItem.getBlockEntityData(stack);
        return blockEntityTag != null && blockEntityTag.contains(StickyMimicBlockEntity.TAG_MIMIC)
            ? blockEntityTag.getCompound(StickyMimicBlockEntity.TAG_MIMIC)
            : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        CompoundTag captured = getCapturedStateTag(stack);
        Component name = null;
        if (captured != null) {
            ResourceLocation id = ResourceLocation.tryParse(captured.getString("Name"));
            Block block = id != null ? ForgeRegistries.BLOCKS.getValue(id) : null;
            if (block != null) {
                name = block.getName();
            }
        }
        tooltip.add(
            Component.translatable("gravity_changer.caster.tooltip",
                name != null ? name : Component.translatable("gravity_changer.caster.tooltip.nothing"))
            .withStyle(ChatFormatting.GRAY)
        );
        // be upfront about the experiment's current limits
        tooltip.add(Component.translatable("gravity_changer.caster.tooltip.limit.0").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("gravity_changer.caster.tooltip.limit.1").withStyle(ChatFormatting.DARK_GRAY));
    }
}

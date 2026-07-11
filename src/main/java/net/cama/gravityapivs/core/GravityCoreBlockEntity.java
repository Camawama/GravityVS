package net.cama.gravityapivs.core;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.cama.gravityapivs.EntityTags;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.api.RotationParameters;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.init.GravityBlocks;
import net.cama.gravityapivs.util.GCUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GravityCoreBlockEntity extends BlockEntity {

    private static final RotationParameters CORE_ROTATION_PARAMS = new RotationParameters(true, true, 300);
    // cores sit slightly below plating priority so plates win when both apply
    private static final double CORE_BASE_PRIORITY = 990;

    private int range;
    private boolean attracting = true;

    public GravityCoreBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_CORE_BLOCK_ENTITY.get(), pos, state);
        this.range = GravityConfig.gravityCoreDefaultRange.get();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("range")) {
            range = Mth.clamp(tag.getInt("range"), 1, GravityConfig.gravityCoreMaxRange.get());
        }
        if (tag.contains("attracting")) {
            attracting = tag.getBoolean("attracting");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("range", range);
        tag.putBoolean("attracting", attracting);
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

    public static void tick(Level world, BlockPos blockPos, BlockState blockState, GravityCoreBlockEntity be) {
        Ship ownShip = VSGameUtilsKt.getShipManagingPos(world, blockPos);

        // core center in world space (ship transform aware)
        Vec3 center = Vec3.atCenterOf(blockPos);
        if (ownShip != null) {
            Vector3d c = new Vector3d(center.x, center.y, center.z);
            ownShip.getTransform().getShipToWorldMatrix().transformPosition(c);
            center = new Vec3(c.x, c.y, c.z);
        }

        double range = be.range;
        AABB searchBox = new AABB(
            center.x - range, center.y - range, center.z - range,
            center.x + range, center.y + range, center.z + range
        );

        be.applyToEntities(world, center, range, searchBox);

        if (!world.isClientSide() && GravityConfig.gravityCoreAffectsShips.get()) {
            be.applyToShips(world, ownShip, center, range, searchBox);
        }
    }

    private void applyToEntities(Level world, Vec3 center, double range, AABB searchBox) {
        List<Entity> entities = world.getEntitiesOfClass(
            Entity.class, searchBox, EntityTags::canChangeGravity
        );

        double rangeSq = range * range;

        for (Entity entity : entities) {
            if (world.isClientSide() && !entity.isControlledByLocalInstance() && !GCUtil.isClientPlayer(entity)) {
                continue;
            }

            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
            if (comp == null) {
                continue;
            }

            Vec3 toCenter = center.subtract(entity.position());
            double distSq = toCenter.lengthSqr();
            if (distSq > rangeSq || distSq < 0.25) {
                // outside the field, or standing inside the core block itself
                continue;
            }

            double distance = Math.sqrt(distSq);
            Vec3 direction = attracting
                ? toCenter.scale(1.0 / distance)
                : toCenter.scale(-1.0 / distance);

            comp.applyGravityDirectionEffect(
                direction, CORE_ROTATION_PARAMS, CORE_BASE_PRIORITY - distance
            );
        }
    }

    private void applyToShips(Level world, @Nullable Ship ownShip, Vec3 center, double range, AABB searchBox) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }

        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(serverLevel, searchBox)) {
            if (ship == ownShip || !(ship instanceof ServerShip serverShip)) {
                continue;
            }

            Vector3d shipPos = new Vector3d(serverShip.getTransform().getPositionInWorld());
            Vector3d toCenter = new Vector3d(center.x, center.y, center.z).sub(shipPos);
            double distance = toCenter.length();
            if (distance > range || distance < 1.0) {
                continue;
            }

            double mass = serverShip.getInertiaData().getMass();
            double acceleration = 10.0 * GravityConfig.gravityCoreShipForceMultiplier.get(); // ~1g in m/s^2
            Vector3d force = toCenter.normalize(new Vector3d()).mul(mass * acceleration);
            if (!attracting) {
                force.negate();
            }

            GravityCoreForceInducer.getOrCreate(serverShip).queueForce(force);
        }
    }

    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack handItem = player.getItemInHand(hand);

        if (handItem.getItem() == Items.AIR) {
            if (player.isShiftKeyDown()) {
                if (range > 1) {
                    range -= 1;
                    if (!player.isCreative()) {
                        player.getInventory().add(new ItemStack(Items.AMETHYST_CLUSTER));
                    }
                }
            }
            else {
                attracting = !attracting;
            }
        }
        else if (handItem.getItem() == Items.AMETHYST_CLUSTER) {
            if (range >= GravityConfig.gravityCoreMaxRange.get()) {
                player.displayClientMessage(
                    Component.translatable("gravity_changer.core.max_range"), true
                );
                return InteractionResult.FAIL;
            }
            if (!player.isCreative()) {
                handItem.shrink(1);
            }
            range += 1;
        }
        else {
            player.displayClientMessage(
                Component.translatable("gravity_changer.plate.wrong_interaction"), true
            );
            return InteractionResult.FAIL;
        }

        sync();

        player.displayClientMessage(
            Component.translatable(
                "gravity_changer.core.status",
                range,
                net.cama.gravityapivs.plating.GravityPlatingBlockEntity.getForceText(attracting)
            ),
            true
        );

        return InteractionResult.SUCCESS;
    }

    private void sync() {
        Level world = getLevel();
        if (world == null || world.isClientSide()) {
            return;
        }
        setChanged();
        ((ServerChunkCache) world.getChunkSource()).blockChanged(this.getBlockPos());
    }
}

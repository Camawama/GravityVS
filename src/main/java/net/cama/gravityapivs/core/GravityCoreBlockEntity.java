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
    // glow-ink-sac toggle: render the field as radially flowing particles
    private boolean showParticles = false;

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
        showParticles = tag.getBoolean("showParticles");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("range", range);
        tag.putBoolean("attracting", attracting);
        tag.putBoolean("showParticles", showParticles);
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

        if (world.isClientSide()) {
            be.spawnFieldParticles(world, center, range);
        }
        else if (GravityConfig.gravityCoreAffectsShips.get()) {
            be.applyToShips(world, ownShip, center, range, searchBox);
        }
    }

    /**
     * Field visualization (toggled with a glow ink sac): particles spawned
     * throughout the field sphere, drifting radially — blue soul flames flowing
     * inward for attract, orange flames flowing outward for repulse. The radial
     * flow makes the "unlocked" spherical field readable at a glance.
     */
    private void spawnFieldParticles(Level world, Vec3 center, double range) {
        if (!showParticles) {
            return;
        }
        var random = world.getRandom();

        int count = Mth.clamp((int) range, 4, 12);
        for (int i = 0; i < count; i++) {
            // uniform direction, cbrt-weighted distance = uniform in the volume
            double theta = random.nextDouble() * Math.PI * 2.0;
            double cosPhi = random.nextDouble() * 2.0 - 1.0;
            double sinPhi = Math.sqrt(1.0 - cosPhi * cosPhi);
            Vec3 dir = new Vec3(sinPhi * Math.cos(theta), cosPhi, sinPhi * Math.sin(theta));

            double distance = range * Math.cbrt(random.nextDouble());
            if (distance < 1.0) {
                continue;
            }

            Vec3 pos = center.add(dir.scale(distance));
            // attract: flow toward the core; repulse: flow away
            Vec3 vel = dir.scale(attracting ? -0.1 : 0.1);

            world.addParticle(
                attracting
                    ? net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME
                    : net.minecraft.core.particles.ParticleTypes.FLAME,
                pos.x, pos.y, pos.z, vel.x, vel.y, vel.z
            );
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
            // must be LOADED: attachment access on an unloaded ServerShip throws
            if (ship == ownShip || !(ship instanceof org.valkyrienskies.core.api.ships.LoadedServerShip serverShip)) {
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
        else if (handItem.getItem() == Items.GLOW_INK_SAC) {
            showParticles = !showParticles;
            if (showParticles && !player.isCreative()) {
                handItem.shrink(1);
            }
            sync();
            player.displayClientMessage(
                Component.translatable(showParticles
                    ? "gravity_changer.field_visual.on"
                    : "gravity_changer.field_visual.off"),
                true
            );
            return InteractionResult.SUCCESS;
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

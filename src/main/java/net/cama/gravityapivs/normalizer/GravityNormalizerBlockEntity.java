package net.cama.gravityapivs.normalizer;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
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
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
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

/**
 * Ship-local "down" for a cubic zone around the block (see
 * {@link GravityNormalizerBlock}). The zone and the down direction live in
 * GRID space: on a Valkyrien Skies ship both rotate with the ship, giving the
 * crew natural ship-relative gravity through any maneuver.
 *
 * The field is deliberately UNIFORM (a normalizer's whole point is one
 * consistent down) and sits ABOVE plating/core priority inside its zone, so a
 * normalized room stays normalized even when exterior plating fields bleed
 * into it.
 */
public class GravityNormalizerBlockEntity extends BlockEntity {

    private static final RotationParameters NORMALIZER_ROTATION_PARAMS = new RotationParameters(true, true, 300);
    // above plating (1000 - distance) and cores (990 - distance) by more than
    // the capability's BLEND_RANGE (5), so inside the zone the normalizer
    // alone defines down — no blending with exterior fields
    private static final double NORMALIZER_PRIORITY = 1010;

    // GRID-local down direction the zone enforces
    private Direction localDown = Direction.DOWN;
    // cubic zone half-extent, in blocks
    private int range;
    private boolean showParticles = false;

    // client BEs stay inert until authoritative data arrives (same rollback
    // protection as plating/core — see GravityPlatingBlockEntity)
    private boolean dataInitialized = false;

    // entity-query staggering, same scheme as GravityPlatingBlockEntity
    private @Nullable List<Entity> cachedEntities = null;
    private long entitiesCacheExpiry = Long.MIN_VALUE;

    public GravityNormalizerBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_NORMALIZER_BLOCK_ENTITY.get(), pos, state);
        this.range = GravityConfig.normalizerDefaultRange.get();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        dataInitialized = true;
        if (tag.contains("range")) {
            range = Mth.clamp(tag.getInt("range"), 1, GravityConfig.normalizerMaxRange.get());
        }
        if (tag.contains("localDown")) {
            Direction dir = Direction.byName(tag.getString("localDown"));
            if (dir != null) {
                localDown = dir;
            }
        }
        showParticles = tag.getBoolean("showParticles");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("range", range);
        tag.putString("localDown", localDown.getName());
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

    /** The zone in GRID coordinates (shipyard space on ships). */
    private AABB getZoneBox() {
        Vec3 center = Vec3.atCenterOf(getBlockPos());
        return new AABB(
            center.x - range, center.y - range, center.z - range,
            center.x + range, center.y + range, center.z + range
        );
    }

    public static void tick(Level world, BlockPos blockPos, BlockState blockState, GravityNormalizerBlockEntity be) {
        if (world.isClientSide() && !be.dataInitialized) {
            return;
        }

        Ship ship = VSGameUtilsKt.getShipManagingPos(world, blockPos);

        AABB zone = be.getZoneBox();
        AABB searchBox = zone;
        if (ship != null) {
            searchBox = gravityapivs$shipToWorldBox(ship, zone);
        }

        // the GRID-local down, expressed in world space this tick — rotates
        // with the ship, which is the whole point of the normalizer
        Vec3 worldDown = Vec3.atLowerCornerOf(be.localDown.getNormal());
        if (ship != null) {
            Vector3d d = new Vector3d(worldDown.x, worldDown.y, worldDown.z);
            ship.getTransform().getShipToWorldMatrix().transformDirection(d);
            d.normalize();
            worldDown = new Vec3(d.x, d.y, d.z);
        }

        if (world.isClientSide()) {
            if (be.showParticles) {
                // reuse the plate visual: the zone box with flow along the
                // local down (grid-space geometry; the renderer applies the
                // ship's render transform)
                net.cama.gravityapivs.util.FieldVisuals.submitPlate(
                    world, blockPos, be.localDown, zone, be.localDown, true, ship
                );
            }
        }

        List<Entity> entities = be.cachedEntities;
        long gameTime = world.getGameTime();
        if (entities == null || gameTime >= be.entitiesCacheExpiry) {
            entities = world.getEntitiesOfClass(
                Entity.class, searchBox, EntityTags::canChangeGravity
            );
            be.cachedEntities = entities;
            be.entitiesCacheExpiry = gameTime + (Math.floorMod(gameTime + blockPos.hashCode(), 2L) == 0 ? 2 : 1);
        }

        for (Entity entity : entities) {
            if (entity.isRemoved()) {
                continue;
            }
            if (world.isClientSide() && !entity.isControlledByLocalInstance() && !GCUtil.isClientPlayer(entity)) {
                continue;
            }

            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
            if (comp == null) {
                continue;
            }

            // membership is tested in GRID space so the zone is exactly the
            // cube around the block even on a rotated ship
            Vec3 testingPos = entity.position();
            if (ship != null) {
                Vector3d p = new Vector3d(testingPos.x, testingPos.y, testingPos.z);
                ship.getTransform().getWorldToShipMatrix().transformPosition(p);
                testingPos = new Vec3(p.x, p.y, p.z);
            }
            if (!zone.contains(testingPos)) {
                continue;
            }

            comp.applyGravityDirectionEffect(
                worldDown, NORMALIZER_ROTATION_PARAMS, NORMALIZER_PRIORITY
            );
        }
    }

    private static AABB gravityapivs$shipToWorldBox(Ship ship, AABB box) {
        Vector3d[] corners = new Vector3d[] {
            new Vector3d(box.minX, box.minY, box.minZ), new Vector3d(box.maxX, box.minY, box.minZ),
            new Vector3d(box.minX, box.maxY, box.minZ), new Vector3d(box.maxX, box.maxY, box.minZ),
            new Vector3d(box.minX, box.minY, box.maxZ), new Vector3d(box.maxX, box.minY, box.maxZ),
            new Vector3d(box.minX, box.maxY, box.maxZ), new Vector3d(box.maxX, box.maxY, box.maxZ)
        };
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vector3d corner : corners) {
            ship.getTransform().getShipToWorldMatrix().transformPosition(corner);
            minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack handItem = player.getItemInHand(hand);

        if (handItem.getItem() == Items.AIR) {
            if (player.isShiftKeyDown()) {
                if (range > 1) {
                    range -= 1;
                    if (!player.isCreative()) {
                        net.cama.gravityapivs.plating.GravityPlatingBlockEntity.gravityapivs$refund(
                            player, new ItemStack(Items.AMETHYST_CLUSTER)
                        );
                    }
                }
            }
            else {
                // cycle the grid-local down direction through all six
                localDown = Direction.from3DDataValue((localDown.get3DDataValue() + 1) % 6);
            }
        }
        else if (handItem.getItem() == Items.AMETHYST_CLUSTER) {
            if (range >= GravityConfig.normalizerMaxRange.get()) {
                player.displayClientMessage(
                    Component.translatable("gravity_changer.normalizer.max_range"), true
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
                "gravity_changer.normalizer.status",
                GCUtil.getDirectionText(localDown),
                range
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

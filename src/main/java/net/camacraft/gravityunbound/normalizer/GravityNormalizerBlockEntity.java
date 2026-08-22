package net.camacraft.gravityunbound.normalizer;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.camacraft.gravityunbound.EntityTags;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.api.RotationParameters;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.util.GCUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
public class GravityNormalizerBlockEntity extends BlockEntity
    implements net.camacraft.gravityunbound.util.GravityFieldLookup.Source {

    // rotateVelocity=false: world momentum is conserved on zone entry (see
    // GravityPlatingBlockEntity.PLATING_ROTATION_PARAMS)
    private static final RotationParameters NORMALIZER_ROTATION_PARAMS = new RotationParameters(false, true, 300);
    // above plating (1000 - distance) and cores (990 - distance) by more than
    // the capability's BLEND_RANGE (5), so inside the zone the normalizer
    // alone defines down — no blending with exterior fields
    private static final double NORMALIZER_PRIORITY = 1010;

    // GRID-local down direction the zone enforces
    private Direction localDown = Direction.DOWN;
    // cubic zone half-extent, in blocks
    private int range;
    private boolean showParticles = false;
    // gravity acceleration (blocks/tick^2) inside the zone; 0.08 = vanilla
    private double gravityAccel = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;

    // client BEs stay inert until authoritative data arrives (same rollback
    // protection as plating/core — see GravityPlatingBlockEntity)
    private boolean dataInitialized = false;

    // entity-query staggering, same scheme as GravityPlatingBlockEntity
    private @Nullable List<Entity> cachedEntities = null;
    private long entitiesCacheExpiry = Long.MIN_VALUE;

    // the ship-clamped zone computed by the last tick, for fluid queries
    // (grid space; null while the block is not actively ticking)
    private @Nullable AABB fluidZoneCache = null;

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
        gravityAccel = tag.contains("gravityAccel")
            ? Mth.clamp(tag.getDouble("gravityAccel"), 0.0, 1.0)
            : GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("range", range);
        tag.putString("localDown", localDown.getName());
        tag.putBoolean("showParticles", showParticles);
        tag.putDouble("gravityAccel", gravityAccel);
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

    // how far past the ship's outermost blocks the zone may reach — enough
    // that crews standing ON hull surfaces are still inside, while the field
    // never leaks meaningfully into the surrounding world
    private static final double SHIP_CONTAIN_MARGIN = 1.0;

    public static void tick(Level world, BlockPos blockPos, BlockState blockState, GravityNormalizerBlockEntity be) {
        if (world.isClientSide() && !be.dataInitialized) {
            return;
        }

        Ship ship = VSGameUtilsKt.getShipManagingPos(world, blockPos);

        AABB zone = be.getZoneBox();
        be.fluidZoneCache = null;

        // On a ship the field is CONTAINED INSIDE THE SHIP: the zone is
        // clamped to the ship's actual block extent (Valkyrien Skies keeps
        // getShipAABB() up to date as blocks are placed/broken, so building
        // onto the ship dynamically extends the field). A range larger than
        // the ship simply cuts off at the hull — the normalizer's gravity
        // never leaks into the non-ship world.
        if (ship != null) {
            org.joml.primitives.AABBic voxels = ship.getShipAABB();
            if (voxels == null) {
                return;
            }
            AABB shipBlocks = new AABB(
                voxels.minX(), voxels.minY(), voxels.minZ(),
                voxels.maxX(), voxels.maxY(), voxels.maxZ()
            ).inflate(SHIP_CONTAIN_MARGIN);
            if (!zone.intersects(shipBlocks)) {
                return;
            }
            zone = zone.intersect(shipBlocks);
        }

        be.fluidZoneCache = zone;
        net.camacraft.gravityunbound.util.GravityFieldLookup.register(world, blockPos, be);

        AABB searchBox = zone;
        if (ship != null) {
            searchBox = gravityunbound$shipToWorldBox(ship, zone);
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
                net.camacraft.gravityunbound.util.FieldVisuals.submitPlate(
                    world, blockPos, be.localDown, zone, be.localDown, true, ship
                );
            }
        }

        List<Entity> entities = be.cachedEntities;
        long gameTime = world.getGameTime();
        if (entities == null || gameTime >= be.entitiesCacheExpiry) {
            entities = net.camacraft.gravityunbound.util.GCUtil.safeFieldEntityQuery(
                world, searchBox, EntityTags::canChangeGravity, be.cachedEntities
            );
            be.cachedEntities = entities;
            be.entitiesCacheExpiry = gameTime + (Math.floorMod(gameTime + blockPos.hashCode(), 2L) == 0 ? 2 : 1);
        }

        for (Entity entity : entities) {
            if (entity.isRemoved()) {
                continue;
            }
            // Remote LIVING entities are server-authoritative (frames arrive
            // via sync packets). NON-living remotes (items, XP orbs, TNT,
            // minecarts) are client-PREDICTED physics objects: the client
            // must compute their frames from its own local field sources or
            // its prediction runs in a stale frame and every server position
            // packet reads as a rubber-band.
            if (world.isClientSide() && !entity.isControlledByLocalInstance()
                && !GCUtil.isClientPlayer(entity)
                && entity instanceof net.minecraft.world.entity.LivingEntity) {
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
                worldDown, NORMALIZER_ROTATION_PARAMS, NORMALIZER_PRIORITY, false,
                be.gravityAccel / GravityCapabilityImpl.BASE_GRAVITY_ACCEL, true,
                ship, ship != null ? Vec3.atLowerCornerOf(be.localDown.getNormal()) : null
            );
        }
    }

    private static AABB gravityunbound$shipToWorldBox(Ship ship, AABB box) {
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

    private void sync() {
        Level world = getLevel();
        if (world == null || world.isClientSide()) {
            return;
        }
        setChanged();
        ((ServerChunkCache) world.getChunkSource()).blockChanged(this.getBlockPos());
    }

    // read access for the client settings screen (the client BE carries
    // authoritative data via the update tag)
    // ---- GravityFieldLookup.Source (fluid gravity queries) ----

    @Override
    public @Nullable Direction fluidDownAt(net.minecraft.core.BlockPos pos) {
        AABB zone = fluidZoneCache;
        return zone != null && zone.contains(Vec3.atCenterOf(pos)) ? localDown : null;
    }

    @Override
    public void invalidateEntityCache() {
        cachedEntities = null;
    }

    @Override
    public int sourceMaxRange() {
        return range + 2;
    }

    @Override
    public net.minecraft.core.BlockPos sourcePos() {
        return worldPosition;
    }

    @Override
    public int sourcePriority() {
        return 3;
    }

    public Direction getLocalDown() {
        return localDown;
    }

    public int getRange() {
        return range;
    }

    public double getGravityAccel() {
        return gravityAccel;
    }

    public boolean isShowParticles() {
        return showParticles;
    }

    /**
     * Server-side entry point for the settings GUI (arrives via
     * {@code network.UpdateGravityBlockSettingsPacket}). Every value is
     * clamped to its legal range — client input is never trusted.
     */
    public void applySettingsFromGui(
        Direction newLocalDown, int newRange, double gravityAccel, boolean showParticles
    ) {
        Level world = getLevel();
        if (world == null || world.isClientSide()) {
            return;
        }
        this.localDown = newLocalDown;
        this.range = Mth.clamp(newRange, 1, GravityConfig.normalizerMaxRange.get());
        this.gravityAccel = Mth.clamp(gravityAccel, 0.0, 1.0);
        this.showParticles = showParticles;
        sync();
    }
}

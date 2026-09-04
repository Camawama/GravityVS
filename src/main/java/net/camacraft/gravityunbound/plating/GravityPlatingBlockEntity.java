package net.camacraft.gravityunbound.plating;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.EntityTags;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.api.RotationParameters;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.init.GravityBlocks;
import net.camacraft.gravityunbound.util.GCUtil;
import net.camacraft.gravityunbound.util.RotationUtil;

// VS2 imports (Valkyrien Skies is a mandatory dependency)
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.core.api.ships.Ship;
import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Based on code from AmethystGravity (by CyborgCabbage)
 */
public class GravityPlatingBlockEntity extends BlockEntity
    implements net.camacraft.gravityunbound.util.GravityFieldLookup.Source {

    // rotateVelocity=false: entering/crossing a field must CONSERVE world
    // momentum (a projectile thrown into the field keeps flying and curves,
    // instead of having its velocity rotated in one sharp turn); gravity only
    // redirects future acceleration. rotation time applies to discrete flips.
    private static final RotationParameters PLATING_ROTATION_PARAMS = new RotationParameters(false, true, 300);

    public GravityPlatingBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_PLATING_BLOCK_ENTITY.get(), pos, state);
    }

    private static int maxLevel() {
        return GravityConfig.platingMaxLevel.get();
    }

    public static class SideData {
        public boolean isAttracting = true;
        public int level = 1;
        // glow-ink-sac toggle: render the field visualization
        public boolean showParticles = false;
        // echo-shard toggle — falloff mode: FULL (false, default) applies the
        // same force everywhere in the field; GRADUAL (true) weakens the
        // force linearly with distance from the plate. Falloff affects FORCE
        // only, never orientation (see roadmap "Gradual Field Behavior").
        public boolean gradualFalloff = false;
        // gravity acceleration (blocks/tick^2) this plate applies at full
        // force; 0.08 = vanilla gravity. Applied through the capability's
        // strength (living entities via the forge:entity_gravity attribute).
        public double gravityAccel = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
        // whether entities under this plate's field may planet-walk-snap to
        // the surfaces they stand on (GUI toggle)
        public boolean surfaceSnap = true;
        // what the field acts on (players / mobs / objects / particles /
        // fluids), see util.FieldTargets
        public int targets = net.camacraft.gravityunbound.util.FieldTargets.ALL;
        // whether this plate's field pulls/pushes OTHER Valkyrien Skies
        // ships whose center enters it (a landing pad's field catching a
        // shuttle); ANDed with the global gravityPlatingAffectsShips config
        public boolean affectsShips = true;
        // whether a ship this side holds has the dimension's gravity
        // REPLACED by the field (true) or keeps it, the field adding on top
        // (false — also for ships whose gravity another mod controls)
        public boolean replacesGravity = true;

        public @Nullable AABB effectBoxCache = null;

        // the plate's own footprint + outward range (what the visual shows):
        // gravity applied outside this but inside the effect box (the hidden
        // sideways bleed) is only a SECONDARY, blend-supporting contribution
        public @Nullable AABB primaryBoxCache = null;

        // visual grouping cache: adjacent same-config plates render as ONE
        // merged field; only the group's master plate submits the visual
        public @Nullable AABB visualBoxCache = null;
        public boolean visualMaster = true;

        public SideData(boolean isAttracting, int level) {
            this.isAttracting = isAttracting;
            this.level = level;
        }

        public static SideData createDefault() {
            return new SideData(true, 1);
        }

        public static SideData fromTag(CompoundTag tag) {
            boolean isAttracting_ = tag.getBoolean("isAttracting");
            int level_ = tag.getInt("level");

            level_ = Mth.clamp(level_, 1, maxLevel());

            SideData data = new SideData(isAttracting_, level_);
            data.showParticles = tag.getBoolean("showParticles");
            data.gradualFalloff = tag.getBoolean("gradualFalloff");
            data.gravityAccel = tag.contains("gravityAccel")
                ? Mth.clamp(tag.getDouble("gravityAccel"), 0.0, 1.0)
                : GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
            data.surfaceSnap = !tag.contains("surfaceSnap") || tag.getBoolean("surfaceSnap");
            data.targets = tag.contains("targets")
                ? net.camacraft.gravityunbound.util.FieldTargets.sanitize(tag.getInt("targets"))
                : net.camacraft.gravityunbound.util.FieldTargets.ALL;
            data.affectsShips = !tag.contains("affectsShips") || tag.getBoolean("affectsShips");
            data.replacesGravity = !tag.contains("replacesGravity") || tag.getBoolean("replacesGravity");
            return data;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("isAttracting", isAttracting);
            tag.putInt("level", level);
            tag.putBoolean("showParticles", showParticles);
            tag.putBoolean("gradualFalloff", gradualFalloff);
            tag.putDouble("gravityAccel", gravityAccel);
            tag.putBoolean("surfaceSnap", surfaceSnap);
            tag.putInt("targets", targets);
            tag.putBoolean("affectsShips", affectsShips);
            tag.putBoolean("replacesGravity", replacesGravity);
            return tag;
        }

        public double getEffectRange() {
            return level - 0.1;
        }

        public AABB getEffectBox(BlockPos blockPos, Direction plateDir, Level world) {
            if (effectBoxCache == null) {
                double expand = 1.0;

                double minX = blockPos.getX() - expand;
                double minY = blockPos.getY() - expand;
                double minZ = blockPos.getZ() - expand;
                double maxX = blockPos.getX() + 1 + expand;
                double maxY = blockPos.getY() + 1 + expand;
                double maxZ = blockPos.getZ() + 1 + expand;

                double delta = getEffectRange() - 1;
                switch (plateDir) {
                    case DOWN -> maxY += delta;
                    case UP -> minY -= delta;
                    case NORTH -> maxZ += delta;
                    case SOUTH -> minZ -= delta;
                    case WEST -> maxX += delta;
                    case EAST -> minX -= delta;
                }

                // merge with same-polarity plates on adjacent perpendicular faces
                BlockPos wallPos = blockPos.relative(plateDir);
                for (Direction sideDir : Direction.values()) {
                    if (sideDir.getAxis() == plateDir.getAxis()) {continue;}

                    BlockPos sidePos = wallPos.relative(sideDir);
                    BlockState sideBlockState = world.getBlockState(sidePos);
                    if (!(sideBlockState.getBlock() instanceof GravityPlatingBlock)) {continue;}

                    Direction neighborPlateDir = sideDir.getOpposite();
                    if (!GravityPlatingBlock.hasDir(sideBlockState, neighborPlateDir)) {continue;}

                    if (!(world.getBlockEntity(sidePos) instanceof GravityPlatingBlockEntity be)) {continue;}

                    SideData neighborSide = be.getSideData(neighborPlateDir);
                    if (neighborSide == null || neighborSide.isAttracting != this.isAttracting) {continue;}

                    double sideDelta = neighborSide.getEffectRange();
                    switch (sideDir) {
                        case DOWN -> minY -= sideDelta;
                        case UP -> maxY += sideDelta;
                        case NORTH -> minZ -= sideDelta;
                        case SOUTH -> maxZ += sideDelta;
                        case WEST -> minX -= sideDelta;
                        case EAST -> maxX += sideDelta;
                    }
                }

                effectBoxCache = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            }

            return effectBoxCache;
        }

        /**
         * The plate's own cell extended outward by the effect range — the
         * region the visual shows. A small tolerance keeps positions right on
         * the boundary inside.
         *
         * DOORWAY BRIDGES: a door (or trapdoor / fence gate) cannot share a
         * cell with plating, so a plated floor or ceiling always had a
         * one-cell hole at every doorway. When the cell right beside this
         * plate holds such a block and the cell beyond it holds a plate with
         * the same facing and polarity, the primary column extends across
         * the gap — both plates do, so the doorway is fully inside the field
         * and walking through it never leaves the plating. (The hidden
         * 1-block bleed already covered the cell as a SECONDARY, blend-only
         * contribution; a primary is what keeps gravity there while standing
         * still.) The plate model draws a connecting panel for the same
         * condition, see {@code client.PlatingBridgeModel}.
         */
        public AABB getPrimaryBox(BlockPos blockPos, Direction plateDir, @Nullable Level world) {
            if (primaryBoxCache == null) {
                double expand = 0.05;

                double minX = blockPos.getX() - expand;
                double minY = blockPos.getY() - expand;
                double minZ = blockPos.getZ() - expand;
                double maxX = blockPos.getX() + 1 + expand;
                double maxY = blockPos.getY() + 1 + expand;
                double maxZ = blockPos.getZ() + 1 + expand;

                double delta = getEffectRange() - 1;
                switch (plateDir) {
                    case DOWN -> maxY += delta;
                    case UP -> minY -= delta;
                    case NORTH -> maxZ += delta;
                    case SOUTH -> minZ -= delta;
                    case WEST -> maxX += delta;
                    case EAST -> minX -= delta;
                }

                if (world != null) {
                    for (Direction tangent : Direction.values()) {
                        if (tangent.getAxis() == plateDir.getAxis()
                            || !isBridgedToward(world, blockPos, plateDir, isAttracting, tangent)) {
                            continue;
                        }
                        switch (tangent) {
                            case DOWN -> minY -= 1.0;
                            case UP -> maxY += 1.0;
                            case NORTH -> minZ -= 1.0;
                            case SOUTH -> maxZ += 1.0;
                            case WEST -> minX -= 1.0;
                            case EAST -> maxX += 1.0;
                        }
                    }
                }

                primaryBoxCache = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            }

            return primaryBoxCache;
        }
    }

    /**
     * Blocks a plate field may bridge across: openings that can never share a
     * cell with plating but sit in the middle of plated surfaces.
     */
    public static boolean isBridgeableGap(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    /**
     * True when {@code pos + tangent} is a bridgeable gap and {@code pos +
     * 2 * tangent} holds a plate with the same facing and polarity.
     */
    public static boolean isBridgedToward(
        net.minecraft.world.level.BlockGetter world, BlockPos pos, Direction plateDir,
        boolean attracting, Direction tangent
    ) {
        BlockPos gap = pos.relative(tangent);
        if (!isBridgeableGap(world.getBlockState(gap))) {
            return false;
        }
        BlockPos beyond = gap.relative(tangent);
        BlockState beyondState = world.getBlockState(beyond);
        if (!(beyondState.getBlock() instanceof GravityPlatingBlock)
            || !GravityPlatingBlock.hasDir(beyondState, plateDir)) {
            return false;
        }
        if (!(world.getBlockEntity(beyond) instanceof GravityPlatingBlockEntity be)) {
            return false;
        }
        SideData other = be.getSideData(plateDir);
        return other != null && other.isAttracting == attracting;
    }

    // ---- connected-panel rendering across doorways (client model data) ----

    /** One bit per (plate side, bridge tangent): {@code side.ordinal() * 6 + tangent.ordinal()}. */
    public static final net.minecraftforge.client.model.data.ModelProperty<Long> BRIDGES =
        new net.minecraftforge.client.model.data.ModelProperty<>();

    private long bridgeMask = 0L;

    public static int bridgeBit(Direction plateDir, Direction tangent) {
        return plateDir.ordinal() * 6 + tangent.ordinal();
    }

    @Override
    public net.minecraftforge.client.model.data.ModelData getModelData() {
        return net.minecraftforge.client.model.data.ModelData.builder().with(BRIDGES, bridgeMask).build();
    }

    private long computeBridgeMask(Level world) {
        if (sideData == null) {
            return 0L;
        }
        long mask = 0L;
        for (Direction plateDir : Direction.values()) {
            SideData side = sideData[plateDir.ordinal()];
            if (side == null) {
                continue;
            }
            for (Direction tangent : Direction.values()) {
                if (tangent.getAxis() != plateDir.getAxis()
                    && isBridgedToward(world, worldPosition, plateDir, side.isAttracting, tangent)) {
                    mask |= 1L << bridgeBit(plateDir, tangent);
                }
            }
        }
        return mask;
    }

    private @Nullable SideData[] sideData = null;

    private @Nullable AABB roughAreaBoxCache = null;

    // entity-query staggering: the AABB entity query is the dominant cost of
    // the field system (every plate, every tick, both sides); reuse the
    // result for one extra tick. Per-tick position tests below still use the
    // entities' CURRENT positions, so a cached entity that left the field
    // simply fails the contains() checks — only field ENTRY can lag by one
    // tick, which the capability's grace machinery absorbs anyway.
    private @Nullable List<Entity> cachedEntities = null;
    private long entitiesCacheExpiry = Long.MIN_VALUE;

    // True once this block entity has AUTHORITATIVE side data: loaded from NBT
    // or a server update packet, or configured server-side on placement. A
    // client-side block entity that never got data is a prediction artifact —
    // e.g. a break on a ship that the server silently rejected as "too far"
    // rolls the block back with a FRESH block entity. refreshCache would give
    // it default side data (attracting, level 1, visual off), creating an
    // invisible phantom gravity field. Such a block entity stays inert.
    private boolean dataInitialized = false;

    public @Nullable SideData getSideData(Direction dir) {
        if (sideData == null) {
            return null;
        }
        return sideData[dir.ordinal()];
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        dataInitialized = true;
        sideData = new SideData[6];
        for (Direction dir : Direction.values()) {
            String dirName = dir.getName();
            if (tag.contains(dirName)) {
                CompoundTag sideTag = tag.getCompound(dirName);
                sideData[dir.ordinal()] = SideData.fromTag(sideTag);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        if (sideData != null) {
            for (Direction dir : Direction.values()) {
                String dirName = dir.getName();
                SideData side = sideData[dir.ordinal()];
                if (side != null) {
                    tag.put(dirName, side.toTag());
                }
            }
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

    public void refreshCache() {
        if (getLevel() != null) {
            refreshCache(getLevel().getBlockState(getBlockPos()));
        }
    }

    public void refreshCache(BlockState blockState) {
        Level world = getLevel();

        if (world == null) {
            return;
        }

        if (sideData == null) {
            sideData = new SideData[6];
        }

        if (!(blockState.getBlock() instanceof GravityPlatingBlock)) {
            return;
        }

        for (Direction dir : Direction.values()) {
            if (GravityPlatingBlock.hasDir(blockState, dir)) {
                if (sideData[dir.ordinal()] == null) {
                    sideData[dir.ordinal()] = SideData.createDefault();
                }
            }
            else {
                sideData[dir.ordinal()] = null;
            }
        }

        // stagger cache invalidation across plates; floorMod because hashCode may be negative
        if (Math.floorMod(this.worldPosition.hashCode(), 5) == world.getGameTime() % 5) {
            invalidateBoxCaches();

            // doorway bridges: re-mesh the plate when a connecting panel
            // appears or disappears (door placed/broken, plate beyond
            // placed/broken/re-polarized)
            if (world.isClientSide()) {
                long mask = computeBridgeMask(world);
                if (mask != bridgeMask) {
                    bridgeMask = mask;
                    requestModelDataUpdate();
                    world.setBlocksDirty(worldPosition, blockState, blockState);
                }
            }
        }
    }

    public void invalidateBoxCaches() {
        roughAreaBoxCache = null;
        if (sideData != null) {
            for (SideData sideDatum : sideData) {
                if (sideDatum != null) {
                    sideDatum.effectBoxCache = null;
                    sideDatum.primaryBoxCache = null;
                    sideDatum.visualBoxCache = null;
                }
            }
        }
    }

    private AABB getRoughEffectBox() {
        if (roughAreaBoxCache == null) {
            double maxRange = 0;
            for (SideData sideDatum : sideData) {
                if (sideDatum != null) {
                    maxRange = Math.max(maxRange, sideDatum.getEffectRange());
                }
            }

            BlockPos blockPos = this.getBlockPos();
            double expand = 0.001;
            double delta = maxRange + expand;
            roughAreaBoxCache = new AABB(
                    blockPos.getX() - delta, blockPos.getY() - delta, blockPos.getZ() - delta,
                    blockPos.getX() + 1 + delta, blockPos.getY() + 1 + delta, blockPos.getZ() + 1 + delta
            );
        }
        return roughAreaBoxCache;
    }

    public static void tick(Level world, BlockPos blockPos, BlockState blockState, GravityPlatingBlockEntity be) {
        if (!(blockState.getBlock() instanceof GravityPlatingBlock)) {
            return;
        }

        // client block entities apply fields/visuals only once the server's
        // data has actually arrived (see dataInitialized)
        if (world.isClientSide() && !be.dataInitialized) {
            return;
        }

        be.refreshCache(blockState);
        net.camacraft.gravityunbound.util.GravityFieldLookup.register(world, blockPos, be);

        AABB roughBox = be.getRoughEffectBox();
        AABB searchBox = roughBox;

        Ship ship = VSGameUtilsKt.getShipManagingPos(world, blockPos);

        if (ship != null) {
            searchBox = gravityunbound$shipToWorldBox(ship, roughBox);
        }

        if (world.isClientSide()) {
            be.submitFieldVisuals(world, blockPos, ship);
        }

        List<Entity> entities = be.cachedEntities;
        long gameTime = world.getGameTime();
        if (entities == null || gameTime >= be.entitiesCacheExpiry) {
            entities = net.camacraft.gravityunbound.util.GCUtil.safeFieldEntityQuery(
                    world, searchBox, EntityTags::canChangeGravity, be.cachedEntities
            );
            be.cachedEntities = entities;
            // phase-offset by position hash so half the plates refresh on
            // even ticks and half on odd ones
            be.entitiesCacheExpiry = gameTime + (Math.floorMod(gameTime + blockPos.hashCode(), 2L) == 0 ? 2 : 1);
        }

        for (Entity entity : entities) {
            if (entity.isRemoved()) {
                continue;
            }
            // on the client, only the locally controlled entity computes gravity
            // from fields; remote entities follow the server sync
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
            Vec3 entityGravityDir = comp.getCurrGravityDirectionVec();

            boolean applies = false;
            boolean primaryApplies = false;
            Direction bestPlateDir = null;
            double bestDistance = Double.MAX_VALUE;

            for (Direction plateDir : Direction.values()) {
                SideData sideDatum = be.sideData[plateDir.ordinal()];
                if (sideDatum == null) {
                    continue;
                }
                // per-side target categories (GUI): a field may leave
                // players, mobs or objects alone entirely
                if (!net.camacraft.gravityunbound.util.FieldTargets.appliesTo(sideDatum.targets, entity)) {
                    continue;
                }

                Direction localEffectDir = sideDatum.isAttracting ? plateDir : plateDir.getOpposite();

                // the world-space direction this plate wants gravity to point
                Vec3 worldEffectDir = Vec3.atLowerCornerOf(localEffectDir.getNormal());
                if (ship != null) {
                    Vector3d dirVec = new Vector3d(localEffectDir.getStepX(), localEffectDir.getStepY(), localEffectDir.getStepZ());
                    ship.getTransform().getShipToWorldMatrix().transformDirection(dirVec);
                    worldEffectDir = new Vec3(dirVec.x, dirVec.y, dirVec.z);
                }

                boolean isOpposite = (entityGravityDir.normalize().dot(worldEffectDir.normalize()) < -0.99);

                // test the eye position when the plate opposes current gravity so the
                // entity has to actually reach into the field before flipping
                Vec3 testingPosWorld = isOpposite ? entity.getEyePosition() : entity.position();
                Vec3 testingPos;

                if (ship != null) {
                    Vector3d posJoml = new Vector3d(testingPosWorld.x, testingPosWorld.y, testingPosWorld.z);
                    ship.getTransform().getWorldToShipMatrix().transformPosition(posJoml);
                    testingPos = new Vec3(posJoml.x, posJoml.y, posJoml.z);
                } else {
                    testingPos = testingPosWorld;
                }

                AABB gravityEffectBox = sideDatum.getEffectBox(blockPos, plateDir, world);
                if (!gravityEffectBox.contains(testingPos)) {
                    continue;
                }

                Vec3 plateDirVec = Vec3.atLowerCornerOf(plateDir.getNormal());
                Vec3 effectCenter = Vec3.atCenterOf(blockPos).add(plateDirVec.scale(0.5));

                double adjustment = 1.0;
                Vec3 effectCenterAdjusted = effectCenter.add(plateDirVec.scale(-adjustment));

                Vec3 deltaVec = testingPos.subtract(effectCenterAdjusted);

                double distanceToPlane = -deltaVec.dot(plateDirVec);
                if (distanceToPlane < -adjustment - 0.001) {
                    continue;
                }

                Vec3 localVec = RotationUtil.vecWorldToPlayer(deltaVec, plateDir);
                double dx = GCUtil.distanceToRange(localVec.x, -0.5, 0.5);
                double dz = GCUtil.distanceToRange(localVec.z, -0.5, 0.5);
                double distanceToPlate = Math.sqrt(dx * dx + dz * dz + distanceToPlane * distanceToPlane);

                double priority = 1000 - distanceToPlate;
                if (isOpposite) {
                    priority -= 10;
                }
                // inside the plate's own column (footprint + outward) the
                // field is PRIMARY; in the hidden sideways bleed it is only a
                // blend-supporting secondary — a player standing one block
                // beside a plate must not have their gravity changed, but the
                // bleed still smooths cube-edge transitions where a primary
                // field is present
                boolean secondary = !sideDatum.getPrimaryBox(blockPos, plateDir, world).contains(testingPos);

                // GRADUAL falloff: the force weakens linearly from full at
                // the plate face to zero at the field edge. distanceToPlate
                // includes the 1-block "adjustment" behind the face, so
                // subtract it to measure from the surface. Orientation is
                // never scaled — the direction effect stays fully weighted.
                double strengthScale = sideDatum.gravityAccel / GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
                if (sideDatum.gradualFalloff) {
                    double surfaceDistance = Math.max(0.0, distanceToPlate - adjustment);
                    strengthScale *= Mth.clamp(1.0 - surfaceDistance / sideDatum.getEffectRange(), 0.0, 1.0);
                }
                // the PRIMARY column (footprint + outward range, what the
                // visual shows) is the region reported for the held-surface
                // sustain: standing on a face the plate endorsed keeps the
                // field alive through effect dropouts only while still
                // inside it — walking off the plating releases
                comp.applyGravityDirectionEffect(
                        worldEffectDir, PLATING_ROTATION_PARAMS, priority, secondary, strengthScale,
                        sideDatum.surfaceSnap,
                        ship, ship != null
                            ? Vec3.atLowerCornerOf(localEffectDir.getNormal()) : null,
                        sideDatum.getPrimaryBox(blockPos, plateDir, world)
                );
                applies = true;
                primaryApplies = primaryApplies || !secondary;

                if (distanceToPlate < bestDistance) {
                    bestDistance = distanceToPlate;
                    bestPlateDir = plateDir;
                }
            }

            // bleed-only contact is not a field (the capability drops it too):
            // no artificial-gravity force, friction or corner assist from it
            if (!applies || !primaryApplies) {
                continue;
            }

            // velocity writes: only on the side that controls this entity
            boolean controlsEntity = world.isClientSide()
                ? entity.isControlledByLocalInstance()
                : !(entity instanceof Player);

            // the zero-g pull for non-living entities decides its own side
            // (it also runs for client-PREDICTED items, see the helper)
            if (bestPlateDir != null) {
                gravityunbound$applyArtificialGravityForce(world, ship, be, bestPlateDir, entity, comp);
            }
            if (controlsEntity && bestPlateDir != null) {
                gravityunbound$applyShipFriction(ship, be, bestPlateDir, entity);
            }

            // Non-player entities on a ship: register them with Valkyrien
            // Skies' own dragging every tick they stand in a plated field.
            // Under rotated gravity our collide mixins change the collide
            // result, which trips VS's "movement changed -> wipe ship-standing
            // state" heuristic every tick — mobs were never dragged, so a
            // moving ship slid out from under them (looked like they phased
            // through the hull and fell off; players were unaffected because
            // the capsule path already does exactly this).
            if (controlsEntity && ship != null && !(entity instanceof Player) && entity.onGround()) {
                org.valkyrienskies.mod.common.util.EntityDraggingInformation dragInfo =
                    ((org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider) entity)
                        .getDraggingInformation();
                dragInfo.setLastShipStoodOn(ship.getId());
                dragInfo.setTicksSinceStoodOnShip(0);
                dragInfo.setIgnoreNextGroundStand(true);
            }

            // The legacy corner auto-jump is for the cardinal-snap entities
            // (mobs, items). Capsule players handle inner corners by rotating
            // their frame onto the wall instead; with the smooth transition
            // the auto-jump's trigger condition stayed true for many ticks in
            // a row, stacking its 0.4-block hop every tick — the "walking from
            // floor plating onto wall plating launches me into the air" bug.
            if (controlsEntity
                && GravityConfig.autoJumpOnGravityPlateInnerCorner.get()
                && !comp.useCapsuleCollision()
            ) {
                tryToDoCornerAutoJump(blockState, blockPos, entity, comp, ship);
            }
        }

        if (!world.isClientSide()) {
            if (GravityConfig.gravityPlatingAffectsShips.get()) {
                be.applyToShips(world, ship, blockPos, searchBox, gameTime);
            }
            if (ship != null) {
                be.wakeWorldFluidsUnderShip(world, ship, roughBox, gameTime);
            }
        }
    }

    // the world-space box this ship-mounted plate's field covered at its
    // last fluid wake-up (see wakeWorldFluidsUnderShip)
    private @Nullable AABB lastShipWakeBox = null;
    private static final int SHIP_FLUID_WAKE_PERIOD = 8;

    /**
     * A ship-mounted field flying over WORLD liquid: wake the fluid blocks
     * in the region the field covers (and the region it just left, so water
     * it pulled into shape settles back) every few ticks. Still water has no
     * scheduled ticks of its own, so without this a lake under a passing
     * ship's field never noticed the field at all. The cross-grid fluid
     * lookup (GravityFieldLookup.fluidDownAt) then bends the woken blocks.
     */
    private void wakeWorldFluidsUnderShip(Level world, Ship ship, AABB gridBox, long gameTime) {
        if (!GravityConfig.gravityAffectsFluids.get() || !GravityConfig.shipFieldsAffectWorldFluids.get()) {
            return;
        }
        if (sideData == null || Math.floorMod(gameTime + worldPosition.hashCode(), SHIP_FLUID_WAKE_PERIOD) != 0) {
            return;
        }
        boolean anyFluidSide = false;
        for (SideData side : sideData) {
            if (side != null && net.camacraft.gravityunbound.util.FieldTargets.has(
                side.targets, net.camacraft.gravityunbound.util.GravityFieldLookup.Kind.FLUID)) {
                anyFluidSide = true;
                break;
            }
        }
        if (!anyFluidSide) {
            return;
        }
        AABB worldBox = gravityunbound$shipToWorldBox(ship, gridBox);
        AABB sweep = lastShipWakeBox != null ? worldBox.minmax(lastShipWakeBox) : worldBox;
        lastShipWakeBox = worldBox;
        net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluids(world, sweep);
    }

    /**
     * SHIPS IN THE FIELD (server). Every OTHER loaded ship whose center of
     * mass lies inside a side's field gets one pull along that side's
     * direction — mass x 1 g x the plate's acceleration setting (x falloff)
     * — queued on the ship's force inducer, at most once per ship per tick
     * however many plates of a deck hold it. A shuttle flying into a
     * landing pad's field is drawn onto the pad.
     */
    private void applyToShips(Level world, @Nullable Ship ownShip, BlockPos blockPos, AABB searchBox, long gameTime) {
        if (!(world instanceof net.minecraft.server.level.ServerLevel serverLevel) || sideData == null) {
            return;
        }
        boolean anyShipSide = false;
        for (SideData side : sideData) {
            if (side != null && side.affectsShips) {
                anyShipSide = true;
                break;
            }
        }
        if (!anyShipSide) {
            return;
        }

        for (Ship other : VSGameUtilsKt.getShipsIntersecting(serverLevel, searchBox)) {
            if (ownShip != null && other.getId() == ownShip.getId()) {
                continue;
            }
            // the intersection query hands back ship DATA; forces need the
            // LOADED ship object (null while the ship is unloaded)
            org.valkyrienskies.core.api.ships.LoadedServerShip loaded =
                VSGameUtilsKt.getShipObjectWorld(serverLevel).getLoadedShips().getById(other.getId());
            if (loaded == null) {
                continue;
            }
            Vector3d centerWorld = new Vector3d(loaded.getTransform().getPositionInWorld());
            Vec3 testingPos = new Vec3(centerWorld.x, centerWorld.y, centerWorld.z);
            if (ownShip != null) {
                Vector3d local = new Vector3d(centerWorld);
                ownShip.getTransform().getWorldToShipMatrix().transformPosition(local);
                testingPos = new Vec3(local.x, local.y, local.z);
            }

            for (Direction plateDir : Direction.values()) {
                SideData sideDatum = sideData[plateDir.ordinal()];
                if (sideDatum == null || !sideDatum.affectsShips) {
                    continue;
                }
                AABB effectBox = sideDatum.getEffectBox(blockPos, plateDir, world);
                if (!effectBox.contains(testingPos)) {
                    continue;
                }
                net.camacraft.gravityunbound.core.GravityCoreForceInducer inducer =
                    net.camacraft.gravityunbound.core.GravityCoreForceInducer.getOrCreate(loaded);
                if (inducer == null) {
                    return; // inducer unavailable (registration refused): leave ships alone
                }
                // describe the field in THIS plate's grid; the physics thread
                // evaluates it against live transforms (reduced mass, reaction
                // on the carrying ship, damping) every physics tick
                Direction localEffectDir = sideDatum.isAttracting ? plateDir : plateDir.getOpposite();
                Vec3 plateNormal = Vec3.atLowerCornerOf(plateDir.getNormal());
                Vec3 effectCenter = Vec3.atCenterOf(blockPos).add(plateNormal.scale(0.5));
                double acceleration = 10.0 * GravityConfig.gravityCoreShipForceMultiplier.get()
                    * (sideDatum.gravityAccel / GravityCapabilityImpl.BASE_GRAVITY_ACCEL);
                inducer.offer(net.camacraft.gravityunbound.core.GravityCoreForceInducer.FieldSource.planar(
                    ownShip != null ? ownShip.getId() : net.camacraft.gravityunbound.core.GravityCoreForceInducer.WORLD,
                    new org.joml.primitives.AABBd(effectBox.minX, effectBox.minY, effectBox.minZ,
                        effectBox.maxX, effectBox.maxY, effectBox.maxZ),
                    new Vector3d(localEffectDir.getStepX(), localEffectDir.getStepY(), localEffectDir.getStepZ()),
                    new Vector3d(effectCenter.x, effectCenter.y, effectCenter.z),
                    new Vector3d(plateNormal.x, plateNormal.y, plateNormal.z),
                    sideDatum.getEffectRange(), acceleration, sideDatum.gradualFalloff, sideDatum.replacesGravity));
                break;
            }
        }
    }

    /**
     * Field visualization (toggled per side with a glow ink sac), rendered as
     * line geometry by {@code client.FieldVisualsRenderer} — this only submits
     * the field's box each tick.
     *
     * Adjacent plates with the same facing/polarity/range that all have the
     * visual enabled are GROUPED: only the group's master plate submits, with
     * the merged box — a 3x3 wall of plates draws one 3x3 field, not nine
     * overlapping ones. The box is the plates' own footprint extended outward
     * by the effect range (the visual intentionally does not show the 1-block
     * sideways trigger bleed).
     */
    private void submitFieldVisuals(Level world, BlockPos blockPos, @Nullable Ship ship) {
        if (sideData == null) {
            return;
        }

        for (Direction plateDir : Direction.values()) {
            SideData sideDatum = sideData[plateDir.ordinal()];
            if (sideDatum == null || !sideDatum.showParticles) {
                continue;
            }

            if (sideDatum.visualBoxCache == null) {
                computeVisualGroup(world, blockPos, plateDir, sideDatum);
            }
            if (!sideDatum.visualMaster || sideDatum.visualBoxCache == null) {
                continue;
            }

            Direction flowDir = sideDatum.isAttracting ? plateDir : plateDir.getOpposite();
            net.camacraft.gravityunbound.util.FieldVisuals.submitPlate(
                world, blockPos, plateDir,
                sideDatum.visualBoxCache, flowDir, sideDatum.isAttracting, ship
            );
        }
    }

    /**
     * Flood-fills the in-plane group of adjacent, same-config plates (same
     * facing, polarity, range, visual enabled). Rectangular groups merge into
     * one box submitted by the lexicographically-smallest member; irregular
     * groups fall back to per-plate boxes.
     */
    private void computeVisualGroup(Level world, BlockPos origin, Direction plateDir, SideData side) {
        Direction.Axis axis = plateDir.getAxis();

        java.util.HashSet<BlockPos> members = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        members.add(origin);
        queue.add(origin);

        int minX = origin.getX(), minY = origin.getY(), minZ = origin.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        boolean overflow = false;
        // the group's master is its lexicographically smallest PLATE (a
        // bridged doorway cell may be the bounding box's corner but holds
        // no plate to submit the visual)
        BlockPos master = origin;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction tangent : Direction.values()) {
                if (tangent.getAxis() == axis) {
                    continue;
                }
                BlockPos next = cur.relative(tangent);
                if (members.contains(next)) {
                    continue;
                }
                // a plated neighbor, or a bridged doorway with a plated cell
                // beyond it (the doorway cell joins the group as a member so
                // the rectangle test still holds — see getPrimaryBox)
                BlockPos plate = null;
                BlockPos gap = null;
                if (isSameVisualGroup(world, next, plateDir, side)) {
                    plate = next;
                }
                else if (isBridgeableGap(world.getBlockState(next))) {
                    BlockPos beyond = next.relative(tangent);
                    if (!members.contains(beyond) && isSameVisualGroup(world, beyond, plateDir, side)) {
                        gap = next;
                        plate = beyond;
                    }
                }
                if (plate == null) {
                    continue;
                }
                if (members.size() + (gap != null ? 2 : 1) > 121) {
                    overflow = true;
                    break;
                }
                if (gap != null) {
                    members.add(gap.immutable());
                    minX = Math.min(minX, gap.getX());
                    minY = Math.min(minY, gap.getY());
                    minZ = Math.min(minZ, gap.getZ());
                    maxX = Math.max(maxX, gap.getX());
                    maxY = Math.max(maxY, gap.getY());
                    maxZ = Math.max(maxZ, gap.getZ());
                }
                members.add(plate.immutable());
                queue.add(plate);
                minX = Math.min(minX, plate.getX());
                minY = Math.min(minY, plate.getY());
                minZ = Math.min(minZ, plate.getZ());
                maxX = Math.max(maxX, plate.getX());
                maxY = Math.max(maxY, plate.getY());
                maxZ = Math.max(maxZ, plate.getZ());
                if (comparePos(plate, master) < 0) {
                    master = plate.immutable();
                }
            }
        }

        // group must fill its bounding rectangle to merge into a single box
        long area = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        boolean rectangular = !overflow && members.size() == area;

        if (!rectangular) {
            side.visualMaster = true;
            side.visualBoxCache = buildFieldBox(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX(), origin.getY(), origin.getZ(),
                plateDir, side.getEffectRange()
            );
            return;
        }

        side.visualMaster = origin.equals(master);
        side.visualBoxCache = buildFieldBox(minX, minY, minZ, maxX, maxY, maxZ, plateDir, side.getEffectRange());
    }

    private static int comparePos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    private boolean isSameVisualGroup(Level world, BlockPos pos, Direction plateDir, SideData ref) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof GravityPlatingBlock) || !GravityPlatingBlock.hasDir(state, plateDir)) {
            return false;
        }
        if (!(world.getBlockEntity(pos) instanceof GravityPlatingBlockEntity be) || be.sideData == null) {
            return false;
        }
        SideData other = be.sideData[plateDir.ordinal()];
        return other != null
            && other.showParticles
            && other.isAttracting == ref.isAttracting
            && other.level == ref.level;
    }

    /**
     * The visual field box. The plate sits flush against the {@code plateDir}
     * face of its own block space, and the field fills that block space plus
     * the effect range beyond it — so the box KEEPS the footprint cells and
     * extends outward (opposite the plate facing).
     */
    private static AABB buildFieldBox(
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        Direction plateDir, double effectRange
    ) {
        double x1 = minX, y1 = minY, z1 = minZ;
        double x2 = maxX + 1, y2 = maxY + 1, z2 = maxZ + 1;

        // the effect range COUNTS the plate's own block cell (see
        // getPrimaryBox: cell + range - 1) — extending the full range past the
        // cell drew the visual exactly one block longer than the real field
        double outward = Math.max(0.0, effectRange - 1);
        switch (plateDir.getOpposite()) {
            case UP -> y2 += outward;
            case DOWN -> y1 -= outward;
            case SOUTH -> z2 += outward;
            case NORTH -> z1 -= outward;
            case EAST -> x2 += outward;
            case WEST -> x1 -= outward;
        }
        return new AABB(x1, y1, z1, x2, y2, z2);
    }

    private static AABB gravityunbound$shipToWorldBox(Ship ship, AABB box) {
        Vector3d[] corners = new Vector3d[] {
                new Vector3d(box.minX, box.minY, box.minZ), new Vector3d(box.maxX, box.minY, box.minZ),
                new Vector3d(box.minX, box.maxY, box.minZ), new Vector3d(box.maxX, box.maxY, box.minZ),
                new Vector3d(box.minX, box.minY, box.maxZ), new Vector3d(box.maxX, box.minY, box.maxZ),
                new Vector3d(box.minX, box.maxY, box.maxZ), new Vector3d(box.maxX, box.maxY, box.maxZ)
        };

        double wMinX = Double.MAX_VALUE, wMinY = Double.MAX_VALUE, wMinZ = Double.MAX_VALUE;
        double wMaxX = -Double.MAX_VALUE, wMaxY = -Double.MAX_VALUE, wMaxZ = -Double.MAX_VALUE;

        for (Vector3d corner : corners) {
            ship.getTransform().getShipToWorldMatrix().transformPosition(corner);
            if (corner.x < wMinX) wMinX = corner.x;
            if (corner.y < wMinY) wMinY = corner.y;
            if (corner.z < wMinZ) wMinZ = corner.z;
            if (corner.x > wMaxX) wMaxX = corner.x;
            if (corner.y > wMaxY) wMaxY = corner.y;
            if (corner.z > wMaxZ) wMaxZ = corner.z;
        }

        return new AABB(wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ);
    }

    /**
     * In configured zero-g dimensions the field also accelerates NON-LIVING
     * entities (items, minecarts, TNT — their hardcoded gravity paths are the
     * ones a zero-g dimension suppresses and the capability's deficit skips).
     * Scaled by the entity's gravity strength so it agrees with normal
     * gravity handling.
     *
     * LIVING entities are excluded: applyFieldPullDeficit already supplies
     * their FULL intended pull every field tick (round 68/70 — an active
     * field owns gravity, no-gravity zero-g dimensions included), so this
     * force on top DOUBLED the pull — and it stacked once more for EVERY
     * plate whose field contains the entity (a plated floor is many plates,
     * each within its 1-block bleed of the player). Scale-1 ships showed the
     * full slam; scaled ships hid it, because transformDirection through the
     * scaled ship matrix shrank the redundant force by the ship scale.
     */
    private static void gravityunbound$applyArtificialGravityForce(
            Level world, @Nullable Ship ship, GravityPlatingBlockEntity be,
            Direction plateDir, Entity entity, GravityCapabilityImpl comp
    ) {
        if (entity instanceof LivingEntity) {
            return;
        }
        SideData sideDatum = be.sideData[plateDir.ordinal()];
        if (sideDatum == null) {
            return;
        }
        Direction localEffectDir = sideDatum.isAttracting ? plateDir : plateDir.getOpposite();

        Vector3d accel = new Vector3d(
                localEffectDir.getStepX(),
                localEffectDir.getStepY(),
                localEffectDir.getStepZ()
        );
        if (ship != null) {
            ship.getTransform().getShipToWorldMatrix().transformDirection(accel);
            // a scaled ship's matrix scales direction lengths by the ship
            // scale — the ACCELERATION must not shrink or grow with it
            accel.normalize();
        }

        // dimension gate, Ad Astra hand-off, once-per-tick claim, terminal
        // velocity and the client-side prediction rule all live in the
        // shared helper (cores use the same one with their radial pull)
        GravityCapabilityImpl.applyZeroGravityFieldForce(
            world, entity, comp, new Vec3(accel.x, accel.y, accel.z));
    }

    /**
     * Extra grip when standing on a ship: damp the entity's OWN tangential
     * velocity. Never zeroes momentum outright (keeps knockback/jumps alive).
     *
     * The entity is carried with the ship by Valkyrien Skies' POSITIONAL drag
     * (registered every tick below), so its own velocity is already its
     * ship-relative motion. The old version pushed the velocity toward the
     * ship's SURFACE velocity — correct only for undragged entities; combined
     * with the drag it carried the mob TWICE (the drag moved it with the ship
     * and the velocity moved it at ship speed on top), so mobs shot straight
     * off any moving or rotating plated ship.
     */
    private static void gravityunbound$applyShipFriction(
            @Nullable Ship ship, GravityPlatingBlockEntity be, Direction plateDir, Entity entity
    ) {
        if (ship == null || !(entity instanceof LivingEntity living)) {
            return;
        }
        if (entity instanceof Player) {
            // players use capsule collision + ship-surface drag instead
            return;
        }
        if (!entity.onGround()) {
            return;
        }
        // only when the entity is not trying to move
        if (Math.abs(living.xxa) > 0.01 || Math.abs(living.zza) > 0.01) {
            return;
        }

        Vec3 worldVel = GravityChangerAPI.getWorldVelocity(entity);
        Vector3d vel = new Vector3d(worldVel.x, worldVel.y, worldVel.z);

        // plate normal in world space
        Vector3d normal = new Vector3d(plateDir.getStepX(), plateDir.getStepY(), plateDir.getStepZ());
        ship.getTransform().getShipToWorldMatrix().transformDirection(normal);
        normal.normalize();

        double normalComponent = vel.dot(normal);
        Vector3d tangential = new Vector3d(vel).sub(new Vector3d(normal).mul(normalComponent));

        // damp tangential slip instead of zeroing it
        tangential.mul(0.5);

        Vector3d newVel = new Vector3d(normal).mul(normalComponent).add(tangential);
        GravityChangerAPI.setWorldVelocity(entity, new Vec3(newVel.x, newVel.y, newVel.z));
    }

    private static void tryToDoCornerAutoJump(
            BlockState blockState, BlockPos blockPos,
            Entity entity, GravityCapabilityImpl comp, @Nullable Ship ship
    ) {
        if (!entity.onGround()) {
            return;
        }

        Direction entityGravityDir = comp.getCurrGravityDirection();

        Vec3 entityPosLocal;
        if (ship != null) {
            Vector3d posJoml = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
            ship.getTransform().getWorldToShipMatrix().transformPosition(posJoml);
            entityPosLocal = new Vec3(posJoml.x, posJoml.y, posJoml.z);
        } else {
            entityPosLocal = entity.position();
        }

        // the entity's gravity is a WORLD direction; every comparison below
        // happens in SHIP-local space on ships, so bring it into that frame
        // once (the old code dotted a ship-local offset against the world
        // vector and later double-rotated the gravity component of the kick)
        Vec3 entityGravityVecLocal = Vec3.atLowerCornerOf(entityGravityDir.getNormal());
        if (ship != null) {
            Vector3d g = new Vector3d(entityGravityVecLocal.x, entityGravityVecLocal.y, entityGravityVecLocal.z);
            ship.getTransform().getWorldToShipMatrix().transformDirection(g);
            g.normalize();
            entityGravityVecLocal = new Vec3(g.x, g.y, g.z);
        }

        // axis comparison must also happen in ship-local space
        Direction gravityDirShipLocal = Direction.getNearest(
            entityGravityVecLocal.x, entityGravityVecLocal.y, entityGravityVecLocal.z
        );

        for (Direction plateDir : Direction.values()) {
            if (GravityPlatingBlock.hasDir(blockState, plateDir)) {
                boolean orthogonal = gravityDirShipLocal.getAxis() != plateDir.getAxis();
                if (!orthogonal) {
                    continue;
                }

                Vec3 plateDirVec = Vec3.atLowerCornerOf(plateDir.getNormal());

                Vec3 effectCenter = Vec3.atCenterOf(blockPos).add(plateDirVec.scale(0.5));

                Vec3 offset = effectCenter.subtract(entityPosLocal);
                if (offset.dot(entityGravityVecLocal) > 0) {
                    continue;
                }

                Vec3 worldVelocity = GravityChangerAPI.getWorldVelocity(entity);

                // compare velocity to the plate direction in ship-local space
                if (ship != null) {
                    Vector3d velJoml = new Vector3d(worldVelocity.x, worldVelocity.y, worldVelocity.z);
                    ship.getTransform().getWorldToShipMatrix().transformDirection(velJoml);
                    worldVelocity = new Vec3(velJoml.x, velJoml.y, velJoml.z);
                }

                if (worldVelocity.dot(plateDirVec) < 0.01) {
                    continue;
                }

                double distanceToPlate = Math.abs(entityPosLocal.subtract(effectCenter).dot(plateDirVec));
                if (distanceToPlate < 0.8) {
                    double strengthSqrt = Math.sqrt(comp.getCurrGravityStrength());

                    // compose the kick entirely in SHIP-local space (gravity
                    // vector was already brought into it above), then rotate
                    // the combined result to world exactly once
                    Vec3 deltaWorldVelocity =
                            entityGravityVecLocal.scale(-strengthSqrt * 0.4)
                                    .add(plateDirVec.scale(0.08));

                    if (ship != null) {
                        Vector3d deltaJoml = new Vector3d(deltaWorldVelocity.x, deltaWorldVelocity.y, deltaWorldVelocity.z);
                        ship.getTransform().getShipToWorldMatrix().transformDirection(deltaJoml);
                        deltaWorldVelocity = new Vec3(deltaJoml.x, deltaJoml.y, deltaJoml.z);
                    }

                    GravityChangerAPI.setWorldVelocity(
                            entity,
                            GravityChangerAPI.getWorldVelocity(entity).add(deltaWorldVelocity)
                    );

                    return;
                }
            }
        }
    }

    // ---- GravityFieldLookup.Source (fluid gravity queries) ----
    // fluids follow a plate's PRIMARY column only (never the hidden sideways
    // bleed): the effect direction inside the plate's own footprint + range

    @Override
    public @Nullable Direction downAt(BlockPos pos, net.camacraft.gravityunbound.util.GravityFieldLookup.Kind kind) {
        if (sideData == null) {
            return null;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        for (Direction plateDir : Direction.values()) {
            SideData sideDatum = sideData[plateDir.ordinal()];
            if (sideDatum == null
                || !net.camacraft.gravityunbound.util.FieldTargets.has(sideDatum.targets, kind)) {
                continue;
            }
            if (sideDatum.getPrimaryBox(worldPosition, plateDir, getLevel()).contains(center)) {
                return sideDatum.isAttracting ? plateDir : plateDir.getOpposite();
            }
        }
        return null;
    }

    @Override
    public void invalidateEntityCache() {
        cachedEntities = null;
    }

    @Override
    public int sourceMaxRange() {
        if (sideData == null) {
            return 2;
        }
        int max = 1;
        for (SideData sideDatum : sideData) {
            if (sideDatum != null) {
                max = Math.max(max, sideDatum.level);
            }
        }
        return max + 2;
    }

    @Override
    public BlockPos sourcePos() {
        return worldPosition;
    }

    @Override
    public int sourcePriority() {
        return 2;
    }

    public static MutableComponent getForceText(boolean isAttracting) {
        return Component.translatable(
                isAttracting ?
                        "gravity_changer.plate.force.attract" : "gravity_changer.plate.force.repulse"
        );
    }

    /** Give a refund stack to the player; drop it if the inventory is full
     *  (the old bare {@code Inventory.add} silently destroyed it). */
    public static void gravityunbound$refund(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    public void sync() {
        Level world = getLevel();
        Validate.notNull(world);
        Validate.isTrue(!world.isClientSide());

        setChanged();
        ((ServerChunkCache) world.getChunkSource()).blockChanged(this.getBlockPos());
    }

    public void onPlacing(Direction side, SideData sideData) {
        dataInitialized = true;
        refreshCache();
        this.sideData[side.ordinal()] = sideData;
        invalidateBoxCaches();
        sync();
        // a new field over STILL liquid: nothing schedules a still block's
        // fluid tick, so a lake beside a freshly placed plate never noticed
        // the field — wake everything in reach once
        Level world = getLevel();
        if (world != null && !world.isClientSide()) {
            net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluidsAround(
                world, worldPosition, sourceMaxRange());
        }
    }

    /** Cap on how many plates one "apply to connected" flood-fill may touch. */
    private static final int CONNECTED_APPLY_CAP = 4096;

    /**
     * Server-side entry point for the settings GUI (arrives via
     * {@code network.UpdateGravityBlockSettingsPacket}). Every value is
     * clamped to its legal range — client input is never trusted.
     *
     * With {@code applyToConnected} the same settings are flood-filled over
     * the in-plane group of adjacent plates with the same facing (the same
     * adjacency rule as the visual grouping in computeVisualGroup /
     * isSameVisualGroup), capped at {@link #CONNECTED_APPLY_CAP} plates.
     */
    public void applySettingsFromGui(
        Direction side, int newLevel, boolean isAttracting, boolean gradualFalloff,
        double gravityAccel, boolean surfaceSnap, boolean showParticles, boolean applyToConnected
    ) {
        SideData own = getSideData(side);
        applySettingsFromGui(side, newLevel, isAttracting, gradualFalloff, gravityAccel, surfaceSnap, showParticles,
            own != null ? own.affectsShips : true,
            own != null ? own.targets : net.camacraft.gravityunbound.util.FieldTargets.ALL,
            own != null ? own.replacesGravity : true,
            applyToConnected);
    }

    public void applySettingsFromGui(
        Direction side, int newLevel, boolean isAttracting, boolean gradualFalloff,
        double gravityAccel, boolean surfaceSnap, boolean showParticles,
        boolean affectsShips, int targets, boolean replacesGravity, boolean applyToConnected
    ) {
        Level world = getLevel();
        if (world == null || world.isClientSide()) {
            return;
        }

        refreshCache();
        SideData own = getSideData(side);
        if (own == null) {
            return;
        }

        int clampedLevel = Mth.clamp(newLevel, 1, maxLevel());
        double clampedAccel = Mth.clamp(gravityAccel, 0.0, 1.0);
        int clampedTargets = net.camacraft.gravityunbound.util.FieldTargets.sanitize(targets);

        if (!applyToConnected) {
            writeSideSettings(own, clampedLevel, isAttracting, gradualFalloff, clampedAccel, surfaceSnap, showParticles,
                affectsShips, clampedTargets, replacesGravity);
            invalidateBoxCaches();
            sync();
            return;
        }

        // flood-fill the in-plane group of adjacent plates with the same facing
        Direction.Axis axis = side.getAxis();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        List<GravityPlatingBlockEntity> members = new ArrayList<>();

        BlockPos origin = getBlockPos();
        visited.add(origin);
        queue.add(origin);
        members.add(this);

        while (!queue.isEmpty() && members.size() < CONNECTED_APPLY_CAP) {
            BlockPos cur = queue.poll();
            for (Direction tangent : Direction.values()) {
                if (tangent.getAxis() == axis) {
                    continue;
                }
                BlockPos next = cur.relative(tangent);
                if (!visited.add(next.immutable())) {
                    continue;
                }
                BlockState state = world.getBlockState(next);
                if (!(state.getBlock() instanceof GravityPlatingBlock)
                    || !GravityPlatingBlock.hasDir(state, side)
                ) {
                    continue;
                }
                if (!(world.getBlockEntity(next) instanceof GravityPlatingBlockEntity nbe)
                    || nbe.getSideData(side) == null
                ) {
                    continue;
                }
                members.add(nbe);
                if (members.size() >= CONNECTED_APPLY_CAP) {
                    break;
                }
                queue.add(next.immutable());
            }
        }

        for (GravityPlatingBlockEntity member : members) {
            SideData memberSide = member.getSideData(side);
            if (memberSide == null) {
                continue;
            }
            writeSideSettings(memberSide, clampedLevel, isAttracting, gradualFalloff, clampedAccel, surfaceSnap, showParticles,
                affectsShips, clampedTargets, replacesGravity);
            member.invalidateBoxCaches();
            member.sync();
        }
    }

    private static void writeSideSettings(
        SideData side, int level, boolean isAttracting, boolean gradualFalloff,
        double gravityAccel, boolean surfaceSnap, boolean showParticles,
        boolean affectsShips, int targets, boolean replacesGravity
    ) {
        side.level = level;
        side.isAttracting = isAttracting;
        side.gradualFalloff = gradualFalloff;
        side.gravityAccel = gravityAccel;
        side.surfaceSnap = surfaceSnap;
        side.showParticles = showParticles;
        side.affectsShips = affectsShips;
        side.targets = targets;
        side.replacesGravity = replacesGravity;
    }

    public List<ItemStack> getDrops() {
        if (sideData == null) {
            return List.of();
        }

        List<ItemStack> drops = new ArrayList<>();
        for (Direction value : Direction.values()) {
            SideData sideDatum = sideData[value.ordinal()];
            if (sideDatum != null) {
                ItemStack stack = GravityPlatingItem.createStack(sideDatum);
                drops.add(stack);
            }
        }
        return drops;
    }
}

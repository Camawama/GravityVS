package net.cama.gravityapivs.plating;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import net.cama.gravityapivs.EntityTags;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.api.RotationParameters;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.init.GravityBlocks;
import net.cama.gravityapivs.util.GCUtil;
import net.cama.gravityapivs.util.RotationUtil;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Based on code from AmethystGravity (by CyborgCabbage)
 */
public class GravityPlatingBlockEntity extends BlockEntity {

    // rotateVelocity=true, rotateView=true; rotation time only applies to
    // discrete flips (small drift is handled smoothly by the frame transport)
    private static final RotationParameters PLATING_ROTATION_PARAMS = new RotationParameters(true, true, 300);

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

        public @Nullable AABB effectBoxCache = null;

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
            return data;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("isAttracting", isAttracting);
            tag.putInt("level", level);
            tag.putBoolean("showParticles", showParticles);
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
    }

    private @Nullable SideData[] sideData = null;

    private @Nullable AABB roughAreaBoxCache = null;

    public @Nullable SideData getSideData(Direction dir) {
        if (sideData == null) {
            return null;
        }
        return sideData[dir.ordinal()];
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

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
        }
    }

    public void invalidateBoxCaches() {
        roughAreaBoxCache = null;
        if (sideData != null) {
            for (SideData sideDatum : sideData) {
                if (sideDatum != null) {
                    sideDatum.effectBoxCache = null;
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

        be.refreshCache(blockState);

        AABB roughBox = be.getRoughEffectBox();
        AABB searchBox = roughBox;

        Ship ship = VSGameUtilsKt.getShipManagingPos(world, blockPos);

        if (ship != null) {
            searchBox = gravityapivs$shipToWorldBox(ship, roughBox);
        }

        if (world.isClientSide()) {
            be.submitFieldVisuals(world, blockPos, ship);
        }

        List<Entity> entities = world.getEntitiesOfClass(
                Entity.class,
                searchBox,
                EntityTags::canChangeGravity
        );

        for (Entity entity : entities) {
            // on the client, only the locally controlled entity computes gravity
            // from fields; remote entities follow the server sync
            if (world.isClientSide() && !entity.isControlledByLocalInstance() && !GCUtil.isClientPlayer(entity)) {
                continue;
            }

            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
            if (comp == null) {
                continue;
            }
            Vec3 entityGravityDir = comp.getCurrGravityDirectionVec();

            boolean applies = false;
            Direction bestPlateDir = null;
            double bestDistance = Double.MAX_VALUE;

            for (Direction plateDir : Direction.values()) {
                SideData sideDatum = be.sideData[plateDir.ordinal()];
                if (sideDatum == null) {
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
                comp.applyGravityDirectionEffect(
                        worldEffectDir, PLATING_ROTATION_PARAMS, priority
                );
                applies = true;

                if (distanceToPlate < bestDistance) {
                    bestDistance = distanceToPlate;
                    bestPlateDir = plateDir;
                }
            }

            if (!applies) {
                continue;
            }

            // velocity writes: only on the side that controls this entity
            boolean controlsEntity = world.isClientSide()
                ? entity.isControlledByLocalInstance()
                : !(entity instanceof Player);

            if (controlsEntity && bestPlateDir != null) {
                gravityapivs$applyArtificialGravityForce(world, ship, be, bestPlateDir, entity, comp);
                gravityapivs$applyShipFriction(ship, be, bestPlateDir, entity);
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
            net.cama.gravityapivs.util.FieldVisuals.submitPlate(
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

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction tangent : Direction.values()) {
                if (tangent.getAxis() == axis) {
                    continue;
                }
                BlockPos next = cur.relative(tangent);
                if (members.contains(next) || !isSameVisualGroup(world, next, plateDir, side)) {
                    continue;
                }
                if (members.size() >= 121) {
                    overflow = true;
                    break;
                }
                members.add(next.immutable());
                queue.add(next);
                minX = Math.min(minX, next.getX());
                minY = Math.min(minY, next.getY());
                minZ = Math.min(minZ, next.getZ());
                maxX = Math.max(maxX, next.getX());
                maxY = Math.max(maxY, next.getY());
                maxZ = Math.max(maxZ, next.getZ());
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

        side.visualMaster = origin.getX() == minX && origin.getY() == minY && origin.getZ() == minZ;
        side.visualBoxCache = buildFieldBox(minX, minY, minZ, maxX, maxY, maxZ, plateDir, side.getEffectRange());
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

        switch (plateDir.getOpposite()) {
            case UP -> y2 += effectRange;
            case DOWN -> y1 -= effectRange;
            case SOUTH -> z2 += effectRange;
            case NORTH -> z1 -= effectRange;
            case EAST -> x2 += effectRange;
            case WEST -> x1 -= effectRange;
        }
        return new AABB(x1, y1, z1, x2, y2, z2);
    }

    private static AABB gravityapivs$shipToWorldBox(Ship ship, AABB box) {
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
     * In configured zero-g dimensions the field also accelerates the entity
     * (vanilla gravity is absent there). Scaled by the entity's gravity strength
     * so it agrees with normal gravity handling.
     */
    private static void gravityapivs$applyArtificialGravityForce(
            Level world, @Nullable Ship ship, GravityPlatingBlockEntity be,
            Direction plateDir, Entity entity, GravityCapabilityImpl comp
    ) {
        String currentDim = world.dimension().location().toString();
        if (!GravityConfig.artificialGravityDimensions.get().contains(currentDim)) {
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
        }

        accel.mul(GravityConfig.artificialGravityAcceleration.get() * comp.getCurrGravityStrength());

        Vec3 currentVel = GravityChangerAPI.getWorldVelocity(entity);
        // crude terminal velocity so stacked fields cannot accelerate indefinitely
        Vec3 gravityDir = comp.getCurrGravityDirectionVec();
        if (currentVel.dot(gravityDir) < 3.0) {
            GravityChangerAPI.setWorldVelocity(
                    entity,
                    currentVel.add(accel.x, accel.y, accel.z)
            );
        }
    }

    /**
     * Extra grip when standing on a moving/rotating ship: damp the entity's
     * velocity *relative to the ship surface* tangentially to the plate. Never
     * zeroes momentum outright and never fights the ship's own motion.
     */
    private static void gravityapivs$applyShipFriction(
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

        // velocity of the ship surface at the entity's position
        Vector3d shipVelocity = new Vector3d(ship.getVelocity());
        Vector3d omega = new Vector3d(ship.getOmega());
        Vector3d r = new Vector3d(entity.getX(), entity.getY(), entity.getZ())
                .sub(ship.getTransform().getPositionInWorld());
        Vector3d surfaceVel = omega.cross(r, new Vector3d()).add(shipVelocity);

        Vec3 worldVel = GravityChangerAPI.getWorldVelocity(entity);
        Vector3d relVel = new Vector3d(worldVel.x, worldVel.y, worldVel.z).sub(surfaceVel);

        // plate normal in world space
        Vector3d normal = new Vector3d(plateDir.getStepX(), plateDir.getStepY(), plateDir.getStepZ());
        ship.getTransform().getShipToWorldMatrix().transformDirection(normal);
        normal.normalize();

        double normalComponent = relVel.dot(normal);
        Vector3d tangential = new Vector3d(relVel).sub(new Vector3d(normal).mul(normalComponent));

        // damp tangential slip instead of zeroing it (keeps knockback/jumps alive)
        tangential.mul(0.5);

        Vector3d newVel = new Vector3d(normal).mul(normalComponent).add(tangential).add(surfaceVel);
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

        for (Direction plateDir : Direction.values()) {
            if (GravityPlatingBlock.hasDir(blockState, plateDir)) {
                boolean orthogonal = entityGravityDir.getAxis() != plateDir.getAxis();
                if (!orthogonal) {
                    continue;
                }

                Vec3 plateDirVec = Vec3.atLowerCornerOf(plateDir.getNormal());

                Vec3 effectCenter = Vec3.atCenterOf(blockPos).add(plateDirVec.scale(0.5));

                Vec3 offset = effectCenter.subtract(entityPosLocal);
                if (offset.dot(Vec3.atLowerCornerOf(entityGravityDir.getNormal())) > 0) {
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

                    Vec3 entityGravityVec = Vec3.atLowerCornerOf(entityGravityDir.getNormal());

                    Vec3 deltaWorldVelocity =
                            entityGravityVec.scale(-strengthSqrt * 0.4)
                                    .add(plateDirVec.scale(0.08));

                    // deltaWorldVelocity was computed in ship-local space; transform
                    // back to world space before applying
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

    public InteractionResult interact(Level level, BlockPos pos, Direction plateDir, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        refreshCache();

        SideData sideDatum = sideData[plateDir.ordinal()];

        if (sideDatum == null) {
            return InteractionResult.FAIL;
        }

        ItemStack handItem = player.getItemInHand(hand);
        if (handItem.getItem() == Items.AIR) {
            if (sideDatum.level != 1) {
                sideDatum.level -= 1;
                if (!player.isCreative()) {
                    player.getInventory().add(new ItemStack(Items.AMETHYST_CLUSTER));
                }
            }
            else {
                sideDatum.isAttracting = !sideDatum.isAttracting;
            }
        }
        else if (handItem.getItem() == Items.AMETHYST_CLUSTER) {
            if (sideDatum.level >= maxLevel()) {
                player.displayClientMessage(
                        Component.translatable("gravity_changer.plate.max_level"), true
                );
                return InteractionResult.FAIL;
            }

            if (!player.isCreative()) {
                handItem.shrink(1);
            }

            sideDatum.level += 1;
        }
        else if (handItem.getItem() == Items.GLOW_INK_SAC) {
            sideDatum.showParticles = !sideDatum.showParticles;
            if (sideDatum.showParticles && !player.isCreative()) {
                handItem.shrink(1);
            }
            sync();
            player.displayClientMessage(
                    Component.translatable(sideDatum.showParticles
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

        invalidateBoxCaches();
        sync();

        boolean isAttracting = sideDatum.isAttracting;
        player.displayClientMessage(
                Component.translatable(
                        "gravity_changer.plate.status",
                        GCUtil.getDirectionText(plateDir.getOpposite()),
                        sideDatum.level,
                        getForceText(isAttracting)
                ),
                true
        );

        return InteractionResult.SUCCESS;
    }

    public static MutableComponent getForceText(boolean isAttracting) {
        return Component.translatable(
                isAttracting ?
                        "gravity_changer.plate.force.attract" : "gravity_changer.plate.force.repulse"
        );
    }

    public void sync() {
        Level world = getLevel();
        Validate.notNull(world);
        Validate.isTrue(!world.isClientSide());

        setChanged();
        ((ServerChunkCache) world.getChunkSource()).blockChanged(this.getBlockPos());
    }

    public void onPlacing(Direction side, SideData sideData) {
        refreshCache();
        this.sideData[side.ordinal()] = sideData;
        invalidateBoxCaches();
        sync();
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

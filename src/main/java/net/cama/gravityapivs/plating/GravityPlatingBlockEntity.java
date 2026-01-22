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

// VS2 IMPORTS
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
import net.minecraft.server.level.ServerPlayer;
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

    private static final int MAX_LEVEL = 64;
    
    // rotateVelocity=true, rotateView=true, rotationTime=0 (instant/continuous)
    private static final RotationParameters PLATING_ROTATION_PARAMS = new RotationParameters(true, true, 0);

    // HSRDCODED DIMENSION LIST
    // Artificial gravity from gravity plating will ONLY apply in these dimensions.
    private static final List<String> ARTIFICIAL_GRAVITY_DIMS = List.of(
            "ad_astra:earth_orbit",
            "ad_astra:glacio_orbit",
            "ad_astra:mars_orbit",
            "ad_astra:mercury_orbit",
            "ad_astra:moon_orbit",
            "ad_astra:venus_orbit",
            "genesis:great_unknown",
            "genesis:wormhole"
    );

    public GravityPlatingBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_PLATING_BLOCK_ENTITY.get(), pos, state);
    }

    public static class SideData {
        public boolean isAttracting = true;
        public int level = 1;

        public @Nullable AABB effectBoxCache = null;

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

            level_ = Mth.clamp(level_, 1, MAX_LEVEL);

            return new SideData(isAttracting_, level_);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("isAttracting", isAttracting);
            tag.putInt("level", level);
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

                BlockPos wallPos = blockPos.relative(plateDir);
                for (Direction sideDir : Direction.values()) {
                    if (sideDir.getAxis() == plateDir.getAxis()) {continue;}

                    BlockPos sidePos = wallPos.relative(sideDir);
                    BlockState sideBlockState = world.getBlockState(sidePos);
                    if (!(sideBlockState.getBlock() instanceof GravityPlatingBlock sidePlatingBlock)) {continue;}

                    if (!GravityPlatingBlock.hasDir(sideBlockState, sideDir.getOpposite())) {continue;}

                    if (!(world.getBlockEntity(sidePos) instanceof GravityPlatingBlockEntity be)) {continue;}

                    if (isAttracting != this.isAttracting) {continue;}

                    double sideDelta = getEffectRange();
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
        Level world = getLevel();

        if (world == null) {
            return;
        }

        if (sideData == null) {
            sideData = new SideData[6];
        }

        BlockState blockState = world.getBlockState(this.getBlockPos());
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

        if (this.worldPosition.hashCode() % 5 == world.getGameTime() % 5) {
            roughAreaBoxCache = null;
            for (SideData sideDatum : sideData) {
                if (sideDatum != null) {
                    sideDatum.effectBoxCache = null;
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
            return new AABB(
                    blockPos.getX() - delta, blockPos.getY() - delta, blockPos.getZ() - delta,
                    blockPos.getX() + 1 + delta, blockPos.getY() + 1 + delta, blockPos.getZ() + 1 + delta
            );
        }
        return roughAreaBoxCache;
    }

    public static void tick(Level world, BlockPos blockPos, BlockState blockState, GravityPlatingBlockEntity be) {
        if (!(blockState.getBlock() instanceof GravityPlatingBlock gravityPlatingBlock)) {
            return;
        }

        be.refreshCache();

        AABB roughBox = be.getRoughEffectBox();
        AABB searchBox = roughBox;

        // --- VS2 COMPATIBILITY START ---
        Ship ship = null;
        try {
            ship = VSGameUtilsKt.getShipManagingPos(world, blockPos);
        } catch (Exception e) {
            // VS2 probably not present or error, ignore
        }

        if (ship != null) {
            Vector3d min = new Vector3d(roughBox.minX, roughBox.minY, roughBox.minZ);
            Vector3d max = new Vector3d(roughBox.maxX, roughBox.maxY, roughBox.maxZ);

            Vector3d[] corners = new Vector3d[] {
                    new Vector3d(min.x, min.y, min.z), new Vector3d(max.x, min.y, min.z),
                    new Vector3d(min.x, max.y, min.z), new Vector3d(max.x, max.y, min.z),
                    new Vector3d(min.x, min.y, max.z), new Vector3d(max.x, min.y, max.z),
                    new Vector3d(min.x, max.y, max.z), new Vector3d(max.x, max.y, max.z)
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

            searchBox = new AABB(wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ);
        }
        // --- VS2 COMPATIBILITY END ---

        List<Entity> entities = world.getEntitiesOfClass(
                Entity.class,
                searchBox,
                e -> EntityTags.canChangeGravity(e)
        );

        for (Entity entity : entities) {
            boolean applies = false;

            GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponent(entity);
            Vec3 entityGravityDir = comp.getCurrGravityDirectionVec();

            for (Direction plateDir : Direction.values()) {
                SideData sideDatum = be.sideData[plateDir.ordinal()];
                if (sideDatum != null) {
                    Direction localEffectDir = sideDatum.isAttracting ? plateDir : plateDir.getOpposite();

                    // 1. Calculate the 'Real' World Direction required
                    Vec3 worldEffectDir = Vec3.atLowerCornerOf(localEffectDir.getNormal());
                    if (ship != null) {
                        Vector3d dirVec = new Vector3d(localEffectDir.getStepX(), localEffectDir.getStepY(), localEffectDir.getStepZ());
                        ship.getTransform().getShipToWorldMatrix().transformDirection(dirVec);
                        worldEffectDir = new Vec3(dirVec.x, dirVec.y, dirVec.z);
                    }

                    boolean isOpposite = (entityGravityDir.normalize().dot(worldEffectDir.normalize()) < -0.99);

                    // VS2 COMPAT: Transform entity position to Ship Space
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

                    // --- ARTIFICIAL GRAVITY FORCE (Fix for Space) ---
                    // CHECK: Are we in one of the specific Zero-G dimensions?
                    String currentDim = world.dimension().location().toString();

                    if (ARTIFICIAL_GRAVITY_DIMS.contains(currentDim)) {
                        // 1. Get local gravity vector (e.g. 0, -1, 0)
                        Vector3d artificialGravity = new Vector3d(
                                localEffectDir.getStepX(),
                                localEffectDir.getStepY(),
                                localEffectDir.getStepZ()
                        );

                        // 2. Transform to World Space (handles ship rotation)
                        if (ship != null) {
                            ship.getTransform().getShipToWorldMatrix().transformDirection(artificialGravity);
                        }

                        // 3. Scale by Standard Gravity Strength (approx 0.08 blocks/tick)
                        artificialGravity.mul(0.08);

                        // 4. Add to current velocity
                        Vec3 currentVel = GravityChangerAPI.getWorldVelocity(entity);
                        GravityChangerAPI.setWorldVelocity(
                                entity,
                                currentVel.add(artificialGravity.x, artificialGravity.y, artificialGravity.z)
                        );
                    }
                    // ----------------------------------------------------

                    // --- VS2 COMPAT: ANTI-SLIDE LOGIC ---
                    if (ship != null && entity instanceof LivingEntity living) {
                        if (Math.abs(living.xxa) < 0.01 && Math.abs(living.zza) < 0.01) {
                            Vec3 worldVel = GravityChangerAPI.getWorldVelocity(entity);
                            Vector3d shipVel = new Vector3d(worldVel.x, worldVel.y, worldVel.z);
                            ship.getTransform().getWorldToShipMatrix().transformDirection(shipVel);

                            Vector3d plateNormal = new Vector3d(plateDir.getStepX(), plateDir.getStepY(), plateDir.getStepZ());
                            double dot = shipVel.dot(plateNormal);

                            shipVel.set(plateNormal).mul(dot);

                            ship.getTransform().getShipToWorldMatrix().transformDirection(shipVel);
                            GravityChangerAPI.setWorldVelocity(entity, new Vec3(shipVel.x, shipVel.y, shipVel.z));
                        }
                    }
                    // --- END ANTI-SLIDE ---
                }
            }

            if (applies && GravityConfig.autoJumpOnGravityPlateInnerCorner.get()) {
                tryToDoCornerAutoJump(blockState, blockPos, entity, comp, ship);
            }
        }
    }

    // Updated signature to accept Ship
    private static void tryToDoCornerAutoJump(
            BlockState blockState, BlockPos blockPos,
            Entity entity, GravityCapabilityImpl comp, @Nullable Ship ship
    ) {
        if (!entity.onGround()) {
            return;
        }

        Direction entityGravityDir = comp.getCurrGravityDirection();

        // VS2 COMPAT: Get Local Position
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

                // VS2 COMPAT: Use entityPosLocal
                Vec3 offset = effectCenter.subtract(entityPosLocal);
                if (offset.dot(Vec3.atLowerCornerOf(entityGravityDir.getNormal())) > 0) {
                    continue;
                }

                Vec3 worldVelocity = GravityChangerAPI.getWorldVelocity(entity);

                // Note: We are comparing world velocity to plate direction.
                // On a ship, plateDirVec is Ship Local. worldVelocity is World Global.
                // Strictly speaking, we should transform worldVelocity to Ship Local too.
                if (ship != null) {
                    Vector3d velJoml = new Vector3d(worldVelocity.x, worldVelocity.y, worldVelocity.z);
                    ship.getTransform().getWorldToShipMatrix().transformDirection(velJoml); // Direction, not Position
                    worldVelocity = new Vec3(velJoml.x, velJoml.y, velJoml.z);
                }

                if (worldVelocity.dot(plateDirVec) < 0.01) {
                    continue;
                }

                // VS2 COMPAT: Use entityPosLocal
                double distanceToPlate = Math.abs(entityPosLocal.subtract(effectCenter).dot(plateDirVec));
                if (distanceToPlate < 0.8) {
                    double strengthSqrt = Math.sqrt(comp.getCurrGravityStrength());

                    Vec3 entityGravityVec = Vec3.atLowerCornerOf(entityGravityDir.getNormal());

                    Vec3 deltaWorldVelocity =
                            entityGravityVec.scale(-strengthSqrt * 0.4)
                                    .add(plateDirVec.scale(0.08));

                    // Note: If on ship, we calculated deltaWorldVelocity in Ship Local space.
                    // We must transform it back to World Global to apply it to the entity.
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
            if (!player.isCreative()) {
                handItem.shrink(1);
            }

            sideDatum.level += 1;

            if (sideDatum.level > MAX_LEVEL) {
                sideDatum.level = MAX_LEVEL;
            }
        }
        else {
            ((ServerPlayer) player).sendSystemMessage(
                    Component.translatable("gravity_changer.plate.wrong_interaction"),
                    true
            );
            return InteractionResult.FAIL;
        }

        sync();

        boolean isAttracting = sideDatum.isAttracting;
        ((ServerPlayer) player).sendSystemMessage(
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
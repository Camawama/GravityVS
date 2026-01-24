package net.cama.gravityapivs.capabilities;

import java.util.ArrayList;
import java.util.List;

import net.cama.gravityapivs.EntityTags;
import net.cama.gravityapivs.RotationAnimation;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.api.RotationParameters;
import net.cama.gravityapivs.config.GravityConfig;
import net.cama.gravityapivs.init.GravityMobEffects;
import net.cama.gravityapivs.item.GravityAnchorItem;
import net.cama.gravityapivs.mixin.EntityAccessor;
import net.cama.gravityapivs.mob_effect.GravityDirectionMobEffect;
import net.cama.gravityapivs.network.GravityNetwork;
import net.cama.gravityapivs.network.UpdateGravityCapabilityPacket;
import net.cama.gravityapivs.network.UpdateGravitySyncStatePacket;
import net.cama.gravityapivs.util.GCUtil;
import net.cama.gravityapivs.util.QuaternionUtil;
import net.cama.gravityapivs.util.RotationUtil;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.PacketDistributor;

/**
 * The gravity is determined by the follows:
 * 1. base gravity
 * 2. gravity modifier, can override base gravity (determined from modifier events)
 * 3. gravity effects, can override modified gravity
 * The result of applying 1 and 2 is called modified gravity and is synced.
 * The result of 3 is current gravity and is not synced.
 * The gravity effect should be applied both on client and server, except for remote players.
 * (The client player's gravity attributes are separately computed.
 * Other client entities' are synced from server.)
 */
public class GravityCapabilityImpl implements IGravityCapability {
    public boolean initialized = false;
    
    // not synchronized
    private Vec3 prevGravityDirection = new Vec3(0, -1, 0);
    private double prevGravityStrength = 1.0;
    
    // the base gravity direction
    Vec3 baseGravityDirection = new Vec3(0, -1, 0);
    
    // the base gravity strength
    double baseGravityStrength = 1.0;
    
    @Nullable RotationParameters currentRotationParameters = RotationParameters.getDefault();
    
    // Only used on client, not synchronized.
    @Nullable
    public RotationAnimation animation;
    
    public Entity entity;
    
    private Vec3 currGravityDirection = new Vec3(0, -1, 0);
    private double currGravityStrength = 1.0;
    
    private boolean isFiringUpdateEvent = false;
    
    private List<GravityDirEffect> delayApplyDirEffects = new ArrayList<>();
    private List<GravityDirEffect> tempEffects = new ArrayList<>();
    
    private double delayApplyStrengthEffect = 1.0;
    
    // only used on server side
    public boolean needsSync = false;
    
    public boolean noAnimation = false;
    public boolean noPositionAdjust = false;
    
	@Override
	public void setEntity(Entity entity) 
	{
		this.entity = entity;
        if (entity.level.isClientSide()) {
            animation = new RotationAnimation();
        }
        else {
            animation = null;
        }
	}
    
    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains("baseGravityDirectionX")) {
            baseGravityDirection = new Vec3(
                tag.getDouble("baseGravityDirectionX"),
                tag.getDouble("baseGravityDirectionY"),
                tag.getDouble("baseGravityDirectionZ")
            );
        }
        else if (tag.contains("baseGravityDirection")) {
            // Legacy support
            Direction dir = Direction.byName(tag.getString("baseGravityDirection"));
            if (dir != null) {
                baseGravityDirection = Vec3.atLowerCornerOf(dir.getNormal());
            }
        }
        else {
            baseGravityDirection = new Vec3(0, -1, 0);
        }
        
        if (tag.contains("baseGravityStrength")) {
            baseGravityStrength = tag.getDouble("baseGravityStrength");
        }
        else {
            baseGravityStrength = 1.0;
        }
        
        // the current gravity is serialized to avoid unnecessary gravity rotation when entering world
        // do not deserialize it when for client player when not initializing
        if (!initialized || shouldAcceptServerSync()) {
            if (tag.contains("currentGravityDirectionX")) {
                currGravityDirection = new Vec3(
                    tag.getDouble("currentGravityDirectionX"),
                    tag.getDouble("currentGravityDirectionY"),
                    tag.getDouble("currentGravityDirectionZ")
                );
            }
            else if (tag.contains("currentGravityDirection")) {
                // Legacy support
                Direction dir = Direction.byName(tag.getString("currentGravityDirection"));
                if (dir != null) {
                    currGravityDirection = Vec3.atLowerCornerOf(dir.getNormal());
                }
            }
            else {
                currGravityDirection = new Vec3(0, -1, 0);
            }
            
            if (tag.contains("currentGravityStrength")) {
                currGravityStrength = tag.getDouble("currentGravityStrength");
            }
            else {
                currGravityStrength = 1.0;
            }
        }
        
        if (!initialized) {
            prevGravityDirection = currGravityDirection;
            prevGravityStrength = currGravityStrength;
            initialized = true;
            this.needsSync = true;
            this.noAnimation = true;
            applyGravityDirectionChange(
                prevGravityDirection, currGravityDirection, currentRotationParameters, true
            );
        }
    }
    
    private boolean shouldAcceptServerSync() {
        return entity.level().isClientSide() && !GCUtil.isClientPlayer(entity);
    }
    
    @Override
    public CompoundTag serializeNBT() {
		CompoundTag tag = new CompoundTag();
        tag.putDouble("baseGravityDirectionX", baseGravityDirection.x);
        tag.putDouble("baseGravityDirectionY", baseGravityDirection.y);
        tag.putDouble("baseGravityDirectionZ", baseGravityDirection.z);
        
        tag.putDouble("currentGravityDirectionX", currGravityDirection.x);
        tag.putDouble("currentGravityDirectionY", currGravityDirection.y);
        tag.putDouble("currentGravityDirectionZ", currGravityDirection.z);
        
        tag.putDouble("baseGravityStrength", baseGravityStrength);
        tag.putDouble("currentGravityStrength", currGravityStrength);
		return tag;
    }
    
    @Override
    public void tick() {
        if (!canChangeGravity()) {
            return;
        }
        
        updateGravityStatus(true);
        
        applyGravityChange();
        
        if (!entity.level.isClientSide()) {
            if (needsSync) {
                sendSyncPacketToOtherPlayers();
            }
        }
    }
    
    public void updateGravityStatus(boolean sendPacketIfNecessary) {
        // for the remote players and non-player entities,
        // their effect data is not synchronized to the client
        // (possibly for making it harder to cheat for hacked clients)
        // then we don't calculate its gravity in normal way in client
        if (shouldAcceptServerSync()) {
            return;
        }
        
        Vec3 oldGravityDirection = currGravityDirection;
        double oldGravityStrength = currGravityStrength;
        
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            currGravityDirection = GravityChangerAPI.getGravityDirectionVec(vehicle);
            currGravityStrength = GravityChangerAPI.getGravityStrength(vehicle);
        }
        else {
            currGravityDirection = baseGravityDirection;
            currGravityStrength = baseGravityStrength;
            currGravityStrength *= GravityConfig.gravityStrengthMultiplier.get();
            
            tempEffects.clear();
            isFiringUpdateEvent = true;
            try {
                for (ItemStack handSlot : entity.getHandSlots()) {
                    Item item = handSlot.getItem();
                    if (item instanceof GravityAnchorItem anchorItem) {
                        this.applyGravityDirectionEffect(
                            Vec3.atLowerCornerOf(anchorItem.direction.getNormal()),
                            null, 1000000
                        );
                    }
                }
                if (entity instanceof LivingEntity livingEntity) {
                    for (GravityDirectionMobEffect dirEffect : GravityDirectionMobEffect.EFFECT_MAP.values()) {
                        MobEffectInstance effectInstance = livingEntity.getEffect(dirEffect);
                        if (effectInstance != null) {
                            int amplifier = effectInstance.getAmplifier();
                            
                            this.applyGravityDirectionEffect(
                                Vec3.atLowerCornerOf(dirEffect.gravityDirection.getNormal()),
                                null,
                                amplifier + 1.0
                            );
                        }
                    }
                    if (livingEntity.hasEffect(GravityMobEffects.INVERT.get())) {
                        this.applyGravityDirectionEffect(
                        		this.getCurrGravityDirectionVec().scale(-1),
                            null, 5
                        );
                    }
                    GravityMobEffects.INCREASE.get().apply(livingEntity, this);
                    GravityMobEffects.DECREASE.get().apply(livingEntity, this);
                    GravityMobEffects.REVERSE.get().apply(livingEntity, this);
                }
                
                tempEffects.addAll(delayApplyDirEffects);
                delayApplyDirEffects.clear();
                
                currGravityStrength *= delayApplyStrengthEffect;
                delayApplyStrengthEffect = 1.0;
            }
            finally {
                isFiringUpdateEvent = false;
            }
            
            resolveGravityDirection();
        }
        
        if (sendPacketIfNecessary) {
            boolean changed = !oldGravityDirection.equals(currGravityDirection) ||
                Math.abs(oldGravityStrength - currGravityStrength) > 0.0001;
            if (changed) {
                sendSyncPacketToOtherPlayers();
            }
        }
    }
    
    private void resolveGravityDirection() {
        if (tempEffects.isEmpty()) {
            currentRotationParameters = RotationParameters.getDefault();
            return;
        }
        
        // Find max priority
        double maxPriority = -Double.MAX_VALUE;
        for (GravityDirEffect effect : tempEffects) {
            if (effect.priority > maxPriority) {
                maxPriority = effect.priority;
            }
        }
        
        // Blend effects within range
        double BLEND_RANGE = 5.0;
        Vec3 accumulatedGravity = Vec3.ZERO;
        double totalWeight = 0;
        RotationParameters bestParams = null;
        double bestParamPriority = -Double.MAX_VALUE;
        
        for (GravityDirEffect effect : tempEffects) {
            if (effect.priority >= maxPriority - BLEND_RANGE) {
                double weight = 1.0 - (maxPriority - effect.priority) / BLEND_RANGE;
                // Clamp weight to be safe, though logic guarantees >= 0
                weight = Math.max(0, weight);
                
                accumulatedGravity = accumulatedGravity.add(effect.direction.scale(weight));
                totalWeight += weight;
                
                if (effect.priority > bestParamPriority) {
                    bestParamPriority = effect.priority;
                    if (effect.rotationParameters != null) {
                        bestParams = effect.rotationParameters;
                    }
                }
            }
        }
        
        if (totalWeight > 0.0001 && accumulatedGravity.lengthSqr() > 0.0001) {
            currGravityDirection = accumulatedGravity.normalize();
        }
        
        if (bestParams != null) {
            currentRotationParameters = bestParams;
        } else {
            currentRotationParameters = RotationParameters.getDefault();
        }
    }
    
    private void sendSyncPacketToOtherPlayers() 
    {
		if(!this.entity.level.isClientSide)
		{
			GravityNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> this.entity), new UpdateGravityCapabilityPacket(this.noAnimation, this.entity.getUUID(), baseGravityDirection, currGravityDirection, baseGravityStrength, currGravityStrength));
		}
    }
	
	public void sync(boolean noAnimation, Vec3 baseGravityDirection, Vec3 currentGravityDirection, double baseGravityStrength, double currentGravityStrength)
    {
		this.baseGravityDirection = baseGravityDirection;
		this.currGravityDirection = currentGravityDirection;
		this.baseGravityStrength = baseGravityStrength;
		this.currGravityStrength = currentGravityStrength;
		if(noAnimation)
		{
			GravityChangerAPI.instantlySetClientBaseGravityDirection(this.entity, baseGravityDirection);
		}
		GravityNetwork.sendToServer(new UpdateGravitySyncStatePacket(this.entity.getUUID()));
    }
    
    public void applyGravityDirectionEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
        GravityDirEffect effect = new GravityDirEffect(direction, rotationParameters, priority);
        if (isFiringUpdateEvent) {
            tempEffects.add(effect);
        }
        else {
            delayApplyDirEffects.add(effect);
        }
    }
    
    public void applyGravityStrengthEffect(
        double strengthMultiplier
    ) {
        if (isFiringUpdateEvent) {
            currGravityStrength *= strengthMultiplier;
        }
        else {
            delayApplyStrengthEffect *= strengthMultiplier;
        }
    }
    
    public void applyGravityDirectionChange(
        Vec3 oldGravity, Vec3 newGravity,
        RotationParameters rotationParameters, boolean isInitialization
    ) {
        if (!canChangeGravity()) {
            return;
        }
        
        // update bounding box
        entity.setBoundingBox(((EntityAccessor) entity).gc_makeBoundingBox());
        
        // A weird thing is that,
        // using `entity.setPos(entity.position())` to a painting on client side
        // make the painting move wrongly, because Painting overrides `trackingPosition()`.
        // No entity other than Painting overrides that method.
        // It seems to be legacy code from early versions of Minecraft.
        
        if (isInitialization) {
            return;
        }
        
        entity.fallDistance = 0;
        
        long timeMs = entity.level().getGameTime() * 50;
        
        Vec3 relativeRotationCenter = getLocalRotationCenter(
            entity, oldGravity, newGravity, rotationParameters
        );
        Vec3 oldPos = entity.position();
        Vec3 oldLastTickPos = new Vec3(entity.xOld, entity.yOld, entity.zOld);
        Vec3 rotationCenter = oldPos.add(RotationUtil.vecPlayerToWorld(relativeRotationCenter, oldGravity));
        Vec3 newPos = rotationCenter.subtract(RotationUtil.vecPlayerToWorld(relativeRotationCenter, newGravity));
        Vec3 posTranslation = newPos.subtract(oldPos);
        Vec3 newLastTickPos = oldLastTickPos.add(posTranslation);
        
        if(!this.noPositionAdjust)
        {
            entity.setPos(newPos);
            entity.xo = newLastTickPos.x;
            entity.yo = newLastTickPos.y;
            entity.zo = newLastTickPos.z;
            entity.xOld = newLastTickPos.x;
            entity.yOld = newLastTickPos.y;
            entity.zOld = newLastTickPos.z;
            
            adjustEntityPosition(oldGravity, newGravity, entity.getBoundingBox());
        }
        
        if (entity.level().isClientSide()) {
            Validate.notNull(animation, "gravity animation is null");
            
            int rotationTimeMS = rotationParameters.rotationTimeMS();
            
            // Use frame-based time for animation start
            float partialTick = net.minecraft.client.Minecraft.getInstance().getFrameTime();
            long frameTimeMs = entity.level().getGameTime() * 50 + (long) (partialTick * 50);
            
            animation.startRotationAnimation(
                newGravity, oldGravity,
                rotationTimeMS,
                entity, frameTimeMs, rotationParameters.rotateView(),
                relativeRotationCenter
            );
        }
        
        Vec3 realWorldVelocity = getRealWorldVelocity(entity, oldGravity);
        if (rotationParameters.rotateVelocity()) {
            // Rotate velocity with gravity, this will cause things to appear to take a sharp turn
            Vector3f worldSpaceVec = realWorldVelocity.toVector3f();
            worldSpaceVec.rotate(QuaternionUtil.getRotationBetween(oldGravity, newGravity));
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(new Vec3(worldSpaceVec), newGravity));
        }
        else {
            // Velocity will be conserved relative to the world, will result in more natural motion
            entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(realWorldVelocity, newGravity));
        }
    }
    
    // getVelocity() does not return the actual velocity. It returns the velocity plus acceleration.
    // Even if the entity is standing still, getVelocity() will still give a downwards vector.
    // The real velocity is this tick position subtract last tick position
    private static Vec3 getRealWorldVelocity(Entity entity, Vec3 prevGravityDirection) {
        if (entity.isControlledByLocalInstance()) {
            return new Vec3(
                entity.getX() - entity.xo,
                entity.getY() - entity.yo,
                entity.getZ() - entity.zo
            );
        }
        
        return RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), prevGravityDirection);
    }
    
    @NotNull
    private static Vec3 getLocalRotationCenter(
        Entity entity,
        Vec3 oldGravity, Vec3 newGravity, RotationParameters rotationParameters
    ) {
        if (entity instanceof EndCrystal) {
            //In the middle of the block below
            return new Vec3(0, -0.5, 0);
        }
        
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        if (newGravity.normalize().dot(oldGravity.normalize()) < -0.99) {
            // In the center of the hit-box
            return new Vec3(0, dimensions.height / 2, 0);
        }
        else {
            return Vec3.ZERO;
        }
    }
    
    // Adjust position to avoid suffocation in blocks when changing gravity
    private void adjustEntityPosition(Vec3 oldGravity, Vec3 newGravity, AABB entityBoundingBox) {
        if (!GravityConfig.adjustPositionAfterChangingGravity.get()) {
            return;
        }
        
        if (entity instanceof AreaEffectCloud || entity instanceof AbstractArrow || entity instanceof EndCrystal) {
            return;
        }
        
        // for example, if gravity changed from down to north, move up
        // if gravity changed from down to up, also move up
        Vec3 movingDirection = oldGravity.scale(-1);
        
        Iterable<VoxelShape> collisions = entity.level().getCollisions(
            entity,
            entityBoundingBox.inflate(-0.01) // shrink to avoid floating point error
        );
        AABB totalCollisionBox = null;
        for (VoxelShape collision : collisions) {
            if (!collision.isEmpty()) {
                AABB boundingBox = collision.bounds();
                if (totalCollisionBox == null) {
                    totalCollisionBox = boundingBox;
                }
                else {
                    totalCollisionBox = totalCollisionBox.minmax(boundingBox);
                }
            }
        }
        
        if (totalCollisionBox != null) {
            Vec3 positionAdjustmentOffset = getPositionAdjustmentOffset(
                entityBoundingBox, totalCollisionBox, movingDirection
            );
            if (entity instanceof Player) {
                //LOGGER.info("Adjusting player position {} {}", positionAdjustmentOffset, entity);
            }
            entity.setPos(entity.position().add(positionAdjustmentOffset));
        }
    }
    
    private static Vec3 getPositionAdjustmentOffset(
        AABB entityBoundingBox, AABB nearbyCollisionUnion, Vec3 movingDirection
    ) {
        Direction nearestDir = Direction.getNearest(movingDirection.x, movingDirection.y, movingDirection.z);
        Direction.Axis axis = nearestDir.getAxis();
        double offset = 0;
        if (nearestDir.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            double pushing = nearbyCollisionUnion.max(axis);
            double pushed = entityBoundingBox.min(axis);
            if (pushing > pushed) {
                offset = pushing - pushed;
            }
        }
        else {
            double pushing = nearbyCollisionUnion.min(axis);
            double pushed = entityBoundingBox.max(axis);
            if (pushing < pushed) {
                offset = pushed - pushing;
            }
        }
        
        return new Vec3(nearestDir.step()).scale(offset);
    }
    
    public double getBaseGravityStrength() {
        return baseGravityStrength;
    }
    
    public void setBaseGravityStrength(double strength) {
        if (!canChangeGravity()) {
            return;
        }
        
        baseGravityStrength = strength;
        needsSync = true;
    }
    
    public Direction getCurrGravityDirection() {
        return Direction.getNearest(currGravityDirection.x, currGravityDirection.y, currGravityDirection.z);
    }
    
    public Vec3 getCurrGravityDirectionVec() {
        return currGravityDirection;
    }
    
    public double getCurrGravityStrength() {
        return currGravityStrength;
    }
    
    private boolean canChangeGravity() {
        return EntityTags.canChangeGravity(entity);
    }
    
    public Direction getPrevGravityDirection() {
        return Direction.getNearest(prevGravityDirection.x, prevGravityDirection.y, prevGravityDirection.z);
    }
    
    public Direction getBaseGravityDirection() {
        return Direction.getNearest(baseGravityDirection.x, baseGravityDirection.y, baseGravityDirection.z);
    }
    
    public void setBaseGravityDirection(Direction gravityDirection) {
        setBaseGravityDirection(Vec3.atLowerCornerOf(gravityDirection.getNormal()));
    }
    
    public void setBaseGravityDirection(Vec3 gravityDirection) {
        if (!canChangeGravity()) {
            return;
        }
        
        if (!baseGravityDirection.equals(gravityDirection)) {
            baseGravityDirection = gravityDirection;
            needsSync = true;
            
            // update gravity immediately
            // avoid having wrong info from getGravityDirection()
            updateGravityStatus(false); // will this cause issue?
        }
    }
    
    public void reset() {
        baseGravityDirection = new Vec3(0, -1, 0);
        baseGravityStrength = 1.0;
        needsSync = true;
    }
    
    @OnlyIn(Dist.CLIENT)
    public RotationAnimation getRotationAnimation() {
        return animation;
    }
    
    @Override
    public void applyGravityChange() {
        if (currentRotationParameters == null) {
            currentRotationParameters = RotationParameters.getDefault();
        }
        
        if (!prevGravityDirection.equals(currGravityDirection)) {
            applyGravityDirectionChange(
                prevGravityDirection, currGravityDirection,
                currentRotationParameters, false
            );
            prevGravityDirection = currGravityDirection;
        }
        
        if (Math.abs(currGravityStrength - prevGravityStrength) > 0.0001) {
            prevGravityStrength = currGravityStrength;
        }
    }
    
    /**
     * Not needed in normal cases.
     * Only used in {@link GravityChangerAPI#instantlySetClientBaseGravityDirection(Entity, Direction)}
     * Used by ImmPtl.
     */
    public void forceApplyGravityChange() {
        prevGravityDirection = currGravityDirection;
        prevGravityStrength = currGravityStrength;
    }
    
    private static record GravityDirEffect(
        @NotNull Vec3 direction,
        @Nullable RotationParameters rotationParameters,
        double priority
    ) {
    
    }
}

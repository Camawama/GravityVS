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

public class GravityCoreBlockEntity extends BlockEntity
    implements net.cama.gravityapivs.util.GravityFieldLookup.Source {

    // rotateVelocity=false: world momentum is conserved on field entry (see
    // GravityPlatingBlockEntity.PLATING_ROTATION_PARAMS)
    private static final RotationParameters CORE_ROTATION_PARAMS = new RotationParameters(false, true, 300);
    // cores sit slightly below plating priority so plates win when both apply
    private static final double CORE_BASE_PRIORITY = 990;

    private int range;
    private boolean attracting = true;
    // glow-ink-sac toggle: render the field as radially flowing particles
    private boolean showParticles = false;
    // echo-shard toggle — falloff mode: FULL (false) pulls equally hard
    // everywhere in the sphere; GRADUAL (true) weakens the pull linearly with
    // distance from the core. Force only — orientation is never scaled.
    private boolean gradualFalloff = false;
    // gravity acceleration (blocks/tick^2) at full force; 0.08 = vanilla
    private double gravityAccel = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
    // whether entities in this field may planet-walk-snap onto surfaces
    private boolean surfaceSnap = true;

    // A client block entity that never received authoritative data (a break
    // prediction the server rejected rolls the block back with a FRESH BE)
    // must stay inert: resurrecting with config-default range and
    // attract=true created an invisible phantom field client-side.
    // Mirrors GravityPlatingBlockEntity.dataInitialized.
    private boolean dataInitialized = false;

    // entity-query staggering, same scheme as GravityPlatingBlockEntity
    private @Nullable List<Entity> cachedEntities = null;
    private long entitiesCacheExpiry = Long.MIN_VALUE;

    public GravityCoreBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_CORE_BLOCK_ENTITY.get(), pos, state);
        this.range = GravityConfig.gravityCoreDefaultRange.get();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        dataInitialized = true;
        if (tag.contains("range")) {
            range = Mth.clamp(tag.getInt("range"), 1, GravityConfig.gravityCoreMaxRange.get());
        }
        if (tag.contains("attracting")) {
            attracting = tag.getBoolean("attracting");
        }
        showParticles = tag.getBoolean("showParticles");
        gradualFalloff = tag.getBoolean("gradualFalloff");
        gravityAccel = tag.contains("gravityAccel")
            ? Mth.clamp(tag.getDouble("gravityAccel"), 0.0, 1.0)
            : GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
        surfaceSnap = !tag.contains("surfaceSnap") || tag.getBoolean("surfaceSnap");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("range", range);
        tag.putBoolean("attracting", attracting);
        tag.putBoolean("showParticles", showParticles);
        tag.putBoolean("gradualFalloff", gradualFalloff);
        tag.putDouble("gravityAccel", gravityAccel);
        tag.putBoolean("surfaceSnap", surfaceSnap);
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
        // client BEs apply fields/visuals only once server data has arrived
        if (world.isClientSide() && !be.dataInitialized) {
            return;
        }

        Ship ownShip = VSGameUtilsKt.getShipManagingPos(world, blockPos);

        // core center in world space (ship transform aware)
        Vec3 center = Vec3.atCenterOf(blockPos);
        if (ownShip != null) {
            Vector3d c = new Vector3d(center.x, center.y, center.z);
            ownShip.getTransform().getShipToWorldMatrix().transformPosition(c);
            center = new Vec3(c.x, c.y, c.z);
        }

        net.cama.gravityapivs.util.GravityFieldLookup.register(world, blockPos, be);

        double range = be.range;
        AABB searchBox = new AABB(
            center.x - range, center.y - range, center.z - range,
            center.x + range, center.y + range, center.z + range
        );

        be.applyToEntities(world, center, range, searchBox);

        if (world.isClientSide()) {
            if (be.showParticles) {
                // grid-local center: the renderer applies the ship's per-frame
                // render transform, so the visual sticks to moving ships
                net.cama.gravityapivs.util.FieldVisuals.submitCore(
                    world, blockPos, Vec3.atCenterOf(blockPos), range, be.attracting, ownShip
                );
            }
        }
        else if (GravityConfig.gravityCoreAffectsShips.get()) {
            be.applyToShips(world, ownShip, center, range, searchBox);
        }
    }

    private void applyToEntities(Level world, Vec3 center, double range, AABB searchBox) {
        List<Entity> entities = cachedEntities;
        long gameTime = world.getGameTime();
        if (entities == null || gameTime >= entitiesCacheExpiry) {
            entities = world.getEntitiesOfClass(
                Entity.class, searchBox, EntityTags::canChangeGravity
            );
            cachedEntities = entities;
            entitiesCacheExpiry = gameTime + (Math.floorMod(gameTime + worldPosition.hashCode(), 2L) == 0 ? 2 : 1);
        }

        double rangeSq = range * range;

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

            // GRADUAL falloff weakens the pull toward zero at the sphere's
            // edge; orientation (the direction effect itself) is untouched,
            // so a distant entity is still oriented by the core, just pulled
            // gently — and only resets once fully outside the field
            double strengthScale = gravityAccel / GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
            if (gradualFalloff) {
                strengthScale *= Mth.clamp(1.0 - distance / range, 0.0, 1.0);
            }

            comp.applyGravityDirectionEffect(
                direction, CORE_ROTATION_PARAMS, CORE_BASE_PRIORITY - distance, false, strengthScale,
                surfaceSnap
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
            if (gradualFalloff) {
                // ships obey the same falloff rule as entities
                acceleration *= Mth.clamp(1.0 - distance / range, 0.0, 1.0);
            }
            Vector3d force = toCenter.normalize(new Vector3d()).mul(mass * acceleration);
            if (!attracting) {
                force.negate();
            }

            GravityCoreForceInducer.getOrCreate(serverShip).queueForce(force);
        }
    }

    public InteractionResult interact(Player player, InteractionHand hand) {
        // NOTE: the empty-hand interaction opens the settings GUI instead (see
        // GravityCoreBlock.use); interact() only handles the item shortcuts
        ItemStack handItem = player.getItemInHand(hand);

        if (handItem.getItem() == Items.ECHO_SHARD) {
            // falloff mode selector: FULL <-> GRADUAL (force-only falloff)
            gradualFalloff = !gradualFalloff;
            sync();
            player.displayClientMessage(
                Component.translatable(gradualFalloff
                    ? "gravity_changer.falloff.gradual"
                    : "gravity_changer.falloff.full"),
                true
            );
            return InteractionResult.SUCCESS;
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

    // read access for the client settings screen (the client BE carries
    // authoritative data via the update tag)
    // ---- GravityFieldLookup.Source (fluid gravity queries) ----
    // SAME-GRID semantics: the radial direction is computed in the core's
    // own block grid (a ship core bends ship fluids in shipyard space)

    @Override
    public @Nullable net.minecraft.core.Direction fluidDownAt(net.minecraft.core.BlockPos pos) {
        Vec3 delta = Vec3.atCenterOf(pos).subtract(Vec3.atCenterOf(worldPosition));
        double distance = delta.length();
        if (distance > range || distance < 1.0) {
            return null;
        }
        // attract: down points toward the core; repulse: away from it
        Vec3 down = (attracting ? delta.scale(-1) : delta).normalize();

        // Deterministic ROTATIONAL tie-break: the integer lattice puts every
        // corner cell around the core at an exact 45-degree tie between two
        // cardinals, and Direction.getNearest breaks ties by enum order
        // (Y, then Z, then X) — a fixed axis bias. Around the equator ring
        // half the corners need the OTHER axis to forward water onto the
        // next face, so wrapping flow stalled after two faces. A small
        // tangential bias (down x worldY) resolves every equatorial tie in
        // one consistent rotational sense, letting liquid circulate around
        // all faces; it is far too small to affect non-tied cells, and
        // vertical (pole) ties keep the default downward preference.
        Vec3 tangent = new Vec3(-down.z, 0.0, down.x);
        if (tangent.lengthSqr() > 1.0E-6) {
            down = down.add(tangent.scale(0.05));
        }
        return net.minecraft.core.Direction.getNearest(down.x, down.y, down.z);
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
        return 1;
    }

    public int getRange() {
        return range;
    }

    public boolean isAttracting() {
        return attracting;
    }

    public boolean isGradualFalloff() {
        return gradualFalloff;
    }

    public double getGravityAccel() {
        return gravityAccel;
    }

    public boolean isSurfaceSnap() {
        return surfaceSnap;
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
        int newRange, boolean attracting, boolean gradualFalloff,
        double gravityAccel, boolean surfaceSnap, boolean showParticles
    ) {
        Level world = getLevel();
        if (world == null || world.isClientSide()) {
            return;
        }
        this.range = Mth.clamp(newRange, 1, GravityConfig.gravityCoreMaxRange.get());
        this.attracting = attracting;
        this.gradualFalloff = gradualFalloff;
        this.gravityAccel = Mth.clamp(gravityAccel, 0.0, 1.0);
        this.surfaceSnap = surfaceSnap;
        this.showParticles = showParticles;
        sync();
    }
}

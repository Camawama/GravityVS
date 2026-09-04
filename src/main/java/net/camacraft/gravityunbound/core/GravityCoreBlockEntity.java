package net.camacraft.gravityunbound.core;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ServerShip;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class GravityCoreBlockEntity extends BlockEntity
    implements net.camacraft.gravityunbound.util.GravityFieldLookup.Source {

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
    // whether this core pulls/pushes Valkyrien Skies ships (per-block toggle,
    // ANDed with the global gravityCoreAffectsShips config)
    private boolean affectsShips = true;
    // what the field acts on (players / mobs / objects / particles /
    // fluids), see util.FieldTargets
    private int targets = net.camacraft.gravityunbound.util.FieldTargets.ALL;
    // the world-space box this ship-mounted core's field covered at its
    // last world-fluid wake-up (see GravityPlatingBlockEntity)
    private @Nullable AABB lastShipWakeBox = null;
    private static final int SHIP_FLUID_WAKE_PERIOD = 8;

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
        affectsShips = !tag.contains("affectsShips") || tag.getBoolean("affectsShips");
        targets = tag.contains("targets")
            ? net.camacraft.gravityunbound.util.FieldTargets.sanitize(tag.getInt("targets"))
            : net.camacraft.gravityunbound.util.FieldTargets.ALL;
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
        tag.putBoolean("affectsShips", affectsShips);
        tag.putInt("targets", targets);
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

        net.camacraft.gravityunbound.util.GravityFieldLookup.register(world, blockPos, be);

        // SHIP SCALE: the range is authored in the core's OWN block grid —
        // a core on a scaled-down ship must project a proportionally
        // smaller world-space field (measured from the transform matrix,
        // like CapsuleCollider does; 1.0 for world cores and unscaled
        // ships). Plating/normalizer zones already respect scale because
        // their membership tests run through the full world->ship
        // matrices; the core computes in world space, so its world reach
        // and its ship-unit distances (falloff, priority) must be scaled
        // explicitly. Fluids are untouched (fluidDownAt works same-grid in
        // shipyard units).
        double shipScale = 1.0;
        if (ownShip != null) {
            Vector3d unit = new Vector3d(1.0, 0.0, 0.0);
            ownShip.getTransform().getShipToWorldMatrix().transformDirection(unit);
            double len = unit.length();
            if (len > 1.0E-9) {
                shipScale = len;
            }
        }

        double range = be.range * shipScale;
        AABB searchBox = new AABB(
            center.x - range, center.y - range, center.z - range,
            center.x + range, center.y + range, center.z + range
        );

        be.applyToEntities(world, center, range, shipScale, searchBox);

        if (world.isClientSide()) {
            if (be.showParticles) {
                // grid-local center AND grid-local range: the renderer
                // applies the ship's per-frame render transform (scale
                // included), so the visual sticks to — and scales with —
                // moving ships
                net.camacraft.gravityunbound.util.FieldVisuals.submitCore(
                    world, blockPos, Vec3.atCenterOf(blockPos), be.range, be.attracting, ownShip
                );
            }
        }
        else {
            if (GravityConfig.gravityCoreAffectsShips.get() && be.affectsShips) {
                be.applyToShips(world, ownShip, center, range, shipScale, searchBox);
            }
            if (ownShip != null) {
                be.wakeWorldFluidsUnderShip(world, ownShip);
            }
        }
    }

    /**
     * A ship-mounted core flying over WORLD liquid: wake the fluid blocks in
     * the region the field covers (and the region it just left) every few
     * ticks — still water has no scheduled ticks of its own. See the plating
     * counterpart; the cross-grid fluid lookup does the bending.
     */
    private void wakeWorldFluidsUnderShip(Level world, Ship ownShip) {
        if (!GravityConfig.gravityAffectsFluids.get() || !GravityConfig.shipFieldsAffectWorldFluids.get()
            || !net.camacraft.gravityunbound.util.FieldTargets.has(
                targets, net.camacraft.gravityunbound.util.GravityFieldLookup.Kind.FLUID)) {
            return;
        }
        long gameTime = world.getGameTime();
        if (Math.floorMod(gameTime + worldPosition.hashCode(), SHIP_FLUID_WAKE_PERIOD) != 0) {
            return;
        }
        Vec3 gridCenter = Vec3.atCenterOf(worldPosition);
        AABB gridBox = new AABB(
            gridCenter.x - range, gridCenter.y - range, gridCenter.z - range,
            gridCenter.x + range, gridCenter.y + range, gridCenter.z + range);
        Vector3d[] corners = {
            new Vector3d(gridBox.minX, gridBox.minY, gridBox.minZ), new Vector3d(gridBox.maxX, gridBox.minY, gridBox.minZ),
            new Vector3d(gridBox.minX, gridBox.maxY, gridBox.minZ), new Vector3d(gridBox.maxX, gridBox.maxY, gridBox.minZ),
            new Vector3d(gridBox.minX, gridBox.minY, gridBox.maxZ), new Vector3d(gridBox.maxX, gridBox.minY, gridBox.maxZ),
            new Vector3d(gridBox.minX, gridBox.maxY, gridBox.maxZ), new Vector3d(gridBox.maxX, gridBox.maxY, gridBox.maxZ)
        };
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vector3d corner : corners) {
            ownShip.getTransform().getShipToWorldMatrix().transformPosition(corner);
            minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
        }
        AABB worldBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        AABB sweep = lastShipWakeBox != null ? worldBox.minmax(lastShipWakeBox) : worldBox;
        lastShipWakeBox = worldBox;
        net.camacraft.gravityunbound.util.GravityFieldLookup.resettleFluids(world, sweep);
    }

    private void applyToEntities(Level world, Vec3 center, double range, double shipScale, AABB searchBox) {
        List<Entity> entities = cachedEntities;
        long gameTime = world.getGameTime();
        if (entities == null || gameTime >= entitiesCacheExpiry) {
            entities = GCUtil.safeFieldEntityQuery(
                world, searchBox, EntityTags::canChangeGravity, cachedEntities
            );
            cachedEntities = entities;
            entitiesCacheExpiry = gameTime + (Math.floorMod(gameTime + worldPosition.hashCode(), 2L) == 0 ? 2 : 1);
        }

        double rangeSq = range * range;

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
            // target categories (GUI): the field may leave players, mobs
            // or objects alone entirely
            if (!net.camacraft.gravityunbound.util.FieldTargets.appliesTo(targets, entity)) {
                continue;
            }

            Vec3 toCenter = center.subtract(entity.position());
            double distSq = toCenter.lengthSqr();
            if (distSq > rangeSq || distSq < 0.25 * shipScale * shipScale) {
                // outside the field, or standing inside the core block itself
                continue;
            }

            // world distance normalizes the direction; GRID distance (the
            // core's own block units) drives falloff and priority, so a
            // scaled ship's field keeps its authored shape at its own scale
            double worldDistance = Math.sqrt(distSq);
            double distance = worldDistance / shipScale;
            Vec3 direction = attracting
                ? toCenter.scale(1.0 / worldDistance)
                : toCenter.scale(-1.0 / worldDistance);

            // GROUNDED entities get the SECTOR-FRAME CARDINAL (the same
            // dominant-axis rule fluids use): standing on a face means
            // clean face-normal gravity, like flat ground. The raw radial
            // direction has a tangential component that drags everything
            // toward the face center — for mobs (which have no surface-
            // alignment machinery) and for players with surface snap off,
            // that made walking impossible ("constantly pulled to the
            // center of the face"). Airborne entities keep the continuous
            // radial pull: orbits stay smooth and falls curve around edges
            // onto the next face. Players with snap enabled keep radial
            // everywhere — their alignment machinery already resolves the
            // face normal and drives the planet-walk transitions.
            // MOBS get the sector cardinal while grounded (they cannot manage
            // the radial pull's tangential drag). PLAYERS never do: with snap
            // ON, alignment resolves the face; with snap OFF the user's spec
            // is NO snapping of any kind — pure smooth radial — and the
            // tilted-ground pins absorb the standing creep instead.
            boolean cardinalized = false;
            if (!(entity instanceof net.minecraft.world.entity.player.Player)
                && comp.isGroundedInFrame()) {
                direction = cardinalDirection(direction);
                cardinalized = true;
            }

            // GRADUAL falloff weakens the pull with the SQUARE of distance
            // (full strength within 4 blocks, inverse-square beyond) —
            // orbit-friendly: a linear ramp stayed strong enough at range
            // that flung entities were dragged straight down instead of
            // orbiting. Orientation is never scaled, only force.
            double strengthScale = gravityAccel / GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
            if (gradualFalloff) {
                // grid-unit distance: full strength within 4 of the CORE's
                // own blocks, whatever the ship's scale
                strengthScale *= Math.min(1.0, 16.0 / (distance * distance));
            }

            // Ship-mounted anchor: a RADIAL direction is position-dependent —
            // sampling it into ship space once per tick and holding it fed
            // the render alignment a 20 Hz staircase (the circling jitter).
            // The ship-space CONSTANT of this field is the core's position:
            // pass that, and the direction is re-derived live everywhere.
            // The cardinalized grounded-mob direction IS sector-constant, so
            // it keeps the rotation-only anchor.
            Vec3 shipLocalDir = null;
            Vec3 shipLocalPos = null;
            Ship anchorShip = VSGameUtilsKt.getShipManagingPos(world, worldPosition);
            if (anchorShip != null) {
                if (cardinalized) {
                    org.joml.Vector3d local = new org.joml.Quaterniond(
                        anchorShip.getTransform().getShipToWorldRotation()).conjugate()
                        .transform(new org.joml.Vector3d(direction.x, direction.y, direction.z));
                    shipLocalDir = new Vec3(local.x, local.y, local.z);
                }
                else {
                    shipLocalPos = Vec3.atCenterOf(worldPosition);
                }
            }
            // region for the held-surface sustain: the field's bounding cube
            // in the core's OWN grid (this.range is grid-authored; the
            // world-scaled reach is the method parameter)
            Vec3 gridCenter = Vec3.atCenterOf(worldPosition);
            AABB gridRegion = new AABB(
                gridCenter.x - this.range, gridCenter.y - this.range, gridCenter.z - this.range,
                gridCenter.x + this.range, gridCenter.y + this.range, gridCenter.z + this.range
            );
            // radial: the surface machinery must not read this field's
            // tangential component as endorsing stair risers and walls (a
            // cardinalized grounded mob's direction is a grid constant)
            comp.applyGravityDirectionEffect(
                direction, CORE_ROTATION_PARAMS, CORE_BASE_PRIORITY - distance, false, strengthScale,
                surfaceSnap, anchorShip, shipLocalDir, shipLocalPos, attracting ? 1.0 : -1.0,
                gridRegion, !cardinalized
            );

            // zero-g dimensions: items, carts and TNT need an explicit pull
            // (the living-entity deficit does not cover them); plating
            // applies the same force along its face direction
            GravityCapabilityImpl.applyZeroGravityFieldForce(world, entity, comp, direction);
        }
    }

    /**
     * Dominant-axis cardinal of a pull direction, ties resolved by the same
     * priority the fluid sector frames use (world-down > X > Z > world-up),
     * so grounded entities and fluids agree about which face a boundary
     * cell belongs to.
     */
    private static Vec3 cardinalDirection(Vec3 pull) {
        double ax = Math.abs(pull.x);
        double ay = Math.abs(pull.y);
        double az = Math.abs(pull.z);
        if (ay >= ax && ay >= az && pull.y < 0) {
            return new Vec3(0, -1, 0);
        }
        if (ax >= ay && ax >= az && ax > 0) {
            return new Vec3(Math.signum(pull.x), 0, 0);
        }
        if (az >= ay && az >= ax && az > 0) {
            return new Vec3(0, 0, Math.signum(pull.z));
        }
        return new Vec3(0, pull.y < 0 ? -1 : 1, 0);
    }

    private void applyToShips(Level world, @Nullable Ship ownShip, Vec3 center, double range, double shipScale, AABB searchBox) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }

        for (Ship ship : VSGameUtilsKt.getShipsIntersecting(serverLevel, searchBox)) {
            if (ownShip != null && ship.getId() == ownShip.getId()) {
                continue;
            }
            // The intersection query answers with ship DATA objects, which
            // never are the LOADED ship (the old instanceof test on them
            // filtered every ship out — this is why "affects ships" never
            // did anything). Forces need the loaded ship, looked up by id;
            // null while the ship is unloaded, and attachment access on an
            // unloaded ship would throw.
            org.valkyrienskies.core.api.ships.LoadedServerShip serverShip =
                VSGameUtilsKt.getShipObjectWorld(serverLevel).getLoadedShips().getById(ship.getId());
            if (serverShip == null) {
                continue;
            }

            Vector3d shipPos = new Vector3d(serverShip.getTransform().getPositionInWorld());
            Vector3d toCenter = new Vector3d(center.x, center.y, center.z).sub(shipPos);
            double worldDistance = toCenter.length();
            // range is already world-scaled; falloff runs in the core's own
            // grid units (see applyToEntities)
            if (worldDistance > range || worldDistance < 1.0 * shipScale) {
                continue;
            }
            double distance = worldDistance / shipScale;

            double mass = serverShip.getInertiaData().getMass();
            // ~1 g in m/s^2, scaled by the core's own acceleration setting
            double acceleration = 10.0 * GravityConfig.gravityCoreShipForceMultiplier.get()
                * (gravityAccel / GravityCapabilityImpl.BASE_GRAVITY_ACCEL);
            if (gradualFalloff) {
                // ships obey the same inverse-square falloff as entities
                acceleration *= Math.min(1.0, 16.0 / (distance * distance));
            }
            Vector3d force = toCenter.normalize(new Vector3d()).mul(mass * acceleration);
            if (!attracting) {
                force.negate();
            }

            GravityCoreForceInducer inducer = GravityCoreForceInducer.getOrCreate(serverShip);
            if (inducer == null) {
                return; // inducer unavailable (registration refused): leave ships alone
            }
            inducer.queueForce(force);
        }
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
    public @Nullable net.minecraft.core.Direction downAt(
        net.minecraft.core.BlockPos pos, net.camacraft.gravityunbound.util.GravityFieldLookup.Kind kind
    ) {
        if (!net.camacraft.gravityunbound.util.FieldTargets.has(targets, kind)) {
            return null;
        }
        int dx = pos.getX() - worldPosition.getX();
        int dy = pos.getY() - worldPosition.getY();
        int dz = pos.getZ() - worldPosition.getZ();
        double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        if (distance > range || distance < 1.0) {
            return null;
        }
        // SECTOR FRAMES: down is the dominant-axis cardinal toward the core
        // (attract) or away from it (repulse); lattice ties — every edge and
        // corner cell of a cube sits at an exact |x|==|y| style tie — are
        // resolved by the fixed priority world-DOWN > X > Z > world-UP.
        // This partitions the field into six face sectors whose boundaries
        // are all oriented ONE way (top feeds sides, sides feed bottom, Z
        // feeds X), so cross-sector fluid transfer forms an acyclic graph:
        // convex-edge cells become pour-over lips of the upstream face
        // (vanilla cliff rims), and no chain of feeds can loop back to its
        // origin. Earlier continuous tie-nudges (y-shrink + rotational
        // tangent) gave edge cells TANGENTIAL downs, which let "lateral"
        // spread climb another frame's up — the runaway-flood generator.
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);
        int dominant = Math.max(ax, Math.max(ay, az));
        if (ay == dominant && (attracting ? dy > 0 : dy < 0)) {
            return net.minecraft.core.Direction.DOWN;
        }
        if (ax == dominant && ax > 0) {
            return (attracting ? dx > 0 : dx < 0)
                ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST;
        }
        if (az == dominant && az > 0) {
            return (attracting ? dz > 0 : dz < 0)
                ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH;
        }
        return net.minecraft.core.Direction.UP;
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
        return 1;
    }

    @Override
    public boolean radialSkin() {
        // core fields render supported fluid as a flush planet skin
        return true;
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

    public boolean isAffectsShips() {
        return affectsShips;
    }

    public boolean isShowParticles() {
        return showParticles;
    }

    public int getTargets() {
        return targets;
    }

    /**
     * Server-side entry point for the settings GUI (arrives via
     * {@code network.UpdateGravityBlockSettingsPacket}). Every value is
     * clamped to its legal range — client input is never trusted.
     */
    public void applySettingsFromGui(
        int newRange, boolean attracting, boolean gradualFalloff,
        double gravityAccel, boolean surfaceSnap, boolean showParticles,
        boolean affectsShips
    ) {
        applySettingsFromGui(newRange, attracting, gradualFalloff, gravityAccel, surfaceSnap, showParticles,
            affectsShips, this.targets);
    }

    public void applySettingsFromGui(
        int newRange, boolean attracting, boolean gradualFalloff,
        double gravityAccel, boolean surfaceSnap, boolean showParticles,
        boolean affectsShips, int targets
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
        this.affectsShips = affectsShips;
        this.targets = net.camacraft.gravityunbound.util.FieldTargets.sanitize(targets);
        sync();
    }
}

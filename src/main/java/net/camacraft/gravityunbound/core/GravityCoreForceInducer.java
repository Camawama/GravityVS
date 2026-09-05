package net.camacraft.gravityunbound.core;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipForcesInducer;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.api.world.PhysLevel;

import com.fasterxml.jackson.annotation.JsonIgnore;

import net.camacraft.gravityunbound.config.GravityConfig;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Applies gravity-field forces (cores and plating) to a Valkyrien Skies ship.
 *
 * THE MODEL. A field sets a CLOSING ACCELERATION between the held ship and
 * the ship (or the world) carrying the field: the force is the field's
 * acceleration times the pair's REDUCED mass, applied equal and opposite to
 * both bodies. A planet-sized source barely moves and the held ship falls
 * at the full field strength; equal masses each take half; a one-block
 * core ship pulling a freighter simply falls onto the freighter at the
 * field strength. No body ever accelerates faster than the field, and the
 * pair's momentum is conserved — a field can never push its own ship
 * around by pulling something into its hull. World-mounted fields react
 * on nothing: the world is the immovable mass.
 *
 * DISSIPATION. A constant pull pressing blocks into a hull is a textbook
 * energy source for a rigid-body contact solver (penetration correction,
 * friction), and a conservative field never removes any of it — a cluster
 * of captured blocks around a core ship kept jittering until it spun the
 * core up and flung them off. A held pair therefore also gets pairwise
 * damping of its RELATIVE velocity and RELATIVE spin (config
 * {@code shipFieldDamping}, per second), equal and opposite on both, so
 * captures settle into resting contact with momentum still conserved.
 *
 * THREADING. The game thread only describes the fields that hold a ship
 * ({@link FieldSource}: which ship carries it, its geometry in that ship's
 * block grid, strength, falloff). The physics thread evaluates them every
 * physics tick from LIVE transforms and velocities — a force computed 50
 * ms earlier on the game thread and held constant was itself a second
 * energy source (a lagged central force is a negative damper). Sources are
 * handed over with a game-tick-stamped list that is only promoted once its
 * tick has passed, so the physics thread never sees a half-built list.
 *
 * GRAVITY. A field replaces the gravity acting on a ship it holds (the
 * way an entity in a field ignores world gravity): the game thread
 * determines that gravity ({@code util.ShipGravity}: the dimension's
 * vector as VS registered it, or a per-ship override such as VMod's) and
 * the physics thread cancels exactly that — unless the field is set to
 * BLEND with world gravity, for fields meant to add to it.
 *
 * PHYSICS CALLBACK. The core API that Valkyrien Skies 2.4.11 bundles (core
 * 1.1.0+cf208d8b56 — see build.gradle) dispatches attachments through
 * {@link ShipPhysicsListener#physTick(PhysShip, PhysLevel)}; its
 * {@link ShipForcesInducer} is an empty marker. Newer core builds dispatch
 * through {@code ShipForcesInducer.applyForces(PhysShip)} instead (without
 * a level, so without other-ship lookups: world-mounted fields only). Both
 * entry points exist here; {@code applyForces} carries no {@code @Override}
 * because the pinned interface declares nothing to override.
 */
// FINAL is mandatory: VS 2.4.11 refuses to register a non-final attachment
// class ("cannot be registered because it is not final"), and setAttachment
// throws for unregistered classes.
public final class GravityCoreForceInducer implements ShipForcesInducer, ShipPhysicsListener {

    /** Source id of a field mounted in the world rather than on a ship. */
    public static final long WORLD = -1L;

    /**
     * One field holding this ship, as the game thread described it. All
     * geometry is in the SOURCE's block grid (world coordinates for a
     * world-mounted source) and is transformed with the source ship's live
     * transform on the physics thread. Immutable; never mutate the vectors.
     */
    public record FieldSource(
        long sourceShipId,
        boolean radial,
        // radial (core): grid center, WORLD-scaled reach, grid->world scale
        Vector3dc center, double range, boolean attracting, double gridScale,
        // planar (plating): grid effect box, grid effect direction, falloff plane
        AABBdc box, Vector3dc direction, Vector3dc falloffOrigin, Vector3dc falloffNormal, double falloffRange,
        // closing acceleration (m/s^2) and how it fades
        double accel, boolean gradualFalloff,
        // whether the field replaces the dimension's gravity for the held ship
        boolean replacesGravity
    ) {
        public static FieldSource radial(
            long sourceShipId, Vector3dc gridCenter, double worldRange, double gridScale,
            double accel, boolean attracting, boolean gradualFalloff, boolean replacesGravity
        ) {
            return new FieldSource(sourceShipId, true, new Vector3d(gridCenter), worldRange, attracting, gridScale,
                null, null, null, null, 0.0, accel, gradualFalloff, replacesGravity);
        }

        public static FieldSource planar(
            long sourceShipId, AABBdc gridBox, Vector3dc gridDirection,
            Vector3dc falloffOrigin, Vector3dc falloffNormal, double falloffRange,
            double accel, boolean gradualFalloff, boolean replacesGravity
        ) {
            return new FieldSource(sourceShipId, false, null, 0.0, true, 1.0,
                new AABBd(gridBox), new Vector3d(gridDirection), new Vector3d(falloffOrigin),
                new Vector3d(falloffNormal), falloffRange, accel, gradualFalloff, replacesGravity);
        }
    }

    /** Physics ticks per game tick is small; after this many physics ticks
     *  without a fresh list the game thread has stopped refreshing (source
     *  removed / ship left every field) and the hold expires. */
    private static final int EXPIRY_PHYS_TICKS = 10;

    // in-progress list for the game tick stamped by buildingTick (guarded by `this`)
    @JsonIgnore
    private List<FieldSource> building = null;
    @JsonIgnore
    private int buildingTick = 0;
    // the gravity (m/s^2, world axes) acting on this ship as the game
    // thread determined it — dimension gravity, or another mod's per-ship
    // override — which a REPLACING field cancels
    @JsonIgnore
    private Vector3d buildingGravity = null;

    // last COMPLETE game-tick list, not yet latched by the physics thread (guarded by `this`)
    @JsonIgnore
    private List<FieldSource> completed = null;
    @JsonIgnore
    private Vector3d completedGravity = null;
    @JsonIgnore
    private boolean completedFresh = false;

    // physics-thread state: the physics runs several ticks per game tick, so
    // the last known list keeps applying until the game thread stops refreshing it
    @JsonIgnore
    private List<FieldSource> active = null;
    @JsonIgnore
    private Vector3d activeGravity = null;
    @JsonIgnore
    private int physTicksSinceUpdate = 0;

    /**
     * Only loaded ships may carry attachments — {@code ServerShip.getAttachment}
     * THROWS for unloaded ships, which crashed the server the moment a core's
     * range box swept up a distant ship. Nothing here needs persisting, so
     * the transient {@code setAttachment} is also the right call.
     */
    @Nullable
    public static GravityCoreForceInducer getOrCreate(LoadedServerShip ship) {
        GravityCoreForceInducer inducer = ship.getAttachment(GravityCoreForceInducer.class);
        if (inducer == null) {
            if (!registered) {
                // VS throws on setAttachment for an unregistered class —
                // never let a field tick crash the server over it
                if (!warnedUnregistered) {
                    warnedUnregistered = true;
                    LOGGER.warn("Gravity Unbound ship force inducer is not registered with Valkyrien Skies; "
                        + "fields will not pull ships this session");
                }
                return null;
            }
            inducer = new GravityCoreForceInducer();
            ship.setAttachment(GravityCoreForceInducer.class, inducer);
        }
        return inducer;
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static volatile boolean registered = false;
    private static volatile boolean warnedUnregistered = false;

    /**
     * Registers the attachment with Valkyrien Skies and attaches one idle
     * inducer to every server ship AS IT LOADS — the pattern VS's own
     * buoyancy handler (also a {@link ShipPhysicsListener}) uses. The
     * physics ship's listener list is built from the ship's attachments,
     * so being attached from the first physics frame never depends on a
     * late attachment propagating to the physics thread. An idle inducer
     * costs one null check per physics tick. Called once from common setup;
     * {@link #getOrCreate} stays as the fallback for ships that loaded
     * before registration (never, in practice).
     */
    public static void registerWithValkyrienSkies() {
        try {
            org.valkyrienskies.mod.api.VsApi api = org.valkyrienskies.mod.api.ValkyrienSkies.api();
            api.registerAttachment(GravityCoreForceInducer.class, builder -> {
                builder.key("gravityunbound:field_force");
                builder.useTransientSerializer();
                return kotlin.Unit.INSTANCE;
            });
            registered = true;
            api.getShipLoadEvent().on(
                (java.util.function.Consumer<org.valkyrienskies.core.api.events.ShipLoadEvent>)
                    event -> getOrCreate(event.getShip()));
        }
        catch (Throwable t) {
            // a VS build that rejects the registration: getOrCreate then
            // answers null and fields leave ships alone instead of crashing
            LOGGER.warn("Gravity Unbound could not register its ship force inducer with Valkyrien Skies; "
                + "fields will not pull ships", t);
        }
    }

    // ------------------------------------------------------------------
    // game thread
    // ------------------------------------------------------------------

    /**
     * Declares that {@code source} holds this ship during the current game
     * tick. A plated deck is many plates whose fields all hold the same
     * ship: only the FIRST planar source per carrying ship (or the world)
     * counts per tick, or a deck would multiply its gravity by its plate
     * count. Every radial source (core) counts — each core is one source.
     */
    public synchronized void offer(FieldSource source, Vector3dc gravityActingOnShip) {
        promoteIfTickPassed();
        int tick = currentGameTick();
        if (building == null || buildingTick != tick) {
            building = new ArrayList<>(4);
            buildingTick = tick;
        }
        buildingGravity = new Vector3d(gravityActingOnShip);
        if (!source.radial()) {
            for (FieldSource existing : building) {
                if (!existing.radial() && existing.sourceShipId() == source.sourceShipId()) {
                    return;
                }
            }
        }
        building.add(source);
    }

    /**
     * Moves {@code building} to {@code completed} once its game tick is over,
     * i.e. once every source has had its chance to declare itself. Must be
     * called with {@code this} held.
     */
    private void promoteIfTickPassed() {
        if (building != null && buildingTick != currentGameTick()) {
            completed = building;
            completedGravity = buildingGravity;
            completedFresh = true;
            building = null;
            buildingGravity = null;
        }
    }

    /**
     * The server's game tick counter, readable from both the game and physics
     * threads (a plain int read; at worst one tick stale on the physics thread,
     * which only delays a promotion by one physics tick).
     */
    private int currentGameTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.getTickCount() : buildingTick + 1;
    }

    // ------------------------------------------------------------------
    // physics thread
    // ------------------------------------------------------------------

    /** VS 2.4.11 physics-thread entry point (see the class comment). */
    @Override
    public void physTick(@NotNull PhysShip physShip, @NotNull PhysLevel physLevel) {
        applyForces(physShip, physLevel);
    }

    /** Newer-core physics-thread entry point (see the class comment). */
    public void applyForces(@NotNull PhysShip physShip) {
        applyForces(physShip, null);
    }

    private void applyForces(PhysShip self, @Nullable PhysLevel physLevel) {
        List<FieldSource> sources;
        Vector3d gravityActing;
        synchronized (this) {
            promoteIfTickPassed();
            if (completedFresh) {
                active = completed;
                activeGravity = completedGravity;
                completedFresh = false;
                physTicksSinceUpdate = 0;
            }
            else if (++physTicksSinceUpdate > EXPIRY_PHYS_TICKS) {
                // the game thread stopped refreshing (source removed / out of range)
                active = null;
                activeGravity = null;
            }
            sources = active;
            gravityActing = activeGravity;
        }
        if (sources == null || sources.isEmpty()) {
            return;
        }

        double selfMass = self.getMass();
        if (!(selfMass > 0.0)) {
            return;
        }
        Vector3dc selfPos = self.getTransform().getPositionInWorld();
        double selfScale = shipScale(self);

        boolean held = false;
        boolean replacesGravity = false;
        boolean heldByWorld = false;
        List<PhysShip> partners = new ArrayList<>(2);

        for (FieldSource field : sources) {
            PhysShip source = null;
            if (field.sourceShipId() != WORLD) {
                if (physLevel == null) {
                    continue;
                }
                source = physLevel.getShipById(field.sourceShipId());
                if (source == null || source.getId() == self.getId()) {
                    continue;
                }
            }
            Vector3d force = evaluate(field, selfPos, selfMass, selfScale, source);
            if (force == null) {
                continue;
            }
            self.applyInvariantForce(force);
            if (source != null) {
                // Newton's third law: the carrying ship gets the opposite pull
                source.applyInvariantForce(force.negate(new Vector3d()));
                if (!partners.contains(source)) {
                    partners.add(source);
                }
            }
            else {
                heldByWorld = true;
            }
            held = true;
            replacesGravity |= field.replacesGravity();
        }
        if (!held) {
            return;
        }

        double damping = GravityConfig.shipFieldDamping.get();
        if (damping > 0.0) {
            for (PhysShip partner : partners) {
                damp(self, selfMass, partner, damping);
            }
            if (heldByWorld) {
                damp(self, selfMass, null, damping);
            }
        }

        if (replacesGravity && gravityActing != null && gravityActing.isFinite()
            && gravityActing.lengthSquared() > 1.0E-12) {
            // A FIELD REPLACES GRAVITY: cancel exactly the gravity the game
            // thread saw acting on this ship (the dimension's vector as VS
            // registered it, or another mod's per-ship override such as
            // VMod's — cancelling the dimension's gravity on a ship VMod
            // had already zeroed left a net 1 g UPWARD).
            self.applyInvariantForce(gravityActing.mul(-selfMass, new Vector3d()));
        }
    }

    /**
     * The pull one field exerts on this ship right now: field acceleration
     * times the pair's reduced mass, along the field's direction evaluated
     * from the source's LIVE transform. Null when the ship's center of mass
     * is outside the field.
     */
    @Nullable
    private static Vector3d evaluate(
        FieldSource field, Vector3dc selfPos, double selfMass, double selfScale, @Nullable PhysShip source
    ) {
        ShipTransform sourceTransform = source != null ? source.getTransform() : null;
        double reducedMass = selfMass;
        if (source != null) {
            double sourceMass = source.getMass();
            if (!(sourceMass > 0.0)) {
                return null;
            }
            reducedMass = selfMass * sourceMass / (selfMass + sourceMass);
        }

        // A field's acceleration is authored for full-size ships. A SCALED
        // ship (Genesis space runs ships at 1/16) feels it at its own scale
        // — a 1/16 ship falls one of its own blocks in the time a full-size
        // ship falls one block — otherwise a mini ship is flung across the
        // room by what a full-size ship feels as 1 g.
        double accel = field.accel() * selfScale;
        Vector3d direction;
        if (field.radial()) {
            Vector3d center = new Vector3d(field.center());
            if (sourceTransform != null) {
                sourceTransform.getShipToWorld().transformPosition(center);
            }
            Vector3d toCenter = center.sub(selfPos);
            double distance = toCenter.length();
            // no inner dead zone: a one-block ship resting ON a one-block
            // core ship has its center exactly one block away
            if (distance > field.range() || distance < 1.0E-3) {
                return null;
            }
            if (field.gradualFalloff()) {
                // inverse-square in the source's own grid units, capped at
                // full strength (same law the entity pull follows)
                double gridDistance = distance / field.gridScale();
                accel *= Math.min(1.0, 16.0 / (gridDistance * gridDistance));
            }
            direction = toCenter.div(distance);
            if (!field.attracting()) {
                direction.negate();
            }
        }
        else {
            Vector3d local = new Vector3d(selfPos);
            if (sourceTransform != null) {
                sourceTransform.getWorldToShip().transformPosition(local);
            }
            if (!field.box().containsPoint(local)) {
                return null;
            }
            if (field.gradualFalloff()) {
                double distanceToPlane = -new Vector3d(local).sub(field.falloffOrigin()).dot(field.falloffNormal());
                accel *= Math.max(0.0, Math.min(1.0, 1.0 - Math.max(0.0, distanceToPlane) / field.falloffRange()));
            }
            direction = new Vector3d(field.direction());
            if (sourceTransform != null) {
                sourceTransform.getShipToWorld().transformDirection(direction);
            }
            if (direction.lengthSquared() < 1.0E-12) {
                return null;
            }
            direction.normalize();
        }
        if (!(accel > 0.0)) {
            return null;
        }
        Vector3d force = direction.mul(accel * reducedMass);
        return force.isFinite() ? force : null;
    }

    /**
     * Pairwise damping of the relative velocity and relative spin between
     * this ship and {@code partner} (null: the world frame), scaled by the
     * pair's reduced mass / reduced inertia so the RELATIVE motion decays
     * at {@code rate} per second whatever the masses, and applied equal
     * and opposite so momentum and angular momentum are conserved.
     */
    private static void damp(PhysShip self, double selfMass, @Nullable PhysShip partner, double rate) {
        double reducedMass = selfMass;
        Vector3d relativeVelocity = new Vector3d(self.getVelocity());
        Vector3d relativeSpin = new Vector3d(self.getAngularVelocity());
        if (partner != null) {
            double partnerMass = partner.getMass();
            if (!(partnerMass > 0.0)) {
                return;
            }
            reducedMass = selfMass * partnerMass / (selfMass + partnerMass);
            relativeVelocity.sub(partner.getVelocity());
            relativeSpin.sub(partner.getAngularVelocity());
        }

        if (relativeVelocity.isFinite() && relativeVelocity.lengthSquared() > 1.0E-8) {
            Vector3d force = relativeVelocity.mul(-rate * reducedMass);
            self.applyInvariantForce(force);
            if (partner != null) {
                partner.applyInvariantForce(force.negate(new Vector3d()));
            }
        }

        double spinSq = relativeSpin.lengthSquared();
        if (relativeSpin.isFinite() && spinSq > 1.0E-8) {
            Vector3d axis = new Vector3d(relativeSpin).div(Math.sqrt(spinSq));
            double selfInertia = inertiaAbout(self.getMomentOfInertia(), axis);
            double reducedInertia = selfInertia;
            if (partner != null) {
                double partnerInertia = inertiaAbout(partner.getMomentOfInertia(), axis);
                if (selfInertia + partnerInertia > 0.0) {
                    reducedInertia = selfInertia * partnerInertia / (selfInertia + partnerInertia);
                }
            }
            if (reducedInertia > 0.0) {
                Vector3d torque = relativeSpin.mul(-rate * reducedInertia);
                self.applyInvariantTorque(torque);
                if (partner != null) {
                    partner.applyInvariantTorque(torque.negate(new Vector3d()));
                }
            }
        }
    }

    /** The ship's uniform world scale (1 for ordinary ships), from its live transform. */
    private static double shipScale(PhysShip ship) {
        try {
            Vector3dc scaling = ship.getTransform().getShipToWorldScaling();
            double scale = Math.cbrt(Math.abs(scaling.x() * scaling.y() * scaling.z()));
            if (!Double.isFinite(scale) || scale < 1.0E-3) {
                return 1.0;
            }
            return Math.min(scale, 1.0E3);
        }
        catch (RuntimeException e) {
            return 1.0;
        }
    }

    /** Scalar moment of inertia about a unit axis: axis^T I axis. */
    private static double inertiaAbout(Matrix3dc inertia, Vector3dc axis) {
        Vector3d transformed = inertia.transform(axis, new Vector3d());
        return Math.max(0.0, transformed.dot(axis));
    }
}

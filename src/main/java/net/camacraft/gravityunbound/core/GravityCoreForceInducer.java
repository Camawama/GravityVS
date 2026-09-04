package net.camacraft.gravityunbound.core;

import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipForcesInducer;
import org.valkyrienskies.core.api.ships.ShipPhysicsListener;
import org.valkyrienskies.core.api.world.PhysLevel;

import com.fasterxml.jackson.annotation.JsonIgnore;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Applies gravity-field forces (cores and plating) to a ship.
 *
 * Forces are computed on the game thread ({@link GravityCoreBlockEntity},
 * {@code GravityPlatingBlockEntity}) once per game tick and consumed here on
 * the physics thread every physics tick (Eureka-style control pattern).
 * Nothing is persisted.
 *
 * PHYSICS CALLBACK: the core API that Valkyrien Skies 2.4.11 bundles
 * (core 1.1.0+cf208d8b56 — see build.gradle) dispatches attachments through
 * {@link ShipPhysicsListener#physTick(PhysShip, PhysLevel)}; its
 * {@link ShipForcesInducer} is an empty marker. Newer core builds dispatch
 * through {@code ShipForcesInducer.applyForces(PhysShip)} instead. Both
 * entry points exist here with the same body so the same class serves
 * either pipeline; {@code applyForces} carries no {@code @Override} because
 * the pinned interface declares nothing to override.
 *
 * Torn-sum safety: several cores may queue forces for the same ship within one
 * game tick, and a physics tick can land between those queue calls. The queued
 * forces therefore accumulate into a game-tick-stamped {@code building} sum
 * that is only PROMOTED to the applied force once its game tick has passed —
 * the physics thread never applies a half-accumulated same-tick sum, it keeps
 * applying the previous complete sum instead.
 */
// FINAL is mandatory: VS 2.4.11 refuses to register a non-final attachment
// class ("cannot be registered because it is not final"), and setAttachment
// throws for unregistered classes.
public final class GravityCoreForceInducer implements ShipForcesInducer, ShipPhysicsListener {

    /** Physics ticks the physics runs per game tick is small; after this many
     *  physics ticks without a new complete sum the game thread has stopped
     *  refreshing (core removed / out of range) and the force expires. */
    private static final int EXPIRY_PHYS_TICKS = 10;

    // in-progress sum for the game tick stamped by buildingTick (guarded by `this`)
    @JsonIgnore
    private Vector3d building = null;
    @JsonIgnore
    private int buildingTick = 0;

    // last COMPLETE game-tick sum, not yet latched by the physics thread (guarded by `this`)
    @JsonIgnore
    private Vector3d completed = null;
    @JsonIgnore
    private boolean completedFresh = false;

    // physics-thread state: the physics runs several ticks per game tick, so the
    // last known force keeps applying until the game thread stops refreshing it
    @JsonIgnore
    private Vector3d activeForce = null;
    @JsonIgnore
    private int physTicksSinceUpdate = 0;

    /**
     * Only loaded ships may carry attachments — {@code ServerShip.getAttachment}
     * THROWS for unloaded ships (VS 2.5-era core), which crashed the server the
     * moment a core's range box swept up a distant ship. Nothing here needs
     * persisting, so the transient {@code setAttachment} is also the right call.
     */
    @org.jetbrains.annotations.Nullable
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

    /**
     * Accumulates a force (newtons) for this game tick; multiple cores stack.
     * Called on the game (server) thread.
     */
    public synchronized void queueForce(Vector3d force) {
        promoteIfTickPassed();
        if (building == null) {
            building = new Vector3d(force);
            buildingTick = currentGameTick();
        } else {
            building.add(force);
        }
    }

    // game tick of the last once-per-tick claim (plating fields: a plated
    // floor is many plates whose fields all hold the same ship)
    @JsonIgnore
    private long onceTick = Long.MIN_VALUE;

    /**
     * Queues a force at most ONCE per game tick across every caller that
     * uses this entry point — gravity plating: every plate of a plated deck
     * holds the same ship in its field, and stacking one pull per plate
     * would multiply the gravity by the deck's plate count. Cores keep
     * {@link #queueForce} (each core is one source).
     */
    public synchronized boolean queueForceOnce(Vector3d force, long gameTick) {
        if (onceTick == gameTick) {
            return false;
        }
        onceTick = gameTick;
        queueForce(force);
        return true;
    }

    /** VS 2.4.11 physics-thread entry point (see the class comment). */
    @Override
    public void physTick(@NotNull PhysShip physShip, @NotNull PhysLevel physLevel) {
        applyForces(physShip);
    }

    /** Newer-core physics-thread entry point (see the class comment). */
    public void applyForces(@NotNull PhysShip physShip) {
        Vector3d force;
        synchronized (this) {
            promoteIfTickPassed();
            if (completedFresh) {
                activeForce = completed;
                completedFresh = false;
                physTicksSinceUpdate = 0;
            } else if (++physTicksSinceUpdate > EXPIRY_PHYS_TICKS) {
                // the game thread stopped refreshing (core removed / out of range)
                activeForce = null;
            }
            force = activeForce;
        }

        if (force != null && force.isFinite()) {
            physShip.applyInvariantForce(force);
        }
    }

    /**
     * Moves {@code building} to {@code completed} once its game tick is over,
     * i.e. once every core has had its chance to stack into the sum. Must be
     * called with {@code this} held.
     */
    private void promoteIfTickPassed() {
        if (building != null && buildingTick != currentGameTick()) {
            completed = building;
            completedFresh = true;
            building = null;
        }
    }

    /**
     * The server's game tick counter, readable from both the game and physics
     * threads (a plain int read; at worst one tick stale on the physics thread,
     * which only delays promotion by a physics tick). Falls back to a value
     * that promotes immediately if the server is somehow unavailable.
     */
    private int currentGameTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.getTickCount() : buildingTick + 1;
    }
}

package net.cama.gravityapivs.core;

import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ShipForcesInducer;

import com.fasterxml.jackson.annotation.JsonIgnore;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Applies gravity-core forces to a ship.
 *
 * Forces are computed on the game thread ({@link GravityCoreBlockEntity}) once
 * per game tick and consumed here on the physics thread every physics tick
 * (Eureka-style control pattern). Nothing is persisted.
 *
 * Torn-sum safety: several cores may queue forces for the same ship within one
 * game tick, and a physics tick can land between those queue calls. The queued
 * forces therefore accumulate into a game-tick-stamped {@code building} sum
 * that is only PROMOTED to the applied force once its game tick has passed —
 * the physics thread never applies a half-accumulated same-tick sum, it keeps
 * applying the previous complete sum instead.
 */
public class GravityCoreForceInducer implements ShipForcesInducer {

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
    public static GravityCoreForceInducer getOrCreate(LoadedServerShip ship) {
        GravityCoreForceInducer inducer = ship.getAttachment(GravityCoreForceInducer.class);
        if (inducer == null) {
            inducer = new GravityCoreForceInducer();
            ship.setAttachment(GravityCoreForceInducer.class, inducer);
        }
        return inducer;
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

    @Override
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

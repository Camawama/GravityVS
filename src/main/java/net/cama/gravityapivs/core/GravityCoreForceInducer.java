package net.cama.gravityapivs.core;

import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.ShipForcesInducer;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Applies gravity-core forces to a ship.
 *
 * Forces are computed on the game thread ({@link GravityCoreBlockEntity}) once
 * per game tick and consumed here on the physics thread every physics tick
 * (Eureka-style control pattern). Nothing is persisted.
 */
public class GravityCoreForceInducer implements ShipForcesInducer {

    @JsonIgnore
    private final AtomicReference<Vector3d> pendingForce = new AtomicReference<>(null);

    // physics-thread state: the physics runs several ticks per game tick, so the
    // last known force keeps applying until the game thread stops refreshing it
    @JsonIgnore
    private Vector3d activeForce = null;
    @JsonIgnore
    private int physTicksSinceUpdate = 0;

    public static GravityCoreForceInducer getOrCreate(ServerShip ship) {
        GravityCoreForceInducer inducer = ship.getAttachment(GravityCoreForceInducer.class);
        if (inducer == null) {
            inducer = new GravityCoreForceInducer();
            ship.saveAttachment(GravityCoreForceInducer.class, inducer);
        }
        return inducer;
    }

    /**
     * Accumulates a force (newtons) for this game tick; multiple cores stack.
     */
    public void queueForce(Vector3d force) {
        pendingForce.updateAndGet(existing -> {
            if (existing == null) {
                return new Vector3d(force);
            }
            return existing.add(force, new Vector3d());
        });
    }

    @Override
    public void applyForces(@NotNull PhysShip physShip) {
        Vector3d fresh = pendingForce.getAndSet(null);
        if (fresh != null) {
            activeForce = fresh;
            physTicksSinceUpdate = 0;
        }
        else if (++physTicksSinceUpdate > 10) {
            // the game thread stopped refreshing (core removed / out of range)
            activeForce = null;
        }

        if (activeForce != null && activeForce.isFinite()) {
            physShip.applyInvariantForce(activeForce);
        }
    }
}

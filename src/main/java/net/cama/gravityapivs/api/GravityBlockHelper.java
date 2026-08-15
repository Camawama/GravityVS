package net.cama.gravityapivs.api;

import net.cama.gravityapivs.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Entry point of the Gravity Block Framework: helpers for block placement and
 * block orientation code that wants to respect an entity's gravity frame.
 *
 * The framework's goal (see docs/GravityBlockFramework_Design.md) is
 * grid-aligned but rotation-unlocked blocks: blocks always snap to the block
 * grid, but within the grid any of the 24 orientations is legal — so players
 * building under rotated gravity can place blocks that match their local
 * "down". These helpers answer the two questions every such placement needs:
 * which world direction is the player's local X/Y/Z axis, and vice versa.
 *
 * All directions here are cardinal (grid) directions — the framework is
 * explicitly NOT about arbitrary off-grid angles.
 */
public final class GravityBlockHelper {

    /**
     * The world-space grid direction of the entity's LOCAL direction
     * (e.g. {@code localToWorld(Direction.DOWN, player)} = the grid direction
     * the player perceives as down). Uses the snapped physics cardinal, which
     * is exactly what grid-aligned placement wants.
     */
    public static Direction localToWorld(Direction local, Entity entity) {
        return RotationUtil.dirPlayerToWorld(local, GravityChangerAPI.getGravityDirection(entity));
    }

    /**
     * The entity-local grid direction of a WORLD direction (inverse of
     * {@link #localToWorld}).
     */
    public static Direction worldToLocal(Direction world, Entity entity) {
        return RotationUtil.dirWorldToPlayer(world, GravityChangerAPI.getGravityDirection(entity));
    }

    /**
     * The grid direction a placed block should treat as "down" for this
     * entity — i.e. the direction gravity pulls them, snapped to the grid.
     */
    public static Direction placementDown(Entity entity) {
        return GravityChangerAPI.getGravityDirection(entity);
    }

    /**
     * The grid direction the entity is facing horizontally IN ITS OWN frame,
     * expressed in world space — the correct "horizontal facing" for
     * placement logic under rotated gravity (vanilla's
     * {@code getHorizontalDirection} assumes world-down gravity).
     */
    public static Direction placementHorizontalFacing(Entity entity) {
        Vec3 lookLocal = RotationUtil.vecWorldToPlayer(
            entity.getLookAngle(), GravityChangerAPI.getAimRotation(entity)
        );
        // flatten in the LOCAL frame, then pick the nearest local horizontal
        Direction local = Direction.getNearest(lookLocal.x, 0, lookLocal.z);
        return localToWorld(local, entity);
    }

    private GravityBlockHelper() {}
}

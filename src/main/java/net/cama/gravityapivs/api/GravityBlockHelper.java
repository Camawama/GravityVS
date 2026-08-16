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

    // ------------------------------------------------------------------
    // grid-aware placement (Valkyrien Skies ships)
    //
    // Blockstates live in the BLOCK GRID of their level region — for blocks
    // on a VS ship that grid is the SHIPYARD frame, which can be arbitrarily
    // rotated against the world. Placement orientation must therefore be
    // computed as world-space VECTORS and re-expressed in the target grid
    // BEFORE snapping to cardinals; snapping in world space first picks the
    // wrong axes on any tilted ship.
    // ------------------------------------------------------------------

    /**
     * The world-space DOWN direction of the entity's smooth gravity frame,
     * as a vector (not snapped to a cardinal).
     */
    public static Vec3 gravityDownVector(Entity entity) {
        return RotationUtil.vecPlayerToWorld(
            new Vec3(0, -1, 0), GravityChangerAPI.getAimRotation(entity)
        );
    }

    /**
     * The entity's horizontal facing (flattened in its OWN gravity frame),
     * as a world-space vector.
     */
    public static Vec3 placementFacingVector(Entity entity) {
        Vec3 lookLocal = RotationUtil.vecWorldToPlayer(
            entity.getLookAngle(), GravityChangerAPI.getAimRotation(entity)
        );
        Vec3 flatLocal = new Vec3(lookLocal.x, 0, lookLocal.z);
        if (flatLocal.lengthSqr() < 1.0E-6) {
            flatLocal = new Vec3(0, 0, 1);
        }
        return RotationUtil.vecPlayerToWorld(flatLocal.normalize(), GravityChangerAPI.getAimRotation(entity));
    }

    /**
     * Re-expresses a world-space direction vector in the BLOCK GRID that
     * contains {@code pos}: shipyard space when the position is managed by a
     * Valkyrien Skies ship, unchanged otherwise.
     */
    public static Vec3 worldDirectionToGrid(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, Vec3 worldDir) {
        org.valkyrienskies.core.api.ships.Ship ship =
            org.valkyrienskies.mod.common.VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) {
            return worldDir;
        }
        org.joml.Vector3d v = new org.joml.Vector3d(worldDir.x, worldDir.y, worldDir.z);
        ship.getTransform().getWorldToShipMatrix().transformDirection(v);
        v.normalize();
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * The GRID direction a block placed at {@code pos} should treat as
     * "down" for this entity — the entity's gravity down re-expressed in the
     * target grid (ship-aware), snapped to the nearest grid cardinal.
     */
    public static Direction placementDown(Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        Vec3 down = worldDirectionToGrid(level, pos, gravityDownVector(entity));
        return Direction.getNearest(down.x, down.y, down.z);
    }

    /**
     * Re-expresses a world-space POSITION in the block grid that contains
     * {@code pos}: shipyard coordinates when the position is managed by a
     * Valkyrien Skies ship, unchanged otherwise.
     *
     * PLACEMENT-FLOW WARNING: inside {@code BlockItem.place} /
     * {@code getStateForPlacement}, Valkyrien Skies' block_placement mixins
     * have TEMPORARILY transformed the placing player for ship placements —
     * their position is already shipyard coordinates and their rotation
     * fields already ship-grid look angles ({@code PlayerUtil
     * .transformPlayerTemporarily}). Do NOT run the placer's position or a
     * look-derived vector through this transform there — it double-rotates.
     * See {@code Rotation24.fromPlacement} for the correct pattern.
     */
    public static Vec3 worldPositionToGrid(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, Vec3 worldPos) {
        org.valkyrienskies.core.api.ships.Ship ship =
            org.valkyrienskies.mod.common.VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) {
            return worldPos;
        }
        org.joml.Vector3d v = new org.joml.Vector3d(worldPos.x, worldPos.y, worldPos.z);
        ship.getTransform().getWorldToShipMatrix().transformPosition(v);
        return new Vec3(v.x, v.y, v.z);
    }

    private GravityBlockHelper() {}
}

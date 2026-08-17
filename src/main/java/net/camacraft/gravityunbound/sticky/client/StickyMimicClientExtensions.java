package net.camacraft.gravityunbound.sticky.client;

import net.camacraft.gravityunbound.sticky.StickyMimicBlockEntity;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;

/**
 * Client extensions for the {@code StickyMimicBlock}: destroy and hit
 * particles use the CAPTURED state's particle texture over the ROTATED
 * silhouette. Without this, vanilla would spawn missing-texture particles for
 * the model-less shell on break, and no crack particles at all while digging
 * (vanilla {@code ParticleEngine.crack} skips {@code RenderShape.INVISIBLE}
 * blocks). Both methods mirror the corresponding vanilla
 * {@code ParticleEngine} logic ({@code destroy} / {@code crack}), swapping in
 * the mimicked state and the shell's own (already rotated) outline shape.
 *
 * Always returns true — when the capture is missing, spawning nothing beats
 * vanilla's missing-texture particles for an invisible shell.
 */
public final class StickyMimicClientExtensions implements IClientBlockExtensions {

    /** Mirror of {@code ParticleEngine.destroy}, using the captured state. */
    @Override
    public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine manager) {
        if (!(level instanceof ClientLevel clientLevel)
            || !(level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic)) {
            return true;
        }
        BlockState mimicked = mimic.getMimickedState();
        if (mimicked == null || mimicked.isAir()) {
            return true;
        }
        // the shell's outline shape IS the mimicked shape, rotated
        VoxelShape shape = state.getShape(level, pos);
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double sizeX = Math.min(1.0, maxX - minX);
            double sizeY = Math.min(1.0, maxY - minY);
            double sizeZ = Math.min(1.0, maxZ - minZ);
            int countX = Math.max(2, Mth.ceil(sizeX / 0.25));
            int countY = Math.max(2, Mth.ceil(sizeY / 0.25));
            int countZ = Math.max(2, Mth.ceil(sizeZ / 0.25));
            for (int ix = 0; ix < countX; ++ix) {
                for (int iy = 0; iy < countY; ++iy) {
                    for (int iz = 0; iz < countZ; ++iz) {
                        double fx = (ix + 0.5) / countX;
                        double fy = (iy + 0.5) / countY;
                        double fz = (iz + 0.5) / countZ;
                        double x = fx * sizeX + minX;
                        double y = fy * sizeY + minY;
                        double z = fz * sizeZ + minZ;
                        manager.add(new TerrainParticle(
                            clientLevel,
                            pos.getX() + x, pos.getY() + y, pos.getZ() + z,
                            fx - 0.5, fy - 0.5, fz - 0.5,
                            mimicked, pos
                        ).updateSprite(mimicked, pos));
                    }
                }
            }
        });
        return true;
    }

    /** Mirror of {@code ParticleEngine.crack}, using the captured state. */
    @Override
    public boolean addHitEffects(BlockState state, Level level, HitResult target, ParticleEngine manager) {
        if (!(target instanceof BlockHitResult hit) || !(level instanceof ClientLevel clientLevel)) {
            return true;
        }
        BlockPos pos = hit.getBlockPos();
        if (!(level.getBlockEntity(pos) instanceof StickyMimicBlockEntity mimic)) {
            return true;
        }
        BlockState mimicked = mimic.getMimickedState();
        if (mimicked == null || mimicked.isAir()) {
            return true;
        }
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            return true;
        }
        Direction side = hit.getDirection();
        AABB aabb = shape.bounds();
        double x = pos.getX() + level.getRandom().nextDouble() * (aabb.maxX - aabb.minX - 0.2) + 0.1 + aabb.minX;
        double y = pos.getY() + level.getRandom().nextDouble() * (aabb.maxY - aabb.minY - 0.2) + 0.1 + aabb.minY;
        double z = pos.getZ() + level.getRandom().nextDouble() * (aabb.maxZ - aabb.minZ - 0.2) + 0.1 + aabb.minZ;
        switch (side) {
            case DOWN -> y = pos.getY() + aabb.minY - 0.1;
            case UP -> y = pos.getY() + aabb.maxY + 0.1;
            case NORTH -> z = pos.getZ() + aabb.minZ - 0.1;
            case SOUTH -> z = pos.getZ() + aabb.maxZ + 0.1;
            case WEST -> x = pos.getX() + aabb.minX - 0.1;
            case EAST -> x = pos.getX() + aabb.maxX + 0.1;
        }
        manager.add(new TerrainParticle(clientLevel, x, y, z, 0.0, 0.0, 0.0, mimicked, pos)
            .updateSprite(mimicked, pos)
            .setPower(0.2f)
            .scale(0.6f));
        return true;
    }
}

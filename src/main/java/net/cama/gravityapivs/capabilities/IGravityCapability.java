package net.cama.gravityapivs.capabilities;

import net.cama.gravityapivs.GravityAPI;

import org.joml.Quaternionf;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.util.INBTSerializable;

@AutoRegisterCapability
public interface IGravityCapability extends INBTSerializable<CompoundTag>
{
	ResourceLocation ID = new ResourceLocation(GravityAPI.MODID, "gravity");

	void setEntity(Entity entity);

	void tick();

	void applyGravityChange();

	void sync(boolean noAnimation, Vec3 baseGravityDirection, Vec3 currentGravityDirection, double baseGravityStrength, double currentGravityStrength, Quaternionf rotation);
}

package net.camacraft.gravityunbound.capabilities;

import javax.annotation.Nonnull;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

public class GravityCapabilities
{
	public static final Capability<IGravityCapability> GRAVITY = CapabilityManager.get(new CapabilityToken<>() {});
	
	public static void attachEntityCapability(AttachCapabilitiesEvent<Entity> e)
	{
		GravityCapabilityImpl impl = new GravityCapabilityImpl();
		impl.setEntity(e.getObject());
		LazyOptional<IGravityCapability> inst = LazyOptional.of(() -> impl);
		// invalidate the LazyOptional when the entity's capabilities are invalidated
		e.addListener(inst::invalidate);
		e.addCapability(IGravityCapability.ID, new ICapabilitySerializable<CompoundTag>()
		{

			@Nonnull
			@Override
			public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing)
			{
				return GRAVITY.orEmpty(capability, inst.cast());
			}

			// NBT (de)serialization goes through the impl directly, not the LazyOptional:
			// Forge still saves entity NBT after caps are invalidated (e.g. dead players
			// during autosave), and the invalidated optional would throw.
			@Override
			public CompoundTag serializeNBT()
			{
				return impl.serializeNBT();
			}

			@Override
			public void deserializeNBT(CompoundTag nbt)
			{
				impl.deserializeNBT(nbt);
			}
		});
	}
}

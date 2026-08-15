package net.cama.gravityapivs.api;

import net.cama.gravityapivs.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

public class GravityBlockPos extends BlockPos
{
	private final Direction direction;

	public GravityBlockPos(Vec3i vec3i, Direction direction)
	{
		super(vec3i);
		this.direction = direction;
	}

	public GravityBlockPos(int x, int y, int z, Direction direction)
	{
		super(x, y, z);
		this.direction = direction;
	}

	@Override
	public BlockPos relative(Direction p_121946_, int p_121949_)
	{
	    return this.gravityRelative(RotationUtil.dirPlayerToWorld(p_121946_, this.direction), p_121949_);
	}

	@Override
	public BlockPos relative(Direction p_121946_)
	{
	    return this.gravityRelative(RotationUtil.dirPlayerToWorld(p_121946_, this.direction));
	}

	public BlockPos gravityRelative(Direction p_121946_)
	{
		return new GravityBlockPos(this.getX() + p_121946_.getStepX(), this.getY() + p_121946_.getStepY(), this.getZ() + p_121946_.getStepZ(), this.direction);
	}

	public BlockPos gravityRelative(Direction p_121948_, int p_121949_)
	{
		return p_121949_ == 0 ? this : new GravityBlockPos(this.getX() + p_121948_.getStepX() * p_121949_, this.getY() + p_121948_.getStepY() * p_121949_, this.getZ() + p_121948_.getStepZ() * p_121949_, this.direction);
	}
}

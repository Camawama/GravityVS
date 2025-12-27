package net.cama.gravityapivs.config;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

public class GravityConfig 
{
	public static final GravityConfig CONFIG;
	public static final ForgeConfigSpec CONFIG_SPEC;
	
    public static ConfigValue<Integer> rotationTime;
    public static ForgeConfigSpec.BooleanValue worldVelocity;
    
    public static ForgeConfigSpec.DoubleValue gravityStrengthMultiplier;

	public static ForgeConfigSpec.BooleanValue resetGravityOnRespawn;
	public static ForgeConfigSpec.BooleanValue voidDamageAboveWorld;
	public static ForgeConfigSpec.BooleanValue voidDamageOnHorizontalFallTooFar;
	public static ForgeConfigSpec.BooleanValue autoJumpOnGravityPlateInnerCorner;
	public static ForgeConfigSpec.BooleanValue adjustPositionAfterChangingGravity;
    
    static 
    {
    	Pair<GravityConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(GravityConfig::new);
    	CONFIG = pair.getLeft();
    	CONFIG_SPEC = pair.getRight();
    }
	
    public GravityConfig(ForgeConfigSpec.Builder config) 
    {
    	config.push("Gravity Settings");
    	GravityConfig.rotationTime = config.comment("animation rotation time").defineInRange("rotationTime", 500, 0, Integer.MAX_VALUE);
    	GravityConfig.gravityStrengthMultiplier = config.comment("gravity strength multiplier").defineInRange("gravityStrengthMultiplier", 1.0F, 0.0F, Float.MAX_VALUE);
    	GravityConfig.worldVelocity = config.comment("world velocity").define("worldVelocity", false);
    	GravityConfig.resetGravityOnRespawn = config.comment("reset gravity on respawn").define("resetGravityOnRespawn", true);
    	GravityConfig.voidDamageAboveWorld = config.comment("void damage when above world").define("voidDamageAboveWorld", true);
    	GravityConfig.voidDamageOnHorizontalFallTooFar = config.comment("void damage when horizontally fall too far").define("voidDamageOnHorizontalFallTooFar", true);
    	GravityConfig.autoJumpOnGravityPlateInnerCorner = config.comment("auto jump on gravity plate inner corner").define("autoJumpOnGravityPlateInnerCorner", true);
    	GravityConfig.adjustPositionAfterChangingGravity = config.comment("adjust position after gravity change").define("adjustPositionAfterChangingGravity", true);
        config.pop();
    }
}

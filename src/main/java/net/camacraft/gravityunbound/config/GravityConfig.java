package net.camacraft.gravityunbound.config;

import java.util.List;

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

	// plating / fields
	public static ForgeConfigSpec.IntValue platingMaxLevel;
	public static ForgeConfigSpec.BooleanValue gravityAffectsFluids;
	public static ForgeConfigSpec.BooleanValue gravityAffectsParticles;
	public static ForgeConfigSpec.ConfigValue<List<? extends String>> artificialGravityDimensions;
	public static ForgeConfigSpec.DoubleValue artificialGravityAcceleration;
	public static ForgeConfigSpec.ConfigValue<List<? extends String>> dimensionGravity;

	// gravity core
	public static ForgeConfigSpec.IntValue gravityCoreDefaultRange;
	public static ForgeConfigSpec.IntValue gravityCoreMaxRange;
	public static ForgeConfigSpec.BooleanValue gravityCoreAffectsShips;
	public static ForgeConfigSpec.BooleanValue gravityPlatingAffectsShips;
	public static ForgeConfigSpec.BooleanValue shipFieldsAffectWorldFluids;
	public static ForgeConfigSpec.DoubleValue gravityCoreShipForceMultiplier;
	public static ForgeConfigSpec.DoubleValue shipFieldDamping;
	public static ForgeConfigSpec.BooleanValue shipsCollideWithPlatingPanels;

	// gravity normalizer
	public static ForgeConfigSpec.IntValue normalizerDefaultRange;
	public static ForgeConfigSpec.IntValue normalizerMaxRange;

    static
    {
    	Pair<GravityConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(GravityConfig::new);
    	CONFIG = pair.getLeft();
    	CONFIG_SPEC = pair.getRight();
    }

    public GravityConfig(ForgeConfigSpec.Builder config)
    {
    	config.push("Gravity Settings");
    	GravityConfig.rotationTime = config.comment("animation rotation time (ms) for discrete gravity flips").defineInRange("rotationTime", 500, 0, Integer.MAX_VALUE);
    	GravityConfig.gravityStrengthMultiplier = config.comment("gravity strength multiplier").defineInRange("gravityStrengthMultiplier", 1.0F, 0.0F, Float.MAX_VALUE);
    	GravityConfig.worldVelocity = config.comment("conserve world-space velocity on gravity flips instead of rotating it").define("worldVelocity", false);
    	GravityConfig.resetGravityOnRespawn = config.comment("reset gravity on respawn").define("resetGravityOnRespawn", true);
    	GravityConfig.voidDamageAboveWorld = config.comment("void damage when above world").define("voidDamageAboveWorld", true);
    	GravityConfig.voidDamageOnHorizontalFallTooFar = config.comment("void damage when horizontally fall too far").define("voidDamageOnHorizontalFallTooFar", true);
    	GravityConfig.autoJumpOnGravityPlateInnerCorner = config.comment("auto jump on gravity plate inner corner").define("autoJumpOnGravityPlateInnerCorner", true);
    	GravityConfig.adjustPositionAfterChangingGravity = config.comment("adjust position after gravity change").define("adjustPositionAfterChangingGravity", true);
        config.pop();

        config.push("Gravity Fields");
        GravityConfig.platingMaxLevel = config.comment("maximum upgrade level (field range) of gravity plating").defineInRange("platingMaxLevel", 64, 1, 256);
        GravityConfig.gravityAffectsFluids = config.comment("liquids inside gravity fields flow along the field's down direction").define("gravityAffectsFluids", true);
        GravityConfig.gravityAffectsParticles = config.comment("particles inside gravity fields fall along the field's down direction").define("gravityAffectsParticles", true);
        GravityConfig.artificialGravityDimensions = config
            .comment("dimensions where gravity fields also apply an accelerating force (zero-g dimensions)")
            .defineListAllowEmpty(List.of("artificialGravityDimensions"), () -> List.of(
                "ad_astra:earth_orbit",
                "ad_astra:glacio_orbit",
                "ad_astra:mars_orbit",
                "ad_astra:mercury_orbit",
                "ad_astra:moon_orbit",
                "ad_astra:venus_orbit",
                "genesis:great_unknown",
                "genesis:wormhole"
            ), o -> o instanceof String);
        GravityConfig.artificialGravityAcceleration = config
            .comment("acceleration (blocks/tick^2) applied by fields in artificial-gravity dimensions")
            .defineInRange("artificialGravityAcceleration", 0.08, 0.0, 1.0);
        GravityConfig.dimensionGravity = config
            .comment("per-dimension ambient gravity: entries of the form \"namespace:dimension=direction\" or",
                     "\"namespace:dimension=direction,strength\" (direction: down/up/north/south/west/east;",
                     "strength: 1.0 = vanilla). Overridden by any gravity field. Example: \"minecraft:the_nether=up,0.5\"")
            .defineListAllowEmpty(List.of("dimensionGravity"), List::of, o -> o instanceof String);
        config.pop();

        config.push("Gravity Core");
        GravityConfig.gravityCoreDefaultRange = config.comment("default radius (blocks) of a freshly placed gravity core's field").defineInRange("gravityCoreDefaultRange", 8, 1, 128);
        GravityConfig.gravityCoreMaxRange = config.comment("maximum radius (blocks) a gravity core can be upgraded to").defineInRange("gravityCoreMaxRange", 32, 1, 128);
        GravityConfig.gravityCoreAffectsShips = config.comment("whether gravity cores pull/push Valkyrien Skies ships").define("gravityCoreAffectsShips", true);
        GravityConfig.gravityPlatingAffectsShips = config.comment("whether gravity plating fields pull/push Valkyrien Skies ships (per-plate toggle in the settings GUI)").define("gravityPlatingAffectsShips", true);
        GravityConfig.shipFieldsAffectWorldFluids = config.comment("whether fields mounted on Valkyrien Skies ships also bend the WORLD's liquids they fly over (a ship's field pulling water out of a lake)").define("shipFieldsAffectWorldFluids", true);
        GravityConfig.gravityCoreShipForceMultiplier = config.comment("multiplier for the force gravity cores apply to ships (1.0 = 1g)").defineInRange("gravityCoreShipForceMultiplier", 1.0, 0.0, 10.0);
        GravityConfig.shipFieldDamping = config.comment(
            "damping of a held ship's velocity and spin RELATIVE to the ship (or world) carrying the field, per second",
            "(0 = none). Fields are conservative and the contact solver injects energy: without damping a cluster of",
            "captured ships keeps jittering until it spins its attractor up; with it captures settle into resting contact.",
            "Applied equal and opposite to both ships, so momentum is still conserved.")
            .defineInRange("shipFieldDamping", 0.5, 0.0, 10.0);
        GravityConfig.shipsCollideWithPlatingPanels = config.comment(
            "whether Valkyrien Skies ships collide with gravity plating as its thin panels (registered with VS's physics at",
            "world load). Set false to fall back to VS's own default shape for plating if a world refuses to load.")
            .define("shipsCollideWithPlatingPanels", true);
        config.pop();

        config.push("Gravity Normalizer");
        GravityConfig.normalizerDefaultRange = config.comment("default half-extent (blocks) of a freshly placed gravity normalizer's zone").defineInRange("normalizerDefaultRange", 8, 1, 128);
        GravityConfig.normalizerMaxRange = config.comment("maximum half-extent (blocks) a gravity normalizer can be upgraded to").defineInRange("normalizerMaxRange", 32, 1, 128);
        config.pop();
    }
}

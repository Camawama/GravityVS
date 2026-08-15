package net.cama.gravityapivs.mob_effect;

import java.util.EnumMap;

import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

public class GravityDirectionMobEffect extends MobEffect {
    public static final int COLOR = 0x98D982;
    
    // Populated by the constructor, so it always holds the REGISTERED
    // instances from GravityMobEffects. The old static initializer filled it
    // with six unregistered orphans — LivingEntity.getEffect() is keyed by
    // instance, so every lookup missed and gravity-direction potions/effects
    // silently did nothing.
    public static final EnumMap<Direction, GravityDirectionMobEffect> EFFECT_MAP =
            new EnumMap<>(Direction.class);

    public final Direction gravityDirection;

    public GravityDirectionMobEffect(Direction gravityDirection) {
        super(MobEffectCategory.NEUTRAL, COLOR);
        this.gravityDirection = gravityDirection;
        EFFECT_MAP.put(gravityDirection, this);
    }
}

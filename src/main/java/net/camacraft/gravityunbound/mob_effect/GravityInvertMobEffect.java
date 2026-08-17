package net.camacraft.gravityunbound.mob_effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

public class GravityInvertMobEffect extends MobEffect {
    
    public static final int COLOR = 0x98D982;
    
    public GravityInvertMobEffect() {
        super(MobEffectCategory.NEUTRAL, COLOR);
    }
}

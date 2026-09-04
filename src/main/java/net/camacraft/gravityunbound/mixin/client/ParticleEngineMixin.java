package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.client.GravityParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;

/**
 * A particle spawned inside a gravity field takes the field's frame as it
 * is added: its constructor wrote a WORLD-space velocity (a smoke column
 * rises world-up), and the frame-aware physics (see ParticleMixin) reads
 * velocity in the field's frame. Valkyrien Skies has already moved
 * ship-spawned particles into world coordinates by this point.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void gravityunbound$adoptFieldFrame(Particle particle, CallbackInfo ci) {
        if (particle instanceof GravityParticle gravityParticle) {
            gravityParticle.gravityunbound$adoptFieldFrame();
        }
    }
}

package net.camacraft.gravityunbound.client;

/**
 * Duck interface the particle mixin implements: lets the particle engine
 * hand a freshly created particle its field frame before its first tick.
 */
public interface GravityParticle {
    /** Adopt the gravity frame at the particle's current position (see the mixin). */
    void gravityunbound$adoptFieldFrame();
}

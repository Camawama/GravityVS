package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.util.GravityFieldLookup;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Particles fall along the gravity field they are in. Vanilla's base tick
 * accelerates straight down by {@code 0.04 * gravity} before moving; right
 * after that store the acceleration is re-aimed along the field's down at
 * the particle's position (its own grid, so particles living on a ship
 * follow the ship's fields). Particle classes that replace the base tick
 * with their own physics (drips, splashes, smoke) keep vanilla behavior.
 */
@Mixin(Particle.class)
public abstract class ParticleMixin {
    @Shadow
    protected double x;
    @Shadow
    protected double y;
    @Shadow
    protected double z;
    @Shadow
    protected double xd;
    @Shadow
    protected double yd;
    @Shadow
    protected double zd;
    @Shadow
    protected float gravity;
    @Shadow
    @Final
    protected ClientLevel level;

    @Inject(
        method = "tick",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/particle/Particle;yd:D",
            opcode = Opcodes.PUTFIELD,
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void gravityunbound$fieldGravity(CallbackInfo ci) {
        if (this.gravity == 0.0F) {
            return;
        }
        Direction down = GravityFieldLookup.particleDownAt(this.level, BlockPos.containing(this.x, this.y, this.z));
        if (down == null || down == Direction.DOWN) {
            return;
        }
        double g = 0.04 * this.gravity;
        // undo vanilla's world-down acceleration, apply it along the field
        this.yd += g;
        this.xd += g * down.getStepX();
        this.yd += g * down.getStepY();
        this.zd += g * down.getStepZ();
    }
}

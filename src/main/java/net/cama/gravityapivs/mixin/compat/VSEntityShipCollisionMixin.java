package net.cama.gravityapivs.mixin.compat;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Valkyrien Skies wraps the {@code Entity.collide} CALL inside
 * {@code Entity.move} and applies its own entity-vs-ship collision to the
 * result — using the entity's stored AABB against ship polygons.
 *
 * For capsule players that stored AABB is only a loose world-aligned ENVELOPE
 * of the rotated capsule; the capsule itself already collides with ships
 * EXACTLY, against the ship's real blocks in shipyard space. Letting VS
 * re-collide the envelope box fought the capsule every tick: the envelope's
 * corners hit ship geometry the spheres were nowhere near, holding players
 * afloat above plating on rotated ships and causing constant stutter.
 *
 * Skip VS's adjustment entirely while the capsule owns collision.
 */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public abstract class VSEntityShipCollisionMixin {

    @Inject(
        method = "adjustEntityMovementForShipCollisions",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void gravityapivs$skipForCapsulePlayers(
        Entity entity, Vec3 movement, AABB entityBoundingBox, Level world,
        CallbackInfoReturnable<Vec3> cir
    ) {
        if (entity == null) {
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp != null && comp.useCapsuleCollision()) {
            cir.setReturnValue(movement);
        }
    }
}

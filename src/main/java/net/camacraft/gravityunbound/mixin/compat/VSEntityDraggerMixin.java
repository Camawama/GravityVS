package net.camacraft.gravityunbound.mixin.compat;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.util.EntityDragger;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Valkyrien Skies' sneak edge-protection ({@code EntityDragger.backOff},
 * called from its {@code maybeBackOffFromEdge} hook) clips the player's box
 * toward ship edges assuming a vanilla world-down player box. For capsule
 * players (rotated gravity frame, envelope AABB) its geometry assumptions
 * don't hold — sneaking on a tilted ship slid the player around instead of
 * holding them. The capsule handles its own ground contact; skip VS's
 * back-off while it owns collision.
 */
@Mixin(value = EntityDragger.class, remap = false)
public abstract class VSEntityDraggerMixin {

    @Inject(
        method = "backOff",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void gravityunbound$skipBackOffForCapsulePlayers(
        Vec3 movement, Ship ship, Player player, Level level,
        CallbackInfoReturnable<Vec3> cir
    ) {
        if (player == null) {
            return;
        }
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(player);
        if (comp != null && comp.useCapsuleCollision()) {
            cir.setReturnValue(movement);
        }
    }

    /**
     * Ship yaw-follow, corrected for rotated gravity frames AT THE SOURCE.
     *
     * VS adds the ship's world yaw delta directly to dragged entities' yaw.
     * Yaw is frame-relative under rotated gravity: a yaw increment turns the
     * look about the frame's OWN up axis, so the world delta only lands
     * correctly upright — stood upside down it turns the camera the opposite
     * way. The correct local increment is the world delta projected onto the
     * frame's up axis: {@code delta * (frameUp . worldUp)}.
     *
     * Correcting one tick later (previous approach) alternated VS's wrong
     * step and our counter-step every tick — a per-tick yaw sawtooth felt as
     * "extremely jittery camera" on moving ships. Wrapping the yaw writes
     * inside the drag itself applies the projected delta in one step, in the
     * same phase. Upright frames project to exactly the original value.
     */
    @WrapOperation(
        method = "dragEntitiesWithShips",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setYRot(F)V",
            remap = true
        ),
        remap = false
    )
    private void gravityunbound$projectDragYaw(Entity entity, float newYaw, Operation<Void> original) {
        original.call(entity, gravityunbound$projectYaw(entity, entity.getYRot(), newYaw));
    }

    @WrapOperation(
        method = "dragEntitiesWithShips",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setYHeadRot(F)V",
            remap = true
        ),
        remap = false
    )
    private void gravityunbound$projectDragHeadYaw(Entity entity, float newYaw, Operation<Void> original) {
        original.call(entity, gravityunbound$projectYaw(entity, entity.getYHeadRot(), newYaw));
    }

    private static float gravityunbound$projectYaw(Entity entity, float currentYaw, float newYaw) {
        GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null) {
            return newYaw;
        }
        // FIELD-ANCHORED entities: the capability owns the spin-follow — it
        // computes the EXACT twist about the frame's up from the ship's
        // full rotation delta (VS only knows its world-yaw component, which
        // misses the twist a rolling/pitching ship applies to its riders).
        // Suppress VS's yaw entirely so nothing double-turns; the sub-tick
        // camera twist (CameraMixin) provides the between-tick smoothness.
        if (comp.isShipFieldAnchored()) {
            return currentYaw;
        }
        if (comp.isVisuallyDefault()) {
            return newYaw;
        }
        float delta = Mth.wrapDegrees(newYaw - currentYaw);
        double sense = comp.getUpVector().y;
        return currentYaw + (float) (delta * sense);
    }
}

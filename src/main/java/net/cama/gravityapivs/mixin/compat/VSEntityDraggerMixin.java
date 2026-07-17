package net.cama.gravityapivs.mixin.compat;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.capabilities.GravityCapabilityImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.util.EntityDragger;

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
    private static void gravityapivs$skipBackOffForCapsulePlayers(
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
}

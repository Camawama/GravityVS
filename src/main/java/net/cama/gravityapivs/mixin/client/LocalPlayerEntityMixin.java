package net.cama.gravityapivs.mixin.client;

import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerEntityMixin extends AbstractClientPlayer {
    public LocalPlayerEntityMixin(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }
    
    @Shadow
    protected abstract boolean suffocatesAt(BlockPos pos);

    @Redirect(
        method = "Lnet/minecraft/client/player/LocalPlayer;suffocatesAt(Lnet/minecraft/core/BlockPos;)Z",
        at = @At(
            value = "NEW",
            target = "(DDDDDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        )
    )
    private AABB redirect_wouldCollideAt_new_0(double x1, double y1, double z1, double x2, double y2, double z2, BlockPos pos) {
        if (GravityChangerAPI.isGravityDefault(this)) {
            return new AABB(x1, y1, z1, x2, y2, z2);
        }
        org.joml.Quaternionf gravityRotation = GravityChangerAPI.getGravityRotation(this);

        AABB playerBox = this.getBoundingBox();
        Vec3 playerMask = RotationUtil.maskPlayerToWorld(new Vec3(0.0D, 1.0D, 0.0D), gravityRotation);
        AABB posBox = new AABB(pos);
        Vec3 posMask = RotationUtil.maskPlayerToWorld(new Vec3(1.0D, 0.0D, 1.0D), gravityRotation);

        return new AABB(
            playerMask.multiply(playerBox.minX, playerBox.minY, playerBox.minZ).add(posMask.multiply(posBox.minX, posBox.minY, posBox.minZ)),
            playerMask.multiply(playerBox.maxX, playerBox.maxY, playerBox.maxZ).add(posMask.multiply(posBox.maxX, posBox.maxY, posBox.maxZ))
        );
    }

    @Inject(
        method = "Lnet/minecraft/client/player/LocalPlayer;moveTowardsClosestSpace(DD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_pushOutOfBlocks(double x, double z, CallbackInfo ci) {
        if (GravityChangerAPI.isGravityDefault(this)) return;

        // the capsule collider keeps the player out of blocks; the vanilla
        // box-based push-out fights it and causes jitter
        ci.cancel();
    }
}

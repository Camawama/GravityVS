package net.camacraft.gravityunbound.mixin;


import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

@Mixin(EntityCollisionContext.class)
public abstract class EntityCollisionContextMixin {
    @Shadow
    @Final
    private Entity entity;
    
    @Shadow
    @Final
    private double entityBottom;
    
    @Redirect(
        method = "<init>(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getY()D",
            ordinal = 0
        )
    )
    private static double redirect_init_getY_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getY();
        }
        
        return RotationUtil.boxWorldToPlayer(entity.getBoundingBox(), gravityDirection).minY;
    }
    
    @Inject(
        method = "Lnet/minecraft/world/phys/shapes/EntityCollisionContext;isAbove(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void inject_isAbove(VoxelShape shape, BlockPos pos, boolean defaultValue, CallbackInfoReturnable<Boolean> cir) {
        if (this.entity == null) return;
        
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(this.entity);
        if (gravityDirection == Direction.DOWN) return;
        
        if (shape.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        
        // Vanilla: entityBottom > pos.getY() + shape.max(Axis.Y) - 1.0E-5F.
        // Transform the ABSOLUTE shape box into the entity's local frame and compare
        // against its local up extent (entityBottom is local, see the <init> redirect above).
        double topLocal = RotationUtil.boxWorldToPlayer(
            shape.bounds().move(pos.getX(), pos.getY(), pos.getZ()), gravityDirection
        ).maxY;
        cir.setReturnValue(this.entityBottom > topLocal - 9.999999747378752E-6D);
    }
}

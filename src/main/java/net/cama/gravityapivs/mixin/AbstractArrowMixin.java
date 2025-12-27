package net.cama.gravityapivs.mixin;


import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin extends Entity {
    
    public AbstractArrowMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/AbstractArrow;tick()V",
        at = @At(
            value = "STORE"
        )
        , ordinal = 0
    )
    public Vec3 tick(Vec3 modify) {
        modify = new Vec3(modify.x, modify.y + 0.05, modify.z);
        modify = RotationUtil.vecWorldToPlayer(modify, GravityChangerAPI.getGravityDirection(this));
        modify = new Vec3(modify.x, modify.y - 0.05, modify.z);
        modify = RotationUtil.vecPlayerToWorld(modify, GravityChangerAPI.getGravityDirection(this));
        return modify;
    }
    
    
    /*@WrapOperation(
        method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;<init>(Lnet/minecraft/world/entity/EntityType;DDDLnet/minecraft/world/level/Level;)V"
        )
    )
    private static AbstractArrow modifyargs_init_init_0(AbstractArrow instance, EntityType<? extends AbstractArrow> type, double x, double y, double z, Level world, Operation<AbstractArrow> original, @Local LivingEntity owner
                                                        ) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(owner);
        if(gravityDirection != Direction.DOWN) {
            Vec3 pos = owner.getEyePosition().subtract(RotationUtil.vecPlayerToWorld(0.0D, 0.10000000149011612D, 0.0D, gravityDirection));
            return original.call(instance, type, pos.x, pos.y, pos.z, world);
        }

        return original.call(instance, type, x, y, z, world);
    }*/
    
    @ModifyConstant(method = "Lnet/minecraft/world/entity/projectile/AbstractArrow;tick()V", constant = @Constant(doubleValue = 0.05000000074505806))
    private double multiplyGravity(double constant) {
        return constant * GravityChangerAPI.getGravityStrength(this);
    }
}

package net.cama.gravityapivs.mixin.client;

import net.cama.gravityapivs.EntityTags;
import net.cama.gravityapivs.RotationAnimation;
import net.cama.gravityapivs.api.GravityChangerAPI;
import net.cama.gravityapivs.util.RotationUtil;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Shadow
    @Final
    private static RenderType SHADOW_RENDER_TYPE;
    
    @Shadow
    private boolean shouldRenderShadow;

    // whether inject_render_0 pushed a pose, so the pop stays balanced
    @org.spongepowered.asm.mixin.Unique
    private boolean gravityapivs$pushedPose = false;
    
    @Shadow
    private static void shadowVertex(PoseStack.Pose entry, VertexConsumer vertices, float alpha, float x, float y, float z, float u, float v) {}
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void inject_render_0(Entity entity, double x, double y, double z, float yaw, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, CallbackInfo ci) {
        gravityapivs$pushedPose = false;
        if (!(entity instanceof Projectile) && !(entity instanceof ExperienceOrb) && EntityTags.allowGravityTransformationInRendering(entity)) {
            net.cama.gravityapivs.capabilities.GravityCapabilityImpl comp =
                GravityChangerAPI.getGravityComponentOrNull(entity);
            if (comp == null || comp.isVisuallyDefault()) {
                return;
            }

            // the model follows the smooth visual frame
            matrices.pushPose();
            gravityapivs$pushedPose = true;
            matrices.mulPose(new Quaternionf(comp.getRenderRotation(tickDelta)).conjugate());
        }
    }
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            ordinal = 1
        )
    )
    private void inject_render_1(Entity entity, double x, double y, double z, float yaw, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, CallbackInfo ci) {
        if (gravityapivs$pushedPose) {
            gravityapivs$pushedPose = false;
            matrices.popPose();
        }
    }
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void inject_render_2(Entity entity, double x, double y, double z, float yaw, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, CallbackInfo ci) {
        if (!(entity instanceof Projectile) && !(entity instanceof ExperienceOrb) && EntityTags.allowGravityTransformationInRendering(entity)) {
            net.cama.gravityapivs.capabilities.GravityCapabilityImpl comp =
                GravityChangerAPI.getGravityComponentOrNull(entity);
            if (comp == null || comp.isDefault()) return;

            // Shadow and hitbox rendering below supply player-space coordinates
            // (computed with the PHYSICS frame), so the pose must apply the
            // player->world rotation, i.e. the CONJUGATE of the frame. Using the
            // frame itself double-rotates and made F3+B hitboxes appear inside
            // walls for 90-degree gravity directions.
            matrices.mulPose(new Quaternionf(comp.getCurrentRotation()).conjugate());
        }
    }
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void inject_renderShadow(PoseStack matrices, MultiBufferSource vertexConsumers, Entity entity, float opacity, float tickDelta, LevelReader world, float radius, CallbackInfo ci) {
        net.cama.gravityapivs.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isDefault()) return;
        // the shadow is anchored to the physics frame, matching the pose applied
        // in inject_render_2
        Quaternionf gravityRotation = comp.getCurrentRotation();
        Vec3 gravityDirection = comp.getCurrGravityDirectionVec();

        ci.cancel();

        double x = Mth.lerp(tickDelta, entity.xOld, entity.getX());
        double y = Mth.lerp(tickDelta, entity.yOld, entity.getY());
        double z = Mth.lerp(tickDelta, entity.zOld, entity.getZ());
        Vec3 minShadowPos = RotationUtil.vecPlayerToWorld((double) -radius, (double) -radius, (double) -radius, gravityRotation).add(x, y, z);
        Vec3 maxShadowPos = RotationUtil.vecPlayerToWorld((double) radius, 0.0D, (double) radius, gravityRotation).add(x, y, z);
        PoseStack.Pose entry = matrices.last();
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(SHADOW_RENDER_TYPE);

        for (BlockPos blockPos : BlockPos.betweenClosed(BlockPos.containing(minShadowPos), BlockPos.containing(maxShadowPos))) {
            gravitychanger$renderShadowPartPlayer(entry, vertexConsumer, world, blockPos, x, y, z, radius, opacity, gravityDirection, gravityRotation);
        }
    }

    private static void gravitychanger$renderShadowPartPlayer(PoseStack.Pose entry, VertexConsumer vertices, LevelReader world, BlockPos pos, double x, double y, double z, float radius, float opacity, Vec3 gravityDirection, Quaternionf gravityRotation) {
        BlockPos posBelow = pos.relative(Direction.getNearest(gravityDirection.x, gravityDirection.y, gravityDirection.z));
        BlockState blockStateBelow = world.getBlockState(posBelow);
        if (blockStateBelow.getRenderShape() != RenderShape.INVISIBLE && world.getMaxLocalRawBrightness(pos) > 3) {
            if (blockStateBelow.isCollisionShapeFullBlock(world, posBelow)) {
                VoxelShape voxelShape = blockStateBelow.getShape(world, posBelow);
                if (!voxelShape.isEmpty()) {
                    Vec3 playerPos = RotationUtil.vecWorldToPlayer(x, y, z, gravityRotation);
                    float alpha = (float) (((double) opacity - (playerPos.y - (RotationUtil.vecWorldToPlayer(Vec3.atCenterOf(pos), gravityRotation).y - 0.5D)) / 2.0D) * 0.5D * (double) world.getLightLevelDependentMagicValue(pos));
                    if (alpha >= 0.0F) {
                        if (alpha > 1.0F) {
                            alpha = 1.0F;
                        }
                        
                        Vec3 centerPos = Vec3.atCenterOf(pos);
                        Vec3 playerCenterPos = RotationUtil.vecWorldToPlayer(centerPos, gravityRotation);
                        
                        Vec3 playerRelNN = playerCenterPos.add(-0.5D, -0.5D, -0.5D).subtract(playerPos);
                        Vec3 playerRelPP = playerCenterPos.add(0.5D, -0.5D, 0.5D).subtract(playerPos);
                        
                        Vec3 relNN = RotationUtil.vecWorldToPlayer(centerPos.add(RotationUtil.vecPlayerToWorld(-0.5D, -0.5D, -0.5D, gravityRotation)).subtract(x, y, z), gravityRotation);
                        Vec3 relNP = RotationUtil.vecWorldToPlayer(centerPos.add(RotationUtil.vecPlayerToWorld(-0.5D, -0.5D, 0.5D, gravityRotation)).subtract(x, y, z), gravityRotation);
                        Vec3 relPN = RotationUtil.vecWorldToPlayer(centerPos.add(RotationUtil.vecPlayerToWorld(0.5D, -0.5D, -0.5D, gravityRotation)).subtract(x, y, z), gravityRotation);
                        Vec3 relPP = RotationUtil.vecWorldToPlayer(centerPos.add(RotationUtil.vecPlayerToWorld(0.5D, -0.5D, 0.5D, gravityRotation)).subtract(x, y, z), gravityRotation);
                        
                        float minU = -(float) playerRelNN.x / 2.0F / radius + 0.5F;
                        float maxU = -(float) playerRelPP.x / 2.0F / radius + 0.5F;
                        float minV = -(float) playerRelNN.z / 2.0F / radius + 0.5F;
                        float maxV = -(float) playerRelPP.z / 2.0F / radius + 0.5F;
                        
                        shadowVertex(entry, vertices, alpha, (float) relNN.x, (float) relNN.y, (float) relNN.z, minU, minV);
                        shadowVertex(entry, vertices, alpha, (float) relNP.x, (float) relNP.y, (float) relNP.z, minU, maxV);
                        shadowVertex(entry, vertices, alpha, (float) relPP.x, (float) relPP.y, (float) relPP.z, maxU, maxV);
                        shadowVertex(entry, vertices, alpha, (float) relPN.x, (float) relPN.y, (float) relPN.z, maxU, minV);
                    }
                }
            }
        }
    }
    
    @ModifyVariable(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
        at = @At(
            value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;",
            ordinal = 0
        ),
        ordinal = 0
    )
    private static AABB modify_renderHitbox_Box_0(AABB box, PoseStack matrices, VertexConsumer vertices, Entity entity, float tickDelta) {
        if (GravityChangerAPI.isGravityDefault(entity)) {
            return box;
        }

        return RotationUtil.boxWorldToPlayer(box, GravityChangerAPI.getGravityRotation(entity));
    }
    
    /**
     * F3+B debug view of the capsule collider: one colored wire SPHERE per
     * collision sphere (green = feet, yellow = middle, red = head), stacked
     * along the visual up axis — this is the true collision volume; there are
     * no box corners. The white box stays the stored envelope AABB.
     * Coordinates are supplied in the physics frame because inject_render_2
     * already applied the player->world pose for hitbox rendering.
     */
    @Inject(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
        at = @At("TAIL")
    )
    private static void inject_renderCapsuleDebug(PoseStack matrices, VertexConsumer vertices, Entity entity, float tickDelta, CallbackInfo ci) {
        net.cama.gravityapivs.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || !comp.useCapsuleCollision()) {
            return;
        }

        double radius = net.cama.gravityapivs.util.CapsuleCollider.capsuleRadius(entity);
        double height = net.cama.gravityapivs.util.CapsuleCollider.capsuleHeight(entity, radius);
        double[] offsets = net.cama.gravityapivs.util.CapsuleCollider.sphereOffsets(height, radius);

        Quaternionf renderRotation = comp.getRenderRotation(tickDelta);
        Vec3 up = RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), renderRotation);
        Vec3 right = RotationUtil.vecPlayerToWorld(new Vec3(1, 0, 0), renderRotation);
        Vec3 forward = RotationUtil.vecPlayerToWorld(new Vec3(0, 0, 1), renderRotation);
        Quaternionf physicsRotation = comp.getCurrentRotation();

        float[][] colors = {
            {0.25f, 1.0f, 0.25f},   // bottom sphere: green
            {1.0f, 1.0f, 0.25f},    // middle sphere: yellow
            {1.0f, 0.35f, 0.35f}    // top sphere: red
        };

        for (int i = 0; i < offsets.length; i++) {
            Vec3 center = up.scale(offsets[i]);
            float[] color = colors[Math.min(i, colors.length - 1)];
            gravityapivs$drawCircle(matrices, vertices, physicsRotation, center, right, forward, radius, color);
            gravityapivs$drawCircle(matrices, vertices, physicsRotation, center, right, up, radius, color);
            gravityapivs$drawCircle(matrices, vertices, physicsRotation, center, forward, up, radius, color);
        }
    }

    /**
     * Wire circle around {@code center} in the plane spanned by {@code a}/{@code b}
     * (world-space, relative to the entity origin), emitted in physics-frame
     * coordinates to match the pose applied by inject_render_2.
     */
    @org.spongepowered.asm.mixin.Unique
    private static void gravityapivs$drawCircle(
        PoseStack matrices, VertexConsumer vertices, Quaternionf physicsRotation,
        Vec3 center, Vec3 a, Vec3 b, double radius, float[] color
    ) {
        PoseStack.Pose pose = matrices.last();
        int segments = 32;

        Vec3 prev = RotationUtil.vecWorldToPlayer(center.add(a.scale(radius)), physicsRotation);
        for (int i = 1; i <= segments; i++) {
            double angle = (Math.PI * 2.0 * i) / segments;
            Vec3 next = RotationUtil.vecWorldToPlayer(
                center.add(a.scale(radius * Math.cos(angle))).add(b.scale(radius * Math.sin(angle))),
                physicsRotation
            );
            Vec3 dir = next.subtract(prev);
            double len = dir.length();
            if (len > 1.0E-9) {
                dir = dir.scale(1.0 / len);
                vertices.vertex(pose.pose(), (float) prev.x, (float) prev.y, (float) prev.z)
                    .color(color[0], color[1], color[2], 1.0F)
                    .normal(pose.normal(), (float) dir.x, (float) dir.y, (float) dir.z)
                    .endVertex();
                vertices.vertex(pose.pose(), (float) next.x, (float) next.y, (float) next.z)
                    .color(color[0], color[1], color[2], 1.0F)
                    .normal(pose.normal(), (float) dir.x, (float) dir.y, (float) dir.z)
                    .endVertex();
            }
            prev = next;
        }
    }

    @Redirect(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewVector(F)Lnet/minecraft/world/phys/Vec3;",
            ordinal = 0
        )
    )
    private static Vec3 redirectViewVector(Entity instance, float partialTicks) {
        Vec3 viewVector = instance.getViewVector(partialTicks);
        if (GravityChangerAPI.isGravityDefault(instance)) {
            return viewVector;
        }

        return RotationUtil.vecWorldToPlayer(viewVector, GravityChangerAPI.getGravityRotation(instance));
    }
}

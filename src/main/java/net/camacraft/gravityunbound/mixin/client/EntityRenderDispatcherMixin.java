package net.camacraft.gravityunbound.mixin.client;

import net.camacraft.gravityunbound.EntityTags;
import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    // per-invocation pushed-pose flags: a STACK, not a boolean field — nested
    // dispatcher.render calls (some modded renderers re-enter) would reset a
    // single flag and leave the outer pushed pose unpopped, corrupting the
    // PoseStack for the rest of the frame (render thread only, so no
    // synchronization needed)
    @org.spongepowered.asm.mixin.Unique
    private final java.util.ArrayDeque<Boolean> gravityunbound$pushedPose = new java.util.ArrayDeque<>();
    // render-thread scratch to avoid per-entity-per-frame allocations
    @org.spongepowered.asm.mixin.Unique
    private final Quaternionf gravityunbound$scratchRotation = new Quaternionf();

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
        boolean pushed = false;
        if (!net.camacraft.gravityunbound.client.GuiRenderState.renderingGuiEntity
            && !(entity instanceof Projectile) && !(entity instanceof ExperienceOrb) && EntityTags.allowGravityTransformationInRendering(entity)) {
            net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
                GravityChangerAPI.getGravityComponentOrNull(entity);
            if (comp != null && !comp.isRenderDefault()) {
                // the model follows the smooth visual frame
                matrices.pushPose();
                pushed = true;
                matrices.mulPose(comp.getRenderRotation(tickDelta, gravityunbound$scratchRotation).conjugate());
            }
        }
        gravityunbound$pushedPose.push(pushed);
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
        if (!gravityunbound$pushedPose.isEmpty() && gravityunbound$pushedPose.pop()) {
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
        if (!net.camacraft.gravityunbound.client.GuiRenderState.renderingGuiEntity
            && !(entity instanceof Projectile) && !(entity instanceof ExperienceOrb) && EntityTags.allowGravityTransformationInRendering(entity)) {
            net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
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
    
    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private static net.minecraft.client.renderer.RenderType SHADOW_RENDER_TYPE;

    @org.spongepowered.asm.mixin.Shadow
    private static void shadowVertex(
        PoseStack.Pose pose, VertexConsumer consumer, float alpha,
        float x, float y, float z, float u, float v
    ) {
        throw new AssertionError();
    }

    /**
     * The vanilla circle shadow is a stylistic stand-in for sunlight, so it
     * must NOT rotate with gravity — an upside-down player still casts a
     * world-oriented shadow on the ground below. But it must ADAPT: a player
     * lying sideways on a wall shades a longer strip of ground, centered
     * under the MODEL's center rather than under the feet point. For
     * visually-default entities vanilla runs untouched; rotated entities get
     * a ported shadow with an elliptical, model-centered blob (long axis
     * along the model's world-horizontal lying direction, growing toward
     * half the model height as the model tips over).
     * inject_render_2 has already applied the player->world physics rotation
     * to the pose (needed for hitbox rendering), so it is undone around the
     * shadow rendering either way.
     */
    @WrapOperation(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V"
        )
    )
    private void wrap_renderShadow(PoseStack matrices, MultiBufferSource vertexConsumers, Entity entity, float opacity, float tickDelta, LevelReader world, float radius, Operation<Void> original) {
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || comp.isDefault()) {
            original.call(matrices, vertexConsumers, entity, opacity, tickDelta, world, radius);
            return;
        }

        matrices.pushPose();
        // cancel the conjugated physics rotation applied by inject_render_2
        // (mulPose only reads the quaternion, so the shared canonical
        // instance is safe to pass directly)
        matrices.mulPose(comp.getCurrentRotation());
        if (comp.isVisuallyDefault()) {
            original.call(matrices, vertexConsumers, entity, opacity, tickDelta, world, radius);
        }
        else {
            gravityunbound$renderRotatedShadow(matrices, vertexConsumers, entity, opacity, tickDelta, world, radius, comp);
        }
        matrices.popPose();
    }

    /**
     * Port of vanilla renderShadow (verified against the 1.20.1-47.4.16
     * decompiled source) with three generalizations: the blob is CENTERED
     * under the rotated model's center; it is an ELLIPSE whose long axis
     * follows the model's world-horizontal lying direction (long radius
     * grows from the vanilla radius toward half the model height as the
     * model tips over); and the vertical fade references the model's lowest
     * extent instead of the feet. Geometry stays world-aligned and is
     * emitted relative to the entity's render position, matching the pose.
     */
    @org.spongepowered.asm.mixin.Unique
    private void gravityunbound$renderRotatedShadow(
        PoseStack matrices, MultiBufferSource buffers, Entity entity,
        float weight, float tickDelta, LevelReader world, float radius,
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp
    ) {
        if (weight <= 0.0F) {
            return;
        }
        if (entity instanceof net.minecraft.world.entity.Mob mob && mob.isBaby()) {
            radius *= 0.5F;
        }

        // entity render position — the pose origin (vertex offsets are
        // relative to it, exactly like vanilla)
        double ex = Mth.lerp((double) tickDelta, entity.xOld, entity.getX());
        double ey = Mth.lerp((double) tickDelta, entity.yOld, entity.getY());
        double ez = Mth.lerp((double) tickDelta, entity.zOld, entity.getZ());

        Quaternionf renderRotation = comp.getRenderRotation(tickDelta);
        Vec3 up = RotationUtil.vecPlayerToWorld(new Vec3(0, 1, 0), renderRotation);
        float height = entity.getBbHeight();

        // model center; the blob centers under it
        double cx = ex + up.x * (height * 0.5);
        double cy = ey + up.y * (height * 0.5);
        double cz = ez + up.z * (height * 0.5);

        // lying direction = world-horizontal part of the model's up;
        // horizontality 0 (upright/upside-down) .. 1 (fully sideways)
        double lyingX = up.x;
        double lyingZ = up.z;
        double horizontality = Math.sqrt(lyingX * lyingX + lyingZ * lyingZ);
        double longX;
        double longZ;
        if (horizontality > 1.0E-4) {
            longX = lyingX / horizontality;
            longZ = lyingZ / horizontality;
        }
        else {
            longX = 0.0;
            longZ = 1.0;
        }
        // perpendicular (short) axis in the ground plane
        double perpX = -longZ;
        double perpZ = longX;

        float longRadius = (float) (radius + Math.max(0.0, height * 0.5 - radius) * horizontality);
        float shortRadius = radius;

        // the model's lowest world-Y extent — fade and sampling reference
        double lowY = cy - (height * 0.5) * Math.abs(up.y);

        float depth = Math.min(weight / 0.5F, longRadius);
        int minX = Mth.floor(cx - longRadius);
        int maxX = Mth.floor(cx + longRadius);
        int minY = Mth.floor(lowY - depth);
        int maxY = Mth.floor(lowY);
        int minZ = Mth.floor(cz - longRadius);
        int maxZ = Mth.floor(cz + longRadius);

        PoseStack.Pose pose = matrices.last();
        VertexConsumer consumer = buffers.getBuffer(SHADOW_RENDER_TYPE);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                cursor.set(x, 0, z);
                net.minecraft.world.level.chunk.ChunkAccess chunk = world.getChunk(cursor);

                for (int y = minY; y <= maxY; y++) {
                    cursor.setY(y);
                    float alpha = weight - (float) (lowY - cursor.getY()) * 0.5F;
                    gravityunbound$rotatedBlockShadow(
                        pose, consumer, chunk, world, cursor,
                        ex, ey, ez, cx, cz,
                        longX, longZ, perpX, perpZ, longRadius, shortRadius, alpha
                    );
                }
            }
        }
    }

    /**
     * Port of vanilla renderBlockShadow with an elliptical, rotatable UV
     * mapping: u runs along the blob's long axis, v along the short axis,
     * both measured from the blob CENTER (cx, cz) — vertex positions stay
     * relative to the entity render position to match the pose.
     */
    @org.spongepowered.asm.mixin.Unique
    private static void gravityunbound$rotatedBlockShadow(
        PoseStack.Pose pose, VertexConsumer consumer,
        net.minecraft.world.level.chunk.ChunkAccess chunk, LevelReader world, BlockPos pos,
        double ex, double ey, double ez, double cx, double cz,
        double longX, double longZ, double perpX, double perpZ,
        float longRadius, float shortRadius, float alpha
    ) {
        BlockPos below = pos.below();
        BlockState belowState = chunk.getBlockState(below);
        if (belowState.getRenderShape() == RenderShape.INVISIBLE || world.getMaxLocalRawBrightness(pos) <= 3) {
            return;
        }
        if (!belowState.isCollisionShapeFullBlock(chunk, below)) {
            return;
        }
        VoxelShape shape = belowState.getShape(chunk, below);
        if (shape.isEmpty()) {
            return;
        }

        float brightness = net.minecraft.client.renderer.LightTexture.getBrightness(
            world.dimensionType(), world.getMaxLocalRawBrightness(pos)
        );
        float quadAlpha = alpha * 0.5F * brightness;
        if (quadAlpha < 0.0F) {
            return;
        }
        if (quadAlpha > 1.0F) {
            quadAlpha = 1.0F;
        }

        AABB bounds = shape.bounds();
        double x0 = pos.getX() + bounds.minX;
        double x1 = pos.getX() + bounds.maxX;
        double surfaceY = pos.getY() + bounds.minY;
        double z0 = pos.getZ() + bounds.minZ;
        double z1 = pos.getZ() + bounds.maxZ;

        float fy = (float) (surfaceY - ey);

        // per-corner elliptical UVs about the blob center
        float u00 = gravityunbound$shadowU(x0, z0, cx, cz, longX, longZ, longRadius);
        float u01 = gravityunbound$shadowU(x0, z1, cx, cz, longX, longZ, longRadius);
        float u11 = gravityunbound$shadowU(x1, z1, cx, cz, longX, longZ, longRadius);
        float u10 = gravityunbound$shadowU(x1, z0, cx, cz, longX, longZ, longRadius);
        float v00 = gravityunbound$shadowU(x0, z0, cx, cz, perpX, perpZ, shortRadius);
        float v01 = gravityunbound$shadowU(x0, z1, cx, cz, perpX, perpZ, shortRadius);
        float v11 = gravityunbound$shadowU(x1, z1, cx, cz, perpX, perpZ, shortRadius);
        float v10 = gravityunbound$shadowU(x1, z0, cx, cz, perpX, perpZ, shortRadius);

        shadowVertex(pose, consumer, quadAlpha, (float) (x0 - ex), fy, (float) (z0 - ez), u00, v00);
        shadowVertex(pose, consumer, quadAlpha, (float) (x0 - ex), fy, (float) (z1 - ez), u01, v01);
        shadowVertex(pose, consumer, quadAlpha, (float) (x1 - ex), fy, (float) (z1 - ez), u11, v11);
        shadowVertex(pose, consumer, quadAlpha, (float) (x1 - ex), fy, (float) (z0 - ez), u10, v10);
    }

    @org.spongepowered.asm.mixin.Unique
    private static float gravityunbound$shadowU(
        double px, double pz, double cx, double cz,
        double axisX, double axisZ, float axisRadius
    ) {
        double along = (px - cx) * axisX + (pz - cz) * axisZ;
        return (float) (-along / 2.0 / axisRadius + 0.5);
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
        net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl comp =
            GravityChangerAPI.getGravityComponentOrNull(entity);
        if (comp == null || !comp.useCapsuleCollision()) {
            return;
        }

        double radius = net.camacraft.gravityunbound.util.CapsuleCollider.capsuleRadius(entity);
        double height = net.camacraft.gravityunbound.util.CapsuleCollider.capsuleHeight(entity, radius);
        double[] offsets = net.camacraft.gravityunbound.util.CapsuleCollider.sphereOffsets(height, radius);

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
            gravityunbound$drawCircle(matrices, vertices, physicsRotation, center, right, forward, radius, color);
            gravityunbound$drawCircle(matrices, vertices, physicsRotation, center, right, up, radius, color);
            gravityunbound$drawCircle(matrices, vertices, physicsRotation, center, forward, up, radius, color);
        }
    }

    /**
     * Wire circle around {@code center} in the plane spanned by {@code a}/{@code b}
     * (world-space, relative to the entity origin), emitted in physics-frame
     * coordinates to match the pose applied by inject_render_2.
     */
    @org.spongepowered.asm.mixin.Unique
    private static void gravityunbound$drawCircle(
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

package net.cama.gravityapivs.client;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.Ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.cama.gravityapivs.GravityAPI;
import net.cama.gravityapivs.util.FieldVisuals;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the gravity field visuals as real geometry in the level render pass.
 *
 * Why not particles: particles are individually simulated engine objects with
 * hard caps, distance culling and a fixed sprite set — thousands of them lag
 * and still vanish at range. This renderer draws each field as one batch of
 * line geometry: the field EXTENT as a crisp outline (box wireframe for
 * plates, rings for cores) and the DIRECTION as dashes marching along fixed
 * flow lines. Zero per-entity cost, no culling beyond chunk load range, and
 * fields on ships follow the ship's per-frame render transform exactly.
 */
@Mod.EventBusSubscriber(modid = GravityAPI.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FieldVisualsRenderer {

    private static final float[] ATTRACT = {0.35f, 0.6f, 1.0f};
    private static final float[] REPULSE = {1.0f, 0.6f, 0.2f};

    // animation period in ticks (one full dash cycle)
    private static final float PERIOD = 40.0f;
    private static final float DASH_LENGTH = 0.35f;
    private static final float DASH_SPACING = 1.0f;

    /** The 26 fixed radial spoke directions for core flow. */
    private static final Vec3[] SPOKES = buildSpokes();

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }

        FieldVisuals.prune(level.getGameTime());
        if (FieldVisuals.PLATES.isEmpty() && FieldVisuals.CORES.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        float phase = ((level.getGameTime() % (long) PERIOD) + event.getPartialTick()) / PERIOD;

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        for (FieldVisuals.PlateField field : FieldVisuals.PLATES.values()) {
            poseStack.pushPose();
            applyGridPose(poseStack, cam, field.ship());
            float[] color = field.attracting() ? ATTRACT : REPULSE;
            drawBoxOutline(poseStack, lines, field.box(), color);
            drawPlateFlow(poseStack, lines, field.box(), field.flowDir(), field.attracting(), phase, color);
            poseStack.popPose();
        }

        for (FieldVisuals.CoreField field : FieldVisuals.CORES.values()) {
            poseStack.pushPose();
            applyGridPose(poseStack, cam, field.ship());
            float[] color = field.attracting() ? ATTRACT : REPULSE;
            drawCoreRings(poseStack, lines, field.center(), field.range(), color);
            drawCoreSpokes(poseStack, lines, field.center(), field.range(), field.attracting(), phase, color);
            poseStack.popPose();
        }

        buffers.endBatch(RenderType.lines());
    }

    /**
     * Positions are grid-local: apply camera-relative translation, and for
     * ships the per-frame RENDER transform (composed in doubles first — the
     * shipyard sits hundreds of thousands of blocks out, so going through
     * floats before subtracting the camera would destroy all precision).
     */
    private static void applyGridPose(PoseStack poseStack, Vec3 cam, @Nullable Ship ship) {
        if (ship == null) {
            poseStack.translate(-cam.x, -cam.y, -cam.z);
            return;
        }

        Matrix4d m = new Matrix4d()
            .translate(-cam.x, -cam.y, -cam.z)
            .mul(ship instanceof ClientShip clientShip
                ? clientShip.getRenderTransform().getShipToWorld()
                : ship.getTransform().getShipToWorld());
        poseStack.mulPoseMatrix(new Matrix4f(
            (float) m.m00(), (float) m.m01(), (float) m.m02(), (float) m.m03(),
            (float) m.m10(), (float) m.m11(), (float) m.m12(), (float) m.m13(),
            (float) m.m20(), (float) m.m21(), (float) m.m22(), (float) m.m23(),
            (float) m.m30(), (float) m.m31(), (float) m.m32(), (float) m.m33()
        ));
    }

    // ------------------------------------------------------------------
    // geometry
    // ------------------------------------------------------------------

    private static void line(
        PoseStack poseStack, VertexConsumer vc,
        double x1, double y1, double z1, double x2, double y2, double z2,
        float[] color, float alpha
    ) {
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6f) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;

        PoseStack.Pose pose = poseStack.last();
        vc.vertex(pose.pose(), (float) x1, (float) y1, (float) z1)
            .color(color[0], color[1], color[2], alpha)
            .normal(pose.normal(), dx, dy, dz)
            .endVertex();
        vc.vertex(pose.pose(), (float) x2, (float) y2, (float) z2)
            .color(color[0], color[1], color[2], alpha)
            .normal(pose.normal(), dx, dy, dz)
            .endVertex();
    }

    private static void drawBoxOutline(PoseStack poseStack, VertexConsumer vc, AABB box, float[] color) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;

        // bottom rectangle, top rectangle, verticals
        line(poseStack, vc, x1, y1, z1, x2, y1, z1, color, 0.9f);
        line(poseStack, vc, x2, y1, z1, x2, y1, z2, color, 0.9f);
        line(poseStack, vc, x2, y1, z2, x1, y1, z2, color, 0.9f);
        line(poseStack, vc, x1, y1, z2, x1, y1, z1, color, 0.9f);
        line(poseStack, vc, x1, y2, z1, x2, y2, z1, color, 0.9f);
        line(poseStack, vc, x2, y2, z1, x2, y2, z2, color, 0.9f);
        line(poseStack, vc, x2, y2, z2, x1, y2, z2, color, 0.9f);
        line(poseStack, vc, x1, y2, z2, x1, y2, z1, color, 0.9f);
        line(poseStack, vc, x1, y1, z1, x1, y2, z1, color, 0.9f);
        line(poseStack, vc, x2, y1, z1, x2, y2, z1, color, 0.9f);
        line(poseStack, vc, x2, y1, z2, x2, y2, z2, color, 0.9f);
        line(poseStack, vc, x1, y1, z2, x1, y2, z2, color, 0.9f);
    }

    /**
     * Flow dashes marching through the box along the flow axis, one line per
     * block-center of the box's cross-section. Attract marches toward the
     * plate, repulse away from it.
     */
    private static void drawPlateFlow(
        PoseStack poseStack, VertexConsumer vc,
        AABB box, Direction flowDir, boolean attracting, float phase, float[] color
    ) {
        Direction.Axis axis = flowDir.getAxis();
        Direction outward = attracting ? flowDir.getOpposite() : flowDir;

        double depth = box.max(axis) - box.min(axis);
        if (depth < 0.1) {
            return;
        }
        // surface (s = 0) is the box face the field emanates from
        double surface = outward.getAxisDirection() == Direction.AxisDirection.POSITIVE
            ? box.min(axis)
            : box.max(axis);
        double sign = outward.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : -1.0;

        // attract flows toward the surface, repulse away from it
        float march = attracting ? 1.0f - phase : phase;

        Direction.Axis tangentA = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis tangentB = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;

        int dashes = Math.max(1, (int) Math.ceil(depth / DASH_SPACING));
        for (double a = Math.floor(box.min(tangentA)) + 0.5; a < box.max(tangentA); a += 1.0) {
            for (double b = Math.floor(box.min(tangentB)) + 0.5; b < box.max(tangentB); b += 1.0) {
                for (int k = 0; k < dashes; k++) {
                    double s = ((k + march) * DASH_SPACING) % depth;
                    double end = Math.min(s + DASH_LENGTH, depth);

                    double c1 = surface + sign * s;
                    double c2 = surface + sign * end;
                    line(poseStack, vc,
                        axis == Direction.Axis.X ? c1 : a,
                        axis == Direction.Axis.Y ? c1 : (axis == Direction.Axis.X ? a : b),
                        axis == Direction.Axis.Z ? c1 : b,
                        axis == Direction.Axis.X ? c2 : a,
                        axis == Direction.Axis.Y ? c2 : (axis == Direction.Axis.X ? a : b),
                        axis == Direction.Axis.Z ? c2 : b,
                        color, 1.0f
                    );
                }
            }
        }
    }

    private static void drawCoreRings(PoseStack poseStack, VertexConsumer vc, Vec3 center, double range, float[] color) {
        int segments = 48;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < segments; i++) {
                double a1 = (Math.PI * 2.0 * i) / segments;
                double a2 = (Math.PI * 2.0 * (i + 1)) / segments;
                Vec3 p1 = ringPoint(center, range, ring, a1);
                Vec3 p2 = ringPoint(center, range, ring, a2);
                line(poseStack, vc, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color, 0.9f);
            }
        }
    }

    private static Vec3 ringPoint(Vec3 center, double range, int ring, double angle) {
        double cos = Math.cos(angle) * range;
        double sin = Math.sin(angle) * range;
        return switch (ring) {
            case 0 -> center.add(cos, sin, 0);
            case 1 -> center.add(cos, 0, sin);
            default -> center.add(0, cos, sin);
        };
    }

    /** Dashes marching along the 26 fixed radial spokes. */
    private static void drawCoreSpokes(
        PoseStack poseStack, VertexConsumer vc,
        Vec3 center, double range, boolean attracting, float phase, float[] color
    ) {
        double inner = 1.5;
        double span = range - inner;
        if (span < 0.5) {
            return;
        }
        double spacing = 2.0;
        int dashes = Math.max(1, (int) Math.ceil(span / spacing));
        float march = attracting ? 1.0f - phase : phase;

        for (Vec3 dir : SPOKES) {
            for (int k = 0; k < dashes; k++) {
                double s = inner + ((k + march) * spacing) % span;
                double end = Math.min(s + DASH_LENGTH, range);
                Vec3 p1 = center.add(dir.scale(s));
                Vec3 p2 = center.add(dir.scale(end));
                line(poseStack, vc, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, color, 1.0f);
            }
        }
    }

    private static Vec3[] buildSpokes() {
        java.util.List<Vec3> spokes = new java.util.ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    spokes.add(new Vec3(x, y, z).normalize());
                }
            }
        }
        return spokes.toArray(new Vec3[0]);
    }

    private FieldVisualsRenderer() {}
}

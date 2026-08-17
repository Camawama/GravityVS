package net.camacraft.gravityunbound.client;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.Ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.camacraft.gravityunbound.GravityAPI;
import net.camacraft.gravityunbound.util.FieldVisuals;

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
            // rebase all geometry onto the box corner so every vertex is a
            // SMALL number before it becomes a float — grid coordinates on
            // ships are shipyard-scale (~200k), where floats only resolve
            // ~1/60 of a block (visible as jagged, wobbling lines)
            Vec3 origin = new Vec3(field.box().minX, field.box().minY, field.box().minZ);
            applyGridPose(poseStack, cam, field.ship(), origin);
            AABB box = field.box().move(-origin.x, -origin.y, -origin.z);
            float[] color = field.attracting() ? ATTRACT : REPULSE;
            drawBoxOutline(poseStack, lines, box, color);
            drawPlateFlow(poseStack, lines, box, field.flowDir(), field.attracting(), phase, color);
            poseStack.popPose();
        }

        java.util.List<FieldVisuals.CoreField> coreList =
            new java.util.ArrayList<>(FieldVisuals.CORES.values());

        for (int i = 0; i < coreList.size(); i++) {
            FieldVisuals.CoreField field = coreList.get(i);

            // the other cores, with centers expressed in THIS core's grid, so
            // spokes can yield to the combined-field dashes in blend zones
            java.util.List<OtherCore> others = new java.util.ArrayList<>();
            for (int j = 0; j < coreList.size(); j++) {
                if (j == i) {
                    continue;
                }
                FieldVisuals.CoreField other = coreList.get(j);
                others.add(new OtherCore(
                    toGrid(other.center(), other.ship(), field.ship()), other.range()
                ));
            }

            poseStack.pushPose();
            applyGridPose(poseStack, cam, field.ship(), field.center());
            float[] color = field.attracting() ? ATTRACT : REPULSE;
            drawCoreRings(poseStack, lines, Vec3.ZERO, field.range(), color);
            drawCoreSpokes(poseStack, lines, field.center(), field.range(), field.attracting(), phase, color, others);
            poseStack.popPose();
        }

        drawCoreIntersections(poseStack, lines, cam, phase, coreList);

        buffers.endBatch(RenderType.lines());
    }

    private record OtherCore(Vec3 center, double range) {}

    // mirrors GravityCapabilityImpl.resolveGravityTarget: effects blend when
    // their priorities (base - distance) are within this range of each other
    private static final double BLEND_RANGE = 5.0;

    /**
     * Where two core spheres overlap AND their priorities are close enough to
     * blend, sample the zone on a lattice and draw a dash at each sample along
     * the ACTUAL combined gravity vector — the same weighted blend the gravity
     * resolution computes. Dash length scales with the net pull, so mutual
     * cancellation zones fade out naturally (no net gravity, no dash).
     * Cross-grid pairs (ship core over a world core) are re-evaluated every
     * frame in the first core's grid, so the zone tracks moving ships.
     */
    private static void drawCoreIntersections(
        PoseStack poseStack, VertexConsumer lines, Vec3 cam, float phase,
        java.util.List<FieldVisuals.CoreField> coreList
    ) {
        for (int i = 0; i < coreList.size(); i++) {
            for (int j = i + 1; j < coreList.size(); j++) {
                FieldVisuals.CoreField a = coreList.get(i);
                FieldVisuals.CoreField b = coreList.get(j);

                Vec3 bCenter = toGrid(b.center(), b.ship(), a.ship());
                double centerDistance = a.center().distanceTo(bCenter);
                if (centerDistance >= a.range() + b.range()) {
                    continue;
                }

                float[] colorA = a.attracting() ? ATTRACT : REPULSE;
                float[] colorB = b.attracting() ? ATTRACT : REPULSE;
                float[] mix = {
                    Math.min(1.0f, (colorA[0] + colorB[0]) * 0.5f + 0.35f),
                    Math.min(1.0f, (colorA[1] + colorB[1]) * 0.5f + 0.35f),
                    Math.min(1.0f, (colorA[2] + colorB[2]) * 0.5f + 0.35f)
                };

                AABB lens = new AABB(
                    a.center().x - a.range(), a.center().y - a.range(), a.center().z - a.range(),
                    a.center().x + a.range(), a.center().y + a.range(), a.center().z + a.range()
                ).intersect(new AABB(
                    bCenter.x - b.range(), bCenter.y - b.range(), bCenter.z - b.range(),
                    bCenter.x + b.range(), bCenter.y + b.range(), bCenter.z + b.range()
                ));

                // integer lattice stride keeping the sample count bounded
                double volume = lens.getXsize() * lens.getYsize() * lens.getZsize();
                int stride = 1;
                while (volume / ((double) stride * stride * stride) > 250) {
                    stride++;
                }

                poseStack.pushPose();
                Vec3 origin = new Vec3(lens.minX, lens.minY, lens.minZ);
                applyGridPose(poseStack, cam, a.ship(), origin);

                double travel = 0.9;
                double offset = phase * travel - travel * 0.5;

                for (double x = latticeStart(lens.minX, stride); x < lens.maxX; x += stride) {
                    for (double y = latticeStart(lens.minY, stride); y < lens.maxY; y += stride) {
                        for (double z = latticeStart(lens.minZ, stride); z < lens.maxZ; z += stride) {
                            Vec3 p = new Vec3(x, y, z);
                            double d1 = p.distanceTo(a.center());
                            double d2 = p.distanceTo(bCenter);
                            if (d1 > a.range() || d2 > b.range() || d1 < 0.75 || d2 < 0.75) {
                                continue;
                            }
                            if (Math.abs(d1 - d2) > BLEND_RANGE) {
                                continue;   // one core fully dominates here
                            }

                            Vec3 dir1 = a.center().subtract(p).scale((a.attracting() ? 1.0 : -1.0) / d1);
                            Vec3 dir2 = bCenter.subtract(p).scale((b.attracting() ? 1.0 : -1.0) / d2);
                            double nearest = Math.min(d1, d2);
                            double w1 = 1.0 - (d1 - nearest) / BLEND_RANGE;
                            double w2 = 1.0 - (d2 - nearest) / BLEND_RANGE;

                            Vec3 sum = dir1.scale(w1).add(dir2.scale(w2));
                            double length = sum.length();
                            double magnitude = length / (w1 + w2);
                            if (magnitude < 0.12) {
                                continue;   // cancellation: no net pull to show
                            }
                            Vec3 dir = sum.scale(1.0 / length);

                            Vec3 local = p.subtract(origin);
                            Vec3 p1 = local.add(dir.scale(offset));
                            Vec3 p2 = local.add(dir.scale(
                                Math.min(offset + 0.1 + DASH_LENGTH * magnitude, travel * 0.5)
                            ));
                            line(poseStack, lines, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, mix, 1.0f);
                        }
                    }
                }

                poseStack.popPose();
            }
        }
    }

    /** First absolute-grid-aligned lattice point (stride multiples, +0.5) at or after min. */
    private static double latticeStart(double min, int stride) {
        double start = Math.floor(min / stride) * stride + 0.5;
        while (start < min) {
            start += stride;
        }
        return start;
    }

    /** Transforms a grid-local position from one grid into another (per-frame render transforms). */
    private static Vec3 toGrid(Vec3 pos, @Nullable Ship from, @Nullable Ship to) {
        if (from == to) {
            return pos;
        }
        org.joml.Vector3d v = new org.joml.Vector3d(pos.x, pos.y, pos.z);
        if (from != null) {
            (from instanceof ClientShip clientShip
                ? clientShip.getRenderTransform().getShipToWorld()
                : from.getTransform().getShipToWorld()
            ).transformPosition(v);
        }
        if (to != null) {
            (to instanceof ClientShip clientShip
                ? clientShip.getRenderTransform().getWorldToShip()
                : to.getTransform().getWorldToShip()
            ).transformPosition(v);
        }
        return new Vec3(v.x, v.y, v.z);
    }

    /**
     * Sets up the pose so that geometry drawn relative to {@code origin}
     * (grid-local) lands in the right place. The whole chain
     * camera-translation ∘ ship-render-transform ∘ origin-translation is
     * composed in DOUBLES and only the final matrix (whose translation is
     * camera-relative and therefore small) is handed to the float pose stack —
     * shipyard coordinates must never pass through a float.
     */
    private static void applyGridPose(PoseStack poseStack, Vec3 cam, @Nullable Ship ship, Vec3 origin) {
        if (ship == null) {
            poseStack.translate(origin.x - cam.x, origin.y - cam.y, origin.z - cam.z);
            return;
        }

        Matrix4d m = new Matrix4d()
            .translate(-cam.x, -cam.y, -cam.z)
            .mul(ship instanceof ClientShip clientShip
                ? clientShip.getRenderTransform().getShipToWorld()
                : ship.getTransform().getShipToWorld())
            .translate(origin.x, origin.y, origin.z);
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

    /**
     * Dashes marching along the 26 fixed radial spokes, drawn relative to the
     * core (the pose is already rebased onto the core center). Dashes inside
     * another core's BLEND ZONE are suppressed — the combined-field dashes
     * from {@link #drawCoreIntersections} own that region.
     */
    private static void drawCoreSpokes(
        PoseStack poseStack, VertexConsumer vc,
        Vec3 gridCenter, double range, boolean attracting, float phase, float[] color,
        java.util.List<OtherCore> others
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
            spokeDashes:
            for (int k = 0; k < dashes; k++) {
                double s = inner + ((k + march) * spacing) % span;
                double end = Math.min(s + DASH_LENGTH, range);

                Vec3 gridPos = gridCenter.add(dir.scale(s));
                for (OtherCore other : others) {
                    double otherDistance = gridPos.distanceTo(other.center());
                    if (otherDistance <= other.range() && Math.abs(s - otherDistance) < BLEND_RANGE) {
                        continue spokeDashes;
                    }
                }

                Vec3 p1 = dir.scale(s);
                Vec3 p2 = dir.scale(end);
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

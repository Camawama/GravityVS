package net.camacraft.gravityunbound.enchantment;

import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.init.GravityEnchantments;
import net.camacraft.gravityunbound.network.GravityNetwork;
import net.camacraft.gravityunbound.network.SurfaceClingTargetPacket;
import net.camacraft.gravityunbound.util.RotationUtil;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/**
 * SURFACE CLING — an API showcase enchantment for boots.
 *
 * A wearer clings to whatever block surface they stand on: gravity is pulled
 * toward that face, so they can walk up walls, across ceilings and around
 * the outside of any structure — Valkyrien Skies ships included, moving or
 * spinning, because the effect is anchored to the ship the surface belongs
 * to exactly like a ship-mounted gravity plate.
 *
 * Everything here goes through the public Gravity Unbound API:
 * <ul>
 * <li>{@link GravityCapabilityImpl#applyGravityDirectionEffect} — the same
 *     per-tick effect call gravity plating, cores and normalizers make. A
 *     direction, a priority, and (for ship surfaces) the ship plus the
 *     direction in the ship's own coordinates. The mod does the rest:
 *     smooth arbitrary-angle frames, capsule collision, ship riding.</li>
 * <li>{@link GravityCapabilityImpl#getHeldSurfaceNormal()} /
 *     {@link GravityCapabilityImpl#getHeldSurfaceShip()} — the surface the
 *     mod's own ground probe currently holds for the entity.</li>
 * <li>{@link GravityCapabilityImpl#probeSurfaceNormal} and
 *     {@link GravityCapabilityImpl#getInputTangentDirection} — short
 *     raycasts and the player's movement intent, used to ENDORSE the next
 *     face: a wall the wearer pushes into, or the face just around a walked
 *     -off edge. The mod's surface machinery adopts a face only when the
 *     field endorses it, so blending that face into the effect is exactly
 *     how a plate field lets players walk around a cube's edges.</li>
 * </ul>
 *
 * Endorsement needs the player's INPUT, which only the controlling client
 * sees; the client therefore reports its cling target to the server
 * ({@link SurfaceClingTargetPacket}) and the server mirrors it, so both
 * sides agree about the wearer's gravity at every step. Mobs wearing the
 * boots cling to what they stand on and follow their own velocity.
 */
public class SurfaceClingEnchantment extends Enchantment {
    /**
     * Below gravity plating / cores / normalizers (~1000): an engineered
     * field overrides the boots; above per-dimension ambient gravity (100)
     * and potion effects.
     */
    public static final double PRIORITY = 500.0;

    // a client report older than this is ignored and the server falls back
    // to its own (input-less) estimate
    private static final int REPORT_TTL_TICKS = 40;

    public SurfaceClingEnchantment() {
        super(Rarity.RARE, EnchantmentCategory.ARMOR_FEET, new EquipmentSlot[] { EquipmentSlot.FEET });
    }

    @Override
    public int getMinCost(int level) {
        return 20;
    }

    @Override
    public int getMaxCost(int level) {
        return 50;
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    public static boolean isWearing(LivingEntity living) {
        ItemStack boots = living.getItemBySlot(EquipmentSlot.FEET);
        return !boots.isEmpty()
            && EnchantmentHelper.getItemEnchantmentLevel(GravityEnchantments.SURFACE_CLING.get(), boots) > 0;
    }

    /**
     * Per-tick hook, called by the capability while it gathers this tick's
     * gravity effects — on the side that computes the entity's gravity (the
     * controlling client for the local player, the server for everyone).
     */
    public static void applyTo(LivingEntity living, GravityCapabilityImpl comp) {
        if (!isWearing(living)) {
            comp.clingMemoryDown = null;
            comp.clingMemoryShip = null;
            comp.clingMemoryLocalDown = null;
            return;
        }
        if (living instanceof Player player && (player.getAbilities().flying || player.isSpectator())) {
            return;
        }
        if (living.isFallFlying()) {
            return;
        }

        boolean serverSide = !living.level().isClientSide();
        Vec3 down;
        Ship ship = null;
        Vec3 localDown = null;

        if (serverSide && living instanceof Player
            && comp.clingReportedDown != null && comp.clingReportedAge < REPORT_TTL_TICKS) {
            // MIRROR the controlling client's target
            comp.clingReportedAge++;
            ship = comp.clingReportedShipId >= 0 ? findShip(living, comp.clingReportedShipId) : null;
            if (ship != null && comp.clingReportedLocalDown != null) {
                localDown = comp.clingReportedLocalDown;
                down = shipToWorld(ship, localDown);
            }
            else {
                ship = null;
                down = comp.clingReportedDown;
            }
        }
        else {
            Vec3 held = comp.getHeldSurfaceNormal();
            if (held != null) {
                // cling to the stood-on face...
                ship = comp.getHeldSurfaceShip();
                down = held.scale(-1);

                // ...and ENDORSE the next one the wearer is heading for
                Vec3 intent = intentTangent(living, comp, held);
                if (intent != null) {
                    double scale = Mth.clamp(living.getBbHeight() / 1.8, 0.05, 1.0);
                    Vec3 feet = living.position();
                    // concave: a face the wearer pushes into becomes the new floor
                    Vec3 wall = comp.probeSurfaceNormal(feet.add(held.scale(0.2 * scale)), intent, 0.6 * scale);
                    if (wall != null && wall.dot(held) < 0.7 && wall.dot(intent) < -0.5) {
                        down = down.add(wall.scale(-1));
                    }
                    // convex: feet past an edge — the face around the corner
                    else if (comp.probeSurfaceNormal(feet.add(held.scale(0.2 * scale)), held.scale(-1), 0.8 * scale) == null) {
                        Vec3 wrap = comp.probeSurfaceNormal(feet.subtract(held.scale(0.15 * scale)), intent.scale(-1), 0.75 * scale);
                        if (wrap != null && wrap.dot(held) < 0.7 && wrap.dot(intent) > 0.1) {
                            down = down.add(wrap.scale(-1));
                        }
                    }
                }
                down = down.normalize();
            }
            else if (comp.clingMemoryDown != null) {
                // airborne with nothing held: keep pulling toward the surface
                // that was left (jumps land back on it, ships keep carrying)
                ship = comp.clingMemoryShip;
                localDown = comp.clingMemoryLocalDown;
                down = ship != null && localDown != null ? shipToWorld(ship, localDown) : comp.clingMemoryDown;
            }
            else {
                // first tick with the boots on: seed with the current pull so
                // the ground probe engages and finds the floor
                down = comp.getEffectiveUpVector().scale(-1);
            }
            if (ship != null && localDown == null) {
                localDown = worldToShip(ship, down);
            }
        }

        comp.clingMemoryDown = down;
        comp.clingMemoryShip = ship;
        comp.clingMemoryLocalDown = localDown;

        // THE API CALL: one effect per tick. Ship-anchored when the surface
        // belongs to a ship, so the wearer rides it like a plated deck.
        comp.applyGravityDirectionEffect(down, null, PRIORITY, false, 1.0, true, ship, localDown, null);

        if (!serverSide && living instanceof Player && living.isControlledByLocalInstance()) {
            report(comp, down, ship, localDown);
        }
    }

    /**
     * Where the wearer is trying to go, tangential to the held face: the
     * movement input for the controlling client, the packet-driven position
     * delta for a server-side player, the entity's own velocity for mobs.
     */
    private static @Nullable Vec3 intentTangent(LivingEntity living, GravityCapabilityImpl comp, Vec3 held) {
        Vec3 input = comp.getInputTangentDirection(held);
        if (input != null) {
            return input;
        }
        Vec3 motion = living instanceof Player
            ? new Vec3(living.getX() - living.xo, living.getY() - living.yo, living.getZ() - living.zo)
            : RotationUtil.vecPlayerToWorld(living.getDeltaMovement(), comp.getVisualRotation());
        Vec3 tangent = motion.subtract(held.scale(motion.dot(held)));
        return tangent.lengthSqr() > 1.0E-4 ? tangent.normalize() : null;
    }

    private static void report(GravityCapabilityImpl comp, Vec3 down, @Nullable Ship ship, @Nullable Vec3 localDown) {
        long shipId = ship != null ? ship.getId() : -1L;
        Vec3 key = ship != null && localDown != null ? localDown : down;
        if (comp.clingLastSentDown != null && comp.clingLastSentShipId == shipId
            && comp.clingLastSentDown.dot(key) > 0.9998) {
            return; // unchanged within ~1 degree
        }
        comp.clingLastSentDown = key;
        comp.clingLastSentShipId = shipId;
        GravityNetwork.sendToServer(new SurfaceClingTargetPacket(down, shipId, ship != null ? localDown : null));
    }

    private static @Nullable Ship findShip(LivingEntity living, long shipId) {
        Object ship = VSGameUtilsKt.getShipObjectWorld(living.level()).getAllShips().getById(shipId);
        return ship instanceof Ship found ? found : null;
    }

    public static Vec3 shipToWorld(Ship ship, Vec3 localDir) {
        org.joml.Vector3d v = new org.joml.Vector3d(localDir.x, localDir.y, localDir.z);
        ship.getTransform().getShipToWorldMatrix().transformDirection(v);
        v.normalize();
        return new Vec3(v.x, v.y, v.z);
    }

    public static Vec3 worldToShip(Ship ship, Vec3 worldDir) {
        org.joml.Vector3d v = new org.joml.Vector3d(worldDir.x, worldDir.y, worldDir.z);
        ship.getTransform().getWorldToShipMatrix().transformDirection(v);
        v.normalize();
        return new Vec3(v.x, v.y, v.z);
    }
}

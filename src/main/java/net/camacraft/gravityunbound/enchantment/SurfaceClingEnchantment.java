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
 * A wearer clings to whatever block surface they touch: gravity is pulled
 * toward that face, so they can walk up walls, across ceilings and around
 * the outside of any structure — Valkyrien Skies ships included, moving or
 * spinning, because the effect is anchored to the ship the surface belongs
 * to exactly like a ship-mounted gravity plate. With NO surface in reach
 * the boots do nothing: ambient gravity applies, and a zero-g dimension
 * stays zero-g. Jumping lets go — the wearer leaves the surface under
 * ambient gravity (or floats away) and clings again on the next touch.
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
 * <li>{@link GravityCapabilityImpl#probeSurface} and
 *     {@link GravityCapabilityImpl#getInputTangentDirection} — short
 *     raycasts and the player's movement intent, used to find the surface
 *     underfoot and to ENDORSE the next face: a wall the wearer pushes
 *     into, or the face just around a walked-off edge. The mod's surface
 *     machinery adopts a face only when the field endorses it, so blending
 *     that face into the effect is exactly how a plate field lets players
 *     walk around a cube's edges.</li>
 * <li>{@link GravityCapabilityImpl#releaseFieldGraceNow()} — the jump
 *     release: fields normally keep pulling through a jump's grace window;
 *     a wearer letting go must simply fall.</li>
 * </ul>
 *
 * Endorsement and the jump need the player's INPUT and velocity, which only
 * the controlling client sees; the client therefore reports its cling state
 * to the server ({@link SurfaceClingTargetPacket}) and the server mirrors
 * it, so both sides agree about the wearer's gravity at every step. Mobs
 * wearing the boots cling to what they stand on and follow their own
 * velocity.
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
    // after a jump, the boots stay off this long (the wearer clears the
    // surface before anything can re-cling them to it)
    private static final int RELEASE_TICKS = 10;
    // outward speed along the held face that counts as a jump (a vanilla
    // jump leaves ~0.33 after its first tick of gravity)
    private static final double JUMP_RELEASE_SPEED = 0.25;

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
            comp.clingReleaseTicks = 0;
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

        if (serverSide && living instanceof Player && comp.clingReportedAge < REPORT_TTL_TICKS) {
            // MIRROR the controlling client's state
            comp.clingReportedAge++;
            if (comp.clingReportedReleased) {
                comp.clingReportedReleased = false;
                comp.releaseFieldGraceNow();
            }
            if (!comp.clingReportedActive || comp.clingReportedDown == null) {
                return;
            }
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
            boolean controlling = !serverSide && living instanceof Player && living.isControlledByLocalInstance();

            if (comp.clingReleaseTicks > 0) {
                comp.clingReleaseTicks--;
                if (controlling) {
                    report(comp, false, false, null, null, null);
                }
                return;
            }

            double scale = Mth.clamp(living.getBbHeight() / 1.8, 0.05, 1.0);
            Vec3 feet = living.position();
            Vec3 held = comp.getHeldSurfaceNormal();

            // JUMPING = LETTING GO. Moving away from the held face at jump
            // speed drops the field on the spot (no grace pull-back) and
            // keeps the boots off until the wearer is clear of the surface.
            if (held != null) {
                Vec3 worldVelocity = RotationUtil.vecPlayerToWorld(living.getDeltaMovement(), comp.getVisualRotation());
                if (worldVelocity.dot(held) > JUMP_RELEASE_SPEED * scale) {
                    comp.clingReleaseTicks = RELEASE_TICKS;
                    comp.releaseFieldGraceNow();
                    if (controlling) {
                        report(comp, false, true, null, null, null);
                    }
                    return;
                }
            }

            Vec3 refUp = held != null ? held : comp.getUpVector();
            Vec3 sum = Vec3.ZERO;

            // the surface underfoot (the held face, or whatever the feet are
            // on when nothing is held yet) — nothing there, nothing to cling to
            GravityCapabilityImpl.SurfaceHit ground =
                comp.probeSurface(feet.add(refUp.scale(0.2 * scale)), refUp.scale(-1), 0.8 * scale);
            if (held != null) {
                sum = held.scale(-1);
                ship = comp.getHeldSurfaceShip();
            }
            else if (ground != null) {
                sum = ground.normal().scale(-1);
                ship = ground.ship();
            }

            // ENDORSE the next face the wearer is heading for
            Vec3 intent = intentTangent(living, comp, refUp);
            if (intent != null) {
                // concave: a face the wearer pushes into becomes the new floor
                GravityCapabilityImpl.SurfaceHit wall =
                    comp.probeSurface(feet.add(refUp.scale(0.2 * scale)), intent, 0.6 * scale);
                if (wall != null && wall.normal().dot(refUp) < 0.7 && wall.normal().dot(intent) < -0.5) {
                    sum = sum.add(wall.normal().scale(-1));
                    if (ship == null) {
                        ship = wall.ship();
                    }
                }
                // convex: feet past an edge — the face around the corner
                else if (held != null && ground == null) {
                    GravityCapabilityImpl.SurfaceHit wrap =
                        comp.probeSurface(feet.subtract(held.scale(0.15 * scale)), intent.scale(-1), 0.75 * scale);
                    if (wrap != null && wrap.normal().dot(held) < 0.7 && wrap.normal().dot(intent) > 0.1) {
                        sum = sum.add(wrap.normal().scale(-1));
                        if (ship == null) {
                            ship = wrap.ship();
                        }
                    }
                }
            }

            if (sum.lengthSqr() < 1.0E-6) {
                // free of every surface: ambient gravity (zero-g stays zero-g)
                if (controlling) {
                    report(comp, false, false, null, null, null);
                }
                return;
            }
            down = sum.normalize();
            if (ship != null) {
                localDown = worldToShip(ship, down);
            }
            if (controlling) {
                report(comp, true, false, down, ship, localDown);
            }
        }

        // THE API CALL: one effect per tick. Ship-anchored when the surface
        // belongs to a ship, so the wearer rides it like a plated deck.
        comp.applyGravityDirectionEffect(down, null, PRIORITY, false, 1.0, true, ship, localDown, null);
    }

    /**
     * Where the wearer is trying to go, tangential to the reference face:
     * the movement input for the controlling client, the packet-driven
     * position delta for a server-side player, the entity's own velocity
     * for mobs.
     */
    private static @Nullable Vec3 intentTangent(LivingEntity living, GravityCapabilityImpl comp, Vec3 refUp) {
        Vec3 input = comp.getInputTangentDirection(refUp);
        if (input != null) {
            return input;
        }
        Vec3 motion = living instanceof Player
            ? new Vec3(living.getX() - living.xo, living.getY() - living.yo, living.getZ() - living.zo)
            : RotationUtil.vecPlayerToWorld(living.getDeltaMovement(), comp.getVisualRotation());
        Vec3 tangent = motion.subtract(refUp.scale(motion.dot(refUp)));
        return tangent.lengthSqr() > 1.0E-4 ? tangent.normalize() : null;
    }

    private static void report(GravityCapabilityImpl comp, boolean active, boolean released,
                               @Nullable Vec3 down, @Nullable Ship ship, @Nullable Vec3 localDown) {
        if (!active) {
            if (comp.clingLastSentActive || released) {
                comp.clingLastSentActive = false;
                comp.clingLastSentDown = null;
                comp.clingLastSentShipId = -1L;
                GravityNetwork.sendToServer(new SurfaceClingTargetPacket(false, released, Vec3.ZERO, -1L, null));
            }
            return;
        }
        long shipId = ship != null ? ship.getId() : -1L;
        Vec3 key = ship != null && localDown != null ? localDown : down;
        if (comp.clingLastSentActive && comp.clingLastSentDown != null && comp.clingLastSentShipId == shipId
            && comp.clingLastSentDown.dot(key) > 0.9998) {
            return; // unchanged within ~1 degree
        }
        comp.clingLastSentActive = true;
        comp.clingLastSentDown = key;
        comp.clingLastSentShipId = shipId;
        GravityNetwork.sendToServer(new SurfaceClingTargetPacket(true, false, down, shipId, ship != null ? localDown : null));
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

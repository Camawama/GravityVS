package net.camacraft.gravityunbound.network;

import java.util.function.Supplier;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.jetbrains.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: the controlling client's current Surface Cling state (see
 * {@code enchantment.SurfaceClingEnchantment}). Finding and endorsing faces
 * depends on movement input the server cannot see, and letting go depends
 * on the jump the client performs, so the server mirrors the client's state
 * instead of estimating its own — the two sides then agree about the
 * wearer's gravity at every step. For a surface on a Valkyrien Skies ship
 * the direction travels in the ship's own coordinates, so it stays valid
 * while the ship rotates. Sent only when the state changes.
 */
public class SurfaceClingTargetPacket {
    private final boolean active;
    private final boolean released;
    private final Vec3 down;
    private final long shipId;
    private final @Nullable Vec3 localDown;

    public SurfaceClingTargetPacket(boolean active, boolean released, Vec3 down, long shipId, @Nullable Vec3 localDown) {
        this.active = active;
        this.released = released;
        this.down = down;
        this.shipId = shipId;
        this.localDown = localDown;
    }

    public SurfaceClingTargetPacket(FriendlyByteBuf buf) {
        this.active = buf.readBoolean();
        this.released = buf.readBoolean();
        this.down = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        this.shipId = buf.readLong();
        if (buf.readBoolean()) {
            this.localDown = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        else {
            this.localDown = null;
        }
    }

    public static void encode(SurfaceClingTargetPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.active);
        buf.writeBoolean(msg.released);
        buf.writeDouble(msg.down.x);
        buf.writeDouble(msg.down.y);
        buf.writeDouble(msg.down.z);
        buf.writeLong(msg.shipId);
        buf.writeBoolean(msg.localDown != null);
        if (msg.localDown != null) {
            buf.writeDouble(msg.localDown.x);
            buf.writeDouble(msg.localDown.y);
            buf.writeDouble(msg.localDown.z);
        }
    }

    private static boolean isUnit(@Nullable Vec3 v) {
        if (v == null) {
            return false;
        }
        double len = v.lengthSqr();
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z)
            && len > 1.0E-6 && len < 4.0;
    }

    public static class Handler {
        public static void onMessage(SurfaceClingTargetPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null) {
                    return;
                }
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(sender);
                if (comp == null) {
                    return;
                }
                // untrusted input: only unit-ish directions are accepted;
                // the direction itself is the player's own gravity, nothing
                // a client could not already choose by walking
                boolean active = msg.active && isUnit(msg.down);
                comp.clingReportedActive = active;
                comp.clingReportedReleased = comp.clingReportedReleased || msg.released;
                comp.clingReportedDown = active ? msg.down.normalize() : null;
                comp.clingReportedShipId = active ? msg.shipId : -1L;
                comp.clingReportedLocalDown = active && isUnit(msg.localDown) ? msg.localDown.normalize() : null;
                comp.clingReportedAge = 0;
            });
            ctx.get().setPacketHandled(true);
        }
    }
}

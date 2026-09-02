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
 * C2S: the controlling client's current Surface Cling target (see
 * {@code enchantment.SurfaceClingEnchantment}). The endorsement of the next
 * face depends on movement input the server cannot see, so the server
 * mirrors the client's direction instead of estimating its own — the two
 * sides then agree about the wearer's gravity at every step of a wall
 * climb. For a surface on a Valkyrien Skies ship the direction travels in
 * the ship's own coordinates, so it stays valid while the ship rotates.
 */
public class SurfaceClingTargetPacket {
    private final Vec3 down;
    private final long shipId;
    private final @Nullable Vec3 localDown;

    public SurfaceClingTargetPacket(Vec3 down, long shipId, @Nullable Vec3 localDown) {
        this.down = down;
        this.shipId = shipId;
        this.localDown = localDown;
    }

    public SurfaceClingTargetPacket(FriendlyByteBuf buf) {
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
                if (sender == null || !isUnit(msg.down)) {
                    return;
                }
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(sender);
                if (comp == null) {
                    return;
                }
                // untrusted input: only unit-ish directions are accepted;
                // the direction itself is the player's own gravity, nothing
                // a client could not already choose by walking
                comp.clingReportedDown = msg.down.normalize();
                comp.clingReportedShipId = msg.shipId;
                comp.clingReportedLocalDown = isUnit(msg.localDown) ? msg.localDown.normalize() : null;
                comp.clingReportedAge = 0;
            });
            ctx.get().setPacketHandled(true);
        }
    }
}

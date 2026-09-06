package net.camacraft.gravityunbound.network;

import java.util.function.Supplier;

import net.camacraft.gravityunbound.api.GravityChangerAPI;
import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import org.joml.Quaternionf;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: the controlling client's current visual frame (world -> player
 * rotation). The client computes the player's gravity from fields and its
 * frame is the one the camera, the capsule and the movement packets are
 * expressed in; the server's own chase of the same fields can sit a snap or
 * a surface hold behind it. Every server-side computation that depends on
 * the player's frame — the look vector and eye position that server-side
 * rays are cast from (VMod's physgun, mob targeting), the movement
 * convention of velocity packets, FullStop's impact measurement — now uses
 * the reported frame instead (see {@code advanceVisualRotation}). Sent when
 * the frame turns by more than half a degree, with a keep-alive while it is
 * not the vanilla frame; a stale report (2 s) lets the server's own chase
 * take over again.
 */
public class PlayerFrameSyncPacket {
    private final Quaternionf frame;

    public PlayerFrameSyncPacket(Quaternionf frame) {
        this.frame = frame;
    }

    public PlayerFrameSyncPacket(FriendlyByteBuf buf) {
        this.frame = new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void encode(PlayerFrameSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.frame.x());
        buf.writeFloat(msg.frame.y());
        buf.writeFloat(msg.frame.z());
        buf.writeFloat(msg.frame.w());
    }

    public static class Handler {
        public static void onMessage(PlayerFrameSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null) {
                    return;
                }
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponentOrNull(sender);
                if (comp == null) {
                    return;
                }
                Quaternionf q = msg.frame;
                // untrusted input: a finite, unit-ish rotation or nothing —
                // the frame is the player's own orientation, nothing a client
                // could not already reach by walking into a field
                float len = q.lengthSquared();
                if (!Float.isFinite(len) || len < 0.9f || len > 1.1f) {
                    return;
                }
                if (comp.clientReportedFrame == null) {
                    comp.clientReportedFrame = new Quaternionf(q).normalize();
                }
                else {
                    comp.clientReportedFrame.set(q).normalize();
                }
                comp.clientFrameAge = 0;
            });
            ctx.get().setPacketHandled(true);
        }
    }
}

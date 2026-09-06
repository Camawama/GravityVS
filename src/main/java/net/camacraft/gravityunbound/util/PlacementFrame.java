package net.camacraft.gravityunbound.util;

import java.util.Optional;

import net.camacraft.gravityunbound.api.GravityChangerAPI;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.Vec3;

/**
 * Which HALF of a block a placement means, judged in the placing player's
 * gravity frame.
 *
 * Vanilla decides stairs, slabs and trapdoors by the clicked face and by
 * whether the click landed above the block's middle — in WORLD Y. For a
 * player whose up is not world up, "the upper half" is somewhere else: on a
 * ceiling walker's floor, vanilla's "bottom" is their top, so every stair
 * came out inverted and slabs landed on the wrong half. The half a stair or
 * a slab can take is world-vertical only, so the frame's answer is mapped
 * back whenever the player's up runs mostly along world Y (upright or
 * inverted, including the tilted frames between); a wall-walker's frame has
 * no half to map onto, and vanilla's decision stands.
 */
public final class PlacementFrame {

    private PlacementFrame() {
    }

    private record Frame(Vec3 up, boolean inverted, double faceUp, double alongUp) {
    }

    private static Optional<Frame> frameOf(BlockPlaceContext context) {
        Player player = context.getPlayer();
        if (player == null || GravityChangerAPI.isAimDefault(player)) {
            return Optional.empty();
        }
        Vec3 up = GravityChangerAPI.getUpVector(player);
        if (Math.abs(up.y) < 0.5) {
            return Optional.empty(); // a wall-walker: no world half means "theirs"
        }
        Direction face = context.getClickedFace();
        double faceUp = face.getStepX() * up.x + face.getStepY() * up.y + face.getStepZ() * up.z;
        Vec3 center = Vec3.atCenterOf(context.getClickedPos());
        double alongUp = context.getClickLocation().subtract(center).dot(up);
        return Optional.of(new Frame(up, up.y < 0, faceUp, alongUp));
    }

    /**
     * Stairs and slabs: vanilla places the BOTTOM half unless the click was
     * on the underside, or on a side above the middle. The same rule in the
     * player's frame, then mapped onto the world half; empty when vanilla's
     * decision stands.
     */
    public static Optional<Boolean> bottomHalf(BlockPlaceContext context) {
        return frameOf(context).map(frame -> {
            // vanilla: face != DOWN && (face == UP || !(clickY > middle))
            boolean localBottom = frame.faceUp() > 0.5
                || (frame.faceUp() > -0.5 && frame.alongUp() <= 0.0);
            return frame.inverted() ? !localBottom : localBottom;
        });
    }

    /**
     * Trapdoors: on a wall face the click height decides, on a floor/ceiling
     * face the face itself does (vanilla's two branches), in the player's
     * frame; empty when vanilla's decision stands.
     */
    public static Optional<Boolean> trapdoorTopHalf(BlockPlaceContext context) {
        return frameOf(context).map(frame -> {
            boolean wallFace = Math.abs(frame.faceUp()) <= 0.5;
            boolean localTop = !context.replacingClickedOnBlock() && wallFace
                ? frame.alongUp() > 0.0
                : !(frame.faceUp() > 0.5);
            return frame.inverted() ? !localTop : localTop;
        });
    }
}

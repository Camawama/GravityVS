package net.cama.gravityapivs.api;

import net.cama.gravityapivs.config.GravityConfig;

import net.minecraft.nbt.CompoundTag;

public record RotationParameters(
    boolean rotateVelocity,
    boolean rotateView, // currently ignores this
    int rotationTimeMS
) {
    public static RotationParameters defaultParam = new RotationParameters(
        true, true, 500
    );
    
    public static void updateDefault() {
        defaultParam = new RotationParameters(
            !GravityConfig.worldVelocity.get(),
            true,
            GravityConfig.rotationTime.get()
        );
    }
    
    public static RotationParameters getDefault() {
        return defaultParam;
    }
    
    public RotationParameters withRotationTimeMs(int rotationTimeMS) {
        return new RotationParameters(
            rotateVelocity,
            rotateView,
            rotationTimeMS
        );
    }
    
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("RotateVelocity", rotateVelocity);
        tag.putBoolean("RotateView", rotateView);
        tag.putInt("RotationTimeMS", rotationTimeMS);
        return tag;
    }
    
    public static RotationParameters fromTag(CompoundTag tag) {
        return new RotationParameters(
            tag.getBoolean("RotateVelocity"),
            tag.getBoolean("RotateView"),
            tag.getInt("RotationTimeMS")
        );
    }
}

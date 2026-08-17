package net.camacraft.gravityunbound.client.gui;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.camacraft.gravityunbound.capabilities.GravityCapabilityImpl;
import net.camacraft.gravityunbound.config.GravityConfig;
import net.camacraft.gravityunbound.core.GravityCoreBlockEntity;
import net.camacraft.gravityunbound.network.GravityNetwork;
import net.camacraft.gravityunbound.network.UpdateGravityBlockSettingsPacket;
import net.camacraft.gravityunbound.network.UpdateGravityBlockSettingsPacket.TargetType;
import net.camacraft.gravityunbound.normalizer.GravityNormalizerBlockEntity;
import net.camacraft.gravityunbound.plating.GravityPlatingBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Settings dialog for the three gravity field blocks (plating side, core,
 * normalizer). No container/menu involved — like the sign edit screen it is a
 * pure client dialog; Done sends {@link UpdateGravityBlockSettingsPacket} to
 * the server, Cancel just closes. Initial values come from the CLIENT-side
 * block entity at the position (which carries authoritative data via the
 * update tag).
 */
public class GravityBlockSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;

    private final TargetType type;
    private final BlockPos pos;
    @Nullable
    private final Direction plateSide;

    // working values (kept on the screen so a window resize, which rebuilds
    // the widgets, does not lose the user's edits)
    private boolean attracting = true;
    private int rangeValue = 1;
    private boolean gradualFalloff = false;
    private boolean surfaceSnap = true;
    private boolean affectsShips = true;
    private boolean showParticles = false;
    private boolean applyToConnected = false;
    private Direction localDown = Direction.DOWN;
    private double initialAccel = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
    private String accelText;

    @Nullable
    private EditBox accelBox;
    private int accelLabelX;
    private int accelLabelY;

    public GravityBlockSettingsScreen(TargetType type, BlockPos pos, @Nullable Direction plateSide) {
        super(titleFor(pos));
        this.type = type;
        this.pos = pos;
        this.plateSide = plateSide;
        readInitialValues();
        this.accelText = String.format(Locale.ROOT, "%.4f", initialAccel);
    }

    private static Component titleFor(BlockPos pos) {
        Level level = Minecraft.getInstance().level;
        return level != null ? level.getBlockState(pos).getBlock().getName() : Component.empty();
    }

    private void readInitialValues() {
        switch (type) {
            case PLATING -> rangeValue = 1;
            case CORE -> rangeValue = GravityConfig.gravityCoreDefaultRange.get();
            case NORMALIZER -> rangeValue = GravityConfig.normalizerDefaultRange.get();
        }

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);

        switch (type) {
            case PLATING -> {
                if (blockEntity instanceof GravityPlatingBlockEntity be && plateSide != null) {
                    GravityPlatingBlockEntity.SideData side = be.getSideData(plateSide);
                    if (side != null) {
                        attracting = side.isAttracting;
                        rangeValue = side.level;
                        gradualFalloff = side.gradualFalloff;
                        surfaceSnap = side.surfaceSnap;
                        showParticles = side.showParticles;
                        initialAccel = side.gravityAccel;
                    }
                }
            }
            case CORE -> {
                if (blockEntity instanceof GravityCoreBlockEntity be) {
                    attracting = be.isAttracting();
                    rangeValue = be.getRange();
                    gradualFalloff = be.isGradualFalloff();
                    surfaceSnap = be.isSurfaceSnap();
                    affectsShips = be.isAffectsShips();
                    showParticles = be.isShowParticles();
                    initialAccel = be.getGravityAccel();
                }
            }
            case NORMALIZER -> {
                if (blockEntity instanceof GravityNormalizerBlockEntity be) {
                    localDown = be.getLocalDown();
                    rangeValue = be.getRange();
                    showParticles = be.isShowParticles();
                    initialAccel = be.getGravityAccel();
                }
            }
        }
    }

    @Override
    protected void init() {
        int rows = switch (type) {
            case PLATING -> 8;
            case CORE -> 7;
            case NORMALIZER -> 5;
        };
        int x = this.width / 2 - WIDGET_WIDTH / 2;
        int y = Math.max(32, (this.height - rows * ROW_HEIGHT) / 2);

        if (type == TargetType.NORMALIZER) {
            addRenderableWidget(CycleButton.<Direction>builder(
                    dir -> Component.translatable("direction." + dir.getName()))
                .withValues(Direction.values())
                .withInitialValue(localDown)
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.local_down"),
                    (button, value) -> localDown = value));
        }
        else {
            addRenderableWidget(CycleButton.<Boolean>builder(
                    value -> Component.translatable(value
                        ? "gravity_changer.plate.force.attract"
                        : "gravity_changer.plate.force.repulse"))
                .withValues(Boolean.TRUE, Boolean.FALSE)
                .withInitialValue(attracting)
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.force"),
                    (button, value) -> attracting = value));
        }
        y += ROW_HEIGHT;

        String sliderKey = switch (type) {
            case PLATING -> "gravity_changer.gui.level";
            case CORE -> "gravity_changer.gui.range";
            case NORMALIZER -> "gravity_changer.gui.zone_size";
        };
        int maxRange = switch (type) {
            case PLATING -> GravityConfig.platingMaxLevel.get();
            case CORE -> GravityConfig.gravityCoreMaxRange.get();
            case NORMALIZER -> GravityConfig.normalizerMaxRange.get();
        };
        rangeValue = Mth.clamp(rangeValue, 1, maxRange);
        addRenderableWidget(new IntSlider(x, y, sliderKey, 1, maxRange, rangeValue));
        y += ROW_HEIGHT;

        if (type != TargetType.NORMALIZER) {
            addRenderableWidget(CycleButton.<Boolean>builder(
                    value -> Component.translatable(value
                        ? "gravity_changer.gui.falloff.gradual"
                        : "gravity_changer.gui.falloff.full"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(gradualFalloff)
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.falloff"),
                    (button, value) -> gradualFalloff = value));
            y += ROW_HEIGHT;

            addRenderableWidget(CycleButton.onOffBuilder(surfaceSnap)
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.surface_snap"),
                    (button, value) -> surfaceSnap = value));
            y += ROW_HEIGHT;
        }

        if (type == UpdateGravityBlockSettingsPacket.TargetType.CORE) {
            addRenderableWidget(CycleButton.onOffBuilder(affectsShips)
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.affects_ships"),
                    (button, value) -> affectsShips = value));
            y += ROW_HEIGHT;
        }

        // gravity acceleration: label on the left, numeric edit box on the right
        accelLabelX = x;
        accelLabelY = y + (WIDGET_HEIGHT - 8) / 2;
        EditBox box = new EditBox(this.font, x + 110, y, WIDGET_WIDTH - 110, WIDGET_HEIGHT,
            Component.translatable("gravity_changer.gui.gravity_accel"));
        box.setValue(accelText);
        box.setFilter(s -> s.isEmpty() || s.matches("[0-9]*\\.?[0-9]*"));
        box.setResponder(s -> accelText = s);
        accelBox = box;
        addRenderableWidget(box);
        y += ROW_HEIGHT;

        addRenderableWidget(CycleButton.onOffBuilder(showParticles)
            .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                Component.translatable("gravity_changer.gui.field_visual"),
                (button, value) -> showParticles = value));
        y += ROW_HEIGHT;

        if (type == TargetType.PLATING) {
            addRenderableWidget(CycleButton.onOffBuilder(applyToConnected)
                .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.apply_connected"),
                    (button, value) -> applyToConnected = value));
            y += ROW_HEIGHT;
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> sendAndClose())
            .bounds(x, y, WIDGET_WIDTH / 2 - 2, WIDGET_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
            .bounds(x + WIDGET_WIDTH / 2 + 2, y, WIDGET_WIDTH / 2 - 2, WIDGET_HEIGHT).build());
    }

    private void sendAndClose() {
        double accel = parseAccel();
        CompoundTag tag = new CompoundTag();
        switch (type) {
            case PLATING -> {
                tag.putString("side", (plateSide != null ? plateSide : Direction.DOWN).getName());
                tag.putInt("level", rangeValue);
                tag.putBoolean("isAttracting", attracting);
                tag.putBoolean("gradualFalloff", gradualFalloff);
                tag.putDouble("gravityAccel", accel);
                tag.putBoolean("surfaceSnap", surfaceSnap);
                tag.putBoolean("showParticles", showParticles);
                tag.putBoolean("applyToConnected", applyToConnected);
            }
            case CORE -> {
                tag.putInt("range", rangeValue);
                tag.putBoolean("attracting", attracting);
                tag.putBoolean("gradualFalloff", gradualFalloff);
                tag.putDouble("gravityAccel", accel);
                tag.putBoolean("surfaceSnap", surfaceSnap);
                tag.putBoolean("showParticles", showParticles);
                tag.putBoolean("affectsShips", affectsShips);
            }
            case NORMALIZER -> {
                tag.putString("localDown", localDown.getName());
                tag.putInt("range", rangeValue);
                tag.putDouble("gravityAccel", accel);
                tag.putBoolean("showParticles", showParticles);
            }
        }
        GravityNetwork.sendToServer(new UpdateGravityBlockSettingsPacket(pos, type, tag));
        onClose();
    }

    /** Invalid or empty input keeps the block's previous acceleration. */
    private double parseAccel() {
        try {
            double value = Double.parseDouble(accelText.trim());
            if (!Double.isFinite(value)) {
                return initialAccel;
            }
            return Mth.clamp(value, 0.0, 1.0);
        }
        catch (NumberFormatException e) {
            return initialAccel;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (accelBox != null) {
            accelBox.tick();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        graphics.drawString(this.font,
            Component.translatable("gravity_changer.gui.gravity_accel"),
            accelLabelX, accelLabelY, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Integer slider writing into {@link #rangeValue}. */
    private class IntSlider extends AbstractSliderButton {
        private final String labelKey;
        private final int min;
        private final int max;

        IntSlider(int x, int y, String labelKey, int min, int max, int initial) {
            super(x, y, WIDGET_WIDTH, WIDGET_HEIGHT, Component.empty(),
                max <= min ? 0.0 : (initial - min) / (double) (max - min));
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(labelKey, rangeValue));
        }

        @Override
        protected void applyValue() {
            rangeValue = Mth.clamp(min + (int) Math.round(value * (max - min)), min, max);
        }
    }
}

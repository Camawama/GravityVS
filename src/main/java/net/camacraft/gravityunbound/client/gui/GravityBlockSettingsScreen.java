package net.camacraft.gravityunbound.client.gui;

import java.util.ArrayList;
import java.util.List;
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
import net.camacraft.gravityunbound.util.FieldTargets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
 * pure client dialog; Apply sends {@link UpdateGravityBlockSettingsPacket} to
 * the server, Cancel just closes. Initial values come from the CLIENT-side
 * block entity at the position (which carries authoritative data via the
 * update tag).
 *
 * Layout: widgets are grouped into labeled SECTIONS (small gray header +
 * separator line) — "Field" (direction/force, size, falloff), "Gravity"
 * (acceleration + presets, surface snap), "Visuals", "Affects" (everything
 * the field acts on: players, mobs, objects, particles, fluids and — for
 * plating and cores — ships; green = on, red = off) — with the action row
 * at the bottom. "Copy to Connected Plates" (plating
 * only) applies the on-screen values to the whole connected plate group
 * IMMEDIATELY without closing, and briefly shows "Copied!".
 */
public class GravityBlockSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_WIDTH = 260;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SECTION_GAP = 14;
    private static final int HALF_WIDTH = WIDGET_WIDTH / 2 - 2;
    private static final int FEEDBACK_TICKS = 40;

    /** Accel presets: {lang key, value}. Row of four, 62px each. */
    private static final Object[][] ACCEL_PRESETS = {
        {"gravity_changer.gui.preset.overworld", 0.08},
        {"gravity_changer.gui.preset.moon", 0.0133},
        {"gravity_changer.gui.preset.zero_g", 0.0},
        {"gravity_changer.gui.preset.jupiter", 0.2},
    };

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
    private int targets = FieldTargets.ALL;
    private boolean replacesGravity = true;
    private Direction localDown = Direction.DOWN;
    private double initialAccel = GravityCapabilityImpl.BASE_GRAVITY_ACCEL;
    private String accelText;

    @Nullable
    private EditBox accelBox;
    private int accelLabelX;
    private int accelLabelY;

    // section headers drawn in render(); rebuilt on every init (resize-safe)
    private record SectionHeader(Component label, int y) {}
    private final List<SectionHeader> sectionHeaders = new ArrayList<>();
    private int actionSeparatorY = -1;

    // "Copied!" feedback countdown for the copy-to-connected button
    private int connectedFeedbackTicks = 0;
    @Nullable
    private Button connectedButton;

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
                        affectsShips = side.affectsShips;
                        targets = side.targets;
                        replacesGravity = side.replacesGravity;
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
                    targets = be.getTargets();
                    replacesGravity = be.isReplacesGravity();
                    initialAccel = be.getGravityAccel();
                }
            }
            case NORMALIZER -> {
                if (blockEntity instanceof GravityNormalizerBlockEntity be) {
                    localDown = be.getLocalDown();
                    rangeValue = be.getRange();
                    showParticles = be.isShowParticles();
                    targets = be.getTargets();
                    initialAccel = be.getGravityAccel();
                }
            }
        }
    }

    @Override
    protected void init() {
        sectionHeaders.clear();
        connectedButton = null;

        // rows + section gaps per variant, vertically centered
        int rows = switch (type) {
            case PLATING -> 9;   // force|falloff, slider | accel, presets, snap | ships|visuals | targets x2 | actions
            case CORE -> 9;
            case NORMALIZER -> 8;
        };
        int sections = 4;
        int total = rows * ROW_HEIGHT + sections * SECTION_GAP;
        int x = this.width / 2 - WIDGET_WIDTH / 2;
        int y = Math.max(28, (this.height - total) / 2);

        // ---- Field section ----
        y = sectionHeader("gravity_changer.gui.section.field", y);

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
            // Force | Falloff share one row
            addRenderableWidget(CycleButton.<Boolean>builder(
                    value -> Component.translatable(value
                        ? "gravity_changer.plate.force.attract"
                        : "gravity_changer.plate.force.repulse"))
                .withValues(Boolean.TRUE, Boolean.FALSE)
                .withInitialValue(attracting)
                .create(x, y, HALF_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.force"),
                    (button, value) -> attracting = value));
            addRenderableWidget(CycleButton.<Boolean>builder(
                    value -> Component.translatable(value
                        ? "gravity_changer.gui.falloff.gradual"
                        : "gravity_changer.gui.falloff.full"))
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(gradualFalloff)
                .create(x + HALF_WIDTH + 4, y, HALF_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.falloff"),
                    (button, value) -> gradualFalloff = value));
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

        // ---- Gravity section ----
        y = sectionHeader("gravity_changer.gui.section.gravity", y);

        // acceleration: label left, numeric edit box right
        accelLabelX = x;
        accelLabelY = y + (WIDGET_HEIGHT - 8) / 2;
        EditBox box = new EditBox(this.font, x + 150, y, WIDGET_WIDTH - 150, WIDGET_HEIGHT,
            Component.translatable("gravity_changer.gui.gravity_accel"));
        box.setValue(accelText);
        box.setFilter(s -> s.isEmpty() || s.matches("[0-9]*\\.?[0-9]*"));
        box.setResponder(s -> accelText = s);
        accelBox = box;
        addRenderableWidget(box);
        y += ROW_HEIGHT;

        // preset row: four buttons filling the column
        int presetWidth = (WIDGET_WIDTH - 3 * 4) / 4;
        for (int i = 0; i < ACCEL_PRESETS.length; i++) {
            String key = (String) ACCEL_PRESETS[i][0];
            double value = (Double) ACCEL_PRESETS[i][1];
            addRenderableWidget(Button.builder(Component.translatable(key), b -> {
                    if (accelBox != null) {
                        accelBox.setValue(String.format(Locale.ROOT, "%.4f", value));
                    }
                })
                .bounds(x + i * (presetWidth + 4), y, presetWidth, WIDGET_HEIGHT).build());
        }
        y += ROW_HEIGHT;

        if (type != TargetType.NORMALIZER) {
            // Surface Snapping | World Gravity (for held ships) share one row
            addRenderableWidget(CycleButton.onOffBuilder(surfaceSnap)
                .create(x, y, HALF_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.surface_snap"),
                    (button, value) -> surfaceSnap = value));
            addRenderableWidget(CycleButton.<Boolean>builder(
                    value -> Component.translatable(value
                        ? "gravity_changer.gui.world_gravity.replace"
                        : "gravity_changer.gui.world_gravity.blend"))
                .withValues(Boolean.TRUE, Boolean.FALSE)
                .withInitialValue(replacesGravity)
                .withTooltip(value -> Tooltip.create(Component.translatable(value
                    ? "gravity_changer.gui.world_gravity.replace.tooltip"
                    : "gravity_changer.gui.world_gravity.blend.tooltip")))
                .create(x + HALF_WIDTH + 4, y, HALF_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("gravity_changer.gui.world_gravity"),
                    (button, value) -> replacesGravity = value));
            y += ROW_HEIGHT;
        }

        // ---- Visuals section ----
        y = sectionHeader("gravity_changer.gui.section.visuals", y);
        addRenderableWidget(CycleButton.onOffBuilder(showParticles)
            .create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
                Component.translatable("gravity_changer.gui.field_visual"),
                (button, value) -> showParticles = value));
        y += ROW_HEIGHT;

        // ---- Affects section: everything the field acts on, one place
        // (green on / red off) — ships included for plating and cores ----
        y = sectionHeader("gravity_changer.gui.section.targets", y);
        int thirdWidth = (WIDGET_WIDTH - 2 * 4) / 3;
        addRenderableWidget(targetToggle(x, y, thirdWidth, FieldTargets.PLAYERS, "players"));
        addRenderableWidget(targetToggle(x + thirdWidth + 4, y, thirdWidth, FieldTargets.MOBS, "mobs"));
        addRenderableWidget(targetToggle(x + 2 * (thirdWidth + 4), y, thirdWidth, FieldTargets.OBJECTS, "objects"));
        y += ROW_HEIGHT;
        if (type != TargetType.NORMALIZER) {
            addRenderableWidget(targetToggle(x, y, thirdWidth, FieldTargets.PARTICLES, "particles"));
            addRenderableWidget(targetToggle(x + thirdWidth + 4, y, thirdWidth, FieldTargets.FLUIDS, "fluids"));
            addRenderableWidget(shipsToggle(x + 2 * (thirdWidth + 4), y, thirdWidth));
        }
        else {
            addRenderableWidget(targetToggle(x, y, HALF_WIDTH, FieldTargets.PARTICLES, "particles"));
            addRenderableWidget(targetToggle(x + HALF_WIDTH + 4, y, HALF_WIDTH, FieldTargets.FLUIDS, "fluids"));
        }
        y += ROW_HEIGHT;

        // ---- action row ----
        actionSeparatorY = y + 2;
        y += 8;
        if (type == TargetType.PLATING) {
            // Copy to Connected Plates applies IMMEDIATELY (screen stays open)
            connectedButton = Button.builder(connectedLabel(), button -> {
                    send(true);
                    connectedFeedbackTicks = FEEDBACK_TICKS;
                    button.setMessage(Component.translatable("gravity_changer.gui.apply_connected.applied"));
                })
                .bounds(x, y, 150, WIDGET_HEIGHT).build();
            addRenderableWidget(connectedButton);
            addRenderableWidget(Button.builder(
                    Component.translatable("gravity_changer.gui.apply"), button -> sendAndClose())
                .bounds(x + 154, y, 51, WIDGET_HEIGHT).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(x + 209, y, 51, WIDGET_HEIGHT).build());
        }
        else {
            addRenderableWidget(Button.builder(
                    Component.translatable("gravity_changer.gui.apply"), button -> sendAndClose())
                .bounds(x, y, HALF_WIDTH, WIDGET_HEIGHT).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(x + HALF_WIDTH + 4, y, HALF_WIDTH, WIDGET_HEIGHT).build());
        }
    }

    /** One target-category toggle: flips a bit of {@link #targets}. */
    private Button targetToggle(int x, int y, int width, int bit, String name) {
        Button button = Button.builder(targetLabel(bit, name), b -> {
                targets ^= bit;
                b.setMessage(targetLabel(bit, name));
            })
            .bounds(x, y, width, WIDGET_HEIGHT).build();
        button.setTooltip(Tooltip.create(Component.translatable("gravity_changer.gui.target." + name + ".tooltip")));
        return button;
    }

    private Component targetLabel(int bit, String name) {
        boolean on = (targets & bit) != 0;
        return Component.translatable("gravity_changer.gui.target." + name)
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    /**
     * The ships toggle lives in the Affects section with the same look as
     * the category toggles, but drives its own flag: ships are a per-block
     * (plating: per-side) setting rather than a target-mask bit.
     */
    private Button shipsToggle(int x, int y, int width) {
        Button button = Button.builder(shipsLabel(), b -> {
                affectsShips = !affectsShips;
                b.setMessage(shipsLabel());
            })
            .bounds(x, y, width, WIDGET_HEIGHT).build();
        button.setTooltip(Tooltip.create(Component.translatable("gravity_changer.gui.target.ships.tooltip")));
        return button;
    }

    private Component shipsLabel() {
        return Component.translatable("gravity_changer.gui.target.ships")
            .withStyle(affectsShips ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private Component connectedLabel() {
        return connectedFeedbackTicks > 0
            ? Component.translatable("gravity_changer.gui.apply_connected.applied")
            : Component.translatable("gravity_changer.gui.apply_connected");
    }

    /** Records a section header position and returns the row y below it. */
    private int sectionHeader(String key, int y) {
        sectionHeaders.add(new SectionHeader(Component.translatable(key), y));
        return y + SECTION_GAP;
    }

    private void sendAndClose() {
        send(false);
        onClose();
    }

    /** Builds the payload from the current on-screen values and sends it. */
    private void send(boolean applyToConnected) {
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
                tag.putBoolean("affectsShips", affectsShips);
                tag.putInt("targets", targets);
                tag.putBoolean("replacesGravity", replacesGravity);
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
                tag.putInt("targets", targets);
                tag.putBoolean("replacesGravity", replacesGravity);
            }
            case NORMALIZER -> {
                tag.putString("localDown", localDown.getName());
                tag.putInt("range", rangeValue);
                tag.putDouble("gravityAccel", accel);
                tag.putBoolean("showParticles", showParticles);
                tag.putInt("targets", targets);
            }
        }
        GravityNetwork.sendToServer(new UpdateGravityBlockSettingsPacket(pos, type, tag));
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
        if (connectedFeedbackTicks > 0 && --connectedFeedbackTicks == 0 && connectedButton != null) {
            connectedButton.setMessage(connectedLabel());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int x = this.width / 2 - WIDGET_WIDTH / 2;
        for (SectionHeader header : sectionHeaders) {
            int labelWidth = this.font.width(header.label());
            int textY = header.y() + (SECTION_GAP - 8) / 2 - 1;
            graphics.drawString(this.font, header.label(), x, textY, 0xA0A0A0);
            // thin separator from the label's end to the column's right edge
            int lineY = textY + 4;
            graphics.fill(x + labelWidth + 6, lineY, x + WIDGET_WIDTH, lineY + 1, 0x50FFFFFF);
        }
        if (actionSeparatorY >= 0) {
            graphics.fill(x, actionSeparatorY, x + WIDGET_WIDTH, actionSeparatorY + 1, 0x50FFFFFF);
        }

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

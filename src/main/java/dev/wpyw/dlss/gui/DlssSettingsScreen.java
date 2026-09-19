package dev.wpyw.dlss.gui;

import dev.wpyw.dlss.cfg.FlatCfg;
import dev.wpyw.dlss.install.DlssStackInstaller;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live quality settings.
 *
 * <p>Everything here is written into the two {@code .cfg} files that sit beside the add-ons. That
 * is worth explaining, because it is why this screen can work at all: the DLSS5-Feeder README
 * states that {@code dlss5-feed.cfg} is "re-read while the game runs, if you prefer editing the
 * file directly", and Deep Fried Chicken behaves the same way. So most knobs take effect within a
 * second or two without a restart - the notable exception being {@code arm}, which is
 * restart-only.
 *
 * <p>The defaults are already sane (one neural pass at 100% work scale), so this screen exists
 * mainly for two things: trading the neural pass off against frame rate, and fixing the HUD if
 * the neural pass starts eating the hotbar.
 */
public class DlssSettingsScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("wpywdlss/gui");

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFAAAAAA;
    private static final int YELLOW = 0xFFFFD166;
    private static final int GREEN = 0xFF55FF55;

    private final Screen parent;

    private FlatCfg feed;
    private FlatCfg chicken;
    private final List<String> notes = new ArrayList<>();

    private int left;
    private int right;

    public DlssSettingsScreen(Screen parent) {
        super(Component.literal("Wpyw DLSS - 画质设置"));
        this.parent = parent;
    }

    private Path bin() {
        return DlssStackInstaller.jvmBinDir();
    }

    @Override
    protected void init() {
        notes.clear();
        try {
            feed = FlatCfg.load(bin().resolve("dlss5-feed.cfg"));
        } catch (Exception e) {
            notes.add("读不到 dlss5-feed.cfg: " + e.getMessage());
        }
        try {
            chicken = FlatCfg.load(bin().resolve("deep-fried-chicken.cfg"));
        } catch (Exception e) {
            notes.add("读不到 deep-fried-chicken.cfg: " + e.getMessage());
        }

        left = this.width / 2 - 235;
        right = this.width / 2 + 5;
        int y = 44;
        final int row = 24;

        // ---- Feed side (left column): the DLSS transport itself ----
        addRenderableWidget(Button.builder(toggleLabel("Feeder 总开关", feed, "enabled"), b -> {
            toggle(feed, "enabled");
            b.setMessage(toggleLabel("Feeder 总开关", feed, "enabled"));
        }).bounds(left, y, 230, 20).build());

        y += row;
        addRenderableWidget(new DoubleSlider(left, y, 230, 20, "锐化 (work_sharpness)", 0.0, 1.0, 0.01,
                () -> feed == null ? 0.30 : feed.getDouble("work_sharpness", 0.30),
                v -> setDouble(feed, "work_sharpness", v)));

        y += row;
        addRenderableWidget(new DoubleSlider(left, y, 230, 20, "创建延迟 (create_delay, 帧)", 0, 240, 1,
                () -> feed == null ? 60 : feed.getInt("create_delay", 60),
                v -> setInt(feed, "create_delay", (int) Math.round(v))));

        y += row;
        addRenderableWidget(new DoubleSlider(left, y, 230, 20, "卡顿阈值 (stall_log_ms)", 0, 200, 5,
                () -> feed == null ? 50 : feed.getInt("stall_log_ms", 50),
                v -> setInt(feed, "stall_log_ms", (int) Math.round(v))));

        y += row;
        addRenderableWidget(new DoubleSlider(left, y, 230, 20, "运动矢量放大 (mv_scale)", 0.5, 2.0, 0.05,
                () -> feed == null ? 1.0 : feed.getDouble("mv_scale_x", 1.0),
                v -> {
                    setDouble(feed, "mv_scale_x", v);
                    setDouble(feed, "mv_scale_y", v);
                }));

        // ---- Chicken side (right column): the neural pass ----
        y = 44;
        addRenderableWidget(Button.builder(toggleLabel("神经渲染", chicken, "enabled"), b -> {
            toggle(chicken, "enabled");
            b.setMessage(toggleLabel("神经渲染", chicken, "enabled"));
        }).bounds(right, y, 230, 20).build());

        y += row;
        addRenderableWidget(new DoubleSlider(right, y, 230, 20, "神经 pass 数", 1, 10, 0.1,
                () -> chicken == null ? 1.0 : chicken.getDouble("passes", 1.0),
                v -> {
                    setDouble(chicken, "passes", v);
                    setInt(chicken, "layers", Math.max(1, (int) Math.ceil(v)));
                }));

        y += row;
        addRenderableWidget(new DoubleSlider(right, y, 230, 20, "神经工作分辨率 %", 25, 150, 5,
                () -> chicken == null ? 100 : chicken.getInt("neural_work_percent", 100),
                v -> {
                    setInt(chicken, "neural_work_percent", (int) Math.round(v));
                    // >100% is neural supersampling; <100% can look soft. Both are documented trade-offs.
                }));

        y += row;
        addRenderableWidget(new DoubleSlider(right, y, 230, 20, "强度 (layer_1_intensity)", 0.0, 3.0, 0.05,
                () -> chicken == null ? 1.0 : chicken.getDouble("layer_1_intensity", 1.0),
                v -> setDouble(chicken, "layer_1_intensity", v)));

        y += row;
        addRenderableWidget(Button.builder(toggleLabel("HUD 修正 (layer_1_ui_correction)", chicken, "layer_1_ui_correction"), b -> {
            toggle(chicken, "layer_1_ui_correction");
            b.setMessage(toggleLabel("HUD 修正 (layer_1_ui_correction)", chicken, "layer_1_ui_correction"));
        }).bounds(right, y, 230, 20).build());

        y += row;
        addRenderableWidget(Button.builder(toggleLabel("保留原版色调 Native Look", chicken, "preserve_native_tone_color"), b -> {
            toggle(chicken, "preserve_native_tone_color");
            b.setMessage(toggleLabel("保留原版色调 Native Look", chicken, "preserve_native_tone_color"));
        }).bounds(right, y, 230, 20).build());

        // ---- bottom row ----
        int by = this.height - 48;
        addRenderableWidget(Button.builder(Component.literal("重载配置"), b -> {
            this.rebuildWidgets();
        }).bounds(this.width / 2 - 155, by, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(this.width / 2 + 5, by, 150, 20).build());
    }

    private Component toggleLabel(String label, FlatCfg cfg, String key) {
        if (cfg == null) {
            return Component.literal(label + ": ?");
        }
        return Component.literal(label + ": " + (cfg.getBool(key, false) ? "开" : "关"));
    }

    private void toggle(FlatCfg cfg, String key) {
        if (cfg == null) {
            return;
        }
        boolean now = cfg.getBool(key, false);
        cfg.set(key, !now);
        flush(cfg, key);
    }

    private void setInt(FlatCfg cfg, String key, int v) {
        if (cfg != null && cfg.set(key, v)) {
            flush(cfg, key);
        }
    }

    private void setDouble(FlatCfg cfg, String key, double v) {
        if (cfg != null && cfg.set(key, v)) {
            // Sliders fire continuously; writing on every pixel of drag would be wasteful.
            // The value is committed on release (see DoubleSlider.onRelease).
            pendingFlush = cfg;
        }
    }

    private FlatCfg pendingFlush;

    private void flush(FlatCfg cfg, String key) {
        try {
            cfg.save();
            LOG.info("wpywdlss cfg write: {} -> {}", key, cfg.get(key, "?"));
        } catch (Exception e) {
            notes.add("写入失败: " + e.getMessage());
            LOG.warn("cfg write failed", e);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.minecraft.font, this.title, this.width / 2, 14, WHITE);
        g.drawString(this.minecraft.font, "DLSS5-Feeder 传输", left, 32, GREY);
        g.drawString(this.minecraft.font, "Deep Fried Chicken 神经渲染", right, 32, GREY);

        int y = this.height - 78;
        g.drawString(this.minecraft.font,
                "多数项即时生效（cfg 在游戏运行时被重读）；arm / early_load 类改动需重启。",
                24, y, GREY);
        y += 12;
        g.drawString(this.minecraft.font,
                "神经工作分辨率 >100% 是超采样（更清晰、更慢），<100% 会变软。",
                24, y, GREY);
        y += 12;
        for (String n : notes) {
            g.drawString(this.minecraft.font, n, 24, y, YELLOW);
            y += 12;
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (pendingFlush != null) {
            flush(pendingFlush, "(slider release)");
            pendingFlush = null;
        }
        this.minecraft.setScreen(parent);
    }

    /** Slider bound to a getter/setter pair so it always reflects the file on disk. */
    private class DoubleSlider extends AbstractSliderButton {
        private final String label;
        private final double min;
        private final double max;
        private final double step;
        private final java.util.function.DoubleSupplier getter;
        private final java.util.function.DoubleConsumer setter;

        DoubleSlider(int x, int y, int w, int h, String label, double min, double max, double step,
                     java.util.function.DoubleSupplier getter, java.util.function.DoubleConsumer setter) {
            super(x, y, w, h, Component.literal(label), normalise(getter.getAsDouble(), min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.setter = setter;
            updateMessage();
        }

        private static double normalise(double v, double min, double max) {
            return max <= min ? 0.0 : Math.max(0.0, Math.min(1.0, (v - min) / (max - min)));
        }

        private double actual() {
            double raw = min + this.value * (max - min);
            if (step > 0) {
                raw = Math.round(raw / step) * step;
            }
            return raw;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT, "%s: %.2f", label, actual())));
        }

        @Override
        protected void applyValue() {
            setter.accept(actual());
        }
    }
}

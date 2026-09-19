package dev.wpyw.dlss.gui;

import dev.wpyw.dlss.cfg.MasterSwitch;
import dev.wpyw.dlss.install.DlssStackInstaller;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Puts the DLSS controls into <b>Options - Video Settings</b>, which is where a player looks
 * for anything that changes how the game is drawn.
 *
 * <p>Two buttons flank the vanilla "Done" button:
 * <ul>
 *   <li><b>left</b> - the master on/off switch (only shown once the stack is installed, because
 *       before that there is nothing to switch);</li>
 *   <li><b>right</b> - opens the DLSS panel, and its label carries the live install state so the
 *       answer to "is it actually working" is visible without clicking anything.</li>
 * </ul>
 *
 * <h2>Why this is safe to do from an event instead of a mixin</h2>
 * {@code Screens.getButtons(screen)} is a {@code ButtonList} built over the screen's own
 * {@code drawables}, {@code selectables} and {@code children} lists, and its {@code add} inserts
 * into all three. That is exactly what {@code Screen.addRenderableWidget} does, so an injected
 * button renders, takes clicks and takes keyboard focus on vanilla's normal code path - no
 * accessor, no {@code @Invoker}, and nothing to break when the screen is resized.
 *
 * <h2>Geometry</h2>
 * Taken from the 1.20.1 bytecode rather than guessed: {@code VideoSettingsScreen.init()} builds
 * {@code new OptionsList(minecraft, width, height, 32, height - 32, 25)} and then adds the Done
 * button at {@code (width/2 - 100, height - 27, 200, 20)}. The option rows stop at
 * {@code height - 32}, so the strip the Done button lives in is free on both sides of it - which
 * is where these two go. When the window is too narrow to fit anything beside Done, nothing is
 * injected; {@code /dlss} always works.
 */
public final class VideoSettingsHook {

    private static final Logger LOG = LoggerFactory.getLogger("wpywdlss/gui");

    // Vanilla geometry, verified against the compiled class.
    private static final int DONE_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int DONE_Y_FROM_BOTTOM = 27;

    private static final int GAP = 6;
    private static final int EDGE_INSET = 4;
    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 170;

    /** Probing means hashing ~10 MB of payload; do not do it on every screen open. */
    private static final long CACHE_TTL_MS = 1500L;

    private static DlssStackInstaller.Status cachedStatus = DlssStackInstaller.Status.UNSUPPORTED;
    private static String cachedDetail = "";
    private static boolean cachedSwitchable;
    private static boolean cachedOn;
    private static long cachedAt;

    private VideoSettingsHook() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof VideoSettingsScreen)) {
                return;
            }
            try {
                inject(screen, width, height);
            } catch (Throwable t) {
                // A broken button must never take the vanilla video settings screen down with it.
                LOG.warn("could not inject the DLSS buttons into the video settings screen", t);
            }
        });
    }

    private static void inject(Screen screen, int width, int height) {
        int side = width / 2 - DONE_WIDTH / 2 - GAP - EDGE_INSET;
        int buttonWidth = Math.min(MAX_WIDTH, side);
        if (buttonWidth < MIN_WIDTH) {
            LOG.info("window too narrow ({}x{}) for the DLSS buttons in video settings; use /dlss",
                    width, height);
            return;
        }

        refresh();

        int y = height - DONE_Y_FROM_BOTTOM;
        int leftX = width / 2 - DONE_WIDTH / 2 - GAP - buttonWidth;
        int rightX = width / 2 + DONE_WIDTH / 2 + GAP;

        if (cachedSwitchable) {
            Screens.getButtons(screen).add(switchButton(leftX, y, buttonWidth));
        }
        Screens.getButtons(screen).add(panelButton(screen, rightX, y, buttonWidth));
    }

    // ------------------------------------------------------------------ buttons

    private static Button switchButton(int x, int y, int width) {
        return Button.builder(Component.literal(switchLabel(cachedOn)), b -> {
            Path bin = DlssStackInstaller.jvmBinDir();
            MasterSwitch.State before = MasterSwitch.read(bin);
            MasterSwitch.State after = MasterSwitch.write(bin, !before.on());
            b.setMessage(Component.literal(switchLabel(after.on())));
            cachedOn = after.on();
            cachedAt = 0L; // force a re-probe the next time the screen opens
            if (!after.note().isEmpty()) {
                LOG.warn("master switch: {}", after.note());
            } else {
                LOG.info("DLSS master switch -> {}", after.on() ? "on" : "off");
            }
        }).bounds(x, y, width, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.literal(
                        "DLSS 总开关\n"
                                + "同时写入 DLSS5-Feeder 与 Deep Fried Chicken 两个 cfg 的 enabled。\n"
                                + "两个 add-on 都会在游戏运行时重读自己的 cfg，所以这是即时生效的开关，不需要重启。\n"
                                + "分开开关会出现「半开」状态：Feeder 在请求神经帧，但没人渲染它们。")))
                .build();
    }

    private static Button panelButton(Screen parent, int x, int y, int width) {
        return Button.builder(Component.literal(panelLabel(cachedStatus)), b ->
                        Minecraft.getInstance().setScreen(new DlssSetupScreen(parent)))
                .bounds(x, y, width, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.literal(
                        "Wpyw DLSS —— 状态、安装、画质设置、日志诊断\n\n" + cachedDetail)))
                .build();
    }

    private static String switchLabel(boolean on) {
        return on ? "DLSS: 开" : "DLSS: 关";
    }

    private static String panelLabel(DlssStackInstaller.Status status) {
        return switch (status) {
            case INSTALLED -> "DLSS · 已安装";
            case NOT_INSTALLED -> "DLSS · 未安装";
            case NEEDS_REPAIR -> "DLSS · 需修复";
            case UNSUPPORTED -> "DLSS · 不可用";
        };
    }

    // ------------------------------------------------------------------ probe

    private static void refresh() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0L && now - cachedAt < CACHE_TTL_MS) {
            return;
        }
        cachedAt = now;
        try {
            DlssStackInstaller.Report r = DlssStackInstaller.analyze();
            MasterSwitch.State sw = MasterSwitch.read(DlssStackInstaller.jvmBinDir());

            cachedStatus = r.status();
            cachedSwitchable = sw.switchable();
            cachedOn = sw.on();
            cachedDetail = describe(r);
        } catch (Throwable t) {
            cachedStatus = DlssStackInstaller.Status.UNSUPPORTED;
            cachedSwitchable = false;
            cachedOn = false;
            cachedDetail = "状态探测失败: " + t;
            LOG.warn("probe failed", t);
        }
    }

    private static String describe(DlssStackInstaller.Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(switch (r.status()) {
            case INSTALLED -> "已安装";
            case NOT_INSTALLED -> "未安装";
            case NEEDS_REPAIR -> "需要修复";
            case UNSUPPORTED -> "目标不可用";
        }).append('\n');
        sb.append("安装目标: ").append(r.target()).append('\n');
        sb.append("载荷: ").append(r.present().size()).append(" 个文件已就位");
        if (!r.missing().isEmpty()) {
            sb.append("，缺 ").append(r.missing().size());
        }
        if (!r.modified().isEmpty()) {
            sb.append("，被改动 ").append(r.modified().size());
        }
        sb.append('\n');
        if (!r.ngxMissing().isEmpty()) {
            sb.append("NVIDIA 运行时缺失: ").append(String.join(", ", r.ngxMissing())).append('\n');
        }
        sb.append("\n命令行同样可用: /dlss");
        return sb.toString();
    }

    /** Drops the cached probe so the next screen open re-reads the disk. */
    public static void invalidate() {
        cachedAt = 0L;
    }
}

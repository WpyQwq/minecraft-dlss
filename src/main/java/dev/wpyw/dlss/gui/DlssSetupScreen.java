package dev.wpyw.dlss.gui;

import dev.wpyw.dlss.diag.LogTail;
import dev.wpyw.dlss.install.DlssStackInstaller;
import dev.wpyw.dlss.install.NgxRuntimeLocator;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main screen: shows whether the stack is installed, offers install / repair / uninstall, and
 * surfaces the verdict lines from the three logs so the player can see what actually happened
 * without leaving the game.
 */
public class DlssSetupScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("wpywdlss/gui");

    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFD166;
    private static final int RED = 0xFFFF6B6B;
    private static final int GREY = 0xFFAAAAAA;
    private static final int WHITE = 0xFFFFFFFF;

    private final Screen parent;

    private DlssStackInstaller.Report report;
    private List<String> statusLines = new ArrayList<>();
    private int statusColour = GREY;
    private String actionMessage = "";

    public DlssSetupScreen(Screen parent) {
        super(Component.literal("Wpyw DLSS - 状态与安装"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refresh();

        int cx = this.width / 2;
        int y = this.height - 58;
        int gap = 6;

        Button installBtn = Button.builder(Component.literal(installLabel()), b -> {
            try {
                DlssStackInstaller.Report r = DlssStackInstaller.install();
                actionMessage = "安装完成：" + r.present().size() + " 个文件就位"
                        + (r.ngxMissing().isEmpty() ? "" : "，但缺 " + r.ngxMissing().size() + " 个 NVIDIA 运行时");
                LOG.info("install finished: {}", r.status());
                LOG.info("\n{}", r.describe());
            } catch (Exception e) {
                actionMessage = "安装失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
                LOG.error("install failed", e);
            }
            this.rebuildWidgets();
        }).bounds(cx - 235, y, 150, 20).build();

        Button settingsBtn = Button.builder(Component.literal("画质设置"), b ->
                this.minecraft.setScreen(new DlssSettingsScreen(this))).bounds(cx - 79, y, 150, 20).build();

        Button logsBtn = Button.builder(Component.literal("日志与诊断"), b ->
                this.minecraft.setScreen(new DlssLogScreen(this))).bounds(cx + 77, y, 150, 20).build();

        Button uninstallBtn = Button.builder(Component.literal("卸载"), b -> {
            try {
                DlssStackInstaller.Report r = DlssStackInstaller.uninstall();
                actionMessage = "已卸载（" + r.notes().get(r.notes().size() - 1) + "）";
            } catch (Exception e) {
                actionMessage = "卸载失败：" + e.getMessage();
            }
            this.rebuildWidgets();
        }).bounds(cx - 235, y + 24, 150, 20).build();

        Button openBtn = Button.builder(Component.literal("打开安装目录"), b -> {
            try {
                Util.getPlatform().openFile(DlssStackInstaller.jvmBinDir().toFile());
            } catch (Exception e) {
                actionMessage = "无法打开目录：" + e.getMessage();
            }
        }).bounds(cx - 79, y + 24, 150, 20).build();

        Button doneBtn = Button.builder(Component.literal("完成"), b -> this.onClose())
                .bounds(cx + 77, y + 24, 150, 20).build();

        addRenderableWidget(installBtn);
        addRenderableWidget(settingsBtn);
        addRenderableWidget(logsBtn);
        addRenderableWidget(uninstallBtn);
        addRenderableWidget(openBtn);
        addRenderableWidget(doneBtn);

        // Only an unsupported target is a dead end; otherwise let the user re-verify at will.
        installBtn.active = report.status() != DlssStackInstaller.Status.UNSUPPORTED;
    }

    private String installLabel() {
        if (report == null) {
            return "安装 / 修复";
        }
        return switch (report.status()) {
            case NOT_INSTALLED -> "安装";
            case NEEDS_REPAIR -> "修复";
            case INSTALLED -> "重新校验 / 修复运行时";
            case UNSUPPORTED -> "无法安装";
        };
    }

    private void refresh() {
        report = DlssStackInstaller.analyze();
        statusLines = buildStatusLines();
        // The video settings buttons cache their probe; anything that happens here can change
        // the answer they show, so drop that cache on every entry into this panel.
        VideoSettingsHook.invalidate();
    }

    private List<String> buildStatusLines() {
        List<String> out = new ArrayList<>();
        Path target = report.target();

        statusColour = switch (report.status()) {
            case INSTALLED -> GREEN;
            case NEEDS_REPAIR -> YELLOW;
            default -> RED;
        };

        out.add("状态: " + report.status());
        out.add("安装目标: " + target);
        out.add("载荷: " + report.present().size() + " 个文件已就位"
                + (report.missing().isEmpty() ? "" : "，缺 " + report.missing().size())
                + (report.modified().isEmpty() ? "" : "，被改动 " + report.modified().size()));

        for (NgxRuntimeLocator.Found f : report.ngx()) {
            out.add("NVIDIA: " + f.name() + "  " + String.format("%,d B", f.size())
                    + (f.sizeMatchesNominal() ? "" : "  (与验证过的 SDK 大小不同)"));
        }
        for (String m : report.ngxMissing()) {
            out.add("NVIDIA: 缺失 " + m + " —— 需要放在 javaw.exe 旁");
        }

        boolean runningNow = Files.isRegularFile(target.resolve("opengl32.dll"));
        if (report.status() == DlssStackInstaller.Status.INSTALLED) {
            out.add("");
            out.add("ReShade 是代理 DLL，只在 javaw.exe 启动时加载。");
            out.add("本次启动是否已生效，取决于启动时它是否已经就位：");
            Path reshadeLog = target.resolve(LogTail.RESHADE);
            String rv = LogTail.verdict(reshadeLog, "Registered add-on");
            out.add("  ReShade.log: " + (rv.equals("ok") ? "已加载并注册 add-on" : "尚未生效 —— 请重启游戏"));
        }
        if (!report.notes().isEmpty()) {
            out.add("");
            for (String n : report.notes()) {
                out.add("· " + n);
            }
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        g.drawCenteredString(this.minecraft.font, this.title, this.width / 2, 14, WHITE);

        int y = 38;
        int left = 24;
        int maxWidth = this.width - 48;
        int colour = statusColour;
        for (String line : statusLines) {
            if (line.isEmpty()) {
                y += 6;
                colour = GREY;
                continue;
            }
            for (var wrapped : this.minecraft.font.split(Component.literal(line), maxWidth)) {
                g.drawString(this.minecraft.font, wrapped, left, y, colour);
                y += 11;
            }
            y += 1;
            colour = GREY; // only the status line is coloured
        }

        if (!actionMessage.isEmpty()) {
            g.drawString(this.minecraft.font, actionMessage, left, this.height - 74, YELLOW);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

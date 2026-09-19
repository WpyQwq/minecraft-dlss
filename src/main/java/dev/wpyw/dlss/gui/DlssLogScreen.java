package dev.wpyw.dlss.gui;

import dev.wpyw.dlss.diag.LogTail;
import dev.wpyw.dlss.install.DlssStackInstaller;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the verdict lines from the three logs, in game.
 *
 * <p>The point is to remove the alt-tab: whether the stack is actually working is answered by
 * specific log lines, not by how the picture looks. For example a working setup prints
 * {@code Registered add-on}, {@code session ready}, {@code feature ready ... DLAA} and
 * {@code standalone neural frame succeeded}, while the characteristic failure prints
 * {@code depth is FLAT while the scene moves}.
 *
 * <p>Scrolling uses buttons rather than the mouse wheel on purpose: the wheel handler's signature
 * changed between 1.20.1 and later versions, and getting it wrong breaks the build for no benefit.
 */
public class DlssLogScreen extends Screen {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int ORANGE = 0xFFFFAA55;

    private final Screen parent;

    private record Tab(String label, String fileName, String successMarker) {
    }

    private static final Tab[] TABS = {
            new Tab("ReShade", LogTail.RESHADE, "Registered add-on"),
            new Tab("Feeder", LogTail.FEEDER, "session ready"),
            new Tab("Chicken", LogTail.CHICKEN, "neural frame succeeded"),
    };

    private int tab = 0;
    private int scroll = 0;
    private List<String> lines = new ArrayList<>();
    private String verdict = "";

    private Button tabButton;
    private Button upButton;
    private Button downButton;

    public DlssLogScreen(Screen parent) {
        super(Component.literal("Wpyw DLSS - 日志与诊断"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        tabButton = Button.builder(Component.literal(tabLabel()), b -> {
            tab = (tab + 1) % TABS.length;
            scroll = 0;
            b.setMessage(Component.literal(tabLabel()));
            load();
            updateScrollButtons();
        }).bounds(cx - 235, 34, 200, 20).build();
        addRenderableWidget(tabButton);

        addRenderableWidget(Button.builder(Component.literal("只看关键行 / 看全部"), b -> {
            showAll = !showAll;
            scroll = 0;
            load();
            updateScrollButtons();
        }).bounds(cx - 27, 34, 140, 20).build());

        addRenderableWidget(Button.builder(Component.literal("复制到剪贴板"), b -> {
            this.minecraft.keyboardHandler.setClipboard(String.join("\n", lines));
        }).bounds(cx + 121, 34, 114, 20).build());

        upButton = Button.builder(Component.literal("▲"), b -> {
            scroll = Math.max(0, scroll - 12);
            updateScrollButtons();
        }).bounds(cx + 243, 34 + 30, 20, 20).build();
        addRenderableWidget(upButton);

        downButton = Button.builder(Component.literal("▼"), b -> {
            scroll = Math.min(maxScroll(), scroll + 12);
            updateScrollButtons();
        }).bounds(cx + 243, this.height - 74, 20, 20).build();
        addRenderableWidget(downButton);

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(cx - 76, this.height - 48, 152, 20).build());

        load();
        updateScrollButtons();
    }

    private boolean showAll = false;

    private String tabLabel() {
        return "日志: " + TABS[tab].label() + "（点击切换）";
    }

    private void load() {
        Path dir = DlssStackInstaller.jvmBinDir();
        List<String> raw = showAll
                ? LogTail.tail(dir.resolve(TABS[tab].fileName()), 400)
                : LogTail.highlights(dir.resolve(TABS[tab].fileName()), 400);
        lines = raw.isEmpty() ? List.of("(没有匹配的关键行 - 勾选「看全部」，或该日志尚未生成)") : raw;
        verdict = LogTail.verdict(dir.resolve(TABS[tab].fileName()), TABS[tab].successMarker());
    }

    private int visibleRows() {
        return Math.max(1, (this.height - 120) / 10);
    }

    private int maxScroll() {
        return Math.max(0, lines.size() - visibleRows());
    }

    private void updateScrollButtons() {
        if (upButton != null) {
            upButton.active = scroll > 0;
        }
        if (downButton != null) {
            downButton.active = scroll < maxScroll();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(this.minecraft.font, this.title, this.width / 2, 14, WHITE);

        int verdictColour = switch (verdict) {
            case "ok" -> GREEN;
            case "absent" -> GREY;
            default -> ORANGE;
        };
        String v = "判据 " + TABS[tab].successMarker() + " -> " + verdict;
        g.drawString(this.minecraft.font, v, this.width / 2 - 235, 62, verdictColour);

        int y = 78;
        int rows = visibleRows();
        int end = Math.min(lines.size(), scroll + rows);
        for (int i = scroll; i < end; i++) {
            String line = lines.get(i);
            int colour = GREY;
            if (line.contains("ERROR") || line.contains("FAIL")) {
                colour = ORANGE;
            } else if (line.contains("Registered add-on") || line.contains("neural frame succeeded")
                    || line.contains("session ready") || line.contains("feature ready")) {
                colour = GREEN;
            } else if (line.contains("WARN")) {
                colour = ORANGE;
            }
            g.drawString(this.minecraft.font, this.minecraft.font.plainSubstrByWidth(line, this.width - 96),
                    this.width / 2 - 235, y, colour);
            y += 10;
        }

        g.drawString(this.minecraft.font,
                String.format("%d 行，显示 %d-%d", lines.size(), scroll + 1, end),
                this.width / 2 - 235, this.height - 78, GREY);

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

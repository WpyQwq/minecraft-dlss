package dev.wpyw.dlss;

import com.mojang.blaze3d.platform.InputConstants;
import dev.wpyw.dlss.cfg.MasterSwitch;
import dev.wpyw.dlss.gui.DlssSetupScreen;
import dev.wpyw.dlss.gui.VideoSettingsHook;
import dev.wpyw.dlss.install.DlssStackInstaller;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client entrypoint.
 *
 * <p>This mod is the installer, the control panel and the diagnostics for the DLSS stack - it is
 * not the thing that runs DLSS. The renderer is ReShade plus its add-ons, which is why the single
 * most important fact about this mod is that <b>it cannot activate ReShade in the current
 * process</b>: ReShade is a proxy DLL for {@code opengl32.dll}, and Windows resolved that import
 * before any Fabric code ran. Installing therefore always ends with "restart the game", and the
 * GUI says so rather than pretending otherwise.
 *
 * <p>Everything the player needs is reachable three ways, in decreasing order of discoverability:
 * the buttons this mod injects into <b>Options - Video Settings</b>, the {@code /dlss} command,
 * and an (unbound by default) key binding.
 */
public class DlssClientMod implements ClientModInitializer {
    public static final String MOD_ID = "wpywdlss";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openGuiKey;

    @Override
    public void onInitializeClient() {
        LOG.info("Wpyw DLSS initialising (client)");

        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.wpywdlss.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,   // unbound by default; the video settings button is the way in
                "Wpyw DLSS"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.consumeClick()) {
                openPanel();
            }
        });

        // The entry point the user actually asked for: two buttons inside Options - Video Settings.
        VideoSettingsHook.register();

        registerCommands();

        // Report the state once at startup, because the interesting question at this point is
        // always "did ReShade actually load this launch?".
        DlssStackInstaller.Report r = DlssStackInstaller.analyze();
        LOG.info("stack target     : {}", r.target());
        LOG.info("stack status     : {}", r.status());
        LOG.info("payload files    : {}", r.present().size());
        if (!r.ngxMissing().isEmpty()) {
            LOG.warn("NVIDIA runtime(s) missing: {}", r.ngxMissing());
        }
        Path reshadeLog = r.target().resolve("ReShade.log");
        if (r.status() == DlssStackInstaller.Status.INSTALLED
                && !Files.isRegularFile(reshadeLog)) {
            LOG.warn("installed, but no ReShade.log yet - restart Minecraft to let the proxy load");
        }
        LOG.info("game dir         : {}", FabricLoader.getInstance().getGameDir());
        LOG.info("open the panel with /dlss, or Options - Video Settings");
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("dlss")
                        .executes(ctx -> {
                            openPanel();
                            return 1;
                        })
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> {
                                    DlssStackInstaller.Report r = DlssStackInstaller.analyze();
                                    LOG.info("\n{}", r.describe());
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("install")
                                .executes(ctx -> {
                                    try {
                                        DlssStackInstaller.Report r = DlssStackInstaller.install();
                                        LOG.info("installed: {}\n{}", r.status(), r.describe());
                                    } catch (Exception e) {
                                        LOG.error("install failed", e);
                                    }
                                    VideoSettingsHook.invalidate();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("uninstall")
                                .executes(ctx -> {
                                    try {
                                        DlssStackInstaller.Report r = DlssStackInstaller.uninstall();
                                        LOG.info("uninstalled: {}", r.status());
                                    } catch (Exception e) {
                                        LOG.error("uninstall failed", e);
                                    }
                                    VideoSettingsHook.invalidate();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("on")
                                .executes(ctx -> {
                                    setMasterSwitch(true);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("off")
                                .executes(ctx -> {
                                    setMasterSwitch(false);
                                    return 1;
                                }))
                ));
    }

    private static void setMasterSwitch(boolean on) {
        MasterSwitch.State s = MasterSwitch.write(DlssStackInstaller.jvmBinDir(), on);
        if (!s.note().isEmpty()) {
            LOG.warn("DLSS {} failed: {}", on ? "on" : "off", s.note());
        }
        LOG.info("DLSS is now {}", s.on() ? "ON" : "OFF");
        VideoSettingsHook.invalidate();
    }

    /**
     * Opens the panel over whatever is on screen now, so closing it returns the player there
     * instead of dumping them back into the world. Screens may only be replaced on the client
     * thread, so hop there first when called from a command.
     */
    private static void openPanel() {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = mc.screen;
        mc.execute(() -> mc.setScreen(new DlssSetupScreen(parent)));
    }
}

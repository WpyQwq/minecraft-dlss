package dev.wpyw.dlss.cfg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one switch a player actually wants: "is DLSS on right now?".
 *
 * <p>There is no single flag for that. Two independent ReShade add-ons have to be enabled -
 * DLSS5-Feeder, which turns each frame into a DLSS request, and Deep Fried Chicken, which
 * is the thing that performs the neural render - and each keeps its own {@code enabled} key
 * in its own flat cfg file. A half-on stack is a real and confusing failure mode (the feeder
 * requests frames nobody renders), so both are always read and written together.
 *
 * <p>Both files are re-read by their add-ons while the game runs, so this is a live switch:
 * flipping it does not require a restart. Installing the stack does, because ReShade is a
 * proxy DLL and Windows resolved {@code opengl32.dll} before any mod code ran.
 */
public final class MasterSwitch {

    public static final String FEED_CFG = "dlss5-feed.cfg";
    public static final String CHICKEN_CFG = "deep-fried-chicken.cfg";

    private static final String KEY = "enabled";

    /**
     * @param on             both cfg files exist and both say {@code enabled=1}
     * @param feedPresent    {@code dlss5-feed.cfg} is on disk
     * @param chickenPresent {@code deep-fried-chicken.cfg} is on disk
     * @param note           empty unless the last operation failed, then the reason
     */
    public record State(boolean on, boolean feedPresent, boolean chickenPresent, String note) {

        /** Both add-on cfgs are present, so there is something to switch. */
        public boolean switchable() {
            return this.feedPresent && this.chickenPresent;
        }
    }

    private MasterSwitch() {
    }

    /** Reads the current state. Never throws; a read failure is reported as {@code on=false}. */
    public static State read(Path binDir) {
        Path feed = binDir.resolve(FEED_CFG);
        Path chicken = binDir.resolve(CHICKEN_CFG);
        boolean feedPresent = Files.isRegularFile(feed);
        boolean chickenPresent = Files.isRegularFile(chicken);

        if (!feedPresent || !chickenPresent) {
            // Nothing to read. Deliberately not "on": a missing cfg is an uninstalled stack,
            // not an enabled one, and reporting it as enabled would hide the real problem.
            return new State(false, feedPresent, chickenPresent, "");
        }
        try {
            boolean on = FlatCfg.load(feed).getBool(KEY, false)
                    && FlatCfg.load(chicken).getBool(KEY, false);
            return new State(on, true, true, "");
        } catch (IOException e) {
            return new State(false, true, true, "read failed: " + e.getMessage());
        }
    }

    /**
     * Writes {@code enabled} to both cfgs. Returns the state that is actually on disk
     * afterwards, which is what the caller should display - not what it asked for.
     */
    public static State write(Path binDir, boolean enabled) {
        Path feed = binDir.resolve(FEED_CFG);
        Path chicken = binDir.resolve(CHICKEN_CFG);
        boolean feedPresent = Files.isRegularFile(feed);
        boolean chickenPresent = Files.isRegularFile(chicken);

        if (!feedPresent || !chickenPresent) {
            return new State(false, feedPresent, chickenPresent,
                    "add-on cfg missing - install the stack first");
        }
        try {
            // Feeder first, deliberately. If the second write fails we are left with feeder=off,
            // chicken=on - which is inert (nothing is asking for neural frames) rather than the
            // other way round, which would leave the feeder requesting frames nobody renders.
            for (Path p : new Path[]{feed, chicken}) {
                FlatCfg cfg = FlatCfg.load(p);
                // Only touch the file when the value really changes, so a no-op toggle does
                // not rewrite 666 lines and risk racing the add-on's own reader.
                if (cfg.set(KEY, enabled)) {
                    cfg.save();
                }
            }
        } catch (IOException e) {
            State now = read(binDir);
            return new State(now.on(), feedPresent, chickenPresent, "write failed: " + e.getMessage());
        }
        return read(binDir);
    }
}

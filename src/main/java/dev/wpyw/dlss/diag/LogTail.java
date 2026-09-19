package dev.wpyw.dlss.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Reads the tail of the stack's log files so the in-game GUI can show what is actually happening
 * without the player alt-tabbing to a text editor.
 *
 * <p>Three logs matter and each answers a different question:
 * <ul>
 *   <li>{@code ReShade.log} - did ReShade load at all, and did it register the add-ons?</li>
 *   <li>{@code dlss5-feed.log} - did the feeder open its transport, create the DLSS feature, and
 *       is it getting motion vectors and depth?</li>
 *   <li>{@code deep-fried-chicken.log} - did the neural pass run, and how many frames succeeded?</li>
 * </ul>
 */
public final class LogTail {

    private LogTail() {
    }

    public static final String RESHADE = "ReShade.log";
    public static final String FEEDER = "dlss5-feed.log";
    public static final String CHICKEN = "deep-fried-chicken.log";

    /** Last {@code maxLines} lines of a log, or an explanatory single line when unusable. */
    public static List<String> tail(Path logFile, int maxLines) {
        if (!Files.isRegularFile(logFile)) {
            return List.of("(no " + logFile.getFileName() + " - the stack has not run yet)");
        }
        Deque<String> ring = new ArrayDeque<>();
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                ring.addLast(stripAnsi(line));
                if (ring.size() > maxLines) {
                    ring.removeFirst();
                }
            }
        } catch (IOException e) {
            return List.of("(cannot read " + logFile.getFileName() + ": " + e.getMessage() + ")");
        }
        return new ArrayList<>(ring);
    }

    /** Greps a log for the lines that carry a verdict, rather than dumping everything. */
    public static List<String> highlights(Path logFile, int maxLines) {
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                String l = stripAnsi(line);
                if (l.contains("Registered add-on")
                        || l.contains("neural frame succeeded")
                        || l.contains("feature ready")
                        || l.contains("feature 18 created")
                        || l.contains("session ready")
                        || l.contains("unified neural frame accepted")
                        || l.contains("MV probe")
                        || l.contains("Depth probe")
                        || l.contains("WARN")
                        || l.contains("ERROR")) {
                    hits.add(l);
                }
            }
        } catch (IOException e) {
            return List.of("(cannot read " + logFile.getFileName() + ": " + e.getMessage() + ")");
        }
        if (hits.size() > maxLines) {
            return new ArrayList<>(hits.subList(hits.size() - maxLines, hits.size()));
        }
        return hits;
    }

    /** ReShade colourises its log; those escapes render as garbage in a plain text widget. */
    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
    }

    /** One short status word for a log, for the GUI's summary line. */
    public static String verdict(Path logFile, String successMarker) {
        if (!Files.isRegularFile(logFile)) {
            return "absent";
        }
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line.contains(successMarker)) {
                    return "ok";
                }
            }
        } catch (IOException ignored) {
            return "unreadable";
        }
        return "no match";
    }
}

package dev.wpyw.dlss.cfg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-preserving editor for the flat {@code key=value} files this stack uses.
 *
 * <p>Both {@code dlss5-feed.cfg} and {@code deep-fried-chicken.cfg} are completely flat
 * (no sections, 600+ keys each) and, crucially, <b>re-read while the game runs</b> - the
 * DLSS5-Feeder documentation says so explicitly. That means a Minecraft GUI can change most
 * settings live, without a restart, by rewriting the file.
 *
 * <p>These files belong to other programs, so edits are as narrow as they can be:
 * <ul>
 *   <li>edits are applied per line and unknown lines are kept verbatim - rebuilding the file
 *       from a parsed map would drop the hundreds of keys this mod does not know about and
 *       reorder everything;</li>
 *   <li>the original <b>line ending is preserved</b>. Both live files are CRLF; writing them
 *       back with LF would rewrite all 666 lines of a file Deep Fried Chicken owns, to no
 *       benefit, and risks churn against whatever that add-on does when it saves.</li>
 * </ul>
 */
public final class FlatCfg {

    private final Path path;
    private final List<String> lines = new ArrayList<>();
    private final String eol;

    private FlatCfg(Path path, List<String> lines, String eol) {
        this.path = path;
        this.lines.addAll(lines);
        this.eol = eol;
    }

    /** Reads an existing file, or starts an empty one if it does not exist. */
    public static FlatCfg load(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            byte[] raw = Files.readAllBytes(path);
            return new FlatCfg(path, decodeLines(raw), detectEol(raw));
        }
        // A file this mod creates should look native on the host platform.
        return new FlatCfg(path, new ArrayList<>(), System.lineSeparator());
    }

    /** The line terminator that will be used on {@link #save()}, as read from disk. */
    public String lineEnding() {
        return eol;
    }

    public Path path() {
        return path;
    }

    /** Snapshot of the current key/value pairs, in file order. Only simple {@code k=v} lines. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            if (!k.isEmpty() && !k.startsWith("#") && !k.startsWith(";")) {
                m.put(k, line.substring(eq + 1).trim());
            }
        }
        return m;
    }

    public String get(String key, String fallback) {
        return asMap().getOrDefault(key, fallback);
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        String v = get(key, fallback ? "1" : "0").trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /**
     * Sets a key in place, or appends it if absent. Returns {@code true} when the value actually
     * changed, so callers can avoid pointless disk writes.
     */
    public boolean set(String key, String value) {
        String wanted = key + "=" + value;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                if (line.equals(wanted)) {
                    return false;
                }
                lines.set(i, wanted);
                return true;
            }
        }
        lines.add(wanted);
        return true;
    }

    public boolean set(String key, int value) {
        return set(key, Integer.toString(value));
    }

    public boolean set(String key, double value) {
        // The cfg files use plain decimal notation; keep it stable to avoid needless rewrites.
        return set(key, String.format(java.util.Locale.ROOT, "%.3f", value));
    }

    public boolean set(String key, boolean value) {
        return set(key, value ? "1" : "0");
    }

    /** Writes the file, creating parent directories, using the line ending it was read with. */
    public void save() throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, (String.join(eol, lines) + eol).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ line handling

    /**
     * Splits on LF, CRLF and lone CR, with the same shape as {@code Files.readAllLines}: no
     * trailing empty element for a final terminator, but empty elements kept for blank lines.
     */
    private static List<String> decodeLines(byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') {
                continue;
            }
            out.add(text.substring(start, i));
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                i++;
            }
            start = i + 1;
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static String detectEol(byte[] raw) {
        int crlf = 0;
        int bareLf = 0;
        int bareCr = 0;
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '\n') {
                if (i > 0 && raw[i - 1] == '\r') {
                    crlf++;
                } else {
                    bareLf++;
                }
            } else if (b == '\r' && (i + 1 >= raw.length || raw[i + 1] != '\n')) {
                bareCr++;
            }
        }
        if (crlf > 0 && crlf >= bareLf && crlf >= bareCr) {
            return "\r\n";
        }
        if (bareCr > 0 && bareCr > bareLf) {
            return "\r";
        }
        if (bareLf > 0) {
            return "\n";
        }
        return System.lineSeparator();
    }
}

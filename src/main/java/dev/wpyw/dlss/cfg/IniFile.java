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
 * Line-preserving, section-aware editor for ReShade's ini files.
 *
 * <p>Two shapes have to be handled, and they are different:
 * <ul>
 *   <li>{@code ReShade.ini} is a normal ini - every key lives under a {@code [Section]}.</li>
 *   <li>{@code ReShadePreset.ini} puts {@code Techniques=} and {@code TechniqueSorting=} in the
 *       file's <b>preamble</b>, before the first {@code [Effect.fx]} section. Those two keys are
 *       the ones that matter most: {@code Techniques=} says what is enabled, and
 *       {@code TechniqueSorting=} defines execution order - which is what decides whether the
 *       motion-vector provider runs before the shader that consumes it.</li>
 * </ul>
 * A {@code null} section therefore means "the preamble". Replacing either file wholesale would
 * destroy state we do not own (there are hundreds of keys here, and ReShade regenerates defaults
 * on exit), so edits are applied line by line and everything unknown is preserved verbatim.
 */
public final class IniFile {

    private final Path path;
    private final List<String> lines = new ArrayList<>();

    private IniFile(Path path, List<String> lines) {
        this.path = path;
        this.lines.addAll(lines);
    }

    public static IniFile load(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return new IniFile(path, Files.readAllLines(path, StandardCharsets.UTF_8));
        }
        return new IniFile(path, new ArrayList<>());
    }

    public Path path() {
        return path;
    }

    public boolean isNew() {
        return lines.isEmpty();
    }

    private static boolean isHeader(String line) {
        String t = line.trim();
        return t.length() >= 3 && t.startsWith("[") && t.endsWith("]");
    }

    /** Index of the given section's header line, or -1. */
    private int headerIndex(String section) {
        if (section == null || section.isEmpty()) {
            return -1;
        }
        String want = "[" + section + "]";
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(want)) {
                return i;
            }
        }
        return -1;
    }

    /** Exclusive end of a section body (the next header, or the end of file). */
    private int bodyEnd(int headerIdx) {
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            if (isHeader(lines.get(i))) {
                return i;
            }
        }
        return lines.size();
    }

    /** For the preamble: [first, last) index range that may contain keys. */
    private int preambleEnd() {
        for (int i = 0; i < lines.size(); i++) {
            if (isHeader(lines.get(i))) {
                return i;
            }
        }
        return lines.size();
    }

    private int findKey(String section, String key) {
        int from;
        int to;
        if (section == null || section.isEmpty()) {
            from = 0;
            to = preambleEnd();
        } else {
            int h = headerIndex(section);
            if (h < 0) {
                return -1;
            }
            from = h + 1;
            to = bodyEnd(h);
        }
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }

    public String get(String section, String key, String fallback) {
        int i = findKey(section, key);
        if (i < 0) {
            return fallback;
        }
        String line = lines.get(i);
        return line.substring(line.indexOf('=') + 1).trim();
    }

    /** Sets a key, creating the section (or preamble entry) when needed. */
    public boolean set(String section, String key, String value) {
        String wanted = key + "=" + value;
        int i = findKey(section, key);
        if (i >= 0) {
            if (lines.get(i).equals(wanted)) {
                return false;
            }
            lines.set(i, wanted);
            return true;
        }

        if (section == null || section.isEmpty()) {
            // Insert at the end of the preamble, i.e. immediately before the first header.
            lines.add(preambleEnd(), wanted);
            return true;
        }

        int h = headerIndex(section);
        if (h < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("[" + section + "]");
            lines.add(wanted);
            return true;
        }
        lines.add(bodyEnd(h), wanted);
        return true;
    }

    /**
     * Adds or updates one element of a comma-separated list value, preserving every other element
     * and their order. Used for {@code PreprocessorDefinitions} and {@code [ADDON] LoadFromDllMain},
     * where other tools legitimately own entries too.
     *
     * @return true when something changed
     */
    public boolean addToListElement(String section, String key, String element) {
        String current = get(section, key, "");
        List<String> parts = new ArrayList<>();
        for (String p : current.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        String wantedName = element.contains("=") ? element.substring(0, element.indexOf('=')).trim() : element;
        for (int k = 0; k < parts.size(); k++) {
            String p = parts.get(k);
            String name = p.contains("=") ? p.substring(0, p.indexOf('=')).trim() : p;
            if (name.equalsIgnoreCase(wantedName)) {
                if (p.equals(element)) {
                    return false;
                }
                parts.set(k, element);
                return set(section, key, String.join(",", parts));
            }
        }
        parts.add(element);
        return set(section, key, String.join(",", parts));
    }

    /** Moves an element to the front of a comma-separated list. Used for TechniqueSorting. */
    public boolean moveToFront(String section, String key, String element) {
        String current = get(section, key, "");
        if (current.isEmpty()) {
            return false;
        }
        List<String> parts = new ArrayList<>();
        for (String p : current.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        int idx = parts.indexOf(element);
        if (idx == 0) {
            return false;
        }
        if (idx > 0) {
            parts.remove(idx);
        }
        parts.add(0, element);
        return set(section, key, String.join(",", parts));
    }

    public void removeKey(String section, String key) {
        int i = findKey(section, key);
        if (i >= 0) {
            lines.remove(i);
        }
    }

    /** All {@code key=value} pairs of a section (or of the preamble when section is null). */
    public Map<String, String> section(String section) {
        Map<String, String> m = new LinkedHashMap<>();
        int from;
        int to;
        if (section == null || section.isEmpty()) {
            from = 0;
            to = preambleEnd();
        } else {
            int h = headerIndex(section);
            if (h < 0) {
                return m;
            }
            from = h + 1;
            to = bodyEnd(h);
        }
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return m;
    }

    public List<String> rawLines() {
        return List.copyOf(lines);
    }

    public void save() throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> out = new ArrayList<>(lines);
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        // ReShade writes CRLF; match it so its own diffs stay clean.
        Files.write(path, (String.join("\r\n", out) + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}

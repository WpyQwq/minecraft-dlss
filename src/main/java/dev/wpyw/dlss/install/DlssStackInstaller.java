package dev.wpyw.dlss.install;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.wpyw.dlss.cfg.IniFile;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Installs the DLSS stack into the running JVM so that Minecraft's OpenGL renderer picks it up.
 *
 * <h2>Why the JVM's own {@code bin/} directory</h2>
 * ReShade is not a library the game links against - it is a <b>proxy DLL</b>. Windows resolves
 * {@code opengl32.dll} by searching the directory of the running executable first, so the proxy
 * must sit next to {@code javaw.exe}, and ReShade then loads its add-ons from that same directory.
 * The NGX runtimes must be there too, for the same reason (the feeder and Deep Fried Chicken both
 * look beside the executable).
 *
 * <h2>Why a restart is always required</h2>
 * A Fabric mod initialises long after {@code javaw.exe} has resolved its imports. By the time this
 * code runs, {@code opengl32.dll} has already been loaded - the stock system one, if ReShade was
 * not there at process start. Nothing can retro-fit a proxy DLL into a running process, so the
 * only honest design is: install now, activate on the next launch. Deep Fried Chicken's own
 * installer reaches the same conclusion in its logs
 * ("will only prepare the next launch").
 */
public final class DlssStackInstaller {

    private static final String PAYLOAD_ROOT = "/assets/wpywdlss/payload";
    private static final String MANIFEST_RES = PAYLOAD_ROOT + "/payload-manifest.txt";
    private static final String RECORD_NAME = "wpywdlss-install.json";
    private static final String BACKUP_DIR_NAME = "wpywdlss-backup";
    /** Payload paths are stored relative to the payload root, prefixed with the target subtree. */
    private static final String TARGET_SUBTREE = "jvm/";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DlssStackInstaller() {
    }

    // ------------------------------------------------------------------ model

    public enum Status {
        /** Nothing of ours is present. */
        NOT_INSTALLED,
        /** Every payload file is present with the expected hash. */
        INSTALLED,
        /** Partially installed, or files were changed/removed since install. */
        NEEDS_REPAIR,
        /** Blocked: the target does not look like a writable 64-bit JVM directory. */
        UNSUPPORTED
    }

    public record Entry(String relPath, String sha256, long size) {
    }

    public record Report(
            Status status,
            Path target,
            List<Entry> present,
            List<Entry> missing,
            List<Entry> modified,
            List<NgxRuntimeLocator.Found> ngx,
            List<String> ngxMissing,
            Path foreignProxyBackup,
            List<String> notes,
            boolean restartRequired) {

        public boolean ok() {
            return status == Status.INSTALLED;
        }

        /** Multi-line human-readable summary, used by the GUI and the log. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("target           : ").append(target).append('\n');
            sb.append("status           : ").append(status).append('\n');
            sb.append("payload files    : ").append(present.size()).append(" ok");
            if (!missing.isEmpty()) {
                sb.append(", ").append(missing.size()).append(" missing");
            }
            if (!modified.isEmpty()) {
                sb.append(", ").append(modified.size()).append(" modified");
            }
            sb.append('\n');
            for (NgxRuntimeLocator.Found f : ngx) {
                sb.append("ngx runtime      : ").append(f.name())
                        .append("  ").append(String.format("%,d B", f.size()))
                        .append(f.sizeMatchesNominal() ? "" : "  (size differs from verified SDK)")
                        .append('\n');
            }
            for (String m : ngxMissing) {
                sb.append("ngx runtime      : MISSING ").append(m).append('\n');
            }
            if (foreignProxyBackup != null) {
                sb.append("previous opengl32.dll backed up to: ").append(foreignProxyBackup).append('\n');
            }
            for (String n : notes) {
                sb.append("note             : ").append(n).append('\n');
            }
            if (restartRequired) {
                sb.append("action           : RESTART Minecraft - ReShade only loads at process start\n");
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ paths

    /** The directory holding {@code java.exe} / {@code javaw.exe} for the running JVM. */
    public static Path jvmBinDir() {
        return Path.of(System.getProperty("java.home"), "bin");
    }

    public static Path installRecordPath() {
        return jvmBinDir().resolve(RECORD_NAME);
    }

    private static boolean looksLikeJvmBin(Path bin) {
        return Files.isRegularFile(bin.resolve("java.exe")) || Files.isRegularFile(bin.resolve("javaw.exe"));
    }

    // ------------------------------------------------------------------ manifest

    /** Parses the SHA-256 manifest generated by Gradle at build time. */
    public static List<Entry> readManifest() throws IOException {
        try (InputStream in = DlssStackInstaller.class.getResourceAsStream(MANIFEST_RES)) {
            if (in == null) {
                throw new IOException("payload manifest missing from the jar (" + MANIFEST_RES + ")");
            }
            List<Entry> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String[] parts = t.split("\\s{2,}");
                if (parts.length < 2) {
                    continue;
                }
                long size = parts.length > 2 ? Long.parseLong(parts[2].trim()) : -1L;
                out.add(new Entry(parts[1].trim(), parts[0].trim(), size));
            }
            out.sort(Comparator.comparing(Entry::relPath));
            return out;
        }
    }

    private static String targetRelative(String payloadRelPath) {
        return payloadRelPath.startsWith(TARGET_SUBTREE)
                ? payloadRelPath.substring(TARGET_SUBTREE.length())
                : payloadRelPath;
    }

    // ------------------------------------------------------------------ analyze

    /** Read-only inspection. Never writes anything. */
    public static Report analyze() {
        Path target = jvmBinDir();
        List<String> notes = new ArrayList<>();
        List<Entry> present = new ArrayList<>();
        List<Entry> missing = new ArrayList<>();
        List<Entry> modified = new ArrayList<>();
        List<NgxRuntimeLocator.Found> ngx = new ArrayList<>();
        List<String> ngxMissing = new ArrayList<>();

        if (!looksLikeJvmBin(target)) {
            notes.add("java.home does not point at a JVM bin directory containing java.exe/javaw.exe");
            return new Report(Status.UNSUPPORTED, target, present, missing, modified, ngx, ngxMissing,
                    null, notes, false);
        }

        List<Entry> manifest;
        try {
            manifest = readManifest();
        } catch (IOException e) {
            notes.add("cannot read payload manifest: " + e.getMessage());
            return new Report(Status.UNSUPPORTED, target, present, missing, modified, ngx, ngxMissing,
                    null, notes, false);
        }

        if (manifest.isEmpty()) {
            notes.add("the jar contains no payload - build with the payload directory present");
        }

        for (Entry e : manifest) {
            Path p = target.resolve(targetRelative(e.relPath()));
            if (!Files.isRegularFile(p)) {
                missing.add(e);
                continue;
            }
            String actual = sha256(p);
            if (actual != null && actual.equalsIgnoreCase(e.sha256())) {
                present.add(e);
            } else {
                modified.add(e);
            }
        }

        NgxRuntimeLocator loc = new NgxRuntimeLocator(target,
                FabricLoader.getInstance().getGameDir(),
                FabricLoader.getInstance().getConfigDir());
        for (NgxRuntimeLocator.Found f : loc.locateAll()) {
            ngx.add(f);
        }
        for (String name : NgxRuntimeLocator.runtimeNames()) {
            boolean found = ngx.stream().anyMatch(f -> f.name().equals(name));
            if (!found) {
                ngxMissing.add(name);
            }
        }

        Status status;
        if (present.isEmpty() && missing.size() == manifest.size()) {
            status = Status.NOT_INSTALLED;
        } else if (missing.isEmpty() && modified.isEmpty()) {
            status = Status.INSTALLED;
        } else {
            status = Status.NEEDS_REPAIR;
        }

        // A foreign proxy (a ReShade build we did not put there, or another injector) matters.
        Path proxy = target.resolve("opengl32.dll");
        boolean proxyIsOurs = present.stream().anyMatch(e -> targetRelative(e.relPath()).equalsIgnoreCase("opengl32.dll"));
        if (Files.isRegularFile(proxy) && !proxyIsOurs) {
            notes.add("a different opengl32.dll is already present; installing will back it up");
        }

        return new Report(status, target, present, missing, modified, ngx, ngxMissing, null, notes, false);
    }

    // ------------------------------------------------------------------ install

    /**
     * Copies the payload into the JVM bin directory and writes the ReShade configuration.
     * Idempotent: re-running repairs missing or altered files and leaves everything else alone.
     */
    public static Report install() throws IOException {
        Path target = jvmBinDir();
        List<String> notes = new ArrayList<>();
        List<Entry> present = new ArrayList<>();
        List<Entry> missing = new ArrayList<>();
        List<Entry> modified = new ArrayList<>();
        List<NgxRuntimeLocator.Found> ngx = new ArrayList<>();
        List<String> ngxMissing = new ArrayList<>();

        if (!looksLikeJvmBin(target)) {
            notes.add("refusing to install: " + target + " is not a JVM bin directory");
            return new Report(Status.UNSUPPORTED, target, present, missing, modified, ngx, ngxMissing,
                    null, notes, false);
        }

        Files.createDirectories(target);
        Path backupDir = target.resolve(BACKUP_DIR_NAME);
        Path foreignProxyBackup = null;

        List<Entry> manifest = readManifest();
        if (manifest.isEmpty()) {
            notes.add("nothing to install: the jar contains no payload");
            return new Report(Status.UNSUPPORTED, target, present, missing, modified, ngx, ngxMissing,
                    null, notes, false);
        }

        // --- 1. preserve any opengl32.dll we did not put there -----------------
        boolean payloadOwnsProxy = manifest.stream()
                .anyMatch(e -> targetRelative(e.relPath()).equalsIgnoreCase("opengl32.dll"));
        Path proxy = target.resolve("opengl32.dll");
        if (payloadOwnsProxy && Files.isRegularFile(proxy)) {
            String existing = sha256(proxy);
            Entry expected = manifest.stream()
                    .filter(e -> targetRelative(e.relPath()).equalsIgnoreCase("opengl32.dll"))
                    .findFirst().orElse(null);
            if (expected != null && existing != null && !existing.equalsIgnoreCase(expected.sha256())) {
                Files.createDirectories(backupDir);
                Path bak = backupDir.resolve("opengl32.dll." + stamp() + ".bak");
                Files.copy(proxy, bak, StandardCopyOption.REPLACE_EXISTING);
                foreignProxyBackup = bak;
                notes.add("existing opengl32.dll was not ours and has been backed up");
            }
        }

        // --- 2. extract / repair every payload file ---------------------------
        long copied = 0;
        long bytes = 0;
        for (Entry e : manifest) {
            Path dest = target.resolve(targetRelative(e.relPath()));
            boolean needWrite = true;
            if (Files.isRegularFile(dest)) {
                String actual = sha256(dest);
                if (actual != null && actual.equalsIgnoreCase(e.sha256())) {
                    needWrite = false;
                    present.add(e);
                } else {
                    modified.add(e);
                }
            } else {
                missing.add(e);
            }
            if (!needWrite) {
                continue;
            }

            try (InputStream in = DlssStackInstaller.class.getResourceAsStream(PAYLOAD_ROOT + "/" + e.relPath())) {
                if (in == null) {
                    notes.add("payload resource missing from jar: " + e.relPath());
                    continue;
                }
                Files.createDirectories(dest.getParent());
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
                bytes += Files.size(dest);
                present.add(e);
                missing.remove(e);
                modified.remove(e);
            }
        }
        notes.add(String.format("wrote %d file(s), %,.2f MB", copied, bytes / 1048576.0));

        // --- 3. NVIDIA NGX runtimes ------------------------------------------
        NgxRuntimeLocator loc = new NgxRuntimeLocator(target,
                FabricLoader.getInstance().getGameDir(),
                FabricLoader.getInstance().getConfigDir());
        for (NgxRuntimeLocator.Found f : loc.locateAll()) {
            Path dest = target.resolve(f.name());
            if (!dest.equals(f.path())) {
                Files.copy(f.path(), dest, StandardCopyOption.REPLACE_EXISTING);
                notes.add("copied " + f.name() + " from " + f.path());
            }
            ngx.add(new NgxRuntimeLocator.Found(f.name(), dest, Files.size(dest),
                    f.sizeMatchesNominal(), f.origin()));
        }
        for (String name : NgxRuntimeLocator.runtimeNames()) {
            if (ngx.stream().noneMatch(f -> f.name().equals(name))) {
                ngxMissing.add(name);
                notes.add("NVIDIA runtime not found anywhere: " + name
                        + " - place an official copy next to javaw.exe, or rebuild with -PbundleNvidia=true");
            }
        }

        // --- 4. ReShade configuration ----------------------------------------
        writeReshadeIni(target, backupDir, notes);
        writeReshadePreset(target, backupDir, notes);

        // --- 5. install record ------------------------------------------------
        writeRecord(target, manifest, ngx);

        Status status = (missing.isEmpty() && modified.isEmpty())
                ? Status.INSTALLED
                : Status.NEEDS_REPAIR;
        return new Report(status, target, present, missing, modified, ngx, ngxMissing,
                foreignProxyBackup, notes, true);
    }

    // ------------------------------------------------------------------ ini writers

    private static final String DEFAULT_RESHADE_INI = """
            [DEPTH]
            DepthCopyAtClearIndex=0
            DepthCopyBeforeClears=1
            DrawStatsHeuristic=0
            UseAspectRatioHeuristics=1

            [GENERAL]
            EffectSearchPaths=.\\reshade-shaders\\Shaders\\**
            NoDebugInfo=1
            NoEffectCache=0
            NoReloadOnInit=0
            PerformanceMode=0
            PresetPath=.\\ReShadePreset.ini
            PreprocessorDefinitions=RESHADE_DEPTH_LINEARIZATION_FAR_PLANE=1000.0,RESHADE_DEPTH_INPUT_IS_UPSIDE_DOWN=0,RESHADE_DEPTH_INPUT_IS_REVERSED=0,RESHADE_DEPTH_INPUT_IS_LOGARITHMIC=0,DLSS5_MV_PROVIDER=3
            TextureSearchPaths=.\\reshade-shaders\\Textures\\**

            [INPUT]
            GamepadNavigation=0
            KeyOverlay=36,0,0,0

            [PROXY]
            EnableProxyLibrary=0
            ProxyLibrary=
            """;

    private static void writeReshadeIni(Path target, Path backupDir, List<String> notes) throws IOException {
        Path ini = target.resolve("ReShade.ini");
        boolean existed = Files.isRegularFile(ini);
        IniFile f = IniFile.load(ini);

        if (!existed || f.isNew()) {
            Files.writeString(ini, DEFAULT_RESHADE_INI, StandardCharsets.UTF_8);
            notes.add("wrote a fresh ReShade.ini");
            return;
        }

        // Merge rather than replace: ReShade and Deep Fried Chicken both own keys in this file.
        Files.createDirectories(backupDir);
        Files.copy(ini, backupDir.resolve("ReShade.ini." + stamp() + ".bak"), StandardCopyOption.REPLACE_EXISTING);

        boolean changed = false;
        // The motion-vector provider is what makes DLSS5_Feed.fx able to find its input.
        changed |= f.addToListElement("GENERAL", "PreprocessorDefinitions", "DLSS5_MV_PROVIDER=3");
        // Minecraft's depth is not reversed - confirmed by direct observation in-game.
        changed |= f.addToListElement("GENERAL", "PreprocessorDefinitions", "RESHADE_DEPTH_INPUT_IS_REVERSED=0");
        changed |= f.set("GENERAL", "EffectSearchPaths", ".\\reshade-shaders\\Shaders\\**");
        changed |= f.set("GENERAL", "TextureSearchPaths", ".\\reshade-shaders\\Textures\\**");
        changed |= f.set("GENERAL", "PresetPath", ".\\ReShadePreset.ini");
        changed |= f.set("DEPTH", "UseAspectRatioHeuristics", "1");
        changed |= f.set("INPUT", "KeyOverlay", "36,0,0,0");
        if (changed) {
            f.save();
            notes.add("merged required keys into the existing ReShade.ini (backed up)");
        } else {
            notes.add("existing ReShade.ini already had every required key");
        }
    }

    private static final String PROVIDER = "Lumenite_Kernel@lumenite_Kernel.fx";
    private static final String FEEDER = "DLSS5_Feed@DLSS5_Feed.fx";

    private static void writeReshadePreset(Path target, Path backupDir, List<String> notes) throws IOException {
        Path preset = target.resolve("ReShadePreset.ini");
        boolean existed = Files.isRegularFile(preset);
        IniFile f = IniFile.load(preset);

        if (!existed || f.isNew()) {
            Files.writeString(preset,
                    "# Managed by wpywdlss.\n"
                            + "# Techniques= lists what is ENABLED; TechniqueSorting= defines EXECUTION ORDER,\n"
                            + "# and the motion-vector provider must run before the consumer that reads it.\n"
                            + "Techniques=" + PROVIDER + "," + FEEDER + "\n"
                            + "TechniqueSorting=" + PROVIDER + "," + FEEDER + "\n",
                    StandardCharsets.UTF_8);
            notes.add("wrote a fresh ReShadePreset.ini with the provider ordered before the feeder");
            return;
        }

        Files.createDirectories(backupDir);
        Files.copy(preset, backupDir.resolve("ReShadePreset.ini." + stamp() + ".bak"), StandardCopyOption.REPLACE_EXISTING);

        boolean changed = false;
        // Enable both, without disabling whatever else the user had enabled.
        // null section = the file's preamble, which is where these two keys live.
        changed |= f.addToListElement(null, "Techniques", PROVIDER);
        changed |= f.addToListElement(null, "Techniques", FEEDER);
        // The provider must execute before the consumer that reads its texture.
        changed |= f.moveToFront(null, "TechniqueSorting", PROVIDER);
        if (changed) {
            f.save();
            notes.add("merged the motion-vector provider and feeder into the existing preset (backed up)");
        }
    }

    // ------------------------------------------------------------------ record / uninstall

    private static void writeRecord(Path target, List<Entry> manifest,
                                    List<NgxRuntimeLocator.Found> ngx) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("installedUtc", Instant.now().toString());
        root.addProperty("target", target.toString());
        root.addProperty("javaHome", System.getProperty("java.home"));
        root.addProperty("javaVersion", System.getProperty("java.version"));

        JsonArray files = new JsonArray();
        for (Entry e : manifest) {
            JsonObject o = new JsonObject();
            o.addProperty("path", targetRelative(e.relPath()));
            o.addProperty("sha256", e.sha256());
            o.addProperty("size", e.size());
            files.add(o);
        }
        root.add("files", files);

        JsonArray runtimes = new JsonArray();
        for (NgxRuntimeLocator.Found f : ngx) {
            JsonObject o = new JsonObject();
            o.addProperty("name", f.name());
            o.addProperty("origin", f.origin());
            o.addProperty("source", f.path().toString());
            runtimes.add(o);
        }
        root.add("ngxRuntimes", runtimes);

        Files.writeString(target.resolve(RECORD_NAME), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /** Reads back the install record, or null when there is none / it is unreadable. */
    public static JsonObject readRecord() {
        Path p = installRecordPath();
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Removes only the files this installer recorded, and restores any proxy it displaced.
     * Files it does not own (ReShade's own later additions, other tools) are left alone.
     */
    public static Report uninstall() throws IOException {
        Path target = jvmBinDir();
        List<String> notes = new ArrayList<>();
        List<Entry> removed = new ArrayList<>();
        JsonObject rec = readRecord();
        if (rec == null) {
            notes.add("no install record found - nothing to remove");
            return new Report(Status.NOT_INSTALLED, target, List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, notes, true);
        }
        if (rec.has("files")) {
            for (var el : rec.getAsJsonArray("files")) {
                String rel = el.getAsJsonObject().get("path").getAsString();
                Path p = target.resolve(rel);
                if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                    removed.add(new Entry(rel, "", 0));
                }
            }
        }
        Files.deleteIfExists(target.resolve(RECORD_NAME));

        // Restore a displaced proxy, if we made a backup.
        Path backupDir = target.resolve(BACKUP_DIR_NAME);
        if (Files.isDirectory(backupDir)) {
            try (Stream<Path> s = Files.list(backupDir)) {
                Path newest = s.filter(p -> p.getFileName().toString().startsWith("opengl32.dll."))
                        .max(Comparator.comparing(p -> p.getFileName().toString()))
                        .orElse(null);
                if (newest != null) {
                    Files.copy(newest, target.resolve("opengl32.dll"), StandardCopyOption.REPLACE_EXISTING);
                    notes.add("restored the previous opengl32.dll from " + newest.getFileName());
                }
            }
        }
        notes.add("removed " + removed.size() + " file(s); ReShade's own later additions were left alone");
        return new Report(Status.NOT_INSTALLED, target, List.of(), List.of(), List.of(),
                List.of(), List.of(), null, notes, true);
    }

    // ------------------------------------------------------------------ util

    public static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stamp() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
    }

    /** Convenience for the GUI: a short one-line state. */
    public static String shortState() {
        Report r = analyze();
        if (r.status() == Status.INSTALLED) {
            return "installed";
        }
        if (r.status() == Status.NOT_INSTALLED) {
            return "not installed";
        }
        if (r.status() == Status.UNSUPPORTED) {
            return "unsupported";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("missing", r.missing().size());
        counts.put("modified", r.modified().size());
        return "needs repair (" + counts.get("missing") + " missing, " + counts.get("modified") + " modified)";
    }
}

package dev.wpyw.dlss.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Locates the NVIDIA NGX runtimes this stack needs.
 *
 * <p>Both files must end up <b>next to the game executable</b> - for Minecraft Java that is the
 * JVM's own {@code bin/} directory, because that is where {@code java.exe}/{@code javaw.exe}
 * live. Deep Fried Chicken's installer says the same thing in its own words ("Copy these beside
 * the actual game executable"), and it is why its host helper looks for the runtimes next to
 * itself rather than in the game folder.
 *
 * <p>We deliberately do not ship these DLLs by default: together they are ~225 MB, they are
 * NVIDIA proprietary binaries, and on any machine that has already run this stack once they are
 * sitting in the target directory already. When they are missing this class searches a bounded
 * set of plausible roots, and the installer copies whatever it finds.
 *
 * <p>The nominal sizes come from the SDK that was actually verified on this machine, so a
 * wildly different file is rejected rather than copied.
 */
public final class NgxRuntimeLocator {

    /** name -> {minimum plausible size, nominal size seen in the verified SDK} */
    private static final Object[][] KNOWN = {
            {"nvngx_dlss.dll",   20L * 1024 * 1024, 58_956_912L},
            {"nvngx_dlssnr.dll", 40L * 1024 * 1024, 165_840_496L},
    };

    /** A located runtime: where it is and whether it is trustworthy enough to copy. */
    public record Found(String name, Path path, long size, boolean sizeMatchesNominal, String origin) {
        public String describe() {
            return String.format("%s  %,d B%s  [%s]%n    %s",
                    name, size, sizeMatchesNominal ? "" : "  (size differs from verified SDK)", origin, path);
        }
    }

    private final Path target;
    private final List<Path> extraRoots = new ArrayList<>();

    public NgxRuntimeLocator(Path targetBinDir, Path gameDir, Path modConfigDir) {
        this.target = targetBinDir;
        if (gameDir != null) {
            extraRoots.add(gameDir);
        }
        if (modConfigDir != null) {
            extraRoots.add(modConfigDir);
        }
    }

    /** Adds a user-configured extra search root. */
    public void addRoot(Path root) {
        if (root != null) {
            extraRoots.add(root);
        }
    }

    public static List<String> runtimeNames() {
        List<String> names = new ArrayList<>();
        for (Object[] row : KNOWN) {
            names.add((String) row[0]);
        }
        return names;
    }

    /** The path a runtime must occupy for the feeder and DFC to load it. */
    public Path expectedPath(String name) {
        return target.resolve(name);
    }

    public boolean isInPlace(String name) {
        return verify(expectedPath(name)) != null;
    }

    /** Returns null when the file is unusable, otherwise a short verdict string. */
    public static String verify(Path p) {
        if (p == null || !Files.isRegularFile(p)) {
            return null;
        }
        long size;
        try {
            size = Files.size(p);
        } catch (IOException e) {
            return null;
        }
        String name = p.getFileName().toString();
        long min = 1L * 1024 * 1024;
        for (Object[] row : KNOWN) {
            if (row[0].equals(name)) {
                min = (Long) row[1];
            }
        }
        if (size < min) {
            return "too small (" + size + " B)";
        }
        if (!PeProbe.isX64Pe(p)) {
            return "not a 64-bit PE image";
        }
        return String.format("%,d B, x64 PE", size);
    }

    /**
     * Finds a usable copy of every runtime, preferring the target directory (already correct),
     * then the extra roots, then a bounded scan of each root.
     */
    public List<Found> locateAll() {
        List<Found> out = new ArrayList<>();
        for (Object[] row : KNOWN) {
            String name = (String) row[0];
            long nominal = (Long) row[2];

            Path inPlace = expectedPath(name);
            if (Files.isRegularFile(inPlace)) {
                long sz = sizeOf(inPlace);
                out.add(new Found(name, inPlace, sz, sz == nominal, "already in the target directory"));
                continue;
            }

            Found found = null;
            for (Path root : searchRoots()) {
                Path direct = root.resolve(name);
                if (Files.isRegularFile(direct)) {
                    found = new Found(name, direct, sizeOf(direct), sizeOf(direct) == nominal, "direct hit");
                    break;
                }
                Path scanned = scanFor(root, name);
                if (scanned != null) {
                    found = new Found(name, scanned, sizeOf(scanned), sizeOf(scanned) == nominal,
                            "found by bounded scan under " + root);
                    break;
                }
            }
            if (found != null) {
                out.add(found);
            }
        }
        return out;
    }

    private Set<Path> searchRoots() {
        // Ordered, de-duplicated. The target comes first so an existing correct install wins.
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(target);
        for (Path p : extraRoots) {
            if (p != null && Files.isDirectory(p)) {
                roots.add(p);
            }
        }
        return roots;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Depth-bounded recursive search. Minecraft mod folders contain a lot of jars, and the whole
     * point is to stay fast, so this stays shallow.
     */
    private static Path scanFor(Path root, String name) {
        try (Stream<Path> s = Files.walk(root, 4)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Minimal PE header probe: DOS magic, PE signature, COFF machine == AMD64. */
    public static final class PeProbe {
        private PeProbe() {
        }

        public static boolean isX64Pe(Path p) {
            try (InputStream in = Files.newInputStream(p)) {
                byte[] head = in.readNBytes(0x1000);
                if (head.length < 0x40 || head[0] != 'M' || head[1] != 'Z') {
                    return false;
                }
                int peOff = u32(head, 0x3C);
                if (peOff < 0 || peOff + 6 > head.length) {
                    return false;
                }
                if (head[peOff] != 'P' || head[peOff + 1] != 'E' || head[peOff + 2] != 0 || head[peOff + 3] != 0) {
                    return false;
                }
                int machine = (head[peOff + 4] & 0xFF) | ((head[peOff + 5] & 0xFF) << 8);
                return machine == 0x8664; // IMAGE_FILE_MACHINE_AMD64
            } catch (IOException e) {
                return false;
            }
        }

        private static int u32(byte[] b, int off) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
        }
    }
}

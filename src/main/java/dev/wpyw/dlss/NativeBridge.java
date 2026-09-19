package dev.wpyw.dlss;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Thin Java facade over the native bridge DLL.
 *
 * <p>The bridge does the things Java cannot: create a Vulkan device, share textures with
 * Minecraft's OpenGL context through Win32 external memory, and (later) drive NVIDIA
 * Streamline / NGX.
 *
 * <p>The DLL is loaded by extracting it from this mod's own jar to a temp directory, because
 * {@code System.loadLibrary} cannot read inside a jar.
 */
public final class NativeBridge {
    private static final String[] RESOURCE_CANDIDATES = {
            "/assets/wpywdlss/native/windows-x86_64/wpywdlss_bridge.dll",
            "/assets/wpywdlss/native/windows-x86_64/wpywdlss_bridge_debug.dll",
    };

    private static boolean attempted;
    private static boolean loaded;
    private static String loadError = "not attempted";
    private static Path loadedFrom;

    private NativeBridge() {
    }

    public static synchronized boolean isLoaded() {
        return loaded;
    }

    public static synchronized String loadError() {
        return loadError;
    }

    public static synchronized Path loadedFrom() {
        return loadedFrom;
    }

    /** Loads the bridge exactly once. Returns true on success. Never throws. */
    public static synchronized boolean load() {
        if (attempted) {
            return loaded;
        }
        attempted = true;

        try {
            // Dev override: point straight at a build output so a rebuild needs no repackaging.
            String override = System.getProperty("wpywdlss.bridgePath");
            if (override != null && !override.isBlank()) {
                Path p = Path.of(override);
                if (Files.isRegularFile(p)) {
                    System.load(p.toAbsolutePath().toString());
                    loaded = true;
                    loadedFrom = p;
                    loadError = null;
                    return true;
                }
                loadError = "wpywdlss.bridgePath set but not a file: " + override;
                return false;
            }

            Path dir = Files.createTempDirectory("wpywdlss-bridge");
            dir.toFile().deleteOnExit();

            for (String resource : RESOURCE_CANDIDATES) {
                try (InputStream in = NativeBridge.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        continue;
                    }
                    String fileName = resource.substring(resource.lastIndexOf('/') + 1);
                    Path target = dir.resolve(fileName);
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                    System.load(target.toAbsolutePath().toString());
                    loaded = true;
                    loadedFrom = target;
                    loadError = null;
                    return true;
                }
            }
            loadError = "no bridge DLL found in jar resources (looked for "
                    + String.join(", ", RESOURCE_CANDIDATES) + ")";
        } catch (IOException | UnsatisfiedLinkError | SecurityException e) {
            loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return false;
    }

    // ---- native surface -------------------------------------------------

    /** Bridge build identifier, e.g. "0.1.0-p0". */
    public static native String nativeVersion();

    /** Runs the phase-0 Vulkan / interop capability probe and returns a report. */
    public static native String nativeProbe();

    /**
     * Creates a hidden WGL context and reports the OpenGL side of the interop story:
     * whether GL_EXT_memory_object_win32 / GL_EXT_semaphore_win32 are present and
     * whether the interop entry points resolve. This is the go/no-go check for every
     * AI-upscaling route, because OpenGL cannot export memory -- a Vulkan/D3D12 device
     * allocates and exports, and GL imports.
     */
    public static native String nativeProbeOpenGL();

    /** True once a Vulkan device is up and the interop entry points resolved. */
    public static native boolean nativeIsReady();

    /** Destroys the Vulkan device and instance. */
    public static native void nativeShutdown();
}

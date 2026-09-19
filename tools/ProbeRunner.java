import dev.wpyw.dlss.NativeBridge;

/**
 * Standalone harness for the native bridge, so capability results can be verified
 * WITHOUT launching Minecraft.
 *
 * run:
 *   $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
 *   java -cp "build\classes\java\main" `
 *        -Dwpywdlss.bridgePath=native\build\wpywdlss_bridge.dll `
 *        tools\ProbeRunner.java
 */
public class ProbeRunner {
    public static void main(String[] args) {
        System.out.println("=== wpywdlss native bridge probe ===");
        System.out.println();

        if (!NativeBridge.load()) {
            System.out.println("[FAIL] could not load bridge DLL");
            System.out.println("       " + NativeBridge.loadError());
            System.exit(1);
        }
        System.out.println("bridge loaded from : " + NativeBridge.loadedFrom());
        System.out.println("bridge version     : " + NativeBridge.nativeVersion());
        System.out.println();

        // ---- Vulkan side -------------------------------------------------
        System.out.println(NativeBridge.nativeProbe());
        boolean vkReady = NativeBridge.nativeIsReady();
        System.out.println("nativeIsReady() = " + vkReady);
        System.out.println();

        // ---- OpenGL side -------------------------------------------------
        System.out.println(NativeBridge.nativeProbeOpenGL());

        NativeBridge.nativeShutdown();
        System.out.println("shutdown ok");
        System.exit(vkReady ? 0 : 2);
    }
}

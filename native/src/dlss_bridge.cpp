// ---------------------------------------------------------------------------
// wpywdlss native bridge -- phase 0
//
// Purpose
// -------
// Minecraft Java renders with OpenGL, but NVIDIA's DLSS lives behind NGX /
// Streamline, which only speak D3D11 / D3D12 / Vulkan. There is no OpenGL path
// into DLSS. The way through is therefore:
//
//   OpenGL renders  ->  share the texture with Vulkan (Win32 external memory)
//                   ->  run DLSS on Vulkan
//                   ->  share the result back to OpenGL
//
// Before writing any DLSS code we must prove that sharing actually works on the
// target machine. This file does exactly that, and reports everything it finds
// as one human-readable string so the Java side can log it.
//
// It deliberately does NOT need the NVIDIA SDKs to build: phase 0 only needs
// the Vulkan loader plus a few Win32 external-memory entry points we fetch at
// runtime via vkGetDeviceProcAddr.
// ---------------------------------------------------------------------------

#include <jni.h>

#include <windows.h>

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_win32.h>

#include <algorithm>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char* kBridgeVersion = "0.1.0-p0";

// --------------------------------------------------------------------------
// small helpers
// --------------------------------------------------------------------------

std::string vkResultName(VkResult r) {
    switch (r) {
        case VK_SUCCESS:                        return "VK_SUCCESS";
        case VK_NOT_READY:                      return "VK_NOT_READY";
        case VK_TIMEOUT:                        return "VK_TIMEOUT";
        case VK_INCOMPLETE:                     return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY:       return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:     return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED:    return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST:              return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_LAYER_NOT_PRESENT:        return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT:    return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER:      return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_FORMAT_NOT_SUPPORTED:     return "VK_ERROR_FORMAT_NOT_SUPPORTED";
        default: {
            std::ostringstream os;
            os << "VkResult(" << static_cast<int>(r) << ")";
            return os.str();
        }
    }
}

std::string apiVersionString(uint32_t v) {
    std::ostringstream os;
    os << VK_API_VERSION_MAJOR(v) << '.' << VK_API_VERSION_MINOR(v)
       << '.' << VK_API_VERSION_PATCH(v);
    return os.str();
}

std::string driverVersionString(uint32_t v) {
    // NVIDIA encodes the driver version in a slightly odd way.
    std::ostringstream os;
    os << ((v >> 22) & 0x3FF) << '.' << ((v >> 14) & 0x0FF) << '.'
       << ((v >> 6) & 0x0FF) << '.' << (v & 0x03F);
    return os.str();
}

const char* yesNo(bool b) { return b ? "YES" : "no "; }

bool hasExtension(const std::vector<VkExtensionProperties>& list, const char* name) {
    return std::any_of(list.begin(), list.end(), [name](const VkExtensionProperties& e) {
        return std::strcmp(e.extensionName, name) == 0;
    });
}

// --------------------------------------------------------------------------
// GL<->VK interop symbol table
//
// These are device-level extension entry points; the Vulkan loader does NOT
// export them, so they must be resolved through vkGetDeviceProcAddr.
// --------------------------------------------------------------------------

struct InteropFns {
    PFN_vkGetMemoryWin32HandleKHR         getMemoryWin32Handle      = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR      getSemaphoreWin32Handle   = nullptr;
    PFN_vkImportSemaphoreWin32HandleKHR   importSemaphoreWin32Handle = nullptr;
};

InteropFns resolveInteropFns(VkDevice device) {
    InteropFns f{};
    auto load = [device](const char* n) {
        return vkGetDeviceProcAddr(device, n);
    };
    f.getMemoryWin32Handle =
        reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(load("vkGetMemoryWin32HandleKHR"));
    f.getSemaphoreWin32Handle =
        reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(load("vkGetSemaphoreWin32HandleKHR"));
    f.importSemaphoreWin32Handle =
        reinterpret_cast<PFN_vkImportSemaphoreWin32HandleKHR>(load("vkImportSemaphoreWin32HandleKHR"));
    return f;
}

// --------------------------------------------------------------------------
// probe state (kept alive between probe() and shutdown())
// --------------------------------------------------------------------------

struct ProbeState {
    VkInstance       instance = VK_NULL_HANDLE;
    VkDevice         device   = VK_NULL_HANDLE;
    VkPhysicalDevice phys     = VK_NULL_HANDLE;
    bool             valid    = false;
};
ProbeState g_state;

// --------------------------------------------------------------------------

void appendExternalImageSupport(std::ostringstream& os, VkPhysicalDevice phys,
                                VkExternalMemoryHandleTypeFlagBits handleType,
                                const char* label) {
    VkPhysicalDeviceExternalImageFormatInfo extInfo{};
    extInfo.sType      = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    extInfo.handleType = handleType;

    VkPhysicalDeviceImageFormatInfo2 fmtInfo{};
    fmtInfo.sType  = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    fmtInfo.pNext  = &extInfo;
    fmtInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    fmtInfo.type   = VK_IMAGE_TYPE_2D;
    fmtInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    fmtInfo.usage  = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                     VK_IMAGE_USAGE_SAMPLED_BIT |
                     VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                     VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    fmtInfo.flags  = 0;

    VkExternalImageFormatProperties extProps{};
    extProps.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;

    VkImageFormatProperties2 props{};
    props.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    props.pNext = &extProps;

    VkResult r = vkGetPhysicalDeviceImageFormatProperties2(phys, &fmtInfo, &props);
    os << "    " << label << " (R8G8B8A8_UNORM 2D OPTIMAL, color|sampled|transfer): ";
    if (r != VK_SUCCESS) {
        os << "query failed -> " << vkResultName(r) << "\n";
        return;
    }
    const auto& m = extProps.externalMemoryProperties;
    os << "exportable=" << yesNo(m.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)
       << " importable=" << yesNo(m.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT)
       << " dedicatedOnly=" << yesNo(m.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT)
       << "\n";
}

std::string runProbe() {
    std::ostringstream os;
    os << "wpywdlss native bridge " << kBridgeVersion << "\n";
    os << "========================================================\n";

    // ---- 1. loader ------------------------------------------------------
    // vulkan-1.dll 1.4.x always exports this; we link it directly.
    uint32_t loaderVersion = 0;
    VkResult verResult = vkEnumerateInstanceVersion(&loaderVersion);
    os << "[1] Vulkan loader\n";
    os << "    vkEnumerateInstanceVersion : " << vkResultName(verResult) << "\n";
    os << "    instance version : " << apiVersionString(loaderVersion) << "\n";

    // ---- 2. instance extensions we need ---------------------------------
    uint32_t extCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> instExts(extCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extCount, instExts.data());

    const char* wantInstanceExts[] = {
        VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
        VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_WIN32_SURFACE_EXTENSION_NAME,
    };
    os << "    instance extensions (" << instExts.size() << " available)\n";
    std::vector<const char*> enabledInstExts;
    for (const char* w : wantInstanceExts) {
        bool ok = hasExtension(instExts, w);
        os << "      " << yesNo(ok) << "  " << w << "\n";
        if (ok) enabledInstExts.push_back(w);
    }

    // ---- 3. create instance ---------------------------------------------
    VkApplicationInfo appInfo{};
    appInfo.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName   = "Minecraft (wpywdlss)";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 20, 1);
    appInfo.pEngineName        = "wpywdlss-bridge";
    appInfo.engineVersion      = VK_MAKE_VERSION(0, 1, 0);
    // Ask for 1.2: FSR/DLSS-grade temporal work needs timeline semaphores.
    appInfo.apiVersion         = VK_API_VERSION_1_2;

    VkInstanceCreateInfo ci{};
    ci.sType                   = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo        = &appInfo;
    ci.enabledExtensionCount   = static_cast<uint32_t>(enabledInstExts.size());
    ci.ppEnabledExtensionNames = enabledInstExts.data();

    os << "[2] vkCreateInstance\n";
    VkResult r = vkCreateInstance(&ci, nullptr, &g_state.instance);
    os << "    result : " << vkResultName(r) << "\n";
    if (r != VK_SUCCESS) {
        return os.str();
    }

    // ---- 4. pick a physical device --------------------------------------
    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(g_state.instance, &devCount, nullptr);
    if (devCount == 0) {
        os << "[3] no Vulkan physical devices found\n";
        return os.str();
    }
    std::vector<VkPhysicalDevice> devices(devCount);
    vkEnumeratePhysicalDevices(g_state.instance, &devCount, devices.data());

    os << "[3] physical devices (" << devCount << ")\n";
    VkPhysicalDevice best = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties bestProps{};
    for (VkPhysicalDevice d : devices) {
        VkPhysicalDeviceProperties p{};
        vkGetPhysicalDeviceProperties(d, &p);
        const bool isNvidia = (p.vendorID == 0x10DE);
        os << "    - " << p.deviceName
           << "  vendor=0x" << std::hex << p.vendorID << std::dec
           << "  type=" << p.deviceType
           << "  api=" << apiVersionString(p.apiVersion)
           << "  driver=" << driverVersionString(p.driverVersion)
           << (isNvidia ? "   <-- NVIDIA" : "") << "\n";

        // Preference order: NVIDIA (DLSS requires it) > discrete > anything else.
        bool better = false;
        if (best == VK_NULL_HANDLE) {
            better = true;
        } else if (isNvidia) {
            better = true;
        } else {
            better = bestProps.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU &&
                     p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
        }
        if (better) {
            best = d;
            bestProps = p;
        }
    }

    g_state.phys = best;
    const VkPhysicalDeviceProperties& props = bestProps;

    // ---- 5. device extensions for sharing -------------------------------
    uint32_t dxCount = 0;
    vkEnumerateDeviceExtensionProperties(best, nullptr, &dxCount, nullptr);
    std::vector<VkExtensionProperties> devExts(dxCount);
    vkEnumerateDeviceExtensionProperties(best, nullptr, &dxCount, devExts.data());

    const char* wantDeviceExts[] = {
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
        VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
        VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME,
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
    };
    os << "[4] device extensions (" << devExts.size() << " available)\n";
    std::vector<const char*> enabledDevExts;
    for (const char* w : wantDeviceExts) {
        bool ok = hasExtension(devExts, w);
        os << "      " << yesNo(ok) << "  " << w << "\n";
        if (ok) enabledDevExts.push_back(w);
    }

    // ---- 6. queue family -------------------------------------------------
    uint32_t qCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(best, &qCount, nullptr);
    std::vector<VkQueueFamilyProperties> queues(qCount);
    vkGetPhysicalDeviceQueueFamilyProperties(best, &qCount, queues.data());

    uint32_t queueIndex = UINT32_MAX;
    for (uint32_t i = 0; i < qCount; ++i) {
        if ((queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) &&
            (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
            queueIndex = i;
            break;
        }
    }
    if (queueIndex == UINT32_MAX) {
        os << "[5] no graphics+compute queue family found\n";
        return os.str();
    }

    // ---- 7. create the device -------------------------------------------
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = queueIndex;
    qci.queueCount       = 1;
    qci.pQueuePriorities = &priority;

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline{};
    timeline.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    timeline.timelineSemaphore = VK_TRUE;

    VkDeviceCreateInfo dci{};
    dci.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.pNext                   = &timeline;
    dci.queueCreateInfoCount    = 1;
    dci.pQueueCreateInfos       = &qci;
    dci.enabledExtensionCount   = static_cast<uint32_t>(enabledDevExts.size());
    dci.ppEnabledExtensionNames = enabledDevExts.data();

    os << "[5] vkCreateDevice (queue family " << queueIndex << ")\n";
    r = vkCreateDevice(best, &dci, nullptr, &g_state.device);
    os << "    result : " << vkResultName(r) << "\n";
    if (r != VK_SUCCESS) {
        return os.str();
    }
    g_state.valid = true;

    // ---- 8. resolve the interop entry points ----------------------------
    InteropFns fns = resolveInteropFns(g_state.device);
    os << "[6] GL<->VK interop entry points (via vkGetDeviceProcAddr)\n";
    os << "      " << yesNo(fns.getMemoryWin32Handle != nullptr)
       << "  vkGetMemoryWin32HandleKHR        (export VK memory as a Win32 HANDLE)\n";
    os << "      " << yesNo(fns.getSemaphoreWin32Handle != nullptr)
       << "  vkGetSemaphoreWin32HandleKHR     (export semaphore)\n";
    os << "      " << yesNo(fns.importSemaphoreWin32Handle != nullptr)
       << "  vkImportSemaphoreWin32HandleKHR  (import GL-exported semaphore)\n";
    if (!fns.getMemoryWin32Handle || !fns.importSemaphoreWin32Handle) {
        os << "    >> sharing is NOT available; this blocks the whole approach\n";
        return os.str();
    }

    // ---- 9. THE decisive question: can this device export images? -------
    os << "[7] external image export support  <<< this is the go/no-go check\n";
    appendExternalImageSupport(os, best, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT, "OPAQUE_WIN32    ");
    appendExternalImageSupport(os, best, VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT, "D3D11_TEXTURE   ");
    appendExternalImageSupport(os, best, VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_HEAP_BIT, "D3D12_HEAP      ");

    // ---- 10. semaphore sharing ------------------------------------------
    os << "[8] external semaphore support\n";
    {
        VkPhysicalDeviceExternalSemaphoreInfo si{};
        si.sType      = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
        si.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkExternalSemaphoreProperties sp{};
        sp.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;
        vkGetPhysicalDeviceExternalSemaphoreProperties(best, &si, &sp);
        os << "    OPAQUE_WIN32 : exportable="
           << yesNo(sp.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT)
           << " importable="
           << yesNo(sp.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT)
           << "\n";
    }

    // ---- 11. memory budget ----------------------------------------------
    os << "[9] device properties\n";
    os << "    device name    : " << props.deviceName << "\n";
    os << "    device type    : " << props.deviceType << "\n";
    {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(best, &mp);
        for (uint32_t i = 0; i < mp.memoryHeapCount; ++i) {
            if (mp.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
                os << "    device-local   : heap " << i << " = "
                   << (mp.memoryHeaps[i].size / (1024ull * 1024ull)) << " MiB\n";
            }
        }
    }

    os << "========================================================\n";
    os << "phase 0 verdict: Vulkan device up, interop entry points resolved.\n";
    return os.str();
}

void teardown() {
    if (g_state.device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g_state.device);
        vkDestroyDevice(g_state.device, nullptr);
        g_state.device = VK_NULL_HANDLE;
    }
    if (g_state.instance != VK_NULL_HANDLE) {
        vkDestroyInstance(g_state.instance, nullptr);
        g_state.instance = VK_NULL_HANDLE;
    }
    g_state.phys  = VK_NULL_HANDLE;
    g_state.valid = false;
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI surface:  dev.wpyw.dlss.NativeBridge
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_wpyw_dlss_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(kBridgeVersion);
}

JNIEXPORT jstring JNICALL
Java_dev_wpyw_dlss_NativeBridge_nativeProbe(JNIEnv* env, jclass) {
    std::string report;
    try {
        report = runProbe();
    } catch (const std::exception& e) {
        report = std::string("probe threw: ") + e.what();
    } catch (...) {
        report = "probe threw an unknown exception";
    }
    return env->NewStringUTF(report.c_str());
}

JNIEXPORT jboolean JNICALL
Java_dev_wpyw_dlss_NativeBridge_nativeIsReady(JNIEnv*, jclass) {
    return g_state.valid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_wpyw_dlss_NativeBridge_nativeShutdown(JNIEnv*, jclass) {
    teardown();
}

}  // extern "C"

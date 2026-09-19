// ---------------------------------------------------------------------------
// wpywdlss native bridge -- OpenGL capability probe
//
// Why this exists
// ---------------
// Every viable route for AI upscaling / DLSS in Minecraft Java depends on
// sharing textures between OpenGL and a DX12/Vulkan device, and the OpenGL half
// of that is a GPU/driver capability, not something we can compile in.
//
// DLSS5-Feeder's own documentation puts it exactly this way:
//
//   "If GL_EXT_memory_object_win32 and GL_EXT_semaphore_win32 are in the
//    extension string, the transport works. If they are not, the frame is not
//    being rendered on an NVIDIA GPU, so DLSS could not run either way."
//
// So this probe creates a hidden window with a real WGL context and reports
// exactly which side of that line this machine is on. It is the go/no-go check
// for the OpenGL route, and it also validates the GL half of our own bridge.
// ---------------------------------------------------------------------------

#include <jni.h>

#include <windows.h>
#include <GL/gl.h>

#include <cstring>
#include <sstream>
#include <string>
#include <vector>

// --- GL types / constants the Windows SDK's GL 1.1 header does not define ---
#ifndef APIENTRY
#define APIENTRY __stdcall
#endif

typedef char GLchar;
typedef ptrdiff_t GLsizeiptr;
typedef ptrdiff_t GLintptr;
typedef unsigned long long GLuint64;

#define GL_NUM_EXTENSIONS                 0x821D
#define GL_HANDLE_TYPE_OPAQUE_WIN32_EXT   0x9587
#define GL_DEVICE_UUID_EXT                0x9597
#define GL_DRIVER_UUID_EXT                0x9598
// GL 2.0 constant: the Windows SDK's GL 1.1 gl.h does not define it.
#ifndef GL_SHADING_LANGUAGE_VERSION
#define GL_SHADING_LANGUAGE_VERSION       0x8B8C
#endif

// --- entry points we need (declared locally: no glext.h dependency) --------
typedef const GLubyte* (APIENTRY* PFNGLGETSTRINGI)(GLenum, GLuint);
typedef void (APIENTRY* PFNGLCREATEMEMORYOBJECTSEXT)(GLsizei, GLuint*);
typedef void (APIENTRY* PFNGLDELETEMEMORYOBJECTSEXT)(GLsizei, const GLuint*);
typedef void (APIENTRY* PFNGLTEXTURESTORAGEMEM2DEXT)(GLuint, GLsizei, GLenum, GLsizei, GLsizei, GLuint, GLuint64);
typedef void (APIENTRY* PFNGLIMPORTMEMORYWIN32HANDLEEXT)(GLuint, GLuint64, GLenum, void*, const GLchar*);
typedef void (APIENTRY* PFNGLCREATESEMAPHORESEXT)(GLsizei, GLuint*);
typedef void (APIENTRY* PFNGLDELETESEMAPHORESEXT)(GLsizei, const GLuint*);
typedef void (APIENTRY* PFNGLIMPORTSEMAPHOREWIN32HANDLEEXT)(GLuint, GLenum, void*, const GLchar*);
typedef void (APIENTRY* PFNGLSEMAPHORESIGNALEXT)(GLuint, GLuint, GLuint, GLenum, const GLuint*);
typedef void (APIENTRY* PFNGLGETUNSIGNEDBYTEVEXT)(GLenum, GLubyte*);

typedef const char* (WINAPI* PFNWGLGETEXTENSIONSSTRINGARB)(HDC);
typedef BOOL (WINAPI* PFNWGLCHOOSEPIXELFORMATARB)(HDC, const int*, const FLOAT*, UINT, int*, UINT*);
typedef HGLRC (WINAPI* PFNWGLCREATECONTEXTATTRIBSARB)(HDC, HGLRC, const int*);

namespace {

const char* kModule = "gl_probe";

template <typename T>
T glGet(const char* name) {
    return reinterpret_cast<T>(wglGetProcAddress(name));
}

struct HiddenGlContext {
    HWND  hwnd = nullptr;
    HDC   hdc  = nullptr;
    HGLRC hglrc = nullptr;
    bool  ok = false;

    ~HiddenGlContext() { destroy(); }

    bool create() {
        static bool classRegistered = false;
        const wchar_t* kClass = L"wpywdlss_probe_wnd";

        if (!classRegistered) {
            WNDCLASSEXW wc{};
            wc.cbSize        = sizeof(wc);
            wc.style         = CS_OWNDC;
            wc.lpfnWndProc   = DefWindowProcW;
            wc.hInstance     = GetModuleHandleW(nullptr);
            wc.lpszClassName = kClass;
            if (!RegisterClassExW(&wc)) {
                return false;
            }
            classRegistered = true;
        }

        // Deliberately hidden: we only want a context to query, no visible window.
        hwnd = CreateWindowExW(0, kClass, L"wpywdlss probe", WS_POPUP,
                               0, 0, 16, 16, nullptr, nullptr,
                               GetModuleHandleW(nullptr), nullptr);
        if (!hwnd) {
            return false;
        }
        hdc = GetDC(hwnd);
        if (!hdc) {
            return false;
        }

        PIXELFORMATDESCRIPTOR pfd{};
        pfd.nSize      = sizeof(pfd);
        pfd.nVersion   = 1;
        pfd.dwFlags    = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
        pfd.iPixelType = PFD_TYPE_RGBA;
        pfd.cColorBits = 32;
        pfd.cDepthBits = 24;

        int pf = ChoosePixelFormat(hdc, &pfd);
        if (pf == 0 || !SetPixelFormat(hdc, pf, &pfd)) {
            return false;
        }
        hglrc = wglCreateContext(hdc);
        if (!hglrc) {
            return false;
        }
        if (!wglMakeCurrent(hdc, hglrc)) {
            return false;
        }
        ok = true;
        return true;
    }

    void destroy() {
        if (hglrc) {
            wglMakeCurrent(nullptr, nullptr);
            wglDeleteContext(hglrc);
            hglrc = nullptr;
        }
        if (hdc && hwnd) {
            ReleaseDC(hwnd, hdc);
            hdc = nullptr;
        }
        if (hwnd) {
            DestroyWindow(hwnd);
            hwnd = nullptr;
        }
        ok = false;
    }
};

std::vector<std::string> enumerateExtensions() {
    std::vector<std::string> out;

    PFNGLGETSTRINGI glGetStringi = glGet<PFNGLGETSTRINGI>("glGetStringi");
    if (glGetStringi) {
        GLint count = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        for (GLint i = 0; i < count; ++i) {
            const GLubyte* s = glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i));
            if (s) {
                out.emplace_back(reinterpret_cast<const char*>(s));
            }
        }
        if (!out.empty()) {
            return out;
        }
    }

    // GL 2.1 fallback: one space-separated string.
    const GLubyte* all = glGetString(GL_EXTENSIONS);
    if (all) {
        std::istringstream is(reinterpret_cast<const char*>(all));
        std::string tok;
        while (is >> tok) {
            out.push_back(tok);
        }
    }
    return out;
}

bool has(const std::vector<std::string>& v, const char* name) {
    for (const auto& e : v) {
        if (e == name) {
            return true;
        }
    }
    return false;
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI:  Java_dev_wpyw_dlss_NativeBridge_nativeProbeOpenGL
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_dev_wpyw_dlss_NativeBridge_nativeProbeOpenGL(JNIEnv* env, jclass) {
    std::ostringstream os;
    os << "OpenGL capability probe (hidden WGL context)\n";
    os << "========================================================\n";

    HiddenGlContext ctx;
    if (!ctx.create()) {
        os << "[FAIL] could not create a hidden WGL context.\n";
        os << "       This is a probe-environment problem, not necessarily a GPU one.\n";
        return env->NewStringUTF(os.str().c_str());
    }

    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* vendor   = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* version  = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* glsl     = reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION));

    os << "[1] context\n";
    os << "    GL_RENDERER : " << (renderer ? renderer : "?") << "\n";
    os << "    GL_VENDOR   : " << (vendor ? vendor : "?") << "\n";
    os << "    GL_VERSION  : " << (version ? version : "?") << "\n";
    os << "    GLSL        : " << (glsl ? glsl : "?") << "\n";

    bool nvidiaRenderer = false;
    if (renderer) {
        nvidiaRenderer = (std::strstr(renderer, "NVIDIA") != nullptr) ||
                         (std::strstr(renderer, "GeForce") != nullptr) ||
                         (std::strstr(renderer, "RTX") != nullptr);
    }
    os << "    NVIDIA GL driver : " << (nvidiaRenderer ? "YES" : "NO (DLSS needs the NVIDIA GPU to render)") << "\n";

    os << "[2] WGL extension string\n";
    PFNWGLGETEXTENSIONSSTRINGARB wglGetExtensionsStringARB =
        glGet<PFNWGLGETEXTENSIONSSTRINGARB>("wglGetExtensionsStringARB");
    int wglExtCount = 0;
    if (wglGetExtensionsStringARB) {
        const char* s = wglGetExtensionsStringARB(ctx.hdc);
        if (s) {
            std::istringstream is(s);
            std::string tok;
            while (is >> tok) {
                ++wglExtCount;
            }
        }
        os << "    available : " << wglExtCount << " WGL extensions\n";
    } else {
        os << "    wglGetExtensionsStringARB unavailable\n";
    }

    os << "[3] GL extensions (" ;
    std::vector<std::string> exts = enumerateExtensions();
    os << exts.size() << " available)\n";

    // ---- THE go/no-go set -------------------------------------------------
    //
    // Direction matters, and getting it wrong produces a false negative:
    // OpenGL can only IMPORT external memory and semaphores -- it cannot export
    // them. So the allocator is always Vulkan/D3D12, and GL imports the Win32
    // HANDLE it is handed. The transport we rely on is the OPAQUE_WIN32 one
    // (Vulkan reported exportable=YES for it), NOT the Vulkan-specific
    // GL_EXT_external_objects family, which is a separate mechanism.
    struct Check { const char* name; const char* why; };
    const Check required[] = {
        {"GL_EXT_memory_object",       "wrap imported memory as a texture"},
        {"GL_EXT_memory_object_win32", "accept a Win32 HANDLE for that memory"},
        {"GL_EXT_semaphore",           "import external sync objects"},
        {"GL_EXT_semaphore_win32",     "accept a Win32 HANDLE for sync"},
    };
    os << "    --- REQUIRED: GL importing what Vulkan/D3D12 exported (go/no-go) ---\n";
    bool allRequiredExts = true;
    for (const Check& c : required) {
        bool ok = has(exts, c.name);
        if (!ok) {
            allRequiredExts = false;
        }
        os << "      " << (ok ? "YES" : "no ") << "  " << c.name << "   (" << c.why << ")\n";
    }

    const Check informational[] = {
        {"GL_EXT_external_objects",      "Vulkan-specific import path; the OPAQUE_WIN32 path does not need it"},
        {"GL_EXT_external_objects_win32", "same"},
        {"GL_EXT_memory_object_fd",      "Linux only"},
        {"GL_EXT_semaphore_fd",          "Linux only"},
    };
    os << "    --- informational (not needed for the OPAQUE_WIN32 path) ---\n";
    for (const Check& c : informational) {
        os << "      " << (has(exts, c.name) ? "YES" : "no ") << "  " << c.name << "\n";
    }

    // ---- entry points ------------------------------------------------------
    os << "[4] interop entry points (via wglGetProcAddress)\n";
    struct Fn { const char* name; void* ptr; const char* why; };
    const Fn requiredFns[] = {
        {"glCreateMemoryObjectsEXT",        reinterpret_cast<void*>(glGet<PFNGLCREATEMEMORYOBJECTSEXT>("glCreateMemoryObjectsEXT")),                 "allocate a GL memory object handle"},
        {"glImportMemoryWin32HandleEXT",    reinterpret_cast<void*>(glGet<PFNGLIMPORTMEMORYWIN32HANDLEEXT>("glImportMemoryWin32HandleEXT")),         "import the VK/D3D12-exported HANDLE"},
        {"glTextureStorageMem2DEXT",        reinterpret_cast<void*>(glGet<PFNGLTEXTURESTORAGEMEM2DEXT>("glTextureStorageMem2DEXT")),                 "wrap that memory as a 2D texture"},
        {"glImportSemaphoreWin32HandleEXT", reinterpret_cast<void*>(glGet<PFNGLIMPORTSEMAPHOREWIN32HANDLEEXT>("glImportSemaphoreWin32HandleEXT")),   "import a fence HANDLE"},
        {"glDeleteMemoryObjectsEXT",        reinterpret_cast<void*>(glGet<PFNGLDELETEMEMORYOBJECTSEXT>("glDeleteMemoryObjectsEXT")),                 "cleanup"},
        {"glDeleteSemaphoresEXT",           reinterpret_cast<void*>(glGet<PFNGLDELETESEMAPHORESEXT>("glDeleteSemaphoresEXT")),                       "cleanup"},
    };
    os << "    --- REQUIRED ---\n";
    bool allRequiredFns = true;
    for (const Fn& f : requiredFns) {
        bool ok = (f.ptr != nullptr);
        if (!ok) {
            allRequiredFns = false;
        }
        os << "      " << (ok ? "YES" : "no ") << "  " << f.name << "   (" << f.why << ")\n";
    }

    const Fn informationalFns[] = {
        {"glCreateSemaphoresEXT", reinterpret_cast<void*>(glGet<PFNGLCREATESEMAPHORESEXT>("glCreateSemaphoresEXT")), "GL-allocated semaphore; unused, we only import"},
        {"glGetUnsignedBytevEXT", reinterpret_cast<void*>(glGet<PFNGLGETUNSIGNEDBYTEVEXT>("glGetUnsignedBytevEXT")), "query device UUID; diagnostics only"},
    };
    os << "    --- informational ---\n";
    for (const Fn& f : informationalFns) {
        os << "      " << (f.ptr ? "YES" : "no ") << "  " << f.name << "\n";
    }

    os << "========================================================\n";
    if (nvidiaRenderer && allRequiredExts && allRequiredFns) {
        os << "VERDICT: the OpenGL transport is AVAILABLE on this machine.\n";
        os << "         GL can import textures allocated and exported by Vulkan/D3D12,\n";
        os << "         and synchronise through imported Win32 semaphores.\n";
        os << "         Every AI-upscaling route is unblocked on the GL side.\n";
    } else if (!nvidiaRenderer) {
        os << "VERDICT: BLOCKED -- OpenGL is not on the NVIDIA GPU in this probe.\n";
        os << "         (In Minecraft this would mean the game is rendering on the iGPU.)\n";
    } else {
        os << "VERDICT: BLOCKED -- the NVIDIA GPU is rendering, but the required\n";
        os << "         interop extensions or entry points are missing.\n";
    }

    ctx.destroy();
    return env->NewStringUTF(os.str().c_str());
}

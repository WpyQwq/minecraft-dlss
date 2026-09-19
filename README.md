# Wpyw DLSS — Minecraft Java 1.20.1 (Fabric)

在 Minecraft Java 版里接入 **NVIDIA DLSS 超分（Super Resolution）** 与 **帧生成（Frame Generation / 插帧）**。

> 工程名 `wpywdlss` / `MinecraftDLSS` 是**临时名**，随你改。

---

## 一、先讲清楚三个硬事实（省掉走弯路的成本）

### 1. DLSS 5 不是超分，也不是插帧

| | 超分 | 插帧 | 本质 |
|---|---|---|---|
| **DLSS 4** | ✅ Super Resolution | ✅ Multi Frame Generation（最高 6X） | 你要的就是这两个 |
| **DLSS 5** | ❌ | ❌ | **3D 引导神经渲染**：重建材质 / 光照 / 皮肤 / 阴影 / 反射 |

DLSS 5 于 2026-09-03 上线，**官方仅 RTX 50 系**，目前只有 NBA 2K27 一款游戏，集成走 **UE5 插件**路径。
在 **Streamline SDK 2.14.1 的 `include/` 与 `bin/x64/` 里不存在任何 dlssnr / 神经渲染的头文件或插件**——
即通用 Streamline 层根本没有 DLSS 5 的集成接口。

### 2. Minecraft Java 是 OpenGL，而 NGX 不支持 OpenGL

NGX / Streamline 只支持 **D3D11 / D3D12 / Vulkan**。所以 DLSS 进 MC Java 的唯一办法是：
游戏继续用 OpenGL 渲染，模组**内部再起一个 Vulkan 设备**，用外部内存/信号量把纹理零拷贝共享过去，
在 Vulkan 侧跑 NGX。

### 3. 真正的工程量在「运动矢量」

DLSS 超分必须吃 **运动矢量 + 深度 + 抖动投影矩阵**，而 Minecraft 的 OpenGL 管线**一个都不生产**。
这是本项目 80% 的工作量。在 **Iris + 光影包**环境下更难：Iris 接管整条管线、自己控制投影矩阵与 G-buffer，
且还有 Distant Horizons / Voxy 的 LOD 几何要算速度。

---

## 二、架构

```
┌─ Minecraft 1.20.1 (Java, OpenGL / LWJGL) ──────────────────────────┐
│  Fabric 模组 (本仓库 src/main/java)                                 │
│   • Mixin 投影矩阵亚像素抖动 (jitter)                               │
│   • Mixin 渲染分辨率 → 低分渲染 world framebuffer                   │
│   • 导出 depth / motion vector 纹理                                 │
│   • 在 present 点调用原生桥接                                        │
└────────────────────────┬──────────────────────────────────────────┘
                         │ JNI
┌────────────────────────▼──────────────────────────────────────────┐
│  自研原生桥接 DLL (C++ / MSVC)   ← native/  本项目核心               │
│   • 建 Vulkan 1.2+ 设备（同一块 GPU）                                │
│   • GL↔VK 外部内存互操作                                             │
│     GL_EXT_memory_object_win32 ↔ VK_KHR_external_memory_win32       │
│     GL_EXT_semaphore_win32      ↔ VK_KHR_external_semaphore_win32   │
│   • slInit / slSetVulkanDevice / slSetConstants                     │
│   • slDLSSSetOptions + slEvaluateFeature   （超分）                  │
│   • slDLSSGSetOptions                      （帧生成）                │
└────────────────────────┬──────────────────────────────────────────┘
                         │
             ┌───────────▼────────────┐
             │  sl.interposer.dll     │   ← 用户自备，不随模组分发
             │   ├─ sl.dlss.dll       │ → nvngx_dlss.dll   超分
             │   ├─ sl.dlss_g.dll     │ → nvngx_dlssg.dll  插帧
             │   └─ NvLowLatencyVk.dll│ → Reflex（Vulkan）
             └────────────────────────┘
```

---

## 三、NVIDIA 运行时：**绝不打包进模组**

`nvngx_dlss.dll` / `nvngx_dlssg.dll` / `sl.*.dll` 均为 NVIDIA 专有二进制，**不允许随模组再分发**。
本模组只提供**加载逻辑**，二进制由用户自行放置。

运行时查找顺序：

1. JVM 参数 `-Dwpywdlss.runtimeDir=<路径>`
2. `<gameDir>/ngx/`
3. `<gameDir>/config/ngx/`

需要的文件（全部来自 **Streamline SDK 2.14.1 的 `bin/x64/`**）：

| 文件 | 大小 | 用途 |
|---|---|---|
| `sl.interposer.dll` | 652,928 | Streamline 核心 |
| `sl.common.dll` | 843,392 | 公共层 |
| `sl.dlss.dll` | 422,016 | 超分插件 |
| `sl.dlss_g.dll` | 636,032 | 帧生成插件 |
| `nvngx_dlss.dll` | 58,956,912 | DLSS 超分模型 (310.9.1.0) |
| `nvngx_dlssg.dll` | 7,460,976 | DLSS 帧生成模型 (310.9.1.0) |
| `NvLowLatencyVk.dll` | 57,840 | Vulkan Reflex（可选） |

> 若 NGX 因缺少 `applicationId` 拒绝加载，可改用 `bin/x64/development/` 下的开发版 DLL 做实验。

---

## 四、构建

### Java 侧（Fabric 模组）

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat build
```

产物：`build/libs/wpywdlss-0.1.0.jar`

版本组合（对齐 Fabric 官方 1.20.1 分支）：
`minecraft 1.20.1` · `loader 0.19.5` · `loom 1.17-SNAPSHOT` · `fabric-api 0.92.12+1.20.1` · Mojang 官方映射

### 原生侧（C++ 桥接 DLL）

见 `native/`。使用 MSVC（VS2022 BuildTools 14.44.35207）+ CMake。

**不需要 Vulkan SDK**：Khronos 头文件取自 DLSS demo 自带的
`DLSS_Sample_App/donut/thirdparty/glfw/deps/vulkan/`，导入库由
`dumpbin /exports C:\Windows\System32\vulkan-1.dll` + `lib /def:` 自造。

---

## 五、路线图

| 阶段 | 目标 | 验证方式 |
|---|---|---|
| **P0 地基** | Fabric 工程可编译；原生桥接 DLL 可编译；JNI 打通；Vulkan 设备创建成功；GL↔VK 互操作往返 | 游戏内一张纹理经 GL→VK→GL 往返后画面一致 |
| **P1 数据源** | 深度纹理 + 抖动投影 + 运动矢量。**先在纯净版 / Sodium 下做** | 运动矢量可视化输出正确 |
| **P2 DLSS 超分** | 接 `sl.dlss`，出画面。applicationId 风险在此暴露 | 1080p→4K 提升且画质可接受 |
| **P3 HUD 原生分辨率** | 世界低分渲染、HUD/UI 保持原生清晰 | HUD 文字不糊 |
| **P4 帧生成** | 接 `sl.dlss_g`。最难一步（需接管 present 路径） | 输出帧率提升，伪影可接受 |
| **P5 Iris 兼容** | 在光影包环境下算运动矢量 | 主流光影包下不崩不糊 |

---

## 六、P0 实测结果（已跑通，非推演）

**环境**：RTX 5070 / 驱动 616.92 / Vulkan loader 1.4.341 / Win11 26100
**方式**：`tools/ProbeRunner.java` 独立加载桥接 DLL 调用 `nativeProbe()`，不启动游戏
**结论**：`nativeIsReady() = true`，exit code 0

| 检查项 | 实测结果 |
|---|---|
| Vulkan 实例 | ✅ `VK_SUCCESS`，loader 1.4.341 |
| 物理设备 | 2 个：**RTX 5070**（0x10de, DISCRETE, api 1.4.351）+ AMD 核显（0x1002, INTEGRATED, 1.4.315）；自动选中 NVIDIA |
| 设备扩展（291 可用） | ✅ `VK_KHR_external_memory_win32` / `VK_KHR_external_semaphore_win32` / `VK_KHR_timeline_semaphore` / `dedicated_allocation` / `image_format_list` / `synchronization2` 全部可用 |
| 设备创建 | ✅ `VK_SUCCESS`（queue family 0，已启用 timeline semaphore） |
| 互操作函数指针 | ✅ `vkGetMemoryWin32HandleKHR` / `vkGetSemaphoreWin32HandleKHR` / `vkImportSemaphoreWin32HandleKHR` 经 `vkGetDeviceProcAddr` 全部取到 |
| **镜像导出（go/no-go）** | ✅ **`OPAQUE_WIN32`: exportable=YES, importable=YES, dedicatedOnly=no** |
| 信号量 | ✅ `OPAQUE_WIN32`: exportable=YES, importable=YES |
| 显存 | 11,943 MiB device-local |

### 两个必须记住的实测细节

1. **`D3D12_HEAP` / `D3D11_TEXTURE` 只能导入、不能导出**（`exportable=no`）。
   这直接影响你那个 `rtx_vsr_host.dll`（只有 `NVSDK_NGX_D3D12_*` 接口）：
   它无法接收"OpenGL/Vulkan 分配、D3D12 导入"的纹理，**只能反过来由 D3D12 侧分配**再共享出来。
   所以 VSR 超分和 DLSS 超分**不能复用同一条互操作管线**，这是两套东西。
2. **`dedicatedOnly=no`**：不强制专用分配，意味着我们可以在同一块 VkDeviceMemory 里规划输入/输出镜像，省一次分配和一次拷贝。

### 常用命令

```powershell
# 编原生桥接（自动处理 vulkan-1.lib）
.\native\tools\build_native.cmd

# 只重造 vulkan-1.lib
.\native\tools\make_vulkan_lib.cmd

# 跑 P0 探测（不需要启动游戏）
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
java -cp "build\classes\java\main" `
     -Dwpywdlss.bridgePath=native\build\wpywdlss_bridge.dll `
     tools\ProbeRunner.java
```

---

## 七、OpenGL 互操作实测结果（2026-09-16，已跑通）

**为什么这一个探测决定所有路线的生死**：OpenGL **只能导入、不能导出**内存和信号量。
所以分配方必须是 Vulkan/D3D12，GL 只负责导入对方给过来的 Win32 HANDLE。
`GL_EXT_external_objects*` 是**导入 Vulkan 对象的另一条路**，走 `OPAQUE_WIN32` 时**不需要**它 ——
把它当必需项会得出假阴性（我第一版就写错了，已修正）。

`nativeProbeOpenGL()` 创建一个**隐藏 WGL 上下文**实测：

| 检查 | 结果 |
|---|---|
| GL_RENDERER | `NVIDIA GeForce RTX 5070/PCIe/SSE2` |
| GL_VERSION | `4.6.0 NVIDIA 616.92` |
| GL 扩展总数 | 405 |
| **`GL_EXT_memory_object`** | ✅ |
| **`GL_EXT_memory_object_win32`** | ✅ |
| **`GL_EXT_semaphore`** | ✅ |
| **`GL_EXT_semaphore_win32`** | ✅ |
| `glCreateMemoryObjectsEXT` | ✅ |
| **`glImportMemoryWin32HandleEXT`** | ✅ |
| **`glTextureStorageMem2DEXT`** | ✅ |
| **`glImportSemaphoreWin32HandleEXT`** | ✅ |
| `glDeleteMemoryObjectsEXT` / `glDeleteSemaphoresEXT` | ✅ |
| `GL_EXT_external_objects*` | ❌（**不需要**，走 OPAQUE_WIN32 时用不到）|
| `glCreateSemaphoresEXT` | ❌（**不需要**，GL 只导入不导出）|

**判定：`VERDICT: the OpenGL transport is AVAILABLE on this machine.`**

两侧合起来的完整链路（能力层面已验证）：

```
Vulkan/D3D12 分配镜像
      │ vkGetMemoryWin32HandleKHR / D3D12 CreateSharedHandle
      ▼  (Win32 HANDLE)
OpenGL  glCreateMemoryObjectsEXT → glImportMemoryWin32HandleEXT
        → glTextureStorageMem2DEXT   (得到可渲染/可采样的 GL 纹理)
      ▲  (Win32 信号量 HANDLE)
        glImportSemaphoreWin32HandleEXT
```

---

## 八、路线 C（ReShade 路线）落地清单

**先明确 C 在 OpenGL 下的天花板**（引自 DLSS5-Feeder 官方 Limitations）：

> **DLAA contract, optional reduced work extent on D3D11** — render resolution still equals DLAA output
> resolution... **D3D12, Vulkan and OpenGL paths remain at 100%.** This is not jittered DLSS Super
> Resolution, and **a Quality/Balanced/Performance mode cannot be added**.

即：**OpenGL 下是纯 DLAA + DLSS 5 神经渲染观感，内部渲染分辨率不降 → 零帧率收益，拿不到超分，也没有插帧。**
另外官方承认 **"the UI is processed with the scene"** —— Minecraft 的物品栏/聊天/F3 会被一起处理而拖影。

### 需要的组件

| 组件 | 来源 | 状态 |
|---|---|---|
| ReShade 6.8+（**需 add-on 支持**），装成 `opengl32.dll`，API 选 **OpenGL** | reshade.me | 需你下载 |
| **Generic Depth add-on** 启用并**认到 Minecraft 的场景深度** | ReShade 自带 | ⚠️ 最大风险点，见下 |
| 神经消费者：**Deep Fried Chicken**（推荐）或 Krish 的 `renodx-dlss5.addon64` | 各自 Discord | 需你获取（Discord 门禁，我拿不到）|
| `nvngx_dlssnr.dll` | 已有 | ✅ GUI-DLSS5 包里 |
| `nvngx_dlss.dll`（放在游戏目录旁） | 已有 | ✅ Streamline SDK 里 |
| 运动矢量提供者：**LumeniteFX Kernel（=3，推荐）** | github.com/umar-afzaal/LumeniteFX | 需下载 |
| `dlss5-feed.addon64` + `DLSS5_Feed.fx` | DLSS5-Feeder release | 需下载 |

### Minecraft 特有的四个坑（我预判，待实测确认）

1. **深度缓冲**：ReShade 的 Generic Depth 必须正确识别 Minecraft 的场景深度。
   Iris/光影会自建 FBO，Sodium 改了区块渲染 —— 识别很可能失败。
2. **GPU 选择**：这条路要求**渲染真的在 NVIDIA GPU 上**。你有 RTX 5070 + AMD 核显，
   必须在 Windows「设置 ▸ 显示 ▸ 图形」里把 Java 强制到 NVIDIA，否则 DLSS 无从谈起。
3. **HUD 拖影**：官方明说 UI 与场景一起处理。要么忍受，要么自己加 UI 遮罩。
4. **驱动版本**：他们实测的坏组合是驱动 **616.56 / 616.64**（`nvngx_dlssnr.dll` 内部 fault）。
   **你是 616.92，比他们测过的都新，未知。** 先跑他们的自检
   `host64\dlss5-feed-host64.exe --test`（`300/300 evaluates succeeded` 才算 OK）。

---

## 九、许可与合规

- 本模组代码：MIT
- **NVIDIA 专有二进制一律不再分发**，仅由用户自备（同 Salt's Anti-Aliasing 等既有项目的做法）
- 参考但不复制任何现有模组代码；`Super Resolution`（GPL-3.0）与 `Salt's Anti-Aliasing`（MIT）
  仅作为「这条路走得通」的存在性证据
- NGX `applicationId` 需向 NVIDIA 申请；在获批前 NVIDIA 目前为宽容放行（会打警告日志）

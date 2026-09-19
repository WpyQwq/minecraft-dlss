# 终末地（Arknights: Endfield）· DLSS 5 神经渲染 完整配方

> 2026-09-18 实测跑通。本文记录**可复现的最小配置**与**每一条失败路径的实证原因**，
> 避免以后再走一遍弯路。

---

## 一、最终可用配置

**形态：单 DLL 外部注入。** 游戏目录里**零外来文件**。

```
注入（只进 Endfield.exe，不进其它进程）:
    H:\endfield-dlss5\winmm.dll          OptiScaler-DLSSNR v0.2.0（改名为 winmm.dll）
    ├── H:\endfield-dlss5\OptiScaler\    后端 DLL（FidelityFX / XeSS 等）
    ├── H:\endfield-dlss5\nvngx.dll_dlssnr.dll   神经转发器（包内自带）
    └── H:\endfield-dlss5\nvngx_dlssnr.dll       神经模型本体 310.8.0.0（需自备，165 MB）

游戏目录: 只保留游戏自带文件（含它自己的 nvngx_dlss.dll 310.5.2.0，未改动）
启动参数: -force-d3d11 -launcher_lang=zh-cn -launcher_sub_channel=1
```

**关键 ini 设置**（`H:\endfield-dlss5\OptiScaler.ini`）

| 段 | 键 | 值 | 为什么 |
|---|---|---|---|
| `[Upscalers]` | `Dx11Upscaler` | **`dlss_12`** | ini 注释原文：`dlss_12 (dx11on12, **the only option that can run DLSS Neural Rendering as well**)` —— D3D11 上唯一能跑神经渲染的选项 |
| `[DlssNr]` | `Enabled` | `true` | 默认关 |
| `[Menu]` | `ShortcutKey` | `0x24`（Home） | 默认 `0x2D`(Insert)，很多键盘没有 Insert |
| `[DlssNr]` | `WhitePointScale` | `1.0` | 默认 auto 会**逐帧测量**，导致亮度泵动（见「已知问题」） |
| `[ProcessFilter]` | `TargetProcessName` | `endfield.exe` | 双保险；ini 自带的示例就是终末地 |
| `[ProcessFilter]` | `ProcessExclusionList` | `PlatformProcess.exe\|QtWebEngineProcess.exe\|ACE-Setup64.exe\|ACE-Service64.exe\|CefViewWing.exe\|UnityCrashHandler64.exe` | 同上 |

**启动方式**：桌面 `终末地-DLSS5启动.bat` → `H:\endfield-dlss5\launch.ps1` → `injector.exe`

---

## 二、为什么必须是「外部注入 + 单 DLL」

### 2.1 目录代理 DLL 会被同目录所有进程吞掉（ACE 拦的根因）

终末地在同一目录派生：`PlatformProcess.exe`、`QtWebEngineProcess.exe`、`CefViewWing.exe`、
`ACE-Setup64.exe`、`ACE-Service64.exe`、`UnityCrashHandler64.exe`。
**放在游戏目录的代理 DLL 会被它们 `LoadLibrary` 动态加载**（应用目录优先），
于是 ReShade/feeder 跑进了鹰角的辅助进程 —— 实测铁证：

```
dlss5-feed.log  21:18:08.600   host: H:\Arknights Endfield\PlatformProcess.exe   ← 不是 Endfield.exe
```

后果：ACE 报「检测到黑客工具」。
（另见 optiscaler/OptiScaler issue #848，报的就是终末地这个游戏。）

**OptiScaler 的 `TargetProcessName`/`ProcessExclusionList` 挡不住这件事** ——
它们只阻止 OptiScaler **初始化**，阻止不了 DLL 被加载进那些进程。

### 2.2 社区的做法是「只注入目标进程」

XXMI（终末地社区实际在用的 mod 加载器）用 `SetWindowsHookEx`，
在**目标进程创建第一个窗口时**把 DLL 送进去，钩子在 `finally` 里必定移除；
其文档明确说这样「更少直接内存操作、**潜在更低的反作弊检测风险**」。
本项目用等价的 `CREATE_SUSPENDED` + `CreateRemoteThread(LoadLibraryW)` + `ResumeThread` 实现。

### 2.3 ReShade 与 feeder 都必须拿掉

* feeder 自己的日志判定：
  > 这游戏走 **Streamline**、**自带 DLSS**。OptiScaler 会捕获进程里**每一个** NGX 调用，
  > 所以它的神经通路会在游戏的 DLSS 上**和**这个 feed 上各跑一次。**本项目是给没有 DLSS 的
  > 游戏用的 —— 请用游戏自己的 DLSS 配 OptiScaler，并移除 `dlss5-feed.addon64`。**
* OptiScaler 自己就挂在游戏自己的 DLSS 上（不需要 feeder）：
  ```
  DlssNr::EvaluateAfterUpscale DLSS-NR reached through the game's DLSS input
  DlssNr_Dx12::Dispatch DLSS-NR running at 2560x1440, guides 2560x1440
  ```
* ReShade 插进 D3D11 调用链会造成三方钩子冲突并崩溃：
  ```
  ### CRASH RECORDED ### exception 0xC0000005 at ...d3d11.dll
  crash stack: d3d11.dll <- reshade.dll <- winmm.dll <- unityplayer.dll
  ```
  （转储 198 MB 已存档）

---

## 三、走过的死路（都不必再试）

| 路径 | 失败原因（实证） |
|---|---|
| `dxgi.dll` 目录代理 ReShade | 被 ACE 辅助进程吞掉 → 「检测到黑客工具」 |
| `d3d12.dll` 目录代理 ReShade | 注入成功，但 ReShade 的 Vulkan/后续链路与游戏冲突 |
| ReShade 的 **Vulkan 层** | 引擎确定性崩溃：`unityplayer.dll +0x57d123`，`0xc0000005`，四次同偏移 |
| Deep Fried Chicken（DFC）作神经消费者 | ① `hook_mode=AUTO` **主动跳过 Streamline**，而游戏的 DLSS 正走 Streamline v2 → 拿不到资源图（`fail-open: complete native resource map unavailable`, `device=0`）② DFC 的 private/standalone 桥在**干净进程里**也失败（`host64 --test`：feeder 300/300 通过，但 DFC 的 `Feature18Create` 返回 `0xBAD00002 PlatformError` + `nvngx_dlssnr.dll` 内 AV）→ 与 ACE、驱动、游戏均无关 |
| `-force-d3d12` | 该构建的 Unity 播放器**没有 D3D12 后端**（`UnityPlayer.dll` 有 `force-d3d11` 却无 `D3D12CreateDevice`），回落到 Vulkan |
| feeder 合成 DLAA 契约 | 本游戏自带 DLSS，多余（见 2.3） |

---

## 四、已知问题与调参

* **画面亮度快速闪动** —— `[DlssNr] WhitePointScale=auto` 会**逐帧从画面测量**白点，
  实测在 1.35–1.41 来回扫，每一帧都缩放模型输出 → 亮度泵动。
  **改成固定值 `1.0`**（游戏自报曝光就是 1.0）。
  该键**实时生效**，也可以直接在 OptiScaler 菜单里改。
* **菜单键**：默认 `Insert`（`0x2D`）。很多键盘没有 → 已改 `0x24`(Home)。
  独占全屏下若按键被吞，切无边框窗口。
* **`dlssnr-capture` 未生成** —— `AutoCapture` 默认开，但实测未触发；
  改用菜单里的 **DebugView = 3（Difference）** 判断模型是否在改画面：
  **整屏灰 = 什么都没改；有颜色 = 确实在改。**
* **NVIDIA Smooth Motion**：进程里有 `NvPresent64.dll` 说明它开着。
  Vulkan 路径下**必须关**（文档原文：永远无法共存）；D3D11 路径文档说可以留，
  但若出现闪动/串帧，用 NVIDIA Profile Inspector 的
  `Smooth Motion - Enabled APIs`（`0xB0CC0875`）**清掉 DX11 那一位**（`7` → `5`）。

---

## 五、操作速查

```powershell
# 启动（桌面 .bat 等价）
H:\endfield-dlss5\launch.ps1

# 注入器手动用法
H:\endfield-dlss5\injector.exe --exe "H:\Arknights Endfield\Endfield.exe" `
  --args "-force-d3d11 -launcher_lang=zh-cn -launcher_sub_channel=1" `
  --basepath "H:\endfield-dlss5" --log "H:\endfield-dlss5\injector-last.txt" `
  --dll "H:\endfield-dlss5\winmm.dll"

# 看神经通路是否在跑
Get-Content H:\endfield-dlss5\OptiScaler.log | Select-String 'DlssNr_Dx12::Dispatch|DlssNr::EvaluateAfterUpscale' | Select-Object -Last 5

# 完全回滚：删掉外部目录即可，游戏目录本来就干净
Remove-Item H:\endfield-dlss5 -Recurse -Force
Remove-Item D:\Desktop\终末地-DLSS5启动.bat -Force
```

**验收判据（缺一不可）**

1. `injector-last.txt` 里 `[OK] ...winmm.dll`，`注入完成（1/1）`
2. `OptiScaler.log` 有 `CheckWorkingMode OptiScaler working as winmm.dll, system dll loaded`
3. `OptiScaler.log` 有 `StreamlineHooks::hookInterposer Streamline version: 2.10.3`
4. `OptiScaler.log` 有 `DLSSFeatureDx12::InitDLSS _CreateFeature result: NVSDK_NGX_Result_Success`
5. `OptiScaler.log` 有 `DlssNr::EvaluateAfterUpscale DLSS-NR reached through the game's DLSS input`
6. 游戏目录外来文件数 = **0**

---

## 六、产物清单

| 文件 | 作用 |
|---|---|
| `E:\deepseek\_tmp\feedkit\injector.cs` / `injector.exe` | 注入器（C#，`csc` 编译，x64） |
| `H:\endfield-dlss5\launch.ps1` | 提权启动 + 注入（含两个已修 bug 的说明） |
| `D:\Desktop\终末地-DLSS5启动.bat` | 用户入口（CRLF + 纯 ASCII） |
| `E:\deepseek\_tmp\feedkit\endfield-install-optiscaler.ps1` | 安装脚本 |
| `E:\deepseek\_tmp\feedkit\endfield-migrate-external.ps1` | 目录代理 → 外部注入 迁移 |
| `E:\deepseek\_tmp\feedkit\revert-endfield.ps1` | 回滚（按清单精确删除） |
| `E:\deepseek\game-backups\Endfield\` | 全部证据：日志、模块快照、崩溃转储（198 MB）、安装清单、安装前快照 |

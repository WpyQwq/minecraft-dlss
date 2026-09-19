# 地平线 5（Forza Horizon 5）· DLSS 5 神经渲染 完整配方

> 2026-09-18 实测跑通。姊妹文档：`endfield-dlss5-recipe.md`（终末地）。
> 两者共用同一套结论：**游戏自带 DLSS 时，只需要 OptiScaler-DLSSNR 一个组件。**

---

## 一、最终可用配置

**形态：目录代理单 DLL。** 无反向代理注入、无 ReShade、无 feeder。

```
D:\SteamLibrary\steamapps\common\ForzaHorizon5\
    dxgi.dll                  OptiScaler-DLSSNR v0.2.0（OptiScaler.dll 改名而来）
    OptiScaler.ini            配置（见下表）
    OptiScaler\               后端 DLL（FidelityFX / XeSS / D3D12_OptiScaler\D3D12Core.dll）
    nvngx.dll_dlssnr.dll      神经转发器（包内自带）
    nvngx_dlssnr.dll          神经模型 310.8.0.0（需自备）
    nvngx_dlss.dll            游戏自带 3.1.11.0 —— **不要动**
    nvngx_dlssg.dll           游戏自带 —— 不要动
    sl.*.dll                  游戏自带 Streamline 2.10.3 —— 不要动
```

**启动方式：Steam 正常启动，不需要换启动器。**

### 关键 ini 设置（`OptiScaler.ini`）

| 段 | 键 | 值 | 为什么 |
|---|---|---|---|
| `[Upscalers]` | `Dx12Upscaler` | `dlss` | 地平线是 D3D12；神经通路必须骑在游戏自己的 DLSS 上 |
| `[DlssNr]` | `Enabled` | `true` | 默认关 |
| `[DlssNr]` | `WhitePointScale` | `1.0` | 默认 auto 会**逐帧测光**导致亮度泵动（同终末地） |
| `[DlssNr]` | `TransferStrength` | `0.7` | 满档 1.0 = 完全是模型的画面 → 噪点明显。0.7 是降噪起点 |
| `[DlssNr]` | `ColourStrength` | `0.5` | 1.0 = 连模型的颜色一起用 → HDR 场景下色彩噪点偏重 |
| `[Menu]` | `ShortcutKey` | `0x24`（Home） | |
| `[Menu]` | `OverlayMenu` | **`false`** | 见「三、已解决的故障」——覆盖层与输入钩子会卡死游戏 |
| `[Hotfix]` | `ManualInputPolling` | **`true`** | 同上：不 hook WndProc，改成自己轮询 |
| `[Hotfix]` | `CheckForUpdate` | `false` | 关掉联网版本检查，少一个变量 |

---

## 二、验收判据（缺一不可）

读 `D:\SteamLibrary\steamapps\common\ForzaHorizon5\OptiScaler.log`：

1. `CheckWorkingMode OptiScaler working as dxgi.dll, system dll loaded`
2. `HookNgxApi NVSDK_NGX_XXXXXX_GetFeatureRequirements found, hooking!`
3. `DLSSFeatureDx12::InitDLSS _CreateFeature result: NVSDK_NGX_Result_Success`
4. **`DlssNr::EvaluateAfterUpscale DLSS-NR reached through the game's DLSS input`** ← 核心
5. `DlssNr_Dx12::Dispatch DLSS-NR running at 2560x1440`
6. 进程模块表里出现 **两个 `dxgi.dll`**（游戏目录的 OptiScaler + 系统 dxgi）
7. 无 `Application Hang` / `Application Error`

---

## 三、已解决的故障（都有实证，不必再走）

### 3.1 启动后「未响应」→ `Application Hang`

```
OptiInput::InstallWindowSubclass  subclass installed  previousWndProc:… → optiWndProc:…
OptiInput::InstallHooks  Win32 message / key state / GetMessagePos / clip cursor /
                         HID / **raw input** / windows input / position input hooks installed
OptiInput::UpdateXInputIntegrationLocked XInput hooks installed
… 之后 Windows 记录 Application Hang，游戏被关闭
```

OptiScaler 给游戏主窗口挂了**子类化窗口过程**并装了 **raw input + XInput 钩子**，
而地平线是重度输入的游戏 → 消息泵被卡住。

**修法（两个一起，实测有效）**：
```ini
[Hotfix] ManualInputPolling=true    ; 不去 hook WndProc，改成自己轮询输入
[Menu]   OverlayMenu=false          ; 关掉覆盖层
```

### 3.2 在菜单里切到 FSR → 游戏崩溃

```
21:59:08  FeatureProvider_Dx12::ChangeFeature changing backend to FSR 2.2.1
21:59:11  Application Error: ForzaHorizon5.exe  0xc0000005  偏移 0x92dfbc
```
**不要在地平线里实时切后端。** 要换就改 ini 再重启（而且 `Dx12Upscaler` 必须是 `dlss`，
否则神经通路没有游戏自己的 DLSS 可骑）。

### 3.3 遗留：菜单显示得出，但收不到输入

```
[W] OptiInput::LogInputHealthSnapshotLocked menu is visible but no window/queue/raw input
    was received this frame. input:0x…, foreground:0x…, focused:yes, subclassed:yes.
```
菜单能唤出（`menu visibility changed 0 -> 1`），但鼠标键盘进不去。
**不影响神经渲染**，所有参数都从 ini 调、改完重启游戏即可。

### 3.4 游戏自身的帧生成在报错（与 OptiScaler 无关）

```
[E] present.h:288[updateStatus] eDLSSGStatusFailReflexNotDetectedAtRuntime
    - sl.reflex must be enabled and active
```
游戏自己的 DLSS-G 处于失败状态。**「远处物体闪动」很可能来自这里** ——
建议在游戏画面设置里关掉帧生成，或把 Reflex 打开。

### 3.5 左上角两个重叠的 FPS 数字

不是 OptiScaler 的（`ShowFps=auto`=关）。是 **NVIDIA App 的性能叠加（绿色）** 与
**Steam 的游戏内 FPS 显示** 都默认在左上角。关掉其中一个即可。

---

## 四、调参对照表（全部来自 ini 里的原文说明）

| 键 | 含义 | 方向 |
|---|---|---|
| `TransferStrength` | **Detail strength**：0 = 超分器原样（真旁路）→ 1 = 模型的画面 → >1 超出模型本意 | ↓ 降噪/压远处闪动 |
| `ColourStrength` | **Colour strength**：0 = 完全保留游戏颜色、只让亮度带模型判断；1 = 连模型颜色一起用 | ↓ 去色彩噪点 |
| `MaxRatio` | **Highlight guard**（默认 2.0x）：限制这一遍最多提亮多少倍 | 2x 说明书说已足够 |
| `WorkingScale` | 模型工作分辨率，**画面本身不降**，开销按平方走；**>1.0 超采样在 D3D12 可用**（终末地 D3D11 上不可用） | ↓ 变软/省开销 |
| `ScalingDownscaler` | 只在 `WorkingScale>1.0` 时用 | 上采样时再动 |
| `DebugView` | 0 关 / 1 模型看到的 / 2 模型原始答案 / **3 改动量放大 20 倍** | 灰屏 = 模型没在改 |
| `AutoCapture` | 写 `dlssnr-capture` 前后对比帧 | 实测未触发 |
| `Intensity` / `Preset` / `Style` / `Local*` | NVIDIA 自己的参数；`Preset` 改动需重启 | |

---

## 五、回滚

```powershell
& 'E:\deepseek\_tmp\feedkit\revert-forza.ps1'
```
按安装清单精确删除（12 个文件），并与安装前快照交叉核对。
上一轮的 ReShade / DFC 残留保留在 `E:\deepseek\game-backups\ForzaHorizon5\leftovers-*\`。

---

## 六、与终末地的差异对照

| | 终末地 | 地平线 5 |
|---|---|---|
| 反作弊 | **ACE** → 目录代理会被 `ACE-Setup64.exe` / `PlatformProcess.exe` 等吞掉 | **无** → 目录代理最简形态即可 |
| 注入方式 | 外部注入（`injector.exe`，只进 Endfield.exe） | 目录代理 `dxgi.dll` |
| 渲染 API | 只有 D3D11 / Vulkan（**没有 D3D12 后端**） | D3D12 |
| 超分器键 | `Dx11Upscaler=dlss_12`（dx11on12，**D3D11 上唯一能跑神经渲染的选项**） | `Dx12Upscaler=dlss` |
| 超采样 `WorkingScale>1` | 不可用 | **可用** |
| Smooth Motion | 必须为 Vulkan 关掉 | 无此约束 |
| 启动 | 桌面 `终末地-DLSS5启动.bat`（含提权） | Steam 正常启动 |
| 坑 | 目录代理污染 ACE 进程 | 窗口子类化 + raw input 钩子卡死消息泵 |

---

## 七、产物清单

| 文件 | 作用 |
|---|---|
| `E:\deepseek\_tmp\feedkit\forza-install-optiscaler.ps1` | 安装脚本 |
| `E:\deepseek\_tmp\feedkit\revert-forza.ps1` | 回滚脚本 |
| `E:\deepseek\_tmp\feedkit\OptiScaler-DLSSNR-v0.2.0.zip` | 原料（124 MB，SHA256 `8EECE7A4D7DE6DE5917F0C99AC60540B2D77022E7699BBA717B0A6D9E1829BCE`） |
| `E:\deepseek\game-backups\ForzaHorizon5\` | 证据：`hang-run-*\`（含 Application Hang 事件）、`OptiScaler.ini` 各阶段备份、安装清单、安装前快照、`leftovers-*\` |

# 路线 C（ReShade 路线）落地现状 — 实测记录

> 本文所有内容均为**实测**（文件系统检查、PE 导出表解析、DLL 字符串扫描、注册表读取），非推演。
> 记录时间：2026-09-16

---

## ★ 环境更正（2026-09-16 晚，推翻了本文早先的假设）

早先的记录基于「用户用 Modrinth App 玩 MC」这一**错误前提**，据此准备的
`H:\Java\zulu...` 专用 Java 与 Modrinth `app.db` 的 `java_path` 改动**打在了错误目标上**。
（当时搜 javaw.exe 的盘符列表**漏了 H 盘**，因此也没发现 `H:\javahome`。）

**实测出的真实环境：**

| 项 | 真实值 |
|---|---|
| 启动器 | **PCL2 便携版** `W:\PCL 正式版 2.12.8.2\Plain Craft Launcher 2.exe` |
| PCL 全局设置 | `W:\PCL 正式版 2.12.8.2\PCL\Setup.ini`（仅 RAM/窗口/目录，**不含 Java 选择**） |
| **MC 实际使用的 Java** | **`H:\javahome\bin\java.exe`** = **Oracle JDK 21.0.12.1**（318 MB，421 文件） |
| Java 清单 | `%APPDATA%\PCL\config.json` 的 `JavaList`（9 项，**不含 H:\javahome**，说明是自动探测） |
| 游戏目录 | `W:\PCL 正式版 2.12.8.2\.minecraft\versions\NGT\.minecraft\` |
| 真实实例 | 该目录下 `versions\1.20.1-Fabric 0.19.3\`（另有 `PVP`） |
| 版本级设置 | 实例内 `PCL\Setup.ini`：`VersionFabric:0.19.3`、`VersionVanilla:20.0.1`、`VersionArgumentJavaV2:3` |
| 该实例的 mods | 含 **Sodium** 0.5.13、DistantHorizons 3.2.0-b、lithium、c2me、modernfix、Jade、JEI 等；另有 `gpu-optimizer-0.1.0.jar.disabled` 与 `mods\disabled\` |
| 实例内的 `reshade` 目录 | 存在但**为空**（早先尝试的残留） |

**结论：ReShade 应注入 `H:\javahome\bin\`（用户已自行完成），Modrinth 相关的改动与
`H:\Java\zulu...` 对路线 C 无意义。**

### 注入结果（用户自行完成，已核验）

| 检查 | 结果 |
|---|---|
| `H:\javahome\bin\opengl32.dll` | 5,255,448 B，产品名 `ReShade`，ver `6.8.0.2158` |
| SHA256 | `B2945C29E7095491A901746B400E58DB9B1592AB092BACF2A888CE37F02D08DA` —— 与已验证那份**一致** |
| `ReShade.ini` | 506 B，安装器默认：`EffectSearchPaths=.\reshade-shaders\Shaders\**`、`KeyOverlay=36`（**Home**）、**无 `[ADDON]` 段** → addon 默认从 DLL 所在目录加载，正合 DLSS5-Feeder 预期 |
| 标准 effect 包 | 11 个 shader + 2 纹理，含 **`DisplayDepth.fx`**（深度自检工具）与 **`UIMask.fx`** |
| **`ReShade.log`** | 仍是 **982 B 安装器占位说明** → **ReShade 尚未加载** |
| 原因（已定位） | MC 进程 21:46 启动，ReShade 21:51 注入 —— **进程早于注入**，需重启游戏 |

### NGX 前置条件（已核验，正常）

| 项 | 值 |
|---|---|
| 注册表 | `HKLM\SOFTWARE\NVIDIA Corporation\Global\NGXCore` → `FullPath=C:\WINDOWS\System32\DriverStore\FileRepository\nv_dispi.inf_amd64_b20cc8aeaed64fc2`，`Installed=1` |
| `nvngx.dll` | **不在 System32 是正常的** —— NGX 靠上述注册表键定位 |
| 驱动 | RTX 5070 / **616.92**（DLSS5-Feeder 实测的坏组合是 616.56 / 616.64，本机更**新**，未知） |

---

## ★★ 里程碑：深度检测已实测通过（2026-09-16 22:0x）

**这是 C 路线最大的风险点，现已排除。绿色通过。**

### 证据链

用户按 `DisplayDepth.fx` 的默认设置截图（`H:\javahome\bin\java 2026-09-16 22-02-43_1.png`，2560×1369，3.7 MB）。
当前模型不支持读图，改用**程序化量化分析**（PIL + numpy），结论反而更硬。

`DisplayDepth.fx` 源码里的选项定义：

```glsl
ui_items = "Depth map\0Normal map\0Show both (Vertical 50/50)\0";
```

`iUIPresentType=2` → **左右分割：左半 = 法线图，右半 = 深度图**
（日文说明原文：「左に法線マップ、右に深度マップ」）。

**逐格灰度占比分析精确命中该分界：**

```
0|CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD|
  左半 32 格全部 C（彩色）          右半 32 格全部 D（单色 >80%）
```

**判定「深度认到了」的三条依据：**

1. 右半深度图为单色且层次丰富（亮度跨 0-9 全档，第 11-12 行有清晰**地平线跃变**）。
   若深度缓冲缺失，此处应为一片均匀死值。
2. 左半法线图为蓝绿系、**地面偏绿** —— 法线**只能由深度缓冲重建**。
   源码说明原文：「正しい見え方では、全体的に青緑風で、**地平線を見たときに地面が緑掛かった色合い**になります」，**实测完全吻合**。
3. 色相分类图中左侧下半部大量 `g`（绿），即地面法线朝上的特征。

此前日志里 3 次 `WARN | A depth-stencil resource was destroyed while still in use.`
**经证实是无害噪声**，不是失败征兆。（我一度把它当成失败征兆，判断错了。）

### 深度方向：经用户实时观测确认为**正确**（我先前判断错了）

我基于**单张静态截图**的亮度分布（上暗下亮）推断「近亮远暗、方向反相」，
并据此改了 `RESHADE_DEPTH_INPUT_IS_REVERSED=1`。

**用户用可转动视角的实时画面直接观测，确认「远处亮」—— 即 `=0` 本来就是正确的。**
该设置**已回退为 `0`**。

**我错在哪**：从一张定格图推断「画面顶部 = 天空 = 远处」是无效推理 ——
镜头俯仰、地形遮挡都会让「屏幕上/下」与「远/近」不再对应，而静态图无法揭示拍摄时的朝向。
**凡是需要判断空间朝向的结论，必须靠可以主动移动视角的实时观测，不能靠单张静态帧。**

`DisplayDepth.fx` 的正确外观（源码定义）：近处暗、远处亮。

---

## 一、已确认可用的环境事实

### 1.1 ReShade 注入位置（已成功）

| 项 | 值 |
|---|---|
| 启动器 | **Modrinth App** |
| 实例 | `Aged`（1.20.1 Fabric）、`Fabulously Optimized`（1.21.11 Fabric） |
| 注入目标 | `C:\Users\Administrator\AppData\Roaming\ModrinthApp\meta\java_versions\zulu21.48.17-ca-jre21.0.10-win_x64\bin\` |
| 代理 DLL | `opengl32.dll`，**5,255,448 B** |
| 版本 | `6.8.0.2158`，产品名 `ReShade`，公司 `crosire`，描述 "post-processing injector for 64-bit" |
| 系统原生对比 | `C:\Windows\System32\opengl32.dll` = 983,040 B（证明注入的是代理层） |
| 覆盖层按键 | `ReShade.ini` → `[INPUT] KeyOverlay=36,0,0,0` → key code 36 = **`Home`** |
| 已生成 | `ReShade.ini`(408 B)、`ReShade.log`(982 B)、`ReShadePreset.ini`(0 B) |

**⚠️ 这个 JRE 是 Modrinth App 的公共运行时**，被它所有实例共用 → 注入同时影响 `Aged` 和 `Fabulously Optimized`。
如果改用 PCL / HMCL 启动，它们用别的 Java，**ReShade 不会加载**。

### 1.2 Add-on 支持：**完整版，已用导出表定案**

`ReShade.log` 里那 982 字节是安装器**预置的占位说明**，不是真日志 ——
原文 "If you are reading this after launching the game at least once, it likely means ReShade was
not loaded by the game."，即 **ReShade 尚未真正加载过**。

DLL 里同时存在这两条字符串，光看字符串**无法定案**（两种构建可能都编进去）：

```
Skipped loading add-on from '%s' because this build of ReShade has only limited add-on functionality.
Failed to register an event because only limited add-on functionality is available!
```

**定案证据是导出表**（共 494 个导出）：

| 导出 | 用途 |
|---|---|
| `ReShadeRegisterAddon` / `ReShadeUnregisterAddon` | addon 注册入口 |
| `ReShadeRegisterEvent` / `ReShadeUnregisterEvent` | 事件 |
| **`ReShadeRegisterEventForAddon`** / `ReShadeUnregisterEventForAddon` | 每 addon 事件 ← DLSS5-Feeder 必需 |
| **`ReShadeRegisterOverlayForAddon`** / `ReShadeUnregisterOverlayForAddon` | 每 addon 覆盖层 |
| **`ReShadeCreateEffectRuntime`** / `ReShadeDestroyEffectRuntime` / **`ReShadeUpdateAndPresentEffectRuntime`** | effect runtime —— `reshade_render_technique` 靠这组工作 |
| `ReShadeGetImGuiFunctionTable` | ImGui 函数表 |
| `ReShadeGetConfigValue` / `ReShadeSetConfigValue` / `ReShadeSetConfigArray` | 配置读写 |
| `ReShadeGetBasePath` / `ReShadeLogMessage` / `ReShadeVersion` | 杂项 |

→ **受限版不会有 `*ForAddon` 系列与 effect-runtime 导出。这是完整 addon 版。**

### 1.3 GPU / 驱动（与官方实测表的对照）

| 项 | 值 |
|---|---|
| GPU | NVIDIA GeForce RTX 5070（+ AMD Radeon 核显） |
| GL | `4.6.0 NVIDIA 616.92`，405 个扩展 |
| **驱动版本风险** | DLSS5-Feeder 实测坏组合是 **616.56 / 616.64**；**本题机器是 616.92，比他们测过的都新，未知** |
| 自检手段 | `host64\dlss5-feed-host64.exe --test`，`300/300 evaluates succeeded` 才算 OK |

---

## 二、实例现状（决定了能不能测）

### 2.1 `Aged` —— 目标实例（1.20.1 Fabric，131 个 mod）

| 存在 | 缺失（与用户原述不符） |
|---|---|
| `iris-1.7.5+mc1.20.1.jar` | **Sodium 缺失** |
| `DistantHorizons-2.2.1-a-1.20.1-forge-fabric.jar` | Voxy 无 |
| `indium-1.0.34+mc1.20.1.jar` | Embeddium 无（且 Embeddium 是 Forge/NeoForge 系，Fabric 上本就不适用） |
| `Shut Up GL Error-fabric-1.20.1-1.0.0.jar` | **shaderpacks = 0 个** |
| `GeckoLibIrisCompat-Fabric-1.0.0.jar` | `config` 目录 **0 项**（131 个 mod 的实例，配置为空，反常） |

**两个必须先处理的问题：**

1. **Indium 硬依赖 Sodium，而 Sodium 不在 `mods/` 里**（按文件名核验，未读 jar 清单）。
   Fabric 会在启动时报依赖错误弹红屏 —— **这与 ReShade 无关，但极易被误判成 ReShade 搞坏的**。
2. **`Shut Up GL Error` 会吞掉 GL 错误。** 调试 ReShade 期间应**移出 mods**，否则真实报错会被静默吃掉。

**好消息**：**shaderpacks 为 0** → Iris 不走自定义渲染管线 → 深度识别更接近原版，
比"带光影包"的情形**通过率更高**。这修正了此前"在 Iris 下很难"的判断。

### 2.2 `Fabulously Optimized` —— 1.21.11（非目标版本）

45 个 mod，含 `sodium-fabric-0.8.11+mc1.21.11.jar`、`iris-fabric-1.10.7+mc1.21.11.jar`、
`lithium`、`entityculling` 等；`shaderpacks` 同样为 0。
可作为"验证 ReShade 能否注入 Minecraft"的**干净对照实例**（用一个变量隔离问题）。

---

## 三、ReShade 的目录约定（关键，容易踩）

DLSS5-Feeder 文档原话：

> Add-on discovery is also a non-question: **ReShade *is* the local `opengl32.dll`,
> and add-ons load from its own directory.**

即 addon 默认要从 **`opengl32.dll` 所在的目录**加载 —— 对我们是那个 **JRE 的 `bin\`**，
这很不干净。但 DLL 里有 `AddonPath` 字符串，说明可用配置项改掉：

```ini
; 注入目录\ReShade.ini
[ADDON]
AddonPath=<游戏目录>\reshade-addons
DisabledAddons=

[GENERAL]
EffectSearchPaths=<游戏目录>\reshade-shaders\Shaders
TextureSearchPaths=<游戏目录>\reshade-shaders\Textures
```

- `*.addon` / `*.addon64` 放 `AddonPath`
- `DLSS5_Feed.fx` 放进 `EffectSearchPaths` 才能作为 effect 加载
- 当前安装**没有装任何 effect 包**（`reshade-shaders` 目录不存在），需要手工建目录或补装

---

## 四、所需组件清单

| 组件 | 来源 | 状态 |
|---|---|---|
| ReShade 6.8+ 含 add-on 支持 | reshade.me | ✅ **已装（6.8.0.2158，导出表已证 addon 完整）** |
| Generic Depth add-on | ReShade 自带，需在 Add-ons 页启用 | ⏳ 待首次运行确认 |
| 神经消费者：**Deep Fried Chicken**（推荐）或 Krish `renodx-dlss5.addon64` | 各自 **Discord** | ❌ 需用户获取（AI 无法访问 Discord） |
| `nvngx_dlssnr.dll` | GUI-DLSS5 包 | ✅ 已有 |
| `nvngx_dlss.dll`（放游戏目录旁） | Streamline SDK `bin\x64\` | ✅ 已有 |
| 运动矢量提供者：**LumeniteFX Kernel（=3，推荐）** | github.com/umar-afzaal/LumeniteFX | ❌ 需下载 |
| `dlss5-feed.addon64` + `DLSS5_Feed.fx` | DLSS5-Feeder release | ❌ 需下载 |

---

## 五、C 路线在 OpenGL 下的天花板（引自官方 Limitations）

> **DLAA contract, optional reduced work extent on D3D11** — render resolution still equals DLAA
> output resolution, but the private work extent can be 50–100% of the native backbuffer...
> **D3D12, Vulkan and OpenGL paths remain at 100%.** This is not jittered DLSS Super Resolution,
> and **a Quality/Balanced/Performance mode cannot be added**.

> Estimated motion vectors → temporal artifacts in fast motion; **the UI is processed with the scene**
> (a UI mask / pre-UI colour capture is future work).

**结论：OpenGL 下是纯 DLAA + DLSS 5 神经渲染观感，内部渲染分辨率不降 → 零帧率收益，无超分、无插帧。
且 UI（MC 的物品栏 / 聊天 / F3）会与场景一起被处理而拖影。**

底层机制（DLSS5-Feeder 官方描述 + 本项目实测验证）：

```
game frame → ReShade effects → [motion vectors] → [DLSS5_Feed] → 私有 D3D12 设备
                                                  深度 + MV        跑真正的 DLSS evaluate
                                                                    ↓
                                             神经结果写回画面 → 后续 effects → present
```

---

## 六、独立 Java 运行时（H 盘）— 已完成并验证

**动机**：Modrinth 的 JRE 是所有实例共用的，且 Modrinth App 可能校验/重下它把注入清掉。
独立一份 Java 可同时解决「污染其他实例」「被覆盖」「JRE 的 `bin\` 不适合作 addon 目录」三个问题。

| 项 | 值 |
|---|---|
| 运行时 | `H:\Java\zulu21.48.17-ca-jre21.0.10-win_x64\` |
| 来源 | Azul 官方 `https://cdn.azul.com/zulu/bin/zulu21.48.17-ca-jre21.0.10-win_x64.zip`（49,573,633 B） |
| 版本校验 | `Zulu21.48+17-CA (build 21.0.10+7-LTS)`；`IMPLEMENTOR=Azul Systems, Inc.`；`OS_ARCH=x86_64` —— **与 Modrinth 那份逐字一致** |
| 洁净性 | 解压后 `bin\` 内**无** `opengl32.dll`（原版） |
| ReShade 代理 | `bin\opengl32.dll` = 5,255,448 B，**SHA256 与已工作那份一致**：`B2945C29E7095491A901746B400E58DB9B1592AB092BACF2A888CE37F02D08DA` |
| 配置 | `bin\ReShade.ini`（本项目手写，1,600 B） |
| 内容根 | `H:\ReShadeMC\{addons, shaders, textures, presets}` |
| 备份 | `H:\Java\_reshade-backup\from_modrinth_jre\`（从 Modrinth JRE 移出的 17 个 ReShade 文件） |

**隔离已验证**：Modrinth 共享 JRE 已还原为 **327 个文件的原版**，
H 盘那份为 **329 个**，差异**恰好**是 `bin\opengl32.dll` 与 `bin\ReShade.ini` 两个文件。

**ini 键名已从 DLL 二进制核准**（非凭记忆）：
`AddonPath` ✅ `DisabledAddons` ✅ `EffectSearchPaths` ✅ `TextureSearchPaths` ✅
`PerformanceMode` ✅ `NoEffectCache` ✅ `IntermediateCachePath` ✅
`ScreenshotPath` ❌ 不存在（故未写入）

> ⚠️ 本 ini 是**部分文件**，ReShade 首次运行会与自身默认值合并并**重写**它。
> 首次启动后**必须重读该 ini**，确认这些路径被接受而非被重置。

### 切换实例 Java 的开关（已定位）

`%APPDATA%\ModrinthApp\app.db`（SQLite）→ 表 **`instance_launch_overrides`**，
列 `overrides`（JSONB），字段 **`java_path`**：

```json
{"java_path":null,"extra_launch_args":null,"custom_env_vars":null,"memory":null,
 "force_fullscreen":null,"game_resolution":null,
 "hooks":{"pre_launch":"","wrapper":"","post_exit":""}}
```

UI 路径：Modrinth App → 实例 → Options → Java 版本 → 覆盖全局设置 → 选
`H:\Java\zulu21.48.17-ca-jre21.0.10-win_x64\bin\javaw.exe`

另：`java_versions` 表登记了 Modrinth 自管的那份：
`21 | 21 | amd64 | ...\ModrinthApp\meta\java_versions\zulu21.48.17-ca-jre21.0.10-win_x64\bin\javaw.exe`

---

## 七、实测发现的阻塞项

### ⚠️ `Aged` 实例根本没安装完

```
instances 表：
  legacy:Fabulously Optimized   install_stage = installed        ✅
  legacy:Aged                   install_stage = not_installed    ⚠️ 未完成
```

这一条**解释了此前三个反常现象**：

1. `config` 目录 **0 项**（131 个 mod 的实例不该如此）
2. **Sodium 缺失**（`mods/` 里无 `sodium-*`）
3. **Indium 存在但依赖缺失** → Fabric 会报依赖错误弹红屏

**在修好之前测 ReShade，等于在坏地基上做实验。**
且该红屏**与 ReShade 无关**，极易被误判为 ReShade 搞坏了。

### 建议的测试顺序（单变量隔离）

1. 先拿 **`Fabulously Optimized`（installed，Sodium+Iris 正常）** 验证「ReShade 能否注入 Minecraft」
2. 再修好 `Aged`，验证「1.20.1 下深度识别能否成功」

### 其他

- **`Shut Up GL Error-fabric-1.20.1-1.0.0.jar`** 会吞掉 GL 错误 → 调试期间**移出 `mods/`**
- 已装上 ReShade 标准 effect 包，其中 **`UIMask.fx`** 可能是缓解「HUD 被一起处理」的抓手

---

## 八、待办与判据

| 步骤 | 判据 |
|---|---|
| 1. Modrinth App 里把实例 Java 覆盖为 H 盘那份 | Options → Java 版本 → 覆盖 |
| 2. 先用 `Fabulously Optimized` 启动 | ReShade 覆盖层能用 `Home` 呼出 |
| 3. 读**真** `ReShade.log` | 出现真实初始化日志，且含 `Searching for add-ons (*.addon, *.addon64) in '...'` |
| 4. 确认 `H:\Java\...\bin\ReShade.ini` 被重写后仍保留我们的路径 | `AddonPath` / `EffectSearchPaths` 未被重置 |
| 5. 让 `Aged` 装完（`install_stage` 变 `installed`） | Sodium 到位，无依赖红屏 |
| 6. 移出 `Shut Up GL Error` | — |
| 7. `Aged` 启动后进 **Add-ons** 页 | **`Generic Depth` 认到 MC 的场景深度 ← 整条 C 路线的生死线** |
| 8. 若第 7 步失败 | 记录 `ReShade.log` 全文；结论：C 在 MC 上不可行 → 回退路线 A/B |

**已备好的退路**：本项目自己的桥接已实测证明 GL↔VK 互操作完全可用
（见 README 第七节），因此即使 C 失败，超分/插帧仍有 A/B 两条路可走。

---

## ★★ 组件部署完成（2026-09-16 晚，全部经静态核验）

### 文件归位

| 文件 | 大小 | 放置位置 | 来源 | 核验 |
|---|---|---|---|---|
| `opengl32.dll` | 5,255,448 | `H:\javahome\bin\` | ReShade 6.8.0.2158 | SHA256 `B2945C29…08DA` |
| `nvngx_dlss.dll` | 58,956,912 | `H:\javahome\bin\` | Streamline SDK 2.14.1 | ver `310.9.1.0` |
| **`nvngx_dlssnr.dll`** | **165,840,496** | `H:\javahome\bin\` | GUI-DLSS5 v0.1.46 | **SHA256 `E16BCF15…1FC8E`，与原始 zip 逐位一致** |
| `dlss5-feed.addon64` | 308,224 | `H:\javahome\bin\` | DLSS5-Feeder 1.16.0-beta.2 | 见下 |
| `DLSS5_Feed.fx` | 51,193 | `…\bin\reshade-shaders\Shaders\` | 同上 | — |
| `lumenite_*.fx` ×8 | — | `…\Shaders\` | LumeniteFX `mainline` | — |
| `lumenite_*.fxh` ×4 | — | `…\Shaders\include\` | 同上（**必需**，缺则编译失败） | — |
| `lumenite_bluenoise256.png` | 259,314 | `…\reshade-shaders\Textures\` | 同上 | — |

### `nvngx_dlssnr.dll` 导出符号（证实是 NGX 插件）

55 个导出，含完整 NGX API：
`NVSDK_NGX_VULKAN_Init` / `_CreateFeature` / `_EvaluateFeature` / `_GetScratchBufferSize`，
`NVSDK_NGX_D3D12_Init` / `_CreateFeature` / `_EvaluateFeature`，
`NVSDK_NGX_D3D11_*`、`NVSDK_NGX_CUDA_*`。
> 注：`dlssnr_init/process/...` 那组**不在**这个文件里 —— 它们在 `dlssnr_host.dll`（另一个 shim）。
> 我一开始按 `dlssnr*` 过滤导出，什么也没匹配到，就是这个原因。

### `dlss5-feed.addon64` 的加载机制（曾误判，已澄清）

`dumpbin /exports` 确认它**只导出 `DESCRIPTION` 与 `NAME`**，我一度以为它无法被 ReShade 加载。

真相：它的**导入表里只有系统 DLL**（VERSION/KERNEL32/USER32/ADVAPI32/MSVCP140/VCRUNTIME140/ucrt），
**没有任何 ReShade 模块依赖**。二进制里那些 `ReShadeRegisterAddon` / `ReShadeGetConfigValue` /
`ReShadeRegisterEvent` 等是**字符串**（运行时用 `GetProcAddress` 解析）。

**结论：我们的主机命名为 `opengl32.dll`（而非 `ReShade64.dll`）不会造成任何问题。**

### ★★ 重大纠正：ReShade 分两个构建，导出表**证明不了**是哪一种

我早前从导出表看到 `ReShadeRegisterAddon` / `*ForAddon` / `ReShadeCreateEffectRuntime` 等，
据此写了「已用导出表定案：这是完整 addon 版」。**这个结论是错的。**

实测对照（同一目录、同一 addon，**只换 DLL**）：

| ReShade DLL | 大小 | `ReShade.log` 结果 |
|---|---|---|
| 标准/受限版 | 5,255,448 | `WARN \| Skipped loading add-on from '...' because this build of ReShade has only limited add-on functionality.` ❌ |
| **Addon 版** | **5,592,064** | `INFO \| Registered add-on "DLSS 5 Feed 1.16.0-beta.2" v1.16.0.2 using ReShade API version 20.` ✅ |

**教训**：受限版**照样导出完整 addon API**（好让 addon 能编译链接），真正的开关是**编译期**
`RESHADE_ADDON_LITE`，**只在运行时拒绝加载时才暴露**。
→ 判断这类能力时，**导出表只能证明「API 存在」，不能证明「功能启用」；必须做运行时验证**。

### ReShade 两个构建的获取方式

| 构建 | 安装包 | 大小 |
|---|---|---|
| 标准版（受限 addon） | `https://reshade.me/downloads/ReShade_Setup_6.8.0.exe` | 4,075,064 B |
| **Addon 版（完整 addon）** | `https://reshade.me/downloads/ReShade_Setup_6.8.0_Addon.exe` | **4,318,424 B** |

Addon 版安装包是自定义格式（载荷压缩存在 `.rsrc`），但**可用 7-Zip 直接解开**，
无需运行安装器：

```
7z x ReShade_Setup_6.8.0_Addon.exe -o<目录>
  → ReShade64.dll  5,592,064 B   ver 6.8.0.2155
    SHA256 0CEE63F9C9F13F3AC909C5B4903F4DBB4B719A7AB3B4F13B0DEAF83C814B94F7
    （另有 ReShade32.dll + 4 个 Vulkan/XR layer json）
```

把 `ReShade64.dll` 改名为 `opengl32.dll` 放进 Java 的 `bin\` 即等效于安装。
**受限版已备份**：`H:\DLSS\reshade_addon_extract\opengl32.dll.limited-build.bak`。

依赖预检：15 个依赖全部可解析（MSVC 运行时在 System32 与 JDK 自带副本中均有）。

### `DLSS5_Feed.fx` 源码揭示的必知规则

1. **addon 在 `DLSS5_Feed` technique 渲染完之后立刻运行 DLSS** → **必须勾选 `DLSS5_Feed.fx`**
2. 运动矢量 provider：`0`=texMotionVectors（默认）/ `1`=Launchpad / **`2`=VORT（源码标注 recommended）** /
   **`3`=LumeniteFX Kernel** / `4`=LumeniteFX QuantMotion。已备好 3 与 4
3. provider 的 technique **必须排在本 effect 之上**才会生效
4. 它带有一条针对我们最担心失败模式的自我诊断字符串：
   `"<-- depth is FLAT while the scene moves: ReShade's Generic Depth is on the wrong buffer"`
5. **无神经消费者时仍可运行「纯 DLAA」** —— 驱动兼容表里 `(none — DLAA only, no neural pass)` 一行是 `✅ 300/300`。
   因此**不必等 Discord 组件就能验证整条管线**

### 仍缺（只能从 Discord 取，AI 无法访问）

`deep-fried-chicken.addon64` + `deep-fried-chicken-nvngx.dll` + `deep-fried-chicken.cfg`
**或** Krish 的 `renodx-dlss5.addon64`（RenoDX Discord `#DLSS5`）。**两者只能放一个。**
两者均不自带 `nvngx_dlssnr.dll`。

### 下一步操作（用户）

1. ReShade 全局预处理定义加 `DLSS5_MV_PROVIDER=3`
2. 效果列表中：**先启用 `lumenite_Kernel.fx` 的 technique，再在其下方启用 `DLSS5_Feed.fx`**；关掉 `DisplayDepth.fx`
3. 重启 MC（addon 与 effect 变更需重新加载）
4. 读日志：`H:\javahome\bin\dlss5-feed.log`（新增）与 `ReShade.log`
   —— DLSS5-Feeder 要求 `dlss5-feed.log` **第 1 行**为
   `dlss5-feed 1.16.0-beta.2 commit a6c23bd`

---

## ★★★ 驱动与 NGX 栈预检：完全通过（2026-09-16 22:24）

DLSS5-Feeder 自带 `host64\dlss5-feed-host64.exe --test`，可在**不启动游戏**的前提下
单独验证「驱动 + NGX + NGX 插件」这一栈。它需要三样东西与它同目录：

| 需要 | 说明 |
|---|---|
| `nvngx_dlss.dll`、`nvngx_dlssnr.dll` | 它在**自己所在目录**查找 NGX 运行时（不是在游戏目录） |
| `dxgi.dll` | ReShade 的 DLL **改名为 `dxgi.dll`**（同一份二进制可直接改名复用）——它需要自己起一个私有 D3D12 swapchain |
| 工作目录 = 它自己所在目录 | 结果写进 `dlss5-feed-host.log`，**stdout 无输出**，退出码反映结果 |

### 实测结果

```
[host] device adapter: NVIDIA GeForce RTX 5070  PCI 10DE:2F04  driver 616.92
[host] NGX runtime nvngx_dlssnr.dll: 310.8.0.0, NVIDIA DLSSNR - DVS PRODUCTION, CL 38718415
[host] NGX runtime nvngx_dlss.dll:   310.9.1.0, NVIDIA Deep Learning SuperSampling, CL 38868064
[host] NGX feature requirements: SuperSampling (DLSS)          -> supported (min arch 0x160)
[host] NGX feature requirements: feature 18 (neural rendering) -> supported (min arch 0x1B0)
[host] NVSDK_NGX_D3D12_Init -> 0x00000001 (Success)
[host] NGX capabilities: SuperSampling.Available=1  NeedsUpdatedDriver=0  MinDriver=470.0
[host] DLSS Quality at 1920x1080: optimal 1280x720, render range 960x540 .. 1920x1080
[host] feature ready: 640x360 DLAA flags=74
[host] --test finished: 300/300 evaluates succeeded
[host] --test: DLSS GPU 0.43 ms/frame over 299 timed frames at 640x360
[host] exit 0
```

**`300/300 evaluates succeeded` 即官方规定的通过标准。**

### 这一条解决了哪些悬置的未知

| 未知 | 结论 |
|---|---|
| **驱动 616.92 可用性**（官方实测的坏组合是 616.56 / 616.64，本机更新） | ✅ **可用**，`NeedsUpdatedDriver=0`，`MinDriver=470.0` |
| `nvngx_dlssnr.dll` 身份 | ✅ `NVIDIA DLSSNR - DVS PRODUCTION` `310.8.0.0` |
| feature 18（神经渲染）在 RTX 5070 上是否支持 | ✅ 支持，最低架构 `0x1B0` = Blackwell |
| DLSS evaluate 实际吞吐 | ✅ 实测 **0.43 ms/帧 @640×360** |
| 无神经消费者时能否运行 | ✅ 能 —— 本次即「无消费者」配置，对应官方表 `(none — DLAA only, no neural pass)` 一行 |

**推论：不必等 Discord 组件即可验证游戏侧整条管线（纯 DLAA 路径）。**

### 已可清理

`H:\DLSS\DLSS5-Neural-Render-v0.1.46-windows-x64.zip`（467,988,619 B）——
`nvngx_dlssnr.dll` 已解出并通过哈希核验，此 zip 可删。

---

## ★★★ 全部组件安装完成（2026-09-17 18:45）

### 最终文件清单（`H:\javahome\bin\`）

| 文件 | 大小 | 来源 | 校验 |
|---|---|---|---|
| `opengl32.dll` | 5,592,064 | ReShade 6.8.0.2155 **Addon 版** | SHA256 `0CEE63F9…B94F7` |
| `nvngx_dlss.dll` | 58,956,912 | Streamline SDK 2.14.1 | Authenticode **Valid**（NVIDIA） |
| `nvngx_dlssnr.dll` | 165,840,496 | GUI-DLSS5 v0.1.46 | SHA256 `E16BCF15…1FC8E` |
| `dlss5-feed.addon64` | 308,224 | DLSS5-Feeder 1.16.0-beta.2 | — |
| `deep-fried-chicken.addon64` | 4,422,656 | DFC 2.0.0-CP184 | SHA256 `C99C3528…4F303` ✅ |
| `deep-fried-chicken-nvngx.dll` | 3,584 | 同上 | SHA256 `9350E044…394FF` ✅ |
| `deep-fried-chicken.cfg` | 19,790 | 同上 | SHA256 `A52A51EA…6A9AD` ✅ |
| `ReShade.ini` | 2,982 | — | 见下 |
| `.dfc-installer\manifest.json` | 1,558 | Chicken Assist | `status: installed` |

effect 侧：`DLSS5_Feed.fx` + `lumenite_*.fx`(8) + `include\*.fxh`(4) + `lumenite_bluenoise256.png`

### DFC 安装（用官方 Chicken Assist，非手动复制）

```
CHICKEN-ASSIST.cmd -Action Install -Target H:\javahome\bin \
  -GameExecutable H:\javahome\bin\java.exe -Api opengl -Yes -NonInteractive
```

安装卡实测输出：
```
Route       : DFC through external DLSS5-Feeder
Layout      : x64-external-core
Plan        : An external feeder is already present. DFC will keep it and
              install only the DFC consumer.
[PASS] ×10，仅 [WARN] Runtime evidence（启动游戏后可消除）
```

**路线矩阵里明确列着我们的情况**（Chicken Assist `installer/README.md`）：
> `| x64 OpenGL | DFC through external DLSS5-Feeder | Chicken Assist invokes the official
> cross-API route; **optical-flow motion may be required** |`

→ 正对应我们已配好的 LumeniteFX Kernel 光流（`DLSS5_MV_PROVIDER=3`）。

### 三个 payload 变体是同一份二进制

`payload-manifest.json` 里 `x64-core` / `x64-external-core` / `x64-native-d3d11-core`
的 SHA256 **完全相同**（`C99C3528…` / `9350E044…` / `A52A51EA…`），只是安装器的记账分类，
**不存在放错变体的风险**。

### Chicken Assist 对我们配置的改动：仅一行

安装器**主动删除**了我手写的 `[ADDON] LoadFromDllMain=deep-fried-chicken.addon64`，
理由是该键在 2.0 中已废弃（对应 1.4.3 更新说明：Chicken 会自己加入 early-load 列表）。
逐行 diff 确认**其余配置一字未动**：`DLSS5_MV_PROVIDER=3`、effect 路径、预设路径全部保留。
精确备份：`.dfc-installer\backup\reshade\ReShade.ini.D4A4C0F8….pre-cp47.bak`

### ⚠️ 环境坑：调用 Chicken Assist 必须先修正 `PSModulePath`

DSH 会话的 `PSModulePath` 是 PowerShell 7 的（且含空条目与 `W:\Fuck`），
子进程 `powershell.exe` 5.1 继承后会**无法自动加载自己的 `Microsoft.PowerShell.*` 模块**，
表现为 `Get-AuthenticodeSignature` / `Get-FileHash` "not recognized"，进而误报
`[FAIL] Ownership record`、`DLSS-NR DLL: missing`。**规律已实测确认**：

| 调用 | 修正 PSModulePath | 结果 |
|---|---|---|
| Inspect #1 | ❌ | FAIL（`Get-AuthenticodeSignature` 找不到） |
| Inspect #2 | ✅ | PASS |
| Install | ✅ | PASS |
| Diagnose #1 | ❌ | FAIL（`Get-FileHash` 找不到，误报） |
| Diagnose #2 | ✅ | **全 PASS** |

修法：调用前设
```
$env:PSModulePath='C:\Users\Administrator\Documents\WindowsPowerShell\Modules;C:\Program Files\WindowsPowerShell\Modules;C:\Windows\System32\WindowsPowerShell\v1.0\Modules'
```
**这是一条通用教训**：从本会话派生的任何 5.1 子进程都可能撞上，涉及签名的判断尤其要先确认模块可用。

### 关键配置现状

| 项 | 值 |
|---|---|
| `Techniques` | `Lumenite_Kernel@lumenite_Kernel.fx,DLSS5_Feed@DLSS5_Feed.fx` |
| `TechniqueSorting` 前 3 位 | `Lumenite_Kernel` → `Daltonize` → `Deband`（**生产者在前** ✅） |
| `DLSS5_MV_PROVIDER` | `3`（LumeniteFX Kernel） |
| `RESHADE_DEPTH_INPUT_IS_REVERSED` | `0`（据用户实时观测确认） |
| `dlss5-feed.cfg` | `enabled=1 mode=2 work_resolution=100 stall_log_ms=50` |

### 唯一剩余步骤

**启动游戏**（此前的 `ReShade.log`/`dlss5-feed.log` 都是安装前的）。
DFC 1.4.3 说明：会自己加入 early-load 列表，**首次启动是注册，可能需再完整重启一次**。
之后应产出新文件 `deep-fried-chicken.log`，再跑 `-Action Diagnose` 即可拿到运行期裁决。

---

# 2026-09-17 19:07 — 模组侧收尾：`/dlss` 命令 + 视频设置里的总开关与面板入口

路线 C 已经跑通（见上文运行期证据）。这一轮做的是**模组自己的壳**：让玩家不需要记命令就能开 DLSS。

## 交付物

| 东西 | 位置 |
|---|---|
| 构建产物 | `build/libs/wpywdlss-0.1.0.jar`（3.83 MB，SHA `765E71434912A74F96199FD4C2CE50942F1BAC49FACBB270685F910EBDA125BB`） |
| 已部署 | `…\1.20.1-Fabric 0.19.3\mods\wpywdlss-0.1.0.jar`（与构建产物哈希一致） |
| 新增源码 | `cfg/MasterSwitch.java`、`gui/VideoSettingsHook.java` |
| 改动源码 | `cfg/FlatCfg.java`（行尾保留，见下）、`DlssClientMod.java`、`gui/DlssSetupScreen.java`、`fabric.mod.json` |

## 1. 命令改名 `wpywdlss` → `dlss`

子命令：`dlss`（开面板）/ `status` / `install` / `uninstall` / `on` / `off`。

`openPanel()` 现在用 `mc.screen` 当 parent，所以从哪个界面进去、关掉就回到哪个界面，而不是一律掉回游戏。

## 2. 视频设置里注入两个按钮（`gui/VideoSettingsHook.java`）

**左侧 = 总开关（`DLSS: 开` / `DLSS: 关`），右侧 = 面板入口（`DLSS · 已安装` 等，标签直接带实时状态）。**

### 为什么用事件而不是 mixin

`Screens.getButtons(screen)` 返回的是 Fabric 的 `ButtonList`，构造时**同时持有屏幕自己的 `drawables` / `selectables` / `children` 三个列表**，
`add()` 往三个里都插 —— 也就是 `addRenderableWidget` 做的事。**渲染、点击派发、键盘焦点全部走原版同一条路径**，
不需要 accessor / `@Invoker`。这一点是抽 `ButtonList.java` 源码确认的，不是推测。

### 布局常量是 dump 出来的，不是猜的

`javap -c net.minecraft.client.gui.screens.VideoSettingsScreen` 实测：

```
new OptionsList(minecraft, width, height, 32, height - 32, 25)
Done → Button.builder(GUI_DONE, …).bounds(width/2 - 100, height - 27, 200, 20)
```

所以**选项行止于 `height - 32`，Done 所在那条横带左右两侧都是空的**，两个按钮就放那里：

```
side = width/2 - 100 - 6 - 4          // 4 = 离窗口边的余量
w    = min(170, side)                 // side < 64 时干脆不注入，/dlss 仍可用
左侧 x = width/2 - 100 - 6 - w        右侧 x = width/2 + 100 + 6
y    = height - 27
```

窗口宽度实测：853 → w=170 两侧都放得下；640 → w=170 仍在界内；427 → w=103 仍在界内。

### 重复注入的疑虑（查清了，是安全的）

`Screen.init(mc,w,h)` 有个 `initialized` 标志位，且 `ScreenMixin` 在 `init` TAIL **和** `resize` TAIL 都调 `afterInit`，
一度怀疑会重复加按钮。dump `Screen` 字节码后确认三条路径都只重建一次：

| 路径 | `init(mc,w,h)` 里发生了什么 | 结果 |
|---|---|---|
| 首次打开 | `initialized=false` → `init()` | 一次 |
| 从面板返回（同一个 Screen 实例） | `initialized=true` → `repositionElements()` | 一次 |
| 窗口缩放 | `resize` → `repositionElements()` | 一次 |

关键：`Screen.repositionElements()` 的实现就是 `rebuildWidgets()` = `clearWidgets(); clearFocus(); init();`，
而 `resize` 只调 `repositionElements()`、**不再回调 `init(mc,w,h)`**，所以不会叠加触发。
而且 `clearWidgets()` 清的正是 `ButtonList` 持有的那三个列表，与注入点完全对齐。

### 探测缓存

`analyze()` 要哈希 ~10 MB 载荷，所以状态探测带 1.5 s TTL；`DlssSetupScreen.refresh()` 与 `install/uninstall/on/off` 都会
`VideoSettingsHook.invalidate()` 强制下次重读。

## 3. `cfg/MasterSwitch.java`：一个开关同时管两个 add-on

DLSS 不是单一标志位 —— **DLSS5-Feeder 和 Deep Fried Chicken 各有一个 `enabled`，必须同时开**，
半开状态（Feeder 在请求神经帧但没人渲染）是真实且难查的故障模式。所以 `read/write` 一律成对处理，且：

- 任一 cfg 缺失 → 不算「开」（不把「没装」显示成「已开」）；
- 未安装时 `write` 直接拒绝，**不创建空 cfg 文件**；
- 值没变就不写盘（`FlatCfg.set` 返回 false），避免无谓重写 666 行、与 add-on 自己的写回打架；
- 返回的是**写完之后磁盘上的真实状态**，不是调用方要求的状态。

## 4. ★ 实测抓到的真 bug：`FlatCfg.save()` 会把别的程序的文件整份改成 LF

真实环境的行尾实测：

| 文件 | CRLF | 裸 LF | 字节 |
|---|---|---|---|
| `dlss5-feed.cfg` | 23 | 0 | 353 |
| `deep-fried-chicken.cfg` | **666** | **0** | 19790 |
| `ReShade.ini` | 95 | 0 | 2982 |
| `ReShadePreset.ini` | 109 | 0 | 2452 |

而 `FlatCfg.save()` 固定 `String.join("\n", …)` —— **一按开关就会把 DFC 那份 666 行的 CRLF 文件连行尾一起重写**。
`FlatCfg` 原本的注释还把「用 LF 保持 diff 友好」当成优点，恰恰搞反了：这两个文件属于别的程序。

修法：`load()` 读原始字节并探测主导行尾，`save()` 用读进来的那个行尾；新建文件才回退 `System.lineSeparator()`。
顺带补了 `decodeLines()`，语义对齐 `Files.readAllLines`（末尾终止符不产生空行、中间空行保留）。

`IniFile.save()` 本来就固定写 CRLF，与真实文件一致，**未改动**（避免无谓churn）。

## 5. 验证

`MasterSwitch` 只碰文件系统、不依赖 MC 与 GL，所以**能真跑**：`E:\deepseek\_tmp\tlstest\MasterSwitchTest.java`，
**35 项全过**：

- 未安装：不创建文件、`write` 被拒且给出原因；
- 半安装（只有 feeder cfg）：`switchable=false`、`on=false`；
- **CRLF 场景（照抄真实安装）**：605 行 CRLF 文件翻转后 **CRLF=605 / 裸 LF=0**，且
  「恰好一行变化，其余逐字保留」—— 首键、末键、注释行、空行全部原样；
- **LF 场景**：LF 进 LF 出，确认修法没有把 CRLF 写死；
- 冗余写是真空操作（mtime 与字节都不变）；
- 真值判定：`0`/`1`/`true`/`TRUE`/`enabled = 1`（等号两侧空格）。

对真实安装目录只读探测：`on=true switchable=true feedPresent=true chickPresent=true`。

## 6. 静态验证清单（每条都对着实际编译好的 jar 查过）

| 依赖 | 依据 |
|---|---|
| `Screens.getButtons(Screen) → List<AbstractWidget>` | `javap` fabric-screen-api-v1-2.0.9 |
| `ButtonList.add` 同时进三个列表 | 抽 `ButtonList.java` 源码 |
| `ScreenEvents.AFTER_INIT` 回调签名 `(client, screen, w, h)` | 抽 `ScreenMixin.java` 源码 |
| `Button.Builder.tooltip(Tooltip)` / `Tooltip.create(Component)` | `javap` minecraft-merged 1.20.1 |
| `OptionsList` 只有 `addBig/addSmall(OptionInstance…)` | `javap` —— 所以塞自定义控件行要 2–3 个 mixin accessor，**不划算，放弃** |
| 已装 `fabric-api-0.92.11` 内含 `fabric-screen-api-v1` | 列出 jar 内嵌 `META-INF/jars/fabric-screen-api-v1-0.92.11.jar` |

## 7. 待用户确认

**必须重启游戏**（ReShade 是 `opengl32.dll` 代理，本次启动时早已解析完毕，模组无法在进程内激活它）。
重启后进 **设置 → 选项 → 视频设置**，应看到：

- 右侧 `DLSS · 已安装`（若显示未安装/需修复，说明 `H:\javahome\bin` 的载荷状态变了）；
- 左侧 `DLSS: 开`（只有两个 cfg 都在时才出现）。

点左侧应能即时切换（两个 cfg 都带 CRLF 保留、只有 `enabled=` 一行变化），点右侧进面板。

## 8. 顺手发现（与本模组无关）

`mods` 目录里有**两个 MouseTweaks**（2.25 与 2.26）同时存在，是重复 mod；另外
`gpu-optimizer-0.1.0.jar.disabled` 已被改名为 `.disabled` 所以不会加载。

---

# 2026-09-17 19:40 — 【重大】1.21.1 + VulkanMod 的 Vulkan 通路打通

**结论先行：能。** DLSS5-Feeder 有**一等公民的 Vulkan 传输层**，不需要 OpenGL，也不是"勉强识别"。
这一轮把整条链路在**不启动 Minecraft 的前提下**验证到了 API 层。

## 8.1 起因：为什么之前认为不可能，以及为什么那是错的

1.21.1 实例的 `mods` 里有 **VulkanMod 0.6.7**（`== VulkanMod ==` / `Backend library: LWJGL 3.3.3`，
日志里没有 `OpenGL Version` 行），而当时 ReShade 是**按 OpenGL 装的**（`H:\javahome\bin\opengl32.dll` 代理）。

19:15 那次启动的实测证据（说明 GL 装法在 Vulkan 游戏上确实拿不到帧）：

| 事实 | 出处 |
|---|---|
| ReShade 初始化了，但只挂到 `wglSetPixelFormat`（GLFW 的临时 GL 上下文） | `ReShade.log` 19:15:21 |
| 两个 add-on 注册后 **6 毫秒就被卸载**（`.358` 注册 → `.364/.365` Unloading） | 同上 |
| feeder/chicken 日志**只有启动行**，零帧 | `dlss5-feed.log` |

**但那只是"装法不对"，不是"做不到"。** feeder 的二进制里带完整的 Vulkan 实现：

> *"Feeds DLSS 5 neural rendering ... in **D3D11, D3D12, Vulkan and OpenGL** games without DLSS"*

## 8.2 feeder 的 Vulkan 机制（源码级）

`jlrouzies-fr/DLSS5-Feeder` 是**开源**的，`docs/` 与 `layer/` 都在。README §The Vulkan path：

- DLSS 5 add-on **只 hook D3D12 NGX 入口**，所以即使 NGX 有 Vulkan API 也没用；
- 因此 evaluate 跑在**私有 D3D12 设备**上，帧通过**共享内存**跨 API 边界（不是拷到内存再回来）；
- D3D12 侧建 `D3D12_HEAP_FLAG_SHARED` 纹理 + 两个 `D3D12_FENCE_FLAG_SHARED` 栅栏并导出 NT handle；
- add-on 用**裸 Vulkan** 把它们导入游戏自己的 `VkDevice`
  （`VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT` 等）—— **D3D12 栅栏和 Vulkan timeline semaphore 是同一个对象**；
- 互操作扩展**在 `vkCreateDevice` 时就固定了**，而游戏很少主动要 → add-on 在 `vulkan-1.dll` 的
  `vkCreateDevice` 上打 MinHook 把扩展追加进去（`src/feed_vk_hook.h`）。

## 8.3 ★ 两个必须知道的坑（都是实测定位的，不是猜的）

### 坑 1：`ReShade64.dll` 加载失败 `error 1114` —— 根因是 `<exe目录>\ReShade.ini` 必须存在

现象：loader 报 `Failed to open dynamic library "C:\ProgramData\ReShade\.\ReShade64.dll" with error 1114`。

定位过程（每一步都排除了一个假设）：

| 实验 | 结果 | 排除了什么 |
|---|---|---|
| `DONT_RESOLVE_DLL_REFERENCES` 加载 | 成功，`vkNegotiateLoaderLayerInterfaceVersion` **是导出符号** | 导出表缺失 |
| 同字节改名 `ReShade64.dll` 放同一目录 | 1114 | 目录/DLL 损坏 |
| 把它加进 `ReShadeApps.ini` 白名单 | 仍 1114 | 白名单（至少不是这么用的） |
| 补 `ReShade32.dll` / `ReShade32.json` 兄弟文件 | 仍 1114 | 32 位兄弟 |
| 同字节命名为 `opengl32.dll` | **成功** | → 决定因素是**模块名** |

然后读 `crosire/reshade@v6.8.0` 的 `source/dll_main.cpp`：

```cpp
const bool is_opengl = _wcsicmp(module_name.c_str(), L"opengl32") == 0;
...
const bool default_base_to_target_executable_path =
    !is_d3d && !is_dxgi && !is_opengl && !is_dinput && !is_asi && !is_uwp_app();
...
// "This e.g. prevents loading the implicit Vulkan layer when not explicitly enabled for an application"
if (default_base_to_target_executable_path && !GetEnvironmentVariableW(L"RESHADE_DISABLE_LOADING_CHECK", ...))
    if (!std::filesystem::exists(config.path(), ec))
        return FALSE;   // ← 1114 的来源
```

而 `ini_file.cpp`：

```cpp
reshade::ini_file &reshade::global_config() {
    return ini_file::load_cache(g_reshade_base_path / L"ReShade.ini");
}
```

**所以那道门就是 `<基路径>\ReShade.ini` 是否存在**；`opengl32.dll` 这种代理名直接跳过该检查，
`ReShade64.dll` 这种 layer 名则必须过。`vulkaninfo.exe` 在 System32、那里没有 `ReShade.ini` → 必然 1114。
（注意：`ReShadeApps.ini` 在 **ReShade DLL 源码里根本不存在**，它是 setup 工具的概念。）

### 坑 2：GL 代理与 Vulkan layer **不能共存于同一进程**

`dll_main.cpp` L200-211：

```cpp
for (DWORD i = 1; i < ...; ++i)
    if (modules[i] != hModule && GetProcAddress(modules[i], "ReShadeVersion") != nullptr) {
        // "Another ReShade instance was already loaded from '%s'! Aborting initialization ..."
        return FALSE;
    }
```

`ReShadeVersion` 是 `extern "C" __declspec(dllexport) const char *ReShadeVersion`，我们的 `opengl32.dll`
**确实导出它**。隔离实验（用 `RESHADE_DISABLE_LOADING_CHECK=1` 绕过坑 1 以触及坑 2）：

| 场景 | 结果 |
|---|---|
| 单独加载 layer 的 `ReShade64.dll` | **成功** |
| 先加载 GL 代理 `opengl32.dll`，再加载 layer | **1114 —— 被挡** |

而 **GLFW 初始化时必然加载 exe 目录里的 `opengl32.dll`**（`wglGetProcAddress`），且它常驻不卸载。
所以**只要 `opengl32.dll` 还在 JVM 目录里，Vulkan layer 永远起不来** —— 这解释了为什么
"共用 `H:\javahome`"这条路走不通。

## 8.4 解法：给 1.21.1 一个干净的 JVM 目录，配置用绝对 BasePath 共享

`H:\javahome` 原样不动（1.20.1 的 OpenGL 那套继续用），新建：

```
H:\javahome-vulkan\                 ← 从 C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot 复制的干净 JDK
                                        (327 MB / 486 文件；bin 里没有 opengl32.dll)
  bin\ReShade.ini                   ← 唯一需要的文件，两行：
                                        [INSTALL]
                                        BasePath=H:\javahome\bin
  conf\ include\ jmods\ legal\ lib\ release    ← 原样复制
```

`get_base_path()` 会先读 `<exe目录>\ReShade.ini` 的 `[INSTALL] BasePath`（绝对路径直接用），
于是这个实例的**全部配置、预设、shader、add-on、NGX 运行时都取自 `H:\javahome\bin`，一份都没复制**。

### 机器级 Vulkan layer 目录

```
C:\ProgramData\ReShade\
  ReShade64.dll   5,592,064 B  ← 与 H:\javahome\bin\opengl32.dll **字节完全相同**
                                  （都是 ReShade 6.8.0.2155 add-on 构建，双方 SHA 一致）
  ReShade64.json  526 B        ← crosire 官方 layer 清单（name=VK_LAYER_reshade）
  ReShade32.dll / ReShade32.json
  ReShadeApps.ini              ← Apps=<java 路径列表>，UTF-8+BOM
```

注册：**`HKCU\SOFTWARE\Khronos\Vulkan\ImplicitLayers`**（名称=json 路径，DWORD 0）。
**故意不用 HKLM** —— 本会话是 Medium 完整性、写 HKLM 被拒；而本机 OBS 的
`obs-vulkan64.json` 就在 HKCU，证明这条在 loader 1.4.341 上确实会加载。

## 8.5 验证结果（不依赖 Minecraft）

把 `vulkaninfo.exe` 临时复制进 `H:\javahome-vulkan\bin\` 再跑（跑完删除）：

```
[Vulkan Loader] DEBUG | LAYER:  Loading layer library C:\ProgramData\ReShade\.\ReShade64.dll
[Vulkan Loader] INFO  | LAYER:   Insert instance layer "VK_LAYER_reshade"
[Vulkan Loader] INFO  | LAYER:   Inserted device layer "VK_LAYER_reshade"
```
```
ReShade.log:
  Initializing ReShade 6.8.0.2155 loaded from 'C:\ProgramData\ReShade\ReShade64.dll'
      into 'H:\javahome-vulkan\bin\vulkaninfo.exe' ...
  Redirecting vkCreateInstance(...)
  Searching for add-ons (*.addon, *.addon64) in 'H:\javahome\bin' ...
  Registered add-on "Deep Fried Chicken 2.0.0"
  Registered add-on "DLSS 5 Feed 1.16.0-beta.4"
```
```
dlss5-feed.log:
  [feed] vkCreateDevice hook installed on vulkan-1!vkCreateDevice
  [feed] vkQueuePresentKHR hook installed
  [feed] vkCreateDevice #1/#2: app asked for 0 extension(s), added 7, timelineSemaphore enabled
  [feed]   VK_KHR_external_memory / _win32 / external_semaphore / _win32 /
  [feed]   dedicated_allocation / get_memory_requirements2 / timeline_semaphore   ← 全部 ADDED
  [feed] Vulkan present dependency hook installed at device dispatch
  [feed] vkCreateDevice -> 0
```

唯一没验证的只剩"**真实 swapchain 每帧投递**"（vulkaninfo 不呈现画面），那需要真正启动游戏。

## 8.6 顺带升级与产物

- feeder **beta.2 → beta.4**（`dlss5-feed.addon64` SHA 从 `0994FC75…` 变 `875B6BAA…`；
  `DLSS5_Feed.fx` 两份一致）。beta.4 zip 的 SHA-256 与官方 release 页公布值
  `D16F8B527F76FF1F531682A576D699CB5634838C0E2899585E3671814EDA2745` **完全一致**。
- 兜底 layer 放到 `H:\javahome\bin\layer-x64\`（`VkLayer_feed_vk.dll/json` + `run-with-feed-layer.bat`），
  仅当 `vkCreateDevice` hook 装不上时才需要，当前**未启用**。
- `H:\javahome\bin\ReShade.ini` 的 `[ADDON]` 补了 `AddonPath=H:\javahome\bin`（原来是空段）。
- 官方离线产物存到 `tools\feeder\`（4.8 MB）：beta.4 zip、`Install-DLSS5Feeder.ps1`、
  `Verify-DLSS5Feeder.ps1`、`ReShade_Setup_6.8.0_Addon.exe`、两份 layer json、README。

## 8.7 待用户执行

PCL2 里把 **1.21.1 实例**的 Java 单独指到 `H:\javahome-vulkan`（版本设置 → 设置 → Java 选择），
**不要改全局**（全局若变，1.20.1 也会离开带 `opengl32.dll` 的目录，那条已跑通的路会断）。

启动后看三处：`ReShade.log` 是否出现 `loaded from 'C:\ProgramData\ReShade\ReShade64.dll'`、
`dlss5-feed.log` 是否有 `frame N delivered (…, Vulkan transport, 1 submit)`、
`deep-fried-chicken.log` 是否有 `feeder_marker=` / `interop_state=`。官方校验脚本：
`powershell -File tools\feeder\Verify-DLSS5Feeder.ps1 -GamePath H:\javahome-vulkan\bin`。


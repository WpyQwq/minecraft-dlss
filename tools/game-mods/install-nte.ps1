$ErrorActionPreference = 'Continue'

$nte    = 'D:\Neverness To Everness\Client\WindowsNoEditor\HT\Binaries\Win64'
$src    = 'H:\javahome\bin'
$reshade = 'E:\deepseek\MinecraftDLSS\tools\games'   # not used; kept for clarity

Write-Host '=== STEP 0: 目标目录是我上次还原干净的，先确认没有任何游戏自己的文件会被覆盖 ==='
$willAdd = @('d3d12.dll','ReShade.ini','ReShadePreset.ini',
             'deep-fried-chicken.addon64','deep-fried-chicken-nvngx.dll','deep-fried-chicken.cfg',
             'nvngx_dlssnr.dll')
$clash = @()
foreach ($n in $willAdd) { if (Test-Path "$nte\$n") { $clash += $n } }
if ($clash.Count -eq 0) {
    Write-Host '  无冲突：我们只新增文件，不动游戏自己的任何文件'
} else {
    Write-Host ('  注意，这些已存在，会被覆盖: ' + ($clash -join ', '))
}
Write-Host ('  HTGame.exe 在位: ' + (Test-Path "$nte\HTGame.exe"))
Write-Host ('  目录里已有 dxgi.dll / d3d12.dll: dxgi=' + (Test-Path "$nte\dxgi.dll") + '  d3d12=' + (Test-Path "$nte\d3d12.dll"))

Write-Host ''
Write-Host '=== STEP 1: 代理 DLL —— 命名为 d3d12.dll（ReShade 官方支持的代理名）==='
# 用经过验证的 add-on 构建：导出 ReShadeRegisterAddon + ReShadeUnregisterAddon，
# 且已在 Minecraft 上实测成功加载过 add-on。
$addonBuild = "$src\opengl32.dll"
Copy-Item $addonBuild "$nte\d3d12.dll" -Force
$h = (Get-FileHash $addonBuild -Algorithm SHA256).Hash
Write-Host ("  d3d12.dll  <- " + $addonBuild)
Write-Host ("  大小 {0,12:N0} B   版本 {1}   SHA256 {2}" -f (Get-Item "$nte\d3d12.dll").Length, (Get-Item "$nte\d3d12.dll").VersionInfo.FileVersion, $h)

Write-Host ''
Write-Host '=== STEP 2: ReShade 配置（中文界面 + 深度设置）==='
$ini = @"
[DEPTH]
DepthCopyBeforeClears=1
UseAspectRatioHeuristics=1

[GENERAL]
NoDebugInfo=1
NoEffectCache=0
PerformanceMode=0

[INPUT]
GamepadNavigation=0
KeyOverlay=36,0,0,0

[OVERLAY]
Language=zh-CN
ShowFPS=2
TutorialProgress=4
"@
[IO.File]::WriteAllText("$nte\ReShade.ini", $ini, (New-Object Text.UTF8Encoding $false))
[IO.File]::WriteAllText("$nte\ReShadePreset.ini", '', (New-Object Text.UTF8Encoding $false))
Write-Host '  ReShade.ini 写好（KeyOverlay=Home，界面中文）'
Get-Content "$nte\ReShade.ini" | ForEach-Object { Write-Host ("      " + $_) }

Write-Host ''
Write-Host '=== STEP 3: 神经渲染组件 ==='
$plan = @(
    @{ n='deep-fried-chicken.addon64';    why='神经消费者（Deep Fried Chicken 2.0.0）' },
    @{ n='deep-fried-chicken-nvngx.dll';  why='它的 NGX 垫片' },
    @{ n='deep-fried-chicken.cfg';        why='配置：arm=1 hook_mode=3(AUTO) enabled=1' },
    @{ n='nvngx_dlssnr.dll';              why='DLSS 5 神经渲染运行时（feature 18），v310.8.0.0' }
)
foreach ($p in $plan) {
    if (-not (Test-Path "$src\$($p.n)")) { Write-Host ("  缺源文件: " + $p.n); continue }
    Copy-Item "$src\$($p.n)" "$nte\$($p.n)" -Force
    Write-Host ("  {0,-30} {1,12:N0} B   {2}" -f $p.n, (Get-Item "$nte\$($p.n)").Length, $p.why)
}

Write-Host ''
Write-Host '=== STEP 4: 故意没有装的东西，以及理由 ==='
Write-Host '  nvngx_dlss.dll      : 不动 —— 异环自带 310.5，和我们的 310.8 同代，碰它只会多一个变量'
Write-Host '  nvngx_dlssg.dll     : 不动 —— 同理（自带 310.5.2）'
Write-Host '  dlss5-feed.addon64  : 不装 —— 异环自己做 DLSS 调用，feeder 是给没有 DLSS 的游戏造契约的'
Write-Host '  layer-x64 / VK layer: 不装 —— 这是 Direct3D 12 游戏，不是 Vulkan'
Write-Host '  reshade-shaders     : 不装 —— DFC 是 add-on 不是 .fx，这条路径不需要着色器'

Write-Host ''
Write-Host '=== STEP 5: 校验（哈希 + 导出符号）==='
foreach ($p in @(
    @($src.'ToString'(),'') )) { }
$check = @('d3d12.dll','deep-fried-chicken.addon64','nvngx_dlssnr.dll')
Add-Type -Namespace P9 -Name N -MemberDefinition @'
[DllImport("kernel32", SetLastError=true, CharSet=CharSet.Unicode, EntryPoint="LoadLibraryExW")]
public static extern IntPtr L(string f, IntPtr h, uint fl);
[DllImport("kernel32", CharSet=CharSet.Ansi, ExactSpelling=true, EntryPoint="GetProcAddress")]
public static extern IntPtr G(IntPtr h, string n);
[DllImport("kernel32", EntryPoint="FreeLibrary")] public static extern bool F(IntPtr h);
'@
foreach ($n in $check) {
    $f = "$nte\$n"
    $hh = [P9.N]::L($f, [IntPtr]::Zero, 0x1)
    $ex = @()
    if ($hh -ne [IntPtr]::Zero) {
        foreach ($s in @('ReShadeVersion','ReShadeRegisterAddon','ReShadeUnregisterAddon','AddonInit','AddonUninit','NVSDK_NGX_D3D12_Init_Ext')) {
            if ([P9.N]::G($hh,$s) -ne [IntPtr]::Zero) { $ex += $s }
        }
        [void][P9.N]::F($hh)
    }
    Write-Host ("  {0,-30} 导出: {1}" -f $n, $(if ($ex) { $ex -join ', ' } else { '(未探测到)' }))
}

Write-Host ''
Write-Host '=== 装完的清单 ==='
Get-ChildItem $nte -File | Where-Object { $_.Name -match '^(d3d12\.dll|ReShade|deep-fried|nvngx_dlssnr)' } |
    Sort-Object Name | ForEach-Object { Write-Host ("  {0,-32} {1,12:N0} B" -f $_.Name, $_.Length) }
$sum = 0
foreach ($n in $willAdd) { if (Test-Path "$nte\$n") { $sum += (Get-Item "$nte\$n").Length } }
Write-Host ("  合计 {0:N0} MB" -f ($sum / 1MB))

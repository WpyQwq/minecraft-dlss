$ErrorActionPreference = 'Continue'

$g   = 'D:\SteamLibrary\steamapps\common\ForzaHorizon5'
$src = 'H:\javahome\bin'
$bak = 'E:\deepseek\game-backups\ForzaHorizon5'

Write-Host '=== STEP 0: backups (outside the game folder, so the game never sees them) ==='
New-Item -ItemType Directory -Force -Path $bak | Out-Null
foreach ($n in @('nvngx_dlss.dll', 'nvngx_dlssg.dll', 'dxgi.dll', 'ReShade.ini', 'ReShade.log', 'ReShadePreset.ini')) {
    if (Test-Path "$g\$n") {
        Copy-Item "$g\$n" "$bak\$n.orig" -Force
        $h = (Get-FileHash "$g\$n" -Algorithm SHA256).Hash
        Write-Host ("  {0,-22} {1,12} B  {2}" -f $n, (Get-Item "$g\$n").Length, $h)
    }
}
Write-Host ("  backups -> " + $bak)

Write-Host ''
Write-Host '=== STEP 1: record the before-state of the DLSS runtime ==='
Write-Host ("  FH5 nvngx_dlss.dll   : {0}  {1}  (version {2})" -f (Get-Item "$g\nvngx_dlss.dll").Length,
    (Get-FileHash "$g\nvngx_dlss.dll" -Algorithm SHA256).Hash.Substring(0,16),
    (Get-Item "$g\nvngx_dlss.dll").VersionInfo.FileVersion)
Write-Host ("  our  nvngx_dlss.dll  : {0}  {1}  (DLSS 5 era)" -f (Get-Item "$src\nvngx_dlss.dll").Length,
    (Get-FileHash "$src\nvngx_dlss.dll" -Algorithm SHA256).Hash.Substring(0,16))

Write-Host ''
Write-Host '=== STEP 2: install the neural-rendering stack into the game folder ==='
$plan = @(
    @{ n = 'deep-fried-chicken.addon64';  why = 'neural consumer (the add-on ReShade loads)' },
    @{ n = 'deep-fried-chicken-nvngx.dll'; why = 'its NGX shim' },
    @{ n = 'deep-fried-chicken.cfg';       why = 'its config (arm=1 hook_mode=3 enabled=1)' },
    @{ n = 'nvngx_dlssnr.dll';             why = 'DLSS neural-rendering runtime (feature 18)' },
    @{ n = 'nvngx_dlss.dll';               why = 'upgrade 3.1.11 -> 310.9.1 (DLSS 5 era SR runtime)' }
)
foreach ($p in $plan) {
    if (-not (Test-Path "$src\$($p.n)")) { Write-Host ("  MISSING SOURCE: " + $p.n); continue }
    Copy-Item "$src\$($p.n)" "$g\$($p.n)" -Force
    Write-Host ("  {0,-28} {1,12} B   <- {2}" -f $p.n, (Get-Item "$g\$($p.n)").Length, $p.why)
}

Write-Host ''
Write-Host '=== STEP 3: deliberately NOT installed, and why ==='
Write-Host '  dlss5-feed.addon64  : NOT copied -- FH5 already makes its own DLSS calls via Streamline.'
Write-Host '                        The feeder builds a synthetic contract for games that have none;'
Write-Host '                        two DLSS features in one frame is exactly what we do not want.'
Write-Host '  layer-x64 / VK layer: NOT copied -- this is a Direct3D 12 game, not Vulkan.'
Write-Host '  reshade-shaders     : NOT copied -- DFC is an add-on, not an .fx effect. No preset needed.'

Write-Host ''
Write-Host '=== STEP 4: verify what ReShade will find in that folder ==='
Add-Type -Namespace P3 -Name N -MemberDefinition @'
[DllImport("kernel32", SetLastError=true, CharSet=CharSet.Unicode, EntryPoint="LoadLibraryExW")]
public static extern IntPtr L(string f, IntPtr h, uint fl);
[DllImport("kernel32", CharSet=CharSet.Ansi, ExactSpelling=true, EntryPoint="GetProcAddress")]
public static extern IntPtr G(IntPtr h, string n);
[DllImport("kernel32", EntryPoint="FreeLibrary")] public static extern bool F(IntPtr h);
'@
foreach ($n in @('dxgi.dll','nvngx_dlss.dll','nvngx_dlssnr.dll','deep-fried-chicken.addon64')) {
    $f = "$g\$n"
    if (-not (Test-Path $f)) { Write-Host ("  {0,-30} MISSING" -f $n); continue }
    $h = [P3.N]::L($f, [IntPtr]::Zero, 0x1)
    $ex = @()
    if ($h -ne [IntPtr]::Zero) {
        foreach ($s in @('ReShadeVersion','ReShadeRegisterAddon','NVSDK_NGX_D3D12_Init','NVSDK_NGX_D3D12_Init_Ext','ReShadeAddon')) {
            if ([P3.N]::G($h,$s) -ne [IntPtr]::Zero) { $ex += $s }
        }
        [void][P3.N]::F($h)
    }
    Write-Host ("  {0,-30} {1,12} B   exports: {2}" -f $n, (Get-Item $f).Length, $(if ($ex) { $ex -join ', ' } else { '(none probed)' }))
}

Write-Host ''
Write-Host '=== STEP 5: total space used in the game folder by our files ==='
$ours = @('deep-fried-chicken.addon64','deep-fried-chicken-nvngx.dll','deep-fried-chicken.cfg','nvngx_dlssnr.dll','nvngx_dlss.dll')
$sum = 0
foreach ($n in $ours) { if (Test-Path "$g\$n") { $sum += (Get-Item "$g\$n").Length } }
Write-Host ("  {0:N0} MB" -f ($sum / 1MB))

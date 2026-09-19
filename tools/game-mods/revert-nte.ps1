$ErrorActionPreference = 'Continue'

$nte = 'D:\Neverness To Everness\Client\WindowsNoEditor\HT\Binaries\Win64'

Write-Host '=== what this session put into the NTE client folder ==='
$ours = @('dxgi.dll','D3D12.dll','ReShade.ini','ReShade.log','ReShadePreset.ini',
          'deep-fried-chicken.addon64','deep-fried-chicken-nvngx.dll','deep-fried-chicken.cfg',
          'deep-fried-chicken.log','nvngx_dlssnr.dll')
foreach ($n in $ours) {
    $p = Join-Path $nte $n
    if (Test-Path $p) {
        Write-Host ("  PRESENT  {0,-30} {1,12} B  {2}" -f $n, (Get-Item $p).Length, (Get-Item $p).LastWriteTime.ToString('MM-dd HH:mm'))
    }
}
Write-Host '  (anything not listed above is the game''s own and is left untouched)'

Write-Host ''
Write-Host '=== revert: remove every file we added to NTE ==='
$removed = 0
foreach ($n in $ours) {
    $p = Join-Path $nte $n
    if (Test-Path $p) {
        Remove-Item $p -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path $p)) { Write-Host ("  removed " + $n); $removed++ }
        else { Write-Host ("  COULD NOT REMOVE " + $n) }
    }
}
Write-Host ("  {0} file(s) removed" -f $removed)

Write-Host ''
Write-Host '=== verify NTE is back to stock ==='
$left = Get-ChildItem $nte -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'deep-fried|nvngx_dlssnr|^dxgi\.dll$|^D3D12\.dll$|^ReShade' }
if ($left) { $left | ForEach-Object { Write-Host ("  LEFTOVER: " + $_.Name) } }
else { Write-Host '  clean - nothing of ours remains in that folder' }

Write-Host ''
Write-Host '=== is the game''s own DLSS plugin payload untouched? ==='
Get-ChildItem 'D:\Neverness To Everness\Client\WindowsNoEditor\Engine\Plugins\Runtime\Nvidia' -Recurse -File -Filter '*.dll' -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host ("  {0,12}  v{1,-14} {2}" -f $_.Length, $_.VersionInfo.FileVersion, $_.Name) }

Write-Host ''
Write-Host '=== evidence of the anti-cheat being active ==='
Get-Service 'AntiCheatExpert Service' -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ("  service: {0} / {1}" -f $_.Status, $_.StartType) }
Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match 'ACE|AntiCheat|HTGame|NTELauncher' } |
    ForEach-Object { Write-Host ("  running: {0} (PID {1})" -f $_.ProcessName, $_.Id) }
Write-Host '  --- crash artifacts in the client folder (last 3 days) ---'
Get-ChildItem 'D:\Neverness To Everness' -Recurse -Depth 3 -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in @('.dmp','.log','.txt') -and $_.LastWriteTime -gt (Get-Date).AddDays(-3) -and $_.Length -gt 0 } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 12 |
    ForEach-Object { Write-Host ("  {0}  {1,10} B  {2}" -f $_.LastWriteTime.ToString('MM-dd HH:mm'), $_.Length, $_.FullName.Replace('D:\Neverness To Everness\','')) }

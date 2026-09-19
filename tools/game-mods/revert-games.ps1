# Reverts everything this session added to Forza Horizon 5 and Neverness to Everness.
# Nothing of either game's own files is deleted except files we created.
# Run:  powershell -ExecutionPolicy Bypass -File .\revert-games.ps1

$ErrorActionPreference = 'Continue'
$fh5 = 'D:\SteamLibrary\steamapps\common\ForzaHorizon5'
$nte = 'D:\Neverness To Everness\Client\WindowsNoEditor\HT\Binaries\Win64'
$bak = 'E:\deepseek\game-backups'

Write-Host '=== Forza Horizon 5 ==='
# Remove what we added
foreach ($n in @('deep-fried-chicken.addon64','deep-fried-chicken-nvngx.dll','deep-fried-chicken.cfg',
                 'nvngx_dlssnr.dll','deep-fried-chicken.log','ReShade.log')) {
    if (Test-Path "$fh5\$n") { Remove-Item "$fh5\$n" -Force; Write-Host ("  removed  " + $n) }
}
# Restore the runtime we replaced and the config/log the installer had written
foreach ($n in @('nvngx_dlss.dll','ReShade.ini','ReShadePreset.ini','ReShade.log')) {
    if (Test-Path "$bak\ForzaHorizon5\$n.orig") {
        Copy-Item "$bak\ForzaHorizon5\$n.orig" "$fh5\$n" -Force
        Write-Host ("  restored " + $n)
    }
}
# dxgi.dll was there before us (ReShade 6.8.0.2158). Restore the byte-identical original.
if (Test-Path "$bak\ForzaHorizon5\dxgi.dll.orig") {
    Copy-Item "$bak\ForzaHorizon5\dxgi.dll.orig" "$fh5\dxgi.dll" -Force
    Write-Host '  restored dxgi.dll'
}
Write-Host '  -> back to stock (ReShade proxy kept, since it was already installed before this session)'

Write-Host ''
Write-Host '=== Neverness to Everness ==='
foreach ($n in @('dxgi.dll','ReShade.ini','ReShade.log','ReShadePreset.ini',
                 'deep-fried-chicken.addon64','deep-fried-chicken-nvngx.dll','deep-fried-chicken.cfg',
                 'deep-fried-chicken.log','nvngx_dlssnr.dll')) {
    if (Test-Path "$nte\$n") { Remove-Item "$nte\$n" -Force; Write-Host ("  removed  " + $n) }
}
Write-Host '  -> back to stock (we never overwrote any of the game'"'"'s own files there)'

Write-Host ''
Write-Host '=== verify ==='
Write-Host ('  FH5 nvngx_dlss.dll : ' + (Get-Item "$fh5\nvngx_dlss.dll").VersionInfo.FileVersion)
Write-Host ('  FH5 leftovers      : ' + (@(Get-ChildItem $fh5 -File | Where-Object { $_.Name -match 'deep-fried|nvngx_dlssnr' }).Count) + ' file(s)')
Write-Host ('  NTE leftovers      : ' + (@(Get-ChildItem $nte -File | Where-Object { $_.Name -match 'deep-fried|nvngx_dlssnr|^dxgi\.dll$|^ReShade' }).Count) + ' file(s)')

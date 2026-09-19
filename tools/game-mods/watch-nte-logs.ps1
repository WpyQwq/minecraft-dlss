$ErrorActionPreference = 'Continue'

# Watches the Ananta client folder for ReShade / DFC logs and archives every new version
# immediately. Written because last time the only two logs that could explain a crash were
# deleted before anyone read them.

$nte = 'D:\Neverness To Everness\Client\WindowsNoEditor\HT\Binaries\Win64'
$arc = 'E:\deepseek\game-backups\NevernessToEverness\logs-live'
New-Item -ItemType Directory -Force -Path $arc | Out-Null

$seen = @{}
$deadline = (Get-Date).AddHours(3)

Write-Output ("watcher started " + (Get-Date -Format 'HH:mm:ss') + "  watching: " + $nte)
Write-Output ("archive: " + $arc)

while ((Get-Date) -lt $deadline) {
    foreach ($n in @('ReShade.log', 'deep-fried-chicken.log', 'ReShade.ini')) {
        $p = Join-Path $nte $n
        if (-not (Test-Path $p)) { continue }
        $fi = Get-Item $p
        $key = $n + '|' + $fi.LastWriteTimeUtc.Ticks + '|' + $fi.Length
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        $stamp = (Get-Date).ToString('HHmmss')
        $dst = Join-Path $arc ($stamp + '__' + $n)
        try {
            Copy-Item $p $dst -Force -ErrorAction Stop
            Write-Output ("ARCHIVED {0}  {1,10} B  -> {2}" -f (Get-Date -Format 'HH:mm:ss'), $fi.Length, (Split-Path $dst -Leaf))
        } catch {
            Write-Output ("COPY FAILED " + $n + " : " + $_.Exception.Message)
        }
    }
    Start-Sleep -Seconds 3
}
Write-Output "watcher window ended (3 hours)"

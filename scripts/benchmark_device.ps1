param(
    [string]$DeviceId = "",
    [string]$PackageName = "com.star.launcher.debug",
    [int]$ColdStartRuns = 3,
    [int]$SettleSeconds = 4,
    [string]$AdbPath = $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } elseif ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" } else { "adb" })
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $AdbPath)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $AdbPath = $cmd.Source }
    else { throw "adb.exe not found at $AdbPath or in PATH" }
}

function Invoke-Adb {
    param([string[]]$AdbArgs)
    if ($DeviceId) {
        & $AdbPath -s $DeviceId @AdbArgs
    } else {
        & $AdbPath @AdbArgs
    }
}

$devices = & $AdbPath devices
Write-Host "=== CONNECTED DEVICES ==="
$devices | Out-Host

$launcherComp = (Invoke-Adb @("shell", "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PackageName | tail -n 1") | Out-String).Trim()
if (-not $launcherComp -or $launcherComp -match "No activity") {
    throw "Could not resolve launcher activity for $PackageName"
}
Write-Host "Resolved Activity: $launcherComp"

$coldTotalTimes = @()
$coldWaitTimes = @()

Write-Host ""
Write-Host "=== COLD START BENCHMARK ($ColdStartRuns runs) ==="
for ($i = 1; $i -le $ColdStartRuns; $i++) {
    Invoke-Adb @("shell", "am force-stop $PackageName") | Out-Null
    Invoke-Adb @("logcat", "-c") | Out-Null
    Start-Sleep -Seconds 1
    $launchOut = Invoke-Adb @("shell", "am start -W $launcherComp")
    $launchOut | Out-Host
    $launchText = ($launchOut | Out-String)
    if ($launchText -match "TotalTime:\s*(\d+)") { $coldTotalTimes += [double]$Matches[1] }
    if ($launchText -match "WaitTime:\s*(\d+)") { $coldWaitTimes += [double]$Matches[1] }
    Start-Sleep -Seconds $SettleSeconds
}

if ($coldTotalTimes.Count -gt 0) {
    $avgTotal = ($coldTotalTimes | Measure-Object -Average).Average
    $avgWait = ($coldWaitTimes | Measure-Object -Average).Average
    Write-Host ("Cold Start TotalTime (ms): [{0}] -> Avg {1:N1} ms" -f ($coldTotalTimes -join ", "), $avgTotal)
    Write-Host ("Cold Start WaitTime  (ms): [{0}] -> Avg {1:N1} ms" -f ($coldWaitTimes -join ", "), $avgWait)
}

Write-Host ""
Write-Host "=== [STAR-PROFILE] LOGCAT STAGES ==="
$profileLogs = Invoke-Adb @("logcat", "-d", "-s", "STAR-PROFILE:I", "CrashReportCoordinator:W", "AndroidRuntime:E")
$profileLogs | Select-Object -Last 60 | Out-Host

Write-Host ""
Write-Host "=== MEMORY FOOTPRINT (${PackageName}:launcher) ==="
$memInfo = Invoke-Adb @("shell", "dumpsys meminfo ${PackageName}:launcher")
$memInfo | Select-String -Pattern "MEMINFO|TOTAL PSS|TOTAL RSS|Java Heap:|Native Heap:|Graphics:|Code:|Stack:|Views:|Activities:" | Out-Host

Write-Host ""
Write-Host "=== UI FRAME PACING & JANK (${PackageName}:launcher) ==="
# Perform light scroll/touch input so UI renders real frames before gfxinfo dump
Invoke-Adb @("shell", "input swipe 500 1600 500 800 250") | Out-Null
Start-Sleep -Milliseconds 600
Invoke-Adb @("shell", "input swipe 500 800 500 1600 250") | Out-Null
Start-Sleep -Seconds 1

$gfx = Invoke-Adb @("shell", "dumpsys gfxinfo ${PackageName}:launcher framestats")
$gfx | Select-String -Pattern "Total frames|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI|Pipeline=" | Out-Host

# Compute true Avg FPS & 1% Low FPS from PROFILEDATA using header column names
$frameDurationsMs = @()
$inProfile = $false
$flagsIdx = 0
$intendedIdx = -1
$completedIdx = -1
foreach ($line in $gfx) {
    if ($line -match "---PROFILEDATA---") {
        $inProfile = -not $inProfile
        continue
    }
    if ($inProfile -and $line -match "^Flags,") {
        $headers = $line.Split(",")
        $flagsIdx = [Array]::IndexOf($headers, "Flags")
        $intendedIdx = [Array]::IndexOf($headers, "IntendedVsync")
        $completedIdx = [Array]::IndexOf($headers, "FrameCompleted")
        continue
    }
    if ($inProfile -and $line -match "^\d+," -and $intendedIdx -ge 0 -and $completedIdx -ge 0) {
        $cols = $line.Split(",")
        $maxIdx = [Math]::Max($flagsIdx, [Math]::Max($intendedIdx, $completedIdx))
        if ($cols.Count -gt $maxIdx) {
            $flags = [long]$cols[$flagsIdx]
            $intended = [long]$cols[$intendedIdx]
            $completed = [long]$cols[$completedIdx]
            if ($flags -eq 0 -and $completed -gt $intended -and ($completed - $intended) -lt 2000000000) {
                $frameDurationsMs += (($completed - $intended) / 1000000.0)
            }
        }
    }
}

if ($frameDurationsMs.Count -gt 0) {
    $sorted = $frameDurationsMs | Sort-Object
    $avgFt = ($sorted | Measure-Object -Average).Average
    $p95Idx = [Math]::Floor(($sorted.Count - 1) * 0.95)
    $onePctCount = [Math]::Max(1, [int][Math]::Ceiling($sorted.Count * 0.01))
    $slowest = $sorted | Select-Object -Last $onePctCount
    $onePctFt = ($slowest | Measure-Object -Average).Average
    $avgFps = 1000.0 / $avgFt
    $onePctFps = 1000.0 / $onePctFt
    Write-Host ("Framestats Samples: {0} | Avg Frame: {1:N2} ms ({2:N1} FPS equiv) | P95: {3:N2} ms | 1% Slowest Frame: {4:N2} ms ({5:N1} FPS equiv)" -f $sorted.Count, $avgFt, $avgFps, $sorted[$p95Idx], $onePctFt, $onePctFps)
} else {
    Write-Host "No active UI framestats profile samples captured in window (screen may be static)."
}

Write-Host ""
Write-Host "=== GPU & THERMAL SENSORS ==="
$sensorScript = @'
echo -n 'KGSL busy %: '; cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null || echo 'N/A'; for z in /sys/class/thermal/thermal_zone*; do t=$(cat "$z/type" 2>/dev/null); v=$(cat "$z/temp" 2>/dev/null); if [ -n "$v" ] && [ "$v" -gt 10000 ] 2>/dev/null; then echo "${t}: ${v}"; fi; done | head -n 12
'@
Invoke-Adb @("shell", $sensorScript) | Out-Host

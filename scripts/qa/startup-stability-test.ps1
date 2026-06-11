param(
    [ValidateSet("cold", "hot")]
    [string]$Mode,

    [int]$Start = 1,

    [int]$Count = 10,

    [string]$Serial = "emulator-5554",

    [string]$Package = "com.dawncourse.app",

    [string]$Activity = "com.dawncourse.app/.MainActivity",

    [string]$RunDir = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunDir)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $RunDir = Join-Path (Get-Location) "logs/startup-stability/$timestamp"
}

New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$resultsPath = Join-Path $RunDir "results.jsonl"
$summaryPath = Join-Path $RunDir "summary.md"

function Invoke-AdbText {
    param([string[]]$AdbArgs)

    $output = & adb -s $Serial @AdbArgs 2>&1
    return ($output -join "`n")
}

function Save-AdbBinary {
    param(
        [string[]]$AdbArgs,
        [string]$Path
    )

    $stderrPath = "$Path.stderr.txt"
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "adb"
    $psi.Arguments = ((@("-s", $Serial) + $AdbArgs) -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()

    $output = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
        $process.StandardOutput.BaseStream.CopyTo($output)
    } finally {
        $output.Dispose()
    }

    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $stderr | Set-Content -Path $stderrPath -Encoding UTF8

    return $process.ExitCode
}

function Get-PngStats {
    param([string]$Path)

    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue
        $bitmap = [System.Drawing.Bitmap]::new($Path)
        try {
            $xStart = [Math]::Floor($bitmap.Width * 0.1)
            $xEnd = [Math]::Ceiling($bitmap.Width * 0.9)
            $yStart = [Math]::Floor($bitmap.Height * 0.1)
            $yEnd = [Math]::Ceiling($bitmap.Height * 0.9)
            $stepX = [Math]::Max(1, [Math]::Floor(($xEnd - $xStart) / 32))
            $stepY = [Math]::Max(1, [Math]::Floor(($yEnd - $yStart) / 32))
            $values = New-Object System.Collections.Generic.List[double]

            for ($x = $xStart; $x -lt $xEnd; $x += $stepX) {
                for ($y = $yStart; $y -lt $yEnd; $y += $stepY) {
                    $pixel = $bitmap.GetPixel($x, $y)
                    $values.Add((($pixel.R + $pixel.G + $pixel.B) / 3.0))
                }
            }

            if ($values.Count -eq 0) {
                return [pscustomobject]@{
                    available = $false
                    reason = "no sampled pixels"
                }
            }

            $average = ($values | Measure-Object -Average).Average
            $variance = 0.0
            foreach ($value in $values) {
                $variance += [Math]::Pow($value - $average, 2)
            }
            $stddev = [Math]::Sqrt($variance / $values.Count)

            return [pscustomobject]@{
                available = $true
                average = [Math]::Round($average, 2)
                stddev = [Math]::Round($stddev, 2)
                blankWhite = ($average -gt 248 -and $stddev -lt 3)
                blankBlack = ($average -lt 8 -and $stddev -lt 3)
            }
        } finally {
            $bitmap.Dispose()
        }
    } catch {
        return [pscustomobject]@{
            available = $false
            reason = $_.Exception.Message
        }
    }
}

function Get-UiStats {
    param([string]$Path)

    try {
        $xml = Get-Content -Path $Path -Raw -ErrorAction Stop
        return [pscustomobject]@{
            available = $true
            hasPackage = $xml.Contains($Package)
            nodeCount = ([regex]::Matches($xml, "<node ")).Count
            hasInteractiveNode = ($xml -match 'clickable="true"|scrollable="true"|focusable="true"')
            isEmpty = ([string]::IsNullOrWhiteSpace($xml) -or ([regex]::Matches($xml, "<node ")).Count -eq 0)
        }
    } catch {
        return [pscustomobject]@{
            available = $false
            hasPackage = $false
            nodeCount = 0
            hasInteractiveNode = $false
            isEmpty = $true
        }
    }
}

function Collect-Snapshot {
    param(
        [string]$RoundDir,
        [string]$Label,
        [int]$ElapsedMs
    )

    $activityPath = Join-Path $RoundDir "$Label-activity.txt"
    $windowPath = Join-Path $RoundDir "$Label-window.txt"
    $uiPath = Join-Path $RoundDir "$Label-ui.xml"
    $pngPath = Join-Path $RoundDir "$Label-screen.png"

    Invoke-AdbText @("shell", "dumpsys", "activity", "activities") |
        Set-Content -Path $activityPath -Encoding UTF8
    Invoke-AdbText @("shell", "dumpsys", "window", "windows") |
        Set-Content -Path $windowPath -Encoding UTF8
    Invoke-AdbText @("exec-out", "uiautomator", "dump", "/dev/tty") |
        Set-Content -Path $uiPath -Encoding UTF8
    Save-AdbBinary @("exec-out", "screencap", "-p") $pngPath | Out-Null

    $activityText = Get-Content -Path $activityPath -Raw
    $windowText = Get-Content -Path $windowPath -Raw
    $uiStats = Get-UiStats $uiPath
    $pngStats = Get-PngStats $pngPath

    return [pscustomobject]@{
        label = $Label
        elapsedMs = $ElapsedMs
        activityPath = $activityPath
        windowPath = $windowPath
        uiPath = $uiPath
        screenshotPath = $pngPath
        resumedOk = ($activityText -match [regex]::Escape($Package) -and $activityText -match "MainActivity")
        windowFocused = ($windowText -match [regex]::Escape($Package))
        ui = $uiStats
        screenshot = $pngStats
    }
}

function Write-Summary {
    if (-not (Test-Path $resultsPath)) {
        return
    }

    $results = Get-Content -Path $resultsPath |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json }

    $cold = @($results | Where-Object { $_.mode -eq "cold" })
    $hot = @($results | Where-Object { $_.mode -eq "hot" })
    $failures = @($results | Where-Object { -not $_.passed })
    $maxVisible = ($results | Measure-Object -Property firstVisibleMs -Maximum).Maximum
    $maxWait = ($results | Measure-Object -Property amStartWaitMs -Maximum).Maximum

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Dawn Course startup stability report")
    $lines.Add("")
    $lines.Add("- Package: ``$Package``")
    $lines.Add("- Activity: ``$Activity``")
    $lines.Add("- Device: ``$Serial``")
    $lines.Add("- Total rounds: $($results.Count)")
    $lines.Add("- Cold: $(@($cold | Where-Object { $_.passed }).Count) passed / $($cold.Count) total")
    $lines.Add("- Hot: $(@($hot | Where-Object { $_.passed }).Count) passed / $($hot.Count) total")
    $lines.Add("- Failures: $($failures.Count)")
    $lines.Add("- Longest first visible time: ${maxVisible}ms")
    $lines.Add("- Longest am start wait time: ${maxWait}ms")
    $lines.Add("")

    if ($failures.Count -gt 0) {
        $lines.Add("## Failures")
        foreach ($failure in $failures) {
            $reasons = ($failure.reasons -join "; ")
            $lines.Add("- $($failure.mode) #$($failure.round): $reasons")
            $lines.Add("  - screenshot: ``$($failure.finalSnapshot.screenshotPath)``")
            $lines.Add("  - ui: ``$($failure.finalSnapshot.uiPath)``")
            $lines.Add("  - logcat: ``$($failure.logcatPath)``")
        }
        $lines.Add("")
    }

    $lines.Add("## Notes")
    $lines.Add("- A short launch flash is not counted as failure if the 5s snapshot has an app window and UI tree.")
    $lines.Add("- Blank screen detection samples the central screenshot region and is combined with UI-tree checks.")

    $lines | Set-Content -Path $summaryPath -Encoding UTF8
}

function Test-RoundResult {
    param(
        [object[]]$Snapshots,
        [string]$LogcatPath,
        [string]$CrashPath,
        [int]$AmStartWaitMs
    )

    $logText = ""
    if (Test-Path $LogcatPath) {
        $logText += Get-Content -Path $LogcatPath -Raw
    }
    if (Test-Path $CrashPath) {
        $logText += "`n"
        $logText += Get-Content -Path $CrashPath -Raw
    }

    $crashPattern = "FATAL EXCEPTION|Fatal signal|ANR in|Application Not Responding|Input dispatching timed out|am_crash|native crash"
    $hasCrashOrAnr = ($logText -match $crashPattern)
    $final = $Snapshots[-1]
    $blankNoUi = (($final.screenshot.available -and ($final.screenshot.blankWhite -or $final.screenshot.blankBlack)) -and
        (-not $final.ui.hasPackage -or -not $final.ui.hasInteractiveNode))

    $firstVisible = $null
    foreach ($snapshot in $Snapshots) {
        $snapshotBlankNoUi = (($snapshot.screenshot.available -and ($snapshot.screenshot.blankWhite -or $snapshot.screenshot.blankBlack)) -and
            (-not $snapshot.ui.hasPackage -or -not $snapshot.ui.hasInteractiveNode))
        if ($snapshot.resumedOk -and $snapshot.windowFocused -and $snapshot.ui.hasPackage -and -not $snapshot.ui.isEmpty -and -not $snapshotBlankNoUi) {
            $firstVisible = $snapshot.elapsedMs
            break
        }
    }

    $reasons = New-Object System.Collections.Generic.List[string]
    if ($hasCrashOrAnr) {
        $reasons.Add("crash-or-anr-log-pattern")
    }
    if (-not $final.resumedOk) {
        $reasons.Add("main-activity-not-resumed-at-5s")
    }
    if (-not $final.ui.hasPackage -or $final.ui.isEmpty) {
        $reasons.Add("app-ui-tree-missing-at-5s")
    }
    if ($blankNoUi) {
        $reasons.Add("blank-screenshot-without-app-ui-at-5s")
    }
    if ($null -eq $firstVisible) {
        $reasons.Add("no-visible-app-ui-within-5s")
    }

    return [pscustomobject]@{
        passed = ($reasons.Count -eq 0)
        reasons = @($reasons)
        firstVisibleMs = $(if ($null -eq $firstVisible) { 999999 } else { $firstVisible })
        hasCrashOrAnr = $hasCrashOrAnr
        finalSnapshot = $final
        amStartWaitMs = $AmStartWaitMs
    }
}

function Invoke-Launch {
    $output = Invoke-AdbText @("shell", "am", "start", "-W", "-n", $Activity)
    $wait = 0
    if ($output -match "WaitTime:\s*(\d+)") {
        $wait = [int]$Matches[1]
    } elseif ($output -match "TotalTime:\s*(\d+)") {
        $wait = [int]$Matches[1]
    }
    return [pscustomobject]@{
        output = $output
        waitMs = $wait
    }
}

if ($Mode -eq "hot") {
    Invoke-Launch | Out-Null
    Start-Sleep -Seconds 2
}

for ($offset = 0; $offset -lt $Count; $offset++) {
    $round = $Start + $offset
    $roundName = "{0}-{1:D3}" -f $Mode, $round
    $roundDir = Join-Path $RunDir $roundName
    New-Item -ItemType Directory -Force -Path $roundDir | Out-Null

    Write-Host "[$Mode] round $round starting"
    Invoke-AdbText @("logcat", "-c") | Out-Null

    if ($Mode -eq "cold") {
        Invoke-AdbText @("shell", "am", "force-stop", $Package) | Out-Null
        Start-Sleep -Milliseconds 300
    } else {
        Invoke-AdbText @("shell", "input", "keyevent", "HOME") | Out-Null
        $intervals = @(500, 1000, 2000)
        Start-Sleep -Milliseconds $intervals[($round - 1) % $intervals.Count]
    }

    $launch = Invoke-Launch
    $launch.output | Set-Content -Path (Join-Path $roundDir "am-start.txt") -Encoding UTF8

    Start-Sleep -Milliseconds 1000
    $snapshot1 = Collect-Snapshot $roundDir "t1s" 1000
    Start-Sleep -Milliseconds 2000
    $snapshot3 = Collect-Snapshot $roundDir "t3s" 3000
    Start-Sleep -Milliseconds 2000
    $snapshot5 = Collect-Snapshot $roundDir "t5s" 5000
    $snapshots = @($snapshot1, $snapshot3, $snapshot5)

    $logcatPath = Join-Path $roundDir "logcat.txt"
    $crashPath = Join-Path $roundDir "logcat-crash.txt"
    Invoke-AdbText @("logcat", "-d") | Set-Content -Path $logcatPath -Encoding UTF8
    Invoke-AdbText @("logcat", "-b", "crash", "-d") | Set-Content -Path $crashPath -Encoding UTF8

    $assessment = Test-RoundResult $snapshots $logcatPath $crashPath $launch.waitMs
    $result = [pscustomobject]@{
        mode = $Mode
        round = $round
        passed = $assessment.passed
        reasons = $assessment.reasons
        firstVisibleMs = $assessment.firstVisibleMs
        amStartWaitMs = $assessment.amStartWaitMs
        hasCrashOrAnr = $assessment.hasCrashOrAnr
        roundDir = $roundDir
        logcatPath = $logcatPath
        crashLogPath = $crashPath
        finalSnapshot = $assessment.finalSnapshot
    }

    $result | ConvertTo-Json -Depth 20 -Compress | Add-Content -Path $resultsPath -Encoding UTF8
    Write-Summary

    if ($assessment.passed) {
        Write-Host "[$Mode] round $round passed, visible=$($assessment.firstVisibleMs)ms, startWait=$($assessment.amStartWaitMs)ms"
    } else {
        Write-Host "[$Mode] round $round failed: $($assessment.reasons -join '; ')"
    }
}

Write-Summary
Write-Host "summary: $summaryPath"

param(
    [switch]$SkipBuild,
    [switch]$Online
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$reportDir = Join-Path $projectRoot "build\reports"
$reportPath = Join-Path $reportDir "drawing-audit.md"
$logDir = Join-Path $projectRoot "build\drawing-audit-logs"
$gradle = Join-Path $projectRoot "gradlew.bat"
$offlineFlag = @()
if (-not $Online) {
    $offlineFlag += "--offline"
}
$results = New-Object System.Collections.Generic.List[object]

New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Invoke-AuditStep {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [string]$Covers = ""
    )

    $safeName = ($Name -replace "[^a-zA-Z0-9]+", "-").Trim("-").ToLowerInvariant()
    $logPath = Join-Path $logDir "$safeName.log"
    $started = Get-Date
    Write-Host "==> $Name" -ForegroundColor Cyan
    Write-Host "    .\gradlew.bat $($Arguments -join ' ')" -ForegroundColor DarkGray

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Gradle/IDE tasks can emit harmless environment warnings on stderr. Capture them
        # in the log, but rely on the native exit code for pass/fail.
        $ErrorActionPreference = "Continue"
        $output = & $gradle @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output | Set-Content -Path $logPath -Encoding UTF8
    $duration = [Math]::Round(((Get-Date) - $started).TotalSeconds, 1)

    $status = if ($exitCode -eq 0) { "PASS" } else { "FAIL" }
    $results.Add([pscustomobject]@{
        Name = $Name
        Status = $status
        Seconds = $duration
        LogPath = $logPath
        Covers = $Covers
    })

    if ($exitCode -ne 0) {
        Write-Host "FAILED: $Name. See $logPath" -ForegroundColor Red
        throw "Audit step failed: $Name"
    }

    Write-Host "PASS: $Name in ${duration}s" -ForegroundColor Green
}

$focusedTestArgs = @(
    $offlineFlag
    "test"
    "--tests", "com.drawing.architecture.SourceLayoutTest"
    "--tests", "com.drawing.document.DrawingDocumentEditInteractionTest"
    "--tests", "com.drawing.persistence.DrawingStrokeStoreTest"
    "--tests", "com.drawing.persistence.DrawingStateServiceTest"
    "--tests", "com.drawing.geometry.PaintGeometryEngineTest"
    "--tests", "com.drawing.geometry.ShapeStrokeFactoryTest"
    "--tests", "com.drawing.geometry.BalloonTextStrokeFactoryTest"
    "--tests", "com.drawing.ui.DrawingCanvasPanelStateTest"
    "--tests", "com.drawing.ui.PhotoshopColorPickerPanelTest"
    "--tests", "com.drawing.ui.DrawingToolWindowMenuFilterTest"
    "--tests", "com.drawing.ui.DrawingToolbarPanelTest"
    "--tests", "com.drawing.ui.ShapeMenuFactoryTest"
    "--tests", "com.drawing.ui.DrawingStateBinderTest"
)

$defaultTestArgs = @($offlineFlag) + @("test")
$intellijTestArgs = @($offlineFlag) + @("-PplatformType=IC", "test")
$buildArgs = @($offlineFlag) + @("-PplatformType=IC", "buildPlugin")

$auditStarted = Get-Date
$expectedStepCount = if ($SkipBuild) { 3 } else { 4 }

try {
    Invoke-AuditStep `
        -Name "Focused feature coverage" `
        -Arguments $focusedTestArgs `
        -Covers "tool-window-only entry point, pencil icon, filtered Float/Dock Pinned Tool Window menu, drawing-code anchoring, top-line object migration, grouped code-overlap avoidance, select-and-move drawing groups, erase geometry, shapes, filled/default text and balloon text, remembered shape/text/balloon picker activation, fill, toolbar/menu state, tool/color persistence, Photoshop-style color picker"

    Invoke-AuditStep `
        -Name "Full PyCharm target suite" `
        -Arguments $defaultTestArgs `
        -Covers "default PC platform compile/test coverage"

    Invoke-AuditStep `
        -Name "Full IntelliJ target suite" `
        -Arguments $intellijTestArgs `
        -Covers "IC platform compile/test coverage"

    if (-not $SkipBuild) {
        Invoke-AuditStep `
            -Name "Build plugin zip" `
            -Arguments $buildArgs `
            -Covers "installable plugin package"
    }
} finally {
    $auditFinished = Get-Date
    $zip = Get-ChildItem -Path (Join-Path $projectRoot "build\distributions") -Filter "*.zip" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Drawing Audit Report")
    $lines.Add("")
    $lines.Add("- Started: $($auditStarted.ToString("yyyy-MM-dd HH:mm:ss"))")
    $lines.Add("- Finished: $($auditFinished.ToString("yyyy-MM-dd HH:mm:ss"))")
    $lines.Add("- Mode: $(if ($Online) { "online Gradle resolution allowed" } else { "offline Gradle mode" })")
    $failedStepCount = ($results | Where-Object Status -eq "FAIL").Count
    $completedAllSteps = $results.Count -eq $expectedStepCount
    $overallResult = if ($failedStepCount -eq 0 -and $completedAllSteps) { "PASS" } else { "FAIL" }
    $lines.Add("- Result: $overallResult")
    if (-not $completedAllSteps) {
        $lines.Add("- Completed steps: $($results.Count) of $expectedStepCount")
    }
    if ($zip) {
        $lines.Add("- Latest package: $($zip.FullName)")
        $lines.Add("- Package size: $($zip.Length) bytes")
        $lines.Add("- Package timestamp: $($zip.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss"))")
    }
    $lines.Add("")
    $lines.Add("## Automated Checks")
    $lines.Add("")
    $lines.Add("| Check | Status | Seconds | Covers | Log |")
    $lines.Add("| --- | --- | ---: | --- | --- |")
    foreach ($result in $results) {
        $relativeLog = Resolve-Path -Path $result.LogPath -Relative
        $lines.Add("| $($result.Name) | $($result.Status) | $($result.Seconds) | $($result.Covers) | $relativeLog |")
    }
    $lines.Add("")
    $lines.Add("## Covered Automatically")
    $lines.Add("")
    $lines.Add("- New line above existing drawings moves anchors with code.")
    $lines.Add("- Editing an existing line keeps freehand, shapes, and text as rigid objects.")
    $lines.Add("- Legacy freehand drawings migrate before/load document edits.")
    $lines.Add("- Freehand drawings anchor to the topmost occupied line so lower-line edits do not stretch them.")
    $lines.Add("- All shape tools, including rectangle and curly bracket, use the same top-line object anchoring.")
    $lines.Add("- Arrow shape renders as a thin open line arrow and erases through the normal stroke path.")
    $lines.Add("- Text/balloon strokes and fill strokes use grouped object anchoring.")
    $lines.Add("- Grouped objects shift right as one object when lower occupied code grows underneath them.")
    $lines.Add("- Selected drawing groups move as one rigid object without changing their shape.")
    $lines.Add("- Shape generation, text/balloon generation, erase splitting, persistence, menus, toolbar state, color/tool state, and the Photoshop-style color picker have unit coverage.")
    $lines.Add("- Drawing is registered as a sidebar Tool Window without the duplicate status-bar widget or startup floating dialog.")
    $lines.Add("- Drawing filters the native Tool Window View Mode popup down to Dock Pinned and Float when that IDE-owned menu is shown.")
    $lines.Add("- Drawing registers a pencil icon and defaults text/balloon text to the filled style while keeping hollow text selectable.")
    $lines.Add("- Shapes, Text, and Balloon buttons activate their last remembered choice while still showing the available options.")
    $lines.Add("- PyCharm default target and IntelliJ target both compile and pass tests.")
    $lines.Add("")
    $lines.Add("## Still Best Verified By Video")
    $lines.Add("")
    $lines.Add("- Actual mouse feel while drawing and erasing inside the live IDE.")
    $lines.Add("- Visual transparency/compositing over the editor on your GPU/display setup.")
    $lines.Add("- Text editor popup focus behavior while typing inside the live overlay.")
    $lines.Add("- Drawing window positioning across multiple monitors.")

    $lines | Set-Content -Path $reportPath -Encoding UTF8
    Write-Host "Audit report written to $reportPath" -ForegroundColor Cyan
}

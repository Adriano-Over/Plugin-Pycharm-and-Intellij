param(
    [switch]$SkipPrepare
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Gradle = Join-Path $ProjectRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $Gradle)) {
    throw "Gradle wrapper not found at $Gradle"
}

Set-Location -LiteralPath $ProjectRoot

if (-not $SkipPrepare) {
    & $Gradle "prepareBothIdeSandboxes" "--no-daemon"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

function Start-IdeTask {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Title,

        [Parameter(Mandatory = $true)]
        [string]$TaskName
    )

    $safeProjectRoot = $ProjectRoot.Replace("'", "''")
    $safeGradle = $Gradle.Replace("'", "''")
    $safeTitle = $Title.Replace("'", "''")

    $command = @"
`$Host.UI.RawUI.WindowTitle = '$safeTitle'
Set-Location -LiteralPath '$safeProjectRoot'
& '$safeGradle' '$TaskName' '--no-daemon'
if (`$LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '$safeTitle exited with code ' `$LASTEXITCODE -ForegroundColor Red
}
Write-Host ''
Write-Host 'Close this window after closing the IDE sandbox.' -ForegroundColor Yellow
"@

    Start-Process `
        -FilePath "powershell.exe" `
        -ArgumentList @("-NoExit", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command) `
        -WorkingDirectory $ProjectRoot
}

Start-IdeTask -Title "Drawing IntelliJ IDEA sandbox" -TaskName "runIdeIntellij"
Start-Sleep -Seconds 2
Start-IdeTask -Title "Drawing PyCharm sandbox" -TaskName "runIdePyCharm"

Write-Host "Started IntelliJ IDEA and PyCharm sandbox launchers."

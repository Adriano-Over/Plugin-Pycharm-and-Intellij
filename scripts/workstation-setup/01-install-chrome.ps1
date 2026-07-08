[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$RunId = Get-Date -Format 'yyyyMMddHHmmss'
$TempDir = Join-Path $env:TEMP "ChromeSetup_$RunId"
$LogDir = Join-Path $env:ProgramData 'WorkstationSetup\Logs'
$TranscriptPath = Join-Path $LogDir "01-install-chrome_$RunId.txt"
$InstallerUrl = 'https://dl.google.com/chrome/install/GoogleChromeStandaloneEnterprise64.msi'
$InstallerPath = Join-Path $TempDir 'GoogleChromeStandaloneEnterprise64.msi'
$InstallLog = Join-Path $TempDir 'ChromeInstall.log'

function Write-Log {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [ValidateSet('INFO', 'SUCCESS', 'WARN', 'ERROR')][string]$Level = 'INFO'
    )

    $Color = switch ($Level) {
        'INFO' { 'Cyan' }
        'SUCCESS' { 'Green' }
        'WARN' { 'Yellow' }
        'ERROR' { 'Red' }
    }

    Write-Host "[$Level] $Message" -ForegroundColor $Color
}

function Test-IsAdministrator {
    $Identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $Principal = New-Object Security.Principal.WindowsPrincipal($Identity)
    return $Principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Enable-StrongTls {
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    } catch {
    }

    try {
        [System.Net.WebRequest]::DefaultWebProxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
    } catch {
    }
}

function Test-ChromeInstalled {
    $ChromePaths = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe"
    )

    foreach ($Path in $ChromePaths) {
        if ($Path -and (Test-Path $Path)) {
            return $true
        }
    }

    return $false
}

function Test-InstallerSignature {
    param([Parameter(Mandatory = $true)][string]$Path)

    $Signature = Get-AuthenticodeSignature -FilePath $Path
    if ($Signature.Status -ne 'Valid') {
        throw "Signature validation failed for $Path. Status: $($Signature.Status)"
    }

    if ($Signature.SignerCertificate.Subject -notmatch 'Google LLC') {
        throw "Unexpected installer signer: $($Signature.SignerCertificate.Subject)"
    }
}

function Download-FileWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination,
        [int64]$MinBytes = 1000000
    )

    for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
        try {
            if (Test-Path $Destination) {
                Remove-Item -LiteralPath $Destination -Force
            }

            Write-Log "Downloading Google Chrome installer, attempt $Attempt..." 'INFO'
            Invoke-WebRequest -Uri $Url -OutFile $Destination -Headers @{ 'User-Agent' = 'Mozilla/5.0' }

            if (-not (Test-Path $Destination)) {
                throw 'Downloaded file is missing.'
            }

            if ((Get-Item $Destination).Length -lt $MinBytes) {
                throw 'Downloaded file is smaller than expected.'
            }

            Test-InstallerSignature -Path $Destination
            return
        } catch {
            Write-Log "Download attempt $Attempt failed: $($_.Exception.Message)" 'WARN'
            Start-Sleep -Seconds 3
        }
    }

    throw 'Could not download Google Chrome installer.'
}

function Install-Msi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$LogPath
    )

    $Arguments = "/i `"$Path`" /qn /norestart /l*v `"$LogPath`""
    $Process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $Arguments -Wait -PassThru

    switch ($Process.ExitCode) {
        0 { Write-Log 'Google Chrome installed successfully.' 'SUCCESS' }
        3010 { Write-Log 'Google Chrome installed successfully. Restart required.' 'WARN' }
        default { throw "Google Chrome installation failed. Exit code: $($Process.ExitCode). Log: $LogPath" }
    }
}

try {
    if (-not (Test-IsAdministrator)) {
        throw 'Please run PowerShell as Administrator.'
    }

    New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    Start-Transcript -Path $TranscriptPath -Append | Out-Null
    Enable-StrongTls

    Write-Log '=== STEP 1: Google Chrome ===' 'INFO'
    if (Test-ChromeInstalled) {
        Write-Log 'Google Chrome is already installed. Nothing to do.' 'SUCCESS'
        return
    }

    Download-FileWithRetry -Url $InstallerUrl -Destination $InstallerPath
    Install-Msi -Path $InstallerPath -LogPath $InstallLog
} catch {
    Write-Log $_.Exception.Message 'ERROR'
    throw
} finally {
    if (Test-Path $TempDir) {
        Remove-Item -LiteralPath $TempDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    try {
        Stop-Transcript | Out-Null
    } catch {
    }
}

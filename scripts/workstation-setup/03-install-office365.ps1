[CmdletBinding()]
param(
    [ValidateSet('O365ProPlusEEANoTeamsRetail', 'O365ProPlusRetail')]
    [string]$OfficeProductId = 'O365ProPlusEEANoTeamsRetail',
    [ValidateSet('Current', 'MonthlyEnterprise', 'SemiAnnualEnterprise')]
    [string]$OfficeChannel = 'Current'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$RunId = Get-Date -Format 'yyyyMMddHHmmss'
$TempDir = Join-Path $env:TEMP "OfficeSetup_$RunId"
$OfficeDir = Join-Path $TempDir 'Office'
$LogDir = Join-Path $env:ProgramData 'WorkstationSetup\Logs'
$TranscriptPath = Join-Path $LogDir "03-install-office365_$RunId.txt"
$OdtUrl = 'https://download.microsoft.com/download/6c1eeb25-cf8b-41d9-8d0d-cc1dbc032140/officedeploymenttool_19929-20062.exe'
$OdtExe = Join-Path $TempDir 'officedeploymenttool.exe'
$SetupExe = Join-Path $OfficeDir 'setup.exe'
$ConfigXml = Join-Path $OfficeDir 'configuration.xml'

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

function Test-InstallerSignature {
    param([Parameter(Mandatory = $true)][string]$Path)

    $Signature = Get-AuthenticodeSignature -FilePath $Path
    if ($Signature.Status -ne 'Valid') {
        throw "Signature validation failed for $Path. Status: $($Signature.Status)"
    }

    if ($Signature.SignerCertificate.Subject -notmatch 'Microsoft') {
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

            Write-Log "Downloading Office Deployment Tool, attempt $Attempt..." 'INFO'
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

    throw 'Could not download Office Deployment Tool.'
}

function Invoke-CheckedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$StepName
    )

    if (-not (Test-Path $FilePath)) {
        throw "$StepName failed. File not found: $FilePath"
    }

    Write-Log $StepName 'INFO'
    $Process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -Wait -PassThru

    switch ($Process.ExitCode) {
        0 { Write-Log "$StepName completed successfully." 'SUCCESS' }
        3010 { Write-Log "$StepName completed successfully. Restart required." 'WARN' }
        default { throw "$StepName failed. Exit code: $($Process.ExitCode)" }
    }
}

function Write-OfficeConfig {
    $OfficeConfig = @"
<Configuration>
  <Add OfficeClientEdition="64" Channel="$OfficeChannel">
    <Product ID="$OfficeProductId">
      <Language ID="pt-br" />
      <ExcludeApp ID="Access" />
      <ExcludeApp ID="Groove" />
      <ExcludeApp ID="Lync" />
      <ExcludeApp ID="OneDrive" />
      <ExcludeApp ID="OneNote" />
      <ExcludeApp ID="OutlookForWindows" />
      <ExcludeApp ID="Publisher" />
      <ExcludeApp ID="Teams" />
    </Product>
  </Add>
  <Property Name="FORCEAPPSHUTDOWN" Value="TRUE" />
  <Property Name="SharedComputerLicensing" Value="0" />
  <Property Name="DeviceBasedLicensing" Value="0" />
  <Updates Enabled="TRUE" />
  <RemoveMSI />
  <Display Level="None" AcceptEULA="TRUE" />
</Configuration>
"@

    Set-Content -Path $ConfigXml -Value $OfficeConfig -Encoding UTF8 -Force
}

try {
    if (-not (Test-IsAdministrator)) {
        throw 'Please run PowerShell as Administrator.'
    }

    New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
    New-Item -ItemType Directory -Path $OfficeDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    Start-Transcript -Path $TranscriptPath -Append | Out-Null
    Enable-StrongTls

    Write-Log '=== STEP 4: Office 365 / Microsoft 365 Apps ===' 'INFO'
    Download-FileWithRetry -Url $OdtUrl -Destination $OdtExe

    Invoke-CheckedProcess -FilePath $OdtExe -ArgumentList "/quiet /extract:`"$OfficeDir`"" -StepName 'Extracting Office Deployment Tool'
    if (-not (Test-Path $SetupExe)) {
        throw "setup.exe was not found after extracting the Office Deployment Tool. Expected path: $SetupExe"
    }

    Write-OfficeConfig
    Invoke-CheckedProcess -FilePath $SetupExe -ArgumentList "/download `"$ConfigXml`"" -StepName 'Downloading Office installation files'
    Invoke-CheckedProcess -FilePath $SetupExe -ArgumentList "/configure `"$ConfigXml`"" -StepName 'Installing Office'
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

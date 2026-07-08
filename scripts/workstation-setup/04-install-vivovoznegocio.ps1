[CmdletBinding()]
param(
    [switch]$ForceReinstall
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$RunId = Get-Date -Format 'yyyyMMddHHmmss'
$TempDir = Join-Path $env:TEMP "VivoVozNegocioSetup_$RunId"
$LogDir = Join-Path $env:ProgramData 'WorkstationSetup\Logs'
$TranscriptPath = Join-Path $LogDir "04-install-vivovoznegocio_$RunId.txt"
$InstallerUrl = 'https://gestaocontactcenter.vivo.com.br/hpbx/file/VivoVozNegocio.exe'
$InstallerPath = Join-Path $TempDir 'VivoVozNegocio.exe'
$ShortcutPath = 'C:\Users\Public\Desktop\VivoVozNegocio.lnk'

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

function Get-UninstallEntries {
    param([Parameter(Mandatory = $true)][string[]]$NamePatterns)

    $RegistryPaths = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )

    foreach ($RegistryPath in $RegistryPaths) {
        foreach ($Item in (Get-ItemProperty -Path $RegistryPath -ErrorAction SilentlyContinue)) {
            $DisplayNameProperty = $Item.PSObject.Properties['DisplayName']
            if (-not $DisplayNameProperty) {
                continue
            }

            foreach ($Pattern in $NamePatterns) {
                if ($DisplayNameProperty.Value -like $Pattern) {
                    $Item
                    break
                }
            }
        }
    }
}

function Get-VivoExecutable {
    $CandidatePaths = @(
        Join-Path $env:ProgramFiles 'VivoVozNegocio\VivoVozNegocio.exe',
        Join-Path $env:ProgramFiles 'Vivo Voz Negocio\VivoVozNegocio.exe',
        Join-Path ${env:ProgramFiles(x86)} 'VivoVozNegocio\VivoVozNegocio.exe',
        Join-Path ${env:ProgramFiles(x86)} 'Vivo Voz Negocio\VivoVozNegocio.exe'
    )

    foreach ($Path in $CandidatePaths) {
        if ($Path -and (Test-Path $Path)) {
            return Get-Item $Path
        }
    }

    foreach ($App in Get-UninstallEntries -NamePatterns @('*VivoVozNegocio*', '*Vivo Voz Negocio*', '*Vivo*Voz*')) {
        if ($App.PSObject.Properties['InstallLocation'] -and $App.InstallLocation) {
            $PossibleExe = Join-Path ($App.InstallLocation.Trim('"')) 'VivoVozNegocio.exe'
            if (Test-Path $PossibleExe) {
                return Get-Item $PossibleExe
            }
        }

        if ($App.PSObject.Properties['DisplayIcon'] -and $App.DisplayIcon) {
            $IconPath = ($App.DisplayIcon -replace ',\d+$', '').Trim('"')
            if ((Test-Path $IconPath) -and ((Split-Path $IconPath -Leaf) -ieq 'VivoVozNegocio.exe')) {
                return Get-Item $IconPath
            }
        }
    }

    return $null
}

function Download-FileWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination,
        [int64]$MinBytes = 100000
    )

    for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
        try {
            if (Test-Path $Destination) {
                Remove-Item -LiteralPath $Destination -Force
            }

            Write-Log "Downloading VivoVozNegocio installer, attempt $Attempt..." 'INFO'
            Invoke-WebRequest -Uri $Url -OutFile $Destination -Headers @{ 'User-Agent' = 'Mozilla/5.0' }

            if (-not (Test-Path $Destination)) {
                throw 'Downloaded file is missing.'
            }

            if ((Get-Item $Destination).Length -lt $MinBytes) {
                throw 'Downloaded file is smaller than expected.'
            }

            $Signature = Get-AuthenticodeSignature -FilePath $Destination
            if ($Signature.Status -ne 'Valid') {
                Write-Log "Vivo installer signature is not valid or unavailable. Status: $($Signature.Status)" 'WARN'
            }

            return
        } catch {
            Write-Log "Download attempt $Attempt failed: $($_.Exception.Message)" 'WARN'
            Start-Sleep -Seconds 3
        }
    }

    throw 'Could not download VivoVozNegocio installer.'
}

function Invoke-CheckedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$StepName
    )

    Write-Log $StepName 'INFO'
    $Process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -Wait -PassThru

    switch ($Process.ExitCode) {
        0 { Write-Log "$StepName completed successfully." 'SUCCESS' }
        3010 { Write-Log "$StepName completed successfully. Restart required." 'WARN' }
        default { throw "$StepName failed. Exit code: $($Process.ExitCode)" }
    }
}

function New-PublicShortcut {
    param(
        [Parameter(Mandatory = $true)][string]$TargetPath,
        [Parameter(Mandatory = $true)][string]$ShortcutPath,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not (Test-Path $TargetPath)) {
        throw "Shortcut target not found: $TargetPath"
    }

    if (Test-Path $ShortcutPath) {
        Copy-Item -LiteralPath $ShortcutPath -Destination "$ShortcutPath.bak.$RunId" -Force
    }

    $Shell = New-Object -ComObject WScript.Shell
    $Shortcut = $Shell.CreateShortcut($ShortcutPath)
    $Shortcut.TargetPath = $TargetPath
    $Shortcut.WorkingDirectory = Split-Path $TargetPath -Parent
    $Shortcut.WindowStyle = 1
    $Shortcut.IconLocation = "$TargetPath,0"
    $Shortcut.Description = $Description
    $Shortcut.Save()

    Write-Log "Shortcut created: $ShortcutPath" 'SUCCESS'
}

try {
    if (-not (Test-IsAdministrator)) {
        throw 'Please run PowerShell as Administrator.'
    }

    New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    Start-Transcript -Path $TranscriptPath -Append | Out-Null
    Enable-StrongTls

    Write-Log '=== STEP 5: VivoVozNegocio ===' 'INFO'
    $Existing = Get-VivoExecutable
    if ($Existing -and -not $ForceReinstall) {
        Write-Log "VivoVozNegocio already exists at: $($Existing.FullName)" 'WARN'
        New-PublicShortcut -TargetPath $Existing.FullName -ShortcutPath $ShortcutPath -Description 'VivoVozNegocio'
        return
    }

    if ($Existing -and $ForceReinstall) {
        Write-Log "Force reinstall requested. Existing executable: $($Existing.FullName)" 'WARN'
        Get-Process -Name 'VivoVozNegocio' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    }

    Download-FileWithRetry -Url $InstallerUrl -Destination $InstallerPath
    Invoke-CheckedProcess -FilePath $InstallerPath -ArgumentList '/quiet /norestart' -StepName 'Installing VivoVozNegocio'

    Start-Sleep -Seconds 5
    $Installed = Get-VivoExecutable
    if (-not $Installed) {
        throw 'VivoVozNegocio installer finished, but the executable was not found.'
    }

    New-PublicShortcut -TargetPath $Installed.FullName -ShortcutPath $ShortcutPath -Description 'VivoVozNegocio'
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

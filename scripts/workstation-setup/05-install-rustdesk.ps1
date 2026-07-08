[CmdletBinding()]
param(
    [switch]$ForceInstall
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$RunId = Get-Date -Format 'yyyyMMddHHmmss'
$TempDir = Join-Path $env:TEMP "RustDeskSetup_$RunId"
$LogDir = Join-Path $env:ProgramData 'WorkstationSetup\Logs'
$TranscriptPath = Join-Path $LogDir "05-install-rustdesk_$RunId.txt"
$RustDeskApiUrl = 'https://api.github.com/repos/rustdesk/rustdesk/releases/latest'
$InstallerPath = Join-Path $TempDir 'rustdesk.msi'
$InstallLog = Join-Path $TempDir 'RustDeskInstall.log'
$ShortcutPath = 'C:\Users\Public\Desktop\RustDesk.lnk'

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

function Get-RustDeskExecutable {
    $CandidatePaths = @(
        'C:\Program Files\RustDesk\rustdesk.exe',
        'C:\Program Files (x86)\RustDesk\rustdesk.exe'
    )

    foreach ($Path in $CandidatePaths) {
        if ($Path -and (Test-Path $Path)) {
            return Get-Item $Path
        }
    }

    foreach ($App in Get-UninstallEntries -NamePatterns @('*RustDesk*')) {
        if ($App.PSObject.Properties['InstallLocation'] -and $App.InstallLocation) {
            $PossibleExe = Join-Path ($App.InstallLocation.Trim('"')) 'rustdesk.exe'
            if (Test-Path $PossibleExe) {
                return Get-Item $PossibleExe
            }
        }

        if ($App.PSObject.Properties['DisplayIcon'] -and $App.DisplayIcon) {
            $IconPath = ($App.DisplayIcon -replace ',\d+$', '').Trim('"')
            if ((Test-Path $IconPath) -and ((Split-Path $IconPath -Leaf) -ieq 'rustdesk.exe')) {
                return Get-Item $IconPath
            }
        }
    }

    return $null
}

function Get-LatestRustDeskMsiUrl {
    $Headers = @{
        'User-Agent' = 'PowerShell-RustDeskInstaller'
        'Accept' = 'application/vnd.github+json'
    }

    Write-Log 'Resolving latest RustDesk MSI from GitHub...' 'INFO'
    $Release = Invoke-RestMethod -Uri $RustDeskApiUrl -Headers $Headers
    $Asset = $Release.assets |
        Where-Object { $_.name -match 'x86_64\.msi$' -or $_.name -match 'x64\.msi$' } |
        Select-Object -First 1

    if (-not $Asset) {
        throw 'No x86_64/x64 MSI asset found in the latest RustDesk release.'
    }

    Write-Log "Latest RustDesk MSI found: $($Asset.name)" 'SUCCESS'
    return $Asset.browser_download_url
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

            Write-Log "Downloading RustDesk installer, attempt $Attempt..." 'INFO'
            Invoke-WebRequest -Uri $Url -OutFile $Destination -Headers @{ 'User-Agent' = 'Mozilla/5.0' }

            if (-not (Test-Path $Destination)) {
                throw 'Downloaded file is missing.'
            }

            if ((Get-Item $Destination).Length -lt $MinBytes) {
                throw 'Downloaded file is smaller than expected.'
            }

            $Signature = Get-AuthenticodeSignature -FilePath $Destination
            if ($Signature.Status -ne 'Valid') {
                Write-Log "RustDesk installer signature is not valid or unavailable. Status: $($Signature.Status)" 'WARN'
            }

            return
        } catch {
            Write-Log "Download attempt $Attempt failed: $($_.Exception.Message)" 'WARN'
            Start-Sleep -Seconds 3
        }
    }

    throw 'Could not download RustDesk installer.'
}

function Install-Msi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$LogPath
    )

    $Arguments = "/i `"$Path`" /qn /norestart /l*v `"$LogPath`""
    $Process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $Arguments -Wait -PassThru

    switch ($Process.ExitCode) {
        0 { Write-Log 'RustDesk installed successfully.' 'SUCCESS' }
        3010 { Write-Log 'RustDesk installed successfully. Restart required.' 'WARN' }
        default { throw "RustDesk installation failed. Exit code: $($Process.ExitCode). Log: $LogPath" }
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

    Write-Log '=== STEP 6: RustDesk ===' 'INFO'
    $Existing = Get-RustDeskExecutable
    if ($Existing -and -not $ForceInstall) {
        Write-Log "RustDesk already exists at: $($Existing.FullName)" 'WARN'
        New-PublicShortcut -TargetPath $Existing.FullName -ShortcutPath $ShortcutPath -Description 'RustDesk'
        return
    }

    $MsiUrl = Get-LatestRustDeskMsiUrl
    Download-FileWithRetry -Url $MsiUrl -Destination $InstallerPath
    Install-Msi -Path $InstallerPath -LogPath $InstallLog

    Start-Sleep -Seconds 3
    $Installed = Get-RustDeskExecutable
    if (-not $Installed) {
        throw 'RustDesk installer finished, but rustdesk.exe was not found.'
    }

    New-PublicShortcut -TargetPath $Installed.FullName -ShortcutPath $ShortcutPath -Description 'RustDesk'
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

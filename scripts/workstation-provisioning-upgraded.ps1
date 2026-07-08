[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [switch]$ForceReinstallVivo,
    [switch]$ForceInstallRustDesk,
    [switch]$KeepTempFiles,
    [switch]$SkipChrome,
    [switch]$SkipOpenVpn,
    [switch]$SkipOffice,
    [switch]$SkipVivo,
    [switch]$SkipRustDesk,
    [ValidateSet('O365ProPlusEEANoTeamsRetail', 'O365ProPlusRetail')]
    [string]$OfficeProductId = 'O365ProPlusEEANoTeamsRetail',
    [ValidateSet('Current', 'MonthlyEnterprise', 'SemiAnnualEnterprise')]
    [string]$OfficeChannel = 'Current'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$Script:RunId = Get-Date -Format 'yyyyMMddHHmmss'
$Script:TempRoot = Join-Path $env:TEMP "WorkstationSetup_$Script:RunId"
$Script:LogRoot = Join-Path $env:ProgramData 'WorkstationSetup\Logs'
$Script:TranscriptPath = Join-Path $Script:LogRoot "WorkstationSetup_$Script:RunId.txt"
$Script:RollbackActions = New-Object System.Collections.Generic.List[object]
$Script:CreatedPaths = New-Object System.Collections.Generic.List[string]
$Script:CreatedShortcuts = New-Object System.Collections.Generic.List[string]
$Script:SkippedItems = New-Object System.Collections.Generic.List[string]

$Script:ChromeInstallerUrl = 'https://dl.google.com/chrome/install/GoogleChromeStandaloneEnterprise64.msi'
$Script:OpenVpnMsiUrl = 'https://build.openvpn.net/downloads/releases/latest/openvpn-latest-stable-amd64.msi'
$Script:OdtUrl = 'https://download.microsoft.com/download/6c1eeb25-cf8b-41d9-8d0d-cc1dbc032140/officedeploymenttool_19929-20062.exe'
$Script:VivoExeUrl = 'https://gestaocontactcenter.vivo.com.br/hpbx/file/VivoVozNegocio.exe'
$Script:RustDeskApiUrl = 'https://api.github.com/repos/rustdesk/rustdesk/releases/latest'

$Script:ChromeInstallerPath = Join-Path $Script:TempRoot 'GoogleChromeStandaloneEnterprise64.msi'
$Script:ChromeInstallLog = Join-Path $Script:TempRoot 'ChromeInstall.log'

$Script:OpenVpnInstallerPath = Join-Path $Script:TempRoot 'openvpn-latest-stable-amd64.msi'
$Script:OpenVpnInstallLog = Join-Path $Script:TempRoot 'OpenVPNInstall.log'
$Script:OpenVpnInstallDir = 'C:\Program Files\OpenVPN'
$Script:OpenVpnConfigDir = Join-Path $Script:OpenVpnInstallDir 'config'
$Script:OpenVpnConfigPath = Join-Path $Script:OpenVpnConfigDir 'FecomercioSP.ovpn'

$Script:OfficeDir = Join-Path $Script:TempRoot 'Office'
$Script:OdtExe = Join-Path $Script:TempRoot 'officedeploymenttool.exe'
$Script:SetupExe = Join-Path $Script:OfficeDir 'setup.exe'
$Script:ConfigXml = Join-Path $Script:OfficeDir 'configuration.xml'

$Script:VivoExePath = Join-Path $Script:TempRoot 'VivoVozNegocio.exe'
$Script:VivoShortcutPath = 'C:\Users\Public\Desktop\VivoVozNegocio.lnk'

$Script:RustDeskMsiPath = Join-Path $Script:TempRoot 'rustdesk.msi'
$Script:RustDeskInstallLog = Join-Path $Script:TempRoot 'RustDeskInstall.log'
$Script:RustDeskShortcutPath = 'C:\Users\Public\Desktop\RustDesk.lnk'

function Write-Log {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [ValidateSet('INFO', 'SUCCESS', 'WARN', 'ERROR')]
        [string]$Level = 'INFO'
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

function Initialize-RunFolders {
    New-Item -ItemType Directory -Path $Script:TempRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $Script:LogRoot -Force | Out-Null
    Start-Transcript -Path $Script:TranscriptPath -Append | Out-Null
}

function Register-RollbackAction {
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    $Script:RollbackActions.Add([pscustomobject]@{
            Type = 'Custom'
            Action = $Action
        }) | Out-Null
}

function Register-DeleteRollback {
    param([Parameter(Mandatory = $true)][string]$Path)
    $Script:RollbackActions.Add([pscustomobject]@{
            Type = 'Delete'
            Path = $Path
        }) | Out-Null
}

function Register-RestoreRollback {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BackupPath
    )

    $Script:RollbackActions.Add([pscustomobject]@{
            Type = 'Restore'
            Path = $Path
            BackupPath = $BackupPath
        }) | Out-Null
}

function Invoke-Rollback {
    if ($Script:RollbackActions.Count -eq 0) {
        return
    }

    Write-Log 'Starting rollback of local changes...' 'WARN'

    for ($Index = $Script:RollbackActions.Count - 1; $Index -ge 0; $Index--) {
        try {
            $Item = $Script:RollbackActions[$Index]
            switch ($Item.Type) {
                'Custom' { & $Item.Action }
                'Delete' {
                    if (Test-Path $Item.Path) {
                        Remove-Item -LiteralPath $Item.Path -Force -ErrorAction SilentlyContinue
                    }
                }
                'Restore' {
                    if (Test-Path $Item.BackupPath) {
                        Copy-Item -LiteralPath $Item.BackupPath -Destination $Item.Path -Force
                        Remove-Item -LiteralPath $Item.BackupPath -Force -ErrorAction SilentlyContinue
                    }
                }
            }
        } catch {
            Write-Log "Rollback step failed: $($_.Exception.Message)" 'WARN'
        }
    }
}

function Enable-StrongTls {
    try {
        $Tls = [Net.SecurityProtocolType]::Tls12
        if ([Enum]::IsDefined([Net.SecurityProtocolType], 'Tls11')) {
            $Tls = $Tls -bor [Net.SecurityProtocolType]::Tls11
        }
        if ([Enum]::IsDefined([Net.SecurityProtocolType], 'Tls')) {
            $Tls = $Tls -bor [Net.SecurityProtocolType]::Tls
        }
        [Net.ServicePointManager]::SecurityProtocol = $Tls
    } catch {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    }

    try {
        [System.Net.WebRequest]::DefaultWebProxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
    } catch {
        # Ignore proxy configuration failures.
    }
}

function Test-InstalledFile {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Paths
    )

    foreach ($Path in $Paths) {
        if ($Path -and (Test-Path $Path)) {
            return Get-Item $Path
        }
    }

    return $null
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
            foreach ($Pattern in $NamePatterns) {
                if ($Item.DisplayName -like $Pattern) {
                    $Item
                    break
                }
            }
        }
    }
}

function Test-ChromeInstalled {
    $Paths = @(
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe"
    )
    return [bool](Test-InstalledFile -Paths $Paths)
}

function Test-OpenVpnInstalled {
    $Paths = @(
        'C:\Program Files\OpenVPN\bin\openvpn.exe',
        'C:\Program Files\OpenVPN\bin\openvpn-gui.exe',
        'C:\Program Files (x86)\OpenVPN\bin\openvpn.exe',
        'C:\Program Files (x86)\OpenVPN\bin\openvpn-gui.exe'
    )
    return [bool](Test-InstalledFile -Paths $Paths)
}

function Get-VivoExecutable {
    $CandidatePaths = @(
        Join-Path $env:ProgramFiles 'VivoVozNegocio\VivoVozNegocio.exe',
        Join-Path $env:ProgramFiles 'Vivo Voz Negocio\VivoVozNegocio.exe',
        Join-Path ${env:ProgramFiles(x86)} 'VivoVozNegocio\VivoVozNegocio.exe',
        Join-Path ${env:ProgramFiles(x86)} 'Vivo Voz Negocio\VivoVozNegocio.exe'
    ) | Where-Object { $_ }

    $Found = Test-InstalledFile -Paths $CandidatePaths
    if ($Found) {
        return $Found
    }

    foreach ($App in Get-UninstallEntries -NamePatterns @('*VivoVozNegocio*', '*Vivo Voz Negocio*', '*Vivo*Voz*')) {
        if ($App.InstallLocation) {
            $PossibleExe = Join-Path ($App.InstallLocation.Trim('"')) 'VivoVozNegocio.exe'
            if (Test-Path $PossibleExe) {
                return Get-Item $PossibleExe
            }
        }

        if ($App.DisplayIcon) {
            $IconPath = ($App.DisplayIcon -replace ',\d+$', '').Trim('"')
            if ((Test-Path $IconPath) -and ((Split-Path $IconPath -Leaf) -ieq 'VivoVozNegocio.exe')) {
                return Get-Item $IconPath
            }
        }
    }

    return $null
}

function Get-RustDeskExecutable {
    $CandidatePaths = @(
        'C:\Program Files\RustDesk\rustdesk.exe',
        'C:\Program Files (x86)\RustDesk\rustdesk.exe'
    )

    $Found = Test-InstalledFile -Paths $CandidatePaths
    if ($Found) {
        return $Found
    }

    foreach ($App in Get-UninstallEntries -NamePatterns @('*RustDesk*')) {
        if ($App.InstallLocation) {
            $PossibleExe = Join-Path ($App.InstallLocation.Trim('"')) 'rustdesk.exe'
            if (Test-Path $PossibleExe) {
                return Get-Item $PossibleExe
            }
        }

        if ($App.DisplayIcon) {
            $IconPath = ($App.DisplayIcon -replace ',\d+$', '').Trim('"')
            if ((Test-Path $IconPath) -and ((Split-Path $IconPath -Leaf) -ieq 'rustdesk.exe')) {
                return Get-Item $IconPath
            }
        }
    }

    return $null
}

function Test-AuthenticodeSignature {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [string[]]$ExpectedIssuerTokens = @(),
        [switch]$AllowUnsigned
    )

    $Signature = Get-AuthenticodeSignature -FilePath $Path
    if ($Signature.Status -eq 'Valid') {
        if ($ExpectedIssuerTokens.Count -gt 0) {
            $Subject = $Signature.SignerCertificate.Subject
            foreach ($Token in $ExpectedIssuerTokens) {
                if ($Subject -match [regex]::Escape($Token)) {
                    return $true
                }
            }

            throw "Signature is valid but the signer subject did not match the expected vendor tokens: $($ExpectedIssuerTokens -join ', '). Subject: $Subject"
        }

        return $true
    }

    if ($AllowUnsigned) {
        Write-Log "Signature check skipped for unsigned file: $Path" 'WARN'
        return $false
    }

    throw "Authenticode signature validation failed for $Path. Status: $($Signature.Status)"
}

function Invoke-DownloadFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,
        [Parameter(Mandatory = $true)]
        [string]$Destination,
        [Parameter(Mandatory = $true)]
        [string]$DisplayName,
        [int64]$MinBytes = 1,
        [string[]]$ExpectedIssuerTokens = @(),
        [switch]$AllowUnsigned
    )

    $Methods = @('BITS', 'InvokeWebRequest', 'WebClient', 'Curl')

    foreach ($Method in $Methods) {
        for ($Attempt = 1; $Attempt -le 3; $Attempt++) {
            try {
                if (Test-Path $Destination) {
                    Remove-Item -LiteralPath $Destination -Force
                }

                Write-Log "Downloading $DisplayName using $Method, attempt $Attempt..." 'INFO'

                switch ($Method) {
                    'BITS' {
                        if (Get-Command Start-BitsTransfer -ErrorAction SilentlyContinue) {
                            Start-BitsTransfer -Source $Url -Destination $Destination -ErrorAction Stop
                        } else {
                            throw 'BITS is unavailable on this system.'
                        }
                    }
                    'InvokeWebRequest' {
                        Invoke-WebRequest -Uri $Url -OutFile $Destination -Headers @{ 'User-Agent' = 'Mozilla/5.0' } -ErrorAction Stop
                    }
                    'WebClient' {
                        $Client = New-Object System.Net.WebClient
                        try {
                            $Client.Headers.Add('User-Agent', 'Mozilla/5.0')
                            if ($Client.Proxy) {
                                $Client.Proxy.Credentials = [System.Net.CredentialCache]::DefaultCredentials
                            }
                            $Client.DownloadFile($Url, $Destination)
                        } finally {
                            $Client.Dispose()
                        }
                    }
                    'Curl' {
                        $CurlPath = Join-Path $env:SystemRoot 'System32\curl.exe'
                        if (-not (Test-Path $CurlPath)) {
                            throw 'curl.exe not found.'
                        }

                        & $CurlPath -L --retry 3 --output $Destination $Url
                        if ($LASTEXITCODE -ne 0) {
                            throw "curl.exe failed with exit code $LASTEXITCODE"
                        }
                    }
                }

                if (-not (Test-Path $Destination)) {
                    throw 'Download completed but the file does not exist.'
                }

                $Item = Get-Item $Destination
                if ($Item.Length -lt $MinBytes) {
                    throw "Downloaded file is smaller than expected. Size: $($Item.Length) bytes."
                }

                if (-not $AllowUnsigned -or $ExpectedIssuerTokens.Count -gt 0) {
                    Test-AuthenticodeSignature -Path $Destination -ExpectedIssuerTokens $ExpectedIssuerTokens -AllowUnsigned:$AllowUnsigned | Out-Null
                }

                Write-Log "$DisplayName download completed successfully." 'SUCCESS'
                return $true
            } catch {
                Write-Log "${Method} failed for ${DisplayName}: $($_.Exception.Message)" 'WARN'
                Start-Sleep -Seconds 3
            }
        }
    }

    return $false
}

function Invoke-MsiInstall {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallerPath,
        [Parameter(Mandatory = $true)]
        [string]$LogPath,
        [Parameter(Mandatory = $true)]
        [string]$DisplayName
    )

    if (-not (Test-Path $InstallerPath)) {
        throw "$DisplayName installer not found: $InstallerPath"
    }

    Write-Log "Installing $DisplayName silently..." 'INFO'
    $Arguments = "/i `"$InstallerPath`" /qn /norestart /l*v `"$LogPath`""

    $Process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $Arguments -Wait -PassThru
    switch ($Process.ExitCode) {
        0 { Write-Log "$DisplayName installed successfully." 'SUCCESS' }
        3010 { Write-Log "$DisplayName installed successfully. Restart required." 'WARN' }
        default {
            throw "$DisplayName installation failed. Exit code: $($Process.ExitCode). Log: $LogPath"
        }
    }
}

function Invoke-Executable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(Mandatory = $true)]
        [string]$ArgumentList,
        [Parameter(Mandatory = $true)]
        [string]$StepName
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

function New-PublicShortcut {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetPath,
        [Parameter(Mandatory = $true)]
        [string]$ShortcutPath,
        [Parameter(Mandatory = $true)]
        [string]$Description
    )

    if (-not (Test-Path $TargetPath)) {
        throw "Shortcut target not found: $TargetPath"
    }

    $ShortcutDir = Split-Path $ShortcutPath -Parent
    New-Item -ItemType Directory -Path $ShortcutDir -Force | Out-Null

    $ShortcutAlreadyExists = Test-Path $ShortcutPath
    if ($ShortcutAlreadyExists) {
        $ShortcutBackupPath = "$ShortcutPath.bak.$Script:RunId"
        Copy-Item -LiteralPath $ShortcutPath -Destination $ShortcutBackupPath -Force
        Register-RestoreRollback -Path $ShortcutPath -BackupPath $ShortcutBackupPath
    } else {
        Register-DeleteRollback -Path $ShortcutPath
    }

    $Shell = New-Object -ComObject WScript.Shell
    $Shortcut = $Shell.CreateShortcut($ShortcutPath)
    $Shortcut.TargetPath = $TargetPath
    $Shortcut.WorkingDirectory = Split-Path $TargetPath -Parent
    $Shortcut.WindowStyle = 1
    $Shortcut.IconLocation = "$TargetPath,0"
    $Shortcut.Description = $Description
    $Shortcut.Save()

    Write-Log "Shortcut created successfully: $ShortcutPath" 'SUCCESS'
}

function Write-OpenVpnConfig {
    $ConfigExists = Test-Path $Script:OpenVpnConfigPath
    $BackupPath = if ($ConfigExists) { "$Script:OpenVpnConfigPath.bak.$Script:RunId" } else { $null }

    if ($ConfigExists) {
        Copy-Item -LiteralPath $Script:OpenVpnConfigPath -Destination $BackupPath -Force
        Register-RestoreRollback -Path $Script:OpenVpnConfigPath -BackupPath $BackupPath
    } else {
        Register-DeleteRollback -Path $Script:OpenVpnConfigPath
    }

    New-Item -ItemType Directory -Path $Script:OpenVpnConfigDir -Force | Out-Null

    $OvpnConfig = @'
client
dev tun
proto udp
remote 189.112.243.91 9443
remote 189.112.243.89 9443
remote 189.112.243.92 9443
remote 189.112.243.93 9443
resolv-retry infinite
nobind
cipher AES-128-CBC
data-ciphers AES-128-CBC
auth SHA256
keepalive 5 10
persist-key
persist-tun
auth-user-pass
auth-nocache
comp-lzo
reneg-sec 0
route 192.168.0.0 255.255.255.0
route 192.168.1.0 255.255.255.0
route 172.16.10.0 255.255.255.0
route 172.16.11.0 255.255.255.0
verb 3
<ca>
-----BEGIN CERTIFICATE-----
MIIFXDCCA0SgAwIBAgIIUPoQ9AxyiT4wDQYJKoZIhvcNAQENBQAwTDELMAkGA1UE
BhMCQlIxEzARBgNVBAoTCkZlY29tZXJjaW8xCzAJBgNVBAsTAlRJMRswGQYDVQQD
ExJGZWNvbWVyY2lvIFJvb3QgQ0EwHhcNMjQwMjI5MTcyMjA0WhcNMzQwMjI2MTcy
MjA0WjBMMQswCQYDVQQGEwJCUjETMBEGA1UEChMKRmVjb21lcmNpbzELMAkGA1UE
CxMCVEkxGzAZBgNVBAMTEkZlY29tZXJjaW8gUm9vdCBDQTCCAiIwDQYJKoZIhvcN
AQEBBQADggIPADCCAgoCggIBANe3D/31cil7jQRadL7RrSPMhgujxW07iMpAX7Qw
at+gxutmbDRPbDsZalAFmqnDXXauUCHpceu5UukwfdTlTxQ6SE9LBuCe/4e5gjti
ka6UpO3eJ32YUZop2y+Qt8J76K9TEuBEKan90vBvqEcgRTUKu4ktY2WVfj6aBQc0
89amSnqSOm3wVHFwV8YH+yJtXoAJj9lUb9vXlw0xJpFfn/66lyWVnIM62WbLIK8S
E4uncplQGIFh00LVXHMYbuud9hgqFETF2gVUnJoLmuUx4Wkls5hZqcZSgz16TQDb
ixdzJ104WdiJzqYSGEGenZwBA+N4997+uZ2XkqqSamaWyCM/AREGm/4yyRLcoOBl
UA6J6rxM9TvJ5Z6BwPlSSVnoBCBqEwQRFWMPFaQ3feIOL3zRqg7KN2QvXL/Rt9CJ
TUsgmwOZ73NHLGUZsSQrPYQ+rWyp4FAd36NofU13DvB5INDP872nfLMvKsqiJojK
LndEQjc9nGObGutTMjK7IZK3ICMgmfq+TgJfuRtVz3KmmVhvQxl82X9Brxh+WmOZ
Ybr8vSOOh2J2tO0xQRtQTW304XJj8tJbY8Sky3t30THNCt+JM5b847k6G8F0YXWo
HUZcEYpOEJJV09wPuwXBwbKFHglgWWALlnqgyj27qnK7aDyx6H8MEkQgmYNzfmgi
WyJdAgMBAAGjQjBAMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMB0G
A1UdDgQWBBSxvNhZyxs7go3o7iRrJVxAQxeXYzANBgkqhkiG9w0BAQ0FAAOCAgEA
YU1IcvecjmQJWZ1Oob3OHt8JBxjpu2qWcPOy9Kn12Ybl1FBduw8KJ/Il4ySL2CSu
0XYn2RMseMUBgcizApi5y02ZzXvS410tvjEZf1KgYdUWJ7miLpIRMm/0tiTacXN4
COV4e4Gk+D2qLOhiUy7GtuyoA0NgFj2ByK90BIrG938Es0sXk24i15cuD5583BLR
TEMZCF/nLF+dcpvH0mERNm0DqFOxq/rpxWv1ScwcblDEyVLBrO8lbsLJ2kCPcXs0
p7Yd+G/Kwntw2044EnuIpg3rwy8KZbqyb19rHG+agrxbeGLgmSf9aHRGNJ5R4bC/
DfFufAw9ehtCBje7JO+dk/fy/vVkHbbA0puvQSTnUnmCtF60Vj9ZPYNZNZBKkW2a
21G/KihLOFWI16Rg2rdiDiXBsK67JUdohKsFRMxJTp8B69+swteBGUFZkDMH4q09
YSmFklqLc0RQXqmw18ejC3lXdW3QT/5sMwoJPiTTEB9vzh5ovx8g1cpHdr4jSO3p
3D2v1YY6OW90QkX6Uql9rTsHaILBXEyVhkbHnzAiZ3S+xybSEhFhuJA6V4Jg9eTM
oeiRu0QmzXHrkJ7+hBeHTCQPnGXJonzVX/Otgomp68CJabDF/qLCJowVCfn1H/Ly
TFitdiamtZtpC/ELa6ot3cA6USnwP3/GssSJNLVAAoI=
-----END CERTIFICATE-----
</ca>
'@

    Set-Content -Path $Script:OpenVpnConfigPath -Value $OvpnConfig -Encoding ASCII -Force
    Write-Log "VPN profile written to $Script:OpenVpnConfigPath" 'SUCCESS'
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

    Set-Content -Path $Script:ConfigXml -Value $OfficeConfig -Encoding UTF8 -Force
}

function Get-LatestRustDeskMsiUrl {
    Write-Log 'Getting latest RustDesk stable release from GitHub...' 'INFO'

    $Headers = @{
        'User-Agent' = 'PowerShell-RustDeskInstaller'
        'Accept' = 'application/vnd.github+json'
    }

    try {
        $Release = Invoke-RestMethod -Uri $Script:RustDeskApiUrl -Headers $Headers -ErrorAction Stop
        $Asset = $Release.assets |
            Where-Object {
                $_.name -match 'x86_64\.msi$' -or $_.name -match 'x64\.msi$'
            } |
            Select-Object -First 1

        if (-not $Asset) {
            throw 'No MSI asset found in the latest RustDesk release.'
        }

        Write-Log "Latest RustDesk MSI found: $($Asset.name)" 'SUCCESS'
        return $Asset.browser_download_url
    } catch {
        throw "Could not resolve the latest RustDesk MSI: $($_.Exception.Message)"
    }
}

function Install-Chrome {
    if (Test-ChromeInstalled) {
        Write-Log 'Google Chrome is already installed. Skipping.' 'WARN'
        $Script:SkippedItems.Add('Chrome') | Out-Null
        return
    }

    if (-not $PSCmdlet.ShouldProcess('Google Chrome', 'Download and install')) {
        return
    }

    $Downloaded = Invoke-DownloadFile -Url $Script:ChromeInstallerUrl -Destination $Script:ChromeInstallerPath -DisplayName 'Google Chrome' -MinBytes 1000000 -ExpectedIssuerTokens @('Google LLC')
    if (-not $Downloaded) {
        throw 'Could not download Google Chrome installer.'
    }

    Invoke-MsiInstall -InstallerPath $Script:ChromeInstallerPath -LogPath $Script:ChromeInstallLog -DisplayName 'Google Chrome'
}

function Install-OpenVpn {
    if (Test-OpenVpnInstalled) {
        Write-Log 'OpenVPN appears to already be installed. Skipping.' 'WARN'
        $Script:SkippedItems.Add('OpenVPN') | Out-Null
        return
    }

    if (-not $PSCmdlet.ShouldProcess('OpenVPN Community', 'Download and install')) {
        return
    }

    $Downloaded = Invoke-DownloadFile -Url $Script:OpenVpnMsiUrl -Destination $Script:OpenVpnInstallerPath -DisplayName 'OpenVPN Community' -MinBytes 1000000 -ExpectedIssuerTokens @('OpenVPN')
    if (-not $Downloaded) {
        throw 'Could not download OpenVPN installer.'
    }

    Invoke-MsiInstall -InstallerPath $Script:OpenVpnInstallerPath -LogPath $Script:OpenVpnInstallLog -DisplayName 'OpenVPN Community'
}

function Install-Office {
    if (-not $PSCmdlet.ShouldProcess("Microsoft 365 Apps ($OfficeProductId)", 'Download and install')) {
        return
    }

    $Downloaded = Invoke-DownloadFile -Url $Script:OdtUrl -Destination $Script:OdtExe -DisplayName 'Office Deployment Tool' -MinBytes 1000000 -ExpectedIssuerTokens @('Microsoft')
    if (-not $Downloaded) {
        throw 'Could not download the Office Deployment Tool.'
    }

    Invoke-Executable -FilePath $Script:OdtExe -ArgumentList "/quiet /extract:`"$Script:OfficeDir`"" -StepName 'Extracting Office Deployment Tool'

    if (-not (Test-Path $Script:SetupExe)) {
        throw "setup.exe was not found after extracting the Office Deployment Tool. Expected path: $Script:SetupExe"
    }

    Write-OfficeConfig
    Invoke-Executable -FilePath $Script:SetupExe -ArgumentList "/download `"$Script:ConfigXml`"" -StepName 'Downloading Office installation files'
    Invoke-Executable -FilePath $Script:SetupExe -ArgumentList "/configure `"$Script:ConfigXml`"" -StepName 'Installing Office'
}

function Install-Vivo {
    $Existing = Get-VivoExecutable

    if ($Existing -and -not $ForceReinstallVivo) {
        Write-Log "Existing VivoVozNegocio executable found at: $($Existing.FullName). Reusing it." 'WARN'
        New-PublicShortcut -TargetPath $Existing.FullName -ShortcutPath $Script:VivoShortcutPath -Description 'VivoVozNegocio'
        $Script:SkippedItems.Add('VivoVozNegocio') | Out-Null
        return
    }

    if ($Existing -and $ForceReinstallVivo) {
        Write-Log "Force reinstall requested for VivoVozNegocio. Existing executable: $($Existing.FullName)" 'WARN'
    }

    if (-not $PSCmdlet.ShouldProcess('VivoVozNegocio', 'Download and install')) {
        return
    }

    $Downloaded = Invoke-DownloadFile -Url $Script:VivoExeUrl -Destination $Script:VivoExePath -DisplayName 'VivoVozNegocio' -MinBytes 100000 -AllowUnsigned
    if (-not $Downloaded) {
        throw 'Could not download VivoVozNegocio installer.'
    }

    Invoke-Executable -FilePath $Script:VivoExePath -ArgumentList '/quiet /norestart' -StepName 'Installing VivoVozNegocio'

    Start-Sleep -Seconds 5
    $Installed = Get-VivoExecutable
    if (-not $Installed) {
        throw 'VivoVozNegocio installer finished, but the application executable was not found.'
    }

    New-PublicShortcut -TargetPath $Installed.FullName -ShortcutPath $Script:VivoShortcutPath -Description 'VivoVozNegocio'
}

function Install-RustDesk {
    $Existing = Get-RustDeskExecutable

    if ($Existing -and -not $ForceInstallRustDesk) {
        Write-Log "RustDesk is already installed at: $($Existing.FullName). Reusing it." 'WARN'
        New-PublicShortcut -TargetPath $Existing.FullName -ShortcutPath $Script:RustDeskShortcutPath -Description 'RustDesk'
        $Script:SkippedItems.Add('RustDesk') | Out-Null
        return
    }

    if (-not $PSCmdlet.ShouldProcess('RustDesk', 'Download and install')) {
        return
    }

    $RustDeskMsiUrl = Get-LatestRustDeskMsiUrl
    $Downloaded = Invoke-DownloadFile -Url $RustDeskMsiUrl -Destination $Script:RustDeskMsiPath -DisplayName 'RustDesk' -MinBytes 1000000
    if (-not $Downloaded) {
        throw 'Could not download RustDesk installer.'
    }

    Invoke-MsiInstall -InstallerPath $Script:RustDeskMsiPath -LogPath $Script:RustDeskInstallLog -DisplayName 'RustDesk'

    Start-Sleep -Seconds 3
    $Installed = Get-RustDeskExecutable
    if (-not $Installed) {
        throw 'RustDesk installer finished, but rustdesk.exe was not found.'
    }

    New-PublicShortcut -TargetPath $Installed.FullName -ShortcutPath $Script:RustDeskShortcutPath -Description 'RustDesk'
}

function Cleanup-TempFiles {
    if ($KeepTempFiles) {
        Write-Log "Keeping temporary files at: $Script:TempRoot" 'WARN'
        return
    }

    if (Test-Path $Script:TempRoot) {
        Remove-Item -LiteralPath $Script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Log 'Temporary files removed.' 'SUCCESS'
}

try {
    if (-not (Test-IsAdministrator)) {
        throw 'Please run PowerShell as Administrator.'
    }

    Enable-StrongTls
    Initialize-RunFolders
    Write-Log "Starting workstation provisioning run $Script:RunId" 'INFO'
    Write-Log "Temporary folder: $Script:TempRoot" 'INFO'
    Write-Log "Transcript: $Script:TranscriptPath" 'INFO'

    if (-not $SkipChrome) {
        Write-Log '=== STEP 1: Google Chrome ===' 'INFO'
        Install-Chrome
    } else {
        Write-Log 'Chrome step skipped by parameter.' 'WARN'
    }

    if (-not $SkipOpenVpn) {
        Write-Log '=== STEP 2: OpenVPN Community ===' 'INFO'
        Install-OpenVpn

        Write-Log '=== STEP 3: Add OpenVPN Config ===' 'INFO'
        Write-OpenVpnConfig
        Write-Log "OpenVPN profile created at: $Script:OpenVpnConfigPath" 'SUCCESS'
    } else {
        Write-Log 'OpenVPN step skipped by parameter.' 'WARN'
    }

    if (-not $SkipOffice) {
        Write-Log '=== STEP 4: Office 365 / Microsoft 365 Apps ===' 'INFO'
        Install-Office
    } else {
        Write-Log 'Office step skipped by parameter.' 'WARN'
    }

    if (-not $SkipVivo) {
        Write-Log '=== STEP 5: VivoVozNegocio ===' 'INFO'
        Install-Vivo
    } else {
        Write-Log 'VivoVozNegocio step skipped by parameter.' 'WARN'
    }

    if (-not $SkipRustDesk) {
        Write-Log '=== STEP 6: RustDesk ===' 'INFO'
        Install-RustDesk
    } else {
        Write-Log 'RustDesk step skipped by parameter.' 'WARN'
    }

    Write-Log '=== CLEANUP ===' 'INFO'
    Cleanup-TempFiles

    Write-Host ''
    Write-Log 'All steps finished.' 'SUCCESS'
    Write-Log "Skipped items: $($Script:SkippedItems -join ', ')" 'INFO'
    Write-Log "VPN profile: $Script:OpenVpnConfigPath" 'SUCCESS'
    Write-Log "Transcript saved at: $Script:TranscriptPath" 'SUCCESS'

    Write-Host ''
    Write-Host 'VPN connection steps:'
    Write-Host '1. Open OpenVPN GUI.'
    Write-Host '2. Right-click the OpenVPN icon in the system tray.'
    Write-Host '3. Select FecomercioSP.'
    Write-Host '4. Click Connect.'
    Write-Host '5. Enter the VPN username and password when prompted.'
} catch {
    Write-Log $_.Exception.Message 'ERROR'
    Invoke-Rollback
    try {
        if (-not $KeepTempFiles) {
            if (Test-Path $Script:TempRoot) {
                Remove-Item -LiteralPath $Script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue
            }
        } else {
            Write-Log "Keeping temporary files at: $Script:TempRoot" 'WARN'
        }
    } finally {
        Write-Log "Failure transcript: $Script:TranscriptPath" 'ERROR'
    }

    throw
} finally {
    try {
        Stop-Transcript | Out-Null
    } catch {
        # Transcript may not have been started if initialization failed early.
    }
}

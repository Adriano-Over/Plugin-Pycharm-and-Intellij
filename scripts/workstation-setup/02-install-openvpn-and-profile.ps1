[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$RunId = Get-Date -Format 'yyyyMMddHHmmss'
$TempDir = Join-Path $env:TEMP "OpenVpnSetup_$RunId"
$LogDir = Join-Path $env:ProgramData 'WorkstationSetup\Logs'
$TranscriptPath = Join-Path $LogDir "02-install-openvpn-and-profile_$RunId.txt"
$InstallerUrl = 'https://build.openvpn.net/downloads/releases/latest/openvpn-latest-stable-amd64.msi'
$InstallerPath = Join-Path $TempDir 'openvpn-latest-stable-amd64.msi'
$InstallLog = Join-Path $TempDir 'OpenVPNInstall.log'
$OpenVpnInstallDir = 'C:\Program Files\OpenVPN'
$OpenVpnConfigDir = Join-Path $OpenVpnInstallDir 'config'
$OpenVpnConfigPath = Join-Path $OpenVpnConfigDir 'FecomercioSP.ovpn'

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

function Test-OpenVpnInstalled {
    $OpenVpnPaths = @(
        'C:\Program Files\OpenVPN\bin\openvpn.exe',
        'C:\Program Files\OpenVPN\bin\openvpn-gui.exe',
        'C:\Program Files (x86)\OpenVPN\bin\openvpn.exe',
        'C:\Program Files (x86)\OpenVPN\bin\openvpn-gui.exe'
    )

    foreach ($Path in $OpenVpnPaths) {
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

    if ($Signature.SignerCertificate.Subject -notmatch 'OpenVPN') {
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

            Write-Log "Downloading OpenVPN installer, attempt $Attempt..." 'INFO'
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

    throw 'Could not download OpenVPN installer.'
}

function Install-Msi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$LogPath
    )

    $Arguments = "/i `"$Path`" /qn /norestart /l*v `"$LogPath`""
    $Process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $Arguments -Wait -PassThru

    switch ($Process.ExitCode) {
        0 { Write-Log 'OpenVPN installed successfully.' 'SUCCESS' }
        3010 { Write-Log 'OpenVPN installed successfully. Restart required.' 'WARN' }
        default { throw "OpenVPN installation failed. Exit code: $($Process.ExitCode). Log: $LogPath" }
    }
}

function Write-OpenVpnProfile {
    New-Item -ItemType Directory -Path $OpenVpnConfigDir -Force | Out-Null

    if (Test-Path $OpenVpnConfigPath) {
        $BackupPath = "$OpenVpnConfigPath.bak.$RunId"
        Copy-Item -LiteralPath $OpenVpnConfigPath -Destination $BackupPath -Force
        Write-Log "Existing VPN profile backed up to: $BackupPath" 'WARN'
    }

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

    Set-Content -Path $OpenVpnConfigPath -Value $OvpnConfig -Encoding ASCII -Force
    Write-Log "VPN profile written to: $OpenVpnConfigPath" 'SUCCESS'
}

try {
    if (-not (Test-IsAdministrator)) {
        throw 'Please run PowerShell as Administrator.'
    }

    New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    Start-Transcript -Path $TranscriptPath -Append | Out-Null
    Enable-StrongTls

    Write-Log '=== STEP 2: OpenVPN Community ===' 'INFO'
    if (Test-OpenVpnInstalled) {
        Write-Log 'OpenVPN is already installed. Skipping installer.' 'WARN'
    } else {
        Download-FileWithRetry -Url $InstallerUrl -Destination $InstallerPath
        Install-Msi -Path $InstallerPath -LogPath $InstallLog
    }

    Write-Log '=== STEP 3: OpenVPN Profile ===' 'INFO'
    Write-OpenVpnProfile
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

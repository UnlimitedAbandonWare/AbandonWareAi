<#
.SYNOPSIS
Installs a ZeroSSL bundle outside the Git worktree and manages opt-in-only Caddy HTTPS.

.DESCRIPTION
The default mode is install. Installation validates the exact ZeroSSL bundle entries,
certificate domain and lifetime, certificate/private-key pairing through Caddy, and
restricts Windows ACLs to the current user and SYSTEM. Installation never starts Caddy.

HTTPS start requires either -EnableHttps or process environment variable
AWX_OPTIONAL_HTTPS_ENABLED=true. The generated Caddyfile contains no HTTP site or
redirect and sets auto_https off, so existing HTTP remains the default.

.EXAMPLE
pwsh -NoProfile -File scripts\domain_optional_https.ps1 -BundlePath C:\path\certificate.zip

.EXAMPLE
$env:AWX_OPTIONAL_HTTPS_ENABLED = "true"
pwsh -NoProfile -File scripts\domain_optional_https.ps1 -Mode start

.EXAMPLE
pwsh -NoProfile -File scripts\domain_optional_https.ps1 -Mode start -EnableHttps

.EXAMPLE
pwsh -NoProfile -File scripts\domain_optional_https.ps1 -Mode stop
#>
[CmdletBinding()]
param(
    [ValidateSet("install", "validate", "start", "stop", "status")]
    [string]$Mode = "install",
    [string]$BundlePath = "",
    [string]$Domain = "abandonwareai.kro.kr",
    [string]$RuntimeRoot = "",
    [string]$CaddyPath = "",
    [string]$BackendAddress = "127.0.0.1:8080",
    [ValidateRange(1, 65535)]
    [int]$HttpsPort = 443,
    [switch]$EnableHttps,
    [switch]$ReplaceExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:operationStage = "initialization"

function Write-OptionalHttpsResult {
    param([string]$Text)
    Write-Host "[AWX][optional-https] $Text"
}

function Throw-KnownFailure {
    param([string]$Reason)
    throw [InvalidOperationException]::new($Reason)
}

function Test-PathInsideRoot {
    param(
        [string]$Candidate,
        [string]$Root
    )

    $candidateFull = [System.IO.Path]::GetFullPath($Candidate).TrimEnd('\')
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    return $candidateFull.Equals($rootFull, [StringComparison]::OrdinalIgnoreCase) -or
        $candidateFull.StartsWith($rootFull + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Assert-Domain {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or
        $Value.Length -gt 253 -or
        $Value -notmatch '^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$') {
        Throw-KnownFailure "invalid_domain"
    }
}

function Assert-BackendAddress {
    param([string]$Value)

    if ($Value -notmatch '^(?:127\.0\.0\.1|localhost|\[::1\]):(?:[1-9][0-9]{0,4})$') {
        Throw-KnownFailure "backend_must_be_loopback_host_port"
    }
    $portText = $Value.Substring($Value.LastIndexOf(':') + 1)
    $port = 0
    if (-not [int]::TryParse($portText, [ref]$port) -or $port -gt 65535) {
        Throw-KnownFailure "invalid_backend_port"
    }
}

function Resolve-OperatorPaths {
    $projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
    $resolvedRuntimeRoot = $RuntimeRoot
    if ([string]::IsNullOrWhiteSpace($resolvedRuntimeRoot)) {
        $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            $localAppData = [Environment]::GetEnvironmentVariable("LOCALAPPDATA")
        }
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            Throw-KnownFailure "local_app_data_missing"
        }
        $resolvedRuntimeRoot = Join-Path $localAppData "AbandonWareAI\optional-https"
    }
    $resolvedRuntimeRoot = [System.IO.Path]::GetFullPath($resolvedRuntimeRoot)
    if (Test-PathInsideRoot -Candidate $resolvedRuntimeRoot -Root $projectRoot) {
        Throw-KnownFailure "runtime_root_inside_worktree"
    }

    $resolvedCaddyPath = $CaddyPath
    if ([string]::IsNullOrWhiteSpace($resolvedCaddyPath)) {
        $resolvedCaddyPath = Join-Path $projectRoot "var\codex-tools\caddy\caddy.exe"
    }
    $resolvedCaddyPath = [System.IO.Path]::GetFullPath($resolvedCaddyPath)

    $installDirectory = Join-Path $resolvedRuntimeRoot $Domain
    return [ordered]@{
        projectRoot = $projectRoot
        runtimeRoot = $resolvedRuntimeRoot
        installDirectory = $installDirectory
        fullChainPath = Join-Path $installDirectory "fullchain.pem"
        privateKeyPath = Join-Path $installDirectory "private.key"
        configPath = Join-Path $installDirectory "Caddyfile"
        statePath = Join-Path $installDirectory "install-state.json"
        pidPath = Join-Path $installDirectory "caddy.pid"
        logDirectory = Join-Path $installDirectory "logs"
        caddyPath = $resolvedCaddyPath
    }
}

function Invoke-IcaclsQuiet {
    param([string[]]$Arguments)

    $null = & icacls.exe @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        Throw-KnownFailure "acl_restriction_failed"
    }
}

function Set-RestrictedDirectory {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Invoke-IcaclsQuiet -Arguments @(
        $Path,
        "/inheritance:r",
        "/grant:r",
        "*${sid}:(OI)(CI)F",
        "*S-1-5-18:(OI)(CI)F"
    )
}

function Set-RestrictedFile {
    param([string]$Path)

    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Invoke-IcaclsQuiet -Arguments @(
        $Path,
        "/inheritance:r",
        "/grant:r",
        "*${sid}:F",
        "*S-1-5-18:F"
    )
}

function Get-PemCertificateBytes {
    param([string]$PemText)

    $match = [regex]::Match(
        $PemText,
        '-----BEGIN CERTIFICATE-----\s*(?<body>[A-Za-z0-9+/=\r\n]+?)\s*-----END CERTIFICATE-----',
        [Text.RegularExpressions.RegexOptions]::Singleline
    )
    if (-not $match.Success) {
        Throw-KnownFailure "certificate_pem_missing"
    }
    try {
        $base64 = [regex]::Replace($match.Groups['body'].Value, '\s', '')
        $bytes = [Convert]::FromBase64String($base64)
        return ,$bytes
    } catch {
        Throw-KnownFailure "certificate_pem_invalid"
    }
}

function Assert-CaBundle {
    param([string]$PemText)

    $matches = [regex]::Matches(
        $PemText,
        '-----BEGIN CERTIFICATE-----\s*(?<body>[A-Za-z0-9+/=\r\n]+?)\s*-----END CERTIFICATE-----',
        [Text.RegularExpressions.RegexOptions]::Singleline
    )
    if ($matches.Count -lt 1) {
        Throw-KnownFailure "ca_bundle_certificate_missing"
    }
    foreach ($match in $matches) {
        $certificate = $null
        try {
            $base64 = [regex]::Replace($match.Groups['body'].Value, '\s', '')
            $bytes = [Convert]::FromBase64String($base64)
            $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($bytes)
        } catch {
            Throw-KnownFailure "ca_bundle_certificate_invalid"
        } finally {
            if ($null -ne $certificate) {
                $certificate.Dispose()
            }
        }
    }
}

function Read-ZipEntryText {
    param($Entry)

    if ($Entry.Length -le 0 -or $Entry.Length -gt 1048576) {
        Throw-KnownFailure "bundle_entry_size_invalid"
    }
    $stream = $Entry.Open()
    try {
        $reader = [System.IO.StreamReader]::new(
            $stream,
            [System.Text.UTF8Encoding]::new($false, $true),
            $true,
            4096,
            $true
        )
        try {
            $text = $reader.ReadToEnd()
            if ($text.IndexOf([char]0) -ge 0) {
                Throw-KnownFailure "bundle_entry_contains_nul"
            }
            return $text
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Read-CertificateBundle {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Throw-KnownFailure "bundle_missing"
    }
    $resolvedBundle = (Resolve-Path -LiteralPath $Path).Path
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedBundle)
    try {
        $expectedNames = @("certificate.crt", "ca_bundle.crt", "private.key")
        $actualEntries = @($archive.Entries | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Name) })
        if ($actualEntries.Count -ne $expectedNames.Count) {
            Throw-KnownFailure "unexpected_bundle_entries"
        }
        foreach ($entry in $actualEntries) {
            if ($entry.FullName.Contains('/') -or $entry.FullName.Contains('\') -or
                $expectedNames -cnotcontains $entry.FullName) {
                Throw-KnownFailure "unexpected_bundle_entries"
            }
        }

        $values = @{}
        foreach ($name in $expectedNames) {
            $entry = @($actualEntries | Where-Object { $_.FullName -ceq $name })
            if ($entry.Count -ne 1) {
                Throw-KnownFailure "unexpected_bundle_entries"
            }
            $values[$name] = Read-ZipEntryText -Entry $entry[0]
        }
        return $values
    } finally {
        $archive.Dispose()
    }
}

function Assert-LeafCertificate {
    param(
        [string]$CertificatePem,
        [string]$ExpectedDomain
    )

    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
        (Get-PemCertificateBytes -PemText $CertificatePem)
    )
    try {
        $dnsName = $certificate.GetNameInfo(
            [Security.Cryptography.X509Certificates.X509NameType]::DnsName,
            $false
        )
        if (-not $dnsName.Equals($ExpectedDomain, [StringComparison]::OrdinalIgnoreCase)) {
            Throw-KnownFailure "certificate_domain_mismatch"
        }
        $now = [DateTime]::UtcNow
        if ($certificate.NotBefore.ToUniversalTime() -gt $now.AddMinutes(5)) {
            Throw-KnownFailure "certificate_not_yet_valid"
        }
        if ($certificate.NotAfter.ToUniversalTime() -le $now) {
            Throw-KnownFailure "certificate_expired"
        }
        return [ordered]@{
            thumbprint = $certificate.Thumbprint
            subject = $certificate.Subject
            issuer = $certificate.Issuer
            validFromUtc = $certificate.NotBefore.ToUniversalTime().ToString("o")
            validToUtc = $certificate.NotAfter.ToUniversalTime().ToString("o")
        }
    } finally {
        $certificate.Dispose()
    }
}

function Assert-PrivateKeyPem {
    param([string]$PemText)

    if ($PemText -notmatch '-----BEGIN (?:RSA |EC )?PRIVATE KEY-----' -or
        $PemText -notmatch '-----END (?:RSA |EC )?PRIVATE KEY-----') {
        Throw-KnownFailure "private_key_pem_invalid"
    }
}

function Convert-ToCaddyPath {
    param([string]$Path)

    $full = [System.IO.Path]::GetFullPath($Path)
    if ($full.Contains('"')) {
        Throw-KnownFailure "caddy_path_contains_quote"
    }
    return $full.Replace('\', '/')
}

function New-CaddyConfigText {
    param(
        [string]$FullChainPath,
        [string]$PrivateKeyPath
    )

    $certToken = Convert-ToCaddyPath -Path $FullChainPath
    $keyToken = Convert-ToCaddyPath -Path $PrivateKeyPath
    return @"
{
    auto_https off
    admin off
    persist_config off
}

https://${Domain}:${HttpsPort} {
    tls "$certToken" "$keyToken"
    reverse_proxy $BackendAddress
}
"@
}

function Invoke-CaddyValidation {
    param(
        [string]$Executable,
        [string]$ConfigPath
    )

    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
        Throw-KnownFailure "caddy_executable_missing"
    }
    $validationRoot = Split-Path -Parent ([System.IO.Path]::GetFullPath($ConfigPath))
    $validationId = [Guid]::NewGuid().ToString("N")
    $stdoutPath = Join-Path $validationRoot (".caddy-validate-$validationId.out")
    $stderrPath = Join-Path $validationRoot (".caddy-validate-$validationId.err")
    $quotedConfig = '"' + $ConfigPath.Replace('"', '\"') + '"'
    try {
        $process = Start-Process `
            -FilePath $Executable `
            -ArgumentList @("validate", "--config", $quotedConfig, "--adapter", "caddyfile") `
            -WorkingDirectory $validationRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -Wait `
            -PassThru
        if ($process.ExitCode -ne 0) {
            Throw-KnownFailure "caddy_validation_failed"
        }
    } finally {
        foreach ($validationLog in @($stdoutPath, $stderrPath)) {
            if ((Test-Path -LiteralPath $validationLog -PathType Leaf) -and
                (Test-PathInsideRoot -Candidate $validationLog -Root $validationRoot)) {
                Remove-Item -LiteralPath $validationLog -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

function Test-HttpsOptIn {
    if ($EnableHttps) {
        return $true
    }
    $value = [Environment]::GetEnvironmentVariable("AWX_OPTIONAL_HTTPS_ENABLED")
    if ($null -eq $value) {
        return $false
    }
    return $value.Trim().ToLowerInvariant() -in @("true", "1", "yes", "y", "on")
}

function Get-ManagedCaddyProcess {
    param($Paths)

    if (-not (Test-Path -LiteralPath $Paths.pidPath -PathType Leaf)) {
        return $null
    }
    $pidText = (Get-Content -Raw -LiteralPath $Paths.pidPath).Trim()
    $managedPid = 0
    if (-not [int]::TryParse($pidText, [ref]$managedPid) -or $managedPid -le 0) {
        return $null
    }
    $process = Get-Process -Id $managedPid -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return $null
    }
    $details = Get-CimInstance Win32_Process -Filter "ProcessId=$managedPid" -ErrorAction SilentlyContinue
    if ($null -eq $details -or [string]::IsNullOrWhiteSpace($details.ExecutablePath) -or
        [string]::IsNullOrWhiteSpace($details.CommandLine)) {
        return $null
    }
    $expectedExecutable = [System.IO.Path]::GetFullPath($Paths.caddyPath)
    $actualExecutable = [System.IO.Path]::GetFullPath($details.ExecutablePath)
    if (-not $actualExecutable.Equals($expectedExecutable, [StringComparison]::OrdinalIgnoreCase) -or
        $details.CommandLine.IndexOf($Paths.configPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        return $null
    }
    return $process
}

function Get-PortListeners {
    return @(Get-NetTCPConnection -State Listen -LocalPort $HttpsPort -ErrorAction SilentlyContinue)
}

function Install-CertificateBundle {
    param($Paths)

    $script:operationStage = "bundle-read"
    $bundle = Read-CertificateBundle -Path $BundlePath
    $certificatePem = [string]$bundle["certificate.crt"]
    $caBundlePem = [string]$bundle["ca_bundle.crt"]
    $privateKeyPem = [string]$bundle["private.key"]
    $script:operationStage = "certificate-parse"
    $certificate = Assert-LeafCertificate -CertificatePem $certificatePem -ExpectedDomain $Domain
    Assert-CaBundle -PemText $caBundlePem
    Assert-PrivateKeyPem -PemText $privateKeyPem

    $script:operationStage = "acl-prepare"
    Set-RestrictedDirectory -Path $Paths.runtimeRoot
    Set-RestrictedDirectory -Path $Paths.installDirectory

    if ((Test-Path -LiteralPath $Paths.fullChainPath -PathType Leaf) -and -not $ReplaceExisting) {
        $existingCertificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
            (Get-PemCertificateBytes -PemText (Get-Content -Raw -LiteralPath $Paths.fullChainPath))
        )
        try {
            if (-not $existingCertificate.Thumbprint.Equals($certificate.thumbprint, [StringComparison]::OrdinalIgnoreCase)) {
                Throw-KnownFailure "certificate_already_installed_use_replace"
            }
        } finally {
            $existingCertificate.Dispose()
        }
    }

    $suffix = [Guid]::NewGuid().ToString("N")
    $tempFullChain = Join-Path $Paths.installDirectory ("fullchain.$suffix.pem")
    $tempPrivateKey = Join-Path $Paths.installDirectory ("private.$suffix.key")
    $tempConfig = Join-Path $Paths.installDirectory ("Caddyfile.$suffix")
    $tempPaths = @($tempFullChain, $tempPrivateKey, $tempConfig)
    try {
        $script:operationStage = "staging-write"
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        $fullChainText = $certificatePem.Trim() + "`r`n" + $caBundlePem.Trim() + "`r`n"
        $privateKeyText = $privateKeyPem.Trim() + "`r`n"
        [System.IO.File]::WriteAllText($tempFullChain, $fullChainText, $utf8NoBom)
        [System.IO.File]::WriteAllText($tempPrivateKey, $privateKeyText, $utf8NoBom)
        [System.IO.File]::WriteAllText(
            $tempConfig,
            (New-CaddyConfigText -FullChainPath $tempFullChain -PrivateKeyPath $tempPrivateKey),
            $utf8NoBom
        )
        foreach ($tempPath in $tempPaths) {
            Set-RestrictedFile -Path $tempPath
        }

        $script:operationStage = "staging-validate"
        Invoke-CaddyValidation -Executable $Paths.caddyPath -ConfigPath $tempConfig

        $script:operationStage = "finalize-files"
        [System.IO.File]::Copy($tempFullChain, $Paths.fullChainPath, $true)
        [System.IO.File]::Copy($tempPrivateKey, $Paths.privateKeyPath, $true)
        [System.IO.File]::WriteAllText(
            $Paths.configPath,
            (New-CaddyConfigText -FullChainPath $Paths.fullChainPath -PrivateKeyPath $Paths.privateKeyPath),
            $utf8NoBom
        )
        foreach ($finalPath in @($Paths.fullChainPath, $Paths.privateKeyPath, $Paths.configPath)) {
            Set-RestrictedFile -Path $finalPath
        }

        $script:operationStage = "final-validate"
        Invoke-CaddyValidation -Executable $Paths.caddyPath -ConfigPath $Paths.configPath

        $script:operationStage = "state-write"
        $state = [ordered]@{
            schemaVersion = 1
            domain = $Domain
            installedAtUtc = [DateTime]::UtcNow.ToString("o")
            certificateThumbprint = $certificate.thumbprint
            certificateSubject = $certificate.subject
            certificateIssuer = $certificate.issuer
            validFromUtc = $certificate.validFromUtc
            validToUtc = $certificate.validToUtc
            httpsDefault = $false
            automaticHttps = $false
            redirect = $false
            httpsPort = $HttpsPort
            backendAddress = $BackendAddress
        }
        [System.IO.File]::WriteAllText(
            $Paths.statePath,
            ($state | ConvertTo-Json -Depth 4),
            $utf8NoBom
        )
        Set-RestrictedFile -Path $Paths.statePath

        $script:operationStage = "complete"
        Write-OptionalHttpsResult "installed=true domain=$Domain validToUtc=$($certificate.validToUtc) keyMatch=true caddyValidated=true httpsDefault=false enabled=false redirect=false automaticHttps=false"
    } finally {
        foreach ($tempPath in $tempPaths) {
            if ((Test-Path -LiteralPath $tempPath -PathType Leaf) -and
                (Test-PathInsideRoot -Candidate $tempPath -Root $Paths.installDirectory)) {
                Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
            }
        }
        $privateKeyPem = $null
        $privateKeyText = $null
    }
}

function Validate-InstalledConfiguration {
    param($Paths)

    foreach ($requiredPath in @($Paths.fullChainPath, $Paths.privateKeyPath, $Paths.configPath, $Paths.statePath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            Throw-KnownFailure "certificate_installation_incomplete"
        }
    }
    Invoke-CaddyValidation -Executable $Paths.caddyPath -ConfigPath $Paths.configPath
    Write-OptionalHttpsResult "installed=true validated=true httpsDefault=false redirect=false automaticHttps=false enabled=false"
}

function Show-OptionalHttpsStatus {
    param($Paths)

    $script:operationStage = "status"
    $installed = (Test-Path -LiteralPath $Paths.fullChainPath -PathType Leaf) -and
        (Test-Path -LiteralPath $Paths.privateKeyPath -PathType Leaf) -and
        (Test-Path -LiteralPath $Paths.configPath -PathType Leaf)
    $process = Get-ManagedCaddyProcess -Paths $Paths
    $listeners = @(Get-PortListeners)
    $listenerState = "free"
    $enabled = $false
    if ($null -ne $process -and @($listeners | Where-Object { $_.OwningProcess -eq $process.Id }).Count -gt 0) {
        $listenerState = "managed"
        $enabled = $true
    } elseif ($listeners.Count -gt 0) {
        $listenerState = "other"
    }
    Write-OptionalHttpsResult "installed=$($installed.ToString().ToLowerInvariant()) enabled=$($enabled.ToString().ToLowerInvariant()) listener=$listenerState port=$HttpsPort httpsDefault=false redirect=false automaticHttps=false"
}

function Start-OptionalHttps {
    param($Paths)

    $script:operationStage = "start-opt-in"
    if (-not (Test-HttpsOptIn)) {
        Write-OptionalHttpsResult "started=false classification=explicit-opt-in-required env=AWX_OPTIONAL_HTTPS_ENABLED switch=EnableHttps httpsDefault=false redirect=false"
        exit 2
    }
    foreach ($requiredPath in @($Paths.fullChainPath, $Paths.privateKeyPath, $Paths.configPath, $Paths.statePath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            Throw-KnownFailure "certificate_installation_incomplete"
        }
    }
    Invoke-CaddyValidation -Executable $Paths.caddyPath -ConfigPath $Paths.configPath

    $existingProcess = Get-ManagedCaddyProcess -Paths $Paths
    if ($null -ne $existingProcess) {
        Write-OptionalHttpsResult "started=true reused=true pid=$($existingProcess.Id) port=$HttpsPort redirect=false automaticHttps=false"
        return
    }
    if (@(Get-PortListeners).Count -gt 0) {
        Throw-KnownFailure "https_port_conflict"
    }

    $quotedConfig = '"' + $Paths.configPath.Replace('"', '\"') + '"'
    $process = Start-Process `
        -FilePath $Paths.caddyPath `
        -ArgumentList @("run", "--config", $quotedConfig, "--adapter", "caddyfile") `
        -WorkingDirectory $Paths.installDirectory `
        -WindowStyle Hidden `
        -PassThru
    [System.IO.File]::WriteAllText($Paths.pidPath, [string]$process.Id, [System.Text.Encoding]::ASCII)
    Set-RestrictedFile -Path $Paths.pidPath

    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 250
        $process.Refresh()
        if ($process.HasExited) {
            break
        }
        if (@((Get-PortListeners) | Where-Object { $_.OwningProcess -eq $process.Id }).Count -gt 0) {
            $ready = $true
            break
        }
    }
    if (-not $ready) {
        if (-not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        Throw-KnownFailure "caddy_start_failed"
    }
    Write-OptionalHttpsResult "started=true reused=false pid=$($process.Id) port=$HttpsPort redirect=false automaticHttps=false"
}

function Stop-OptionalHttps {
    param($Paths)

    $process = Get-ManagedCaddyProcess -Paths $Paths
    if ($null -eq $process) {
        if (Test-Path -LiteralPath $Paths.pidPath -PathType Leaf) {
            Remove-Item -LiteralPath $Paths.pidPath -Force -ErrorAction SilentlyContinue
        }
        Write-OptionalHttpsResult "stopped=true alreadyStopped=true port=$HttpsPort"
        return
    }
    Stop-Process -Id $process.Id -Force
    $process.WaitForExit(5000) | Out-Null
    if (Test-Path -LiteralPath $Paths.pidPath -PathType Leaf) {
        Remove-Item -LiteralPath $Paths.pidPath -Force -ErrorAction SilentlyContinue
    }
    Write-OptionalHttpsResult "stopped=true alreadyStopped=false pid=$($process.Id) port=$HttpsPort"
}

$knownReasons = @(
    "invalid_domain",
    "backend_must_be_loopback_host_port",
    "invalid_backend_port",
    "local_app_data_missing",
    "runtime_root_inside_worktree",
    "acl_restriction_failed",
    "certificate_pem_missing",
    "certificate_pem_invalid",
    "ca_bundle_certificate_missing",
    "ca_bundle_certificate_invalid",
    "bundle_entry_size_invalid",
    "bundle_entry_contains_nul",
    "bundle_missing",
    "unexpected_bundle_entries",
    "certificate_domain_mismatch",
    "certificate_not_yet_valid",
    "certificate_expired",
    "private_key_pem_invalid",
    "caddy_path_contains_quote",
    "caddy_executable_missing",
    "caddy_validation_failed",
    "certificate_already_installed_use_replace",
    "certificate_installation_incomplete",
    "https_port_conflict",
    "caddy_start_failed"
)

try {
    Assert-Domain -Value $Domain
    Assert-BackendAddress -Value $BackendAddress
    $paths = Resolve-OperatorPaths

    switch ($Mode) {
        "install" {
            Install-CertificateBundle -Paths $paths
        }
        "validate" {
            Validate-InstalledConfiguration -Paths $paths
        }
        "start" {
            Start-OptionalHttps -Paths $paths
        }
        "stop" {
            Stop-OptionalHttps -Paths $paths
        }
        "status" {
            Show-OptionalHttpsStatus -Paths $paths
        }
    }
    exit 0
} catch {
    $message = $_.Exception.Message
    $reason = if ($knownReasons -contains $message) { $message } else { $_.Exception.GetType().Name }
    $innerType = if ($null -ne $_.Exception.InnerException) { $_.Exception.InnerException.GetType().Name } else { "none" }
    Write-OptionalHttpsResult "success=false mode=$Mode stage=$script:operationStage classification=$reason innerType=$innerType httpsDefault=false redirect=false automaticHttps=false"
    exit 1
}

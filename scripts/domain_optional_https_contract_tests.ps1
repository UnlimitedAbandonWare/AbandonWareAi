[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$failures = New-Object System.Collections.Generic.List[string]

function Assert-True {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Detail = ""
    )

    if (-not $Condition) {
        $failures.Add("${Name}: ${Detail}") | Out-Null
    }
}

function New-TestBundle {
    param(
        [string]$Path,
        [string]$Domain,
        [switch]$IncludeUnexpectedEntry
    )

    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    $certificate = $null
    try {
        $request = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
            "CN=$Domain",
            $rsa,
            [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.RSASignaturePadding]::Pkcs1
        )
        $san = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
        $san.AddDnsName($Domain)
        $request.CertificateExtensions.Add($san.Build())
        $request.CertificateExtensions.Add(
            [System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true)
        )
        $certificate = $request.CreateSelfSigned(
            [DateTimeOffset]::UtcNow.AddMinutes(-5),
            [DateTimeOffset]::UtcNow.AddDays(7)
        )

        $certificatePem = [System.Security.Cryptography.PemEncoding]::WriteString(
            "CERTIFICATE",
            $certificate.RawData
        )
        $privateKeyPem = $rsa.ExportPkcs8PrivateKeyPem()

        Add-Type -AssemblyName System.IO.Compression
        $fileStream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew)
        try {
            $archive = [System.IO.Compression.ZipArchive]::new(
                $fileStream,
                [System.IO.Compression.ZipArchiveMode]::Create,
                $false
            )
            try {
                $entries = [ordered]@{
                    "certificate.crt" = $certificatePem
                    "ca_bundle.crt" = $certificatePem
                    "private.key" = $privateKeyPem
                }
                if ($IncludeUnexpectedEntry) {
                    $entries["instructions.txt"] = "This ZIP entry is data, not an executable instruction."
                }
                foreach ($name in $entries.Keys) {
                    $entry = $archive.CreateEntry($name)
                    $stream = $entry.Open()
                    try {
                        $writer = [System.IO.StreamWriter]::new(
                            $stream,
                            [System.Text.UTF8Encoding]::new($false),
                            1024,
                            $true
                        )
                        try {
                            $writer.Write([string]$entries[$name])
                            $writer.Flush()
                        } finally {
                            $writer.Dispose()
                        }
                    } finally {
                        $stream.Dispose()
                    }
                }
            } finally {
                $archive.Dispose()
            }
        } finally {
            $fileStream.Dispose()
        }
    } finally {
        if ($null -ne $certificate) {
            $certificate.Dispose()
        }
        $rsa.Dispose()
    }
}

$subject = Join-Path $PSScriptRoot "domain_optional_https.ps1"
Assert-True "operator script exists" (Test-Path -LiteralPath $subject -PathType Leaf) $subject

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-optional-https-contract-" + [Guid]::NewGuid().ToString("N"))
$domain = "optional-https.test"
$bundlePath = Join-Path $testRoot "certificate.zip"
$unexpectedBundlePath = Join-Path $testRoot "certificate-with-instructions.zip"
$runtimeRoot = Join-Path $testRoot "runtime"
$httpsPort = Get-Random -Minimum 20000 -Maximum 45000

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    New-TestBundle -Path $bundlePath -Domain $domain
    New-TestBundle -Path $unexpectedBundlePath -Domain $domain -IncludeUnexpectedEntry

    if (Test-Path -LiteralPath $subject -PathType Leaf) {
        $insideWorktreeRoot = Join-Path $PSScriptRoot ("should-not-install-" + [Guid]::NewGuid().ToString("N"))
        $insideOutput = @(& pwsh -NoProfile -File $subject `
            -BundlePath $bundlePath `
            -Domain $domain `
            -RuntimeRoot $insideWorktreeRoot `
            -HttpsPort $httpsPort 2>&1)
        $insideExit = $LASTEXITCODE
        $insideText = $insideOutput -join "`n"
        Assert-True "runtime path inside worktree is rejected" ($insideExit -eq 1) "exit=$insideExit output=$insideText"
        Assert-True "worktree rejection is classified" ($insideText.Contains('runtime_root_inside_worktree')) $insideText
        Assert-True "worktree rejection writes no runtime directory" (-not (Test-Path -LiteralPath $insideWorktreeRoot)) $insideWorktreeRoot

        $unexpectedRuntimeRoot = Join-Path $testRoot "unexpected-runtime"
        $unexpectedOutput = @(& pwsh -NoProfile -File $subject `
            -BundlePath $unexpectedBundlePath `
            -Domain $domain `
            -RuntimeRoot $unexpectedRuntimeRoot `
            -HttpsPort $httpsPort 2>&1)
        $unexpectedExit = $LASTEXITCODE
        $unexpectedText = $unexpectedOutput -join "`n"
        Assert-True "unexpected ZIP instruction entry is rejected as data" ($unexpectedExit -eq 1) "exit=$unexpectedExit output=$unexpectedText"
        Assert-True "unexpected ZIP entry is classified" ($unexpectedText.Contains('unexpected_bundle_entries')) $unexpectedText
        Assert-True "unexpected ZIP entry writes no certificate" (
            -not (Test-Path -LiteralPath (Join-Path $unexpectedRuntimeRoot "$domain\private.key") -PathType Leaf)
        ) $unexpectedRuntimeRoot

        $beforeListener = Get-NetTCPConnection -State Listen -LocalPort $httpsPort -ErrorAction SilentlyContinue
        Assert-True "test HTTPS port starts free" ($null -eq $beforeListener) "port=$httpsPort"

        $installOutput = @(& pwsh -NoProfile -File $subject `
            -BundlePath $bundlePath `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort 2>&1)
        $installExit = $LASTEXITCODE
        $installText = $installOutput -join "`n"

        Assert-True "install mode succeeds" ($installExit -eq 0) "exit=$installExit output=$installText"
        Assert-True "install reports HTTPS disabled" ($installText.Contains('httpsDefault=false')) $installText
        Assert-True "install reports redirects disabled" ($installText.Contains('redirect=false')) $installText
        Assert-True "install output contains no PEM private key" (-not $installText.Contains('BEGIN PRIVATE KEY')) "private key leaked"

        $installDir = Join-Path $runtimeRoot $domain
        $configPath = Join-Path $installDir "Caddyfile"
        Assert-True "full chain is installed" (Test-Path -LiteralPath (Join-Path $installDir "fullchain.pem") -PathType Leaf) $installDir
        Assert-True "private key is installed" (Test-Path -LiteralPath (Join-Path $installDir "private.key") -PathType Leaf) $installDir
        Assert-True "Caddyfile is installed" (Test-Path -LiteralPath $configPath -PathType Leaf) $configPath

        if (Test-Path -LiteralPath $configPath -PathType Leaf) {
            $config = Get-Content -Raw -LiteralPath $configPath
            Assert-True "installed Caddyfile disables automatic HTTPS" ($config.Contains('auto_https off')) $config
            Assert-True "installed Caddyfile has no redirect" (-not [regex]::IsMatch($config, '(?m)^\s*redir\s')) $config
        Assert-True "installed Caddyfile has no HTTP listener" (-not $config.Contains('http://')) $config
            Assert-True "installed Caddyfile disables the admin endpoint" ($config.Contains('admin off')) $config
        }

        $windowsPowerShell = Get-Command powershell.exe -ErrorAction SilentlyContinue
        if ($null -ne $windowsPowerShell) {
            $ps51ValidateOutput = @(& $windowsPowerShell.Source `
                -NoProfile `
                -ExecutionPolicy Bypass `
                -File $subject `
                -Mode validate `
                -Domain $domain `
                -RuntimeRoot $runtimeRoot `
                -HttpsPort $httpsPort 2>&1)
            $ps51ValidateExit = $LASTEXITCODE
            Assert-True "Windows PowerShell 5.1 validates the installed Caddy config" (
                $ps51ValidateExit -eq 0
            ) "exit=$ps51ValidateExit output=$($ps51ValidateOutput -join "`n")"
        }

        $afterInstallListener = Get-NetTCPConnection -State Listen -LocalPort $httpsPort -ErrorAction SilentlyContinue
        Assert-True "install does not open HTTPS port" ($null -eq $afterInstallListener) "port=$httpsPort"

        $statusOutput = @(& pwsh -NoProfile -File $subject `
            -Mode status `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort 2>&1)
        $statusExit = $LASTEXITCODE
        $statusText = $statusOutput -join "`n"
        Assert-True "status succeeds with no HTTPS listener" ($statusExit -eq 0) "exit=$statusExit output=$statusText"
        Assert-True "status reports HTTPS disabled" ($statusText.Contains('enabled=false')) $statusText
        Assert-True "status reports free HTTPS port" ($statusText.Contains('listener=free')) $statusText

        $startOutput = @(& pwsh -NoProfile -File $subject `
            -Mode start `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort 2>&1)
        $startExit = $LASTEXITCODE
        $startText = $startOutput -join "`n"
        Assert-True "start without opt-in is refused" ($startExit -eq 2) "exit=$startExit output=$startText"
        Assert-True "start refusal names explicit opt-in" ($startText.Contains('explicit-opt-in-required')) $startText

        $afterStartListener = Get-NetTCPConnection -State Listen -LocalPort $httpsPort -ErrorAction SilentlyContinue
        Assert-True "refused start does not open HTTPS port" ($null -eq $afterStartListener) "port=$httpsPort"

        $enabledStartOutput = @(& pwsh -NoProfile -File $subject `
            -Mode start `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort `
            -EnableHttps 2>&1)
        $enabledStartExit = $LASTEXITCODE
        $enabledStartText = $enabledStartOutput -join "`n"
        Assert-True "explicit opt-in starts HTTPS" ($enabledStartExit -eq 0) "exit=$enabledStartExit output=$enabledStartText"

        $enabledStatusOutput = @(& pwsh -NoProfile -File $subject `
            -Mode status `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort 2>&1)
        $enabledStatusExit = $LASTEXITCODE
        $enabledStatusText = $enabledStatusOutput -join "`n"
        Assert-True "enabled status succeeds" ($enabledStatusExit -eq 0) "exit=$enabledStatusExit output=$enabledStatusText"
        Assert-True "enabled status reports managed listener" (
            $enabledStatusText.Contains('enabled=true') -and $enabledStatusText.Contains('listener=managed')
        ) $enabledStatusText

        $stopOutput = @(& pwsh -NoProfile -File $subject `
            -Mode stop `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort 2>&1)
        $stopExit = $LASTEXITCODE
        $stopText = $stopOutput -join "`n"
        Assert-True "managed HTTPS stops cleanly" ($stopExit -eq 0) "exit=$stopExit output=$stopText"
        Start-Sleep -Milliseconds 300
        $afterStopListener = Get-NetTCPConnection -State Listen -LocalPort $httpsPort -ErrorAction SilentlyContinue
        Assert-True "stopped HTTPS releases its port" ($null -eq $afterStopListener) "port=$httpsPort"

        $previousOptIn = [Environment]::GetEnvironmentVariable("AWX_OPTIONAL_HTTPS_ENABLED", "Process")
        try {
            $env:AWX_OPTIONAL_HTTPS_ENABLED = "true"
            $environmentStartOutput = @(& pwsh -NoProfile -File $subject `
                -Mode start `
                -Domain $domain `
                -RuntimeRoot $runtimeRoot `
                -HttpsPort $httpsPort 2>&1)
            $environmentStartExit = $LASTEXITCODE
            $environmentStartText = $environmentStartOutput -join "`n"
            Assert-True "environment opt-in starts HTTPS" ($environmentStartExit -eq 0) "exit=$environmentStartExit output=$environmentStartText"

            $environmentStopOutput = @(& pwsh -NoProfile -File $subject `
                -Mode stop `
                -Domain $domain `
                -RuntimeRoot $runtimeRoot `
                -HttpsPort $httpsPort 2>&1)
            $environmentStopExit = $LASTEXITCODE
            Assert-True "environment-started HTTPS stops cleanly" ($environmentStopExit -eq 0) ($environmentStopOutput -join "`n")
        } finally {
            [Environment]::SetEnvironmentVariable("AWX_OPTIONAL_HTTPS_ENABLED", $previousOptIn, "Process")
        }
        Start-Sleep -Milliseconds 300
        $afterEnvironmentStop = Get-NetTCPConnection -State Listen -LocalPort $httpsPort -ErrorAction SilentlyContinue
        Assert-True "environment-started HTTPS releases its port" ($null -eq $afterEnvironmentStop) "port=$httpsPort"
    }
} finally {
    if ((Test-Path -LiteralPath $subject -PathType Leaf) -and (Test-Path -LiteralPath $runtimeRoot -PathType Container)) {
        $null = & pwsh -NoProfile -File $subject `
            -Mode stop `
            -Domain $domain `
            -RuntimeRoot $runtimeRoot `
            -HttpsPort $httpsPort 2>&1
    }
    $resolvedTemp = [System.IO.Path]::GetFullPath($testRoot)
    $expectedParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedTemp.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTemp).StartsWith('awx-optional-https-contract-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Host "[optional-https-contract][FAIL] $failure"
    }
    Write-Host "[optional-https-contract][SUMMARY] failed=$($failures.Count)"
    exit 1
}

Write-Host "[optional-https-contract][SUMMARY] failed=0"
exit 0

[CmdletBinding()]
param(
    [string]$Domain = "abandonwareai.kro.kr",
    [string]$JarPath = "",
    [int]$HttpsPort = 443,
    [int]$HttpPort = 80,
    [int]$BackendPort = 8080,
    [int]$ManagementPort = 18081,
    [int]$NettyPort = 18082,
    [ValidateSet("embedded", "offload")]
    [string]$TlsMode = "embedded",
    [int]$StartupTimeoutSec = 120,
    [string]$LogDir = "",
    [switch]$NoBrowserProbe,
    [switch]$StopAfterReady
)

$ErrorActionPreference = "Stop"

function Env-Value {
    param([string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($null -eq $value) {
        return ""
    }
    return $value.Trim()
}

function Env-Present {
    param([string]$Name)
    return -not [string]::IsNullOrWhiteSpace((Env-Value $Name))
}

function Stop-StartedProcess {
    param($Process)
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force
        Start-Sleep -Seconds 2
        Write-Host "[AWX][domain-start] stopped pid=$($Process.Id)"
    }
}

function Test-PortFree {
    param([int]$Port)
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return $null -eq $listener
}

function Test-HttpsRedirectTarget {
    param(
        [string]$Location,
        [string]$ExpectedHost,
        [int]$ExpectedPort
    )
    try {
        $target = [Uri]::new($Location, [UriKind]::Absolute)
        $actualHost = $target.IdnHost.TrimEnd('.')
        $wantedHost = $ExpectedHost.Trim().TrimEnd('.')
        $portMatches = $target.IsDefaultPort -or $target.Port -eq $ExpectedPort
        return $target.Scheme -ieq "https" -and $actualHost -ieq $wantedHost -and $portMatches
    } catch {
        return $false
    }
}

function Resolve-Jar {
    param(
        [string]$ProjectRoot,
        [string]$RequestedJar
    )
    if (-not [string]::IsNullOrWhiteSpace($RequestedJar)) {
        $resolved = Resolve-Path -LiteralPath $RequestedJar -ErrorAction Stop
        return $resolved.Path
    }
    $jar = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "build\libs") -Filter "*.jar" -File |
        Sort-Object LastWriteTime |
        Select-Object -Last 1
    if ($null -eq $jar) {
        throw "jar_missing"
    }
    return $jar.FullName
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($LogDir)) {
    $LogDir = Join-Path $root "verification\domain-runtime"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$embeddedSslRequired = $TlsMode -eq "embedded"
$keyStoreLocation = ""
$keyStoreType = "PKCS12"
$keyAlias = ""
if ($embeddedSslRequired) {
    $sslEnabled = (Env-Value "SERVER_SSL_ENABLED").ToLowerInvariant() -in @("true", "1", "yes", "y")
    $requiredEnv = @(
        "SERVER_SSL_ENABLED",
        "SERVER_SSL_KEY_STORE",
        "SERVER_SSL_KEY_STORE_PASSWORD"
    )
    $missing = @($requiredEnv | Where-Object { -not (Env-Present $_) })
    if (-not $sslEnabled -or $missing.Count -gt 0) {
        Write-Host "[AWX][domain-start] ready=false classification=secret-required missing=$($missing -join ',') sslEnabled=$sslEnabled tlsMode=$TlsMode"
        Write-Host "[AWX][domain-start] evidence_needed=SERVER_SSL_ENABLED,SERVER_SSL_KEY_STORE,SERVER_SSL_KEY_STORE_PASSWORD"
        exit 2
    }

    $keyStore = Env-Value "SERVER_SSL_KEY_STORE"
    if (-not (Test-Path -LiteralPath $keyStore -PathType Leaf)) {
        Write-Host "[AWX][domain-start] ready=false classification=ssl-risk evidence_needed=ssl.keystore.file tlsMode=$TlsMode"
        exit 2
    }
    $resolvedKeyStorePath = (Resolve-Path -LiteralPath $keyStore -ErrorAction Stop).Path
    $keyStoreLocation = ([System.Uri]::new($resolvedKeyStorePath)).AbsoluteUri
    $keyStoreType = Env-Value "SERVER_SSL_KEY_STORE_TYPE"
    if ([string]::IsNullOrWhiteSpace($keyStoreType)) {
        $keyStoreType = "PKCS12"
    }
    $keyAlias = Env-Value "SERVER_SSL_KEY_ALIAS"
    Write-Host "[AWX][domain-start] keyPasswordOptional=true keyPasswordPresent=$(Env-Present 'SERVER_SSL_KEY_PASSWORD')"
}

$serverPort = if ($embeddedSslRequired) { $HttpsPort } else { $BackendPort }
if ($ManagementPort -gt 0 -and $NettyPort -gt 0 -and $ManagementPort -eq $NettyPort) {
    Write-Host "[AWX][domain-start] ready=false classification=port-conflict reason=management-netty-overlap port=$ManagementPort tlsMode=$TlsMode"
    exit 2
}
$portsToCheck = if ($embeddedSslRequired) {
    @($HttpPort, $HttpsPort, $ManagementPort, $NettyPort)
} else {
    @($BackendPort, $ManagementPort, $NettyPort)
}
foreach ($port in @((@($portsToCheck) | Where-Object { $_ -gt 0 }) | Sort-Object -Unique)) {
    if (-not (Test-PortFree -Port $port)) {
        Write-Host "[AWX][domain-start] ready=false classification=port-conflict port=$port tlsMode=$TlsMode"
        exit 2
    }
}

$jar = Resolve-Jar -ProjectRoot $root -RequestedJar $JarPath
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdout = Join-Path $LogDir "domain-public-https-$stamp.out.log"
$stderr = Join-Path $LogDir "domain-public-https-$stamp.err.log"
$pidFile = Join-Path $LogDir "domain-public-https.latest.pid"
$forwardHeadersStrategy = if ($embeddedSslRequired) { "none" } else { "framework" }

$args = @(
    "-jar", $jar,
    "--spring.profiles.active=prod",
    "--server.port=$serverPort",
    "--server.https-port=$HttpsPort",
    "--server.http-port=$HttpPort",
    "--management.server.port=$ManagementPort",
    "--management.server.address=127.0.0.1",
    "--management.server.ssl.enabled=false",
    "--netty.port=$NettyPort",
    "--server.forward-headers-strategy=$forwardHeadersStrategy",
    "--security.force-https=true",
    "--lms.cors.allowed-origins=https://$Domain",
    "--app.public-base-url=https://$Domain",
    "--probe.search.enabled=false"
)

if ($embeddedSslRequired) {
    $args += "--server.ssl.enabled=true"
    $args += "--security.tls-offload.enabled=false"
    $args += "--server.ssl.key-store=$keyStoreLocation"
    $args += "--server.ssl.key-store-type=$keyStoreType"
    if (-not [string]::IsNullOrWhiteSpace($keyAlias)) {
        $args += "--server.ssl.key-alias=$keyAlias"
    }
} else {
    $args += "--server.ssl.enabled=false"
    $args += "--server.address=127.0.0.1"
    $args += "--security.tls-offload.enabled=true"
}

$process = $null
try {
    Write-Host "[AWX][domain-start] launchArgs=$($args -join ' ') tlsMode=$TlsMode backendPort=$BackendPort"
    $startProcessSplat = @{
        FilePath = "java"
        ArgumentList = $args
        PassThru = $true
        WindowStyle = "Hidden"
    }
    $deployLogMode = "detached"
    if ($StopAfterReady) {
        $startProcessSplat.RedirectStandardOutput = $stdout
        $startProcessSplat.RedirectStandardError = $stderr
        $deployLogMode = "redirected"
    } else {
        Set-Content -LiteralPath $stdout -Value "[AWX][domain-start] deployLogMode=detached java stdout not captured by launcher" -Encoding ASCII
        Set-Content -LiteralPath $stderr -Value "[AWX][domain-start] deployLogMode=detached java stderr not captured by launcher" -Encoding ASCII
    }
    $process = Start-Process @startProcessSplat
    Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ASCII
    Write-Host "[AWX][domain-start] started pid=$($process.Id) jar=$(Split-Path -Leaf $jar) stdout=$stdout stderr=$stderr deployLogMode=$deployLogMode tlsMode=$TlsMode"

    $healthScheme = if ($embeddedSslRequired) { "https" } else { "http" }
    $healthUrl = "${healthScheme}://127.0.0.1:$serverPort/"
    $healthy = $false
    for ($i = 0; $i -lt $StartupTimeoutSec; $i++) {
        Start-Sleep -Seconds 1
        $process.Refresh()
        if ($process.HasExited) {
            Write-Host "[AWX][domain-start] exited code=$($process.ExitCode)"
            break
        }
        $code = ""
        try {
            $healthCurlArgs = @("-s", "--connect-timeout", "2", "--max-time", "4", "-o", "NUL", "-w", "%{http_code}")
            if ($embeddedSslRequired) {
                $healthCurlArgs = @("-k") + $healthCurlArgs
            } else {
                $healthCurlArgs += @("-H", "X-Forwarded-Proto: https", "-H", "X-Forwarded-Host: $Domain")
            }
            $healthCurlArgs += $healthUrl
            $code = curl.exe @healthCurlArgs
        } catch {
            $code = ""
        }
        if ($code -in @("200", "301", "302", "307", "308", "401", "403")) {
            $healthy = $true
            break
        }
    }

    if (-not $healthy) {
        Write-Host "[AWX][domain-start] ready=false classification=spring-bind evidence_needed=management.health tlsMode=$TlsMode"
        Stop-StartedProcess -Process $process
        exit 3
    }

    Write-Host "[AWX][domain-start] health=ok urlScheme=$healthScheme serverPort=$serverPort publicHttpsPort=$HttpsPort managementPort=$ManagementPort tlsMode=$TlsMode"
    if (-not $NoBrowserProbe) {
        $httpProbe = curl.exe -s --connect-timeout 3 --max-time 8 -o NUL -w "%{http_code} %{redirect_url}" "http://$Domain/"
        $httpParts = @($httpProbe -split " ", 2)
        $httpCode = if ($httpParts.Count -gt 0) { $httpParts[0] } else { "" }
        $httpRedirectUrl = if ($httpParts.Count -gt 1) { $httpParts[1].Trim() } else { "" }
        $httpsCode = curl.exe -s --connect-timeout 3 --max-time 8 -o NUL -w "%{http_code}" "https://$Domain/"
        $httpRedirectCode = $httpCode -in @("301", "302", "307", "308")
        $httpRedirectOk = Test-HttpsRedirectTarget -Location $httpRedirectUrl -ExpectedHost $Domain -ExpectedPort $HttpsPort
        $httpOk = $httpRedirectCode -and $httpRedirectOk
        Write-Host "[AWX][domain-start] publicProbe http=$httpCode https=$httpsCode httpRedirectOk=$httpRedirectOk"
        if (-not $httpOk -or $httpsCode -ne "200") {
            Write-Host "[AWX][domain-start] ready=false classification=public-https-response evidence_needed=trusted-public-domain-response tlsMode=$TlsMode appScheme=$healthScheme httpCode=$httpCode httpsCode=$httpsCode httpRedirectOk=$httpRedirectOk"
            Write-Host "[AWX][domain-start] publicProbe.cleanup=stopping pid=$($process.Id)"
            Stop-StartedProcess -Process $process
            exit 4
        }
    } else {
        Write-Host "[AWX][domain-start] ready=local-only publicEdgeVerified=false evidence_needed=trusted-public-domain-response pid=$($process.Id)"
        if ($StopAfterReady) {
            Stop-StartedProcess -Process $process
        }
        exit 0
    }

    Write-Host "[AWX][domain-start] ready=true publicEdgeVerified=true pid=$($process.Id)"
    if ($StopAfterReady) {
        Stop-StartedProcess -Process $process
    }
    exit 0
} catch {
    Write-Host "[AWX][domain-start] ready=false classification=other reason=$($_.Exception.GetType().Name)"
    Stop-StartedProcess -Process $process
    exit 1
}

[CmdletBinding()]
param(
    [ValidateSet("snapshot", "memory", "ledger", "strategy")]
    [string]$Endpoint = "snapshot",

    [string]$BaseUrl = $env:AWX_AGENT_DB_CONTEXT_BASE_URL,

    [int]$TimeoutSec = 10,

    [switch]$RequireDbEnv,

    [switch]$RequireRuntime,

    [switch]$SkipTcpProbe,

    [switch]$SkipHttpProbe
)

$ErrorActionPreference = "Stop"

function Test-EnvPresent {
    param([Parameter(Mandatory = $true)][string]$Name)
    return -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))
}

function Get-JdbcEndpointSummary {
    param([string]$JdbcUrl)
    if ([string]::IsNullOrWhiteSpace($JdbcUrl)) {
        return [ordered]@{
            UrlPresent = $false
            Host = "127.0.0.1"
            Port = 3306
            Source = "default-local"
        }
    }

    $match = [regex]::Match($JdbcUrl, '^jdbc:(?:mariadb|mysql)://(?<host>[^/:?]+)(?::(?<port>\d+))?')
    if (-not $match.Success) {
        return [ordered]@{
            UrlPresent = $true
            Host = "unparsed"
            Port = 3306
            Source = "unparsed"
        }
    }

    $hostName = $match.Groups["host"].Value
    if ($hostName -notmatch '^[A-Za-z0-9_.-]{1,180}$') {
        $hostName = "redacted"
    }
    $port = 3306
    if ($match.Groups["port"].Success) {
        $port = [int]$match.Groups["port"].Value
    }

    return [ordered]@{
        UrlPresent = $true
        Host = $hostName
        Port = $port
        Source = "jdbc-url"
    }
}

function Test-TcpConnect {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMs = 1500
    )
    if ($HostName -in @("unparsed", "redacted")) {
        return [ordered]@{ Ok = $false; Reason = "endpoint-unparsed" }
    }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if (-not $connected) {
            return [ordered]@{ Ok = $false; Reason = "timeout" }
        }
        $client.EndConnect($async)
        return [ordered]@{ Ok = $true; Reason = "connect-ok" }
    } catch {
        return [ordered]@{ Ok = $false; Reason = $_.Exception.GetType().Name }
    } finally {
        $client.Close()
    }
}

function Get-SafeReasonToken {
    param([object]$Value)
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return ""
    }
    if ($text -match '^[A-Za-z0-9_.-]{1,80}$') {
        return $text
    }
    return "redacted"
}

if ([string]::IsNullOrWhiteSpace($BaseUrl) -and -not [string]::IsNullOrWhiteSpace($env:APP_PUBLIC_BASE_URL)) {
    $BaseUrl = $env:APP_PUBLIC_BASE_URL
} elseif ([string]::IsNullOrWhiteSpace($BaseUrl) -and -not [string]::IsNullOrWhiteSpace($env:PUBLIC_BASE_URL)) {
    $BaseUrl = $env:PUBLIC_BASE_URL
} elseif ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = "http://127.0.0.1:8080"
}

$requiredDbEnv = @("LMS_DB_URL", "LMS_DB_USERNAME", "LMS_DB_PASSWORD")
$optionalEnv = @(
    "LMS_DB_DRIVER",
    "LMS_DB_DIALECT",
    "LMS_DB_HIKARI_CONNECTION_TIMEOUT",
    "AGENT_DB_CONTEXT_QUERY_TIMEOUT_SECONDS",
    "AWX_ADMIN_TOKEN",
    "DOMAIN_ALLOWLIST_ADMIN_TOKEN"
)

$missingRequired = @()
foreach ($name in $requiredDbEnv) {
    $present = Test-EnvPresent $name
    if (-not $present) {
        $missingRequired += $name
    }
    Write-Host "[AWX][agent-db-context][env] $name.present=$present"
}
foreach ($name in $optionalEnv) {
    Write-Host "[AWX][agent-db-context][env] $name.present=$(Test-EnvPresent $name)"
}

$summary = Get-JdbcEndpointSummary ([Environment]::GetEnvironmentVariable("LMS_DB_URL"))
Write-Host "[AWX][agent-db-context][jdbc] urlPresent=$($summary.UrlPresent) endpointHost=$($summary.Host) endpointPort=$($summary.Port) source=$($summary.Source)"

if ($RequireDbEnv -and $missingRequired.Count -gt 0) {
    Write-Host "[AWX][agent-db-context] evidence_needed: missing required env refs: $($missingRequired -join ',')"
    exit 2
}

if ($SkipTcpProbe) {
    Write-Host "[AWX][agent-db-context][tcp] skipped=true"
} else {
    $tcp = Test-TcpConnect -HostName $summary.Host -Port $summary.Port
    Write-Host "[AWX][agent-db-context][tcp] ok=$($tcp.Ok) reason=$($tcp.Reason) endpointHost=$($summary.Host) endpointPort=$($summary.Port)"
}

if ($SkipHttpProbe) {
    Write-Host "[AWX][agent-db-context][http] skipped=true"
    exit 0
}

$toolbox = Join-Path $PSScriptRoot "awx_mcp_toolbox.ps1"
if (-not (Test-Path -LiteralPath $toolbox)) {
    Write-Host "[AWX][agent-db-context] evidence_needed: missing scripts\awx_mcp_toolbox.ps1"
    exit 3
}

$payload = [ordered]@{
    endpoint = $Endpoint
    base_url = $BaseUrl
    timeout_sec = $TimeoutSec
    nodeRole = "desktop"
    requestId = "agent-db-context-smoke"
} | ConvertTo-Json -Depth 8 -Compress

$raw = & $toolbox -Tool agent_db_snapshot -InputJson $payload
$exitCode = $LASTEXITCODE
try {
    $result = $raw | ConvertFrom-Json -ErrorAction Stop
} catch {
    Write-Host "[AWX][agent-db-context][http] invalid-json-from-toolbox"
    exit 3
}

$disabledReason = ""
$failureClass = ""
if ($null -ne $result.snapshot) {
    $disabledReason = Get-SafeReasonToken $result.snapshot.disabledReason
    $failureClass = Get-SafeReasonToken $result.snapshot.failureClass
}
$failReason = Get-SafeReasonToken $result.failReason
$localFallbackPresent = $null -ne $result.localFallback
$localFallbackEvidenceNeededCount = 0
if ($localFallbackPresent -and $null -ne $result.localFallback.evidenceNeeded) {
    $localFallbackEvidenceNeededCount = @($result.localFallback.evidenceNeeded).Count
}

Write-Host "[AWX][agent-db-context][http] ok=$($result.ok) decision=$($result.decision) httpStatus=$($result.httpStatus) tokenPresented=$($result.tokenPresented) disabledReason=$disabledReason failureClass=$failureClass failReason=$failReason localFallbackPresent=$localFallbackPresent localFallbackEvidenceNeededCount=$localFallbackEvidenceNeededCount"

if ($RequireRuntime -and ($exitCode -ne 0 -or $result.ok -ne $true)) {
    Write-Host "[AWX][agent-db-context] evidence_needed: runtime DB-context probe did not return ok=true"
    exit 3
}

exit 0

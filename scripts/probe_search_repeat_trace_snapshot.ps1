param(
    [string]$BaseUrl = "",
    [string]$ProbePath = "/api/probe/search",
    [ValidateRange(1, 50)]
    [int]$Repeat = 3,
    [string]$Query = "RAG evidence starvation debug",
    [string]$SessionId = "probe-repeat-session",
    [string]$RequestIdPrefix = "probe-repeat",
    [ValidateRange(1, 20)]
    [int]$WebTopK = 5,
    [string]$AdminToken = $env:PROBE_ADMIN_TOKEN,
    [string]$OutFile
)

$ErrorActionPreference = "Stop"

function Get-StringHash {
    param([string]$Value)
    if ($null -eq $Value) {
        $Value = ""
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $sha.ComputeHash($bytes)
        return "sha256:" + ([System.BitConverter]::ToString($hash).Replace("-", "").Substring(0, 16).ToLowerInvariant())
    } finally {
        $sha.Dispose()
    }
}

function Get-HeaderValue {
    param(
        [object]$Headers,
        [string]$Name
    )
    if ($null -eq $Headers) {
        return $null
    }
    foreach ($key in $Headers.Keys) {
        if ([string]::Equals([string]$key, $Name, [System.StringComparison]::OrdinalIgnoreCase)) {
            $value = $Headers[$key]
            if ($value -is [array]) {
                return ($value -join ",")
            }
            return [string]$value
        }
    }
    return $null
}

function Get-ParamValue {
    param(
        [object]$Params,
        [string]$Name
    )
    if ($null -eq $Params) {
        return $null
    }
    $prop = $Params.PSObject.Properties[$Name]
    if ($null -eq $prop) {
        return $null
    }
    return $prop.Value
}

if ([string]::IsNullOrWhiteSpace($AdminToken)) {
    Write-Error "[AWX][probe][repeat] evidence_needed: set PROBE_ADMIN_TOKEN or pass -AdminToken for X-Probe-Token."
    exit 1
}

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    if (-not [string]::IsNullOrWhiteSpace($env:APP_PUBLIC_BASE_URL)) {
        $BaseUrl = $env:APP_PUBLIC_BASE_URL
    } elseif (-not [string]::IsNullOrWhiteSpace($env:PUBLIC_BASE_URL)) {
        $BaseUrl = $env:PUBLIC_BASE_URL
    } else {
        $BaseUrl = "http://localhost:8080"
    }
}

$uri = $BaseUrl.TrimEnd("/") + "/" + $ProbePath.TrimStart("/")
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $OutFile = Join-Path (Get-Location) ("build\probe-search-repeat\probe-search-repeat-{0}.json" -f $timestamp)
}
$outDir = Split-Path -Parent $OutFile
if (-not [string]::IsNullOrWhiteSpace($outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

$seed = @(
    [ordered]@{
        id = "seed-doc-1"
        title = "RAG trace selected seed"
        snippet = "diagnostic seed snippet redacted from report"
        url = "https://example.invalid/rag-seed-1"
        source = "notebook-local"
        score = 0.91
        rank = 1
        meta = [ordered]@{
            seedHash = "static-seed-a"
            sessionId = $SessionId
        }
    },
    [ordered]@{
        id = "seed-doc-2"
        title = "CFVM recovery seed"
        snippet = "diagnostic seed snippet redacted from report"
        url = "https://example.invalid/cfvm-seed-2"
        source = "notebook-local"
        score = 0.84
        rank = 2
        meta = [ordered]@{
            seedHash = "static-seed-b"
            sessionId = $SessionId
        }
    }
)

$payload = [ordered]@{
    query = $Query
    seedMode = "candidates"
    seed = $seed
    flags = [ordered]@{
        seedOnly = $true
        useWeb = $true
        useRag = $true
        officialSourcesOnly = $false
    }
    webTopK = $WebTopK
}
$body = $payload | ConvertTo-Json -Depth 8

$interestingKeys = @(
    "traceSnapshot.probe.search.restoredSelectedTrace",
    "traceSnapshot.probe.search.headerPresent",
    "traceSnapshot.probe.search.storeAvailable",
    "traceSnapshot.probe.search.captured",
    "traceSnapshot.probe.search.missingReason",
    "traceSnapshot.probe.search.failed",
    "traceSnapshot.probe.search.errorType",
    "trace.snapshot.capture.skipped",
    "trace.snapshot.capture.skipReason",
    "trace.snapshot.capture.reason",
    "trace.snapshot.capture.failed",
    "trace.snapshot.capture.errorType",
    "retrievalOrder.lastSetBy",
    "retrievalOrder.lastOrder",
    "cfvm.boltzmannTemp",
    "cfvm.tempAnnealApplied",
    "cfvm.failureRecovery.triggered",
    "cfvm.failureRecovery.cancelTrueDowngraded",
    "cfvm.failureRecovery.timeoutCondition",
    "cfvm.failureRecovery.snapshot.saved",
    "cfvm.rawBuffer.weightMode",
    "extremeZ.cancelShieldWrapped",
    "extremeZ.timeBudgetConsumedMs",
    "hypernova.cvarPhi",
    "moe.evolverPlateRegistered",
    "cihRag.breadcrumb.queryRedacted"
)

$results = New-Object System.Collections.Generic.List[object]

for ($i = 1; $i -le $Repeat; $i++) {
    $requestId = "{0}-{1:d2}" -f $RequestIdPrefix, $i
    $headers = @{
        "X-Probe-Token" = $AdminToken
        "X-Session-Id" = $SessionId
        "X-Request-Id" = $requestId
    }

    try {
        $response = Invoke-WebRequest -Uri $uri -Method Post -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 60 -UseBasicParsing
        $parsed = $response.Content | ConvertFrom-Json
        $stages = @($parsed.stages)
        $traceSelected = @($stages | Where-Object { $_.name -eq "trace:selected" })
        $selected = [ordered]@{}
        if ($traceSelected.Count -gt 0) {
            foreach ($key in $interestingKeys) {
                $value = Get-ParamValue -Params $traceSelected[0].params -Name $key
                if ($null -ne $value) {
                    $selected[$key] = $value
                }
            }
        }

        $results.Add([ordered]@{
            requestId = $requestId
            httpStatus = [int]$response.StatusCode
            traceSnapshotId = Get-HeaderValue -Headers $response.Headers -Name "X-Trace-Snapshot-Id"
            traceSelectedCount = $traceSelected.Count
            stageCount = $stages.Count
            finalResultCount = @($parsed.finalResults).Count
            selected = $selected
            errorType = $null
        }) | Out-Null
    } catch {
        $status = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            try {
                $status = [int]$_.Exception.Response.StatusCode
            } catch {
                $status = $null
            }
        }
        $results.Add([ordered]@{
            requestId = $requestId
            httpStatus = $status
            traceSnapshotId = $null
            traceSelectedCount = 0
            stageCount = 0
            finalResultCount = 0
            selected = [ordered]@{}
            errorType = $_.Exception.GetType().Name
        }) | Out-Null
    }

    Start-Sleep -Milliseconds 200
}

$snapshotIds = @($results | Where-Object { -not [string]::IsNullOrWhiteSpace($_.traceSnapshotId) } | ForEach-Object { $_.traceSnapshotId })
$duplicateSnapshotIds = @($snapshotIds | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
$failedRequests = @($results | Where-Object { $null -ne $_.errorType }).Count
$missingSnapshotIds = @($results | Where-Object { [string]::IsNullOrWhiteSpace($_.traceSnapshotId) }).Count
$missingTraceSelected = @($results | Where-Object { $_.traceSelectedCount -eq 0 }).Count
$multiTraceSelected = @($results | Where-Object { $_.traceSelectedCount -gt 1 }).Count

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    endpoint = $uri
    repeat = $Repeat
    sessionId = $SessionId
    requestIdPrefix = $RequestIdPrefix
    queryHash = Get-StringHash $Query
    queryLength = $Query.Length
    seedCount = $seed.Count
    diagnostics = [ordered]@{
        failedRequestCount = $failedRequests
        missingSnapshotIdCount = $missingSnapshotIds
        duplicateSnapshotIds = $duplicateSnapshotIds
        missingTraceSelectedCount = $missingTraceSelected
        multiTraceSelectedCount = $multiTraceSelected
    }
    results = $results
}

$summary | ConvertTo-Json -Depth 12 | Set-Content -Path $OutFile -Encoding UTF8
Write-Host ("[AWX][probe][repeat] report={0}" -f $OutFile)
Write-Host ("[AWX][probe][repeat] failed={0} missingSnapshot={1} duplicateSnapshot={2} missingTraceSelected={3} multiTraceSelected={4}" -f `
        $failedRequests, $missingSnapshotIds, $duplicateSnapshotIds.Count, $missingTraceSelected, $multiTraceSelected)

if ($failedRequests -gt 0 -or $missingSnapshotIds -gt 0 -or $duplicateSnapshotIds.Count -gt 0 -or $missingTraceSelected -gt 0 -or $multiTraceSelected -gt 0) {
    exit 2
}
exit 0

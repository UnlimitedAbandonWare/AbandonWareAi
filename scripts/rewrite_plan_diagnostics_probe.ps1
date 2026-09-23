[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:18169",
    [string]$OutDir = "var/codex-smoke/rewrite-plan-diagnostics",
    [int]$Limit = 30,
    [string]$SnapshotJsonPath = "",
    [switch]$AllowMissing,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
try {
    $ResolvedOutDir = if ([System.IO.Path]::IsPathRooted($OutDir)) {
        [System.IO.Path]::GetFullPath($OutDir)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $Root $OutDir))
    }
} catch {
    throw "[AWX][rewrite-plan-diagnostics] outdir-invalid reason=path-resolution-failed"
}
if (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $rootPrefix = $Root.TrimEnd([char[]]"\/") + [System.IO.Path]::DirectorySeparatorChar
    $insideRoot = $ResolvedOutDir.Equals($Root, [System.StringComparison]::OrdinalIgnoreCase) -or
        $ResolvedOutDir.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    if (-not $insideRoot) {
        throw "[AWX][rewrite-plan-diagnostics] outdir-invalid reason=outdir-relative-outside-root"
    }
    $hasReparseAncestor = $false
    try {
        $cursor = $ResolvedOutDir
        while (-not $cursor.Equals($Root, [System.StringComparison]::OrdinalIgnoreCase)) {
            if (Test-Path -LiteralPath $cursor -ErrorAction Stop) {
                $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
                if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                    $hasReparseAncestor = $true
                    break
                }
            }
            $parent = [System.IO.Directory]::GetParent($cursor)
            if ($null -eq $parent) {
                break
            }
            $cursor = $parent.FullName
        }
    } catch {
        throw "[AWX][rewrite-plan-diagnostics] outdir-invalid reason=outdir-relative-inspection-failed"
    }
    if ($hasReparseAncestor) {
        throw "[AWX][rewrite-plan-diagnostics] outdir-invalid reason=outdir-relative-reparse-point"
    }
}
$SummaryPath = Join-Path $ResolvedOutDir "rewrite-plan-diagnostics.summary.json"
$EventsPath = Join-Path $ResolvedOutDir "rewrite-plan-diagnostics.events.ndjson"

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Add-Event {
    param(
        [Parameter(Mandatory = $true)][object]$Events,
        [Parameter(Mandatory = $true)][string]$Type,
        [hashtable]$Fields = @{}
    )
    $row = [ordered]@{
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
        type = $Type
    }
    foreach ($key in $Fields.Keys) {
        $row[$key] = $Fields[$key]
    }
    [void]$Events.Add([pscustomobject]$row)
}

function ConvertTo-ShortHash {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) {
        return ""
    }
    $text = [string]$Value
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return (($hash | ForEach-Object { $_.ToString("x2") }) -join "").Substring(0, 12)
}

function Get-PropertyValue {
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [object]$Default = $null
    )
    if ($null -eq $Object) {
        return $Default
    }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) {
            return $Object[$Name]
        }
        return $Default
    }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) {
        return $Default
    }
    return $prop.Value
}

function Get-NestedPropertyValue {
    param(
        [AllowNull()][object]$Object,
        [Parameter(Mandatory = $true)][string[]]$Path,
        [object]$Default = $null
    )
    $current = $Object
    foreach ($part in $Path) {
        $current = Get-PropertyValue -Object $current -Name $part -Default $null
        if ($null -eq $current) {
            return $Default
        }
    }
    return $current
}

function Get-TraceMap {
    param([AllowNull()][object]$Snapshot)
    $trace = Get-PropertyValue -Object $Snapshot -Name "trace" -Default $null
    if ($null -ne $trace) {
        return $trace
    }
    return Get-NestedPropertyValue -Object $Snapshot -Path @("Json", "trace") -Default $null
}

function Get-SafeValue {
    param([AllowNull()][object]$Value)
    if ($null -eq $Value) {
        return $null
    }
    if ($Value -is [bool] -or $Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal]) {
        return $Value
    }
    if ($Value -is [System.Collections.IDictionary] -or
        (-not ($Value -is [string]) -and
            -not ($Value -is [System.Collections.IEnumerable]) -and
            @($Value.PSObject.Properties).Count -gt 0)) {
        $hash12 = [string](Get-PropertyValue -Object $Value -Name "hash12" -Default "")
        if ($hash12 -match '^[0-9a-f]{12}$') {
            $out = [ordered]@{
                hash12 = $hash12
            }
            $present = Get-PropertyValue -Object $Value -Name "present" -Default $null
            $length = Get-PropertyValue -Object $Value -Name "len" -Default $null
            if ($null -ne $present) {
                $out.present = [bool]$present
            }
            if ($null -ne $length) {
                $out.len = [int]$length
            }
            return $out
        }
        $safeMap = [ordered]@{}
        foreach ($prop in @($Value.PSObject.Properties | Select-Object -First 24)) {
            if ($prop.Name -match '^[A-Za-z0-9_.:-]{1,80}$') {
                $safeMap[$prop.Name] = Get-SafeValue $prop.Value
            }
        }
        return $safeMap
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        $items = @()
        foreach ($item in @($Value)) {
            $items += (Get-SafeValue $item)
            if ($items.Count -ge 24) {
                break
            }
        }
        return $items
    }

    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return ""
    }
    if ($text -match '^[0-9a-f]{12}$') {
        return $text
    }
    if ($text.Length -le 240 -and $text -match '^[A-Za-z0-9_.:,|/+\-\[\] ]+$') {
        return $text
    }
    return [ordered]@{
        kind = "redacted-text"
        length = $text.Length
        hash12 = ConvertTo-ShortHash $text
    }
}

function Count-SecretPatterns {
    param([AllowEmptyString()][string]$Text)
    $pattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization\s*:|Cookie\s*:|Bearer\s+[A-Za-z0-9._~+/-]+=*|client_secret'
    return ([regex]::Matches([string]$Text, $pattern)).Count
}

function Invoke-JsonGet {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 20
    )
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
    return [pscustomobject]@{
        Status = [int]$response.StatusCode
        Json = ($response.Content | ConvertFrom-Json)
        ContentLength = ([string]$response.Content).Length
    }
}

function New-Summary {
    param(
        [string]$Status,
        [string]$FailureClass = "",
        [AllowNull()][object]$Snapshot = $null,
        [AllowNull()][object]$Trace = $null,
        [string[]]$SelectedKeys = @()
    )
    $rewritePlan = [ordered]@{}
    foreach ($key in $SelectedKeys) {
        $rewritePlan[$key] = Get-SafeValue (Get-PropertyValue -Object $Trace -Name $key -Default $null)
    }
    $snapshotId = [string](Get-PropertyValue -Object $Snapshot -Name "id" -Default "")
    $snapshotReason = [string](Get-PropertyValue -Object $Snapshot -Name "reason" -Default "")
    $snapshotTs = [string](Get-PropertyValue -Object $Snapshot -Name "ts" -Default "")
    if ([string]::IsNullOrWhiteSpace($snapshotTs)) {
        $snapshotTs = [string](Get-PropertyValue -Object $Snapshot -Name "createdAt" -Default "")
    }
    $summary = [ordered]@{
        schemaVersion = "awx.rewrite_plan_diagnostics_probe.v1"
        generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
        status = $Status
        ok = ($Status -eq "ok")
        mutationAllowed = $false
        rawQueryStored = $false
        rawTokenStored = $false
        baseUrl = $BaseUrl.TrimEnd("/")
        limit = [Math]::Max(1, [Math]::Min(200, $Limit))
        snapshotId = $snapshotId
        snapshotReason = $snapshotReason
        snapshotTs = $snapshotTs
        traceKeyCount = if ($null -eq $Trace) { 0 } else { @($Trace.PSObject.Properties).Count }
        selectedKeys = $SelectedKeys
        rewritePlan = $rewritePlan
        rawQueryLeak = $false
        secretPatternHits = 0
        failureClass = $FailureClass
        summaryPath = $SummaryPath
        eventsPath = $EventsPath
    }
    $summaryText = $summary | ConvertTo-Json -Depth 20
    $summary.secretPatternHits = Count-SecretPatterns $summaryText
    $summary.rawQueryLeak = ($summaryText -match 'rawQueryText|rawUserQuery|fullPrompt|promptText|Authorization|Cookie|Bearer\s+')
    return [pscustomobject]$summary
}

New-Item -ItemType Directory -Force -Path $ResolvedOutDir | Out-Null
$events = [System.Collections.ArrayList]::new()
$selectedKeys = @(
    "web.rewritePlan.seedHash12",
    "web.rewritePlan.variantHash12",
    "web.rewritePlan.verificationCount",
    "web.rewritePlan.explorationCount",
    "web.rewritePlan.laneSummary",
    "web.query.rewrite.querySeedHash12",
    "web.query.rewrite.variantSetHash12",
    "web.query.rewrite.verificationLaneCount",
    "web.query.rewrite.explorationLaneCount",
    "web.query.rewrite.laneLabels",
    "web.query.rewrite.laneSummary",
    "web.naver.adaptive.querySeedHash12",
    "web.naver.adaptive.variantSetHash12",
    "web.naver.adaptive.verificationLaneCount",
    "web.naver.adaptive.explorationLaneCount",
    "web.naver.adaptive.explorationTemperature",
    "web.naver.adaptive.temperatureProfile",
    "web.naver.adaptive.variantCount",
    "web.naver.adaptive.laneLabels",
    "web.brave.adaptive.querySeedHash12",
    "web.brave.adaptive.variantSetHash12",
    "web.brave.adaptive.verificationLaneCount",
    "web.brave.adaptive.explorationLaneCount",
    "web.brave.adaptive.explorationTemperature",
    "web.brave.adaptive.temperatureProfile",
    "web.brave.adaptive.variantCount",
    "web.brave.adaptive.laneLabels",
    "queryTransformer.subQueries.superTokens.enabled",
    "queryTransformer.subQueries.superTokens.reason",
    "queryTransformer.subQueries.superTokens.branchCount",
    "queryTransformer.subQueries.superTokens.tokenCount",
    "queryTransformer.subQueries.superTokens.subModelCount",
    "queryTransformer.subQueries.superTokens.subModelAssignmentCount",
    "queryTransformer.subQueries.superTokens.subModelIds",
    "queryTransformer.subQueries.superTokens.axisCount",
    "queryTransformer.subQueries.superTokens.axes",
    "queryTransformer.subQueries.superTokens.titlePresent",
    "queryTransformer.subQueries.superTokens.titleHash12",
    "queryTransformer.subQueries.superTokens.titleTokenCount",
    "queryTransformer.subQueries.superTokens.branchTitleCount",
    "queryTransformer.subQueries.superTokens.branchTitleHashCount",
    "queryTransformer.subQueries.superTokens.branchTitleHashes",
    "queryTransformer.subQueries.superTokens.branchTitleMetadataCount",
    "queryTransformer.subQueries.superTokens.branchQueryMetadataCount",
    "queryTransformer.subQueries.superTokens.branchQueryHashes",
    "queryTransformer.subQueries.superTokens.branchQueryCoverageComplete",
    "queryTransformer.subQueries.superTokens.branchTitleCoverageComplete",
    "queryTransformer.subQueries.superTokens.coverageComplete"
)

if ($StaticOnly) {
    Add-Event -Events $events -Type "static_only" -Fields @{ ok = $true; mutationAllowed = $false }
    $summary = New-Summary -Status "static-only" -FailureClass "not_run"
    Write-Utf8NoBom -Path $SummaryPath -Value ($summary | ConvertTo-Json -Depth 20)
    $eventLines = @($events) | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }
    Write-Utf8NoBom -Path $EventsPath -Value (($eventLines -join "`n") + "`n")
    [Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 20 -Compress))
    exit 0
}

if (-not [string]::IsNullOrWhiteSpace($SnapshotJsonPath)) {
    $offlinePath = if ([System.IO.Path]::IsPathRooted($SnapshotJsonPath)) {
        $SnapshotJsonPath
    } else {
        Join-Path (Get-Location) $SnapshotJsonPath
    }
    Add-Event -Events $events -Type "offline_snapshot_started" -Fields @{ mutationAllowed = $false }
    $snapshot = Get-Content -Raw -LiteralPath $offlinePath | ConvertFrom-Json
    $trace = Get-TraceMap -Snapshot $snapshot
    $status = if ($null -eq $trace) { "evidence_needed" } else { "ok" }
    $failureClass = if ($null -eq $trace) { "trace_payload_missing" } else { "" }
    $summary = New-Summary -Status $status -FailureClass $failureClass -Snapshot $snapshot -Trace $trace -SelectedKeys $selectedKeys
    Add-Event -Events $events -Type "offline_snapshot_loaded" -Fields @{
        status = $summary.status
        traceKeyCount = $summary.traceKeyCount
        snapshotId = $summary.snapshotId
    }
    Add-Event -Events $events -Type "summary_written" -Fields @{
        status = $summary.status
        ok = $summary.ok
        secretPatternHits = $summary.secretPatternHits
        rawQueryLeak = $summary.rawQueryLeak
    }
    Write-Utf8NoBom -Path $SummaryPath -Value ($summary | ConvertTo-Json -Depth 20)
    $eventLines = @($events) | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }
    Write-Utf8NoBom -Path $EventsPath -Value (($eventLines -join "`n") + "`n")
    [Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 20 -Compress))
    if ($summary.secretPatternHits -gt 0 -or $summary.rawQueryLeak) {
        exit 4
    }
    exit 0
}

$safeLimit = [Math]::Max(1, [Math]::Min(200, $Limit))
$base = $BaseUrl.TrimEnd("/")

try {
    Add-Event -Events $events -Type "started" -Fields @{ baseUrl = $base; limit = $safeLimit; mutationAllowed = $false }
    $listUrl = "$base/api/diagnostics/trace/snapshots?limit=$safeLimit"
    $listed = Invoke-JsonGet -Url $listUrl
    $snapshots = @(Get-PropertyValue -Object $listed.Json -Name "snapshots" -Default @())
    Add-Event -Events $events -Type "snapshots_listed" -Fields @{ status = $listed.Status; count = @($snapshots).Count }

    $selected = $null
    foreach ($candidate in $snapshots) {
        $reason = [string](Get-PropertyValue -Object $candidate -Name "reason" -Default "")
        if ($reason -eq "chat.trace_html.final") {
            $selected = $candidate
            break
        }
    }
    if ($null -eq $selected -and @($snapshots).Count -gt 0) {
        $selected = @($snapshots)[0]
    }
    if ($null -eq $selected) {
        $summary = New-Summary -Status "evidence_needed" -FailureClass "trace_snapshot_missing" -SelectedKeys $selectedKeys
        Add-Event -Events $events -Type "evidence_needed" -Fields @{ failureClass = "trace_snapshot_missing" }
    } else {
        $snapshotId = [string](Get-PropertyValue -Object $selected -Name "id" -Default "")
        Add-Event -Events $events -Type "snapshot_selected" -Fields @{
            snapshotId = $snapshotId
            reason = [string](Get-PropertyValue -Object $selected -Name "reason" -Default "")
        }
        $detail = Invoke-JsonGet -Url "$base/api/diagnostics/trace/snapshots/$snapshotId"
        $trace = Get-TraceMap -Snapshot $detail.Json
        Add-Event -Events $events -Type "snapshot_detail_loaded" -Fields @{
            status = $detail.Status
            snapshotId = $snapshotId
            traceKeyCount = if ($null -eq $trace) { 0 } else { @($trace.PSObject.Properties).Count }
        }
        $summaryStatus = if ($null -eq $trace) { "evidence_needed" } else { "ok" }
        $failureClass = if ($null -eq $trace) { "trace_payload_missing" } else { "" }
        $summary = New-Summary -Status $summaryStatus -FailureClass $failureClass -Snapshot $detail.Json -Trace $trace -SelectedKeys $selectedKeys
    }
} catch {
    $failureClass = "diagnostics_unreachable"
    if ($AllowMissing) {
        $summary = New-Summary -Status "evidence_needed" -FailureClass $failureClass -SelectedKeys $selectedKeys
        Add-Event -Events $events -Type "evidence_needed" -Fields @{ failureClass = $failureClass; errorHash12 = ConvertTo-ShortHash $_.Exception.Message }
    } else {
        Add-Event -Events $events -Type "failed" -Fields @{ failureClass = $failureClass; errorHash12 = ConvertTo-ShortHash $_.Exception.Message }
        $summary = New-Summary -Status "failed" -FailureClass $failureClass -SelectedKeys $selectedKeys
    }
}

Add-Event -Events $events -Type "summary_written" -Fields @{
    status = $summary.status
    ok = $summary.ok
    secretPatternHits = $summary.secretPatternHits
    rawQueryLeak = $summary.rawQueryLeak
}

Write-Utf8NoBom -Path $SummaryPath -Value ($summary | ConvertTo-Json -Depth 20)
$eventLines = @($events) | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }
Write-Utf8NoBom -Path $EventsPath -Value (($eventLines -join "`n") + "`n")

Write-Host "[AWX][rewrite-plan-diagnostics] status=$($summary.status) snapshotId=$($summary.snapshotId) traceKeyCount=$($summary.traceKeyCount) secretPatternHits=$($summary.secretPatternHits) rawQueryLeak=$($summary.rawQueryLeak) summary=$SummaryPath events=$EventsPath"
if ($summary.secretPatternHits -gt 0 -or $summary.rawQueryLeak) {
    Write-Error "[AWX][rewrite-plan-diagnostics] secret-leak-risk secretPatternHits=$($summary.secretPatternHits) rawQueryLeak=$($summary.rawQueryLeak)"
    exit 4
}
if ($summary.status -eq "failed" -and -not $AllowMissing) {
    exit 2
}
exit 0

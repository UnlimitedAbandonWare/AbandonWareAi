$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if (-not $Text.Contains($Needle)) {
        throw "[FAIL] $Name missing: $Needle"
    }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if ($Text.Contains($Needle)) {
        throw "[FAIL] $Name unexpected: $Needle"
    }
}

$scriptPath = Join-Path $PSScriptRoot "rewrite_plan_diagnostics_probe.ps1"
if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "[FAIL] missing rewrite diagnostics probe: $scriptPath"
}

$source = Get-Content -Raw -LiteralPath $scriptPath
Assert-Contains "probe has base url" $source "[string]`$BaseUrl"
Assert-Contains "probe has output dir" $source "[string]`$OutDir"
Assert-Contains "probe has bounded limit" $source "[int]`$Limit"
Assert-Contains "probe supports offline snapshot json" $source "[string]`$SnapshotJsonPath"
Assert-Contains "probe supports static-only" $source "[switch]`$StaticOnly"
Assert-Contains "probe lists trace snapshots" $source "/api/diagnostics/trace/snapshots?limit="
Assert-Contains "probe fetches trace snapshot detail" $source "/api/diagnostics/trace/snapshots/"
Assert-Contains "probe writes summary artifact" $source "rewrite-plan-diagnostics.summary.json"
Assert-Contains "probe writes event ledger" $source "rewrite-plan-diagnostics.events.ndjson"
Assert-Contains "probe records mutation false" $source "mutationAllowed = `$false"
Assert-Contains "probe records raw query storage false" $source "rawQueryStored = `$false"
Assert-Contains "probe records raw token storage false" $source "rawTokenStored = `$false"
Assert-Contains "probe tracks rewrite seed hash" $source "web.rewritePlan.seedHash12"
Assert-Contains "probe tracks rewrite variant hash" $source "web.rewritePlan.variantHash12"
Assert-Contains "probe tracks rewrite verification count" $source "web.rewritePlan.verificationCount"
Assert-Contains "probe tracks rewrite exploration count" $source "web.rewritePlan.explorationCount"
Assert-Contains "probe tracks rewrite lane summary" $source "web.rewritePlan.laneSummary"
Assert-Contains "probe tracks generic query rewrite lane summary" $source "web.query.rewrite.laneSummary"
Assert-Contains "probe tracks naver lane labels" $source "web.naver.adaptive.laneLabels"
Assert-Contains "probe tracks brave lane labels" $source "web.brave.adaptive.laneLabels"
Assert-Contains "probe tracks super-token query rewrite enabled" $source "queryTransformer.subQueries.superTokens.enabled"
Assert-Contains "probe tracks super-token branch count" $source "queryTransformer.subQueries.superTokens.branchCount"
Assert-Contains "probe tracks super-token branch hashes" $source "queryTransformer.subQueries.superTokens.branchQueryHashes"
Assert-Contains "probe tracks super-token coverage" $source "queryTransformer.subQueries.superTokens.coverageComplete"
Assert-Contains "probe tracks secret hits" $source "secretPatternHits"
Assert-Contains "probe tracks raw query leak" $source "rawQueryLeak"
Assert-NotContains "probe does not dump env" $source "Get-ChildItem Env:"
Assert-NotContains "probe does not print auth headers" $source "Authorization="

$durableOut = Join-Path ([System.IO.Path]::GetTempPath()) ("rewrite-plan-diagnostics-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $durableOut | Out-Null
try {
    $static = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -StaticOnly -OutDir $durableOut 2>$null
    $json = $static | ConvertFrom-Json
    if ($json.schemaVersion -ne "awx.rewrite_plan_diagnostics_probe.v1") {
        throw "[FAIL] static schema mismatch: $($json.schemaVersion)"
    }
    if ($json.status -ne "static-only") {
        throw "[FAIL] static status mismatch: $($json.status)"
    }
    if ($json.mutationAllowed -ne $false) {
        throw "[FAIL] static probe must not mutate"
    }
    if ($json.rawQueryStored -ne $false -or $json.rawTokenStored -ne $false) {
        throw "[FAIL] static probe must not store raw query/token"
    }
    if (-not (Test-Path -LiteralPath $json.summaryPath)) {
        throw "[FAIL] static summary missing: $($json.summaryPath)"
    }
    if (-not (Test-Path -LiteralPath $json.eventsPath)) {
        throw "[FAIL] static events missing: $($json.eventsPath)"
    }
    $summaryText = Get-Content -Raw -LiteralPath $json.summaryPath
    Assert-Contains "static summary records not-run" $summaryText '"status":'
    Assert-NotContains "static summary has no raw query marker" $summaryText "rawQueryText"
    Assert-NotContains "static summary has no auth token marker" $summaryText ("Bear" + "er ")

    $fakeSnapshotPath = Join-Path $durableOut "fake-snapshot.json"
    @{
        id = "offline-snapshot"
        reason = "chat.trace_html.final"
        ts = "2026-07-08T00:00:00Z"
        trace = @{
            "web.query.rewrite.querySeedHash12" = @{
                present = $true
                len = 44
                hash12 = "123456789abc"
            }
            "web.query.rewrite.laneLabels" = @(
                @{
                    present = $true
                    len = 31
                    hash12 = "aaaaaaaaaaaa"
                },
                "verification:official_source"
            )
            "web.query.rewrite.laneSummary" = "verification:official_source|exploration:latest_update"
            "web.naver.adaptive.variantSetHash12" = "57749fe11958"
            "web.naver.adaptive.verificationLaneCount" = 2
            "web.rewritePlan.variantHash12" = "57749fe11958"
            "web.rewritePlan.verificationCount" = 2
            "queryTransformer.subQueries.superTokens.enabled" = $true
            "queryTransformer.subQueries.superTokens.branchCount" = 3
            "queryTransformer.subQueries.superTokens.branchQueryHashes" = @(
                "111111111111",
                "222222222222",
                "333333333333"
            )
            "queryTransformer.subQueries.superTokens.coverageComplete" = $true
        }
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $fakeSnapshotPath -Encoding UTF8

    $offline = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -SnapshotJsonPath $fakeSnapshotPath -OutDir $durableOut 2>$null
    $offlineJson = $offline | ConvertFrom-Json
    if ($offlineJson.status -ne "ok") {
        throw "[FAIL] offline status mismatch: $($offlineJson.status)"
    }
    if ($offlineJson.rewritePlan.'web.query.rewrite.querySeedHash12'.hash12 -ne "123456789abc") {
        throw "[FAIL] offline should preserve redacted hash object"
    }
    if ($offlineJson.rewritePlan.'web.naver.adaptive.variantSetHash12' -ne "57749fe11958") {
        throw "[FAIL] offline should preserve safe variant hash"
    }
    if ($offlineJson.rewritePlan.'queryTransformer.subQueries.superTokens.enabled' -ne $true) {
        throw "[FAIL] offline should preserve super-token rewrite enabled flag"
    }
    if ($offlineJson.rewritePlan.'queryTransformer.subQueries.superTokens.branchCount' -ne 3) {
        throw "[FAIL] offline should preserve super-token branch count"
    }
    if (@($offlineJson.rewritePlan.'queryTransformer.subQueries.superTokens.branchQueryHashes').Count -ne 3) {
        throw "[FAIL] offline should preserve super-token branch hash count"
    }
    if ($offlineJson.rewritePlan.'queryTransformer.subQueries.superTokens.coverageComplete' -ne $true) {
        throw "[FAIL] offline should preserve super-token coverage flag"
    }
    if ($offlineJson.secretPatternHits -ne 0 -or $offlineJson.rawQueryLeak -ne $false) {
        throw "[FAIL] offline should stay leak-safe"
    }
} finally {
    Remove-Item -LiteralPath $durableOut -Recurse -Force -ErrorAction SilentlyContinue
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outsideLeaf = "rewrite-plan-diagnostics-outside-" + [guid]::NewGuid().ToString("N")
$relativeEscape = Join-Path ".." $outsideLeaf
$outsidePath = [IO.Path]::GetFullPath((Join-Path $root $relativeEscape))
$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $escapeOutput = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -StaticOnly -OutDir $relativeEscape 2>&1)
    $escapeExit = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($escapeExit -eq 0) {
        throw "[FAIL] relative output escape must return nonzero"
    }
    if (Test-Path -LiteralPath $outsidePath) {
        throw "[FAIL] relative output escape must not create artifacts"
    }
    Assert-Contains "relative output escape is categorical" ($escapeOutput -join "`n") "outdir-relative-outside-root"
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Remove-Item -LiteralPath $outsidePath -Recurse -Force -ErrorAction SilentlyContinue
}

$reparseFixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rewrite-plan-reparse-root-" + [guid]::NewGuid().ToString("N"))
$reparseOutsideRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("rewrite-plan-reparse-outside-" + [guid]::NewGuid().ToString("N"))
$fixtureScripts = Join-Path $reparseFixtureRoot "scripts"
$fixtureJunction = Join-Path $reparseFixtureRoot "out-link"
$fixtureScript = Join-Path $fixtureScripts "rewrite_plan_diagnostics_probe.ps1"
$previousErrorActionPreference = $ErrorActionPreference
try {
    New-Item -ItemType Directory -Force -Path $fixtureScripts, $reparseOutsideRoot | Out-Null
    Copy-Item -LiteralPath $scriptPath -Destination $fixtureScript

    $relativeControlDir = Join-Path $reparseFixtureRoot "ordinary-output"
    $relativeControl = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $fixtureScript -StaticOnly -OutDir "ordinary-output" 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] ordinary relative output must succeed"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $relativeControlDir "rewrite-plan-diagnostics.summary.json"))) {
        throw "[FAIL] ordinary relative output must write the summary"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $relativeControlDir "rewrite-plan-diagnostics.events.ndjson"))) {
        throw "[FAIL] ordinary relative output must write the events"
    }

    New-Item -ItemType Junction -Path $fixtureJunction -Target $reparseOutsideRoot -ErrorAction Stop | Out-Null

    $ErrorActionPreference = "Continue"
    $reparseOutput = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $fixtureScript -StaticOnly -OutDir "out-link" 2>&1)
    $reparseExit = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
    if ($reparseExit -eq 0) {
        throw "[FAIL] relative reparse output escape must return nonzero"
    }
    if (Test-Path -LiteralPath (Join-Path $reparseOutsideRoot "rewrite-plan-diagnostics.summary.json")) {
        throw "[FAIL] relative reparse escape must not write the summary outside"
    }
    if (Test-Path -LiteralPath (Join-Path $reparseOutsideRoot "rewrite-plan-diagnostics.events.ndjson")) {
        throw "[FAIL] relative reparse escape must not write the events outside"
    }
    Assert-Contains "relative reparse escape is categorical" ($reparseOutput -join "`n") "outdir-relative-reparse-point"
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
    if ([IO.Directory]::Exists($fixtureJunction)) {
        [IO.Directory]::Delete($fixtureJunction, $false)
    }
    if ([IO.Directory]::Exists($reparseFixtureRoot)) {
        [IO.Directory]::Delete($reparseFixtureRoot, $true)
    }
    if ([IO.Directory]::Exists($reparseOutsideRoot)) {
        [IO.Directory]::Delete($reparseOutsideRoot, $true)
    }
}

Write-Host "[AWX][rewrite-plan-diagnostics-probe-tests] ok"

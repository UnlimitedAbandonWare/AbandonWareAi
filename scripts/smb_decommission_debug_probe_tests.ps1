$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "[smb-debug-probe-test][FAIL] $Name :: $Message"
    }
    Write-Host "[smb-debug-probe-test][PASS] $Name"
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected output to contain '$Needle'; output=$Text"
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Content
    )
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Invoke-Captured {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $lines = & powershell -NoProfile -ExecutionPolicy Bypass @Arguments 2>&1 |
        ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

function New-FakeRepo {
    $root = Join-Path ([IO.Path]::GetTempPath()) ("awx-smb-debug-probe-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'scripts') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $root '__patch_drop__') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'main\java\com\example') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'main\resources') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'src\test\java\com\example') | Out-Null
    Set-TestFile (Join-Path $root '__patch_drop__\janitor_inventory.ps1') @'
Write-Host "[janitor][inventory] source-edit-locks=0"
Write-Host "[janitor][inventory] active-top-level-patches=0"
'@
    Set-TestFile (Join-Path $root 'scripts\awx_mcp_toolbox.py') @'
import json
import sys
from pathlib import Path

tool = sys.argv[-1]
payload = json.load(sys.stdin)
ledger = Path(__file__).with_name("tool-invocations.jsonl")
with ledger.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps({"tool": tool, "payload": payload}, sort_keys=True) + "\n")
if tool == "source_scan":
    print(json.dumps({
        "ok": True,
        "decision": "read_only_probe",
        "activeSourceSets": {
            "mainJava": {"exists": True, "fileCount": 2},
            "mainResources": {"exists": True, "fileCount": 1}
        },
        "patchDrop": {"exists": True, "topLevelPatchCount": 0, "pendingProducerCount": 1},
        "secretPatternHits": 0
    }))
elif tool == "desktop_control_loop":
    print(json.dumps({
        "ok": True,
        "localReady": True,
        "completionReady": False,
        "desktopFinalProof": "evidence_needed",
        "externalEvidenceComplete": False,
        "sourceScan": {
            "decision": "read_only_probe",
            "activeSourceSets": {
                "mainJava": {"exists": True, "fileCount": 2},
                "mainResources": {"exists": True, "fileCount": 1}
            },
            "patchDrop": {"exists": True, "topLevelPatchCount": 0, "pendingProducerCount": 1},
            "secretPatternHits": 0
        },
        "dispatch": {
            "producerBundlesRequired": False,
            "externalEvidenceMode": "optional",
            "nextActions": []
        },
        "nextActions": [],
        "optionalNextActions": [{"action": "assign-producer-role-pathspec"}],
        "supportingEvidenceNeeded": ["external node smoke missing role=macmini"],
        "evidence_needed": ["boot/live TraceStore export for runtime-only coverage"],
        "rawSecretPatternHits": 0,
        "decision": "external_evidence_needed"
    }))
elif tool == "supabase_context_probe":
    print(json.dumps({
        "ok": True,
        "projectScope": {"status": "project_ref_missing", "readOnlyMode": True},
        "dbSnapshotPlan": {"mutationAllowed": False},
        "evidence_needed": ["Supabase MCP project_ref missing / set SUPABASE_PROJECT_REF after selecting the target project"],
        "rawSecretPatternHits": 0,
        "decision": "supabase_context_probe"
    }))
else:
    print(json.dumps({"ok": False, "decision": "unknown-tool", "failReason": tool}))
    sys.exit(2)
'@
    Set-TestFile (Join-Path $root 'var\codex-smoke\smb-decommission\browser-viewer-current-proof.json') @'
{
  "schemaVersion": "awx.local.browser_debug_viewer_proof.v1",
  "ok": true,
  "decision": "ok",
  "localhost": true,
  "publicDomain": false,
  "storesRawUrl": false,
  "screenshotCaptured": true,
  "storesScreenshotPath": false,
  "screenshotByteCount": 12345,
  "rawSecretPatternHits": 0,
  "windowsAbsPathHits": 0
}
'@
    Set-TestFile (Join-Path $root 'var\codex-smoke\computer-use-smoke.json') @'
{
  "schemaVersion": "awx.local.computer_use_smoke.v1",
  "ok": true,
  "decision": "computer_use_gui_reachable",
  "reachable": true,
  "guiOnly": true,
  "countOnly": true,
  "storesRawAppNames": false,
  "storesWindowTitles": false,
  "appCount": 40,
  "targetableWindowCount": 8,
  "rawSecretPatternHits": 0,
  "helperSecretPatternHits": 0
}
'@
    Set-TestFile (Join-Path $root 'var\codex-smoke\goal-next-auto.status.json') @'
{
  "schemaVersion": "awx.goal_next_auto.status.v1",
  "statusDecision": "desktop_only_ready",
  "failureClassification": "",
  "staleLatest": false,
  "firstAction": "",
  "secretHits": 0,
  "computerUse": {
    "decision": "evidence_needed",
    "safePendingProof": true,
    "secretHits": 0
  },
  "browserUse": {
    "decision": "ok",
    "safePendingProof": false,
    "secretHits": 0
  },
  "traceMemoryRuntimeProof": {
    "decision": "ok",
    "present": true,
    "parsed": true,
    "ok": true,
    "status": 200,
    "stale": false,
    "requireTraceMemory": true,
    "seedsSelfProbe": true,
    "traceMemoryPresent": true,
    "traceMemoryRouteDecision": "retry_failsoft_degrade_warn_live_failsoft",
    "traceMemoryCfvmOffered": "true",
    "traceMemoryCfvmPatternId": "4286282189",
    "traceMemorySeedStatus": 200,
    "mutationAllowed": false,
    "storesRawPrompt": false,
    "storesRawModel": false,
    "storesRawSsePayload": false,
    "secretHits": 0,
    "rawPromptHits": 0,
    "rawModelHits": 0,
    "evidenceNeeded": []
  },
  "externalInputGate": {
    "status": "local_or_unknown",
    "action": "",
    "mutationAllowed": false,
    "secretHits": 0
  }
}
'@
    Set-TestFile (Join-Path $root 'verification\source-health-scorecard.json') @'
{
  "schemaVersion": "awx.source_health_scorecard.v1",
  "decision": "source_health_scorecard",
  "strictEvidenceAdjustedScore": 78.1425,
  "riskCount": 5,
  "activeRiskCount": 0,
  "evidenceNeededCount": 1,
  "failurePatternPrediction": {
    "failurePatternKind": "cross_subsystem_concentration",
    "patternId": "FP-S01S08-CROSS-CONCENTRATION"
  },
  "completionAuditRequirementEvidenceNeededDetailMode": "compact",
  "completionAuditRequirementEvidenceNeededCompactRowCount": 3,
  "completionAuditRequirementEvidenceNeededDetailsOmitted": 9,
  "completionAuditRequirementEvidenceNeededMaxLength": 105,
  "completionAuditRequirementEvidenceNeededDetailHint": "rerun with --include-supporting-next-actions for full requirement evidence text",
  "localInteractionProof": {
    "computerUse": {
      "decision": "evidence_needed",
      "safePendingProof": true,
      "secretPatternHits": 0
    },
    "browserUse": {
      "decision": "ok",
      "safePendingProof": false,
      "secretPatternHits": 0
    }
  },
  "rawSecretPatternHits": 0
}
'@
    Set-TestFile (Join-Path $root 'pasted-text-1.txt') @'
plain intro
TraceStore MDC probe boundary
Soak Plan DSL CFVM recovery probe
Browser Computer Supabase tags remain evidence lanes
Breadcrumb and Probe row
'@
    $headerLabelOnly = 'Authorization' + ':'
    Set-TestFile (Join-Path $root 'main\java\com\example\TraceHarness.java') @"
class TraceHarness {
  // TraceStore MDC source probe raw line must not render
  // TraceStore $headerLabelOnly header label only must not count as a raw secret
  void debug() {}
}
"@
    $fixtureAuthValue = 'Bearer' + ' ' + ('A' * 24)
    Set-TestFile (Join-Path $root 'src\test\java\com\example\TraceFixtureTest.java') @"
class TraceFixtureTest {
  // TraceStore redaction fixture exercises $fixtureAuthValue without storing a live token
}
"@
    Set-TestFile (Join-Path $root 'scripts\smb_decommission_debug_probe_tests.ps1') @"
# TraceStore script redaction fixture exercises $fixtureAuthValue without storing a live token
"@
    Set-TestFile (Join-Path $root 'main\resources\probe.yml') @'
plan: Plan DSL CFVM Breadcrumb source raw line must not render
'@
    return $root
}

$ScriptPath = Join-Path $PSScriptRoot 'smb_decommission_debug_probe.ps1'

$help = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Help')
Assert-True 'help exits zero' ($help.ExitCode -eq 0) "exit=$($help.ExitCode) output=$($help.Output)"
Assert-Contains 'help names source scan' $help.Output 'source_scan'
Assert-Contains 'help names desktop control loop' $help.Output 'desktop_control_loop'
Assert-Contains 'help names Supabase probe' $help.Output 'supabase_context_probe'
Assert-Contains 'help explains no dispatch writes' $help.Output 'write_dispatch=false'

$fakeRoot = New-FakeRepo
$outputDir = Join-Path $fakeRoot 'var\codex-smoke\smb-decommission'
$attachmentPath = Join-Path $fakeRoot 'pasted-text-1.txt'
$run = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Root', $fakeRoot, '-OutputDir', $outputDir, '-Topic', 'desktop-only-test', '-AttachmentPath', $attachmentPath, '-AttachmentSampleCount', '3')

Assert-True 'probe exits zero' ($run.ExitCode -eq 0) "exit=$($run.ExitCode) output=$($run.Output)"
Assert-Contains 'probe reports desktop-only decision' $run.Output 'decision=desktop_only_probe'

$summaryPath = Join-Path $outputDir 'smb-decommission-debug-probe.summary.json'
Assert-True 'probe writes summary' (Test-Path $summaryPath) "missing $summaryPath"
$summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
$viewerPath = Join-Path $outputDir 'smb-decommission-debug-probe.viewer.html'
Assert-True 'probe writes browser viewer' (Test-Path $viewerPath) "missing $viewerPath"
$viewerHtml = Get-Content -Raw -LiteralPath $viewerPath

Assert-True 'summary schema version' ([string]$summary.schemaVersion -eq 'awx.smb_decommission_debug_probe.v1') ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary accepts sanitized nested source scan without redundant ok field' (
    $summary.ok -eq $true -and $summary.sourceScan.ok -eq $true
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary is mutation-free' ($summary.mutationAllowed -eq $false -and $summary.writeDispatch -eq $false -and $summary.writeProducerKit -eq $false) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary preserves Desktop local readiness' ($summary.desktopControlLoop.localReady -eq $true -and $summary.desktopControlLoop.completionReady -eq $false) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary makes producer proof optional' ($summary.desktopControlLoop.producerBundlesRequired -eq $false -and [string]$summary.desktopControlLoop.externalEvidenceMode -eq 'optional') ($summary | ConvertTo-Json -Depth 20 -Compress)
$invocations = @(Get-Content -LiteralPath (Join-Path $fakeRoot 'scripts\tool-invocations.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$sourceScanInvocations = @($invocations | Where-Object { [string]$_.tool -eq 'source_scan' })
$desktopInvocation = @($invocations | Where-Object { [string]$_.tool -eq 'desktop_control_loop' }) | Select-Object -First 1
$supabaseInvocation = @($invocations | Where-Object { [string]$_.tool -eq 'supabase_context_probe' }) | Select-Object -First 1
Assert-True 'probe reuses control-loop source scan without a second process' (
    @($sourceScanInvocations).Count -eq 0 -and
    [string]$summary.sourceScan.evidenceSource -eq 'desktop_control_loop.sourceScan'
) ($invocations | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'probe defers nested completion and runtime network work' (
    $desktopInvocation.payload.run_completion_audit -eq $false -and
    $desktopInvocation.payload.runtimeProof.traceStoreExportOk -eq $false -and
    $desktopInvocation.payload.runtimeProof.agentDbSnapshotOk -eq $false
) ($desktopInvocation | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'Supabase context remains local-only by default' (
    $supabaseInvocation.payload.skip_mcp_network_probe -eq $true -and
    $summary.supabase.networkProbeRequested -eq $false
) ($supabaseInvocation | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary classifies empty top-level PatchDrop as Desktop unblocked' (
    [string]$summary.patchDropManualDefault.mode -eq 'manual' -and
    [string]$summary.patchDropManualDefault.sourceOwnership -eq 'desktop_unblocked' -and
    [string]$summary.patchDropManualDefault.producerEvidence -eq 'supporting' -and
    [int]$summary.patchDropManualDefault.topLevelPatchCount -eq 0 -and
    [int]$summary.patchDropManualDefault.pendingProducerCount -eq 1
) ($summary | ConvertTo-Json -Depth 20 -Compress)

Assert-True 'summary keeps Supabase read-only evidence-needed' ([string]$summary.supabase.projectScopeStatus -eq 'project_ref_missing' -and $summary.supabase.mutationAllowed -eq $false) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary ingests browser proof as supporting evidence' (
    [string]$summary.browser.status -eq 'verified_supporting' -and
    [string]$summary.browser.schemaVersion -eq 'awx.local.browser_debug_viewer_proof.v1' -and
    $summary.browser.localhost -eq $true -and
    $summary.browser.rawUrlStored -eq $false -and
    $summary.browser.screenshotStored -eq $false -and
    [int]$summary.browser.screenshotByteLength -eq 12345
) ($summary | ConvertTo-Json -Depth 20 -Compress)

$defaultBrowserProof = Join-Path $fakeRoot 'var\codex-smoke\smb-decommission\browser-viewer-current-proof.json'
$defaultComputerProof = Join-Path $fakeRoot 'var\codex-smoke\computer-use-smoke.json'
$latestBrowserProof = Join-Path $outputDir 'browser-viewer-proof-continuation.json'
$latestComputerProof = Join-Path $outputDir 'computer-count-proof-continuation.json'
Set-TestFile $latestBrowserProof @'
{
  "schemaVersion": "awx.local.browser_debug_viewer_proof.v1",
  "ok": true,
  "decision": "latest-output-dir-browser-proof",
  "localhost": true,
  "publicDomain": false,
  "storesRawUrl": false,
  "screenshotCaptured": true,
  "storesScreenshotPath": false,
  "screenshotByteCount": 67890,
  "rawSecretPatternHits": 0,
  "windowsAbsPathHits": 0
}
'@
Set-TestFile $latestComputerProof @'
{
  "schemaVersion": "awx.local.computer_use_count_probe.v1",
  "ok": true,
  "decision": "latest-output-dir-computer-proof",
  "reachable": true,
  "guiOnly": true,
  "countOnly": true,
  "storesRawAppNames": false,
  "storesWindowTitles": false,
  "appCount": 7,
  "targetableWindowCount": 9,
  "rawSecretPatternHits": 0,
  "helperSecretPatternHits": 0
}
'@
(Get-Item -LiteralPath $defaultBrowserProof).LastWriteTimeUtc = ([DateTime]::UtcNow).AddMinutes(-10)
(Get-Item -LiteralPath $defaultComputerProof).LastWriteTimeUtc = ([DateTime]::UtcNow).AddMinutes(-10)
(Get-Item -LiteralPath $latestBrowserProof).LastWriteTimeUtc = ([DateTime]::UtcNow).AddMinutes(10)
(Get-Item -LiteralPath $latestComputerProof).LastWriteTimeUtc = ([DateTime]::UtcNow).AddMinutes(10)
$latestSidecarRun = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Root', $fakeRoot, '-OutputDir', $outputDir, '-Topic', 'desktop-only-test', '-AttachmentPath', $attachmentPath, '-AttachmentSampleCount', '3')
Assert-True 'probe exits zero with latest output-dir proof sidecars' ($latestSidecarRun.ExitCode -eq 0) "exit=$($latestSidecarRun.ExitCode) output=$($latestSidecarRun.Output)"
$latestSidecarSummary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
$latestSidecarViewer = Get-Content -Raw -LiteralPath $viewerPath
Assert-True 'summary selects latest browser proof sidecar from output dir' (
    [string]$latestSidecarSummary.browser.status -eq 'verified_supporting' -and
    [string]$latestSidecarSummary.browser.artifact -eq 'browser-viewer-proof-continuation.json' -and
    [int]$latestSidecarSummary.browser.screenshotByteLength -eq 67890
) ($latestSidecarSummary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary selects latest computer proof sidecar from output dir' (
    [string]$latestSidecarSummary.computer.status -eq 'verified_supporting' -and
    [string]$latestSidecarSummary.computer.artifact -eq 'computer-count-proof-continuation.json' -and
    [int]$latestSidecarSummary.computer.appCount -eq 7 -and
    [int]$latestSidecarSummary.computer.targetableWindowCount -eq 9
) ($latestSidecarSummary | ConvertTo-Json -Depth 20 -Compress)
Assert-Contains 'viewer exposes latest browser proof artifact' $latestSidecarViewer 'browser-viewer-proof-continuation.json'
Assert-Contains 'viewer exposes latest computer proof artifact' $latestSidecarViewer 'computer-count-proof-continuation.json'

Set-TestFile $latestComputerProof @'
{
  "schemaVersion": "awx.local.computer_use_smoke.v1",
  "ok": false,
  "decision": "evidence_needed",
  "safePendingProof": true,
  "reachable": true,
  "guiOnly": true,
  "countOnly": true,
  "helperCountOnly": true,
  "storesRawAppNames": false,
  "storesWindowTitles": false,
  "appCount": 0,
  "targetableWindowCount": 0,
  "evidenceNeeded": "computer_use_zero_count_supporting_evidence_missing",
  "rawSecretPatternHits": 0,
  "helperSecretPatternHits": 0
}
'@
$safePendingComputerOutputDir = Join-Path $fakeRoot 'var\codex-smoke\smb-decommission-safe-pending-computer'
$safePendingComputerRun = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Root', $fakeRoot, '-OutputDir', $safePendingComputerOutputDir, '-Topic', 'desktop-only-test', '-AttachmentPath', $attachmentPath, '-AttachmentSampleCount', '3', '-ComputerProofPath', $latestComputerProof)
Assert-True 'probe exits zero with Computer safe-pending proof' ($safePendingComputerRun.ExitCode -eq 0) "exit=$($safePendingComputerRun.ExitCode) output=$($safePendingComputerRun.Output)"
$safePendingComputerSummary = Get-Content -Raw -LiteralPath (Join-Path $safePendingComputerOutputDir 'smb-decommission-debug-probe.summary.json') | ConvertFrom-Json
Assert-True 'summary keeps Computer safe-pending proof as supporting evidence missing' (
    [string]$safePendingComputerSummary.computer.status -eq 'safe_pending_supporting' -and
    $safePendingComputerSummary.computer.ok -eq $false -and
    $safePendingComputerSummary.computer.reachable -eq $true -and
    $safePendingComputerSummary.computer.safePendingProof -eq $true -and
    [string]$safePendingComputerSummary.computer.evidenceNeeded -eq 'computer_use_zero_count_supporting_evidence_missing' -and
    [int]$safePendingComputerSummary.computer.rawSecretPatternHits -eq 0
) ($safePendingComputerSummary | ConvertTo-Json -Depth 20 -Compress)

$rawWindowTitleComputerProof = Join-Path $outputDir 'computer-raw-window-title-proof.json'
Set-TestFile $rawWindowTitleComputerProof @'
{
  "schemaVersion": "awx.local.computer_use_count_probe.v1",
  "ok": true,
  "decision": "raw-window-title-proof",
  "reachable": true,
  "guiOnly": true,
  "countOnly": true,
  "storesRawAppNames": false,
  "storesWindowTitles": false,
  "rawWindowTitlesStored": true,
  "appCount": 4,
  "targetableWindowCount": 5,
  "rawSecretPatternHits": 0
}
'@
$rawWindowTitleOutputDir = Join-Path $fakeRoot 'var\codex-smoke\smb-decommission-raw-window-title-computer'
$rawWindowTitleRun = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Root', $fakeRoot, '-OutputDir', $rawWindowTitleOutputDir, '-Topic', 'desktop-only-test', '-AttachmentPath', $attachmentPath, '-AttachmentSampleCount', '3', '-ComputerProofPath', $rawWindowTitleComputerProof)
Assert-True 'probe exits zero with raw-window-title Computer proof' ($rawWindowTitleRun.ExitCode -eq 0) "exit=$($rawWindowTitleRun.ExitCode) output=$($rawWindowTitleRun.Output)"
$rawWindowTitleSummary = Get-Content -Raw -LiteralPath (Join-Path $rawWindowTitleOutputDir 'smb-decommission-debug-probe.summary.json') | ConvertFrom-Json
Assert-True 'summary rejects Computer proof that stored raw window titles' (
    [string]$rawWindowTitleSummary.computer.status -eq 'proof_present_redaction_risk' -and
    $rawWindowTitleSummary.computer.ok -eq $true -and
    $rawWindowTitleSummary.computer.storesWindowTitles -eq $true -and
    [int]$rawWindowTitleSummary.computer.rawSecretPatternHits -eq 0
) ($rawWindowTitleSummary | ConvertTo-Json -Depth 20 -Compress)

Set-TestFile (Join-Path $fakeRoot 'var\codex-smoke\smb-decommission\browser-viewer-current-proof.json') @'
{
  "schemaVersion": "awx.local.browser_debug_viewer_proof.v1",
  "ok": true,
  "decision": "ok",
  "localhost": true,
  "publicDomain": false,
  "storesRawUrl": false,
  "screenshotCaptured": true,
  "storesScreenshotPath": true,
  "screenshotByteCount": 12345,
  "rawSecretPatternHits": 0,
  "windowsAbsPathHits": 0
}
'@
$unsafeBrowserOutputDir = Join-Path $fakeRoot 'var\codex-smoke\smb-decommission-unsafe-browser'
$unsafeBrowserRun = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Root', $fakeRoot, '-OutputDir', $unsafeBrowserOutputDir, '-Topic', 'desktop-only-test', '-AttachmentPath', $attachmentPath, '-AttachmentSampleCount', '3')
Assert-True 'probe exits zero with browser screenshot-path evidence' ($unsafeBrowserRun.ExitCode -eq 0) "exit=$($unsafeBrowserRun.ExitCode) output=$($unsafeBrowserRun.Output)"
$unsafeBrowserSummaryPath = Join-Path $unsafeBrowserOutputDir 'smb-decommission-debug-probe.summary.json'
$unsafeBrowserSummary = Get-Content -Raw -LiteralPath $unsafeBrowserSummaryPath | ConvertFrom-Json
Assert-True 'summary flags browser screenshot-path storage as redaction risk' (
    [string]$unsafeBrowserSummary.browser.status -eq 'proof_present_redaction_risk' -and
    $unsafeBrowserSummary.browser.screenshotStored -eq $true -and
    [int]$unsafeBrowserSummary.browser.rawSecretPatternHits -eq 0
) ($unsafeBrowserSummary | ConvertTo-Json -Depth 20 -Compress)

$fakeSecret = 'sk-' + ('x' * 24)
Set-TestFile (Join-Path $fakeRoot 'var\codex-smoke\smb-decommission\browser-viewer-current-proof.json') @"
{
  "schemaVersion": "awx.local.browser_debug_viewer_proof.v1",
  "ok": true,
  "decision": "ok-$fakeSecret",
  "localhost": true,
  "publicDomain": false,
  "storesRawUrl": false,
  "screenshotCaptured": true,
  "storesScreenshotPath": false,
  "screenshotByteCount": 12345,
  "rawSecretPatternHits": 0,
  "windowsAbsPathHits": 0
}
"@
$secretProofOutputDir = Join-Path $fakeRoot 'var\codex-smoke\smb-decommission-secret-proof'
$secretProofRun = Invoke-Captured -Arguments @('-File', $ScriptPath, '-Root', $fakeRoot, '-OutputDir', $secretProofOutputDir, '-Topic', 'desktop-only-test', '-AttachmentPath', $attachmentPath, '-AttachmentSampleCount', '3')
Assert-True 'probe redacts secret-like browser proof metadata without failing the local probe' ($secretProofRun.ExitCode -eq 0) "exit=$($secretProofRun.ExitCode) output=$($secretProofRun.Output)"
$secretProofSummaryPath = Join-Path $secretProofOutputDir 'smb-decommission-debug-probe.summary.json'
$secretProofViewerPath = Join-Path $secretProofOutputDir 'smb-decommission-debug-probe.viewer.html'
$secretProofSummaryRaw = Get-Content -Raw -LiteralPath $secretProofSummaryPath
$secretProofViewerRaw = Get-Content -Raw -LiteralPath $secretProofViewerPath
$secretProofSummary = $secretProofSummaryRaw | ConvertFrom-Json
Assert-True 'summary redacts secret-like browser proof metadata and preserves count-only risk' (
    [string]$secretProofSummary.browser.status -eq 'proof_present_redaction_risk' -and
    [string]$secretProofSummary.browser.decision -eq '<redacted-secret-like-value>' -and
    [int]$secretProofSummary.browser.rawSecretPatternHits -gt 0 -and
    [int]$secretProofSummary.inputSecretPatternHits -gt 0 -and
    [int]$secretProofSummary.artifactSecretPatternHits -eq 0 -and
    [int]$secretProofSummary.rawSecretPatternHits -eq 0 -and
    -not $secretProofSummaryRaw.Contains($fakeSecret) -and
    -not $secretProofViewerRaw.Contains($fakeSecret)
) ($secretProofSummary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary ingests computer proof as count-only supporting evidence' (
    [string]$summary.computer.status -eq 'verified_supporting' -and
    [string]$summary.computer.schemaVersion -eq 'awx.local.computer_use_smoke.v1' -and
    $summary.computer.reachable -eq $true -and
    $summary.computer.helperCountOnly -eq $true -and
    $summary.computer.storesRawAppNames -eq $false -and
    $summary.computer.storesWindowTitles -eq $false -and
    [int]$summary.computer.appCount -eq 40 -and
    [int]$summary.computer.targetableWindowCount -eq 8
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary ingests cached goal-next status without rerunning external lanes' (
    [string]$summary.goalNextStatus.status -eq 'observed' -and
    [string]$summary.goalNextStatus.statusDecision -eq 'desktop_only_ready' -and
    $summary.goalNextStatus.staleLatest -eq $false -and
    [string]$summary.goalNextStatus.computerUseDecision -eq 'evidence_needed' -and
    $summary.goalNextStatus.computerUseSafePending -eq $true -and
    [string]$summary.goalNextStatus.browserUseDecision -eq 'ok' -and
    [string]$summary.goalNextStatus.externalInputGateStatus -eq 'local_or_unknown' -and
    $summary.goalNextStatus.mutationAllowed -eq $false
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary ingests trace-memory runtime proof from goal-next cache' (
    [string]$summary.traceMemoryRuntimeProof.status -eq 'verified_runtime' -and
    [string]$summary.traceMemoryRuntimeProof.decision -eq 'ok' -and
    $summary.traceMemoryRuntimeProof.present -eq $true -and
    $summary.traceMemoryRuntimeProof.traceMemoryPresent -eq $true -and
    [string]$summary.traceMemoryRuntimeProof.traceMemoryRouteDecision -eq 'retry_failsoft_degrade_warn_live_failsoft' -and
    [string]$summary.traceMemoryRuntimeProof.traceMemoryCfvmOffered -eq 'true' -and
    [int]$summary.traceMemoryRuntimeProof.traceMemorySeedStatus -eq 200 -and
    $summary.traceMemoryRuntimeProof.mutationAllowed -eq $false -and
    [int]$summary.traceMemoryRuntimeProof.rawSecretPatternHits -eq 0
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary ingests source-health compact requirement debug state' (
    [string]$summary.sourceHealthDebug.status -eq 'observed' -and
    [string]$summary.sourceHealthDebug.requirementEvidenceNeededDetailMode -eq 'compact' -and
    [int]$summary.sourceHealthDebug.requirementEvidenceNeededCompactRowCount -eq 3 -and
    [int]$summary.sourceHealthDebug.requirementEvidenceNeededDetailsOmitted -eq 9 -and
    [int]$summary.sourceHealthDebug.requirementEvidenceNeededMaxLength -eq 105 -and
    [string]$summary.sourceHealthDebug.failurePatternKind -eq 'cross_subsystem_concentration' -and
    [string]$summary.sourceHealthDebug.patternId -eq 'FP-S01S08-CROSS-CONCENTRATION' -and
    $summary.sourceHealthDebug.localInteractionComputerSafePending -eq $true -and
    [string]$summary.sourceHealthDebug.localInteractionBrowserDecision -eq 'ok' -and
    [int]$summary.sourceHealthDebug.rawSecretPatternHits -eq 0
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary maps attachment into hash-only probe plan' (
    [string]$summary.attachmentProbe.status -eq 'sampled_hash_only' -and
    [string]$summary.attachmentProbe.fileName -eq 'pasted-text-1.txt' -and
    [string]$summary.attachmentProbe.sampleSeedHash12 -match '^[0-9A-F]{12}$' -and
    [int]$summary.attachmentProbe.lineCount -eq 5 -and
    [int]$summary.attachmentProbe.keywordCounts.TraceStore -eq 1 -and
    [int]$summary.attachmentProbe.keywordCounts.MDC -eq 1 -and
    [int]$summary.attachmentProbe.keywordCounts.Soak -eq 1 -and
    [int]$summary.attachmentProbe.keywordCounts.Probe -eq 1 -and
    [int]$summary.attachmentProbe.keywordCounts.'Plan DSL' -eq 1 -and
    [int]$summary.attachmentProbe.keywordCounts.CFVM -eq 1 -and
    @($summary.attachmentProbe.samples).Count -eq 3
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'attachment samples are redacted hashes not raw lines' (
    $null -eq $summary.attachmentProbe.samples[0].rawText -and
    [string]$summary.attachmentProbe.samples[0].sha12 -match '^[0-9A-F]{12}$' -and
    [int]$summary.attachmentProbe.rawSecretPatternHits -eq 0
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary maps attachment terms to source hash-only probe targets' (
    [string]$summary.sourceProbe.status -eq 'sampled_hash_only' -and
    [int]$summary.sourceProbe.scannedFileCount -ge 2 -and
    [string]$summary.sourceProbe.sampleSeedHash12 -match '^[0-9A-F]{12}$' -and
    [int]$summary.sourceProbe.matchedFileCount -ge 2 -and
    [int]$summary.sourceProbe.matchedLineCount -ge 2 -and
    [string]$summary.sourceProbe.engine -match '^(ripgrep|powershell_fallback)$' -and
    @($summary.sourceProbe.samples).Count -ge 2 -and
    @($summary.sourceProbe.samples).Count -le [int]$summary.sourceProbe.sampleLimit -and
    [string]$summary.sourceProbe.samples[0].sha12 -match '^[0-9A-F]{12}$' -and
    $null -eq $summary.sourceProbe.samples[0].rawText -and
    $null -eq $summary.sourceProbe.samples[0].absolutePath
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'source probe keeps test fixture secret-like hits out of input secret total' (
    [int]$summary.sourceProbe.rawSecretPatternHits -eq 0 -and
    [int]$summary.sourceProbe.testFixtureSecretPatternHits -eq 2 -and
    [int]$summary.inputSecretPatternHits -eq 0
) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary has no primary external next actions' (@($summary.nextActions).Count -eq 0) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary counts no secret patterns' ([int]$summary.rawSecretPatternHits -eq 0) ($summary | ConvertTo-Json -Depth 20 -Compress)
Assert-True 'summary is token safe' (-not ((Get-Content -Raw -LiteralPath $summaryPath) -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|SUPABASE_ACCESS_TOKEN\s*=|TraceStore MDC probe boundary|Soak Plan DSL CFVM recovery probe|TraceStore MDC source probe raw line|Plan DSL CFVM Breadcrumb source raw line')) (Get-Content -Raw -LiteralPath $summaryPath)
Assert-Contains 'viewer exposes probe marker' $viewerHtml 'data-awx-viewer="smb-decommission"'
Assert-Contains 'viewer exposes source ownership' $viewerHtml 'desktop_unblocked'
Assert-Contains 'viewer exposes producer evidence as supporting' $viewerHtml 'supporting'
Assert-Contains 'viewer exposes Supabase evidence-needed lane' $viewerHtml 'project_ref_missing'
Assert-Contains 'viewer exposes browser supporting proof' $viewerHtml 'verified_supporting'
Assert-Contains 'viewer exposes goal-next desktop-only status' $viewerHtml 'desktop_only_ready'
Assert-Contains 'viewer exposes source-health compact detail mode' $viewerHtml 'compact'
Assert-Contains 'viewer exposes trace-memory runtime proof' $viewerHtml 'retry_failsoft_degrade_warn_live_failsoft'
Assert-Contains 'viewer exposes attachment hash-only status' $viewerHtml 'sampled_hash_only'
Assert-Contains 'viewer exposes source hash-only status' $viewerHtml 'Source probe'
Assert-Contains 'viewer exposes source probe engine' $viewerHtml ([string]$summary.sourceProbe.engine)
Assert-Contains 'viewer exposes attachment sample seed' $viewerHtml ([string]$summary.attachmentProbe.sampleSeedHash12)
Assert-Contains 'viewer exposes source sample seed' $viewerHtml ([string]$summary.sourceProbe.sampleSeedHash12)
Assert-True 'viewer is token safe' (-not ($viewerHtml -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|SUPABASE_ACCESS_TOKEN\s*=|C:\\|TraceStore MDC probe boundary|Soak Plan DSL CFVM recovery probe|TraceStore MDC source probe raw line|Plan DSL CFVM Breadcrumb source raw line')) $viewerHtml
$eventsPath = Join-Path $outputDir 'smb-decommission-debug-probe.events.ndjson'
Assert-True 'probe writes source probe event' ((Get-Content -Raw -LiteralPath $eventsPath).Contains('source_probe_map')) (Get-Content -Raw -LiteralPath $eventsPath)
Assert-True 'probe writes cached status events' (
    (Get-Content -Raw -LiteralPath $eventsPath).Contains('goal_next_status') -and
    (Get-Content -Raw -LiteralPath $eventsPath).Contains('trace_memory_runtime_proof') -and
    (Get-Content -Raw -LiteralPath $eventsPath).Contains('source_health_debug')
) (Get-Content -Raw -LiteralPath $eventsPath)
$eventRows = Get-Content -LiteralPath $eventsPath | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
$attachmentProbeEvent = $eventRows | Where-Object { $_.toolName -eq 'attachment_probe_map' } | Select-Object -First 1
$sourceProbeEvent = $eventRows | Where-Object { $_.toolName -eq 'source_probe_map' } | Select-Object -First 1
Assert-True 'attachment probe event carries sample seed' (
    [string]$attachmentProbeEvent.sampleSeedHash12 -eq [string]$summary.attachmentProbe.sampleSeedHash12 -and
    [string]$attachmentProbeEvent.sampleSeedHash12 -match '^[0-9A-F]{12}$'
) ($attachmentProbeEvent | ConvertTo-Json -Depth 10 -Compress)
Assert-True 'source probe event carries sample seed' (
    [string]$sourceProbeEvent.sampleSeedHash12 -eq [string]$summary.sourceProbe.sampleSeedHash12 -and
    [string]$sourceProbeEvent.sampleSeedHash12 -match '^[0-9A-F]{12}$'
) ($sourceProbeEvent | ConvertTo-Json -Depth 10 -Compress)

Write-Host '[smb-debug-probe-test][SUMMARY] failed=0'

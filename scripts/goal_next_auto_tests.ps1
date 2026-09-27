$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "[goal-next-test][FAIL] $Name :: $Message"
    }
    Write-Host "[goal-next-test][PASS] $Name"
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected output to contain '$Needle'; output=$Text"
}

function Assert-CompactArtifactLog {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Path
    )
    Assert-True "$Name exists" (Test-Path -LiteralPath $Path -PathType Leaf) "missing compact log=$Path"
    $raw = Get-Content -Raw -LiteralPath $Path
    $row = $raw | ConvertFrom-Json
    Assert-True "$Name uses artifact reference schema" (
        [string]$row.schemaVersion -eq 'awx.goal_next_auto.compact_artifact_log.v1' -and
        $row.rawOutputStored -eq $false -and
        $row.artifactPresent -eq $true -and
        $row.artifactProducedThisRun -eq $true -and
        [string]$row.artifactHash -match '^[0-9a-f]{64}$' -and
        [int64]$row.artifactBytes -gt 0 -and
        [int]$row.secretHits -eq 0 -and
        -not ([string]$row.artifactPath -match '^[A-Za-z]:[\\/]') -and
        (Get-Item -LiteralPath $Path).Length -lt 2048
    ) "compactLog=$raw"
    Assert-True "$Name omits full payload" (
        -not ($raw -match '"checked"\s*:|"requirements"\s*:|"peerEvidenceBus"\s*:')
    ) "compactLog=$raw"
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    Set-Content -LiteralPath $Path -Value $Content -Encoding UTF8
}

function Invoke-Captured {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $lines = & powershell -NoProfile -ExecutionPolicy Bypass @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

function New-FakeGoalRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Mode,
        [switch]$OmitEmbeddedPeerEvidenceBus,
        [switch]$MalformedEmbeddedPeerEvidenceBus,
        [switch]$TruncatedEmbeddedPeerEvidenceBus,
        [switch]$IncompleteEmbeddedPeerWebProbe,
        [switch]$EmbeddedPeerJdbcSecret,
        [switch]$FailWebProbeRefresh
    )
    $root = Join-Path ([IO.Path]::GetTempPath()) ("awx-goal-next-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'scripts') | Out-Null
    Set-TestFile (Join-Path $root 'mode.txt') $Mode
    if ($OmitEmbeddedPeerEvidenceBus) {
        Set-TestFile (Join-Path $root 'omit-embedded-peer-evidence-bus.txt') '1'
    }
    if ($MalformedEmbeddedPeerEvidenceBus) {
        Set-TestFile (Join-Path $root 'malformed-embedded-peer-evidence-bus.txt') '1'
    }
    if ($TruncatedEmbeddedPeerEvidenceBus) {
        Set-TestFile (Join-Path $root 'truncated-embedded-peer-evidence-bus.txt') '1'
    }
    if ($IncompleteEmbeddedPeerWebProbe) {
        Set-TestFile (Join-Path $root 'incomplete-embedded-peer-web-probe.txt') '1'
    }
    if ($EmbeddedPeerJdbcSecret) {
        Set-TestFile (Join-Path $root 'embedded-peer-jdbc-secret.txt') '1'
    }
    if ($FailWebProbeRefresh) {
        Set-TestFile (Join-Path $root 'fail-web-probe-refresh.txt') '1'
    }
    Set-TestFile (Join-Path $root '.mcp.json') @'
{
  "mcpServers": {
    "supabase": {
      "type": "http",
      "url": "https://mcp.supabase.com/mcp?read_only=true&features=database,debugging,docs&project_ref=${SUPABASE_PROJECT_REF}"
    }
  },
  "notes": [
    "Authenticate through the MCP client flow; do not store Supabase tokens in this file."
  ]
}
'@
    if ($Mode -eq 'patchdrop') {
        Set-TestFile (Join-Path $root '__patch_drop__\pending-safe.patch') @'
diff --git a/main/resources/example.yml b/main/resources/example.yml
--- a/main/resources/example.yml
+++ b/main/resources/example.yml
@@ -1 +1 @@
-enabled: false
+enabled: true
'@
    }
    if ($Mode -eq 'leaseheld') {
        $expires = [DateTime]::UtcNow.AddHours(1).ToString('o')
        Set-TestFile (Join-Path $root '__patch_drop__\source-edit-locks\active-topic.lock\lease.json') @"
{
  "topic": "active-topic",
  "ownerId": "macmini-codex",
  "role": "macmini",
  "startedAtUtc": "2026-01-01T00:00:00.0000000Z",
  "expiresAtUtc": "$expires"
}
"@
    }
    if ($Mode -eq 'leaseexpiredlegacy') {
        $expires = [DateTime]::UtcNow.AddHours(-1).ToString('o')
        Set-TestFile (Join-Path $root '__patch_drop__\source-edit-locks\expired-topic.lock\lease.json') @"
{
  "topic": "expired-topic",
  "ownerId": "desktop-codex",
  "role": "desktop",
  "expiresAt": "$expires"
}
"@
    }
    if ($Mode -eq 'leasemissingexpiry') {
        Set-TestFile (Join-Path $root '__patch_drop__\source-edit-locks\missing-expiry-topic.lock\lease.json') @'
{
  "topic": "missing-expiry-topic",
  "ownerId": "desktop-codex",
  "role": "desktop"
}
'@
    }
    if ($Mode -eq 'leasecorrupt') {
        New-Item -ItemType Directory -Force -Path (Join-Path $root '__patch_drop__\source-edit-locks\corrupt-topic.lock') | Out-Null
    }
    if ($Mode -ne 'secret') {
        $computerGeneratedAt = if ($Mode -eq 'computerstale') { [DateTime]::UtcNow.AddHours(-2).ToString('o') } else { [DateTime]::UtcNow.ToString('o') }
        Set-TestFile (Join-Path $root 'var\codex-smoke\computer-use-smoke.json') @"
{
  "schemaVersion": "awx.computer_use.smoke.v1",
  "generatedAt": "$computerGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "appCount": 3,
  "runningCount": 2,
  "windowCount": 4,
  "helperCountOnly": true,
  "probeSchemaVersion": "awx.local.computer_use_count_probe.v1",
  "sampleApps": [
    {"id": "MSEdge", "displayName": "Microsoft Edge", "windowCount": 1}
  ],
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        $browserGeneratedAt = if ($Mode -eq 'browserstale') { [DateTime]::UtcNow.AddHours(-2).ToString('o') } else { [DateTime]::UtcNow.ToString('o') }
        Set-TestFile (Join-Path $root 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "schemaVersion": "awx.browser_ui.smoke.v1",
  "generatedAt": "$browserGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "localhost": true,
  "screenshotCaptured": true,
  "statusClass": "ui_visible",
  "targetContentVisible": true,
  "browserSurface": "iab",
  "url": "http://localhost:8080/pipeline-status",
  "screenshotPath": "C:\\unsafe\\browser-ui-smoke.png",
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        Set-TestFile (Join-Path $root 'verification\chat-debug-fx-sse-trace-memory-required\chat-debug-fx-sse.json') @'
{
  "summary": {
    "schemaVersion": "awx.chat_debug_fx_sse.summary.v1",
    "ok": true,
    "status": 200,
    "debugFxPresent": true,
    "operatorActionPresent": true,
    "traceMemoryRequired": true,
    "traceMemoryPresent": true,
    "traceMemoryRouteDecision": "retry_failsoft_degrade_warn_live_failsoft",
    "traceMemoryCfvmOffered": "true",
    "traceMemoryCfvmPatternId": "1985725498",
    "traceMemoryVirtualCheckpointKey": "traceMemory.virtualCheckpoint.second_refinement",
    "traceMemoryVirtualCheckpointStage": "second_refinement",
    "traceMemoryVirtualCheckpointPhase": "post_load",
    "traceMemorySeedStatus": 200,
    "traceMemorySeedRouteDecision": "retry_failsoft_degrade_warn_live_failsoft",
    "eventTypes": ["status", "transformer", "session", "thought", "debug_fx"],
    "eventCount": 7,
    "secretPatternHits": 0,
    "rawPromptHits": 0,
    "rawModelHits": 0
  }
}
'@
        Set-TestFile (Join-Path $root 'verification\chat-debug-fx-sse-trace-memory-required\chat-debug-fx-sse.summary.txt') @'
ok=True
status=200
traceMemoryPresent=True
traceMemoryRouteDecision=retry_failsoft_degrade_warn_live_failsoft
traceMemoryCfvmOffered=true
traceMemoryCfvmPatternId=1985725498
traceMemoryVirtualCheckpointKey=traceMemory.virtualCheckpoint.second_refinement
traceMemoryVirtualCheckpointStage=second_refinement
traceMemoryVirtualCheckpointPhase=post_load
secretPatternHits=0
rawPromptHits=0
rawModelHits=0
'@
    }

    Set-TestFile (Join-Path $root 'scripts\smoke_supabase_readonly_snapshot.ps1') @'
param([string]$Root, [string]$OutputDir, [switch]$RequireProjectScope)
Add-Content -LiteralPath (Join-Path $Root 'tool-invocations.jsonl') -Value 'supabase_smoke' -Encoding UTF8
$mode = (Get-Content -Raw -LiteralPath (Join-Path $Root 'mode.txt')).Trim()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') {
    [ordered]@{
        schemaVersion = 'awx.supabase.readonly_snapshot_smoke.summary.v1'
        ok = $true
        decision = 'ok'
        projectScopeStatus = 'project_ref_ready'
        mcpReachable = $true
        mcpProbeSkipped = $false
        mcpDecision = 'mcp_endpoint_auth_required'
        mcpEndpointReachabilityEvidence = 'reachable_auth_required'
        evidenceNeeded = @()
        secretHits = 0
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'supabase-readonly-snapshot.summary.json') -Encoding UTF8
    Write-Host '[fake-smoke] ok=true projectScopeStatus=project_ref_ready'
    exit 0
}
[ordered]@{
    schemaVersion = 'awx.supabase.readonly_snapshot_smoke.summary.v1'
    ok = $false
    decision = 'evidence_needed'
    projectScopeStatus = 'project_ref_missing'
    mcpReachable = $false
    mcpProbeSkipped = $true
    mcpDecision = 'mcp_endpoint_probe_skipped'
    mcpEndpointReachabilityEvidence = 'probe_skipped'
    evidenceNeeded = @('project_ref_missing')
    secretHits = 0
    rawSecretPatternHits = 0
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'supabase-readonly-snapshot.summary.json') -Encoding UTF8
Write-Host '[fake-smoke] evidence_needed: project_ref_missing'
exit 2
'@

    Set-TestFile (Join-Path $root 'scripts\supabase_apply_collected_evidence.ps1') @'
param([string]$Root, [string]$OutputDir, [switch]$OrchestratedByGoalNext)
Add-Content -LiteralPath (Join-Path $Root 'tool-invocations.jsonl') -Value 'supabase_apply' -Encoding UTF8
$mode = (Get-Content -Raw -LiteralPath (Join-Path $Root 'mode.txt')).Trim()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($mode -eq 'secret') {
    Write-Host ('Bearer ' + 'abcdefghijklmnopqrstuvwxyz' + '123456')
    exit 4
}
[ordered]@{
    schemaVersion = 'awx.supabase.apply_collected_evidence.summary.v1'
    orchestratedByGoalNext = [bool]$OrchestratedByGoalNext
    ok = ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt')
    decision = if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') { 'ok' } else { 'evidence_needed' }
    requiredMcpTools = @('execute_sql','get_advisors')
    requiredResultNames = @('schemas_and_tables','rls_and_table_flags','policies')
    nextActions = @('set_SUPABASE_PROJECT_REF','collect_get_advisors_rows')
    evidenceNeeded = @('env_missing:SUPABASE_PROJECT_REF')
    resultPathRecommendation = 'data/db-gap-report/supabase-query-results.json'
    advisorResultPathRecommendation = 'data/db-gap-report/supabase-advisors.json'
    readOnlyMcpEndpointTemplate = 'https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs'
    projectRefEnvPresent = $false
    accessTokenEnvPresent = $false
    envPreflightStatus = 'missing_project_ref_and_mcp_auth'
    mcpOAuthSupported = $true
    accessTokenManualFallbackEnvPresent = $false
    supportedAuthModes = @('supabase_mcp_oauth_session','manual_SUPABASE_ACCESS_TOKEN')
    secretHits = 0
    rawSecretPatternHits = 0
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'supabase-apply-collected.summary.json') -Encoding UTF8
if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') {
    Write-Host '[fake-supabase-apply] ok=true'
    exit 0
}
Write-Host '[fake-supabase-apply] evidence_needed: missing result sets'
exit 2
'@

    Set-TestFile (Join-Path $root 'scripts\external_apply_collected_evidence.ps1') @'
param([string]$Root, [string]$OutputDir, [string]$Topic, [switch]$NoRequireProducerBundles, [switch]$OrchestratedByGoalNext)
Add-Content -LiteralPath (Join-Path $Root 'tool-invocations.jsonl') -Value 'external_apply' -Encoding UTF8
$mode = (Get-Content -Raw -LiteralPath (Join-Path $Root 'mode.txt')).Trim()
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
if ($mode -eq 'complete' -or $mode -eq 'patchdrop' -or $mode -eq 'leaseheld' -or $mode -eq 'leasecorrupt') {
    [ordered]@{
        schemaVersion = 'awx.external.apply_collected_evidence.summary.v1'
        orchestratedByGoalNext = [bool]$OrchestratedByGoalNext
        ok = $true
        decision = 'ok'
        topic = $Topic
        requiredRoles = @('macmini','notebook')
        requiredProducerEvidenceFiles = @('macmini-node-smoke.json','macmini-producer-handoff.json','notebook-node-smoke.json','notebook-producer-handoff.json')
        requiredPatchDropSidecars = @('pendingNotice','.manifest.json','.patch','.report.md','.verify.log','.sha256.txt')
        requiredSourceIsolation = [ordered]@{ guard = 'PASS'; sourceRootKind = 'local-worktree'; directCanonicalSourceEdit = $false; desktopFinalProof = 'evidence_needed'; rawSecretPatternHits = 0 }
        nextActions = @()
        applyCollectedEvidenceCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic $Topic"
        evidenceNeeded = @()
        copiedEvidenceCount = 2
        copiedHandoffCount = 2
        secretHits = 0
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'external-apply-collected.summary.json') -Encoding UTF8
    Write-Host '[fake-external-apply] ok=true'
    exit 0
}
$producerBundlesRequired = -not $NoRequireProducerBundles.IsPresent
$externalEvidenceMode = if ($producerBundlesRequired) { 'required' } else { 'optional' }
$decision = if ($producerBundlesRequired) { 'evidence_needed' } else { 'supporting_evidence_missing' }
$ok = -not $producerBundlesRequired
$externalNextActions = @('run_macmini_external_node_smoke','collect_macmini_producer_handoff_json','submit_macmini_patchdrop_v3_bundle_sidecars','run_notebook_external_node_smoke','collect_notebook_producer_handoff_json','submit_notebook_patchdrop_v3_bundle_sidecars')
$externalEvidenceNeeded = @('intake:external node smoke missing role=macmini','audit:external node smoke missing role=notebook')
[ordered]@{
    schemaVersion = 'awx.external.apply_collected_evidence.summary.v1'
    orchestratedByGoalNext = [bool]$OrchestratedByGoalNext
    ok = $ok
    decision = $decision
    topic = $Topic
    producerBundlesRequired = $producerBundlesRequired
    externalEvidenceMode = $externalEvidenceMode
    requiredRoles = @('macmini','notebook')
    requiredProducerEvidenceFiles = @('macmini-node-smoke.json','macmini-producer-handoff.json','notebook-node-smoke.json','notebook-producer-handoff.json')
    requiredPatchDropSidecars = @('pendingNotice','.manifest.json','.patch','.report.md','.verify.log','.sha256.txt')
    requiredSourceIsolation = [ordered]@{ guard = 'PASS'; sourceRootKind = 'local-worktree'; directCanonicalSourceEdit = $false; desktopFinalProof = 'evidence_needed'; rawSecretPatternHits = 0 }
    nextActions = if ($producerBundlesRequired) { $externalNextActions } else { @() }
    supportingEvidenceNextActions = $externalNextActions
    applyCollectedEvidenceCommand = if ($producerBundlesRequired) { "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic $Topic" } else { "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic $Topic -NoRequireProducerBundles" }
    evidenceNeeded = if ($producerBundlesRequired) { $externalEvidenceNeeded } else { @() }
    supportingEvidenceNeeded = if ($producerBundlesRequired) { @() } else { $externalEvidenceNeeded }
    copiedEvidenceCount = 0
    copiedHandoffCount = 0
    secretHits = 0
    rawSecretPatternHits = 0
} | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $OutputDir 'external-apply-collected.summary.json') -Encoding UTF8
if ($producerBundlesRequired) {
    Write-Host '[fake-external-apply] evidence_needed: producer evidence missing'
    exit 2
}
Write-Host '[fake-external-apply] supporting_evidence_missing: producer evidence missing'
exit 0
'@

    Set-TestFile (Join-Path $root 'scripts\source_health_scorecard.py') @'
import argparse, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root')
parser.add_argument('--output')
args = parser.parse_args()
root = Path(args.root)
current_audit = root / 'var' / 'codex-smoke' / 'awx-mcp-completion-audit-current.json'
current_audit_exists_before_source_health = current_audit.is_file()
latest_path = root / 'var' / 'codex-smoke' / 'goal-next-auto.latest.json'

def load_json(path):
    try:
        if path and Path(path).is_file():
            return json.loads(Path(path).read_text(encoding='utf-8-sig'))
    except Exception:
        return {}
    return {}

def resolve_packet_path(raw):
    if not raw:
        return None
    p = Path(str(raw))
    if not p.is_absolute():
        p = root / p
    return p

latest = load_json(latest_path)
command_packet = load_json(resolve_packet_path(latest.get("commandPacketPath"))) if latest else {}
collection_packet = load_json(resolve_packet_path(latest.get("collectionPacketPath"))) if latest else {}
external_gate = command_packet.get("externalInputGate") if isinstance(command_packet, dict) else {}
if not isinstance(external_gate, dict):
    external_gate = {}
external_input_gate_ready = (
    external_gate.get("status") == "external_input_needed"
    and external_gate.get("action") == "set_SUPABASE_PROJECT_REF"
    and external_gate.get("localPatchJustified") is False
    and external_gate.get("mutationAllowed") is False
)
collection_supabase = collection_packet.get("supabase") if isinstance(collection_packet, dict) else {}
collection_external = collection_packet.get("external") if isinstance(collection_packet, dict) else {}
if not isinstance(collection_supabase, dict):
    collection_supabase = {}
if not isinstance(collection_external, dict):
    collection_external = {}
supabase_required_env_names = list(collection_supabase.get("requiredEnvNames") or [])
external_required_roles = list(collection_external.get("requiredRoles") or [])
collection_packet_ready = (
    "SUPABASE_PROJECT_REF" in supabase_required_env_names
    and "macmini" in external_required_roles
    and "notebook" in external_required_roles
)
producer_validation_queue = {
    "schema": "producer_validation_queue.v1",
    "method": "riskLedger_componentScores_offline_twpm_cvar_riskk",
    "maxDurationHours": 9,
    "runtimeProductBehavior": False,
    "externalEvidenceOnlyComponentIds": [
        "supabase_external_evidence"
    ],
    "componentScoreInputs": [
        {"componentId": "harmony_pressure", "normalized": 0.78, "confidence": 0.9, "weightedPoints": 18.0, "pressureScore": 0.62},
        {"componentId": "supabase_external_evidence", "normalized": 0.2, "confidence": 0.4, "weightedPoints": 3.0, "pressureScore": 0.8}
    ],
    "assignments": [
        {
            "queueRank": 1,
            "producerRole": "macmini",
            "sourceRootKind": "local-worktree",
            "directCanonicalSourceEdit": False,
            "evidenceOnly": True,
            "sourceRiskId": "cross_subsystem_concentration",
            "failurePatternKind": "cross_subsystem_concentration",
            "patternId": "FP-S01S08-CROSS-CONCENTRATION",
            "riskScore": 0.70,
            "amplifiedSignalScore": 0.88,
            "componentScoreRefs": ["harmony_pressure", "supabase_external_evidence"],
            "requiredGates": ["sourceHealthScorecard", "harmonyPressureReport", "desktop_final_verification"],
            "requiredEvidenceArtifacts": ["riskLedger", "componentScores", "TraceStore keys", "DebugEvent NDJSON", "PatchDrop manifest"],
            "requiredTraceStoreKeys": ["sourceHealth.failurePatternKind", "sourceHealth.patternId", "harmony.score.overall"],
            "amplifierTraceKeys": ["hypernova.twpmP", "hypernova.cvarPhi", "hypernova.riskKAlloc", "hypernova.clampApplied", "sourceHealth.amplifiedSignalScore"],
            "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
            "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
            "stopConditions": ["all_p0_hard_gates_pass", "elapsed_hours_gte_9", "patchdrop_manifest_missing"]
        },
        {
            "queueRank": 2,
            "producerRole": "notebook",
            "sourceRootKind": "local-worktree",
            "directCanonicalSourceEdit": False,
            "evidenceOnly": True,
            "sourceRiskId": "silent_swallow_pressure",
            "failurePatternKind": "silent_swallow_pressure",
            "patternId": "FP-TRACE-SILENT-SWALLOW",
            "riskScore": 0.20,
            "amplifiedSignalScore": 0.41,
            "componentScoreRefs": ["harmony_pressure"],
            "requiredGates": ["sourceHealthScorecard", "harmonyPressureReport", "desktop_final_verification"],
            "requiredEvidenceArtifacts": ["riskLedger", "componentScores", "TraceStore keys", "DebugEvent NDJSON", "PatchDrop manifest"],
            "requiredTraceStoreKeys": ["sourceHealth.failurePatternKind", "sourceHealth.patternId", "failSoft.suppressed.stage"],
            "amplifierTraceKeys": ["hypernova.twpmP", "hypernova.cvarPhi", "hypernova.riskKAlloc", "hypernova.clampApplied", "sourceHealth.amplifiedSignalScore"],
            "debugEventNdjsonPath": "verification/source-health-failure-pattern-events.ndjson",
            "patchDropManifestPath": "verification/source-health-patchdrop-manifest-contract.json",
            "stopConditions": ["all_p0_hard_gates_pass", "elapsed_hours_gte_9", "patchdrop_manifest_missing"]
        }
    ]
}
Path(args.output).parent.mkdir(parents=True, exist_ok=True)
Path(args.output).write_text(json.dumps({
    "decision": "source_health_scorecard",
    "strictEvidenceAdjustedScore": 78.5,
    "riskCount": 2,
    "activeRiskCount": 0,
    "evidenceNeededCount": 1,
    "failurePatternKind": "cross_subsystem_concentration",
    "patternId": "FP-S01S08-CROSS-CONCENTRATION",
    "amplifiedSignalScore": 0.88,
    "failurePatternPrediction": {
        "failurePatternKind": "cross_subsystem_concentration",
        "patternId": "FP-S01S08-CROSS-CONCENTRATION",
        "producerValidationQueue": producer_validation_queue
    },
    "completionAuditFreshness": {
        "path": "var/codex-smoke/awx-mcp-completion-audit-current.json" if current_audit_exists_before_source_health else "",
        "fresh": current_audit_exists_before_source_health,
        "status": "current" if current_audit_exists_before_source_health else "missing_before_source_health"
    },
    "externalInputGateProof": {
        "observed": bool(command_packet),
        "boundaryReady": external_input_gate_ready,
        "status": external_gate.get("status", ""),
        "action": external_gate.get("action", ""),
        "localPatchJustified": external_gate.get("localPatchJustified"),
        "mutationAllowed": external_gate.get("mutationAllowed")
    },
    "goalNextCollectionPacketProof": {
        "observed": bool(collection_packet),
        "boundaryReady": collection_packet_ready,
        "requirementStatus": "satisfied" if collection_packet_ready else "missing_or_incomplete",
        "supabaseRequiredEnvNames": supabase_required_env_names,
        "externalRoles": ",".join(external_required_roles)
    },
    "nextSourceAction": "no_local_source_action_external_evidence_needed",
    "externalEvidenceNextAction": "collect_supabase_data_api_grants_and_rls_result_sets",
    "nextSourceActionDetails": [
        {
            "action": "no-local-source-action",
            "scope": "external_evidence",
            "nodeRole": "desktop",
            "readOnly": True,
            "mutationAllowed": False,
            "localPatchJustified": False,
            "sourceContract": "external_evidence_needed_no_local_source_patch",
            "evidenceNeeded": [
                "SUPABASE_PROJECT_REF",
                "read_only_supabase_mcp_or_cli_auth",
                "execute_sql_results",
                "get_advisors_results"
            ],
            "commands": [
                "python scripts\\awx_mcp_toolbox.py supabase_context_probe",
                "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\smoke_supabase_readonly_snapshot.ps1 -Root .",
                "python scripts\\supabase_schema_snapshot_import.py --root . --input data\\db-gap-report\\supabase-query-results.json --advisors data\\db-gap-report\\supabase-advisors.json"
            ],
            "decision": "evidence_needed"
        }
    ],
    "nextActionDetails": [
        {
            "action": "collect-supabase-live-proof",
            "nodeRole": "desktop",
            "targetService": "supabase",
            "readOnly": True,
            "mutationAllowed": False,
            "requiredMcpTools": ["execute_sql", "get_advisors"],
            "requiredEnv": [
                {"name": "SUPABASE_PROJECT_REF", "sensitive": False}
            ],
            "supportedAuthModes": ["supabase_mcp_oauth_session", "manual_SUPABASE_ACCESS_TOKEN"],
            "manualAuthSensitiveEnvRefs": ["SUPABASE_ACCESS_TOKEN"],
            "mcpOAuthSupported": True,
            "officialContractSignals": [
                "mcp_project_scoped_read_only",
                "data_api_grants_required",
                "rls_policy_required",
                "secret_keys_backend_only",
                "advisors_required_before_schema_claim"
            ],
            "collectionGuards": {
                "mutationAllowed": False,
                "storeRawRows": False,
                "requireProjectScope": True,
                "requireAdvisors": True
            },
            "applyCollectedEvidenceCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\supabase_apply_collected_evidence.ps1",
            "decision": "evidence_needed"
        },
        {
            "action": "collect-external-evidence-files",
            "nodeRole": "desktop",
            "targetRole": "macmini",
            "requiredSidecars": [".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json", "pendingNotice"],
            "requiredSourceIsolation": {
                "guard": "PASS",
                "sourceRootKind": "local-worktree",
                "directCanonicalSourceEdit": False,
                "desktopFinalProof": "evidence_needed",
                "rawSecretPatternHits": 0
            },
            "applyCollectedEvidenceCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop",
            "producerCommandTemplates": [
                "python scripts/awx_mcp_node_smoke.py --root <producer-local-worktree> --canonical-root <desktop-canonical-root> --node-role macmini",
                "python scripts/awx_mcp_producer_handoff.py --source-root <producer-local-worktree> --canonical-root <desktop-canonical-root> --patchdrop-root <PatchDrop> --producer-script <PatchDrop>\\producer_bundle.py --node-role macmini --topic mcp-control-loop --pathspec <relative/source/path>"
            ],
            "decision": "evidence_needed"
        },
        {
            "action": "collect-archive-index-proof",
            "nodeRole": "desktop",
            "targetService": "archive",
            "readOnly": True,
            "mutationAllowed": False,
            "requiredEnvNames": ["ARCHIVE_INDEX", "NAS_ARCHIVE_ROOT"],
            "requiredMcpTools": ["archive.search", "archive.index_build"],
            "indexPathRecommendation": "BackupsXS/index.jsonl",
            "archiveRootRecommendation": "BackupsXS",
            "applyCollectedEvidenceCommand": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\\awx_mcp_toolbox.ps1 -Tool archive.search",
            "nextActions": ["create_or_point_archive_index", "verify_archive_index_path", "rerun_archive_search"],
            "decision": "evidence_needed"
        }
    ]
}), encoding='utf-8')
print('[fake-source-health] ok')
'@

    Set-TestFile (Join-Path $root 'scripts\awx_mcp_completion_audit.py') @'
import argparse, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--root')
parser.add_argument('--output')
parser.add_argument('--require-supabase-proof', action='store_true')
args = parser.parse_args()
root = Path(args.root)
if (root / "fail-completion-audit-before-write.flag").is_file():
    print('[fake-completion-audit] failed-before-write')
    raise SystemExit(17)
latest_path = root / "var" / "codex-smoke" / "goal-next-auto.latest.json"
missing = []
latest = {}
if not latest_path.is_file():
    missing.append("latest")
else:
    latest = json.loads(latest_path.read_text(encoding='utf-8-sig'))
packet_raw = str(latest.get("commandPacketPath") or "")
packet_path = Path(packet_raw) if packet_raw else Path()
if packet_raw and not packet_path.is_absolute():
    packet_path = root / packet_path
if not packet_raw or not packet_path.is_file():
    missing.append("command-packet")
Path(args.output).parent.mkdir(parents=True, exist_ok=True)
with (root / "completion-audit-invocations.jsonl").open("a", encoding="utf-8") as handle:
    handle.write(Path(args.output).name + "\n")
Path(args.output).write_text(json.dumps({"ok": not missing, "status": "local_control_tower_ready" if not missing else "missing_goal_next_packet", "missing": missing, "supabaseLiveProofRequired": args.require_supabase_proof}), encoding='utf-8')
if missing:
    print('[fake-completion-audit] missing=' + ','.join(missing))
    raise SystemExit(5)
print('[fake-completion-audit] ok')
'@

    Set-TestFile (Join-Path $root 'scripts\awx_mcp_toolbox.py') @'
import argparse, datetime, json
import sys
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('tool')
parser.add_argument('--input-json', default='')
args = parser.parse_args()
raw_payload = args.input_json
if not raw_payload or raw_payload.strip() == "-":
    raw_payload = sys.stdin.buffer.read().decode("utf-8-sig")
payload = json.loads(raw_payload or '{}')
root = Path(payload.get("root") or ".")
if not root.is_absolute():
    root = Path.cwd() / root
invocation_ledger = root / "tool-invocations.jsonl"
invocation_ledger.parent.mkdir(parents=True, exist_ok=True)
with invocation_ledger.open("a", encoding="utf-8") as handle:
    handle.write(args.tool + "\n")
peer_evidence_bus = {
    "schemaVersion": "awx.mcp.peer_evidence_bus.v1",
    "ok": True,
    "decision": "peer_evidence_bus",
    "targetMetric": payload.get("targetMetric", "harmony"),
    "nodeRole": payload.get("nodeRole", "desktop"),
    "outputCount": 7,
    "promptPack": {"present": True, "missingSignals": []},
    "laneCount": 7,
    "rawSecretPatternHits": 0,
    "claudePeersProtocol": {
        "referenceTools": ["list_peers", "resolve_peer", "send_message", "close_conversation", "set_summary", "check_messages"],
        "brokerContract": {"defaultPort": 7899},
        "codexDeliveryPolicy": {"mode": "manual-queue-preserving", "manualCheckTool": "check_messages"},
        "conversationClosure": {"requiresMutualClose": True, "reopenRequiresExplicitFlag": True},
        "messageSafety": {
            "redactedSummariesOnly": True,
            "rawMessageBodiesStored": False,
            "directCanonicalSourceEdit": False,
            "dispatchViaPatchDropOrCommandPacket": True
        }
    },
    "safePeerIdentityContract": {
        "sourceFields": ["id", "logical_name", "cwd", "repo_name", "repo_root", "branch", "model", "summary"],
        "safeFields": ["peerIdHash", "logicalName", "repoName", "branch", "summaryHash", "summaryLength", "logicalNameCollisionCount", "cwdHash", "repoRootHash"],
        "forbiddenFields": ["rawCwd", "rawRepoRoot", "rawMessageText", "rawSummary"],
        "redactionMode": "hash-count-and-allowlisted-labels-only",
        "identityResolution": {
            "logicalNamePreferred": True,
            "resolvePeerBeforeSend": True,
            "duplicateLogicalNameRequiresPeerId": True,
            "ambiguousNameResolution": "fail-closed"
        },
        "messageEnvelope": {"storeRawText": False, "storeMessageHash": True, "storeMessageLength": True}
    },
    "peerIdentitySummary": {
        "peerCount": 0,
        "logicalNameCollisionCount": 0,
        "ambiguousNameResolution": "not-applicable",
        "rawIdentityStored": False,
        "rawPathStored": False,
        "rawSummaryStored": False
    },
    "webProbeLedger": {
        "mode": "web-probe-first",
        "sourceCount": 4,
        "allowedDomains": ["modelcontextprotocol.io", "supabase.com"],
        "sources": [
            {"sourceUrl": "https://modelcontextprotocol.io/docs/concepts/tools", "contractImpact": "require_gate", "rawContentStored": False, "fullArticleStored": False},
            {"sourceUrl": "https://modelcontextprotocol.io/docs/concepts/transports", "contractImpact": "allow", "rawContentStored": False, "fullArticleStored": False},
            {"sourceUrl": "https://supabase.com/docs/guides/ai-tools/mcp", "contractImpact": "require_gate", "rawContentStored": False, "fullArticleStored": False},
            {"sourceUrl": "https://supabase.com/docs/guides/api/securing-your-api", "contractImpact": "require_gate", "rawContentStored": False, "fullArticleStored": False}
        ],
        "rawContentStored": False,
        "rawQueryStored": False,
        "requiresRefreshBeforePatch": True
    },
    "webProbeRefreshPacket": {
        "schemaVersion": "awx.web_probe.refresh_packet.v1",
        "mode": "read-only-official-sources",
        "officialSourceCount": 4,
        "mutationAllowed": False,
        "rawContentStored": False,
        "rawQueryStored": False,
        "fetchTargets": [
            {"sourceUrl": "https://modelcontextprotocol.io/docs/concepts/tools", "markdownUrl": "https://modelcontextprotocol.io/docs/concepts/tools.md", "method": "browser-or-markdown-fetch", "rawContentStored": False, "fullArticleStored": False},
            {"sourceUrl": "https://modelcontextprotocol.io/docs/concepts/transports", "markdownUrl": "https://modelcontextprotocol.io/docs/concepts/transports.md", "method": "browser-or-markdown-fetch", "rawContentStored": False, "fullArticleStored": False},
            {"sourceUrl": "https://supabase.com/docs/guides/ai-tools/mcp", "markdownUrl": "https://supabase.com/docs/guides/ai-tools/mcp.md", "method": "markdown-fetch", "rawContentStored": False, "fullArticleStored": False},
            {"sourceUrl": "https://supabase.com/docs/guides/api/securing-your-api", "markdownUrl": "https://supabase.com/docs/guides/api/securing-your-api.md", "method": "markdown-fetch", "rawContentStored": False, "fullArticleStored": False}
        ],
        "supabaseMcpGate": {
            "requiredEnv": ["SUPABASE_PROJECT_REF"],
            "endpointTemplate": "https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs",
            "featureGroups": ["database", "debugging", "docs"],
            "readOnly": True,
            "mutationAllowed": False,
            "storeAccessToken": False
        },
        "browserProbeGate": {"storeRawUrl": False, "storeScreenshotPath": False, "storeDomSnapshot": False},
        "importContract": {
            "storeExtractsOnly": True,
            "maxExtractChars": 240,
            "allowedImpacts": ["allow", "block", "require_gate", "evidence_only"],
            "requiresSourceHash": True
        }
    },
    "evidenceLanes": [
        {"name": "desktop", "role": "canonical-source-owner", "status": "ready-to-run"},
        {"name": "macmini", "role": "patch-producer", "status": "evidence_needed"},
        {"name": "notebook", "role": "supporting-probe", "status": "evidence_needed"},
        {"name": "supabase", "role": "read-only-db-evidence", "status": "evidence_needed"},
        {"name": "browser", "role": "dom-runtime-proof", "status": "evidence_needed"},
        {"name": "computer", "role": "gui-proof-only", "status": "evidence_needed"},
        {"name": "superpowers", "role": "process-guard", "status": "ready-to-run"}
    ],
    "nextActions": ["set_SUPABASE_PROJECT_REF", "collect_external_producer_evidence"]
}
embedded_peer_evidence_bus = (
    None
    if (root / "omit-embedded-peer-evidence-bus.txt").is_file()
    else (
        {"schemaVersion": "awx.mcp.peer_evidence_bus.v0", "ok": True, "decision": "peer_evidence_bus"}
        if (root / "malformed-embedded-peer-evidence-bus.txt").is_file()
        else (
            {
                "schemaVersion": "awx.mcp.peer_evidence_bus.v1",
                "ok": True,
                "decision": "peer_evidence_bus",
                "targetMetric": "harmony",
                "nodeRole": "desktop",
                "outputCount": 7,
                "promptPack": {"present": True, "missingSignals": []},
                "rawSecretPatternHits": 0
            }
            if (root / "truncated-embedded-peer-evidence-bus.txt").is_file()
            else json.loads(json.dumps(peer_evidence_bus))
        )
    )
)
if (root / "incomplete-embedded-peer-web-probe.txt").is_file() and isinstance(embedded_peer_evidence_bus, dict):
    embedded_peer_evidence_bus.get("webProbeRefreshPacket", {}).pop("fetchTargets", None)
jdbc_probe_enabled = (root / "embedded-peer-jdbc-secret.txt").is_file()
if jdbc_probe_enabled and isinstance(embedded_peer_evidence_bus, dict):
    embedded_peer_evidence_bus["diagnostic"] = "jdbc" + ":postgresql://example.invalid/redaction-probe"

def emit_desktop_result(result):
    serialized = json.dumps(result, sort_keys=True)
    if jdbc_probe_enabled:
        serialized = serialized.replace("jdbc:", "jdbc\\u003a")
    print(serialized)

if args.tool == "peer_evidence_bus":
    print(json.dumps(peer_evidence_bus, sort_keys=True))
    raise SystemExit(0)
if args.tool == "web_probe_refresh":
    if (root / "crash-web-probe-refresh-before-write.txt").is_file():
        print('[fake-web-probe-refresh] failed-before-write')
        raise SystemExit(17)
    output_path = Path(payload.get("output_path") or payload.get("outputPath") or root / "var" / "codex-smoke" / "web-probe-refresh.json")
    if not output_path.is_absolute():
        output_path = root / output_path
    output_path.parent.mkdir(parents=True, exist_ok=True)
    web_probe_failed = (root / "fail-web-probe-refresh.txt").is_file()
    artifact = {
        "schemaVersion": "awx.web_probe.refresh.v1",
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "ok": not web_probe_failed,
        "decision": "web_probe_refresh",
        "mode": "read-only-official-sources",
        "targetMetric": payload.get("targetMetric", "harmony"),
        "artifactPath": "var/codex-smoke/web-probe-refresh.json",
        "sourceCount": 5,
        "fetchedCount": 0 if web_probe_failed else 5,
        "failedCount": 5 if web_probe_failed else 0,
        "skippedCount": 0,
        "missingSignalCount": 0,
        "rawContentStored": False,
        "rawQueryStored": False,
        "mutationAllowed": False,
        "rawSecretPatternHits": 0,
        "sources": [
            {"host": "modelcontextprotocol.io", "decision": "fetched", "rawContentStored": False, "fullArticleStored": False},
            {"host": "modelcontextprotocol.io", "decision": "fetched", "rawContentStored": False, "fullArticleStored": False},
            {"host": "supabase.com", "decision": "fetched", "rawContentStored": False, "fullArticleStored": False},
            {"host": "supabase.com", "decision": "fetched", "rawContentStored": False, "fullArticleStored": False},
            {"host": "supabase.com", "decision": "fetched", "rawContentStored": False, "fullArticleStored": False}
        ],
        "evidence_needed": ["synthetic_web_probe_failure"] if web_probe_failed else []
    }
    output_path.write_text(json.dumps(artifact, sort_keys=True), encoding="utf-8")
    print(json.dumps(dict(artifact, artifactHash="f" * 64, artifactBytes=1234, outputCount=5), sort_keys=True))
    raise SystemExit(0)
role_pathspec = (
    payload.get("role_pathspec")
    or payload.get("role_pathspecs")
    or payload.get("producer_pathspecs")
    or {}
)
role_pathspec_counts = {
    role: len(values) if isinstance(values, list) else (1 if isinstance(values, str) and values else 0)
    for role, values in role_pathspec.items()
}
dispatch_write_requested = str(payload.get("write_dispatch", "")).strip().lower() in ("1", "true", "yes", "on")
producer_kit_write_requested = str(payload.get("write_producer_kit", "")).strip().lower() in ("1", "true", "yes", "on")
require_producer_bundles = str(payload.get("require_producer_bundles", "")).strip().lower() in ("1", "true", "yes", "on")
producer_kit_manifest_hash = ("a" * 64) if producer_kit_write_requested else ""
dispatch_artifacts = [
    "__patch_drop__/dispatch/mcp-control-loop.dispatch.json",
    "__patch_drop__/dispatch/mcp-control-loop.dispatch.sha256.txt",
] if dispatch_write_requested else []
if dispatch_write_requested:
    dispatch_dir = root / "__patch_drop__" / "dispatch"
    dispatch_dir.mkdir(parents=True, exist_ok=True)
    command_files = {
        "macmini": dispatch_dir / "mcp-control-loop-macmini.commands.txt",
        "notebook": dispatch_dir / "mcp-control-loop-notebook.commands.txt",
    }
    command_files["macmini"].write_text("\n".join([
        "# Run on macmini producer-local worktree or shell.",
        "set -euo pipefail",
        "ProducerCommandFile='/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__/dispatch/mcp-control-loop-macmini.commands.txt'",
        "python3 '/Users/nninn/agent/macmini/awx-macmini/scripts/awx_mcp_producer_handoff.py' --source-root '/Users/nninn/agent/macmini/awx-macmini' --canonical-root '<desktop-canonical-root>' --patchdrop-root '/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__' --producer-script '/Users/nninn/agent/macmini/awx-macmini/__patch_drop__/producer_bundle.py' --node-role 'macmini' --topic 'mcp-control-loop' --pathspec 'scripts/goal_next_auto.ps1'",
        "",
    ]), encoding="utf-8")
    command_files["notebook"].write_text("\n".join([
        "# Run on notebook producer-local worktree or shell.",
        "$ErrorActionPreference = 'Stop'",
        "$ProducerCommandFile = 'Z:\\PatchDrop\\dispatch\\mcp-control-loop-notebook.commands.txt'",
        "python 'C:/AbandonWare/worktrees/awx-notebook/scripts/awx_mcp_producer_handoff.py' --source-root 'C:/AbandonWare/worktrees/awx-notebook' --canonical-root '<desktop-canonical-root>' --patchdrop-root 'Z:\\PatchDrop' --producer-script 'C:/AbandonWare/worktrees/awx-notebook/__patch_drop__/producer_bundle.py' --node-role 'notebook' --topic 'mcp-control-loop' --pathspec 'scripts/goal_next_auto_tests.ps1'",
        "",
    ]), encoding="utf-8")
    desktop_intake_path = dispatch_dir / "mcp-control-loop-desktop-intake.ps1"
    handoff_path = dispatch_dir / "mcp-control-loop-handoff.md"
    desktop_intake_path.write_text("# fake desktop intake\n", encoding="utf-8")
    handoff_path.write_text("# fake handoff\n", encoding="utf-8")
    import hashlib
    sha_lines = []
    for path in command_files.values():
        sha_lines.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name)
    dispatch_json_path = dispatch_dir / "mcp-control-loop-desktop-dispatch.json"
    dispatch_json = {
        "schemaVersion": "awx.mcp.desktop_dispatch_packet.v1",
        "topic": "mcp-control-loop",
        "artifactCount": 6,
        "dispatchArtifactIndex": {
            "desktopDispatch": str(dispatch_json_path),
            "dispatchSha256Sidecar": str(dispatch_dir / "mcp-control-loop-dispatch.sha256.txt"),
            "producerCommands": [
                {"nodeRole": role, "commandFile": str(path), "fileHash": hashlib.sha256(path.read_bytes()).hexdigest()}
                for role, path in command_files.items()
            ],
            "sha256CoveredArtifacts": [
                str(dispatch_json_path),
                str(command_files["macmini"]),
                str(command_files["notebook"]),
                str(desktop_intake_path),
                str(handoff_path),
            ]
        },
        "nextActions": [
            {"nodeRole": role, "commandFile": str(path), "fileHash": hashlib.sha256(path.read_bytes()).hexdigest()}
            for role, path in command_files.items()
        ]
    }
    dispatch_json_path.write_text(json.dumps(dispatch_json, sort_keys=True), encoding="utf-8")
    sha_lines.append(hashlib.sha256(dispatch_json_path.read_bytes()).hexdigest() + "  " + dispatch_json_path.name)
    sha_lines.append(hashlib.sha256(desktop_intake_path.read_bytes()).hexdigest() + "  " + desktop_intake_path.name)
    sha_lines.append(hashlib.sha256(handoff_path.read_bytes()).hexdigest() + "  " + handoff_path.name)
    (dispatch_dir / "mcp-control-loop-dispatch.sha256.txt").write_text("\n".join(sha_lines) + "\n", encoding="utf-8")
missing = [
    role
    for role in ("macmini", "notebook")
    if role_pathspec_counts.get(role, 0) < 1
]
if missing and not require_producer_bundles:
    emit_desktop_result({
        "ok": True,
        "toolName": args.tool,
        "topic": payload.get("topic", ""),
        "localReady": True,
        "completionReady": False,
        "desktopFinalProof": "evidence_needed",
        "externalEvidenceComplete": False,
        "decision": "desktop_dispatch_packet_optional",
        "outputCount": 0,
        "packets": [],
        "rolePathspecCounts": role_pathspec_counts,
        "missingPathspecRoles": missing,
        "dispatch": {"dispatchArtifacts": [], "artifactCount": 0},
        "dispatchIntegrity": {"ok": False, "failReason": "dispatch-not-requested"},
        "dispatchWriteRequested": False,
        "producerKit": {"ok": False, "manifestHash": ""},
        "producerKitWriteRequested": False,
        "completionAuditRunRequested": payload.get("run_completion_audit", True),
        "completionAuditExecuted": payload.get("run_completion_audit", True),
        "completionAuditEvidenceSource": "nested-control-loop" if payload.get("run_completion_audit", True) else "deferred-to-caller",
        "peerEvidenceBus": embedded_peer_evidence_bus,
        "nextActions": [],
    })
    raise SystemExit(0)
if missing:
    emit_desktop_result({
        "ok": False,
        "toolName": args.tool,
        "topic": payload.get("topic", ""),
        "localReady": False,
        "completionReady": False,
        "desktopFinalProof": "evidence_needed",
        "externalEvidenceComplete": False,
        "failReason": "pathspec-required",
        "missingPathspecRoles": missing,
        "completionAuditRunRequested": payload.get("run_completion_audit", True),
        "completionAuditExecuted": payload.get("run_completion_audit", True),
        "completionAuditEvidenceSource": "nested-control-loop" if payload.get("run_completion_audit", True) else "deferred-to-caller",
        "peerEvidenceBus": embedded_peer_evidence_bus,
        "nextActions": [{"action": "assign-producer-role-pathspec"}],
    })
    raise SystemExit(0)
emit_desktop_result({
    "ok": True,
    "toolName": args.tool,
    "topic": payload.get("topic", ""),
    "localReady": True,
    "completionReady": False,
    "desktopFinalProof": "evidence_needed",
    "externalEvidenceComplete": False,
    "decision": "desktop_dispatch_packet",
    "outputCount": 2,
    "packets": [{"nodeRole": "macmini"}, {"nodeRole": "notebook"}],
    "rolePathspecCounts": role_pathspec_counts,
    "dispatch": {
        "dispatchArtifacts": dispatch_artifacts,
        "artifactCount": len(dispatch_artifacts),
        "dispatchArtifactIndex": {
            "producerCommands": [
                {"nodeRole": "macmini", "producerKitManifestHash": producer_kit_manifest_hash},
                {"nodeRole": "notebook", "producerKitManifestHash": producer_kit_manifest_hash},
            ],
        },
    },
    "dispatchIntegrity": {
        "ok": dispatch_write_requested,
        "failReason": "" if dispatch_write_requested else "dispatch-not-requested",
    },
    "dispatchWriteRequested": dispatch_write_requested,
    "producerKit": {
        "ok": producer_kit_write_requested,
        "manifestHash": producer_kit_manifest_hash,
    },
    "producerKitWriteRequested": producer_kit_write_requested,
    "completionAuditRunRequested": payload.get("run_completion_audit", True),
    "completionAuditExecuted": payload.get("run_completion_audit", True),
    "completionAuditEvidenceSource": "nested-control-loop" if payload.get("run_completion_audit", True) else "deferred-to-caller",
    "peerEvidenceBus": embedded_peer_evidence_bus,
    "nextActions": [{"action": "collect-supabase-live-proof"}],
})
'@

    return $root
}

function Set-CompletionAuditBootstrapFixture {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]
        [ValidateSet('current', 'missing', 'malformed', 'missing_generated_at', 'invalid_generated_at', 'stale', 'future', 'unsafe', 'missing_counter', 'invalid_counter')]
        [string]$State
    )

    $path = Join-Path $Root 'var\codex-smoke\awx-mcp-completion-audit-current.json'
    if ($State -eq 'missing') {
        return
    }
    if ($State -eq 'malformed') {
        Set-TestFile $path '{'
        return
    }

    $artifact = [ordered]@{
        schemaVersion = 'awx.mcp.completion_audit.v1'
        generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
        ok = $true
        status = 'local_control_tower_ready'
        secretHits = 0
        secretPatternHits = 0
        rawSecretPatternHits = 0
    }
    switch ($State) {
        'missing_generated_at' { $artifact.Remove('generatedAt') }
        'invalid_generated_at' { $artifact.generatedAt = 'not-a-timestamp' }
        'stale' { $artifact.generatedAt = [DateTimeOffset]::UtcNow.AddHours(-25).ToString('o') }
        'future' { $artifact.generatedAt = [DateTimeOffset]::UtcNow.AddMinutes(10).ToString('o') }
        'unsafe' { $artifact.rawSecretPatternHits = 1 }
        'missing_counter' { $artifact.Remove('rawSecretPatternHits') }
        'invalid_counter' { $artifact.rawSecretPatternHits = 'not-a-count' }
    }
    Set-TestFile $path ($artifact | ConvertTo-Json -Depth 10)
}

function Import-GoalNextBootstrapFunctions {
    param([Parameter(Mandatory = $true)][string]$Path)

    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$parseErrors)
    if (@($parseErrors).Count -gt 0) {
        throw "goal_next_auto.ps1 parse failed: $($parseErrors[0].Message)"
    }
    foreach ($name in @('Count-Pattern', 'Count-SecretPatternHits', 'Get-SafeCountValue', 'Read-JsonObjectFromFile', 'Get-CompletionAuditBootstrapDecision')) {
        $functionAst = $ast.Find({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
        }, $true)
        if ($null -eq $functionAst) {
            throw "missing function definition: $name"
        }
        $definition = $functionAst.Extent.Text -replace ("^function\s+" + [regex]::Escape($name)), ("function script:" + $name)
        Invoke-Expression $definition
    }
}

$script = Join-Path $PSScriptRoot 'goal_next_auto.ps1'
$nextScript = Join-Path $PSScriptRoot 'goal_next.ps1'
$failures = 0

try {
    Import-GoalNextBootstrapFunctions -Path $script
    Assert-True 'short Bearer prose is not classified as a secret' (
        (Count-SecretPatternHits -Text 'Bearer token authentication is required') -eq 0
    ) 'short Bearer prose was classified as a secret'
    Assert-True 'long Bearer credential remains classified as a secret' (
        (Count-SecretPatternHits -Text ('Bearer ' + 'abcdefghijklmnopqrstuvwxyz' + '123456')) -eq 1
    ) 'long Bearer credential was not classified as a secret'
    Assert-True 'risk prose containing sk hyphen is not classified as an OpenAI secret' (
        (Count-SecretPatternHits -Text 'find-duplicate-risk-or-decisive-counter-evidence') -eq 0
    ) 'risk prose containing sk hyphen was classified as an OpenAI secret'
    $help = Invoke-Captured -Arguments @('-File', $script, '-Help')
    Assert-True 'goal next help exits zero' ($help.ExitCode -eq 0) "expected exit 0; output=$($help.Output)"
    Assert-Contains 'goal next help names Supabase project env' $help.Output 'SUPABASE_PROJECT_REF'
    Assert-Contains 'goal next help names smoke script' $help.Output 'smoke_supabase_readonly_snapshot.ps1'
    Assert-Contains 'goal next help names Supabase apply script' $help.Output 'supabase_apply_collected_evidence.ps1'
    Assert-Contains 'goal next help names external apply script' $help.Output 'external_apply_collected_evidence.ps1'
    Assert-Contains 'goal next help names desktop control loop' $help.Output 'awx_mcp_toolbox.py desktop_control_loop'
    Assert-Contains 'goal next help names status mode' $help.Output '-Status'
    Assert-Contains 'goal next help names ensure-fresh mode' $help.Output '-EnsureFresh'
    Assert-Contains 'goal next help names external dispatch opt-in' $help.Output '-ExternalDispatch'
    Assert-Contains 'goal next help names Supabase proof opt-in' $help.Output '-RequireSupabaseProof'
    Assert-Contains 'goal next help names web probe refresh opt-in' $help.Output '-RefreshWebProbe'
    $goalNextSource = Get-Content -Raw -LiteralPath $script
    Assert-Contains 'goal next reads breadcrumb history with bounded tail IO' $goalNextSource 'Get-Content -LiteralPath $Path -Tail $MaxRows -ErrorAction Stop'
    Assert-Contains 'goal next writes machine JSON without pretty-print context overhead' $goalNextSource '$text = $Value | ConvertTo-Json -Depth 80 -Compress'
    Assert-True 'goal next help prints no raw auth header' (-not ($help.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($help.Output)"

    $nextHelp = Invoke-Captured -Arguments @('-File', $nextScript, '-Help')
    Assert-True 'goal next wrapper help exits zero' ($nextHelp.ExitCode -eq 0) "expected exit 0; output=$($nextHelp.Output)"
    Assert-Contains 'goal next wrapper help names ensure-fresh default' $nextHelp.Output '-EnsureFresh'
    Assert-Contains 'goal next wrapper help names auto script' $nextHelp.Output 'goal_next_auto.ps1'
    Assert-Contains 'goal next wrapper help names external dispatch opt-in' $nextHelp.Output '-ExternalDispatch'
    Assert-Contains 'goal next wrapper help names Supabase proof opt-in' $nextHelp.Output '-RequireSupabaseProof'
    Assert-Contains 'goal next wrapper help names web probe refresh opt-in' $nextHelp.Output '-RefreshWebProbe'
    Assert-True 'goal next wrapper help prints no raw auth header' (-not ($nextHelp.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($nextHelp.Output)"

    $desktopOnlyRoot = New-FakeGoalRoot -Mode 'partial'
    $desktopOnlyOutput = Join-Path $desktopOnlyRoot 'desktop-only-out'
    $desktopOnly = Invoke-Captured -Arguments @('-File', $script, '-Root', $desktopOnlyRoot, '-OutputDir', $desktopOnlyOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'desktop-only default exits zero with optional external lanes skipped' ($desktopOnly.ExitCode -eq 0) "expected exit 0; output=$($desktopOnly.Output)"
    $desktopOnlySummaryPath = Join-Path $desktopOnlyOutput 'goal-next-auto.summary.json'
    Assert-True 'desktop-only default writes summary' (Test-Path $desktopOnlySummaryPath) 'missing desktop-only summary'
    if (Test-Path $desktopOnlySummaryPath) {
        $desktopOnlySummary = Get-Content -Raw -LiteralPath $desktopOnlySummaryPath | ConvertFrom-Json
        Assert-True 'desktop-only default keeps external dispatch manual' (
            $desktopOnlySummary.desktopControlLoop.dispatchWriteRequested -eq $false -and
            $desktopOnlySummary.desktopControlLoop.producerKitWriteRequested -eq $false -and
            [int]$desktopOnlySummary.desktopControlLoop.dispatchArtifactCount -eq 0
        ) "summary=$($desktopOnlySummary | ConvertTo-Json -Compress)"
        $desktopOnlyControlLoop = Get-Content -Raw -LiteralPath $desktopOnlySummary.artifacts.desktopControlLoop | ConvertFrom-Json
        Assert-True 'desktop-only default uses compact optional dispatch fast path' (
            [string]$desktopOnlyControlLoop.decision -eq 'desktop_dispatch_packet_optional' -and
            [int]$desktopOnlyControlLoop.outputCount -eq 0 -and
            @($desktopOnlyControlLoop.packets).Count -eq 0 -and
            [int]$desktopOnlyControlLoop.rolePathspecCounts.macmini -eq 0 -and
            [int]$desktopOnlyControlLoop.rolePathspecCounts.notebook -eq 0
        ) "desktopControlLoop=$($desktopOnlyControlLoop | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only default defers the redundant nested completion audit' (
            $desktopOnlyControlLoop.completionAuditRunRequested -eq $false -and
            $desktopOnlyControlLoop.completionAuditExecuted -eq $false -and
            [string]$desktopOnlyControlLoop.completionAuditEvidenceSource -eq 'deferred-to-caller'
        ) "desktopControlLoop=$($desktopOnlyControlLoop | ConvertTo-Json -Compress)"
        $desktopOnlyAuditInvocations = @(Get-Content -LiteralPath (Join-Path $desktopOnlyRoot 'completion-audit-invocations.jsonl'))
        Assert-True 'desktop-only default preserves preflight packet and final caller audits' (@($desktopOnlyAuditInvocations).Count -eq 3) "auditInvocations=$($desktopOnlyAuditInvocations.Count)"
        $desktopOnlyToolInvocations = @(Get-Content -LiteralPath (Join-Path $desktopOnlyRoot 'tool-invocations.jsonl'))
        Assert-True 'desktop-only default skips optional external child processes and reuses embedded peer evidence bus' (
            @($desktopOnlyToolInvocations | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 0 -and
            @($desktopOnlyToolInvocations | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 0 -and
            @($desktopOnlyToolInvocations | Where-Object { $_ -eq 'external_apply' }).Count -eq 0 -and
            @($desktopOnlyToolInvocations | Where-Object { $_ -eq 'desktop_control_loop' }).Count -eq 1 -and
            @($desktopOnlyToolInvocations | Where-Object { $_ -eq 'peer_evidence_bus' }).Count -eq 0 -and
            @($desktopOnlyToolInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 0
        ) "toolInvocations=$($desktopOnlyToolInvocations -join ',')"
        Assert-True 'desktop-only default keeps web refresh as a truthful manual supporting contract' (
            $desktopOnlySummary.webProbeRefresh.refreshRequested -eq $false -and
            $desktopOnlySummary.webProbeRefresh.processExecuted -eq $false -and
            $desktopOnlySummary.webProbeRefresh.contractReady -eq $true -and
            $desktopOnlySummary.webProbeRefresh.proofReady -eq $false -and
            $desktopOnlySummary.webProbeRefresh.supportingEvidenceOnly -eq $true -and
            $desktopOnlySummary.webProbeRefresh.fresh -eq $false -and
            [string]$desktopOnlySummary.webProbeRefresh.decision -eq 'supporting_evidence_missing'
        ) "webProbeRefresh=$($desktopOnlySummary.webProbeRefresh | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only default does not create a canonical web proof artifact' (-not (Test-Path -LiteralPath (Join-Path $desktopOnlyRoot 'var\codex-smoke\web-probe-refresh.json'))) 'unexpected default web proof artifact'
        Assert-True 'desktop-only default does not promote web refresh into primary actions' (
            -not (($desktopOnlySummary.nextActionSources -join ',').Contains('web_probe_refresh')) -and
            [string]$desktopOnlySummary.firstActionSource -ne 'web_probe_refresh'
        ) "summary=$($desktopOnlySummary | ConvertTo-Json -Compress)"
        $desktopOnlyCommandPacket = Get-Content -Raw -LiteralPath $desktopOnlySummary.artifacts.commandPacket | ConvertFrom-Json
        Assert-True 'desktop-only default omits external dispatch command lane' (
            -not (($desktopOnlyCommandPacket.lanes -join ',') -match 'external_dispatch')
        ) "commandPacket=$($desktopOnlyCommandPacket | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only default records dispatch overlay skip' (
            $desktopOnlySummary.sourceHealth.dispatchOverlay.ok -eq $false -and
            [string]$desktopOnlySummary.sourceHealth.dispatchOverlay.skippedReason -eq 'dispatch-not-written'
        ) "summary=$($desktopOnlySummary | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only default treats external producer proof as supporting evidence' (
            [int]$desktopOnlySummary.externalApplyExit -eq 0 -and
            [string]$desktopOnlySummary.externalApply.decision -eq 'supporting_evidence_missing' -and
            $desktopOnlySummary.externalApply.processExecuted -eq $false -and
            $desktopOnlySummary.externalApply.producerBundlesRequired -eq $false -and
            [int]$desktopOnlySummary.externalApply.nextActionCount -eq 0
        ) "summary=$($desktopOnlySummary | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only default keeps Supabase proof out of primary next actions' (
            -not (($desktopOnlySummary.nextActionSources -join ',').Contains('supabase_apply')) -and
            [string]$desktopOnlySummary.firstAction -ne 'set_SUPABASE_PROJECT_REF' -and
            [string]$desktopOnlySummary.firstActionSource -ne 'supabase_apply' -and
            [string]$desktopOnlySummary.supabaseApply.decision -eq 'evidence_needed' -and
            $desktopOnlySummary.supabaseSmoke.processExecuted -eq $false -and
            $desktopOnlySummary.supabaseApply.processExecuted -eq $false
        ) "summary=$($desktopOnlySummary | ConvertTo-Json -Compress)"
        $desktopOnlySupabaseSmokePath = Join-Path $desktopOnlyOutput 'supabase-readonly-smoke\supabase-readonly-snapshot.summary.json'
        $desktopOnlySupabaseSmoke = Get-Content -Raw -LiteralPath $desktopOnlySupabaseSmokePath | ConvertFrom-Json
        Assert-True 'desktop-only default preserves the complete static supabase smoke contract' (
            -not [string]::IsNullOrWhiteSpace([string]$desktopOnlySupabaseSmoke.generatedAt) -and
            [int]$desktopOnlySupabaseSmoke.docsRefCount -ge 2 -and
            [int]$desktopOnlySupabaseSmoke.securityContractCount -ge 3 -and
            [string]$desktopOnlySupabaseSmoke.cliQueryFallbackTool -eq 'execute_sql' -and
            [string]$desktopOnlySupabaseSmoke.cliAdvisorsFallbackTool -eq 'get_advisors' -and
            $desktopOnlySupabaseSmoke.dataApiGrantProofRequired -eq $true -and
            $desktopOnlySupabaseSmoke.rlsPolicyProofRequired -eq $true -and
            $desktopOnlySupabaseSmoke.apiKeysDocs -eq $true -and
            $desktopOnlySupabaseSmoke.secretKeysBackendOnly -eq $true
        ) "supabaseSmoke=$($desktopOnlySupabaseSmoke | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only static supabase contract does not overwrite canonical smoke proof' (
            -not (Test-Path -LiteralPath (Join-Path $desktopOnlyRoot 'var\codex-smoke\supabase-readonly-snapshot\supabase-readonly-snapshot.summary.json'))
        ) 'unexpected canonical Supabase smoke summary from static optional contract'
        $desktopOnlySupabaseCommand = @($desktopOnlyCommandPacket.commands | Where-Object { $_.lane -eq 'supabase' -and $_.action -eq 'collect-supabase-live-proof' } | Select-Object -First 1)
        Assert-True 'desktop-only default omits Supabase command lane unless proof is requested' (
            @($desktopOnlySupabaseCommand).Count -eq 0
        ) "commandPacket=$($desktopOnlyCommandPacket | ConvertTo-Json -Compress)"
        $desktopOnlyCollectionPacket = Get-Content -Raw -LiteralPath $desktopOnlySummary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'desktop-only collection packet separates web contract from uncollected proof' (
            $desktopOnlyCollectionPacket.webProbeRefresh.contractReady -eq $true -and
            $desktopOnlyCollectionPacket.webProbeRefresh.refreshRequested -eq $false -and
            $desktopOnlyCollectionPacket.webProbeRefresh.processExecuted -eq $false -and
            $desktopOnlyCollectionPacket.webProbeRefresh.proofReady -eq $false -and
            $desktopOnlyCollectionPacket.webProbeRefresh.supportingEvidenceOnly -eq $true
        ) "webProbeRefresh=$($desktopOnlyCollectionPacket.webProbeRefresh | ConvertTo-Json -Compress)"
        $desktopOnlySmbProbeCommand = @($desktopOnlyCommandPacket.commands | Where-Object { $_.lane -eq 'smb_decommission_debug_probe' } | Select-Object -First 1)
        Assert-True 'desktop-only default surfaces SMB debug probe command' (
            @($desktopOnlySmbProbeCommand).Count -eq 1 -and
            [string]$desktopOnlySmbProbeCommand.tool -eq 'smb_decommission_debug_probe' -and
            [string]$desktopOnlySmbProbeCommand.outputPath -eq 'var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.summary.json' -and
            $desktopOnlySmbProbeCommand.mutationAllowed -eq $false -and
            $desktopOnlySmbProbeCommand.writeDispatch -eq $false -and
            $desktopOnlySmbProbeCommand.writeProducerKit -eq $false -and
            $desktopOnlySmbProbeCommand.requireProducerBundles -eq $false
        ) "commandPacket=$($desktopOnlyCommandPacket | ConvertTo-Json -Compress)"
        Assert-True 'desktop-only default records SMB debug probe contract' (
            $null -ne $desktopOnlyCommandPacket.smbDecommissionDebugProbe -and
            [string]$desktopOnlyCommandPacket.smbDecommissionDebugProbe.tool -eq 'smb_decommission_debug_probe' -and
            [string]$desktopOnlyCommandPacket.smbDecommissionDebugProbe.viewerArtifact -eq 'var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.viewer.html' -and
            [string]$desktopOnlyCommandPacket.smbDecommissionDebugProbe.attachmentPathPlaceholder -eq '<attachment-path>' -and
            [string]$desktopOnlyCommandPacket.smbDecommissionDebugProbe.commandWithAttachment -match 'attachmentPath' -and
            [string]$desktopOnlyCommandPacket.smbDecommissionDebugProbe.commandWithAttachment -match '<attachment-path>' -and
            $desktopOnlyCommandPacket.smbDecommissionDebugProbe.mutationAllowed -eq $false -and
            $desktopOnlyCommandPacket.smbDecommissionDebugProbe.supabaseReadOnly -eq $true -and
            $desktopOnlyCommandPacket.smbDecommissionDebugProbe.supportingEvidenceOnly -eq $true
        ) "commandPacket=$($desktopOnlyCommandPacket | ConvertTo-Json -Compress)"
        $desktopOnlyStatusPath = Join-Path $desktopOnlyRoot 'var\codex-smoke\goal-next-auto.status.json'
        Assert-True 'desktop-only default writes status artifact' (Test-Path $desktopOnlyStatusPath) "missing status at $desktopOnlyStatusPath"
        $desktopOnlyStatus = Get-Content -Raw -LiteralPath $desktopOnlyStatusPath | ConvertFrom-Json
        Assert-True 'desktop-only default preserves external input gate without primary Supabase action' (
            -not (($desktopOnlyStatus.nextActionSources -join ',').Contains('supabase_apply')) -and
            [string]$desktopOnlyStatus.firstAction -ne 'set_SUPABASE_PROJECT_REF' -and
            [string]$desktopOnlyStatus.firstActionSource -ne 'supabase_apply' -and
            [string]$desktopOnlyStatus.externalInputGate.status -eq 'local_or_unknown' -and
            [string]$desktopOnlyStatus.externalInputGate.source -ne 'supabase_apply' -and
            [string]$desktopOnlyStatus.externalInputGate.action -ne 'set_SUPABASE_PROJECT_REF' -and
            $desktopOnlyStatus.externalInputGate.localPatchJustified -eq $true -and
            $desktopOnlyStatus.externalInputGate.mutationAllowed -eq $false -and
            [string]$desktopOnlyStatus.supabaseApply.decision -eq 'evidence_needed' -and
            (@($desktopOnlyStatus.supabaseApply.requiredEnvNames) -join ',').Contains('SUPABASE_PROJECT_REF')
        ) "status=$($desktopOnlyStatus | ConvertTo-Json -Compress)"
    }

    $seededAuditArtifact = Join-Path $desktopOnlyOutput 'awx-mcp-completion-audit.preflight.result.json'
    $seededAuditHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $seededAuditArtifact).Hash.ToLowerInvariant()
    Set-TestFile (Join-Path $desktopOnlyRoot 'fail-completion-audit-before-write.flag') '1'
    $failedAuditRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $desktopOnlyRoot, '-OutputDir', $desktopOnlyOutput, '-Topic', 'mcp-control-loop')
    $failedAuditLogRaw = Get-Content -Raw -LiteralPath (Join-Path $desktopOnlyOutput 'awx-mcp-completion-audit.preflight.log')
    $failedAuditLog = $failedAuditLogRaw | ConvertFrom-Json
    Assert-True 'failed child does not relabel a preexisting audit artifact as current-run output' (
        [int]$failedAuditLog.exitCode -eq 17 -and
        $failedAuditLog.artifactPresent -eq $true -and
        $failedAuditLog.artifactProducedThisRun -eq $false -and
        [string]$failedAuditLog.artifactHash -eq $seededAuditHash -and
        [int64]$failedAuditLog.artifactBytes -gt 0 -and
        $failedAuditLog.rawOutputStored -eq $false
    ) "exit=$($failedAuditRefresh.ExitCode) compactLog=$failedAuditLogRaw"

    $warmAuditRoot = New-FakeGoalRoot -Mode 'partial'
    $warmAuditOutput = Join-Path $warmAuditRoot 'warm-out'
    Set-CompletionAuditBootstrapFixture -Root $warmAuditRoot -State 'current'
    $warmAuditRun = Invoke-Captured -Arguments @('-File', $script, '-Root', $warmAuditRoot, '-OutputDir', $warmAuditOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'warm Desktop-only path exits zero' ($warmAuditRun.ExitCode -eq 0) "expected exit 0; output=$($warmAuditRun.Output)"
    $warmAuditSummaryPath = Join-Path $warmAuditOutput 'goal-next-auto.summary.json'
    Assert-True 'warm Desktop-only path writes summary' (Test-Path -LiteralPath $warmAuditSummaryPath) "missing warm summary at $warmAuditSummaryPath"
    if (Test-Path -LiteralPath $warmAuditSummaryPath) {
        $warmAuditSummary = Get-Content -Raw -LiteralPath $warmAuditSummaryPath | ConvertFrom-Json
        $warmAuditInvocations = @(Get-Content -LiteralPath (Join-Path $warmAuditRoot 'completion-audit-invocations.jsonl'))
        Assert-True 'warm Desktop-only path keeps packet and final audits only' (
            @($warmAuditInvocations).Count -eq 2 -and
            ($warmAuditInvocations -join ',') -eq 'awx-mcp-completion-audit.packet.result.json,awx-mcp-completion-audit.result.json' -and
            -not (Test-Path -LiteralPath (Join-Path $warmAuditOutput 'awx-mcp-completion-audit.preflight.result.json')) -and
            -not (Test-Path -LiteralPath (Join-Path $warmAuditOutput 'awx-mcp-completion-audit.preflight.log')) -and
            (Test-Path -LiteralPath (Join-Path $warmAuditOutput 'awx-mcp-completion-audit.packet.result.json')) -and
            (Test-Path -LiteralPath (Join-Path $warmAuditOutput 'awx-mcp-completion-audit.result.json')) -and
            $warmAuditSummary.completionAuditPreflight.executed -eq $false -and
            $warmAuditSummary.completionAuditPreflight.reuseAllowed -eq $true -and
            [string]$warmAuditSummary.completionAuditPreflight.reason -eq 'fresh-safe-canonical-reuse'
        ) "auditInvocations=$($warmAuditInvocations.Count) summary=$($warmAuditSummary | ConvertTo-Json -Compress)"
    }

    $coldAuditCases = [ordered]@{
        missing = 'canonical_missing'
        malformed = 'canonical_malformed'
        missing_generated_at = 'canonical_generated_at_missing'
        invalid_generated_at = 'canonical_generated_at_invalid'
        stale = 'canonical_stale'
        future = 'canonical_future_generated_at'
        unsafe = 'canonical_secret_hits'
        missing_counter = 'canonical_secret_counters_invalid'
        invalid_counter = 'canonical_secret_counters_invalid'
    }
    foreach ($coldState in $coldAuditCases.Keys) {
        $coldAuditRoot = New-FakeGoalRoot -Mode 'partial'
        Set-CompletionAuditBootstrapFixture -Root $coldAuditRoot -State $coldState
        $coldAuditDecision = Get-CompletionAuditBootstrapDecision -ProjectRoot $coldAuditRoot
        Assert-True "cold $coldState bootstrap decision fails closed" (
            [string]$coldAuditDecision.mode -eq 'cold' -and
            $coldAuditDecision.preflightRequired -eq $true -and
            $coldAuditDecision.preflightExecuted -eq $false -and
            $coldAuditDecision.reuseAllowed -eq $false -and
            [string]$coldAuditDecision.reason -eq [string]$coldAuditCases[$coldState]
        ) "state=$coldState decision=$($coldAuditDecision | ConvertTo-Json -Compress)"
    }

    $modeReuseRoot = New-FakeGoalRoot -Mode 'complete'
    $modeReuseDefaultOutput = Join-Path $modeReuseRoot 'mode-reuse-default-out'
    $modeReuseDefault = Invoke-Captured -Arguments @('-File', $script, '-Root', $modeReuseRoot, '-OutputDir', $modeReuseDefaultOutput)
    Assert-True 'mode-reuse fixture writes a default latest pointer' ($modeReuseDefault.ExitCode -eq 0) "expected exit 0; output=$($modeReuseDefault.Output)"
    $modeReuseInvocationPath = Join-Path $modeReuseRoot 'tool-invocations.jsonl'
    $modeReuseInvocationsBefore = @(Get-Content -LiteralPath $modeReuseInvocationPath)
    Assert-True 'default mode does not invoke opt-in Supabase children' (
        @($modeReuseInvocationsBefore | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 0 -and
        @($modeReuseInvocationsBefore | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 0
    ) "toolInvocations=$($modeReuseInvocationsBefore -join ',')"

    $modeReuseSupabaseOutput = Join-Path $modeReuseRoot 'mode-reuse-supabase-out'
    $modeReuseSupabase = Invoke-Captured -Arguments @('-File', $script, '-Root', $modeReuseRoot, '-OutputDir', $modeReuseSupabaseOutput, '-EnsureFresh', '-RequireSupabaseProof')
    Assert-True 'ensure-fresh refreshes when Supabase proof mode differs from latest' ($modeReuseSupabase.ExitCode -eq 0) "expected exit 0; output=$($modeReuseSupabase.Output)"
    Assert-Contains 'mode mismatch reports refresh action' $modeReuseSupabase.Output 'action=refresh'
    Assert-Contains 'mode mismatch reports exact reason' $modeReuseSupabase.Output 'reason=latest-mode-mismatch'
    $modeReuseInvocationsAfter = @(Get-Content -LiteralPath $modeReuseInvocationPath)
    Assert-True 'mode mismatch runs Supabase children exactly once' (
        @($modeReuseInvocationsAfter | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 1 -and
        @($modeReuseInvocationsAfter | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 1
    ) "toolInvocations=$($modeReuseInvocationsAfter -join ',')"
    $modeReuseLatestPath = Join-Path $modeReuseRoot 'var\codex-smoke\goal-next-auto.latest.json'
    $modeReuseLatest = Get-Content -Raw -LiteralPath $modeReuseLatestPath | ConvertFrom-Json
    Assert-True 'latest pointer records exact Supabase request mode' (
        $modeReuseLatest.requestMode.requireSupabaseProof -eq $true -and
        $modeReuseLatest.requestMode.externalDispatch -eq $false -and
        $modeReuseLatest.requestMode.refreshWebProbe -eq $false -and
        $modeReuseLatest.requestMode.requireUiProof -eq $false
    ) "latest=$($modeReuseLatest | ConvertTo-Json -Compress)"

    $modeReuseSupabaseAgainOutput = Join-Path $modeReuseRoot 'mode-reuse-supabase-again-out'
    $modeReuseSupabaseAgain = Invoke-Captured -Arguments @('-File', $script, '-Root', $modeReuseRoot, '-OutputDir', $modeReuseSupabaseAgainOutput, '-EnsureFresh', '-RequireSupabaseProof')
    Assert-True 'ensure-fresh reuses latest when exact request mode matches' ($modeReuseSupabaseAgain.ExitCode -eq 0) "expected exit 0; output=$($modeReuseSupabaseAgain.Output)"
    Assert-Contains 'exact mode match reports status action' $modeReuseSupabaseAgain.Output 'action=status'
    Assert-Contains 'exact mode match reports fresh reason' $modeReuseSupabaseAgain.Output 'reason=fresh-latest'
    $modeReuseInvocationsFinal = @(Get-Content -LiteralPath $modeReuseInvocationPath)
    Assert-True 'exact mode reuse does not rerun Supabase children' ($modeReuseInvocationsFinal.Count -eq $modeReuseInvocationsAfter.Count) "before=$($modeReuseInvocationsAfter.Count) after=$($modeReuseInvocationsFinal.Count)"

    $supabaseOnlyRoot = New-FakeGoalRoot -Mode 'complete'
    $supabaseOnlyOutput = Join-Path $supabaseOnlyRoot 'supabase-only-out'
    $supabaseOnly = Invoke-Captured -Arguments @('-File', $script, '-Root', $supabaseOnlyRoot, '-OutputDir', $supabaseOnlyOutput, '-RequireSupabaseProof')
    Assert-True 'Supabase-proof mode completes with only Supabase child processes' ($supabaseOnly.ExitCode -eq 0) "expected exit 0; output=$($supabaseOnly.Output)"
    $supabaseOnlyInvocations = @(Get-Content -LiteralPath (Join-Path $supabaseOnlyRoot 'tool-invocations.jsonl'))
    Assert-True 'Supabase-proof mode invokes smoke and apply exactly once without external apply' (
        @($supabaseOnlyInvocations | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 1 -and
        @($supabaseOnlyInvocations | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 1 -and
        @($supabaseOnlyInvocations | Where-Object { $_ -eq 'external_apply' }).Count -eq 0 -and
        @($supabaseOnlyInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 0
    ) "toolInvocations=$($supabaseOnlyInvocations -join ',')"
    $supabaseOnlySummary = Get-Content -Raw -LiteralPath (Join-Path $supabaseOnlyOutput 'goal-next-auto.summary.json') | ConvertFrom-Json
    $supabaseOnlyControlLoop = Get-Content -Raw -LiteralPath $supabaseOnlySummary.artifacts.desktopControlLoop | ConvertFrom-Json
    Assert-True 'Supabase-proof mode keeps nested completion audit enabled' ($supabaseOnlyControlLoop.completionAuditRunRequested -eq $true) "desktopControlLoop=$($supabaseOnlyControlLoop | ConvertTo-Json -Compress)"

    $externalOnlyRoot = New-FakeGoalRoot -Mode 'complete'
    $externalOnlyOutput = Join-Path $externalOnlyRoot 'external-only-out'
    $externalOnly = Invoke-Captured -Arguments @('-File', $script, '-Root', $externalOnlyRoot, '-OutputDir', $externalOnlyOutput, '-ExternalDispatch')
    Assert-True 'external-dispatch mode completes with only external apply child process' ($externalOnly.ExitCode -eq 0) "expected exit 0; output=$($externalOnly.Output)"
    $externalOnlyInvocations = @(Get-Content -LiteralPath (Join-Path $externalOnlyRoot 'tool-invocations.jsonl'))
    Assert-True 'external-dispatch mode invokes external apply exactly once without Supabase children' (
        @($externalOnlyInvocations | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 0 -and
        @($externalOnlyInvocations | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 0 -and
        @($externalOnlyInvocations | Where-Object { $_ -eq 'external_apply' }).Count -eq 1 -and
        @($externalOnlyInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 0
    ) "toolInvocations=$($externalOnlyInvocations -join ',')"
    $externalOnlySummary = Get-Content -Raw -LiteralPath (Join-Path $externalOnlyOutput 'goal-next-auto.summary.json') | ConvertFrom-Json
    $externalOnlyControlLoop = Get-Content -Raw -LiteralPath $externalOnlySummary.artifacts.desktopControlLoop | ConvertFrom-Json
    Assert-True 'external-dispatch mode keeps nested completion audit enabled' ($externalOnlyControlLoop.completionAuditRunRequested -eq $true) "desktopControlLoop=$($externalOnlyControlLoop | ConvertTo-Json -Compress)"

    $allExternalProofRoot = New-FakeGoalRoot -Mode 'complete'
    $allExternalProofOutput = Join-Path $allExternalProofRoot 'all-external-proof-out'
    $allExternalProof = Invoke-Captured -Arguments @('-File', $script, '-Root', $allExternalProofRoot, '-OutputDir', $allExternalProofOutput, '-RequireSupabaseProof', '-ExternalDispatch')
    Assert-True 'combined external-proof mode completes' ($allExternalProof.ExitCode -eq 0) "expected exit 0; output=$($allExternalProof.Output)"
    $allExternalProofInvocations = @(Get-Content -LiteralPath (Join-Path $allExternalProofRoot 'tool-invocations.jsonl'))
    Assert-True 'combined external-proof mode invokes all three child processes exactly once' (
        @($allExternalProofInvocations | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 1 -and
        @($allExternalProofInvocations | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 1 -and
        @($allExternalProofInvocations | Where-Object { $_ -eq 'external_apply' }).Count -eq 1 -and
        @($allExternalProofInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 0
    ) "toolInvocations=$($allExternalProofInvocations -join ',')"

    $webRefreshRoot = New-FakeGoalRoot -Mode 'complete'
    $webRefreshOutput = Join-Path $webRefreshRoot 'web-refresh-out'
    $webRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $webRefreshRoot, '-OutputDir', $webRefreshOutput, '-RefreshWebProbe')
    Assert-True 'explicit web refresh mode completes' ($webRefresh.ExitCode -eq 0) "expected exit 0; output=$($webRefresh.Output)"
    $webRefreshInvocations = @(Get-Content -LiteralPath (Join-Path $webRefreshRoot 'tool-invocations.jsonl'))
    Assert-True 'explicit web refresh mode invokes the network-capable tool exactly once' (
        @($webRefreshInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 1 -and
        @($webRefreshInvocations | Where-Object { $_ -eq 'supabase_smoke' }).Count -eq 0 -and
        @($webRefreshInvocations | Where-Object { $_ -eq 'supabase_apply' }).Count -eq 0 -and
        @($webRefreshInvocations | Where-Object { $_ -eq 'external_apply' }).Count -eq 0
    ) "toolInvocations=$($webRefreshInvocations -join ',')"
    $webRefreshSummary = Get-Content -Raw -LiteralPath (Join-Path $webRefreshOutput 'goal-next-auto.summary.json') | ConvertFrom-Json
    Assert-True 'explicit web refresh mode writes current sanitized artifact' (Test-Path -LiteralPath (Join-Path $webRefreshRoot 'var\codex-smoke\web-probe-refresh.json')) 'missing explicit web refresh artifact'
    Assert-True 'explicit web refresh summary records collected proof lineage' (
        $webRefreshSummary.webProbeRefresh.refreshRequested -eq $true -and
        $webRefreshSummary.webProbeRefresh.processExecuted -eq $true -and
        $webRefreshSummary.webProbeRefresh.contractReady -eq $true -and
        $webRefreshSummary.webProbeRefresh.proofReady -eq $true -and
        $webRefreshSummary.webProbeRefresh.supportingEvidenceOnly -eq $true -and
        $webRefreshSummary.webProbeRefresh.fresh -eq $true -and
        [string]$webRefreshSummary.webProbeRefresh.artifactHash -match '^[0-9a-f]{64}$' -and
        -not [string]::IsNullOrWhiteSpace([string]$webRefreshSummary.webProbeRefresh.generatedAt) -and
        [string]$webRefreshSummary.webProbeRefresh.decision -eq 'ok'
    ) "webProbeRefresh=$($webRefreshSummary.webProbeRefresh | ConvertTo-Json -Compress)"

    $webRefreshArtifactPath = Join-Path $webRefreshRoot 'var\codex-smoke\web-probe-refresh.json'
    $webRefreshArtifactHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $webRefreshArtifactPath).Hash.ToLowerInvariant()
    Set-TestFile (Join-Path $webRefreshRoot 'crash-web-probe-refresh-before-write.txt') '1'
    $webRefreshCrashOutput = Join-Path $webRefreshRoot 'web-refresh-crash-out'
    $webRefreshCrash = Invoke-Captured -Arguments @('-File', $script, '-Root', $webRefreshRoot, '-OutputDir', $webRefreshCrashOutput, '-RefreshWebProbe')
    Assert-True 'explicit web refresh crash exits evidence needed' ($webRefreshCrash.ExitCode -eq 2) "expected exit 2; output=$($webRefreshCrash.Output)"
    $webRefreshCrashSummary = Get-Content -Raw -LiteralPath (Join-Path $webRefreshCrashOutput 'goal-next-auto.summary.json') | ConvertFrom-Json
    Assert-True 'explicit web refresh crash cannot reuse prior fresh proof as current' (
        [int]$webRefreshCrashSummary.webProbeRefresh.processExitCode -eq 17 -and
        $webRefreshCrashSummary.webProbeRefresh.artifactProducedThisRun -eq $false -and
        $webRefreshCrashSummary.webProbeRefresh.proofReady -eq $false -and
        [string]$webRefreshCrashSummary.webProbeRefresh.artifactHash -eq $webRefreshArtifactHash
    ) "summary=$($webRefreshCrashSummary.webProbeRefresh | ConvertTo-Json -Compress)"

    $webRefreshFailureRoot = New-FakeGoalRoot -Mode 'complete' -FailWebProbeRefresh
    $webRefreshFailureOutput = Join-Path $webRefreshFailureRoot 'web-refresh-failure-out'
    $webRefreshFailure = Invoke-Captured -Arguments @('-File', $script, '-Root', $webRefreshFailureRoot, '-OutputDir', $webRefreshFailureOutput, '-RefreshWebProbe')
    Assert-True 'explicit failed web refresh exits evidence needed' ($webRefreshFailure.ExitCode -eq 2) "expected exit 2; output=$($webRefreshFailure.Output)"
    $webRefreshFailureInvocations = @(Get-Content -LiteralPath (Join-Path $webRefreshFailureRoot 'tool-invocations.jsonl'))
    Assert-True 'explicit failed web refresh still invokes the tool exactly once' (@($webRefreshFailureInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 1) "toolInvocations=$($webRefreshFailureInvocations -join ',')"
    $webRefreshFailureSummary = Get-Content -Raw -LiteralPath (Join-Path $webRefreshFailureOutput 'goal-next-auto.summary.json') | ConvertFrom-Json
    Assert-True 'explicit failed web refresh fails closed on proof rather than process exit alone' (
        $webRefreshFailureSummary.webProbeRefresh.refreshRequested -eq $true -and
        $webRefreshFailureSummary.webProbeRefresh.processExecuted -eq $true -and
        $webRefreshFailureSummary.webProbeRefresh.contractReady -eq $true -and
        $webRefreshFailureSummary.webProbeRefresh.proofReady -eq $false -and
        [string]$webRefreshFailureSummary.webProbeRefresh.decision -eq 'evidence_needed' -and
        (($webRefreshFailureSummary.nextActionSources -join ',').Contains('web_probe_refresh'))
    ) "summary=$($webRefreshFailureSummary | ConvertTo-Json -Compress)"

    $wrapperWebRoot = New-FakeGoalRoot -Mode 'complete'
    $wrapperWebSeedOutput = Join-Path $wrapperWebRoot 'seed-out'
    $wrapperWebSeed = Invoke-Captured -Arguments @('-File', $script, '-Root', $wrapperWebRoot, '-OutputDir', $wrapperWebSeedOutput)
    Assert-True 'wrapper web refresh seed run completes without web network call' ($wrapperWebSeed.ExitCode -eq 0) "output=$($wrapperWebSeed.Output)"
    $wrapperWebBefore = @(Get-Content -LiteralPath (Join-Path $wrapperWebRoot 'tool-invocations.jsonl') | Where-Object { $_ -eq 'web_probe_refresh' }).Count
    $wrapperWebRefresh = Invoke-Captured -Arguments @('-File', $nextScript, '-Root', $wrapperWebRoot, '-OutputDir', (Join-Path $wrapperWebRoot 'wrapper-web-out'), '-RefreshWebProbe')
    Assert-True 'wrapper explicit web refresh bypasses fresh-latest reuse' ($wrapperWebRefresh.ExitCode -eq 0) "output=$($wrapperWebRefresh.Output)"
    Assert-Contains 'wrapper explicit web refresh reports requested refresh reason' $wrapperWebRefresh.Output 'reason=web-probe-refresh-requested'
    $wrapperWebAfter = @(Get-Content -LiteralPath (Join-Path $wrapperWebRoot 'tool-invocations.jsonl') | Where-Object { $_ -eq 'web_probe_refresh' }).Count
    Assert-True 'wrapper explicit web refresh invokes exactly one new web process' ($wrapperWebAfter -eq ($wrapperWebBefore + 1)) "before=$wrapperWebBefore after=$wrapperWebAfter"
    $statusWebReadOnly = Invoke-Captured -Arguments @('-File', $script, '-Root', $wrapperWebRoot, '-Status', '-RefreshWebProbe')
    Assert-True 'status mode remains read only even with web refresh flag' ($statusWebReadOnly.ExitCode -eq 0) "output=$($statusWebReadOnly.Output)"
    $statusWebAfter = @(Get-Content -LiteralPath (Join-Path $wrapperWebRoot 'tool-invocations.jsonl') | Where-Object { $_ -eq 'web_probe_refresh' }).Count
    Assert-True 'status mode does not launch a web process' ($statusWebAfter -eq $wrapperWebAfter) "before=$wrapperWebAfter after=$statusWebAfter"

    $peerBusFallbackRoot = New-FakeGoalRoot -Mode 'partial' -OmitEmbeddedPeerEvidenceBus
    $peerBusFallbackOutput = Join-Path $peerBusFallbackRoot 'out'
    $peerBusFallback = Invoke-Captured -Arguments @('-File', $script, '-Root', $peerBusFallbackRoot, '-OutputDir', $peerBusFallbackOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'missing embedded peer evidence bus falls back without blocking desktop-only readiness' ($peerBusFallback.ExitCode -eq 0) "expected exit 0; output=$($peerBusFallback.Output)"
    $peerBusFallbackInvocations = @(Get-Content -LiteralPath (Join-Path $peerBusFallbackRoot 'tool-invocations.jsonl'))
    Assert-True 'missing embedded peer evidence bus runs one standalone fallback' (
        @($peerBusFallbackInvocations | Where-Object { $_ -eq 'desktop_control_loop' }).Count -eq 1 -and
        @($peerBusFallbackInvocations | Where-Object { $_ -eq 'peer_evidence_bus' }).Count -eq 1
    ) "toolInvocations=$($peerBusFallbackInvocations -join ',')"
    $peerBusFallbackArtifactPath = Join-Path $peerBusFallbackRoot 'var\codex-smoke\peer-evidence-bus.json'
    Assert-True 'standalone peer evidence bus fallback preserves artifact' (Test-Path -LiteralPath $peerBusFallbackArtifactPath) "missing artifact=$peerBusFallbackArtifactPath"
    if (Test-Path -LiteralPath $peerBusFallbackArtifactPath) {
        $peerBusFallbackArtifact = Get-Content -Raw -LiteralPath $peerBusFallbackArtifactPath | ConvertFrom-Json
        Assert-True 'standalone peer evidence bus fallback preserves schema' (
            [string]$peerBusFallbackArtifact.schemaVersion -eq 'awx.mcp.peer_evidence_bus.v1' -and
            [string]$peerBusFallbackArtifact.decision -eq 'peer_evidence_bus' -and
            [int]$peerBusFallbackArtifact.rawSecretPatternHits -eq 0
        ) "peerBusFallbackArtifact=$($peerBusFallbackArtifact | ConvertTo-Json -Compress)"
    }

    $peerBusMalformedRoot = New-FakeGoalRoot -Mode 'partial' -MalformedEmbeddedPeerEvidenceBus
    $peerBusMalformedOutput = Join-Path $peerBusMalformedRoot 'out'
    $peerBusMalformed = Invoke-Captured -Arguments @('-File', $script, '-Root', $peerBusMalformedRoot, '-OutputDir', $peerBusMalformedOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'malformed embedded peer evidence bus falls back without blocking desktop-only readiness' ($peerBusMalformed.ExitCode -eq 0) "expected exit 0; output=$($peerBusMalformed.Output)"
    $peerBusMalformedInvocations = @(Get-Content -LiteralPath (Join-Path $peerBusMalformedRoot 'tool-invocations.jsonl'))
    Assert-True 'malformed embedded peer evidence bus runs one standalone fallback' (
        @($peerBusMalformedInvocations | Where-Object { $_ -eq 'desktop_control_loop' }).Count -eq 1 -and
        @($peerBusMalformedInvocations | Where-Object { $_ -eq 'peer_evidence_bus' }).Count -eq 1
    ) "toolInvocations=$($peerBusMalformedInvocations -join ',')"
    $peerBusMalformedArtifactPath = Join-Path $peerBusMalformedRoot 'var\codex-smoke\peer-evidence-bus.json'
    $peerBusMalformedArtifact = Get-Content -Raw -LiteralPath $peerBusMalformedArtifactPath | ConvertFrom-Json
    Assert-True 'malformed embedded peer evidence bus fallback overwrites invalid schema' (
        [string]$peerBusMalformedArtifact.schemaVersion -eq 'awx.mcp.peer_evidence_bus.v1' -and
        [string]$peerBusMalformedArtifact.decision -eq 'peer_evidence_bus' -and
        [int]$peerBusMalformedArtifact.rawSecretPatternHits -eq 0
    ) "peerBusMalformedArtifact=$($peerBusMalformedArtifact | ConvertTo-Json -Compress)"

    $peerBusTruncatedRoot = New-FakeGoalRoot -Mode 'partial' -TruncatedEmbeddedPeerEvidenceBus
    $peerBusTruncatedOutput = Join-Path $peerBusTruncatedRoot 'out'
    $peerBusTruncated = Invoke-Captured -Arguments @('-File', $script, '-Root', $peerBusTruncatedRoot, '-OutputDir', $peerBusTruncatedOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'truncated v1 embedded peer evidence bus falls back without blocking desktop-only readiness' ($peerBusTruncated.ExitCode -eq 0) "expected exit 0; output=$($peerBusTruncated.Output)"
    $peerBusTruncatedInvocations = @(Get-Content -LiteralPath (Join-Path $peerBusTruncatedRoot 'tool-invocations.jsonl'))
    Assert-True 'truncated v1 embedded peer evidence bus runs one standalone fallback' (
        @($peerBusTruncatedInvocations | Where-Object { $_ -eq 'desktop_control_loop' }).Count -eq 1 -and
        @($peerBusTruncatedInvocations | Where-Object { $_ -eq 'peer_evidence_bus' }).Count -eq 1
    ) "toolInvocations=$($peerBusTruncatedInvocations -join ',')"
    $peerBusTruncatedArtifactPath = Join-Path $peerBusTruncatedRoot 'var\codex-smoke\peer-evidence-bus.json'
    $peerBusTruncatedArtifact = Get-Content -Raw -LiteralPath $peerBusTruncatedArtifactPath | ConvertFrom-Json
    Assert-True 'truncated v1 embedded peer evidence bus fallback restores full contract' (
        [string]$peerBusTruncatedArtifact.schemaVersion -eq 'awx.mcp.peer_evidence_bus.v1' -and
        $null -ne $peerBusTruncatedArtifact.claudePeersProtocol -and
        $null -ne $peerBusTruncatedArtifact.safePeerIdentityContract -and
        $null -ne $peerBusTruncatedArtifact.peerIdentitySummary -and
        $null -ne $peerBusTruncatedArtifact.webProbeLedger -and
        $null -ne $peerBusTruncatedArtifact.webProbeRefreshPacket
    ) "peerBusTruncatedArtifact=$($peerBusTruncatedArtifact | ConvertTo-Json -Compress)"

    $peerBusIncompleteWebRoot = New-FakeGoalRoot -Mode 'partial' -IncompleteEmbeddedPeerWebProbe
    $peerBusIncompleteWebOutput = Join-Path $peerBusIncompleteWebRoot 'out'
    $peerBusIncompleteWeb = Invoke-Captured -Arguments @('-File', $script, '-Root', $peerBusIncompleteWebRoot, '-OutputDir', $peerBusIncompleteWebOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'incomplete embedded web-probe contract falls back without blocking desktop-only readiness' ($peerBusIncompleteWeb.ExitCode -eq 0) "expected exit 0; output=$($peerBusIncompleteWeb.Output)"
    $peerBusIncompleteWebInvocations = @(Get-Content -LiteralPath (Join-Path $peerBusIncompleteWebRoot 'tool-invocations.jsonl'))
    Assert-True 'missing embedded fetchTargets runs one standalone peer fallback' (
        @($peerBusIncompleteWebInvocations | Where-Object { $_ -eq 'desktop_control_loop' }).Count -eq 1 -and
        @($peerBusIncompleteWebInvocations | Where-Object { $_ -eq 'peer_evidence_bus' }).Count -eq 1
    ) "toolInvocations=$($peerBusIncompleteWebInvocations -join ',')"
    $peerBusIncompleteWebArtifact = Get-Content -Raw -LiteralPath (Join-Path $peerBusIncompleteWebRoot 'var\codex-smoke\peer-evidence-bus.json') | ConvertFrom-Json
    Assert-True 'standalone fallback restores counted web-probe fetch targets' (
        @($peerBusIncompleteWebArtifact.webProbeRefreshPacket.fetchTargets).Count -eq [int]$peerBusIncompleteWebArtifact.webProbeRefreshPacket.officialSourceCount -and
        @($peerBusIncompleteWebArtifact.webProbeRefreshPacket.fetchTargets).Count -ge 4
    ) "peerBusArtifact=$($peerBusIncompleteWebArtifact | ConvertTo-Json -Compress)"

    $peerBusJdbcRoot = New-FakeGoalRoot -Mode 'partial' -EmbeddedPeerJdbcSecret
    $peerBusJdbcOutput = Join-Path $peerBusJdbcRoot 'out'
    $peerBusJdbc = Invoke-Captured -Arguments @('-File', $script, '-Root', $peerBusJdbcRoot, '-OutputDir', $peerBusJdbcOutput, '-Topic', 'mcp-control-loop')
    Assert-True 'decoded embedded JDBC probe fails closed after safe fallback' ($peerBusJdbc.ExitCode -eq 4) "expected exit 4; output=$($peerBusJdbc.Output)"
    $peerBusJdbcInvocations = @(Get-Content -LiteralPath (Join-Path $peerBusJdbcRoot 'tool-invocations.jsonl'))
    Assert-True 'decoded embedded JDBC probe runs one standalone peer fallback' (
        @($peerBusJdbcInvocations | Where-Object { $_ -eq 'desktop_control_loop' }).Count -eq 1 -and
        @($peerBusJdbcInvocations | Where-Object { $_ -eq 'peer_evidence_bus' }).Count -eq 1
    ) "toolInvocations=$($peerBusJdbcInvocations -join ',')"
    $peerBusJdbcPersistedPaths = @(
        (Join-Path $peerBusJdbcRoot 'var\codex-smoke\peer-evidence-bus.json'),
        (Join-Path $peerBusJdbcOutput 'desktop-control-loop.result.json'),
        (Join-Path $peerBusJdbcOutput 'desktop-control-loop.log')
    )
    $peerBusJdbcPersistedText = @($peerBusJdbcPersistedPaths | ForEach-Object { Get-Content -Raw -LiteralPath $_ }) -join "`n"
    Assert-True 'JDBC probe is redacted from persisted peer and control-loop artifacts' (
        -not ($peerBusJdbcPersistedText -match '(?i)\bjdbc:[A-Za-z0-9_+.-]*://|example\.invalid')
    ) "unsafe JDBC probe persisted in one of $($peerBusJdbcPersistedPaths.Count) checked artifacts"

    $noPrimaryRoot = New-FakeGoalRoot -Mode 'complete'
    $noPrimarySmokeRoot = Join-Path $noPrimaryRoot 'var\codex-smoke'
    New-Item -ItemType Directory -Force -Path $noPrimarySmokeRoot | Out-Null
    $noPrimaryLatestPath = Join-Path $noPrimarySmokeRoot 'goal-next-auto.latest.json'
    $noPrimaryGeneratedAt = [DateTime]::UtcNow.ToString('o')
    ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.latest.v1'
        requestMode = [ordered]@{ requireSupabaseProof = $false; externalDispatch = $false; refreshWebProbe = $false; requireUiProof = $false }
        generatedAt = $noPrimaryGeneratedAt
        latestStaleAfterMinutes = 60
        decision = 'evidence_needed'
        summaryPath = ''
        nextActionsPath = ''
        nextActionEntryCount = 0
        firstAction = ''
        firstActionSource = ''
        failureClassification = 'evidence_needed'
        externalInputGate = [ordered]@{
            schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
            status = 'local_or_unknown'
            source = ''
            action = ''
            repeated = $false
            repeatCount = 0
            localPatchJustified = $true
            mutationAllowed = $false
            evidenceNeeded = @()
            secretHits = 0
            windowsAbsPathHits = 0
        }
        secretHits = 0
    } | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $noPrimaryLatestPath -Encoding UTF8
    $noPrimaryStatusRun = Invoke-Captured -Arguments @('-File', $script, '-Root', $noPrimaryRoot, '-Status')
    Assert-True 'no-primary status exits evidence_needed without inventing action' ($noPrimaryStatusRun.ExitCode -eq 2) "expected exit 2; output=$($noPrimaryStatusRun.Output)"
    Assert-Contains 'no-primary status reports fresh latest' $noPrimaryStatusRun.Output 'staleLatest=false'
    Assert-True 'no-primary status output omits generic evidence action' (-not ($noPrimaryStatusRun.Output.Contains('firstAction=evidence_needed') -or $noPrimaryStatusRun.Output.Contains('externalInputGateAction=evidence_needed'))) "output=$($noPrimaryStatusRun.Output)"
    $noPrimaryStatusPath = Join-Path $noPrimarySmokeRoot 'goal-next-auto.status.json'
    Assert-True 'no-primary status writes status artifact' (Test-Path $noPrimaryStatusPath) "missing status at $noPrimaryStatusPath"
    $noPrimaryStatus = Get-Content -Raw -LiteralPath $noPrimaryStatusPath | ConvertFrom-Json
    Assert-True 'no-primary status preserves empty primary action' (
        [string]$noPrimaryStatus.firstAction -eq '' -and
        [string]$noPrimaryStatus.firstActionSource -eq '' -and
        [string]$noPrimaryStatus.externalInputGate.status -eq 'local_or_unknown' -and
        [string]$noPrimaryStatus.externalInputGate.source -eq '' -and
        [string]$noPrimaryStatus.externalInputGate.action -eq '' -and
        $noPrimaryStatus.externalInputGate.localPatchJustified -eq $true -and
        [int]$noPrimaryStatus.secretHits -eq 0
    ) "status=$($noPrimaryStatus | ConvertTo-Json -Compress)"

    $desktopReadyRoot = New-FakeGoalRoot -Mode 'complete'
    $desktopReadySmokeRoot = Join-Path $desktopReadyRoot 'var\codex-smoke'
    New-Item -ItemType Directory -Force -Path $desktopReadySmokeRoot | Out-Null
    $desktopReadySummaryPath = Join-Path $desktopReadySmokeRoot 'desktop-ready-summary.json'
    ([ordered]@{
        decision = 'evidence_needed'
        nextActionCount = 0
        nextActionSources = @()
        topActions = @()
        firstAction = ''
        firstActionSource = ''
        externalInputGate = [ordered]@{
            schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
            status = 'local_or_unknown'
            source = ''
            action = ''
            repeated = $false
            repeatCount = 0
            localPatchJustified = $true
            mutationAllowed = $false
            evidenceNeeded = @()
            secretHits = 0
            windowsAbsPathHits = 0
        }
        desktopControlLoop = [ordered]@{
            localReady = $true
            completionReady = $false
            desktopFinalProof = 'evidence_needed'
            nextActionCount = 0
            dispatchWriteRequested = $false
            producerKitWriteRequested = $false
        }
        computerUse = [ordered]@{ ok = $true; decision = 'ok'; reachable = $true; stale = $false; appCount = 3; secretHits = 0 }
        browserUse = [ordered]@{ ok = $true; decision = 'ok'; reachable = $true; localhost = $true; targetAccepted = $true; stale = $false; secretHits = 0 }
        supabaseApply = [ordered]@{ decision = 'evidence_needed'; requiredEnvNames = @('SUPABASE_PROJECT_REF') }
        externalApply = [ordered]@{ decision = 'supporting_evidence_missing'; producerBundlesRequired = $false; nextActionCount = 0 }
        secretHits = 0
    } | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $desktopReadySummaryPath -Encoding UTF8
    $desktopReadyLatestPath = Join-Path $desktopReadySmokeRoot 'goal-next-auto.latest.json'
    ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.latest.v1'
        requestMode = [ordered]@{ requireSupabaseProof = $false; externalDispatch = $false; refreshWebProbe = $false; requireUiProof = $false }
        generatedAt = [DateTime]::UtcNow.ToString('o')
        latestStaleAfterMinutes = 60
        decision = 'evidence_needed'
        summaryPath = $desktopReadySummaryPath
        nextActionsPath = ''
        nextActionEntryCount = 0
        firstAction = ''
        firstActionSource = ''
        failureClassification = 'evidence_needed'
        externalInputGate = [ordered]@{
            schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
            status = 'local_or_unknown'
            source = ''
            action = ''
            repeated = $false
            repeatCount = 0
            localPatchJustified = $true
            mutationAllowed = $false
            evidenceNeeded = @()
            secretHits = 0
            windowsAbsPathHits = 0
        }
        computerUseOk = $true
        computerUseDecision = 'ok'
        browserUseOk = $true
        browserUseDecision = 'ok'
        secretHits = 0
    } | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $desktopReadyLatestPath -Encoding UTF8
    $desktopReadyEnsure = Invoke-Captured -Arguments @('-File', $script, '-Root', $desktopReadyRoot, '-EnsureFresh')
    Assert-True 'desktop-ready ensure-fresh exits ready without rerun loop' ($desktopReadyEnsure.ExitCode -eq 0) "expected exit 0; output=$($desktopReadyEnsure.Output)"
    Assert-Contains 'desktop-ready ensure-fresh reports desktop only ready' $desktopReadyEnsure.Output 'statusDecision=desktop_only_ready'
    $desktopReadyStatus = Get-Content -Raw -LiteralPath (Join-Path $desktopReadySmokeRoot 'goal-next-auto.status.json') | ConvertFrom-Json
    Assert-True 'desktop-ready ensure-fresh preserves latest evidence decision' (
        [string]$desktopReadyStatus.latestDecision -eq 'evidence_needed' -and
        [string]$desktopReadyStatus.statusDecision -eq 'desktop_only_ready' -and
        [string]$desktopReadyStatus.firstAction -eq '' -and
        [string]$desktopReadyStatus.firstActionSource -eq '' -and
        [int]$desktopReadyStatus.nextActionCount -eq 0
    ) "status=$($desktopReadyStatus | ConvertTo-Json -Compress)"

    $desktopReadySafePendingRoot = New-FakeGoalRoot -Mode 'partial'
    $desktopReadySafePendingSmokeRoot = Join-Path $desktopReadySafePendingRoot 'var\codex-smoke'
    $desktopReadySafePendingGeneratedAt = [DateTime]::UtcNow.ToString('o')
    Set-TestFile (Join-Path $desktopReadySafePendingSmokeRoot 'computer-use-smoke.json') @"
{
  "schemaVersion": "awx.local.computer_use_smoke.v1",
  "generatedAt": "$desktopReadySafePendingGeneratedAt",
  "ok": false,
  "decision": "evidence_needed",
  "safePendingProof": true,
  "reachable": true,
  "guiOnly": true,
  "noTerminalAutomation": true,
  "supportingOnly": true,
  "countOnly": true,
  "helperCountOnly": true,
  "appCount": 0,
  "runningCount": 0,
  "windowCount": 0,
  "targetableWindowCount": 0,
  "storesRawAppNames": false,
  "storesAppNames": false,
  "storesWindowTitles": false,
  "evidenceNeeded": "computer_use_zero_count_supporting_evidence_missing",
  "nextAction": "rerun_computer_use_lightweight_smoke",
  "probeSchemaVersion": "awx.local.computer_use_count_probe.v1",
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
    $desktopReadySafePendingSummaryPath = Join-Path $desktopReadySafePendingSmokeRoot 'desktop-ready-safe-pending-summary.json'
    ([ordered]@{
        decision = 'evidence_needed'
        nextActionCount = 0
        nextActionSources = @()
        topActions = @()
        firstAction = ''
        firstActionSource = ''
        externalInputGate = [ordered]@{
            schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
            status = 'local_or_unknown'
            source = ''
            action = ''
            repeated = $false
            repeatCount = 0
            localPatchJustified = $true
            mutationAllowed = $false
            evidenceNeeded = @()
            secretHits = 0
            windowsAbsPathHits = 0
        }
        desktopControlLoop = [ordered]@{
            localReady = $true
            completionReady = $false
            desktopFinalProof = 'evidence_needed'
            nextActionCount = 0
            dispatchWriteRequested = $false
            producerKitWriteRequested = $false
        }
        computerUse = [ordered]@{ ok = $false; decision = 'evidence_needed'; safePendingProof = $true; reachable = $true; stale = $false; appCount = 0; secretHits = 0 }
        browserUse = [ordered]@{ ok = $true; decision = 'ok'; reachable = $true; localhost = $true; targetAccepted = $true; stale = $false; secretHits = 0 }
        supabaseApply = [ordered]@{ decision = 'evidence_needed'; requiredEnvNames = @('SUPABASE_PROJECT_REF') }
        externalApply = [ordered]@{ decision = 'supporting_evidence_missing'; producerBundlesRequired = $false; nextActionCount = 0 }
        secretHits = 0
    } | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath $desktopReadySafePendingSummaryPath -Encoding UTF8
    ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.latest.v1'
        requestMode = [ordered]@{ requireSupabaseProof = $false; externalDispatch = $false; refreshWebProbe = $false; requireUiProof = $false }
        generatedAt = [DateTime]::UtcNow.ToString('o')
        latestStaleAfterMinutes = 60
        decision = 'evidence_needed'
        summaryPath = $desktopReadySafePendingSummaryPath
        nextActionsPath = ''
        nextActionEntryCount = 0
        firstAction = ''
        firstActionSource = ''
        failureClassification = 'evidence_needed'
        externalInputGate = [ordered]@{
            schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
            status = 'local_or_unknown'
            source = ''
            action = ''
            repeated = $false
            repeatCount = 0
            localPatchJustified = $true
            mutationAllowed = $false
            evidenceNeeded = @()
            secretHits = 0
            windowsAbsPathHits = 0
        }
        computerUseOk = $false
        computerUseDecision = 'evidence_needed'
        browserUseOk = $true
        browserUseDecision = 'ok'
        secretHits = 0
    } | ConvertTo-Json -Depth 30) | Set-Content -LiteralPath (Join-Path $desktopReadySafePendingSmokeRoot 'goal-next-auto.latest.json') -Encoding UTF8
    $desktopReadySafePendingEnsure = Invoke-Captured -Arguments @('-File', $script, '-Root', $desktopReadySafePendingRoot, '-EnsureFresh')
    Assert-True 'desktop-ready accepts Computer safe-pending proof when UI proof is not required' ($desktopReadySafePendingEnsure.ExitCode -eq 0) "expected exit 0; output=$($desktopReadySafePendingEnsure.Output)"
    Assert-Contains 'desktop-ready safe-pending reports desktop only ready' $desktopReadySafePendingEnsure.Output 'statusDecision=desktop_only_ready'
    $desktopReadySafePendingStatus = Get-Content -Raw -LiteralPath (Join-Path $desktopReadySafePendingSmokeRoot 'goal-next-auto.status.json') | ConvertFrom-Json
    Assert-True 'desktop-ready safe-pending remains evidence-backed' (
        [string]$desktopReadySafePendingStatus.statusDecision -eq 'desktop_only_ready' -and
        [string]$desktopReadySafePendingStatus.computerUse.decision -eq 'evidence_needed' -and
        $desktopReadySafePendingStatus.computerUse.safePendingProof -eq $true
    ) "status=$($desktopReadySafePendingStatus | ConvertTo-Json -Compress)"

    $partialRoot = New-FakeGoalRoot -Mode 'partial'
    $partialOutput = Join-Path $partialRoot 'out'
    $partial = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $partialOutput, '-Topic', 'mcp-control-loop', '-ExternalDispatch', '-RequireSupabaseProof', '-RefreshWebProbe')
    Assert-True 'partial goal next exits evidence_needed' ($partial.ExitCode -eq 2) "expected exit 2; output=$($partial.Output)"
    Assert-Contains 'partial goal next names evidence needed' $partial.Output 'decision=evidence_needed'
    Assert-Contains 'partial goal next ran smoke' $partial.Output 'supabaseSmokeExit=2'
    Assert-Contains 'partial goal next ran Supabase apply' $partial.Output 'supabaseApplyExit=2'
    Assert-Contains 'partial goal next ran external apply' $partial.Output 'externalApplyExit=2'
    Assert-Contains 'partial goal next ran desktop control loop' $partial.Output 'desktopControlLoopExit=0'
    Assert-Contains 'partial goal next runs completion audit after command packet is current' $partial.Output 'completionAuditExit=0'
    $partialInvocations = @(Get-Content -LiteralPath (Join-Path $partialRoot 'tool-invocations.jsonl'))
    Assert-True 'partial explicit web mode invokes refresh exactly once' (@($partialInvocations | Where-Object { $_ -eq 'web_probe_refresh' }).Count -eq 1) "toolInvocations=$($partialInvocations -join ',')"
    $partialSummaryPath = Join-Path $partialOutput 'goal-next-auto.summary.json'
    Assert-True 'partial goal next writes summary' (Test-Path $partialSummaryPath) 'missing partial summary'
    if (Test-Path $partialSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $partialSummaryPath | ConvertFrom-Json
        Assert-True 'partial summary reports evidence_needed' ($summary.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary reports not ok' ($summary.ok -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records first action' ([string]$summary.firstAction -eq 'set_SUPABASE_PROJECT_REF') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records first action source' ([string]$summary.firstActionSource -eq 'supabase_apply') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records failure classification' ([string]$summary.failureClassification -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records next action count' ([int]$summary.nextActionCount -ge 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records next action sources' (($summary.nextActionSources -join ',').Contains('supabase_apply')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records safe top actions' (@($summary.topActions).Count -gt 0 -and [string]$summary.topActions[0].action -eq 'set_SUPABASE_PROJECT_REF') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records git root as string' ($summary.preflight.gitRoot -is [string]) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop control loop exit' ([int]$summary.desktopControlLoopExit -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop local readiness' ($summary.desktopControlLoop.localReady -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop completion readiness' ($summary.desktopControlLoop.completionReady -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop final proof state' ($summary.desktopControlLoop.desktopFinalProof -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop next-action count' ([int]$summary.desktopControlLoop.nextActionCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop dispatch write request' ($summary.desktopControlLoop.dispatchWriteRequested -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records producer kit write request' ($summary.desktopControlLoop.producerKitWriteRequested -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records desktop dispatch artifacts' ([int]$summary.desktopControlLoop.dispatchArtifactCount -ge 1) "summary=$($summary | ConvertTo-Json -Compress)"
        $partialControlLoop = Get-Content -Raw -LiteralPath $summary.artifacts.desktopControlLoop | ConvertFrom-Json
        Assert-True 'explicit external dispatch keeps producer role pathspecs' (
            [string]$partialControlLoop.decision -eq 'desktop_dispatch_packet' -and
            [int]$partialControlLoop.outputCount -eq 2 -and
            @($partialControlLoop.packets).Count -eq 2 -and
            [int]$partialControlLoop.rolePathspecCounts.macmini -eq 1 -and
            [int]$partialControlLoop.rolePathspecCounts.notebook -eq 1
        ) "desktopControlLoop=$($partialControlLoop | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records final completion audit exit' ([int]$summary.completionAuditExit -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary surfaces source-health no-local source action' (
            [string]$summary.sourceHealth.nextSourceAction -eq 'no_local_source_action_external_evidence_needed' -and
            [string]$summary.sourceHealth.externalEvidenceNextAction -eq 'collect_supabase_data_api_grants_and_rls_result_sets' -and
            [int]$summary.sourceHealth.nextSourceActionDetailCount -eq 1
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary surfaces source-health nearest failure pattern' (
            [double]$summary.sourceHealth.strictEvidenceAdjustedScore -eq 78.5 -and
            [int]$summary.sourceHealth.riskCount -eq 2 -and
            [int]$summary.sourceHealth.activeRiskCount -eq 0 -and
            [int]$summary.sourceHealth.evidenceNeededCount -eq 1 -and
            [string]$summary.sourceHealth.failurePatternKind -eq 'cross_subsystem_concentration' -and
            [string]$summary.sourceHealth.patternId -eq 'FP-S01S08-CROSS-CONCENTRATION' -and
            [double]$summary.sourceHealth.amplifiedSignalScore -eq 0.88
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        $sourceHealthArtifact = Get-Content -Raw -LiteralPath $summary.artifacts.sourceHealth | ConvertFrom-Json
        Assert-True 'partial source-health saw fresh completion audit current' (
            [string]$sourceHealthArtifact.completionAuditFreshness.path -eq 'var/codex-smoke/awx-mcp-completion-audit-current.json' -and
            $sourceHealthArtifact.completionAuditFreshness.fresh -eq $true
        ) "sourceHealth=$($sourceHealthArtifact | ConvertTo-Json -Compress)"
        Assert-True 'partial source-health preserves external input gate boundary' (
            $sourceHealthArtifact.externalInputGateProof.boundaryReady -eq $true -and
            [string]$sourceHealthArtifact.externalInputGateProof.status -eq 'external_input_needed' -and
            [string]$sourceHealthArtifact.externalInputGateProof.action -eq 'set_SUPABASE_PROJECT_REF' -and
            $sourceHealthArtifact.externalInputGateProof.localPatchJustified -eq $false -and
            $sourceHealthArtifact.externalInputGateProof.mutationAllowed -eq $false
        ) "sourceHealth=$($sourceHealthArtifact | ConvertTo-Json -Compress)"
        Assert-True 'partial source-health preserves goal-next collection packet boundary' (
            $sourceHealthArtifact.goalNextCollectionPacketProof.boundaryReady -eq $true -and
            [string]$sourceHealthArtifact.goalNextCollectionPacketProof.requirementStatus -eq 'satisfied' -and
            (($sourceHealthArtifact.goalNextCollectionPacketProof.supabaseRequiredEnvNames -join ',') -eq 'SUPABASE_PROJECT_REF') -and
            [string]$sourceHealthArtifact.goalNextCollectionPacketProof.externalRoles -eq 'macmini,notebook'
        ) "sourceHealth=$($sourceHealthArtifact | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries source-health producer validation queue' (
            [string]$summary.sourceHealth.producerValidationQueue.schema -eq 'producer_validation_queue.v1' -and
            [int]$summary.sourceHealth.producerValidationQueue.maxDurationHours -eq 9 -and
            (($summary.sourceHealth.producerValidationQueue.producerRoles -join ',') -eq 'macmini,notebook') -and
            [int]$summary.sourceHealth.producerValidationQueue.assignmentCount -eq 2 -and
            (($summary.sourceHealth.producerValidationQueue.assignments[0].amplifierTraceKeys -join ',') -match 'hypernova.riskKAlloc') -and
            [double]$summary.sourceHealth.producerValidationQueue.assignments[0].amplifiedSignalScore -gt 0.0
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary surfaces source-health dispatch overlay sidecar' (
            $summary.sourceHealth.dispatchOverlay.ok -eq $true -and
            [string]$summary.sourceHealth.dispatchOverlay.sidecarPath -eq '__patch_drop__/dispatch/mcp-control-loop-source-health-producer-queue.json' -and
            [string]$summary.sourceHealth.dispatchOverlay.sidecarHash -match '^[a-f0-9]{64}$' -and
            $summary.sourceHealth.dispatchOverlay.shaSidecarUpdated -eq $true
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer smoke presence' ($summary.computerUse.present -eq $true -and $summary.computerUse.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer smoke readiness' ($summary.computerUse.ok -eq $true -and $summary.computerUse.reachable -eq $true -and [int]$summary.computerUse.appCount -eq 3) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer helper proof lineage' ($summary.computerUse.helperCountOnly -eq $true -and [string]$summary.computerUse.probeSchemaVersion -eq 'awx.local.computer_use_count_probe.v1') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records computer smoke path safely' (-not ([string]$summary.computerUse.path -match '[A-Za-z]:\\')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records browser smoke presence' ($summary.browserUse.present -eq $true -and $summary.browserUse.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records browser smoke readiness' ($summary.browserUse.ok -eq $true -and $summary.browserUse.reachable -eq $true -and $summary.browserUse.localhost -eq $true -and $summary.browserUse.screenshotCaptured -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records browser smoke status class' ([string]$summary.browserUse.statusClass -eq 'ui_visible' -and $summary.browserUse.targetContentVisible -eq $true -and [string]$summary.browserUse.browserSurface -eq 'iab') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary keeps browser smoke safe' (-not (($summary.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\')) "summary=$($summary | ConvertTo-Json -Compress)"
        $peerBusPath = Join-Path $partialRoot 'var\codex-smoke\peer-evidence-bus.json'
        Assert-True 'partial goal next writes peer evidence bus artifact' (Test-Path $peerBusPath) "missing peer bus artifact at $peerBusPath"
        $peerBusArtifact = Get-Content -Raw -LiteralPath $peerBusPath | ConvertFrom-Json
        Assert-True 'partial peer evidence bus artifact targets harmony safely' (
            [string]$peerBusArtifact.schemaVersion -eq 'awx.mcp.peer_evidence_bus.v1' -and
            [string]$peerBusArtifact.targetMetric -eq 'harmony' -and
            [int]$peerBusArtifact.rawSecretPatternHits -eq 0
        ) "peerBusArtifact=$($peerBusArtifact | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records peer evidence bus readiness' (
            $summary.peerEvidenceBus.present -eq $true -and
            $summary.peerEvidenceBus.ok -eq $true -and
            [string]$summary.peerEvidenceBus.targetMetric -eq 'harmony' -and
            [int]$summary.peerEvidenceBus.outputCount -ge 7 -and
            $summary.peerEvidenceBus.promptPackPresent -eq $true -and
            [int]$summary.peerEvidenceBus.secretHits -eq 0
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        $webProbePath = Join-Path $partialRoot 'var\codex-smoke\web-probe-refresh.json'
        Assert-True 'partial goal next writes web probe refresh artifact' (Test-Path $webProbePath) "missing web probe artifact at $webProbePath"
        if (Test-Path $webProbePath) {
            $webProbeArtifact = Get-Content -Raw -LiteralPath $webProbePath | ConvertFrom-Json
            Assert-True 'partial web probe artifact is read-only and sanitized' (
                [string]$webProbeArtifact.schemaVersion -eq 'awx.web_probe.refresh.v1' -and
                [string]$webProbeArtifact.decision -eq 'web_probe_refresh' -and
                [string]$webProbeArtifact.mode -eq 'read-only-official-sources' -and
                [int]$webProbeArtifact.sourceCount -ge 4 -and
                [int]$webProbeArtifact.fetchedCount -ge 4 -and
                $webProbeArtifact.rawContentStored -eq $false -and
                $webProbeArtifact.rawQueryStored -eq $false -and
                $webProbeArtifact.mutationAllowed -eq $false -and
                [int]$webProbeArtifact.rawSecretPatternHits -eq 0
            ) "webProbeArtifact=$($webProbeArtifact | ConvertTo-Json -Compress)"
        }
        Assert-True 'partial summary records web probe refresh readiness' (
            $summary.webProbeRefresh.present -eq $true -and
            $summary.webProbeRefresh.ok -eq $true -and
            $summary.webProbeRefresh.refreshRequested -eq $true -and
            $summary.webProbeRefresh.processExecuted -eq $true -and
            $summary.webProbeRefresh.contractReady -eq $true -and
            $summary.webProbeRefresh.proofReady -eq $true -and
            [string]$summary.webProbeRefresh.artifactHash -match '^[0-9a-f]{64}$' -and
            [string]$summary.webProbeRefresh.targetMetric -eq 'harmony' -and
            [int]$summary.webProbeRefresh.sourceCount -ge 4 -and
            [int]$summary.webProbeRefresh.fetchedCount -ge 4 -and
            $summary.webProbeRefresh.rawContentStored -eq $false -and
            $summary.webProbeRefresh.rawQueryStored -eq $false -and
            $summary.webProbeRefresh.mutationAllowed -eq $false -and
            [int]$summary.webProbeRefresh.secretHits -eq 0
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records trace-memory runtime proof contract' (
            [string]$summary.traceMemoryRuntimeProof.decision -eq 'ok' -and
            $summary.traceMemoryRuntimeProof.present -eq $true -and
            $summary.traceMemoryRuntimeProof.parsed -eq $true -and
            $summary.traceMemoryRuntimeProof.ok -eq $true -and
            [string]$summary.traceMemoryRuntimeProof.scriptPath -eq 'scripts/smoke_chat_debug_fx_sse.ps1' -and
            $summary.traceMemoryRuntimeProof.requireTraceMemory -eq $true -and
            $summary.traceMemoryRuntimeProof.seedsSelfProbe -eq $true -and
            [string]$summary.traceMemoryRuntimeProof.outputPath -eq 'verification/chat-debug-fx-sse-trace-memory-required/chat-debug-fx-sse.json' -and
            $summary.traceMemoryRuntimeProof.traceMemoryPresent -eq $true -and
            [string]$summary.traceMemoryRuntimeProof.traceMemoryRouteDecision -eq 'retry_failsoft_degrade_warn_live_failsoft' -and
            [string]$summary.traceMemoryRuntimeProof.traceMemoryCfvmOffered -eq 'true' -and
            [string]$summary.traceMemoryRuntimeProof.traceMemoryCfvmPatternId -eq '1985725498' -and
            [string]$summary.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointKey -eq 'traceMemory.virtualCheckpoint.second_refinement' -and
            [string]$summary.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointStage -eq 'second_refinement' -and
            [string]$summary.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointPhase -eq 'post_load' -and
            (($summary.traceMemoryRuntimeProof.expectedDebugFxLabels -join ',') -eq 'traceMemoryRouteDecision,traceMemoryCfvmOffered,traceMemoryCfvmPatternId,traceMemoryVirtualCheckpointKey,traceMemoryVirtualCheckpointStage,traceMemoryVirtualCheckpointPhase') -and
            [int]$summary.traceMemoryRuntimeProof.secretHits -eq 0
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary parses supabase smoke summary' ($summary.supabaseSmoke.parsed -eq $true -and [string]$summary.supabaseSmoke.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records supabase MCP probe skipped state' ($summary.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$summary.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records supabase MCP reachability evidence' ([string]$summary.supabaseSmoke.mcpEndpointReachabilityEvidence -eq 'probe_skipped' -and [string]$summary.supabaseSmoke.projectScopeStatus -eq 'project_ref_missing') "summary=$($summary | ConvertTo-Json -Compress)"
        $defaultSupabaseSmokeSummaryPath = Join-Path $partialRoot 'var\codex-smoke\supabase-readonly-snapshot\supabase-readonly-snapshot.summary.json'
        Assert-True 'partial goal next mirrors fresh supabase smoke for completion audit' (Test-Path $defaultSupabaseSmokeSummaryPath) "missing default smoke summary at $defaultSupabaseSmokeSummaryPath"
        $defaultSupabaseSmokeSummary = Get-Content -Raw -LiteralPath $defaultSupabaseSmokeSummaryPath | ConvertFrom-Json
        Assert-True 'partial goal next default supabase smoke is fresh run output' (
            [string]$defaultSupabaseSmokeSummary.mcpDecision -eq 'mcp_endpoint_probe_skipped' -and
            [string]$defaultSupabaseSmokeSummary.projectScopeStatus -eq 'project_ref_missing'
        ) "defaultSupabaseSmokeSummary=$($defaultSupabaseSmokeSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary parses supabase apply summary' ($summary.supabaseApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase apply decision' ($summary.supabaseApply.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase required MCP tools' (($summary.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase required result names' (($summary.supabaseApply.requiredResultNames -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase env preflight status' ([string]$summary.supabaseApply.envPreflightStatus -eq 'missing_project_ref_and_mcp_auth') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase oauth auth modes' ($summary.supabaseApply.mcpOAuthSupported -eq $true -and (($summary.supabaseApply.supportedAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary keeps supabase token as manual fallback' ($summary.supabaseApply.accessTokenManualFallbackEnvPresent -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase project ref env presence only' ($summary.supabaseApply.projectRefEnvPresent -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase access token env presence only' ($summary.supabaseApply.accessTokenEnvPresent -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries supabase read-only endpoint template' ([string]$summary.supabaseApply.readOnlyMcpEndpointTemplate -match 'project_ref=\$\{SUPABASE_PROJECT_REF\}' -and [string]$summary.supabaseApply.readOnlyMcpEndpointTemplate -match 'read_only=true') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary parses external apply summary' ($summary.externalApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external apply decision' ($summary.externalApply.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external sidecar contract' (($summary.externalApply.requiredPatchDropSidecars -join ',').Contains('.patch')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external source isolation contract' ($summary.externalApply.requiredSourceIsolation.guard -eq 'PASS') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external copied evidence counts' ([int]$summary.externalApply.copiedEvidenceCount -eq 0 -and [int]$summary.externalApply.copiedHandoffCount -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external next action' (($summary.externalApply.nextActions -join ',').Contains('run_macmini_external_node_smoke')) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary carries external apply command' ([string]$summary.externalApply.applyCollectedEvidenceCommand -eq 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes desktop control loop artifact' (Test-Path $summary.artifacts.desktopControlLoop) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-CompactArtifactLog 'partial desktop control-loop compact log' (Join-Path $partialOutput 'desktop-control-loop.log')
        Assert-CompactArtifactLog 'partial completion-audit preflight compact log' (Join-Path $partialOutput 'awx-mcp-completion-audit.preflight.log')
        Assert-CompactArtifactLog 'partial completion-audit packet compact log' (Join-Path $partialOutput 'awx-mcp-completion-audit.packet.log')
        Assert-CompactArtifactLog 'partial completion-audit final compact log' (Join-Path $partialOutput 'awx-mcp-completion-audit.log')
        if (Test-Path $summary.artifacts.desktopControlLoop) {
            $desktopControlLoopArtifact = Get-Content -Raw -LiteralPath $summary.artifacts.desktopControlLoop | ConvertFrom-Json
            Assert-True 'partial desktop control loop received macmini pathspec' ([int]$desktopControlLoopArtifact.rolePathspecCounts.macmini -eq 1) "desktopControlLoop=$($desktopControlLoopArtifact | ConvertTo-Json -Compress)"
            Assert-True 'partial desktop control loop received notebook pathspec' ([int]$desktopControlLoopArtifact.rolePathspecCounts.notebook -eq 1) "desktopControlLoop=$($desktopControlLoopArtifact | ConvertTo-Json -Compress)"
            Assert-True 'partial desktop control loop requested dispatch write' ($desktopControlLoopArtifact.dispatchWriteRequested -eq $true) "desktopControlLoop=$($desktopControlLoopArtifact | ConvertTo-Json -Compress)"
            Assert-True 'partial desktop control loop requested producer kit write' ($desktopControlLoopArtifact.producerKitWriteRequested -eq $true) "desktopControlLoop=$($desktopControlLoopArtifact | ConvertTo-Json -Compress)"
        }
        Assert-True 'partial summary writes next-actions artifact' (Test-Path $summary.artifacts.nextActions) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes command packet artifact' (Test-Path $summary.artifacts.commandPacket) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes command packet markdown artifact' (Test-Path $summary.artifacts.commandPacketMarkdown) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes collection packet artifact' (Test-Path $summary.artifacts.collectionPacket) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary writes collection packet markdown artifact' (Test-Path $summary.artifacts.collectionPacketMarkdown) "summary=$($summary | ConvertTo-Json -Compress)"
        $collectionPacket = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'partial collection packet reports evidence_needed' ([string]$collectionPacket.decision -eq 'evidence_needed') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase lane' (($collectionPacket.supabase.requiredEnvNames -join ',') -eq 'SUPABASE_PROJECT_REF') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase auth fallback lane' (($collectionPacket.supabase.supportedAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN' -and ($collectionPacket.supabase.manualAuthSensitiveEnvRefs -join ',') -eq 'SUPABASE_ACCESS_TOKEN') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP tools' (($collectionPacket.supabase.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet carries the canonical supabase read-only result contract' (
            (@($collectionPacket.supabase.requiredResultNames) -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies,data_api_role_grants,exposed_tables_without_rls,rls_user_metadata_policies,update_policies_without_select_policy,storage_upsert_policy_gaps,views,views_missing_security_invoker,exposed_security_definer_functions,shadow_memory_candidate_tables,shadow_memory_candidate_columns,shadow_memory_metadata_fingerprints,extensions'
        ) "requiredResultNames=$(@($collectionPacket.supabase.requiredResultNames) -join ',')"
        Assert-True 'partial collection packet records supabase official contract signals' (($collectionPacket.supabase.officialContractSignals -join ',') -eq 'mcp_project_scoped_read_only,data_api_grants_required,rls_policy_required,secret_keys_backend_only,advisors_required_before_schema_claim') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase collection guards' (
            $collectionPacket.supabase.collectionGuards.mutationAllowed -eq $false -and
            $collectionPacket.supabase.collectionGuards.storeRawRows -eq $false -and
            $collectionPacket.supabase.collectionGuards.requireProjectScope -eq $true -and
            $collectionPacket.supabase.collectionGuards.requireAdvisors -eq $true
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP config presence' ($collectionPacket.supabase.mcpConfig.present -eq $true -and $collectionPacket.supabase.mcpConfig.parsed -eq $true) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP read-only scope' ($collectionPacket.supabase.mcpConfig.readOnly -eq $true -and $collectionPacket.supabase.mcpConfig.projectRefSource -eq 'SUPABASE_PROJECT_REF') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records supabase MCP host and features' ($collectionPacket.supabase.mcpConfig.serverHost -eq 'mcp.supabase.com' -and (($collectionPacket.supabase.mcpConfig.features -join ',') -eq 'database,debugging,docs')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records no stored supabase token' ($collectionPacket.supabase.mcpConfig.tokenStored -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet preserves computer count-only fields' (
            [int]$collectionPacket.computerUse.appCount -eq 3 -and
            [int]$collectionPacket.computerUse.runningCount -eq 2 -and
            [int]$collectionPacket.computerUse.windowCount -eq 4 -and
            $collectionPacket.computerUse.helperCountOnly -eq $true -and
            [string]$collectionPacket.computerUse.probeSchemaVersion -eq 'awx.local.computer_use_count_probe.v1'
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet keeps empty computer next-actions as array' (
            $collectionPacket.computerUse.nextActions -is [array] -and
            @($collectionPacket.computerUse.nextActions).Count -eq 0
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet preserves browser count-only fields' (
            $collectionPacket.browserUse.reachable -eq $true -and
            $collectionPacket.browserUse.localhost -eq $true -and
            $collectionPacket.browserUse.screenshotCaptured -eq $true
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet preserves browser status labels' ([string]$collectionPacket.browserUse.statusClass -eq 'ui_visible' -and $collectionPacket.browserUse.targetContentVisible -eq $true -and [string]$collectionPacket.browserUse.browserSurface -eq 'iab') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet keeps browser smoke path relative' ([string]$collectionPacket.browserUse.outputPath -eq 'var/codex-smoke/browser-ui-smoke.json') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet avoids raw browser URL and screenshot path' (-not (($collectionPacket.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet carries local smoke refresh contract' (
            [string]$collectionPacket.localInteractionSmokeRefresh.scriptPath -eq 'scripts/refresh_local_interaction_smokes.ps1' -and
            [string]$collectionPacket.localInteractionSmokeRefresh.command -eq 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\refresh_local_interaction_smokes.ps1 -Root . -ComputerProbePath <computer-counts-json> -BrowserProbePath <browser-proof-json>' -and
            ($collectionPacket.localInteractionSmokeRefresh.outputPaths -join ',') -eq 'var/codex-smoke/computer-use-smoke.json,var/codex-smoke/browser-ui-smoke.json,var/codex-smoke/local-interaction-smoke-refresh.summary.json' -and
            $collectionPacket.localInteractionSmokeRefresh.storesRawProbePayloads -eq $false -and
            $collectionPacket.localInteractionSmokeRefresh.mutationAllowed -eq $false
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet carries SMB debug probe contract' (
            $null -ne $collectionPacket.smbDecommissionDebugProbe -and
            [string]$collectionPacket.smbDecommissionDebugProbe.tool -eq 'smb_decommission_debug_probe' -and
            [string]$collectionPacket.smbDecommissionDebugProbe.summaryArtifact -eq 'var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.summary.json' -and
            [string]$collectionPacket.smbDecommissionDebugProbe.eventsArtifact -eq 'var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.events.ndjson' -and
            [string]$collectionPacket.smbDecommissionDebugProbe.attachmentPathPlaceholder -eq '<attachment-path>' -and
            [string]$collectionPacket.smbDecommissionDebugProbe.commandWithAttachment -match 'attachmentPath' -and
            [string]$collectionPacket.smbDecommissionDebugProbe.commandWithAttachment -match '<attachment-path>' -and
            $collectionPacket.smbDecommissionDebugProbe.mutationAllowed -eq $false -and
            $collectionPacket.smbDecommissionDebugProbe.writeDispatch -eq $false -and
            $collectionPacket.smbDecommissionDebugProbe.writeProducerKit -eq $false -and
            $collectionPacket.smbDecommissionDebugProbe.requireProducerBundles -eq $false -and
            $collectionPacket.smbDecommissionDebugProbe.supportingEvidenceOnly -eq $true
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet SMB debug probe is secret safe' (-not (($collectionPacket.smbDecommissionDebugProbe | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "smbDecommissionDebugProbe=$($collectionPacket.smbDecommissionDebugProbe | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet local smoke refresh is secret safe' (-not (($collectionPacket.localInteractionSmokeRefresh | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet carries trace-memory runtime proof contract' (
            [string]$collectionPacket.traceMemoryRuntimeProof.decision -eq 'ok' -and
            $collectionPacket.traceMemoryRuntimeProof.present -eq $true -and
            $collectionPacket.traceMemoryRuntimeProof.parsed -eq $true -and
            [string]$collectionPacket.traceMemoryRuntimeProof.scriptPath -eq 'scripts/smoke_chat_debug_fx_sse.ps1' -and
            [string]$collectionPacket.traceMemoryRuntimeProof.command -match 'smoke_chat_debug_fx_sse\.ps1' -and
            [string]$collectionPacket.traceMemoryRuntimeProof.command -match 'RequireTraceMemory' -and
            $collectionPacket.traceMemoryRuntimeProof.requireTraceMemory -eq $true -and
            $collectionPacket.traceMemoryRuntimeProof.seedsSelfProbe -eq $true -and
            [string]$collectionPacket.traceMemoryRuntimeProof.traceMemoryRouteDecision -eq 'retry_failsoft_degrade_warn_live_failsoft' -and
            [string]$collectionPacket.traceMemoryRuntimeProof.traceMemoryCfvmPatternId -eq '1985725498' -and
            [string]$collectionPacket.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointStage -eq 'second_refinement' -and
            [string]$collectionPacket.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointPhase -eq 'post_load' -and
            $collectionPacket.traceMemoryRuntimeProof.mutationAllowed -eq $false -and
            $collectionPacket.traceMemoryRuntimeProof.storesRawPrompt -eq $false -and
            $collectionPacket.traceMemoryRuntimeProof.storesRawModel -eq $false -and
            [int]$collectionPacket.traceMemoryRuntimeProof.secretHits -eq 0
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet trace-memory contract is secret safe' (-not (($collectionPacket.traceMemoryRuntimeProof | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "traceMemoryRuntimeProof=$($collectionPacket.traceMemoryRuntimeProof | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet carries web probe refresh contract' (
            $collectionPacket.webProbeRefresh.present -eq $true -and
            $collectionPacket.webProbeRefresh.ok -eq $true -and
            $collectionPacket.webProbeRefresh.refreshRequested -eq $true -and
            $collectionPacket.webProbeRefresh.processExecuted -eq $true -and
            $collectionPacket.webProbeRefresh.contractReady -eq $true -and
            $collectionPacket.webProbeRefresh.proofReady -eq $true -and
            [string]$collectionPacket.webProbeRefresh.artifactHash -match '^[0-9a-f]{64}$' -and
            [string]$collectionPacket.webProbeRefresh.tool -eq 'web_probe_refresh' -and
            [string]$collectionPacket.webProbeRefresh.outputPath -eq 'var/codex-smoke/web-probe-refresh.json' -and
            [int]$collectionPacket.webProbeRefresh.sourceCount -ge 4 -and
            $collectionPacket.webProbeRefresh.rawContentStored -eq $false -and
            $collectionPacket.webProbeRefresh.rawQueryStored -eq $false -and
            $collectionPacket.webProbeRefresh.mutationAllowed -eq $false
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet web probe contract is secret safe' (-not (($collectionPacket.webProbeRefresh | ConvertTo-Json -Depth 20) -match '[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|http://')) "webProbeRefresh=$($collectionPacket.webProbeRefresh | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records external roles' (($collectionPacket.external.requiredRoles -join ',') -eq 'macmini,notebook') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records external sidecars' (($collectionPacket.external.requiredSidecars -join ',').Contains('.manifest.json') -and ($collectionPacket.external.requiredSidecars -join ',').Contains('.sha256.txt')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records source isolation' ($collectionPacket.external.requiredSourceIsolation.guard -eq 'PASS' -and $collectionPacket.external.requiredSourceIsolation.directCanonicalSourceEdit -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet carries source-health producer queue' (
            [string]$collectionPacket.sourceHealthProducerQueue.schema -eq 'producer_validation_queue.v1' -and
            [int]$collectionPacket.sourceHealthProducerQueue.maxDurationHours -eq 9 -and
            (($collectionPacket.sourceHealthProducerQueue.producerRoles -join ',') -eq 'macmini,notebook') -and
            (($collectionPacket.sourceHealthProducerQueue.externalEvidenceOnlyComponentIds -join ',') -eq 'supabase_external_evidence') -and
            [int]$collectionPacket.sourceHealthProducerQueue.assignmentCount -eq 2
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet strips external-only component refs from producer queue' (
            (($collectionPacket.sourceHealthProducerQueue.assignments[0].componentScoreRefs -join ',') -eq 'harmony_pressure') -and
            (($collectionPacket.sourceHealthProducerQueue.assignments[1].componentScoreRefs -join ',') -eq 'harmony_pressure')
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet surfaces desktop dispatch files' (
            $collectionPacket.desktopDispatch.writeRequested -eq $true -and
            $collectionPacket.desktopDispatch.producerKitWriteRequested -eq $true -and
            [int]$collectionPacket.desktopDispatch.dispatchArtifactCount -ge 1 -and
            $collectionPacket.desktopDispatch.dispatchIntegrityOk -eq $true -and
            $collectionPacket.desktopDispatch.producerKitOk -eq $true -and
            (@($collectionPacket.desktopDispatch.producerCommandFiles).Count -eq 2) -and
            ((@($collectionPacket.desktopDispatch.producerCommandFiles).desktopCommandFile -join ',') -match '__patch_drop__/dispatch/mcp-control-loop-macmini.commands.txt') -and
            ((@($collectionPacket.desktopDispatch.producerCommandFiles).desktopCommandFile -join ',') -match '__patch_drop__/dispatch/mcp-control-loop-notebook.commands.txt')
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet desktop dispatch is path safe' (-not (($collectionPacket.desktopDispatch | ConvertTo-Json -Depth 20) -match '[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}')) "desktopDispatch=$($collectionPacket.desktopDispatch | ConvertTo-Json -Compress)"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        Assert-True 'partial command packet carries source-health producer queue' (
            [string]$commandPacket.sourceHealthProducerQueue.schema -eq 'producer_validation_queue.v1' -and
            [int]$commandPacket.sourceHealthProducerQueue.maxDurationHours -eq 9 -and
            (($commandPacket.sourceHealthProducerQueue.producerRoles -join ',') -eq 'macmini,notebook') -and
            (($commandPacket.sourceHealthProducerQueue.assignments[0].requiredTraceStoreKeys -join ',') -match 'sourceHealth.failurePatternKind') -and
            (($commandPacket.sourceHealthProducerQueue.assignments[0].amplifierTraceKeys -join ',') -match 'hypernova.cvarPhi') -and
            (($commandPacket.sourceHealthProducerQueue.assignments[0].amplifierTraceKeys -join ',') -match 'hypernova.riskKAlloc') -and
            (($commandPacket.sourceHealthProducerQueue.assignments[0].amplifierTraceKeys -join ',') -match 'sourceHealth.amplifiedSignalScore') -and
            [double]$commandPacket.sourceHealthProducerQueue.assignments[0].amplifiedSignalScore -gt 0.0 -and
            (($commandPacket.sourceHealthProducerQueue.assignments[0].requiredEvidenceArtifacts -join ',') -match 'DebugEvent NDJSON') -and
            [string]$commandPacket.sourceHealthProducerQueue.assignments[0].patchDropManifestPath -eq 'verification/source-health-patchdrop-manifest-contract.json'
        ) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $commandAssignmentSinks = @($commandPacket.sourceHealthProducerQueue.assignments[0].requiredEvidenceSinks)
        Assert-True 'partial command packet enforces source-health evidence sinks' (
            ($commandAssignmentSinks -contains 'TraceStore') -and
            ($commandAssignmentSinks -contains 'DebugEventStore') -and
            ($commandAssignmentSinks -contains 'CFVM Failure Pattern')
        ) "requiredEvidenceSinks=$($commandAssignmentSinks -join ',') commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $dispatchDir = Join-Path $partialRoot '__patch_drop__\dispatch'
        $sourceHealthQueuePath = Join-Path $dispatchDir 'mcp-control-loop-source-health-producer-queue.json'
        $macminiCommandPath = Join-Path $dispatchDir 'mcp-control-loop-macmini.commands.txt'
        $notebookCommandPath = Join-Path $dispatchDir 'mcp-control-loop-notebook.commands.txt'
        $dispatchShaPath = Join-Path $dispatchDir 'mcp-control-loop-dispatch.sha256.txt'
        Assert-True 'partial dispatch writes source-health producer queue sidecar' (Test-Path $sourceHealthQueuePath) "missing queue sidecar at $sourceHealthQueuePath"
        $sourceHealthQueueSidecar = Get-Content -Raw -LiteralPath $sourceHealthQueuePath | ConvertFrom-Json
        Assert-True 'partial dispatch queue sidecar carries producer validation assignments' (
            [string]$sourceHealthQueueSidecar.sourceHealthProducerQueue.schema -eq 'producer_validation_queue.v1' -and
            (($sourceHealthQueueSidecar.sourceHealthProducerQueue.producerRoles -join ',') -eq 'macmini,notebook') -and
            [int]$sourceHealthQueueSidecar.sourceHealthProducerQueue.assignmentCount -eq 2 -and
            (($sourceHealthQueueSidecar.sourceHealthProducerQueue.assignments[0].amplifierTraceKeys -join ',') -match 'hypernova.cvarPhi') -and
            (($sourceHealthQueueSidecar.sourceHealthProducerQueue.assignments[0].amplifierTraceKeys -join ',') -match 'hypernova.riskKAlloc') -and
            (($sourceHealthQueueSidecar.sourceHealthProducerQueue.assignments[0].amplifierTraceKeys -join ',') -match 'sourceHealth.amplifiedSignalScore') -and
            (($sourceHealthQueueSidecar.sourceHealthProducerQueue.assignments[0].requiredEvidenceSinks -join ',') -eq 'TraceStore,DebugEventStore,CFVM Failure Pattern') -and
            [double]$sourceHealthQueueSidecar.sourceHealthProducerQueue.assignments[0].amplifiedSignalScore -gt 0.0
        ) "queueSidecar=$($sourceHealthQueueSidecar | ConvertTo-Json -Compress)"
        $macminiCommandText = Get-Content -Raw -LiteralPath $macminiCommandPath
        $notebookCommandText = Get-Content -Raw -LiteralPath $notebookCommandPath
        Assert-Contains 'partial macmini command file enforces producer queue schema' $macminiCommandText 'producer_validation_queue.v1'
        Assert-Contains 'partial macmini command file enforces failure pattern id' $macminiCommandText 'FP-S01S08-CROSS-CONCENTRATION'
        Assert-Contains 'partial macmini command file pins failure pattern kind in validator' $macminiCommandText 'expectedKind'
        Assert-Contains 'partial macmini command file pins exact failure pattern kind' $macminiCommandText 'cross_subsystem_concentration'
        Assert-Contains 'partial macmini command file pins exact failure pattern id' $macminiCommandText 'expectedPatternId'
        Assert-Contains 'partial macmini command file checks source-health queue hash' $macminiCommandText 'sourceHealthProducerQueue.sha256='
        Assert-Contains 'partial macmini command file surfaces amplified signal score' $macminiCommandText 'sourceHealth.amplifiedSignalScore='
        Assert-Contains 'partial macmini command file enforces amplifier trace keys' $macminiCommandText 'requiredAmplifierTraceKeys='
        Assert-Contains 'partial macmini command file enforces HYPERNOVA CVaR trace key' $macminiCommandText 'hypernova.cvarPhi'
        Assert-Contains 'partial macmini command file enforces HYPERNOVA Risk-K trace key' $macminiCommandText 'hypernova.riskKAlloc'
        Assert-Contains 'partial macmini command file validates amplifier trace key field' $macminiCommandText 'amplifierTraceKeys'
        Assert-Contains 'partial macmini command file validates required evidence sinks field' $macminiCommandText 'requiredEvidenceSinks'
        Assert-Contains 'partial macmini command file validates source root kind field' $macminiCommandText 'sourceRootKind'
        Assert-Contains 'partial macmini command file requires local worktree source root kind' $macminiCommandText 'local-worktree'
        Assert-Contains 'partial macmini command file enforces TraceStore evidence sink' $macminiCommandText 'TraceStore'
        Assert-Contains 'partial macmini command file enforces DebugEventStore evidence sink' $macminiCommandText 'DebugEventStore'
        Assert-Contains 'partial macmini command file enforces CFVM evidence sink' $macminiCommandText 'CFVM Failure Pattern'
        Assert-True 'partial macmini command file replaces desktop canonical placeholder' (-not $macminiCommandText.Contains('<desktop-canonical-root>')) "macminiCommand=$macminiCommandText"
        Assert-Contains 'partial macmini command file uses producer-visible canonical root' $macminiCommandText '/Volumes/WinSrc/demo-1/demo-1/src'
        Assert-Contains 'partial macmini command file enforces trace keys' $macminiCommandText 'sourceHealth.failurePatternKind'
        Assert-Contains 'partial macmini command file enforces DebugEvent NDJSON' $macminiCommandText 'DebugEvent NDJSON'
        Assert-Contains 'partial macmini command file enforces PatchDrop manifest' $macminiCommandText 'PatchDrop manifest'
        Assert-Contains 'partial notebook command file enforces producer queue schema' $notebookCommandText 'producer_validation_queue.v1'
        Assert-Contains 'partial notebook command file enforces failure pattern id' $notebookCommandText 'FP-TRACE-SILENT-SWALLOW'
        Assert-Contains 'partial notebook command file pins failure pattern kind in validator' $notebookCommandText 'expectedKind'
        Assert-Contains 'partial notebook command file pins exact failure pattern kind' $notebookCommandText 'silent_swallow_pressure'
        Assert-Contains 'partial notebook command file pins exact failure pattern id' $notebookCommandText 'expectedPatternId'
        Assert-Contains 'partial notebook command file checks source-health queue hash' $notebookCommandText 'sourceHealthProducerQueue.sha256='
        Assert-Contains 'partial notebook command file surfaces amplified signal score' $notebookCommandText 'sourceHealth.amplifiedSignalScore='
        Assert-Contains 'partial notebook command file enforces amplifier trace keys' $notebookCommandText 'requiredAmplifierTraceKeys='
        Assert-Contains 'partial notebook command file enforces HYPERNOVA CVaR trace key' $notebookCommandText 'hypernova.cvarPhi'
        Assert-Contains 'partial notebook command file enforces HYPERNOVA Risk-K trace key' $notebookCommandText 'hypernova.riskKAlloc'
        Assert-Contains 'partial notebook command file validates amplifier trace key field' $notebookCommandText 'amplifierTraceKeys'
        Assert-Contains 'partial notebook command file validates required evidence sinks field' $notebookCommandText 'requiredEvidenceSinks'
        Assert-Contains 'partial notebook command file validates source root kind field' $notebookCommandText 'sourceRootKind'
        Assert-Contains 'partial notebook command file requires local worktree source root kind' $notebookCommandText 'local-worktree'
        Assert-Contains 'partial notebook command file enforces TraceStore evidence sink' $notebookCommandText 'TraceStore'
        Assert-Contains 'partial notebook command file enforces DebugEventStore evidence sink' $notebookCommandText 'DebugEventStore'
        Assert-Contains 'partial notebook command file enforces CFVM evidence sink' $notebookCommandText 'CFVM Failure Pattern'
        Assert-True 'partial notebook command file replaces desktop canonical placeholder' (-not $notebookCommandText.Contains('<desktop-canonical-root>')) "notebookCommand=$notebookCommandText"
        Assert-Contains 'partial notebook command file uses producer-visible canonical root' $notebookCommandText 'Z:\'
        Assert-Contains 'partial notebook command file enforces trace keys' $notebookCommandText 'sourceHealth.failurePatternKind'
        Assert-Contains 'partial notebook command file enforces DebugEvent NDJSON' $notebookCommandText 'DebugEvent NDJSON'
        Assert-Contains 'partial notebook command file enforces PatchDrop manifest' $notebookCommandText 'PatchDrop manifest'
        $dispatchShaText = Get-Content -Raw -LiteralPath $dispatchShaPath
        $dispatchShaBytes = [System.IO.File]::ReadAllBytes($dispatchShaPath)
        $macminiCommandHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $macminiCommandPath).Hash.ToLowerInvariant()
        $notebookCommandHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $notebookCommandPath).Hash.ToLowerInvariant()
        Assert-Contains 'partial dispatch sidecar covers source-health queue sidecar' $dispatchShaText 'mcp-control-loop-source-health-producer-queue.json'
        Assert-True 'partial dispatch sidecar is utf8 without bom' (-not ($dispatchShaBytes.Length -ge 3 -and $dispatchShaBytes[0] -eq 0xEF -and $dispatchShaBytes[1] -eq 0xBB -and $dispatchShaBytes[2] -eq 0xBF)) "dispatch sidecar starts with UTF-8 BOM"
        Assert-Contains 'partial dispatch sidecar updates macmini command hash' $dispatchShaText $macminiCommandHash
        Assert-Contains 'partial dispatch sidecar updates notebook command hash' $dispatchShaText $notebookCommandHash
        $desktopDispatchJsonPath = Join-Path $dispatchDir 'mcp-control-loop-desktop-dispatch.json'
        $desktopDispatchJson = Get-Content -Raw -LiteralPath $desktopDispatchJsonPath | ConvertFrom-Json
        Assert-True 'partial desktop dispatch json indexes source-health queue sidecar' (
            (Split-Path -Leaf ([string]$desktopDispatchJson.dispatchArtifactIndex.sourceHealthProducerQueue)) -eq 'mcp-control-loop-source-health-producer-queue.json' -and
            ((@($desktopDispatchJson.dispatchArtifactIndex.sha256CoveredArtifacts) | ForEach-Object { Split-Path -Leaf ([string]$_) }) -contains 'mcp-control-loop-source-health-producer-queue.json')
        ) "desktopDispatch=$($desktopDispatchJson | ConvertTo-Json -Compress)"
        $macminiDispatchEntry = @($desktopDispatchJson.dispatchArtifactIndex.producerCommands | Where-Object { $_.nodeRole -eq 'macmini' } | Select-Object -First 1)
        $notebookDispatchEntry = @($desktopDispatchJson.dispatchArtifactIndex.producerCommands | Where-Object { $_.nodeRole -eq 'notebook' } | Select-Object -First 1)
        Assert-True 'partial desktop dispatch json updates macmini command hash' ([string]$macminiDispatchEntry[0].fileHash -eq $macminiCommandHash) "desktopDispatch=$($desktopDispatchJson | ConvertTo-Json -Compress)"
        Assert-True 'partial desktop dispatch json updates notebook command hash' ([string]$notebookDispatchEntry[0].fileHash -eq $notebookCommandHash) "desktopDispatch=$($desktopDispatchJson | ConvertTo-Json -Compress)"
        Assert-True 'partial desktop dispatch json command text has producer-visible roots' (
            -not (($desktopDispatchJson | ConvertTo-Json -Depth 50) -match '<desktop-canonical-root>')
        ) "desktopDispatch=$($desktopDispatchJson | ConvertTo-Json -Compress)"
        $desktopDispatchHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $desktopDispatchJsonPath).Hash.ToLowerInvariant()
        Assert-Contains 'partial dispatch sidecar updates desktop dispatch json hash' $dispatchShaText $desktopDispatchHash
        $handoffPath = Join-Path $dispatchDir 'mcp-control-loop-handoff.md'
        $handoffText = Get-Content -Raw -LiteralPath $handoffPath
        Assert-Contains 'partial handoff markdown reports final macmini command hash' $handoffText $macminiCommandHash
        Assert-Contains 'partial handoff markdown reports final notebook command hash' $handoffText $notebookCommandHash
        $dispatchShaBytes = [System.IO.File]::ReadAllBytes($dispatchShaPath)
        Assert-True 'partial dispatch sidecar is written without UTF-8 BOM' (
            -not ($dispatchShaBytes.Length -ge 3 -and $dispatchShaBytes[0] -eq 0xEF -and $dispatchShaBytes[1] -eq 0xBB -and $dispatchShaBytes[2] -eq 0xBF)
        ) "firstBytes=$([BitConverter]::ToString($dispatchShaBytes[0..([Math]::Min(2, $dispatchShaBytes.Length - 1))]))"
        $dispatchShaArtifactNames = @(
            Get-Content -LiteralPath $dispatchShaPath |
                ForEach-Object {
                    if ($_ -match '^\s*[A-Fa-f0-9]{64}\s+(.+?)\s*$') { Split-Path -Leaf $Matches[1] }
                }
        )
        $expectedDispatchShaArtifactNames = @(
            'mcp-control-loop-desktop-dispatch.json',
            'mcp-control-loop-source-health-producer-queue.json',
            'mcp-control-loop-macmini.commands.txt',
            'mcp-control-loop-notebook.commands.txt',
            'mcp-control-loop-desktop-intake.ps1',
            'mcp-control-loop-handoff.md'
        )
        Assert-True 'partial dispatch sidecar preserves strict dispatch artifact coverage only' (
            (($dispatchShaArtifactNames | Sort-Object) -join ',') -eq (($expectedDispatchShaArtifactNames | Sort-Object) -join ',')
        ) "dispatchShaArtifacts=$($dispatchShaArtifactNames -join ',')"
        Assert-True 'partial dispatch artifact index preserves source-health queue coverage order' (
            ((@($desktopDispatchJson.dispatchArtifactIndex.sha256CoveredArtifacts) | ForEach-Object { Split-Path -Leaf ([string]$_) }) -join ',') -eq ($expectedDispatchShaArtifactNames -join ',')
        ) "coveredArtifacts=$((@($desktopDispatchJson.dispatchArtifactIndex.sha256CoveredArtifacts) | ForEach-Object { Split-Path -Leaf ([string]$_) }) -join ',')"
        Assert-True 'partial collection packet records archive lane' (
            $collectionPacket.archive.readOnly -eq $true -and
            $collectionPacket.archive.mutationAllowed -eq $false -and
            (($collectionPacket.archive.requiredEnvNames -join ',') -eq 'ARCHIVE_INDEX,NAS_ARCHIVE_ROOT') -and
            [string]$collectionPacket.archive.indexPathRecommendation -eq 'BackupsXS/index.jsonl'
        ) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records archive MCP tools' (($collectionPacket.archive.requiredMcpTools -join ',') -eq 'archive.search,archive.index_build') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet records archive next action' (($collectionPacket.archive.nextActions -join ',').Contains('verify_archive_index_path')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet avoids Windows absolute paths' (-not (($collectionPacket | ConvertTo-Json -Depth 50) -match '[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial collection packet is secret safe' (-not (($collectionPacket | ConvertTo-Json -Depth 50) -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        $collectionPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacketMarkdown
        Assert-Contains 'partial collection packet markdown records supabase env' $collectionPacketMarkdown 'SUPABASE_PROJECT_REF'
        Assert-Contains 'partial collection packet markdown records supabase result path' $collectionPacketMarkdown 'supabase.resultPathRecommendation=data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial collection packet markdown records supabase advisor path' $collectionPacketMarkdown 'supabase.advisorResultPathRecommendation=data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial collection packet markdown records supabase MCP read-only config' $collectionPacketMarkdown 'supabase.mcpConfig.readOnly=True'
        Assert-Contains 'partial collection packet markdown records supabase MCP project ref source' $collectionPacketMarkdown 'supabase.mcpConfig.projectRefSource=SUPABASE_PROJECT_REF'
        Assert-Contains 'partial collection packet markdown records supabase MCP host' $collectionPacketMarkdown 'supabase.mcpConfig.serverHost=mcp.supabase.com'
        Assert-Contains 'partial collection packet markdown records supabase MCP token guard' $collectionPacketMarkdown 'supabase.mcpConfig.tokenStored=False'
        Assert-Contains 'partial collection packet markdown records external sidecar' $collectionPacketMarkdown '.manifest.json'
        Assert-Contains 'partial collection packet markdown records desktop dispatch file' $collectionPacketMarkdown '__patch_drop__/dispatch/mcp-control-loop-macmini.commands.txt'
        Assert-Contains 'partial collection packet markdown records producer kit state' $collectionPacketMarkdown 'desktopDispatch.producerKitOk=True'
        Assert-Contains 'partial collection packet markdown records archive index path' $collectionPacketMarkdown 'archive.indexPathRecommendation=BackupsXS/index.jsonl'
        Assert-Contains 'partial collection packet markdown records archive env names' $collectionPacketMarkdown 'archive.requiredEnvNames=ARCHIVE_INDEX,NAS_ARCHIVE_ROOT'
        Assert-Contains 'partial collection packet markdown records computer window-title guard' $collectionPacketMarkdown 'computerUse.storesWindowTitles=False'
        Assert-Contains 'partial collection packet markdown records computer app count' $collectionPacketMarkdown 'computerUse.appCount=3'
        Assert-Contains 'partial collection packet markdown records computer running count' $collectionPacketMarkdown 'computerUse.runningCount=2'
        Assert-Contains 'partial collection packet markdown records computer window count' $collectionPacketMarkdown 'computerUse.windowCount=4'
        Assert-Contains 'partial collection packet markdown records computer helper count-only proof' $collectionPacketMarkdown 'computerUse.helperCountOnly=True'
        Assert-Contains 'partial collection packet markdown records computer probe schema' $collectionPacketMarkdown 'computerUse.probeSchemaVersion=awx.local.computer_use_count_probe.v1'
        Assert-Contains 'partial collection packet markdown records computer secret count' $collectionPacketMarkdown 'computerUse.secretHits=0'
        Assert-Contains 'partial collection packet markdown records browser output path' $collectionPacketMarkdown 'browserUse.outputPath=var/codex-smoke/browser-ui-smoke.json'
        Assert-Contains 'partial collection packet markdown records browser localhost' $collectionPacketMarkdown 'browserUse.localhost=True'
        Assert-Contains 'partial collection packet markdown records browser screenshot state' $collectionPacketMarkdown 'browserUse.screenshotCaptured=True'
        Assert-Contains 'partial collection packet markdown records web probe output path' $collectionPacketMarkdown 'webProbeRefresh.outputPath=var/codex-smoke/web-probe-refresh.json'
        Assert-Contains 'partial collection packet markdown records web probe source count' $collectionPacketMarkdown 'webProbeRefresh.sourceCount=5'
        Assert-True 'partial collection packet markdown is secret safe' (-not ($collectionPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "collectionPacketMarkdown=$collectionPacketMarkdown"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        Assert-True 'partial command packet reports evidence_needed' ([string]$commandPacket.decision -eq 'evidence_needed') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet records commands' ([int]$commandPacket.commandCount -ge 3) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes supabase lane' (($commandPacket.lanes -join ',') -match 'supabase') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes external desktop lane' (($commandPacket.lanes -join ',') -match 'external_desktop') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes external producer lane' (($commandPacket.lanes -join ',') -match 'external_producer') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes dispatch command lane' (($commandPacket.lanes -join ',') -match 'external_dispatch') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes archive lane' (($commandPacket.lanes -join ',') -match 'archive') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes peer evidence bus lane' (($commandPacket.lanes -join ',') -match 'peer_evidence_bus') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes web probe refresh lane' (($commandPacket.lanes -join ',') -match 'web_probe_refresh') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet includes SMB debug probe lane' (($commandPacket.lanes -join ',') -match 'smb_decommission_debug_probe') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet records next action count' ([int]$commandPacket.nextActionCount -ge 5) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet records next action sources' (($commandPacket.nextActionSources -join ',') -match 'supabase_apply' -and ($commandPacket.nextActionSources -join ',') -match 'source_health_scorecard') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet records safe top actions' (@($commandPacket.topActions).Count -gt 0 -and [string]$commandPacket.topActions[0].action -eq 'set_SUPABASE_PROJECT_REF' -and [string]$commandPacket.topActions[0].source -eq 'supabase_apply') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet surfaces external input gate' (
            [string]$commandPacket.externalInputGate.status -eq 'external_input_needed' -and
            [string]$commandPacket.externalInputGate.source -eq 'supabase_apply' -and
            [string]$commandPacket.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF' -and
            $commandPacket.externalInputGate.localPatchJustified -eq $false -and
            $commandPacket.externalInputGate.mutationAllowed -eq $false -and
            (@($commandPacket.externalInputGate.evidenceNeeded) -join ',').Contains('SUPABASE_PROJECT_REF') -and
            [int]$commandPacket.externalInputGate.secretHits -eq 0 -and
            [int]$commandPacket.externalInputGate.windowsAbsPathHits -eq 0
        ) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet preserves producer placeholders' (($commandPacket.commands.command -join "`n") -match '<producer-local-worktree>' -and ($commandPacket.commands.command -join "`n") -match '<desktop-canonical-root>') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $supabaseCommand = @($commandPacket.commands | Where-Object { [string]$_.lane -eq 'supabase' } | Select-Object -First 1)
        Assert-True 'partial command packet keeps required env names narrow' ((@($supabaseCommand.requiredEnvNames) -join ',') -eq 'SUPABASE_PROJECT_REF') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $supabaseCommandAuthModes = @((@($supabaseCommand.supportedAuthModes) -join ' ') -split '[,\s]+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $supabaseCommandManualRefs = @((@($supabaseCommand.manualAuthSensitiveEnvRefs) -join ' ') -split '[,\s]+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        Assert-True 'partial command packet keeps supabase token as auth fallback' (($supabaseCommandAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN' -and ($supabaseCommandManualRefs -join ',') -eq 'SUPABASE_ACCESS_TOKEN') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries full computer smoke summary' ($commandPacket.computerUse.ok -eq $true -and $commandPacket.computerUse.reachable -eq $true -and [int]$commandPacket.computerUse.appCount -eq 3) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet preserves computer count-only fields' ([int]$commandPacket.computerUse.runningCount -eq 2 -and [int]$commandPacket.computerUse.windowCount -eq 4 -and $commandPacket.computerUse.helperCountOnly -eq $true -and [string]$commandPacket.computerUse.probeSchemaVersion -eq 'awx.local.computer_use_count_probe.v1') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries browser smoke summary' ($commandPacket.browserUse.ok -eq $true -and $commandPacket.browserUse.reachable -eq $true -and $commandPacket.browserUse.localhost -eq $true -and $commandPacket.browserUse.screenshotCaptured -eq $true) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries browser status labels' ([string]$commandPacket.browserUse.statusClass -eq 'ui_visible' -and $commandPacket.browserUse.targetContentVisible -eq $true -and [string]$commandPacket.browserUse.browserSurface -eq 'iab') "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet keeps browser raw fields disabled' ($commandPacket.browserUse.storesRawUrl -eq $false -and $commandPacket.browserUse.storesScreenshotPath -eq $false) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries local smoke refresh contract' (
            [string]$commandPacket.localInteractionSmokeRefresh.scriptPath -eq 'scripts/refresh_local_interaction_smokes.ps1' -and
            [string]$commandPacket.localInteractionSmokeRefresh.command -eq 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\refresh_local_interaction_smokes.ps1 -Root . -ComputerProbePath <computer-counts-json> -BrowserProbePath <browser-proof-json>' -and
            ($commandPacket.localInteractionSmokeRefresh.outputPaths -join ',') -eq 'var/codex-smoke/computer-use-smoke.json,var/codex-smoke/browser-ui-smoke.json,var/codex-smoke/local-interaction-smoke-refresh.summary.json' -and
            $commandPacket.localInteractionSmokeRefresh.storesRawProbePayloads -eq $false -and
            $commandPacket.localInteractionSmokeRefresh.mutationAllowed -eq $false
        ) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet local smoke refresh is secret safe' (-not (($commandPacket.localInteractionSmokeRefresh | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries trace-memory runtime proof summary' (
            [string]$commandPacket.traceMemoryRuntimeProof.scriptPath -eq 'scripts/smoke_chat_debug_fx_sse.ps1' -and
            [string]$commandPacket.traceMemoryRuntimeProof.command -match 'RequireTraceMemory' -and
            [string]$commandPacket.traceMemoryRuntimeProof.outputPath -eq 'verification/chat-debug-fx-sse-trace-memory-required/chat-debug-fx-sse.json' -and
            $commandPacket.traceMemoryRuntimeProof.requireTraceMemory -eq $true -and
            $commandPacket.traceMemoryRuntimeProof.seedsSelfProbe -eq $true -and
            [int]$commandPacket.traceMemoryRuntimeProof.secretHits -eq 0
        ) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $supabaseCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'supabase' -and $_.action -eq 'collect-supabase-live-proof' } | Select-Object -First 1)
        Assert-True 'partial command packet carries supabase read-only contract' ($supabaseCommand.readOnly -eq $true -and $supabaseCommand.mutationAllowed -eq $false) "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase MCP endpoint template' ([string]$supabaseCommand.mcpEndpointTemplate -match 'mcp\.supabase\.com/mcp' -and [string]$supabaseCommand.mcpEndpointTemplate -match 'read_only=true') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase docs refs' (($supabaseCommand.docsRefs -join ',') -match 'supabase.com/docs/guides/ai-tools/mcp') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase official contract signals' (($supabaseCommand.officialContractSignals -join ',') -eq 'mcp_project_scoped_read_only,data_api_grants_required,rls_policy_required,secret_keys_backend_only,advisors_required_before_schema_claim') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase collection guards' (
            $supabaseCommand.collectionGuards.mutationAllowed -eq $false -and
            $supabaseCommand.collectionGuards.storeRawRows -eq $false -and
            $supabaseCommand.collectionGuards.requireProjectScope -eq $true -and
            $supabaseCommand.collectionGuards.requireAdvisors -eq $true
        ) "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase query results path' ([string]$supabaseCommand.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase advisor results path' ([string]$supabaseCommand.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase required MCP tools' (($supabaseCommand.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries supabase artifact paths' (($supabaseCommand.artifactPaths -join ',').Contains('supabase-execute-sql-collection.packet.json') -and ($supabaseCommand.artifactPaths -join ',').Contains('supabase-query-results.template.json')) "supabaseCommand=$($supabaseCommand | ConvertTo-Json -Compress)"
        $archiveCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'archive' -and $_.action -eq 'collect-archive-index-proof' } | Select-Object -First 1)
        Assert-True 'partial command packet carries archive read-only contract' ($archiveCommand.readOnly -eq $true -and $archiveCommand.mutationAllowed -eq $false) "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries archive index path' ([string]$archiveCommand.indexPathRecommendation -eq 'BackupsXS/index.jsonl' -and [string]$archiveCommand.archiveRootRecommendation -eq 'BackupsXS') "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries archive env names' (($archiveCommand.requiredEnvNames -join ',') -eq 'ARCHIVE_INDEX,NAS_ARCHIVE_ROOT') "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet carries archive MCP tools' (($archiveCommand.requiredMcpTools -join ',') -eq 'archive.search,archive.index_build') "archiveCommand=$($archiveCommand | ConvertTo-Json -Compress)"
        $peerEvidenceBusCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'peer_evidence_bus' -and $_.action -eq 'collect-peer-evidence-bus-lanes' } | Select-Object -First 1)
        Assert-True 'partial command packet carries peer evidence bus command' ($null -ne $peerEvidenceBusCommand -and [string]$peerEvidenceBusCommand.command -match 'peer_evidence_bus' -and [string]$peerEvidenceBusCommand.targetMetric -eq 'harmony') "peerEvidenceBusCommand=$($peerEvidenceBusCommand | ConvertTo-Json -Compress)"
        $peerEvidenceBusEnvNames = @()
        if ($null -ne $peerEvidenceBusCommand.requiredEnvNames) {
            $peerEvidenceBusEnvNames = @($peerEvidenceBusCommand.requiredEnvNames)
        }
        Assert-True 'partial command packet keeps peer evidence bus env empty' ($peerEvidenceBusEnvNames.Count -eq 0) "peerEvidenceBusCommand=$($peerEvidenceBusCommand | ConvertTo-Json -Compress)"
        $webProbeRefreshCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'web_probe_refresh' -and $_.action -eq 'refresh-official-source-probe' } | Select-Object -First 1)
        Assert-True 'partial command packet carries web probe refresh command' (
            $null -ne $webProbeRefreshCommand -and
            [string]$webProbeRefreshCommand.command -match 'web_probe_refresh' -and
            [string]$webProbeRefreshCommand.outputPath -eq 'var/codex-smoke/web-probe-refresh.json' -and
            $webProbeRefreshCommand.rawContentStored -eq $false -and
            $webProbeRefreshCommand.rawQueryStored -eq $false -and
            $webProbeRefreshCommand.mutationAllowed -eq $false
        ) "webProbeRefreshCommand=$($webProbeRefreshCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet web probe command is secret safe' (-not (($webProbeRefreshCommand | ConvertTo-Json -Depth 20) -match '[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|http://')) "webProbeRefreshCommand=$($webProbeRefreshCommand | ConvertTo-Json -Compress)"
        $smbProbeCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'smb_decommission_debug_probe' } | Select-Object -First 1)
        Assert-True 'partial command packet carries SMB debug probe command' (
            $null -ne $smbProbeCommand -and
            [string]$smbProbeCommand.command -match 'smb_decommission_debug_probe' -and
            [string]$smbProbeCommand.outputPath -eq 'var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.summary.json' -and
            [string]$smbProbeCommand.viewerArtifact -eq 'var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.viewer.html' -and
            [string]$smbProbeCommand.attachmentPathPlaceholder -eq '<attachment-path>' -and
            [string]$smbProbeCommand.commandWithAttachment -match 'attachmentPath' -and
            [string]$smbProbeCommand.commandWithAttachment -match '<attachment-path>' -and
            $smbProbeCommand.mutationAllowed -eq $false -and
            $smbProbeCommand.writeDispatch -eq $false -and
            $smbProbeCommand.writeProducerKit -eq $false -and
            $smbProbeCommand.requireProducerBundles -eq $false
        ) "smbProbeCommand=$($smbProbeCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet SMB debug command is secret safe' (-not (($smbProbeCommand | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "smbProbeCommand=$($smbProbeCommand | ConvertTo-Json -Compress)"
        $traceMemoryCommand = @($commandPacket.commands | Where-Object { $_.lane -eq 'trace_memory_runtime_proof' -and $_.action -eq 'verify-trace-memory-debug-fx-sse' } | Select-Object -First 1)
        Assert-True 'partial command packet carries trace-memory runtime command' (
            $null -ne $traceMemoryCommand -and
            [string]$traceMemoryCommand.command -match 'smoke_chat_debug_fx_sse\.ps1' -and
            [string]$traceMemoryCommand.command -match 'RequireTraceMemory' -and
            [string]$traceMemoryCommand.outputPath -eq 'verification/chat-debug-fx-sse-trace-memory-required/chat-debug-fx-sse.json' -and
            $traceMemoryCommand.requireTraceMemory -eq $true -and
            $traceMemoryCommand.seedsSelfProbe -eq $true -and
            $traceMemoryCommand.storesRawPrompt -eq $false -and
            $traceMemoryCommand.storesRawModel -eq $false
        ) "traceMemoryCommand=$($traceMemoryCommand | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet trace-memory command is secret safe' (-not (($traceMemoryCommand | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "traceMemoryCommand=$($traceMemoryCommand | ConvertTo-Json -Compress)"
        $dispatchCommands = @($commandPacket.commands | Where-Object { $_.lane -eq 'external_dispatch' })
        Assert-True 'partial command packet carries dispatch command files' (
            $dispatchCommands.Count -eq 2 -and
            (($dispatchCommands.commandFile -join ',') -match '__patch_drop__/dispatch/mcp-control-loop-macmini.commands.txt') -and
            (($dispatchCommands.commandFile -join ',') -match '__patch_drop__/dispatch/mcp-control-loop-notebook.commands.txt') -and
            (($dispatchCommands.command -join "`n") -match 'run-producer-command-file')
        ) "dispatchCommands=$($dispatchCommands | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet dispatch commands are path safe' (-not (($dispatchCommands | ConvertTo-Json -Depth 20) -match '[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}')) "dispatchCommands=$($dispatchCommands | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet avoids Windows absolute paths in commands' (-not (($commandPacket.commands.command -join "`n") -match '[A-Za-z]:\\')) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        Assert-True 'partial command packet is secret safe' (-not (($commandPacket.commands.command -join "`n") -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $commandPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacketMarkdown
        Assert-Contains 'partial command packet markdown records supabase lane' $commandPacketMarkdown 'lane=supabase'
        Assert-Contains 'partial command packet markdown records archive lane' $commandPacketMarkdown 'lane=archive'
        Assert-Contains 'partial command packet markdown records peer evidence bus lane' $commandPacketMarkdown 'lane=peer_evidence_bus'
        Assert-Contains 'partial command packet markdown records web probe refresh lane' $commandPacketMarkdown 'lane=web_probe_refresh'
        Assert-Contains 'partial command packet markdown records web probe output path' $commandPacketMarkdown 'outputPath=var/codex-smoke/web-probe-refresh.json'
        Assert-Contains 'partial command packet markdown records SMB debug probe lane' $commandPacketMarkdown 'lane=smb_decommission_debug_probe'
        Assert-Contains 'partial command packet markdown records SMB debug probe output path' $commandPacketMarkdown 'outputPath=var/codex-smoke/smb-decommission-control-tower/smb-decommission-debug-probe.summary.json'
        Assert-Contains 'partial command packet markdown records SMB debug probe attachment placeholder' $commandPacketMarkdown 'attachmentPath=<attachment-path>'
        Assert-Contains 'partial command packet markdown records trace-memory lane' $commandPacketMarkdown 'lane=trace_memory_runtime_proof'
        Assert-Contains 'partial command packet markdown records trace-memory requirement' $commandPacketMarkdown 'requireTraceMemory=True'
        Assert-Contains 'partial command packet markdown records dispatch lane' $commandPacketMarkdown 'lane=external_dispatch'
        Assert-Contains 'partial command packet markdown records dispatch command file' $commandPacketMarkdown '__patch_drop__/dispatch/mcp-control-loop-notebook.commands.txt'
        Assert-Contains 'partial command packet markdown records archive index path' $commandPacketMarkdown 'index=BackupsXS/index.jsonl'
        Assert-Contains 'partial command packet markdown records supabase result path' $commandPacketMarkdown 'results=data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial command packet markdown records supabase advisor path' $commandPacketMarkdown 'advisors=data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial command packet markdown records supabase MCP tools' $commandPacketMarkdown 'mcpTools=execute_sql,get_advisors'
        Assert-Contains 'partial command packet markdown records no-local source action' $commandPacketMarkdown 'action=no-local-source-action'
        Assert-Contains 'partial command packet markdown surfaces external input gate' $commandPacketMarkdown 'externalInputGate.status=external_input_needed'
        Assert-Contains 'partial command packet markdown records external input gate safety counts' $commandPacketMarkdown 'secretHits=0 windowsAbsPathHits=0'
        Assert-Contains 'partial command packet markdown records producer placeholder' $commandPacketMarkdown '<producer-local-worktree>'
        Assert-Contains 'partial command packet markdown records computer summary' $commandPacketMarkdown 'computerUse.decision=ok reachable=True stale=False appCount=3 runningCount=2 windowCount=4 storesRawAppNames=False storesWindowTitles=False secretHits=0 outputPath=var/codex-smoke/computer-use-smoke.json'
        Assert-Contains 'partial command packet markdown records computer helper count-only proof' $commandPacketMarkdown 'helperCountOnly=True'
        Assert-Contains 'partial command packet markdown records computer probe schema' $commandPacketMarkdown 'probeSchemaVersion=awx.local.computer_use_count_probe.v1'
        Assert-Contains 'partial command packet markdown records browser summary' $commandPacketMarkdown 'browserUse.decision=ok reachable=True localhost=True stale=False screenshotCaptured=True storesRawUrl=False storesScreenshotPath=False secretHits=0 outputPath=var/codex-smoke/browser-ui-smoke.json'
        Assert-Contains 'partial command packet markdown records SMB debug probe summary' $commandPacketMarkdown 'smbDecommissionDebugProbe.tool=smb_decommission_debug_probe'
        Assert-True 'partial command packet markdown is secret safe' (-not ($commandPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "commandPacketMarkdown=$commandPacketMarkdown"
        $nextActions = Get-Content -Raw -LiteralPath $summary.artifacts.nextActions | ConvertFrom-Json
        Assert-True 'partial next-actions records entries' ([int]$nextActions.entryCount -ge 5) "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes supabase source' (($nextActions.sources -join ',') -match 'supabase_apply') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes external source' (($nextActions.sources -join ',') -match 'external_apply') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes desktop source' (($nextActions.sources -join ',') -match 'desktop_control_loop') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions includes source-health detail source' (($nextActions.sources -join ',') -match 'source_health_scorecard') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $sourceHealthActions = @($nextActions.actions | Where-Object { $_.source -eq 'source_health_scorecard' })
        Assert-True 'partial next-actions preserves source-health detail count' ($sourceHealthActions.Count -eq 4) "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $noLocalDetail = $sourceHealthActions | Where-Object { $_.action -eq 'no-local-source-action' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves no-local-source sentinel' (
            $null -ne $noLocalDetail -and
            [string]$noLocalDetail.scope -eq 'external_evidence' -and
            $noLocalDetail.mutationAllowed -eq $false -and
            $noLocalDetail.localPatchJustified -eq $false -and
            [string]$noLocalDetail.sourceContract -eq 'external_evidence_needed_no_local_source_patch' -and
            ((@($noLocalDetail.evidenceNeeded) -join ',').Contains('SUPABASE_PROJECT_REF'))
        ) "detail=$($noLocalDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions keeps no-local commands safe' (-not ((@($noLocalDetail.commands) -join "`n") -match '[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}')) "detail=$($noLocalDetail | ConvertTo-Json -Compress)"
        $supabaseDetail = $sourceHealthActions | Where-Object { $_.action -eq 'collect-supabase-live-proof' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves Supabase required MCP tools' (($supabaseDetail.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "detail=$($supabaseDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves Supabase required env names only' (($supabaseDetail.requiredEnvNames -join ',') -eq 'SUPABASE_PROJECT_REF') "detail=$($supabaseDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves Supabase auth fallback signals' (($supabaseDetail.supportedAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN' -and ($supabaseDetail.manualAuthSensitiveEnvRefs -join ',') -eq 'SUPABASE_ACCESS_TOKEN') "detail=$($supabaseDetail | ConvertTo-Json -Compress)"
        $externalDetail = $sourceHealthActions | Where-Object { $_.action -eq 'collect-external-evidence-files' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves external sidecar contract' (($externalDetail.requiredSidecars -join ',').Contains('.manifest.json')) "detail=$($externalDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves external source isolation guard' ($externalDetail.requiredSourceIsolation.guard -eq 'PASS') "detail=$($externalDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves producer command templates' (($externalDetail.producerCommandTemplates -join "`n") -match '<producer-local-worktree>' -and ($externalDetail.producerCommandTemplates -join "`n") -match '<desktop-canonical-root>') "detail=$($externalDetail | ConvertTo-Json -Compress)"
        $archiveDetail = $sourceHealthActions | Where-Object { $_.action -eq 'collect-archive-index-proof' } | Select-Object -First 1
        Assert-True 'partial next-actions preserves archive env names' (($archiveDetail.requiredEnvNames -join ',') -eq 'ARCHIVE_INDEX,NAS_ARCHIVE_ROOT') "detail=$($archiveDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions preserves archive index path' ([string]$archiveDetail.indexPathRecommendation -eq 'BackupsXS/index.jsonl') "detail=$($archiveDetail | ConvertTo-Json -Compress)"
        Assert-True 'partial next-actions keeps supabase first' (($nextActions.actions | Select-Object -First 1).source -eq 'supabase_apply') "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $latestPath = Join-Path $partialRoot 'var\codex-smoke\goal-next-auto.latest.json'
        Assert-True 'partial goal next writes stable latest pointer' (Test-Path $latestPath) "missing latest pointer at $latestPath"
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'partial latest pointer records summary path' ([string]$latest.summaryPath -eq $partialSummaryPath) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records next-actions path' ([string]$latest.nextActionsPath -eq [string]$summary.artifacts.nextActions) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records decision' ([string]$latest.decision -eq 'evidence_needed') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records freshness age' ([double]$latest.latestGeneratedAtAgeMinutes -ge 0 -and [double]$latest.latestGeneratedAtAgeMinutes -lt 5) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records stale threshold' ([int]$latest.latestStaleAfterMinutes -eq 60) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records not stale at write' ($latest.staleLatest -eq $false) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records expires at' (-not [string]::IsNullOrWhiteSpace([string]$latest.latestExpiresAt)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records digest path' (-not [string]::IsNullOrWhiteSpace([string]$latest.digestPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest digest exists' (Test-Path $latest.digestPath) "missing latest digest at $($latest.digestPath)"
        Assert-True 'partial latest pointer records digest markdown path' (-not [string]::IsNullOrWhiteSpace([string]$latest.digestMarkdownPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest digest markdown exists' (Test-Path $latest.digestMarkdownPath) "missing latest digest markdown at $($latest.digestMarkdownPath)"
        Assert-True 'partial latest pointer records breadcrumb timeline path' (-not [string]::IsNullOrWhiteSpace([string]$latest.breadcrumbTimelinePath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb timeline exists' (Test-Path $latest.breadcrumbTimelinePath) "missing breadcrumb timeline at $($latest.breadcrumbTimelinePath)"
        Assert-True 'partial latest pointer records breadcrumb fusion path' (-not [string]::IsNullOrWhiteSpace([string]$latest.breadcrumbFusionPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb fusion exists' (Test-Path $latest.breadcrumbFusionPath) "missing breadcrumb fusion at $($latest.breadcrumbFusionPath)"
        $breadcrumbFusion = Get-Content -Raw -LiteralPath $latest.breadcrumbFusionPath | ConvertFrom-Json
        Assert-True 'partial breadcrumb fusion records schema and latest tile' ([string]$breadcrumbFusion.schemaVersion -eq 'awx.goal_next_auto.breadcrumb_fusion.v1' -and [string]$breadcrumbFusion.latestRawTile.nextAction -eq 'set_SUPABASE_PROJECT_REF') "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb fusion records repeated action count' ($breadcrumbFusion.timelineRowsRead -eq 1 -and [string]$breadcrumbFusion.repeatedFirstActions[0].action -eq 'set_SUPABASE_PROJECT_REF' -and [int]$breadcrumbFusion.repeatedFirstActions[0].count -eq 1) "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb fusion classifies external input gate' ([string]$breadcrumbFusion.externalInputGate.status -eq 'external_input_needed' -and $breadcrumbFusion.externalInputGate.localPatchJustified -eq $false -and [string]$breadcrumbFusion.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF' -and [int]$breadcrumbFusion.externalInputGate.secretHits -eq 0 -and [int]$breadcrumbFusion.externalInputGate.windowsAbsPathHits -eq 0) "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        Assert-True 'partial summary surfaces external input gate' ([string]$summary.externalInputGate.status -eq 'external_input_needed' -and $summary.externalInputGate.localPatchJustified -eq $false -and [int]$summary.externalInputGate.secretHits -eq 0 -and [int]$summary.externalInputGate.windowsAbsPathHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer surfaces external input gate' ([string]$latest.externalInputGate.status -eq 'external_input_needed' -and [string]$latest.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF' -and [int]$latest.externalInputGate.secretHits -eq 0 -and [int]$latest.externalInputGate.windowsAbsPathHits -eq 0) "latest=$($latest | ConvertTo-Json -Compress)"
        $digestMarkdown = Get-Content -Raw -LiteralPath $latest.digestMarkdownPath
        Assert-Contains 'partial digest markdown surfaces external input gate' $digestMarkdown 'externalInputGate.status=external_input_needed'
        Assert-Contains 'partial digest markdown surfaces local patch decision' $digestMarkdown 'localPatchJustified=False'
        Assert-Contains 'partial digest markdown records external input gate safety counts' $digestMarkdown 'secretHits=0 windowsAbsPathHits=0'
        Assert-True 'partial breadcrumb fusion keeps paths safe' (-not (($breadcrumbFusion | ConvertTo-Json -Depth 50) -match '[A-Za-z]:\\')) "fusion=$($breadcrumbFusion | ConvertTo-Json -Compress)"
        $breadcrumbLines = @(Get-Content -LiteralPath $latest.breadcrumbTimelinePath)
        Assert-True 'partial breadcrumb timeline has one run row' ($breadcrumbLines.Count -eq 1) "breadcrumbLines=$($breadcrumbLines -join "`n")"
        $breadcrumb = $breadcrumbLines[-1] | ConvertFrom-Json
        Assert-True 'partial breadcrumb records schema and decision' ([string]$breadcrumb.schemaVersion -eq 'awx.goal_next_auto.breadcrumb.v1' -and [string]$breadcrumb.decision -eq 'evidence_needed') "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records tool steps' ((@($breadcrumb.toolSteps).name -join ',').Contains('supabase_smoke') -and (@($breadcrumb.toolSteps).name -join ',').Contains('completion_audit')) "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records raw tile summary' ([string]$breadcrumb.rawTile.schemaVersion -eq 'awx.goal_next_auto.raw_tile.v1' -and [string]$breadcrumb.rawTile.nextAction -eq 'set_SUPABASE_PROJECT_REF') "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records trace keys' ((@($breadcrumb.trace.keys) -join ',').Contains('goalNext.decision') -and (@($breadcrumb.trace.keys) -join ',').Contains('goalNext.previousBreadcrumb.present')) "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb records mdc labels' ([string]$breadcrumb.mdc.nodeRole -eq 'desktop' -and [string]$breadcrumb.mdc.topic -eq 'mcp-control-loop' -and [string]$breadcrumb.mdc.root -eq '<desktop-canonical-root>') "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial breadcrumb keeps paths safe' (-not (($breadcrumb | ConvertTo-Json -Depth 50) -match '[A-Za-z]:\\')) "breadcrumb=$($breadcrumb | ConvertTo-Json -Compress)"
        Assert-True 'partial summary records previous breadcrumb absence' ($summary.previousBreadcrumb.present -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records command packet path' (-not [string]::IsNullOrWhiteSpace([string]$latest.commandPacketPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest command packet exists' (Test-Path $latest.commandPacketPath) "missing latest command packet at $($latest.commandPacketPath)"
        Assert-True 'partial latest pointer records collection packet path' (-not [string]::IsNullOrWhiteSpace([string]$latest.collectionPacketPath)) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest collection packet exists' (Test-Path $latest.collectionPacketPath) "missing latest collection packet at $($latest.collectionPacketPath)"
        Assert-True 'partial latest pointer records supabase MCP config summary' ($latest.supabaseMcpConfig.present -eq $true -and $latest.supabaseMcpConfig.readOnly -eq $true -and [string]$latest.supabaseMcpConfig.projectRefSource -eq 'SUPABASE_PROJECT_REF') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records no stored supabase token' ($latest.supabaseMcpConfig.tokenStored -eq $false) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase smoke MCP decision' ($latest.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$latest.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase apply MCP tools' (($latest.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase required result names' (($latest.supabaseApply.requiredResultNames -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase apply result paths' ([string]$latest.supabaseApply.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json' -and [string]$latest.supabaseApply.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase env preflight status' ([string]$latest.supabaseApply.envPreflightStatus -eq 'missing_project_ref_and_mcp_auth') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records supabase oauth auth modes' ($latest.supabaseApply.mcpOAuthSupported -eq $true -and (($latest.supabaseApply.supportedAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN')) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external apply roles' (($latest.externalApply.requiredRoles -join ',') -eq 'macmini,notebook') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external apply sidecars' (($latest.externalApply.requiredPatchDropSidecars -join ',').Contains('.manifest.json') -and ($latest.externalApply.requiredPatchDropSidecars -join ',').Contains('.sha256.txt')) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external source isolation guard' ($latest.externalApply.requiredSourceIsolation.guard -eq 'PASS') "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer records external copied evidence counts' ([int]$latest.externalApply.copiedEvidenceCount -eq 0 -and [int]$latest.externalApply.copiedHandoffCount -eq 0) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer carries full computer smoke summary' ($latest.computerUse.ok -eq $true -and $latest.computerUse.reachable -eq $true -and [int]$latest.computerUse.appCount -eq 3) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer carries full browser smoke summary' ($latest.browserUse.ok -eq $true -and $latest.browserUse.reachable -eq $true -and $latest.browserUse.localhost -eq $true) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer carries peer evidence bus summary' ($latest.peerEvidenceBus.ok -eq $true -and [string]$latest.peerEvidenceBus.targetMetric -eq 'harmony' -and [int]$latest.peerEvidenceBus.secretHits -eq 0) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'partial latest pointer carries trace-memory runtime proof summary' ($latest.traceMemoryRuntimeProof.requireTraceMemory -eq $true -and [string]$latest.traceMemoryRuntimeProof.scriptPath -eq 'scripts/smoke_chat_debug_fx_sse.ps1' -and [string]$latest.traceMemoryRuntimeProof.decision -eq 'ok' -and [string]$latest.traceMemoryRuntimeProof.traceMemoryRouteDecision -eq 'retry_failsoft_degrade_warn_live_failsoft' -and [string]$latest.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointKey -eq 'traceMemory.virtualCheckpoint.second_refinement') "latest=$($latest | ConvertTo-Json -Compress)"
        $digest = Get-Content -Raw -LiteralPath $latest.digestPath | ConvertFrom-Json
        Assert-True 'partial digest records freshness age' ([double]$digest.latestGeneratedAtAgeMinutes -ge 0 -and [double]$digest.latestGeneratedAtAgeMinutes -lt 5) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records not stale at write' ($digest.staleLatest -eq $false) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records first action' ([string]$digest.firstAction -eq 'set_SUPABASE_PROJECT_REF') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records first action source' ([string]$digest.firstActionSource -eq 'supabase_apply') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records computer readiness' ($digest.computerUseOk -eq $true -and $digest.computerUseReachable -eq $true -and [int]$digest.computerUseAppCount -eq 3) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest carries full computer smoke summary' ($digest.computerUse.ok -eq $true -and $digest.computerUse.reachable -eq $true -and [int]$digest.computerUse.appCount -eq 3) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records browser readiness' ($digest.browserUseOk -eq $true -and $digest.browserUseReachable -eq $true -and $digest.browserUseLocalhost -eq $true) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest carries full browser smoke summary' ($digest.browserUse.ok -eq $true -and $digest.browserUse.reachable -eq $true -and $digest.browserUse.localhost -eq $true) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest carries peer evidence bus summary' ($digest.peerEvidenceBus.ok -eq $true -and [string]$digest.peerEvidenceBus.targetMetric -eq 'harmony' -and [int]$digest.peerEvidenceBus.secretHits -eq 0) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest carries trace-memory runtime proof summary' ($digest.traceMemoryRuntimeProof.requireTraceMemory -eq $true -and [string]$digest.traceMemoryRuntimeProof.outputPath -eq 'verification/chat-debug-fx-sse-trace-memory-required/chat-debug-fx-sse.json' -and [string]$digest.traceMemoryRuntimeProof.decision -eq 'ok' -and [string]$digest.traceMemoryRuntimeProof.traceMemoryCfvmPatternId -eq '1985725498' -and [string]$digest.traceMemoryRuntimeProof.traceMemoryVirtualCheckpointStage -eq 'second_refinement') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase MCP config summary' ($digest.supabaseMcpConfig.present -eq $true -and $digest.supabaseMcpConfig.readOnly -eq $true -and [string]$digest.supabaseMcpConfig.serverHost -eq 'mcp.supabase.com') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase MCP token guard' ($digest.supabaseMcpConfig.tokenStored -eq $false) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase smoke MCP decision' ($digest.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$digest.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase apply MCP tools' (($digest.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase apply result paths' ([string]$digest.supabaseApply.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json' -and [string]$digest.supabaseApply.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase env preflight status' ([string]$digest.supabaseApply.envPreflightStatus -eq 'missing_project_ref_and_mcp_auth') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase oauth auth modes' ($digest.supabaseApply.mcpOAuthSupported -eq $true -and (($digest.supabaseApply.supportedAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN')) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external apply roles' (($digest.externalApply.requiredRoles -join ',') -eq 'macmini,notebook') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external apply sidecars' (($digest.externalApply.requiredPatchDropSidecars -join ',').Contains('.manifest.json') -and ($digest.externalApply.requiredPatchDropSidecars -join ',').Contains('.sha256.txt')) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external source isolation guard' ($digest.externalApply.requiredSourceIsolation.guard -eq 'PASS') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external copied evidence counts' ([int]$digest.externalApply.copiedEvidenceCount -eq 0 -and [int]$digest.externalApply.copiedHandoffCount -eq 0) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records supabase action count' ([int]$digest.sourceActionCounts.supabase_apply -ge 2) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records external action count' ([int]$digest.sourceActionCounts.external_apply -ge 2) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records desktop action count' ([int]$digest.sourceActionCounts.desktop_control_loop -ge 1) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records source-health detail action count' ([int]$digest.sourceActionCounts.source_health_scorecard -eq 4) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest surfaces source-health no-local source action' ([string]$digest.sourceHealth.nextSourceAction -eq 'no_local_source_action_external_evidence_needed') "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest surfaces source-health dispatch overlay sidecar' (
            $digest.sourceHealth.dispatchOverlay.ok -eq $true -and
            [string]$digest.sourceHealth.dispatchOverlay.sidecarPath -eq '__patch_drop__/dispatch/mcp-control-loop-source-health-producer-queue.json' -and
            [string]$digest.sourceHealth.dispatchOverlay.sidecarHash -match '^[a-f0-9]{64}$' -and
            $digest.sourceHealth.dispatchOverlay.shaSidecarUpdated -eq $true
        ) "digest=$($digest | ConvertTo-Json -Compress)"
        Assert-True 'partial digest records safe top actions' (($digest.topActions | Select-Object -First 1).action -eq 'set_SUPABASE_PROJECT_REF') "digest=$($digest | ConvertTo-Json -Compress)"
        $digestMarkdown = Get-Content -Raw -LiteralPath $latest.digestMarkdownPath
        Assert-Contains 'partial digest markdown records decision' $digestMarkdown 'decision=evidence_needed'
        Assert-Contains 'partial digest markdown records freshness age' $digestMarkdown 'latestGeneratedAtAgeMinutes='
        Assert-Contains 'partial digest markdown records stale state' $digestMarkdown 'staleLatest=false'
        Assert-Contains 'partial digest markdown records first action' $digestMarkdown 'firstAction=set_SUPABASE_PROJECT_REF'
        Assert-Contains 'partial digest markdown records supabase source count' $digestMarkdown 'supabase_apply='
        Assert-Contains 'partial digest markdown records external source count' $digestMarkdown 'external_apply='
        Assert-Contains 'partial digest markdown records desktop source count' $digestMarkdown 'desktop_control_loop='
        Assert-Contains 'partial digest markdown records source-health source count' $digestMarkdown 'source_health_scorecard='
        Assert-Contains 'partial digest markdown records computer readiness' $digestMarkdown 'computerUse=ok'
        Assert-Contains 'partial digest markdown records computer count-only summary' $digestMarkdown 'computerUse=ok reachable=True stale=False appCount=3 runningCount=2 windowCount=4 helperCountOnly=True probeSchemaVersion=awx.local.computer_use_count_probe.v1 secretHits=0'
        Assert-Contains 'partial digest markdown records browser readiness' $digestMarkdown 'browserUse=ok'
        Assert-Contains 'partial digest markdown records browser count-only summary' $digestMarkdown 'browserUse=ok reachable=True localhost=True stale=False screenshotCaptured=True secretHits=0'
        Assert-Contains 'partial digest markdown records peer evidence bus readiness' $digestMarkdown 'peerEvidenceBus=ok targetMetric=harmony'
        Assert-Contains 'partial digest markdown records trace-memory proof' $digestMarkdown 'traceMemoryRuntimeProof=ok requireTraceMemory=True'
        Assert-Contains 'partial digest markdown records external required roles' $digestMarkdown 'external.requiredRoles=macmini,notebook'
        Assert-Contains 'partial digest markdown records external sidecars' $digestMarkdown 'external.requiredSidecars=pendingNotice,.manifest.json,.patch,.report.md,.verify.log,.sha256.txt'
        Assert-Contains 'partial digest markdown records external source isolation guard' $digestMarkdown 'external.sourceIsolation.guard=PASS'
        Assert-Contains 'partial digest markdown records source-health dispatch overlay' $digestMarkdown 'sourceHealthDispatchOverlay=ok sidecar=__patch_drop__/dispatch/mcp-control-loop-source-health-producer-queue.json'
        Assert-Contains 'partial digest markdown records source-health dispatch sidecar hash' $digestMarkdown 'sourceHealthDispatchOverlay.sidecarHash='
        Assert-Contains 'partial digest markdown records supabase required env names' $digestMarkdown 'supabase.requiredEnvNames=SUPABASE_PROJECT_REF'
        Assert-Contains 'partial digest markdown records supabase required MCP tools' $digestMarkdown 'supabase.requiredMcpTools=execute_sql,get_advisors'
        Assert-Contains 'partial digest markdown records supabase env preflight status' $digestMarkdown 'supabase.envPreflightStatus=missing_project_ref_and_mcp_auth'
        Assert-Contains 'partial digest markdown records supabase result path' $digestMarkdown 'supabase.resultPathRecommendation=data/db-gap-report/supabase-query-results.json'
        Assert-Contains 'partial digest markdown records supabase advisor path' $digestMarkdown 'supabase.advisorResultPathRecommendation=data/db-gap-report/supabase-advisors.json'
        Assert-Contains 'partial digest markdown records supabase smoke MCP decision' $digestMarkdown 'supabaseSmoke.mcpDecision=mcp_endpoint_probe_skipped'
        Assert-Contains 'partial digest markdown records supabase MCP read-only config' $digestMarkdown 'supabaseMcp.readOnly=True'
        Assert-Contains 'partial digest markdown records supabase MCP token guard' $digestMarkdown 'supabaseMcp.tokenStored=False'
        Assert-True 'partial digest markdown is secret safe' (-not ($digestMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}')) "digestMarkdown=$digestMarkdown"
        Assert-True 'partial summary has no secret hits' ([int]$summary.secretHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"

        $statusPath = Join-Path $partialRoot 'var\codex-smoke\goal-next-auto.status.json'
        Assert-True 'partial direct run writes status artifact' (Test-Path $statusPath) "missing direct-run status at $statusPath"
        $directStatus = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'partial direct status records evidence gate' (
            [string]$directStatus.statusDecision -eq 'evidence_needed' -and
            [string]$directStatus.externalInputGate.status -eq 'external_input_needed' -and
            [string]$directStatus.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF' -and
            [int]$directStatus.externalInputGate.secretHits -eq 0 -and
            [int]$directStatus.externalInputGate.windowsAbsPathHits -eq 0
        ) "directStatus=$($directStatus | ConvertTo-Json -Compress)"

        $partialStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'partial status exits evidence_needed' ($partialStatus.ExitCode -eq 2) "expected exit 2; output=$($partialStatus.Output)"
        Assert-Contains 'partial status reports evidence needed' $partialStatus.Output 'statusDecision=evidence_needed'
        Assert-Contains 'partial status reports fresh latest' $partialStatus.Output 'staleLatest=false'
        Assert-Contains 'partial status reports first action' $partialStatus.Output 'firstAction=set_SUPABASE_PROJECT_REF'
        Assert-Contains 'partial status reports external input gate' $partialStatus.Output 'externalInputGateStatus=external_input_needed'
        Assert-Contains 'partial status reports external input gate secret count' $partialStatus.Output 'externalInputGateSecretHits=0'
        Assert-Contains 'partial status reports external input gate path count' $partialStatus.Output 'externalInputGateWindowsAbsPathHits=0'
        Assert-True 'partial status writes status artifact' (Test-Path $statusPath) "missing status at $statusPath"
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'partial status records latest decision' ([string]$status.latestDecision -eq 'evidence_needed') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records status decision' ([string]$status.statusDecision -eq 'evidence_needed') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records not stale' ($status.staleLatest -eq $false) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records source-health source' ([string]$status.firstActionSource -eq 'supabase_apply') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records next action count' ([int]$status.nextActionCount -ge 1) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records next action sources' (($status.nextActionSources -join ',').Contains('supabase_apply')) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records safe top actions' (@($status.topActions).Count -gt 0 -and [string]$status.topActions[0].action -eq 'set_SUPABASE_PROJECT_REF') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records computer readiness' ($status.computerUseOk -eq $true -and $status.computerUseReachable -eq $true -and [int]$status.computerUseAppCount -eq 3) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status carries full computer smoke summary' ($status.computerUse.ok -eq $true -and $status.computerUse.reachable -eq $true -and [int]$status.computerUse.appCount -eq 3) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records browser readiness' ($status.browserUseOk -eq $true -and $status.browserUseReachable -eq $true -and $status.browserUseLocalhost -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status carries full browser smoke summary' ($status.browserUse.ok -eq $true -and $status.browserUse.reachable -eq $true -and $status.browserUse.localhost -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase MCP config summary' ($status.supabaseMcpConfig.present -eq $true -and $status.supabaseMcpConfig.readOnly -eq $true -and [string]$status.supabaseMcpConfig.projectRefSource -eq 'SUPABASE_PROJECT_REF') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase MCP token guard' ($status.supabaseMcpConfig.tokenStored -eq $false) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase smoke MCP decision' ($status.supabaseSmoke.mcpProbeSkipped -eq $true -and [string]$status.supabaseSmoke.mcpDecision -eq 'mcp_endpoint_probe_skipped') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase apply MCP tools' (($status.supabaseApply.requiredMcpTools -join ',') -eq 'execute_sql,get_advisors') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase required result names' (($status.supabaseApply.requiredResultNames -join ',') -eq 'schemas_and_tables,rls_and_table_flags,policies') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase apply result paths' ([string]$status.supabaseApply.resultPathRecommendation -eq 'data/db-gap-report/supabase-query-results.json' -and [string]$status.supabaseApply.advisorResultPathRecommendation -eq 'data/db-gap-report/supabase-advisors.json') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase env preflight status' ([string]$status.supabaseApply.envPreflightStatus -eq 'missing_project_ref_and_mcp_auth') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records supabase oauth auth modes' ($status.supabaseApply.mcpOAuthSupported -eq $true -and (($status.supabaseApply.supportedAuthModes -join ',') -eq 'supabase_mcp_oauth_session,manual_SUPABASE_ACCESS_TOKEN')) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records external apply roles' (($status.externalApply.requiredRoles -join ',') -eq 'macmini,notebook') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records external apply sidecars' (($status.externalApply.requiredPatchDropSidecars -join ',').Contains('.manifest.json') -and ($status.externalApply.requiredPatchDropSidecars -join ',').Contains('.sha256.txt')) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status records external source isolation guard' ($status.externalApply.requiredSourceIsolation.guard -eq 'PASS') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status surfaces external input gate' (
            [string]$status.externalInputGate.status -eq 'external_input_needed' -and
            [string]$status.externalInputGate.source -eq 'supabase_apply' -and
            [string]$status.externalInputGate.action -eq 'set_SUPABASE_PROJECT_REF' -and
            $status.externalInputGate.localPatchJustified -eq $false -and
            $status.externalInputGate.mutationAllowed -eq $false -and
            (@($status.externalInputGate.evidenceNeeded) -join ',').Contains('SUPABASE_PROJECT_REF') -and
            [int]$status.externalInputGate.secretHits -eq 0 -and
            [int]$status.externalInputGate.windowsAbsPathHits -eq 0
        ) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'partial status has no secret hits' ([int]$status.secretHits -eq 0) "status=$($status | ConvertTo-Json -Compress)"

        $defaultSourceHealthRoot = New-FakeGoalRoot -Mode 'partial'
        $defaultSourceHealthOutput = Join-Path $defaultSourceHealthRoot 'out'
        $defaultSourceHealth = Invoke-Captured -Arguments @('-File', $script, '-Root', $defaultSourceHealthRoot, '-OutputDir', $defaultSourceHealthOutput, '-Topic', 'mcp-control-loop')
        Assert-True 'default source-health external proof remains supporting and exits zero' ($defaultSourceHealth.ExitCode -eq 0) "expected exit 0; output=$($defaultSourceHealth.Output)"
        $defaultSourceHealthSummaryPath = Join-Path $defaultSourceHealthOutput 'goal-next-auto.summary.json'
        Assert-True 'default source-health external proof writes summary' (Test-Path $defaultSourceHealthSummaryPath) "missing summary at $defaultSourceHealthSummaryPath"
        if (Test-Path $defaultSourceHealthSummaryPath) {
            $defaultSourceHealthSummary = Get-Content -Raw -LiteralPath $defaultSourceHealthSummaryPath | ConvertFrom-Json
            $defaultSourceHealthNextActions = Get-Content -Raw -LiteralPath $defaultSourceHealthSummary.artifacts.nextActions | ConvertFrom-Json
            $defaultSourceHealthActions = @($defaultSourceHealthNextActions.actions | Where-Object { $_.source -eq 'source_health_scorecard' })
            Assert-True 'default source-health keeps external producer proof out of primary next actions' (
                $null -eq ($defaultSourceHealthActions | Where-Object { $_.action -eq 'collect-external-evidence-files' } | Select-Object -First 1)
            ) "nextActions=$($defaultSourceHealthNextActions | ConvertTo-Json -Compress)"
            Assert-True 'default source-health keeps no-local external sentinel out of primary next actions' (
                $null -eq ($defaultSourceHealthActions | Where-Object { $_.action -eq 'no-local-source-action' } | Select-Object -First 1)
            ) "nextActions=$($defaultSourceHealthNextActions | ConvertTo-Json -Compress)"
            $defaultSourceHealthCommandPacket = Get-Content -Raw -LiteralPath $defaultSourceHealthSummary.artifacts.commandPacket | ConvertFrom-Json
            Assert-True 'default source-health command packet omits external producer lane' (
                $null -eq ($defaultSourceHealthCommandPacket.commands | Where-Object { $_.lane -eq 'external_producer' } | Select-Object -First 1)
            ) "commandPacket=$($defaultSourceHealthCommandPacket | ConvertTo-Json -Compress)"
            Assert-True 'default source-health command packet omits external evidence lane' (
                $null -eq ($defaultSourceHealthCommandPacket.commands | Where-Object { $_.lane -eq 'external_evidence' } | Select-Object -First 1)
            ) "commandPacket=$($defaultSourceHealthCommandPacket | ConvertTo-Json -Compress)"
            Assert-True 'default source-health command packet omits Supabase lane unless proof is requested' (
                $null -eq ($defaultSourceHealthCommandPacket.commands | Where-Object { $_.lane -eq 'supabase' -and $_.action -eq 'collect-supabase-live-proof' } | Select-Object -First 1)
            ) "commandPacket=$($defaultSourceHealthCommandPacket | ConvertTo-Json -Compress)"
        }

        $publicDomainRoot = New-FakeGoalRoot -Mode 'partial'
        $publicGeneratedAt = [DateTime]::UtcNow.ToString('o')
        Set-TestFile (Join-Path $publicDomainRoot 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "schemaVersion": "awx.browser_ui.smoke.v1",
  "generatedAt": "$publicGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "localhost": false,
  "publicDomain": true,
  "targetUrl": "https://abandonwareai.kro.kr/private-debug-path",
  "screenshotCaptured": true,
  "statusClass": "ui_visible",
  "targetContentVisible": true,
  "browserSurface": "iab",
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        $publicDomainOutput = Join-Path $publicDomainRoot 'out'
        $publicDomain = Invoke-Captured -Arguments @('-File', $script, '-Root', $publicDomainRoot, '-OutputDir', $publicDomainOutput)
        Assert-True 'public-domain browser smoke exits zero when external proof is optional' ($publicDomain.ExitCode -eq 0) "expected exit 0; output=$($publicDomain.Output)"
        $publicDomainSummaryPath = Join-Path $publicDomainOutput 'goal-next-auto.summary.json'
        Assert-True 'public-domain browser smoke writes summary' (Test-Path $publicDomainSummaryPath) "missing public-domain summary at $publicDomainSummaryPath"
        if (Test-Path $publicDomainSummaryPath) {
            $publicSummary = Get-Content -Raw -LiteralPath $publicDomainSummaryPath | ConvertFrom-Json
            Assert-True 'public-domain browser smoke is accepted without localhost' (
                $publicSummary.browserUse.ok -eq $true -and
                $publicSummary.browserUse.reachable -eq $true -and
                $publicSummary.browserUse.localhost -eq $false -and
                $publicSummary.browserUse.publicDomain -eq $true -and
                $publicSummary.browserUse.targetAccepted -eq $true -and
                [string]$publicSummary.browserUse.targetHost -eq 'abandonwareai.kro.kr'
            ) "summary=$($publicSummary | ConvertTo-Json -Compress)"
            Assert-True 'public-domain browser smoke does not publish raw URL' (-not (($publicSummary.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|private-debug-path|[A-Za-z]:\\')) "browserUse=$($publicSummary.browserUse | ConvertTo-Json -Compress)"
        }

        $unreachablePublicRoot = New-FakeGoalRoot -Mode 'partial'
        $unreachableGeneratedAt = [DateTime]::UtcNow.ToString('o')
        Set-TestFile (Join-Path $unreachablePublicRoot 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "schemaVersion": "awx.browser_ui.smoke.v1",
  "generatedAt": "$unreachableGeneratedAt",
  "ok": false,
  "decision": "evidence_needed",
  "reachable": false,
  "localhost": false,
  "publicDomain": true,
  "targetHost": "abandonwareai.kro.kr",
  "screenshotCaptured": false,
  "statusClass": "connection_refused",
  "targetContentVisible": false,
  "browserSurface": "iab",
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        $unreachablePublicOutput = Join-Path $unreachablePublicRoot 'out'
        $unreachablePublic = Invoke-Captured -Arguments @('-File', $script, '-Root', $unreachablePublicRoot, '-OutputDir', $unreachablePublicOutput)
        Assert-True 'unreachable public-domain browser smoke remains supporting by default' ($unreachablePublic.ExitCode -eq 0) "expected exit 0; output=$($unreachablePublic.Output)"
        $unreachablePublicSummaryPath = Join-Path $unreachablePublicOutput 'goal-next-auto.summary.json'
        Assert-True 'unreachable public-domain browser smoke writes summary' (Test-Path $unreachablePublicSummaryPath) "missing summary at $unreachablePublicSummaryPath"
        if (Test-Path $unreachablePublicSummaryPath) {
            $unreachablePublicSummary = Get-Content -Raw -LiteralPath $unreachablePublicSummaryPath | ConvertFrom-Json
            Assert-True 'unreachable public-domain browser action stays supporting by default' (
                [string]$unreachablePublicSummary.firstActionSource -ne 'browser_use' -and
                [string]$unreachablePublicSummary.topActions[0].source -ne 'browser_use'
            ) "summary=$($unreachablePublicSummary | ConvertTo-Json -Compress)"
            Assert-True 'unreachable public-domain browser summary records listener failure reason' (
                [string]$unreachablePublicSummary.browserUse.evidenceNeeded -eq 'public-listener-unreachable'
            ) "summary=$($unreachablePublicSummary | ConvertTo-Json -Compress)"
            $unreachablePublicCommandPacket = Get-Content -Raw -LiteralPath $unreachablePublicSummary.artifacts.commandPacket | ConvertFrom-Json
            $unreachablePublicBrowserCommand = $unreachablePublicCommandPacket.commands | Where-Object { $_.lane -eq 'browser_use' } | Select-Object -First 1
            Assert-True 'unreachable public-domain browser command packet omits browser lane by default' ($null -eq $unreachablePublicBrowserCommand) "commandPacket=$($unreachablePublicCommandPacket | ConvertTo-Json -Compress)"
            $unreachablePublicCommandPacketMarkdown = Get-Content -Raw -LiteralPath $unreachablePublicSummary.artifacts.commandPacketMarkdown
            Assert-True 'unreachable public-domain command packet markdown omits browser lane by default' (-not ($unreachablePublicCommandPacketMarkdown -match 'lane=browser_use')) "commandPacketMarkdown=$unreachablePublicCommandPacketMarkdown"
            $unreachablePublicCollectionPacket = Get-Content -Raw -LiteralPath $unreachablePublicSummary.artifacts.collectionPacket | ConvertFrom-Json
            Assert-True 'unreachable public-domain collection packet preserves listener prerequisite' (
                $unreachablePublicCollectionPacket.browserUse.externalListenerRequired -eq $true -and
                [string]$unreachablePublicCollectionPacket.browserUse.preflightCommand -match 'domain_public_https_preflight\.ps1' -and
                [string]$unreachablePublicCollectionPacket.browserUse.evidenceNeeded -eq 'public-listener-unreachable'
            ) "collectionPacket=$($unreachablePublicCollectionPacket | ConvertTo-Json -Compress)"
            $unreachablePublicCollectionPacketMarkdown = Get-Content -Raw -LiteralPath $unreachablePublicSummary.artifacts.collectionPacketMarkdown
            Assert-Contains 'unreachable public-domain collection packet markdown records listener prerequisite' $unreachablePublicCollectionPacketMarkdown 'browserUse.externalListenerRequired=True'
            Assert-Contains 'unreachable public-domain collection packet markdown records preflight command' $unreachablePublicCollectionPacketMarkdown 'domain_public_https_preflight.ps1'
        }

        $missingPublicSmokeRoot = New-FakeGoalRoot -Mode 'partial'
        Remove-Item -LiteralPath (Join-Path $missingPublicSmokeRoot 'var\codex-smoke\browser-ui-smoke.json') -Force
        Set-TestFile (Join-Path $missingPublicSmokeRoot 'main\resources\application.yml') @'
app:
  public-base-url: ${APP_PUBLIC_BASE_URL:https://abandonwareai.kro.kr}
'@
        $oldAppPublicBaseUrl = $env:APP_PUBLIC_BASE_URL
        $oldPublicBaseUrl = $env:PUBLIC_BASE_URL
        try {
            $env:APP_PUBLIC_BASE_URL = ''
            $env:PUBLIC_BASE_URL = ''
            $missingPublicSmokeOutput = Join-Path $missingPublicSmokeRoot 'out'
            $missingPublicSmoke = Invoke-Captured -Arguments @('-File', $script, '-Root', $missingPublicSmokeRoot, '-OutputDir', $missingPublicSmokeOutput)
            Assert-True 'missing public-domain browser smoke remains supporting by default' ($missingPublicSmoke.ExitCode -eq 0) "expected exit 0; output=$($missingPublicSmoke.Output)"
            $missingPublicSmokeSummaryPath = Join-Path $missingPublicSmokeOutput 'goal-next-auto.summary.json'
            Assert-True 'missing public-domain browser smoke writes summary' (Test-Path $missingPublicSmokeSummaryPath) "missing summary at $missingPublicSmokeSummaryPath"
            if (Test-Path $missingPublicSmokeSummaryPath) {
                $missingPublicSummary = Get-Content -Raw -LiteralPath $missingPublicSmokeSummaryPath | ConvertFrom-Json
                Assert-True 'missing public-domain browser smoke requests public rerun' (
                    $missingPublicSummary.browserUse.present -eq $false -and
                    $missingPublicSummary.browserUse.publicDomain -eq $true -and
                    $missingPublicSummary.browserUse.targetAccepted -eq $true -and
                    [string]$missingPublicSummary.browserUse.targetHost -eq 'abandonwareai.kro.kr' -and
                    [string]$missingPublicSummary.browserUse.nextAction -eq 'run_browser_public_domain_ui_smoke'
                ) "summary=$($missingPublicSummary | ConvertTo-Json -Compress)"
                Assert-True 'missing public-domain browser smoke summary stays URL-free' (-not (($missingPublicSummary.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|[A-Za-z]:\\')) "browserUse=$($missingPublicSummary.browserUse | ConvertTo-Json -Compress)"
                $missingPublicNextActions = Get-Content -Raw -LiteralPath $missingPublicSummary.artifacts.nextActions | ConvertFrom-Json
                $missingPublicBrowserAction = $missingPublicNextActions.actions | Where-Object { $_.source -eq 'browser_use' } | Select-Object -First 1
                Assert-True 'missing public-domain browser smoke stays out of primary next actions by default' ($null -eq $missingPublicBrowserAction) "nextActions=$($missingPublicNextActions | ConvertTo-Json -Compress)"
                $missingPublicCommandPacket = Get-Content -Raw -LiteralPath $missingPublicSummary.artifacts.commandPacket | ConvertFrom-Json
                $missingPublicBrowserCommand = $missingPublicCommandPacket.commands | Where-Object { $_.lane -eq 'browser_use' } | Select-Object -First 1
                Assert-True 'missing public-domain browser command packet omits browser lane by default' ($null -eq $missingPublicBrowserCommand) "commandPacket=$($missingPublicCommandPacket | ConvertTo-Json -Compress)"
                $missingPublicCollectionPacket = Get-Content -Raw -LiteralPath $missingPublicSummary.artifacts.collectionPacket | ConvertFrom-Json
                Assert-True 'missing public-domain browser collection packet records public action' (
                    $missingPublicCollectionPacket.browserUse.nextActions -contains 'run_browser_public_domain_ui_smoke'
                ) "collectionPacket=$($missingPublicCollectionPacket | ConvertTo-Json -Compress)"
            }
        } finally {
            $env:APP_PUBLIC_BASE_URL = $oldAppPublicBaseUrl
            $env:PUBLIC_BASE_URL = $oldPublicBaseUrl
        }

        $targetNotCurrentRoot = New-FakeGoalRoot -Mode 'partial'
        $targetNotCurrentGeneratedAt = [DateTime]::UtcNow.ToString('o')
        Set-TestFile (Join-Path $targetNotCurrentRoot 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "schemaVersion": "awx.local.browser_ui_smoke.v1",
  "generatedAt": "$targetNotCurrentGeneratedAt",
  "ok": false,
  "decision": "evidence_needed",
  "safePendingProof": true,
  "artifactPresent": true,
  "artifactGeneratedAt": true,
  "artifactFresh": true,
  "artifactPath": "var/codex-smoke/browser-ui-smoke.json",
  "reachable": true,
  "localhost": false,
  "publicDomain": false,
  "targetAccepted": false,
  "targetHost": "unknown",
  "screenshotCaptured": true,
  "statusClass": "page",
  "targetContentVisible": false,
  "browserSurface": "in_app_browser",
  "storesRawUrl": false,
  "storesScreenshotPath": false,
  "evidenceNeeded": "browser_ui_target_not_current",
  "nextAction": "open_local_or_public_ui_target_then_rerun_browser_smoke",
  "stale": false,
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        $targetNotCurrentOutput = Join-Path $targetNotCurrentRoot 'out'
        $targetNotCurrent = Invoke-Captured -Arguments @('-File', $script, '-Root', $targetNotCurrentRoot, '-OutputDir', $targetNotCurrentOutput)
        Assert-True 'target-not-current browser smoke remains supporting by default' ($targetNotCurrent.ExitCode -eq 0) "expected exit 0; output=$($targetNotCurrent.Output)"
        $targetNotCurrentSummaryPath = Join-Path $targetNotCurrentOutput 'goal-next-auto.summary.json'
        Assert-True 'target-not-current browser smoke writes summary' (Test-Path $targetNotCurrentSummaryPath) "missing target-not-current summary at $targetNotCurrentSummaryPath"
        if (Test-Path $targetNotCurrentSummaryPath) {
            $targetNotCurrentSummary = Get-Content -Raw -LiteralPath $targetNotCurrentSummaryPath | ConvertFrom-Json
            Assert-True 'target-not-current browser smoke preserves safe pending reason' (
                $targetNotCurrentSummary.browserUse.ok -eq $false -and
                $targetNotCurrentSummary.browserUse.safePendingProof -eq $true -and
                [string]$targetNotCurrentSummary.browserUse.evidenceNeeded -eq 'browser_ui_target_not_current' -and
                [string]$targetNotCurrentSummary.browserUse.nextAction -eq 'open_local_or_public_ui_target_then_rerun_browser_smoke'
            ) "summary=$($targetNotCurrentSummary | ConvertTo-Json -Compress)"
            $targetNotCurrentNextActions = Get-Content -Raw -LiteralPath $targetNotCurrentSummary.artifacts.nextActions | ConvertFrom-Json
            $targetNotCurrentBrowserAction = $targetNotCurrentNextActions.actions | Where-Object { $_.source -eq 'browser_use' } | Select-Object -First 1
            Assert-True 'target-not-current browser action stays supporting by default' ($null -eq $targetNotCurrentBrowserAction) "nextActions=$($targetNotCurrentNextActions | ConvertTo-Json -Compress)"
            $targetNotCurrentCollectionPacket = Get-Content -Raw -LiteralPath $targetNotCurrentSummary.artifacts.collectionPacket | ConvertFrom-Json
            Assert-True 'target-not-current collection packet preserves safe pending browser evidence' (
                $targetNotCurrentCollectionPacket.browserUse.safePendingProof -eq $true -and
                [string]$targetNotCurrentCollectionPacket.browserUse.evidenceNeeded -eq 'browser_ui_target_not_current' -and
                $targetNotCurrentCollectionPacket.browserUse.nextActions -contains 'open_local_or_public_ui_target_then_rerun_browser_smoke'
            ) "collectionPacket=$($targetNotCurrentCollectionPacket | ConvertTo-Json -Compress)"
            $targetNotCurrentCommandPacket = Get-Content -Raw -LiteralPath $targetNotCurrentSummary.artifacts.commandPacket | ConvertFrom-Json
            $targetNotCurrentBrowserCommand = $targetNotCurrentCommandPacket.commands | Where-Object { $_.lane -eq 'browser_use' } | Select-Object -First 1
            Assert-True 'target-not-current command packet omits browser lane by default' ($null -eq $targetNotCurrentBrowserCommand) "commandPacket=$($targetNotCurrentCommandPacket | ConvertTo-Json -Compress)"
        }

        $latest.generatedAt = (Get-Item -LiteralPath $script).LastWriteTimeUtc.AddSeconds(-5).ToString('o')
        $latest.latestStaleAfterMinutes = 999999
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $scriptNewerStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'script-newer status exits evidence_needed' ($scriptNewerStatus.ExitCode -eq 2) "expected exit 2; output=$($scriptNewerStatus.Output)"
        Assert-Contains 'script-newer status reports stale latest' $scriptNewerStatus.Output 'staleLatest=true'
        Assert-Contains 'script-newer status reports classifier' $scriptNewerStatus.Output 'failureClassification=script-newer-than-latest'
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'script-newer status records stale latest' ($status.staleLatest -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'script-newer status records classifier' ([string]$status.failureClassification -eq 'script-newer-than-latest') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'script-newer status records dependency name only' (
            -not [string]::IsNullOrWhiteSpace([string]$status.latestDependencyName) -and
            -not ([string]$status.latestDependencyName -match '[\\/:]') -and
            ([string]$status.latestDependencyName -match '\.(ps1|py)$')
        ) "status=$($status | ConvertTo-Json -Compress)"

        $preflightDependency = Join-Path $partialRoot 'scripts\domain_public_https_preflight.ps1'
        Set-TestFile $preflightDependency 'Write-Host "[fixture] domain preflight"'
        (Get-Item -LiteralPath $preflightDependency).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(1)
        $latest.generatedAt = [DateTime]::UtcNow.ToString('o')
        $latest.latestStaleAfterMinutes = 999999
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $preflightNewerStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'preflight-newer status exits evidence_needed' ($preflightNewerStatus.ExitCode -eq 2) "expected exit 2; output=$($preflightNewerStatus.Output)"
        Assert-Contains 'preflight-newer status reports classifier' $preflightNewerStatus.Output 'failureClassification=script-newer-than-latest'
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'preflight-newer status records dependency name' (
            [string]$status.latestDependencyName -eq 'domain_public_https_preflight.ps1'
        ) "status=$($status | ConvertTo-Json -Compress)"
        Remove-Item -LiteralPath $preflightDependency -Force

        $dbGapDependency = Join-Path $partialRoot 'data\db-gap-report\gap_matrix.json'
        Set-TestFile $dbGapDependency '{"generatedAt":"fixture","action_required_count":0}'
        (Get-Item -LiteralPath $dbGapDependency).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(1)
        $latest.generatedAt = [DateTime]::UtcNow.ToString('o')
        $latest.latestStaleAfterMinutes = 999999
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $dbGapNewerStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'db-gap-report-newer status exits evidence_needed' ($dbGapNewerStatus.ExitCode -eq 2) "expected exit 2; output=$($dbGapNewerStatus.Output)"
        Assert-Contains 'db-gap-report-newer status reports stale latest' $dbGapNewerStatus.Output 'staleLatest=true'
        Assert-Contains 'db-gap-report-newer status reports classifier' $dbGapNewerStatus.Output 'failureClassification=db-gap-report-newer-than-latest'
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'db-gap-report-newer status records dependency name' (
            [string]$status.latestDependencyName -eq 'gap_matrix.json'
        ) "status=$($status | ConvertTo-Json -Compress)"
        Remove-Item -LiteralPath $dbGapDependency -Force

        $latest.generatedAt = (Get-Date).ToUniversalTime().AddMinutes(-90).ToString('o')
        $latest.latestStaleAfterMinutes = 60
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $staleStatus = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Status')
        Assert-True 'stale status exits evidence_needed' ($staleStatus.ExitCode -eq 2) "expected exit 2; output=$($staleStatus.Output)"
        Assert-Contains 'stale status reports stale latest' $staleStatus.Output 'staleLatest=true'
        Assert-Contains 'stale status reports stale classifier' $staleStatus.Output 'failureClassification=stale-latest'
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'stale status records stale latest' ($status.staleLatest -eq $true) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'stale status records stale classifier' ([string]$status.failureClassification -eq 'stale-latest') "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'stale status records latest age' ([double]$status.latestGeneratedAtAgeMinutes -ge 60) "status=$($status | ConvertTo-Json -Compress)"

        $wrapperOutput = Join-Path $partialRoot 'wrapper-out'
        $wrapper = Invoke-Captured -Arguments @('-File', $nextScript, '-Root', $partialRoot, '-OutputDir', $wrapperOutput, '-Topic', 'mcp-control-loop')
        Assert-True 'goal next wrapper refreshes stale latest and exits zero' ($wrapper.ExitCode -eq 0) "expected exit 0; output=$($wrapper.Output)"
        Assert-Contains 'goal next wrapper reports refresh action' $wrapper.Output 'action=refresh'
        Assert-Contains 'goal next wrapper reports stale reason' $wrapper.Output 'reason=stale-latest'
        $wrapperSummaryPath = Join-Path $wrapperOutput 'goal-next-auto.summary.json'
        Assert-True 'goal next wrapper writes refreshed summary' (Test-Path $wrapperSummaryPath) "missing wrapper summary at $wrapperSummaryPath"
        $wrapperSummary = Get-Content -Raw -LiteralPath $wrapperSummaryPath | ConvertFrom-Json
        Assert-True 'goal next wrapper reuses previous breadcrumb' ($wrapperSummary.previousBreadcrumb.present -eq $true -and [string]$wrapperSummary.previousBreadcrumb.firstAction -eq 'set_SUPABASE_PROJECT_REF') "wrapperSummary=$($wrapperSummary | ConvertTo-Json -Compress)"
        Assert-True 'goal next wrapper records breadcrumb fusion artifact' (-not [string]::IsNullOrWhiteSpace([string]$wrapperSummary.artifacts.breadcrumbFusion) -and (Test-Path $wrapperSummary.artifacts.breadcrumbFusion)) "wrapperSummary=$($wrapperSummary | ConvertTo-Json -Compress)"
        $wrapperFusion = Get-Content -Raw -LiteralPath $wrapperSummary.artifacts.breadcrumbFusion | ConvertFrom-Json
        Assert-True 'goal next wrapper fusion demotes repeated Supabase blocker from primary action' (
            $wrapperFusion.timelineRowsRead -ge 2 -and
            (($wrapperFusion.repeatedFirstActions.action -join ',').Contains('set_SUPABASE_PROJECT_REF')) -and
            [int]($wrapperFusion.repeatedFirstActions | Where-Object { $_.action -eq 'set_SUPABASE_PROJECT_REF' } | Select-Object -First 1).count -eq 1 -and
            [string]$wrapperFusion.latestFirstAction -ne 'set_SUPABASE_PROJECT_REF'
        ) "wrapperFusion=$($wrapperFusion | ConvertTo-Json -Compress)"
        Assert-True 'goal next wrapper fusion preserves local gate after Supabase demotion' (
            [string]$wrapperFusion.externalInputGate.status -eq 'local_or_unknown' -and
            [string]$wrapperFusion.externalInputGate.source -ne 'supabase_apply' -and
            [string]$wrapperFusion.externalInputGate.action -ne 'set_SUPABASE_PROJECT_REF' -and
            $wrapperFusion.externalInputGate.localPatchJustified -eq $true
        ) "wrapperFusion=$($wrapperFusion | ConvertTo-Json -Compress)"
        Assert-True 'goal next wrapper summary surfaces external default gate' (
            [string]$wrapperSummary.externalInputGate.status -eq 'local_or_unknown' -and
            [string]$wrapperSummary.externalInputGate.source -ne 'supabase_apply' -and
            $wrapperSummary.externalInputGate.localPatchJustified -eq $true
        ) "wrapperSummary=$($wrapperSummary | ConvertTo-Json -Compress)"
        $breadcrumbLines = @(Get-Content -LiteralPath $latest.breadcrumbTimelinePath)
        Assert-True 'goal next wrapper appends breadcrumb row' ($breadcrumbLines.Count -ge 2) "breadcrumbLines=$($breadcrumbLines -join "`n")"
        $wrapperBreadcrumb = $breadcrumbLines[-1] | ConvertFrom-Json
        Assert-True 'goal next wrapper raw tile records reuse source' ([string]$wrapperBreadcrumb.rawTile.reuseSource -eq 'previous_breadcrumb' -and [string]$wrapperBreadcrumb.rawTile.previousFirstAction -eq 'set_SUPABASE_PROJECT_REF') "wrapperBreadcrumb=$($wrapperBreadcrumb | ConvertTo-Json -Compress)"

        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        $latest.generatedAt = (Get-Date).ToUniversalTime().AddMinutes(-90).ToString('o')
        $latest.latestStaleAfterMinutes = 60
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8

        $ensureOutput = Join-Path $partialRoot 'ensure-out'
        $ensure = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $ensureOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh refreshes stale latest and exits zero' ($ensure.ExitCode -eq 0) "expected exit 0; output=$($ensure.Output)"
        Assert-Contains 'ensure-fresh reports refresh action' $ensure.Output 'action=refresh'
        Assert-Contains 'ensure-fresh reports stale reason' $ensure.Output 'reason=stale-latest'
        $ensureSummaryPath = Join-Path $ensureOutput 'goal-next-auto.summary.json'
        Assert-True 'ensure-fresh writes refreshed summary' (Test-Path $ensureSummaryPath) "missing ensure summary at $ensureSummaryPath"
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'ensure-fresh refreshes latest pointer' ([string]$latest.summaryPath -eq $ensureSummaryPath) "latest=$($latest | ConvertTo-Json -Compress)"
        Assert-True 'ensure-fresh writes non-stale latest pointer' ($latest.staleLatest -eq $false) "latest=$($latest | ConvertTo-Json -Compress)"
        $status = Get-Content -Raw -LiteralPath $statusPath | ConvertFrom-Json
        Assert-True 'ensure-fresh refreshes status artifact after refresh' ([string]$status.summaryPath -eq $ensureSummaryPath) "status=$($status | ConvertTo-Json -Compress)"
        Assert-True 'ensure-fresh status artifact is non-stale after refresh' ($status.staleLatest -eq $false) "status=$($status | ConvertTo-Json -Compress)"

        $freshOutput = Join-Path $partialRoot 'ensure-out-fresh'
        $freshEnsure = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $freshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh keeps fresh desktop-only ready status' ($freshEnsure.ExitCode -eq 0) "expected exit 0; output=$($freshEnsure.Output)"
        Assert-Contains 'ensure-fresh reports status action for fresh latest' $freshEnsure.Output 'action=status'
        Assert-Contains 'ensure-fresh reports fresh reason' $freshEnsure.Output 'reason=fresh-latest'
        Assert-True 'ensure-fresh does not rerun child gates when latest is fresh' (-not (Test-Path (Join-Path $freshOutput 'goal-next-auto.summary.json'))) "fresh ensure unexpectedly wrote summary under $freshOutput"

        $traceProofPath = Join-Path $partialRoot 'verification\chat-debug-fx-sse-trace-memory-required\chat-debug-fx-sse.json'
        (Get-Item -LiteralPath $traceProofPath).LastWriteTimeUtc = [DateTime]::UtcNow.AddSeconds(5)
        $traceRefreshOutput = Join-Path $partialRoot 'ensure-out-trace-refresh'
        $traceRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $traceRefreshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh refreshes newer trace-memory runtime proof and exits zero' ($traceRefresh.ExitCode -eq 0) "expected exit 0; output=$($traceRefresh.Output)"
        Assert-Contains 'ensure-fresh reports refresh action for newer trace-memory runtime proof' $traceRefresh.Output 'action=refresh'
        Assert-Contains 'ensure-fresh reports trace-memory runtime proof newer reason' $traceRefresh.Output 'reason=trace-memory-runtime-proof-newer-than-latest'
        Assert-True 'ensure-fresh writes refreshed summary after newer trace-memory runtime proof' (Test-Path (Join-Path $traceRefreshOutput 'goal-next-auto.summary.json')) "missing trace refresh summary at $traceRefreshOutput"
        (Get-Item -LiteralPath $traceProofPath).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(-1)

        $computerSmokePath = Join-Path $partialRoot 'var\codex-smoke\computer-use-smoke.json'
        $computerGeneratedAt = [DateTime]::UtcNow.ToString('o')
        Set-TestFile $computerSmokePath @"
{
  "schemaVersion": "awx.computer_use_smoke.v1",
  "generatedAt": "$computerGeneratedAt",
  "ok": true,
  "decision": "ok",
  "appCount": 5,
  "runningCount": 2,
  "windowCount": 4,
  "storesRawAppNames": false,
  "storesWindowTitles": false,
  "rawSecretPatternHits": 0
}
"@
        (Get-Item -LiteralPath $computerSmokePath).LastWriteTimeUtc = [DateTime]::UtcNow.AddSeconds(5)
        $computerRefreshOutput = Join-Path $partialRoot 'ensure-out-computer-refresh'
        $computerRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $computerRefreshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh treats newer computer smoke as supporting by default' ($computerRefresh.ExitCode -eq 0) "expected exit 0; output=$($computerRefresh.Output)"
        Assert-Contains 'ensure-fresh reports status action for newer computer smoke by default' $computerRefresh.Output 'action=status'
        Assert-Contains 'ensure-fresh reports fresh reason for newer computer smoke by default' $computerRefresh.Output 'reason=fresh-latest'
        Assert-True 'ensure-fresh does not write refreshed summary for newer computer smoke by default' (-not (Test-Path (Join-Path $computerRefreshOutput 'goal-next-auto.summary.json'))) "unexpected computer refresh summary at $computerRefreshOutput"

        $computerRefreshUiProofOutput = Join-Path $partialRoot 'ensure-out-computer-refresh-ui-proof'
        $computerRefreshUiProof = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $computerRefreshUiProofOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh', '-RequireUiProof')
        Assert-True 'ensure-fresh UI proof mode refreshes valid newer computer smoke and exits zero' ($computerRefreshUiProof.ExitCode -eq 0) "expected exit 0; output=$($computerRefreshUiProof.Output)"
        Assert-Contains 'ensure-fresh UI proof mode reports refresh action for newer computer smoke' $computerRefreshUiProof.Output 'action=refresh'
        Assert-Contains 'ensure-fresh UI proof mode reports computer smoke newer reason' $computerRefreshUiProof.Output 'reason=computer-use-smoke-newer-than-latest'
        $computerRefreshSummaryPath = Join-Path $computerRefreshUiProofOutput 'goal-next-auto.summary.json'
        Assert-True 'ensure-fresh UI proof mode writes refreshed summary after newer computer smoke' (Test-Path $computerRefreshSummaryPath) "missing computer refresh summary at $computerRefreshSummaryPath"
        if (Test-Path $computerRefreshSummaryPath) {
            $computerSummary = Get-Content -Raw -LiteralPath $computerRefreshSummaryPath | ConvertFrom-Json
            Assert-True 'newer computer smoke is reflected in UI proof refreshed summary' ([int]$computerSummary.computerUse.appCount -eq 5) "summary=$($computerSummary | ConvertTo-Json -Compress)"
            Assert-True 'newer helper-style computer smoke infers readiness without reachable field in UI proof mode' ($computerSummary.computerUse.ok -eq $true -and $computerSummary.computerUse.reachable -eq $true -and [string]$computerSummary.computerUse.nextAction -eq '') "summary=$($computerSummary | ConvertTo-Json -Compress)"
        }

        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        $latest.generatedAt = [DateTime]::UtcNow.ToString('o')
        $latest.latestStaleAfterMinutes = 60
        $latest.requestMode = [pscustomobject][ordered]@{ requireSupabaseProof = $false; externalDispatch = $false; refreshWebProbe = $false; requireUiProof = $false }
        ($latest | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $latestPath -Encoding UTF8
        $staleComputerGeneratedAt = [DateTime]::UtcNow.AddMinutes(-90).ToString('o')
        Set-TestFile $computerSmokePath @"
{
  "schemaVersion": "awx.computer_use.smoke.v1",
  "generatedAt": "$staleComputerGeneratedAt",
  "ok": true,
  "decision": "ok",
  "reachable": true,
  "appCount": 5,
  "secretHits": 0,
  "rawSecretPatternHits": 0
}
"@
        (Get-Item -LiteralPath $computerSmokePath).LastWriteTimeUtc = [DateTime]::UtcNow.AddMinutes(-5)
        $staleComputerRefreshOutput = Join-Path $partialRoot 'ensure-out-computer-stale-refresh'
        $staleComputerRefresh = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-OutputDir', $staleComputerRefreshOutput, '-Topic', 'mcp-control-loop', '-EnsureFresh')
        Assert-True 'ensure-fresh keeps stale computer smoke supporting under fresh latest' ($staleComputerRefresh.ExitCode -eq 0) "expected exit 0; output=$($staleComputerRefresh.Output)"
        Assert-Contains 'ensure-fresh keeps stale computer smoke supporting by default' $staleComputerRefresh.Output 'action=status'
        Assert-Contains 'ensure-fresh keeps latest fresh without UI proof mode' $staleComputerRefresh.Output 'reason=fresh-latest'
    }

    $computerStaleRoot = New-FakeGoalRoot -Mode 'computerstale'
    $computerStaleOutput = Join-Path $computerStaleRoot 'out'
    $computerStale = Invoke-Captured -Arguments @('-File', $script, '-Root', $computerStaleRoot, '-OutputDir', $computerStaleOutput)
    Assert-True 'stale computer smoke remains supporting by default' ($computerStale.ExitCode -eq 0) "expected exit 0; output=$($computerStale.Output)"
    $computerStaleSummaryPath = Join-Path $computerStaleOutput 'goal-next-auto.summary.json'
    Assert-True 'stale computer smoke writes summary' (Test-Path $computerStaleSummaryPath) "missing stale computer summary at $computerStaleSummaryPath"
    if (Test-Path $computerStaleSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $computerStaleSummaryPath | ConvertFrom-Json
        Assert-True 'stale computer smoke is not accepted as ready' ($summary.computerUse.ok -eq $false -and $summary.computerUse.reachable -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke records stale age' ($summary.computerUse.stale -eq $true -and [double]$summary.computerUse.ageMinutes -ge 60) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke requests rerun' ([string]$summary.computerUse.nextAction -eq 'rerun_computer_use_lightweight_smoke') "summary=$($summary | ConvertTo-Json -Compress)"
        $nextActions = Get-Content -Raw -LiteralPath $summary.artifacts.nextActions | ConvertFrom-Json
        $computerAction = $nextActions.actions | Where-Object { $_.source -eq 'computer_use' } | Select-Object -First 1
        Assert-True 'stale computer smoke stays out of primary next actions by default' ($null -eq $computerAction) "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        $computerCommand = $commandPacket.commands | Where-Object { $_.lane -eq 'computer_use' } | Select-Object -First 1
        Assert-True 'stale computer smoke command packet omits computer use lane by default' ($null -eq $computerCommand) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $commandPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacketMarkdown
        Assert-True 'stale computer smoke command packet markdown omits computer use lane by default' (-not ($commandPacketMarkdown -match 'lane=computer_use')) "commandPacketMarkdown=$commandPacketMarkdown"
        Assert-True 'stale computer smoke command packet markdown is secret safe' (-not ($commandPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "commandPacketMarkdown=$commandPacketMarkdown"
        $collectionPacket = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'stale computer smoke collection packet includes computer use lane' ($null -ne $collectionPacket.computerUse) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet records evidence needed' ([string]$collectionPacket.computerUse.decision -eq 'evidence_needed') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet names node repl tool' ([string]$collectionPacket.computerUse.tool -eq 'mcp__node_repl.js') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet keeps output path relative' ([string]$collectionPacket.computerUse.outputPath -eq 'var/codex-smoke/computer-use-smoke.json') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet keeps raw app names disabled' ($collectionPacket.computerUse.storesRawAppNames -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet records rerun action' ($collectionPacket.computerUse.nextActions -contains 'rerun_computer_use_lightweight_smoke') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale computer smoke collection packet is secret safe' (-not (($collectionPacket | ConvertTo-Json -Depth 50) -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        $collectionPacketMarkdown = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacketMarkdown
        Assert-Contains 'stale computer smoke collection packet markdown records computer use tool' $collectionPacketMarkdown 'computerUse.tool=mcp__node_repl.js'
        Assert-Contains 'stale computer smoke collection packet markdown records computer use output path' $collectionPacketMarkdown 'computerUse.outputPath=var/codex-smoke/computer-use-smoke.json'
        Assert-Contains 'stale computer smoke collection packet markdown records raw app names guard' $collectionPacketMarkdown 'computerUse.storesRawAppNames=False'
        Assert-Contains 'stale computer smoke collection packet markdown records window-title guard' $collectionPacketMarkdown 'computerUse.storesWindowTitles=False'
        Assert-Contains 'stale computer smoke collection packet markdown records helper count-only proof' $collectionPacketMarkdown 'computerUse.helperCountOnly=True'
        Assert-Contains 'stale computer smoke collection packet markdown records probe schema' $collectionPacketMarkdown 'computerUse.probeSchemaVersion=awx.local.computer_use_count_probe.v1'
        Assert-Contains 'stale computer smoke collection packet markdown records app count' $collectionPacketMarkdown 'computerUse.appCount=3'
        Assert-Contains 'stale computer smoke collection packet markdown records running count' $collectionPacketMarkdown 'computerUse.runningCount=2'
        Assert-Contains 'stale computer smoke collection packet markdown records window count' $collectionPacketMarkdown 'computerUse.windowCount=4'
        Assert-Contains 'stale computer smoke collection packet markdown records secret count' $collectionPacketMarkdown 'computerUse.secretHits=0'
        Assert-True 'stale computer smoke collection packet markdown is secret safe' (-not ($collectionPacketMarkdown -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "collectionPacketMarkdown=$collectionPacketMarkdown"
        $latestPath = Join-Path $computerStaleRoot 'var\codex-smoke\goal-next-auto.latest.json'
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'stale latest pointer carries full stale computer smoke summary' ($latest.computerUse.stale -eq $true -and [string]$latest.computerUse.nextAction -eq 'rerun_computer_use_lightweight_smoke') "latest=$($latest | ConvertTo-Json -Compress)"
    }
    $computerStaleUiProofOutput = Join-Path $computerStaleRoot 'ui-proof-out'
    $computerStaleUiProof = Invoke-Captured -Arguments @('-File', $script, '-Root', $computerStaleRoot, '-OutputDir', $computerStaleUiProofOutput, '-RequireUiProof')
    Assert-True 'stale computer smoke UI proof mode exits evidence_needed' ($computerStaleUiProof.ExitCode -eq 2) "expected exit 2; output=$($computerStaleUiProof.Output)"
    $computerStaleUiProofSummaryPath = Join-Path $computerStaleUiProofOutput 'goal-next-auto.summary.json'
    Assert-True 'stale computer smoke UI proof mode writes summary' (Test-Path $computerStaleUiProofSummaryPath) "missing stale computer ui proof summary"
    if (Test-Path $computerStaleUiProofSummaryPath) {
        $uiProofSummary = Get-Content -Raw -LiteralPath $computerStaleUiProofSummaryPath | ConvertFrom-Json
        $uiProofNextActions = Get-Content -Raw -LiteralPath $uiProofSummary.artifacts.nextActions | ConvertFrom-Json
        $uiProofComputerAction = $uiProofNextActions.actions | Where-Object { $_.source -eq 'computer_use' } | Select-Object -First 1
        Assert-True 'stale computer smoke UI proof mode adds computer next action' (
            $null -ne $uiProofComputerAction -and
            [string]$uiProofComputerAction.action -eq 'rerun_computer_use_lightweight_smoke'
        ) "nextActions=$($uiProofNextActions | ConvertTo-Json -Compress)"
        $uiProofCommandPacket = Get-Content -Raw -LiteralPath $uiProofSummary.artifacts.commandPacket | ConvertFrom-Json
        $uiProofComputerCommand = $uiProofCommandPacket.commands | Where-Object { $_.lane -eq 'computer_use' } | Select-Object -First 1
        Assert-True 'stale computer smoke UI proof mode includes computer command lane' ($null -ne $uiProofComputerCommand) "commandPacket=$($uiProofCommandPacket | ConvertTo-Json -Compress)"
    }

    $browserStaleRoot = New-FakeGoalRoot -Mode 'browserstale'
    $browserStaleOutput = Join-Path $browserStaleRoot 'out'
    $browserStale = Invoke-Captured -Arguments @('-File', $script, '-Root', $browserStaleRoot, '-OutputDir', $browserStaleOutput)
    Assert-True 'stale browser smoke remains supporting by default' ($browserStale.ExitCode -eq 0) "expected exit 0; output=$($browserStale.Output)"
    $browserStaleSummaryPath = Join-Path $browserStaleOutput 'goal-next-auto.summary.json'
    Assert-True 'stale browser smoke writes summary' (Test-Path $browserStaleSummaryPath) "missing stale browser summary at $browserStaleSummaryPath"
    if (Test-Path $browserStaleSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $browserStaleSummaryPath | ConvertFrom-Json
        Assert-True 'stale browser smoke is not accepted as ready' ($summary.browserUse.ok -eq $false -and $summary.browserUse.reachable -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke records stale age' ($summary.browserUse.stale -eq $true -and [double]$summary.browserUse.ageMinutes -ge 60) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke requests rerun' ([string]$summary.browserUse.nextAction -eq 'rerun_browser_local_ui_smoke') "summary=$($summary | ConvertTo-Json -Compress)"
        $nextActions = Get-Content -Raw -LiteralPath $summary.artifacts.nextActions | ConvertFrom-Json
        $browserAction = $nextActions.actions | Where-Object { $_.source -eq 'browser_use' } | Select-Object -First 1
        Assert-True 'stale browser smoke stays out of primary next actions by default' ($null -eq $browserAction) "nextActions=$($nextActions | ConvertTo-Json -Compress)"
        $commandPacket = Get-Content -Raw -LiteralPath $summary.artifacts.commandPacket | ConvertFrom-Json
        $browserCommand = $commandPacket.commands | Where-Object { $_.lane -eq 'browser_use' } | Select-Object -First 1
        Assert-True 'stale browser smoke command packet omits browser lane by default' ($null -eq $browserCommand) "commandPacket=$($commandPacket | ConvertTo-Json -Compress)"
        $collectionPacket = Get-Content -Raw -LiteralPath $summary.artifacts.collectionPacket | ConvertFrom-Json
        Assert-True 'stale browser smoke collection packet includes browser use lane' ($null -ne $collectionPacket.browserUse) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet records evidence needed' ([string]$collectionPacket.browserUse.decision -eq 'evidence_needed') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet names browser tool' ([string]$collectionPacket.browserUse.tool -eq 'browser.control-in-app-browser') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet keeps output path relative' ([string]$collectionPacket.browserUse.outputPath -eq 'var/codex-smoke/browser-ui-smoke.json') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet keeps raw fields disabled' ($collectionPacket.browserUse.storesRawUrl -eq $false -and $collectionPacket.browserUse.storesScreenshotPath -eq $false) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet records rerun action' ($collectionPacket.browserUse.nextActions -contains 'rerun_browser_local_ui_smoke') "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        Assert-True 'stale browser smoke collection packet is secret safe' (-not (($collectionPacket.browserUse | ConvertTo-Json -Depth 20) -match 'http://|https://|Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|[A-Za-z]:\\')) "collectionPacket=$($collectionPacket | ConvertTo-Json -Compress)"
        $latestPath = Join-Path $browserStaleRoot 'var\codex-smoke\goal-next-auto.latest.json'
        $latest = Get-Content -Raw -LiteralPath $latestPath | ConvertFrom-Json
        Assert-True 'stale latest pointer carries full stale browser smoke summary' ($latest.browserUse.stale -eq $true -and [string]$latest.browserUse.nextAction -eq 'rerun_browser_local_ui_smoke') "latest=$($latest | ConvertTo-Json -Compress)"
    }
    $browserStaleUiProofOutput = Join-Path $browserStaleRoot 'ui-proof-out'
    $browserStaleUiProof = Invoke-Captured -Arguments @('-File', $script, '-Root', $browserStaleRoot, '-OutputDir', $browserStaleUiProofOutput, '-RequireUiProof')
    Assert-True 'stale browser smoke UI proof mode exits evidence_needed' ($browserStaleUiProof.ExitCode -eq 2) "expected exit 2; output=$($browserStaleUiProof.Output)"
    $browserStaleUiProofSummaryPath = Join-Path $browserStaleUiProofOutput 'goal-next-auto.summary.json'
    Assert-True 'stale browser smoke UI proof mode writes summary' (Test-Path $browserStaleUiProofSummaryPath) "missing stale browser ui proof summary"
    if (Test-Path $browserStaleUiProofSummaryPath) {
        $uiProofSummary = Get-Content -Raw -LiteralPath $browserStaleUiProofSummaryPath | ConvertFrom-Json
        $uiProofNextActions = Get-Content -Raw -LiteralPath $uiProofSummary.artifacts.nextActions | ConvertFrom-Json
        $uiProofBrowserAction = $uiProofNextActions.actions | Where-Object { $_.source -eq 'browser_use' } | Select-Object -First 1
        Assert-True 'stale browser smoke UI proof mode adds browser next action' (
            $null -ne $uiProofBrowserAction -and
            [string]$uiProofBrowserAction.action -eq 'rerun_browser_local_ui_smoke'
        ) "nextActions=$($uiProofNextActions | ConvertTo-Json -Compress)"
        $uiProofCommandPacket = Get-Content -Raw -LiteralPath $uiProofSummary.artifacts.commandPacket | ConvertFrom-Json
        $uiProofBrowserCommand = $uiProofCommandPacket.commands | Where-Object { $_.lane -eq 'browser_use' } | Select-Object -First 1
        Assert-True 'stale browser smoke UI proof mode includes browser command lane' ($null -ne $uiProofBrowserCommand) "commandPacket=$($uiProofCommandPacket | ConvertTo-Json -Compress)"
    }

    $proofStripRoot = New-FakeGoalRoot -Mode 'partial'
    $proofGeneratedAt = [DateTime]::UtcNow.ToString('o')
    Set-TestFile (Join-Path $proofStripRoot 'var\codex-smoke\computer-use-smoke.json') @"
{
  "ok": true,
  "decision": "ok",
  "checkedAt": "$proofGeneratedAt",
  "appCount": 42,
  "runningCount": 22,
  "windowCount": 147,
  "rawSecretPatternHits": []
}
"@
    Set-TestFile (Join-Path $proofStripRoot 'var\codex-smoke\browser-ui-smoke.json') @"
{
  "checkedAt": "$proofGeneratedAt",
  "proofRootPresent": true,
  "proofCellCount": 6,
  "proofNames": "local,browser,computer,supabase,producer,action",
  "flowStepCount": 6,
  "missionAxisCount": 5,
  "cockpitCellCount": 5,
  "matrixCellCount": 4,
  "heartbeatFieldCount": 29,
  "allDetailCellCount": 51,
  "detailLeakHits": [],
  "rawSecretPatternHits": []
}
"@
    $proofStripOutput = Join-Path $proofStripRoot 'out'
    $proofStrip = Invoke-Captured -Arguments @('-File', $script, '-Root', $proofStripRoot, '-OutputDir', $proofStripOutput)
    Assert-True 'proof-strip smoke goal next exits zero without parser error' ($proofStrip.ExitCode -eq 0) "expected exit 0; output=$($proofStrip.Output)"
    $proofStripSummaryPath = Join-Path $proofStripOutput 'goal-next-auto.summary.json'
    Assert-True 'proof-strip smoke writes summary' (Test-Path $proofStripSummaryPath) "missing proof-strip summary at $proofStripSummaryPath"
    if (Test-Path $proofStripSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $proofStripSummaryPath | ConvertFrom-Json
        Assert-True 'proof-strip computer smoke accepts checkedAt timestamp' ($summary.computerUse.ok -eq $true -and $summary.computerUse.reachable -eq $true -and $summary.computerUse.stale -eq $false -and [int]$summary.computerUse.appCount -eq 42 -and [int]$summary.computerUse.rawSecretPatternHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'proof-strip browser smoke infers visible localhost proof' ($summary.browserUse.ok -eq $true -and $summary.browserUse.reachable -eq $true -and $summary.browserUse.localhost -eq $true -and $summary.browserUse.screenshotCaptured -eq $true -and $summary.browserUse.targetContentVisible -eq $true -and $summary.browserUse.stale -eq $false -and [string]$summary.browserUse.statusClass -eq 'proof_strip_visible' -and [int]$summary.browserUse.rawSecretPatternHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $completeRoot = New-FakeGoalRoot -Mode 'complete'
    $completeOutput = Join-Path $completeRoot 'out'
    $complete = Invoke-Captured -Arguments @('-File', $script, '-Root', $completeRoot, '-OutputDir', $completeOutput)
    Assert-True 'complete goal next exits zero' ($complete.ExitCode -eq 0) "expected exit 0; output=$($complete.Output)"
    Assert-Contains 'complete goal next reports ok' $complete.Output 'decision=ok'
    Assert-Contains 'complete goal next ran desktop control loop' $complete.Output 'desktopControlLoopExit=0'
    $completeSummaryPath = Join-Path $completeOutput 'goal-next-auto.summary.json'
    Assert-True 'complete goal next writes summary' (Test-Path $completeSummaryPath) 'missing complete summary'
    if (Test-Path $completeSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $completeSummaryPath | ConvertFrom-Json
        Assert-True 'complete summary reports ok' ($summary.ok -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records git root as string' ($summary.preflight.gitRoot -is [string]) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records desktop control loop exit' ([int]$summary.desktopControlLoopExit -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records desktop local readiness' ($summary.desktopControlLoop.localReady -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary records desktop completion readiness' ($summary.desktopControlLoop.completionReady -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary parses supabase apply summary' ($summary.supabaseApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete default keeps Supabase proof optional without executing it' (
            $summary.supabaseSmoke.processExecuted -eq $false -and
            $summary.supabaseApply.processExecuted -eq $false -and
            $summary.supabaseApply.decision -eq 'evidence_needed'
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary parses external apply summary' ($summary.externalApply.parsed -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete default keeps external producer proof optional without executing it' (
            $summary.externalApply.processExecuted -eq $false -and
            $summary.externalApply.decision -eq 'supporting_evidence_missing' -and
            $summary.externalApply.producerBundlesRequired -eq $false
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary has no secret hits' ([int]$summary.secretHits -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $patchdropRoot = New-FakeGoalRoot -Mode 'patchdrop'
    $patchdropOutput = Join-Path $patchdropRoot 'out'
    $patchdrop = Invoke-Captured -Arguments @('-File', $script, '-Root', $patchdropRoot, '-OutputDir', $patchdropOutput)
    Assert-True 'patchdrop-pending goal next exits evidence_needed' ($patchdrop.ExitCode -eq 2) "expected exit 2; output=$($patchdrop.Output)"
    Assert-Contains 'patchdrop-pending goal next names classifier' $patchdrop.Output 'decision=evidence_needed'
    $patchdropSummaryPath = Join-Path $patchdropOutput 'goal-next-auto.summary.json'
    Assert-True 'patchdrop-pending goal next writes summary' (Test-Path $patchdropSummaryPath) 'missing patchdrop summary'
    if (Test-Path $patchdropSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $patchdropSummaryPath | ConvertFrom-Json
        Assert-True 'patchdrop summary reports evidence_needed' ($summary.decision -eq 'evidence_needed') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary reports not ok' ($summary.ok -eq $false) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary records pending patch count' ([int]$summary.preflight.patchDropPendingPatchCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary records failure classifier' ($summary.preflight.failureClassification -eq 'patch-drop-pending') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'patchdrop summary records safe pending patch name only' (($summary.preflight.patchDropPendingPatchNames -join ',') -eq 'pending-safe.patch') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $leaseHeldRoot = New-FakeGoalRoot -Mode 'leaseheld'
    $leaseHeldOutput = Join-Path $leaseHeldRoot 'out'
    $leaseHeld = Invoke-Captured -Arguments @('-File', $script, '-Root', $leaseHeldRoot, '-OutputDir', $leaseHeldOutput)
    Assert-True 'source lease held goal next exits evidence_needed' ($leaseHeld.ExitCode -eq 2) "expected exit 2; output=$($leaseHeld.Output)"
    Assert-Contains 'source lease held goal next reports evidence_needed' $leaseHeld.Output 'decision=evidence_needed'
    $leaseHeldSummaryPath = Join-Path $leaseHeldOutput 'goal-next-auto.summary.json'
    Assert-True 'source lease held goal next writes summary' (Test-Path $leaseHeldSummaryPath) 'missing source lease held summary'
    if (Test-Path $leaseHeldSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $leaseHeldSummaryPath | ConvertFrom-Json
        Assert-True 'source lease held summary records active count' ([int]$summary.preflight.sourceLeaseActiveCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease held summary records corrupt count zero' ([int]$summary.preflight.sourceLeaseCorruptCount -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease held summary records failure classifier' ($summary.preflight.failureClassification -eq 'source-edit-lease-held') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease held summary records topic only' (($summary.preflight.sourceLeaseActiveTopics -join ',') -eq 'active-topic') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $leaseCorruptRoot = New-FakeGoalRoot -Mode 'leasecorrupt'
    $leaseCorruptOutput = Join-Path $leaseCorruptRoot 'out'
    $leaseCorrupt = Invoke-Captured -Arguments @('-File', $script, '-Root', $leaseCorruptRoot, '-OutputDir', $leaseCorruptOutput)
    Assert-True 'source lease corrupt goal next exits evidence_needed' ($leaseCorrupt.ExitCode -eq 2) "expected exit 2; output=$($leaseCorrupt.Output)"
    Assert-Contains 'source lease corrupt goal next reports evidence_needed' $leaseCorrupt.Output 'decision=evidence_needed'
    $leaseCorruptSummaryPath = Join-Path $leaseCorruptOutput 'goal-next-auto.summary.json'
    Assert-True 'source lease corrupt goal next writes summary' (Test-Path $leaseCorruptSummaryPath) 'missing source lease corrupt summary'
    if (Test-Path $leaseCorruptSummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $leaseCorruptSummaryPath | ConvertFrom-Json
        Assert-True 'source lease corrupt summary records active count zero' ([int]$summary.preflight.sourceLeaseActiveCount -eq 0) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease corrupt summary records corrupt count' ([int]$summary.preflight.sourceLeaseCorruptCount -eq 1) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease corrupt summary records failure classifier' ($summary.preflight.failureClassification -eq 'source-edit-lease-corrupt') "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'source lease corrupt summary records topic only' (($summary.preflight.sourceLeaseCorruptTopics -join ',') -eq 'corrupt-topic') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $leaseExpiredLegacyRoot = New-FakeGoalRoot -Mode 'leaseexpiredlegacy'
    $leaseExpiredLegacyOutput = Join-Path $leaseExpiredLegacyRoot 'out'
    $leaseExpiredLegacy = Invoke-Captured -Arguments @('-File', $script, '-Root', $leaseExpiredLegacyRoot, '-OutputDir', $leaseExpiredLegacyOutput)
    Assert-True 'legacy expired source lease does not create a preflight hard stop' ($leaseExpiredLegacy.ExitCode -eq 0) "expected exit 0; output=$($leaseExpiredLegacy.Output)"
    $leaseExpiredLegacySummaryPath = Join-Path $leaseExpiredLegacyOutput 'goal-next-auto.summary.json'
    Assert-True 'legacy expired source lease writes summary' (Test-Path $leaseExpiredLegacySummaryPath) 'missing legacy expired lease summary'
    if (Test-Path $leaseExpiredLegacySummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $leaseExpiredLegacySummaryPath | ConvertFrom-Json
        Assert-True 'legacy expired source lease is observable but nonblocking' (
            [int]$summary.preflight.sourceLeaseActiveCount -eq 0 -and
            [int]$summary.preflight.sourceLeaseCorruptCount -eq 0 -and
            [int]$summary.preflight.sourceLeaseExpiredCount -eq 1 -and
            [string]::IsNullOrWhiteSpace([string]$summary.preflight.failureClassification)
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'legacy expired source lease records topic only' (($summary.preflight.sourceLeaseExpiredTopics -join ',') -eq 'expired-topic') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $leaseMissingExpiryRoot = New-FakeGoalRoot -Mode 'leasemissingexpiry'
    $leaseMissingExpiryOutput = Join-Path $leaseMissingExpiryRoot 'out'
    $leaseMissingExpiry = Invoke-Captured -Arguments @('-File', $script, '-Root', $leaseMissingExpiryRoot, '-OutputDir', $leaseMissingExpiryOutput)
    Assert-True 'missing-expiry source lease exits evidence_needed' ($leaseMissingExpiry.ExitCode -eq 2) "expected exit 2; output=$($leaseMissingExpiry.Output)"
    $leaseMissingExpirySummaryPath = Join-Path $leaseMissingExpiryOutput 'goal-next-auto.summary.json'
    Assert-True 'missing-expiry source lease writes summary' (Test-Path $leaseMissingExpirySummaryPath) 'missing missing-expiry lease summary'
    if (Test-Path $leaseMissingExpirySummaryPath) {
        $summary = Get-Content -Raw -LiteralPath $leaseMissingExpirySummaryPath | ConvertFrom-Json
        Assert-True 'missing-expiry source lease fails closed as corrupt' (
            [int]$summary.preflight.sourceLeaseActiveCount -eq 0 -and
            [int]$summary.preflight.sourceLeaseCorruptCount -eq 1 -and
            [int]$summary.preflight.sourceLeaseExpiredCount -eq 0 -and
            [string]$summary.preflight.failureClassification -eq 'source-edit-lease-corrupt'
        ) "summary=$($summary | ConvertTo-Json -Compress)"
        Assert-True 'missing-expiry source lease records topic only' (($summary.preflight.sourceLeaseCorruptTopics -join ',') -eq 'missing-expiry-topic') "summary=$($summary | ConvertTo-Json -Compress)"
    }

    $secretRoot = New-FakeGoalRoot -Mode 'secret'
    $secretOutput = Join-Path $secretRoot 'out'
    $leakProbe = Invoke-Captured -Arguments @('-File', $script, '-Root', $secretRoot, '-OutputDir', $secretOutput, '-RequireSupabaseProof')
    Assert-True 'secret goal next exits leak risk' ($leakProbe.ExitCode -eq 4) "expected exit 4; output=$($leakProbe.Output)"
    Assert-Contains 'secret goal next names classifier' $leakProbe.Output 'decision=secret-leak-risk'
    $secretLog = Join-Path $secretOutput 'supabase-apply.log'
    $bearerPattern = 'Bearer' + '\s+' + '[A-Za-z0-9._~+/-]' + '+=*'
    Assert-True 'secret child log is redacted' ((Test-Path $secretLog) -and -not ((Get-Content -Raw -LiteralPath $secretLog) -match $bearerPattern)) 'raw auth token leaked into child log'
} catch {
    $failures++
    Write-Host $_.Exception.Message
} finally {
    if ($failures -gt 0) {
        Write-Host "[goal-next-test][SUMMARY] failed=$failures"
        exit 1
    }
    Write-Host '[goal-next-test][SUMMARY] failed=0'
}

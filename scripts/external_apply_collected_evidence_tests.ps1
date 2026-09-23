[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "[external-apply-test][FAIL] $Name :: $Message"
    }
    Write-Host "[external-apply-test][PASS] $Name"
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected output to contain '$Needle'; output=$Text"
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

function Invoke-ExternalHelp {
    param([Parameter(Mandatory = $true)][string]$ScriptPath)
    return Invoke-Captured -Arguments @('-File', $ScriptPath, '-Help')
}

function New-FakeRepo {
    param(
        [string]$Mode,
        [switch]$OmitSourceEvidenceDir
    )
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-external-apply-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'scripts') | Out-Null
    if (-not $OmitSourceEvidenceDir) {
        New-Item -ItemType Directory -Force -Path (Join-Path $root '__patch_drop__\external-node-proof') | Out-Null
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'data\agent-handoff\mcp-control-tower') | Out-Null

    Set-TestFile (Join-Path $root 'scripts\awx_mcp_toolbox.ps1') @'
param(
    [Parameter(Mandatory = $true)][string]$Tool,
    [string]$Root = "",
    [string]$InputJson = ""
)
$payload = $InputJson | ConvertFrom-Json
$payload | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $Root ("toolbox-" + $Tool + "-input.json")) -Encoding UTF8
$mode = Get-Content -Raw -LiteralPath (Join-Path $Root "mode.txt")
if ($Tool -eq "external_evidence_intake") {
    if ($mode.Trim() -eq "complete" -or $mode.Trim() -eq "audit-incomplete") {
        [ordered]@{
            ok = $true
            externalEvidenceComplete = $true
            decision = "external_evidence_intake"
            rawSecretPatternHits = 0
            intakeSummary = [ordered]@{
                copiedEvidenceCount = 2
                copiedHandoffCount = 2
                rejectedEvidenceCount = 0
                rejectedHandoffCount = 0
                rawSecretPatternHits = 0
            }
            evidence_needed = @()
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
    if ($mode.Trim() -eq "secret") {
        [ordered]@{
            ok = $false
            externalEvidenceComplete = $false
            decision = "external_evidence_incomplete"
            rawSecretPatternHits = 1
            intakeSummary = [ordered]@{ rawSecretPatternHits = 1; copiedEvidenceCount = 0; copiedHandoffCount = 0 }
            evidence_needed = @("external node smoke rawSecretPatternHits > 0")
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
    [ordered]@{
        ok = $false
        externalEvidenceComplete = $false
        decision = "external_evidence_incomplete"
        rawSecretPatternHits = 0
        intakeSummary = [ordered]@{ copiedEvidenceCount = 0; copiedHandoffCount = 0; rejectedEvidenceCount = 1; rejectedHandoffCount = 1; rawSecretPatternHits = 0 }
        evidence_needed = @(
            "external node smoke missing role=macmini",
            "external node smoke missing role=notebook",
            "producer bundle missing-or-invalid role=macmini topic=mcp-control-loop reason=producer-sidecars-missing:manifest,patch,pendingNotice,report,sha256,verifyLog"
        )
    } | ConvertTo-Json -Depth 20 -Compress
    exit 0
}
if ($Tool -eq "external_evidence_audit") {
    if ($mode.Trim() -eq "complete" -or $mode.Trim() -eq "audit-incomplete") {
        [ordered]@{
            ok = $true
            externalEvidenceComplete = $true
            decision = "external_evidence_audit"
            rawSecretPatternHits = 0
            outputCount = 2
            evidence_needed = @()
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
    if ($mode.Trim() -eq "secret") {
        [ordered]@{
            ok = $false
            externalEvidenceComplete = $false
            decision = "external_evidence_incomplete"
            rawSecretPatternHits = 1
            outputCount = 0
            evidence_needed = @("external node smoke rawSecretPatternHits > 0")
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
    [ordered]@{
        ok = $false
        externalEvidenceComplete = $false
        decision = "external_evidence_incomplete"
        rawSecretPatternHits = 0
        outputCount = 0
        evidence_needed = @(
            "external node smoke missing role=macmini",
            "external node smoke missing role=notebook",
            "producer bundle missing-or-invalid role=notebook topic=mcp-control-loop reason=producer-sidecars-missing:manifest,patch,pendingNotice,report,sha256,verifyLog"
        )
    } | ConvertTo-Json -Depth 20 -Compress
    exit 0
}
throw "unexpected tool $Tool"
'@
    Set-TestFile (Join-Path $root 'scripts\awx_mcp_completion_audit.py') @'
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
if (root / "completion-audit-incomplete.flag").exists():
    print(json.dumps({"ok": False, "status": "local_control_tower_incomplete", "externalEvidenceComplete": True, "rawSecretPatternHits": 0, "evidence_needed": ["goal-next.command-packet missing external proof evidence"]}))
    raise SystemExit(1)
print(json.dumps({"ok": True, "status": "local_control_tower_ready", "externalEvidenceComplete": True, "rawSecretPatternHits": 0, "evidence_needed": []}))
'@
    Set-TestFile (Join-Path $root 'mode.txt') $Mode
    if ($Mode -eq 'audit-incomplete') {
        Set-TestFile (Join-Path $root 'completion-audit-incomplete.flag') '1'
    }
    return $root
}

$script = Join-Path $PSScriptRoot 'external_apply_collected_evidence.ps1'
$failures = 0

try {
    $help = Invoke-ExternalHelp -ScriptPath $script
    Assert-True 'external apply help exits zero' ($help.ExitCode -eq 0) "expected exit 0; output=$($help.Output)"
    Assert-Contains 'external apply help names Mac mini role' $help.Output 'macmini'
    Assert-Contains 'external apply help names Notebook role' $help.Output 'notebook'
    Assert-Contains 'external apply help names source evidence dir' $help.Output '__patch_drop__\external-node-proof'
    Assert-Contains 'external apply help names node smoke artifact' $help.Output 'macmini-node-smoke.json'
    Assert-Contains 'external apply help names sidecar manifest' $help.Output '.manifest.json'
    Assert-Contains 'external apply help names audit command' $help.Output 'external_evidence_audit'
    Assert-Contains 'external apply help names internal goal-next ownership mode' $help.Output 'OrchestratedByGoalNext'
    Assert-True 'external apply help prints no raw auth header' (-not ($help.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($help.Output)"

    $partialRoot = New-FakeRepo -Mode 'partial' -OmitSourceEvidenceDir
    $partial = Invoke-Captured -Arguments @('-File', $script, '-Root', $partialRoot, '-Topic', 'mcp-control-loop')
    Assert-True 'partial import exits evidence_needed' ($partial.ExitCode -eq 2) "expected exit 2; output=$($partial.Output)"
    Assert-Contains 'partial output names evidence_needed' $partial.Output 'evidence_needed'
    $partialSummaryPath = Join-Path $partialRoot 'var\codex-smoke\external-apply-collected-evidence\external-apply-collected.summary.json'
    Assert-True 'partial import writes summary artifact' (Test-Path $partialSummaryPath) 'missing partial summary artifact'
    if (Test-Path $partialSummaryPath) {
        $partialSummary = Get-Content -Raw -LiteralPath $partialSummaryPath | ConvertFrom-Json
        Assert-True 'partial summary reports evidence_needed' ($partialSummary.decision -eq 'evidence_needed') "summary=$($partialSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial summary reports not ok' ($partialSummary.ok -eq $false) "summary=$($partialSummary | ConvertTo-Json -Compress)"
        Assert-True 'partial run creates source evidence inbox' ($partialSummary.sourceEvidenceDirPresent -eq $true) "summary=$($partialSummary | ConvertTo-Json -Compress)"
        $partialSummaryText = $partialSummary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'partial summary records bounded evidence-needed list' (@($partialSummary.evidenceNeeded).Count -ge 4) "summary=$partialSummaryText"
        Assert-Contains 'partial summary names missing macmini smoke' $partialSummaryText 'intake:external node smoke missing role=macmini'
        Assert-Contains 'partial summary names missing notebook smoke' $partialSummaryText 'audit:external node smoke missing role=notebook'
        Assert-Contains 'partial summary names macmini sidecar gap' $partialSummaryText 'producer-sidecars-missing:manifest,patch,pendingNotice,report,sha256,verifyLog'
        Assert-Contains 'partial summary lists required patch body sidecar' $partialSummaryText '.patch'
        Assert-Contains 'partial summary lists required manifest sidecar' $partialSummaryText '.manifest.json'
        Assert-Contains 'partial summary lists required pending notice sidecar' $partialSummaryText 'pendingNotice'
        Assert-Contains 'partial summary lists required Mac mini smoke file' $partialSummaryText 'macmini-node-smoke.json'
        Assert-Contains 'partial summary lists required Notebook handoff file' $partialSummaryText 'notebook-producer-handoff.json'
        Assert-Contains 'partial summary requires source isolation PASS' $partialSummaryText '"guard":"PASS"'
        Assert-Contains 'partial summary requires producer local worktree' $partialSummaryText '"sourceRootKind":"local-worktree"'
        Assert-Contains 'partial summary keeps Desktop proof pending' $partialSummaryText '"desktopFinalProof":"evidence_needed"'
        Assert-Contains 'partial summary includes Mac mini next action' $partialSummaryText 'run_macmini_external_node_smoke'
        Assert-Contains 'partial summary includes Notebook next action' $partialSummaryText 'submit_notebook_patchdrop_v3_bundle_sidecars'
        Assert-Contains 'partial summary includes apply command' $partialSummaryText 'scripts\\external_apply_collected_evidence.ps1 -Root . -Topic mcp-control-loop'
        Assert-True 'partial summary evidence list is secret safe' (-not ($partialSummaryText -match 'Bearer\s+|jdbc:|sb_(?:secret|publishable)_|sbp_|sk-[A-Za-z0-9_-]{20,}')) "summary=$partialSummaryText"
    }

    $optionalRoot = New-FakeRepo -Mode 'partial' -OmitSourceEvidenceDir
    $optional = Invoke-Captured -Arguments @('-File', $script, '-Root', $optionalRoot, '-Topic', 'mcp-control-loop', '-NoRequireProducerBundles')
    Assert-True 'optional producer import exits zero' ($optional.ExitCode -eq 0) "expected exit 0; output=$($optional.Output)"
    Assert-Contains 'optional output names supporting evidence' $optional.Output 'supporting_evidence_missing'
    $optionalSummaryPath = Join-Path $optionalRoot 'var\codex-smoke\external-apply-collected-evidence\external-apply-collected.summary.json'
    Assert-True 'optional import writes summary artifact' (Test-Path $optionalSummaryPath) 'missing optional summary artifact'
    if (Test-Path $optionalSummaryPath) {
        $optionalSummary = Get-Content -Raw -LiteralPath $optionalSummaryPath | ConvertFrom-Json
        $optionalSummaryText = $optionalSummary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'optional summary reports ok' ($optionalSummary.ok -eq $true) "summary=$optionalSummaryText"
        Assert-True 'optional summary reports supporting evidence missing' ($optionalSummary.decision -eq 'supporting_evidence_missing') "summary=$optionalSummaryText"
        Assert-True 'optional summary marks producer bundles optional' ($optionalSummary.producerBundlesRequired -eq $false -and $optionalSummary.externalEvidenceMode -eq 'optional') "summary=$optionalSummaryText"
        Assert-True 'optional summary does not promote external next actions' (@($optionalSummary.nextActions).Count -eq 0) "summary=$optionalSummaryText"
        Assert-Contains 'optional summary keeps supporting action' $optionalSummaryText 'run_macmini_external_node_smoke'
        Assert-Contains 'optional summary keeps sidecar contract' $optionalSummaryText '.manifest.json'
    }

    $orchestratedRoot = New-FakeRepo -Mode 'partial' -OmitSourceEvidenceDir
    $orchestratedOutput = Join-Path $orchestratedRoot 'var\codex-smoke\external-apply-collected-evidence'
    $staleNestedSecret = 'Bearer ' + ('y' * 30)
    Set-TestFile (Join-Path $orchestratedOutput 'awx-mcp-completion-audit.log') "stale-audit-log $staleNestedSecret"
    Set-TestFile (Join-Path $orchestratedOutput 'awx-mcp-completion-audit.result.json') "{`"stale`":true,`"payload`":`"$staleNestedSecret`"}"
    $orchestrated = Invoke-Captured -Arguments @('-File', $script, '-Root', $orchestratedRoot, '-Topic', 'mcp-control-loop', '-NoRequireProducerBundles', '-OrchestratedByGoalNext')
    Assert-True 'goal-next orchestrated external apply keeps optional evidence nonblocking' ($orchestrated.ExitCode -eq 0) "expected exit 0; output=$($orchestrated.Output)"
    Assert-Contains 'goal-next orchestrated external apply defers completion audit explicitly' $orchestrated.Output 'completionAuditStatus=deferred_to_goal_next completionAuditExit=n/a'
    Assert-True 'goal-next orchestrated external apply removes stale audit log' (-not (Test-Path (Join-Path $orchestratedOutput 'awx-mcp-completion-audit.log'))) 'unexpected nested completion audit log'
    Assert-True 'goal-next orchestrated external apply removes stale audit artifact' (-not (Test-Path (Join-Path $orchestratedOutput 'awx-mcp-completion-audit.result.json'))) 'unexpected nested completion audit artifact'
    Assert-True 'goal-next orchestrated external apply does not expose stale nested secret' (-not $orchestrated.Output.Contains($staleNestedSecret)) "output=$($orchestrated.Output)"
    $orchestratedSummaryPath = Join-Path $orchestratedOutput 'external-apply-collected.summary.json'
    Assert-True 'goal-next orchestrated external apply writes summary artifact' (Test-Path $orchestratedSummaryPath) 'missing orchestrated external summary'
    if (Test-Path $orchestratedSummaryPath) {
        $orchestratedSummary = Get-Content -Raw -LiteralPath $orchestratedSummaryPath | ConvertFrom-Json
        $orchestratedSummaryText = $orchestratedSummary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'goal-next orchestrated external summary records single owner' (
            $orchestratedSummary.orchestratedByGoalNext -eq $true -and
            [string]$orchestratedSummary.derivedGateOwner -eq 'goal_next_auto'
        ) "summary=$orchestratedSummaryText"
        Assert-True 'goal-next orchestrated external summary records deferred audit without fake success' (
            [string]$orchestratedSummary.completionAuditStatus -eq 'deferred_to_goal_next' -and
            $null -eq $orchestratedSummary.completionAuditExit
        ) "summary=$orchestratedSummaryText"
        Assert-True 'goal-next orchestrated external summary preserves optional producer evidence' (
            [string]$orchestratedSummary.decision -eq 'supporting_evidence_missing' -and
            $orchestratedSummary.producerBundlesRequired -eq $false
        ) "summary=$orchestratedSummaryText"
    }

    $completeRoot = New-FakeRepo -Mode 'complete'
    $complete = Invoke-Captured -Arguments @('-File', $script, '-Root', $completeRoot, '-Topic', 'mcp-control-loop')
    Assert-True 'complete import exits zero' ($complete.ExitCode -eq 0) "expected exit 0; output=$($complete.Output)"
    Assert-Contains 'complete output reports ok' $complete.Output '[AWX][external][apply-collected] ok=true'
    $completeSummaryPath = Join-Path $completeRoot 'var\codex-smoke\external-apply-collected-evidence\external-apply-collected.summary.json'
    Assert-True 'complete import writes summary artifact' (Test-Path $completeSummaryPath) 'missing complete summary artifact'
    if (Test-Path $completeSummaryPath) {
        $completeSummary = Get-Content -Raw -LiteralPath $completeSummaryPath | ConvertFrom-Json
        Assert-True 'complete summary reports ok' ($completeSummary.ok -eq $true) "summary=$($completeSummary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary reports zero secret hits' ([int]$completeSummary.secretHits -eq 0) "summary=$($completeSummary | ConvertTo-Json -Compress)"
        Assert-True 'complete summary carries audit decision' ($completeSummary.auditDecision -eq 'external_evidence_audit') "summary=$($completeSummary | ConvertTo-Json -Compress)"
    }
    foreach ($tool in @('external_evidence_intake', 'external_evidence_audit')) {
        $payload = Get-Content -Raw -LiteralPath (Join-Path $completeRoot ("toolbox-" + $tool + "-input.json")) | ConvertFrom-Json
        Assert-True "$tool uses absolute patchdrop path" ([System.IO.Path]::IsPathRooted([string]$payload.patchdrop_root)) "patchdrop_root=$($payload.patchdrop_root)"
        Assert-True "$tool uses absolute evidence dir" ([System.IO.Path]::IsPathRooted([string]$payload.evidence_dir)) "evidence_dir=$($payload.evidence_dir)"
        Assert-True "$tool uses absolute source evidence dir" ([System.IO.Path]::IsPathRooted([string]$payload.source_evidence_dir)) "source_evidence_dir=$($payload.source_evidence_dir)"
    }

    $auditIncompleteRoot = New-FakeRepo -Mode 'audit-incomplete'
    $auditIncomplete = Invoke-Captured -Arguments @('-File', $script, '-Root', $auditIncompleteRoot, '-Topic', 'mcp-control-loop')
    Assert-True 'completion audit incomplete exits evidence_needed not verifier failure' ($auditIncomplete.ExitCode -eq 2) "expected exit 2; output=$($auditIncomplete.Output)"
    Assert-Contains 'audit incomplete output names completion audit exit' $auditIncomplete.Output 'completionAuditExit=1'
    Assert-Contains 'audit incomplete output names evidence_needed' $auditIncomplete.Output 'completion audit incomplete'
    $auditIncompleteSummaryPath = Join-Path $auditIncompleteRoot 'var\codex-smoke\external-apply-collected-evidence\external-apply-collected.summary.json'
    Assert-True 'audit incomplete writes summary artifact' (Test-Path $auditIncompleteSummaryPath) 'missing audit incomplete summary'
    if (Test-Path $auditIncompleteSummaryPath) {
        $auditIncompleteSummary = Get-Content -Raw -LiteralPath $auditIncompleteSummaryPath | ConvertFrom-Json
        $auditIncompleteSummaryText = $auditIncompleteSummary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'audit incomplete summary records evidence_needed' ($auditIncompleteSummary.decision -eq 'evidence_needed') "summary=$auditIncompleteSummaryText"
        Assert-True 'audit incomplete summary records completion audit exit' ([int]$auditIncompleteSummary.completionAuditExit -eq 1) "summary=$auditIncompleteSummaryText"
        Assert-Contains 'audit incomplete summary records safe completion audit gap' $auditIncompleteSummaryText 'completion_audit_incomplete'
    }

    $secretRoot = New-FakeRepo -Mode 'secret'
    $secret = Invoke-Captured -Arguments @('-File', $script, '-Root', $secretRoot)
    Assert-True 'secret hit exits leak risk' ($secret.ExitCode -eq 4) "expected exit 4; output=$($secret.Output)"
    Assert-Contains 'secret hit output names classifier' $secret.Output 'secret-leak-risk'
    $secretSummaryPath = Join-Path $secretRoot 'var\codex-smoke\external-apply-collected-evidence\external-apply-collected.summary.json'
    Assert-True 'secret hit writes summary artifact' (Test-Path $secretSummaryPath) 'missing secret summary artifact'
    if (Test-Path $secretSummaryPath) {
        $secretSummary = Get-Content -Raw -LiteralPath $secretSummaryPath | ConvertFrom-Json
        Assert-True 'secret summary reports leak risk' ($secretSummary.decision -eq 'secret-leak-risk') "summary=$($secretSummary | ConvertTo-Json -Compress)"
    }
} catch {
    $failures++
    Write-Host $_.Exception.Message
} finally {
    if ($failures -gt 0) {
        Write-Host "[external-apply-test][SUMMARY] failed=$failures"
        exit 1
    }
    Write-Host '[external-apply-test][SUMMARY] failed=0'
}

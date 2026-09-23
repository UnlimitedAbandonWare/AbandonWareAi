[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$PatchDropRoot = '',

    [string]$SourceEvidenceDir = '',

    [string]$EvidenceDir = '',

    [string]$OutputDir = '',

    [string]$Topic = '',

    [string[]]$RequiredRoles = @('macmini', 'notebook'),

    [switch]$NoRequireProducerBundles,

    [switch]$OrchestratedByGoalNext,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    @'
[AWX][external][apply-collected] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root <repo-root> -Topic mcp-control-loop

Internal orchestration:
  -OrchestratedByGoalNext keeps evidence intake/audit and PatchDrop safety here, then defers the completion audit to goal_next_auto.
  Omit this switch for standalone/manual verification.

Required external evidence before this can close:
  macmini-node-smoke.json and macmini-producer-handoff.json
  notebook-node-smoke.json and notebook-producer-handoff.json
  PatchDrop v3 sidecars for each producer role: .patch, .report.md, .verify.log, .sha256.txt, .manifest.json, pendingNotice
  sourceIsolation.guard=PASS, sourceRootKind=local-worktree, directCanonicalSourceEdit=false

Default paths:
  __patch_drop__\external-node-proof
  data\agent-handoff\mcp-control-tower
  var\codex-smoke\external-apply-collected-evidence

Verification:
  external_evidence_intake
  external_evidence_audit
  awx_mcp_completion_audit.py

Safety:
  Desktop consumes evidence only after sidecar validation
  do not print token values, Authorization headers, cookies, JDBC URLs, or raw secrets
  missing producer evidence remains evidence_needed
'@ | Write-Host
    exit 0
}

function Resolve-RepoRoot {
    param([string]$Candidate)
    if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
        return (Resolve-Path -LiteralPath $Candidate).Path
    }
    $scriptRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
        $scriptRoot = Split-Path -Parent $PSCommandPath
    }
    return (Resolve-Path (Join-Path $scriptRoot '..')).Path
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$PathText
    )
    if ([System.IO.Path]::IsPathRooted($PathText)) {
        return $PathText
    }
    return (Join-Path $ProjectRoot $PathText)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $text = $Value | ConvertTo-Json -Depth 100
    Set-Content -LiteralPath $Path -Value $text -Encoding UTF8
}

function Invoke-ToolboxJson {
    param(
        [Parameter(Mandatory = $true)][string]$Tool,
        [Parameter(Mandatory = $true)][hashtable]$Payload,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $toolbox = Join-Path $ProjectRoot 'scripts\awx_mcp_toolbox.ps1'
    if (-not (Test-Path -LiteralPath $toolbox)) {
        throw "evidence_needed: missing scripts\awx_mcp_toolbox.ps1"
    }
    $json = $Payload | ConvertTo-Json -Depth 60 -Compress
    $raw = & $toolbox -Root $ProjectRoot -Tool $Tool -InputJson $json
    if ($LASTEXITCODE -ne 0) {
        throw "evidence_needed: toolbox failed tool=$Tool exit=$LASTEXITCODE"
    }
    return ($raw -join "`n") | ConvertFrom-Json -ErrorAction Stop
}

function Invoke-PythonCapture {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        throw 'evidence_needed: python missing'
    }
    $lines = & $python.Source $ScriptPath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    $exit = $LASTEXITCODE
    Set-Content -LiteralPath $LogPath -Value ($lines -join "`n") -Encoding UTF8
    return [pscustomobject]@{
        ExitCode = $exit
        Output = ($lines -join "`n")
    }
}

function Count-Pattern {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, $Pattern)).Count
}

function Count-Items {
    param($Value)
    if ($null -eq $Value) {
        return 0
    }
    return @($Value).Count
}

function Add-SafeEvidenceNeededItem {
    param(
        [string[]]$Items,
        [AllowEmptyString()][string]$Source,
        [AllowEmptyString()][string]$Text
    )
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @($Items)
    }
    $safe = ($Text -replace '\s+', ' ').Trim()
    if ($safe -match 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|Bearer\s+|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|(?i)\bjdbc:[A-Za-z0-9_+.-]*://|[A-Za-z]:\\') {
        return @($Items)
    }
    if ($safe.Length -gt 260) {
        $safe = $safe.Substring(0, 260)
    }
    $safeSource = ([string]$Source).Trim()
    if ($safeSource -match '^[A-Za-z0-9_.:-]+$') {
        $safe = "${safeSource}:$safe"
    }
    if (@($Items).Contains($safe) -or @($Items).Count -ge 24) {
        return @($Items)
    }
    return @($Items + $safe)
}

$Root = Resolve-RepoRoot $Root
if ([string]::IsNullOrWhiteSpace($PatchDropRoot)) {
    $PatchDropRoot = '__patch_drop__'
}
if ([string]::IsNullOrWhiteSpace($SourceEvidenceDir)) {
    $SourceEvidenceDir = '__patch_drop__\external-node-proof'
}
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $EvidenceDir = 'data\agent-handoff\mcp-control-tower'
}
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = 'var\codex-smoke\external-apply-collected-evidence'
}

$PatchDropRoot = Resolve-RepoPath -ProjectRoot $Root -PathText $PatchDropRoot
$SourceEvidenceDir = Resolve-RepoPath -ProjectRoot $Root -PathText $SourceEvidenceDir
$EvidenceDir = Resolve-RepoPath -ProjectRoot $Root -PathText $EvidenceDir
$OutputDir = Resolve-RepoPath -ProjectRoot $Root -PathText $OutputDir
New-Item -ItemType Directory -Force -Path $SourceEvidenceDir, $EvidenceDir, $OutputDir | Out-Null

$roles = @($RequiredRoles | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($roles.Count -eq 0) {
    $roles = @('macmini', 'notebook')
}

$payload = @{
    root = $Root
    patchdrop_root = $PatchDropRoot
    source_evidence_dir = $SourceEvidenceDir
    evidence_dir = $EvidenceDir
    required_roles = $roles
    require_producer_bundles = (-not $NoRequireProducerBundles.IsPresent)
}
if (-not [string]::IsNullOrWhiteSpace($Topic)) {
    $payload.topic = $Topic
}

$intake = Invoke-ToolboxJson -Tool 'external_evidence_intake' -Payload $payload -ProjectRoot $Root
$intakePath = Join-Path $OutputDir 'external-evidence-intake.result.json'
Write-JsonFile -Path $intakePath -Value $intake

$audit = Invoke-ToolboxJson -Tool 'external_evidence_audit' -Payload $payload -ProjectRoot $Root
$auditPath = Join-Path $OutputDir 'external-evidence-audit.result.json'
Write-JsonFile -Path $auditPath -Value $audit

$completionAuditScript = Join-Path $Root 'scripts\awx_mcp_completion_audit.py'
$completionAuditLog = Join-Path $OutputDir 'awx-mcp-completion-audit.log'
$completionAuditPath = Join-Path $OutputDir 'awx-mcp-completion-audit.result.json'
$derivedGateOwner = if ($OrchestratedByGoalNext.IsPresent) { 'goal_next_auto' } else { 'external_apply_collected_evidence' }
$completionAuditStatus = if ($OrchestratedByGoalNext.IsPresent) { 'deferred_to_goal_next' } else { 'executed' }
$completionAuditExit = $null
if ($OrchestratedByGoalNext.IsPresent) {
    foreach ($staleDerivedArtifact in @($completionAuditLog, $completionAuditPath)) {
        Remove-Item -LiteralPath $staleDerivedArtifact -Force -ErrorAction SilentlyContinue
    }
} else {
    $completionAudit = Invoke-PythonCapture -ProjectRoot $Root -ScriptPath $completionAuditScript -Arguments @('--root', $Root, '--output', $completionAuditPath) -LogPath $completionAuditLog
    $completionAuditExit = [int]$completionAudit.ExitCode
    if (-not [string]::IsNullOrWhiteSpace($completionAudit.Output)) {
        Set-Content -LiteralPath $completionAuditPath -Value $completionAudit.Output -Encoding UTF8
    }
}

$combined = @()
$combinedPaths = @($intakePath, $auditPath)
if (-not $OrchestratedByGoalNext.IsPresent) {
    $combinedPaths += @($completionAuditLog, $completionAuditPath)
}
foreach ($p in $combinedPaths) {
    if (Test-Path -LiteralPath $p) {
        $combined += Get-Content -Raw -LiteralPath $p
    }
}
$combinedText = $combined -join "`n"
$secretHits = Count-Pattern -Text $combinedText -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._-]+'

$intakeDecision = [string]$intake.decision
$auditDecision = [string]$audit.decision
$intakeComplete = $intake.externalEvidenceComplete -eq $true
$auditComplete = $audit.externalEvidenceComplete -eq $true
$intakeSummary = $intake.intakeSummary
$intakeEvidenceNeededCount = Count-Items $intake.evidence_needed
$auditEvidenceNeededCount = Count-Items $audit.evidence_needed
$safeEvidenceNeeded = @()
foreach ($item in @($intake.evidence_needed)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Source 'intake' -Text ([string]$item)
}
foreach ($item in @($audit.evidence_needed)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Source 'audit' -Text ([string]$item)
}
$rawSecretHits = [int]($intake.rawSecretPatternHits -as [int]) + [int]($audit.rawSecretPatternHits -as [int])
if ($null -ne $intakeSummary -and $null -ne $intakeSummary.rawSecretPatternHits) {
    $rawSecretHits += [int]($intakeSummary.rawSecretPatternHits -as [int])
}
$sourceEvidenceDirPresent = Test-Path -LiteralPath $SourceEvidenceDir
$evidenceDirPresent = Test-Path -LiteralPath $EvidenceDir
$completionAuditIncomplete = (-not $OrchestratedByGoalNext.IsPresent) -and ($completionAuditExit -ne 0)
if ($completionAuditIncomplete) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'completion_audit_incomplete'
}
$externalEvidenceIncomplete = -not $intakeComplete -or -not $auditComplete -or $intakeEvidenceNeededCount -gt 0 -or $auditEvidenceNeededCount -gt 0
$requiredPatchDropSidecars = @(
    'pendingNotice',
    '.manifest.json',
    '.patch',
    '.report.md',
    '.verify.log',
    '.sha256.txt'
)
$requiredProducerEvidenceFiles = @()
$recommendedNextActions = @()
foreach ($role in $roles) {
    $safeRole = ([string]$role).Trim()
    if ([string]::IsNullOrWhiteSpace($safeRole) -or $safeRole -notmatch '^[A-Za-z0-9_.:-]+$') {
        continue
    }
    $requiredProducerEvidenceFiles += "$safeRole-node-smoke.json"
    $requiredProducerEvidenceFiles += "$safeRole-producer-handoff.json"
    $recommendedNextActions += "run_${safeRole}_external_node_smoke"
    $recommendedNextActions += "collect_${safeRole}_producer_handoff_json"
    $recommendedNextActions += "submit_${safeRole}_patchdrop_v3_bundle_sidecars"
}
$recommendedNextActions += @(
    'run_desktop_external_apply_collected_evidence',
    'run_desktop_external_evidence_audit'
)
$producerBundlesRequired = -not $NoRequireProducerBundles.IsPresent
$externalEvidenceMode = if ($producerBundlesRequired) { 'required' } else { 'optional' }
$promotedNextActions = if ($producerBundlesRequired) { @($recommendedNextActions) } else { @() }
$supportingEvidenceNextActions = @($recommendedNextActions)
$applyCommand = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root .'
if (-not [string]::IsNullOrWhiteSpace($Topic)) {
    if ($Topic -match '^[A-Za-z0-9_.:-]+$') {
        $applyCommand = "$applyCommand -Topic $Topic"
    } else {
        $applyCommand = "$applyCommand -Topic <safe-topic>"
    }
}
if (-not $producerBundlesRequired) {
    $applyCommand = "$applyCommand -NoRequireProducerBundles"
}
$decision = 'ok'
if ($secretHits -gt 0 -or $rawSecretHits -gt 0) {
    $decision = 'secret-leak-risk'
} elseif ($completionAuditIncomplete -or $externalEvidenceIncomplete) {
    $decision = if ($producerBundlesRequired) { 'evidence_needed' } else { 'supporting_evidence_missing' }
}
$summaryPath = Join-Path $OutputDir 'external-apply-collected.summary.json'
Write-JsonFile -Path $summaryPath -Value ([ordered]@{
    schemaVersion = 'awx.external.apply_collected_evidence.summary.v1'
    ok = ($decision -eq 'ok' -or $decision -eq 'supporting_evidence_missing')
    decision = $decision
    topic = $Topic
    producerBundlesRequired = $producerBundlesRequired
    externalEvidenceMode = $externalEvidenceMode
    intakeDecision = $intakeDecision
    auditDecision = $auditDecision
    requiredRoles = @($roles)
    requiredProducerEvidenceFiles = @($requiredProducerEvidenceFiles)
    requiredPatchDropSidecars = @($requiredPatchDropSidecars)
    requiredSourceIsolation = [ordered]@{
        guard = 'PASS'
        sourceRootKind = 'local-worktree'
        directCanonicalSourceEdit = $false
        desktopFinalProof = 'evidence_needed'
        rawSecretPatternHits = 0
    }
    nextActions = @($promotedNextActions)
    supportingEvidenceNextActions = @($supportingEvidenceNextActions)
    applyCollectedEvidenceCommand = $applyCommand
    patchDropRootRecommendation = '__patch_drop__'
    sourceEvidenceDirRecommendation = '__patch_drop__\external-node-proof'
    evidenceDirRecommendation = 'data\agent-handoff\mcp-control-tower'
    intakeComplete = $intakeComplete
    auditComplete = $auditComplete
    copiedEvidenceCount = $intakeSummary.copiedEvidenceCount
    copiedHandoffCount = $intakeSummary.copiedHandoffCount
    outputCount = $audit.outputCount
    intakeEvidenceNeededCount = $intakeEvidenceNeededCount
    auditEvidenceNeededCount = $auditEvidenceNeededCount
    evidenceNeeded = if ($producerBundlesRequired) { @($safeEvidenceNeeded) } else { @() }
    supportingEvidenceNeeded = if ($producerBundlesRequired) { @() } else { @($safeEvidenceNeeded) }
    orchestratedByGoalNext = [bool]$OrchestratedByGoalNext
    derivedGateOwner = $derivedGateOwner
    completionAuditStatus = $completionAuditStatus
    completionAuditExit = $completionAuditExit
    secretHits = $secretHits
    rawSecretPatternHits = $rawSecretHits
    sourceEvidenceDirPresent = $sourceEvidenceDirPresent
    evidenceDirPresent = $evidenceDirPresent
})

Write-Host "[AWX][external][apply-collected] intakeDecision=$intakeDecision externalEvidenceComplete=$intakeComplete copiedEvidenceCount=$($intakeSummary.copiedEvidenceCount) copiedHandoffCount=$($intakeSummary.copiedHandoffCount) evidenceNeededCount=$intakeEvidenceNeededCount"
Write-Host "[AWX][external][apply-collected] auditDecision=$auditDecision externalEvidenceComplete=$auditComplete outputCount=$($audit.outputCount) evidenceNeededCount=$auditEvidenceNeededCount"
$completionAuditExitText = if ($null -eq $completionAuditExit) { 'n/a' } else { [string]$completionAuditExit }
Write-Host "[AWX][external][apply-collected] completionAuditStatus=$completionAuditStatus completionAuditExit=$completionAuditExitText secretHits=$secretHits rawSecretPatternHits=$rawSecretHits"
Write-Host "[AWX][external][apply-collected] sourceEvidenceDirPresent=$sourceEvidenceDirPresent evidenceDirPresent=$evidenceDirPresent"

if ($secretHits -gt 0 -or $rawSecretHits -gt 0) {
    Write-Host '[AWX][external][apply-collected] secret-leak-risk'
    exit 4
}
if ($completionAuditIncomplete) {
    Write-Host '[AWX][external][apply-collected] evidence_needed: completion audit incomplete'
    exit 2
}
if ($externalEvidenceIncomplete -and $producerBundlesRequired) {
    Write-Host '[AWX][external][apply-collected] evidence_needed: external producer evidence is incomplete'
    exit 2
}
if ($externalEvidenceIncomplete) {
    Write-Host '[AWX][external][apply-collected] supporting_evidence_missing: external producer evidence is incomplete'
    exit 0
}

Write-Host '[AWX][external][apply-collected] ok=true'
exit 0

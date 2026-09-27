[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Finalize', 'GradeSandbox', 'PromoteIntent', 'PlanSession')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [string]$RedEvidenceFile,
    [string]$DockerBin = 'docker',
    [switch]$ContractTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$MaxPacketBytes = 262144
$SecretPattern = [regex]'(?:sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,})'
$goalScoreContract = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\scripts\awx_goal_score_contract.ps1'))
. $goalScoreContract

function Throw-Classified {
    param([string]$Class, [string]$Detail)
    throw "$Class`: $Detail"
}
function Get-CanonicalRoot {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { Throw-Classified 'root-not-found' $Path }
    $resolved = Resolve-Path -LiteralPath $Path
    $providerPath = [string]$resolved.ProviderPath
    if ([string]::IsNullOrWhiteSpace($providerPath)) { $providerPath = [string]$resolved.Path }
    if ($providerPath -match '^([A-Za-z]):\\') {
        $drive = Get-PSDrive -Name $Matches[1] -ErrorAction SilentlyContinue
        if ($drive -and -not [string]::IsNullOrWhiteSpace([string]$drive.DisplayRoot)) {
            $driveRoot = [IO.Path]::GetPathRoot($providerPath)
            $suffix = $providerPath.Substring($driveRoot.Length)
            $providerPath = if ([string]::IsNullOrWhiteSpace($suffix)) { [string]$drive.DisplayRoot } else { Join-Path ([string]$drive.DisplayRoot) $suffix }
        }
    }
    $providerPath.TrimEnd('\', '/')
}
function Resolve-RootFile {
    param([string]$CanonicalRoot, [string]$RelativePath, [bool]$MustExist)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(':')) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { Throw-Classified 'path-outside-root' $RelativePath }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { Throw-Classified 'autograder-packet-missing' $RelativePath }
    $cursor = if (Test-Path -LiteralPath $candidate) { $candidate } else { [IO.Path]::GetDirectoryName($candidate) }
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Throw-Classified 'reparse-path-rejected' $RelativePath }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    [pscustomobject]@{ FullPath = $candidate; Relative = $candidate.Substring($prefix.Length).Replace('\', '/') }
}
function Read-JsonSnapshot {
    param([string]$Path, [string]$FailureClass)
    $stream = $null; $memory = $null; $sha = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($stream.Length -gt $MaxPacketBytes) { Throw-Classified $FailureClass 'too-large' }
        $memory = New-Object IO.MemoryStream
        $stream.CopyTo($memory)
        $bytes = $memory.ToArray()
        $sha = [Security.Cryptography.SHA256]::Create()
        $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        $text = (New-Object Text.UTF8Encoding($false, $true)).GetString($bytes)
        try { $value = $text | ConvertFrom-Json } catch { Throw-Classified $FailureClass 'invalid-json' }
        [pscustomobject]@{ Value = $value; Sha256 = $hash; Text = $text }
    } finally {
        if ($null -ne $sha) { $sha.Dispose() }
        if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
    }
}
function Test-Fields {
    param([object]$Value, [string[]]$Fields)
    if ($null -eq $Value) { return $false }
    $names = @($Value.PSObject.Properties.Name)
    foreach ($field in $Fields) { if ($names -notcontains $field) { return $false } }
    return $true
}
function Test-ScoreEvidenceIds {
    param([object]$Value)
    $names = @(
        'evidenceStrength', 'causalStrength', 'verificationFeasibility',
        'userValue', 'reversibility', 'costEfficiency', 'timeFit',
        'blastRadius', 'ambiguity', 'authorityOrSafetyExpansion'
    )
    if ($null -eq $Value -or @($Value.PSObject.Properties.Name).Count -ne $names.Count) { return $false }
    foreach ($name in $names) {
        if (@($Value.PSObject.Properties.Name) -notcontains $name) { return $false }
        $ids = @($Value.$name)
        if ($ids.Count -eq 0 -or @($ids | Where-Object { [string]$_ -cne 'autograderEvidence' }).Count -gt 0) { return $false }
    }
    return $true
}
function Add-Failure {
    param([Collections.Generic.List[string]]$List, [string]$Class)
    if (-not $List.Contains($Class)) { $List.Add($Class) }
}
function Publish-Record {
    param([System.Collections.IDictionary]$Record, [string]$Path)
    $shaPath = $Path + '.sha256'; $readyPath = $Path + '.ready'
    if ((Test-Path -LiteralPath $Path) -or (Test-Path -LiteralPath $shaPath) -or (Test-Path -LiteralPath $readyPath)) {
        Throw-Classified 'autograder-decision-already-exists' $RunId
    }
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $token = [guid]::NewGuid().ToString('N')
    $temps = @(
        (Join-Path $parent ".decision-$token.tmp"),
        (Join-Path $parent ".decision-sha-$token.tmp"),
        (Join-Path $parent ".decision-ready-$token.tmp")
    )
    try {
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temps[0], (($Record | ConvertTo-Json -Depth 14) + [Environment]::NewLine), $utf8)
        $shaValue = (Get-FileHash -LiteralPath $temps[0] -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText($temps[1], $shaValue + [Environment]::NewLine, $utf8)
        [IO.File]::WriteAllText($temps[2], $shaValue + [Environment]::NewLine, $utf8)
        Move-Item -LiteralPath $temps[0] -Destination $Path
        Move-Item -LiteralPath $temps[1] -Destination $shaPath
        Move-Item -LiteralPath $temps[2] -Destination $readyPath
        return $shaValue
    } finally {
        foreach ($temp in $temps) { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
    }
}
function Read-ReadySnapshot {
    param([string]$Path, [string]$FailureClass)
    $snapshot = Read-JsonSnapshot $Path $FailureClass
    $shaPath = $Path + '.sha256'; $readyPath = $Path + '.ready'
    if (-not (Test-Path -LiteralPath $shaPath -PathType Leaf) -or -not (Test-Path -LiteralPath $readyPath -PathType Leaf)) {
        Throw-Classified $FailureClass 'publication-incomplete'
    }
    $sidecar = (Get-Content -LiteralPath $shaPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
    $ready = (Get-Content -LiteralPath $readyPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
    if ($snapshot.Sha256 -ne $sidecar -or $snapshot.Sha256 -ne $ready) { Throw-Classified $FailureClass 'checksum-mismatch' }
    return $snapshot
}

$canonicalRoot = Get-CanonicalRoot $Root
$probeRelative = "data/agent-handoff/macsrc-defect-intake/$RunId/autograder-probe"
$probeDirectory = [IO.Path]::GetFullPath((Join-Path $canonicalRoot $probeRelative))
if ($Mode -eq 'GradeSandbox') {
    $decisionMember = Resolve-RootFile $canonicalRoot "$probeRelative/decision.json" $true
    $intentSpecMember = Resolve-RootFile $canonicalRoot "$probeRelative/intent-spec.json" $true
    $jobRelative = "data/agent-handoff/docker-autograder/$RunId/job.json"
    $resultRelative = "data/agent-handoff/docker-autograder/$RunId/result.json"
    $jobMember = Resolve-RootFile $canonicalRoot $jobRelative $true
    $decisionSnapshot = Read-ReadySnapshot $decisionMember.FullPath 'autograder-decision-invalid'
    $intentSpecSnapshot = Read-ReadySnapshot $intentSpecMember.FullPath 'autograder-intent-spec-invalid'
    $jobSnapshot = Read-ReadySnapshot $jobMember.FullPath 'autograder-job-invalid'
    $decision = $decisionSnapshot.Value
    $intentSpec = $intentSpecSnapshot.Value
    $job = $jobSnapshot.Value
    if ($decision.schemaVersion -ne 'awx.autograder.intake-decision.v1' -or
        $decision.subjectRunId -ne $RunId -or $decision.verdict -ne 'APPLY' -or
        $decision.nextAction -ne 'COLLECT_INTENT_SPEC' -or $decision.mutationAllowed -ne $false -or
        $decision.patchIntentCreated -ne $false -or [int]$decision.secretPatternHits -ne 0) {
        Throw-Classified 'autograder-decision-invalid' 'schema-run-verdict-mutation'
    }
    $requiredJobFields = @(
        'schemaVersion', 'runId', 'purpose', 'profile', 'imageRef', 'includePaths',
        'testSelectors', 'declaredTestCommand', 'expectedSignal', 'decisionSha256',
        'intentSpecSha256', 'limits', 'networkMode', 'pullPolicy', 'mutationAllowed'
    )
    if (-not (Test-Fields $job $requiredJobFields) -or
        @($job.PSObject.Properties.Name).Count -ne $requiredJobFields.Count -or
        $job.schemaVersion -ne 'awx.docker-autograder.job.v1' -or $job.runId -ne $RunId -or
        $job.purpose -ne 'RED_PROBE' -or $job.decisionSha256 -ne $decisionSnapshot.Sha256 -or
        $job.intentSpecSha256 -ne $intentSpecSnapshot.Sha256 -or
        $job.declaredTestCommand -ne $intentSpec.redCommand -or
        $job.expectedSignal -ne $intentSpec.expectedRedSignal -or
        $job.networkMode -ne 'none' -or $job.pullPolicy -ne 'never' -or
        $job.mutationAllowed -ne $false -or $SecretPattern.Matches($jobSnapshot.Text).Count -gt 0) {
        Throw-Classified 'autograder-job-invalid' 'schema-run-purpose-upstream-command-policy'
    }
    $wrapper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\demo1-docker-autograder\scripts\invoke_docker_autograder.ps1'))
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        Throw-Classified 'autograder-wrapper-missing' $wrapper
    }
    $wrapperArguments = @{
        Root = $canonicalRoot
        RunId = $RunId
        JobFile = $jobRelative
        OutputPath = $resultRelative
        DockerBin = $DockerBin
    }
    if ($ContractTest) { $wrapperArguments.ContractTest = $true }
    & $wrapper @wrapperArguments | Out-Null
    $resultMember = Resolve-RootFile $canonicalRoot $resultRelative $true
    $resultSnapshot = Read-ReadySnapshot $resultMember.FullPath 'autograder-grade-result-invalid'
    $result = $resultSnapshot.Value
    $requiredResultFields = @(
        'schemaVersion', 'runId', 'purpose', 'jobSha256', 'executionStatus', 'failureClass',
        'exitCode', 'timedOut', 'testVerdict', 'tests', 'expectedSignalMatched',
        'combinedOutputSha256', 'mutationAllowed', 'sourceMutationPerformed'
    )
    if (-not (Test-Fields $result $requiredResultFields) -or
        $result.schemaVersion -ne 'awx.docker-autograder.result.v1' -or $result.runId -ne $RunId -or
        $result.purpose -ne 'RED_PROBE' -or $result.jobSha256 -ne $jobSnapshot.Sha256 -or
        $result.mutationAllowed -ne $false -or $result.sourceMutationPerformed -ne $false -or
        [string]$result.combinedOutputSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $SecretPattern.Matches($resultSnapshot.Text).Count -gt 0) {
        Throw-Classified 'autograder-grade-result-invalid' 'schema-run-job-mutation-output'
    }
    $redProven = (
        $result.executionStatus -eq 'COMPLETE' -and $result.failureClass -eq 'none' -and
        $result.testVerdict -eq 'FAIL' -and $null -ne $result.exitCode -and [int]$result.exitCode -ne 0 -and
        ([int]$result.tests.failed + [int]$result.tests.errored) -gt 0 -and
        $result.expectedSignalMatched -eq $true -and $result.timedOut -eq $false
    )
    $gradeFailureClass = if ($redProven) {
        'none'
    } elseif ($result.executionStatus -eq 'HOLD') {
        [string]$result.failureClass
    } else {
        'red-not-reproduced'
    }
    $transition = [ordered]@{
        schemaVersion = 'awx.autograder.grade-transition.v1'
        subjectRunId = $RunId
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        decisionSha256 = $decisionSnapshot.Sha256
        intentSpecSha256 = $intentSpecSnapshot.Sha256
        jobSha256 = $jobSnapshot.Sha256
        resultSha256 = $resultSnapshot.Sha256
        resultFile = $resultMember.Relative
        executionStatus = [string]$result.executionStatus
        failureClass = $gradeFailureClass
        exitCode = $result.exitCode
        testVerdict = [string]$result.testVerdict
        totalTests = [int]$result.tests.total
        failingTestCount = ([int]$result.tests.failed + [int]$result.tests.errored)
        passRatio = $result.tests.passRatio
        expectedSignalMatched = [bool]$result.expectedSignalMatched
        outputSha256 = ([string]$result.combinedOutputSha256).ToLowerInvariant()
        nextAction = if ($redProven) { 'PROMOTE_INTENT' } else { 'HOLD' }
        mutationAllowed = $false
        desktopFinalProof = 'evidence_needed'
    }
    $transitionPath = Join-Path $probeDirectory 'grade-transition.json'
    $transitionSha = Publish-Record $transition $transitionPath
    [pscustomobject]@{
        status = if ($redProven) { 'RED_PROVEN' } else { 'GRADE_HOLD' }
        transitionPath = $transitionPath
        transitionSha256 = $transitionSha
        resultPath = $resultMember.FullPath
        resultSha256 = $resultSnapshot.Sha256
        failureClass = $gradeFailureClass
        nextAction = if ($redProven) { 'PROMOTE_INTENT' } else { 'HOLD' }
        mutationAllowed = $false
    } | ConvertTo-Json -Compress
    exit 0
}
if ($Mode -eq 'PlanSession') {
    $promotionMember = Resolve-RootFile $canonicalRoot "$probeRelative/promotion.json" $true
    $promotionSnapshot = Read-ReadySnapshot $promotionMember.FullPath 'autograder-promotion-invalid'
    $promotion = $promotionSnapshot.Value
    if ([string]::IsNullOrWhiteSpace($RedEvidenceFile)) {
        if (-not (Test-Fields $promotion @('redEvidenceFile'))) {
            Throw-Classified 'autograder-red-evidence-required' $RunId
        }
        $RedEvidenceFile = [string]$promotion.redEvidenceFile
    }
    $intentMember = Resolve-RootFile $canonicalRoot "data/agent-handoff/macsrc-defect-intake/$RunId/intent.json" $true
    $redMember = Resolve-RootFile $canonicalRoot $RedEvidenceFile $true
    $intentSnapshot = Read-ReadySnapshot $intentMember.FullPath 'autograder-promoted-intent-invalid'
    $redSnapshot = Read-ReadySnapshot $redMember.FullPath 'autograder-red-evidence-invalid'
    $intent = $intentSnapshot.Value
    $red = $redSnapshot.Value
    if ($promotion.schemaVersion -ne 'awx.autograder.intent-promotion.v1' -or
        $promotion.subjectRunId -ne $RunId -or $promotion.intentSha256 -ne $intentSnapshot.Sha256 -or
        $promotion.redEvidenceSha256 -ne $redSnapshot.Sha256 -or
        $promotion.redEvidenceFile -ne $redMember.Relative -or
        $promotion.nextAction -ne 'PLAN_GUARD_SESSION' -or $promotion.mutationAllowed -ne $false) {
        Throw-Classified 'autograder-promotion-invalid' 'schema-run-intent-mutation'
    }
    $requiredRedFields = @('schemaVersion', 'runId', 'intentSha256', 'command', 'exitCode', 'expectedSignalMatched', 'outputSha256')
    if (-not (Test-Fields $red $requiredRedFields) -or
        @($red.PSObject.Properties.Name).Count -ne $requiredRedFields.Count -or
        $red.schemaVersion -ne 'awx.red-evidence.v1' -or $red.runId -ne $RunId -or
        $red.intentSha256 -ne $intentSnapshot.Sha256 -or $red.command -ne $intent.red.command -or
        [int]$red.exitCode -eq 0 -or $red.expectedSignalMatched -ne $true -or
        [string]$red.outputSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $SecretPattern.Matches($redSnapshot.Text).Count -gt 0) {
        Throw-Classified 'autograder-red-evidence-invalid' 'schema-run-intent-command-signal'
    }
    $planRelative = "data/agent-handoff/macsrc-guarded-patch/$RunId/plan.json"
    $planMember = Resolve-RootFile $canonicalRoot $planRelative $false
    $planner = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\demo1-macsrc-guarded-patch-session\scripts\new_guard_session_plan.ps1'))
    $planResult = & $planner -Root $canonicalRoot -IntentFile $intentMember.Relative -RedEvidenceFile $redMember.Relative -OutputPath $planRelative | ConvertFrom-Json
    $planSnapshot = Read-ReadySnapshot $planMember.FullPath 'autograder-session-plan-invalid'
    $plan = $planSnapshot.Value
    if ($plan.schemaVersion -ne 'awx.guard-session-plan.v1' -or $plan.runId -ne $RunId -or
        $plan.intentSha256 -ne $intentSnapshot.Sha256 -or $plan.redEvidenceSha256 -ne $redSnapshot.Sha256 -or
        $plan.mutationAllowed -ne $false -or $planResult.planSha256 -ne $planSnapshot.Sha256) {
        Throw-Classified 'autograder-session-plan-invalid' 'schema-run-input-mutation'
    }
    $transition = [ordered]@{
        schemaVersion = 'awx.autograder.session-transition.v1'
        subjectRunId = $RunId
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        promotionSha256 = $promotionSnapshot.Sha256
        intentSha256 = $intentSnapshot.Sha256
        redEvidenceSha256 = $redSnapshot.Sha256
        guardPlanSha256 = $planSnapshot.Sha256
        guardPlanFile = $planMember.Relative
        guardVerdict = [string]$plan.verdict
        nextAction = [string]$plan.nextAction
        mutationAllowed = $false
        desktopFinalProof = 'evidence_needed'
    }
    $transitionPath = Join-Path $probeDirectory 'session-transition.json'
    $transitionSha = Publish-Record $transition $transitionPath
    [pscustomobject]@{
        status = 'SESSION_PLANNED'
        transitionPath = $transitionPath
        transitionSha256 = $transitionSha
        planPath = $planMember.FullPath
        planSha256 = $planSnapshot.Sha256
        verdict = [string]$plan.verdict
        nextAction = [string]$plan.nextAction
        mutationAllowed = $false
    } | ConvertTo-Json -Compress
    exit 0
}
if ($Mode -eq 'PromoteIntent') {
    $decisionMember = Resolve-RootFile $canonicalRoot "$probeRelative/decision.json" $true
    $intentSpecMember = Resolve-RootFile $canonicalRoot "$probeRelative/intent-spec.json" $true
    $gradeTransitionMember = Resolve-RootFile $canonicalRoot "$probeRelative/grade-transition.json" $false
    if (-not (Test-Path -LiteralPath $gradeTransitionMember.FullPath -PathType Leaf)) {
        Throw-Classified 'autograder-grade-transition-invalid' 'missing'
    }
    $decisionSnapshot = Read-ReadySnapshot $decisionMember.FullPath 'autograder-decision-invalid'
    $intentSpecSnapshot = Read-ReadySnapshot $intentSpecMember.FullPath 'autograder-intent-spec-invalid'
    $gradeTransitionSnapshot = Read-ReadySnapshot $gradeTransitionMember.FullPath 'autograder-grade-transition-invalid'
    $decision = $decisionSnapshot.Value
    $intentSpec = $intentSpecSnapshot.Value
    $gradeTransition = $gradeTransitionSnapshot.Value
    if ($decision.schemaVersion -ne 'awx.autograder.intake-decision.v1' -or
        $decision.subjectRunId -ne $RunId -or $decision.verdict -ne 'APPLY' -or
        $decision.nextAction -ne 'COLLECT_INTENT_SPEC' -or $decision.mutationAllowed -ne $false -or
        $decision.patchIntentCreated -ne $false -or [int]$decision.secretPatternHits -ne 0) {
        Throw-Classified 'autograder-decision-invalid' 'schema-run-verdict-mutation'
    }
    $requiredSpecFields = @(
        'schemaVersion', 'subjectRunId', 'decisionSha256', 'defectClass',
        'evidenceIds', 'targetFiles', 'watchRoots', 'boundaryEvidenceFiles',
        'boundaryProofType', 'redCommand', 'expectedRedSignal', 'greenCommand',
        'mutationAllowed'
    )
    if (-not (Test-Fields $intentSpec $requiredSpecFields) -or
        @($intentSpec.PSObject.Properties.Name).Count -ne $requiredSpecFields.Count -or
        $intentSpec.schemaVersion -ne 'awx.autograder.intent-spec.v1' -or
        $intentSpec.subjectRunId -ne $RunId -or
        $intentSpec.decisionSha256 -ne $decisionSnapshot.Sha256 -or
        $intentSpec.mutationAllowed -ne $false -or
        @($intentSpec.evidenceIds).Count -eq 0 -or @($intentSpec.targetFiles).Count -eq 0 -or
        @($intentSpec.watchRoots).Count -eq 0 -or @($intentSpec.boundaryEvidenceFiles).Count -eq 0 -or
        @('GradleSourceSet', 'CallPath') -notcontains [string]$intentSpec.boundaryProofType -or
        $SecretPattern.Matches($intentSpecSnapshot.Text).Count -gt 0) {
        Throw-Classified 'autograder-intent-spec-invalid' 'schema-run-decision-boundary'
    }
    $requiredGradeFields = @(
        'schemaVersion', 'subjectRunId', 'decisionSha256', 'intentSpecSha256',
        'jobSha256', 'resultSha256', 'resultFile', 'executionStatus', 'failureClass',
        'exitCode', 'testVerdict', 'totalTests', 'failingTestCount', 'passRatio',
        'expectedSignalMatched', 'outputSha256', 'nextAction', 'mutationAllowed'
    )
    if (-not (Test-Fields $gradeTransition $requiredGradeFields) -or
        $gradeTransition.schemaVersion -ne 'awx.autograder.grade-transition.v1' -or
        $gradeTransition.subjectRunId -ne $RunId -or
        $gradeTransition.decisionSha256 -ne $decisionSnapshot.Sha256 -or
        $gradeTransition.intentSpecSha256 -ne $intentSpecSnapshot.Sha256 -or
        $gradeTransition.executionStatus -ne 'COMPLETE' -or $gradeTransition.failureClass -ne 'none' -or
        $gradeTransition.testVerdict -ne 'FAIL' -or [int]$gradeTransition.exitCode -eq 0 -or
        [int]$gradeTransition.failingTestCount -le 0 -or $gradeTransition.expectedSignalMatched -ne $true -or
        [string]$gradeTransition.outputSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
        $gradeTransition.nextAction -ne 'PROMOTE_INTENT' -or $gradeTransition.mutationAllowed -ne $false) {
        Throw-Classified 'autograder-grade-transition-invalid' 'schema-run-upstream-red-mutation'
    }
    $gradeResultMember = Resolve-RootFile $canonicalRoot ([string]$gradeTransition.resultFile) $true
    $gradeResultSnapshot = Read-ReadySnapshot $gradeResultMember.FullPath 'autograder-grade-result-invalid'
    $gradeResult = $gradeResultSnapshot.Value
    $gradeJobMember = Resolve-RootFile $canonicalRoot "data/agent-handoff/docker-autograder/$RunId/job.json" $true
    $gradeJobSnapshot = Read-ReadySnapshot $gradeJobMember.FullPath 'autograder-job-invalid'
    $gradeJob = $gradeJobSnapshot.Value
    if ($gradeTransition.resultSha256 -ne $gradeResultSnapshot.Sha256 -or
        $gradeTransition.jobSha256 -ne $gradeJobSnapshot.Sha256 -or
        $gradeResult.jobSha256 -ne $gradeJobSnapshot.Sha256 -or
        $gradeResult.combinedOutputSha256 -ne $gradeTransition.outputSha256 -or
        $gradeJob.decisionSha256 -ne $decisionSnapshot.Sha256 -or
        $gradeJob.intentSpecSha256 -ne $intentSpecSnapshot.Sha256 -or
        $gradeJob.declaredTestCommand -ne $intentSpec.redCommand -or
        $gradeJob.expectedSignal -ne $intentSpec.expectedRedSignal -or
        $SecretPattern.Matches($gradeTransitionSnapshot.Text + $gradeResultSnapshot.Text + $gradeJobSnapshot.Text).Count -gt 0) {
        Throw-Classified 'autograder-grade-transition-invalid' 'job-result-command-checksum'
    }
    $intentScript = Join-Path $PSScriptRoot 'prepare_defect_intent.ps1'
    $intentRelative = "data/agent-handoff/macsrc-defect-intake/$RunId/intent.json"
    $intentArguments = @{
        Root = $canonicalRoot
        RunId = $RunId
        DefectClass = [string]$intentSpec.defectClass
        EvidenceIds = @($intentSpec.evidenceIds)
        TargetFiles = @($intentSpec.targetFiles)
        WatchRoots = @($intentSpec.watchRoots)
        BoundaryEvidenceFiles = @($intentSpec.boundaryEvidenceFiles)
        BoundaryProofType = [string]$intentSpec.boundaryProofType
        RedCommand = [string]$intentSpec.redCommand
        ExpectedRedSignal = [string]$intentSpec.expectedRedSignal
        GreenCommand = [string]$intentSpec.greenCommand
        OutputPath = $intentRelative
    }
    & $intentScript @intentArguments | Out-Null
    $intentMember = Resolve-RootFile $canonicalRoot $intentRelative $true
    $intentSnapshot = Read-ReadySnapshot $intentMember.FullPath 'autograder-promoted-intent-invalid'
    if ($intentSnapshot.Value.schemaVersion -ne 'awx.patch-intent.v1' -or
        $intentSnapshot.Value.runId -ne $RunId -or $intentSnapshot.Value.stage -ne 'PREPARED' -or
        $intentSnapshot.Value.nextAction -ne 'REPRODUCE_RED' -or $intentSnapshot.Value.mutationAllowed -ne $false) {
        Throw-Classified 'autograder-promoted-intent-invalid' 'schema-run-stage-mutation'
    }
    $redEvidence = [ordered]@{
        schemaVersion = 'awx.red-evidence.v1'
        runId = $RunId
        intentSha256 = $intentSnapshot.Sha256
        command = [string]$gradeJob.declaredTestCommand
        exitCode = [int]$gradeTransition.exitCode
        expectedSignalMatched = [bool]$gradeTransition.expectedSignalMatched
        outputSha256 = ([string]$gradeTransition.outputSha256).ToLowerInvariant()
    }
    $redEvidencePath = Join-Path $probeDirectory 'red-evidence.json'
    $redEvidenceSha = Publish-Record $redEvidence $redEvidencePath
    $redEvidenceMember = Resolve-RootFile $canonicalRoot "$probeRelative/red-evidence.json" $true
    $promotion = [ordered]@{
        schemaVersion = 'awx.autograder.intent-promotion.v1'
        subjectRunId = $RunId
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        decisionSha256 = $decisionSnapshot.Sha256
        intentSpecSha256 = $intentSpecSnapshot.Sha256
        gradeTransitionSha256 = $gradeTransitionSnapshot.Sha256
        gradeResultSha256 = $gradeResultSnapshot.Sha256
        intentSha256 = $intentSnapshot.Sha256
        intentFile = $intentMember.Relative
        redEvidenceSha256 = $redEvidenceSha
        redEvidenceFile = $redEvidenceMember.Relative
        nextAction = 'PLAN_GUARD_SESSION'
        mutationAllowed = $false
        desktopFinalProof = 'evidence_needed'
    }
    $promotionPath = Join-Path $probeDirectory 'promotion.json'
    $promotionSha = Publish-Record $promotion $promotionPath
    [pscustomobject]@{
        status = 'INTENT_PROMOTED'
        promotionPath = $promotionPath
        promotionSha256 = $promotionSha
        intentPath = $intentMember.FullPath
        intentSha256 = $intentSnapshot.Sha256
        redEvidencePath = $redEvidenceMember.FullPath
        redEvidenceSha256 = $redEvidenceSha
        nextAction = 'PLAN_GUARD_SESSION'
        mutationAllowed = $false
    } | ConvertTo-Json -Compress
    exit 0
}
$manifestMember = Resolve-RootFile $canonicalRoot "$probeRelative/probe.manifest.json" $true
$manifestSnapshot = Read-JsonSnapshot $manifestMember.FullPath 'autograder-manifest-invalid'
$manifest = $manifestSnapshot.Value
$manifestShaPath = $manifestMember.FullPath + '.sha256'
$manifestReadyPath = $manifestMember.FullPath + '.ready'
if (-not (Test-Path -LiteralPath $manifestShaPath -PathType Leaf) -or -not (Test-Path -LiteralPath $manifestReadyPath -PathType Leaf)) {
    Throw-Classified 'autograder-manifest-incomplete' $RunId
}
$manifestSidecar = (Get-Content -LiteralPath $manifestShaPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
$manifestReady = (Get-Content -LiteralPath $manifestReadyPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
if ($manifestSnapshot.Sha256 -ne $manifestSidecar -or $manifestSnapshot.Sha256 -ne $manifestReady) {
    Throw-Classified 'autograder-manifest-hash-mismatch' $RunId
}
if ($manifest.schemaVersion -ne 'awx.autograder.probe-manifest.v1' -or $manifest.subjectRunId -ne $RunId -or $manifest.mutationAllowed -ne $false) {
    Throw-Classified 'autograder-manifest-invalid' 'schema-run-mutation'
}
$evidenceMember = Resolve-RootFile $canonicalRoot ([string]$manifest.evidenceFile) $true
$evidenceSnapshot = Read-JsonSnapshot $evidenceMember.FullPath 'autograder-evidence-invalid'
if ($evidenceSnapshot.Sha256 -ne [string]$manifest.evidenceSha256) { Throw-Classified 'autograder-evidence-hash-mismatch' $RunId }

$positiveSnapshot = Read-JsonSnapshot (Join-Path $probeDirectory 'positive.packet.json') 'autograder-packet-invalid'
$negativeSnapshot = Read-JsonSnapshot (Join-Path $probeDirectory 'negative.packet.json') 'autograder-packet-invalid'
$neutralSnapshot = Read-JsonSnapshot (Join-Path $probeDirectory 'neutral.packet.json') 'autograder-packet-invalid'
$positive = $positiveSnapshot.Value; $negative = $negativeSnapshot.Value; $neutral = $neutralSnapshot.Value
$failures = New-Object Collections.Generic.List[string]

$positiveFields = @('schemaVersion', 'packetType', 'subjectRunId', 'evidenceSha256', 'candidateGoal', 'validatedAssumptions', 'reusableAssets', 'expectedUserValue', 'minimalVerification', 'evidenceIds', 'unknowns', 'mutationAllowed')
$negativeFields = @('schemaVersion', 'packetType', 'subjectRunId', 'evidenceSha256', 'challengedGoal', 'falsifiers', 'counterExamples', 'authorityRisks', 'safetyRisks', 'missingEvidence', 'smallestDisconfirmingProbe', 'evidenceIds', 'mutationAllowed')
$neutralFields = @('schemaVersion', 'packetType', 'subjectRunId', 'evidenceSha256', 'positivePacketSha256', 'negativePacketSha256', 'orderABVerdict', 'orderBAVerdict', 'orderStable', 'verdict', 'selectedOrRewrittenGoal', 'goalScoreComponents', 'goalScoreEvidenceIds', 'goalScore', 'decisiveEvidence', 'rejectedClaims', 'nextSingleProof', 'confidence', 'mutationAllowed', 'secretPatternHits')
if (-not (Test-Fields $positive $positiveFields) -or -not (Test-Fields $negative $negativeFields) -or -not (Test-Fields $neutral $neutralFields)) {
    Add-Failure $failures 'autograder-packet-schema-invalid'
}
if ($positive.schemaVersion -ne 'awx.autograder.probe-positive.v1' -or $positive.packetType -ne 'POSITIVE_QUERY' -or
    $negative.schemaVersion -ne 'awx.autograder.probe-negative.v1' -or $negative.packetType -ne 'NEGATIVE_QUERY' -or
    $neutral.schemaVersion -ne 'awx.autograder.probe-neutral.v1' -or $neutral.packetType -ne 'NEUTRAL_QUERY') {
    Add-Failure $failures 'autograder-packet-schema-invalid'
}
foreach ($packet in @($positive, $negative, $neutral)) {
    if ($packet.subjectRunId -ne $RunId -or $packet.evidenceSha256 -ne $manifest.evidenceSha256 -or $packet.mutationAllowed -ne $false) {
        Add-Failure $failures 'autograder-packet-input-mismatch'
    }
}
if ($neutral.positivePacketSha256 -ne $positiveSnapshot.Sha256 -or $neutral.negativePacketSha256 -ne $negativeSnapshot.Sha256) {
    Add-Failure $failures 'autograder-packet-hash-mismatch'
}
if ($neutral.orderStable -ne $true -or $neutral.orderABVerdict -ne $neutral.orderBAVerdict -or $neutral.verdict -ne $neutral.orderABVerdict) {
    Add-Failure $failures 'neutral-order-unstable'
}
$scoreResult = Measure-AwxGoalScore -Components $neutral.goalScoreComponents -ProvidedScore $neutral.goalScore
if (-not $scoreResult.valid) { Add-Failure $failures ([string]$scoreResult.failureClassification) }
if (-not (Test-ScoreEvidenceIds $neutral.goalScoreEvidenceIds)) { Add-Failure $failures 'goal-score-evidence-unbound' }
$secretHits = $SecretPattern.Matches($positiveSnapshot.Text).Count + $SecretPattern.Matches($negativeSnapshot.Text).Count + $SecretPattern.Matches($neutralSnapshot.Text).Count
if ($secretHits -gt 0 -or [int]$neutral.secretPatternHits -ne 0) { Add-Failure $failures 'secret-leak-risk' }
if (@('APPLY', 'HOLD', 'REJECT') -notcontains [string]$neutral.verdict -or @('L', 'M', 'H') -notcontains [string]$neutral.confidence) {
    Add-Failure $failures 'autograder-packet-schema-invalid'
}

$failureClasses = @($failures | Sort-Object -Unique)
$score = $scoreResult.computedScore
$verdict = if ($failureClasses.Count -gt 0) {
    'HOLD'
} elseif ($neutral.verdict -eq 'REJECT') {
    'REJECT'
} elseif ($neutral.verdict -eq 'APPLY' -and $score -ge 50.0 -and $manifest.concreteRedCandidate -eq $true) {
    'APPLY'
} else {
    'HOLD'
}
$nextAction = if ($verdict -eq 'APPLY') { 'COLLECT_INTENT_SPEC' } elseif ($verdict -eq 'REJECT') { 'STOP' } elseif ($failureClasses.Count -gt 0) { 'RERUN_AUTOGRADER_TRI_QUERY' } else { 'COLLECT_NEXT_SINGLE_PROOF' }
$decision = [ordered]@{
    schemaVersion = 'awx.autograder.intake-decision.v1'
    subjectRunId = $RunId
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    probeManifestSha256 = $manifestSnapshot.Sha256
    evidenceSha256 = [string]$manifest.evidenceSha256
    positivePacketSha256 = $positiveSnapshot.Sha256
    negativePacketSha256 = $negativeSnapshot.Sha256
    neutralPacketSha256 = $neutralSnapshot.Sha256
    verdict = $verdict
    failureClasses = $failureClasses
    orderStable = [bool]$neutral.orderStable
    selectedOrRewrittenGoal = [string]$neutral.selectedOrRewrittenGoal
    goalScore = $score
    nextSingleProof = [string]$neutral.nextSingleProof
    confidence = [string]$neutral.confidence
    nextAction = $nextAction
    patchIntentCreated = $false
    mutationAllowed = $false
    secretPatternHits = $secretHits
    desktopFinalProof = 'evidence_needed'
}
$decisionPath = Join-Path $probeDirectory 'decision.json'
$decisionSha = Publish-Record $decision $decisionPath
[pscustomobject]@{
    status = 'FINALIZED'
    verdict = $verdict
    decisionPath = $decisionPath
    decisionSha256 = $decisionSha
    nextAction = $nextAction
    mutationAllowed = $false
} | ConvertTo-Json -Compress

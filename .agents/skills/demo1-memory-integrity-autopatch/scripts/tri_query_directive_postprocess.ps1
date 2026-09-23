[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Prepare", "Finalize")]
    [string]$Mode,
    [Parameter(Mandatory = $true)]
    [string]$Root,
    [string]$EvidencePath = "data\agent-handoff\memory-integrity\latest.json",
    [string]$DirectivePath = "agent-prompts\agents\demo1_context_contamination_scout_directive\system_ko.md",
    [string]$RunDirectory,
    [string]$RunId,
    [string]$PositivePacketPath,
    [string]$NegativePacketPath,
    [string]$NeutralPacketPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$MaxPacketBytes = 262144
$SecretPattern = [regex]'(?:sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,})'
$goalScoreContract = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\scripts\awx_goal_score_contract.ps1'))
. $goalScoreContract

function Resolve-ProviderPath {
    param([string]$Path)
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

function Resolve-UnderRoot {
    param([string]$ResolvedRoot, [string]$Path, [switch]$MustExist)
    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $ResolvedRoot $Path }
    if ($MustExist) { return Resolve-ProviderPath $candidate }
    return [System.IO.Path]::GetFullPath($candidate)
}

function Get-Sha256File {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    Write-Utf8File -Path $Path -Content ($Value | ConvertTo-Json -Depth 14)
}

function Test-RunDirectory {
    param([string]$ResolvedRoot, [string]$Candidate)
    $allowed = [System.IO.Path]::GetFullPath((Join-Path $ResolvedRoot "data\agent-handoff\memory-integrity\tri-query")).TrimEnd('\', '/')
    $full = [System.IO.Path]::GetFullPath($Candidate).TrimEnd('\', '/')
    $prefix = $allowed + [System.IO.Path]::DirectorySeparatorChar
    if (($full -ine $allowed) -and (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase))) {
        throw "run-directory-outside-handoff-root"
    }
    return $full
}

function Test-PacketPath {
    param([string]$RunRoot, [string]$Candidate)
    $full = Resolve-ProviderPath $Candidate
    $prefix = $RunRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "packet-path-outside-run-directory"
    }
    if ((Get-Item -LiteralPath $full).Length -gt $MaxPacketBytes) { throw "packet-size-limit" }
    return $full
}

function Get-JsonPacket {
    param([string]$Path)
    $text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    try { return [pscustomobject]@{ value = ($text | ConvertFrom-Json); text = $text } }
    catch { throw "packet-json-invalid" }
}

function Test-Fields {
    param([object]$Value, [string[]]$Fields)
    $names = @($Value.PSObject.Properties.Name)
    foreach ($field in $Fields) { if ($names -notcontains $field) { return $false } }
    return $true
}

function Write-FinalDecision {
    param(
        [string]$RunRoot,
        [string]$RunIdentifier,
        [string]$RewriteVerdict,
        [string]$SourceMutationVerdict,
        [string]$Failure,
        [bool]$OrderStable,
        [object]$GoalScore,
        [int]$SecretHits,
        [string]$CandidateHash = ""
    )
    $decision = [ordered]@{
        schemaVersion = "awx.tri_query.final.v1"
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        runId = $RunIdentifier
        rewriteVerdict = $RewriteVerdict
        sourceMutationVerdict = $SourceMutationVerdict
        failureClassification = $Failure
        orderStable = $OrderStable
        goalScore = $GoalScore
        mutationAllowed = $false
        secretPatternHits = $SecretHits
        candidateDirectiveSha256 = $CandidateHash
        originalDirectiveOverwritten = $false
        desktopFinalProof = "evidence_needed"
    }
    Write-JsonFile -Path (Join-Path $RunRoot "final.decision.json") -Value $decision
    return $decision
}

$resolvedRoot = Resolve-ProviderPath $Root
$handoffRoot = Join-Path $resolvedRoot "data\agent-handoff\memory-integrity\tri-query"

if ($Mode -eq "Prepare") {
    if ([string]::IsNullOrWhiteSpace($RunId)) { $RunId = "tri-" + [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssfffZ") }
    if ($RunId -notmatch '^[A-Za-z0-9._-]{1,64}$') { throw "run-id-invalid" }
    if ([string]::IsNullOrWhiteSpace($RunDirectory)) { $RunDirectory = Join-Path $handoffRoot $RunId }
    $runRoot = Test-RunDirectory -ResolvedRoot $resolvedRoot -Candidate $RunDirectory
    $evidenceFull = Resolve-UnderRoot -ResolvedRoot $resolvedRoot -Path $EvidencePath -MustExist
    $directiveFull = Resolve-UnderRoot -ResolvedRoot $resolvedRoot -Path $DirectivePath -MustExist
    if ((Get-Item -LiteralPath $evidenceFull).Length -gt 1048576) { throw "evidence-size-limit" }
    if ((Get-Item -LiteralPath $directiveFull).Length -gt 524288) { throw "directive-size-limit" }
    $evidenceText = Get-Content -LiteralPath $evidenceFull -Raw -Encoding UTF8
    $directiveText = Get-Content -LiteralPath $directiveFull -Raw -Encoding UTF8
    $secretHits = $SecretPattern.Matches($evidenceText).Count + $SecretPattern.Matches($directiveText).Count
    if ($secretHits -gt 0) { throw "input-secret-risk" }
    try { $evidence = $evidenceText | ConvertFrom-Json } catch { throw "evidence-json-invalid" }
    if (-not (Test-Fields -Value $evidence -Fields @("schemaVersion", "verdict", "mutationAllowed"))) { throw "evidence-schema-invalid" }
    if ([bool]$evidence.mutationAllowed) { throw "evidence-mutation-contract-invalid" }
    if ((Test-Path -LiteralPath $runRoot) -and @((Get-ChildItem -LiteralPath $runRoot -Force -ErrorAction SilentlyContinue)).Count -gt 0) {
        throw "run-directory-not-empty"
    }
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    $snapshotPath = Join-Path $runRoot "evidence.snapshot.json"
    Write-Utf8File -Path $snapshotPath -Content $evidenceText
    $evidenceHash = Get-Sha256File $evidenceFull
    $directiveHash = Get-Sha256File $directiveFull
    $skillRoot = Split-Path -Parent $PSScriptRoot
    $positiveRole = Resolve-ProviderPath (Join-Path $skillRoot "references\tri-positive-agent.md")
    $negativeRole = Resolve-ProviderPath (Join-Path $skillRoot "references\tri-negative-agent.md")
    $neutralRole = Resolve-ProviderPath (Join-Path $skillRoot "references\tri-neutral-agent.md")
    $manifest = [ordered]@{
        schemaVersion = "awx.tri_query.input.v1"
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        runId = $RunId
        mutationAllowed = $false
        rootHash = (Get-Sha256File $snapshotPath)
        evidencePath = $evidenceFull
        evidenceSnapshotPath = $snapshotPath
        evidenceSha256 = $evidenceHash
        directivePath = $directiveFull
        directiveSha256 = $directiveHash
        sourceMutationVerdict = [string]$evidence.verdict
        sourceFailureClassification = if ($evidence.PSObject.Properties.Name -contains "failureClassification") { [string]$evidence.failureClassification } else { "none" }
        rolePromptHashes = [ordered]@{
            positive = Get-Sha256File $positiveRole
            negative = Get-Sha256File $negativeRole
            neutral = Get-Sha256File $neutralRole
        }
    }
    Write-JsonFile -Path (Join-Path $runRoot "input-manifest.json") -Value $manifest
    $shared = [ordered]@{
        runId = $RunId
        evidencePath = $snapshotPath
        evidenceSha256 = $evidenceHash
        directivePath = $directiveFull
        directiveSha256 = $directiveHash
        mutationAllowed = $false
    }
    $positiveRequest = [ordered]@{} + $shared
    $positiveRequest.schemaVersion = "awx.tri_query.request.v1"
    $positiveRequest.packetType = "POSITIVE_QUERY"
    $positiveRequest.rolePromptPath = $positiveRole
    $positiveRequest.outputPath = Join-Path $runRoot "positive.packet.json"
    $negativeRequest = [ordered]@{} + $shared
    $negativeRequest.schemaVersion = "awx.tri_query.request.v1"
    $negativeRequest.packetType = "NEGATIVE_QUERY"
    $negativeRequest.rolePromptPath = $negativeRole
    $negativeRequest.outputPath = Join-Path $runRoot "negative.packet.json"
    $neutralRequest = [ordered]@{} + $shared
    $neutralRequest.schemaVersion = "awx.tri_query.request.v1"
    $neutralRequest.packetType = "NEUTRAL_QUERY"
    $neutralRequest.rolePromptPath = $neutralRole
    $neutralRequest.positivePacketPath = Join-Path $runRoot "positive.packet.json"
    $neutralRequest.negativePacketPath = Join-Path $runRoot "negative.packet.json"
    $neutralRequest.outputPath = Join-Path $runRoot "neutral.packet.json"
    Write-JsonFile -Path (Join-Path $runRoot "positive.request.json") -Value $positiveRequest
    Write-JsonFile -Path (Join-Path $runRoot "negative.request.json") -Value $negativeRequest
    Write-JsonFile -Path (Join-Path $runRoot "neutral.request.json") -Value $neutralRequest
    $dispatch = [ordered]@{
        schemaVersion = "awx.tri_query.dispatch.v1"
        runId = $RunId
        mutationAllowed = $false
        stages = @(
            [ordered]@{ stage = 1; execution = "parallel"; roles = @("POSITIVE_QUERY", "NEGATIVE_QUERY"); requests = @("positive.request.json", "negative.request.json") },
            [ordered]@{ stage = 2; execution = "after-stage-1"; roles = @("NEUTRAL_QUERY"); requests = @("neutral.request.json") },
            [ordered]@{ stage = 3; execution = "deterministic"; roles = @("FINALIZER"); command = "tri_query_directive_postprocess.ps1 -Mode Finalize" }
        )
    }
    Write-JsonFile -Path (Join-Path $runRoot "dispatch-plan.json") -Value $dispatch
    [ordered]@{
        schemaVersion = "awx.tri_query.prepare-result.v1"
        status = "PREPARED"
        runId = $RunId
        runDirectory = $runRoot
        mutationAllowed = $false
        sourceMutationVerdict = [string]$evidence.verdict
        nextStage = "dispatch POSITIVE_QUERY and NEGATIVE_QUERY in parallel"
    } | ConvertTo-Json -Depth 6
    exit 0
}

if ([string]::IsNullOrWhiteSpace($RunDirectory)) { throw "run-directory-required" }
$runRoot = Test-RunDirectory -ResolvedRoot $resolvedRoot -Candidate $RunDirectory
$manifestPath = Join-Path $runRoot "input-manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath)) { throw "input-manifest-missing" }
$manifestPacket = Get-JsonPacket $manifestPath
$manifest = $manifestPacket.value
if (-not (Test-Fields -Value $manifest -Fields @("schemaVersion", "runId", "evidenceSha256", "directiveSha256", "sourceMutationVerdict"))) {
    throw "input-manifest-invalid"
}
if ([string]::IsNullOrWhiteSpace($PositivePacketPath)) { $PositivePacketPath = Join-Path $runRoot "positive.packet.json" }
if ([string]::IsNullOrWhiteSpace($NegativePacketPath)) { $NegativePacketPath = Join-Path $runRoot "negative.packet.json" }
if ([string]::IsNullOrWhiteSpace($NeutralPacketPath)) { $NeutralPacketPath = Join-Path $runRoot "neutral.packet.json" }

$failure = "none"
$positiveFull = $null; $negativeFull = $null; $neutralFull = $null
try {
    $positiveFull = Test-PacketPath -RunRoot $runRoot -Candidate $PositivePacketPath
    $negativeFull = Test-PacketPath -RunRoot $runRoot -Candidate $NegativePacketPath
    $neutralFull = Test-PacketPath -RunRoot $runRoot -Candidate $NeutralPacketPath
} catch { $failure = [string]$_.Exception.Message }

$positivePacket = $null; $negativePacket = $null; $neutralPacket = $null
if ($failure -eq "none") {
    try {
        $positivePacket = Get-JsonPacket $positiveFull
        $negativePacket = Get-JsonPacket $negativeFull
        $neutralPacket = Get-JsonPacket $neutralFull
    } catch { $failure = [string]$_.Exception.Message }
}

$positiveRequired = @("schemaVersion", "packetType", "runId", "evidenceSha256", "directiveSha256", "candidateGoal", "validatedAssumptions", "reusableAssets", "expectedUserValue", "minimalVerification", "evidenceIds", "unknowns")
$negativeRequired = @("schemaVersion", "packetType", "runId", "evidenceSha256", "directiveSha256", "challengedGoal", "falsifiers", "counterExamples", "authorityRisks", "safetyRisks", "missingEvidence", "smallestDisconfirmingProbe", "evidenceIds")
$neutralRequired = @("schemaVersion", "packetType", "runId", "evidenceSha256", "directiveSha256", "positivePacketSha256", "negativePacketSha256", "orderABVerdict", "orderBAVerdict", "orderStable", "verdict", "sourceMutationVerdict", "selectedOrRewrittenGoal", "goalScoreComponents", "goalScore", "decisiveEvidence", "rejectedClaims", "nextSingleProof", "confidence", "rewrittenDirective")
if ($failure -eq "none" -and ((-not (Test-Fields $positivePacket.value $positiveRequired)) -or (-not (Test-Fields $negativePacket.value $negativeRequired)) -or (-not (Test-Fields $neutralPacket.value $neutralRequired)))) {
    $failure = "packet-schema-invalid"
}
if ($failure -eq "none") {
    $p = $positivePacket.value; $n = $negativePacket.value; $u = $neutralPacket.value
    if ($p.schemaVersion -ne "awx.tri_query.positive.v1" -or $p.packetType -ne "POSITIVE_QUERY" -or
        $n.schemaVersion -ne "awx.tri_query.negative.v1" -or $n.packetType -ne "NEGATIVE_QUERY" -or
        $u.schemaVersion -ne "awx.tri_query.neutral.v1" -or $u.packetType -ne "NEUTRAL_QUERY") {
        $failure = "packet-schema-invalid"
    }
}
if ($failure -eq "none") {
    foreach ($packet in @($p, $n, $u)) {
        if ($packet.runId -ne $manifest.runId -or $packet.evidenceSha256 -ne $manifest.evidenceSha256 -or $packet.directiveSha256 -ne $manifest.directiveSha256) {
            $failure = "input-hash-mismatch"; break
        }
    }
}
if ($failure -eq "none") {
    $positiveHash = Get-Sha256File $positiveFull
    $negativeHash = Get-Sha256File $negativeFull
    if ($u.positivePacketSha256 -ne $positiveHash -or $u.negativePacketSha256 -ne $negativeHash) { $failure = "packet-hash-mismatch" }
}
if ($failure -eq "none") {
    $skillRoot = Split-Path -Parent $PSScriptRoot
    $currentRoleHashes = [ordered]@{
        positive = Get-Sha256File (Resolve-ProviderPath (Join-Path $skillRoot "references\tri-positive-agent.md"))
        negative = Get-Sha256File (Resolve-ProviderPath (Join-Path $skillRoot "references\tri-negative-agent.md"))
        neutral = Get-Sha256File (Resolve-ProviderPath (Join-Path $skillRoot "references\tri-neutral-agent.md"))
    }
    if (-not (Test-Fields -Value $manifest -Fields @("rolePromptHashes")) -or
        $manifest.rolePromptHashes.positive -ne $currentRoleHashes.positive -or
        $manifest.rolePromptHashes.negative -ne $currentRoleHashes.negative -or
        $manifest.rolePromptHashes.neutral -ne $currentRoleHashes.neutral) {
        $failure = "role-prompt-hash-mismatch"
    }
}
if ($failure -eq "none" -and ((-not [bool]$u.orderStable) -or $u.orderABVerdict -ne $u.orderBAVerdict -or $u.verdict -ne $u.orderABVerdict)) {
    $failure = "neutral-order-unstable"
}
$score = $null
if ($failure -eq "none") {
    $scoreResult = Measure-AwxGoalScore -Components $u.goalScoreComponents -ProvidedScore $u.goalScore
    $score = $scoreResult.computedScore
    if (-not $scoreResult.valid) { $failure = [string]$scoreResult.failureClassification }
}
if ($failure -eq "none" -and $u.verdict -eq "APPLY" -and $score -lt 50) { $failure = "neutral-score-below-threshold" }
if ($failure -eq "none" -and $u.sourceMutationVerdict -ne $manifest.sourceMutationVerdict) { $failure = "source-verdict-mismatch" }
if ($failure -eq "none" -and (@("APPLY", "HOLD", "REJECT") -notcontains [string]$u.verdict)) { $failure = "neutral-verdict-invalid" }
if ($failure -eq "none" -and (@("L", "M", "H") -notcontains [string]$u.confidence)) { $failure = "neutral-confidence-invalid" }

$secretHits = 0
if ($positivePacket) { $secretHits += $SecretPattern.Matches($positivePacket.text).Count }
if ($negativePacket) { $secretHits += $SecretPattern.Matches($negativePacket.text).Count }
if ($neutralPacket) { $secretHits += $SecretPattern.Matches($neutralPacket.text).Count }
if ($failure -eq "none" -and $secretHits -gt 0) { $failure = "packet-secret-risk" }
if ($failure -eq "none" -and $u.verdict -eq "APPLY" -and [string]::IsNullOrWhiteSpace([string]$u.rewrittenDirective)) { $failure = "neutral-rewrite-missing" }
if ($failure -eq "none" -and $u.verdict -eq "APPLY") {
    $requiredRewriteTokens = @(
        "scanner", "sourceMutationVerdict", "mutationAllowed=false", "Desktop",
        "RED", "GREEN", "PromptBuilder.build(PromptContext)", "1.0.1",
        "rollback", "desktopFinalProof=evidence_needed"
    )
    foreach ($token in $requiredRewriteTokens) {
        if (([string]$u.rewrittenDirective).IndexOf($token, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $failure = "rewrite-contract-missing"; break
        }
    }
}

$candidatePath = Join-Path $runRoot "rewritten.directive.candidate.md"
if (Test-Path -LiteralPath $candidatePath) { Remove-Item -LiteralPath $candidatePath -Force }
if ($failure -ne "none") {
    $decision = Write-FinalDecision -RunRoot $runRoot -RunIdentifier ([string]$manifest.runId) -RewriteVerdict "HOLD" -SourceMutationVerdict ([string]$manifest.sourceMutationVerdict) -Failure $failure -OrderStable $false -GoalScore $score -SecretHits $secretHits
    $decision | ConvertTo-Json -Depth 6
    exit 2
}

$candidateHash = ""
if ($u.verdict -eq "APPLY") {
    Write-Utf8File -Path $candidatePath -Content ([string]$u.rewrittenDirective)
    $candidateHash = Get-Sha256File $candidatePath
}
$decision = Write-FinalDecision -RunRoot $runRoot -RunIdentifier ([string]$manifest.runId) -RewriteVerdict ([string]$u.verdict) -SourceMutationVerdict ([string]$u.sourceMutationVerdict) -Failure "none" -OrderStable ([bool]$u.orderStable) -GoalScore $score -SecretHits 0 -CandidateHash $candidateHash
$decision | ConvertTo-Json -Depth 6
exit 0

param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).ProviderPath
)

$ErrorActionPreference = "Stop"
$script:Pass = 0
$script:Fail = 0

function Test-Contract {
    param([string]$Name, [bool]$Condition, [string]$Detail = "")
    if ($Condition) { $script:Pass++; Write-Host "[PASS] $Name" }
    else { $script:Fail++; Write-Host "[FAIL] $Name $Detail" }
}

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    Write-Utf8File -Path $Path -Content ($Value | ConvertTo-Json -Depth 12)
}

function Invoke-Postprocess {
    param([string[]]$Arguments)
    $all = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $tool) + $Arguments
    $output = & powershell @all 2>&1
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; text = ($output -join "`n") }
}

$skillRoot = Join-Path $Root ".agents\skills\demo1-memory-integrity-autopatch"
$tool = Join-Path $skillRoot "scripts\tri_query_directive_postprocess.ps1"
$positivePrompt = Join-Path $skillRoot "references\tri-positive-agent.md"
$negativePrompt = Join-Path $skillRoot "references\tri-negative-agent.md"
$neutralPrompt = Join-Path $skillRoot "references\tri-neutral-agent.md"
$contract = Join-Path $skillRoot "references\tri-query-postprocess.md"
$skillFile = Join-Path $skillRoot "SKILL.md"
$registeredPrompt = Join-Path $Root "agent-prompts\agents\demo1_context_contamination_scout_directive\system_ko.md"

Test-Contract "tri-query tool exists" (Test-Path -LiteralPath $tool)
Test-Contract "positive role prompt exists" (Test-Path -LiteralPath $positivePrompt)
Test-Contract "negative role prompt exists" (Test-Path -LiteralPath $negativePrompt)
Test-Contract "neutral role prompt exists" (Test-Path -LiteralPath $neutralPrompt)
Test-Contract "tri-query packet contract exists" (Test-Path -LiteralPath $contract)
if (Test-Path -LiteralPath $skillFile) {
    $skillText = Get-Content -LiteralPath $skillFile -Raw -Encoding UTF8
    Test-Contract "skill routes to tri-query postprocess" ($skillText.Contains("tri_query_directive_postprocess.ps1"))
    Test-Contract "skill names all three agent roles" ($skillText.Contains("POSITIVE_QUERY") -and $skillText.Contains("NEGATIVE_QUERY") -and $skillText.Contains("NEUTRAL_QUERY"))
}
if (Test-Path -LiteralPath $registeredPrompt) {
    $promptText = Get-Content -LiteralPath $registeredPrompt -Raw -Encoding UTF8
    Test-Contract "registered Desktop prompt continues into tri-query postprocess" ($promptText.Contains("tri_query_directive_postprocess.ps1"))
}

if (Test-Path -LiteralPath $tool) {
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-tri-query-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    try {
        $fixtureRoot = Join-Path $temp "repo"
        $evidencePath = Join-Path $fixtureRoot "data\agent-handoff\memory-integrity\latest.json"
        $directivePath = Join-Path $fixtureRoot "agent-prompts\directive.md"
        $runDir = Join-Path $fixtureRoot "data\agent-handoff\memory-integrity\tri-query\run-001"
        Write-JsonFile $evidencePath ([ordered]@{
            schemaVersion = "awx.memory_integrity.feedback.v1"
            verdict = "HOLD"
            failureClassification = "desktop-owner-or-root-unproven"
            mutationAllowed = $false
            patchQueue = @([ordered]@{ priority = 1; id = "memory-checksum-stamp-verify" })
        })
        Write-Utf8File $directivePath "# Original directive`nKeep this text unchanged.`n"
        $originalHash = (Get-FileHash -LiteralPath $directivePath -Algorithm SHA256).Hash.ToLowerInvariant()

        $prepare = Invoke-Postprocess @(
            "-Mode", "Prepare", "-Root", $fixtureRoot,
            "-EvidencePath", $evidencePath, "-DirectivePath", $directivePath,
            "-RunDirectory", $runDir, "-RunId", "run-001"
        )
        Test-Contract "prepare exits successfully" ($prepare.exitCode -eq 0) $prepare.text
        $manifestPath = Join-Path $runDir "input-manifest.json"
        $dispatchPath = Join-Path $runDir "dispatch-plan.json"
        Test-Contract "prepare writes immutable input manifest" (Test-Path -LiteralPath $manifestPath)
        Test-Contract "prepare writes dispatch plan" (Test-Path -LiteralPath $dispatchPath)
        Test-Contract "prepare writes positive request" (Test-Path -LiteralPath (Join-Path $runDir "positive.request.json"))
        Test-Contract "prepare writes negative request" (Test-Path -LiteralPath (Join-Path $runDir "negative.request.json"))
        Test-Contract "prepare writes neutral request" (Test-Path -LiteralPath (Join-Path $runDir "neutral.request.json"))

        if (Test-Path -LiteralPath $manifestPath) {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $dispatch = Get-Content -LiteralPath $dispatchPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Test-Contract "manifest is read-only" (-not $manifest.mutationAllowed)
            Test-Contract "manifest binds evidence hash" ([string]$manifest.evidenceSha256 -eq (Get-FileHash -LiteralPath $evidencePath -Algorithm SHA256).Hash.ToLowerInvariant())
            Test-Contract "manifest binds directive hash" ([string]$manifest.directiveSha256 -eq $originalHash)
            Test-Contract "positive and negative are parallel stage" ((@($dispatch.stages[0].roles) -join ",") -eq "POSITIVE_QUERY,NEGATIVE_QUERY")
            Test-Contract "neutral is a dependent stage" ((@($dispatch.stages[1].roles) -join ",") -eq "NEUTRAL_QUERY")

            $positivePath = Join-Path $runDir "positive.packet.json"
            $negativePath = Join-Path $runDir "negative.packet.json"
            $neutralPath = Join-Path $runDir "neutral.packet.json"
            Write-JsonFile $positivePath ([ordered]@{
                schemaVersion = "awx.tri_query.positive.v1"; packetType = "POSITIVE_QUERY"; runId = "run-001"
                evidenceSha256 = $manifest.evidenceSha256; directiveSha256 = $manifest.directiveSha256
                candidateGoal = "Create a bounded checksum directive"; validatedAssumptions = @("existing scanner is reusable")
                reusableAssets = @("memory-integrity scanner"); expectedUserValue = "reproducible review"
                minimalVerification = @("focused contract test"); evidenceIds = @("scanner:latest"); unknowns = @("Desktop final proof")
            })
            Write-JsonFile $negativePath ([ordered]@{
                schemaVersion = "awx.tri_query.negative.v1"; packetType = "NEGATIVE_QUERY"; runId = "run-001"
                evidenceSha256 = $manifest.evidenceSha256; directiveSha256 = $manifest.directiveSha256
                challengedGoal = "Do not mutate from an SMB session"; falsifiers = @("Desktop-local proof changes owner verdict")
                counterExamples = @("UNC ownership mismatch"); authorityRisks = @("non-owner mutation")
                safetyRisks = @("raw context leak"); missingEvidence = @("Desktop focused tests")
                smallestDisconfirmingProbe = "rerun from Desktop-local root"; evidenceIds = @("scanner:latest")
            })
            $positiveHash = (Get-FileHash -LiteralPath $positivePath -Algorithm SHA256).Hash.ToLowerInvariant()
            $negativeHash = (Get-FileHash -LiteralPath $negativePath -Algorithm SHA256).Hash.ToLowerInvariant()
            $rewritten = @'
# Rewritten candidate
Run the existing scanner read-only before any patch. Keep sourceMutationVerdict=HOLD and mutationAllowed=false until the Desktop owner gates pass.
Observe focused RED and GREEN. Preserve PromptBuilder.build(PromptContext), LangChain4j 1.0.1, rollback, and desktopFinalProof=evidence_needed.
'@
            Write-JsonFile $neutralPath ([ordered]@{
                schemaVersion = "awx.tri_query.neutral.v1"; packetType = "NEUTRAL_QUERY"; runId = "run-001"
                evidenceSha256 = $manifest.evidenceSha256; directiveSha256 = $manifest.directiveSha256
                positivePacketSha256 = $positiveHash; negativePacketSha256 = $negativeHash
                orderABVerdict = "APPLY"; orderBAVerdict = "APPLY"; orderStable = $true
                verdict = "APPLY"; sourceMutationVerdict = "HOLD"; selectedOrRewrittenGoal = "Produce a safe candidate directive"
                goalScoreComponents = [ordered]@{
                    evidenceStrength = 0.8; causalStrength = 0.6; verificationFeasibility = 0.9
                    userValue = 0.8; reversibility = 0.9; costEfficiency = 0.8; timeFit = 0.8
                    blastRadius = 0.1; ambiguity = 0.2; authorityOrSafetyExpansion = 0.0
                }
                goalScore = 73.5; decisiveEvidence = @("scanner:latest"); rejectedClaims = @("Notebook final proof")
                nextSingleProof = "Desktop-local rerun"; confidence = "M"; rewrittenDirective = $rewritten
            })

            $finalize = Invoke-Postprocess @(
                "-Mode", "Finalize", "-Root", $fixtureRoot, "-RunDirectory", $runDir,
                "-PositivePacketPath", $positivePath, "-NegativePacketPath", $negativePath,
                "-NeutralPacketPath", $neutralPath
            )
            Test-Contract "valid packets finalize successfully" ($finalize.exitCode -eq 0) $finalize.text
            $candidatePath = Join-Path $runDir "rewritten.directive.candidate.md"
            $decisionPath = Join-Path $runDir "final.decision.json"
            Test-Contract "finalizer writes candidate, not active directive" ((Test-Path -LiteralPath $candidatePath) -and ((Get-Content -LiteralPath $candidatePath -Raw -Encoding UTF8) -eq $rewritten))
            Test-Contract "original directive remains byte-identical" ((Get-FileHash -LiteralPath $directivePath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $originalHash)
            $decision = Get-Content -LiteralPath $decisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Test-Contract "decision preserves source HOLD" ($decision.sourceMutationVerdict -eq "HOLD")
            Test-Contract "decision never authorizes mutation" (-not $decision.mutationAllowed)
            Test-Contract "finalizer recomputes the official goal score" ([double]$decision.goalScore -eq 73.5)

            Remove-Item -LiteralPath $candidatePath -Force
            $neutral = Get-Content -LiteralPath $neutralPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $neutral.orderBAVerdict = "HOLD"
            $neutral.orderStable = $false
            Write-JsonFile $neutralPath $neutral
            $unstable = Invoke-Postprocess @(
                "-Mode", "Finalize", "-Root", $fixtureRoot, "-RunDirectory", $runDir,
                "-PositivePacketPath", $positivePath, "-NegativePacketPath", $negativePath,
                "-NeutralPacketPath", $neutralPath
            )
            Test-Contract "order instability fails closed" ($unstable.exitCode -ne 0)
            Test-Contract "order instability emits no candidate" (-not (Test-Path -LiteralPath $candidatePath))
            $holdDecision = Get-Content -LiteralPath $decisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Test-Contract "order instability is classified" ($holdDecision.failureClassification -eq "neutral-order-unstable")

            $neutral.orderABVerdict = "APPLY"; $neutral.orderBAVerdict = "APPLY"; $neutral.orderStable = $true
            $neutral.positivePacketSha256 = "0" * 64
            Write-JsonFile $neutralPath $neutral
            $hashMismatch = Invoke-Postprocess @(
                "-Mode", "Finalize", "-Root", $fixtureRoot, "-RunDirectory", $runDir,
                "-PositivePacketPath", $positivePath, "-NegativePacketPath", $negativePath,
                "-NeutralPacketPath", $neutralPath
            )
            Test-Contract "packet hash mismatch fails closed" ($hashMismatch.exitCode -ne 0)
            $hashDecision = Get-Content -LiteralPath $decisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Test-Contract "packet hash mismatch is classified" ($hashDecision.failureClassification -eq "packet-hash-mismatch")

            $neutral.positivePacketSha256 = $positiveHash
            $neutral.goalScore = 72
            Write-JsonFile $neutralPath $neutral
            $scoreMismatch = Invoke-Postprocess @(
                "-Mode", "Finalize", "-Root", $fixtureRoot, "-RunDirectory", $runDir,
                "-PositivePacketPath", $positivePath, "-NegativePacketPath", $negativePath,
                "-NeutralPacketPath", $neutralPath
            )
            Test-Contract "agent-supplied score mismatch fails closed" ($scoreMismatch.exitCode -ne 0)
            $scoreDecision = Get-Content -LiteralPath $decisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Test-Contract "score mismatch is classified" ($scoreDecision.failureClassification -eq "goal-score-mismatch")

            $neutral.goalScore = 73.5
            $neutral.rewrittenDirective = "# Missing safety contract"
            Write-JsonFile $neutralPath $neutral
            $contractMissing = Invoke-Postprocess @(
                "-Mode", "Finalize", "-Root", $fixtureRoot, "-RunDirectory", $runDir,
                "-PositivePacketPath", $positivePath, "-NegativePacketPath", $negativePath,
                "-NeutralPacketPath", $neutralPath
            )
            Test-Contract "rewrite missing safety gates fails closed" ($contractMissing.exitCode -ne 0)
            $contractDecision = Get-Content -LiteralPath $decisionPath -Raw -Encoding UTF8 | ConvertFrom-Json
            Test-Contract "missing safety gates are classified" ($contractDecision.failureClassification -eq "rewrite-contract-missing")

            $fakeSecret = "sk-" + ("Z" * 24)
            $neutral.rewrittenDirective = "unsafe $fakeSecret"
            Write-JsonFile $neutralPath $neutral
            $secret = Invoke-Postprocess @(
                "-Mode", "Finalize", "-Root", $fixtureRoot, "-RunDirectory", $runDir,
                "-PositivePacketPath", $positivePath, "-NegativePacketPath", $negativePath,
                "-NeutralPacketPath", $neutralPath
            )
            Test-Contract "secret-bearing rewrite fails closed" ($secret.exitCode -ne 0)
            $secretDecisionText = Get-Content -LiteralPath $decisionPath -Raw -Encoding UTF8
            Test-Contract "decision output never echoes secret" (-not $secretDecisionText.Contains($fakeSecret))
        }
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

Write-Host "[SUMMARY] pass=$script:Pass fail=$script:Fail"
if ($script:Fail -gt 0) { exit 1 }

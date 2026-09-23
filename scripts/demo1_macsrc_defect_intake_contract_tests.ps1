param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).ProviderPath
)

$ErrorActionPreference = "Stop"
$sut = Join-Path $Root ".agents\skills\demo1-macsrc-defect-intake\scripts\prepare_defect_intent.ps1"
$autograderProbe = Join-Path $Root ".agents\skills\demo1-macsrc-defect-intake\scripts\prepare_autograder_probe.ps1"
$autograderAdvance = Join-Path $Root ".agents\skills\demo1-macsrc-defect-intake\scripts\advance_autograder_probe.ps1"
if (-not (Test-Path -LiteralPath $sut -PathType Leaf)) {
    Write-Host "[FAIL] prepare_defect_intent.ps1 is missing"
    exit 1
}
if (-not (Test-Path -LiteralPath $autograderProbe -PathType Leaf)) {
    Write-Host "[FAIL] prepare_autograder_probe.ps1 is missing"
    exit 1
}

$script:passed = 0
function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Name)
    if ($Actual -ne $Expected) {
        throw "$Name expected=<$Expected> actual=<$Actual>"
    }
    $script:passed++
    Write-Host "[PASS] $Name"
}

function Assert-True {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw $Name }
    $script:passed++
    Write-Host "[PASS] $Name"
}

function Invoke-ExpectedFailure {
    param([scriptblock]$Action, [string]$Pattern, [string]$Name)
    $message = $null
    try { & $Action } catch { $message = $_.Exception.Message }
    Assert-True ($message -match $Pattern) $Name
}

function Write-Utf8Json {
    param([string]$Path, [System.Collections.IDictionary]$Value)
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 14) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
}

function Write-ReadyJson {
    param([string]$Path, [System.Collections.IDictionary]$Value)
    Write-Utf8Json $Path $Value
    $sha = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($Path + '.sha256', $sha + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($Path + '.ready', $sha + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    return $sha
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-intake-test-" + [guid]::NewGuid().ToString("N"))
try {
    $targetDir = Join-Path $tempRoot "main\java\example"
    $outputRel = "data\agent-handoff\macsrc-defect-intake\intake-red-001\intent.json"
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tempRoot "settings.gradle") -Encoding UTF8 -Value "rootProject.name = 'fixture'"
    Set-Content -LiteralPath (Join-Path $targetDir "Foo.java") -Encoding UTF8 -Value "class Foo {}"
    New-Item -ItemType Directory -Path (Join-Path $tempRoot 'tests') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tempRoot 'tests\test_foo.py') -Encoding UTF8 -Value 'def test_foo(): assert False, "expected failure"'

    & $sut -Root $tempRoot -RunId "intake-red-001" `
        -DefectClass "silent-catch" -EvidenceIds "E1" `
        -TargetFiles "main/java/example/Foo.java" `
        -WatchRoots "main/java/example" `
        -BoundaryEvidenceFiles "settings.gradle" `
        -BoundaryProofType "GradleSourceSet" `
        -RedCommand ".\gradlew.bat test --tests FooTest" `
        -ExpectedRedSignal "expected failure" `
        -GreenCommand ".\gradlew.bat test --tests FooTest" `
        -OutputPath $outputRel | Out-Null

    $output = Join-Path $tempRoot $outputRel
    $intent = Get-Content -LiteralPath $output -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $intent.schemaVersion "awx.patch-intent.v1" "schema is stable"
    Assert-Equal $intent.stage "PREPARED" "stage is machine readable"
    Assert-Equal $intent.mutationAllowed $false "intake never mutates"
    Assert-Equal $intent.runId "intake-red-001" "run id is bound"
    Assert-Equal $intent.targetFiles[0] "main/java/example/Foo.java" "target is normalized"
    Assert-Equal $intent.watchRoots[0] "main/java/example" "watch root is normalized"
    Assert-Equal $intent.boundaryEvidence[0].path "settings.gradle" "boundary path is recorded"
    Assert-True ($intent.boundaryEvidence[0].sha256 -match '^[a-f0-9]{64}$') "boundary hash is recorded"
    Assert-Equal $intent.boundaryProofType "GradleSourceSet" "boundary semantics are explicit"
    Assert-Equal $intent.supabase.mode "NOT_APPLICABLE" "Supabase defaults to not applicable"
    Assert-Equal $intent.nextAction "REPRODUCE_RED" "next action is bounded"
    Assert-True (Test-Path -LiteralPath ($output + ".sha256")) "intent hash sidecar exists"
    Assert-True (Test-Path -LiteralPath ($output + ".ready")) "ready marker is published last"
    Assert-True (-not (Test-Path -LiteralPath ($output + ".tmp"))) "atomic temp file is absent"

    $autograderEvidenceRel = 'data/agent-handoff/macsrc-defect-intake/autograder-red-001/autograder-evidence.json'
    $autograderEvidencePath = Join-Path $tempRoot $autograderEvidenceRel
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($autograderEvidencePath)) -Force | Out-Null
    [IO.File]::WriteAllText($autograderEvidencePath, (([ordered]@{
        schemaVersion = 'awx.autograder.evidence.v1'; runId = 'autograder-red-001'
        exitCode = 1; failingTestCount = 1; failureClass = 'focused-test-failed'
        outputSha256 = ('e' * 64); mutationAllowed = $false
    } | ConvertTo-Json -Depth 6) + [Environment]::NewLine), (New-Object Text.UTF8Encoding($false)))
    $probeResult = & $autograderProbe -Root $tempRoot -RunId 'autograder-red-001' -AutograderEvidenceFile $autograderEvidenceRel | ConvertFrom-Json
    $probeDir = Join-Path $tempRoot 'data\agent-handoff\macsrc-defect-intake\autograder-red-001\autograder-probe'
    $probeManifest = Get-Content -LiteralPath (Join-Path $probeDir 'probe.manifest.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    $probeDispatch = Get-Content -LiteralPath (Join-Path $probeDir 'dispatch-plan.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $probeResult.nextAction 'DISPATCH_POSITIVE_NEGATIVE_PARALLEL' 'autograder starts the bounded probe routine'
    Assert-Equal (@($probeDispatch.stages[0].roles) -join '>') 'POSITIVE_QUERY>NEGATIVE_QUERY' 'autograder positive and negative probes are parallel'
    Assert-Equal (@($probeDispatch.stages[1].roles) -join '>') 'NEUTRAL_QUERY' 'autograder neutral probe waits for both packets'
    Assert-Equal $probeDispatch.stages[2].command '.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1' 'autograder decision stage names the executable tool'
    Assert-Equal $probeDispatch.stages[2].mode 'Finalize' 'autograder decision stage names the deterministic mode'
    Assert-Equal $probeDispatch.stages[3].mode 'GradeSandbox' 'autograder dispatch inserts the Docker RED probe before promotion'
    Assert-Equal $probeDispatch.stages[3].gate 'decision=APPLY and ready intent-spec.json and ready Docker job' 'Docker RED probe has immutable-input gates'
    Assert-Equal $probeDispatch.stages[4].mode 'PromoteIntent' 'autograder dispatch exposes the bounded intent promotion mode'
    Assert-Equal $probeDispatch.stages[4].gate 'ready COMPLETE failing grade transition with expected signal' 'intent promotion requires a proven sandbox RED'
    Assert-Equal $probeDispatch.stages[5].mode 'PlanSession' 'autograder dispatch exposes guard session planning'
    Assert-Equal $probeDispatch.stages[5].gate 'ready promotion.json and derived ready RED evidence' 'session planning consumes derived RED evidence'
    Assert-Equal $probeManifest.evidenceSha256 (Get-FileHash -LiteralPath $autograderEvidencePath -Algorithm SHA256).Hash.ToLowerInvariant() 'autograder probe binds evidence bytes'
    Assert-Equal $probeManifest.mutationAllowed $false 'autograder probe cannot patch source'
    Assert-True (Test-Path -LiteralPath (Join-Path $probeDir 'probe.manifest.json.ready')) 'autograder probe publishes ready last'

    $positivePacketPath = Join-Path $probeDir 'positive.packet.json'
    $negativePacketPath = Join-Path $probeDir 'negative.packet.json'
    $neutralPacketPath = Join-Path $probeDir 'neutral.packet.json'
    Write-Utf8Json $positivePacketPath ([ordered]@{
        schemaVersion = 'awx.autograder.probe-positive.v1'; packetType = 'POSITIVE_QUERY'
        subjectRunId = 'autograder-red-001'; evidenceSha256 = $probeManifest.evidenceSha256
        candidateGoal = 'normalize one focused failure'; validatedAssumptions = @('focused failure exists')
        reusableAssets = @('prepare_defect_intent'); expectedUserValue = 'bounded intake candidate'
        minimalVerification = 'reproduce the exact focused RED'; evidenceIds = @('autograderEvidence')
        unknowns = @('active target boundary'); mutationAllowed = $false
    })
    Write-Utf8Json $negativePacketPath ([ordered]@{
        schemaVersion = 'awx.autograder.probe-negative.v1'; packetType = 'NEGATIVE_QUERY'
        subjectRunId = 'autograder-red-001'; evidenceSha256 = $probeManifest.evidenceSha256
        challengedGoal = 'do not infer a patch from one failure'; falsifiers = @('failure is transient')
        counterExamples = @('wrong source set'); authorityRisks = @('target path invented')
        safetyRisks = @('source mutation before RED'); missingEvidence = @('intent specification')
        smallestDisconfirmingProbe = 're-run the focused test'; evidenceIds = @('autograderEvidence')
        mutationAllowed = $false
    })
    $positivePacketSha = (Get-FileHash -LiteralPath $positivePacketPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $negativePacketSha = (Get-FileHash -LiteralPath $negativePacketPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $autograderScoreComponents = [ordered]@{
        evidenceStrength = 0.8; causalStrength = 0.6; verificationFeasibility = 0.9
        userValue = 0.8; reversibility = 0.9; costEfficiency = 0.8; timeFit = 0.8
        blastRadius = 0.1; ambiguity = 0.2; authorityOrSafetyExpansion = 0.0
    }
    $autograderScoreEvidenceIds = [ordered]@{
        evidenceStrength = @('autograderEvidence'); causalStrength = @('autograderEvidence')
        verificationFeasibility = @('autograderEvidence'); userValue = @('autograderEvidence')
        reversibility = @('autograderEvidence'); costEfficiency = @('autograderEvidence')
        timeFit = @('autograderEvidence'); blastRadius = @('autograderEvidence')
        ambiguity = @('autograderEvidence'); authorityOrSafetyExpansion = @('autograderEvidence')
    }
    Write-Utf8Json $neutralPacketPath ([ordered]@{
        schemaVersion = 'awx.autograder.probe-neutral.v1'; packetType = 'NEUTRAL_QUERY'
        subjectRunId = 'autograder-red-001'; evidenceSha256 = $probeManifest.evidenceSha256
        positivePacketSha256 = $positivePacketSha; negativePacketSha256 = $negativePacketSha
        orderABVerdict = 'APPLY'; orderBAVerdict = 'APPLY'; orderStable = $true; verdict = 'APPLY'
        selectedOrRewrittenGoal = 'prepare one bounded intake candidate'
        goalScoreComponents = $autograderScoreComponents; goalScoreEvidenceIds = $autograderScoreEvidenceIds
        goalScore = 73.5; decisiveEvidence = @('autograderEvidence'); rejectedClaims = @('patch is authorized')
        nextSingleProof = 'collect an immutable intent specification'; confidence = 'M'
        mutationAllowed = $false; secretPatternHits = 0
    })
    $advanceResult = & $autograderAdvance -Mode Finalize -Root $tempRoot -RunId 'autograder-red-001' | ConvertFrom-Json
    $decisionPath = Join-Path $probeDir 'decision.json'
    $decision = Get-Content -LiteralPath $decisionPath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $advanceResult.status 'FINALIZED' 'autograder finalizer returns a machine status'
    Assert-Equal $decision.schemaVersion 'awx.autograder.intake-decision.v1' 'autograder decision schema is stable'
    Assert-Equal $decision.verdict 'APPLY' 'order-stable evidence can open intent preparation'
    Assert-Equal $decision.goalScore 73.5 'autograder decision publishes the computed score'
    Assert-Equal $decision.nextAction 'COLLECT_INTENT_SPEC' 'autograder finalizer stops before intent promotion'
    Assert-Equal $decision.mutationAllowed $false 'autograder decision never mutates source'
    Assert-True (Test-Path -LiteralPath ($decisionPath + '.ready')) 'autograder decision publishes ready last'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $tempRoot 'data\agent-handoff\macsrc-defect-intake\autograder-red-001\intent.json'))) 'Finalize does not create a PatchIntent'

    $decisionSha = (Get-FileHash -LiteralPath $decisionPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $intentSpecPath = Join-Path $probeDir 'intent-spec.json'
    $intentSpecSha = Write-ReadyJson $intentSpecPath ([ordered]@{
        schemaVersion = 'awx.autograder.intent-spec.v1'; subjectRunId = 'autograder-red-001'
        decisionSha256 = $decisionSha; defectClass = 'focused-test-failed'
        evidenceIds = @('autograderEvidence'); targetFiles = @('main/java/example/Foo.java')
        watchRoots = @('main/java/example'); boundaryEvidenceFiles = @('settings.gradle')
        boundaryProofType = 'GradleSourceSet'; redCommand = '.\gradlew.bat test --tests FooTest'
        expectedRedSignal = 'expected failure'; greenCommand = '.\gradlew.bat test --tests FooTest'
        mutationAllowed = $false
    })
    Invoke-ExpectedFailure {
        & $autograderAdvance -Mode PromoteIntent -Root $tempRoot -RunId 'autograder-red-001' | Out-Null
    } 'autograder-grade-transition-invalid' 'intent promotion rejects a missing Docker RED transition'

    $fakeDockerPy = Join-Path $tempRoot 'fake_grade_docker.py'
    $fakeDockerCmd = Join-Path $tempRoot 'docker.cmd'
    [IO.File]::WriteAllText($fakeDockerPy, @'
import io
import sys
import tarfile

args = sys.argv[1:]
if args and args[0] == "kill":
    raise SystemExit(0)
xml = b'<testsuite tests="1" failures="1" errors="0" skipped="0"/>'
payload = io.BytesIO()
with tarfile.open(fileobj=payload, mode="w") as archive:
    member = tarfile.TarInfo("junit.xml")
    member.size = len(xml)
    archive.addfile(member, io.BytesIO(xml))
print("expected failure", file=sys.stderr)
sys.stdout.buffer.write(payload.getvalue())
raise SystemExit(1)
'@, (New-Object Text.UTF8Encoding($false)))
    $python = (Get-Command python -ErrorAction Stop).Source
    [IO.File]::WriteAllText($fakeDockerCmd, "@echo off`r`n`"$python`" `"$fakeDockerPy`" %*`r`n", [Text.Encoding]::ASCII)
    $gradeJobRel = 'data/agent-handoff/docker-autograder/autograder-red-001/job.json'
    $gradeJobPath = Join-Path $tempRoot $gradeJobRel
    $gradeJobSha = Write-ReadyJson $gradeJobPath ([ordered]@{
        schemaVersion = 'awx.docker-autograder.job.v1'; runId = 'autograder-red-001'
        purpose = 'RED_PROBE'; profile = 'pytest'
        imageRef = 'example.invalid/awx-pytest@sha256:' + ('a' * 64)
        includePaths = @('main/java/example/Foo.java', 'tests/test_foo.py')
        testSelectors = @('tests/test_foo.py')
        declaredTestCommand = '.\gradlew.bat test --tests FooTest'
        expectedSignal = 'expected failure'; decisionSha256 = $decisionSha
        intentSpecSha256 = $intentSpecSha
        limits = [ordered]@{
            cpus = 0.5; memoryMb = 256; pids = 64; timeoutSeconds = 5
            maxFiles = 16; maxInputBytes = 1048576; maxLogBytes = 65536
            maxXmlBytes = 1048576; maxTestCases = 1000
        }
        networkMode = 'none'; pullPolicy = 'never'; mutationAllowed = $false
    })
    $gradeResult = & $autograderAdvance -Mode GradeSandbox -Root $tempRoot -RunId 'autograder-red-001' `
        -DockerBin $fakeDockerCmd -ContractTest | ConvertFrom-Json
    $gradeTransitionPath = Join-Path $probeDir 'grade-transition.json'
    $gradeTransition = Get-Content -LiteralPath $gradeTransitionPath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $gradeResult.status 'RED_PROVEN' 'Docker grade transition returns a machine status'
    Assert-Equal $gradeTransition.schemaVersion 'awx.autograder.grade-transition.v1' 'Docker grade transition schema is stable'
    Assert-Equal $gradeTransition.jobSha256 $gradeJobSha 'Docker grade transition binds immutable job bytes'
    Assert-Equal $gradeTransition.decisionSha256 $decisionSha 'Docker grade transition binds decision bytes'
    Assert-Equal $gradeTransition.intentSpecSha256 $intentSpecSha 'Docker grade transition binds intent specification bytes'
    Assert-Equal $gradeTransition.testVerdict 'FAIL' 'Docker RED must be a focused failure'
    Assert-Equal $gradeTransition.expectedSignalMatched $true 'Docker RED must match the intended signal'
    Assert-Equal $gradeTransition.nextAction 'PROMOTE_INTENT' 'Docker RED opens only intent promotion'
    Assert-True (Test-Path -LiteralPath ($gradeTransitionPath + '.ready')) 'Docker grade transition publishes ready last'

    $promoteResult = & $autograderAdvance -Mode PromoteIntent -Root $tempRoot -RunId 'autograder-red-001' | ConvertFrom-Json
    $promotedIntentPath = Join-Path $tempRoot 'data\agent-handoff\macsrc-defect-intake\autograder-red-001\intent.json'
    $promotionPath = Join-Path $probeDir 'promotion.json'
    $promotedIntent = Get-Content -LiteralPath $promotedIntentPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $promotion = Get-Content -LiteralPath $promotionPath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $promoteResult.status 'INTENT_PROMOTED' 'autograder promotion returns a machine status'
    Assert-Equal $promotedIntent.stage 'PREPARED' 'autograder promotion uses the existing PatchIntent contract'
    Assert-Equal $promotedIntent.nextAction 'REPRODUCE_RED' 'PatchIntent preserves its guarded-session contract'
    Assert-Equal $promotion.schemaVersion 'awx.autograder.intent-promotion.v1' 'promotion record schema is stable'
    Assert-Equal $promotion.decisionSha256 $decisionSha 'promotion binds the decision bytes'
    Assert-Equal $promotion.intentSpecSha256 $intentSpecSha 'promotion binds the intent specification bytes'
    Assert-Equal $promotion.gradeTransitionSha256 (Get-FileHash -LiteralPath $gradeTransitionPath -Algorithm SHA256).Hash.ToLowerInvariant() 'promotion binds the Docker grade transition bytes'
    Assert-Equal $promotion.intentSha256 (Get-FileHash -LiteralPath $promotedIntentPath -Algorithm SHA256).Hash.ToLowerInvariant() 'promotion binds the PatchIntent bytes'
    Assert-Equal $promotion.mutationAllowed $false 'intent promotion never mutates source'
    Assert-True (Test-Path -LiteralPath ($promotionPath + '.ready')) 'promotion record publishes ready last'

    $redEvidencePath = Join-Path $probeDir 'red-evidence.json'
    $redEvidenceSha = (Get-FileHash -LiteralPath $redEvidencePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $redEvidence = Get-Content -LiteralPath $redEvidencePath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $redEvidence.command '.\gradlew.bat test --tests FooTest' 'promotion derives the declared RED command'
    Assert-Equal $redEvidence.outputSha256 $gradeTransition.outputSha256 'promotion derives the bounded Docker output hash'
    Assert-True (Test-Path -LiteralPath ($redEvidencePath + '.ready')) 'derived RED evidence publishes ready last'
    $planResult = & $autograderAdvance -Mode PlanSession -Root $tempRoot -RunId 'autograder-red-001' | ConvertFrom-Json
    $transitionPath = Join-Path $probeDir 'session-transition.json'
    $transition = Get-Content -LiteralPath $transitionPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $guardPlan = Get-Content -LiteralPath $planResult.planPath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $planResult.status 'SESSION_PLANNED' 'autograder RED evidence opens only a guard plan'
    Assert-Equal $transition.schemaVersion 'awx.autograder.session-transition.v1' 'session transition schema is stable'
    Assert-Equal $transition.promotionSha256 (Get-FileHash -LiteralPath $promotionPath -Algorithm SHA256).Hash.ToLowerInvariant() 'session transition binds promotion bytes'
    Assert-Equal $transition.intentSha256 $promotion.intentSha256 'session transition binds PatchIntent bytes'
    Assert-Equal $transition.redEvidenceSha256 $redEvidenceSha 'session transition binds RED evidence bytes'
    Assert-Equal $transition.guardPlanSha256 (Get-FileHash -LiteralPath $planResult.planPath -Algorithm SHA256).Hash.ToLowerInvariant() 'session transition binds guard plan bytes'
    Assert-Equal $guardPlan.mutationAllowed $false 'guard planner remains non-mutating'
    Assert-Equal $transition.mutationAllowed $false 'session transition never mutates source'
    Assert-True (Test-Path -LiteralPath ($transitionPath + '.ready')) 'session transition publishes ready last'

    $rawAutograderRel = 'data/agent-handoff/macsrc-defect-intake/autograder-raw-001/autograder-evidence.json'
    $rawAutograderPath = Join-Path $tempRoot $rawAutograderRel
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($rawAutograderPath)) -Force | Out-Null
    [IO.File]::WriteAllText($rawAutograderPath, (([ordered]@{
        schemaVersion = 'awx.autograder.evidence.v1'; runId = 'autograder-raw-001'
        exitCode = 1; failingTestCount = 1; failureClass = 'focused-test-failed'
        outputSha256 = ('f' * 64); mutationAllowed = $false; rawOutput = 'forbidden raw log'
    } | ConvertTo-Json -Depth 6) + [Environment]::NewLine), (New-Object Text.UTF8Encoding($false)))
    Invoke-ExpectedFailure {
        & $autograderProbe -Root $tempRoot -RunId 'autograder-raw-001' -AutograderEvidenceFile $rawAutograderRel | Out-Null
    } 'autograder-evidence-field-not-allowed' 'autograder raw output fields fail closed'

    Invoke-ExpectedFailure {
        & $sut -Root $tempRoot -RunId "intake-red-001" `
            -DefectClass "silent-catch" -EvidenceIds "E1" `
            -TargetFiles "main/java/example/Foo.java" `
            -WatchRoots "main/java/example" `
            -BoundaryEvidenceFiles "settings.gradle" `
            -BoundaryProofType "GradleSourceSet" `
            -RedCommand "x" -ExpectedRedSignal "y" -GreenCommand "z" `
            -OutputPath $outputRel | Out-Null
    } 'intent-already-exists' "existing intent is immutable"

    Invoke-ExpectedFailure {
        & $sut -Root $tempRoot -RunId "intake-red-002" `
            -DefectClass "silent-catch" -EvidenceIds "E1" `
            -TargetFiles "../outside.java" -WatchRoots "main/java/example" `
            -BoundaryEvidenceFiles "settings.gradle" `
            -BoundaryProofType "GradleSourceSet" `
            -RedCommand "x" -ExpectedRedSignal "y" -GreenCommand "z" | Out-Null
    } 'path-outside-root' "path traversal is rejected"

    Invoke-ExpectedFailure {
        & $sut -Root $tempRoot -RunId "intake-red-003" `
            -DefectClass "provider-disabled" -EvidenceIds "E1" `
            -TargetFiles "main/java/example/Foo.java" `
            -WatchRoots "main/java/example" `
            -BoundaryEvidenceFiles "settings.gradle" `
            -BoundaryProofType "GradleSourceSet" `
            -RedCommand "Authorization: Bearer abcdefghijklmnop" `
            -ExpectedRedSignal "y" -GreenCommand "z" | Out-Null
    } 'secret-like-input' "secret-like input is rejected"

    Invoke-ExpectedFailure {
        & $sut -Root $tempRoot -RunId "intake-red-004" `
            -DefectClass "silent-catch" -EvidenceIds "E1" `
            -TargetFiles "main/java/example/Foo.java" `
            -WatchRoots "main/java/example" `
            -BoundaryEvidenceFiles "settings.gradle" `
            -BoundaryProofType "GradleSourceSet" `
            -RedCommand "x" -ExpectedRedSignal "y" -GreenCommand "z" `
            -OutputPath "main/java/example/injected-intent.json" | Out-Null
    } 'output-outside-handoff' "intent output cannot target an active source directory"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $targetDir "injected-intent.json"))) "rejected source output creates no file"

    $outsideRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-intake-outside-" + [guid]::NewGuid().ToString("N"))
    $junctionPath = Join-Path $tempRoot "handoff-escape"
    New-Item -ItemType Directory -Path $outsideRoot -Force | Out-Null
    New-Item -ItemType Junction -Path $junctionPath -Target $outsideRoot | Out-Null
    try {
        Invoke-ExpectedFailure {
            & $sut -Root $tempRoot -Mode Hold -RunId "intake-hold-escape" `
                -DefectClass "silent-catch" -FailureClass "source-boundary-unproven" `
                -EvidenceIds "E1" -OutputPath "handoff-escape/hold.json" | Out-Null
        } 'output-outside-handoff|reparse-path-rejected' "output cannot escape through an in-root junction"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $outsideRoot "hold.json"))) "reparse rejection creates no outside file"
    } finally {
        if (Test-Path -LiteralPath $junctionPath) { Remove-Item -LiteralPath $junctionPath -Force }
        if (Test-Path -LiteralPath $outsideRoot) { Remove-Item -LiteralPath $outsideRoot -Recurse -Force }
    }

    $holdRel = "data\agent-handoff\macsrc-defect-intake\intake-hold-001\hold.json"
    & $sut -Root $tempRoot -Mode Hold -RunId "intake-hold-001" `
        -DefectClass "silent-catch" -FailureClass "source-boundary-unproven" `
        -EvidenceIds "E1" `
        -OutputPath $holdRel | Out-Null
    $holdPath = Join-Path $tempRoot $holdRel
    $hold = Get-Content -LiteralPath $holdPath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $hold.schemaVersion "awx.patch-intake-hold.v1" "HOLD schema is stable"
    Assert-Equal $hold.stage "HOLD" "HOLD stage is machine readable"
    Assert-Equal $hold.candidateDefectClass "silent-catch" "HOLD preserves candidate class"
    Assert-Equal $hold.failureClass "source-boundary-unproven" "HOLD separates gate failure"
    Assert-Equal $hold.mutationAllowed $false "HOLD never mutates"
    Assert-Equal $hold.nextAction "COLLECT_BOUNDARY_EVIDENCE" "HOLD next action is singular"
    Assert-True (Test-Path -LiteralPath ($holdPath + ".ready")) "HOLD ready marker exists"

    Write-Host "[SUMMARY] passed=$script:passed failed=0"
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

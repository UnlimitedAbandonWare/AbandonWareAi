param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).ProviderPath
)

$ErrorActionPreference = 'Stop'
$sut = Join-Path $Root '.agents\skills\demo1-macsrc-patch-postprocessor\scripts\new_postprocess_packet.ps1'
$triPrepare = Join-Path $Root '.agents\skills\demo1-macsrc-patch-postprocessor\scripts\prepare_patch_tri_query.ps1'
if (-not (Test-Path -LiteralPath $sut -PathType Leaf)) {
    Write-Host '[FAIL] new_postprocess_packet.ps1 is missing'
    exit 1
}
if (-not (Test-Path -LiteralPath $triPrepare -PathType Leaf)) {
    Write-Host '[FAIL] prepare_patch_tri_query.ps1 is missing'
    exit 1
}
$intake = Join-Path $Root '.agents\skills\demo1-macsrc-defect-intake\scripts\prepare_defect_intent.ps1'
$script:passed = 0
function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Name)
    if ($Actual -ne $Expected) { throw "$Name expected=<$Expected> actual=<$Actual>" }
    $script:passed++
    Write-Host "[PASS] $Name"
}
function Assert-True {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw $Name }
    $script:passed++
    Write-Host "[PASS] $Name"
}
function Test-HashMapEquivalent {
    param([object]$Actual, [System.Collections.IDictionary]$Expected)
    $names = @($Actual.PSObject.Properties.Name)
    if ($names.Count -ne $Expected.Count) { return $false }
    foreach ($key in $Expected.Keys) {
        if ($names -notcontains [string]$key -or [string]$Actual.$key -ne [string]$Expected[$key]) { return $false }
    }
    return $true
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
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine), (New-Object Text.UTF8Encoding($false)))
}
function Write-ReadyJson {
    param([string]$Path, [System.Collections.IDictionary]$Value)
    Write-Utf8Json $Path $Value
    $sha = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($Path + '.sha256', $sha + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($Path + '.ready', $sha + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    return $sha
}
function Get-TextSha256 {
    param([string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}
function Invoke-Postprocess {
    param(
        [string]$FixtureRoot,
        [string]$VerificationRel,
        [string]$AfterRel,
        [string]$OutputRel,
        [string]$SupabaseRel = '',
        [string]$OutcomeRel = 'data/complete.json',
        [string]$TriQueryRel = 'data/tri-final.json',
        [string]$SessionRel = 'data/session.json',
        [string]$AutograderRel = 'data/agent-handoff/docker-autograder/post-red-001/result.json'
    )
    $arguments = @{
        Root = $FixtureRoot
        RunId = 'post-red-001'
        IntentFile = 'data/agent-handoff/macsrc-defect-intake/post-red-001/intent.json'
        SessionFile = $SessionRel
        OutcomeFile = $OutcomeRel
        VerificationFile = $VerificationRel
        BeforeIntegrityFile = 'data/before.json'
        AfterIntegrityFile = $AfterRel
        TriQueryDecisionFile = $TriQueryRel
        OutputPath = $OutputRel
    }
    if (-not [string]::IsNullOrWhiteSpace($SupabaseRel)) { $arguments.SupabaseEvidenceFile = $SupabaseRel }
    if (-not [string]::IsNullOrWhiteSpace($AutograderRel)) { $arguments.AutograderEvidenceFile = $AutograderRel }
    & $sut @arguments | Out-Null
    Get-Content -LiteralPath (Join-Path $FixtureRoot $OutputRel) -Encoding UTF8 -Raw | ConvertFrom-Json
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-post-test-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $tempRoot 'main\java\example') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tempRoot 'settings.gradle') -Encoding UTF8 -Value "rootProject.name = 'fixture'"
    $targetPath = Join-Path $tempRoot 'main\java\example\Foo.java'
    Set-Content -LiteralPath $targetPath -Encoding UTF8 -Value 'class Foo {}'
    & $intake -Root $tempRoot -RunId 'post-red-001' -DefectClass 'rls-regression' `
        -EvidenceIds 'E1' -TargetFiles 'main/java/example/Foo.java' `
        -WatchRoots 'main/java/example' -BoundaryEvidenceFiles 'settings.gradle' `
        -BoundaryProofType 'GradleSourceSet' -RedCommand 'red-command' `
        -ExpectedRedSignal 'expected failure' -GreenCommand 'green-command' `
        -SupabaseMode 'REQUIRED_READ_ONLY' `
        -OutputPath 'data/agent-handoff/macsrc-defect-intake/post-red-001/intent.json' | Out-Null

    $intentPath = Join-Path $tempRoot 'data\agent-handoff\macsrc-defect-intake\post-red-001\intent.json'
    $intentSha = (Get-FileHash -LiteralPath $intentPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $intentObject = Get-Content -LiteralPath $intentPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $session = [ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_session.v2'; runId = 'post-red-001'
        sourceWriteMode = 'MACSRC_SMB_DIRECT'; canonicalRoot = $tempRoot
        targets = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; existed = $true; preimageSha256 = $intentObject.targetPreimages[0].sha256 })
    }
    Write-Utf8Json (Join-Path $tempRoot 'data\session.json') $session
    $sessionSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\session.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath $targetPath -Encoding UTF8 -Value 'class Foo { int patched; }'
    $targetSha = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8Json (Join-Path $tempRoot 'data\verification-incomplete.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_verification.v1'; runId = 'post-red-001'
        sessionSha256 = $sessionSha; command = 'green-command'; exitCode = 0; targetPostimages = @()
    })
    Write-Utf8Json (Join-Path $tempRoot 'data\verification-complete.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_verification.v1'; runId = 'post-red-001'
        sessionSha256 = $sessionSha; command = 'green-command'; exitCode = 0
        targetPostimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; sha256 = $targetSha })
    })
    $verificationCompleteSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\verification-complete.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8Json (Join-Path $tempRoot 'data\complete.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_completion.v2'; state = 'COMPLETE'; runId = 'post-red-001'
        sessionSha256 = $sessionSha; changedFileCount = 1; undeclaredSourceChangeCount = 0
        rawSecretPatternHits = 0; verificationEvidenceSha256 = $verificationCompleteSha
        postimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; postimageSha256 = $targetSha; changed = $true })
    })
    Write-Utf8Json (Join-Path $tempRoot 'data\complete-bad-verification-hash.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_completion.v2'; state = 'COMPLETE'; runId = 'post-red-001'
        sessionSha256 = $sessionSha; changedFileCount = 1; undeclaredSourceChangeCount = 0
        rawSecretPatternHits = 0; verificationEvidenceSha256 = ('0' * 64)
        postimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; postimageSha256 = $targetSha; changed = $true })
    })
    Write-Utf8Json (Join-Path $tempRoot 'data\before.json') ([ordered]@{
        schemaVersion = 'awx.memory_integrity.feedback.v1'; mutationAllowed = $false; root = $tempRoot
        checksumProbe = [ordered]@{ seed = 100; populationCount = 10; sampleCount = 5 }
        observedMetrics = [ordered]@{ checksumMismatchRate = 0.2; contextContaminationRate = 0.3; desktopErrorRate = 0.4; pairedIntegrityContribution = 0.1 }
    })
    Write-Utf8Json (Join-Path $tempRoot 'data\after-different-seed.json') ([ordered]@{
        schemaVersion = 'awx.memory_integrity.feedback.v1'; mutationAllowed = $false; root = $tempRoot
        checksumProbe = [ordered]@{ seed = 101; populationCount = 10; sampleCount = 5 }
        observedMetrics = [ordered]@{ checksumMismatchRate = 0.1; contextContaminationRate = 0.2; desktopErrorRate = 0.2; pairedIntegrityContribution = 0.3 }
    })
    Write-Utf8Json (Join-Path $tempRoot 'data\after-same-seed.json') ([ordered]@{
        schemaVersion = 'awx.memory_integrity.feedback.v1'; mutationAllowed = $false; root = $tempRoot
        checksumProbe = [ordered]@{ seed = 100; populationCount = 10; sampleCount = 5 }
        observedMetrics = [ordered]@{ checksumMismatchRate = 0.1; contextContaminationRate = 0.2; desktopErrorRate = 0.2; pairedIntegrityContribution = 0.3 }
    })
    Write-Utf8Json (Join-Path $tempRoot 'data\supabase-readonly.json') ([ordered]@{
        schemaVersion = 'awx.supabase.readonly-evidence.v1'; authPresent = $true
        projectRefPresent = $true; projectRefHash = ('d' * 64); readOnly = $true
        featureGroups = @('database'); mutationPerformed = $false
    })
    $dockerDir = Join-Path $tempRoot 'data\agent-handoff\docker-autograder\post-red-001'
    $dockerJobPath = Join-Path $dockerDir 'job.json'
    $dockerResultPath = Join-Path $dockerDir 'result.json'
    $dockerJob = [ordered]@{
        schemaVersion = 'awx.docker-autograder.job.v1'; runId = 'post-red-001'
        purpose = 'GREEN_VERIFICATION'; profile = 'gradle-junit'
        imageRef = 'example.invalid/awx-gradle@sha256:' + ('a' * 64)
        includePaths = @('main/java/example/Foo.java', 'settings.gradle')
        testSelectors = @('FooTest'); declaredTestCommand = 'green-command'
        expectedSignal = ''; decisionSha256 = $verificationCompleteSha
        intentSpecSha256 = $intentSha
        limits = [ordered]@{
            cpus = 1.0; memoryMb = 512; pids = 128; timeoutSeconds = 30
            maxFiles = 32; maxInputBytes = 1048576; maxLogBytes = 65536
            maxXmlBytes = 1048576; maxTestCases = 1000
        }
        networkMode = 'none'; pullPolicy = 'never'; mutationAllowed = $false
    }
    $dockerJobSha = Write-ReadyJson $dockerJobPath $dockerJob
    $dockerIsolation = [ordered]@{
        sourceMode = 'local-temp-copy'; inputMountReadOnly = $true; rootFilesystemReadOnly = $true
        networkMode = 'none'; pullPolicy = 'never'; capDropAll = $true
        noNewPrivileges = $true; nonRootUser = $true; resourceLimitsApplied = $true
        resultMode = 'container-tmpfs-tar'
    }
    $dockerAllPass = [ordered]@{
        schemaVersion = 'awx.docker-autograder.result.v1'; runId = 'post-red-001'
        purpose = 'GREEN_VERIFICATION'; generatedAtUtc = '2026-07-29T00:00:00Z'
        jobSha256 = $dockerJobSha; inputManifestSha256 = ('b' * 64)
        executionStatus = 'COMPLETE'; failureClass = 'none'; exitCode = 0; timedOut = $false
        testVerdict = 'PASS'
        tests = [ordered]@{ total = 4; passed = 4; failed = 0; errored = 0; skipped = 0; passRatio = 1.0 }
        expectedSignalMatched = $false
        stdout = [ordered]@{ bytes = 0; persistedBytes = 0; sha256 = ('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'); truncated = $false }
        stderr = [ordered]@{ bytes = 0; persistedBytes = 0; sha256 = ('d' * 64); truncated = $false }
        junitArchive = [ordered]@{
            transport = 'container-tmpfs-tar'; bytes = 10240; persistedBytes = 10240
            sha256 = ('c' * 64); truncated = $false
        }
        combinedOutputSha256 = (Get-TextSha256 ('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' + ':' + ('d' * 64))); isolation = $dockerIsolation
        secretPatternHits = 0; mutationAllowed = $false; sourceMutationPerformed = $false
        desktopFinalProof = 'evidence_needed'
    }
    Write-ReadyJson $dockerResultPath $dockerAllPass | Out-Null
    $dockerPartialPath = Join-Path $dockerDir 'result-partial.json'
    $dockerPartial = [ordered]@{} + $dockerAllPass
    $dockerPartial.exitCode = 1; $dockerPartial.testVerdict = 'FAIL'
    $dockerPartial.tests = [ordered]@{ total = 4; passed = 2; failed = 1; errored = 0; skipped = 1; passRatio = 0.5 }
    Write-ReadyJson $dockerPartialPath $dockerPartial | Out-Null
    $dockerForgedPath = Join-Path $dockerDir 'result-forged-ratio.json'
    $dockerForged = [ordered]@{} + $dockerPartial
    $dockerForged.tests = [ordered]@{ total = 4; passed = 2; failed = 1; errored = 0; skipped = 1; passRatio = 1.0 }
    Write-ReadyJson $dockerForgedPath $dockerForged | Out-Null
    $dockerAllFailPath = Join-Path $dockerDir 'result-all-fail.json'
    $dockerAllFail = [ordered]@{} + $dockerPartial
    $dockerAllFail.tests = [ordered]@{ total = 4; passed = 0; failed = 4; errored = 0; skipped = 0; passRatio = 0.0 }
    Write-ReadyJson $dockerAllFailPath $dockerAllFail | Out-Null
    $dockerWrongRunPath = Join-Path $dockerDir 'result-wrong-run.json'
    $dockerWrongRun = [ordered]@{} + $dockerAllPass; $dockerWrongRun.runId = 'foreign-run-001'
    Write-ReadyJson $dockerWrongRunPath $dockerWrongRun | Out-Null
    $dockerWrongPurposePath = Join-Path $dockerDir 'result-wrong-purpose.json'
    $dockerWrongPurpose = [ordered]@{} + $dockerAllPass; $dockerWrongPurpose.purpose = 'RED_PROBE'
    Write-ReadyJson $dockerWrongPurposePath $dockerWrongPurpose | Out-Null
    $dockerTimeoutPath = Join-Path $dockerDir 'result-timeout.json'
    $dockerTimeout = [ordered]@{} + $dockerAllPass
    $dockerTimeout.executionStatus = 'HOLD'; $dockerTimeout.failureClass = 'autograder-timeout'
    $dockerTimeout.exitCode = $null; $dockerTimeout.timedOut = $true; $dockerTimeout.testVerdict = 'NOT_RUN'
    $dockerTimeout.tests = [ordered]@{ total = 0; passed = 0; failed = 0; errored = 0; skipped = 0; passRatio = $null }
    Write-ReadyJson $dockerTimeoutPath $dockerTimeout | Out-Null
    $dockerZeroPath = Join-Path $dockerDir 'result-zero.json'
    $dockerZero = [ordered]@{} + $dockerAllPass
    $dockerZero.tests = [ordered]@{ total = 0; passed = 0; failed = 0; errored = 0; skipped = 0; passRatio = $null }
    Write-ReadyJson $dockerZeroPath $dockerZero | Out-Null
    $dockerMalformedPath = Join-Path $dockerDir 'result-malformed-counts.json'
    $dockerMalformed = [ordered]@{} + $dockerAllPass
    $dockerMalformed.tests = [ordered]@{ total = 4; passed = 4; failed = 1; errored = 0; skipped = 0; passRatio = 1.0 }
    Write-ReadyJson $dockerMalformedPath $dockerMalformed | Out-Null
    $dockerBadCombinedPath = Join-Path $dockerDir 'result-bad-combined-hash.json'
    $dockerBadCombined = [ordered]@{} + $dockerAllPass; $dockerBadCombined.combinedOutputSha256 = ('f' * 64)
    Write-ReadyJson $dockerBadCombinedPath $dockerBadCombined | Out-Null
    $dockerOversizedCountsPath = Join-Path $dockerDir 'result-oversized-counts.json'
    $dockerOversizedCounts = [ordered]@{} + $dockerAllPass
    $dockerOversizedCounts.tests = [ordered]@{
        total = ('9' * 80); passed = ('9' * 80); failed = 0; errored = 0; skipped = 0; passRatio = 1.0
    }
    Write-ReadyJson $dockerOversizedCountsPath $dockerOversizedCounts | Out-Null
    $subjectInputHashes = [ordered]@{
        intent = (Get-FileHash -LiteralPath $intentPath -Algorithm SHA256).Hash.ToLowerInvariant()
        session = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\session.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        outcome = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\complete.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        verification = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\verification-complete.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        beforeIntegrity = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\before.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        afterIntegrity = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\after-same-seed.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        supabase = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\supabase-readonly.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        autograder = (Get-FileHash -LiteralPath $dockerResultPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    & $triPrepare -Root $tempRoot -RunId 'post-red-001' `
        -IntentFile 'data/agent-handoff/macsrc-defect-intake/post-red-001/intent.json' `
        -SessionFile 'data/session.json' -OutcomeFile 'data/complete.json' `
        -VerificationFile 'data/verification-complete.json' `
        -BeforeIntegrityFile 'data/before.json' -AfterIntegrityFile 'data/after-same-seed.json' `
        -SupabaseEvidenceFile 'data/supabase-readonly.json' `
        -AutograderEvidenceFile 'data/agent-handoff/docker-autograder/post-red-001/result.json' | Out-Null
    $triDir = Join-Path $tempRoot 'data\agent-handoff\macsrc-patch-postprocess\post-red-001\tri-query'
    $triManifestPath = Join-Path $triDir 'subject.manifest.json'
    $triManifest = Get-Content -LiteralPath $triManifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $triManifestSha = (Get-FileHash -LiteralPath $triManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $positiveRequestSha = [string]$triManifest.requestHashes.positive
    $negativeRequestSha = [string]$triManifest.requestHashes.negative
    $neutralRequestSha = [string]$triManifest.requestHashes.neutral
    $triDispatch = Get-Content -LiteralPath (Join-Path $triDir 'dispatch-plan.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    $triNeutralRequest = Get-Content -LiteralPath (Join-Path $triDir 'neutral.request.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    $expectedGoalScoreComponents = @('evidenceStrength', 'causalStrength', 'verificationFeasibility', 'userValue', 'reversibility', 'costEfficiency', 'timeFit', 'blastRadius', 'ambiguity', 'authorityOrSafetyExpansion')
    Assert-True (Test-HashMapEquivalent $triManifest.inputHashes $subjectInputHashes) 'tri-query prepare binds the current subject hashes'
    Assert-Equal (@($triDispatch.stages[0].roles) -join '>') 'POSITIVE_QUERY>NEGATIVE_QUERY' 'positive and negative are a parallel first stage'
    Assert-Equal (@($triDispatch.stages[1].roles) -join '>') 'NEUTRAL_QUERY' 'neutral is a dependent second stage'
    Assert-Equal (@($triNeutralRequest.goalScoreComponentNames) -join '>') ($expectedGoalScoreComponents -join '>') 'neutral request declares the official goal-score inputs'
    Assert-Equal ((@($triNeutralRequest.allowedGoalScoreEvidenceIds) | Sort-Object) -join '>') ((@($subjectInputHashes.Keys) | Sort-Object) -join '>') 'neutral request bounds score evidence to frozen inputs'
    Assert-True (Test-Path -LiteralPath (Join-Path $triDir 'subject.manifest.json.ready')) 'tri-query manifest publishes a ready marker last'
    $positiveRel = 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/positive.packet.json'
    $negativeRel = 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/negative.packet.json'
    $neutralRel = 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/neutral.packet.json'
    $positivePacket = [ordered]@{
        schemaVersion = 'awx.patch-tri-query.positive.v1'; packetType = 'POSITIVE_QUERY'
        subjectRunId = 'post-red-001'; subjectInputHashes = $subjectInputHashes
        requestSha256 = $positiveRequestSha; mutationAllowed = $false
    }
    Write-Utf8Json (Join-Path $tempRoot $positiveRel) $positivePacket
    $negativePacket = [ordered]@{
        schemaVersion = 'awx.patch-tri-query.negative.v1'; packetType = 'NEGATIVE_QUERY'
        subjectRunId = 'post-red-001'; subjectInputHashes = $subjectInputHashes
        requestSha256 = $negativeRequestSha; mutationAllowed = $false
    }
    Write-Utf8Json (Join-Path $tempRoot $negativeRel) $negativePacket
    $positiveSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $positiveRel) -Algorithm SHA256).Hash.ToLowerInvariant()
    $negativeSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $negativeRel) -Algorithm SHA256).Hash.ToLowerInvariant()
    $goalScoreComponents = [ordered]@{
        evidenceStrength = 0.8; causalStrength = 0.6; verificationFeasibility = 0.9
        userValue = 0.8; reversibility = 0.9; costEfficiency = 0.8; timeFit = 0.8
        blastRadius = 0.1; ambiguity = 0.2; authorityOrSafetyExpansion = 0.0
    }
    $goalScoreEvidenceIds = [ordered]@{
        evidenceStrength = @('intent', 'verification'); causalStrength = @('outcome', 'verification')
        verificationFeasibility = @('verification'); userValue = @('intent'); reversibility = @('session')
        costEfficiency = @('outcome'); timeFit = @('verification'); blastRadius = @('session', 'outcome')
        ambiguity = @('beforeIntegrity', 'afterIntegrity'); authorityOrSafetyExpansion = @('session')
    }
    $neutralPacket = [ordered]@{
        schemaVersion = 'awx.patch-tri-query.neutral.v1'; packetType = 'NEUTRAL_QUERY'
        subjectRunId = 'post-red-001'; subjectInputHashes = $subjectInputHashes
        positivePacketSha256 = $positiveSha; negativePacketSha256 = $negativeSha
        orderABVerdict = 'APPLY'; orderBAVerdict = 'APPLY'; verdict = 'APPLY'; orderStable = $true
        goalScoreComponents = $goalScoreComponents; goalScoreEvidenceIds = $goalScoreEvidenceIds; goalScore = 73.5
        requestSha256 = $neutralRequestSha; mutationAllowed = $false; secretPatternHits = 0
    }
    Write-Utf8Json (Join-Path $tempRoot $neutralRel) $neutralPacket
    $neutralSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $neutralRel) -Algorithm SHA256).Hash.ToLowerInvariant()
    $triFinal = [ordered]@{
        schemaVersion = 'awx.patch-tri-query.final.v1'; subjectRunId = 'post-red-001'
        subjectInputHashes = $subjectInputHashes; subjectManifestSha256 = $triManifestSha
        positivePacketFile = $positiveRel; negativePacketFile = $negativeRel; neutralPacketFile = $neutralRel
        positivePacketSha256 = $positiveSha; negativePacketSha256 = $negativeSha; neutralPacketSha256 = $neutralSha
        forwardOrder = @('POSITIVE_QUERY', 'NEGATIVE_QUERY'); reverseOrderChecked = $true
        verdict = 'APPLY'; orderStable = $true; goalScoreComponents = $goalScoreComponents
        goalScoreEvidenceIds = $goalScoreEvidenceIds; goalScore = 73.5
        mutationAllowed = $false; secretPatternHits = 0
    }
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-final.json') $triFinal
    $missingManifestFinal = [ordered]@{} + $triFinal
    [void]$missingManifestFinal.Remove('subjectManifestSha256')
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-missing-subject-manifest.json') $missingManifestFinal
    $swappedPositiveRel = 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/positive-swapped-request.packet.json'
    $swappedPositive = [ordered]@{} + $positivePacket
    $swappedPositive.requestSha256 = $negativeRequestSha
    Write-Utf8Json (Join-Path $tempRoot $swappedPositiveRel) $swappedPositive
    $swappedPositiveSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $swappedPositiveRel) -Algorithm SHA256).Hash.ToLowerInvariant()
    $swappedRequestFinal = [ordered]@{} + $triFinal
    $swappedRequestFinal.positivePacketFile = $swappedPositiveRel
    $swappedRequestFinal.positivePacketSha256 = $swappedPositiveSha
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-swapped-request.json') $swappedRequestFinal
    function New-TriVariant {
        param([string]$Slug, [string]$AutograderRel, [bool]$IncludeAutograder)
        $resolvedTriDir = [IO.Path]::GetFullPath($triDir)
        $resolvedTempRoot = [IO.Path]::GetFullPath($tempRoot).TrimEnd('\', '/')
        if (-not $resolvedTriDir.StartsWith($resolvedTempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'test tri-query directory escaped the temporary fixture root'
        }
        if (Test-Path -LiteralPath $resolvedTriDir) {
            [IO.Directory]::Delete($resolvedTriDir, $true)
        }
        $prepareArguments = @{
            Root = $tempRoot
            RunId = 'post-red-001'
            IntentFile = 'data/agent-handoff/macsrc-defect-intake/post-red-001/intent.json'
            SessionFile = 'data/session.json'
            OutcomeFile = 'data/complete.json'
            VerificationFile = 'data/verification-complete.json'
            BeforeIntegrityFile = 'data/before.json'
            AfterIntegrityFile = 'data/after-same-seed.json'
            SupabaseEvidenceFile = 'data/supabase-readonly.json'
        }
        if ($IncludeAutograder) { $prepareArguments.AutograderEvidenceFile = $AutograderRel }
        & $triPrepare @prepareArguments | Out-Null

        $variantManifestPath = Join-Path $triDir 'subject.manifest.json'
        $variantManifest = Get-Content -LiteralPath $variantManifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
        $variantManifestSha = (Get-FileHash -LiteralPath $variantManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $variantHashes = [ordered]@{}
        foreach ($property in $variantManifest.inputHashes.PSObject.Properties) {
            $variantHashes[$property.Name] = [string]$property.Value
        }
        $variantPositiveRel = "data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/positive-$Slug.packet.json"
        $variantNegativeRel = "data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/negative-$Slug.packet.json"
        $variantNeutralRel = "data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/neutral-$Slug.packet.json"
        $variantPositive = [ordered]@{
            schemaVersion = 'awx.patch-tri-query.positive.v1'; packetType = 'POSITIVE_QUERY'
            subjectRunId = 'post-red-001'; subjectInputHashes = $variantHashes
            requestSha256 = [string]$variantManifest.requestHashes.positive; mutationAllowed = $false
        }
        $variantNegative = [ordered]@{
            schemaVersion = 'awx.patch-tri-query.negative.v1'; packetType = 'NEGATIVE_QUERY'
            subjectRunId = 'post-red-001'; subjectInputHashes = $variantHashes
            requestSha256 = [string]$variantManifest.requestHashes.negative; mutationAllowed = $false
        }
        Write-Utf8Json (Join-Path $tempRoot $variantPositiveRel) $variantPositive
        Write-Utf8Json (Join-Path $tempRoot $variantNegativeRel) $variantNegative
        $variantPositiveSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $variantPositiveRel) -Algorithm SHA256).Hash.ToLowerInvariant()
        $variantNegativeSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $variantNegativeRel) -Algorithm SHA256).Hash.ToLowerInvariant()
        $variantNeutral = [ordered]@{
            schemaVersion = 'awx.patch-tri-query.neutral.v1'; packetType = 'NEUTRAL_QUERY'
            subjectRunId = 'post-red-001'; subjectInputHashes = $variantHashes
            positivePacketSha256 = $variantPositiveSha; negativePacketSha256 = $variantNegativeSha
            orderABVerdict = 'APPLY'; orderBAVerdict = 'APPLY'; verdict = 'APPLY'; orderStable = $true
            goalScoreComponents = $goalScoreComponents; goalScoreEvidenceIds = $goalScoreEvidenceIds; goalScore = 73.5
            requestSha256 = [string]$variantManifest.requestHashes.neutral
            mutationAllowed = $false; secretPatternHits = 0
        }
        Write-Utf8Json (Join-Path $tempRoot $variantNeutralRel) $variantNeutral
        $variantNeutralSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $variantNeutralRel) -Algorithm SHA256).Hash.ToLowerInvariant()
        $variantFinalRel = "data/tri-$Slug.json"
        $variantFinal = [ordered]@{
            schemaVersion = 'awx.patch-tri-query.final.v1'; subjectRunId = 'post-red-001'
            subjectInputHashes = $variantHashes; subjectManifestSha256 = $variantManifestSha
            positivePacketFile = $variantPositiveRel; negativePacketFile = $variantNegativeRel; neutralPacketFile = $variantNeutralRel
            positivePacketSha256 = $variantPositiveSha; negativePacketSha256 = $variantNegativeSha; neutralPacketSha256 = $variantNeutralSha
            forwardOrder = @('POSITIVE_QUERY', 'NEGATIVE_QUERY'); reverseOrderChecked = $true
            verdict = 'APPLY'; orderStable = $true; goalScoreComponents = $goalScoreComponents
            goalScoreEvidenceIds = $goalScoreEvidenceIds; goalScore = 73.5
            mutationAllowed = $false; secretPatternHits = 0
        }
        Write-Utf8Json (Join-Path $tempRoot $variantFinalRel) $variantFinal
        return [pscustomobject]@{
            finalRel = $variantFinalRel
            final = $variantFinal
            neutralPacket = $variantNeutral
            manifest = $variantManifest
        }
    }

    Invoke-ExpectedFailure {
        Invoke-Postprocess -FixtureRoot $tempRoot `
            -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
            -OutputRel 'main/java/example/injected-postprocess.json' -SupabaseRel 'data/supabase-readonly.json'
    } 'output-outside-handoff' 'postprocess output cannot target an active source directory'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $tempRoot 'main\java\example\injected-postprocess.json'))) 'rejected postprocess output creates no source file'

    $hold = Invoke-Postprocess $tempRoot 'data/verification-incomplete.json' `
        'data/after-different-seed.json' 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-hold.json'
    Assert-Equal $hold.schemaVersion 'awx.patch-postprocess.v1' 'postprocess schema is stable'
    Assert-Equal $hold.verdict 'HOLD' 'incomplete evidence holds'
    Assert-True (@($hold.failureClasses) -contains 'verification-target-hash-mismatch') 'missing postimages are classified'
    Assert-True (@($hold.failureClasses) -contains 'paired-seed-mismatch') 'different seeds are classified'
    Assert-True (@($hold.failureClasses) -contains 'supabase-auth-missing') 'missing Supabase auth is classified'
    Assert-True (@($hold.failureClasses) -contains 'supabase-project-ref-missing') 'missing Supabase scope is classified'
    Assert-Equal $hold.nextAction 'REPAIR_VERIFICATION_EVIDENCE' 'verification repair has fixed priority token'
    Assert-Equal $hold.nextMutationAllowed $false 'postprocess never chains mutation'
    Assert-Equal ($hold.reviewRoles -join '>') 'POSITIVE_QUERY>NEGATIVE_QUERY>NEUTRAL_QUERY' 'exactly three review roles are fixed'
    Assert-True (Test-Path -LiteralPath ((Join-Path $tempRoot 'data\agent-handoff\macsrc-patch-postprocess\post-red-001\results\post-hold.json') + '.ready')) 'HOLD ready marker exists'

    $complete = Invoke-Postprocess $tempRoot 'data/verification-complete.json' `
        'data/after-same-seed.json' 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-complete.json' 'data/supabase-readonly.json'
    Assert-Equal $complete.verdict 'COMPLETE' 'complete evidence closes the session'
    Assert-Equal $complete.nextAction 'STOP' 'complete session stops'
    Assert-Equal $complete.nextMutationAllowed $false 'complete does not authorize another patch'
    Assert-Equal $complete.metricDeltas.desktopErrorRate -0.2 'desktop error delta is computed'
    Assert-Equal $complete.supabase.mode 'CALLER_ASSERTED_PROJECT_SCOPED_READ_ONLY' 'Supabase metadata is not promoted to provider proof'
    Assert-Equal $complete.runtimeLineageVerdict 'HOLD' 'caller metadata cannot prove runtime lineage'
    Assert-Equal $complete.orderStable $true 'tri-query order stability is preserved'
    Assert-Equal $complete.goalScoreBase 73.5 'postprocess preserves the deterministic base score'
    Assert-Equal $complete.autograderPassRatio 1.0 'postprocess recomputes the all-pass ratio'
    Assert-Equal $complete.autograderDelta 10.0 'all-pass execution adds the bounded maximum'
    Assert-Equal $complete.goalScore 83.5 'postprocess publishes the adjusted deterministic score'
    Assert-Equal $complete.dynamicGrade.mode 'APPLIED' 'valid Docker evidence activates dynamic grading'
    Assert-Equal $complete.dynamicGrade.orderApplied 'A-B_AND_B-A' 'dynamic grade is identical in both review orders'

    $missingManifest = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-missing-subject-manifest.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-missing-subject-manifest.json'
    Assert-Equal $missingManifest.verdict 'HOLD' 'tri-query without the prepared subject manifest hash holds'
    Assert-True (@($missingManifest.failureClasses) -contains 'tri-query-invalid') 'missing subject manifest lineage is classified'
    Assert-Equal $missingManifest.nextAction 'RERUN_TRI_QUERY' 'missing subject manifest lineage has one repair action'

    $swappedRequest = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-swapped-request.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-swapped-request.json'
    Assert-Equal $swappedRequest.verdict 'HOLD' 'packet bound to another role request holds'
    Assert-True (@($swappedRequest.failureClasses) -contains 'tri-query-invalid') 'swapped request lineage is classified'
    Assert-Equal $swappedRequest.nextAction 'RERUN_TRI_QUERY' 'swapped request lineage has one repair action'

    $wrongManifestHashFinal = [ordered]@{} + $triFinal
    $wrongManifestHashFinal.subjectManifestSha256 = ('0' * 64)
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-wrong-subject-manifest-hash.json') $wrongManifestHashFinal
    $wrongManifestHash = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-wrong-subject-manifest-hash.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-wrong-subject-manifest-hash.json'
    Assert-Equal $wrongManifestHash.verdict 'HOLD' 'tri-query bound to a foreign subject manifest hash holds'
    Assert-True (@($wrongManifestHash.failureClasses) -contains 'tri-query-invalid') 'foreign subject manifest hash is classified'
    Assert-Equal $wrongManifestHash.nextAction 'RERUN_TRI_QUERY' 'foreign subject manifest hash has one repair action'

    $triMissingReady = New-TriVariant 'missing-manifest-ready' 'data/agent-handoff/docker-autograder/post-red-001/result.json' $true
    [IO.File]::Delete((Join-Path $triDir 'subject.manifest.json.ready'))
    $missingManifestReady = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-missing-manifest-ready.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel $triMissingReady.finalRel
    Assert-Equal $missingManifestReady.verdict 'HOLD' 'unpublished subject manifest holds'
    Assert-True (@($missingManifestReady.failureClasses) -contains 'tri-query-invalid') 'unpublished subject manifest is classified'
    Assert-Equal $missingManifestReady.nextAction 'RERUN_TRI_QUERY' 'unpublished subject manifest has one repair action'

    $triTamperedRequest = New-TriVariant 'tampered-positive-request' 'data/agent-handoff/docker-autograder/post-red-001/result.json' $true
    [IO.File]::AppendAllText(
        (Join-Path $triDir 'positive.request.json'),
        [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
    $tamperedRequest = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-tampered-positive-request.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel $triTamperedRequest.finalRel
    Assert-Equal $tamperedRequest.verdict 'HOLD' 'tampered prepared request bytes hold'
    Assert-True (@($tamperedRequest.failureClasses) -contains 'tri-query-invalid') 'tampered prepared request bytes are classified'
    Assert-Equal $tamperedRequest.nextAction 'RERUN_TRI_QUERY' 'tampered prepared request has one repair action'

    $triForeignManifest = New-TriVariant 'foreign-manifest-inputs' 'data/agent-handoff/docker-autograder/post-red-001/result.json' $true
    $foreignManifestPath = Join-Path $triDir 'subject.manifest.json'
    $foreignManifestObject = Get-Content -LiteralPath $foreignManifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
    $foreignManifest = [ordered]@{}
    foreach ($property in $foreignManifestObject.PSObject.Properties) {
        $foreignManifest[$property.Name] = $property.Value
    }
    $foreignManifest.inputHashes.beforeIntegrity = ('f' * 64)
    $foreignManifestSha = Write-ReadyJson $foreignManifestPath $foreignManifest
    $foreignManifestFinal = [ordered]@{} + $triForeignManifest.final
    $foreignManifestFinal.subjectManifestSha256 = $foreignManifestSha
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-foreign-manifest-inputs.json') $foreignManifestFinal
    $foreignManifestInputs = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-foreign-manifest-inputs.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-foreign-manifest-inputs.json'
    Assert-Equal $foreignManifestInputs.verdict 'HOLD' 'ready but foreign subject manifest inputs hold'
    Assert-True (@($foreignManifestInputs.failureClasses) -contains 'tri-query-invalid') 'foreign subject manifest inputs are classified'
    Assert-Equal $foreignManifestInputs.nextAction 'RERUN_TRI_QUERY' 'foreign subject manifest inputs have one repair action'

    $triPartial = New-TriVariant 'partial' 'data/agent-handoff/docker-autograder/post-red-001/result-partial.json' $true
    $partial = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-partial.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel $triPartial.finalRel -AutograderRel 'data/agent-handoff/docker-autograder/post-red-001/result-partial.json'
    Assert-Equal $partial.verdict 'HOLD' 'a failing GREEN execution cannot complete the patch'
    Assert-True (@($partial.failureClasses) -contains 'autograder-green-failed') 'failing GREEN execution is classified'
    Assert-Equal $partial.autograderPassRatio 0.5 'partial pass ratio is recomputed from counts'
    Assert-Equal $partial.autograderDelta 0.0 'half pass ratio has zero dynamic delta'
    Assert-Equal $partial.goalScore 73.5 'zero delta preserves the base score'

    $triForged = New-TriVariant 'forged' 'data/agent-handoff/docker-autograder/post-red-001/result-forged-ratio.json' $true
    $forged = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-forged-ratio.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel $triForged.finalRel -AutograderRel 'data/agent-handoff/docker-autograder/post-red-001/result-forged-ratio.json'
    Assert-Equal $forged.verdict 'HOLD' 'a forged pass ratio holds'
    Assert-True (@($forged.failureClasses) -contains 'autograder-pass-ratio-mismatch') 'forged pass ratio is classified'
    Assert-Equal $forged.dynamicGrade.mode 'INVALID' 'invalid Docker evidence applies no delta'
    Assert-Equal $forged.autograderDelta $null 'invalid Docker evidence has no dynamic delta'
    Assert-Equal $forged.nextAction 'RERUN_DOCKER_AUTOGRADER' 'invalid Docker evidence has one repair action'

    $triAllFail = New-TriVariant 'all-fail' 'data/agent-handoff/docker-autograder/post-red-001/result-all-fail.json' $true
    $allFail = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-all-fail.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel $triAllFail.finalRel -AutograderRel 'data/agent-handoff/docker-autograder/post-red-001/result-all-fail.json'
    Assert-Equal $allFail.verdict 'HOLD' 'all-fail GREEN cannot complete the patch'
    Assert-Equal $allFail.autograderPassRatio 0.0 'all-fail ratio is recomputed as zero'
    Assert-Equal $allFail.autograderDelta -10.0 'all-fail execution applies the bounded minimum delta'
    Assert-Equal $allFail.goalScore 63.5 'all-fail execution subtracts ten from the base score'

    function Assert-InvalidGradeCase {
        param([string]$Slug, [string]$ResultRel, [string]$FailureClass)
        $triVariant = New-TriVariant $Slug $ResultRel $true
        $record = Invoke-Postprocess -FixtureRoot $tempRoot `
            -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
            -OutputRel "data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-$Slug.json" -SupabaseRel 'data/supabase-readonly.json' `
            -TriQueryRel $triVariant.finalRel -AutograderRel $ResultRel
        Assert-Equal $record.verdict 'HOLD' "$Slug Docker evidence holds"
        Assert-True (@($record.failureClasses) -contains $FailureClass) "$Slug Docker evidence is classified"
        Assert-Equal $record.dynamicGrade.mode 'INVALID' "$Slug Docker evidence applies no score"
        Assert-Equal $record.autograderDelta $null "$Slug Docker evidence has no delta"
    }
    Assert-InvalidGradeCase 'wrong-run' 'data/agent-handoff/docker-autograder/post-red-001/result-wrong-run.json' 'autograder-result-identity-mismatch'
    Assert-InvalidGradeCase 'wrong-purpose' 'data/agent-handoff/docker-autograder/post-red-001/result-wrong-purpose.json' 'autograder-result-identity-mismatch'
    Assert-InvalidGradeCase 'timeout' 'data/agent-handoff/docker-autograder/post-red-001/result-timeout.json' 'autograder-execution-incomplete'
    Assert-InvalidGradeCase 'zero' 'data/agent-handoff/docker-autograder/post-red-001/result-zero.json' 'autograder-zero-tests'
    Assert-InvalidGradeCase 'malformed-counts' 'data/agent-handoff/docker-autograder/post-red-001/result-malformed-counts.json' 'autograder-test-counts-invalid'
    Assert-InvalidGradeCase 'bad-combined-hash' 'data/agent-handoff/docker-autograder/post-red-001/result-bad-combined-hash.json' 'autograder-log-metadata-invalid'
    Assert-InvalidGradeCase 'oversized-counts' 'data/agent-handoff/docker-autograder/post-red-001/result-oversized-counts.json' 'autograder-test-counts-invalid'

    $triNoGrade = New-TriVariant 'no-grade' '' $false
    $noGrade = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-no-grade.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel $triNoGrade.finalRel -AutograderRel ''
    Assert-Equal $noGrade.verdict 'COMPLETE' 'legacy callers remain valid without Docker evidence'
    Assert-Equal $noGrade.dynamicGrade.mode 'NOT_PROVIDED' 'missing optional evidence is explicit'
    Assert-Equal $noGrade.goalScoreBase 73.5 'legacy caller keeps the base score'
    Assert-Equal $noGrade.goalScore 73.5 'legacy caller receives no dynamic delta'

    $baselineTri = New-TriVariant 'final' 'data/agent-handoff/docker-autograder/post-red-001/result.json' $true
    $scoreMismatchNeutralRel = 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/neutral-score-mismatch.packet.json'
    $scoreMismatchNeutral = [ordered]@{} + $baselineTri.neutralPacket
    $scoreMismatchNeutral.goalScore = 70
    Write-Utf8Json (Join-Path $tempRoot $scoreMismatchNeutralRel) $scoreMismatchNeutral
    $scoreMismatchNeutralSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $scoreMismatchNeutralRel) -Algorithm SHA256).Hash.ToLowerInvariant()
    $scoreMismatchFinal = [ordered]@{} + $baselineTri.final
    $scoreMismatchFinal.neutralPacketFile = $scoreMismatchNeutralRel
    $scoreMismatchFinal.neutralPacketSha256 = $scoreMismatchNeutralSha
    $scoreMismatchFinal.goalScore = 70
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-score-mismatch.json') $scoreMismatchFinal

    $foreignEvidenceIds = [ordered]@{} + $goalScoreEvidenceIds
    $foreignEvidenceIds.evidenceStrength = @('foreign-evidence')
    $foreignEvidenceNeutralRel = 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/tri-query/neutral-foreign-evidence.packet.json'
    $foreignEvidenceNeutral = [ordered]@{} + $baselineTri.neutralPacket
    $foreignEvidenceNeutral.goalScoreEvidenceIds = $foreignEvidenceIds
    Write-Utf8Json (Join-Path $tempRoot $foreignEvidenceNeutralRel) $foreignEvidenceNeutral
    $foreignEvidenceNeutralSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot $foreignEvidenceNeutralRel) -Algorithm SHA256).Hash.ToLowerInvariant()
    $foreignEvidenceFinal = [ordered]@{} + $baselineTri.final
    $foreignEvidenceFinal.goalScoreEvidenceIds = $foreignEvidenceIds
    $foreignEvidenceFinal.neutralPacketFile = $foreignEvidenceNeutralRel
    $foreignEvidenceFinal.neutralPacketSha256 = $foreignEvidenceNeutralSha
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-foreign-evidence.json') $foreignEvidenceFinal

    $triReject = [ordered]@{} + $baselineTri.final
    $triReject.verdict = 'REJECT'
    $triReject.goalScore = 0
    Write-Utf8Json (Join-Path $tempRoot 'data\tri-reject.json') $triReject

    $scoreMismatch = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-score-mismatch.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-score-mismatch.json'
    Assert-Equal $scoreMismatch.verdict 'HOLD' 'submitted goal score mismatch holds'
    Assert-True (@($scoreMismatch.failureClasses) -contains 'goal-score-mismatch') 'submitted goal score mismatch is classified'

    $foreignEvidence = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-foreign-score-evidence.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-foreign-evidence.json'
    Assert-Equal $foreignEvidence.verdict 'HOLD' 'foreign goal-score evidence holds'
    Assert-True (@($foreignEvidence.failureClasses) -contains 'goal-score-evidence-unbound') 'foreign goal-score evidence is classified'

    $badVerificationBinding = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-bad-verification-hash.json' -SupabaseRel 'data/supabase-readonly.json' `
        -OutcomeRel 'data/complete-bad-verification-hash.json'
    Assert-Equal $badVerificationBinding.verdict 'HOLD' 'completion with a foreign verification hash holds'
    Assert-True (@($badVerificationBinding.failureClasses) -contains 'verification-evidence-hash-mismatch') 'verification hash mismatch is classified'

    $foreignSession = [ordered]@{} + $session
    $foreignSession.canonicalRoot = (Join-Path $tempRoot 'foreign-root')
    Write-Utf8Json (Join-Path $tempRoot 'data\session-foreign-root.json') $foreignSession
    $foreignSessionSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\session-foreign-root.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8Json (Join-Path $tempRoot 'data\verification-foreign-root.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_verification.v1'; runId = 'post-red-001'
        sessionSha256 = $foreignSessionSha; command = 'green-command'; exitCode = 0
        targetPostimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; sha256 = $targetSha })
    })
    $foreignVerificationSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\verification-foreign-root.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8Json (Join-Path $tempRoot 'data\complete-foreign-root.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_completion.v2'; state = 'COMPLETE'; runId = 'post-red-001'
        sessionSha256 = $foreignSessionSha; changedFileCount = 1; undeclaredSourceChangeCount = 0
        rawSecretPatternHits = 0; verificationEvidenceSha256 = $foreignVerificationSha
        postimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; postimageSha256 = $targetSha; changed = $true })
    })
    $foreignRoot = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-foreign-root.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-foreign-root.json' -SupabaseRel 'data/supabase-readonly.json' `
        -OutcomeRel 'data/complete-foreign-root.json' -SessionRel 'data/session-foreign-root.json'
    Assert-Equal $foreignRoot.verdict 'HOLD' 'session from a foreign root holds'
    Assert-True (@($foreignRoot.failureClasses) -contains 'session-root-mismatch') 'foreign session root is classified'

    Set-Content -LiteralPath $targetPath -Encoding UTF8 -Value 'class Foo {}'
    $noOpSha = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8Json (Join-Path $tempRoot 'data\verification-no-op.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_verification.v1'; runId = 'post-red-001'
        sessionSha256 = $sessionSha; command = 'green-command'; exitCode = 0
        targetPostimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; sha256 = $noOpSha })
    })
    $noOpVerificationSha = (Get-FileHash -LiteralPath (Join-Path $tempRoot 'data\verification-no-op.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8Json (Join-Path $tempRoot 'data\complete-no-op.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_completion.v2'; state = 'COMPLETE'; runId = 'post-red-001'
        sessionSha256 = $sessionSha; changedFileCount = 0; undeclaredSourceChangeCount = 0
        rawSecretPatternHits = 0; verificationEvidenceSha256 = $noOpVerificationSha
        postimages = @([ordered]@{ relativePath = 'main/java/example/Foo.java'; postimageSha256 = $noOpSha; changed = $false })
    })
    $noOp = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-no-op.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-no-op.json' -SupabaseRel 'data/supabase-readonly.json' `
        -OutcomeRel 'data/complete-no-op.json'
    Assert-Equal $noOp.verdict 'HOLD' 'no-op completion holds'
    Assert-True (@($noOp.failureClasses) -contains 'no-source-change') 'no-op completion is classified'

    Set-Content -LiteralPath $targetPath -Encoding UTF8 -Value 'class Foo { int patched; }'
    $rejectedTri = Invoke-Postprocess -FixtureRoot $tempRoot `
        -VerificationRel 'data/verification-complete.json' -AfterRel 'data/after-same-seed.json' `
        -OutputRel 'data/agent-handoff/macsrc-patch-postprocess/post-red-001/results/post-rejected-tri.json' -SupabaseRel 'data/supabase-readonly.json' `
        -TriQueryRel 'data/tri-reject.json'
    Assert-Equal $rejectedTri.verdict 'HOLD' 'REJECT tri-query cannot complete a patch'
    Assert-True (@($rejectedTri.failureClasses) -contains 'tri-query-invalid') 'REJECT tri-query is classified'
    Assert-Equal $rejectedTri.nextAction 'RERUN_TRI_QUERY' 'invalid tri-query has a bounded repair action'

    Write-Host "[SUMMARY] passed=$script:passed failed=0"
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$script:Passed = 0
$script:Failed = 0
$script:TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-auto-intake-" + [guid]::NewGuid().ToString('N'))
$script:ToolPath = Join-Path $PSScriptRoot 'desktop_patchdrop_auto_intake.ps1'
$script:SamplePolicyPath = Join-Path $PSScriptRoot 'desktop_patchdrop_auto_intake.policy.sample.json'
$script:TaskManagerPath = Join-Path $PSScriptRoot 'desktop_patchdrop_auto_intake_task.ps1'
$script:NativeFixtureProcesses = [System.Collections.Generic.List[int]]::new()

function Get-RealAutoIntakeTaskSnapshot {
    $taskName = 'AwxDesktopPatchDropAutoIntake'
    try {
        $tasks = @(Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue)
        return [pscustomobject]@{ available = $true; count = $tasks.Count; states = @($tasks | ForEach-Object { [string]$_.State } | Sort-Object) }
    } catch {
        return [pscustomobject]@{ available = $false; count = -1; states = @() }
    }
}

function Test-Contract {
    param([string]$Name, [scriptblock]$Condition, [string]$Detail = '')
    try {
        if (& $Condition) {
            $script:Passed++
            Write-Host "PASS $Name"
        } else {
            throw 'condition-false'
        }
    } catch {
        $script:Failed++
        Write-Host "FAIL $Name $Detail"
    }
}

function Write-Utf8Fixture {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function New-PolicyObject {
    return [ordered]@{
        schemaVersion = 'awx.desktop-patchdrop-auto-intake.policy.v1'
        enabled = $false
        mode = 'OBSERVE'
        expectedBackingShareIdentitySha256 = ''
        expectedShareAclSha256 = ''
        allowedNodes = @()
        allowedTopics = @()
        allowedPathPrefixes = @()
        maxPatchBytes = 1048576
        maxChangedFiles = 20
        maxHunks = 100
        pollIntervalMinutes = 5
        verificationProfile = 'focused'
    }
}

function Write-PolicyFixture {
    param([string]$Name, [object]$Value)
    $path = Join-Path $script:TempRoot $Name
    if ($Value -is [string]) { Write-Utf8Fixture $path $Value }
    else { Write-Utf8Fixture $path ($Value | ConvertTo-Json -Depth 6) }
    return $path
}

function Get-PolicyOrNull {
    param([string]$Path)
    try { return Read-AwxAutoIntakePolicy -Path $Path } catch { return $null }
}

function New-CompleteBundle {
    param([string]$Root, [string]$Base = 'alpha-v3', [string]$ManifestPatch = "$Base.patch")
    Write-Utf8Fixture (Join-Path $Root "$Base.patch") 'diff --git a/a b/a'
    Write-Utf8Fixture (Join-Path $Root "$Base.report.md") 'report'
    Write-Utf8Fixture (Join-Path $Root "$Base.verify.log") 'verify'
    Write-Utf8Fixture (Join-Path $Root "$Base.sha256.txt") 'hash'
    Write-Utf8Fixture (Join-Path $Root "$Base.manifest.json") (([ordered]@{ activePatch = $ManifestPatch } | ConvertTo-Json))
}

function New-Evidence { return [pscustomobject]@{ policyValid = $true } }

function New-ApplyEvidence {
    return [pscustomobject][ordered]@{
        canonicalRootProven = $true
        backingShareIdentityVerified = $true
        shareAclHashMatch = $true
        indexLock = $false
        activeLeaseCount = 0
        dirtyOverlapCount = 0
        preimageMatch = $true
        secretPatternHitCount = 0
        sourceIsolationPass = $true
        patchBytes = 128
        changedFileCount = 1
        hunkCount = 1
        rollbackReady = $true
    }
}

function Copy-ApplyEvidence {
    param([object]$Evidence)
    $copy = [ordered]@{}
    foreach ($property in $Evidence.PSObject.Properties) { $copy[$property.Name] = $property.Value }
    return [pscustomobject]$copy
}

function New-ApplySnapshot {
    return [pscustomobject]@{
        queueCount = 1
        failureClass = ''
        candidate = [pscustomobject]@{
            patchName = 'alpha-v3.patch'
            bundleIdHash = ('a' * 64)
            patchSha256 = ('b' * 64)
            manifestSha256 = ('c' * 64)
            rootPathSha256 = ('d' * 64)
            node = 'notebook'
            topic = 'alpha'
            changedPaths = @('main/java/com/example/Alpha.java')
            patchBytes = 128
            changedFileCount = 1
            hunkCount = 1
        }
        candidates = @()
    }
}

function New-FakeJanitor {
    param([string]$Root)
    $adapter = Join-Path $Root 'fake-janitor.ps1'
    $callLog = Join-Path $Root 'fake-janitor-calls.txt'
    $exitMarker = Join-Path $Root 'fake-janitor-exit.txt'
    $escapedLog = $callLog.Replace("'", "''")
    $escapedExit = $exitMarker.Replace("'", "''")
    Write-Utf8Fixture $adapter @"
param([string]`$PatchName)
[System.IO.File]::AppendAllText('$escapedLog', `$PatchName + [Environment]::NewLine, [System.Text.UTF8Encoding]::new(`$false))
if (Test-Path -LiteralPath '$escapedExit' -PathType Leaf) { exit [int](Get-Content -Raw -LiteralPath '$escapedExit') }
exit 0
"@
    return [pscustomobject]@{ adapter = $adapter; callLog = $callLog; exitMarker = $exitMarker }
}

function New-DescendantJanitor {
    param([string]$Root)
    $adapter = Join-Path $Root 'descendant-janitor.ps1'
    $childScript = Join-Path $Root 'descendant-worker.ps1'
    $pidPath = Join-Path $Root 'descendant.pid'
    $token = [guid]::NewGuid().ToString('N')
    $escapedChild = $childScript.Replace("'", "''")
    $escapedPid = $pidPath.Replace("'", "''")
    Write-Utf8Fixture $childScript @"
param([string]`$Token)
[Console]::Out.WriteLine('descendant-' + `$Token)
[Console]::Out.Flush()
Start-Sleep -Seconds 30
"@
    Write-Utf8Fixture $adapter @"
param([string]`$PatchName)
`$psi = [System.Diagnostics.ProcessStartInfo]::new()
`$psi.FileName = 'powershell.exe'
`$psi.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + '$escapedChild' + '" -Token "$token"'
`$psi.UseShellExecute = `$false
`$psi.CreateNoWindow = `$true
`$child = [System.Diagnostics.Process]::Start(`$psi)
[System.IO.File]::WriteAllText('$escapedPid', [string]`$child.Id, [System.Text.UTF8Encoding]::new(`$false))
[Console]::Out.WriteLine('adapter-parent-exit')
[Console]::Out.Flush()
exit 0
"@
    return [pscustomobject]@{ adapter = $adapter; childScript = $childScript; pidPath = $pidPath; token = $token }
}

function Get-FakeCallCount {
    param([object]$Fake)
    if (-not (Test-Path -LiteralPath $Fake.callLog -PathType Leaf)) { return 0 }
    return @([System.IO.File]::ReadAllLines($Fake.callLog, [System.Text.UTF8Encoding]::new($false))).Count
}

function Invoke-NativeAutoIntakeFixture {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )
    $process = $null
    try {
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = (Get-Command powershell.exe -ErrorAction Stop).Source
        $psi.Arguments = (($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ([string]$_).Replace('"','\"') + '"' } else { [string]$_ } }) -join ' ')
        $psi.WorkingDirectory = $WorkingDirectory
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $psi
        if (-not $process.Start()) { throw 'native-fixture-start-failed' }
        $script:NativeFixtureProcesses.Add([int]$process.Id)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            try { $process.Kill() } catch { }
            throw 'native-fixture-timeout'
        }
        $process.WaitForExit()
        $process.Refresh()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        return [pscustomobject]@{ exitCode = [int]$process.ExitCode; stdout = $stdout.Trim(); stderr = $stderr.Trim() }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
}

function New-NativeApplyBundle {
    param([Parameter(Mandatory)][string]$PatchDrop)
    $base = 'alpha-v3'
    $patchText = "diff --git a/main/java/com/example/Alpha.java b/main/java/com/example/Alpha.java`n--- a/main/java/com/example/Alpha.java`n+++ b/main/java/com/example/Alpha.java`n@@ -1 +1 @@`n-old`n+new`n"
    Write-Utf8Fixture (Join-Path $PatchDrop "$base.patch") $patchText
    Write-Utf8Fixture (Join-Path $PatchDrop "$base.report.md") 'report'
    Write-Utf8Fixture (Join-Path $PatchDrop "$base.verify.log") 'verify'
    Write-Utf8Fixture (Join-Path $PatchDrop "$base.sha256.txt") 'hash'
    Write-Utf8Fixture (Join-Path $PatchDrop "$base.manifest.json") (([ordered]@{ activePatch = "$base.patch"; node = 'notebook'; topic = 'alpha' } | ConvertTo-Json -Compress))
}

function Write-NativeApplyEvidenceFixture {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][object]$Snapshot)
    $candidate = $Snapshot.candidate
    $value = [ordered]@{
        schemaVersion = 'awx.desktop-patchdrop-auto-intake.run-evidence.v1'
        canonicalRootProven = $true
        backingShareIdentityVerified = $true
        shareAclHashMatch = $true
        indexLock = $false
        activeLeaseCount = 0
        dirtyOverlapCount = 0
        preimageMatch = $true
        secretPatternHitCount = 0
        sourceIsolationPass = $true
        patchBytes = [int64]$candidate.patchBytes
        changedFileCount = [int64]$candidate.changedFileCount
        hunkCount = [int64]$candidate.hunkCount
        rollbackReady = $true
    }
    Write-Utf8Fixture $Path ($value | ConvertTo-Json -Compress)
}

function Write-NativeVerificationFixture {
    param([Parameter(Mandatory)][string]$Path, [bool]$VerificationPassed = $true, [string]$RollbackResult = 'READY')
    Write-Utf8Fixture $Path (([ordered]@{ schemaVersion = 'awx.desktop-patchdrop-auto-intake.verification.v1'; verificationPassed = $VerificationPassed; rollbackResult = $RollbackResult } | ConvertTo-Json -Compress))
}

function New-LedgerOutcomeFixture {
    param([string]$Decision = 'HOLD', [string]$FailureClass = 'queue-scan-failed', [string]$Mode = '', [string]$PatchName = '', [string]$BundleIdHash = '', [int64]$QueueCount = 0)
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.desktop-patchdrop-auto-intake.outcome.v1'
        runIdHash = '1' * 64
        bundleIdHash = $BundleIdHash
        mode = $Mode
        decision = $Decision
        failureClass = $FailureClass
        patchName = $PatchName
        queueCount = $QueueCount
        secretPatternHitCount = 0
        leaseConflict = $false
        dirtyOverlap = $false
        preimageMatch = $false
        verificationPassed = $false
        rollbackReady = $false
        rollbackResult = 'NOT_APPLICABLE'
        elapsedMs = 1
        desktopFinalProof = 'evidence_needed'
    }
}

try {
    New-Item -ItemType Directory -Path $script:TempRoot -Force | Out-Null

    Test-Contract 'tooling script exists' { Test-Path -LiteralPath $script:ToolPath -PathType Leaf } 'production script missing'
    if (Test-Path -LiteralPath $script:ToolPath -PathType Leaf) { . $script:ToolPath }

    Test-Contract 'sample policy exists' { Test-Path -LiteralPath $script:SamplePolicyPath -PathType Leaf } 'sample policy missing'
    if (Test-Path -LiteralPath $script:SamplePolicyPath -PathType Leaf) {
        $sample = Get-Content -Raw -Encoding UTF8 $script:SamplePolicyPath | ConvertFrom-Json
        Test-Contract 'sample disabled' { -not [bool]$sample.enabled }
        Test-Contract 'sample observe only' { [string]$sample.mode -ceq 'OBSERVE' }
        Test-Contract 'sample allowlists empty' { @($sample.allowedTopics).Count -eq 0 }
        Test-Contract 'sample contains no live identity' { [string]::IsNullOrEmpty([string]$sample.expectedBackingShareIdentitySha256) }
    }

    $validPolicyPath = Write-PolicyFixture 'valid.json' (New-PolicyObject)
    Test-Contract 'valid policy parses to object' { $null -ne (Get-PolicyOrNull $validPolicyPath) } 'expected policy object'

    $invalidPolicies = @(
        @{ name = 'malformed JSON'; value = '{'; mutate = $null },
        @{ name = 'missing field'; value = $null; mutate = { param($p) $p.Remove('mode') } },
        @{ name = 'extra field'; value = $null; mutate = { param($p) $p.extra = 'no' } },
        @{ name = 'wrong schema'; value = $null; mutate = { param($p) $p.schemaVersion = 'wrong' } },
        @{ name = 'non boolean enabled'; value = $null; mutate = { param($p) $p.enabled = 'false' } },
        @{ name = 'invalid mode'; value = $null; mutate = { param($p) $p.mode = 'RUN' } },
        @{ name = 'non ascii mode'; value = $null; mutate = { param($p) $p.mode = 'OBSERVE' + [char]0x00E9 } },
        @{ name = 'invalid SHA format'; value = $null; mutate = { param($p) $p.expectedShareAclSha256 = 'abc' } },
        @{ name = 'empty APPLY allowlists'; value = $null; mutate = { param($p) $p.enabled = $true; $p.mode = 'APPLY' } },
        @{ name = 'negative budget'; value = $null; mutate = { param($p) $p.maxHunks = -1 } },
        @{ name = 'interval outside range'; value = $null; mutate = { param($p) $p.pollIntervalMinutes = 61 } },
        @{ name = 'duplicate allowlist'; value = $null; mutate = { param($p) $p.allowedTopics = @('a', 'a') } },
        @{ name = 'absolute path prefix'; value = $null; mutate = { param($p) $p.allowedPathPrefixes = @('C:\\unsafe') } },
        @{ name = 'traversal path prefix'; value = $null; mutate = { param($p) $p.allowedPathPrefixes = @('../unsafe') } }
    )
    foreach ($case in $invalidPolicies) {
        $path = $null
        if ($null -ne $case.value) { $path = Write-PolicyFixture ("invalid-" + $case.name.Replace(' ', '-') + '.json') $case.value }
        else {
            $policy = New-PolicyObject
            & $case.mutate $policy
            $path = Write-PolicyFixture ("invalid-" + $case.name.Replace(' ', '-') + '.json') $policy
        }
        $policy = Get-PolicyOrNull $path
        Test-Contract ("invalid policy holds: " + $case.name) { $null -eq $policy } 'expected redacted policy rejection'
    }

    $scannerPolicyValue = New-PolicyObject; $scannerPolicyValue.enabled = $true
    $scannerPolicy = Get-PolicyOrNull (Write-PolicyFixture 'scanner-observe.json' $scannerPolicyValue)
    $unprovenRoot = Join-Path $script:TempRoot ("missing-root-" + [guid]::NewGuid().ToString('N'))
    $unprovenSnapshot = Get-AwxTopLevelBundleSnapshot -Root $unprovenRoot
    $unprovenDecision = Get-AwxAutoIntakeDecision -Policy $scannerPolicy -Snapshot $unprovenSnapshot -Evidence (New-Evidence)
    Test-Contract 'unproven PatchDrop root holds' { $unprovenDecision.decision -ceq 'HOLD' -and $unprovenDecision.failureClass -ceq 'patch-drop-root-unproven' -and [int]$unprovenDecision.queueCount -eq 0 } 'expected fail-closed unproven root hold'
    $queueCases = @(
        @{ name = 'zero top-level patches'; setup = { param($r) }; decision = 'NOOP'; failure = 'queue-empty'; count = 0 },
        @{ name = 'nested producer patch only'; setup = { param($r) $nested = Join-Path $r 'notebook'; New-Item -ItemType Directory -Path $nested | Out-Null; New-CompleteBundle $nested }; decision = 'NOOP'; failure = 'queue-empty'; count = 0 },
        @{ name = 'two top-level patches'; setup = { param($r) New-CompleteBundle $r 'alpha-v3'; New-CompleteBundle $r 'beta-v3' }; decision = 'HOLD'; failure = 'patch-drop-pending'; count = 2 },
        @{ name = 'missing sidecar'; setup = { param($r) Write-Utf8Fixture (Join-Path $r 'alpha-v3.patch') 'diff' }; decision = 'HOLD'; failure = 'missing-bundle-meta'; count = 1 },
        @{ name = 'non-v3 name'; setup = { param($r) New-CompleteBundle $r 'alpha-v2' }; decision = 'HOLD'; failure = 'manifest-invalid'; count = 1 },
        @{ name = 'manifest activePatch mismatch'; setup = { param($r) New-CompleteBundle $r 'alpha-v3' 'other-v3.patch' }; decision = 'HOLD'; failure = 'manifest-invalid'; count = 1 },
        @{ name = 'complete manifest pinned v3'; setup = { param($r) New-CompleteBundle $r 'alpha-v3' }; decision = 'OBSERVE'; failure = ''; count = 1 }
    )
    foreach ($case in $queueCases) {
        $root = Join-Path $script:TempRoot ("queue-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $root | Out-Null
        & $case.setup $root
        $snapshot = Get-AwxTopLevelBundleSnapshot -Root $root
        $decision = Get-AwxAutoIntakeDecision -Policy $scannerPolicy -Snapshot $snapshot -Evidence (New-Evidence)
        Test-Contract ("scanner decision: " + $case.name) { $decision.decision -ceq $case.decision -and $decision.failureClass -ceq $case.failure -and [int]$decision.queueCount -eq $case.count } "expected=$($case.decision)/$($case.failure)/$($case.count)"
        if ($case.name -eq 'complete manifest pinned v3') {
            Test-Contract 'candidate patch name is bare v3 name' { $decision.patchName -ceq 'alpha-v3.patch' -and $decision.patchName -notmatch '[\\/]' } 'expected bare patch filename'
            Test-Contract 'candidate bundle hash is SHA256' { [string]$decision.bundleIdHash -match '^[A-Fa-f0-9]{64}$' } 'expected hash only'
        }
    }

    $apply = New-PolicyObject; $apply.enabled = $true; $apply.mode = 'APPLY'; $apply.allowedNodes = @('notebook'); $apply.allowedTopics = @('alpha'); $apply.allowedPathPrefixes = @('main/java')
    $applyPolicy = Get-PolicyOrNull (Write-PolicyFixture 'apply.json' $apply)
    $applyRoot = Join-Path $script:TempRoot 'apply-queue'; New-Item -ItemType Directory -Path $applyRoot | Out-Null; New-CompleteBundle $applyRoot
    $applyDecision = Get-AwxAutoIntakeDecision -Policy $applyPolicy -Snapshot (Get-AwxTopLevelBundleSnapshot $applyRoot) -Evidence (New-Evidence)
    Test-Contract 'APPLY remains held before preconditions' { $applyDecision.decision -ceq 'HOLD' -and $applyDecision.failureClass -ceq 'apply-preconditions-unproven' }

    $manifestRaceRoot = Join-Path $script:TempRoot 'manifest-snapshot-race'; New-Item -ItemType Directory -Path $manifestRaceRoot | Out-Null; New-CompleteBundle $manifestRaceRoot
    $manifestRacePath = Join-Path $manifestRaceRoot 'alpha-v3.manifest.json'
    Write-Utf8Fixture $manifestRacePath (([ordered]@{activePatch='alpha-v3.patch';node='notebook';topic='alpha'}|ConvertTo-Json -Compress))
    $manifestRaceReplacement = Join-Path $manifestRaceRoot 'manifest-replacement.tmp'
    Write-Utf8Fixture $manifestRaceReplacement (([ordered]@{activePatch='alpha-v3.patch';node='evil';topic='evil'}|ConvertTo-Json -Compress))
    $manifestRaceFake = New-FakeJanitor $manifestRaceRoot
    $script:ManifestReplacementAttempted=$false; $script:ManifestReplacementSucceeded=$false; $manifestRaceFailure=''
    try {
        $ignoredManifestSnapshot = Read-AwxAutoIntakeBundleManifestSnapshot -Path $manifestRacePath -AfterRead {
            $script:ManifestReplacementAttempted=$true
            [IO.File]::Replace($manifestRaceReplacement,$manifestRacePath,$null)
            $script:ManifestReplacementSucceeded=$true
        }
    } catch { $manifestRaceFailure=$_.Exception.Message }
    Test-Contract 'held manifest snapshot blocks metadata/hash replacement and reaches zero adapter calls' {
        $script:ManifestReplacementAttempted -and -not $script:ManifestReplacementSucceeded -and $manifestRaceFailure -ceq 'manifest-snapshot-changed' -and (Get-FakeCallCount $manifestRaceFake) -eq 0
    } "attempted=$script:ManifestReplacementAttempted;replaced=$script:ManifestReplacementSucceeded;failure=$manifestRaceFailure;adapterCalls=$(Get-FakeCallCount $manifestRaceFake)"

    $applySnapshot = New-ApplySnapshot
    $applyEvidence = New-ApplyEvidence
    $fakeRoot = Join-Path $script:TempRoot 'fake-adapter'; New-Item -ItemType Directory -Path $fakeRoot | Out-Null; New-CompleteBundle $fakeRoot
    $fake = New-FakeJanitor $fakeRoot
    $fakeSnapshot = Get-AwxTopLevelBundleSnapshot $fakeRoot
    $gateCases = @(
        @{ name = 'canonical root'; mutate = { param($e) $e.canonicalRootProven = $false }; decision = 'HOLD'; failure = 'canonical-root-unproven' },
        @{ name = 'backing identity'; mutate = { param($e) $e.backingShareIdentityVerified = $false }; decision = 'HOLD'; failure = 'backing-share-identity-unproven' },
        @{ name = 'share ACL'; mutate = { param($e) $e.shareAclHashMatch = $false }; decision = 'HOLD'; failure = 'share-acl-unproven' },
        @{ name = 'index lock'; mutate = { param($e) $e.indexLock = $true }; decision = 'HOLD'; failure = 'index-lock-present' },
        @{ name = 'active lease'; mutate = { param($e) $e.activeLeaseCount = 1 }; decision = 'HOLD'; failure = 'source-lease-conflict' },
        @{ name = 'dirty overlap'; mutate = { param($e) $e.dirtyOverlapCount = 1 }; decision = 'HOLD'; failure = 'dirty-overlap' },
        @{ name = 'preimage'; mutate = { param($e) $e.preimageMatch = $false }; decision = 'HOLD'; failure = 'changed-preimage' },
        @{ name = 'secret'; mutate = { param($e) $e.secretPatternHitCount = 1 }; decision = 'REJECT'; failure = 'secret-leak-risk' },
        @{ name = 'source isolation'; mutate = { param($e) $e.sourceIsolationPass = $false }; decision = 'HOLD'; failure = 'source-isolation-unproven' },
        @{ name = 'patch budget'; mutate = { param($e) $e.patchBytes = 1048577 }; decision = 'HOLD'; failure = 'patch-budget-exceeded' },
        @{ name = 'file budget'; mutate = { param($e) $e.changedFileCount = 21 }; decision = 'HOLD'; failure = 'changed-file-budget-exceeded' },
        @{ name = 'hunk budget'; mutate = { param($e) $e.hunkCount = 101 }; decision = 'HOLD'; failure = 'hunk-budget-exceeded' },
        @{ name = 'rollback'; mutate = { param($e) $e.rollbackReady = $false }; decision = 'HOLD'; failure = 'rollback-unproven' }
    )
    foreach ($case in $gateCases) {
        $caseEvidence = Copy-ApplyEvidence $applyEvidence
        & $case.mutate $caseEvidence
        $gate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $caseEvidence
        Test-Contract ("APPLY evidence gate holds: " + $case.name) { $gate.decision -ceq $case.decision -and $gate.failureClass -ceq $case.failure -and (Get-FakeCallCount $fake) -eq 0 } "expected=$($case.decision)/$($case.failure), fakeCalls=0"
    }
    $extraEvidence = Copy-ApplyEvidence $applyEvidence
    $extraEvidence | Add-Member -NotePropertyName undeclared -NotePropertyValue $true
    $extraGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $extraEvidence
    Test-Contract 'APPLY evidence rejects undeclared fields' { $extraGate.decision -ceq 'HOLD' -and $extraGate.failureClass -ceq 'apply-evidence-invalid' }
    $validGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $applyEvidence
    Test-Contract 'APPLY evidence passes all gates' { $validGate.decision -ceq 'APPLY' -and [string]::IsNullOrEmpty([string]$validGate.failureClass) }
    $nodeMismatch = New-ApplySnapshot; $nodeMismatch.candidate.node = 'Notebook'
    $nodeMismatchGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $nodeMismatch -Evidence $applyEvidence
    Test-Contract 'APPLY evidence rejects case-mismatched manifest node' { $nodeMismatchGate.decision -ceq 'HOLD' -and $nodeMismatchGate.failureClass -ceq 'manifest-node-not-allowed' }
    $topicMismatch = New-ApplySnapshot; $topicMismatch.candidate.topic = 'Alpha'
    $topicMismatchGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $topicMismatch -Evidence $applyEvidence
    Test-Contract 'APPLY evidence rejects case-mismatched manifest topic' { $topicMismatchGate.decision -ceq 'HOLD' -and $topicMismatchGate.failureClass -ceq 'manifest-topic-not-allowed' }
    $normalizedPath = New-ApplySnapshot; $normalizedPath.candidate.changedPaths = @('main\java\com\example\Alpha.java')
    $normalizedPathGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $normalizedPath -Evidence $applyEvidence
    Test-Contract 'APPLY evidence accepts slash-normalized allowed path' { $normalizedPathGate.decision -ceq 'APPLY' }
    $pathMismatch = New-ApplySnapshot; $pathMismatch.candidate.changedPaths = @('Main/Java/com/example/Alpha.java')
    $pathMismatchGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $pathMismatch -Evidence $applyEvidence
    Test-Contract 'APPLY evidence rejects case-mismatched manifest path' { $pathMismatchGate.decision -ceq 'HOLD' -and $pathMismatchGate.failureClass -ceq 'manifest-path-not-allowed' }
    $applyReadyDecision = Get-AwxAutoIntakeDecision -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $applyEvidence
    Test-Contract 'APPLY decision uses validated evidence' { $applyReadyDecision.decision -ceq 'APPLY' -and [string]::IsNullOrEmpty([string]$applyReadyDecision.failureClass) }
    $observeDecision = Get-AwxAutoIntakeDecision -Policy $scannerPolicy -Snapshot $applySnapshot -Evidence (New-Evidence)
    Test-Contract 'OBSERVE never calls fake adapter' { $observeDecision.decision -ceq 'OBSERVE' -and (Get-FakeCallCount $fake) -eq 0 }
    $adapterSuccess = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName 'alpha-v3.patch' -AdapterPath $fake.adapter -ExpectedSnapshot $fakeSnapshot -TestMode
    Test-Contract 'validated APPLY calls fake adapter exactly once with bare name' { $adapterSuccess.decision -ceq 'APPLY' -and $adapterSuccess.exitCode -eq 0 -and [bool]$adapterSuccess.jobAssignmentProven -and [bool]$adapterSuccess.jobResumed -and (Get-FakeCallCount $fake) -eq 1 -and [System.IO.File]::ReadAllText($fake.callLog).Trim() -ceq 'alpha-v3.patch' } "actual=$($adapterSuccess.decision)/$($adapterSuccess.failureClass)/$($adapterSuccess.exitCode)/assignment=$($adapterSuccess.jobAssignmentProven)/resumed=$($adapterSuccess.jobResumed)/runtime=$($adapterSuccess.runtimeReason)/calls=$(Get-FakeCallCount $fake)"
    Write-Host "jobAssignmentEvidence=assignmentProven=$([bool]$adapterSuccess.jobAssignmentProven);resumeReleased=$([bool]$adapterSuccess.jobResumed);adapterCalls=$(Get-FakeCallCount $fake)"
    Remove-Item -LiteralPath $fake.callLog -Force
    $productionAdapterRejected = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName 'alpha-v3.patch' -AdapterPath $fake.adapter -ExpectedSnapshot $fakeSnapshot
    Test-Contract 'production adapter rejects arbitrary path without call' { $productionAdapterRejected.decision -ceq 'HOLD' -and $productionAdapterRejected.failureClass -ceq 'janitor-adapter-invalid' -and (Get-FakeCallCount $fake) -eq 0 }
    $adapterBadName = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName '..\alpha-v3.patch' -AdapterPath $fake.adapter -ExpectedSnapshot $fakeSnapshot -TestMode
    Test-Contract 'adapter rejects path-bearing patch name without call' { $adapterBadName.decision -ceq 'HOLD' -and $adapterBadName.failureClass -ceq 'janitor-adapter-invalid' -and (Get-FakeCallCount $fake) -eq 0 }
    Write-Utf8Fixture $fake.exitMarker '7'
    $adapterFailure = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName 'alpha-v3.patch' -AdapterPath $fake.adapter -ExpectedSnapshot $fakeSnapshot -TestMode
    Test-Contract 'adapter nonzero exit holds janitor failed' { $adapterFailure.decision -ceq 'HOLD' -and $adapterFailure.failureClass -ceq 'janitor-failed' -and $adapterFailure.exitCode -eq 7 -and (Get-FakeCallCount $fake) -eq 1 } "actual=$($adapterFailure.decision)/$($adapterFailure.failureClass)/$($adapterFailure.exitCode)/calls=$(Get-FakeCallCount $fake)"

    $lockRoot = Join-Path $script:TempRoot 'lock-state'; New-Item -ItemType Directory -Path $lockRoot | Out-Null
    $lock = Enter-AwxAutoIntakeLock -StateRoot $lockRoot
    $secondLockFailure = ''
    try { $ignored = Enter-AwxAutoIntakeLock -StateRoot $lockRoot } catch { $secondLockFailure = $_.Exception.Message }
    Test-Contract 'single instance lock does not steal active lock' { $secondLockFailure -ceq 'lock-held' }
    $lock.Dispose()
    Test-Contract 'lock disposal removes only owned lock' { -not (Test-Path -LiteralPath (Join-Path $lockRoot 'auto-intake.lock.json') -PathType Leaf) }
    Write-Utf8Fixture (Join-Path $lockRoot 'auto-intake.lock.json') '{'
    $corruptLockFailure = ''
    try { $ignored = Enter-AwxAutoIntakeLock -StateRoot $lockRoot } catch { $corruptLockFailure = $_.Exception.Message }
    Test-Contract 'corrupt lock is held fail closed' { $corruptLockFailure -ceq 'lock-corrupt' }
    Remove-Item -LiteralPath (Join-Path $lockRoot 'auto-intake.lock.json') -Force
    Write-Utf8Fixture (Join-Path $lockRoot 'auto-intake.lock.json') '{"schemaVersion":"awx.desktop-patchdrop-auto-intake.lock.v1","pid":999999,"token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
    $staleLockFailure = ''
    try { $ignored = Enter-AwxAutoIntakeLock -StateRoot $lockRoot } catch { $staleLockFailure = $_.Exception.Message }
    Test-Contract 'stale lock without ownership proof is not stolen' { $staleLockFailure -ceq 'lock-owner-unproven' }
    Remove-Item -LiteralPath (Join-Path $lockRoot 'auto-intake.lock.json') -Force
    $childReady = Join-Path $lockRoot 'child-ready.txt'
    $childScript = Join-Path $lockRoot 'lock-child.ps1'
    $escapedTool = $script:ToolPath.Replace("'", "''")
    $escapedRoot = $lockRoot.Replace("'", "''")
    $escapedReady = $childReady.Replace("'", "''")
    Write-Utf8Fixture $childScript ". '$escapedTool'`n`$lock = Enter-AwxAutoIntakeLock -StateRoot '$escapedRoot'`n[System.IO.File]::WriteAllText('$escapedReady', 'ready')`nStart-Sleep -Seconds 3`n`$lock.Dispose()`n"
    $child = Start-Process -FilePath powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$childScript) -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath $childReady -PathType Leaf) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
    $parallelLockFailure = ''
    try { $ignored = Enter-AwxAutoIntakeLock -StateRoot $lockRoot } catch { $parallelLockFailure = $_.Exception.Message }
    $lockChildExited = $child.WaitForExit(10000)
    if (-not $lockChildExited) { Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue }
    Test-Contract 'two temp processes permit only one lock owner' { $parallelLockFailure -ceq 'lock-held' -and $lockChildExited -and $child.ExitCode -eq 0 }

    $processedRoot = Join-Path $script:TempRoot 'processed-state'; New-Item -ItemType Directory -Path $processedRoot | Out-Null; New-CompleteBundle $processedRoot
    $processedFake = New-FakeJanitor $processedRoot
    $processedSnapshot = Get-AwxTopLevelBundleSnapshot $processedRoot
    $processedAdapter = Invoke-AwxJanitorAdapter -Root $processedRoot -PatchName 'alpha-v3.patch' -AdapterPath $processedFake.adapter -ExpectedSnapshot $processedSnapshot -TestMode
    $processedLock = Enter-AwxAutoIntakeLock -StateRoot $processedRoot
    $missingVerification = Register-AwxAutoIntakeProcessedHash -StateRoot $processedRoot -Snapshot $processedSnapshot -AdapterResult $processedAdapter -FocusedVerificationPass:$false -RollbackReady:$true -Lock $processedLock
    Test-Contract 'apply success without focused verification is not processed' { $missingVerification.decision -ceq 'HOLD' -and $missingVerification.failureClass -ceq 'focused-verification-unproven' -and -not (Test-AwxAutoIntakeProcessedHash -StateRoot $processedRoot -Snapshot $processedSnapshot) }
    $missingRollback = Register-AwxAutoIntakeProcessedHash -StateRoot $processedRoot -Snapshot $processedSnapshot -AdapterResult $processedAdapter -FocusedVerificationPass:$true -RollbackReady:$false -Lock $processedLock
    Test-Contract 'apply success without rollback proof is not processed' { $missingRollback.decision -ceq 'HOLD' -and $missingRollback.failureClass -ceq 'rollback-unproven' -and -not (Test-AwxAutoIntakeProcessedHash -StateRoot $processedRoot -Snapshot $processedSnapshot) }
    $processed = Register-AwxAutoIntakeProcessedHash -StateRoot $processedRoot -Snapshot $processedSnapshot -AdapterResult $processedAdapter -FocusedVerificationPass:$true -RollbackReady:$true -Lock $processedLock
    $replay = Get-AwxAutoIntakeReplayDecision -StateRoot $processedRoot -Snapshot $processedSnapshot
    Test-Contract 'duplicate manifest and patch hash returns already processed noop' { $processed.decision -ceq 'APPLY' -and $replay.decision -ceq 'NOOP' -and $replay.failureClass -ceq 'already-processed' }
    $processedLock.Dispose()
    Write-Utf8Fixture (Join-Path $processedRoot 'processed-hashes.json') '{'
    $stateCorruptionFailure = ''
    try { $ignored = Test-AwxAutoIntakeProcessedHash -StateRoot $processedRoot -Snapshot $applySnapshot } catch { $stateCorruptionFailure = $_.Exception.Message }
    Test-Contract 'processed hash state corruption holds fail closed' { $stateCorruptionFailure -ceq 'processed-state-corrupt' }

    $ledgerRoot = Join-Path $script:TempRoot 'ledger-state'; New-Item -ItemType Directory -Path $ledgerRoot | Out-Null
    $ledgerLock = Enter-AwxAutoIntakeLock -StateRoot $ledgerRoot
    $ledgerApplyOutcome = New-LedgerOutcomeFixture -Decision 'APPLY' -FailureClass '' -Mode 'APPLY' -PatchName 'alpha-v3.patch' -BundleIdHash ('a' * 64) -QueueCount 1
    $ledgerApplyOutcome.verificationPassed = $true; $ledgerApplyOutcome.rollbackReady = $true; $ledgerApplyOutcome.rollbackResult = 'READY'; $ledgerApplyOutcome.preimageMatch = $true
    $ledgerHash = Write-AwxAutoIntakeLedger -StateRoot $ledgerRoot -Decision $ledgerApplyOutcome -Lock $ledgerLock
    Test-Contract 'redacted ledger writes decision schema and returns hash' { $ledgerHash -match '^[a-f0-9]{64}$' -and @(Get-Content -LiteralPath (Join-Path $ledgerRoot 'auto-intake-ledger.jsonl')).Count -eq 1 } "hashMatch=$($ledgerHash -match '^[a-f0-9]{64}$');type=$($ledgerHash.GetType().FullName)"
    $savedLedgerLimit = $script:AwxAutoIntakeMaxLedgerRows; $script:AwxAutoIntakeMaxLedgerRows = 2
    $secondLedgerDecision = New-LedgerOutcomeFixture -Decision 'HOLD' -FailureClass 'queue-scan-failed'
    $thirdLedgerDecision = New-LedgerOutcomeFixture -Decision 'NOOP' -FailureClass 'queue-empty'
    $ignored = Write-AwxAutoIntakeLedger -StateRoot $ledgerRoot -Decision $secondLedgerDecision -Lock $ledgerLock
    $ignored = Write-AwxAutoIntakeLedger -StateRoot $ledgerRoot -Decision $thirdLedgerDecision -Lock $ledgerLock
    $script:AwxAutoIntakeMaxLedgerRows = $savedLedgerLimit
    Test-Contract 'ledger rotates by bounded row count' { @(Get-Content -LiteralPath (Join-Path $ledgerRoot 'auto-intake-ledger.jsonl')).Count -eq 1 -and @(Get-Content -LiteralPath (Join-Path $ledgerRoot 'auto-intake-ledger.previous.jsonl')).Count -eq 2 }
    $ledgerLock.Dispose()

    $secretAndIdentity = Copy-ApplyEvidence $applyEvidence; $secretAndIdentity.canonicalRootProven = $false; $secretAndIdentity.secretPatternHitCount = 1
    $secretAndIdentityGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $secretAndIdentity
    Test-Contract 'secret rejection takes precedence over missing identity proof' { $secretAndIdentityGate.decision -ceq 'REJECT' -and $secretAndIdentityGate.failureClass -ceq 'secret-leak-risk' }
    $wrongCaseEvidence = Copy-ApplyEvidence $applyEvidence; $wrongCaseValue = $wrongCaseEvidence.canonicalRootProven; $wrongCaseEvidence.PSObject.Properties.Remove('canonicalRootProven'); $wrongCaseEvidence | Add-Member -NotePropertyName canonicalrootproven -NotePropertyValue $wrongCaseValue
    $wrongCaseGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $wrongCaseEvidence
    Test-Contract 'evidence schema rejects case-mismatched property name' { $wrongCaseGate.decision -ceq 'HOLD' -and $wrongCaseGate.failureClass -ceq 'apply-evidence-invalid' }
    $metricMismatch = Copy-ApplyEvidence $applyEvidence; $metricMismatch.patchBytes = 127
    $metricMismatchGate = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $metricMismatch
    Test-Contract 'APPLY evidence rejects metrics not derived from snapshot' { $metricMismatchGate.decision -ceq 'HOLD' -and $metricMismatchGate.failureClass -ceq 'snapshot-metrics-mismatch' }
    $headerRoot = Join-Path $script:TempRoot 'header-queue'; New-Item -ItemType Directory -Path $headerRoot | Out-Null
    New-CompleteBundle $headerRoot
    Write-Utf8Fixture (Join-Path $headerRoot 'alpha-v3.patch') "diff --git a/main/java/Allowed.java b/main/java/Allowed.java`n@@ -1 +1 @@`n-x`n+y`ndiff --git \"a/main/java/Unrecognized.java\" \"b/main/java/Unrecognized.java\"`n@@ -1 +1 @@`n-x`n+y"
    $headerSnapshot = Get-AwxTopLevelBundleSnapshot $headerRoot
    Test-Contract 'scanner rejects patch with any unrecognized diff header' { $headerSnapshot.failureClass -ceq 'patch-headers-invalid' -and $null -eq $headerSnapshot.candidate }
    $preAdapterRoot = Join-Path $script:TempRoot 'pre-adapter-state'; New-Item -ItemType Directory -Path $preAdapterRoot | Out-Null
    $preAdapter = Register-AwxAutoIntakeProcessedHash -StateRoot $preAdapterRoot -Snapshot $applySnapshot -AdapterResult ([pscustomobject]@{ decision = 'APPLY'; exitCode = 0 }) -FocusedVerificationPass:$true -RollbackReady:$true
    Test-Contract 'pre-adapter APPLY decision cannot register processed hash' { $preAdapter.decision -ceq 'HOLD' -and $preAdapter.failureClass -ceq 'adapter-success-unproven' }
    $noLockLedgerRoot = Join-Path $script:TempRoot 'no-lock-ledger'; New-Item -ItemType Directory -Path $noLockLedgerRoot | Out-Null
    $noLockLedgerFailure = ''
    try { $ignored = Write-AwxAutoIntakeLedger -StateRoot $noLockLedgerRoot -Decision $ledgerApplyOutcome } catch { $noLockLedgerFailure = $_.Exception.Message }
    Test-Contract 'ledger write without live lock fails closed' { $noLockLedgerFailure -ceq 'lock-required' }
    $upperStateRoot = Join-Path $script:TempRoot 'upper-state'; New-Item -ItemType Directory -Path $upperStateRoot | Out-Null
    Write-Utf8Fixture (Join-Path $upperStateRoot 'processed-hashes.json') ('{"schemaVersion":"awx.desktop-patchdrop-auto-intake.processed.v2","processedHashes":["' + (('c' * 64).ToUpperInvariant()) + '"]}')
    $upperStateFailure = ''
    try { $ignored = Test-AwxAutoIntakeProcessedHash -StateRoot $upperStateRoot -Snapshot $applySnapshot } catch { $upperStateFailure = $_.Exception.Message }
    Test-Contract 'uppercase processed hash is rejected before replay lookup' { $upperStateFailure -ceq 'processed-state-corrupt' }
    $wrongCaseLedger = [pscustomobject][ordered]@{ Decision = 'HOLD'; failureClass = 'queue-empty'; mode = ''; patchName = ''; bundleIdHash = ''; queueCount = 0; validatorVersion = 'awx.desktop-patchdrop-auto-intake.v1' }
    Test-Contract 'ledger schema rejects case-mismatched property name' { -not (Test-AwxAutoIntakeLedgerDecision $wrongCaseLedger) }
    $productionRoot = Join-Path $script:TempRoot 'production-root'; $productionPatchDrop = Join-Path $productionRoot '__patch_drop__'; New-Item -ItemType Directory -Path $productionPatchDrop -Force | Out-Null; New-CompleteBundle $productionPatchDrop
    $productionFake = New-FakeJanitor $productionPatchDrop; $productionAdapterPath = Join-Path $productionPatchDrop 'janitor_apply_one.ps1'; Move-Item -LiteralPath $productionFake.adapter -Destination $productionAdapterPath
    $productionFake.adapter = $productionAdapterPath
    $productionSnapshot = Get-AwxTopLevelBundleSnapshot $productionPatchDrop
    $productionRootText = [System.IO.Path]::GetFullPath($productionRoot).TrimEnd('\\')
    $productionRootProof = [pscustomobject][ordered]@{ rootProven = $true; rootPathSha256 = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($productionRootText)) }
    $unprovenProduction = Invoke-AwxJanitorAdapter -Root $productionRoot -PatchName 'alpha-v3.patch' -AdapterPath $productionAdapterPath -ExpectedSnapshot $productionSnapshot
    Test-Contract 'production adapter requires explicit proven root evidence without call' { $unprovenProduction.decision -ceq 'HOLD' -and $unprovenProduction.failureClass -ceq 'production-root-unproven' -and (Get-FakeCallCount $productionFake) -eq 0 }
    $provenProduction = Invoke-AwxJanitorAdapter -Root $productionRoot -PatchName 'alpha-v3.patch' -AdapterPath $productionAdapterPath -ExpectedSnapshot $productionSnapshot -ProductionRootEvidence $productionRootProof
    Test-Contract 'proven exact production adapter calls fake once' { $provenProduction.decision -ceq 'APPLY' -and (Get-FakeCallCount $productionFake) -eq 1 }
    $reparseRoot = Join-Path $script:TempRoot 'reparse-root'; $reparseTarget = Join-Path $script:TempRoot 'reparse-target'; New-Item -ItemType Directory -Path $reparseRoot,$reparseTarget -Force | Out-Null
    $reparseCreated = $false
    try { New-Item -ItemType Junction -Path (Join-Path $reparseRoot '__patch_drop__') -Target $reparseTarget -ErrorAction Stop | Out-Null; $reparseCreated = $true } catch { }
    if ($reparseCreated) {
        $reparseFake = New-FakeJanitor $reparseTarget; $reparseAdapterPath = Join-Path $reparseRoot '__patch_drop__\janitor_apply_one.ps1'; Move-Item -LiteralPath $reparseFake.adapter -Destination (Join-Path $reparseTarget 'janitor_apply_one.ps1'); $reparseFake.adapter = $reparseAdapterPath
        $reparseRootText = [System.IO.Path]::GetFullPath($reparseRoot).TrimEnd('\\')
        $reparseProof = [pscustomobject][ordered]@{ rootProven = $true; rootPathSha256 = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($reparseRootText)) }
        $reparseSnapshot = Get-AwxTopLevelBundleSnapshot (Join-Path $reparseRoot '__patch_drop__')
        $reparseResult = Invoke-AwxJanitorAdapter -Root $reparseRoot -PatchName 'alpha-v3.patch' -AdapterPath $reparseAdapterPath -ExpectedSnapshot $reparseSnapshot -ProductionRootEvidence $reparseProof
        Test-Contract 'production adapter rejects junction component without call' { $reparseResult.decision -ceq 'HOLD' -and $reparseResult.failureClass -ceq 'janitor-adapter-invalid' -and (Get-FakeCallCount $reparseFake) -eq 0 }
    } else {
        Test-Contract 'reparse fallback rejects unresolved component deterministically' { -not (Test-AwxAutoIntakeReparseFreePath -Root $reparseRoot -AdapterPath (Join-Path $reparseRoot '__patch_drop__\janitor_apply_one.ps1')) }
    }
    $rootJunctionTarget = Join-Path $script:TempRoot 'root-junction-target'; $rootJunctionLink = Join-Path $script:TempRoot 'root-junction-link'; $rootJunctionPatchDrop = Join-Path $rootJunctionTarget '__patch_drop__'; New-Item -ItemType Directory -Path $rootJunctionPatchDrop -Force | Out-Null
    $rootJunctionFake = New-FakeJanitor $rootJunctionPatchDrop; $rootJunctionAdapter = Join-Path $rootJunctionPatchDrop 'janitor_apply_one.ps1'; Move-Item -LiteralPath $rootJunctionFake.adapter -Destination $rootJunctionAdapter; $rootJunctionFake.adapter = $rootJunctionAdapter
    $rootJunctionCreated = $false
    try { New-Item -ItemType Junction -Path $rootJunctionLink -Target $rootJunctionTarget -ErrorAction Stop | Out-Null; $rootJunctionCreated = $true } catch { }
    $rootJunctionArgument = if ($rootJunctionCreated) { $rootJunctionLink } else { Join-Path $script:TempRoot 'root-junction-unavailable' }
    $rootJunctionAdapterArgument = Join-Path $rootJunctionArgument '__patch_drop__\janitor_apply_one.ps1'
    $rootJunctionText = [System.IO.Path]::GetFullPath($rootJunctionArgument).TrimEnd('\')
    $rootJunctionProof = [pscustomobject][ordered]@{ rootProven = $true; rootPathSha256 = Get-AwxAutoIntakeSha256 ([System.Text.UTF8Encoding]::new($false).GetBytes($rootJunctionText)) }
    $rootJunctionSnapshot = if ($rootJunctionCreated) { Get-AwxTopLevelBundleSnapshot (Join-Path $rootJunctionArgument '__patch_drop__') } else { New-ApplySnapshot }
    $rootJunctionResult = Invoke-AwxJanitorAdapter -Root $rootJunctionArgument -PatchName 'alpha-v3.patch' -AdapterPath $rootJunctionAdapterArgument -ExpectedSnapshot $rootJunctionSnapshot -ProductionRootEvidence $rootJunctionProof
    Test-Contract 'production adapter rejects root junction before call or fails closed when junction unavailable' { $rootJunctionResult.decision -ceq 'HOLD' -and $rootJunctionResult.failureClass -ceq 'janitor-adapter-invalid' -and (Get-FakeCallCount $rootJunctionFake) -eq 0 } "junctionCreated=$rootJunctionCreated;actual=$($rootJunctionResult.decision)/$($rootJunctionResult.failureClass);calls=$(Get-FakeCallCount $rootJunctionFake)"
    Write-Host "rootJunctionEvidence=created=$rootJunctionCreated;adapterCalls=$(Get-FakeCallCount $rootJunctionFake);failureClass=$($rootJunctionResult.failureClass)"
    $hungAdapter = Join-Path $fakeRoot 'hung-janitor.ps1'; Write-Utf8Fixture $hungAdapter "param([string]`$PatchName)`nStart-Sleep -Seconds 3`nexit 0"
    $savedAdapterTimeout = $script:AwxAutoIntakeAdapterTimeoutMilliseconds; $script:AwxAutoIntakeAdapterTimeoutMilliseconds = 200
    $hungResult = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName 'alpha-v3.patch' -AdapterPath $hungAdapter -ExpectedSnapshot $fakeSnapshot -TestMode
    $script:AwxAutoIntakeAdapterTimeoutMilliseconds = $savedAdapterTimeout
    Test-Contract 'hung adapter is terminated and held with bounded result' { $hungResult.decision -ceq 'HOLD' -and $hungResult.failureClass -ceq 'janitor-timeout' -and [bool]$hungResult.childExited -and [int]$hungResult.stdoutBytes -le [int]$script:AwxAutoIntakeMaxAdapterOutputBytes -and [int]$hungResult.stderrBytes -le [int]$script:AwxAutoIntakeMaxAdapterOutputBytes }
    $noisyAdapter = Join-Path $fakeRoot 'noisy-janitor.ps1'; Write-Utf8Fixture $noisyAdapter "param([string]`$PatchName)`n1..20000 | ForEach-Object { Write-Output ('x' * 80) }"
    $savedAdapterTimeout = $script:AwxAutoIntakeAdapterTimeoutMilliseconds; $script:AwxAutoIntakeAdapterTimeoutMilliseconds = 500
    $noisyResult = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName 'alpha-v3.patch' -AdapterPath $noisyAdapter -ExpectedSnapshot $fakeSnapshot -TestMode
    $script:AwxAutoIntakeAdapterTimeoutMilliseconds = $savedAdapterTimeout
    Test-Contract 'noisy adapter cannot exceed capture bound and holds' { $noisyResult.decision -ceq 'HOLD' -and [bool]$noisyResult.childExited -and [int]$noisyResult.stdoutBytes -le [int]$script:AwxAutoIntakeMaxAdapterOutputBytes -and [int]$noisyResult.stderrBytes -le [int]$script:AwxAutoIntakeMaxAdapterOutputBytes }
    $descendantRoot = Join-Path $script:TempRoot 'descendant-adapter'; New-Item -ItemType Directory -Path $descendantRoot | Out-Null; New-CompleteBundle $descendantRoot
    $descendantFake = New-DescendantJanitor $descendantRoot
    $descendantSnapshot = Get-AwxTopLevelBundleSnapshot $descendantRoot
    $savedAdapterTimeout = $script:AwxAutoIntakeAdapterTimeoutMilliseconds; $script:AwxAutoIntakeAdapterTimeoutMilliseconds = 750
    $descendantClock = [System.Diagnostics.Stopwatch]::StartNew()
    try { $descendantResult = Invoke-AwxJanitorAdapter -Root $descendantRoot -PatchName 'alpha-v3.patch' -AdapterPath $descendantFake.adapter -ExpectedSnapshot $descendantSnapshot -TestMode }
    finally { $descendantClock.Stop(); $script:AwxAutoIntakeAdapterTimeoutMilliseconds = $savedAdapterTimeout }
    $descendantPid = 0
    if (Test-Path -LiteralPath $descendantFake.pidPath -PathType Leaf) { [void][int]::TryParse(([System.IO.File]::ReadAllText($descendantFake.pidPath)).Trim(), [ref]$descendantPid) }
    $descendantAlive = $descendantPid -gt 0 -and $null -ne (Get-Process -Id $descendantPid -ErrorAction SilentlyContinue)
    Test-Contract 'adapter timeout kills descendant tree within bounded deadline' { $descendantResult.decision -ceq 'HOLD' -and $descendantResult.failureClass -ceq 'janitor-timeout' -and [bool]$descendantResult.childExited -and $descendantPid -gt 0 -and -not $descendantAlive -and $descendantClock.ElapsedMilliseconds -lt 2500 } "actual=$($descendantResult.decision)/$($descendantResult.failureClass);pidRecorded=$($descendantPid -gt 0);childAlive=$descendantAlive;elapsedMs=$($descendantClock.ElapsedMilliseconds)"
    Write-Host "childCleanupEvidence=pidRecorded=$($descendantPid -gt 0);childAbsent=$(-not $descendantAlive);elapsedMs=$($descendantClock.ElapsedMilliseconds);failureClass=$($descendantResult.failureClass)"
    if ($descendantAlive) { Stop-Process -Id $descendantPid -Force -ErrorAction SilentlyContinue }
    $savedLedgerRows = $script:AwxAutoIntakeMaxLedgerRows; $savedLedgerBytes = $script:AwxAutoIntakeMaxLedgerBytes; $script:AwxAutoIntakeMaxLedgerRows = 2; $script:AwxAutoIntakeMaxLedgerBytes = 8194
    $boundaryRoot = Join-Path $script:TempRoot 'ledger-boundary'; New-Item -ItemType Directory -Path $boundaryRoot | Out-Null; $boundaryLock = Enter-AwxAutoIntakeLock -StateRoot $boundaryRoot
    $nearLimitDecision = New-LedgerOutcomeFixture -Decision 'HOLD' -FailureClass 'bounded-fixture'
    $ignored = Write-AwxAutoIntakeLedger -StateRoot $boundaryRoot -Decision $nearLimitDecision -Lock $boundaryLock
    $ignored = Write-AwxAutoIntakeLedger -StateRoot $boundaryRoot -Decision $nearLimitDecision -Lock $boundaryLock
    $boundaryLock.Dispose(); $script:AwxAutoIntakeMaxLedgerRows = $savedLedgerRows; $script:AwxAutoIntakeMaxLedgerBytes = $savedLedgerBytes
    Test-Contract 'ledger read capacity covers configured near-limit rows' { @(Read-AwxAutoIntakeLedgerLines -StateRoot $boundaryRoot).Count -eq 2 }
    $secretExtra = Copy-ApplyEvidence $applyEvidence; $secretExtra.secretPatternHitCount = 1; $secretExtra | Add-Member -NotePropertyName extra -NotePropertyValue $true
    Test-Contract 'secret count rejects before extra evidence field hold' { $r = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $secretExtra; $r.decision -ceq 'REJECT' -and $r.failureClass -ceq 'secret-leak-risk' }
    $secretWrongType = Copy-ApplyEvidence $applyEvidence; $secretWrongType.secretPatternHitCount = 1; $secretWrongType.patchBytes = 'bad'
    Test-Contract 'secret count rejects before other evidence type hold' { $r = Test-AwxAutoIntakeApplyEvidence -Policy $applyPolicy -Snapshot $applySnapshot -Evidence $secretWrongType; $r.decision -ceq 'REJECT' -and $r.failureClass -ceq 'secret-leak-risk' }
    $tabHeaderRoot = Join-Path $script:TempRoot 'tab-header'; New-Item -ItemType Directory -Path $tabHeaderRoot | Out-Null; New-CompleteBundle $tabHeaderRoot
    Write-Utf8Fixture (Join-Path $tabHeaderRoot 'alpha-v3.patch') "diff --git a/main/java/Allowed.java b/main/java/Allowed.java`n@@ -1 +1 @@`n-x`n+y`ndiff --git`tbare"
    Test-Contract 'scanner rejects tab header-like line after valid header' { (Get-AwxTopLevelBundleSnapshot $tabHeaderRoot).failureClass -ceq 'patch-headers-invalid' }
    $bundleB = New-ApplySnapshot; $bundleB.candidate.patchName = 'beta-v3.patch'; $bundleB.candidate.bundleIdHash = ('d' * 64); $bundleB.candidate.patchSha256 = ('e' * 64)
    $bundleBRoot = Join-Path $script:TempRoot 'bundle-b-state'; New-Item -ItemType Directory -Path $bundleBRoot | Out-Null; $bundleBLock = Enter-AwxAutoIntakeLock $bundleBRoot
    $crossBundle = Register-AwxAutoIntakeProcessedHash -StateRoot $bundleBRoot -Snapshot $bundleB -AdapterResult $processedAdapter -FocusedVerificationPass:$true -RollbackReady:$true -Lock $bundleBLock; $bundleBLock.Dispose()
    Test-Contract 'adapter success for bundle A cannot register bundle B' { $crossBundle.decision -ceq 'HOLD' -and $crossBundle.failureClass -ceq 'adapter-provenance-mismatch' }

    $provenanceRootA = Join-Path $script:TempRoot 'provenance-root-a'; New-Item -ItemType Directory -Path $provenanceRootA | Out-Null; New-CompleteBundle $provenanceRootA
    $provenanceFakeA = New-FakeJanitor $provenanceRootA
    $provenanceSnapshotA = Get-AwxTopLevelBundleSnapshot $provenanceRootA
    $provenanceAdapterA = Invoke-AwxJanitorAdapter -Root $provenanceRootA -PatchName 'alpha-v3.patch' -AdapterPath $provenanceFakeA.adapter -ExpectedSnapshot $provenanceSnapshotA -TestMode
    Write-Utf8Fixture (Join-Path $provenanceRootA 'alpha-v3.patch') "diff --git a/a b/a`n@@ -1 +1 @@`n-old`n+changed"
    Write-Utf8Fixture (Join-Path $provenanceRootA 'alpha-v3.manifest.json') (([ordered]@{ activePatch = 'alpha-v3.patch'; revision = 'changed' } | ConvertTo-Json -Compress))
    $provenanceChangedSnapshot = Get-AwxTopLevelBundleSnapshot $provenanceRootA
    $provenanceChangedLock = Enter-AwxAutoIntakeLock $provenanceRootA
    $provenanceChangedRegistration = Register-AwxAutoIntakeProcessedHash -StateRoot $provenanceRootA -Snapshot $provenanceChangedSnapshot -AdapterResult $provenanceAdapterA -FocusedVerificationPass:$true -RollbackReady:$true -Lock $provenanceChangedLock
    $provenanceChangedRecorded = Test-AwxAutoIntakeProcessedHash -StateRoot $provenanceRootA -Snapshot $provenanceChangedSnapshot
    $provenanceChangedLock.Dispose()
    Test-Contract 'adapter success cannot register same-name changed patch and manifest' { $provenanceChangedRegistration.decision -ceq 'HOLD' -and $provenanceChangedRegistration.failureClass -ceq 'adapter-provenance-mismatch' -and -not $provenanceChangedRecorded } "actual=$($provenanceChangedRegistration.decision)/$($provenanceChangedRegistration.failureClass);recorded=$provenanceChangedRecorded"

    $provenanceRootB = Join-Path $script:TempRoot 'provenance-root-b'; New-Item -ItemType Directory -Path $provenanceRootB | Out-Null; New-CompleteBundle $provenanceRootB
    $provenanceSnapshotB = Get-AwxTopLevelBundleSnapshot $provenanceRootB
    $provenanceRootBLock = Enter-AwxAutoIntakeLock $provenanceRootB
    $provenanceRootMismatch = Register-AwxAutoIntakeProcessedHash -StateRoot $provenanceRootB -Snapshot $provenanceSnapshotB -AdapterResult $provenanceAdapterA -FocusedVerificationPass:$true -RollbackReady:$true -Lock $provenanceRootBLock
    $provenanceRootMismatchRecorded = Test-AwxAutoIntakeProcessedHash -StateRoot $provenanceRootB -Snapshot $provenanceSnapshotB
    $provenanceRootBLock.Dispose()
    Test-Contract 'adapter success from another exact root cannot register same-name bundle' { $provenanceRootMismatch.decision -ceq 'HOLD' -and $provenanceRootMismatch.failureClass -ceq 'adapter-provenance-mismatch' -and -not $provenanceRootMismatchRecorded } "actual=$($provenanceRootMismatch.decision)/$($provenanceRootMismatch.failureClass);recorded=$provenanceRootMismatchRecorded"

    $legacyStateRoot = Join-Path $script:TempRoot 'legacy-v1-state'; New-Item -ItemType Directory -Path $legacyStateRoot | Out-Null
    $legacyStatePath = Join-Path $legacyStateRoot 'processed-hashes.json'
    $legacyStateText = '{"schemaVersion":"awx.desktop-patchdrop-auto-intake.processed.v1","processedHashes":["' + ('e' * 64) + '"]}'
    Write-Utf8Fixture $legacyStatePath $legacyStateText
    $legacyReplay = Get-AwxAutoIntakeReplayDecision -StateRoot $legacyStateRoot -Snapshot $applySnapshot
    $legacyStateAfter = [System.IO.File]::ReadAllText($legacyStatePath, [System.Text.UTF8Encoding]::new($false))
    Test-Contract 'legacy v1 processed state holds as unsupported without mutation' { $legacyReplay.decision -ceq 'HOLD' -and $legacyReplay.failureClass -ceq 'processed-state-schema-unsupported' -and $legacyStateAfter -ceq $legacyStateText } "actual=$($legacyReplay.decision)/$($legacyReplay.failureClass);stateUnchanged=$($legacyStateAfter -ceq $legacyStateText)"

    $unknownStateRoot = Join-Path $script:TempRoot 'unknown-schema-state'; New-Item -ItemType Directory -Path $unknownStateRoot | Out-Null
    $unknownStatePath = Join-Path $unknownStateRoot 'processed-hashes.json'
    Write-Utf8Fixture $unknownStatePath ('{"schemaVersion":"awx.desktop-patchdrop-auto-intake.processed.v99","processedHashes":["' + ('f' * 64) + '"]}')
    $unknownStateBefore = [IO.File]::ReadAllBytes($unknownStatePath)
    try { $unknownReplay = Get-AwxAutoIntakeReplayDecision -StateRoot $unknownStateRoot -Snapshot $applySnapshot }
    catch { $unknownReplay = [pscustomobject]@{ decision = 'THREW'; failureClass = $_.Exception.Message } }
    $unknownStateAfter = [IO.File]::ReadAllBytes($unknownStatePath)
    Test-Contract 'unknown processed state schema holds and preserves exact bytes' { $unknownReplay.decision -ceq 'HOLD' -and $unknownReplay.failureClass -ceq 'processed-state-schema-unsupported' -and [Linq.Enumerable]::SequenceEqual([byte[]]$unknownStateBefore,[byte[]]$unknownStateAfter) } "actual=$($unknownReplay.decision)/$($unknownReplay.failureClass);bytesPreserved=$([Linq.Enumerable]::SequenceEqual([byte[]]$unknownStateBefore,[byte[]]$unknownStateAfter))"
    $schemaV2Root = Join-Path $script:TempRoot 'schema-v2-state'; New-Item -ItemType Directory -Path $schemaV2Root | Out-Null; New-CompleteBundle $schemaV2Root
    $schemaV2Fake = New-FakeJanitor $schemaV2Root
    $schemaV2Snapshot = Get-AwxTopLevelBundleSnapshot $schemaV2Root
    $schemaV2Adapter = Invoke-AwxJanitorAdapter -Root $schemaV2Root -PatchName 'alpha-v3.patch' -AdapterPath $schemaV2Fake.adapter -ExpectedSnapshot $schemaV2Snapshot -TestMode
    $schemaV2Lock = Enter-AwxAutoIntakeLock $schemaV2Root
    $schemaV2Registration = Register-AwxAutoIntakeProcessedHash -StateRoot $schemaV2Root -Snapshot $schemaV2Snapshot -AdapterResult $schemaV2Adapter -FocusedVerificationPass:$true -RollbackReady:$true -Lock $schemaV2Lock
    $recordedState = Get-Content -Raw -LiteralPath (Join-Path $schemaV2Root 'processed-hashes.json') -ErrorAction SilentlyContinue
    $schemaV2Lock.Dispose()
    Test-Contract 'new processed state is explicitly schema v2' { $schemaV2Registration.decision -ceq 'APPLY' -and -not [string]::IsNullOrEmpty($recordedState) -and [string](($recordedState | ConvertFrom-Json).schemaVersion) -ceq 'awx.desktop-patchdrop-auto-intake.processed.v2' }

    $handleProbeRoot = Join-Path $script:TempRoot 'handle-allowlist-probe'; New-Item -ItemType Directory -Path $handleProbeRoot | Out-Null; New-CompleteBundle $handleProbeRoot
    $handleProbeFake = New-FakeJanitor $handleProbeRoot
    $handleProbeSnapshot = Get-AwxTopLevelBundleSnapshot $handleProbeRoot
    $script:AwxAutoIntakeContractProbeHandleAllowlist = $true
    try { $handleProbeResult = Invoke-AwxJanitorAdapter -Root $handleProbeRoot -PatchName 'alpha-v3.patch' -AdapterPath $handleProbeFake.adapter -ExpectedSnapshot $handleProbeSnapshot -TestMode }
    finally { $script:AwxAutoIntakeContractProbeHandleAllowlist = $false }
    $handleAllowlistProven = $null -ne $handleProbeResult.PSObject.Properties['handleAllowlistProven'] -and [bool]$handleProbeResult.handleAllowlistProven
    $sentinelInherited = $null -eq $handleProbeResult.PSObject.Properties['sentinelInherited'] -or [bool]$handleProbeResult.sentinelInherited
    $attributeSizingProbeProven = $null -ne $handleProbeResult.PSObject.Properties['attributeSizingProbeProven'] -and [bool]$handleProbeResult.attributeSizingProbeProven
    Test-Contract 'native handle allowlist excludes unrelated inheritable sentinel and proves expected sizing error' { $handleProbeResult.decision -ceq 'APPLY' -and $handleAllowlistProven -and $attributeSizingProbeProven -and -not $sentinelInherited -and (Get-FakeCallCount $handleProbeFake) -eq 1 } "actual=$($handleProbeResult.decision)/$($handleProbeResult.failureClass);allowlistProven=$handleAllowlistProven;attributeSizingProbeProven=$attributeSizingProbeProven;sentinelInherited=$sentinelInherited;calls=$(Get-FakeCallCount $handleProbeFake)"
    Write-Host "handleInheritanceEvidence=allowlistProven=$handleAllowlistProven;sentinelInherited=$sentinelInherited;adapterCalls=$(Get-FakeCallCount $handleProbeFake)"

    $assignmentFailureRoot = Join-Path $script:TempRoot 'assignment-failure-probe'; New-Item -ItemType Directory -Path $assignmentFailureRoot | Out-Null; New-CompleteBundle $assignmentFailureRoot
    $assignmentFailureFake = New-FakeJanitor $assignmentFailureRoot
    $assignmentFailureSnapshot = Get-AwxTopLevelBundleSnapshot $assignmentFailureRoot
    $script:AwxAutoIntakeContractInjectAssignmentFailure = $true
    try { $assignmentFailureResult = Invoke-AwxJanitorAdapter -Root $assignmentFailureRoot -PatchName 'alpha-v3.patch' -AdapterPath $assignmentFailureFake.adapter -ExpectedSnapshot $assignmentFailureSnapshot -TestMode }
    finally { $script:AwxAutoIntakeContractInjectAssignmentFailure = $false }
    $assignmentRootPid = if ($null -ne $assignmentFailureResult.PSObject.Properties['rootProcessId']) { [int]$assignmentFailureResult.rootProcessId } else { 0 }
    $assignmentRootAbsent = $assignmentRootPid -gt 0 -and $null -eq (Get-Process -Id $assignmentRootPid -ErrorAction SilentlyContinue)
    Test-Contract 'injected assignment failure never resumes or leaks exact root' { $assignmentFailureResult.decision -ceq 'HOLD' -and $assignmentFailureResult.failureClass -ceq 'janitor-job-assignment-unproven' -and -not [bool]$assignmentFailureResult.jobResumed -and [bool]$assignmentFailureResult.childExited -and $assignmentRootAbsent -and (Get-FakeCallCount $assignmentFailureFake) -eq 0 } "actual=$($assignmentFailureResult.decision)/$($assignmentFailureResult.failureClass);resumeReleased=$([bool]$assignmentFailureResult.jobResumed);rootPid=$assignmentRootPid;rootAbsent=$assignmentRootAbsent;calls=$(Get-FakeCallCount $assignmentFailureFake)"
    Write-Host "assignmentFailureEvidence=resumeReleased=$([bool]$assignmentFailureResult.jobResumed);rootPidRecorded=$($assignmentRootPid -gt 0);rootAbsent=$assignmentRootAbsent;adapterCalls=$(Get-FakeCallCount $assignmentFailureFake);failureClass=$($assignmentFailureResult.failureClass)"

    # Final-fix RED family: a native -File boundary must execute the complete bounded orchestration while dot-sourcing remains inert.
    $nativeObserveRoot = Join-Path $script:TempRoot ('native-observe-' + [guid]::NewGuid().ToString('N'))
    $nativeObservePatchDrop = Join-Path $nativeObserveRoot '__patch_drop__'
    $nativeObserveState = Join-Path $nativeObserveRoot 'state'
    New-Item -ItemType Directory -Path $nativeObservePatchDrop,$nativeObserveState -Force | Out-Null
    New-NativeApplyBundle -PatchDrop $nativeObservePatchDrop
    $nativeObservePolicy = New-PolicyObject; $nativeObservePolicy.enabled = $true
    $nativeObservePolicyPath = Join-Path $nativeObserveRoot 'observe-policy.json'; Write-Utf8Fixture $nativeObservePolicyPath ($nativeObservePolicy | ConvertTo-Json -Compress)
    $nativeObserveFake = New-FakeJanitor $nativeObservePatchDrop
    $nativeObserveRun = Invoke-NativeAutoIntakeFixture -WorkingDirectory $nativeObserveRoot -Arguments @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',$script:ToolPath,
        '-PolicyPath',$nativeObservePolicyPath,'-Root',$nativeObserveRoot,'-StateRoot',$nativeObserveState,
        '-AdapterPath',$nativeObserveFake.adapter,'-TestMode'
    )
    $nativeObserveResult = $null
    try { $nativeObserveResult = $nativeObserveRun.stdout | ConvertFrom-Json -ErrorAction Stop } catch { }
    $nativeObserveLedgerPath = Join-Path $nativeObserveState 'auto-intake-ledger.jsonl'
    $nativeObserveLedger = $null
    try { $nativeObserveLedger = [System.IO.File]::ReadAllText($nativeObserveLedgerPath, [System.Text.UTF8Encoding]::new($false)).Trim() | ConvertFrom-Json -ErrorAction Stop } catch { }
    Test-Contract 'native OBSERVE entrypoint emits one JSON result and one final ledger row without adapter call' {
        $nativeObserveRun.exitCode -eq 0 -and $null -ne $nativeObserveResult -and $nativeObserveResult.schemaVersion -ceq 'awx.desktop-patchdrop-auto-intake.result.v1' -and $nativeObserveResult.decision -ceq 'OBSERVE' -and $null -ne $nativeObserveLedger -and $nativeObserveLedger.decision -ceq 'OBSERVE' -and $nativeObserveLedger.desktopFinalProof -ceq 'evidence_needed' -and (Get-FakeCallCount $nativeObserveFake) -eq 0 -and @([System.IO.File]::ReadAllLines($nativeObserveLedgerPath)).Count -eq 1
    } "exit=$($nativeObserveRun.exitCode);stdoutBytes=$([Text.Encoding]::UTF8.GetByteCount($nativeObserveRun.stdout));stderrBytes=$([Text.Encoding]::UTF8.GetByteCount($nativeObserveRun.stderr));calls=$(Get-FakeCallCount $nativeObserveFake)"

    $nativeApplyRoot = Join-Path $script:TempRoot ('native-apply-' + [guid]::NewGuid().ToString('N'))
    $nativeApplyPatchDrop = Join-Path $nativeApplyRoot '__patch_drop__'
    $nativeApplyState = Join-Path $nativeApplyRoot 'state'
    New-Item -ItemType Directory -Path $nativeApplyPatchDrop,$nativeApplyState -Force | Out-Null
    New-NativeApplyBundle -PatchDrop $nativeApplyPatchDrop
    $nativeApplyPolicy = New-PolicyObject; $nativeApplyPolicy.enabled = $true; $nativeApplyPolicy.mode = 'APPLY'; $nativeApplyPolicy.allowedNodes = @('notebook'); $nativeApplyPolicy.allowedTopics = @('alpha'); $nativeApplyPolicy.allowedPathPrefixes = @('main/java')
    $nativeApplyPolicyPath = Join-Path $nativeApplyRoot 'apply-policy.json'; Write-Utf8Fixture $nativeApplyPolicyPath ($nativeApplyPolicy | ConvertTo-Json -Compress)
    $nativeApplySnapshot = Get-AwxTopLevelBundleSnapshot $nativeApplyPatchDrop
    $nativeApplyEvidencePath = Join-Path $nativeApplyRoot 'run-evidence.json'; Write-NativeApplyEvidenceFixture -Path $nativeApplyEvidencePath -Snapshot $nativeApplySnapshot
    $nativeApplyVerificationPath = Join-Path $nativeApplyRoot 'verification.json'; Write-NativeVerificationFixture -Path $nativeApplyVerificationPath
    $nativeApplyFake = New-FakeJanitor $nativeApplyPatchDrop
    $nativeApplyRun = Invoke-NativeAutoIntakeFixture -WorkingDirectory $nativeApplyRoot -Arguments @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',$script:ToolPath,
        '-PolicyPath',$nativeApplyPolicyPath,'-Root',$nativeApplyRoot,'-StateRoot',$nativeApplyState,
        '-EvidencePath',$nativeApplyEvidencePath,'-VerificationEvidencePath',$nativeApplyVerificationPath,
        '-AdapterPath',$nativeApplyFake.adapter,'-TestMode'
    )
    $nativeApplyResult = $null
    try { $nativeApplyResult = $nativeApplyRun.stdout | ConvertFrom-Json -ErrorAction Stop } catch { }
    Test-Contract 'native valid APPLY calls one contained fake adapter and commits one provenance-bound processed result' {
        $nativeApplyRun.exitCode -eq 0 -and $null -ne $nativeApplyResult -and $nativeApplyResult.decision -ceq 'APPLY' -and $nativeApplyResult.verificationPassed -eq $true -and $nativeApplyResult.rollbackResult -ceq 'READY' -and (Get-FakeCallCount $nativeApplyFake) -eq 1 -and (Test-Path -LiteralPath (Join-Path $nativeApplyState 'processed-hashes.json') -PathType Leaf) -and @([System.IO.File]::ReadAllLines((Join-Path $nativeApplyState 'auto-intake-ledger.jsonl'))).Count -eq 1
    } "exit=$($nativeApplyRun.exitCode);stdoutBytes=$([Text.Encoding]::UTF8.GetByteCount($nativeApplyRun.stdout));stderrBytes=$([Text.Encoding]::UTF8.GetByteCount($nativeApplyRun.stderr));calls=$(Get-FakeCallCount $nativeApplyFake)"

    # Final-fix RED family: a post-decision patch or manifest replacement must stop before the fake janitor starts.
    $swapRoot = Join-Path $script:TempRoot ('snapshot-swap-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $swapRoot -Force | Out-Null
    New-NativeApplyBundle -PatchDrop $swapRoot
    $swapSnapshot = Get-AwxTopLevelBundleSnapshot $swapRoot
    $swapFake = New-FakeJanitor $swapRoot
    Write-Utf8Fixture (Join-Path $swapRoot 'alpha-v3.patch') "diff --git a/main/java/com/example/Alpha.java b/main/java/com/example/Alpha.java`n--- a/main/java/com/example/Alpha.java`n+++ b/main/java/com/example/Alpha.java`n@@ -1 +1 @@`n-old`n+swapped`n"
    try { $swapResult = Invoke-AwxJanitorAdapter -Root $swapRoot -PatchName 'alpha-v3.patch' -AdapterPath $swapFake.adapter -ExpectedSnapshot $swapSnapshot -TestMode }
    catch { $swapResult = [pscustomobject]@{ decision = 'THREW'; failureClass = $_.Exception.Message } }
    Test-Contract 'post-decision bundle swap yields zero fake-janitor calls' { $swapResult.decision -ceq 'HOLD' -and $swapResult.failureClass -ceq 'bundle-snapshot-changed' -and (Get-FakeCallCount $swapFake) -eq 0 } "actual=$($swapResult.decision)/$($swapResult.failureClass);calls=$(Get-FakeCallCount $swapFake)"

    # Final-fix RED family: the scanner must reject by file length before allocating/reading an oversized sparse payload.
    $oversizeRoot = Join-Path $script:TempRoot ('oversize-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $oversizeRoot -Force | Out-Null
    New-CompleteBundle $oversizeRoot
    $oversizePatch = Join-Path $oversizeRoot 'alpha-v3.patch'
    $oversizeStream = [System.IO.File]::Open($oversizePatch, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try { $oversizeStream.SetLength(1025); $oversizeStream.Flush($true) } finally { $oversizeStream.Dispose() }
    $savedAbsolutePatchCap = $script:AwxAutoIntakeMaxAdapterPatchBytes; $script:AwxAutoIntakeMaxAdapterPatchBytes = 1024
    try { $oversizeSnapshot = Get-AwxTopLevelBundleSnapshot $oversizeRoot } finally { $script:AwxAutoIntakeMaxAdapterPatchBytes = $savedAbsolutePatchCap }
    Test-Contract 'oversized sparse patch is rejected by absolute cap before payload read' { $oversizeSnapshot.failureClass -ceq 'patch-size-limit-exceeded' -and $null -eq $oversizeSnapshot.candidate } "actual=$($oversizeSnapshot.failureClass)"

    # Final-fix RED family: ledger rows carry the complete design outcome only after the final adapter/verification state.
    $outcomeFixture = [pscustomobject][ordered]@{
        schemaVersion = 'awx.desktop-patchdrop-auto-intake.outcome.v1'; runIdHash = ('1' * 64); bundleIdHash = ('2' * 64); mode = 'APPLY'; decision = 'HOLD'; failureClass = 'janitor-timeout'; patchName = 'alpha-v3.patch'; queueCount = 1; secretPatternHitCount = 0; leaseConflict = $false; dirtyOverlap = $false; preimageMatch = $true; verificationPassed = $false; rollbackReady = $true; rollbackResult = 'AMBIGUOUS'; elapsedMs = 250; desktopFinalProof = 'evidence_needed'
    }
    Test-Contract 'ledger accepts exact bounded final outcome schema' { Test-AwxAutoIntakeLedgerOutcome $outcomeFixture }

    # Final-fix RED family: a mutation followed by timeout is tree-killed, never processed, and records rollback ambiguity.
    $timeoutRoot = Join-Path $script:TempRoot ('native-timeout-' + [guid]::NewGuid().ToString('N'))
    $timeoutPatchDrop = Join-Path $timeoutRoot '__patch_drop__'; $timeoutState = Join-Path $timeoutRoot 'state'
    New-Item -ItemType Directory -Path $timeoutPatchDrop,$timeoutState -Force | Out-Null
    New-NativeApplyBundle -PatchDrop $timeoutPatchDrop
    $timeoutPolicy = New-PolicyObject; $timeoutPolicy.enabled = $true; $timeoutPolicy.mode = 'APPLY'; $timeoutPolicy.allowedNodes = @('notebook'); $timeoutPolicy.allowedTopics = @('alpha'); $timeoutPolicy.allowedPathPrefixes = @('main/java')
    $timeoutPolicyPath = Join-Path $timeoutRoot 'apply-policy.json'; Write-Utf8Fixture $timeoutPolicyPath ($timeoutPolicy | ConvertTo-Json -Compress)
    $timeoutSnapshot = Get-AwxTopLevelBundleSnapshot $timeoutPatchDrop
    $timeoutEvidencePath = Join-Path $timeoutRoot 'run-evidence.json'; Write-NativeApplyEvidenceFixture -Path $timeoutEvidencePath -Snapshot $timeoutSnapshot
    $timeoutVerificationPath = Join-Path $timeoutRoot 'verification.json'; Write-NativeVerificationFixture -Path $timeoutVerificationPath
    $timeoutCallLog = Join-Path $timeoutPatchDrop 'timeout-calls.txt'; $timeoutMutation = Join-Path $timeoutPatchDrop 'mutation-marker.txt'; $timeoutPid = Join-Path $timeoutPatchDrop 'timeout.pid'; $timeoutAdapter = Join-Path $timeoutPatchDrop 'timeout-fake-janitor.ps1'
    $escapedTimeoutCallLog = $timeoutCallLog.Replace("'", "''"); $escapedTimeoutMutation = $timeoutMutation.Replace("'", "''"); $escapedTimeoutPid = $timeoutPid.Replace("'", "''")
    Write-Utf8Fixture $timeoutAdapter "param([string]`$PatchName)`n[IO.File]::AppendAllText('$escapedTimeoutCallLog',`$PatchName+[Environment]::NewLine,[Text.UTF8Encoding]::new(`$false))`n[IO.File]::WriteAllText('$escapedTimeoutMutation','mutated',[Text.UTF8Encoding]::new(`$false))`n[IO.File]::WriteAllText('$escapedTimeoutPid',[string]`$PID,[Text.UTF8Encoding]::new(`$false))`nStart-Sleep -Seconds 10`nexit 0`n"
    $timeoutRun = Invoke-NativeAutoIntakeFixture -WorkingDirectory $timeoutRoot -Arguments @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',$script:ToolPath,
        '-PolicyPath',$timeoutPolicyPath,'-Root',$timeoutRoot,'-StateRoot',$timeoutState,
        '-EvidencePath',$timeoutEvidencePath,'-VerificationEvidencePath',$timeoutVerificationPath,
        '-AdapterPath',$timeoutAdapter,'-AdapterTimeoutMilliseconds','250','-TestMode'
    )
    $timeoutResult = $null; try { $timeoutResult = $timeoutRun.stdout | ConvertFrom-Json -ErrorAction Stop } catch { }
    $timeoutPidValue = 0; if (Test-Path -LiteralPath $timeoutPid) { [void][int]::TryParse(([IO.File]::ReadAllText($timeoutPid)).Trim(), [ref]$timeoutPidValue) }
    $timeoutProcessAbsent = $timeoutPidValue -gt 0 -and $null -eq (Get-Process -Id $timeoutPidValue -ErrorAction SilentlyContinue)
    $timeoutCalls = if (Test-Path -LiteralPath $timeoutCallLog) { @([IO.File]::ReadAllLines($timeoutCallLog)).Count } else { 0 }
    Test-Contract 'mutation-then-delay timeout leaves no processed commit and classifies rollback ambiguity' {
        $timeoutRun.exitCode -eq 2 -and $null -ne $timeoutResult -and $timeoutResult.decision -ceq 'HOLD' -and $timeoutResult.failureClass -ceq 'janitor-timeout' -and $timeoutResult.rollbackResult -ceq 'AMBIGUOUS' -and -not [bool]$timeoutResult.verificationPassed -and $timeoutCalls -eq 1 -and (Test-Path -LiteralPath $timeoutMutation -PathType Leaf) -and -not (Test-Path -LiteralPath (Join-Path $timeoutState 'processed-hashes.json') -PathType Leaf) -and $timeoutProcessAbsent
    } "exit=$($timeoutRun.exitCode);decision=$($timeoutResult.decision);failure=$($timeoutResult.failureClass);rollback=$($timeoutResult.rollbackResult);verification=$($timeoutResult.verificationPassed);calls=$timeoutCalls;pidRecorded=$($timeoutPidValue -gt 0);processAbsent=$timeoutProcessAbsent;stderrBytes=$([Text.Encoding]::UTF8.GetByteCount($timeoutRun.stderr))"

    # Adjacent Minor contracts.
    $redactionPolicyPath = Join-Path $script:TempRoot 'parser-redaction.json'; Write-Utf8Fixture $redactionPolicyPath '{"raw":"SENSITIVE-PARSER-SENTINEL"}'
    $redactionMessage = ''; try { $ignored = Read-AwxAutoIntakePolicy $redactionPolicyPath } catch { $redactionMessage = $_.Exception.Message }
    Test-Contract 'policy parser failure is exact and excludes raw policy bytes' { $redactionMessage -ceq 'policy-invalid' -and $redactionMessage -notmatch 'SENSITIVE-PARSER-SENTINEL' }

    $provenanceNoLockRoot = Join-Path $script:TempRoot ('provenance-no-lock-' + [guid]::NewGuid().ToString('N')); New-Item -ItemType Directory -Path $provenanceNoLockRoot | Out-Null
    $provenanceNoLockMessage = ''; try { $ignored = Register-AwxAutoIntakeProcessedHash -StateRoot $provenanceNoLockRoot -Snapshot $processedSnapshot -AdapterResult $processedAdapter -FocusedVerificationPass:$true -RollbackReady:$true } catch { $provenanceNoLockMessage = $_.Exception.Message }
    Test-Contract 'provenance-valid processed registration still requires the live lock' { $provenanceNoLockMessage -ceq 'lock-required' -and -not (Test-Path -LiteralPath (Join-Path $provenanceNoLockRoot 'processed-hashes.json') -PathType Leaf) }

    $backupRoot = Join-Path $script:TempRoot ('backup-cleanup-' + [guid]::NewGuid().ToString('N')); New-Item -ItemType Directory -Path $backupRoot | Out-Null; Write-Utf8Fixture (Join-Path $backupRoot 'state.json') 'before'
    $script:AwxAutoIntakeContractInjectBackupCleanupFailure = $true; $backupFailure = ''
    try { Write-AwxAutoIntakeAtomicUtf8 -Directory $backupRoot -Name 'state.json' -Content 'after' } catch { $backupFailure = $_.Exception.Message } finally { $script:AwxAutoIntakeContractInjectBackupCleanupFailure = $false }
    Test-Contract 'successful replacement with backup cleanup failure has an explicit non-rollback classification' { $backupFailure -ceq 'state-replaced-backup-cleanup-unproven' -and [IO.File]::ReadAllText((Join-Path $backupRoot 'state.json')) -ceq 'after' } "actual=$backupFailure"

    $script:AwxAutoIntakeContractOverridePowerShellPath = Join-Path $script:TempRoot 'missing-powershell.exe'
    try { $createFailureResult = Invoke-AwxJanitorAdapter -Root $fakeRoot -PatchName 'alpha-v3.patch' -AdapterPath $fake.adapter -ExpectedSnapshot (Get-AwxTopLevelBundleSnapshot $fakeRoot) -TestMode }
    catch { $createFailureResult = [pscustomobject]@{ decision = 'THREW'; failureClass = $_.Exception.Message; runtimeReason = '' } }
    finally { $script:AwxAutoIntakeContractOverridePowerShellPath = '' }
    Test-Contract 'process creation failure is sanitized and not collapsed into assignment failure' { $createFailureResult.decision -ceq 'HOLD' -and $createFailureResult.failureClass -ceq 'janitor-process-create-failed' -and $createFailureResult.runtimeReason -ceq 'process-create-failed' -and $createFailureResult.PSObject.Properties.Name -notcontains 'rawError' } "actual=$($createFailureResult.decision)/$($createFailureResult.failureClass)/$($createFailureResult.runtimeReason)"

    $invalidDecision = Get-AwxAutoIntakeDecision -Policy $null -Snapshot ([pscustomobject]@{ queueCount = 0; failureClass = 'queue-empty'; candidate = $null }) -Evidence $null
    Test-Contract 'policy invalid takes precedence' { $invalidDecision.decision -ceq 'HOLD' -and $invalidDecision.failureClass -ceq 'policy-invalid' }

    # Regression break named: rendering a non-deterministic task definition, or one that contains a credential field.
    $taskBefore = Get-RealAutoIntakeTaskSnapshot
    Test-Contract 'task manager exists for render contract' { Test-Path -LiteralPath $script:TaskManagerPath -PathType Leaf } 'task manager missing'
    if (Test-Path -LiteralPath $script:TaskManagerPath -PathType Leaf) { . $script:TaskManagerPath }

    # Regression breaks named: ACL evaluators trust an untrusted mutating ACE, inspect an inherited-only ACE on the
    # current object, miss numeric/composite rights, or accept an owner identity that cannot resolve to an exact SID.
    $systemSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $administratorsSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $usersSid = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')

    $trustedDirectoryAcl = [System.Security.AccessControl.DirectorySecurity]::new()
    $trustedDirectoryAcl.SetOwner($systemSid)
    $trustedDirectoryAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($administratorsSid,[System.Security.AccessControl.FileSystemRights]::Modify,[System.Security.AccessControl.InheritanceFlags]::None,[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow))
    $trustedFileAcl = [System.Security.AccessControl.FileSecurity]::new()
    $trustedFileAcl.SetOwner($administratorsSid)
    $trustedFileAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($systemSid,[System.Security.AccessControl.FileSystemRights]::WriteData,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'filesystem ACL evaluator accepts trusted SYSTEM and Administrators mutating allows' { (Test-AwxAutoIntakeTaskSecureFileSystemAcl $trustedDirectoryAcl) -and (Test-AwxAutoIntakeTaskSecureFileSystemAcl $trustedFileAcl) }

    $untrustedDirectoryAcl = [System.Security.AccessControl.DirectorySecurity]::new()
    $untrustedDirectoryAcl.SetOwner($systemSid)
    $untrustedDirectoryAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($usersSid,[System.Security.AccessControl.FileSystemRights]::WriteData,[System.Security.AccessControl.InheritanceFlags]::None,[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'filesystem ACL evaluator rejects untrusted mutating allow' { -not (Test-AwxAutoIntakeTaskSecureFileSystemAcl $untrustedDirectoryAcl) }

    $inheritOnlyDirectoryAcl = [System.Security.AccessControl.DirectorySecurity]::new()
    $inheritOnlyDirectoryAcl.SetOwner($systemSid)
    $inheritOnlyDirectoryAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($usersSid,[System.Security.AccessControl.FileSystemRights]::WriteData,[System.Security.AccessControl.InheritanceFlags]::ContainerInherit,[System.Security.AccessControl.PropagationFlags]::InheritOnly,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'filesystem ACL evaluator ignores InheritOnly mutating rule for current object' { Test-AwxAutoIntakeTaskSecureFileSystemAcl $inheritOnlyDirectoryAcl }

    $numericFileAcl = [System.Security.AccessControl.FileSecurity]::new()
    $numericFileAcl.SetOwner($systemSid)
    $numericFileRights = [System.Security.AccessControl.FileSystemRights](([int][System.Security.AccessControl.FileSystemRights]::ReadAndExecute) -bor ([int][System.Security.AccessControl.FileSystemRights]::WriteData))
    $numericFileAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($usersSid,$numericFileRights,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'filesystem ACL evaluator detects numeric composite mutating rights' { -not (Test-AwxAutoIntakeTaskSecureFileSystemAcl $numericFileAcl) }
    Test-Contract 'filesystem ACL evaluator rejects missing or untranslatable owner identity' { -not (Test-AwxAutoIntakeTaskSecureFileSystemAcl ([System.Security.AccessControl.FileSecurity]::new())) -and -not (Test-AwxAutoIntakeTaskSecureFileSystemAcl ([pscustomobject]@{Owner='AWX-NO-SUCH-DOMAIN\AWX-NO-SUCH-OWNER';Access=@()})) }

    $trustedRegistryAcl = [System.Security.AccessControl.RegistrySecurity]::new()
    $trustedRegistryAcl.SetOwner($systemSid)
    $trustedRegistryAcl.AddAccessRule([System.Security.AccessControl.RegistryAccessRule]::new($administratorsSid,[System.Security.AccessControl.RegistryRights]::SetValue,[System.Security.AccessControl.InheritanceFlags]::None,[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'registry ACL evaluator accepts trusted SYSTEM owner and Administrators mutating allow' { Test-AwxAutoIntakeTaskSecureRegistryAcl $trustedRegistryAcl }

    $untrustedRegistryAcl = [System.Security.AccessControl.RegistrySecurity]::new()
    $untrustedRegistryAcl.SetOwner($systemSid)
    $untrustedRegistryAcl.AddAccessRule([System.Security.AccessControl.RegistryAccessRule]::new($usersSid,[System.Security.AccessControl.RegistryRights]::SetValue,[System.Security.AccessControl.InheritanceFlags]::None,[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'registry ACL evaluator rejects untrusted mutating allow' { -not (Test-AwxAutoIntakeTaskSecureRegistryAcl $untrustedRegistryAcl) }

    $inheritOnlyRegistryAcl = [System.Security.AccessControl.RegistrySecurity]::new()
    $inheritOnlyRegistryAcl.SetOwner($systemSid)
    $inheritOnlyRegistryAcl.AddAccessRule([System.Security.AccessControl.RegistryAccessRule]::new($usersSid,[System.Security.AccessControl.RegistryRights]::SetValue,[System.Security.AccessControl.InheritanceFlags]::ContainerInherit,[System.Security.AccessControl.PropagationFlags]::InheritOnly,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'registry ACL evaluator ignores InheritOnly mutating rule for current key' { Test-AwxAutoIntakeTaskSecureRegistryAcl $inheritOnlyRegistryAcl }

    $numericRegistryAcl = [System.Security.AccessControl.RegistrySecurity]::new()
    $numericRegistryAcl.SetOwner($systemSid)
    $numericRegistryRights = [System.Security.AccessControl.RegistryRights](([int][System.Security.AccessControl.RegistryRights]::QueryValues) -bor ([int][System.Security.AccessControl.RegistryRights]::SetValue))
    $numericRegistryAcl.AddAccessRule([System.Security.AccessControl.RegistryAccessRule]::new($usersSid,$numericRegistryRights,[System.Security.AccessControl.InheritanceFlags]::None,[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow))
    Test-Contract 'registry ACL evaluator detects numeric composite mutating rights' { -not (Test-AwxAutoIntakeTaskSecureRegistryAcl $numericRegistryAcl) }
    Test-Contract 'registry ACL evaluator rejects missing or untranslatable owner identity' { -not (Test-AwxAutoIntakeTaskSecureRegistryAcl ([System.Security.AccessControl.RegistrySecurity]::new())) -and -not (Test-AwxAutoIntakeTaskSecureRegistryAcl ([pscustomobject]@{Owner='AWX-NO-SUCH-DOMAIN\AWX-NO-SUCH-OWNER';Access=@()})) }

    $exactAclProbeDirectory = Join-Path $script:TempRoot ('acl-exact-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $exactAclProbeDirectory | Out-Null
    $exactAclProbeLeaf = Join-Path $exactAclProbeDirectory ('attestation-' + [guid]::NewGuid().ToString('N') + '.json')
    Write-Utf8Fixture $exactAclProbeLeaf '{}'
    Test-Contract 'exact-object filesystem helper safely rejects untrusted GUID temp directory and leaf' { -not (Test-AwxAutoIntakeTaskSecureSystemFile $exactAclProbeDirectory) -and -not (Test-AwxAutoIntakeTaskSecureSystemFile $exactAclProbeLeaf) }
    Test-Contract 'real replacement-boundary evaluator rejects ordinary writable GUID temp path without ACL mutation' { -not (Test-AwxAutoIntakeTaskSecureReplacementBoundary -Path $exactAclProbeLeaf) }

    # Final-fix RED family: task rendering/install must consume the consumer's exact policy schema and interval.
    $strictIntervalDefinitions = @{}
    foreach ($interval in @(1,5,60)) {
        $intervalPolicy = New-PolicyObject; $intervalPolicy.enabled = $true; $intervalPolicy.pollIntervalMinutes = $interval
        $intervalPath = Write-PolicyFixture ("task-policy-$interval.json") $intervalPolicy
        try { $strictIntervalDefinitions[$interval] = New-AwxAutoIntakeTaskDefinition -PolicyPath $intervalPath } catch { $strictIntervalDefinitions[$interval] = $null }
    }
    Test-Contract 'task definitions derive valid 1 5 and 60 minute intervals from strict policy' {
        $null -ne $strictIntervalDefinitions[1] -and [int]$strictIntervalDefinitions[1].intervalMinutes -eq 1 -and $null -ne $strictIntervalDefinitions[5] -and [int]$strictIntervalDefinitions[5].intervalMinutes -eq 5 -and $null -ne $strictIntervalDefinitions[60] -and [int]$strictIntervalDefinitions[60].intervalMinutes -eq 60
    }
    $taskPolicyInvalidCases = @(
        @{ name='malformed'; value='{' },
        @{ name='extra'; value=$null; mutate={param($p)$p.extra='blocked'} },
        @{ name='missing'; value=$null; mutate={param($p)$p.Remove('verificationProfile')} }
    )
    foreach ($case in $taskPolicyInvalidCases) {
        if ($null -ne $case.value) { $invalidTaskPolicyPath = Write-PolicyFixture ("task-policy-$($case.name).json") $case.value }
        else { $invalidTaskPolicy = New-PolicyObject; & $case.mutate $invalidTaskPolicy; $invalidTaskPolicyPath = Write-PolicyFixture ("task-policy-$($case.name).json") $invalidTaskPolicy }
        $taskPolicyFailure = ''; try { $ignored = New-AwxAutoIntakeTaskDefinition -PolicyPath $invalidTaskPolicyPath } catch { $taskPolicyFailure = $_.Exception.Message }
        Test-Contract ("task policy parser rejects $($case.name) strict schema") { $taskPolicyFailure -ceq 'policy-invalid' } "actual=$taskPolicyFailure"
    }

    $renderPolicyPath = $script:SamplePolicyPath
    $renderOne = Join-Path $script:TempRoot ('task-render-' + [guid]::NewGuid().ToString('N') + '-one.json')
    $renderTwo = Join-Path $script:TempRoot ('task-render-' + [guid]::NewGuid().ToString('N') + '-two.json')
    try {
        $renderError = ''
        $renderDefinition = New-AwxAutoIntakeTaskDefinition -PolicyPath $renderPolicyPath
        $renderHash = Get-AwxAutoIntakeTaskActionSha256 -Definition $renderDefinition
        $renderInvocationOne = & $script:TaskManagerPath -Action Render -PolicyPath $renderPolicyPath -OutputPath $renderOne -TestMode
        $renderInvocationTwo = & $script:TaskManagerPath -Action Render -PolicyPath $renderPolicyPath -OutputPath $renderTwo -TestMode
        $renderInvocationOneSummary=[string]$renderInvocationOne.decision+'/'+$(if($null-ne$renderInvocationOne.PSObject.Properties['failureClass']){[string]$renderInvocationOne.failureClass}else{''})
        $renderInvocationTwoSummary=[string]$renderInvocationTwo.decision+'/'+$(if($null-ne$renderInvocationTwo.PSObject.Properties['failureClass']){[string]$renderInvocationTwo.failureClass}else{''})
        $renderOneBytes = [System.IO.File]::ReadAllBytes($renderOne)
        $renderTwoBytes = [System.IO.File]::ReadAllBytes($renderTwo)
        $renderJson = [System.Text.Encoding]::UTF8.GetString($renderOneBytes) | ConvertFrom-Json
    } catch {
        $renderError = $_.Exception.Message
        $renderDefinition = $null; $renderHash = ''; $renderOneBytes = @(); $renderTwoBytes = @(); $renderJson = $null
    }
    Test-Contract 'render definition fixes task command interval and noninteractive action' {
        $null -ne $renderDefinition -and $renderDefinition.taskName -ceq 'AwxDesktopPatchDropAutoIntake' -and $renderDefinition.execute -ceq 'powershell.exe' -and $renderDefinition.arguments -match '^-NoProfile -NonInteractive -ExecutionPolicy Bypass ' -and [int]$renderDefinition.intervalMinutes -ge 1 -and [int]$renderDefinition.intervalMinutes -le 60 -and $renderDefinition.multipleInstances -ceq 'IgnoreNew' -and [int]$renderDefinition.executionTimeLimitMinutes -gt 0
    } "error=$renderError;definitionNull=$($null -eq $renderDefinition);invocationOne=$renderInvocationOneSummary;invocationTwo=$renderInvocationTwoSummary"
    Test-Contract 'render output is byte deterministic and has no credential fields' {
        $renderOneBytes.Length -gt 0 -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$renderOneBytes, [byte[]]$renderTwoBytes) -and $renderJson.taskName -ceq 'AwxDesktopPatchDropAutoIntake' -and $renderJson.actionSha256 -ceq $renderHash -and $renderJson.PSObject.Properties.Name -notcontains 'password' -and $renderJson.PSObject.Properties.Name -notcontains 'credential'
    } "error=$renderError;bytesOne=$($renderOneBytes.Length);bytesTwo=$($renderTwoBytes.Length)"
    $nativeTaskRenderOutput = Join-Path $script:TempRoot ('task-render-native-' + [guid]::NewGuid().ToString('N') + '.json')
    $nativeTaskRenderRun = Invoke-NativeAutoIntakeFixture -WorkingDirectory $script:TempRoot -Arguments @(
        '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$script:TaskManagerPath,
        '-Action','Render','-PolicyPath',$renderPolicyPath,'-OutputPath',$nativeTaskRenderOutput,'-TestMode','-CompactJson'
    )
    $nativeTaskRenderJson = $null
    try { $nativeTaskRenderJson = $nativeTaskRenderRun.stdout | ConvertFrom-Json -ErrorAction Stop } catch { }
    Test-Contract 'native compact Render emits one machine-readable result for cross-process proof' {
        $nativeTaskRenderRun.exitCode -eq 0 -and [string]::IsNullOrEmpty($nativeTaskRenderRun.stderr) -and $null -ne $nativeTaskRenderJson -and $nativeTaskRenderJson.decision -ceq 'RENDERED' -and $nativeTaskRenderJson.actionSha256 -match '^[a-f0-9]{64}$' -and $nativeTaskRenderJson.taskIdentitySha256 -match '^[a-f0-9]{64}$' -and (Test-Path -LiteralPath $nativeTaskRenderOutput -PathType Leaf)
    } "exit=$($nativeTaskRenderRun.exitCode);stderrBytes=$([Text.UTF8Encoding]::new($false).GetByteCount($nativeTaskRenderRun.stderr));jsonParsed=$($null-ne$nativeTaskRenderJson)"
    $taskAfterRender = Get-RealAutoIntakeTaskSnapshot
    Test-Contract 'render leaves matching real scheduled task count and state unchanged' {
        $taskBefore.available -eq $taskAfterRender.available -and $taskBefore.count -eq $taskAfterRender.count -and (@($taskBefore.states) -join '|') -ceq (@($taskAfterRender.states) -join '|')
    } "available=$($taskAfterRender.available);countBefore=$($taskBefore.count);countAfter=$($taskAfterRender.count);stateUnchanged=$((@($taskBefore.states) -join '|') -ceq (@($taskAfterRender.states) -join '|'))"

    # Regression break named: a Notebook/TestMode non-Render request reaches a ScheduledTasks operation instead of holding before it.
    $taskBeforeGuards = Get-RealAutoIntakeTaskSnapshot
    $guardedInstall = & $script:TaskManagerPath -Action Install -PolicyPath $renderPolicyPath -ConfirmActionSha256 $renderHash -TestMode
    $guardedStatus = & $script:TaskManagerPath -Action Status -PolicyPath $renderPolicyPath -TestMode
    $guardedUninstall = & $script:TaskManagerPath -Action Uninstall -PolicyPath $renderPolicyPath -ConfirmActionSha256 $renderHash -TestMode
    $unprovenInstall = & $script:TaskManagerPath -Action Install -PolicyPath $renderPolicyPath -ConfirmActionSha256 $renderHash
    $taskAfterGuards = Get-RealAutoIntakeTaskSnapshot
    Test-Contract 'test mode holds every non-render action before task access' {
        @($guardedInstall,$guardedStatus,$guardedUninstall | ForEach-Object { $_.decision -ceq 'HOLD' -and $_.failureClass -ceq 'testmode-render-only' }) -notcontains $false
    }
    Test-Contract 'install without fixed Desktop attestation holds before task access' { $unprovenInstall.decision -ceq 'HOLD' -and $unprovenInstall.failureClass -ceq 'desktop-attestation-unproven' }
    Test-Contract 'guarded status output has no raw task arguments or paths' {
        @($guardedStatus.PSObject.Properties.Name | Where-Object { $_ -match '(?i)(argument|path|command|working)' }).Count -eq 0
    }
    Test-Contract 'guarded non-render requests leave matching task count and state unchanged' {
        $taskBeforeGuards.available -eq $taskAfterGuards.available -and $taskBeforeGuards.count -eq $taskAfterGuards.count -and (@($taskBeforeGuards.states) -join '|') -ceq (@($taskAfterGuards.states) -join '|')
    } "available=$($taskAfterGuards.available);countBefore=$($taskBeforeGuards.count);countAfter=$($taskAfterGuards.count);stateUnchanged=$((@($taskBeforeGuards.states) -join '|') -ceq (@($taskAfterGuards.states) -join '|'))"

    # Regression breaks named: caller-forged Desktop proof, task replacement races, and an action hash that misses WorkingDirectory.
    $realTaskBeforeFakeScheduler = Get-RealAutoIntakeTaskSnapshot
    $fakeTaskRoot = Join-Path $script:TempRoot 'task-manager-fake'; New-Item -ItemType Directory -Path $fakeTaskRoot -Force | Out-Null
    $script:AwxAutoIntakeDesktopRoot = Join-Path $fakeTaskRoot 'desktop-root'; $script:AwxAutoIntakeConsumerPath = Join-Path $script:AwxAutoIntakeDesktopRoot 'scripts\desktop_patchdrop_auto_intake.ps1'; $script:AwxAutoIntakeTaskAttestationPath = Join-Path $fakeTaskRoot 'attestation.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $script:AwxAutoIntakeConsumerPath) -Force | Out-Null; Write-Utf8Fixture $script:AwxAutoIntakeConsumerPath 'consumer-v1'
    $fakePolicyValue = New-PolicyObject; $fakePolicyValue.enabled = $true
    $fakePolicyPath = Join-Path $fakeTaskRoot 'external-policy.json'; Write-Utf8Fixture $fakePolicyPath ($fakePolicyValue | ConvertTo-Json -Compress)
    function Get-AwxAutoIntakeTaskSystemSid { 'S-1-5-18' }
    function Get-AwxAutoIntakeTaskMachineBindingSha256 { 'a' * 64 }
    $script:FakeAnchorPresent=$true
    function Get-AwxAutoIntakeTaskProvisioningAnchor { if($script:FakeAnchorPresent){[pscustomobject]@{schemaVersion='awx.desktop-patchdrop-auto-intake.provisioning.v1';machineBindingSha256=('a'*64);provisioningIdentitySha256=('b'*64)}} }
    $script:FakeReparseBlock = $false; $script:FakeHandleDrift = $false; $script:FakeHandleReadCount = 0
    function Test-AwxAutoIntakeTaskReparseFreePath { param([string]$Path) -not [bool]$script:FakeReparseBlock }
    $script:FakeSecureSystemFilePaths = @()
    function Test-AwxAutoIntakeTaskSecureSystemFile { param([string]$Path) $script:FakeSecureSystemFilePaths += [IO.Path]::GetFullPath($Path); $true }
    function Get-AwxAutoIntakeTaskHandleBytes { param([IO.Stream]$Handle,[int64]$MaxBytes=65536) $script:FakeHandleReadCount++; if($script:FakeHandleDrift -and $script:FakeHandleReadCount -eq 1){return [Text.UTF8Encoding]::new($false).GetBytes('consumer-drift')}; $Handle.Position=0; $memory=[IO.MemoryStream]::new(); $buffer=New-Object byte[] 4096; $total=0; while(($read=$Handle.Read($buffer,0,$buffer.Length)) -gt 0){$total+=$read;if($total -gt $MaxBytes){throw 'bounded-read-exceeded'};$memory.Write($buffer,0,$read)}; return $memory.ToArray() }
    $script:OriginalTaskProductionAttestation = ${function:Test-AwxAutoIntakeTaskProductionAttestation}
    $script:FakeProductionAttestationCalls = 0
    $script:FakeProductionAttestationBypass = $false
    function Test-AwxAutoIntakeTaskProductionAttestation { param([object]$Definition) $script:FakeProductionAttestationCalls++; if($script:FakeProductionAttestationBypass){return $true}; return & $script:OriginalTaskProductionAttestation -Definition $Definition }
    function Write-FakeTaskAttestation {
        param([switch]$Malformed)
        if ($Malformed) { Write-Utf8Fixture $script:AwxAutoIntakeTaskAttestationPath '{"schemaVersion":"bad"}'; return }
        $a = [ordered]@{ schemaVersion = 'awx.desktop-patchdrop-auto-intake.attestation.v1'; machineBindingSha256 = 'a' * 64; provisioningIdentitySha256='b'*64; desktopRootPathSha256 = Get-AwxAutoIntakeTaskPathSha256 $script:AwxAutoIntakeDesktopRoot; consumerPathSha256 = Get-AwxAutoIntakeTaskPathSha256 $script:AwxAutoIntakeConsumerPath; consumerContentSha256 = Get-AwxAutoIntakeTaskFileSha256 $script:AwxAutoIntakeConsumerPath; policyPathSha256 = Get-AwxAutoIntakeTaskPathSha256 $fakePolicyPath; policyContentSha256 = Get-AwxAutoIntakeTaskFileSha256 $fakePolicyPath }
        Write-Utf8Fixture $script:AwxAutoIntakeTaskAttestationPath ($a | ConvertTo-Json -Compress)
    }
    function New-FakeScheduledTask {
        param([object]$Action,[bool]$Enabled=$false,[object]$Trigger=$null,[object]$Settings=$null,[object]$Principal=$null)
        if($null -eq $Trigger){$Trigger=[pscustomobject]@{TriggerType='Once';At=[datetime]'2000-01-01T00:00:00';StartBoundary='2000-01-01T00:00:00';RepetitionInterval=[timespan]::FromMinutes(5);Repetition=[pscustomobject]@{Interval='PT5M';Duration=''}}}
        if($null -eq $Settings){$Settings=[pscustomobject]@{Enabled=$Enabled;MultipleInstances='IgnoreNew';ExecutionTimeLimit=[timespan]::FromMinutes(15)}}else{$Settings.Enabled=$Enabled}
        if($null -eq $Principal){$Principal=[pscustomobject]@{UserId='S-1-5-18';LogonType='ServiceAccount';RunLevel='Highest'}}
        [pscustomobject]@{ Actions=@($Action); Triggers=@($Trigger); Trigger=@($Trigger); Principal=$Principal; State='Ready'; Settings=$Settings }
    }
    $script:FakeTaskScheduler = [ordered]@{ tasks = @{}; getCalls = @(); registerCalls = @(); unregisterCalls = @(); enableCalls=@(); disableCalls=@(); raceOnSecondGet = $false; replaceBeforeUnregister = $false; postReadbackMismatch = $false; postEnableMismatch=$false; ambiguousStatus=$false }
    function Get-ScheduledTask { [CmdletBinding()] param([string]$TaskName,[string]$TaskPath) $script:FakeTaskScheduler.getCalls += "$TaskPath$TaskName"; if ($script:FakeTaskScheduler.raceOnSecondGet -and $script:FakeTaskScheduler.getCalls.Count -eq 2) { $script:FakeTaskScheduler.tasks["$TaskPath$TaskName"] = New-FakeScheduledTask ([pscustomobject]@{ Execute='powershell.exe'; Arguments='race'; WorkingDirectory='C:\race' }) }; $key="$TaskPath$TaskName"; if($script:FakeTaskScheduler.ambiguousStatus){New-FakeScheduledTask ([pscustomobject]@{Execute='powershell.exe';Arguments='a';WorkingDirectory='C:\a'});New-FakeScheduledTask ([pscustomobject]@{Execute='powershell.exe';Arguments='b';WorkingDirectory='C:\b'});return};if($script:FakeTaskScheduler.tasks.ContainsKey($key)){ $script:FakeTaskScheduler.tasks[$key] } }
    function New-ScheduledTaskAction { [CmdletBinding()] param([string]$Execute,[string]$Argument,[string]$WorkingDirectory) [pscustomobject]@{ Execute=$Execute; Arguments=$Argument; WorkingDirectory=$WorkingDirectory } }
    function New-ScheduledTaskTrigger { [CmdletBinding()] param([datetime]$At,[timespan]$RepetitionInterval,[timespan]$RepetitionDuration,[switch]$Once) [pscustomobject]@{ TriggerType=if($Once){'Once'}else{'Unknown'}; At=$At; StartBoundary=$At.ToString('s',[Globalization.CultureInfo]::InvariantCulture); RepetitionInterval=$RepetitionInterval; Repetition=[pscustomobject]@{Interval=('PT'+[int]$RepetitionInterval.TotalMinutes+'M');Duration=if($PSBoundParameters.ContainsKey('RepetitionDuration')){('PT'+[int]$RepetitionDuration.TotalMinutes+'M')}else{''}}; hasRepetitionDuration=$PSBoundParameters.ContainsKey('RepetitionDuration') } }
    function New-ScheduledTaskSettingsSet { [CmdletBinding()] param([string]$MultipleInstances,[timespan]$ExecutionTimeLimit) [pscustomobject]@{ MultipleInstances=$MultipleInstances; ExecutionTimeLimit=$ExecutionTimeLimit; Enabled=$true } }
    function New-ScheduledTaskPrincipal { [CmdletBinding()] param([string]$UserId,[string]$LogonType,[string]$RunLevel) [pscustomobject]@{UserId=$UserId;LogonType=$LogonType;RunLevel=$RunLevel} }
    function New-ScheduledTask { [CmdletBinding()] param([object]$Action,[object]$Trigger,[object]$Settings,[object]$Principal) [pscustomobject]@{Actions=@($Action);Triggers=@($Trigger);Trigger=@($Trigger);Settings=$Settings;Principal=$Principal} }
    function Register-ScheduledTask { [CmdletBinding()] param([string]$TaskName,[string]$TaskPath,[object]$InputObject,[switch]$Force) $script:FakeTaskScheduler.registerCalls += [pscustomobject]@{ taskPath=$TaskPath; force=[bool]$Force; trigger=$InputObject.Triggers[0]; disabled=(-not [bool]$InputObject.Settings.Enabled); principal=$InputObject.Principal; settings=$InputObject.Settings }; $key="$TaskPath$TaskName"; if($script:FakeTaskScheduler.tasks.ContainsKey($key)){throw 'already-exists'}; $script:FakeTaskScheduler.tasks[$key]=New-FakeScheduledTask $InputObject.Actions[0] ([bool]$InputObject.Settings.Enabled) $InputObject.Triggers[0] $InputObject.Settings $InputObject.Principal; if($script:FakeTaskScheduler.postReadbackMismatch){$script:FakeTaskScheduler.tasks[$key]=New-FakeScheduledTask ([pscustomobject]@{Execute='powershell.exe';Arguments='changed';WorkingDirectory='C:\changed'})} }
    function Enable-ScheduledTask { [CmdletBinding()]param([string]$TaskName,[string]$TaskPath) $script:FakeTaskScheduler.enableCalls+="$TaskPath$TaskName";$script:FakeTaskScheduler.tasks["$TaskPath$TaskName"].Settings.Enabled=$true;if($script:FakeTaskScheduler.postEnableMismatch){$script:FakeTaskScheduler.tasks["$TaskPath$TaskName"]=New-FakeScheduledTask ([pscustomobject]@{Execute='powershell.exe';Arguments='changed';WorkingDirectory='C:\changed'}) $true} }
    function Disable-ScheduledTask { [CmdletBinding()]param([string]$TaskName,[string]$TaskPath) $script:FakeTaskScheduler.disableCalls+="$TaskPath$TaskName";$script:FakeTaskScheduler.tasks["$TaskPath$TaskName"].Settings.Enabled=$false }
    function Unregister-ScheduledTask { [CmdletBinding()] param([string]$TaskName,[string]$TaskPath,[switch]$Confirm) $script:FakeTaskScheduler.unregisterCalls += "$TaskPath$TaskName"; $key="$TaskPath$TaskName"; if($script:FakeTaskScheduler.replaceBeforeUnregister){$script:FakeTaskScheduler.tasks[$key]=New-FakeScheduledTask ([pscustomobject]@{Execute='powershell.exe';Arguments='replacement';WorkingDirectory='C:\replacement'})}; $script:FakeTaskScheduler.tasks.Remove($key) | Out-Null }
    Write-FakeTaskAttestation
    $fakeDefinition = New-AwxAutoIntakeTaskDefinition -PolicyPath $fakePolicyPath; $fakeActionHash = Get-AwxAutoIntakeTaskActionSha256 $fakeDefinition
    $fakeIdentityHash = ''; try { $fakeIdentityHash = Get-AwxAutoIntakeTaskIdentitySha256 -Definition $fakeDefinition } catch { }
    $workingDirectoryVariant = [pscustomobject]@{ execute=$fakeDefinition.execute; arguments=$fakeDefinition.arguments; workingDirectory='C:\different' }
    $invalidActionResult = Invoke-AwxAutoIntakeTaskManager -Action 'Destroy' -PolicyPath $fakePolicyPath
    $forgedCallerResult = Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -DesktopEvidence ([pscustomobject]@{desktopRootProven=$true}) -Confirm:$false
    Test-Contract 'public manager holds unknown action before task mutation' { $invalidActionResult.decision -ceq 'HOLD' -and $invalidActionResult.failureClass -ceq 'invalid-action' } "actual=$($invalidActionResult.decision)/$($invalidActionResult.failureClass)"
    Test-Contract 'action hash changes when working directory changes' { (Get-AwxAutoIntakeTaskActionSha256 $fakeDefinition) -cne (Get-AwxAutoIntakeTaskActionSha256 $workingDirectoryVariant) }
    Test-Contract 'caller desktop evidence is ignored; fixed attestation still stops before enable' { $forgedCallerResult.decision -ceq 'HOLD' -and $forgedCallerResult.failureClass -ceq 'desktop-enable-proof-required' -and $script:FakeTaskScheduler.registerCalls.Count -eq 1 } "actual=$($forgedCallerResult.decision)/$($forgedCallerResult.failureClass);registerCalls=$($script:FakeTaskScheduler.registerCalls.Count)"
    $requiredSecureSystemFilePaths = @(
        [IO.Path]::GetFullPath($script:AwxAutoIntakeDesktopRoot),
        [IO.Path]::GetFullPath((Split-Path -Parent $script:AwxAutoIntakeConsumerPath)),
        [IO.Path]::GetFullPath($script:AwxAutoIntakeConsumerPath),
        [IO.Path]::GetFullPath((Split-Path -Parent $fakePolicyPath)),
        [IO.Path]::GetFullPath($fakePolicyPath),
        [IO.Path]::GetFullPath((Split-Path -Parent $script:AwxAutoIntakeTaskAttestationPath)),
        [IO.Path]::GetFullPath($script:AwxAutoIntakeTaskAttestationPath)
    ) | Select-Object -Unique
    $securePathSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase); @($script:FakeSecureSystemFilePaths) | ForEach-Object { [void]$securePathSet.Add([string]$_) }
    Test-Contract 'production attestation probes consumer policy and attestation replacement boundaries' { @($requiredSecureSystemFilePaths | Where-Object {-not $securePathSet.Contains($_)}).Count -eq 0 } "observedUnique=$($securePathSet.Count);required=$($requiredSecureSystemFilePaths.Count)"
    Test-Contract 'Install repeats production attestation before register and disabled readback' { $script:FakeProductionAttestationCalls -ge 3 } "calls=$($script:FakeProductionAttestationCalls)"
    $script:FakeTaskScheduler.tasks=@{}; $script:FakeTaskScheduler.getCalls=@(); $script:FakeTaskScheduler.registerCalls=@(); $script:FakeTaskScheduler.enableCalls=@(); $installResult = Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false
    $installedIdentityAfterInstall = if($script:FakeTaskScheduler.tasks.ContainsKey('\AwxDesktopPatchDropAutoIntake')){Get-AwxAutoIntakeInstalledTaskIdentitySha256 $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake']}else{''}
    $identityMismatchFields=@()
    $installedShape='absent'
    if($script:FakeTaskScheduler.tasks.ContainsKey('\AwxDesktopPatchDropAutoIntake')){
        $installedTaskForDebug=$script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake']
        $installedShape='actions='+@($installedTaskForDebug.Actions).Count+';triggers='+@($installedTaskForDebug.Triggers).Count+';principal='+($null-ne$installedTaskForDebug.Principal)+';settings='+($null-ne$installedTaskForDebug.Settings)+';repeat='+($null-ne$installedTaskForDebug.Triggers[0].Repetition)+';start='+($null-ne$installedTaskForDebug.Triggers[0].PSObject.Properties['StartBoundary'])+';duration='+($null-ne$installedTaskForDebug.Triggers[0].Repetition.PSObject.Properties['Duration'])+';limit='+($null-ne$installedTaskForDebug.Settings.PSObject.Properties['ExecutionTimeLimit'])+';enabled='+($null-ne$installedTaskForDebug.Settings.PSObject.Properties['Enabled'])
        try{$expectedProjection=Get-AwxAutoIntakeTaskIdentityProjection $fakeDefinition;$actualProjection=ConvertFrom-AwxAutoIntakeInstalledTask $installedTaskForDebug;foreach($property in @($expectedProjection.PSObject.Properties.Name)){if(([string]$expectedProjection.$property) -cne ([string]$actualProjection.$property)){$identityMismatchFields+=$property}}}catch{$identityMismatchFields+=('projection-error:'+ $_.Exception.Message)}
    }
    Test-Contract 'guarded fake install registers exact disabled LocalSystem task and requires separate Desktop enable proof' { $installResult.decision -ceq 'HOLD' -and $installResult.failureClass -ceq 'desktop-enable-proof-required' -and $script:FakeTaskScheduler.registerCalls.Count -eq 1 -and $script:FakeTaskScheduler.registerCalls[0].taskPath -ceq '\' -and $script:FakeTaskScheduler.registerCalls[0].disabled -and -not $script:FakeTaskScheduler.registerCalls[0].force -and $script:FakeTaskScheduler.registerCalls[0].principal.UserId -ceq 'S-1-5-18' -and $script:FakeTaskScheduler.registerCalls[0].principal.LogonType -ceq 'ServiceAccount' -and $script:FakeTaskScheduler.registerCalls[0].principal.RunLevel -ceq 'Highest' -and [int]$script:FakeTaskScheduler.registerCalls[0].trigger.RepetitionInterval.TotalMinutes -eq 5 -and -not $script:FakeTaskScheduler.registerCalls[0].trigger.hasRepetitionDuration -and $script:FakeTaskScheduler.registerCalls[0].settings.MultipleInstances -ceq 'IgnoreNew' -and [int]$script:FakeTaskScheduler.registerCalls[0].settings.ExecutionTimeLimit.TotalMinutes -eq 15 -and $script:FakeTaskScheduler.enableCalls.Count -eq 0 } "actual=$($installResult.decision)/$($installResult.failureClass);registerCalls=$($script:FakeTaskScheduler.registerCalls.Count);enableCalls=$($script:FakeTaskScheduler.enableCalls.Count);expectedIdentity=$fakeIdentityHash;installedIdentity=$installedIdentityAfterInstall;mismatchFields=$($identityMismatchFields -join ',');shape=$installedShape"
    $statusResult = Invoke-AwxAutoIntakeTaskManager -Action Status -PolicyPath $fakePolicyPath -Confirm:$false
    Test-Contract 'guarded fake status reports only safe state and canonical full-task identity hash' { $statusResult.taskPresent -and $fakeIdentityHash -match '^[a-f0-9]{64}$' -and $statusResult.taskIdentitySha256 -ceq $fakeIdentityHash -and @($statusResult.PSObject.Properties.Name | Where-Object {$_ -match '(?i)(argument|path|working|decision|failure|action)' }).Count -eq 0 }
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Triggers[0].TriggerType='Daily'
    $wrongTriggerType = Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false
    Test-Contract 'full-task identity rejects a non-Once installed trigger type' { $wrongTriggerType.decision -ceq 'HOLD' -and $wrongTriggerType.failureClass -ceq 'task-identity-changed' } "actual=$($wrongTriggerType.decision)/$($wrongTriggerType.failureClass)"
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Triggers[0].TriggerType='Once'
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Triggers[0].RepetitionInterval=[timespan]::FromSeconds(320)
    $subMinuteRepetition = Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false
    Test-Contract 'full-task identity rejects a sub-minute repetition mismatch' { $subMinuteRepetition.decision -ceq 'HOLD' -and $subMinuteRepetition.failureClass -ceq 'task-identity-changed' } "actual=$($subMinuteRepetition.decision)/$($subMinuteRepetition.failureClass)"
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Triggers[0].RepetitionInterval=[timespan]::FromMinutes(5)
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Settings.ExecutionTimeLimit=[timespan]::FromSeconds(920)
    $subMinuteExecutionLimit = Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false
    Test-Contract 'full-task identity rejects a sub-minute execution-limit mismatch' { $subMinuteExecutionLimit.decision -ceq 'HOLD' -and $subMinuteExecutionLimit.failureClass -ceq 'task-identity-changed' } "actual=$($subMinuteExecutionLimit.decision)/$($subMinuteExecutionLimit.failureClass)"
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Settings.ExecutionTimeLimit=[timespan]::FromMinutes(15)
    $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake'].Settings.MultipleInstances='Parallel'
    $settingsMismatch = Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false
    Test-Contract 'same action with changed settings is rejected by full-task identity before staging' { $settingsMismatch.decision -ceq 'HOLD' -and $settingsMismatch.failureClass -ceq 'task-identity-changed' } "actual=$($settingsMismatch.decision)/$($settingsMismatch.failureClass)"
    $script:FakeTaskScheduler.raceOnSecondGet=$true; $script:FakeTaskScheduler.tasks=@{}; $script:FakeTaskScheduler.getCalls=@(); $script:FakeTaskScheduler.registerCalls=@(); $raceResult=Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false; $script:FakeTaskScheduler.raceOnSecondGet=$false
    Test-Contract 'install holds on immediate pre-register TaskPath race' { $raceResult.decision -ceq 'HOLD' -and $raceResult.failureClass -ceq 'task-race-detected' -and $script:FakeTaskScheduler.registerCalls.Count -eq 0 }
    $script:FakeTaskScheduler.tasks=@{}; $script:FakeTaskScheduler.getCalls=@(); $script:FakeTaskScheduler.registerCalls=@(); $script:FakeTaskScheduler.postReadbackMismatch=$true; $postResult=Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false; $script:FakeTaskScheduler.postReadbackMismatch=$false
    Test-Contract 'install holds on post-registration action mismatch' { $postResult.decision -ceq 'HOLD' -and $postResult.failureClass -ceq 'task-postreadback-mismatch' }
    $script:FakeTaskScheduler.tasks=@{}; $script:FakeTaskScheduler.getCalls=@(); $script:FakeTaskScheduler.unregisterCalls=@(); $script:FakeTaskScheduler.tasks['\AwxDesktopPatchDropAutoIntake']=New-FakeScheduledTask ([pscustomobject]@{Execute=$fakeDefinition.execute;Arguments=$fakeDefinition.arguments;WorkingDirectory=$fakeDefinition.workingDirectory}); $script:FakeTaskScheduler.raceOnSecondGet=$true; $uninstallRace=Invoke-AwxAutoIntakeTaskManager -Action Uninstall -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false; $script:FakeTaskScheduler.raceOnSecondGet=$false
    Test-Contract 'uninstall holds because atomic compare-and-delete is unavailable' { $uninstallRace.decision -ceq 'HOLD' -and $uninstallRace.failureClass -ceq 'atomic-uninstall-unavailable' -and $script:FakeTaskScheduler.unregisterCalls.Count -eq 0 }
    Write-FakeTaskAttestation; $script:FakeReparseBlock=$true; $reparseAttestation=Invoke-AwxAutoIntakeTaskManager -Action Status -PolicyPath $fakePolicyPath -Confirm:$false; $script:FakeReparseBlock=$false
    Test-Contract 'reparse component in attestation trust path holds before scheduler access' { $reparseAttestation.decision -ceq 'HOLD' -and $reparseAttestation.failureClass -ceq 'desktop-attestation-unproven' }
    Write-FakeTaskAttestation; $script:FakeHandleReadCount=0; $script:FakeHandleDrift=$true; $script:FakeProductionAttestationBypass=$true; $driftResult=Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false; $script:FakeHandleDrift=$false; $script:FakeProductionAttestationBypass=$false
    Test-Contract 'consumer hash drift detected while guarded Install holds handles' { $driftResult.decision -ceq 'HOLD' -and $driftResult.failureClass -ceq 'task-input-hash-drift' }
    Write-FakeTaskAttestation; $script:FakeAnchorPresent=$false; $missingAnchor=Invoke-AwxAutoIntakeTaskManager -Action Status -PolicyPath $fakePolicyPath -Confirm:$false; $script:FakeAnchorPresent=$true
    Test-Contract 'missing fixed HKLM provisioning anchor holds before scheduler access' { $missingAnchor.decision -ceq 'HOLD' -and $missingAnchor.failureClass -ceq 'desktop-attestation-unproven' }
    $script:FakeTaskScheduler.tasks=@{}; $script:FakeTaskScheduler.getCalls=@(); $script:FakeTaskScheduler.registerCalls=@(); $script:FakeTaskScheduler.enableCalls=@(); Write-FakeTaskAttestation; $postEnable=Invoke-AwxAutoIntakeTaskManager -Action Install -PolicyPath $fakePolicyPath -ConfirmActionSha256 $fakeActionHash -Confirm:$false
    Test-Contract 'disabled postread holds and never enables a task' { $postEnable.decision -ceq 'HOLD' -and $postEnable.failureClass -ceq 'desktop-enable-proof-required' -and $script:FakeTaskScheduler.enableCalls.Count -eq 0 }
    $script:FakeTaskScheduler.ambiguousStatus=$true; Write-FakeTaskAttestation; $ambiguousStatus=Invoke-AwxAutoIntakeTaskManager -Action Status -PolicyPath $fakePolicyPath -Confirm:$false; $script:FakeTaskScheduler.ambiguousStatus=$false
    Test-Contract 'status holds when exact task query returns multiple rows' { $ambiguousStatus.decision -ceq 'HOLD' -and $ambiguousStatus.failureClass -ceq 'task-state-ambiguous' }
    Write-FakeTaskAttestation -Malformed; $malformedAttestation=Invoke-AwxAutoIntakeTaskManager -Action Status -PolicyPath $fakePolicyPath -Confirm:$false
    Test-Contract 'malformed fixed attestation holds before scheduler access' { $malformedAttestation.decision -ceq 'HOLD' -and $malformedAttestation.failureClass -ceq 'desktop-attestation-unproven' }
    Remove-Item Function:\Get-ScheduledTask,Function:\New-ScheduledTaskAction,Function:\New-ScheduledTaskTrigger,Function:\New-ScheduledTaskSettingsSet,Function:\New-ScheduledTaskPrincipal,Function:\New-ScheduledTask,Function:\Register-ScheduledTask,Function:\Enable-ScheduledTask,Function:\Disable-ScheduledTask,Function:\Unregister-ScheduledTask,Function:\Get-AwxAutoIntakeTaskSystemSid,Function:\Get-AwxAutoIntakeTaskMachineBindingSha256,Function:\Get-AwxAutoIntakeTaskProvisioningAnchor,Function:\Test-AwxAutoIntakeTaskReparseFreePath,Function:\Test-AwxAutoIntakeTaskSecureSystemFile,Function:\Get-AwxAutoIntakeTaskHandleBytes,Function:\Test-AwxAutoIntakeTaskProductionAttestation -ErrorAction SilentlyContinue
    $realTaskAfterFakeScheduler = Get-RealAutoIntakeTaskSnapshot
    Test-Contract 'fake scheduler contracts leave real matching task count and state unchanged' { $realTaskBeforeFakeScheduler.count -eq $realTaskAfterFakeScheduler.count -and (@($realTaskBeforeFakeScheduler.states) -join '|') -ceq (@($realTaskAfterFakeScheduler.states) -join '|') } "countBefore=$($realTaskBeforeFakeScheduler.count);countAfter=$($realTaskAfterFakeScheduler.count)"
    Test-Contract 'all tracked native fixture processes are absent at suite end' { @($script:NativeFixtureProcesses | Where-Object { $null -ne (Get-Process -Id $_ -ErrorAction SilentlyContinue) }).Count -eq 0 }
} finally {
    if (Test-Path -LiteralPath $script:TempRoot) { Remove-Item -LiteralPath $script:TempRoot -Recurse -Force }
}

Write-Host "passed=$script:Passed failed=$script:Failed"
if ($script:Failed -gt 0) { exit 1 }

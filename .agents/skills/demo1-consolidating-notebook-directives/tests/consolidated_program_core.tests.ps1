[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\scripts\consolidated_program_core.psm1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-core-test-' + [guid]::NewGuid().ToString('N'))
$script:passCount = 0
$script:redBId = 'red:sha256:fe1d8ff5be2e5cf1c7a398bf738ba32ce7872c63b2245da8a25f9a6713b2d78b'
$script:greenBId = 'green:sha256:f5e6287581fa29f12fee2010358509cf3390b258e0f56b6fffca33b2c78f2b0c'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
    $script:passCount++
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -cne $Expected) { throw "ASSERTION FAILED: $Message (actual=$Actual expected=$Expected)" }
    $script:passCount++
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Reason, [string]$Message)
    try { & $Action; throw "ASSERTION FAILED: $Message (no exception)" }
    catch {
        if ([string]$_.Exception.Message -cne $Reason) {
            throw "ASSERTION FAILED: $Message (actual=$($_.Exception.Message) expected=$Reason)"
        }
    }
    $script:passCount++
}

function Copy-Value {
    param($Value)
    return ($Value | ConvertTo-Json -Depth 40 -Compress | ConvertFrom-Json)
}

function Get-BytesHash {
    param([byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-FileEvidence {
    param([string]$Path)
    $bytes = [IO.File]::ReadAllBytes($Path)
    return [pscustomobject]@{ sha256 = Get-BytesHash $bytes; bytes = $bytes.Length }
}

function New-State {
    $canonicalHash = 'A' * 64
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.program.v1'
        canonicalMarkdownSha256 = $canonicalHash
        desktopFinalProof = 'evidence_needed'
        runtimeLineageVerdict = 'HOLD'
        workUnits = @(
            [pscustomobject][ordered]@{
                workUnitId = 'WU-A'; status = 'no_patch_needed'; dependencies = @(); required = $true
                targetFiles = @('main/java/A.java'); redCommands = @(); greenCommands = @('characterize-a')
                record = [pscustomobject]@{ desktopFinalProof = 'verified'; runId = 'prior-run'; outcome = 'NoPatchNeeded'; evidenceSha256 = ('1' * 64) }
            },
            [pscustomobject][ordered]@{
                workUnitId = 'WU-B'; status = 'pending'; dependencies = @('WU-A'); required = $true
                targetFiles = @('main/java/B.java', 'main/resources/new.yml'); redCommands = @('red-b'); greenCommands = @('green-b')
            },
            [pscustomobject][ordered]@{
                workUnitId = 'WU-C'; status = 'pending'; dependencies = @('WU-A'); required = $false
                targetFiles = @('main/java/C.java'); redCommands = @('red-c'); greenCommands = @('green-c')
            }
        )
    }
}

function New-TargetRows {
    param([string]$ExistingHash = ('B' * 64), [int]$ExistingBytes = 12)
    return @(
        [pscustomobject][ordered]@{
            path = 'main/java/B.java'; exists = $true; bytes = $ExistingBytes; sha256 = $ExistingHash
            resolvedPathContained = $true; ancestorNonReparse = $true; reparseTraversal = $false; leafKind = 'leaf'
        },
        [pscustomobject][ordered]@{
            path = 'main/resources/new.yml'; exists = $false; bytes = 0; sha256 = $null
            resolvedPathContained = $true; ancestorNonReparse = $true; reparseTraversal = $false; leafKind = 'missing'
        }
    )
}

function New-AdapterFixture {
    param($InitialState)
    $fixtureRoot = Join-Path $testRoot ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
    $context = [pscustomobject][ordered]@{
        state = Copy-Value $InitialState
        stateHash = '9' * 64
        calls = New-Object 'Collections.Generic.List[string]'
        snapshots = New-Object 'Collections.Generic.List[object]'
        targetSnapshot = New-TargetRows
        leaseAbsent = $false
        releaseThrows = $false
        acquireThrows = $false
        captureThrows = $false
        evidenceContained = $true
        evidenceNonReparse = $true
        artifactContained = $true
        artifactNonReparse = $true
        absenceOverride = $null
        snapshotEnvelopeExtra = $false
        casFailPhase = ''
        rivalWinsRunning = $false
        rivalWinsRecoveryClaim = $false
        leaseStatusAfterCapture = ''
        acquireOverride = $null
        currentLeaseEnvelope = $null
        releaseRemovesBeforeThrow = $true
        root = $fixtureRoot
    }

    $cas = {
        param([string]$ExpectedHash, $NextState, [string]$Phase)
        $context.calls.Add("cas:$Phase")
        if ($ExpectedHash -cne $context.stateHash) { throw 'state-hash-changed' }
        if ($Phase -ceq 'running' -and $context.rivalWinsRunning) {
            $winner = Copy-Value $NextState
            $context.state = $winner
            $context.snapshots.Add((Copy-Value $winner))
            $context.stateHash = Get-BytesHash ([Text.Encoding]::UTF8.GetBytes(($winner | ConvertTo-Json -Depth 40 -Compress)))
            throw 'state-hash-changed'
        }
        if ($Phase -ceq 'begin-recovery-required' -and $context.rivalWinsRecoveryClaim) {
            $winner = Copy-Value $NextState
            $context.state = $winner
            $context.snapshots.Add((Copy-Value $winner))
            $context.stateHash = Get-BytesHash ([Text.Encoding]::UTF8.GetBytes(($winner | ConvertTo-Json -Depth 40 -Compress)))
            throw 'state-hash-changed'
        }
        if ($context.casFailPhase -ceq $Phase) { throw 'state-hash-changed' }
        $copy = Copy-Value $NextState
        $context.state = $copy
        $context.snapshots.Add((Copy-Value $copy))
        $context.stateHash = Get-BytesHash ([Text.Encoding]::UTF8.GetBytes(($copy | ConvertTo-Json -Depth 40 -Compress)))
        return $context.stateHash
    }.GetNewClosure()
    $acquire = {
        param($Request)
        $context.calls.Add('acquire')
        if ($context.acquireThrows) { throw 'simulated-acquire-failure' }
        $context.leaseAbsent = $false
        if ($null -ne $context.acquireOverride) { $context.currentLeaseEnvelope = $context.acquireOverride; return $context.acquireOverride }
        $envelope = [pscustomobject]@{
            status = 'active'
            lease = [pscustomobject][ordered]@{
                schemaVersion = 'awx.source_edit_session.lease.v1'; generatedAt = '2099-08-07T00:00:00.0000000+00:00'
                startedAtUtc = '2099-08-07T00:00:00.0000000+00:00'; topic = $Request.topic; role = 'desktop'
                ownerId = $Request.ownerId; root = 'C:\AbandonWare\demo-1\demo-1\src'; expiresAtUtc = '2099-08-07T03:00:00.0000000+00:00'
                expiresAt = '2099-08-07T03:00:00.0000000+00:00'; mutationAllowed = $true
            }
        }
        $context.currentLeaseEnvelope = $envelope
        return $envelope
    }.GetNewClosure()
    $readSession = {
        param($Request)
        $context.calls.Add('read-session')
        if ($context.leaseAbsent) { return [pscustomobject]@{ status = 'absent'; lease = $null } }
        return $context.currentLeaseEnvelope
    }.GetNewClosure()
    $capture = {
        param($Request)
        $context.calls.Add('capture-targets')
        if ($context.captureThrows) { throw 'simulated-capture-failure' }
        $envelope = [pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.target-snapshot.v1'
            canonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'
            rows = @(Copy-Value $context.targetSnapshot)
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$context.leaseStatusAfterCapture)) {
            $context.currentLeaseEnvelope.status = [string]$context.leaseStatusAfterCapture
        }
        if ($context.snapshotEnvelopeExtra) { $envelope | Add-Member unexpected 'field' }
        return $envelope
    }.GetNewClosure()
    $release = {
        param($Request)
        $context.calls.Add('release')
        if (-not $context.releaseThrows -or $context.releaseRemovesBeforeThrow) { $context.leaseAbsent = $true }
        if ($context.releaseThrows) { throw 'simulated-release-crash' }
        return [pscustomobject]@{ released = $true }
    }.GetNewClosure()
    $absent = {
        param($Request)
        $context.calls.Add('lease-absent')
        if ($null -ne $context.absenceOverride) { return $context.absenceOverride }
        return [pscustomobject][ordered]@{ isAbsent = [bool]$context.leaseAbsent; topic = [string]$Request.topic; ownerId = [string]$Request.ownerId; root = 'C:\AbandonWare\demo-1\demo-1\src' }
    }.GetNewClosure()
    $readEvidence = {
        param($Request)
        $context.calls.Add('read-evidence')
        $full = Join-Path $context.root ([string]$Request.path).Replace('/', '\')
        $file = Get-FileEvidence $full
        return [pscustomobject]@{ value = (Get-Content -LiteralPath $full -Raw | ConvertFrom-Json); sha256 = $file.sha256; bytes = $file.bytes; containedRepositoryLeaf = [bool]$context.evidenceContained; nonReparseLeaf = [bool]$context.evidenceNonReparse }
    }.GetNewClosure()
    $readArtifact = {
        param($Request)
        $context.calls.Add(('read-artifact:' + [string]$Request.path))
        $full = Join-Path $context.root ([string]$Request.path).Replace('/', '\')
        $file = Get-FileEvidence $full
        return [pscustomobject]@{ sha256 = $file.sha256; bytes = $file.bytes; containedRepositoryLeaf = [bool]$context.artifactContained; nonReparseLeaf = [bool]$context.artifactNonReparse }
    }.GetNewClosure()

    $adapters = [pscustomobject]@{
        CompareAndSwapState = $cas; AcquireSession = $acquire; CaptureTargets = $capture
        ReleaseSession = $release; ReadSession = $readSession; TestLeaseAbsent = $absent; ReadEvidence = $readEvidence; ReadArtifact = $readArtifact
    }
    return [pscustomobject]@{ context = $context; adapters = $adapters }
}

function Write-TestFile {
    param($Fixture, [string]$RelativePath, [string]$Text)
    $full = Join-Path $Fixture.context.root $RelativePath.Replace('/', '\')
    $parent = Split-Path $full -Parent
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [IO.File]::WriteAllText($full, $Text, [Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{ path = $RelativePath; fullPath = $full; file = Get-FileEvidence $full }
}

function New-Evidence {
    param($Fixture, $State, [string]$Outcome, $Postimages, [string]$DesktopProof = 'verified')
    $redLog = Write-TestFile $Fixture 'verification/red.log' 'expected-red'
    $greenLog = Write-TestFile $Fixture 'verification/green.log' 'expected-green'
    $red = if ($Outcome -ceq 'Green') {
        [pscustomobject][ordered]@{ commandId = $script:redBId; exitCode = 3; logPath = $redLog.path; logSha256 = $redLog.file.sha256; logBytes = $redLog.file.bytes }
    } else { $null }
    $green = if ($Outcome -cin @('Green', 'NoPatchNeeded')) {
        @([pscustomobject][ordered]@{ commandId = $script:greenBId; exitCode = 0; logPath = $greenLog.path; logSha256 = $greenLog.file.sha256; logBytes = $greenLog.file.bytes; characterization = ($Outcome -ceq 'NoPatchNeeded') })
    } else { @() }
    $rollbackStatus = if ($Outcome -cin @('Hold', 'Failed')) { 'verified' } else { 'not_required' }
    $rollbackTargets = if ($rollbackStatus -ceq 'verified') { @(Copy-Value $Postimages) } else { @() }
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.work-unit-evidence.v1'; owner = 'desktop'; runId = 'run-b'; workUnitId = 'WU-B'
        canonicalMarkdownSha256 = $State.canonicalMarkdownSha256; outcome = $Outcome
        targetPreimages = Copy-Value $State.workUnits[1].execution.targetPreimages
        targetPostimages = Copy-Value $Postimages
        redCommand = $red; greenCommands = @($green)
        secretScan = [pscustomobject][ordered]@{ mode = 'count-only'; hitCount = 0 }
        rollback = [pscustomobject][ordered]@{ status = $rollbackStatus; actualTargets = @($rollbackTargets) }
        desktopFinalProof = $DesktopProof
    }
}

function Write-EvidenceFixture {
    param($Fixture, $Evidence, [string]$RelativePath = 'verification/evidence.json')
    $null = Write-TestFile $Fixture $RelativePath ($Evidence | ConvertTo-Json -Depth 20 -Compress)
    return $RelativePath
}

function Start-TestUnit {
    param($Fixture)
    $result = Invoke-ConsolidatedProgramBeginCore -State $Fixture.context.state -StateSha256 $Fixture.context.stateHash `
        -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $Fixture.adapters
    Assert-Equal $result.reason 'work-unit-begun' 'Begin helper reaches running state'
    return $Fixture.context.state
}

function New-PendingBeginTransitionState {
    param([ValidateSet('begin-intent','begin-recovery-required')][string]$Phase)
    $state = New-State
    $topic = 'consolidated-' + ('a' * 12) + '-WU-B'
    $transition = [ordered]@{ phase = $Phase; runId = 'run-b'; owner = 'desktop'; leaseTopic = $topic }
    if ($Phase -ceq 'begin-intent') { $transition['canonicalMarkdownSha256'] = 'A' * 64 }
    $state.workUnits[1] | Add-Member transition ([pscustomobject]$transition)
    return $state
}

function Set-TestLease {
    param($Fixture, [ValidateSet('active','expiring','expired')][string]$Status = 'active')
    $acquire = $Fixture.adapters.AcquireSession
    $null = & $acquire ([pscustomobject]@{ topic = ('consolidated-' + ('a' * 12) + '-WU-B'); ownerId = 'run-b' })
    $Fixture.context.currentLeaseEnvelope.status = $Status
    $Fixture.context.calls.Clear()
}

try {
    Import-Module -Name $modulePath -Force

    $state = New-State
    $fixture = New-AdapterFixture $state
    $result = Invoke-ConsolidatedProgramBeginCore -State $fixture.context.state -StateSha256 $fixture.context.stateHash `
        -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $fixture.adapters
    Assert-Equal $result.workUnitId 'WU-B' 'omitted ID chooses first runnable unit'
    Assert-Equal $result.leaseTopic ('consolidated-' + ('a' * 12) + '-WU-B') 'topic is deterministic and bounded'
    Assert-Equal ($fixture.context.calls -join ',') 'cas:begin-intent,acquire,capture-targets,read-session,cas:running' 'Begin orders intent, ownership, freeze, final ownership proof, and running CAS'
    Assert-Equal $fixture.context.snapshots[0].workUnits[1].status 'pending' 'begin-intent keeps unit pending'
    Assert-Equal $fixture.context.snapshots[0].workUnits[1].transition.phase 'begin-intent' 'begin-intent is durable before acquisition'
    Assert-Equal $fixture.context.state.workUnits[1].status 'running' 'Begin persists running status'
    Assert-True (-not $fixture.context.state.workUnits[1].execution.targetPreimages[1].exists) 'missing target has an explicit absent preimage'
    Assert-True ($fixture.context.state.workUnits[1].execution.targetPreimages[1].resolvedPathContained -and $fixture.context.state.workUnits[1].execution.targetPreimages[1].ancestorNonReparse) 'missing target retains contained non-reparse ancestor proof'

    foreach ($snapshotCase in @(
        @{ name = 'containment false'; mutate = { param($f) $f.context.targetSnapshot[0].resolvedPathContained = $false } },
        @{ name = 'reparse traversal'; mutate = { param($f) $f.context.targetSnapshot[0].ancestorNonReparse = $false; $f.context.targetSnapshot[0].reparseTraversal = $true } },
        @{ name = 'existing nonleaf'; mutate = { param($f) $f.context.targetSnapshot[0].leafKind = 'directory' } },
        @{ name = 'unsafe missing ancestor'; mutate = { param($f) $f.context.targetSnapshot[1].ancestorNonReparse = $false; $f.context.targetSnapshot[1].reparseTraversal = $true } }
    )) {
        $unsafeSnapshot = New-AdapterFixture (New-State); & $snapshotCase.mutate $unsafeSnapshot
        Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $unsafeSnapshot.context.state -StateSha256 $unsafeSnapshot.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $unsafeSnapshot.adapters } 'invalid-target-snapshot' "Begin rejects target snapshot $($snapshotCase.name) despite matching hashes and bytes"
        Assert-True (($unsafeSnapshot.context.calls -join ',') -match 'capture-targets,cas:begin-recovery-required,release,lease-absent,cas:begin-cleanup-complete$') "Begin cleans up ownership for snapshot $($snapshotCase.name)"
    }
    $unknownSnapshotEnvelope = New-AdapterFixture (New-State); $unknownSnapshotEnvelope.context.snapshotEnvelopeExtra = $true
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $unknownSnapshotEnvelope.context.state -StateSha256 $unknownSnapshotEnvelope.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $unknownSnapshotEnvelope.adapters } 'invalid-target-snapshot' 'Begin rejects unknown target snapshot envelope fields'
    $beforeReplayCalls = $fixture.context.calls.Count
    $replayBegin = Invoke-ConsolidatedProgramBeginCore -State $fixture.context.state -StateSha256 $fixture.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $fixture.adapters
    Assert-Equal $replayBegin.reason 'already-running' 'exact Begin replay is idempotent'
    Assert-Equal $fixture.context.calls.Count ($beforeReplayCalls + 1) 'exact Begin replay performs one read-only lease proof'
    Assert-Equal $fixture.context.calls[$fixture.context.calls.Count - 1] 'read-session' 'exact Begin replay reads the deterministic lease'

    $intentAbsent = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent')
    $intentAbsent.context.leaseAbsent = $true
    $intentAbsentResult = Invoke-ConsolidatedProgramBeginCore -State $intentAbsent.context.state -StateSha256 $intentAbsent.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $intentAbsent.adapters
    Assert-Equal $intentAbsentResult.status 'running' 'persisted begin-intent resumes after an absent lease'
    Assert-Equal ($intentAbsent.context.calls -join ',') 'lease-absent,acquire,capture-targets,read-session,cas:running' 'begin-intent absent resume acquires once and revalidates without another intent CAS'

    $intentActive = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent'); Set-TestLease $intentActive active
    $intentActiveResult = Invoke-ConsolidatedProgramBeginCore -State $intentActive.context.state -StateSha256 $intentActive.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $intentActive.adapters
    Assert-Equal $intentActiveResult.status 'running' 'persisted begin-intent reuses its exact active lease'
    Assert-Equal ($intentActive.context.calls -join ',') 'lease-absent,read-session,capture-targets,read-session,cas:running' 'begin-intent active resume does not reacquire or release and revalidates after capture'

    $intentExpired = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent'); Set-TestLease $intentExpired expired
    $intentExpiredResult = Invoke-ConsolidatedProgramBeginCore -State $intentExpired.context.state -StateSha256 $intentExpired.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $intentExpired.adapters
    Assert-Equal $intentExpiredResult.status 'running' 'persisted begin-intent replaces an exact expired lease'
    Assert-Equal ($intentExpired.context.calls -join ',') 'lease-absent,read-session,cas:begin-recovery-required,release,lease-absent,cas:begin-intent,acquire,capture-targets,read-session,cas:running' 'begin-intent expired resume persists recovery ownership before releasing, then reacquires and revalidates exactly once'

    $intentExpiring = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent'); Set-TestLease $intentExpiring expiring
    $intentExpiringResult = Invoke-ConsolidatedProgramBeginCore -State $intentExpiring.context.state -StateSha256 $intentExpiring.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $intentExpiring.adapters
    Assert-Equal $intentExpiringResult.status 'running' 'persisted begin-intent replaces an exact expiring lease'
    Assert-Equal ($intentExpiring.context.calls -join ',') 'lease-absent,read-session,cas:begin-recovery-required,release,lease-absent,cas:begin-intent,acquire,capture-targets,read-session,cas:running' 'begin-intent expiring resume persists recovery ownership before releasing, then reacquires and revalidates exactly once'

    $recoveryAbsent = New-AdapterFixture (New-PendingBeginTransitionState 'begin-recovery-required'); $recoveryAbsent.context.leaseAbsent = $true
    $recoveryAbsentResult = Invoke-ConsolidatedProgramBeginCore -State $recoveryAbsent.context.state -StateSha256 $recoveryAbsent.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $recoveryAbsent.adapters
    Assert-Equal $recoveryAbsentResult.status 'running' 'begin recovery resumes after proven absence'
    Assert-Equal ($recoveryAbsent.context.calls -join ',') 'lease-absent,cas:begin-intent,acquire,capture-targets,read-session,cas:running' 'recovery absence persists intent before acquisition and revalidates after capture'

    foreach ($leaseStatus in @('active','expiring','expired')) {
        $recoveryLease = New-AdapterFixture (New-PendingBeginTransitionState 'begin-recovery-required'); Set-TestLease $recoveryLease $leaseStatus
        $recoveryLeaseResult = Invoke-ConsolidatedProgramBeginCore -State $recoveryLease.context.state -StateSha256 $recoveryLease.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $recoveryLease.adapters
        Assert-Equal $recoveryLeaseResult.status 'running' "recovery replaces an exact $leaseStatus lease"
        Assert-Equal ($recoveryLease.context.calls -join ',') 'lease-absent,read-session,cas:begin-recovery-required,release,lease-absent,cas:begin-intent,acquire,capture-targets,read-session,cas:running' "recovery $leaseStatus lease is serialized by the retained state lock before one release and revalidation"
    }

    $mismatchedIntent = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent'); Set-TestLease $mismatchedIntent active
    $mismatchedIntent.context.currentLeaseEnvelope.lease.ownerId = 'other'
    $mismatchedIntentHash = $mismatchedIntent.context.stateHash
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $mismatchedIntent.context.state -StateSha256 $mismatchedIntent.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $mismatchedIntent.adapters } 'lease-owner-mismatch' 'persisted intent rejects a mismatched lease identity'
    Assert-Equal ($mismatchedIntent.context.calls -join ',') 'lease-absent,read-session' 'mismatched intent does not release, acquire, or CAS'
    Assert-Equal $mismatchedIntent.context.stateHash $mismatchedIntentHash 'mismatched intent leaves durable state unchanged'

    $recoveryCrash = New-AdapterFixture (New-PendingBeginTransitionState 'begin-recovery-required'); Set-TestLease $recoveryCrash active
    $recoveryCrash.context.casFailPhase = 'begin-intent'
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $recoveryCrash.context.state -StateSha256 $recoveryCrash.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $recoveryCrash.adapters } 'state-hash-changed' 'recovery intent CAS crash is retryable after exact release'
    Assert-Equal (@($recoveryCrash.context.calls | Where-Object { $_ -ceq 'release' }).Count) 1 'recovery CAS crash releases exactly once'
    Assert-Equal $recoveryCrash.context.state.workUnits[1].transition.phase 'begin-recovery-required' 'failed recovery intent CAS preserves the recovery state'
    Assert-True $recoveryCrash.context.leaseAbsent 'failed recovery intent CAS leaves the exact lease absent'
    $recoveryCrash.context.casFailPhase = ''; $recoveryCrash.context.calls.Clear()
    $recoveryCrashResult = Invoke-ConsolidatedProgramBeginCore -State $recoveryCrash.context.state -StateSha256 $recoveryCrash.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $recoveryCrash.adapters
    Assert-Equal $recoveryCrashResult.status 'running' 'recovery resumes after a prior intent CAS crash'
    Assert-Equal ($recoveryCrash.context.calls -join ',') 'lease-absent,cas:begin-intent,acquire,capture-targets,read-session,cas:running' 'recovery retry avoids a second release and revalidates ownership'

    foreach ($contendedPhase in @('begin-intent','begin-recovery-required')) {
        $contendedCleanup = New-AdapterFixture (New-PendingBeginTransitionState $contendedPhase); Set-TestLease $contendedCleanup expired
        $contendedCleanup.context.rivalWinsRecoveryClaim = $true
        Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $contendedCleanup.context.state -StateSha256 $contendedCleanup.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $contendedCleanup.adapters } 'state-hash-changed' "losing $contendedPhase cleanup claimant reports the winning CAS"
        Assert-True ($contendedCleanup.context.calls -notcontains 'release') "losing $contendedPhase cleanup claimant never releases the winner lease"
        Assert-True (-not $contendedCleanup.context.leaseAbsent) "losing $contendedPhase cleanup claimant leaves the winner lease present"
        Assert-Equal $contendedCleanup.context.state.workUnits[1].transition.phase 'begin-recovery-required' "winning $contendedPhase recovery claim remains durable"
    }

    $concurrentIntent = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent'); Set-TestLease $concurrentIntent active
    $concurrentIntent.context.rivalWinsRunning = $true
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $concurrentIntent.context.state -StateSha256 $concurrentIntent.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $concurrentIntent.adapters } 'state-hash-changed' 'losing same-intent retry reports the winner CAS without cleanup mutation'
    Assert-True ($concurrentIntent.context.calls -notcontains 'release') 'losing same-intent retry never releases the winner lease'
    Assert-True (-not $concurrentIntent.context.leaseAbsent) 'losing same-intent retry leaves the winner lease active'
    Assert-Equal $concurrentIntent.context.state.workUnits[1].status 'running' 'losing same-intent retry preserves the winner running state'

    $captureExpiry = New-AdapterFixture (New-PendingBeginTransitionState 'begin-intent'); Set-TestLease $captureExpiry active
    $captureExpiry.context.leaseStatusAfterCapture = 'expiring'
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $captureExpiry.context.state -StateSha256 $captureExpiry.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $captureExpiry.adapters } 'lease-not-active' 'lease that becomes expiring during capture cannot reach running'
    Assert-Equal ($captureExpiry.context.calls -join ',') 'lease-absent,read-session,capture-targets,read-session,cas:begin-recovery-required,release,lease-absent,cas:begin-cleanup-complete' 'post-capture expiry is cleanup-claimed and released without a running CAS'
    Assert-Equal $captureExpiry.context.state.workUnits[1].status 'pending' 'post-capture expiry restores the unit to pending'

    $absentReplay = New-AdapterFixture (New-State); $null = Start-TestUnit $absentReplay; $absentReplay.context.leaseAbsent = $true; $absentReplay.context.calls.Clear()
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $absentReplay.context.state -StateSha256 $absentReplay.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $absentReplay.adapters } 'lease-not-active' 'Begin replay fails closed when the exact lease is absent'
    Assert-Equal ($absentReplay.context.calls -join ',') 'read-session' 'absent replay performs no acquisition or state mutation'

    $expiringReplay = New-AdapterFixture (New-State); $null = Start-TestUnit $expiringReplay; $expiringReplay.context.currentLeaseEnvelope.status = 'expiring'; $expiringReplay.context.calls.Clear()
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $expiringReplay.context.state -StateSha256 $expiringReplay.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $expiringReplay.adapters } 'lease-not-active' 'running replay rejects an expiring lease without reacquisition'
    Assert-Equal ($expiringReplay.context.calls -join ',') 'read-session' 'expiring running replay performs no acquisition or state mutation'

    $mismatchedReplay = New-AdapterFixture (New-State); $null = Start-TestUnit $mismatchedReplay; $mismatchedReplay.context.currentLeaseEnvelope.lease.ownerId = 'other'; $mismatchedReplay.context.calls.Clear()
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $mismatchedReplay.context.state -StateSha256 $mismatchedReplay.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $mismatchedReplay.adapters } 'lease-owner-mismatch' 'Begin replay rejects a mismatched active lease'

    $corruptReplayState = Copy-Value $fixture.context.state
    $corruptReplayState.workUnits[2] | Add-Member transition ([pscustomobject]@{ phase = 'begin-intent'; runId = 'other' })
    $corruptReplay = New-AdapterFixture $corruptReplayState
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $corruptReplay.context.state -StateSha256 $corruptReplay.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -WorkUnitId 'WU-B' -Adapters $corruptReplay.adapters } 'another-unit-active' 'idempotent replay cannot hide another active transition'

    $wrongFirst = New-AdapterFixture (New-State)
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $wrongFirst.context.state -StateSha256 $wrongFirst.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-c' -WorkUnitId 'WU-C' -Adapters $wrongFirst.adapters } 'work-unit-not-first-runnable' 'supplied ID must equal deterministic Next selection'
    Assert-Equal $wrongFirst.context.calls.Count 0 'wrong runnable ID has no callback effects'

    foreach ($badRunId in @('', '-bad', ('x' * 65), 'bad/id')) {
        $bad = New-AdapterFixture (New-State)
        Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $bad.context.state -StateSha256 $bad.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId $badRunId -Adapters $bad.adapters } 'invalid-run-id' "invalid RunId '$badRunId' is rejected"
    }
    $maxRun = New-AdapterFixture (New-State)
    $maxRunResult = Invoke-ConsolidatedProgramBeginCore -State $maxRun.context.state -StateSha256 $maxRun.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId ('r' * 64) -Adapters $maxRun.adapters
    Assert-Equal $maxRunResult.status 'running' '64-character RunId is accepted'

    $longTopicState = New-State; $longTopicState.workUnits[1].workUnitId = ('W' * 71)
    $longTopic = New-AdapterFixture $longTopicState
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $longTopic.context.state -StateSha256 $longTopic.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $longTopic.adapters } 'lease-topic-too-long' 'deterministic topic overflow fails instead of truncating or colliding'

    foreach ($case in @(
        @{ path = 'C:\outside.txt'; reason = 'invalid-target-file' },
        @{ path = 'main/*.java'; reason = 'invalid-target-file' },
        @{ path = 'main/../secret'; reason = 'invalid-target-file' },
        @{ path = 'main/[x].java'; reason = 'invalid-target-file' }
    )) {
        $badState = New-State; $badState.workUnits[1].targetFiles = @($case.path)
        $bad = New-AdapterFixture $badState
        Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $bad.context.state -StateSha256 $bad.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $bad.adapters } $case.reason "unsafe target '$($case.path)' is rejected"
    }
    $duplicateState = New-State; $duplicateState.workUnits[1].targetFiles = @('main/A.java', 'MAIN/a.java')
    $duplicate = New-AdapterFixture $duplicateState
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $duplicate.context.state -StateSha256 $duplicate.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $duplicate.adapters } 'duplicate-target-file' 'case-insensitive duplicate targets fail closed'

    $busyState = New-State; $busyState.workUnits[2].status = 'running'
    $busy = New-AdapterFixture $busyState
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $busy.context.state -StateSha256 $busy.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $busy.adapters } 'another-unit-active' 'another running unit blocks Begin'
    $transitionState = New-State; $transitionState.workUnits[2] | Add-Member transition ([pscustomobject]@{ phase = 'record-awaiting-release' })
    $transition = New-AdapterFixture $transitionState
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $transition.context.state -StateSha256 $transition.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $transition.adapters } 'another-unit-active' 'another transition unit blocks Begin'

    foreach ($leaseCase in @(
        @{ name = 'schema'; mutate = { param($x) $x.lease.schemaVersion = 'wrong' }; reason = 'invalid-lease-schema' },
        @{ name = 'active'; mutate = { param($x) $x.status = 'expired' }; reason = 'lease-not-active' },
        @{ name = 'owner'; mutate = { param($x) $x.lease.ownerId = 'RUN-B' }; reason = 'lease-owner-mismatch' },
        @{ name = 'topic'; mutate = { param($x) $x.lease.topic = 'other' }; reason = 'lease-topic-mismatch' },
        @{ name = 'role'; mutate = { param($x) $x.lease.role = 'notebook' }; reason = 'lease-role-mismatch' },
        @{ name = 'root'; mutate = { param($x) $x.lease.root = 'Y:\' }; reason = 'lease-root-mismatch' },
        @{ name = 'mutation'; mutate = { param($x) $x.lease.mutationAllowed = $false }; reason = 'lease-mutation-not-allowed' }
    )) {
        $bad = New-AdapterFixture (New-State)
        $request = [pscustomobject]@{ topic = ('consolidated-' + ('a' * 12) + '-WU-B'); ownerId = 'run-b' }
        $lease = [pscustomobject]@{ status = 'active'; lease = [pscustomobject][ordered]@{ schemaVersion = 'awx.source_edit_session.lease.v1'; generatedAt = '2099-08-07T00:00:00.0000000+00:00'; startedAtUtc = '2099-08-07T00:00:00.0000000+00:00'; topic = $request.topic; role = 'desktop'; ownerId = 'run-b'; root = 'C:\AbandonWare\demo-1\demo-1\src'; expiresAtUtc = '2099-08-07T03:00:00.0000000+00:00'; expiresAt = '2099-08-07T03:00:00.0000000+00:00'; mutationAllowed = $true } }
        & $leaseCase.mutate $lease; $bad.context.acquireOverride = $lease
        Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $bad.context.state -StateSha256 $bad.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $bad.adapters } $leaseCase.reason "lease $($leaseCase.name) mismatch fails closed"
        Assert-True (($bad.context.calls -join ',') -match 'acquire,cas:begin-recovery-required,release,lease-absent') "lease $($leaseCase.name) mismatch uses matching cleanup"
    }

    $unknownLease = New-AdapterFixture (New-State)
    $unknownLeaseEnvelope = [pscustomobject]@{ status = 'active'; lease = [pscustomobject][ordered]@{ schemaVersion = 'awx.source_edit_session.lease.v1'; generatedAt = '2099-08-07T00:00:00.0000000+00:00'; startedAtUtc = '2099-08-07T00:00:00.0000000+00:00'; topic = ('consolidated-' + ('a' * 12) + '-WU-B'); role = 'desktop'; ownerId = 'run-b'; root = 'C:\AbandonWare\demo-1\demo-1\src'; expiresAtUtc = '2099-08-07T03:00:00.0000000+00:00'; expiresAt = '2099-08-07T03:00:00.0000000+00:00'; mutationAllowed = $true; unexpected = 'field' } }
    $unknownLease.context.acquireOverride = $unknownLeaseEnvelope
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $unknownLease.context.state -StateSha256 $unknownLease.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $unknownLease.adapters } 'invalid-lease-schema' 'unknown lease fields fail exact schema validation'

    $casFail = New-AdapterFixture (New-State); $casFail.context.casFailPhase = 'running'
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $casFail.context.state -StateSha256 $casFail.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $casFail.adapters } 'state-hash-changed' 'running CAS failure is reported after cleanup'
    Assert-True (($casFail.context.calls -join ',') -match 'cas:running,cas:begin-recovery-required,release,lease-absent,cas:begin-cleanup-complete$') 'running CAS failure releases and restores pending intent'

    $cleanupFail = New-AdapterFixture (New-State); $cleanupFail.context.acquireOverride = [pscustomobject]@{ status = 'expired'; lease = [pscustomobject]@{} }; $cleanupFail.context.releaseThrows = $true
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $cleanupFail.context.state -StateSha256 $cleanupFail.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $cleanupFail.adapters } 'begin-cleanup-unproven' 'cleanup failure fails closed'
    Assert-Equal $cleanupFail.context.state.workUnits[1].transition.phase 'begin-recovery-required' 'cleanup failure persists a recovery transition'

    $acquireFail = New-AdapterFixture (New-State); $acquireFail.context.acquireThrows = $true
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $acquireFail.context.state -StateSha256 $acquireFail.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $acquireFail.adapters } 'simulated-acquire-failure' 'Acquire failure is returned only after matching cleanup'
    Assert-Equal ($acquireFail.context.calls -join ',') 'cas:begin-intent,acquire,cas:begin-recovery-required,release,lease-absent,cas:begin-cleanup-complete' 'Acquire failure cleanup restores pending intent'

    $captureFail = New-AdapterFixture (New-State); $captureFail.context.captureThrows = $true
    Assert-Throws { Invoke-ConsolidatedProgramBeginCore -State $captureFail.context.state -StateSha256 $captureFail.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -RunId 'run-b' -Adapters $captureFail.adapters } 'simulated-capture-failure' 'CaptureTargets failure is returned only after matching cleanup'
    Assert-Equal ($captureFail.context.calls -join ',') 'cas:begin-intent,acquire,capture-targets,cas:begin-recovery-required,release,lease-absent,cas:begin-cleanup-complete' 'CaptureTargets failure cleanup restores pending intent'

    $greenFixture = New-AdapterFixture (New-State); $runningState = Start-TestUnit $greenFixture
    $greenFixture.context.calls.Clear(); $greenFixture.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $greenEvidence = New-Evidence $greenFixture $runningState 'Green' $greenFixture.context.targetSnapshot
    $greenPath = Write-EvidenceFixture $greenFixture $greenEvidence
    $greenResult = Invoke-ConsolidatedProgramRecordCore -State $greenFixture.context.state -StateSha256 $greenFixture.context.stateHash `
        -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $greenPath -Adapters $greenFixture.adapters
    Assert-Equal $greenResult.reason 'work-unit-recorded' 'valid RED and GREEN evidence records terminal state'
    Assert-Equal $greenFixture.context.state.workUnits[1].status 'green' 'Green maps to green state'
    Assert-Equal $greenFixture.context.state.desktopFinalProof 'verified' 'all required Desktop-verified units derive verified proof'
    Assert-Equal ($greenFixture.context.calls -join ',') 'read-evidence,read-session,capture-targets,read-artifact:verification/red.log,read-artifact:verification/green.log,cas:record-awaiting-release,release,lease-absent,cas:terminal' 'Record proves active ownership before transition, releases, and then terminalizes'
    Assert-Equal $greenFixture.context.snapshots[$greenFixture.context.snapshots.Count - 2].workUnits[1].status 'running' 'record-awaiting-release leaves status running'

    $unchangedGreen = New-AdapterFixture (New-State); $unchangedGreenState = Start-TestUnit $unchangedGreen
    $unchangedGreen.context.calls.Clear(); $unchangedGreen.context.targetSnapshot = New-TargetRows
    $unchangedGreenEvidence = New-Evidence $unchangedGreen $unchangedGreenState 'Green' $unchangedGreen.context.targetSnapshot
    $unchangedGreenPath = Write-EvidenceFixture $unchangedGreen $unchangedGreenEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $unchangedGreen.context.state -StateSha256 $unchangedGreen.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $unchangedGreenPath -Adapters $unchangedGreen.adapters } 'green-target-unchanged' 'Green with declared targets requires at least one changed image'
    Assert-True (@($unchangedGreen.context.calls | Where-Object { $_ -like 'cas:*' -or $_ -ceq 'release' }).Count -eq 0) 'unchanged Green does not persist a transition or release ownership'

    $lostOwnership = New-AdapterFixture (New-State)
    $lostOwnershipState = Start-TestUnit $lostOwnership
    $lostOwnership.context.calls.Clear()
    $lostOwnership.context.leaseAbsent = $true
    $lostOwnership.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $lostOwnershipEvidence = New-Evidence $lostOwnership $lostOwnershipState 'Green' $lostOwnership.context.targetSnapshot
    $lostOwnershipPath = Write-EvidenceFixture $lostOwnership $lostOwnershipEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $lostOwnership.context.state -StateSha256 $lostOwnership.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $lostOwnershipPath -Adapters $lostOwnership.adapters } 'lease-not-active' 'first Record rejects a running unit whose exact lease is already absent'
    Assert-Equal ($lostOwnership.context.calls -join ',') 'read-evidence,read-session' 'lost ownership is rejected before target capture or transition CAS'

    $whitespaceState = New-State
    $whitespaceState.workUnits[1].redCommands = @('cmd')
    $whitespaceState.workUnits[1].greenCommands = @('cmd', 'cmd ')
    $whitespace = New-AdapterFixture $whitespaceState
    $whitespaceRunning = Start-TestUnit $whitespace
    $whitespace.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $whitespaceEvidence = New-Evidence $whitespace $whitespaceRunning 'Green' $whitespace.context.targetSnapshot
    $whitespaceEvidence.redCommand.commandId = 'red:sha256:04dc5b2136328a0dcb189df97734c7c72e5e1227fa0c03469a6ce608f32f1b66'
    $firstWhitespaceGreen = Copy-Value $whitespaceEvidence.greenCommands[0]
    $firstWhitespaceGreen.commandId = 'green:sha256:04dc5b2136328a0dcb189df97734c7c72e5e1227fa0c03469a6ce608f32f1b66'
    $secondWhitespaceGreen = Copy-Value $firstWhitespaceGreen
    $secondWhitespaceGreen.commandId = 'green:sha256:93a5a33325dc00c1e7e17e3c5acc3b12cf6707932298e3103f4dc32fb711833f'
    $whitespaceEvidence.greenCommands = @($firstWhitespaceGreen, $secondWhitespaceGreen)
    $whitespacePath = Write-EvidenceFixture $whitespace $whitespaceEvidence
    $whitespaceResult = Invoke-ConsolidatedProgramRecordCore -State $whitespace.context.state -StateSha256 $whitespace.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $whitespacePath -Adapters $whitespace.adapters
    Assert-Equal $whitespaceResult.status 'green' 'exact trailing whitespace produces a distinct stable command ID'
    Assert-Equal $whitespace.context.state.workUnits[1].record.greenCommandIds[1] 'green:sha256:93a5a33325dc00c1e7e17e3c5acc3b12cf6707932298e3103f4dc32fb711833f' 'terminal record keeps the literal whitespace-sensitive ID'

    $nonAsciiState = New-State
    $nonAsciiCommand = -join ([char[]]@(0xAC80,0xC99D,0x20,0xBA85,0xB839))
    $nonAsciiState.workUnits[1].greenCommands = @($nonAsciiCommand)
    $nonAscii = New-AdapterFixture $nonAsciiState
    $nonAsciiRunning = Start-TestUnit $nonAscii
    $nonAscii.context.targetSnapshot = New-TargetRows
    $nonAsciiEvidence = New-Evidence $nonAscii $nonAsciiRunning 'NoPatchNeeded' $nonAscii.context.targetSnapshot
    $nonAsciiEvidence.greenCommands[0].commandId = 'green:sha256:c4e7fe6dacb65a041b5d0e6141b1f4a16e10b752d93f60eef8547ceab8763d35'
    $nonAsciiPath = Write-EvidenceFixture $nonAscii $nonAsciiEvidence
    $nonAsciiResult = Invoke-ConsolidatedProgramRecordCore -State $nonAscii.context.state -StateSha256 $nonAscii.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $nonAsciiPath -Adapters $nonAscii.adapters
    Assert-Equal $nonAsciiResult.status 'no_patch_needed' 'non-ASCII command uses strict UTF-8 identity'

    $sameCommandState = New-State
    $sameCommandState.workUnits[1].redCommands = @('cmd')
    $sameCommandState.workUnits[1].greenCommands = @('cmd')
    $sameCommand = New-AdapterFixture $sameCommandState
    $sameCommandRunning = Start-TestUnit $sameCommand
    $sameCommand.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $sameCommandEvidence = New-Evidence $sameCommand $sameCommandRunning 'Green' $sameCommand.context.targetSnapshot
    $sameCommandEvidence.redCommand.commandId = 'red:sha256:04dc5b2136328a0dcb189df97734c7c72e5e1227fa0c03469a6ce608f32f1b66'
    $sameCommandEvidence.greenCommands[0].commandId = 'green:sha256:04dc5b2136328a0dcb189df97734c7c72e5e1227fa0c03469a6ce608f32f1b66'
    $sameCommandPath = Write-EvidenceFixture $sameCommand $sameCommandEvidence
    $sameCommandResult = Invoke-ConsolidatedProgramRecordCore -State $sameCommand.context.state -StateSha256 $sameCommand.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $sameCommandPath -Adapters $sameCommand.adapters
    Assert-Equal $sameCommandResult.status 'green' 'red and green kinds remain separated for identical command bytes'
    Assert-True ([string]$sameCommand.context.state.workUnits[1].record.redCommandId -cne [string]$sameCommand.context.state.workUnits[1].record.greenCommandIds[0]) 'kind separation prevents cross-kind ID equality'

    $maxCommandState = New-State
    $maxCommandState.workUnits[1].greenCommands = @((('x' * 4096) -join ''))
    $maxCommand = New-AdapterFixture $maxCommandState
    $maxCommandRunning = Start-TestUnit $maxCommand
    $maxCommand.context.targetSnapshot = New-TargetRows
    $maxCommandEvidence = New-Evidence $maxCommand $maxCommandRunning 'NoPatchNeeded' $maxCommand.context.targetSnapshot
    $maxCommandEvidence.greenCommands[0].commandId = 'green:sha256:a2e659dacb4691e887ac0139f8893d04764ee197d70fb73d3190d56113d18e3e'
    $maxCommandPath = Write-EvidenceFixture $maxCommand $maxCommandEvidence
    $maxCommandResult = Invoke-ConsolidatedProgramRecordCore -State $maxCommand.context.state -StateSha256 $maxCommand.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $maxCommandPath -Adapters $maxCommand.adapters
    Assert-Equal $maxCommandResult.status 'no_patch_needed' '4096 exact UTF-8 bytes are accepted'

    foreach ($commandCase in @(
        @{ name = 'duplicate'; commands = @('cmd','cmd') },
        @{ name = 'blank'; commands = @('   ') },
        @{ name = 'multiline'; commands = @("cmd`nnext") },
        @{ name = 'control'; commands = @("cmd`targ") },
        @{ name = 'Unicode line separator'; commands = @('cmd' + [char]0x2028 + 'next') },
        @{ name = 'Unicode paragraph separator'; commands = @('cmd' + [char]0x2029 + 'next') },
        @{ name = '4097 bytes'; commands = @((('x' * 4097) -join '')) }
    )) {
        $invalidCommandState = New-State
        $invalidCommandState.workUnits[1].greenCommands = @($commandCase.commands)
        $invalidCommand = New-AdapterFixture $invalidCommandState
        $invalidCommandRunning = Start-TestUnit $invalidCommand
        $invalidCommand.context.targetSnapshot = New-TargetRows
        $invalidCommandEvidence = New-Evidence $invalidCommand $invalidCommandRunning 'NoPatchNeeded' $invalidCommand.context.targetSnapshot
        $invalidCommandPath = Write-EvidenceFixture $invalidCommand $invalidCommandEvidence
        Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $invalidCommand.context.state -StateSha256 $invalidCommand.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $invalidCommandPath -Adapters $invalidCommand.adapters } 'invalid-command-contract' "$($commandCase.name) declaration is rejected without raw command leakage"
    }

    $mismatchFixture = New-AdapterFixture (New-State); $mismatchState = Start-TestUnit $mismatchFixture; $mismatchFixture.context.calls.Clear()
    $mismatchFixture.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $mismatchEvidence = New-Evidence $mismatchFixture $mismatchState 'Green' $mismatchFixture.context.targetSnapshot
    $mismatchEvidence.targetPreimages[0].sha256 = 'D' * 64
    $mismatchPath = Write-EvidenceFixture $mismatchFixture $mismatchEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $mismatchFixture.context.state -StateSha256 $mismatchFixture.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $mismatchPath -Adapters $mismatchFixture.adapters } 'evidence-preimage-mismatch' 'evidence must repeat the exact frozen preimage set'
    Assert-True ($mismatchFixture.context.calls -notcontains 'release') 'invalid evidence never releases ownership'

    $postMismatch = New-AdapterFixture (New-State); $postState = Start-TestUnit $postMismatch; $postMismatch.context.calls.Clear()
    $postMismatch.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $postEvidence = New-Evidence $postMismatch $postState 'Green' (New-TargetRows ('D' * 64) 24)
    $postPath = Write-EvidenceFixture $postMismatch $postEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $postMismatch.context.state -StateSha256 $postMismatch.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $postPath -Adapters $postMismatch.adapters } 'evidence-postimage-mismatch' 'evidence postimages must match actual targets'

    foreach ($postSnapshotCase in @(
        @{ name = 'containment false'; mutate = { param($f) $f.context.targetSnapshot[0].resolvedPathContained = $false } },
        @{ name = 'reparse traversal'; mutate = { param($f) $f.context.targetSnapshot[0].ancestorNonReparse = $false; $f.context.targetSnapshot[0].reparseTraversal = $true } },
        @{ name = 'existing nonleaf'; mutate = { param($f) $f.context.targetSnapshot[0].leafKind = 'directory' } },
        @{ name = 'unsafe missing ancestor'; mutate = { param($f) $f.context.targetSnapshot[1].ancestorNonReparse = $false; $f.context.targetSnapshot[1].reparseTraversal = $true } }
    )) {
        $unsafePost = New-AdapterFixture (New-State); $unsafePostState = Start-TestUnit $unsafePost; $unsafePost.context.calls.Clear(); $unsafePost.context.targetSnapshot = New-TargetRows ('C' * 64) 24
        & $postSnapshotCase.mutate $unsafePost
        $unsafePostEvidence = New-Evidence $unsafePost $unsafePostState 'Green' $unsafePost.context.targetSnapshot
        $unsafePostPath = Write-EvidenceFixture $unsafePost $unsafePostEvidence
        Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $unsafePost.context.state -StateSha256 $unsafePost.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $unsafePostPath -Adapters $unsafePost.adapters } 'invalid-target-snapshot' "Record rejects target snapshot $($postSnapshotCase.name) despite matching hashes and bytes"
        Assert-True ($unsafePost.context.calls -notcontains 'release') "Record retains ownership for snapshot $($postSnapshotCase.name)"
    }
    $unknownPostEnvelope = New-AdapterFixture (New-State); $unknownPostState = Start-TestUnit $unknownPostEnvelope; $unknownPostEnvelope.context.targetSnapshot = New-TargetRows ('C' * 64) 24; $unknownPostEnvelope.context.snapshotEnvelopeExtra = $true
    $unknownPostEvidence = New-Evidence $unknownPostEnvelope $unknownPostState 'Green' $unknownPostEnvelope.context.targetSnapshot; $unknownPostPath = Write-EvidenceFixture $unknownPostEnvelope $unknownPostEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $unknownPostEnvelope.context.state -StateSha256 $unknownPostEnvelope.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $unknownPostPath -Adapters $unknownPostEnvelope.adapters } 'invalid-target-snapshot' 'Record rejects unknown target snapshot envelope fields'

    foreach ($evidenceCase in @(
        @{ name = 'RED zero'; mutate = { param($e) $e.redCommand.exitCode = 0 }; reason = 'red-proof-missing' },
        @{ name = 'GREEN nonzero'; mutate = { param($e) $e.greenCommands[0].exitCode = 1 }; reason = 'green-proof-missing' },
        @{ name = 'secret hit'; mutate = { param($e) $e.secretScan.hitCount = 1 }; reason = 'secret-scan-failed' },
        @{ name = 'owner mismatch'; mutate = { param($e) $e.owner = 'Desktop' }; reason = 'evidence-owner-mismatch' },
        @{ name = 'canonical mismatch'; mutate = { param($e) $e.canonicalMarkdownSha256 = 'F' * 64 }; reason = 'evidence-canonical-mismatch' },
        @{ name = 'raw field'; mutate = { param($e) $e | Add-Member rawBody 'forbidden' }; reason = 'invalid-evidence-schema' }
    )) {
        $bad = New-AdapterFixture (New-State); $runState = Start-TestUnit $bad; $bad.context.calls.Clear(); $bad.context.targetSnapshot = New-TargetRows ('C' * 64) 24
        $evidence = New-Evidence $bad $runState 'Green' $bad.context.targetSnapshot; & $evidenceCase.mutate $evidence
        $path = Write-EvidenceFixture $bad $evidence
        Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $bad.context.state -StateSha256 $bad.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $path -Adapters $bad.adapters } $evidenceCase.reason "$($evidenceCase.name) is rejected"
        Assert-True ($bad.context.calls -notcontains 'release') "$($evidenceCase.name) does not release ownership"
    }

    $noPatch = New-AdapterFixture (New-State); $noPatchState = Start-TestUnit $noPatch; $noPatch.context.calls.Clear()
    $noPatch.context.targetSnapshot = New-TargetRows
    $noPatchEvidence = New-Evidence $noPatch $noPatchState 'NoPatchNeeded' $noPatch.context.targetSnapshot
    $noPatchPath = Write-EvidenceFixture $noPatch $noPatchEvidence
    $noPatchResult = Invoke-ConsolidatedProgramRecordCore -State $noPatch.context.state -StateSha256 $noPatch.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $noPatchPath -Adapters $noPatch.adapters
    Assert-Equal $noPatchResult.status 'no_patch_needed' 'unchanged characterization GREEN records NoPatchNeeded'

    $badNoPatch = New-AdapterFixture (New-State); $badNoPatchState = Start-TestUnit $badNoPatch; $badNoPatch.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $badNoPatchEvidence = New-Evidence $badNoPatch $badNoPatchState 'NoPatchNeeded' $badNoPatch.context.targetSnapshot
    $badNoPatchPath = Write-EvidenceFixture $badNoPatch $badNoPatchEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $badNoPatch.context.state -StateSha256 $badNoPatch.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $badNoPatchPath -Adapters $badNoPatch.adapters } 'no-patch-target-changed' 'NoPatchNeeded rejects changed targets'

    $noCharacter = New-AdapterFixture (New-State); $noCharacterState = Start-TestUnit $noCharacter; $noCharacter.context.targetSnapshot = New-TargetRows
    $noCharacterEvidence = New-Evidence $noCharacter $noCharacterState 'NoPatchNeeded' $noCharacter.context.targetSnapshot; $noCharacterEvidence.greenCommands[0].characterization = $false
    $noCharacterPath = Write-EvidenceFixture $noCharacter $noCharacterEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $noCharacter.context.state -StateSha256 $noCharacter.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $noCharacterPath -Adapters $noCharacter.adapters } 'characterization-green-missing' 'NoPatchNeeded requires characterization GREEN'

    $duplicateGreenState = New-State; $duplicateGreenState.workUnits[1].greenCommands = @('g1', 'g2')
    $duplicateGreen = New-AdapterFixture $duplicateGreenState; $duplicateGreenRunning = Start-TestUnit $duplicateGreen; $duplicateGreen.context.targetSnapshot = New-TargetRows
    $duplicateGreenEvidence = New-Evidence $duplicateGreen $duplicateGreenRunning 'NoPatchNeeded' $duplicateGreen.context.targetSnapshot
    $firstGreen = Copy-Value $duplicateGreenEvidence.greenCommands[0]; $firstGreen.commandId = 'green:sha256:711430f6164e93803d93428bc1fab80f41e213bb197689307de8606d437c3038'
    $secondGreen = Copy-Value $firstGreen
    $duplicateGreenEvidence.greenCommands = @($firstGreen, $secondGreen)
    $duplicateGreenPath = Write-EvidenceFixture $duplicateGreen $duplicateGreenEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $duplicateGreen.context.state -StateSha256 $duplicateGreen.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'NoPatchNeeded' -EvidencePath $duplicateGreenPath -Adapters $duplicateGreen.adapters } 'green-proof-missing' 'NoPatchNeeded rejects duplicate commands in place of exact positional coverage'

    $outsideEvidence = New-AdapterFixture (New-State); $outsideEvidenceState = Start-TestUnit $outsideEvidence; $outsideEvidence.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $outsideEvidenceValue = New-Evidence $outsideEvidence $outsideEvidenceState 'Green' $outsideEvidence.context.targetSnapshot; $outsideEvidencePath = Write-EvidenceFixture $outsideEvidence $outsideEvidenceValue
    $outsideEvidence.context.evidenceContained = $false
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $outsideEvidence.context.state -StateSha256 $outsideEvidence.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $outsideEvidencePath -Adapters $outsideEvidence.adapters } 'unsafe-evidence-leaf' 'evidence adapter must prove repository containment'

    $reparseArtifact = New-AdapterFixture (New-State); $reparseArtifactState = Start-TestUnit $reparseArtifact; $reparseArtifact.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $reparseArtifactValue = New-Evidence $reparseArtifact $reparseArtifactState 'Green' $reparseArtifact.context.targetSnapshot; $reparseArtifactPath = Write-EvidenceFixture $reparseArtifact $reparseArtifactValue
    $reparseArtifact.context.artifactNonReparse = $false
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $reparseArtifact.context.state -StateSha256 $reparseArtifact.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $reparseArtifactPath -Adapters $reparseArtifact.adapters } 'unsafe-artifact-leaf' 'artifact adapter must prove a non-reparse repository leaf'

    foreach ($outcome in @('Hold', 'Failed')) {
        $rollback = New-AdapterFixture (New-State); $rollbackState = Start-TestUnit $rollback; $rollback.context.targetSnapshot = New-TargetRows
        $rollbackEvidence = New-Evidence $rollback $rollbackState $outcome $rollback.context.targetSnapshot 'evidence_needed'
        $rollbackPath = Write-EvidenceFixture $rollback $rollbackEvidence
        $rollbackResult = Invoke-ConsolidatedProgramRecordCore -State $rollback.context.state -StateSha256 $rollback.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome $outcome -EvidencePath $rollbackPath -Adapters $rollback.adapters
        Assert-Equal $rollbackResult.status $outcome.ToLowerInvariant() "$outcome releases after exact rollback proof"
    }
    $badRollback = New-AdapterFixture (New-State); $badRollbackState = Start-TestUnit $badRollback; $badRollback.context.calls.Clear(); $badRollback.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $badRollbackEvidence = New-Evidence $badRollback $badRollbackState 'Hold' $badRollback.context.targetSnapshot 'evidence_needed'
    $badRollbackEvidence.rollback.actualTargets = @(New-TargetRows)
    $badRollbackPath = Write-EvidenceFixture $badRollback $badRollbackEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $badRollback.context.state -StateSha256 $badRollback.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Hold' -EvidencePath $badRollbackPath -Adapters $badRollback.adapters } 'rollback-target-mismatch' 'Hold cannot release while actual targets differ from preimages'
    Assert-True ($badRollback.context.calls -notcontains 'release') 'rollback mismatch retains ownership'

    $crash = New-AdapterFixture (New-State); $crashState = Start-TestUnit $crash; $crash.context.calls.Clear(); $crash.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $crashEvidence = New-Evidence $crash $crashState 'Green' $crash.context.targetSnapshot; $crashPath = Write-EvidenceFixture $crash $crashEvidence
    $crash.context.releaseThrows = $true
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $crash.context.state -StateSha256 $crash.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $crashPath -Adapters $crash.adapters } 'release-unproven' 'release crash leaves recoverable transition'
    Assert-Equal $crash.context.state.workUnits[1].transition.phase 'record-awaiting-release' 'release crash retains persisted transition'
    $releaseCount = @($crash.context.calls | Where-Object { $_ -ceq 'release' }).Count
    $crash.context.releaseThrows = $false; $crash.context.calls.Clear()
    $recovered = Invoke-ConsolidatedProgramRecordCore -State $crash.context.state -StateSha256 $crash.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $crashPath -Adapters $crash.adapters
    Assert-Equal $recovered.reason 'work-unit-recorded' 'persisted transition recovers when exact lease is already absent'
    Assert-True ($crash.context.calls -notcontains 'release') 'absent-lease recovery does not call Release twice'
    Assert-Equal $releaseCount 1 'first release attempt occurred exactly once'

    $presentRetry = New-AdapterFixture (New-State); $presentRetryState = Start-TestUnit $presentRetry; $presentRetry.context.calls.Clear(); $presentRetry.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $presentRetryEvidence = New-Evidence $presentRetry $presentRetryState 'Green' $presentRetry.context.targetSnapshot; $presentRetryPath = Write-EvidenceFixture $presentRetry $presentRetryEvidence
    $presentRetry.context.releaseThrows = $true; $presentRetry.context.releaseRemovesBeforeThrow = $false
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $presentRetry.context.state -StateSha256 $presentRetry.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $presentRetryPath -Adapters $presentRetry.adapters } 'release-unproven' 'release failure with lock present retains transition'
    Assert-True (-not $presentRetry.context.leaseAbsent) 'failed release leaves exact lock present in adapter state'
    $presentRetry.context.releaseThrows = $false; $presentRetry.context.calls.Clear()
    $presentRecovered = Invoke-ConsolidatedProgramRecordCore -State $presentRetry.context.state -StateSha256 $presentRetry.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $presentRetryPath -Adapters $presentRetry.adapters
    Assert-Equal $presentRecovered.status 'green' 'persisted transition retries Release while exact lock remains'
    Assert-True (($presentRetry.context.calls -join ',') -match 'lease-absent,release,lease-absent,cas:terminal$') 'present-lock retry proves absence after one new Release call'

    $terminalCas = New-AdapterFixture (New-State); $terminalCasState = Start-TestUnit $terminalCas; $terminalCas.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $terminalCasEvidence = New-Evidence $terminalCas $terminalCasState 'Green' $terminalCas.context.targetSnapshot; $terminalCasPath = Write-EvidenceFixture $terminalCas $terminalCasEvidence
    $terminalCas.context.casFailPhase = 'terminal'
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $terminalCas.context.state -StateSha256 $terminalCas.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $terminalCasPath -Adapters $terminalCas.adapters } 'state-hash-changed' 'terminal CAS failure remains recoverable after proven release'
    Assert-Equal $terminalCas.context.state.workUnits[1].transition.phase 'record-awaiting-release' 'terminal CAS failure retains release transition'
    $savedCommittedRecord = Copy-Value $terminalCas.context.state.workUnits[1].transition.record

    $tamperedTransitionState = Copy-Value $terminalCas.context.state
    $tamperedTransitionState.workUnits[1].transition.record.desktopFinalProof = 'evidence_needed'
    $tamperedTransition = New-AdapterFixture $tamperedTransitionState
    $tamperedTransition.context.leaseAbsent = $true
    $tamperedTransitionPath = Write-EvidenceFixture $tamperedTransition $terminalCasEvidence
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $tamperedTransition.context.state -StateSha256 $tamperedTransition.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $tamperedTransitionPath -Adapters $tamperedTransition.adapters } 'record-transition-mismatch' 'tampered committed transition record cannot release or terminalize'
    Assert-True (@($tamperedTransition.context.calls | Where-Object { $_ -ceq 'release' -or $_ -ceq 'capture-targets' -or $_ -like 'read-artifact:*' -or $_ -like 'cas:*' }).Count -eq 0) 'tampered transition fails before release, capture, artifacts, or CAS'

    $terminalCas.context.casFailPhase = ''; $terminalCas.context.calls.Clear()
    $terminalCas.context.targetSnapshot = New-TargetRows ('D' * 64) 36
    Remove-Item -LiteralPath (Join-Path $terminalCas.context.root $terminalCasPath.Replace('/', '\')) -Force
    $terminalRecovered = Invoke-ConsolidatedProgramRecordCore -State $terminalCas.context.state -StateSha256 $terminalCas.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $terminalCasPath -Adapters $terminalCas.adapters
    Assert-Equal $terminalRecovered.status 'green' 'terminal CAS retry completes from absent lease transition'
    Assert-True ($terminalCas.context.calls -notcontains 'release') 'terminal CAS retry does not release an already absent lease twice'
    Assert-True ($terminalCas.context.calls -notcontains 'read-evidence' -and $terminalCas.context.calls -notcontains 'capture-targets' -and @($terminalCas.context.calls | Where-Object { $_ -like 'read-artifact:*' }).Count -eq 0) 'terminal retry does not reread evidence, recapture targets, or replay command artifacts'
    Assert-Equal ($terminalCas.context.state.workUnits[1].record | ConvertTo-Json -Depth 40 -Compress) ($savedCommittedRecord | ConvertTo-Json -Depth 40 -Compress) 'terminal retry preserves the exact committed transition record'

    $badAbsent = New-AdapterFixture (New-State); $badAbsentState = Start-TestUnit $badAbsent; $badAbsent.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $badAbsentEvidence = New-Evidence $badAbsent $badAbsentState 'Green' $badAbsent.context.targetSnapshot; $badAbsentPath = Write-EvidenceFixture $badAbsent $badAbsentEvidence
    $badAbsent.context.absenceOverride = $true
    Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $badAbsent.context.state -StateSha256 $badAbsent.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $badAbsentPath -Adapters $badAbsent.adapters } 'invalid-lease-absence-envelope' 'TestLeaseAbsent must return its exact typed envelope'

    $incomplete = New-AdapterFixture (New-State); $incomplete.context.state.workUnits[2].required = $true
    $incompleteState = Start-TestUnit $incomplete; $incomplete.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $incompleteEvidence = New-Evidence $incomplete $incompleteState 'Green' $incomplete.context.targetSnapshot; $incompletePath = Write-EvidenceFixture $incomplete $incompleteEvidence
    $incompleteResult = Invoke-ConsolidatedProgramRecordCore -State $incomplete.context.state -StateSha256 $incomplete.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $incompletePath -Adapters $incomplete.adapters
    Assert-Equal $incompleteResult.desktopFinalProof 'evidence_needed' 'pending required unit keeps top-level proof unverified'

    $emptyRequiredState = New-State; foreach ($candidate in @($emptyRequiredState.workUnits)) { $candidate.required = $false }
    $emptyRequired = New-AdapterFixture $emptyRequiredState; $emptyRequiredRunning = Start-TestUnit $emptyRequired; $emptyRequired.context.targetSnapshot = New-TargetRows ('C' * 64) 24
    $emptyRequiredEvidence = New-Evidence $emptyRequired $emptyRequiredRunning 'Green' $emptyRequired.context.targetSnapshot; $emptyRequiredPath = Write-EvidenceFixture $emptyRequired $emptyRequiredEvidence
    $emptyRequiredResult = Invoke-ConsolidatedProgramRecordCore -State $emptyRequired.context.state -StateSha256 $emptyRequired.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $emptyRequiredPath -Adapters $emptyRequired.adapters
    Assert-Equal $emptyRequiredResult.desktopFinalProof 'evidence_needed' 'zero required work units cannot vacuously derive verified proof'

    Remove-Item -LiteralPath (Join-Path $greenFixture.context.root $greenPath.Replace('/', '\')) -Force
    $greenFixture.context.calls.Clear()
    $replay = Invoke-ConsolidatedProgramRecordCore -State $greenFixture.context.state -StateSha256 $greenFixture.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome 'Green' -EvidencePath $greenPath -Adapters $greenFixture.adapters
    Assert-Equal $replay.reason 'already-recorded' 'terminal replay with exact evidence is idempotent'
    Assert-Equal $greenFixture.context.calls.Count 0 'terminal replay relies only on the already validated committed record'

    foreach ($badOutcome in @('green', 'Pass', '')) {
        $bad = New-AdapterFixture (New-State)
        Assert-Throws { Invoke-ConsolidatedProgramRecordCore -State $bad.context.state -StateSha256 $bad.context.stateHash -CanonicalMarkdownSha256 ('A' * 64) -WorkUnitId 'WU-B' -RunId 'run-b' -Outcome $badOutcome -EvidencePath 'verification/evidence.json' -Adapters $bad.adapters } 'invalid-outcome' "outcome '$badOutcome' is case-sensitive"
    }

    Write-Output "PASS: $script:passCount assertions"
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot)
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsafe-test-cleanup-path' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

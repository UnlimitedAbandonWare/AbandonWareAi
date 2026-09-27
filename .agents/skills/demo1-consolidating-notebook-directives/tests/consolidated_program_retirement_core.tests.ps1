[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:AssertionCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    $script:AssertionCount++
    if (-not $Condition) {
        throw "ASSERTION FAILED: $Message"
    }
}

function Assert-False {
    param([bool]$Condition, [string]$Message)
    Assert-True -Condition (-not $Condition) -Message $Message
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    $script:AssertionCount++
    if ($Expected -is [Array] -or $Actual -is [Array]) {
        $expectedJson = @($Expected) | ConvertTo-Json -Compress -Depth 12
        $actualJson = @($Actual) | ConvertTo-Json -Compress -Depth 12
        if ($expectedJson -cne $actualJson) {
            throw "ASSERTION FAILED: $Message expected=$expectedJson actual=$actualJson"
        }
        return
    }
    if ([string]$Expected -cne [string]$Actual) {
        throw "ASSERTION FAILED: $Message expected=$Expected actual=$Actual"
    }
}

function Assert-Contains {
    param([object[]]$Values, [string]$Expected, [string]$Message)
    $script:AssertionCount++
    if (@($Values) -cnotcontains $Expected) {
        throw "ASSERTION FAILED: $Message missing=$Expected actual=$(@($Values) -join ',')"
    }
}

function Assert-ThrowsReason {
    param([scriptblock]$Action, [string]$Reason, [string]$Message)
    $script:AssertionCount++
    try {
        & $Action
    } catch {
        if ([string]$_.Exception.Message -cne $Reason) {
            throw "ASSERTION FAILED: $Message expected=$Reason actual=$($_.Exception.Message)"
        }
        return
    }
    throw "ASSERTION FAILED: $Message expected throw=$Reason"
}

function Copy-Value {
    param($Value)
    return ($Value | ConvertTo-Json -Compress -Depth 30 | ConvertFrom-Json)
}

function New-Fixture {
    $now = [DateTimeOffset]::Parse('2026-08-07T10:00:00Z')
    $canonicalHash = ('A' * 64)
    $stateHash = ('B' * 64)
    $inventoryHash = ('C' * 64)
    $oneHash = ('D' * 64)
    $neighborHash = ('E' * 64)
    $canonicalPath = 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md'

    $program = [pscustomobject]@{
        schemaVersion = 'awx.notebook.directive.program.v1'
        canonicalPath = $canonicalPath
        canonicalSha256 = $canonicalHash
        inventorySha256 = $inventoryHash
        workUnits = @(
            [pscustomobject]@{ workUnitId = 'WU-A0'; required = $true; runnable = $true },
            [pscustomobject]@{ workUnitId = 'WU-E10'; required = $true; runnable = $true },
            [pscustomobject]@{ workUnitId = 'WU-D60'; required = $false; runnable = $false }
        )
    }
    $state = [pscustomobject]@{
        schemaVersion = 'awx.notebook.directive.program-state.v1'
        canonicalSha256 = $canonicalHash
        inventorySha256 = $inventoryHash
        stateSha256 = $stateHash
        desktopFinalProof = 'verified'
        workUnits = @(
            [pscustomobject]@{
                workUnitId = 'WU-A0'
                status = 'green'
                evidence = [pscustomobject]@{ owner = 'desktop'; desktopFinalProof = 'verified' }
            },
            [pscustomobject]@{
                workUnitId = 'WU-E10'
                status = 'no_patch_needed'
                evidence = [pscustomobject]@{ owner = 'desktop'; desktopFinalProof = 'verified' }
            },
            [pscustomobject]@{
                workUnitId = 'WU-D60'
                status = 'hold'
                evidence = [pscustomobject]@{ owner = 'desktop'; desktopFinalProof = 'evidence_needed' }
            }
        )
    }
    $inventory = [pscustomobject]@{
        schemaVersion = 'demo1.notebook-directive-retirement.v1'
        sha256 = $inventoryHash
        frozen = $true
        items = @(
            [pscustomobject]@{ path = 'data/agent-handoff/notebook/one.md'; sha256 = $oneHash; bytes = 11; coverage = 'complete'; classification = 'candidate' },
            [pscustomobject]@{ path = 'data/agent-handoff/notebook/protected.md.json'; sha256 = $neighborHash; bytes = 22; coverage = 'complete'; classification = 'candidate' },
            [pscustomobject]@{ path = $canonicalPath; sha256 = $canonicalHash; bytes = 100; coverage = 'complete'; classification = 'candidate' },
            [pscustomobject]@{ path = 'data/agent-handoff/notebook/protected.md'; sha256 = ('F' * 64); bytes = 33; coverage = 'complete'; classification = 'candidate' },
            [pscustomobject]@{ path = 'data/agent-handoff/notebook/changed.md'; sha256 = ('1' * 64); bytes = 44; coverage = 'complete'; classification = 'candidate' },
            [pscustomobject]@{ path = '__patch_drop__/notebook/input-v3.report.md'; sha256 = ('2' * 64); bytes = 55; coverage = 'complete'; classification = 'excluded'; exclusionReason = 'patchdrop-sidecar' }
        )
    }
    $stability = [pscustomobject]@{
        schemaVersion = 'awx.notebook.directive.stability-snapshot.v1'
        expectedCanonicalSha256 = $canonicalHash
        observedCanonicalSha256 = $canonicalHash
        expectedStateSha256 = $stateHash
        observedStateSha256 = $stateHash
        expectedInventorySha256 = $inventoryHash
        observedInventorySha256 = $inventoryHash
        root = 'C:\AbandonWare\demo-1\demo-1\src'
        capturedAtUtc = $now.ToString('o')
    }
    $gates = [pscustomobject]@{
        schemaVersion = 'awx.notebook.directive.fresh-gates.v1'
        root = 'C:\AbandonWare\demo-1\demo-1\src'
        capturedAtUtc = $now.ToString('o')
        indexLockPresent = $false
        topLevelPatchCount = 0
        activeLeaseCount = 0
        corruptLeaseCount = 0
    }
    $leafSnapshots = @(
        [pscustomobject]@{ path = 'data/agent-handoff/notebook/one.md'; exists = $true; isLeaf = $true; isReparse = $false; containedRepositoryLeaf = $true; bytes = 11; sha256 = $oneHash },
        [pscustomobject]@{ path = 'data/agent-handoff/notebook/protected.md.json'; exists = $true; isLeaf = $true; isReparse = $false; containedRepositoryLeaf = $true; bytes = 22; sha256 = $neighborHash },
        [pscustomobject]@{ path = $canonicalPath; exists = $true; isLeaf = $true; isReparse = $false; containedRepositoryLeaf = $true; bytes = 100; sha256 = $canonicalHash },
        [pscustomobject]@{ path = 'data/agent-handoff/notebook/protected.md'; exists = $true; isLeaf = $true; isReparse = $false; containedRepositoryLeaf = $true; bytes = 33; sha256 = ('F' * 64) },
        [pscustomobject]@{ path = 'data/agent-handoff/notebook/changed.md'; exists = $true; isLeaf = $true; isReparse = $false; containedRepositoryLeaf = $true; bytes = 44; sha256 = ('3' * 64) }
    )
    return [pscustomobject]@{
        now = $now
        runId = 'retire-20260807-a'
        program = $program
        state = $state
        inventory = $inventory
        stability = $stability
        gates = $gates
        leafSnapshots = $leafSnapshots
    }
}

function Get-AssessmentArgs {
    param($Fixture)
    return @{
        Program = $Fixture.program
        State = $Fixture.state
        Inventory = $Fixture.inventory
        StabilitySnapshot = $Fixture.stability
        GateSnapshot = $Fixture.gates
        LeafSnapshots = $Fixture.leafSnapshots
        RunId = $Fixture.runId
        ProtectedPaths = @('data/agent-handoff/notebook/protected.md')
        NowUtc = $Fixture.now
    }
}

function New-DispatchFixture {
    param([string]$FailOperationId = '', [switch]$ReturnOkFalse, [string]$StageDeletePreimageHashOverride = '')
    $context = [pscustomobject]@{
        calls = New-Object 'Collections.Generic.List[string]'
        failOperationId = $FailOperationId
        returnOkFalse = [bool]$ReturnOkFalse
        stageDeletePreimageHashOverride = $StageDeletePreimageHashOverride
    }
    $dispatch = {
        param($request)
        $context.calls.Add([string]$request.operation.operationId)
        $operation = $request.operation
        $ok = -not ($context.returnOkFalse -or [string]$operation.operationId -ceq $context.failOperationId)
        $preExists = if ($operation.expectedPreExists -is [bool]) { [bool]$operation.expectedPreExists } else { $false }
        $preSha = if ($operation.expectedPreSha256 -is [string]) { [string]$operation.expectedPreSha256 } else { $null }
        if ([string]$operation.operationId -ceq 'stage-delete-exact' -and -not [string]::IsNullOrWhiteSpace($context.stageDeletePreimageHashOverride)) {
            $preSha = [string]$context.stageDeletePreimageHashOverride
        }
        $preBytes = [long]$operation.expectedPreBytes
        $preIsLeaf = if ($operation.expectedPreIsLeaf -is [bool]) { [bool]$operation.expectedPreIsLeaf } else { $false }
        $preIsReparse = if ($operation.expectedPreIsReparse -is [bool]) { [bool]$operation.expectedPreIsReparse } else { $false }
        $targetExistsBefore = if ($operation.expectedTargetExistsBefore -is [bool]) { [bool]$operation.expectedTargetExistsBefore } else { $false }
        $postExists = if ($operation.expectedPostExists -is [bool]) { [bool]$operation.expectedPostExists } else { $false }
        $postSha = if ($operation.expectedPostSha256 -is [string]) { [string]$operation.expectedPostSha256 } else { $null }
        $postBytes = [long]$operation.expectedPostBytes
        $postIsLeaf = if ($operation.expectedPostIsLeaf -is [bool]) { [bool]$operation.expectedPostIsLeaf } else { $false }
        $postIsReparse = if ($operation.expectedPostIsReparse -is [bool]) { [bool]$operation.expectedPostIsReparse } else { $false }
        return [pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.cursor-callback-result.v2'
            runId = [string]$request.runId
            planDigest = [string]$request.planDigest
            operationId = [string]$operation.operationId
            ok = [bool]$ok
            preimagePath = [string]$operation.preimagePath
            preExists = [bool]$preExists
            preSha256 = $preSha
            preBytes = [long]$preBytes
            preIsLeaf = [bool]$preIsLeaf
            preIsReparse = [bool]$preIsReparse
            targetPath = [string]$operation.targetPath
            targetExistsBefore = [bool]$targetExistsBefore
            postPath = [string]$operation.postPath
            postExists = [bool]$postExists
            postSha256 = $postSha
            postBytes = [long]$postBytes
            postIsLeaf = [bool]$postIsLeaf
            postIsReparse = [bool]$postIsReparse
        }
    }.GetNewClosure()
    return [pscustomobject]@{ context = $context; callbacks = [pscustomobject]@{ Dispatch = $dispatch } }
}

function New-ReadbackFixture {
    param($Assessment, [object[]]$Payloads)
    $context = [pscustomobject]@{
        archiveMissing = $false
        archiveNonLeaf = $false
        archiveReparse = $false
        archiveContained = $true
        payloadMissing = $false
        payloadNonLeaf = $false
        payloadReparse = $false
        payloadContained = $true
        payloadHashOverride = ''
        calls = New-Object 'Collections.Generic.List[string]'
    }
    $readArchive = {
        param($request)
        $context.calls.Add(('archive:' + [string]$request.path))
        $item = @($Assessment.items | Where-Object { [string]$_.archivePath -ceq [string]$request.path })[0]
        return [pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.leaf-readback.v1'
            path = [string]$request.path
            exists = -not $context.archiveMissing
            isLeaf = -not $context.archiveNonLeaf
            isReparse = [bool]$context.archiveReparse
            containedRepositoryLeaf = [bool]$context.archiveContained
            sha256 = [string]$item.expectedSha256
            bytes = [long]$item.expectedBytes
        }
    }.GetNewClosure()
    $readPayload = {
        param($request)
        $context.calls.Add(('payload:' + [string]$request.path))
        $payload = @($Payloads | Where-Object { [string]$_.sourcePath -ceq [string]$request.path })[0]
        $hash = if ([string]::IsNullOrWhiteSpace($context.payloadHashOverride)) { [string]$payload.sha256 } else { [string]$context.payloadHashOverride }
        return [pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.leaf-readback.v1'
            path = [string]$request.path
            exists = -not $context.payloadMissing
            isLeaf = -not $context.payloadNonLeaf
            isReparse = [bool]$context.payloadReparse
            containedRepositoryLeaf = [bool]$context.payloadContained
            sha256 = $hash
            bytes = [long]$payload.bytes
        }
    }.GetNewClosure()
    return [pscustomobject]@{ context = $context; adapters = [pscustomobject]@{ ReadArchive = $readArchive; ReadPayload = $readPayload } }
}

function New-TestCursor {
    param($Assessment, $Plan, $Fixture, $Readbacks = $null)
    $entry = New-AwxExecutionJournalEntry -Assessment $Assessment -Plan $Plan
    $cursorArgs = @{
        Assessment = $Assessment; Plan = $Plan; JournalEntry = $entry
        StabilitySnapshot = $Fixture.stability; GateSnapshot = $Fixture.gates; NowUtc = $Fixture.now
    }
    if ($null -ne $Readbacks) { $cursorArgs.Readbacks = $Readbacks }
    return New-AwxExecutionCursor @cursorArgs
}

function Advance-TestCursor {
    param($Assessment, $Plan, $Cursor, $Fixture, [string]$ThroughOperationId = '', $Readbacks = $null)
    $dispatch = New-DispatchFixture
    $current = $Cursor
    while (-not [bool]$current.completed) {
        $stepArgs = @{
            Assessment = $Assessment; Plan = $Plan; Cursor = $current; Callbacks = $dispatch.callbacks
            StabilitySnapshot = $Fixture.stability; GateSnapshot = $Fixture.gates; NowUtc = $Fixture.now
        }
        if ($null -ne $Readbacks) { $stepArgs.Readbacks = $Readbacks }
        $step = Invoke-AwxExecutionCursorStep @stepArgs
        $current = $step.cursor
        if (-not [string]::IsNullOrWhiteSpace($ThroughOperationId) -and [string]$step.operationId -ceq $ThroughOperationId) { break }
    }
    return [pscustomobject]@{ cursor = $current; calls = @($dispatch.context.calls) }
}

try {
    $modulePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts\consolidated_program_retirement_core.psm1'
    if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) {
        throw 'ASSERTION FAILED: retirement core module exists'
    }
    Import-Module -Name $modulePath -Force

    $fixture = New-Fixture
    $args = Get-AssessmentArgs -Fixture $fixture

    $dryRunCallbackCount = 0
    $dryRun = Get-AwxRetirementAssessment @args -ReadOnlyProbe { $script:dryRunCallbackCount++; throw 'must-not-run' }
    Assert-Equal 'dry-run' $dryRun.mode 'Retire without confirmation is dry-run'
    Assert-False ([bool]$dryRun.authorityGranted) 'core never grants authority'
    Assert-False ([bool]$dryRun.readyForTrustedAdapter) 'dry-run cannot be executed'
    Assert-Equal 2 $dryRun.eligibleCount 'dry-run reports exact eligible count'
    Assert-Equal 1 $dryRun.heldCount 'dry-run reports exact hash-changed hold count'
    Assert-Equal 3 $dryRun.excludedCount 'dry-run reports exact canonical/protected/sidecar exclusions'
    Assert-Contains $dryRun.reasonCodes 'retirement-confirmation-required' 'dry-run reports explicit confirmation reason'
    Assert-Equal 0 $dryRunCallbackCount 'dry-run invokes zero mutation/read adapter callbacks'

    $confirmed = Get-AwxRetirementAssessment @args -ConfirmRetirement
    Assert-Equal 'confirm' $confirmed.mode 'confirmed assessment mode'
    Assert-False ([bool]$confirmed.authorityGranted) 'confirmed core remains no-authority'
    Assert-True ([bool]$confirmed.readyForTrustedAdapter) 'clean confirmed evidence is adapter-ready'
    Assert-Equal 'verified' $confirmed.derivedDesktopFinalProof 'Desktop proof is derived from required units'
    Assert-Equal 0 @($confirmed.reasonCodes).Count 'clean confirmed assessment has no blocking reasons'
    Assert-True ([string]$confirmed.assessmentDigest -cmatch '^[0-9A-F]{64}$') 'assessment carries a deterministic binding digest'
    $one = @($confirmed.items | Where-Object { $_.path -ceq 'data/agent-handoff/notebook/one.md' })[0]
    Assert-Equal 'data/agent-handoff/notebook/consolidated/archive/retire-20260807-a/data/agent-handoff/notebook/one.md' $one.archivePath 'archive path is fixed and repository-relative'
    $canonical = @($confirmed.items | Where-Object { $_.path -ceq $fixture.program.canonicalPath })[0]
    Assert-Equal 'excluded' $canonical.disposition 'canonical program is excluded exactly'
    Assert-Equal 'canonical-protected' $canonical.reason 'canonical exclusion reason'
    $canonicalClassified = New-Fixture
    $canonicalClassifiedItem = @($canonicalClassified.inventory.items | Where-Object { $_.path -ceq $canonicalClassified.program.canonicalPath })[0]
    $canonicalClassifiedItem.classification = 'excluded'
    $canonicalClassifiedItem | Add-Member -NotePropertyName exclusionReason -NotePropertyValue 'unsupported'
    $canonicalClassifiedArgs = Get-AssessmentArgs -Fixture $canonicalClassified
    $canonicalClassifiedResult = Get-AwxRetirementAssessment @canonicalClassifiedArgs -ConfirmRetirement
    $canonicalClassifiedRow = @($canonicalClassifiedResult.items | Where-Object { $_.path -ceq $canonicalClassified.program.canonicalPath })[0]
    Assert-Equal 'canonical-protected' $canonicalClassifiedRow.reason 'canonical protection overrides caller classification'
    $protected = @($confirmed.items | Where-Object { $_.path -ceq 'data/agent-handoff/notebook/protected.md' })[0]
    Assert-Equal 'excluded' $protected.disposition 'exact protected path is excluded'
    $protectedNeighbor = @($confirmed.items | Where-Object { $_.path -ceq 'data/agent-handoff/notebook/protected.md.json' })[0]
    Assert-Equal 'eligible' $protectedNeighbor.disposition 'protected matching is exact, not prefix based'

    $forged = New-Fixture
    $forged.state.desktopFinalProof = 'verified'
    $forged.state.workUnits[0].status = 'hold'
    $forgedArgs = Get-AssessmentArgs -Fixture $forged
    $forgedResult = Get-AwxRetirementAssessment @forgedArgs -ConfirmRetirement
    Assert-Equal 'evidence_needed' $forgedResult.derivedDesktopFinalProof 'caller top-level Desktop proof cannot override unit evidence'
    Assert-False ([bool]$forgedResult.readyForTrustedAdapter) 'nonterminal required unit blocks confirmation'
    Assert-Contains $forgedResult.reasonCodes 'required-work-units-not-terminal' 'nonterminal unit reason'

    $requiredNonRunnable = New-Fixture
    $requiredNonRunnable.program.workUnits[0].runnable = $false
    $requiredNonRunnable.state.workUnits[0].status = 'hold'
    $requiredNonRunnable.state.desktopFinalProof = 'verified'
    $requiredNonRunnableArgs = Get-AssessmentArgs -Fixture $requiredNonRunnable
    $requiredNonRunnableResult = Get-AwxRetirementAssessment @requiredNonRunnableArgs -ConfirmRetirement
    Assert-Equal 'evidence_needed' $requiredNonRunnableResult.derivedDesktopFinalProof 'required unit cannot bypass proof through runnable=false'
    Assert-Contains $requiredNonRunnableResult.reasonCodes 'required-work-units-not-terminal' 'required nonrunnable unit remains proof-bearing'

    $projectionMissing = New-Fixture
    $projectionMissing.state.workUnits = @($projectionMissing.state.workUnits | Where-Object { $_.workUnitId -cne 'WU-E10' })
    $projectionMissingArgs = Get-AssessmentArgs -Fixture $projectionMissing
    $projectionMissingResult = Get-AwxRetirementAssessment @projectionMissingArgs -ConfirmRetirement
    Assert-Contains $projectionMissingResult.reasonCodes 'program-state-projection-invalid' 'missing state projection blocks proof'

    $unexpectedProof = New-Fixture
    $unexpectedProof.state.workUnits[0].evidence | Add-Member -NotePropertyName unexpected -NotePropertyValue $true
    $unexpectedProofArgs = Get-AssessmentArgs -Fixture $unexpectedProof
    $unexpectedProofResult = Get-AwxRetirementAssessment @unexpectedProofArgs -ConfirmRetirement
    Assert-Contains $unexpectedProofResult.reasonCodes 'desktop-unit-proof-invalid' 'proof envelope rejects unknown fields'

    foreach ($case in @(
        [pscustomobject]@{ name = 'canonical'; property = 'observedCanonicalSha256'; reason = 'canonical-hash-changed' },
        [pscustomobject]@{ name = 'state'; property = 'observedStateSha256'; reason = 'state-hash-changed' },
        [pscustomobject]@{ name = 'inventory'; property = 'observedInventorySha256'; reason = 'inventory-hash-changed' }
    )) {
        $changed = New-Fixture
        $changed.stability.($case.property) = ('9' * 64)
        $changedArgs = Get-AssessmentArgs -Fixture $changed
        $result = Get-AwxRetirementAssessment @changedArgs -ConfirmRetirement
        Assert-False ([bool]$result.readyForTrustedAdapter) "$($case.name) drift blocks confirmation"
        Assert-Contains $result.reasonCodes $case.reason "$($case.name) drift reason"
    }

    foreach ($case in @(
        [pscustomobject]@{ property = 'indexLockPresent'; value = $true; reason = 'index-lock-present' },
        [pscustomobject]@{ property = 'topLevelPatchCount'; value = 1; reason = 'top-level-patch-pending' },
        [pscustomobject]@{ property = 'activeLeaseCount'; value = 1; reason = 'active-source-lease' },
        [pscustomobject]@{ property = 'corruptLeaseCount'; value = 1; reason = 'corrupt-source-lease' }
    )) {
        $blocked = New-Fixture
        $blocked.gates.($case.property) = $case.value
        $blockedArgs = Get-AssessmentArgs -Fixture $blocked
        $result = Get-AwxRetirementAssessment @blockedArgs -ConfirmRetirement
        Assert-False ([bool]$result.readyForTrustedAdapter) "$($case.reason) blocks confirmation"
        Assert-Contains $result.reasonCodes $case.reason "$($case.reason) is categorical"
    }

    $stale = New-Fixture
    $stale.gates.capturedAtUtc = $stale.now.AddMinutes(-2).ToString('o')
    $staleArgs = Get-AssessmentArgs -Fixture $stale
    $staleResult = Get-AwxRetirementAssessment @staleArgs -ConfirmRetirement
    Assert-Contains $staleResult.reasonCodes 'gate-snapshot-stale' 'fresh gates are time bounded'

    $notFrozen = New-Fixture
    $notFrozen.inventory.frozen = $false
    $notFrozenArgs = Get-AssessmentArgs -Fixture $notFrozen
    $notFrozenResult = Get-AwxRetirementAssessment @notFrozenArgs -ConfirmRetirement
    Assert-Contains $notFrozenResult.reasonCodes 'retirement-inventory-not-frozen' 'inventory must be frozen'

    foreach ($snapshotCase in @(
        [pscustomobject]@{ name = 'missing'; mutate = { param($leaf, $item) $leaf.exists = $false }; reason = 'retirement-source-missing' },
        [pscustomobject]@{ name = 'nonleaf'; mutate = { param($leaf, $item) $leaf.isLeaf = $false }; reason = 'retirement-target-not-leaf' },
        [pscustomobject]@{ name = 'reparse'; mutate = { param($leaf, $item) $leaf.isReparse = $true }; reason = 'retirement-reparse-risk' },
        [pscustomobject]@{ name = 'outside'; mutate = { param($leaf, $item) $leaf.containedRepositoryLeaf = $false }; reason = 'retirement-outside-repository' },
        [pscustomobject]@{ name = 'incomplete'; mutate = { param($leaf, $item) $item.coverage = 'incomplete' }; reason = 'coverage-incomplete' }
    )) {
        $snapshotFixture = New-Fixture
        $snapshotLeaf = @($snapshotFixture.leafSnapshots | Where-Object { $_.path -ceq 'data/agent-handoff/notebook/one.md' })[0]
        $snapshotItem = @($snapshotFixture.inventory.items | Where-Object { $_.path -ceq 'data/agent-handoff/notebook/one.md' })[0]
        & $snapshotCase.mutate $snapshotLeaf $snapshotItem
        $snapshotArgs = Get-AssessmentArgs -Fixture $snapshotFixture
        $snapshotResult = Get-AwxRetirementAssessment @snapshotArgs -ConfirmRetirement
        $snapshotRow = @($snapshotResult.items | Where-Object { $_.path -ceq 'data/agent-handoff/notebook/one.md' })[0]
        Assert-Equal 'hold' $snapshotRow.disposition "$($snapshotCase.name) snapshot is held"
        Assert-Equal $snapshotCase.reason $snapshotRow.reason "$($snapshotCase.name) snapshot reason"
    }

    $duplicateSnapshot = New-Fixture
    $duplicateSnapshot.leafSnapshots += Copy-Value $duplicateSnapshot.leafSnapshots[0]
    $duplicateSnapshotArgs = Get-AssessmentArgs -Fixture $duplicateSnapshot
    $duplicateSnapshotResult = Get-AwxRetirementAssessment @duplicateSnapshotArgs -ConfirmRetirement
    Assert-False ([bool]$duplicateSnapshotResult.readyForTrustedAdapter) 'duplicate snapshots block the batch'
    Assert-Contains $duplicateSnapshotResult.reasonCodes 'retirement-inventory-invalid' 'duplicate snapshots are structural invalidity'

    $duplicateInventory = New-Fixture
    $duplicateItem = Copy-Value $duplicateInventory.inventory.items[0]
    $duplicateInventory.inventory.items = @($duplicateInventory.inventory.items) + @($duplicateItem)
    $duplicateArgs = Get-AssessmentArgs -Fixture $duplicateInventory
    $duplicateResult = Get-AwxRetirementAssessment @duplicateArgs -ConfirmRetirement
    Assert-False ([bool]$duplicateResult.readyForTrustedAdapter) 'duplicate retirement inventory blocks the whole batch'
    Assert-Contains $duplicateResult.reasonCodes 'retirement-inventory-invalid' 'duplicate inventory is categorical'

    $outsideInventory = New-Fixture
    $outsideInventory.inventory.items += [pscustomobject]@{
        path = 'main/java/com/example/lms/DoNotRetire.java'
        sha256 = ('8' * 64)
        bytes = 66
        coverage = 'complete'
        classification = 'candidate'
    }
    $outsideInventory.leafSnapshots += [pscustomobject]@{
        path = 'main/java/com/example/lms/DoNotRetire.java'
        exists = $true
        isLeaf = $true
        isReparse = $false
        containedRepositoryLeaf = $true
        bytes = 66
        sha256 = ('8' * 64)
    }
    $outsideArgs = Get-AssessmentArgs -Fixture $outsideInventory
    $outsideResult = Get-AwxRetirementAssessment @outsideArgs -ConfirmRetirement
    $outsideRow = @($outsideResult.items | Where-Object { $_.path -ceq 'main/java/com/example/lms/DoNotRetire.java' })[0]
    Assert-Equal 'hold' $outsideRow.disposition 'candidate outside directive roots is held'
    Assert-Equal 'retirement-candidate-outside-bounds' $outsideRow.reason 'outside candidate reason'
    Assert-False ([bool]$outsideResult.readyForTrustedAdapter) 'outside candidate blocks the batch'

    $tooLongRunId = 'a' * 65
    Assert-ThrowsReason -Reason 'unsafe-run-id' -Message 'RunId length is bounded to 64' -Action {
        $invalidArgs = Get-AssessmentArgs -Fixture $fixture
        $invalidArgs.RunId = $tooLongRunId
        Get-AwxRetirementAssessment @invalidArgs -ConfirmRetirement | Out-Null
    }
    Assert-ThrowsReason -Reason 'unsafe-run-id' -Message 'RunId rejects separators' -Action {
        $invalidArgs = Get-AssessmentArgs -Fixture $fixture
        $invalidArgs.RunId = 'bad/run'
        Get-AwxRetirementAssessment @invalidArgs -ConfirmRetirement | Out-Null
    }
    $maxRunIdArgs = Get-AssessmentArgs -Fixture $fixture
    $maxRunIdArgs.RunId = ('a' * 64)
    $maxRunIdResult = Get-AwxRetirementAssessment @maxRunIdArgs -ConfirmRetirement
    Assert-True ([bool]$maxRunIdResult.readyForTrustedAdapter) '64-character RunId is accepted'

    $forgedAssessment = Copy-Value $dryRun
    $forgedAssessment.confirmRequested = $true
    $forgedAssessment.readyForTrustedAdapter = $true
    $forgedAssessment.derivedDesktopFinalProof = 'verified'
    Assert-ThrowsReason -Reason 'assessment-binding-invalid' -Message 'forged ready assessment cannot create a plan' -Action {
        New-AwxRetirementItemPlan -Assessment $forgedAssessment -Path 'data/agent-handoff/notebook/one.md' | Out-Null
    }

    $itemPlan = New-AwxRetirementItemPlan -Assessment $confirmed -Path 'data/agent-handoff/notebook/one.md'
    Assert-False ([bool]$itemPlan.authorityGranted) 'item plan grants no authority'
    Assert-True ([string]$itemPlan.planDigest -cmatch '^[0-9A-F]{64}$') 'item plan carries deterministic digest'
    Assert-Equal $confirmed.assessmentDigest $itemPlan.assessmentDigest 'plan binds the exact assessment'
    Assert-Equal 'data/agent-handoff/notebook/consolidated/archive/retire-20260807-a/data/agent-handoff/notebook/one.md' $itemPlan.archivePath 'item plan archive location'
    Assert-Equal 'data/agent-handoff/notebook/one.md.retiring.retire-20260807-a' $itemPlan.stagePath 'staging leaf is exact sibling'
    $operationIds = @($itemPlan.operations | ForEach-Object { $_.operationId })
    $deleteIndex = [Array]::IndexOf($operationIds, 'stage-delete-exact')
    Assert-Equal 'checkpoint-delete-intent' $operationIds[$deleteIndex - 1] 'delete intent checkpoint precedes exact deletion'
    Assert-Equal 'checkpoint-delete-observed' $operationIds[$deleteIndex + 1] 'post-delete observation checkpoint follows deletion'
    $archiveRenameIndex = [Array]::IndexOf($operationIds, 'archive-rename-final')
    Assert-Equal 'checkpoint-archive-rename-intent' $operationIds[$archiveRenameIndex - 1] 'archive rename requires prior checkpoint'
    $stageRenameIndex = [Array]::IndexOf($operationIds, 'source-rename-stage')
    Assert-Equal 'checkpoint-stage-rename-intent' $operationIds[$stageRenameIndex - 1] 'stage rename requires prior checkpoint'
    $deleteOp = $itemPlan.operations[$deleteIndex]
    Assert-True ([bool]$deleteOp.exactLeaf) 'delete is exact-leaf only'
    Assert-False ([bool]$deleteOp.recursive) 'delete is never recursive'
    Assert-False ([bool]$deleteOp.coreExecutes) 'core does not execute deletion'
    Assert-Equal $itemPlan.stagePath $deleteOp.preimagePath 'delete binds the exact staged preimage path'
    Assert-True ([bool]$deleteOp.expectedPreExists) 'delete requires staged preimage existence'
    Assert-Equal $itemPlan.expectedSha256 $deleteOp.expectedPreSha256 'delete binds staged preimage hash'
    Assert-Equal $itemPlan.expectedBytes $deleteOp.expectedPreBytes 'delete binds staged preimage bytes'
    Assert-True ([bool]$deleteOp.expectedPreIsLeaf) 'delete preimage must be a leaf'
    Assert-False ([bool]$deleteOp.expectedPreIsReparse) 'delete preimage must not be a reparse point'
    Assert-Equal $itemPlan.stagePath $deleteOp.postPath 'delete postcondition binds the same staged path'
    Assert-False ([bool]$deleteOp.expectedPostExists) 'delete requires post-delete absence'
    $archiveRenameOp = $itemPlan.operations[$archiveRenameIndex]
    Assert-Equal $itemPlan.archiveTempPath $archiveRenameOp.preimagePath 'archive rename binds source preimage path'
    Assert-False ([bool]$archiveRenameOp.expectedTargetExistsBefore) 'archive rename requires target absent before rename'
    Assert-Equal $itemPlan.archivePath $archiveRenameOp.postPath 'archive rename binds target postimage path'
    Assert-Equal $itemPlan.expectedSha256 $archiveRenameOp.expectedPostSha256 'archive rename binds target postimage hash'
    $stageRenameOp = $itemPlan.operations[$stageRenameIndex]
    Assert-Equal $itemPlan.sourcePath $stageRenameOp.preimagePath 'stage rename binds original source preimage'
    Assert-False ([bool]$stageRenameOp.expectedTargetExistsBefore) 'stage rename requires sibling target absent before rename'
    Assert-Equal $itemPlan.stagePath $stageRenameOp.postPath 'stage rename binds staged target postimage'

    $oldDispatcher = Get-Command -Name 'Invoke-AwxRetirementPlannedCallback' -ErrorAction SilentlyContinue
    Assert-True ($null -eq $oldDispatcher) 'arbitrary single-operation dispatcher is not exported'

    $cursor = New-TestCursor -Assessment $confirmed -Plan $itemPlan -Fixture $fixture
    Assert-Equal 0 $cursor.nextIndex 'new cursor starts at first operation'
    Assert-True ([string]$cursor.cursorDigest -cmatch '^[0-9A-F]{64}$') 'cursor is digest-bound'

    $forgedPlan = Copy-Value $itemPlan
    $forgedPlan.operations[0].operationId = 'stage-delete-exact'
    Assert-ThrowsReason -Reason 'plan-binding-invalid' -Message 'forged operation order cannot create a cursor' -Action {
        New-TestCursor -Assessment $confirmed -Plan $forgedPlan -Fixture $fixture | Out-Null
    }

    $forgedCursor = Copy-Value $cursor
    $forgedCursor.nextIndex = $deleteIndex
    $dispatch = New-DispatchFixture
    Assert-ThrowsReason -Reason 'cursor-binding-invalid' -Message 'caller cannot jump cursor to stage deletion' -Action {
        Invoke-AwxExecutionCursorStep -Assessment $confirmed -Plan $itemPlan -Cursor $forgedCursor -Callbacks $dispatch.callbacks `
            -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -NowUtc $fixture.now | Out-Null
    }

    $falseDispatch = New-DispatchFixture -ReturnOkFalse
    Assert-ThrowsReason -Reason 'callback-operation-failed' -Message 'ok=false is never accepted as progress' -Action {
        Invoke-AwxExecutionCursorStep -Assessment $confirmed -Plan $itemPlan -Cursor $cursor -Callbacks $falseDispatch.callbacks `
            -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -NowUtc $fixture.now | Out-Null
    }

    $firstDispatch = New-DispatchFixture
    $firstStep = Invoke-AwxExecutionCursorStep -Assessment $confirmed -Plan $itemPlan -Cursor $cursor -Callbacks $firstDispatch.callbacks `
        -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -NowUtc $fixture.now
    Assert-Equal 'archive-create-temp' $firstStep.operationId 'cursor dispatches only exact next operation'
    Assert-Equal 1 $firstStep.cursor.nextIndex 'successful next operation advances by one'
    Assert-Equal @('archive-create-temp') @($firstDispatch.context.calls) 'only one callback invocation occurs'

    $tempProgress = Advance-TestCursor -Assessment $confirmed -Plan $itemPlan -Cursor $cursor -Fixture $fixture -ThroughOperationId 'checkpoint-archive-temp-created'
    $tempJournal = ConvertTo-AwxExecutionJournalEntry -Assessment $confirmed -Plan $itemPlan -Cursor $tempProgress.cursor
    $tempObservation = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.retirement-observation.v1'
        sourceExists = $true; stageExists = $false; archiveTempExists = $true; archiveExists = $false
        sourceSha256 = $one.expectedSha256; stageSha256 = $null; archiveTempSha256 = $one.expectedSha256; archiveSha256 = $null
        canonicalExists = $true
    }
    $tempReplay = Get-AwxRetirementReplayPlan -ItemPlan $itemPlan -JournalEntry $tempJournal -Observation $tempObservation
    Assert-Equal 'resume' $tempReplay.action 'valid archive temp crash resumes'
    Assert-Equal 'archive-flush-temp' $tempReplay.nextOperationId 'temp crash resumes after committed temp checkpoint'
    $tempCollisionObservation = Copy-Value $tempObservation
    $tempCollisionObservation.archiveTempSha256 = ('9' * 64)
    $tempCollision = Get-AwxRetirementReplayPlan -ItemPlan $itemPlan -JournalEntry $tempJournal -Observation $tempCollisionObservation
    Assert-Equal 'fail-closed' $tempCollision.action 'mismatched temp collision fails closed'
    Assert-Equal 'archive-temp-collision' $tempCollision.reason 'temp collision reason'

    $stageProgress = Advance-TestCursor -Assessment $confirmed -Plan $itemPlan -Cursor $cursor -Fixture $fixture -ThroughOperationId 'checkpoint-stage-renamed'
    $stageJournal = ConvertTo-AwxExecutionJournalEntry -Assessment $confirmed -Plan $itemPlan -Cursor $stageProgress.cursor
    $stageObservation = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.retirement-observation.v1'
        sourceExists = $false; stageExists = $true; archiveTempExists = $false; archiveExists = $true
        sourceSha256 = $null; stageSha256 = $one.expectedSha256; archiveTempSha256 = $null; archiveSha256 = $one.expectedSha256
        canonicalExists = $true
    }
    $stageReplay = Get-AwxRetirementReplayPlan -ItemPlan $itemPlan -JournalEntry $stageJournal -Observation $stageObservation
    Assert-Equal 'stage-rehash' $stageReplay.nextOperationId 'staged crash resumes at revalidation'
    $restoreObservation = Copy-Value $stageObservation
    $restoreObservation.archiveSha256 = ('9' * 64)
    $restore = Get-AwxRetirementReplayPlan -ItemPlan $itemPlan -JournalEntry $stageJournal -Observation $restoreObservation
    Assert-Equal 'restore-staged-source' $restore.action 'bad archive restores only staged source'
    Assert-True ([bool]$restore.onlyIfOriginalAbsent) 'restore is conditional on original absence'
    Assert-False ([bool]$restore.recursive) 'restore remains exact-leaf only'
    $collisionObservation = Copy-Value $stageObservation
    $collisionObservation.sourceExists = $true
    $collisionObservation.sourceSha256 = $one.expectedSha256
    $collision = Get-AwxRetirementReplayPlan -ItemPlan $itemPlan -JournalEntry $stageJournal -Observation $collisionObservation
    Assert-Equal 'retirement-stage-collision' $collision.reason 'source/stage collision fails closed'

    $deleteIntentProgress = Advance-TestCursor -Assessment $confirmed -Plan $itemPlan -Cursor $cursor -Fixture $fixture -ThroughOperationId 'checkpoint-delete-intent'
    $deleteIntentJournal = ConvertTo-AwxExecutionJournalEntry -Assessment $confirmed -Plan $itemPlan -Cursor $deleteIntentProgress.cursor
    $changedStageDispatch = New-DispatchFixture -StageDeletePreimageHashOverride ('9' * 64)
    $deleteCursorIndexBefore = [long]$deleteIntentProgress.cursor.nextIndex
    Assert-ThrowsReason -Reason 'callback-preimage-mismatch' -Message 'changed staged preimage cannot be hidden by post-delete absence' -Action {
        Invoke-AwxExecutionCursorStep -Assessment $confirmed -Plan $itemPlan -Cursor $deleteIntentProgress.cursor -Callbacks $changedStageDispatch.callbacks `
            -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -NowUtc $fixture.now | Out-Null
    }
    Assert-Equal $deleteCursorIndexBefore $deleteIntentProgress.cursor.nextIndex 'rejected changed preimage does not advance the cursor'
    Assert-Equal @('stage-delete-exact') @($changedStageDispatch.context.calls) 'changed preimage attempt dispatches only the exact delete operation'
    $postDeleteObservation = Copy-Value $stageObservation
    $postDeleteObservation.stageExists = $false
    $postDeleteObservation.stageSha256 = $null
    $postDeleteReplay = Get-AwxRetirementReplayPlan -ItemPlan $itemPlan -JournalEntry $deleteIntentJournal -Observation $postDeleteObservation
    Assert-Equal 'resume' $postDeleteReplay.action 'delete-before-journal crash is recoverable'
    Assert-Equal 'checkpoint-delete-observed' $postDeleteReplay.nextOperationId 'post-delete absence resumes at observation checkpoint'

    $completedEntries = @()
    foreach ($eligible in @($confirmed.items | Where-Object { $_.disposition -ceq 'eligible' })) {
        $plan = New-AwxRetirementItemPlan -Assessment $confirmed -Path $eligible.path
        $startCursor = New-TestCursor -Assessment $confirmed -Plan $plan -Fixture $fixture
        $completed = Advance-TestCursor -Assessment $confirmed -Plan $plan -Cursor $startCursor -Fixture $fixture
        Assert-True ([bool]$completed.cursor.completed) "retirement cursor completes $($eligible.path)"
        $completedEntries += ConvertTo-AwxExecutionJournalEntry -Assessment $confirmed -Plan $plan -Cursor $completed.cursor
    }
    $journal = New-AwxRetirementJournal -Assessment $confirmed -Entries $completedEntries
    Assert-True ([bool]$journal.complete) 'journal completion is derived from completed cursors'
    Assert-True ([string]$journal.journalDigest -cmatch '^[0-9A-F]{64}$') 'journal is digest-bound'

    $payloads = @(
        [pscustomobject]@{ name = 'retirement-manifest.json'; sourcePath = 'data/agent-handoff/notebook/consolidated/prepared/retire-20260807-a/retirement-manifest.json'; sha256 = ('4' * 64); bytes = 100 },
        [pscustomobject]@{ name = 'release-manifest.json'; sourcePath = 'data/agent-handoff/notebook/consolidated/prepared/retire-20260807-a/release-manifest.json'; sha256 = ('5' * 64); bytes = 200 },
        [pscustomobject]@{ name = 'CHANGELOG.md'; sourcePath = 'data/agent-handoff/notebook/consolidated/prepared/retire-20260807-a/CHANGELOG.md'; sha256 = ('6' * 64); bytes = 300 },
        [pscustomobject]@{ name = 'tag-proposal.txt'; sourcePath = 'data/agent-handoff/notebook/consolidated/prepared/retire-20260807-a/tag-proposal.txt'; sha256 = ('7' * 64); bytes = 80 }
    )
    $readbacks = New-ReadbackFixture -Assessment $confirmed -Payloads $payloads
    $release = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $journal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $readbacks.adapters
    Assert-False ([bool]$release.authorityGranted) 'release plan grants no authority'
    Assert-True ([bool]$release.readyForTrustedAdapter) 'verified readbacks allow release plan'
    Assert-True ([string]$release.planDigest -cmatch '^[0-9A-F]{64}$') 'release plan is digest-bound'
    Assert-False ([bool]$release.gitMutationPlanned) 'release never plans Git mutation'
    Assert-True ([bool]$release.tagProposalOnly) 'tag output is proposal only'
    Assert-Equal 6 $readbacks.context.calls.Count 'two archives and four payloads are re-read exactly'
    Assert-Equal @(
        'data/agent-handoff/notebook/consolidated/releases/retire-20260807-a/retirement-manifest.json',
        'data/agent-handoff/notebook/consolidated/releases/retire-20260807-a/release-manifest.json',
        'data/agent-handoff/notebook/consolidated/releases/retire-20260807-a/CHANGELOG.md',
        'data/agent-handoff/notebook/consolidated/releases/retire-20260807-a/tag-proposal.txt'
    ) @($release.payloads | ForEach-Object { $_.path }) 'release outputs have fixed paths'
    $lastReleaseOperation = @($release.operations)[@($release.operations).Count - 1]
    Assert-Equal 'ready-rename-final' $lastReleaseOperation.operationId 'ready marker rename is last'
    Assert-False ([bool]$lastReleaseOperation.checkpointAfter) 'ready rename never implies a later checkpoint'
    Assert-Equal 'data/agent-handoff/notebook/consolidated/releases/retire-20260807-a/retire-20260807-a.ready' $lastReleaseOperation.finalPath 'ready marker path is fixed'
    Assert-Equal ('5' * 64) $release.readyMarker.releaseManifestSha256 'ready content carries release-manifest hash'

    $releaseCursor = New-TestCursor -Assessment $confirmed -Plan $release -Fixture $fixture -Readbacks $readbacks.adapters
    $releaseCompleted = Advance-TestCursor -Assessment $confirmed -Plan $release -Cursor $releaseCursor -Fixture $fixture -Readbacks $readbacks.adapters
    Assert-True ([bool]$releaseCompleted.cursor.completed) 'release cursor completes at ready rename'
    Assert-Equal 'ready-rename-final' $releaseCompleted.cursor.lastOperationId 'ready rename is terminal cursor operation'
    $afterReadyDispatch = New-DispatchFixture
    Assert-ThrowsReason -Reason 'cursor-complete' -Message 'no operation can run after ready marker' -Action {
        Invoke-AwxExecutionCursorStep -Assessment $confirmed -Plan $release -Cursor $releaseCompleted.cursor -Callbacks $afterReadyDispatch.callbacks `
            -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -NowUtc $fixture.now -Readbacks $readbacks.adapters | Out-Null
    }
    Assert-Equal 0 $afterReadyDispatch.context.calls.Count 'completed ready cursor dispatches nothing'

    $forgedJournal = Copy-Value $journal
    $forgedJournal.items[0].planDigest = ('9' * 64)
    $forgedJournal.complete = $true
    $forgedJournalRelease = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $forgedJournal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $readbacks.adapters
    Assert-Contains $forgedJournalRelease.reasonCodes 'retirement-journal-binding-invalid' 'forged complete journal is rejected'
    Assert-Equal 0 @($forgedJournalRelease.operations).Count 'forged journal exposes zero operations'

    $forgedReleaseAssessment = Copy-Value $confirmed
    $forgedReleaseAssessment.readyForTrustedAdapter = $false
    $forgedRelease = New-AwxConsolidatedReleasePlan -Assessment $forgedReleaseAssessment -RetirementJournal $journal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $readbacks.adapters
    Assert-Contains $forgedRelease.reasonCodes 'assessment-binding-invalid' 'release rejects forged assessment boolean'

    $badPayloadReadback = New-ReadbackFixture -Assessment $confirmed -Payloads $payloads
    $badPayloadReadback.context.payloadHashOverride = ('9' * 64)
    $badPayloadRelease = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $journal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $badPayloadReadback.adapters
    Assert-Contains $badPayloadRelease.reasonCodes 'release-payload-readback-mismatch' 'payload descriptor cannot forge readback hash'

    foreach ($archiveCase in @(
        [pscustomobject]@{ property = 'archiveMissing'; reason = 'release-archive-readback-unsafe' },
        [pscustomobject]@{ property = 'archiveNonLeaf'; reason = 'release-archive-readback-unsafe' },
        [pscustomobject]@{ property = 'archiveReparse'; reason = 'release-archive-readback-unsafe' }
    )) {
        $badArchiveReadback = New-ReadbackFixture -Assessment $confirmed -Payloads $payloads
        $badArchiveReadback.context.($archiveCase.property) = $true
        $badArchiveRelease = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $journal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $badArchiveReadback.adapters
        Assert-Contains $badArchiveRelease.reasonCodes $archiveCase.reason "$($archiveCase.property) archive readback blocks release"
    }

    $staleReleaseStability = Copy-Value $fixture.stability
    $staleReleaseStability.capturedAtUtc = $fixture.now.AddMinutes(-2).ToString('o')
    $staleRelease = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $journal -StabilitySnapshot $staleReleaseStability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $readbacks.adapters
    Assert-Contains $staleRelease.reasonCodes 'stability-snapshot-stale' 'release requires fresh stability snapshot'

    $overwritePath = 'data/agent-handoff/notebook/consolidated/releases/retire-20260807-a/CHANGELOG.md'
    $overwriteRelease = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $journal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $payloads -ExistingPaths @($overwritePath) -NowUtc $fixture.now -Readbacks $readbacks.adapters
    Assert-Contains $overwriteRelease.reasonCodes 'release-output-exists' 'overwrite reason'

    $missingPayloads = @($payloads | Where-Object { $_.name -cne 'tag-proposal.txt' })
    $missingPayloadReadbacks = New-ReadbackFixture -Assessment $confirmed -Payloads $payloads
    $missingPayloadRelease = New-AwxConsolidatedReleasePlan -Assessment $confirmed -RetirementJournal $journal -StabilitySnapshot $fixture.stability -GateSnapshot $fixture.gates -Payloads $missingPayloads -ExistingPaths @() -NowUtc $fixture.now -Readbacks $missingPayloadReadbacks.adapters
    Assert-Contains $missingPayloadRelease.reasonCodes 'release-payload-incomplete' 'all fixed release payloads are required'

    Write-Output "PASS: $script:AssertionCount assertions"
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}

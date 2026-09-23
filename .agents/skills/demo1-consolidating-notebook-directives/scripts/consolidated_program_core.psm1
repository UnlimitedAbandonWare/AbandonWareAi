Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:CanonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$script:CanonicalHashPattern = '^[A-F0-9]{64}$'
$script:IdentifierPattern = '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'
$script:WorkUnitPattern = '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'
$script:EvidenceLimit = 1MB
$script:ArtifactLimit = 16MB
$script:TargetLimit = 256
$script:CommandLimit = 64
$script:PathLimit = 512
$script:AdoptFreshnessLimitSeconds = 7200
$script:AdoptFutureSkewSeconds = 300

function Test-CoreProperty {
    param($Value, [string]$Name)
    if ($null -eq $Value) { return $false }
    return $null -ne $Value.PSObject.Properties[$Name]
}

function Get-CoreProperty {
    param($Value, [string]$Name, [string]$Reason)
    if (-not (Test-CoreProperty $Value $Name)) { throw $Reason }
    return $Value.PSObject.Properties[$Name].Value
}

function Set-CoreProperty {
    param($Value, [string]$Name, $PropertyValue)
    if (Test-CoreProperty $Value $Name) { $Value.PSObject.Properties[$Name].Value = $PropertyValue }
    else { $Value | Add-Member -NotePropertyName $Name -NotePropertyValue $PropertyValue }
}

function Remove-CoreProperty {
    param($Value, [string]$Name)
    if (Test-CoreProperty $Value $Name) { $Value.PSObject.Properties.Remove($Name) }
}

function Copy-CoreValue {
    param($Value)
    $copy = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $Value -Depth 40 -Compress)
    if ($Value -is [Array]) { return ,@($copy) }
    return $copy
}

function Assert-CoreExactProperties {
    param($Value, [string[]]$Names, [string]$Reason)
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [Array] -or $Value -is [ValueType]) { throw $Reason }
    $actual = @($Value.PSObject.Properties | ForEach-Object { $_.Name })
    if ($actual.Count -ne $Names.Count) { throw $Reason }
    foreach ($name in $Names) {
        if ($actual -cnotcontains $name) { throw $Reason }
    }
}

function Assert-CoreHash {
    param($Value, [string]$Reason)
    if ($Value -isnot [string] -or $Value -cnotmatch $script:CanonicalHashPattern) { throw $Reason }
}

function Test-CoreInteger {
    param($Value)
    return $Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or
        $Value -is [uint16] -or $Value -is [uint32] -or $Value -is [uint64]
}

function Assert-CoreRunId {
    param([AllowEmptyString()][string]$RunId)
    if ([string]::IsNullOrWhiteSpace($RunId) -or $RunId -cnotmatch $script:IdentifierPattern) { throw 'invalid-run-id' }
}

function Assert-CoreRelativePath {
    param($Path, [string]$Reason)
    if ($Path -isnot [string] -or [string]::IsNullOrWhiteSpace($Path) -or $Path.Length -gt $script:PathLimit) { throw $Reason }
    if ([IO.Path]::IsPathRooted($Path) -or $Path.StartsWith('\') -or $Path.StartsWith('/') -or $Path.Contains(':')) { throw $Reason }
    if ($Path.IndexOfAny([char[]]'*?[]') -ge 0) { throw $Reason }
    foreach ($segment in @($Path -split '[\\/]')) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -ceq '.' -or $segment -ceq '..') { throw $Reason }
    }
    return [string]$Path
}

function Get-CoreAdapter {
    param($Adapters, [string]$Name)
    if ($null -eq $Adapters -or -not (Test-CoreProperty $Adapters $Name)) { throw 'adapter-missing' }
    $adapter = Get-CoreProperty $Adapters $Name 'adapter-missing'
    if ($adapter -isnot [scriptblock]) { throw 'adapter-invalid' }
    return $adapter
}

function Assert-CoreStateIdentity {
    param($State, [string]$StateSha256, [string]$CanonicalMarkdownSha256)
    Assert-CoreHash $StateSha256 'invalid-state-hash'
    Assert-CoreHash $CanonicalMarkdownSha256 'invalid-canonical-hash'
    if ($null -eq $State -or -not (Test-CoreProperty $State 'canonicalMarkdownSha256') -or
        [string]$State.canonicalMarkdownSha256 -cne $CanonicalMarkdownSha256) { throw 'canonical-hash-changed' }
    if (-not (Test-CoreProperty $State 'workUnits') -or $State.workUnits -isnot [Array] -or @($State.workUnits).Count -gt 128) { throw 'invalid-work-units' }
}

function Assert-CoreUnitTargets {
    param($Unit)
    if (-not (Test-CoreProperty $Unit 'targetFiles') -or $Unit.targetFiles -isnot [Array]) { throw 'missing-target-files' }
    if (@($Unit.targetFiles).Count -gt $script:TargetLimit) { throw 'too-many-target-files' }
    $seen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in @($Unit.targetFiles)) {
        $exact = Assert-CoreRelativePath $path 'invalid-target-file'
        if (-not $seen.Add($exact)) { throw 'duplicate-target-file' }
    }
}

function Get-CoreUnit {
    param($State, [string]$WorkUnitId)
    if ([string]::IsNullOrWhiteSpace($WorkUnitId) -or $WorkUnitId -cnotmatch $script:WorkUnitPattern) { throw 'invalid-work-unit-id' }
    $matches = @($State.workUnits | Where-Object { (Test-CoreProperty $_ 'workUnitId') -and [string]$_.workUnitId -ceq $WorkUnitId })
    if ($matches.Count -ne 1) { throw 'unknown-work-unit-id' }
    return $matches[0]
}

function Get-CoreRunnableUnits {
    param($State)
    $runnable = @()
    foreach ($unit in @($State.workUnits)) {
        if (-not (Test-CoreProperty $unit 'workUnitId') -or [string]$unit.workUnitId -cnotmatch $script:WorkUnitPattern) { throw 'invalid-work-unit-id' }
        Assert-CoreUnitTargets $unit
        if (-not (Test-CoreProperty $unit 'status') -or [string]$unit.status -cne 'pending') { continue }
        if (-not (Test-CoreProperty $unit 'dependencies') -or $unit.dependencies -isnot [Array]) { throw 'invalid-dependencies' }
        $blocked = $false
        foreach ($dependencyId in @($unit.dependencies)) {
            if ($dependencyId -isnot [string]) { throw 'invalid-dependencies' }
            $dependency = Get-CoreUnit $State $dependencyId
            if ([string]$dependency.status -cnotin @('green', 'no_patch_needed')) { $blocked = $true; break }
        }
        if (-not $blocked) { $runnable += ,$unit }
    }
    return @($runnable)
}

function Assert-CoreNoOtherActiveUnit {
    param($State, [AllowNull()][string]$SelectedId = $null, [AllowNull()][string]$RunId = $null, [switch]$AllowPendingTransition)
    foreach ($unit in @($State.workUnits)) {
        $isSelectedReplay = $false
        if ($null -ne $SelectedId -and [string]$unit.workUnitId -ceq $SelectedId -and [string]$unit.status -ceq 'running' -and
            (Test-CoreProperty $unit 'execution') -and [string]$unit.execution.runId -ceq $RunId -and -not (Test-CoreProperty $unit 'transition')) {
            $isSelectedReplay = $true
        }
        if ($AllowPendingTransition -and $null -ne $SelectedId -and [string]$unit.workUnitId -ceq $SelectedId -and
            [string]$unit.status -ceq 'pending' -and (Test-CoreProperty $unit 'transition') -and
            [string]$unit.transition.runId -ceq $RunId) {
            $isSelectedReplay = $true
        }
        if (-not $isSelectedReplay -and ([string]$unit.status -ceq 'running' -or (Test-CoreProperty $unit 'transition'))) { throw 'another-unit-active' }
    }
}

function Get-CoreTopic {
    param([string]$CanonicalMarkdownSha256, [string]$WorkUnitId)
    $topic = 'consolidated-' + $CanonicalMarkdownSha256.Substring(0, 12).ToLowerInvariant() + '-' + $WorkUnitId
    if ($topic.Length -gt 96) { throw 'lease-topic-too-long' }
    return $topic
}

function Invoke-CoreCas {
    param($Adapters, [string]$ExpectedHash, $State, [string]$Phase)
    $callback = Get-CoreAdapter $Adapters 'CompareAndSwapState'
    $result = & $callback $ExpectedHash (Copy-CoreValue $State) $Phase
    $hash = if ($result -is [string]) { $result } elseif ($null -ne $result -and (Test-CoreProperty $result 'stateHash')) { [string]$result.stateHash } else { '' }
    Assert-CoreHash $hash 'invalid-cas-result'
    return $hash
}

function Assert-CoreLease {
    param($Envelope, [string]$Topic, [string]$OwnerId)
    $status = Assert-CoreLeaseIdentity $Envelope $Topic $OwnerId
    if ($status -cne 'active') { throw 'lease-not-active' }
}

function Assert-CoreLeaseIdentity {
    param($Envelope, [string]$Topic, [string]$OwnerId)
    Assert-CoreExactProperties $Envelope @('status', 'lease') 'invalid-lease-schema'
    if ([string]$Envelope.status -cnotin @('active','expiring','expired')) { throw 'lease-not-active' }
    $lease = $Envelope.lease
    Assert-CoreExactProperties $lease @('schemaVersion', 'generatedAt', 'startedAtUtc', 'topic', 'role', 'ownerId', 'root', 'expiresAtUtc', 'expiresAt', 'mutationAllowed') 'invalid-lease-schema'
    if ([string]$lease.schemaVersion -cne 'awx.source_edit_session.lease.v1') { throw 'invalid-lease-schema' }
    foreach ($timestampName in @('generatedAt', 'startedAtUtc', 'expiresAtUtc', 'expiresAt')) {
        if ($lease.$timestampName -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$lease.$timestampName)) { throw 'invalid-lease-schema' }
    }
    if ([string]$lease.topic -cne $Topic) { throw 'lease-topic-mismatch' }
    if ([string]$lease.role -cne 'desktop') { throw 'lease-role-mismatch' }
    if ([string]$lease.ownerId -cne $OwnerId) { throw 'lease-owner-mismatch' }
    if ([string]$lease.root -cne $script:CanonicalRoot) { throw 'lease-root-mismatch' }
    if ($lease.mutationAllowed -isnot [bool] -or -not $lease.mutationAllowed) { throw 'lease-mutation-not-allowed' }
    return [string]$Envelope.status
}

function Test-CoreExactLeaseAbsent {
    param($Adapters, $Request)
    $adapter = Get-CoreAdapter $Adapters 'TestLeaseAbsent'
    $envelope = & $adapter $Request
    Assert-CoreExactProperties $envelope @('isAbsent', 'topic', 'ownerId', 'root') 'invalid-lease-absence-envelope'
    if ($envelope.isAbsent -isnot [bool] -or [string]$envelope.topic -cne [string]$Request.topic -or
        [string]$envelope.ownerId -cne [string]$Request.ownerId -or [string]$envelope.root -cne $script:CanonicalRoot) {
        throw 'invalid-lease-absence-envelope'
    }
    return [bool]$envelope.isAbsent
}

function Assert-CoreImageRows {
    param($Rows, [string[]]$TargetPaths, [string]$Reason)
    if ($Rows -isnot [Array] -or @($Rows).Count -ne $TargetPaths.Count) { throw $Reason }
    for ($index = 0; $index -lt $TargetPaths.Count; $index++) {
        $row = $Rows[$index]
        Assert-CoreExactProperties $row @('path', 'exists', 'bytes', 'sha256', 'resolvedPathContained', 'ancestorNonReparse', 'reparseTraversal', 'leafKind') $Reason
        if ($row.path -isnot [string] -or [string]$row.path -cne $TargetPaths[$index] -or $row.exists -isnot [bool] -or
            -not (Test-CoreInteger $row.bytes) -or [int64]$row.bytes -lt 0 -or [int64]$row.bytes -gt $script:ArtifactLimit) { throw $Reason }
        if ($row.resolvedPathContained -isnot [bool] -or $row.ancestorNonReparse -isnot [bool] -or $row.reparseTraversal -isnot [bool] -or
            -not $row.resolvedPathContained -or -not $row.ancestorNonReparse -or $row.reparseTraversal -or $row.leafKind -isnot [string]) { throw $Reason }
        if ($row.exists) {
            Assert-CoreHash $row.sha256 $Reason
            if ([string]$row.leafKind -cne 'leaf') { throw $Reason }
        } elseif ([int64]$row.bytes -ne 0 -or $null -ne $row.sha256 -or [string]$row.leafKind -cne 'missing') { throw $Reason }
    }
}

function Get-CoreTargetSnapshotRows {
    param($Envelope, [string[]]$TargetPaths)
    Assert-CoreExactProperties $Envelope @('schemaVersion', 'canonicalRoot', 'rows') 'invalid-target-snapshot'
    if ([string]$Envelope.schemaVersion -cne 'awx.notebook.directive.target-snapshot.v1' -or
        [string]$Envelope.canonicalRoot -cne $script:CanonicalRoot) { throw 'invalid-target-snapshot' }
    Assert-CoreImageRows $Envelope.rows $TargetPaths 'invalid-target-snapshot'
    return @($Envelope.rows)
}

function Test-CoreSameImages {
    param($Left, $Right)
    if (@($Left).Count -ne @($Right).Count) { return $false }
    for ($index = 0; $index -lt @($Left).Count; $index++) {
        foreach ($name in @('path', 'exists', 'bytes', 'sha256', 'resolvedPathContained', 'ancestorNonReparse', 'reparseTraversal', 'leafKind')) {
            $a = $Left[$index].PSObject.Properties[$name].Value
            $b = $Right[$index].PSObject.Properties[$name].Value
            if ($a -is [string] -or $b -is [string]) { if ([string]$a -cne [string]$b) { return $false } }
            elseif ($a -ne $b) { return $false }
        }
    }
    return $true
}

function Get-CoreImageArrayHash {
    param($Adapters, $Rows)
    $adapter = Get-CoreAdapter $Adapters 'HashImageArray'
    $hash = & $adapter (Copy-CoreValue @($Rows))
    Assert-CoreHash $hash 'invalid-target-postimage-hash'
    return [string]$hash
}

function Get-CoreSessionRequest {
    param([string]$Topic, [string]$RunId)
    return [pscustomobject][ordered]@{ action = 'begin'; role = 'desktop'; root = $script:CanonicalRoot; topic = $Topic; ownerId = $RunId; ttlMinutes = 180 }
}

function Invoke-CoreBeginCleanup {
    param($Adapters, $IntentState, [string]$IntentHash, $Unit, [string]$Topic, [string]$RunId, [string]$OriginalReason)
    $recovery = Copy-CoreValue $IntentState
    $recoveryUnit = Get-CoreUnit $recovery ([string]$Unit.workUnitId)
    Set-CoreProperty $recoveryUnit 'transition' ([pscustomobject][ordered]@{
        phase = 'begin-recovery-required'; runId = $RunId; owner = 'desktop'; leaseTopic = $Topic
    })
    try { $recoveryHash = Invoke-CoreCas $Adapters $IntentHash $recovery 'begin-recovery-required' }
    catch { throw $OriginalReason }

    $request = Get-CoreSessionRequest $Topic $RunId
    $request.action = 'end'
    $cleanupProved = $false
    try {
        $release = Get-CoreAdapter $Adapters 'ReleaseSession'
        $null = & $release $request
        $cleanupProved = Test-CoreExactLeaseAbsent $Adapters $request
    } catch { $cleanupProved = $false }

    if ($cleanupProved) {
        $restored = Copy-CoreValue $recovery
        $restoredUnit = Get-CoreUnit $restored ([string]$Unit.workUnitId)
        Remove-CoreProperty $restoredUnit 'transition'
        try { $null = Invoke-CoreCas $Adapters $recoveryHash $restored 'begin-cleanup-complete' }
        catch { throw 'begin-cleanup-unproven' }
        throw $OriginalReason
    }
    throw 'begin-cleanup-unproven'
}

function Set-CoreBeginRecoveryRequired {
    param($Adapters, $IntentState, [string]$IntentHash, $Unit, [string]$Topic, [string]$RunId)
    $recovery = Copy-CoreValue $IntentState
    $recoveryUnit = Get-CoreUnit $recovery ([string]$Unit.workUnitId)
    Set-CoreProperty $recoveryUnit 'transition' ([pscustomobject][ordered]@{
        phase = 'begin-recovery-required'; runId = $RunId; owner = 'desktop'; leaseTopic = $Topic
    })
    try { $null = Invoke-CoreCas $Adapters $IntentHash $recovery 'begin-recovery-required' } catch {}
}

function Invoke-CoreCompleteBeginIntent {
    param(
        $Adapters, $IntentState, [string]$IntentHash, $IntentUnit,
        [string]$Topic, [string]$RunId, [bool]$AcquireLease
    )
    try {
        if ($AcquireLease) {
            $acquire = Get-CoreAdapter $Adapters 'AcquireSession'
            $leaseEnvelope = & $acquire (Get-CoreSessionRequest $Topic $RunId)
            Assert-CoreLease $leaseEnvelope $Topic $RunId
        }
        $capture = Get-CoreAdapter $Adapters 'CaptureTargets'
        $targetPaths = @($IntentUnit.targetFiles | ForEach-Object { [string]$_ })
        $snapshotEnvelope = & $capture ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; paths = $targetPaths; mode = 'preimage' })
        $preimages = @(Get-CoreTargetSnapshotRows $snapshotEnvelope $targetPaths)

        $readSession = Get-CoreAdapter $Adapters 'ReadSession'
        $activeRequest = Get-CoreSessionRequest $Topic $RunId
        $activeRequest.action = 'status'
        Assert-CoreLease (& $readSession $activeRequest) $Topic $RunId

        $runningState = Copy-CoreValue $IntentState
        $runningUnit = Get-CoreUnit $runningState ([string]$IntentUnit.workUnitId)
        $runningUnit.status = 'running'
        Set-CoreProperty $runningUnit 'execution' ([pscustomobject][ordered]@{
            runId = $RunId; owner = 'desktop'; leaseTopic = $Topic; canonicalMarkdownSha256 = [string]$IntentState.canonicalMarkdownSha256
            targetPreimages = Copy-CoreValue $preimages
        })
        Remove-CoreProperty $runningUnit 'transition'
        return Invoke-CoreCas $Adapters $IntentHash $runningState 'running'
    } catch {
        $reason = [string]$_.Exception.Message
        if ($reason -cnotmatch '^[a-z0-9-]+$') { $reason = 'begin-failed' }
        Invoke-CoreBeginCleanup $Adapters $IntentState $IntentHash $IntentUnit $Topic $RunId $reason
    }
}

function Invoke-ConsolidatedProgramBeginCore {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$State,
        [Parameter(Mandatory = $true)][string]$StateSha256,
        [Parameter(Mandatory = $true)][string]$CanonicalMarkdownSha256,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RunId,
        [AllowNull()][string]$WorkUnitId,
        [Parameter(Mandatory = $true)]$Adapters
    )

    Assert-CoreRunId $RunId
    Assert-CoreStateIdentity $State $StateSha256 $CanonicalMarkdownSha256

    if (-not [string]::IsNullOrWhiteSpace($WorkUnitId)) {
        $existing = Get-CoreUnit $State $WorkUnitId
        if ([string]$existing.status -ceq 'running' -and (Test-CoreProperty $existing 'execution') -and
            [string]$existing.execution.runId -ceq $RunId -and -not (Test-CoreProperty $existing 'transition')) {
            Assert-CoreNoOtherActiveUnit $State $WorkUnitId $RunId
            $existingTopic = Get-CoreTopic $CanonicalMarkdownSha256 $WorkUnitId
            if ([string]$existing.execution.owner -cne 'desktop' -or [string]$existing.execution.leaseTopic -cne $existingTopic -or
                [string]$existing.execution.canonicalMarkdownSha256 -cne $CanonicalMarkdownSha256) { throw 'invalid-running-execution' }
            $readSession = Get-CoreAdapter $Adapters 'ReadSession'
            $leaseEnvelope = & $readSession ([pscustomobject][ordered]@{ action = 'status'; role = 'desktop'; root = $script:CanonicalRoot; topic = $existingTopic; ownerId = $RunId; ttlMinutes = 180 })
            Assert-CoreLease $leaseEnvelope $existingTopic $RunId
            return [pscustomobject][ordered]@{ action = 'Begin'; status = 'running'; reason = 'already-running'; workUnitId = $WorkUnitId; leaseTopic = $existingTopic; stateSha256 = $StateSha256 }
        }
    }

    $transitionUnits = @($State.workUnits | Where-Object { Test-CoreProperty $_ 'transition' })
    if ($transitionUnits.Count -gt 0) {
        if ($transitionUnits.Count -ne 1) { throw 'another-unit-active' }
        $resumeUnit = $transitionUnits[0]
        $resumeId = [string]$resumeUnit.workUnitId
        if (-not [string]::IsNullOrWhiteSpace($WorkUnitId) -and $resumeId -cne $WorkUnitId) { throw 'another-unit-active' }
        if ([string]$resumeUnit.status -cne 'pending' -or (Test-CoreProperty $resumeUnit 'execution') -or (Test-CoreProperty $resumeUnit 'record')) {
            throw 'invalid-begin-transition'
        }
        $phase = [string]$resumeUnit.transition.phase
        $resumeTopic = Get-CoreTopic $CanonicalMarkdownSha256 $resumeId
        if ($phase -ceq 'begin-intent') {
            Assert-CoreExactProperties $resumeUnit.transition @('phase','runId','owner','leaseTopic','canonicalMarkdownSha256') 'invalid-begin-transition'
            if ([string]$resumeUnit.transition.canonicalMarkdownSha256 -cne $CanonicalMarkdownSha256) { throw 'invalid-begin-transition' }
        } elseif ($phase -ceq 'begin-recovery-required') {
            Assert-CoreExactProperties $resumeUnit.transition @('phase','runId','owner','leaseTopic') 'invalid-begin-transition'
        } else { throw 'another-unit-active' }
        if ([string]$resumeUnit.transition.runId -cne $RunId -or [string]$resumeUnit.transition.owner -cne 'desktop' -or
            [string]$resumeUnit.transition.leaseTopic -cne $resumeTopic) { throw 'invalid-begin-transition' }
        Assert-CoreNoOtherActiveUnit $State $resumeId $RunId -AllowPendingTransition
        $resumeRunnable = @(Get-CoreRunnableUnits $State)
        if ($resumeRunnable.Count -eq 0 -or [string]$resumeRunnable[0].workUnitId -cne $resumeId) { throw 'work-unit-not-first-runnable' }

        $absenceRequest = Get-CoreSessionRequest $resumeTopic $RunId
        $absenceRequest.action = 'end'
        $leaseAbsent = Test-CoreExactLeaseAbsent $Adapters $absenceRequest
        $leaseStatus = 'absent'
        if (-not $leaseAbsent) {
            $readSession = Get-CoreAdapter $Adapters 'ReadSession'
            $readRequest = Get-CoreSessionRequest $resumeTopic $RunId
            $readRequest.action = 'status'
            $leaseStatus = Assert-CoreLeaseIdentity (& $readSession $readRequest) $resumeTopic $RunId
        }

        $reuseActiveLease = $phase -ceq 'begin-intent' -and $leaseStatus -ceq 'active'
        $mustRelease = -not $leaseAbsent -and ($phase -ceq 'begin-recovery-required' -or $leaseStatus -cin @('expiring','expired'))
        if ($mustRelease) {
            if ($phase -ceq 'begin-intent') {
                $recoveryState = Copy-CoreValue $State
                $recoveryUnit = Get-CoreUnit $recoveryState $resumeId
                Set-CoreProperty $recoveryUnit 'transition' ([pscustomobject][ordered]@{
                    phase = 'begin-recovery-required'; runId = $RunId; owner = 'desktop'; leaseTopic = $resumeTopic
                })
                $StateSha256 = Invoke-CoreCas $Adapters $StateSha256 $recoveryState 'begin-recovery-required'
                $State = $recoveryState
                $resumeUnit = $recoveryUnit
                $phase = 'begin-recovery-required'
            } else {
                $StateSha256 = Invoke-CoreCas $Adapters $StateSha256 (Copy-CoreValue $State) 'begin-recovery-required'
            }
            $releaseProved = $false
            try {
                $release = Get-CoreAdapter $Adapters 'ReleaseSession'
                $null = & $release $absenceRequest
                $releaseProved = Test-CoreExactLeaseAbsent $Adapters $absenceRequest
            } catch { $releaseProved = $false }
            if (-not $releaseProved) {
                throw 'begin-cleanup-unproven'
            }
            $leaseAbsent = $true
        }

        if ($phase -ceq 'begin-recovery-required') {
            $intentState = Copy-CoreValue $State
            $intentUnit = Get-CoreUnit $intentState $resumeId
            Set-CoreProperty $intentUnit 'transition' ([pscustomobject][ordered]@{
                phase = 'begin-intent'; runId = $RunId; owner = 'desktop'; leaseTopic = $resumeTopic; canonicalMarkdownSha256 = $CanonicalMarkdownSha256
            })
            $intentHash = Invoke-CoreCas $Adapters $StateSha256 $intentState 'begin-intent'
        } else {
            $intentState = Copy-CoreValue $State
            $intentUnit = Get-CoreUnit $intentState $resumeId
            $intentHash = $StateSha256
        }
        $runningHash = Invoke-CoreCompleteBeginIntent $Adapters $intentState $intentHash $intentUnit $resumeTopic $RunId (-not $reuseActiveLease)
        return [pscustomobject][ordered]@{
            action = 'Begin'; status = 'running'; reason = 'work-unit-begun'; workUnitId = $resumeId
            leaseTopic = $resumeTopic; stateSha256 = $runningHash
        }
    }

    Assert-CoreNoOtherActiveUnit $State
    $runnable = @(Get-CoreRunnableUnits $State)
    if ($runnable.Count -eq 0) { throw 'no-runnable-work-unit' }
    $selected = $runnable[0]
    if (-not [string]::IsNullOrWhiteSpace($WorkUnitId) -and [string]$selected.workUnitId -cne $WorkUnitId) { throw 'work-unit-not-first-runnable' }
    $selectedId = [string]$selected.workUnitId
    $topic = Get-CoreTopic $CanonicalMarkdownSha256 $selectedId

    $intentState = Copy-CoreValue $State
    $intentUnit = Get-CoreUnit $intentState $selectedId
    Set-CoreProperty $intentUnit 'transition' ([pscustomobject][ordered]@{
        phase = 'begin-intent'; runId = $RunId; owner = 'desktop'; leaseTopic = $topic; canonicalMarkdownSha256 = $CanonicalMarkdownSha256
    })
    $intentHash = Invoke-CoreCas $Adapters $StateSha256 $intentState 'begin-intent'
    $runningHash = Invoke-CoreCompleteBeginIntent $Adapters $intentState $intentHash $intentUnit $topic $RunId $true

    return [pscustomobject][ordered]@{
        action = 'Begin'; status = 'running'; reason = 'work-unit-begun'; workUnitId = $selectedId
        leaseTopic = $topic; stateSha256 = $runningHash
    }
}

function Read-CoreEvidence {
    param($Adapters, [string]$EvidencePath)
    $exactPath = Assert-CoreRelativePath $EvidencePath 'invalid-evidence-path'
    $reader = Get-CoreAdapter $Adapters 'ReadEvidence'
    $envelope = & $reader ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; path = $exactPath; maxBytes = $script:EvidenceLimit })
    Assert-CoreExactProperties $envelope @('value', 'sha256', 'bytes', 'containedRepositoryLeaf', 'nonReparseLeaf') 'invalid-evidence-envelope'
    Assert-CoreHash $envelope.sha256 'invalid-evidence-envelope'
    if (-not (Test-CoreInteger $envelope.bytes) -or [int64]$envelope.bytes -le 0 -or [int64]$envelope.bytes -gt $script:EvidenceLimit) { throw 'invalid-evidence-envelope' }
    if ($envelope.containedRepositoryLeaf -isnot [bool] -or $envelope.nonReparseLeaf -isnot [bool] -or
        -not $envelope.containedRepositoryLeaf -or -not $envelope.nonReparseLeaf) { throw 'unsafe-evidence-leaf' }
    return $envelope
}

function Get-CoreCommandId {
    param($Value, [ValidateSet('red', 'green')][string]$Kind)
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$Value)) { throw 'invalid-command-contract' }
    foreach ($character in ([string]$Value).ToCharArray()) {
        $category = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($character)
        if ([char]::IsControl($character) -or $category -in @(
            [Globalization.UnicodeCategory]::LineSeparator,
            [Globalization.UnicodeCategory]::ParagraphSeparator
        )) { throw 'invalid-command-contract' }
    }
    try {
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $bytes = $strictUtf8.GetBytes([string]$Value)
    } catch { throw 'invalid-command-contract' }
    if ($bytes.Length -gt 4096) { throw 'invalid-command-contract' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hex = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    return ('{0}:sha256:{1}' -f $Kind, $hex)
}

function Assert-CoreCommandId {
    param($Value, [string]$Reason, [ValidateSet('red', 'green')][string]$Kind)
    if ($Value -isnot [string] -or [string]$Value -cnotmatch ('^' + $Kind + ':sha256:[a-f0-9]{64}$')) { throw $Reason }
}

function Assert-CoreCommandEvidence {
    param($Adapters, $Command, [bool]$IsRed, [string[]]$DeclaredIds)
    $names = if ($IsRed) { @('commandId', 'exitCode', 'logPath', 'logSha256', 'logBytes') } else { @('commandId', 'exitCode', 'logPath', 'logSha256', 'logBytes', 'characterization') }
    $reason = if ($IsRed) { 'red-proof-missing' } else { 'green-proof-missing' }
    Assert-CoreExactProperties $Command $names $reason
    $kind = if ($IsRed) { 'red' } else { 'green' }
    Assert-CoreCommandId $Command.commandId $reason $kind
    if ($DeclaredIds -cnotcontains [string]$Command.commandId -or -not (Test-CoreInteger $Command.exitCode)) { throw $reason }
    if ($IsRed) { if ([int64]$Command.exitCode -eq 0) { throw $reason } }
    else {
        if ([int64]$Command.exitCode -ne 0 -or $Command.characterization -isnot [bool]) { throw $reason }
    }
    $path = Assert-CoreRelativePath $Command.logPath $reason
    Assert-CoreHash $Command.logSha256 $reason
    if (-not (Test-CoreInteger $Command.logBytes) -or [int64]$Command.logBytes -lt 0 -or [int64]$Command.logBytes -gt $script:ArtifactLimit) { throw $reason }
    $reader = Get-CoreAdapter $Adapters 'ReadArtifact'
    $actual = & $reader ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; path = $path; maxBytes = $script:ArtifactLimit })
    Assert-CoreExactProperties $actual @('sha256', 'bytes', 'containedRepositoryLeaf', 'nonReparseLeaf') $reason
    if ($actual.containedRepositoryLeaf -isnot [bool] -or $actual.nonReparseLeaf -isnot [bool] -or
        -not $actual.containedRepositoryLeaf -or -not $actual.nonReparseLeaf) { throw 'unsafe-artifact-leaf' }
    if ([string]$actual.sha256 -cne [string]$Command.logSha256 -or [int64]$actual.bytes -ne [int64]$Command.logBytes) { throw $reason }
}

function Assert-CoreEvidenceIdentity {
    param($Evidence, [string]$WorkUnitId, [string]$RunId, [string]$Outcome, [string]$CanonicalHash)
    $rootNames = @('schemaVersion', 'owner', 'runId', 'workUnitId', 'canonicalMarkdownSha256', 'outcome', 'targetPreimages', 'targetPostimages', 'redCommand', 'greenCommands', 'secretScan', 'rollback', 'desktopFinalProof')
    Assert-CoreExactProperties $Evidence $rootNames 'invalid-evidence-schema'
    if ([string]$Evidence.schemaVersion -cne 'awx.notebook.directive.work-unit-evidence.v1') { throw 'invalid-evidence-schema' }
    if ([string]$Evidence.owner -cne 'desktop') { throw 'evidence-owner-mismatch' }
    if ([string]$Evidence.runId -cne $RunId) { throw 'evidence-run-mismatch' }
    if ([string]$Evidence.workUnitId -cne $WorkUnitId) { throw 'evidence-work-unit-mismatch' }
    if ([string]$Evidence.canonicalMarkdownSha256 -cne $CanonicalHash) { throw 'evidence-canonical-mismatch' }
    if ([string]$Evidence.outcome -cne $Outcome) { throw 'evidence-outcome-mismatch' }
    if ([string]$Evidence.desktopFinalProof -cnotin @('verified', 'evidence_needed')) { throw 'invalid-desktop-proof' }
}

function Get-CoreDeclaredCommands {
    param($Unit, [string]$Name)
    if (-not (Test-CoreProperty $Unit $Name) -or $Unit.$Name -isnot [Array] -or @($Unit.$Name).Count -gt $script:CommandLimit) { throw 'invalid-command-contract' }
    $kind = if ($Name -ceq 'redCommands') { 'red' } elseif ($Name -ceq 'greenCommands') { 'green' } else { throw 'invalid-command-contract' }
    $ids = @()
    $seenCommands = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $seenIds = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($command in @($Unit.$Name)) {
        if ($command -isnot [string] -or -not $seenCommands.Add([string]$command)) { throw 'invalid-command-contract' }
        $id = Get-CoreCommandId $command $kind
        if (-not $seenIds.Add($id)) { throw 'invalid-command-contract' }
        $ids += ,$id
    }
    return @($ids)
}

function Assert-CoreEvidence {
    param($Adapters, $Evidence, $Unit, [string]$RunId, [string]$Outcome, [string]$CanonicalHash, $ActualPostimages)
    Assert-CoreEvidenceIdentity $Evidence ([string]$Unit.workUnitId) $RunId $Outcome $CanonicalHash
    if (-not (Test-CoreProperty $Unit 'execution')) { throw 'work-unit-not-running' }
    $preimages = @($Unit.execution.targetPreimages)
    $targetPaths = @($Unit.targetFiles | ForEach-Object { [string]$_ })
    Assert-CoreImageRows $preimages $targetPaths 'invalid-execution-preimages'
    Assert-CoreImageRows $Evidence.targetPreimages $targetPaths 'invalid-evidence-preimages'
    if (-not (Test-CoreSameImages $preimages $Evidence.targetPreimages)) { throw 'evidence-preimage-mismatch' }
    Assert-CoreImageRows $ActualPostimages $targetPaths 'invalid-actual-postimages'
    Assert-CoreImageRows $Evidence.targetPostimages $targetPaths 'invalid-evidence-postimages'
    if (-not (Test-CoreSameImages $ActualPostimages $Evidence.targetPostimages)) { throw 'evidence-postimage-mismatch' }

    Assert-CoreExactProperties $Evidence.secretScan @('mode', 'hitCount') 'invalid-secret-scan'
    if ([string]$Evidence.secretScan.mode -cne 'count-only' -or -not (Test-CoreInteger $Evidence.secretScan.hitCount)) { throw 'invalid-secret-scan' }
    if ([int64]$Evidence.secretScan.hitCount -ne 0) { throw 'secret-scan-failed' }
    Assert-CoreExactProperties $Evidence.rollback @('status', 'actualTargets') 'invalid-rollback-proof'

    $declaredRed = @(Get-CoreDeclaredCommands $Unit 'redCommands')
    $declaredGreen = @(Get-CoreDeclaredCommands $Unit 'greenCommands')
    if ($Evidence.greenCommands -isnot [Array] -or @($Evidence.greenCommands).Count -gt $script:CommandLimit) { throw 'green-proof-missing' }

    if ($Outcome -ceq 'Green') {
        if ($targetPaths.Count -gt 0 -and (Test-CoreSameImages $preimages $ActualPostimages)) { throw 'green-target-unchanged' }
        if ($null -eq $Evidence.redCommand -or $declaredRed.Count -eq 0) { throw 'red-proof-missing' }
        Assert-CoreCommandEvidence $Adapters $Evidence.redCommand $true $declaredRed
        if (@($Evidence.greenCommands).Count -ne $declaredGreen.Count -or $declaredGreen.Count -eq 0) { throw 'green-proof-missing' }
        for ($index = 0; $index -lt $declaredGreen.Count; $index++) {
            if ([string]$Evidence.greenCommands[$index].commandId -cne $declaredGreen[$index]) { throw 'green-proof-missing' }
            Assert-CoreCommandEvidence $Adapters $Evidence.greenCommands[$index] $false $declaredGreen
        }
        if ([string]$Evidence.rollback.status -cne 'not_required' -or $Evidence.rollback.actualTargets -isnot [Array] -or @($Evidence.rollback.actualTargets).Count -ne 0) { throw 'invalid-rollback-proof' }
        if ([string]$Evidence.desktopFinalProof -cne 'verified') { throw 'desktop-proof-missing' }
    } elseif ($Outcome -ceq 'NoPatchNeeded') {
        if ($null -ne $Evidence.redCommand) { throw 'no-patch-red-not-allowed' }
        if (-not (Test-CoreSameImages $preimages $ActualPostimages)) { throw 'no-patch-target-changed' }
        if (@($Evidence.greenCommands).Count -ne $declaredGreen.Count -or $declaredGreen.Count -eq 0) { throw 'characterization-green-missing' }
        for ($index = 0; $index -lt $declaredGreen.Count; $index++) {
            $command = $Evidence.greenCommands[$index]
            if ([string]$command.commandId -cne $declaredGreen[$index]) { throw 'green-proof-missing' }
            Assert-CoreCommandEvidence $Adapters $command $false $declaredGreen
            if (-not $command.characterization) { throw 'characterization-green-missing' }
        }
        if ([string]$Evidence.rollback.status -cne 'not_required' -or $Evidence.rollback.actualTargets -isnot [Array] -or @($Evidence.rollback.actualTargets).Count -ne 0) { throw 'invalid-rollback-proof' }
        if ([string]$Evidence.desktopFinalProof -cne 'verified') { throw 'desktop-proof-missing' }
    } else {
        if ($null -ne $Evidence.redCommand -or @($Evidence.greenCommands).Count -ne 0 -or [string]$Evidence.rollback.status -cne 'verified') { throw 'invalid-rollback-proof' }
        Assert-CoreImageRows $Evidence.rollback.actualTargets $targetPaths 'invalid-rollback-proof'
        if (-not (Test-CoreSameImages $preimages $Evidence.rollback.actualTargets) -or -not (Test-CoreSameImages $preimages $ActualPostimages)) { throw 'rollback-target-mismatch' }
    }
}

function Assert-CoreAdoptCommandEvidence {
    param($Adapters, $Command, [string[]]$DeclaredIds)
    Assert-CoreExactProperties $Command @(
        'commandId','exitCode','assertionCount','durationMs','logPath','logSha256','logBytes','characterization'
    ) 'characterization-green-missing'
    Assert-CoreCommandId $Command.commandId 'characterization-green-missing' 'green'
    if ($DeclaredIds -cnotcontains [string]$Command.commandId -or -not (Test-CoreInteger $Command.exitCode) -or
        [int64]$Command.exitCode -ne 0 -or -not (Test-CoreInteger $Command.assertionCount) -or
        [int64]$Command.assertionCount -le 0 -or -not (Test-CoreInteger $Command.durationMs) -or
        [int64]$Command.durationMs -lt 0 -or [int64]$Command.durationMs -gt 86400000 -or
        $Command.characterization -isnot [bool] -or -not [bool]$Command.characterization) {
        throw 'characterization-green-missing'
    }
    $path = Assert-CoreRelativePath $Command.logPath 'characterization-green-missing'
    Assert-CoreHash $Command.logSha256 'characterization-green-missing'
    if (-not (Test-CoreInteger $Command.logBytes) -or [int64]$Command.logBytes -lt 0 -or [int64]$Command.logBytes -gt $script:ArtifactLimit) {
        throw 'characterization-green-missing'
    }
    $reader = Get-CoreAdapter $Adapters 'ReadArtifact'
    $actual = & $reader ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; path = $path; maxBytes = $script:ArtifactLimit })
    Assert-CoreExactProperties $actual @('sha256','bytes','containedRepositoryLeaf','nonReparseLeaf') 'characterization-green-missing'
    if ($actual.containedRepositoryLeaf -isnot [bool] -or $actual.nonReparseLeaf -isnot [bool] -or
        -not $actual.containedRepositoryLeaf -or -not $actual.nonReparseLeaf -or
        [string]$actual.sha256 -cne [string]$Command.logSha256 -or [int64]$actual.bytes -ne [int64]$Command.logBytes) {
        throw 'characterization-green-missing'
    }
}

function Assert-CoreAdoptEvidence {
    param(
        $Adapters, $EvidenceEnvelope, $Unit, [string]$RunId, [string]$CanonicalHash,
        [string]$ExpectedEvidenceSha256, [string]$ExpectedTargetPostimageSha256,
        $ActualPostimages, [switch]$SkipFreshness
    )
    if ([string]$EvidenceEnvelope.sha256 -cne $ExpectedEvidenceSha256) { throw 'adopt-evidence-sha-mismatch' }
    $evidence = $EvidenceEnvelope.value
    Assert-CoreExactProperties $evidence @(
        'schemaVersion','owner','runId','workUnitId','canonicalMarkdownSha256','outcome','observedAtUtc',
        'targetPostimageSha256','targetPostimages','freezeAnchor','greenCommands','secretScan','desktopFinalProof'
    ) 'invalid-adopt-evidence-schema'
    if ([string]$evidence.schemaVersion -cne 'awx.notebook.directive.adopt-evidence.v1' -or
        [string]$evidence.owner -cne 'desktop' -or [string]$evidence.runId -cne $RunId -or
        [string]$evidence.workUnitId -cne [string]$Unit.workUnitId -or
        [string]$evidence.canonicalMarkdownSha256 -cne $CanonicalHash -or
        [string]$evidence.outcome -cne 'NoPatchNeeded' -or [string]$evidence.desktopFinalProof -cne 'verified') {
        throw 'invalid-adopt-evidence-schema'
    }
    Assert-CoreHash $evidence.targetPostimageSha256 'invalid-target-postimage-hash'
    if ([string]$evidence.targetPostimageSha256 -cne $ExpectedTargetPostimageSha256) { throw 'adopt-target-postimage-sha-mismatch' }

    if ($evidence.observedAtUtc -isnot [string]) { throw 'invalid-adopt-evidence-schema' }
    $observedAt = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::RoundtripKind
    if (-not [DateTimeOffset]::TryParse([string]$evidence.observedAtUtc, [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$observedAt)) {
        throw 'invalid-adopt-evidence-schema'
    }
    if (-not $SkipFreshness) {
        $now = [DateTimeOffset]::UtcNow
        if ($observedAt.ToUniversalTime() -gt $now.AddSeconds($script:AdoptFutureSkewSeconds) -or
            ($now - $observedAt.ToUniversalTime()).TotalSeconds -gt $script:AdoptFreshnessLimitSeconds) {
            throw 'stale-adopt-evidence'
        }
    }

    $targetPaths = @($Unit.targetFiles | ForEach-Object { [string]$_ })
    Assert-CoreImageRows $ActualPostimages $targetPaths 'invalid-actual-postimages'
    Assert-CoreImageRows $evidence.targetPostimages $targetPaths 'invalid-adopt-postimages'
    if (-not (Test-CoreSameImages $ActualPostimages $evidence.targetPostimages)) { throw 'adopt-target-postimage-mismatch' }
    $actualTargetHash = Get-CoreImageArrayHash $Adapters $ActualPostimages
    if ($actualTargetHash -cne $ExpectedTargetPostimageSha256) { throw 'adopt-target-postimage-sha-mismatch' }

    Assert-CoreExactProperties $evidence.freezeAnchor @('path','sha256','bytes') 'invalid-freeze-anchor'
    $anchorPath = Assert-CoreRelativePath $evidence.freezeAnchor.path 'invalid-freeze-anchor'
    Assert-CoreHash $evidence.freezeAnchor.sha256 'invalid-freeze-anchor'
    if (-not (Test-CoreInteger $evidence.freezeAnchor.bytes) -or [int64]$evidence.freezeAnchor.bytes -le 0 -or
        [int64]$evidence.freezeAnchor.bytes -gt $script:ArtifactLimit) { throw 'invalid-freeze-anchor' }
    $artifactReader = Get-CoreAdapter $Adapters 'ReadArtifact'
    try {
        $anchor = & $artifactReader ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; path = $anchorPath; maxBytes = $script:ArtifactLimit })
    } catch {
        if ([string]$_.Exception.Message -ceq 'artifact-missing') { throw 'freeze-anchor-missing' }
        throw
    }
    Assert-CoreExactProperties $anchor @('sha256','bytes','containedRepositoryLeaf','nonReparseLeaf') 'invalid-freeze-anchor'
    if ($anchor.containedRepositoryLeaf -isnot [bool] -or $anchor.nonReparseLeaf -isnot [bool] -or
        -not $anchor.containedRepositoryLeaf -or -not $anchor.nonReparseLeaf -or
        [string]$anchor.sha256 -cne [string]$evidence.freezeAnchor.sha256 -or
        [int64]$anchor.bytes -ne [int64]$evidence.freezeAnchor.bytes) { throw 'freeze-anchor-mismatch' }

    $declaredGreen = @(Get-CoreDeclaredCommands $Unit 'greenCommands')
    if ($evidence.greenCommands -isnot [Array] -or @($evidence.greenCommands).Count -ne $declaredGreen.Count -or $declaredGreen.Count -eq 0) {
        throw 'characterization-green-missing'
    }
    for ($index = 0; $index -lt $declaredGreen.Count; $index++) {
        if ([string]$evidence.greenCommands[$index].commandId -cne $declaredGreen[$index]) { throw 'characterization-green-missing' }
        Assert-CoreAdoptCommandEvidence $Adapters $evidence.greenCommands[$index] $declaredGreen
    }
    Assert-CoreExactProperties $evidence.secretScan @('mode','hitCount') 'invalid-secret-scan'
    if ([string]$evidence.secretScan.mode -cne 'count-only' -or -not (Test-CoreInteger $evidence.secretScan.hitCount) -or
        [int64]$evidence.secretScan.hitCount -ne 0) { throw 'secret-scan-failed' }
    return $evidence
}

function Get-CoreTerminalStatus {
    param([string]$Outcome)
    switch ($Outcome) {
        'Green' { return 'green' }
        'NoPatchNeeded' { return 'no_patch_needed' }
        'Hold' { return 'hold' }
        'Failed' { return 'failed' }
    }
    throw 'invalid-outcome'
}

function Assert-CoreCommittedRecord {
    param(
        $Record, $Unit, [string]$RunId, [string]$Outcome,
        [AllowNull()]$ExpectedPreimages, [string]$Reason
    )

    Assert-CoreExactProperties $Record @(
        'runId','outcome','evidenceSha256','targetPreimages','targetPostimages','redCommandId',
        'greenCommandIds','rollbackStatus','desktopFinalProof'
    ) $Reason
    if ([string]$Record.runId -cne $RunId -or [string]$Record.outcome -cne $Outcome) { throw $Reason }
    Assert-CoreHash $Record.evidenceSha256 $Reason
    if ($Record.desktopFinalProof -isnot [string] -or [string]$Record.desktopFinalProof -cnotin @('verified','evidence_needed') -or
        $Record.rollbackStatus -isnot [string] -or [string]$Record.rollbackStatus -cnotin @('not_required','verified')) { throw $Reason }

    $targetPaths = @($Unit.targetFiles | ForEach-Object { [string]$_ })
    Assert-CoreImageRows $Record.targetPreimages $targetPaths $Reason
    Assert-CoreImageRows $Record.targetPostimages $targetPaths $Reason
    if ($null -ne $ExpectedPreimages) {
        Assert-CoreImageRows $ExpectedPreimages $targetPaths $Reason
        if (-not (Test-CoreSameImages $ExpectedPreimages $Record.targetPreimages)) { throw $Reason }
    }

    if ($Record.greenCommandIds -isnot [Array] -or @($Record.greenCommandIds).Count -gt $script:CommandLimit) { throw $Reason }
    $seenGreen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($commandId in @($Record.greenCommandIds)) {
        Assert-CoreCommandId $commandId $Reason 'green'
        if (-not $seenGreen.Add([string]$commandId)) { throw $Reason }
    }
    if ($null -ne $Record.redCommandId) { Assert-CoreCommandId $Record.redCommandId $Reason 'red' }

    $declaredRed = @(Get-CoreDeclaredCommands $Unit 'redCommands')
    $declaredGreen = @(Get-CoreDeclaredCommands $Unit 'greenCommands')
    $greenExact = @($Record.greenCommandIds).Count -eq $declaredGreen.Count
    if ($greenExact) {
        for ($index = 0; $index -lt $declaredGreen.Count; $index++) {
            if ([string]$Record.greenCommandIds[$index] -cne $declaredGreen[$index]) { $greenExact = $false; break }
        }
    }

    switch -CaseSensitive ($Outcome) {
        'Green' {
            if ($null -eq $Record.redCommandId -or $declaredRed.Count -eq 0 -or
                $declaredRed -cnotcontains [string]$Record.redCommandId -or -not $greenExact -or $declaredGreen.Count -eq 0 -or
                [string]$Record.rollbackStatus -cne 'not_required' -or [string]$Record.desktopFinalProof -cne 'verified') { throw $Reason }
            if ($targetPaths.Count -gt 0 -and (Test-CoreSameImages $Record.targetPreimages $Record.targetPostimages)) { throw $Reason }
        }
        'NoPatchNeeded' {
            if ($null -ne $Record.redCommandId -or -not $greenExact -or $declaredGreen.Count -eq 0 -or
                [string]$Record.rollbackStatus -cne 'not_required' -or [string]$Record.desktopFinalProof -cne 'verified' -or
                -not (Test-CoreSameImages $Record.targetPreimages $Record.targetPostimages)) { throw $Reason }
        }
        { $_ -cin @('Hold','Failed') } {
            if ($null -ne $Record.redCommandId -or @($Record.greenCommandIds).Count -ne 0 -or
                [string]$Record.rollbackStatus -cne 'verified' -or
                -not (Test-CoreSameImages $Record.targetPreimages $Record.targetPostimages)) { throw $Reason }
        }
        default { throw $Reason }
    }
}

function Set-CoreDerivedDesktopProof {
    param($State)
    $requiredUnits = @($State.workUnits | Where-Object { (Test-CoreProperty $_ 'required') -and $_.required -eq $true })
    $verified = $requiredUnits.Count -gt 0
    foreach ($unit in $requiredUnits) {
        if ([string]$unit.status -cnotin @('green', 'no_patch_needed') -or -not (Test-CoreProperty $unit 'record') -or
            -not (Test-CoreProperty $unit.record 'desktopFinalProof') -or [string]$unit.record.desktopFinalProof -cne 'verified') {
            $verified = $false; break
        }
    }
    Set-CoreProperty $State 'desktopFinalProof' $(if ($verified) { 'verified' } else { 'evidence_needed' })
}

function Invoke-ConsolidatedProgramAdoptCore {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$State,
        [Parameter(Mandatory = $true)][string]$StateSha256,
        [Parameter(Mandatory = $true)][string]$CanonicalMarkdownSha256,
        [Parameter(Mandatory = $true)][string]$WorkUnitId,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RunId,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$EvidencePath,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ExpectedEvidenceSha256,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ExpectedTargetPostimageSha256,
        [Parameter(Mandatory = $true)]$Adapters
    )

    Assert-CoreRunId $RunId
    Assert-CoreHash $ExpectedEvidenceSha256 'invalid-expected-evidence-hash'
    Assert-CoreHash $ExpectedTargetPostimageSha256 'invalid-expected-target-postimage-hash'
    Assert-CoreStateIdentity $State $StateSha256 $CanonicalMarkdownSha256
    $unit = Get-CoreUnit $State $WorkUnitId
    Assert-CoreUnitTargets $unit
    $targetPaths = @($unit.targetFiles | ForEach-Object { [string]$_ })
    $capture = Get-CoreAdapter $Adapters 'CaptureTargets'

    if ([string]$unit.status -cin @('green','no_patch_needed','hold','failed')) {
        if ([string]$unit.status -cne 'no_patch_needed' -or -not (Test-CoreProperty $unit 'record') -or
            [string]$unit.record.runId -cne $RunId) { throw 'work-unit-already-terminal' }
        try {
            if ([string]$unit.record.evidenceSha256 -cne $ExpectedEvidenceSha256) { throw 'adopt-replay-mismatch' }
            Assert-CoreCommittedRecord $unit.record $unit $RunId 'NoPatchNeeded' $null 'adopt-replay-mismatch'
            $evidenceEnvelope = Read-CoreEvidence $Adapters $EvidencePath
            $snapshotEnvelope = & $capture ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; paths = $targetPaths; mode = 'postimage' })
            $actualPostimages = @(Get-CoreTargetSnapshotRows $snapshotEnvelope $targetPaths)
            $null = Assert-CoreAdoptEvidence $Adapters $evidenceEnvelope $unit $RunId $CanonicalMarkdownSha256 `
                $ExpectedEvidenceSha256 $ExpectedTargetPostimageSha256 $actualPostimages -SkipFreshness
            if (-not (Test-CoreSameImages $unit.record.targetPostimages $actualPostimages) -or
                -not (Test-CoreSameImages $unit.record.targetPreimages $actualPostimages)) { throw 'adopt-replay-mismatch' }
        } catch { throw 'adopt-replay-mismatch' }
        return [pscustomobject][ordered]@{
            action = 'Adopt'; status = 'no_patch_needed'; reason = 'already-adopted'; workUnitId = $WorkUnitId
            stateSha256 = $StateSha256; desktopFinalProof = [string]$State.desktopFinalProof
        }
    }

    if ([string]$unit.status -cne 'pending' -or (Test-CoreProperty $unit 'execution') -or
        (Test-CoreProperty $unit 'transition') -or (Test-CoreProperty $unit 'record')) { throw 'work-unit-not-pending' }
    if (Test-CoreProperty $unit 'approvalGate') { throw 'approval-required' }
    Assert-CoreNoOtherActiveUnit $State
    $runnable = @(Get-CoreRunnableUnits $State)
    if ($runnable.Count -eq 0 -or [string]$runnable[0].workUnitId -cne $WorkUnitId) { throw 'work-unit-not-first-runnable' }

    $topic = Get-CoreTopic $CanonicalMarkdownSha256 $WorkUnitId
    $absenceRequest = Get-CoreSessionRequest $topic $RunId
    $absenceRequest.action = 'end'
    if (-not (Test-CoreExactLeaseAbsent $Adapters $absenceRequest)) { throw 'adopt-lease-active' }

    $evidenceEnvelope = Read-CoreEvidence $Adapters $EvidencePath
    $snapshotEnvelope = & $capture ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; paths = $targetPaths; mode = 'postimage' })
    $actualPostimages = @(Get-CoreTargetSnapshotRows $snapshotEnvelope $targetPaths)
    $evidence = Assert-CoreAdoptEvidence $Adapters $evidenceEnvelope $unit $RunId $CanonicalMarkdownSha256 `
        $ExpectedEvidenceSha256 $ExpectedTargetPostimageSha256 $actualPostimages

    $record = [pscustomobject][ordered]@{
        runId = $RunId; outcome = 'NoPatchNeeded'; evidenceSha256 = [string]$evidenceEnvelope.sha256
        targetPreimages = Copy-CoreValue $actualPostimages; targetPostimages = Copy-CoreValue $actualPostimages
        redCommandId = $null; greenCommandIds = @($evidence.greenCommands | ForEach-Object { [string]$_.commandId })
        rollbackStatus = 'not_required'; desktopFinalProof = 'verified'
    }
    $terminalState = Copy-CoreValue $State
    $terminalUnit = Get-CoreUnit $terminalState $WorkUnitId
    $terminalUnit.status = 'no_patch_needed'
    Set-CoreProperty $terminalUnit 'record' (Copy-CoreValue $record)
    Set-CoreDerivedDesktopProof $terminalState

    $publish = Get-CoreAdapter $Adapters 'AdoptStateAndEvents'
    $published = & $publish $StateSha256 (Copy-CoreValue $terminalState) (Copy-CoreValue $actualPostimages) $topic $RunId
    $terminalHash = if ($published -is [string]) { $published } elseif ($null -ne $published -and (Test-CoreProperty $published 'stateHash')) { [string]$published.stateHash } else { '' }
    Assert-CoreHash $terminalHash 'invalid-cas-result'
    return [pscustomobject][ordered]@{
        action = 'Adopt'; status = 'no_patch_needed'; reason = 'work-unit-adopted'; workUnitId = $WorkUnitId
        stateSha256 = $terminalHash; desktopFinalProof = [string]$terminalState.desktopFinalProof
    }
}

function Invoke-ConsolidatedProgramRecordCore {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$State,
        [Parameter(Mandatory = $true)][string]$StateSha256,
        [Parameter(Mandatory = $true)][string]$CanonicalMarkdownSha256,
        [Parameter(Mandatory = $true)][string]$WorkUnitId,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$RunId,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Outcome,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$EvidencePath,
        [Parameter(Mandatory = $true)]$Adapters
    )

    if ($Outcome -cnotin @('Green', 'NoPatchNeeded', 'Hold', 'Failed')) { throw 'invalid-outcome' }
    Assert-CoreRunId $RunId
    Assert-CoreStateIdentity $State $StateSha256 $CanonicalMarkdownSha256
    $unit = Get-CoreUnit $State $WorkUnitId
    Assert-CoreUnitTargets $unit

    $terminalStatus = Get-CoreTerminalStatus $Outcome
    if ([string]$unit.status -cin @('green', 'no_patch_needed', 'hold', 'failed')) {
        if (Test-CoreProperty $unit 'record') {
            Assert-CoreCommittedRecord $unit.record $unit $RunId $Outcome $null 'work-unit-already-terminal'
            return [pscustomobject][ordered]@{ action = 'Record'; status = [string]$unit.status; reason = 'already-recorded'; workUnitId = $WorkUnitId; stateSha256 = $StateSha256; desktopFinalProof = [string]$State.desktopFinalProof }
        }
        throw 'work-unit-already-terminal'
    }
    if ([string]$unit.status -cne 'running' -or -not (Test-CoreProperty $unit 'execution') -or
        [string]$unit.execution.runId -cne $RunId -or [string]$unit.execution.owner -cne 'desktop' -or
        [string]$unit.execution.canonicalMarkdownSha256 -cne $CanonicalMarkdownSha256) { throw 'work-unit-not-running' }

    $existingTransition = Test-CoreProperty $unit 'transition'
    $targetPaths = @($unit.targetFiles | ForEach-Object { [string]$_ })
    if ($existingTransition) {
        $transition = $unit.transition
        Assert-CoreExactProperties $transition @('phase','runId','owner','leaseTopic','outcome','terminalStatus','evidenceSha256','record') 'record-transition-mismatch'
        Assert-CoreExactProperties $transition.record @('runId','outcome','evidenceSha256','targetPreimages','targetPostimages','redCommandId','greenCommandIds','rollbackStatus','desktopFinalProof') 'record-transition-mismatch'
        if ([string]$transition.phase -cne 'record-awaiting-release' -or [string]$transition.runId -cne $RunId -or
            [string]$transition.owner -cne 'desktop' -or [string]$transition.leaseTopic -cne [string]$unit.execution.leaseTopic -or
            [string]$transition.outcome -cne $Outcome -or [string]$transition.terminalStatus -cne $terminalStatus -or
            [string]$transition.evidenceSha256 -cne [string]$transition.record.evidenceSha256) { throw 'record-transition-mismatch' }
        Assert-CoreCommittedRecord $transition.record $unit $RunId $Outcome $unit.execution.targetPreimages 'record-transition-mismatch'
        $record = Copy-CoreValue $transition.record
        $awaitingState = Copy-CoreValue $State
        $awaitingHash = $StateSha256
    } else {
        $evidenceEnvelope = Read-CoreEvidence $Adapters $EvidencePath
        $evidence = $evidenceEnvelope.value
        Assert-CoreEvidenceIdentity $evidence $WorkUnitId $RunId $Outcome $CanonicalMarkdownSha256
        $activeRequest = Get-CoreSessionRequest ([string]$unit.execution.leaseTopic) $RunId
        $activeRequest.action = 'status'
        $readSession = Get-CoreAdapter $Adapters 'ReadSession'
        $activeLease = & $readSession $activeRequest
        Assert-CoreLease $activeLease ([string]$unit.execution.leaseTopic) $RunId
        $capture = Get-CoreAdapter $Adapters 'CaptureTargets'
        $snapshotEnvelope = & $capture ([pscustomobject][ordered]@{ root = $script:CanonicalRoot; paths = $targetPaths; mode = 'postimage' })
        $actualPostimages = @(Get-CoreTargetSnapshotRows $snapshotEnvelope $targetPaths)
        Assert-CoreEvidence $Adapters $evidence $unit $RunId $Outcome $CanonicalMarkdownSha256 $actualPostimages

        $record = [pscustomobject][ordered]@{
            runId = $RunId; outcome = $Outcome; evidenceSha256 = [string]$evidenceEnvelope.sha256
            targetPreimages = Copy-CoreValue $unit.execution.targetPreimages
            targetPostimages = Copy-CoreValue $actualPostimages
            redCommandId = if ($null -ne $evidence.redCommand) { [string]$evidence.redCommand.commandId } else { $null }
            greenCommandIds = @($evidence.greenCommands | ForEach-Object { [string]$_.commandId })
            rollbackStatus = [string]$evidence.rollback.status
            desktopFinalProof = [string]$evidence.desktopFinalProof
        }
        $awaitingState = Copy-CoreValue $State
        $awaitingUnit = Get-CoreUnit $awaitingState $WorkUnitId
        Set-CoreProperty $awaitingUnit 'transition' ([pscustomobject][ordered]@{
            phase = 'record-awaiting-release'; runId = $RunId; owner = 'desktop'; leaseTopic = [string]$unit.execution.leaseTopic
            outcome = $Outcome; terminalStatus = $terminalStatus; evidenceSha256 = [string]$evidenceEnvelope.sha256; record = Copy-CoreValue $record
        })
        $awaitingHash = Invoke-CoreCas $Adapters $StateSha256 $awaitingState 'record-awaiting-release'
    }

    $leaseTopic = [string](Get-CoreUnit $awaitingState $WorkUnitId).execution.leaseTopic
    $releaseRequest = Get-CoreSessionRequest $leaseTopic $RunId
    $releaseRequest.action = 'end'
    $alreadyAbsent = $false
    if ($existingTransition) { $alreadyAbsent = Test-CoreExactLeaseAbsent $Adapters $releaseRequest }
    if (-not $alreadyAbsent) {
        try {
            $release = Get-CoreAdapter $Adapters 'ReleaseSession'
            $null = & $release $releaseRequest
        } catch { throw 'release-unproven' }
        if (-not (Test-CoreExactLeaseAbsent $Adapters $releaseRequest)) { throw 'release-unproven' }
    }

    $terminalState = Copy-CoreValue $awaitingState
    $terminalUnit = Get-CoreUnit $terminalState $WorkUnitId
    $terminalUnit.status = $terminalStatus
    Set-CoreProperty $terminalUnit 'record' (Copy-CoreValue $record)
    Remove-CoreProperty $terminalUnit 'execution'
    Remove-CoreProperty $terminalUnit 'transition'
    Set-CoreDerivedDesktopProof $terminalState
    $terminalHash = Invoke-CoreCas $Adapters $awaitingHash $terminalState 'terminal'

    return [pscustomobject][ordered]@{
        action = 'Record'; status = $terminalStatus; reason = 'work-unit-recorded'; workUnitId = $WorkUnitId
        stateSha256 = $terminalHash; desktopFinalProof = [string]$terminalState.desktopFinalProof
    }
}

Export-ModuleMember -Function Invoke-ConsolidatedProgramBeginCore, Invoke-ConsolidatedProgramRecordCore, Invoke-ConsolidatedProgramAdoptCore

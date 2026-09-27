Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:CanonicalDesktopRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$script:ArchiveRoot = 'data/agent-handoff/notebook/consolidated/archive'
$script:ReleaseRoot = 'data/agent-handoff/notebook/consolidated/releases'
$script:PreparedRoot = 'data/agent-handoff/notebook/consolidated/prepared'
$script:Sha256Pattern = '^[0-9A-F]{64}$'
$script:RunIdPattern = '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'

function Test-AwxProperty {
    param($Value, [string]$Name)
    return $null -ne $Value -and $null -ne $Value.PSObject.Properties[$Name]
}

function Get-AwxProperty {
    param($Value, [string]$Name, $Default = $null)
    if (-not (Test-AwxProperty $Value $Name)) { return $Default }
    return $Value.PSObject.Properties[$Name].Value
}

function Assert-AwxExactProperties {
    param($Value, [string[]]$Names, [string]$Reason)
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [Array] -or $Value -is [ValueType]) { throw $Reason }
    $actual = @($Value.PSObject.Properties | ForEach-Object { $_.Name })
    if ($actual.Count -ne $Names.Count) { throw $Reason }
    foreach ($name in $Names) { if ($actual -cnotcontains $name) { throw $Reason } }
}

function Test-AwxBoolean { param($Value); return $Value -is [bool] }

function Test-AwxInteger {
    param($Value)
    if ($Value -is [bool]) { return $false }
    return $Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64]
}

function Test-AwxHash { param($Value); return $Value -is [string] -and $Value -cmatch $script:Sha256Pattern }

function Assert-AwxRunId {
    param([string]$RunId)
    if ([string]::IsNullOrWhiteSpace($RunId) -or $RunId -cnotmatch $script:RunIdPattern) { throw 'unsafe-run-id' }
}

function Copy-AwxValue {
    param($Value)
    return ($Value | ConvertTo-Json -Compress -Depth 60 | ConvertFrom-Json)
}

function Get-AwxValueDigest {
    param($Value)
    $json = $Value | ConvertTo-Json -Compress -Depth 60
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($json)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-AwxTopLevelDigest {
    param($Value, [string]$DigestProperty)
    $projection = [ordered]@{}
    foreach ($property in @($Value.PSObject.Properties)) {
        if ($property.Name -cne $DigestProperty) { $projection[$property.Name] = Copy-AwxValue $property.Value }
    }
    return Get-AwxValueDigest ([pscustomobject]$projection)
}

function Add-AwxDigest {
    param($Value, [string]$DigestProperty)
    $digest = Get-AwxTopLevelDigest $Value $DigestProperty
    if (Test-AwxProperty $Value $DigestProperty) { $Value.PSObject.Properties[$DigestProperty].Value = $digest }
    else { $Value | Add-Member -NotePropertyName $DigestProperty -NotePropertyValue $digest }
    return $Value
}

function Assert-AwxDigest {
    param($Value, [string]$DigestProperty, [string]$Reason)
    $actual = Get-AwxProperty $Value $DigestProperty
    if (-not (Test-AwxHash $actual) -or [string]$actual -cne (Get-AwxTopLevelDigest $Value $DigestProperty)) { throw $Reason }
}

function Get-AwxDistinctStrings {
    param([object[]]$Values)
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $result = [Collections.Generic.List[string]]::new()
    foreach ($value in @($Values)) {
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text) -and $seen.Add($text)) { $result.Add($text) }
    }
    return @($result)
}

function ConvertTo-AwxRelativePath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.Length -gt 512 -or $Path -match '[\x00-\x1F\x7F]' -or
        $Path -match '[:*?\[\]]' -or $Path.StartsWith('/') -or $Path.StartsWith('\')) { throw 'unsafe-relative-path' }
    $normalized = $Path.Replace('\', '/')
    if ($normalized.Contains('//')) { throw 'unsafe-relative-path' }
    foreach ($segment in @($normalized.Split('/'))) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -ceq '.' -or $segment -ceq '..' -or
            $segment.EndsWith('.') -or $segment.EndsWith(' ') -or
            $segment -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$') { throw 'unsafe-relative-path' }
    }
    return $normalized
}

function Test-AwxExactPath {
    param([string]$Path, [string[]]$Paths)
    foreach ($candidate in @($Paths)) {
        if ($Path.Equals([string]$candidate, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function Test-AwxBoundedDirectiveCandidatePath {
    param([string]$Path)
    $segments = @($Path.Split('/'))
    if ($segments.Count -eq 4 -and $segments[0] -ceq 'data' -and $segments[1] -ceq 'agent-handoff' -and
        $segments[2] -ceq 'notebook' -and ($segments[3].EndsWith('.md', [StringComparison]::OrdinalIgnoreCase) -or
        $segments[3].EndsWith('.json', [StringComparison]::OrdinalIgnoreCase))) { return $true }
    if ($segments.Count -eq 3 -and $segments[0] -ceq '__patch_drop__' -and $segments[1] -ceq 'notebook' -and
        $segments[2].IndexOf('directive', [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $segments[2].EndsWith('.md', [StringComparison]::OrdinalIgnoreCase)) { return $true }
    if ($segments.Count -eq 2 -and $segments[0] -ceq 'agent-prompts' -and
        $segments[1].IndexOf('source_directive', [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $segments[1].EndsWith('.md', [StringComparison]::OrdinalIgnoreCase)) { return $true }
    return $false
}

function Test-AwxFreshTimestamp {
    param([string]$Timestamp, [DateTimeOffset]$NowUtc)
    $parsed = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
    if (-not [DateTimeOffset]::TryParse($Timestamp, [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$parsed)) { return $false }
    $age = $NowUtc.ToUniversalTime() - $parsed.ToUniversalTime()
    return $age.TotalSeconds -le 60 -and $age.TotalSeconds -ge -5
}

function Get-AwxGateReasons {
    param($GateSnapshot, [DateTimeOffset]$NowUtc)
    $reasons = @()
    try {
        Assert-AwxExactProperties $GateSnapshot @('schemaVersion', 'root', 'capturedAtUtc', 'indexLockPresent', 'topLevelPatchCount', 'activeLeaseCount', 'corruptLeaseCount') 'gate-snapshot-invalid'
        if ([string]$GateSnapshot.schemaVersion -cne 'awx.notebook.directive.fresh-gates.v1') { throw 'gate-snapshot-invalid' }
        if (-not ([string]$GateSnapshot.root).Equals($script:CanonicalDesktopRoot, [StringComparison]::OrdinalIgnoreCase)) { $reasons += 'unsupported-root' }
        if (-not (Test-AwxFreshTimestamp ([string]$GateSnapshot.capturedAtUtc) $NowUtc)) { $reasons += 'gate-snapshot-stale' }
        if (-not (Test-AwxBoolean $GateSnapshot.indexLockPresent)) { throw 'gate-snapshot-invalid' }
        if ([bool]$GateSnapshot.indexLockPresent) { $reasons += 'index-lock-present' }
        foreach ($row in @(
            [pscustomobject]@{ name = 'topLevelPatchCount'; reason = 'top-level-patch-pending' },
            [pscustomobject]@{ name = 'activeLeaseCount'; reason = 'active-source-lease' },
            [pscustomobject]@{ name = 'corruptLeaseCount'; reason = 'corrupt-source-lease' }
        )) {
            $value = Get-AwxProperty $GateSnapshot $row.name
            if (-not (Test-AwxInteger $value) -or [long]$value -lt 0) { throw 'gate-snapshot-invalid' }
            if ([long]$value -gt 0) { $reasons += $row.reason }
        }
    } catch { $reasons += 'gate-snapshot-invalid' }
    return @(Get-AwxDistinctStrings $reasons)
}

function Get-AwxStabilityReasons {
    param($Program, $State, $Inventory, $StabilitySnapshot, [DateTimeOffset]$NowUtc)
    $reasons = @()
    try {
        Assert-AwxExactProperties $StabilitySnapshot @(
            'schemaVersion', 'expectedCanonicalSha256', 'observedCanonicalSha256', 'expectedStateSha256', 'observedStateSha256',
            'expectedInventorySha256', 'observedInventorySha256', 'root', 'capturedAtUtc'
        ) 'stability-snapshot-invalid'
        if ([string]$StabilitySnapshot.schemaVersion -cne 'awx.notebook.directive.stability-snapshot.v1') { throw 'stability-snapshot-invalid' }
        if (-not ([string]$StabilitySnapshot.root).Equals($script:CanonicalDesktopRoot, [StringComparison]::OrdinalIgnoreCase)) { $reasons += 'unsupported-root' }
        if (-not (Test-AwxFreshTimestamp ([string]$StabilitySnapshot.capturedAtUtc) $NowUtc)) { $reasons += 'stability-snapshot-stale' }
        foreach ($row in @(
            [pscustomobject]@{ reason = 'canonical-hash-changed'; expected = 'expectedCanonicalSha256'; observed = 'observedCanonicalSha256'; ownerA = [string](Get-AwxProperty $Program 'canonicalSha256'); ownerB = [string](Get-AwxProperty $State 'canonicalSha256') },
            [pscustomobject]@{ reason = 'state-hash-changed'; expected = 'expectedStateSha256'; observed = 'observedStateSha256'; ownerA = [string](Get-AwxProperty $State 'stateSha256'); ownerB = [string](Get-AwxProperty $State 'stateSha256') },
            [pscustomobject]@{ reason = 'inventory-hash-changed'; expected = 'expectedInventorySha256'; observed = 'observedInventorySha256'; ownerA = [string](Get-AwxProperty $Program 'inventorySha256'); ownerB = [string](Get-AwxProperty $Inventory 'sha256') }
        )) {
            $expected = [string](Get-AwxProperty $StabilitySnapshot $row.expected)
            $observed = [string](Get-AwxProperty $StabilitySnapshot $row.observed)
            if (-not (Test-AwxHash $expected) -or -not (Test-AwxHash $observed) -or -not (Test-AwxHash $row.ownerA) -or
                -not (Test-AwxHash $row.ownerB) -or $expected -cne $observed -or $expected -cne $row.ownerA -or $expected -cne $row.ownerB) { $reasons += $row.reason }
        }
        if ([string](Get-AwxProperty $State 'inventorySha256') -cne [string](Get-AwxProperty $Inventory 'sha256')) { $reasons += 'inventory-hash-changed' }
    } catch { $reasons += 'stability-snapshot-invalid' }
    return @(Get-AwxDistinctStrings $reasons)
}

function Get-AwxFrozenStabilityReasons {
    param($Assessment, $StabilitySnapshot, [DateTimeOffset]$NowUtc)
    $reasons = @()
    try {
        Assert-AwxExactProperties $StabilitySnapshot @(
            'schemaVersion', 'expectedCanonicalSha256', 'observedCanonicalSha256', 'expectedStateSha256', 'observedStateSha256',
            'expectedInventorySha256', 'observedInventorySha256', 'root', 'capturedAtUtc'
        ) 'stability-snapshot-invalid'
        if ([string]$StabilitySnapshot.schemaVersion -cne 'awx.notebook.directive.stability-snapshot.v1') { throw 'stability-snapshot-invalid' }
        if (-not ([string]$StabilitySnapshot.root).Equals($script:CanonicalDesktopRoot, [StringComparison]::OrdinalIgnoreCase)) { $reasons += 'unsupported-root' }
        if (-not (Test-AwxFreshTimestamp ([string]$StabilitySnapshot.capturedAtUtc) $NowUtc)) { $reasons += 'stability-snapshot-stale' }
        $frozen = $Assessment.frozen
        foreach ($row in @(
            [pscustomobject]@{ name = 'Canonical'; frozen = 'canonicalSha256'; reason = 'canonical-hash-changed' },
            [pscustomobject]@{ name = 'State'; frozen = 'stateSha256'; reason = 'state-hash-changed' },
            [pscustomobject]@{ name = 'Inventory'; frozen = 'inventorySha256'; reason = 'inventory-hash-changed' }
        )) {
            $expected = [string](Get-AwxProperty $StabilitySnapshot ("expected$($row.name)Sha256"))
            $observed = [string](Get-AwxProperty $StabilitySnapshot ("observed$($row.name)Sha256"))
            $bound = [string](Get-AwxProperty $frozen $row.frozen)
            if (-not (Test-AwxHash $expected) -or -not (Test-AwxHash $observed) -or -not (Test-AwxHash $bound) -or
                $expected -cne $observed -or $expected -cne $bound) { $reasons += $row.reason }
        }
    } catch { $reasons += 'stability-snapshot-invalid' }
    return @(Get-AwxDistinctStrings $reasons)
}

function Get-AwxDerivedDesktopProof {
    param($Program, $State)
    $reasons = @()
    $programUnits = @(Get-AwxProperty $Program 'workUnits' @())
    $stateUnits = @(Get-AwxProperty $State 'workUnits' @())
    if ($programUnits.Count -eq 0 -or $programUnits.Count -ne $stateUnits.Count) { $reasons += 'program-state-projection-invalid' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $requiredCount = 0
    for ($index = 0; $index -lt $programUnits.Count; $index++) {
        $programUnit = $programUnits[$index]
        $id = [string](Get-AwxProperty $programUnit 'workUnitId')
        $required = Get-AwxProperty $programUnit 'required'
        $runnable = Get-AwxProperty $programUnit 'runnable'
        if ([string]::IsNullOrWhiteSpace($id) -or -not $seen.Add($id) -or -not (Test-AwxBoolean $required) -or -not (Test-AwxBoolean $runnable)) {
            $reasons += 'program-state-projection-invalid'; continue
        }
        if ($index -ge $stateUnits.Count -or [string](Get-AwxProperty $stateUnits[$index] 'workUnitId') -cne $id) {
            $reasons += 'program-state-projection-invalid'; continue
        }
        if (-not [bool]$required) { continue }
        $requiredCount++
        $stateUnit = $stateUnits[$index]
        if ([string](Get-AwxProperty $stateUnit 'status') -cnotin @('green', 'no_patch_needed')) { $reasons += 'required-work-units-not-terminal'; continue }
        $proof = Get-AwxProperty $stateUnit 'evidence'
        try {
            Assert-AwxExactProperties $proof @('owner', 'desktopFinalProof') 'desktop-unit-proof-invalid'
            if ([string]$proof.owner -cne 'desktop' -or [string]$proof.desktopFinalProof -cne 'verified') { throw 'desktop-unit-proof-invalid' }
        } catch { $reasons += 'desktop-unit-proof-invalid' }
    }
    if ($requiredCount -eq 0) { $reasons += 'required-work-units-missing' }
    $reasons = @(Get-AwxDistinctStrings $reasons)
    return [pscustomobject][ordered]@{ status = if ($reasons.Count -eq 0) { 'verified' } else { 'evidence_needed' }; reasons = $reasons; requiredCount = $requiredCount }
}

function Get-AwxRetirementItems {
    param($Program, $Inventory, [object[]]$LeafSnapshots, [string]$RunId, [string[]]$ProtectedPaths)
    $canonicalPath = ConvertTo-AwxRelativePath ([string](Get-AwxProperty $Program 'canonicalPath'))
    $normalizedProtected = @($ProtectedPaths | ForEach-Object { ConvertTo-AwxRelativePath ([string]$_) })
    $snapshotGroups = @{}
    foreach ($snapshot in @($LeafSnapshots)) {
        $key = ''
        try { $key = (ConvertTo-AwxRelativePath ([string](Get-AwxProperty $snapshot 'path'))).ToLowerInvariant() } catch { $key = [guid]::NewGuid().ToString() }
        if (-not $snapshotGroups.ContainsKey($key)) { $snapshotGroups[$key] = @() }
        $snapshotGroups[$key] = @($snapshotGroups[$key]) + @($snapshot)
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $rows = @()
    foreach ($item in @(Get-AwxProperty $Inventory 'items' @())) {
        $rawPath = [string](Get-AwxProperty $item 'path')
        $path = $rawPath
        try { $path = ConvertTo-AwxRelativePath $rawPath } catch {
            $rows += [pscustomobject][ordered]@{ path = $rawPath; expectedSha256 = [string](Get-AwxProperty $item 'sha256'); expectedBytes = 0; disposition = 'hold'; reason = 'unsafe-retirement-path'; archivePath = ''; exactLeaf = $true }
            continue
        }
        if (-not $seen.Add($path)) {
            $rows += [pscustomobject][ordered]@{ path = $path; expectedSha256 = [string](Get-AwxProperty $item 'sha256'); expectedBytes = 0; disposition = 'hold'; reason = 'duplicate-retirement-path'; archivePath = ''; exactLeaf = $true }
            continue
        }
        $expectedSha = [string](Get-AwxProperty $item 'sha256')
        $expectedBytesValue = Get-AwxProperty $item 'bytes'
        $expectedBytes = if (Test-AwxInteger $expectedBytesValue) { [long]$expectedBytesValue } else { -1 }
        $classification = [string](Get-AwxProperty $item 'classification')
        $disposition = 'hold'; $reason = 'retirement-item-invalid'
        if ($path.Equals($canonicalPath, [StringComparison]::OrdinalIgnoreCase)) { $disposition = 'excluded'; $reason = 'canonical-protected' }
        elseif (Test-AwxExactPath $path $normalizedProtected) { $disposition = 'excluded'; $reason = 'protected-path' }
        elseif ($classification -ceq 'excluded') { $disposition = 'excluded'; $reason = [string](Get-AwxProperty $item 'exclusionReason' 'excluded') }
        elseif ($classification -cne 'candidate') { $reason = 'retirement-classification-invalid' }
        elseif (-not (Test-AwxBoundedDirectiveCandidatePath $path)) { $reason = 'retirement-candidate-outside-bounds' }
        elseif ([string](Get-AwxProperty $item 'coverage') -cne 'complete') { $reason = 'coverage-incomplete' }
        elseif (-not (Test-AwxHash $expectedSha) -or $expectedBytes -lt 0) { $reason = 'retirement-preimage-invalid' }
        else {
            $matches = @($snapshotGroups[$path.ToLowerInvariant()])
            if ($matches.Count -ne 1) { $reason = 'leaf-snapshot-missing-or-duplicate' }
            else {
                $snapshot = $matches[0]
                try {
                    Assert-AwxExactProperties $snapshot @('path', 'exists', 'isLeaf', 'isReparse', 'containedRepositoryLeaf', 'bytes', 'sha256') 'leaf-snapshot-invalid'
                    if (-not (Test-AwxBoolean $snapshot.exists) -or -not (Test-AwxBoolean $snapshot.isLeaf) -or
                        -not (Test-AwxBoolean $snapshot.isReparse) -or -not (Test-AwxBoolean $snapshot.containedRepositoryLeaf) -or
                        -not (Test-AwxInteger $snapshot.bytes) -or [long]$snapshot.bytes -lt 0) { throw 'leaf-snapshot-invalid' }
                    if (-not [bool]$snapshot.exists) { $reason = 'retirement-source-missing' }
                    elseif (-not [bool]$snapshot.isLeaf) { $reason = 'retirement-target-not-leaf' }
                    elseif ([bool]$snapshot.isReparse) { $reason = 'retirement-reparse-risk' }
                    elseif (-not [bool]$snapshot.containedRepositoryLeaf) { $reason = 'retirement-outside-repository' }
                    elseif (-not (Test-AwxHash $snapshot.sha256) -or [string]$snapshot.sha256 -cne $expectedSha -or [long]$snapshot.bytes -ne $expectedBytes) { $reason = 'retirement-preimage-changed' }
                    else { $disposition = 'eligible'; $reason = '' }
                } catch { $reason = 'leaf-snapshot-invalid' }
            }
        }
        $archivePath = if ($disposition -ceq 'eligible') { "$script:ArchiveRoot/$RunId/$path" } else { '' }
        $rows += [pscustomobject][ordered]@{ path = $path; expectedSha256 = $expectedSha; expectedBytes = $expectedBytes; disposition = $disposition; reason = $reason; archivePath = $archivePath; exactLeaf = $true }
    }
    return @($rows)
}

function Get-AwxRetirementAssessment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Program, [Parameter(Mandatory = $true)]$State, [Parameter(Mandatory = $true)]$Inventory,
        [Parameter(Mandatory = $true)]$StabilitySnapshot, [Parameter(Mandatory = $true)]$GateSnapshot,
        [Parameter(Mandatory = $true)][object[]]$LeafSnapshots, [Parameter(Mandatory = $true)][string]$RunId,
        [string[]]$ProtectedPaths = @(), [Parameter(Mandatory = $true)][DateTimeOffset]$NowUtc,
        [switch]$ConfirmRetirement, [scriptblock]$ReadOnlyProbe
    )
    Assert-AwxRunId $RunId
    $items = @(Get-AwxRetirementItems $Program $Inventory $LeafSnapshots $RunId $ProtectedPaths)
    $proof = Get-AwxDerivedDesktopProof $Program $State
    $reasons = @()
    if (-not $ConfirmRetirement.IsPresent) { $reasons += 'retirement-confirmation-required' }
    if ([string](Get-AwxProperty $Inventory 'schemaVersion') -cne 'demo1.notebook-directive-retirement.v1' -or
        -not (Test-AwxBoolean (Get-AwxProperty $Inventory 'frozen')) -or -not [bool](Get-AwxProperty $Inventory 'frozen')) { $reasons += 'retirement-inventory-not-frozen' }
    $reasons += @(Get-AwxStabilityReasons $Program $State $Inventory $StabilitySnapshot $NowUtc)
    $reasons += @(Get-AwxGateReasons $GateSnapshot $NowUtc)
    $reasons += @($proof.reasons)
    $structuralReasons = @('unsafe-retirement-path', 'duplicate-retirement-path', 'retirement-item-invalid', 'retirement-classification-invalid', 'retirement-candidate-outside-bounds', 'retirement-preimage-invalid', 'leaf-snapshot-missing-or-duplicate', 'leaf-snapshot-invalid')
    if (@($items | Where-Object { $structuralReasons -ccontains [string]$_.reason }).Count -gt 0) { $reasons += 'retirement-inventory-invalid' }
    $reasons = @(Get-AwxDistinctStrings $reasons)
    $result = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.retirement-assessment.v2'; runId = $RunId
        mode = if ($ConfirmRetirement.IsPresent) { 'confirm' } else { 'dry-run' }
        confirmRequested = [bool]$ConfirmRetirement.IsPresent; authorityGranted = $false
        readyForTrustedAdapter = [bool]($ConfirmRetirement.IsPresent -and $reasons.Count -eq 0)
        derivedDesktopFinalProof = $proof.status
        eligibleCount = @($items | Where-Object { $_.disposition -ceq 'eligible' }).Count
        heldCount = @($items | Where-Object { $_.disposition -ceq 'hold' }).Count
        excludedCount = @($items | Where-Object { $_.disposition -ceq 'excluded' }).Count
        reasonCodes = $reasons; canonicalPath = ConvertTo-AwxRelativePath ([string](Get-AwxProperty $Program 'canonicalPath'))
        frozen = [pscustomobject][ordered]@{
            canonicalSha256 = [string](Get-AwxProperty $StabilitySnapshot 'expectedCanonicalSha256')
            stateSha256 = [string](Get-AwxProperty $StabilitySnapshot 'expectedStateSha256')
            inventorySha256 = [string](Get-AwxProperty $StabilitySnapshot 'expectedInventorySha256')
        }
        items = $items
    }
    return Add-AwxDigest $result 'assessmentDigest'
}

function Assert-AwxAssessmentBinding {
    param($Assessment)
    Assert-AwxExactProperties $Assessment @(
        'schemaVersion', 'runId', 'mode', 'confirmRequested', 'authorityGranted', 'readyForTrustedAdapter', 'derivedDesktopFinalProof',
        'eligibleCount', 'heldCount', 'excludedCount', 'reasonCodes', 'canonicalPath', 'frozen', 'items', 'assessmentDigest'
    ) 'assessment-binding-invalid'
    if ([string]$Assessment.schemaVersion -cne 'awx.notebook.directive.retirement-assessment.v2') { throw 'assessment-binding-invalid' }
    Assert-AwxRunId ([string]$Assessment.runId)
    Assert-AwxDigest $Assessment 'assessmentDigest' 'assessment-binding-invalid'
    foreach ($name in @('confirmRequested', 'authorityGranted', 'readyForTrustedAdapter')) {
        if (-not (Test-AwxBoolean (Get-AwxProperty $Assessment $name))) { throw 'assessment-binding-invalid' }
    }
    if ([bool]$Assessment.authorityGranted -or [string]$Assessment.mode -cnotin @('dry-run', 'confirm') -or
        ([string]$Assessment.mode -ceq 'confirm') -ne [bool]$Assessment.confirmRequested -or
        [string]$Assessment.derivedDesktopFinalProof -cnotin @('verified', 'evidence_needed')) { throw 'assessment-binding-invalid' }
    foreach ($name in @('eligibleCount', 'heldCount', 'excludedCount')) {
        if (-not (Test-AwxInteger (Get-AwxProperty $Assessment $name)) -or [long](Get-AwxProperty $Assessment $name) -lt 0) { throw 'assessment-binding-invalid' }
    }
    Assert-AwxExactProperties $Assessment.frozen @('canonicalSha256', 'stateSha256', 'inventorySha256') 'assessment-binding-invalid'
    foreach ($name in @('canonicalSha256', 'stateSha256', 'inventorySha256')) {
        if (-not (Test-AwxHash (Get-AwxProperty $Assessment.frozen $name))) { throw 'assessment-binding-invalid' }
    }
    $items = @($Assessment.items)
    $eligibleCount = 0; $heldCount = 0; $excludedCount = 0; $canonicalCount = 0
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($item in $items) {
        Assert-AwxExactProperties $item @('path', 'expectedSha256', 'expectedBytes', 'disposition', 'reason', 'archivePath', 'exactLeaf') 'assessment-binding-invalid'
        $path = ConvertTo-AwxRelativePath ([string]$item.path)
        if (-not $seen.Add($path) -or -not (Test-AwxHash $item.expectedSha256) -or
            -not (Test-AwxInteger $item.expectedBytes) -or [long]$item.expectedBytes -lt 0 -or
            -not (Test-AwxBoolean $item.exactLeaf) -or -not [bool]$item.exactLeaf) { throw 'assessment-binding-invalid' }
        if ($path.Equals([string]$Assessment.canonicalPath, [StringComparison]::OrdinalIgnoreCase)) {
            $canonicalCount++
            if ([string]$item.disposition -cne 'excluded' -or [string]$item.reason -cne 'canonical-protected') { throw 'assessment-binding-invalid' }
        }
        switch ([string]$item.disposition) {
            'eligible' {
                $eligibleCount++
                if (-not (Test-AwxBoundedDirectiveCandidatePath $path) -or -not [string]::IsNullOrWhiteSpace([string]$item.reason) -or
                    [string]$item.archivePath -cne "$script:ArchiveRoot/$($Assessment.runId)/$path") { throw 'assessment-binding-invalid' }
            }
            'hold' {
                $heldCount++
                if ([string]::IsNullOrWhiteSpace([string]$item.reason) -or -not [string]::IsNullOrWhiteSpace([string]$item.archivePath)) { throw 'assessment-binding-invalid' }
            }
            'excluded' {
                $excludedCount++
                if ([string]::IsNullOrWhiteSpace([string]$item.reason) -or -not [string]::IsNullOrWhiteSpace([string]$item.archivePath)) { throw 'assessment-binding-invalid' }
            }
            default { throw 'assessment-binding-invalid' }
        }
    }
    if ($eligibleCount -ne [long]$Assessment.eligibleCount -or $heldCount -ne [long]$Assessment.heldCount -or
        $excludedCount -ne [long]$Assessment.excludedCount -or $canonicalCount -ne 1) { throw 'assessment-binding-invalid' }
    $reasonCount = @($Assessment.reasonCodes).Count
    $expectedReady = [bool]($Assessment.confirmRequested -and $reasonCount -eq 0 -and [string]$Assessment.derivedDesktopFinalProof -ceq 'verified')
    if ([bool]$Assessment.readyForTrustedAdapter -ne $expectedReady) { throw 'assessment-binding-invalid' }
}

function New-AwxOperation {
    param([string]$OperationId, [string]$Kind, [bool]$CheckpointAfter, [hashtable]$Fields = @{})
    $row = [ordered]@{
        schemaVersion = 'awx.notebook.directive.planned-operation.v3'; operationId = $OperationId; kind = $Kind
        checkpointAfter = $CheckpointAfter; coreExecutes = $false; path = ''; sourcePath = ''; targetPath = ''; finalPath = ''
        expectedSha256 = $null; expectedBytes = [long]0; expectedExists = $null; exactLeaf = $false; recursive = $false
        createNew = $false; writeThrough = $false; flushToDisk = $false; requireTargetAbsent = $false; content = $null
        preimagePath = ''; expectedPreExists = $null; expectedPreSha256 = $null; expectedPreBytes = [long]0
        expectedPreIsLeaf = $null; expectedPreIsReparse = $null; expectedTargetExistsBefore = $null
        postPath = ''; expectedPostExists = $null; expectedPostSha256 = $null; expectedPostBytes = [long]0
        expectedPostIsLeaf = $null; expectedPostIsReparse = $null
    }
    foreach ($key in $Fields.Keys) { if (-not $row.Contains($key)) { throw 'operation-field-invalid' }; $row[$key] = $Fields[$key] }
    $hasSource = -not [string]::IsNullOrWhiteSpace([string]$row.sourcePath)
    $isSourceLessCreate = [bool]$row.createNew -and -not $hasSource
    if (-not $Fields.ContainsKey('preimagePath')) { $row.preimagePath = if ($hasSource) { [string]$row.sourcePath } else { [string]$row.path } }
    if (-not $Fields.ContainsKey('expectedPreExists')) {
        $row.expectedPreExists = if ($isSourceLessCreate) { $false } else { $row.expectedExists }
    }
    if (-not $Fields.ContainsKey('expectedPreSha256')) {
        $row.expectedPreSha256 = if ($isSourceLessCreate) { $null } else { $row.expectedSha256 }
    }
    if (-not $Fields.ContainsKey('expectedPreBytes')) {
        $row.expectedPreBytes = if ($isSourceLessCreate) { [long]0 } else { [long]$row.expectedBytes }
    }
    if (-not $Fields.ContainsKey('expectedPreIsLeaf')) {
        $row.expectedPreIsLeaf = if ($null -eq $row.expectedPreExists) { $null } elseif ([bool]$row.expectedPreExists) { [bool]$row.exactLeaf } else { $false }
    }
    if (-not $Fields.ContainsKey('expectedPreIsReparse')) {
        $row.expectedPreIsReparse = if ($null -eq $row.expectedPreExists) { $null } else { $false }
    }
    if (-not $Fields.ContainsKey('expectedTargetExistsBefore') -and -not [string]::IsNullOrWhiteSpace([string]$row.targetPath) -and
        ([bool]$row.createNew -or [bool]$row.requireTargetAbsent)) { $row.expectedTargetExistsBefore = $false }
    if (-not $Fields.ContainsKey('postPath')) { $row.postPath = [string]$row.path }
    if (-not $Fields.ContainsKey('expectedPostExists')) { $row.expectedPostExists = $row.expectedExists }
    if (-not $Fields.ContainsKey('expectedPostSha256')) { $row.expectedPostSha256 = $row.expectedSha256 }
    if (-not $Fields.ContainsKey('expectedPostBytes')) { $row.expectedPostBytes = [long]$row.expectedBytes }
    if (-not $Fields.ContainsKey('expectedPostIsLeaf')) {
        $row.expectedPostIsLeaf = if ($null -eq $row.expectedPostExists) { $null } elseif ([bool]$row.expectedPostExists) { [bool]$row.exactLeaf } else { $false }
    }
    if (-not $Fields.ContainsKey('expectedPostIsReparse')) {
        $row.expectedPostIsReparse = if ($null -eq $row.expectedPostExists) { $null } else { $false }
    }
    return [pscustomobject]$row
}

function New-AwxCheckpointOperation { param([string]$Id, [string]$JournalPath); return New-AwxOperation $Id 'checkpoint' $false @{ path = $JournalPath; exactLeaf = $true; writeThrough = $true; flushToDisk = $true } }

function Get-AwxRetirementOperations {
    param([string]$SourcePath, [string]$ArchivePath, [string]$ArchiveTempPath, [string]$StagePath, [string]$CanonicalPath, [string]$JournalPath, [string]$Sha256, [long]$Bytes, [string]$CanonicalSha256)
    return @(
        (New-AwxOperation 'archive-create-temp' 'effect' $true @{ path = $ArchiveTempPath; sourcePath = $SourcePath; targetPath = $ArchiveTempPath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true; createNew = $true; writeThrough = $true }),
        (New-AwxCheckpointOperation 'checkpoint-archive-temp-created' $JournalPath),
        (New-AwxOperation 'archive-flush-temp' 'effect' $true @{ path = $ArchiveTempPath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true; flushToDisk = $true }),
        (New-AwxCheckpointOperation 'checkpoint-archive-temp-flushed' $JournalPath),
        (New-AwxOperation 'archive-verify-temp' 'read' $true @{ path = $ArchiveTempPath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true }),
        (New-AwxCheckpointOperation 'checkpoint-archive-temp-verified' $JournalPath),
        (New-AwxCheckpointOperation 'checkpoint-archive-rename-intent' $JournalPath),
        (New-AwxOperation 'archive-rename-final' 'effect' $true @{ path = $ArchivePath; sourcePath = $ArchiveTempPath; targetPath = $ArchivePath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true; requireTargetAbsent = $true }),
        (New-AwxCheckpointOperation 'checkpoint-archive-renamed' $JournalPath),
        (New-AwxOperation 'source-rehash' 'read' $true @{ path = $SourcePath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true }),
        (New-AwxCheckpointOperation 'checkpoint-source-rehashed' $JournalPath),
        (New-AwxCheckpointOperation 'checkpoint-stage-rename-intent' $JournalPath),
        (New-AwxOperation 'source-rename-stage' 'effect' $true @{ path = $StagePath; sourcePath = $SourcePath; targetPath = $StagePath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true; requireTargetAbsent = $true }),
        (New-AwxCheckpointOperation 'checkpoint-stage-renamed' $JournalPath),
        (New-AwxOperation 'stage-rehash' 'read' $true @{ path = $StagePath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true }),
        (New-AwxCheckpointOperation 'checkpoint-stage-rehashed' $JournalPath),
        (New-AwxOperation 'archive-reverify' 'read' $true @{ path = $ArchivePath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $true; exactLeaf = $true }),
        (New-AwxCheckpointOperation 'checkpoint-archive-reverified' $JournalPath),
        (New-AwxOperation 'original-verify-absent' 'read' $true @{ path = $SourcePath; expectedExists = $false; exactLeaf = $true }),
        (New-AwxCheckpointOperation 'checkpoint-original-absent' $JournalPath),
        (New-AwxOperation 'canonical-verify-present' 'read' $true @{ path = $CanonicalPath; expectedSha256 = $CanonicalSha256; expectedExists = $true; exactLeaf = $true }),
        (New-AwxCheckpointOperation 'checkpoint-delete-intent' $JournalPath),
        (New-AwxOperation 'stage-delete-exact' 'effect' $true @{
            path = $StagePath; expectedSha256 = $Sha256; expectedBytes = $Bytes; expectedExists = $false; exactLeaf = $true; recursive = $false
            preimagePath = $StagePath; expectedPreExists = $true; expectedPreSha256 = $Sha256; expectedPreBytes = $Bytes
            expectedPreIsLeaf = $true; expectedPreIsReparse = $false; postPath = $StagePath; expectedPostExists = $false
            expectedPostSha256 = $null; expectedPostBytes = [long]0; expectedPostIsLeaf = $false; expectedPostIsReparse = $false
        }),
        (New-AwxCheckpointOperation 'checkpoint-delete-observed' $JournalPath)
    )
}

function New-AwxRetirementItemPlan {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)][string]$Path)
    Assert-AwxAssessmentBinding $Assessment
    if (-not [bool]$Assessment.confirmRequested -or -not [bool]$Assessment.readyForTrustedAdapter -or [string]$Assessment.derivedDesktopFinalProof -cne 'verified') { throw 'retirement-assessment-not-ready' }
    $safePath = ConvertTo-AwxRelativePath $Path
    $matches = @($Assessment.items | Where-Object { [string]$_.path -ceq $safePath -and [string]$_.disposition -ceq 'eligible' })
    if ($matches.Count -ne 1) { throw 'retirement-item-not-eligible' }
    $item = $matches[0]; $runId = [string]$Assessment.runId
    $archivePath = [string]$item.archivePath; $archiveTempPath = "$archivePath.tmp.$runId"; $stagePath = "$safePath.retiring.$runId"
    $journalPath = "$script:ArchiveRoot/$runId/retirement-journal.json"; $journalTempPath = "$journalPath.tmp.$runId"
    $operations = Get-AwxRetirementOperations $safePath $archivePath $archiveTempPath $stagePath ([string]$Assessment.canonicalPath) $journalPath ([string]$item.expectedSha256) ([long]$item.expectedBytes) ([string]$Assessment.frozen.canonicalSha256)
    $plan = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-plan.v2'; planType = 'retirement-item'; runId = $runId
        assessmentDigest = [string]$Assessment.assessmentDigest; authorityGranted = $false; readyForTrustedAdapter = $true; reasonCodes = @()
        sourcePath = $safePath; subjectPath = $safePath; expectedSha256 = [string]$item.expectedSha256; expectedBytes = [long]$item.expectedBytes
        archivePath = $archivePath; archiveTempPath = $archiveTempPath; stagePath = $stagePath; canonicalPath = [string]$Assessment.canonicalPath
        journalProtocol = [pscustomobject][ordered]@{ path = $journalPath; tempPath = $journalTempPath; checkpointBeforeDestructiveStep = $true; writeThrough = $true; flushToDisk = $true; sameDirectoryAtomicRename = $true }
        operations = $operations
    }
    return Add-AwxDigest $plan 'planDigest'
}

function Assert-AwxOperationSchema {
    param($Operation)
    Assert-AwxExactProperties $Operation @(
        'schemaVersion', 'operationId', 'kind', 'checkpointAfter', 'coreExecutes', 'path', 'sourcePath', 'targetPath', 'finalPath',
        'expectedSha256', 'expectedBytes', 'expectedExists', 'exactLeaf', 'recursive', 'createNew', 'writeThrough',
        'flushToDisk', 'requireTargetAbsent', 'content', 'preimagePath', 'expectedPreExists', 'expectedPreSha256',
        'expectedPreBytes', 'expectedPreIsLeaf', 'expectedPreIsReparse', 'expectedTargetExistsBefore', 'postPath',
        'expectedPostExists', 'expectedPostSha256', 'expectedPostBytes', 'expectedPostIsLeaf', 'expectedPostIsReparse'
    ) 'plan-binding-invalid'
    if ([string]$Operation.schemaVersion -cne 'awx.notebook.directive.planned-operation.v3' -or
        [string]::IsNullOrWhiteSpace([string]$Operation.operationId) -or
        [string]$Operation.operationId -cnotmatch '^[a-z0-9][a-z0-9-]{0,95}$' -or
        [string]$Operation.kind -cnotin @('effect', 'read', 'checkpoint')) { throw 'plan-binding-invalid' }
    foreach ($name in @('checkpointAfter', 'coreExecutes', 'exactLeaf', 'recursive', 'createNew', 'writeThrough', 'flushToDisk', 'requireTargetAbsent')) {
        if (-not (Test-AwxBoolean (Get-AwxProperty $Operation $name))) { throw 'plan-binding-invalid' }
    }
    if ([bool]$Operation.coreExecutes) { throw 'plan-binding-invalid' }
    if (-not (Test-AwxInteger $Operation.expectedBytes) -or [long]$Operation.expectedBytes -lt 0) { throw 'plan-binding-invalid' }
    if ($null -ne $Operation.expectedSha256 -and -not (Test-AwxHash $Operation.expectedSha256)) { throw 'plan-binding-invalid' }
    if ($null -ne $Operation.expectedExists -and -not (Test-AwxBoolean $Operation.expectedExists)) { throw 'plan-binding-invalid' }
    foreach ($name in @('path', 'sourcePath', 'targetPath', 'finalPath', 'preimagePath', 'postPath')) {
        $path = [string](Get-AwxProperty $Operation $name)
        if (-not [string]::IsNullOrWhiteSpace($path)) { ConvertTo-AwxRelativePath $path | Out-Null }
    }
    foreach ($name in @('expectedPreExists', 'expectedPreIsLeaf', 'expectedPreIsReparse', 'expectedTargetExistsBefore', 'expectedPostExists', 'expectedPostIsLeaf', 'expectedPostIsReparse')) {
        $value = Get-AwxProperty $Operation $name
        if ($null -ne $value -and -not (Test-AwxBoolean $value)) { throw 'plan-binding-invalid' }
    }
    foreach ($name in @('expectedPreSha256', 'expectedPostSha256')) {
        $value = Get-AwxProperty $Operation $name
        if ($null -ne $value -and -not (Test-AwxHash $value)) { throw 'plan-binding-invalid' }
    }
    foreach ($name in @('expectedPreBytes', 'expectedPostBytes')) {
        $value = Get-AwxProperty $Operation $name
        if (-not (Test-AwxInteger $value) -or [long]$value -lt 0) { throw 'plan-binding-invalid' }
    }
    if ($null -ne $Operation.expectedPreExists -and [string]::IsNullOrWhiteSpace([string]$Operation.preimagePath)) { throw 'plan-binding-invalid' }
    if ($null -ne $Operation.expectedTargetExistsBefore -and [string]::IsNullOrWhiteSpace([string]$Operation.targetPath)) { throw 'plan-binding-invalid' }
    if ($null -ne $Operation.expectedPostExists -and [string]::IsNullOrWhiteSpace([string]$Operation.postPath)) { throw 'plan-binding-invalid' }
    if ($null -ne $Operation.content -and $Operation.content -isnot [string]) { throw 'plan-binding-invalid' }
    if ([string]$Operation.operationId -ceq 'stage-delete-exact') {
        if ([string]$Operation.preimagePath -cne [string]$Operation.path -or -not [bool]$Operation.expectedPreExists -or
            [string]$Operation.expectedPreSha256 -cne [string]$Operation.expectedSha256 -or
            [long]$Operation.expectedPreBytes -ne [long]$Operation.expectedBytes -or -not [bool]$Operation.expectedPreIsLeaf -or
            [bool]$Operation.expectedPreIsReparse -or [string]$Operation.postPath -cne [string]$Operation.path -or
            [bool]$Operation.expectedPostExists -or $null -ne $Operation.expectedPostSha256 -or [long]$Operation.expectedPostBytes -ne 0 -or
            [bool]$Operation.expectedPostIsLeaf -or [bool]$Operation.expectedPostIsReparse -or [bool]$Operation.recursive) { throw 'plan-binding-invalid' }
    }
    $isRename = [string]$Operation.operationId -ceq 'source-rename-stage' -or
        [string]$Operation.operationId -ceq 'archive-rename-final' -or
        [string]$Operation.operationId -ceq 'ready-rename-final' -or
        [string]$Operation.operationId -cmatch '^payload-[a-z0-9-]+-rename-final$'
    if ($isRename) {
        if ([string]::IsNullOrWhiteSpace([string]$Operation.sourcePath) -or [string]::IsNullOrWhiteSpace([string]$Operation.targetPath) -or
            [string]$Operation.preimagePath -cne [string]$Operation.sourcePath -or -not [bool]$Operation.expectedPreExists -or
            [string]$Operation.expectedPreSha256 -cne [string]$Operation.expectedSha256 -or
            [long]$Operation.expectedPreBytes -ne [long]$Operation.expectedBytes -or -not [bool]$Operation.expectedPreIsLeaf -or
            [bool]$Operation.expectedPreIsReparse -or $null -eq $Operation.expectedTargetExistsBefore -or [bool]$Operation.expectedTargetExistsBefore -or
            [string]$Operation.postPath -cne [string]$Operation.targetPath -or -not [bool]$Operation.expectedPostExists -or
            [string]$Operation.expectedPostSha256 -cne [string]$Operation.expectedSha256 -or
            [long]$Operation.expectedPostBytes -ne [long]$Operation.expectedBytes -or -not [bool]$Operation.expectedPostIsLeaf -or
            [bool]$Operation.expectedPostIsReparse) { throw 'plan-binding-invalid' }
    }
}

function Assert-AwxJournalProtocol {
    param($Protocol)
    Assert-AwxExactProperties $Protocol @(
        'path', 'tempPath', 'checkpointBeforeDestructiveStep', 'writeThrough', 'flushToDisk', 'sameDirectoryAtomicRename'
    ) 'plan-binding-invalid'
    ConvertTo-AwxRelativePath ([string]$Protocol.path) | Out-Null
    ConvertTo-AwxRelativePath ([string]$Protocol.tempPath) | Out-Null
    foreach ($name in @('checkpointBeforeDestructiveStep', 'writeThrough', 'flushToDisk', 'sameDirectoryAtomicRename')) {
        if (-not (Test-AwxBoolean (Get-AwxProperty $Protocol $name)) -or -not [bool](Get-AwxProperty $Protocol $name)) { throw 'plan-binding-invalid' }
    }
}

function Assert-AwxOperationSequence {
    param([object[]]$Operations)
    if (@($Operations).Count -eq 0) { throw 'plan-binding-invalid' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt @($Operations).Count; $index++) {
        $operation = @($Operations)[$index]
        Assert-AwxOperationSchema $operation
        if (-not $seen.Add([string]$operation.operationId)) { throw 'plan-binding-invalid' }
        if ([bool]$operation.checkpointAfter) {
            if ($index + 1 -ge @($Operations).Count -or [string]@($Operations)[$index + 1].kind -cne 'checkpoint') { throw 'plan-binding-invalid' }
        }
        if ([string]$operation.kind -ceq 'checkpoint' -and [bool]$operation.checkpointAfter) { throw 'plan-binding-invalid' }
    }
}

function Get-AwxRetirementOperationIds {
    return @(
        'archive-create-temp', 'checkpoint-archive-temp-created', 'archive-flush-temp', 'checkpoint-archive-temp-flushed',
        'archive-verify-temp', 'checkpoint-archive-temp-verified', 'checkpoint-archive-rename-intent', 'archive-rename-final',
        'checkpoint-archive-renamed', 'source-rehash', 'checkpoint-source-rehashed', 'checkpoint-stage-rename-intent',
        'source-rename-stage', 'checkpoint-stage-renamed', 'stage-rehash', 'checkpoint-stage-rehashed', 'archive-reverify',
        'checkpoint-archive-reverified', 'original-verify-absent', 'checkpoint-original-absent', 'canonical-verify-present',
        'checkpoint-delete-intent', 'stage-delete-exact', 'checkpoint-delete-observed'
    )
}

function Assert-AwxPlanStructure {
    param($Plan)
    try {
        $commonNames = @(
            'schemaVersion', 'planType', 'runId', 'assessmentDigest', 'authorityGranted', 'readyForTrustedAdapter', 'reasonCodes'
        )
        if ([string](Get-AwxProperty $Plan 'schemaVersion') -cne 'awx.notebook.directive.execution-plan.v2') { throw 'plan-binding-invalid' }
        Assert-AwxRunId ([string](Get-AwxProperty $Plan 'runId'))
        if (-not (Test-AwxHash (Get-AwxProperty $Plan 'assessmentDigest')) -or
            -not (Test-AwxBoolean (Get-AwxProperty $Plan 'authorityGranted')) -or [bool]$Plan.authorityGranted -or
            -not (Test-AwxBoolean (Get-AwxProperty $Plan 'readyForTrustedAdapter')) -or -not [bool]$Plan.readyForTrustedAdapter -or
            @((Get-AwxProperty $Plan 'reasonCodes' @())).Count -ne 0) { throw 'plan-binding-invalid' }
        if ([string]$Plan.planType -ceq 'retirement-item') {
            Assert-AwxExactProperties $Plan ($commonNames + @(
                'sourcePath', 'subjectPath', 'expectedSha256', 'expectedBytes', 'archivePath', 'archiveTempPath', 'stagePath',
                'canonicalPath', 'journalProtocol', 'operations', 'planDigest'
            )) 'plan-binding-invalid'
            foreach ($name in @('sourcePath', 'subjectPath', 'archivePath', 'archiveTempPath', 'stagePath', 'canonicalPath')) {
                ConvertTo-AwxRelativePath ([string](Get-AwxProperty $Plan $name)) | Out-Null
            }
            if ([string]$Plan.sourcePath -cne [string]$Plan.subjectPath -or -not (Test-AwxHash $Plan.expectedSha256) -or
                -not (Test-AwxInteger $Plan.expectedBytes) -or [long]$Plan.expectedBytes -lt 0) { throw 'plan-binding-invalid' }
            Assert-AwxJournalProtocol $Plan.journalProtocol
            Assert-AwxOperationSequence @($Plan.operations)
            $expectedIds = @(Get-AwxRetirementOperationIds)
            $actualIds = @($Plan.operations | ForEach-Object { [string]$_.operationId })
            if ((Get-AwxValueDigest $expectedIds) -cne (Get-AwxValueDigest $actualIds)) { throw 'plan-binding-invalid' }
            foreach ($pair in @(
                [pscustomobject]@{ operation = 'archive-rename-final'; checkpoint = 'checkpoint-archive-rename-intent' },
                [pscustomobject]@{ operation = 'source-rename-stage'; checkpoint = 'checkpoint-stage-rename-intent' },
                [pscustomobject]@{ operation = 'stage-delete-exact'; checkpoint = 'checkpoint-delete-intent' }
            )) {
                $position = [Array]::IndexOf($actualIds, $pair.operation)
                if ($position -le 0 -or $actualIds[$position - 1] -cne $pair.checkpoint) { throw 'plan-binding-invalid' }
            }
            $delete = @($Plan.operations | Where-Object { [string]$_.operationId -ceq 'stage-delete-exact' })[0]
            if (-not [bool]$delete.exactLeaf -or [bool]$delete.recursive) { throw 'plan-binding-invalid' }
        } elseif ([string]$Plan.planType -ceq 'consolidated-release') {
            Assert-AwxExactProperties $Plan ($commonNames + @(
                'subjectPath', 'releaseRoot', 'retirementJournalDigest', 'readbackDigest', 'gitMutationPlanned', 'tagProposalOnly',
                'payloads', 'readyMarker', 'journalProtocol', 'operations', 'planDigest'
            )) 'plan-binding-invalid'
            if (-not (Test-AwxHash $Plan.retirementJournalDigest) -or -not (Test-AwxHash $Plan.readbackDigest) -or
                -not (Test-AwxBoolean $Plan.gitMutationPlanned) -or [bool]$Plan.gitMutationPlanned -or
                -not (Test-AwxBoolean $Plan.tagProposalOnly) -or -not [bool]$Plan.tagProposalOnly) { throw 'plan-binding-invalid' }
            ConvertTo-AwxRelativePath ([string]$Plan.subjectPath) | Out-Null
            ConvertTo-AwxRelativePath ([string]$Plan.releaseRoot) | Out-Null
            $expectedReleaseRoot = "$script:ReleaseRoot/$($Plan.runId)"
            if ([string]$Plan.subjectPath -cne $expectedReleaseRoot -or [string]$Plan.releaseRoot -cne $expectedReleaseRoot) { throw 'plan-binding-invalid' }
            $requiredPayloadNames = @('retirement-manifest.json', 'release-manifest.json', 'CHANGELOG.md', 'tag-proposal.txt')
            $payloadRows = @($Plan.payloads)
            if ($payloadRows.Count -ne $requiredPayloadNames.Count) { throw 'plan-binding-invalid' }
            for ($payloadIndex = 0; $payloadIndex -lt $requiredPayloadNames.Count; $payloadIndex++) {
                $payload = $payloadRows[$payloadIndex]
                Assert-AwxExactProperties $payload @('name', 'sourcePath', 'path', 'sha256', 'bytes') 'plan-binding-invalid'
                $name = $requiredPayloadNames[$payloadIndex]
                if ([string]$payload.name -cne $name -or
                    [string]$payload.sourcePath -cne "$script:PreparedRoot/$($Plan.runId)/$name" -or
                    [string]$payload.path -cne "$expectedReleaseRoot/$name" -or
                    -not (Test-AwxHash $payload.sha256) -or -not (Test-AwxInteger $payload.bytes) -or [long]$payload.bytes -lt 0) { throw 'plan-binding-invalid' }
            }
            Assert-AwxExactProperties $Plan.readyMarker @(
                'path', 'tempPath', 'releaseManifestSha256', 'contentSha256', 'bytes', 'content'
            ) 'plan-binding-invalid'
            $releaseManifest = $payloadRows[1]
            $expectedReadyPath = "$expectedReleaseRoot/$($Plan.runId).ready"
            $expectedReadyTempPath = "$expectedReadyPath.tmp.$($Plan.runId)"
            $expectedReadyContent = "$($releaseManifest.sha256)`n"
            if ([string]$Plan.readyMarker.path -cne $expectedReadyPath -or [string]$Plan.readyMarker.tempPath -cne $expectedReadyTempPath -or
                [string]$Plan.readyMarker.releaseManifestSha256 -cne [string]$releaseManifest.sha256 -or
                [string]$Plan.readyMarker.content -cne $expectedReadyContent -or
                [string]$Plan.readyMarker.contentSha256 -cne (Get-AwxTextSha256 $expectedReadyContent) -or
                -not (Test-AwxInteger $Plan.readyMarker.bytes) -or [long]$Plan.readyMarker.bytes -ne (Get-AwxTextBytes $expectedReadyContent)) { throw 'plan-binding-invalid' }
            Assert-AwxJournalProtocol $Plan.journalProtocol
            if ([string]$Plan.journalProtocol.path -cne "$expectedReleaseRoot/release-journal.json" -or
                [string]$Plan.journalProtocol.tempPath -cne "$expectedReleaseRoot/release-journal.json.tmp.$($Plan.runId)") { throw 'plan-binding-invalid' }
            Assert-AwxOperationSequence @($Plan.operations)
            $last = @($Plan.operations)[@($Plan.operations).Count - 1]
            if ([string]$last.operationId -cne 'ready-rename-final' -or [bool]$last.checkpointAfter) { throw 'plan-binding-invalid' }
            $ids = @($Plan.operations | ForEach-Object { [string]$_.operationId })
            $readyIndex = [Array]::IndexOf($ids, 'ready-rename-final')
            if ($readyIndex -ne $ids.Count - 1 -or $readyIndex -le 0 -or $ids[$readyIndex - 1] -cne 'checkpoint-ready-rename-intent') { throw 'plan-binding-invalid' }
            $expectedOperations = @(Get-AwxReleaseOperations $payloadRows $Plan.readyMarker $Plan.journalProtocol ([string]$Plan.runId))
            if ((Get-AwxValueDigest $expectedOperations) -cne (Get-AwxValueDigest @($Plan.operations))) { throw 'plan-binding-invalid' }
        } else { throw 'plan-binding-invalid' }
        Assert-AwxDigest $Plan 'planDigest' 'plan-binding-invalid'
    } catch { throw 'plan-binding-invalid' }
}

function Assert-AwxPlanBinding {
    param($Assessment, $Plan)
    try {
        Assert-AwxAssessmentBinding $Assessment
        Assert-AwxPlanStructure $Plan
        if ([string]$Plan.runId -cne [string]$Assessment.runId -or [string]$Plan.assessmentDigest -cne [string]$Assessment.assessmentDigest) { throw 'plan-binding-invalid' }
        if (-not [bool]$Assessment.confirmRequested -or -not [bool]$Assessment.readyForTrustedAdapter -or
            [string]$Assessment.derivedDesktopFinalProof -cne 'verified') { throw 'plan-binding-invalid' }
        if ([string]$Plan.planType -ceq 'retirement-item') {
            $expected = New-AwxRetirementItemPlan -Assessment $Assessment -Path ([string]$Plan.subjectPath)
            if ((Get-AwxValueDigest $expected) -cne (Get-AwxValueDigest $Plan)) { throw 'plan-binding-invalid' }
        }
    } catch { throw 'plan-binding-invalid' }
}

function Get-AwxProgressProjection {
    param($Plan, [long]$NextIndex)
    $operations = @($Plan.operations)
    if ($NextIndex -lt 0 -or $NextIndex -gt $operations.Count) { throw 'progress-projection-invalid' }
    $lastOperationId = if ($NextIndex -eq 0) { '' } else { [string]$operations[$NextIndex - 1].operationId }
    $lastCheckpointId = ''
    $deleteIntentCommitted = $false
    $deleteObserved = $false
    for ($index = 0; $index -lt $NextIndex; $index++) {
        $operation = $operations[$index]
        if ([string]$operation.kind -ceq 'checkpoint') { $lastCheckpointId = [string]$operation.operationId }
        if ([string]$operation.operationId -ceq 'checkpoint-delete-intent') { $deleteIntentCommitted = $true }
        if ([string]$operation.operationId -ceq 'stage-delete-exact') { $deleteObserved = $true }
    }
    return [pscustomobject][ordered]@{
        lastOperationId = $lastOperationId; lastCheckpointId = $lastCheckpointId
        deleteIntentCommitted = $deleteIntentCommitted; deleteObserved = $deleteObserved
        completed = [bool]($NextIndex -eq $operations.Count)
    }
}

function New-AwxExecutionJournalEntry {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)]$Plan)
    Assert-AwxPlanBinding $Assessment $Plan
    $entry = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-journal-entry.v1'; runId = [string]$Plan.runId
        assessmentDigest = [string]$Plan.assessmentDigest; planDigest = [string]$Plan.planDigest; planType = [string]$Plan.planType
        subjectPath = [string]$Plan.subjectPath; nextIndex = [long]0; lastOperationId = ''; lastCheckpointId = ''
        deleteIntentCommitted = $false; deleteObserved = $false; complete = $false
    }
    return Add-AwxDigest $entry 'entryDigest'
}

function Assert-AwxExecutionJournalEntry {
    param($Assessment, $Plan, $JournalEntry, [string]$Reason = 'journal-entry-binding-invalid')
    try {
        Assert-AwxPlanBinding $Assessment $Plan
        Assert-AwxExactProperties $JournalEntry @(
            'schemaVersion', 'runId', 'assessmentDigest', 'planDigest', 'planType', 'subjectPath', 'nextIndex',
            'lastOperationId', 'lastCheckpointId', 'deleteIntentCommitted', 'deleteObserved', 'complete', 'entryDigest'
        ) $Reason
        if ([string]$JournalEntry.schemaVersion -cne 'awx.notebook.directive.execution-journal-entry.v1' -or
            [string]$JournalEntry.runId -cne [string]$Plan.runId -or
            [string]$JournalEntry.assessmentDigest -cne [string]$Plan.assessmentDigest -or
            [string]$JournalEntry.planDigest -cne [string]$Plan.planDigest -or
            [string]$JournalEntry.planType -cne [string]$Plan.planType -or
            [string]$JournalEntry.subjectPath -cne [string]$Plan.subjectPath -or
            -not (Test-AwxInteger $JournalEntry.nextIndex)) { throw $Reason }
        foreach ($name in @('deleteIntentCommitted', 'deleteObserved', 'complete')) {
            if (-not (Test-AwxBoolean (Get-AwxProperty $JournalEntry $name))) { throw $Reason }
        }
        $projection = Get-AwxProgressProjection $Plan ([long]$JournalEntry.nextIndex)
        if ([string]$JournalEntry.lastOperationId -cne [string]$projection.lastOperationId -or
            [string]$JournalEntry.lastCheckpointId -cne [string]$projection.lastCheckpointId -or
            [bool]$JournalEntry.deleteIntentCommitted -ne [bool]$projection.deleteIntentCommitted -or
            [bool]$JournalEntry.deleteObserved -ne [bool]$projection.deleteObserved -or
            [bool]$JournalEntry.complete -ne [bool]$projection.completed) { throw $Reason }
        Assert-AwxDigest $JournalEntry 'entryDigest' $Reason
    } catch { throw $Reason }
}

function Assert-AwxExecutionEvidence {
    param($Assessment, $StabilitySnapshot, $GateSnapshot, [DateTimeOffset]$NowUtc)
    $reasons = @()
    $reasons += @(Get-AwxFrozenStabilityReasons $Assessment $StabilitySnapshot $NowUtc)
    $reasons += @(Get-AwxGateReasons $GateSnapshot $NowUtc)
    if (@(Get-AwxDistinctStrings $reasons).Count -gt 0) { throw 'execution-evidence-invalid' }
}

function New-AwxExecutionCursor {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)]$Plan,
        [Parameter(Mandatory = $true)]$JournalEntry, [Parameter(Mandatory = $true)]$StabilitySnapshot,
        [Parameter(Mandatory = $true)]$GateSnapshot, [Parameter(Mandatory = $true)][DateTimeOffset]$NowUtc,
        $Readbacks = $null
    )
    Assert-AwxExecutionJournalEntry $Assessment $Plan $JournalEntry
    Assert-AwxExecutionEvidence $Assessment $StabilitySnapshot $GateSnapshot $NowUtc
    if ([string]$Plan.planType -ceq 'consolidated-release') { Assert-AwxReleaseReadbacksMatchPlan $Assessment $Plan $Readbacks }
    $cursor = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-cursor.v1'; runId = [string]$JournalEntry.runId
        assessmentDigest = [string]$JournalEntry.assessmentDigest; planDigest = [string]$JournalEntry.planDigest
        planType = [string]$JournalEntry.planType; subjectPath = [string]$JournalEntry.subjectPath
        nextIndex = [long]$JournalEntry.nextIndex; lastOperationId = [string]$JournalEntry.lastOperationId
        lastCheckpointId = [string]$JournalEntry.lastCheckpointId; deleteIntentCommitted = [bool]$JournalEntry.deleteIntentCommitted
        deleteObserved = [bool]$JournalEntry.deleteObserved; completed = [bool]$JournalEntry.complete
    }
    return Add-AwxDigest $cursor 'cursorDigest'
}

function Assert-AwxExecutionCursor {
    param($Assessment, $Plan, $Cursor)
    try {
        Assert-AwxPlanBinding $Assessment $Plan
        Assert-AwxExactProperties $Cursor @(
            'schemaVersion', 'runId', 'assessmentDigest', 'planDigest', 'planType', 'subjectPath', 'nextIndex',
            'lastOperationId', 'lastCheckpointId', 'deleteIntentCommitted', 'deleteObserved', 'completed', 'cursorDigest'
        ) 'cursor-binding-invalid'
        if ([string]$Cursor.schemaVersion -cne 'awx.notebook.directive.execution-cursor.v1' -or
            [string]$Cursor.runId -cne [string]$Plan.runId -or [string]$Cursor.assessmentDigest -cne [string]$Plan.assessmentDigest -or
            [string]$Cursor.planDigest -cne [string]$Plan.planDigest -or [string]$Cursor.planType -cne [string]$Plan.planType -or
            [string]$Cursor.subjectPath -cne [string]$Plan.subjectPath -or -not (Test-AwxInteger $Cursor.nextIndex)) { throw 'cursor-binding-invalid' }
        foreach ($name in @('deleteIntentCommitted', 'deleteObserved', 'completed')) {
            if (-not (Test-AwxBoolean (Get-AwxProperty $Cursor $name))) { throw 'cursor-binding-invalid' }
        }
        $projection = Get-AwxProgressProjection $Plan ([long]$Cursor.nextIndex)
        if ([string]$Cursor.lastOperationId -cne [string]$projection.lastOperationId -or
            [string]$Cursor.lastCheckpointId -cne [string]$projection.lastCheckpointId -or
            [bool]$Cursor.deleteIntentCommitted -ne [bool]$projection.deleteIntentCommitted -or
            [bool]$Cursor.deleteObserved -ne [bool]$projection.deleteObserved -or
            [bool]$Cursor.completed -ne [bool]$projection.completed) { throw 'cursor-binding-invalid' }
        Assert-AwxDigest $Cursor 'cursorDigest' 'cursor-binding-invalid'
    } catch { throw 'cursor-binding-invalid' }
}

function Assert-AwxCallbackResult {
    param($Result, $Plan, $Operation)
    Assert-AwxExactProperties $Result @(
        'schemaVersion', 'runId', 'planDigest', 'operationId', 'ok',
        'preimagePath', 'preExists', 'preSha256', 'preBytes', 'preIsLeaf', 'preIsReparse',
        'targetPath', 'targetExistsBefore',
        'postPath', 'postExists', 'postSha256', 'postBytes', 'postIsLeaf', 'postIsReparse'
    ) 'callback-result-invalid'
    if ([string]$Result.schemaVersion -cne 'awx.notebook.directive.cursor-callback-result.v2' -or
        [string]$Result.runId -cne [string]$Plan.runId -or [string]$Result.planDigest -cne [string]$Plan.planDigest -or
        [string]$Result.operationId -cne [string]$Operation.operationId -or -not (Test-AwxBoolean $Result.ok)) { throw 'callback-result-invalid' }
    foreach ($name in @('preExists', 'preIsLeaf', 'preIsReparse', 'targetExistsBefore', 'postExists', 'postIsLeaf', 'postIsReparse')) {
        if (-not (Test-AwxBoolean (Get-AwxProperty $Result $name))) { throw 'callback-result-invalid' }
    }
    foreach ($name in @('preBytes', 'postBytes')) {
        if (-not (Test-AwxInteger (Get-AwxProperty $Result $name)) -or [long](Get-AwxProperty $Result $name) -lt 0) { throw 'callback-result-invalid' }
    }
    foreach ($name in @('preSha256', 'postSha256')) {
        $value = Get-AwxProperty $Result $name
        if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value) -and -not (Test-AwxHash $value)) { throw 'callback-result-invalid' }
    }
    if (-not [bool]$Result.ok) { throw 'callback-operation-failed' }
    if ([string]$Result.preimagePath -cne [string]$Operation.preimagePath) { throw 'callback-preimage-mismatch' }
    if ($null -ne $Operation.expectedPreExists -and [bool]$Result.preExists -ne [bool]$Operation.expectedPreExists) { throw 'callback-preimage-mismatch' }
    if ($null -ne $Operation.expectedPreSha256 -and [string]$Result.preSha256 -cne [string]$Operation.expectedPreSha256) { throw 'callback-preimage-mismatch' }
    if ($null -ne $Operation.expectedPreExists -and -not [bool]$Operation.expectedPreExists -and
        -not [string]::IsNullOrWhiteSpace([string]$Result.preSha256)) { throw 'callback-preimage-mismatch' }
    if ($null -ne $Operation.expectedPreExists -and [long]$Result.preBytes -ne [long]$Operation.expectedPreBytes) { throw 'callback-preimage-mismatch' }
    if ($null -ne $Operation.expectedPreIsLeaf -and [bool]$Result.preIsLeaf -ne [bool]$Operation.expectedPreIsLeaf) { throw 'callback-preimage-mismatch' }
    if ($null -ne $Operation.expectedPreIsReparse -and [bool]$Result.preIsReparse -ne [bool]$Operation.expectedPreIsReparse) { throw 'callback-preimage-mismatch' }
    if ([string]$Result.targetPath -cne [string]$Operation.targetPath) { throw 'callback-target-before-mismatch' }
    if ($null -ne $Operation.expectedTargetExistsBefore -and [bool]$Result.targetExistsBefore -ne [bool]$Operation.expectedTargetExistsBefore) {
        throw 'callback-target-before-mismatch'
    }
    if ([string]$Result.postPath -cne [string]$Operation.postPath) { throw 'callback-postimage-mismatch' }
    if ($null -ne $Operation.expectedPostExists -and [bool]$Result.postExists -ne [bool]$Operation.expectedPostExists) { throw 'callback-postimage-mismatch' }
    if ($null -ne $Operation.expectedPostSha256 -and [string]$Result.postSha256 -cne [string]$Operation.expectedPostSha256) { throw 'callback-postimage-mismatch' }
    if ($null -ne $Operation.expectedPostExists -and -not [bool]$Operation.expectedPostExists -and
        -not [string]::IsNullOrWhiteSpace([string]$Result.postSha256)) { throw 'callback-postimage-mismatch' }
    if ($null -ne $Operation.expectedPostExists -and [long]$Result.postBytes -ne [long]$Operation.expectedPostBytes) { throw 'callback-postimage-mismatch' }
    if ($null -ne $Operation.expectedPostIsLeaf -and [bool]$Result.postIsLeaf -ne [bool]$Operation.expectedPostIsLeaf) { throw 'callback-postimage-mismatch' }
    if ($null -ne $Operation.expectedPostIsReparse -and [bool]$Result.postIsReparse -ne [bool]$Operation.expectedPostIsReparse) { throw 'callback-postimage-mismatch' }
}

function Invoke-AwxExecutionCursorStep {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)]$Plan,
        [Parameter(Mandatory = $true)]$Cursor, [Parameter(Mandatory = $true)]$Callbacks,
        [Parameter(Mandatory = $true)]$StabilitySnapshot, [Parameter(Mandatory = $true)]$GateSnapshot,
        [Parameter(Mandatory = $true)][DateTimeOffset]$NowUtc, $Readbacks = $null
    )
    Assert-AwxExecutionCursor $Assessment $Plan $Cursor
    if ([bool]$Cursor.completed) { throw 'cursor-complete' }
    Assert-AwxExecutionEvidence $Assessment $StabilitySnapshot $GateSnapshot $NowUtc
    if ([string]$Plan.planType -ceq 'consolidated-release') { Assert-AwxReleaseReadbacksMatchPlan $Assessment $Plan $Readbacks }
    try {
        Assert-AwxExactProperties $Callbacks @('Dispatch') 'callback-contract-invalid'
        if ((Get-AwxProperty $Callbacks 'Dispatch') -isnot [scriptblock]) { throw 'callback-contract-invalid' }
    } catch { throw 'callback-contract-invalid' }
    $operations = @($Plan.operations)
    $operation = $operations[[int]$Cursor.nextIndex]
    $requiredCheckpoint = switch ([string]$operation.operationId) {
        'archive-rename-final' { 'checkpoint-archive-rename-intent' }
        'source-rename-stage' { 'checkpoint-stage-rename-intent' }
        'stage-delete-exact' { 'checkpoint-delete-intent' }
        'ready-rename-final' { 'checkpoint-ready-rename-intent' }
        default { '' }
    }
    if (-not [string]::IsNullOrWhiteSpace($requiredCheckpoint) -and [string]$Cursor.lastCheckpointId -cne $requiredCheckpoint) { throw 'checkpoint-not-committed' }
    if ([string]$operation.operationId -ceq 'stage-delete-exact' -and -not [bool]$Cursor.deleteIntentCommitted) { throw 'delete-intent-not-committed' }
    if ([string]$operation.operationId -ceq 'checkpoint-delete-observed' -and -not [bool]$Cursor.deleteObserved) { throw 'delete-not-observed' }
    if ([string]$operation.operationId -ceq 'ready-rename-final' -and [long]$Cursor.nextIndex -ne $operations.Count - 1) { throw 'ready-not-terminal' }
    $dispatchOperation = Copy-AwxValue $operation
    $checkpointEntry = $null
    if ([string]$operation.kind -ceq 'checkpoint') {
        $checkpointNextIndex = [long]$Cursor.nextIndex + 1
        $checkpointProjection = Get-AwxProgressProjection $Plan $checkpointNextIndex
        $checkpointEntry = [pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.execution-journal-entry.v1'; runId = [string]$Plan.runId
            assessmentDigest = [string]$Plan.assessmentDigest; planDigest = [string]$Plan.planDigest; planType = [string]$Plan.planType
            subjectPath = [string]$Plan.subjectPath; nextIndex = $checkpointNextIndex
            lastOperationId = [string]$checkpointProjection.lastOperationId; lastCheckpointId = [string]$checkpointProjection.lastCheckpointId
            deleteIntentCommitted = [bool]$checkpointProjection.deleteIntentCommitted; deleteObserved = [bool]$checkpointProjection.deleteObserved
            complete = [bool]$checkpointProjection.completed
        }
        $checkpointEntry = Add-AwxDigest $checkpointEntry 'entryDigest'
        $checkpointContent = ($checkpointEntry | ConvertTo-Json -Compress -Depth 60) + "`n"
        $dispatchOperation.expectedSha256 = Get-AwxTextSha256 $checkpointContent
        $dispatchOperation.expectedBytes = Get-AwxTextBytes $checkpointContent
        $dispatchOperation.expectedExists = $true
        $dispatchOperation.content = $checkpointContent
        $dispatchOperation.preimagePath = [string]$Plan.journalProtocol.path
        $dispatchOperation.expectedPreExists = $null
        $dispatchOperation.expectedPreSha256 = $null
        $dispatchOperation.expectedPreBytes = [long]0
        $dispatchOperation.expectedPreIsLeaf = $null
        $dispatchOperation.expectedPreIsReparse = $null
        $dispatchOperation.sourcePath = [string]$Plan.journalProtocol.tempPath
        $dispatchOperation.targetPath = [string]$Plan.journalProtocol.path
        $dispatchOperation.finalPath = [string]$Plan.journalProtocol.path
        $dispatchOperation.expectedTargetExistsBefore = $null
        $dispatchOperation.postPath = [string]$Plan.journalProtocol.path
        $dispatchOperation.expectedPostExists = $true
        $dispatchOperation.expectedPostSha256 = [string]$dispatchOperation.expectedSha256
        $dispatchOperation.expectedPostBytes = [long]$dispatchOperation.expectedBytes
        $dispatchOperation.expectedPostIsLeaf = $true
        $dispatchOperation.expectedPostIsReparse = $false
    }
    $request = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.cursor-dispatch-request.v1'; runId = [string]$Plan.runId
        assessmentDigest = [string]$Plan.assessmentDigest; planDigest = [string]$Plan.planDigest
        cursorDigest = [string]$Cursor.cursorDigest; journalProtocol = Copy-AwxValue $Plan.journalProtocol
        checkpointEntry = $checkpointEntry; operation = $dispatchOperation
    }
    $result = & $Callbacks.Dispatch $request
    Assert-AwxCallbackResult $result $Plan $dispatchOperation
    $nextIndex = [long]$Cursor.nextIndex + 1
    $projection = Get-AwxProgressProjection $Plan $nextIndex
    $nextCursor = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-cursor.v1'; runId = [string]$Plan.runId
        assessmentDigest = [string]$Plan.assessmentDigest; planDigest = [string]$Plan.planDigest; planType = [string]$Plan.planType
        subjectPath = [string]$Plan.subjectPath; nextIndex = $nextIndex; lastOperationId = [string]$projection.lastOperationId
        lastCheckpointId = [string]$projection.lastCheckpointId; deleteIntentCommitted = [bool]$projection.deleteIntentCommitted
        deleteObserved = [bool]$projection.deleteObserved; completed = [bool]$projection.completed
    }
    $nextCursor = Add-AwxDigest $nextCursor 'cursorDigest'
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-step-result.v1'; operationId = [string]$operation.operationId
        cursor = $nextCursor
    }
}

function ConvertTo-AwxExecutionJournalEntry {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)]$Plan, [Parameter(Mandatory = $true)]$Cursor)
    Assert-AwxExecutionCursor $Assessment $Plan $Cursor
    $entry = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-journal-entry.v1'; runId = [string]$Cursor.runId
        assessmentDigest = [string]$Cursor.assessmentDigest; planDigest = [string]$Cursor.planDigest; planType = [string]$Cursor.planType
        subjectPath = [string]$Cursor.subjectPath; nextIndex = [long]$Cursor.nextIndex; lastOperationId = [string]$Cursor.lastOperationId
        lastCheckpointId = [string]$Cursor.lastCheckpointId; deleteIntentCommitted = [bool]$Cursor.deleteIntentCommitted
        deleteObserved = [bool]$Cursor.deleteObserved; complete = [bool]$Cursor.completed
    }
    return Add-AwxDigest $entry 'entryDigest'
}

function Assert-AwxRetirementJournalBinding {
    param($Assessment, $RetirementJournal)
    try {
        Assert-AwxAssessmentBinding $Assessment
        Assert-AwxExactProperties $RetirementJournal @(
            'schemaVersion', 'runId', 'assessmentDigest', 'complete', 'items', 'journalDigest'
        ) 'retirement-journal-binding-invalid'
        if ([string]$RetirementJournal.schemaVersion -cne 'awx.notebook.directive.retirement-journal.v2' -or
            [string]$RetirementJournal.runId -cne [string]$Assessment.runId -or
            [string]$RetirementJournal.assessmentDigest -cne [string]$Assessment.assessmentDigest -or
            -not (Test-AwxBoolean $RetirementJournal.complete) -or -not [bool]$RetirementJournal.complete) { throw 'retirement-journal-binding-invalid' }
        Assert-AwxDigest $RetirementJournal 'journalDigest' 'retirement-journal-binding-invalid'
        $eligible = @($Assessment.items | Where-Object { [string]$_.disposition -ceq 'eligible' })
        $entries = @($RetirementJournal.items)
        if ($entries.Count -ne $eligible.Count) { throw 'retirement-journal-binding-invalid' }
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($item in $eligible) {
            $path = [string]$item.path
            $matches = @($entries | Where-Object { [string]$_.subjectPath -ceq $path })
            if ($matches.Count -ne 1 -or -not $seen.Add($path)) { throw 'retirement-journal-binding-invalid' }
            $plan = New-AwxRetirementItemPlan -Assessment $Assessment -Path $path
            Assert-AwxExecutionJournalEntry $Assessment $plan $matches[0] 'retirement-journal-binding-invalid'
            if (-not [bool]$matches[0].complete -or -not [bool]$matches[0].deleteIntentCommitted -or -not [bool]$matches[0].deleteObserved) { throw 'retirement-journal-binding-invalid' }
        }
    } catch { throw 'retirement-journal-binding-invalid' }
}

function New-AwxRetirementJournal {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)][object[]]$Entries)
    try {
        Assert-AwxAssessmentBinding $Assessment
        if (-not [bool]$Assessment.confirmRequested -or -not [bool]$Assessment.readyForTrustedAdapter) { throw 'retirement-journal-binding-invalid' }
        $eligible = @($Assessment.items | Where-Object { [string]$_.disposition -ceq 'eligible' })
        if (@($Entries).Count -ne $eligible.Count) { throw 'retirement-journal-binding-invalid' }
        $orderedEntries = @()
        foreach ($item in $eligible) {
            $matches = @($Entries | Where-Object { [string]$_.subjectPath -ceq [string]$item.path })
            if ($matches.Count -ne 1) { throw 'retirement-journal-binding-invalid' }
            $plan = New-AwxRetirementItemPlan -Assessment $Assessment -Path ([string]$item.path)
            Assert-AwxExecutionJournalEntry $Assessment $plan $matches[0] 'retirement-journal-binding-invalid'
            if (-not [bool]$matches[0].complete -or -not [bool]$matches[0].deleteObserved) { throw 'retirement-journal-binding-invalid' }
            $orderedEntries += Copy-AwxValue $matches[0]
        }
        $journal = [pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.retirement-journal.v2'; runId = [string]$Assessment.runId
            assessmentDigest = [string]$Assessment.assessmentDigest; complete = $true; items = $orderedEntries
        }
        $journal = Add-AwxDigest $journal 'journalDigest'
        Assert-AwxRetirementJournalBinding $Assessment $journal
        return $journal
    } catch { throw 'retirement-journal-binding-invalid' }
}

function Assert-AwxRetirementObservation {
    param($Observation)
    Assert-AwxExactProperties $Observation @(
        'schemaVersion', 'sourceExists', 'stageExists', 'archiveTempExists', 'archiveExists', 'sourceSha256',
        'stageSha256', 'archiveTempSha256', 'archiveSha256', 'canonicalExists'
    ) 'retirement-observation-invalid'
    if ([string]$Observation.schemaVersion -cne 'awx.notebook.directive.retirement-observation.v1') { throw 'retirement-observation-invalid' }
    foreach ($name in @('sourceExists', 'stageExists', 'archiveTempExists', 'archiveExists', 'canonicalExists')) {
        if (-not (Test-AwxBoolean (Get-AwxProperty $Observation $name))) { throw 'retirement-observation-invalid' }
    }
    foreach ($pair in @(
        [pscustomobject]@{ exists = 'sourceExists'; hash = 'sourceSha256' },
        [pscustomobject]@{ exists = 'stageExists'; hash = 'stageSha256' },
        [pscustomobject]@{ exists = 'archiveTempExists'; hash = 'archiveTempSha256' },
        [pscustomobject]@{ exists = 'archiveExists'; hash = 'archiveSha256' }
    )) {
        $exists = [bool](Get-AwxProperty $Observation $pair.exists)
        $hash = Get-AwxProperty $Observation $pair.hash
        if ($exists -and -not (Test-AwxHash $hash)) { throw 'retirement-observation-invalid' }
        if (-not $exists -and $null -ne $hash -and -not [string]::IsNullOrWhiteSpace([string]$hash)) { throw 'retirement-observation-invalid' }
    }
}

function New-AwxReplayResult {
    param([string]$Action, [string]$Reason = '', [string]$NextOperationId = '')
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.retirement-replay.v2'; action = $Action; reason = $Reason
        nextOperationId = $NextOperationId; sourcePath = ''; targetPath = ''; expectedSha256 = $null
        onlyIfOriginalAbsent = $false; exactLeaf = $true; recursive = $false
    }
}

function Get-AwxRetirementReplayPlan {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$ItemPlan, [Parameter(Mandatory = $true)]$JournalEntry, [Parameter(Mandatory = $true)]$Observation)
    try {
        Assert-AwxPlanStructure $ItemPlan
        if ([string]$ItemPlan.planType -cne 'retirement-item') { throw 'retirement-replay-binding-invalid' }
        Assert-AwxExactProperties $JournalEntry @(
            'schemaVersion', 'runId', 'assessmentDigest', 'planDigest', 'planType', 'subjectPath', 'nextIndex',
            'lastOperationId', 'lastCheckpointId', 'deleteIntentCommitted', 'deleteObserved', 'complete', 'entryDigest'
        ) 'retirement-replay-binding-invalid'
        if ([string]$JournalEntry.schemaVersion -cne 'awx.notebook.directive.execution-journal-entry.v1' -or
            [string]$JournalEntry.runId -cne [string]$ItemPlan.runId -or [string]$JournalEntry.assessmentDigest -cne [string]$ItemPlan.assessmentDigest -or
            [string]$JournalEntry.planDigest -cne [string]$ItemPlan.planDigest -or [string]$JournalEntry.planType -cne 'retirement-item' -or
            [string]$JournalEntry.subjectPath -cne [string]$ItemPlan.subjectPath -or -not (Test-AwxInteger $JournalEntry.nextIndex)) { throw 'retirement-replay-binding-invalid' }
        foreach ($name in @('deleteIntentCommitted', 'deleteObserved', 'complete')) {
            if (-not (Test-AwxBoolean (Get-AwxProperty $JournalEntry $name))) { throw 'retirement-replay-binding-invalid' }
        }
        $projection = Get-AwxProgressProjection $ItemPlan ([long]$JournalEntry.nextIndex)
        if ([string]$JournalEntry.lastOperationId -cne [string]$projection.lastOperationId -or
            [string]$JournalEntry.lastCheckpointId -cne [string]$projection.lastCheckpointId -or
            [bool]$JournalEntry.deleteIntentCommitted -ne [bool]$projection.deleteIntentCommitted -or
            [bool]$JournalEntry.deleteObserved -ne [bool]$projection.deleteObserved -or [bool]$JournalEntry.complete -ne [bool]$projection.completed) { throw 'retirement-replay-binding-invalid' }
        Assert-AwxDigest $JournalEntry 'entryDigest' 'retirement-replay-binding-invalid'
        Assert-AwxRetirementObservation $Observation
    } catch { throw 'retirement-replay-binding-invalid' }

    if (-not [bool]$Observation.canonicalExists) { return New-AwxReplayResult 'fail-closed' 'canonical-missing' }
    $expected = [string]$ItemPlan.expectedSha256
    if ([bool]$Observation.sourceExists -and [string]$Observation.sourceSha256 -cne $expected) { return New-AwxReplayResult 'fail-closed' 'retirement-source-collision' }
    if ([bool]$Observation.sourceExists -and [bool]$Observation.stageExists) { return New-AwxReplayResult 'fail-closed' 'retirement-stage-collision' }
    if ([bool]$JournalEntry.complete) {
        if (-not [bool]$Observation.sourceExists -and -not [bool]$Observation.stageExists -and -not [bool]$Observation.archiveTempExists -and
            [bool]$Observation.archiveExists -and [string]$Observation.archiveSha256 -ceq $expected) {
            return New-AwxReplayResult 'complete'
        }
        return New-AwxReplayResult 'fail-closed' 'completed-state-mismatch'
    }
    if ([bool]$Observation.archiveTempExists) {
        if ([string]$Observation.archiveTempSha256 -cne $expected -or [bool]$Observation.archiveExists -or [bool]$Observation.stageExists) {
            return New-AwxReplayResult 'fail-closed' 'archive-temp-collision'
        }
        if ([string]$JournalEntry.lastCheckpointId -cin @('checkpoint-archive-temp-created', 'checkpoint-archive-temp-flushed', 'checkpoint-archive-temp-verified', 'checkpoint-archive-rename-intent')) {
            $next = switch ([string]$JournalEntry.lastCheckpointId) {
                'checkpoint-archive-temp-created' { 'archive-flush-temp' }
                'checkpoint-archive-temp-flushed' { 'archive-verify-temp' }
                'checkpoint-archive-temp-verified' { 'checkpoint-archive-rename-intent' }
                default { 'archive-rename-final' }
            }
            return New-AwxReplayResult 'resume' '' $next
        }
        return New-AwxReplayResult 'fail-closed' 'archive-temp-uncommitted'
    }
    if ([bool]$Observation.stageExists) {
        if ([string]$Observation.stageSha256 -cne $expected) { return New-AwxReplayResult 'fail-closed' 'retirement-stage-collision' }
        if (-not [bool]$Observation.archiveExists -or [string]$Observation.archiveSha256 -cne $expected) {
            if ([bool]$Observation.sourceExists) { return New-AwxReplayResult 'fail-closed' 'retirement-stage-collision' }
            $restore = New-AwxReplayResult 'restore-staged-source' 'archive-invalid-after-stage'
            $restore.sourcePath = [string]$ItemPlan.stagePath; $restore.targetPath = [string]$ItemPlan.sourcePath
            $restore.expectedSha256 = $expected; $restore.onlyIfOriginalAbsent = $true
            return $restore
        }
        if ([bool]$Observation.sourceExists) { return New-AwxReplayResult 'fail-closed' 'retirement-stage-collision' }
        if ([string]$JournalEntry.lastCheckpointId -ceq 'checkpoint-stage-rename-intent') {
            return New-AwxReplayResult 'resume' '' 'checkpoint-stage-renamed'
        }
        return New-AwxReplayResult 'resume' '' 'stage-rehash'
    }
    if ([bool]$JournalEntry.deleteIntentCommitted -and -not [bool]$Observation.sourceExists -and
        [bool]$Observation.archiveExists -and [string]$Observation.archiveSha256 -ceq $expected) {
        return New-AwxReplayResult 'resume' '' 'checkpoint-delete-observed'
    }
    if ([bool]$Observation.archiveExists -and [string]$Observation.archiveSha256 -cne $expected) { return New-AwxReplayResult 'fail-closed' 'archive-collision' }
    if ([bool]$Observation.sourceExists) {
        if ([bool]$Observation.archiveExists -and [string]$JournalEntry.lastCheckpointId -ceq 'checkpoint-archive-rename-intent') {
            return New-AwxReplayResult 'resume' '' 'checkpoint-archive-renamed'
        }
        $nextIndex = [long]$JournalEntry.nextIndex
        if ($nextIndex -lt @($ItemPlan.operations).Count) {
            return New-AwxReplayResult 'resume' '' ([string]@($ItemPlan.operations)[$nextIndex].operationId)
        }
    }
    return New-AwxReplayResult 'fail-closed' 'retirement-state-unrecognized'
}

function Get-AwxTextSha256 {
    param([string]$Text)
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-AwxTextBytes {
    param([string]$Text)
    return [long][Text.UTF8Encoding]::new($false).GetByteCount($Text)
}

function Get-AwxReleasePayloadRows {
    param([object[]]$Payloads, [string]$RunId)
    $requiredNames = @('retirement-manifest.json', 'release-manifest.json', 'CHANGELOG.md', 'tag-proposal.txt')
    if (@($Payloads).Count -ne $requiredNames.Count) { throw 'release-payload-incomplete' }
    $byName = @{}
    foreach ($payload in @($Payloads)) {
        Assert-AwxExactProperties $payload @('name', 'sourcePath', 'sha256', 'bytes') 'release-payload-invalid'
        $name = [string]$payload.name
        if ($requiredNames -cnotcontains $name -or $byName.ContainsKey($name)) { throw 'release-payload-invalid' }
        $sourcePath = ConvertTo-AwxRelativePath ([string]$payload.sourcePath)
        if ($sourcePath -cne "$script:PreparedRoot/$RunId/$name" -or -not (Test-AwxHash $payload.sha256) -or
            -not (Test-AwxInteger $payload.bytes) -or [long]$payload.bytes -lt 0) { throw 'release-payload-invalid' }
        $byName[$name] = [pscustomobject][ordered]@{
            name = $name; sourcePath = $sourcePath; path = "$script:ReleaseRoot/$RunId/$name"
            sha256 = [string]$payload.sha256; bytes = [long]$payload.bytes
        }
    }
    $rows = @()
    foreach ($name in $requiredNames) {
        if (-not $byName.ContainsKey($name)) { throw 'release-payload-incomplete' }
        $rows += $byName[$name]
    }
    return @($rows)
}

function Get-AwxLeafReadbackEvidence {
    param($Assessment, [object[]]$Payloads, $Readbacks)
    $reasons = @(); $records = @()
    try {
        Assert-AwxExactProperties $Readbacks @('ReadArchive', 'ReadPayload') 'release-readback-contract-invalid'
        if ((Get-AwxProperty $Readbacks 'ReadArchive') -isnot [scriptblock] -or
            (Get-AwxProperty $Readbacks 'ReadPayload') -isnot [scriptblock]) { throw 'release-readback-contract-invalid' }
    } catch {
        return [pscustomobject][ordered]@{ reasons = @('release-readback-contract-invalid'); records = @(); digest = ('0' * 64) }
    }
    foreach ($item in @($Assessment.items | Where-Object { [string]$_.disposition -ceq 'eligible' })) {
        try {
            $request = [pscustomobject][ordered]@{
                schemaVersion = 'awx.notebook.directive.leaf-read-request.v1'; runId = [string]$Assessment.runId
                path = [string]$item.archivePath; expectedSha256 = [string]$item.expectedSha256; expectedBytes = [long]$item.expectedBytes
            }
            $readback = & $Readbacks.ReadArchive $request
            Assert-AwxExactProperties $readback @('schemaVersion', 'path', 'exists', 'isLeaf', 'isReparse', 'containedRepositoryLeaf', 'sha256', 'bytes') 'release-readback-contract-invalid'
            if ([string]$readback.schemaVersion -cne 'awx.notebook.directive.leaf-readback.v1' -or
                -not (Test-AwxBoolean $readback.exists) -or -not (Test-AwxBoolean $readback.isLeaf) -or
                -not (Test-AwxBoolean $readback.isReparse) -or -not (Test-AwxBoolean $readback.containedRepositoryLeaf) -or
                -not (Test-AwxInteger $readback.bytes) -or [long]$readback.bytes -lt 0) { throw 'release-readback-contract-invalid' }
            if (-not [bool]$readback.exists -or -not [bool]$readback.isLeaf -or [bool]$readback.isReparse -or -not [bool]$readback.containedRepositoryLeaf) {
                $reasons += 'release-archive-readback-unsafe'
            } elseif ([string]$readback.path -cne [string]$item.archivePath -or [string]$readback.sha256 -cne [string]$item.expectedSha256 -or
                [long]$readback.bytes -ne [long]$item.expectedBytes) { $reasons += 'release-archive-readback-mismatch' }
            $records += [pscustomobject][ordered]@{
                kind = 'archive'; name = [string]$item.path; path = [string]$readback.path; exists = [bool]$readback.exists
                isLeaf = [bool]$readback.isLeaf; isReparse = [bool]$readback.isReparse
                containedRepositoryLeaf = [bool]$readback.containedRepositoryLeaf; sha256 = [string]$readback.sha256; bytes = [long]$readback.bytes
            }
        } catch { $reasons += 'release-readback-contract-invalid' }
    }
    foreach ($payload in @($Payloads)) {
        try {
            $request = [pscustomobject][ordered]@{
                schemaVersion = 'awx.notebook.directive.leaf-read-request.v1'; runId = [string]$Assessment.runId
                path = [string]$payload.sourcePath; expectedSha256 = [string]$payload.sha256; expectedBytes = [long]$payload.bytes
            }
            $readback = & $Readbacks.ReadPayload $request
            Assert-AwxExactProperties $readback @('schemaVersion', 'path', 'exists', 'isLeaf', 'isReparse', 'containedRepositoryLeaf', 'sha256', 'bytes') 'release-readback-contract-invalid'
            if ([string]$readback.schemaVersion -cne 'awx.notebook.directive.leaf-readback.v1' -or
                -not (Test-AwxBoolean $readback.exists) -or -not (Test-AwxBoolean $readback.isLeaf) -or
                -not (Test-AwxBoolean $readback.isReparse) -or -not (Test-AwxBoolean $readback.containedRepositoryLeaf) -or
                -not (Test-AwxInteger $readback.bytes) -or [long]$readback.bytes -lt 0) { throw 'release-readback-contract-invalid' }
            if (-not [bool]$readback.exists -or -not [bool]$readback.isLeaf -or [bool]$readback.isReparse -or -not [bool]$readback.containedRepositoryLeaf) {
                $reasons += 'release-payload-readback-unsafe'
            } elseif ([string]$readback.path -cne [string]$payload.sourcePath -or [string]$readback.sha256 -cne [string]$payload.sha256 -or
                [long]$readback.bytes -ne [long]$payload.bytes) { $reasons += 'release-payload-readback-mismatch' }
            $records += [pscustomobject][ordered]@{
                kind = 'payload'; name = [string]$payload.name; path = [string]$readback.path; exists = [bool]$readback.exists
                isLeaf = [bool]$readback.isLeaf; isReparse = [bool]$readback.isReparse
                containedRepositoryLeaf = [bool]$readback.containedRepositoryLeaf; sha256 = [string]$readback.sha256; bytes = [long]$readback.bytes
            }
        } catch { $reasons += 'release-readback-contract-invalid' }
    }
    $reasons = @(Get-AwxDistinctStrings $reasons)
    return [pscustomobject][ordered]@{ reasons = $reasons; records = $records; digest = Get-AwxValueDigest $records }
}

function Assert-AwxReleaseReadbacksMatchPlan {
    param($Assessment, $Plan, $Readbacks)
    try {
        $evidence = Get-AwxLeafReadbackEvidence $Assessment @($Plan.payloads) $Readbacks
        if (@($evidence.reasons).Count -ne 0 -or [string]$evidence.digest -cne [string]$Plan.readbackDigest) { throw 'release-readback-invalid' }
    } catch { throw 'release-readback-invalid' }
}

function Get-AwxReleaseOperations {
    param([object[]]$Payloads, $ReadyMarker, $JournalProtocol, [string]$RunId)
    $operations = [Collections.Generic.List[object]]::new()
    foreach ($payload in @($Payloads)) {
        $slug = ([string]$payload.name).ToLowerInvariant() -replace '[^a-z0-9]+', '-'
        $slug = $slug.Trim('-')
        $tempPath = "$($payload.path).tmp.$RunId"
        [void]$operations.Add((New-AwxOperation "payload-$slug-create-temp" 'effect' $true @{
            path = $tempPath; sourcePath = [string]$payload.sourcePath; targetPath = $tempPath; expectedSha256 = [string]$payload.sha256
            expectedBytes = [long]$payload.bytes; expectedExists = $true; exactLeaf = $true; createNew = $true; writeThrough = $true
        }))
        [void]$operations.Add((New-AwxCheckpointOperation "checkpoint-payload-$slug-temp-created" ([string]$JournalProtocol.path)))
        [void]$operations.Add((New-AwxOperation "payload-$slug-flush-temp" 'effect' $true @{
            path = $tempPath; expectedSha256 = [string]$payload.sha256; expectedBytes = [long]$payload.bytes
            expectedExists = $true; exactLeaf = $true; flushToDisk = $true
        }))
        [void]$operations.Add((New-AwxCheckpointOperation "checkpoint-payload-$slug-temp-flushed" ([string]$JournalProtocol.path)))
        [void]$operations.Add((New-AwxOperation "payload-$slug-verify-temp" 'read' $true @{
            path = $tempPath; expectedSha256 = [string]$payload.sha256; expectedBytes = [long]$payload.bytes; expectedExists = $true; exactLeaf = $true
        }))
        [void]$operations.Add((New-AwxCheckpointOperation "checkpoint-payload-$slug-temp-verified" ([string]$JournalProtocol.path)))
        [void]$operations.Add((New-AwxCheckpointOperation "checkpoint-payload-$slug-rename-intent" ([string]$JournalProtocol.path)))
        [void]$operations.Add((New-AwxOperation "payload-$slug-rename-final" 'effect' $true @{
            path = [string]$payload.path; sourcePath = $tempPath; targetPath = [string]$payload.path; finalPath = [string]$payload.path
            expectedSha256 = [string]$payload.sha256; expectedBytes = [long]$payload.bytes; expectedExists = $true
            exactLeaf = $true; requireTargetAbsent = $true
        }))
        [void]$operations.Add((New-AwxCheckpointOperation "checkpoint-payload-$slug-renamed" ([string]$JournalProtocol.path)))
    }
    [void]$operations.Add((New-AwxOperation 'ready-create-temp' 'effect' $true @{
        path = [string]$ReadyMarker.tempPath; targetPath = [string]$ReadyMarker.tempPath; expectedSha256 = [string]$ReadyMarker.contentSha256
        expectedBytes = [long]$ReadyMarker.bytes; expectedExists = $true; exactLeaf = $true; createNew = $true; writeThrough = $true
        content = [string]$ReadyMarker.content
    }))
    [void]$operations.Add((New-AwxCheckpointOperation 'checkpoint-ready-temp-created' ([string]$JournalProtocol.path)))
    [void]$operations.Add((New-AwxOperation 'ready-flush-temp' 'effect' $true @{
        path = [string]$ReadyMarker.tempPath; expectedSha256 = [string]$ReadyMarker.contentSha256; expectedBytes = [long]$ReadyMarker.bytes
        expectedExists = $true; exactLeaf = $true; flushToDisk = $true
    }))
    [void]$operations.Add((New-AwxCheckpointOperation 'checkpoint-ready-temp-flushed' ([string]$JournalProtocol.path)))
    [void]$operations.Add((New-AwxOperation 'ready-verify-temp' 'read' $true @{
        path = [string]$ReadyMarker.tempPath; expectedSha256 = [string]$ReadyMarker.contentSha256; expectedBytes = [long]$ReadyMarker.bytes
        expectedExists = $true; exactLeaf = $true
    }))
    [void]$operations.Add((New-AwxCheckpointOperation 'checkpoint-ready-temp-verified' ([string]$JournalProtocol.path)))
    [void]$operations.Add((New-AwxCheckpointOperation 'checkpoint-ready-rename-intent' ([string]$JournalProtocol.path)))
    [void]$operations.Add((New-AwxOperation 'ready-rename-final' 'effect' $false @{
        path = [string]$ReadyMarker.path; sourcePath = [string]$ReadyMarker.tempPath; targetPath = [string]$ReadyMarker.path
        finalPath = [string]$ReadyMarker.path; expectedSha256 = [string]$ReadyMarker.contentSha256; expectedBytes = [long]$ReadyMarker.bytes
        expectedExists = $true; exactLeaf = $true; requireTargetAbsent = $true
    }))
    return @($operations)
}

function New-AwxBlockedReleasePlan {
    param($Assessment, $RetirementJournal, [string[]]$ReasonCodes, [object[]]$PayloadRows = @())
    $runId = [string](Get-AwxProperty $Assessment 'runId' 'invalid')
    try { Assert-AwxRunId $runId } catch { $runId = 'invalid' }
    $assessmentDigest = [string](Get-AwxProperty $Assessment 'assessmentDigest')
    if (-not (Test-AwxHash $assessmentDigest)) { $assessmentDigest = '0' * 64 }
    $journalDigest = [string](Get-AwxProperty $RetirementJournal 'journalDigest')
    if (-not (Test-AwxHash $journalDigest)) { $journalDigest = '0' * 64 }
    $releaseRoot = "$script:ReleaseRoot/$runId"
    $journalProtocol = [pscustomobject][ordered]@{
        path = "$releaseRoot/release-journal.json"; tempPath = "$releaseRoot/release-journal.json.tmp.$runId"
        checkpointBeforeDestructiveStep = $true; writeThrough = $true; flushToDisk = $true; sameDirectoryAtomicRename = $true
    }
    $readyMarker = [pscustomobject][ordered]@{
        path = "$releaseRoot/$runId.ready"; tempPath = "$releaseRoot/$runId.ready.tmp.$runId"
        releaseManifestSha256 = ('0' * 64); contentSha256 = ('0' * 64); bytes = [long]0; content = ''
    }
    $plan = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-plan.v2'; planType = 'consolidated-release'; runId = $runId
        assessmentDigest = $assessmentDigest; authorityGranted = $false; readyForTrustedAdapter = $false
        reasonCodes = @(Get-AwxDistinctStrings $ReasonCodes); subjectPath = $releaseRoot; releaseRoot = $releaseRoot
        retirementJournalDigest = $journalDigest; readbackDigest = ('0' * 64); gitMutationPlanned = $false; tagProposalOnly = $true
        payloads = @($PayloadRows); readyMarker = $readyMarker; journalProtocol = $journalProtocol; operations = @()
    }
    return Add-AwxDigest $plan 'planDigest'
}

function New-AwxConsolidatedReleasePlan {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Assessment, [Parameter(Mandatory = $true)]$RetirementJournal,
        [Parameter(Mandatory = $true)]$StabilitySnapshot, [Parameter(Mandatory = $true)]$GateSnapshot,
        [Parameter(Mandatory = $true)][object[]]$Payloads, [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$ExistingPaths,
        [Parameter(Mandatory = $true)][DateTimeOffset]$NowUtc, [Parameter(Mandatory = $true)]$Readbacks
    )
    $reasons = @(); $payloadRows = @()
    try {
        Assert-AwxAssessmentBinding $Assessment
        if (-not [bool]$Assessment.confirmRequested -or -not [bool]$Assessment.readyForTrustedAdapter -or
            [string]$Assessment.derivedDesktopFinalProof -cne 'verified') { throw 'assessment-binding-invalid' }
    } catch { $reasons += 'assessment-binding-invalid' }
    if ($reasons.Count -eq 0) {
        $reasons += @(Get-AwxFrozenStabilityReasons $Assessment $StabilitySnapshot $NowUtc)
        $reasons += @(Get-AwxGateReasons $GateSnapshot $NowUtc)
        try { Assert-AwxRetirementJournalBinding $Assessment $RetirementJournal } catch { $reasons += 'retirement-journal-binding-invalid' }
    }
    try { $payloadRows = @(Get-AwxReleasePayloadRows $Payloads ([string](Get-AwxProperty $Assessment 'runId'))) }
    catch {
        if ([string]$_.Exception.Message -ceq 'release-payload-incomplete') { $reasons += 'release-payload-incomplete' }
        else { $reasons += 'release-payload-invalid' }
    }
    $existing = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    try {
        foreach ($path in @($ExistingPaths)) { [void]$existing.Add((ConvertTo-AwxRelativePath ([string]$path))) }
    } catch { $reasons += 'release-existing-path-invalid' }
    $runId = [string](Get-AwxProperty $Assessment 'runId' 'invalid')
    $releaseRoot = "$script:ReleaseRoot/$runId"
    $journalPath = "$releaseRoot/release-journal.json"
    $plannedPaths = @($journalPath, "$journalPath.tmp.$runId", "$releaseRoot/$runId.ready", "$releaseRoot/$runId.ready.tmp.$runId")
    foreach ($payload in @($payloadRows)) { $plannedPaths += @([string]$payload.path, "$($payload.path).tmp.$runId") }
    foreach ($path in $plannedPaths) { if ($existing.Contains($path)) { $reasons += 'release-output-exists'; break } }
    $readbackEvidence = $null
    if ($reasons.Count -eq 0) {
        $readbackEvidence = Get-AwxLeafReadbackEvidence $Assessment $payloadRows $Readbacks
        $reasons += @($readbackEvidence.reasons)
    }
    $reasons = @(Get-AwxDistinctStrings $reasons)
    if ($reasons.Count -gt 0) { return New-AwxBlockedReleasePlan $Assessment $RetirementJournal $reasons $payloadRows }

    $releaseManifest = @($payloadRows | Where-Object { [string]$_.name -ceq 'release-manifest.json' })[0]
    $readyContent = "$($releaseManifest.sha256)`n"
    $readyMarker = [pscustomobject][ordered]@{
        path = "$releaseRoot/$runId.ready"; tempPath = "$releaseRoot/$runId.ready.tmp.$runId"
        releaseManifestSha256 = [string]$releaseManifest.sha256; contentSha256 = Get-AwxTextSha256 $readyContent
        bytes = Get-AwxTextBytes $readyContent; content = $readyContent
    }
    $journalProtocol = [pscustomobject][ordered]@{
        path = $journalPath; tempPath = "$journalPath.tmp.$runId"; checkpointBeforeDestructiveStep = $true
        writeThrough = $true; flushToDisk = $true; sameDirectoryAtomicRename = $true
    }
    $operations = @(Get-AwxReleaseOperations $payloadRows $readyMarker $journalProtocol $runId)
    $plan = [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.execution-plan.v2'; planType = 'consolidated-release'; runId = $runId
        assessmentDigest = [string]$Assessment.assessmentDigest; authorityGranted = $false; readyForTrustedAdapter = $true; reasonCodes = @()
        subjectPath = $releaseRoot; releaseRoot = $releaseRoot; retirementJournalDigest = [string]$RetirementJournal.journalDigest
        readbackDigest = [string]$readbackEvidence.digest; gitMutationPlanned = $false; tagProposalOnly = $true
        payloads = $payloadRows; readyMarker = $readyMarker; journalProtocol = $journalProtocol; operations = $operations
    }
    $plan = Add-AwxDigest $plan 'planDigest'
    Assert-AwxPlanBinding $Assessment $plan
    return $plan
}

Export-ModuleMember -Function @(
    'Get-AwxRetirementAssessment',
    'New-AwxRetirementItemPlan',
    'New-AwxExecutionJournalEntry',
    'New-AwxExecutionCursor',
    'Invoke-AwxExecutionCursorStep',
    'ConvertTo-AwxExecutionJournalEntry',
    'New-AwxRetirementJournal',
    'Get-AwxRetirementReplayPlan',
    'New-AwxConsolidatedReleasePlan'
)

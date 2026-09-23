[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidateSet('Status', 'Snapshot', 'Next', 'Begin', 'Record', 'Adopt', 'Retire', 'Release')]
    [string]$Action = 'Status',
    [string]$Root = '.',
    [string]$ProgramPath,
    [string]$StatePath,
    [string]$WorkUnitId,
    [string]$Outcome,
    [string]$EvidencePath,
    [string]$RunId,
    [string]$ExpectedEvidenceSha256,
    [string]$ExpectedTargetPostimageSha256,
    [switch]$ConfirmRetirement
)

if ($MyInvocation.InvocationName -eq '.') {
    throw 'dot-source-not-supported'
}

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Schema = 'awx.notebook.directive.program.v2'
$CanonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$ProgramLimit = 1MB
$StateLimit = 1MB
$CandidateLimit = 1MB
$InventoryLimit = 512
$WorkUnitLimit = 128
$DependencyLimit = 64
$TargetLimit = 256
$RelativePathLimit = 512
$Statuses = @('pending', 'running', 'green', 'no_patch_needed', 'hold', 'failed')
$AllowedKinds = @('source', 'test', 'verification', 'runtime', 'retirement', 'safety', 'non-goal')
$EventReasonCodes = @(
    'event-publication-pending','event-ledger-diverged','event-lock-timeout','event-lock-invalid',
    'event-lock-failed','event-ledger-too-large','event-row-too-large','event-row-count-exceeded',
    'event-rollback-unproven','event-schema-invalid','events-without-state','event-checkpoint-invalid'
)
$Utf8 = New-Object System.Text.UTF8Encoding($false, $true)
$script:controllerStage = $null
$script:stateCasLockStream = $null
$script:stateCasLockPath = $null
$script:eventLockStream = $null
$script:eventLockPath = $null
$script:eventCommitted = $false
$script:eventCommittedWorkUnitId = $null
$script:eventCommittedStateSha256 = $null
$script:eventCommittedCanonicalSha256 = $null

Import-Module (Join-Path $PSScriptRoot 'consolidated_program_core.psm1') -Force

function Stop-Controller {
    param([string]$Reason)

    if ([bool]$script:eventCommitted -or $Reason -cin $EventReasonCodes) {
        $eventReason = if ($Reason -cin $EventReasonCodes) { $Reason } elseif ([bool]$script:eventCommitted) { 'event-publication-pending' } else { 'event-lock-failed' }
        Write-Output ([ordered]@{
            action = $Action
            status = 'error'
            reason = if ([bool]$script:eventCommitted) { 'event-publication-pending' } else { $eventReason }
            workUnitId = if ([bool]$script:eventCommitted) { $script:eventCommittedWorkUnitId } else { $null }
            committed = [bool]$script:eventCommitted
            stateSha256 = if ([bool]$script:eventCommitted) { $script:eventCommittedStateSha256 } else { $null }
            canonicalMarkdownSha256 = if ([bool]$script:eventCommitted) { $script:eventCommittedCanonicalSha256 } else { $null }
            eventReason = $eventReason
        } | ConvertTo-Json -Compress)
        exit 1
    }
    Write-Output ([ordered]@{
        action = $Action
        status = 'error'
        reason = $Reason
        workUnitId = $null
    } | ConvertTo-Json -Compress)
    exit 1
}

function Set-EventCommittedContext {
    param([AllowNull()]$UnitId, [string]$StateSha256, [string]$CanonicalMarkdownSha256)
    $script:eventCommitted = $true
    $script:eventCommittedWorkUnitId = if ($null -eq $UnitId -or [string]::IsNullOrWhiteSpace([string]$UnitId)) { $null } else { [string]$UnitId }
    $script:eventCommittedStateSha256 = $StateSha256
    $script:eventCommittedCanonicalSha256 = $CanonicalMarkdownSha256
}

function Get-CommittedMutationContext {
    param([ValidateSet('Begin','Record')][string]$MutationAction, [AllowNull()][string]$RequestedUnitId, [string]$RequestedRunId, [AllowNull()][string]$RequestedOutcome)

    $validated = Get-ValidatedState
    $matches = @()
    foreach ($unit in @($validated.state.workUnits)) {
        if (-not [string]::IsNullOrWhiteSpace($RequestedUnitId) -and [string]$unit.workUnitId -cne $RequestedUnitId) { continue }
        if ($MutationAction -ceq 'Begin') {
            if ([string]$unit.status -ceq 'running' -and (Test-HasProperty $unit 'execution') -and
                [string]$unit.execution.runId -ceq $RequestedRunId) { $matches += ,$unit }
            continue
        }
        if ((Test-HasProperty $unit 'transition') -and [string]$unit.transition.phase -ceq 'record-awaiting-release' -and
            [string]$unit.transition.runId -ceq $RequestedRunId -and [string]$unit.transition.outcome -ceq $RequestedOutcome) {
            $matches += ,$unit
            continue
        }
        if ((Test-HasProperty $unit 'record') -and [string]$unit.record.runId -ceq $RequestedRunId -and
            [string]$unit.record.outcome -ceq $RequestedOutcome) { $matches += ,$unit }
    }
    if ($matches.Count -ne 1) { return $null }
    return [pscustomobject]@{
        workUnitId = [string]$matches[0].workUnitId
        stateSha256 = [string]$validated.hash
        canonicalMarkdownSha256 = [string]$validated.manifest.hash
    }
}

function Get-FullPath {
    param([string]$Path, [string]$BasePath)

    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

function Assert-ExistingFileSystemComponents {
    param([string]$Path)

    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw 'unsupported-provider'
    }
    $volumeRoot = [IO.Path]::GetPathRoot($Path)
    if ([string]::IsNullOrWhiteSpace($volumeRoot)) {
        throw 'unsupported-provider'
    }

    $current = $volumeRoot
    $volumeItem = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
    if ($null -ne $volumeItem) {
        if ($volumeItem.PSProvider.Name -cne 'FileSystem') { throw 'unsupported-provider' }
        if (($volumeItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'reparse-traversal' }
    }

    $remainder = $Path.Substring($volumeRoot.Length)
    foreach ($component in $remainder.Split([char[]]@('\', '/'), [StringSplitOptions]::RemoveEmptyEntries)) {
        $current = Join-Path $current $component
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -eq $item) {
            break
        }
        if ($item.PSProvider.Name -cne 'FileSystem') { throw 'unsupported-provider' }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'reparse-traversal' }
    }
}

function Get-FileSystemRootContext {
    param([string]$SuppliedRoot)

    $rootItem = Get-Item -LiteralPath $SuppliedRoot -Force -ErrorAction Stop
    if ($rootItem.PSProvider.Name -cne 'FileSystem') { throw 'unsupported-provider' }
    if (-not $rootItem.PSIsContainer) { throw 'root-not-directory' }
    $fullPath = [IO.Path]::GetFullPath($rootItem.FullName).TrimEnd('\')
    Assert-ExistingFileSystemComponents $fullPath
    return [pscustomobject]@{
        path = $fullPath
        driveName = [IO.Path]::GetPathRoot($fullPath).TrimEnd('\').TrimEnd(':')
    }
}

function Test-InRoot {
    param([string]$RootPath, [string]$Path)

    $normalizedRoot = $RootPath.TrimEnd('\')
    $normalizedPath = $Path.TrimEnd('\')
    return $normalizedPath.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedPath.StartsWith($normalizedRoot + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Get-RelativePath {
    param([string]$RootPath, [string]$Path)

    if (-not (Test-InRoot $RootPath $Path)) {
        throw 'path-outside-root'
    }
    return $Path.TrimEnd('\').Substring($RootPath.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
}

function Assert-NoReparse {
    param([string]$RootPath, [string]$Path)

    if (-not (Test-InRoot $RootPath $Path)) {
        throw 'path-outside-root'
    }
    Assert-ExistingFileSystemComponents $RootPath
    Assert-ExistingFileSystemComponents $Path
}

function Get-Sha256 {
    param([byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

function ConvertTo-CanonicalJsonString {
    param([string]$Value)

    if ($null -eq $Value) { throw 'event-schema-invalid' }
    $builder = New-Object Text.StringBuilder
    $null = $builder.Append('"')
    for ($index = 0; $index -lt $Value.Length; $index++) {
        $code = [int][char]$Value[$index]
        if ($code -ge 0xD800 -and $code -le 0xDBFF) {
            if ($index + 1 -ge $Value.Length) { throw 'event-schema-invalid' }
            $low = [int][char]$Value[$index + 1]
            if ($low -lt 0xDC00 -or $low -gt 0xDFFF) { throw 'event-schema-invalid' }
            $null = $builder.Append($Value[$index])
            $null = $builder.Append($Value[$index + 1])
            $index++
            continue
        }
        if ($code -ge 0xDC00 -and $code -le 0xDFFF) { throw 'event-schema-invalid' }
        $escaped = $null
        switch ($code) {
            0x22 { $escaped = '\"'; break }
            0x5C { $escaped = '\\'; break }
            0x08 { $escaped = '\b'; break }
            0x09 { $escaped = '\t'; break }
            0x0A { $escaped = '\n'; break }
            0x0C { $escaped = '\f'; break }
            0x0D { $escaped = '\r'; break }
            0x2028 { $escaped = '\u2028'; break }
            0x2029 { $escaped = '\u2029'; break }
        }
        if ($null -ne $escaped) {
            $null = $builder.Append($escaped)
            continue
        }
        if ($code -lt 0x20) {
            $null = $builder.Append(('\u{0:x4}' -f $code))
            continue
        }
        $null = $builder.Append($Value[$index])
    }
    $null = $builder.Append('"')
    return $builder.ToString()
}

function ConvertTo-CanonicalJson {
    param($Value, [Parameter(Mandatory = $true)][string]$SchemaKind, [int]$Depth = 0)

    if ($Depth -gt 64) { throw 'event-schema-invalid' }
    switch -CaseSensitive ($SchemaKind) {
        'string' {
            if ($Value -isnot [string]) { throw 'event-schema-invalid' }
            return ConvertTo-CanonicalJsonString ([string]$Value)
        }
        'nullable-string' {
            if ($null -eq $Value) { return 'null' }
            if ($Value -isnot [string]) { throw 'event-schema-invalid' }
            return ConvertTo-CanonicalJsonString ([string]$Value)
        }
        'boolean' {
            if ($Value -isnot [bool]) { throw 'event-schema-invalid' }
            return $(if ([bool]$Value) { 'true' } else { 'false' })
        }
        'integer' {
            if ($Value -isnot [int] -and $Value -isnot [long]) { throw 'event-schema-invalid' }
            return ([Convert]::ToString([int64]$Value, [Globalization.CultureInfo]::InvariantCulture))
        }
        'string-array' { $elementKind = 'string' }
        'candidate-array' { $elementKind = 'candidate' }
        'exclusion-array' { $elementKind = 'exclusion' }
        'legacy-rule-array' { $elementKind = 'legacy-rule' }
        'work-unit-array' { $elementKind = 'work-unit' }
        'image-array' { $elementKind = 'image' }
        'retirement-item-array' { $elementKind = 'retirement-item' }
        default { $elementKind = $null }
    }
    if ($null -ne $elementKind) {
        if ($Value -isnot [Array]) { throw 'event-schema-invalid' }
        $items = New-Object 'Collections.Generic.List[string]'
        foreach ($item in @($Value)) {
            try { $items.Add((ConvertTo-CanonicalJson -Value $item -SchemaKind $elementKind -Depth ($Depth + 1))) }
            catch { throw 'event-schema-invalid' }
        }
        return '[' + ([string]::Join(',', $items.ToArray())) + ']'
    }

    $specification = switch -CaseSensitive ($SchemaKind) {
        'state-projection' { @(
            'schemaVersion|string|r','canonicalMarkdownPath|string|r','canonicalMarkdownSha256|string|r',
            'sourceOwner|string|r','activeSourceSets|string-array|r','controllerManifest|manifest|r',
            'directiveInventory|inventory|r','workUnits|work-unit-array|r','retirement|retirement|r',
            'desktopFinalProof|string|r','runtimeLineageVerdict|string|r','deleteAuthorized|boolean|r'
        ); break }
        'manifest' { @(
            'schemaVersion|string|r','programId|string|o','sourceOwner|string|o','activeSourceSets|string-array|o',
            'desktopFinalProof|string|o','runtimeLineageVerdict|string|o','deleteAuthorized|boolean|o',
            'legacyRequirementCoverage|legacy-rule-array|o','directiveInventory|inventory|r',
            'workUnits|work-unit-array|r','retirement|retirement|r'
        ); break }
        'legacy-rule' { @('matchKind|string|r','value|string|r','workUnitId|string|r','expectedCount|integer|r'); break }
        'inventory' { @(
            'schemaVersion|string|r','canonicalExecutionRoot|string|r','candidateRoots|string-array|r',
            'candidates|candidate-array|r','excluded|exclusion-array|r'
        ); break }
        'candidate' { @(
            'path|string|r','sha256|string|r','bytes|integer|r','gitTracking|string|r','provenance|string|r',
            'format|string|r','directiveIds|string-array|r','targetFiles|string-array|r','inclusionReason|string|r'
        ); break }
        'exclusion' { @('path|string|r','reason|string|r'); break }
        'work-unit' { @(
            'workUnitId|string|r','status|string|r','dependencies|string-array|r','required|boolean|r','kind|string|r',
            'targetFiles|string-array|r','requirementCoverage|string-array|o','redCommands|string-array|o',
            'greenCommands|string-array|o','rollback|string|o','retirementCoverage|string-array|o',
            'causalBoundary|string|o','approvalGate|approval-gate|o','execution|execution|o','record|record|o'
        ); break }
        'approval-gate' { @('schemaVersion|string|r','requiredForBegin|boolean|r','acceptAny|string-array|r'); break }
        'execution' { @(
            'runId|string|r','owner|string|r','leaseTopic|string|r','canonicalMarkdownSha256|string|r','targetPreimages|image-array|r'
        ); break }
        'image' { @(
            'path|string|r','exists|boolean|r','bytes|integer|r','sha256|nullable-string|r',
            'resolvedPathContained|boolean|r','ancestorNonReparse|boolean|r','reparseTraversal|boolean|r','leafKind|string|r'
        ); break }
        'record' { @(
            'runId|string|r','outcome|string|r','evidenceSha256|string|r','targetPreimages|image-array|r',
            'targetPostimages|image-array|r','redCommandId|nullable-string|r','greenCommandIds|string-array|r',
            'rollbackStatus|string|r','desktopFinalProof|string|r'
        ); break }
        'retirement' { @(
            'schemaVersion|string|r','deleteAuthorized|boolean|r','canonicalDirectivePath|string|r',
            'canonicalDirectiveSha256Evidence|string|r','allRequiredWorkUnitsGreen|boolean|r',
            'desktopFinalProof|string|r','status|string|r','items|retirement-item-array|r'
        ); break }
        'retirement-item' { @(
            'path|string|r','sha256|string|r','bytes|integer|r','gitTracking|string|r','coverage|string-array|r',
            'eligibility|string|r','holdReason|string|r','deletionResult|string|r','status|string|r'
        ); break }
        'event-id-input' { @(
            'programId|string|r','canonicalMarkdownSha256|string|r','action|string|r','workUnitId|nullable-string|r',
            'runIdSha256|nullable-string|r','result|string|r','stateProjectionSha256|string|r',
            'evidenceSha256|nullable-string|r','recordSha256|nullable-string|r'
        ); break }
        'event-hash-input' { @(
            'schemaVersion|string|r','sequence|integer|r','previousEventSha256|string|r','eventId|string|r',
            'programId|string|r','canonicalMarkdownSha256|string|r','action|string|r','workUnitId|nullable-string|r',
            'runIdSha256|nullable-string|r','result|string|r','stateProjectionSha256|string|r',
            'evidenceSha256|nullable-string|r','recordSha256|nullable-string|r'
        ); break }
        'event-row' { @(
            'schemaVersion|string|r','sequence|integer|r','previousEventSha256|string|r','eventId|string|r',
            'eventSha256|string|r','programId|string|r','canonicalMarkdownSha256|string|r','action|string|r',
            'workUnitId|nullable-string|r','runIdSha256|nullable-string|r','result|string|r',
            'stateProjectionSha256|string|r','evidenceSha256|nullable-string|r','recordSha256|nullable-string|r'
        ); break }
        default { throw 'event-schema-invalid' }
    }
    if ($null -eq $Value -or ($Value -isnot [Collections.IDictionary] -and $Value -isnot [Management.Automation.PSCustomObject])) {
        throw 'event-schema-invalid'
    }
    $allowed = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $required = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($entry in $specification) {
        $parts = $entry.Split('|')
        $null = $allowed.Add($parts[0])
        if ($parts[2] -ceq 'r') { $null = $required.Add($parts[0]) }
    }
    $actualNames = if ($Value -is [Collections.IDictionary]) {
        @($Value.Keys | ForEach-Object { if ($_ -isnot [string]) { throw 'event-schema-invalid' }; [string]$_ })
    } else {
        @($Value.PSObject.Properties | Where-Object { $_.MemberType -in @('NoteProperty','Property') } | ForEach-Object { [string]$_.Name })
    }
    foreach ($name in $actualNames) { if (-not $allowed.Contains($name)) { throw 'event-schema-invalid' } }
    foreach ($name in $required) {
        $present = if ($Value -is [Collections.IDictionary]) { $Value.Contains($name) } else { $null -ne $Value.PSObject.Properties[$name] }
        if (-not $present) { throw 'event-schema-invalid' }
    }
    $members = New-Object 'Collections.Generic.List[string]'
    foreach ($entry in $specification) {
        $parts = $entry.Split('|')
        $name = $parts[0]
        $present = if ($Value -is [Collections.IDictionary]) { $Value.Contains($name) } else { $null -ne $Value.PSObject.Properties[$name] }
        if (-not $present) { continue }
        if ($Value -is [Collections.IDictionary]) { $propertyValue = $Value[$name] }
        else { $propertyValue = $Value.PSObject.Properties[$name].Value }
        try {
            $members.Add((ConvertTo-CanonicalJsonString $name) + ':' + (ConvertTo-CanonicalJson -Value $propertyValue -SchemaKind $parts[1] -Depth ($Depth + 1)))
        } catch { if ([string]$_.Exception.Message -match '^[a-z0-9-]+$') { throw }; throw ('event-schema-field-' + $SchemaKind.ToLowerInvariant() + '-' + $name.ToLowerInvariant() + '-invalid') }
    }
    return '{' + ([string]::Join(',', $members.ToArray())) + '}'
}

function Get-CanonicalJsonHash {
    param($Value, [Parameter(Mandatory = $true)][string]$SchemaKind)
    return Get-Sha256 $Utf8.GetBytes((ConvertTo-CanonicalJson -Value $Value -SchemaKind $SchemaKind))
}

function Read-BoundedFile {
    param(
        [string]$Path,
        [int64]$MaximumBytes,
        [string]$TooLargeReason,
        [string]$MissingReason = 'file-missing'
    )

    Assert-NoReparse $script:root $Path
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw $MissingReason
    }
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Length -gt $MaximumBytes) {
        throw $TooLargeReason
    }
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -gt $MaximumBytes) {
        throw $TooLargeReason
    }
    return [pscustomobject]@{
        bytes = $bytes
        hash = Get-Sha256 $bytes
        text = $Utf8.GetString($bytes)
    }
}

function Get-FileHashBounded {
    param([string]$Path, [int64]$MaximumBytes, [string]$TooLargeReason)

    return (Read-BoundedFile $Path $MaximumBytes $TooLargeReason).hash
}

function Assert-NoDuplicateJsonKeys {
    param([string]$Text)

    $objectKeySets = New-Object Collections.Stack
    $inString = $false
    $escaped = $false
    $stringStart = 0

    for ($index = 0; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        if ($inString) {
            if ($escaped) {
                $escaped = $false
                continue
            }
            if ($character -eq [char]92) {
                $escaped = $true
                continue
            }
            if ($character -ne '"') {
                continue
            }

            $inString = $false
            $after = $index + 1
            while ($after -lt $Text.Length -and [char]::IsWhiteSpace($Text[$after])) {
                $after++
            }
            if ($after -ge $Text.Length -or $Text[$after] -ne ':' -or $objectKeySets.Count -eq 0) {
                continue
            }

            $rawKey = $Text.Substring($stringStart, $index - $stringStart)
            try {
                $decodedKey = [string](('"' + $rawKey + '"') | ConvertFrom-Json -ErrorAction Stop)
            } catch {
                throw 'malformed-json'
            }
            $keys = $objectKeySets.Peek()
            if (-not $keys.Add($decodedKey)) {
                throw 'duplicate-json-key'
            }
            continue
        }

        if ($character -eq '"') {
            $inString = $true
            $stringStart = $index + 1
        } elseif ($character -eq '{') {
            $objectKeySets.Push((New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)))
        } elseif ($character -eq '}' -and $objectKeySets.Count -gt 0) {
            $null = $objectKeySets.Pop()
        }
    }
}

function ConvertFrom-StrictJson {
    param([string]$Text, [string]$MalformedReason)

    Assert-NoDuplicateJsonKeys $Text
    try {
        return $Text | ConvertFrom-Json -ErrorAction Stop
    } catch {
        if ($_.Exception.Message -eq 'duplicate-json-key') {
            throw
        }
        throw $MalformedReason
    }
}

function Test-HasProperty {
    param($Object, [string]$Name)

    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Assert-RequiredProperties {
    param($Object, [string[]]$Names, [string]$Reason)

    if ($null -eq $Object -or $Object -isnot [pscustomobject]) {
        throw $Reason
    }
    foreach ($name in $Names) {
        if (-not (Test-HasProperty $Object $name)) {
            throw $Reason
        }
    }
}

function Assert-ClosedProperties {
    param($Object, [string[]]$Required, [string[]]$Optional, [string]$Reason)
    Assert-RequiredProperties $Object $Required $Reason
    $allowed = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($name in @($Required) + @($Optional)) { $null = $allowed.Add($name) }
    foreach ($property in $Object.PSObject.Properties) {
        if (-not $allowed.Contains($property.Name)) { throw $Reason }
    }
}

function Assert-JsonArray {
    param($Value, [string]$Reason, [int]$MaximumCount)

    if ($Value -isnot [Array]) {
        throw $Reason
    }
    if (@($Value).Count -gt $MaximumCount) {
        throw $Reason
    }
}

function Test-SameJson {
    param($Left, $Right)

    return (($Left | ConvertTo-Json -Depth 30 -Compress) -ceq
        ($Right | ConvertTo-Json -Depth 30 -Compress))
}

function Test-JsonInteger {
    param($Value)

    return $Value -is [int] -or $Value -is [long]
}

function ConvertTo-ValidatedRelativePath {
    param([string]$Path, [string]$Reason)

    if ([string]::IsNullOrWhiteSpace($Path) -or
        $Path.Length -gt $RelativePathLimit -or
        [IO.Path]::IsPathRooted($Path) -or
        $Path -match '(^|/|\\)\.\.($|/|\\)' -or
        $Path -match '[*?]' -or
        $Path.EndsWith('/')) {
        throw $Reason
    }
    return $Path.Replace('\', '/')
}

function Sort-RowsOrdinal {
    param([object[]]$Rows)

    $sorted = @($Rows)
    for ($index = 1; $index -lt $sorted.Count; $index++) {
        $current = $sorted[$index]
        $position = $index - 1
        while ($position -ge 0 -and
            [StringComparer]::Ordinal.Compare([string]$sorted[$position].path, [string]$current.path) -gt 0) {
            $sorted[$position + 1] = $sorted[$position]
            $position--
        }
        $sorted[$position + 1] = $current
    }
    return $sorted
}

function Get-GitTrackingMap {
    param([string]$RootPath)

    $map = @{}
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $git) {
        $git = Get-Command git -ErrorAction SilentlyContinue
    }
    if ($null -eq $git) {
        return $map
    }

    $queries = @(
        @{ kind = 'tracked'; arguments = @('ls-files', '--cached') },
        @{ kind = 'untracked'; arguments = @('ls-files', '--others', '--exclude-standard') },
        @{ kind = 'ignored'; arguments = @('ls-files', '--others', '--ignored', '--exclude-standard') }
    )
    foreach ($query in $queries) {
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $lines = @(& $git.Source -C $RootPath @($query.arguments) 2>$null)
            $gitExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($gitExitCode -ne 0) {
            return @{}
        }
        foreach ($line in $lines) {
            $relative = ([string]$line).Replace('\', '/')
            if (-not [string]::IsNullOrWhiteSpace($relative)) {
                $map[$relative] = $query.kind
            }
        }
    }
    return $map
}

function Get-GitTrackingKind {
    param([string]$RootPath, [string]$RelativePath)
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $git) { $git = Get-Command git -ErrorAction SilentlyContinue }
    if ($null -eq $git) { return 'unknown' }
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $tracked = @(& $git.Source -C $RootPath ls-files --cached -- $RelativePath 2>$null)
        if ($LASTEXITCODE -ne 0) { return 'unknown' }
        if ($tracked.Count -gt 0) { return 'tracked' }
        $ignored = @(& $git.Source -C $RootPath check-ignore -- $RelativePath 2>$null)
        if ($ignored.Count -gt 0) { return 'ignored' }
        $status = @(& $git.Source -C $RootPath status --porcelain=v1 --untracked-files=all -- $RelativePath 2>$null)
        if ($LASTEXITCODE -ne 0) { return 'unknown' }
        if (@($status | Where-Object { [string]$_ -match '^\?\?' }).Count -gt 0) { return 'untracked' }
        return 'unknown'
    } finally { $ErrorActionPreference = $previous }
}

function Get-ExclusionReason {
    param([string]$Name)

    if ($Name -match '(?i)-v3\.(report\.md|verify\.log|manifest\.json|sha256\.txt)$') {
        return 'patchdrop-sidecar'
    }
    if ($Name -match '(?i)\.report\.md$' -or $Name -match '(?i)\.verify\.log$') {
        return 'report-or-verification'
    }
    if ($Name -match '(?i)\.manifest\.json$' -or $Name -match '(?i)\.sha256\.txt$') {
        return 'patchdrop-sidecar'
    }
    return $null
}

function Test-KnownNotebookHandoffArtifact {
    param($Value)

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [Array] -or $Value -is [ValueType]) { return $false }
    $expected = @('from','to','currentTask','instructions','dataPaths','notes')
    $actual = @($Value.PSObject.Properties | ForEach-Object { $_.Name })
    if ($actual.Count -ne $expected.Count) { return $false }
    foreach ($name in $expected) { if ($actual -cnotcontains $name) { return $false } }
    if ($Value.from -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$Value.from) -or
        $Value.to -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$Value.to) -or
        $Value.currentTask -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$Value.currentTask)) { return $false }
    foreach ($name in @('instructions','dataPaths','notes')) {
        if ($Value.$name -isnot [Array] -or @($Value.$name).Count -gt 64) { return $false }
        foreach ($item in @($Value.$name)) { if ($item -isnot [string]) { return $false } }
    }
    return $true
}

function Get-DirectiveInventory {
    $candidates = New-Object 'Collections.Generic.List[object]'
    $excluded = New-Object 'Collections.Generic.List[object]'
    $specifications = @(
        @{ root = 'data/agent-handoff/notebook'; provenance = 'notebook'; reason = 'standalone-notebook-directive'; shape = 'notebook' },
        @{ root = '__patch_drop__/notebook'; provenance = 'notebook-patchdrop-intent'; reason = 'target-specific-red-directive'; shape = 'patchdrop' },
        @{ root = 'agent-prompts'; provenance = 'prompt'; reason = 'approved-canonical-input'; shape = 'prompt' }
    )

    foreach ($specification in $specifications) {
        $directory = Get-FullPath $specification.root $root
        if (-not (Test-Path -LiteralPath $directory)) {
            continue
        }
        Assert-NoReparse $root $directory

        foreach ($entry in @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop)) {
            $relative = Get-RelativePath $root $entry.FullName
            if ($entry.PSIsContainer) {
                if ($specification.shape -eq 'notebook' -and $entry.Name -like 'source-directive-canary-v*') {
                    $reason = if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { 'reparse-risk' } else { 'sealed-canary' }
                    $excluded.Add([ordered]@{ path = $relative; reason = $reason })
                }
                continue
            }

            $sidecarReason = Get-ExclusionReason $entry.Name
            $matchesShape = switch ($specification.shape) {
                'notebook' { $entry.Extension -in @('.md', '.json') }
                'patchdrop' { $entry.Name -like '*directive*.md' }
                'prompt' { $entry.Name -like '*source_directive*.md' }
            }
            if ($null -eq $sidecarReason -and -not $matchesShape) {
                continue
            }
            if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                $excluded.Add([ordered]@{ path = $relative; reason = 'reparse-risk' })
                continue
            }
            if ($null -ne $sidecarReason) {
                $excluded.Add([ordered]@{ path = $relative; reason = $sidecarReason })
                continue
            }

            $gitTracking = Get-GitTrackingKind $root $relative
            $isCanonical = $entry.FullName.Equals($program, [StringComparison]::OrdinalIgnoreCase)
            if ($isCanonical -or ($specification.shape -eq 'prompt' -and $gitTracking -eq 'tracked')) {
                $excluded.Add([ordered]@{ path = $relative; reason = 'reusable-prompt' })
                continue
            }

            $file = Read-BoundedFile $entry.FullName $CandidateLimit 'candidate-too-large'
            if ($entry.Extension -eq '.json') {
                $jsonValue = ConvertFrom-StrictJson $file.text 'malformed-candidate-json'
                if ($specification.shape -eq 'notebook' -and (Test-KnownNotebookHandoffArtifact $jsonValue)) { continue }
            }
            $candidates.Add([ordered]@{
                path = $relative
                sha256 = $file.hash
                bytes = $file.bytes.Length
                gitTracking = $gitTracking
                provenance = $specification.provenance
                format = if ($entry.Extension -eq '.json') { 'json' } else { 'markdown' }
                directiveIds = @()
                targetFiles = @()
                inclusionReason = $specification.reason
            })
        }
    }

    return [pscustomobject]@{
        schemaVersion = 'demo1.notebook-directive-inventory.v1'
        canonicalExecutionRoot = $CanonicalRoot
        candidateRoots = @('data/agent-handoff/notebook', '__patch_drop__/notebook', 'agent-prompts')
        candidates = @(Sort-RowsOrdinal @($candidates.ToArray()))
        excluded = @(Sort-RowsOrdinal @($excluded.ToArray()))
    }
}

function Assert-InventorySchema {
    param($Inventory)

    Assert-ClosedProperties $Inventory @('schemaVersion', 'canonicalExecutionRoot', 'candidateRoots', 'candidates', 'excluded') @() 'invalid-directive-inventory'
    if ($Inventory.schemaVersion -isnot [string] -or
        $Inventory.schemaVersion -cne 'demo1.notebook-directive-inventory.v1' -or
        $Inventory.canonicalExecutionRoot -isnot [string] -or
        $Inventory.canonicalExecutionRoot -cne $CanonicalRoot) {
        throw 'invalid-directive-inventory'
    }
    Assert-JsonArray $Inventory.candidateRoots 'invalid-directive-inventory' 3
    if (-not (Test-SameJson @($Inventory.candidateRoots) @('data/agent-handoff/notebook', '__patch_drop__/notebook', 'agent-prompts'))) {
        throw 'invalid-directive-inventory'
    }
    if ($Inventory.candidates -isnot [Array]) { throw 'invalid-candidates' }
    if (@($Inventory.candidates).Count -gt $InventoryLimit) { throw 'too-many-candidates' }
    if ($Inventory.excluded -isnot [Array]) { throw 'invalid-exclusions' }
    if (@($Inventory.excluded).Count -gt $InventoryLimit) { throw 'too-many-exclusions' }

    foreach ($candidate in @($Inventory.candidates)) {
        Assert-ClosedProperties $candidate @('path', 'sha256', 'bytes', 'gitTracking', 'provenance', 'format', 'directiveIds', 'targetFiles', 'inclusionReason') @() 'invalid-candidate-record'
        $null = ConvertTo-ValidatedRelativePath ([string]$candidate.path) 'invalid-candidate-path'
        if ($candidate.path -isnot [string] -or
            $candidate.sha256 -isnot [string] -or $candidate.sha256 -cnotmatch '^[0-9A-F]{64}$' -or
            -not (Test-JsonInteger $candidate.bytes) -or [int64]$candidate.bytes -lt 0 -or [int64]$candidate.bytes -gt $CandidateLimit -or
            $candidate.gitTracking -isnot [string] -or @('tracked', 'untracked', 'ignored', 'unknown') -cnotcontains $candidate.gitTracking -or
            $candidate.provenance -isnot [string] -or @('notebook', 'notebook-patchdrop-intent', 'desktop', 'patchdrop', 'prompt', 'evidence_needed') -cnotcontains $candidate.provenance -or
            $candidate.format -isnot [string] -or @('json', 'markdown', 'yaml', 'text', 'unknown') -cnotcontains $candidate.format -or
            $candidate.inclusionReason -isnot [string] -or @('standalone-notebook-directive', 'target-specific-red-directive', 'approved-canonical-input') -cnotcontains $candidate.inclusionReason) {
            throw 'invalid-candidate-record'
        }
        Assert-JsonArray $candidate.directiveIds 'invalid-candidate-record' $InventoryLimit
        Assert-JsonArray $candidate.targetFiles 'invalid-candidate-record' $TargetLimit
    }

    foreach ($exclusion in @($Inventory.excluded)) {
        Assert-ClosedProperties $exclusion @('path', 'reason') @() 'invalid-exclusion-record'
        $null = ConvertTo-ValidatedRelativePath ([string]$exclusion.path) 'invalid-exclusion-record'
        if ($exclusion.path -isnot [string] -or $exclusion.reason -isnot [string] -or
            @('sealed-canary', 'reusable-prompt', 'patchdrop-sidecar', 'report-or-verification', 'directory', 'reparse-risk', 'outside-repo', 'provenance-uncertain', 'unsupported') -cnotcontains $exclusion.reason) {
            throw 'invalid-exclusion-record'
        }
    }
}

function Assert-WorkUnitImages {
    param($Rows, [string[]]$Targets, [string]$Reason)

    Assert-JsonArray $Rows $Reason $TargetLimit
    if (@($Rows).Count -ne $Targets.Count) { throw $Reason }
    for ($index = 0; $index -lt $Targets.Count; $index++) {
        $row = $Rows[$index]
        Assert-ClosedProperties $row @('path','exists','bytes','sha256','resolvedPathContained','ancestorNonReparse','reparseTraversal','leafKind') @() $Reason
        if ($row.path -isnot [string] -or [string]$row.path -cne $Targets[$index] -or
            $row.exists -isnot [bool] -or -not (Test-JsonInteger $row.bytes) -or [int64]$row.bytes -lt 0 -or [int64]$row.bytes -gt 16MB -or
            $row.resolvedPathContained -isnot [bool] -or -not $row.resolvedPathContained -or
            $row.ancestorNonReparse -isnot [bool] -or -not $row.ancestorNonReparse -or
            $row.reparseTraversal -isnot [bool] -or $row.reparseTraversal -or $row.leafKind -isnot [string]) { throw $Reason }
        if ($row.exists) {
            if ($row.sha256 -isnot [string] -or [string]$row.sha256 -cnotmatch '^[A-F0-9]{64}$' -or [string]$row.leafKind -cne 'leaf') { throw $Reason }
        } elseif ([int64]$row.bytes -ne 0 -or $null -ne $row.sha256 -or [string]$row.leafKind -cne 'missing') { throw $Reason }
    }
}

function Get-ControllerCommandIds {
    param($Unit, [ValidateSet('redCommands','greenCommands')][string]$Name)

    if (-not (Test-HasProperty $Unit $Name)) { return ,@() }
    Assert-JsonArray $Unit.$Name 'invalid-command-contract' 64
    $kind = if ($Name -ceq 'redCommands') { 'red' } else { 'green' }
    $seenCommands = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $seenIds = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    $ids = @()
    foreach ($command in @($Unit.$Name)) {
        if ($command -isnot [string] -or [string]::IsNullOrWhiteSpace([string]$command) -or -not $seenCommands.Add([string]$command)) { throw 'invalid-command-contract' }
        foreach ($character in ([string]$command).ToCharArray()) {
            $category = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($character)
            if ([char]::IsControl($character) -or $category -in @(
                [Globalization.UnicodeCategory]::LineSeparator,
                [Globalization.UnicodeCategory]::ParagraphSeparator
            )) { throw 'invalid-command-contract' }
        }
        try { $bytes = $Utf8.GetBytes([string]$command) } catch { throw 'invalid-command-contract' }
        if ($bytes.Length -gt 4096) { throw 'invalid-command-contract' }
        $id = ('{0}:sha256:{1}' -f $kind, (Get-Sha256 $bytes).ToLowerInvariant())
        if (-not $seenIds.Add($id)) { throw 'invalid-command-contract' }
        $ids += ,$id
    }
    return @($ids)
}

function Assert-WorkUnitRecord {
    param($Record, [string[]]$Targets, [string]$Reason)

    Assert-ClosedProperties $Record @(
        'runId','outcome','evidenceSha256','targetPreimages','targetPostimages','redCommandId',
        'greenCommandIds','rollbackStatus','desktopFinalProof'
    ) @() $Reason
    if ($Record.runId -isnot [string] -or [string]$Record.runId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' -or
        $Record.outcome -isnot [string] -or [string]$Record.outcome -cnotin @('Green','NoPatchNeeded','Hold','Failed') -or
        $Record.evidenceSha256 -isnot [string] -or [string]$Record.evidenceSha256 -cnotmatch '^[A-F0-9]{64}$' -or
        $Record.rollbackStatus -isnot [string] -or [string]$Record.rollbackStatus -cnotin @('not_required','verified') -or
        $Record.desktopFinalProof -isnot [string] -or [string]$Record.desktopFinalProof -cnotin @('verified','evidence_needed')) { throw $Reason }
    Assert-WorkUnitImages $Record.targetPreimages $Targets $Reason
    Assert-WorkUnitImages $Record.targetPostimages $Targets $Reason
    Assert-JsonArray $Record.greenCommandIds $Reason 64
    $seenGreenIds = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($commandId in @($Record.greenCommandIds)) {
        if ($commandId -isnot [string] -or [string]$commandId -cnotmatch '^green:sha256:[a-f0-9]{64}$' -or -not $seenGreenIds.Add([string]$commandId)) { throw $Reason }
    }
    if ($null -ne $Record.redCommandId -and ($Record.redCommandId -isnot [string] -or [string]$Record.redCommandId -cnotmatch '^red:sha256:[a-f0-9]{64}$')) { throw $Reason }
    if ([string]$Record.outcome -ceq 'Green' -and $null -eq $Record.redCommandId) { throw $Reason }
    if ([string]$Record.outcome -cne 'Green' -and $null -ne $Record.redCommandId) { throw $Reason }
}

function Assert-MutableWorkUnitPhase {
    param($Unit, [string[]]$Targets, [string]$CanonicalMarkdownSha256, [bool]$AllowFrozenGenericTerminal)

    $hasTransition = Test-HasProperty $Unit 'transition'
    $hasExecution = Test-HasProperty $Unit 'execution'
    $hasRecord = Test-HasProperty $Unit 'record'
    if ($hasExecution) {
        Assert-ClosedProperties $Unit.execution @('runId','owner','leaseTopic','canonicalMarkdownSha256','targetPreimages') @() 'invalid-work-unit-contract'
        if ($Unit.execution.runId -isnot [string] -or [string]$Unit.execution.runId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' -or
            [string]$Unit.execution.owner -cne 'desktop' -or $Unit.execution.leaseTopic -isnot [string] -or
            [string]$Unit.execution.leaseTopic -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$' -or
            $Unit.execution.canonicalMarkdownSha256 -isnot [string] -or [string]$Unit.execution.canonicalMarkdownSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'invalid-work-unit-contract' }
        Assert-WorkUnitImages $Unit.execution.targetPreimages $Targets 'invalid-work-unit-contract'
    }
    if ($hasRecord) { Assert-WorkUnitRecord $Unit.record $Targets 'invalid-work-unit-contract' }
    if ($hasTransition) {
        if ($Unit.transition.phase -isnot [string]) { throw 'invalid-work-unit-contract' }
        switch -CaseSensitive ([string]$Unit.transition.phase) {
            'begin-intent' {
                Assert-ClosedProperties $Unit.transition @('phase','runId','owner','leaseTopic','canonicalMarkdownSha256') @() 'invalid-work-unit-contract'
                if ($Unit.transition.canonicalMarkdownSha256 -isnot [string] -or [string]$Unit.transition.canonicalMarkdownSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'invalid-work-unit-contract' }
            }
            'begin-recovery-required' {
                Assert-ClosedProperties $Unit.transition @('phase','runId','owner','leaseTopic') @() 'invalid-work-unit-contract'
            }
            'record-awaiting-release' {
                Assert-ClosedProperties $Unit.transition @('phase','runId','owner','leaseTopic','outcome','terminalStatus','evidenceSha256','record') @() 'invalid-work-unit-contract'
                if ($Unit.transition.outcome -isnot [string] -or [string]$Unit.transition.outcome -cnotin @('Green','NoPatchNeeded','Hold','Failed') -or
                    $Unit.transition.terminalStatus -isnot [string] -or [string]$Unit.transition.terminalStatus -cnotin @('green','no_patch_needed','hold','failed') -or
                    $Unit.transition.evidenceSha256 -isnot [string] -or [string]$Unit.transition.evidenceSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'invalid-work-unit-contract' }
                Assert-WorkUnitRecord $Unit.transition.record $Targets 'invalid-work-unit-contract'
            }
            default { throw 'invalid-work-unit-contract' }
        }
        if ($Unit.transition.runId -isnot [string] -or [string]$Unit.transition.runId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' -or
            [string]$Unit.transition.owner -cne 'desktop' -or $Unit.transition.leaseTopic -isnot [string] -or
            [string]$Unit.transition.leaseTopic -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$') { throw 'invalid-work-unit-contract' }
    }

    switch -CaseSensitive ([string]$Unit.status) {
        'pending' {
            if ($hasExecution -or $hasRecord) { throw 'invalid-work-unit-contract' }
            if ($hasTransition -and [string]$Unit.transition.phase -cnotin @('begin-intent','begin-recovery-required')) { throw 'invalid-work-unit-contract' }
        }
        'running' {
            if (-not $hasExecution -or $hasRecord) { throw 'invalid-work-unit-contract' }
            if ($hasTransition -and [string]$Unit.transition.phase -cne 'record-awaiting-release') { throw 'invalid-work-unit-contract' }
        }
        { $_ -cin @('green','no_patch_needed','failed') } {
            if (($hasExecution -or $hasTransition) -or (-not $hasRecord -and -not $AllowFrozenGenericTerminal)) { throw 'invalid-work-unit-contract' }
        }
        'hold' {
            $approvalOnly = (Test-HasProperty $Unit 'approvalGate') -and -not $hasRecord
            if (($hasExecution -or $hasTransition) -or (-not $hasRecord -and -not $approvalOnly -and -not $AllowFrozenGenericTerminal)) { throw 'invalid-work-unit-contract' }
        }
    }
    if ($hasExecution -and $hasTransition -and [string]$Unit.transition.phase -eq 'record-awaiting-release') {
        if ([string]$Unit.transition.runId -cne [string]$Unit.execution.runId -or
            [string]$Unit.transition.leaseTopic -cne [string]$Unit.execution.leaseTopic -or
            [string]$Unit.transition.runId -cne [string]$Unit.transition.record.runId -or
            [string]$Unit.transition.outcome -cne [string]$Unit.transition.record.outcome -or
            [string]$Unit.transition.evidenceSha256 -cne [string]$Unit.transition.record.evidenceSha256 -or
            -not (Test-SameJson $Unit.execution.targetPreimages $Unit.transition.record.targetPreimages)) { throw 'invalid-work-unit-contract' }
        $expectedStatus = switch ([string]$Unit.transition.outcome) { 'Green' {'green'} 'NoPatchNeeded' {'no_patch_needed'} 'Hold' {'hold'} 'Failed' {'failed'} }
        if ([string]$Unit.transition.terminalStatus -cne $expectedStatus) { throw 'invalid-work-unit-contract' }
    }
    if ($hasRecord) {
        $expectedStatus = switch ([string]$Unit.record.outcome) { 'Green' {'green'} 'NoPatchNeeded' {'no_patch_needed'} 'Hold' {'hold'} 'Failed' {'failed'} }
        if ([string]$Unit.status -cne $expectedStatus) { throw 'invalid-work-unit-contract' }
    }
    $recordToCheck = if ($hasRecord) { $Unit.record } elseif ($hasTransition -and [string]$Unit.transition.phase -ceq 'record-awaiting-release') { $Unit.transition.record } else { $null }
    if ($null -ne $recordToCheck) {
        $declaredRedIds = @(Get-ControllerCommandIds $Unit 'redCommands')
        $declaredGreenIds = @(Get-ControllerCommandIds $Unit 'greenCommands')
        if ([string]$recordToCheck.outcome -cin @('Green','NoPatchNeeded')) {
            if (-not (Test-SameJson @($recordToCheck.greenCommandIds) $declaredGreenIds) -or $declaredGreenIds.Count -eq 0 -or
                [string]$recordToCheck.desktopFinalProof -cne 'verified' -or [string]$recordToCheck.rollbackStatus -cne 'not_required') { throw 'invalid-work-unit-contract' }
        } elseif (@($recordToCheck.greenCommandIds).Count -ne 0 -or [string]$recordToCheck.rollbackStatus -cne 'verified') { throw 'invalid-work-unit-contract' }
        if ([string]$recordToCheck.outcome -ceq 'Green') {
            if ($declaredRedIds.Count -eq 0 -or $declaredRedIds -cnotcontains [string]$recordToCheck.redCommandId) { throw 'invalid-work-unit-contract' }
            if ($Targets.Count -gt 0 -and (Test-SameJson $recordToCheck.targetPreimages $recordToCheck.targetPostimages)) { throw 'invalid-work-unit-contract' }
        } elseif ($null -ne $recordToCheck.redCommandId) { throw 'invalid-work-unit-contract' }
        if ([string]$recordToCheck.outcome -cin @('NoPatchNeeded','Hold','Failed') -and
            -not (Test-SameJson $recordToCheck.targetPreimages $recordToCheck.targetPostimages)) { throw 'invalid-work-unit-contract' }
    }
    $expectedTopic = 'consolidated-' + $CanonicalMarkdownSha256.Substring(0, 12).ToLowerInvariant() + '-' + [string]$Unit.workUnitId
    if ($expectedTopic.Length -gt 96) { throw 'invalid-work-unit-contract' }
    if ($hasExecution -and ([string]$Unit.execution.leaseTopic -cne $expectedTopic -or [string]$Unit.execution.canonicalMarkdownSha256 -cne $CanonicalMarkdownSha256)) { throw 'invalid-work-unit-contract' }
    if ($hasTransition) {
        if ([string]$Unit.transition.leaseTopic -cne $expectedTopic) { throw 'invalid-work-unit-contract' }
        if ([string]$Unit.transition.phase -ceq 'begin-intent' -and [string]$Unit.transition.canonicalMarkdownSha256 -cne $CanonicalMarkdownSha256) { throw 'invalid-work-unit-contract' }
    }
}

function Assert-ApprovalGate {
    param($ApprovalGate)

    Assert-ClosedProperties $ApprovalGate @('schemaVersion','requiredForBegin','acceptAny') @() 'invalid-work-unit-contract'
    if ($ApprovalGate.schemaVersion -isnot [string] -or [string]$ApprovalGate.schemaVersion -cne 'awx.notebook.directive.approval-gate.v1' -or
        $ApprovalGate.requiredForBegin -isnot [bool] -or -not $ApprovalGate.requiredForBegin) { throw 'invalid-work-unit-contract' }
    Assert-JsonArray $ApprovalGate.acceptAny 'invalid-work-unit-contract' 2
    if (@($ApprovalGate.acceptAny).Count -eq 0) { throw 'invalid-work-unit-contract' }
    $seen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($value in @($ApprovalGate.acceptAny)) {
        if ($value -isnot [string] -or [string]$value -cnotin @('noNewPublicApi','explicitApproval') -or -not $seen.Add([string]$value)) {
            throw 'invalid-work-unit-contract'
        }
    }
}

function Assert-WorkUnits {
    param($WorkUnits, [switch]$MutableState, [AllowNull()][string]$CanonicalMarkdownSha256 = $null, [switch]$AllowFrozenGenericTerminal)

    if ($WorkUnits -isnot [Array]) { throw 'controller-manifest-schema' }
    if (@($WorkUnits).Count -gt $WorkUnitLimit) { throw 'too-many-work-units' }

    $unitsById = @{}
    foreach ($unit in @($WorkUnits)) {
        $optionalFields = @('requirementCoverage','redCommands','greenCommands','rollback','retirementCoverage','causalBoundary','approvalGate')
        if ($MutableState) { $optionalFields += @('transition','execution','record') }
        Assert-ClosedProperties $unit @('workUnitId', 'status', 'dependencies', 'required', 'kind', 'targetFiles') $optionalFields 'invalid-work-unit-contract'
        if ($unit.workUnitId -isnot [string] -or $unit.workUnitId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
            throw 'invalid-work-unit-id'
        }
        if ($unitsById.ContainsKey($unit.workUnitId)) { throw 'duplicate-work-unit-id' }
        if ($unit.status -isnot [string] -or $Statuses -cnotcontains $unit.status) { throw 'invalid-work-unit-status' }
        if ($unit.required -isnot [bool]) { throw 'invalid-work-unit-contract' }
        if ($unit.kind -isnot [string] -or $AllowedKinds -cnotcontains $unit.kind) { throw 'invalid-work-unit-kind' }
        if ($unit.dependencies -isnot [Array]) { throw 'invalid-dependencies' }
        if (@($unit.dependencies).Count -gt $DependencyLimit) { throw 'too-many-dependencies' }
        if ($unit.targetFiles -isnot [Array]) { throw 'invalid-target-files' }
        if (@($unit.targetFiles).Count -gt $TargetLimit) { throw 'too-many-target-files' }

        $targets = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($target in @($unit.targetFiles)) {
            if ($target -isnot [string]) { throw 'invalid-target-file' }
            $normalizedTarget = ConvertTo-ValidatedRelativePath $target 'invalid-target-file'
            if (-not $targets.Add($normalizedTarget)) { throw 'duplicate-target-file' }
        }
        if (Test-HasProperty $unit 'approvalGate') { Assert-ApprovalGate $unit.approvalGate }
        if (Test-HasProperty $unit 'redCommands') { $null = @(Get-ControllerCommandIds $unit 'redCommands') }
        if (Test-HasProperty $unit 'greenCommands') { $null = @(Get-ControllerCommandIds $unit 'greenCommands') }
        if ($MutableState) {
            if ($CanonicalMarkdownSha256 -notmatch '^[A-F0-9]{64}$') { throw 'invalid-state-schema' }
            Assert-MutableWorkUnitPhase $unit @($unit.targetFiles | ForEach-Object { [string]$_ }) $CanonicalMarkdownSha256 ([bool]$AllowFrozenGenericTerminal)
        }
        $unitsById[$unit.workUnitId] = $unit
    }

    foreach ($unit in @($WorkUnits)) {
        $dependencies = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        foreach ($dependency in @($unit.dependencies)) {
            if ($dependency -isnot [string]) { throw 'invalid-dependencies' }
            if ($dependency -ceq $unit.workUnitId) { throw 'self-dependency-id' }
            if (-not $unitsById.ContainsKey($dependency)) { throw 'unknown-dependency-id' }
            if (-not $dependencies.Add($dependency)) { throw 'duplicate-dependency-id' }
        }
    }

    $remaining = @{}
    foreach ($unit in @($WorkUnits)) { $remaining[$unit.workUnitId] = @($unit.dependencies).Count }
    $ready = New-Object 'Collections.Generic.Queue[string]'
    foreach ($unit in @($WorkUnits)) {
        if ($remaining[$unit.workUnitId] -eq 0) { $ready.Enqueue($unit.workUnitId) }
    }
    $visited = 0
    while ($ready.Count -gt 0) {
        $completedId = $ready.Dequeue()
        $visited++
        foreach ($unit in @($WorkUnits)) {
            if (@($unit.dependencies) -ccontains $completedId) {
                $remaining[$unit.workUnitId]--
                if ($remaining[$unit.workUnitId] -eq 0) { $ready.Enqueue($unit.workUnitId) }
            }
        }
    }
    if ($visited -ne @($WorkUnits).Count) { throw 'dependency-cycle' }
    if ($MutableState) {
        $active = @($WorkUnits | Where-Object { [string]$_.status -ceq 'running' -or (Test-HasProperty $_ 'transition') })
        if ($active.Count -gt 1) { throw 'invalid-work-unit-contract' }
    }
}

function Get-ControllerManifest {
    param([string]$Path)

    $programFile = Read-BoundedFile $Path $ProgramLimit 'program-too-large'
    $begin = '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->'
    $end = '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->'
    $beginMatches = [regex]::Matches($programFile.text, '(?m)^' + [regex]::Escape($begin) + '\r?$')
    $endMatches = [regex]::Matches($programFile.text, '(?m)^' + [regex]::Escape($end) + '\r?$')
    if ($beginMatches.Count -gt 1 -or $endMatches.Count -gt 1) { throw 'controller-manifest-duplicate' }
    if ($beginMatches.Count -ne 1 -or $endMatches.Count -ne 1) { throw 'controller-manifest-missing' }

    $pattern = '(?ms)^' + [regex]::Escape($begin) + '\r?\n```json\r?\n(?<json>.*?)\r?\n```\r?\n' + [regex]::Escape($end) + '\r?$'
    $match = [regex]::Match($programFile.text, $pattern)
    if (-not $match.Success) { throw 'controller-manifest-malformed' }
    $manifest = ConvertFrom-StrictJson $match.Groups['json'].Value 'controller-manifest-malformed'
    Assert-ClosedProperties $manifest @('schemaVersion', 'directiveInventory', 'workUnits', 'retirement') @('programId','sourceOwner','activeSourceSets','legacyRequirementCoverage','desktopFinalProof','runtimeLineageVerdict','deleteAuthorized') 'controller-manifest-schema'
    if ($manifest.schemaVersion -isnot [string] -or $manifest.schemaVersion -cne 'awx.notebook.directive.controller-manifest.v1') {
        throw 'controller-manifest-schema'
    }
    Assert-CanonicalManifest $manifest $programFile.text.Substring(0, $beginMatches[0].Index)

    $secondRead = Read-BoundedFile $Path $ProgramLimit 'program-too-large'
    if ($secondRead.hash -cne $programFile.hash) { throw 'canonical-hash-changed' }
    return [pscustomobject]@{ manifest = $manifest; hash = $programFile.hash }
}

function Assert-CanonicalManifest {
    param($Manifest, [string]$ProgramText)

    $ids = @('WU-A0','WU-S40-P0','WU-C20','WU-C21','WU-H30','WU-S50','WU-E10','WU-P100','WU-R70','WU-G80','WU-U90','WU-S40-JAVA','WU-N110','WU-FINAL')
    $dependencyContract = ConvertFrom-StrictJson '{"items":[[],["WU-A0"],["WU-A0"],["WU-C20"],["WU-A0"],["WU-A0"],["WU-A0"],["WU-E10"],["WU-A0"],["WU-A0"],["WU-A0"],["WU-S40-P0"],["WU-C21"],["WU-A0","WU-S40-P0","WU-C20","WU-C21","WU-H30","WU-S50","WU-E10","WU-P100","WU-R70","WU-G80","WU-U90","WU-S40-JAVA","WU-N110"]]}' 'canonical-manifest-schema'
    Assert-RequiredProperties $Manifest @('programId','sourceOwner','activeSourceSets','legacyRequirementCoverage','desktopFinalProof','runtimeLineageVerdict','deleteAuthorized') 'canonical-manifest-schema'
    if ($Manifest.programId -cne 'awx-desktop-notebook-consolidated-source-20260806' -or
        $Manifest.sourceOwner -cne 'desktop' -or
        -not (Test-SameJson @($Manifest.activeSourceSets) @('main/java','main/resources','app/src/main/java_clean','app/src/main/resources')) -or
        $Manifest.desktopFinalProof -cne 'evidence_needed' -or $Manifest.runtimeLineageVerdict -cne 'HOLD' -or
        $Manifest.deleteAuthorized -isnot [bool] -or $Manifest.deleteAuthorized) { throw 'canonical-manifest-schema' }
    if (@($Manifest.workUnits).Count -ne $ids.Count) { throw 'canonical-work-unit-count' }
    $fullFields = @('workUnitId','status','dependencies','required','kind','targetFiles','requirementCoverage','redCommands','greenCommands','rollback','retirementCoverage','causalBoundary')
    for ($index = 0; $index -lt $ids.Count; $index++) {
        $unit = $Manifest.workUnits[$index]
        Assert-RequiredProperties $unit $fullFields 'canonical-work-unit-contract'
        if ($unit.workUnitId -cne $ids[$index] -or -not (Test-SameJson @($unit.dependencies) @($dependencyContract.items[$index])) -or
            $unit.required -isnot [bool] -or -not $unit.required -or $unit.requirementCoverage -isnot [Array] -or
            $unit.redCommands -isnot [Array] -or $unit.greenCommands -isnot [Array] -or $unit.rollback -isnot [string] -or
            $unit.retirementCoverage -isnot [Array] -or $unit.causalBoundary -isnot [string]) { throw 'canonical-work-unit-contract' }
    }
    $n110 = $Manifest.workUnits[12]
    if ($n110.status -cne 'hold' -or -not (Test-HasProperty $n110 'approvalGate')) { throw 'canonical-n110-approval-gate' }
    Assert-ClosedProperties $n110.approvalGate @('schemaVersion','requiredForBegin','acceptAny') @() 'canonical-n110-approval-gate'
    if ($n110.approvalGate.schemaVersion -cne 'awx.notebook.directive.approval-gate.v1' -or $n110.approvalGate.requiredForBegin -ne $true -or
        -not (Test-SameJson @($n110.approvalGate.acceptAny) @('noNewPublicApi','explicitApproval'))) { throw 'canonical-n110-approval-gate' }
    if ($Manifest.legacyRequirementCoverage -isnot [Array]) { throw 'canonical-requirement-coverage' }
    $requirementIds = @([regex]::Matches($ProgramText, '(?m)requirementId:\s*["'']?([A-Z][A-Z0-9-]+)') | ForEach-Object { $_.Groups[1].Value })
    if ($requirementIds.Count -ne 435 -or @($requirementIds | Sort-Object -Unique).Count -ne 435) { throw 'canonical-requirement-count' }
    foreach ($requirementId in $requirementIds) {
        $matches = @($Manifest.legacyRequirementCoverage | Where-Object {
            ($_.matchKind -ceq 'exact' -and $_.value -ceq $requirementId) -or
            ($_.matchKind -ceq 'prefix' -and $requirementId.StartsWith([string]$_.value, [StringComparison]::Ordinal))
        })
        if ($matches.Count -ne 1) { throw 'canonical-requirement-coverage' }
    }
    foreach ($rule in @($Manifest.legacyRequirementCoverage)) {
        Assert-ClosedProperties $rule @('matchKind','value','workUnitId','expectedCount') @() 'canonical-requirement-coverage'
        if ($rule.matchKind -cnotin @('exact','prefix') -or $ids -cnotcontains $rule.workUnitId -or
            -not (Test-JsonInteger $rule.expectedCount)) { throw 'canonical-requirement-coverage' }
        $actual = @($requirementIds | Where-Object { if ($rule.matchKind -ceq 'exact') { $_ -ceq $rule.value } else { $_.StartsWith([string]$rule.value, [StringComparison]::Ordinal) } }).Count
        if ($actual -ne [int]$rule.expectedCount) { throw 'canonical-requirement-coverage' }
    }
}

function Assert-RetirementSchema {
    param($Retirement, $Inventory, $WorkUnits)

    Assert-ClosedProperties $Retirement @(
        'schemaVersion','deleteAuthorized','canonicalDirectivePath','canonicalDirectiveSha256Evidence',
        'allRequiredWorkUnitsGreen','desktopFinalProof','status','items'
    ) @() 'invalid-retirement-schema'
    if ($Retirement.schemaVersion -isnot [string] -or $Retirement.schemaVersion -cne 'demo1.notebook-directive-retirement.v1' -or
        $Retirement.deleteAuthorized -isnot [bool] -or
        $Retirement.canonicalDirectivePath -isnot [string] -or $Retirement.canonicalDirectivePath -cne 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md' -or
        $Retirement.canonicalDirectiveSha256Evidence -isnot [string] -or $Retirement.canonicalDirectiveSha256Evidence -cne 'external-final-output' -or
        $Retirement.allRequiredWorkUnitsGreen -isnot [bool] -or
        $Retirement.desktopFinalProof -isnot [string] -or $Retirement.desktopFinalProof -cnotin @('verified','evidence_needed') -or
        $Retirement.status -isnot [string] -or $Retirement.status -cnotin @('pending','hold','eligible','retired','failed')) {
        throw 'invalid-retirement-schema'
    }
    if ($Retirement.items -isnot [Array]) { throw 'invalid-retirement-items' }
    if (@($Retirement.items).Count -gt $InventoryLimit) { throw 'invalid-retirement-items' }

    $unitsById = @{}
    foreach ($unit in @($WorkUnits)) { $unitsById[[string]$unit.workUnitId] = $unit }
    $candidatesByPath = @{}
    foreach ($candidate in @($Inventory.candidates)) {
        $relativeCandidate = ConvertTo-ValidatedRelativePath ([string]$candidate.path) 'invalid-candidate-path'
        if ($candidatesByPath.ContainsKey($relativeCandidate)) { throw 'duplicate-candidate-path' }
        $candidatesByPath[$relativeCandidate] = $candidate
    }

    $retirementPaths = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $itemCoverageByPath = @{}
    foreach ($item in @($Retirement.items)) {
        Assert-ClosedProperties $item @('path','sha256','bytes','gitTracking','coverage','eligibility','holdReason','deletionResult','status') @() 'invalid-retirement-item'
        if ($item.path -isnot [string] -or
            $item.sha256 -isnot [string] -or $item.sha256 -cnotmatch '^[0-9A-F]{64}$' -or
            -not (Test-JsonInteger $item.bytes) -or [int64]$item.bytes -lt 0 -or [int64]$item.bytes -gt $CandidateLimit -or
            $item.gitTracking -isnot [string] -or $item.gitTracking -cnotin @('tracked','untracked') -or
            $item.coverage -isnot [Array] -or @($item.coverage).Count -eq 0 -or @($item.coverage).Count -gt $WorkUnitLimit -or
            $item.eligibility -isnot [string] -or $item.eligibility -cnotin @('eligible','excluded','hold') -or
            $item.holdReason -isnot [string] -or [string]::IsNullOrWhiteSpace($item.holdReason) -or
            $item.deletionResult -isnot [string] -or $item.deletionResult -cnotin @('not_run','removed','hash_changed','missing') -or
            $item.status -isnot [string] -or $item.status -cnotin @('pending','hold','eligible','retired','failed')) {
            throw 'invalid-retirement-item'
        }
        $relative = ConvertTo-ValidatedRelativePath $item.path 'invalid-retirement-path'
        if (-not $retirementPaths.Add($relative)) { throw 'duplicate-retirement-path' }
        $coverage = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        foreach ($workUnitId in @($item.coverage)) {
            if ($workUnitId -isnot [string] -or [string]::IsNullOrWhiteSpace($workUnitId)) { throw 'invalid-retirement-item' }
            if (-not $coverage.Add($workUnitId)) { throw 'invalid-retirement-coverage' }
            if (-not $unitsById.ContainsKey($workUnitId)) { throw 'unknown-retirement-coverage' }
        }
        if (-not $candidatesByPath.ContainsKey($relative)) { throw 'retirement-candidate-mismatch' }
        $candidate = $candidatesByPath[$relative]
        if ([string]$candidate.sha256 -cne [string]$item.sha256 -or
            [int64]$candidate.bytes -ne [int64]$item.bytes -or
            [string]$candidate.gitTracking -cne [string]$item.gitTracking) {
            throw 'retirement-candidate-mismatch'
        }
        $itemCoverageByPath[$relative] = $coverage
    }

    $workUnitCoverage = @{}
    foreach ($unit in @($WorkUnits)) {
        if (-not (Test-HasProperty $unit 'retirementCoverage')) { continue }
        $seen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($path in @($unit.retirementCoverage)) {
            if ($path -ceq 'all-listed-originals') { continue }
            $relative = ConvertTo-ValidatedRelativePath ([string]$path) 'invalid-retirement-path'
            if (-not $seen.Add($relative)) { throw 'retirement-coverage-mismatch' }
            if (-not $retirementPaths.Contains($relative)) { throw 'retirement-coverage-mismatch' }
            if (-not $workUnitCoverage.ContainsKey($relative)) {
                $workUnitCoverage[$relative] = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
            }
            $null = $workUnitCoverage[$relative].Add([string]$unit.workUnitId)
        }
    }
    foreach ($path in @($retirementPaths)) {
        if (-not $workUnitCoverage.ContainsKey($path)) { throw 'retirement-coverage-mismatch' }
        $itemCoverage = $itemCoverageByPath[$path]
        $unitCoverage = $workUnitCoverage[$path]
        if ($itemCoverage.Count -ne $unitCoverage.Count) { throw 'retirement-coverage-mismatch' }
        foreach ($workUnitId in @($itemCoverage)) {
            if (-not $unitCoverage.Contains($workUnitId)) { throw 'retirement-coverage-mismatch' }
        }
    }
}

function Assert-Manifest {
    param($Manifest)

    Assert-InventorySchema $Manifest.directiveInventory
    Assert-WorkUnits $Manifest.workUnits
    if (-not (Test-SameJson $Manifest.directiveInventory (Get-DirectiveInventory))) {
        throw 'candidate-inventory-mismatch'
    }
    Assert-RetirementSchema $Manifest.retirement $Manifest.directiveInventory $Manifest.workUnits
    $requiredManifestUnits = @($Manifest.workUnits | Where-Object { $_.required -eq $true })
    $allRequiredGreen = $requiredManifestUnits.Count -gt 0 -and
        @($requiredManifestUnits | Where-Object { $_.status -cnotin @('green','no_patch_needed') }).Count -eq 0
    if ($Manifest.retirement.allRequiredWorkUnitsGreen -ne $allRequiredGreen) { throw 'invalid-retirement-schema' }
    if ((Test-HasProperty $Manifest 'deleteAuthorized') -and $Manifest.deleteAuthorized -ne $Manifest.retirement.deleteAuthorized) { throw 'invalid-retirement-schema' }
    if ((Test-HasProperty $Manifest 'desktopFinalProof') -and $Manifest.desktopFinalProof -cne $Manifest.retirement.desktopFinalProof) { throw 'invalid-retirement-schema' }
    if (Test-HasProperty $Manifest 'programId') {
        if (@($Manifest.workUnits).Count -ne 14 -or @($Manifest.directiveInventory.candidates).Count -ne 10 -or
            @($Manifest.directiveInventory.excluded).Count -ne 34 -or @($Manifest.retirement.items).Count -ne 10 -or
            $Manifest.retirement.deleteAuthorized -or $Manifest.retirement.allRequiredWorkUnitsGreen -or
            $Manifest.retirement.desktopFinalProof -cne 'evidence_needed' -or $Manifest.retirement.status -cne 'hold' -or
            @($Manifest.retirement.items | Where-Object { $_.eligibility -cne 'hold' -or $_.deletionResult -cne 'not_run' -or $_.status -cne 'hold' }).Count -ne 0 -or
            @($Manifest.retirement.items | Where-Object { $_.eligibility -ceq 'eligible' }).Count -ne 0) {
            throw 'canonical-retirement-contract'
        }
        foreach ($item in @($Manifest.retirement.items)) {
            $expectedReason = if ($item.path -ceq '__patch_drop__/notebook/http-rollback-local-smoke-desktop-directive.md') {
                'superseded-by-http-rollback-v2'
            } elseif ($item.path -ceq 'data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md') {
                'full-program-proof-pending'
            } else {
                'desktop-work-unit-proof-pending'
            }
            if ($item.holdReason -cne $expectedReason) { throw 'canonical-retirement-contract' }
        }
    }
}

function Copy-ControllerValue {
    param($Value)
    return ConvertFrom-StrictJson (ConvertTo-Json -InputObject $Value -Depth 40 -Compress) 'invalid-state-schema'
}

function Get-DerivedStateProjection {
    param($StateObject)

    $requiredUnits = @($StateObject.workUnits | Where-Object { $_.required -eq $true })
    $allRequiredGreen = $requiredUnits.Count -gt 0
    $desktopVerified = $allRequiredGreen
    foreach ($unit in $requiredUnits) {
        if ([string]$unit.status -cnotin @('green','no_patch_needed')) {
            $allRequiredGreen = $false
            $desktopVerified = $false
            continue
        }
        if (-not (Test-HasProperty $unit 'record') -or [string]$unit.record.desktopFinalProof -cne 'verified') {
            $desktopVerified = $false
        }
    }
    return [pscustomobject][ordered]@{
        allRequiredWorkUnitsGreen = [bool]$allRequiredGreen
        desktopFinalProof = if ($desktopVerified) { 'verified' } else { 'evidence_needed' }
    }
}

function Get-EventProgramId {
    param($ManifestResult, $StateObject)

    if (Test-HasProperty $ManifestResult.manifest 'programId') {
        $programId = [string]$ManifestResult.manifest.programId
    } else {
        # The production manifest gate requires programId. This deterministic
        # fallback is reachable only in the exact temp-copy generic-manifest seam.
        $programId = [IO.Path]::GetFileNameWithoutExtension(([string]$StateObject.canonicalMarkdownPath).Replace('/', '\'))
    }
    if ([string]::IsNullOrWhiteSpace($programId) -or $programId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        throw 'event-checkpoint-invalid'
    }
    return $programId
}

function Get-EventStateProjection {
    param($StateObject)

    $units = @()
    foreach ($unit in @($StateObject.workUnits)) {
        $copy = Copy-ControllerValue $unit
        if (Test-HasProperty $copy 'transition') { $copy.PSObject.Properties.Remove('transition') }
        $units += ,$copy
    }
    return [pscustomobject][ordered]@{
        schemaVersion = [string]$StateObject.schemaVersion
        canonicalMarkdownPath = [string]$StateObject.canonicalMarkdownPath
        canonicalMarkdownSha256 = [string]$StateObject.canonicalMarkdownSha256
        sourceOwner = [string]$StateObject.sourceOwner
        activeSourceSets = @($StateObject.activeSourceSets | ForEach-Object { [string]$_ })
        controllerManifest = Copy-ControllerValue $StateObject.controllerManifest
        directiveInventory = Copy-ControllerValue $StateObject.directiveInventory
        workUnits = @($units)
        retirement = Copy-ControllerValue $StateObject.retirement
        desktopFinalProof = [string]$StateObject.desktopFinalProof
        runtimeLineageVerdict = [string]$StateObject.runtimeLineageVerdict
        deleteAuthorized = [bool]$StateObject.deleteAuthorized
    }
}

function Get-EventBaselineState {
    param($StateObject, $ManifestResult)

    $baseline = [pscustomobject][ordered]@{
        schemaVersion = [string]$StateObject.schemaVersion
        canonicalMarkdownPath = [string]$StateObject.canonicalMarkdownPath
        canonicalMarkdownSha256 = [string]$StateObject.canonicalMarkdownSha256
        sourceOwner = [string]$StateObject.sourceOwner
        activeSourceSets = @($StateObject.activeSourceSets | ForEach-Object { [string]$_ })
        controllerManifest = Copy-ControllerValue $ManifestResult.manifest
        directiveInventory = Copy-ControllerValue $ManifestResult.manifest.directiveInventory
        workUnits = @($ManifestResult.manifest.workUnits | ForEach-Object { Copy-ControllerValue $_ })
        retirement = Copy-ControllerValue $ManifestResult.manifest.retirement
        desktopFinalProof = 'evidence_needed'
        runtimeLineageVerdict = 'HOLD'
        deleteAuthorized = $false
        eventCheckpoint = [ordered]@{}
    }
    $derived = Get-DerivedStateProjection $baseline
    $baseline.desktopFinalProof = [string]$derived.desktopFinalProof
    $baseline.retirement.allRequiredWorkUnitsGreen = [bool]$derived.allRequiredWorkUnitsGreen
    $baseline.retirement.desktopFinalProof = [string]$derived.desktopFinalProof
    return $baseline
}

function Get-EventRunIdHash {
    param([string]$RunIdValue)

    if ([string]::IsNullOrWhiteSpace($RunIdValue)) { throw 'event-checkpoint-invalid' }
    return Get-Sha256 $Utf8.GetBytes("awx.notebook.directive.run-id.v1`0$RunIdValue")
}

function Set-EventDerivedFields {
    param($StateObject)

    $derived = Get-DerivedStateProjection $StateObject
    $StateObject.desktopFinalProof = [string]$derived.desktopFinalProof
    $StateObject.retirement.allRequiredWorkUnitsGreen = [bool]$derived.allRequiredWorkUnitsGreen
    $StateObject.retirement.desktopFinalProof = [string]$derived.desktopFinalProof
}

function Set-EventProjectionUnit {
    param($StateObject, [int]$Index, $Unit)

    $units = @($StateObject.workUnits)
    if ($Index -lt 0 -or $Index -ge $units.Count) { throw 'event-checkpoint-invalid' }
    $units[$Index] = $Unit
    $StateObject.workUnits = @($units)
}

function Get-EventResultFromOutcome {
    param([string]$Outcome)
    switch -CaseSensitive ($Outcome) {
        'Green' { return 'green' }
        'NoPatchNeeded' { return 'no_patch_needed' }
        'Hold' { return 'hold' }
        'Failed' { return 'failed' }
    }
    throw 'event-checkpoint-invalid'
}

function Get-EventRunningUnitFromRecord {
    param($TerminalUnit, [string]$CanonicalHash)

    $record = $TerminalUnit.record
    $running = Copy-ControllerValue $TerminalUnit
    $running.status = 'running'
    if (Test-HasProperty $running 'record') { $running.PSObject.Properties.Remove('record') }
    if (Test-HasProperty $running 'transition') { $running.PSObject.Properties.Remove('transition') }
    if (Test-HasProperty $running 'execution') { $running.PSObject.Properties.Remove('execution') }
    $topic = 'consolidated-' + $CanonicalHash.Substring(0, 12).ToLowerInvariant() + '-' + [string]$running.workUnitId
    $preimages = @($record.targetPreimages)
    $running | Add-Member -NotePropertyName execution -NotePropertyValue ([pscustomobject][ordered]@{
        runId = [string]$record.runId
        owner = 'desktop'
        leaseTopic = $topic
        canonicalMarkdownSha256 = $CanonicalHash
        targetPreimages = $preimages
    })
    return $running
}

function New-EventCheckpointRow {
    param(
        [int64]$Sequence,
        [string]$PreviousEventSha256,
        [string]$ProgramId,
        [string]$CanonicalMarkdownSha256,
        [string]$ActionName,
        [AllowNull()]$UnitId,
        [AllowNull()]$RunHash,
        [string]$Result,
        [string]$StateProjectionSha256,
        [AllowNull()]$EvidenceHash,
        [AllowNull()]$RecordHash
    )

    $semantic = [ordered]@{
        programId = $ProgramId
        canonicalMarkdownSha256 = $CanonicalMarkdownSha256
        action = $ActionName
        workUnitId = $UnitId
        runIdSha256 = $RunHash
        result = $Result
        stateProjectionSha256 = $StateProjectionSha256
        evidenceSha256 = $EvidenceHash
        recordSha256 = $RecordHash
    }
    $eventId = Get-CanonicalJsonHash $semantic 'event-id-input'
    $chain = [ordered]@{
        schemaVersion = 'awx.notebook.directive.event.v2'
        sequence = [int64]$Sequence
        previousEventSha256 = $PreviousEventSha256
        eventId = $eventId
        programId = $ProgramId
        canonicalMarkdownSha256 = $CanonicalMarkdownSha256
        action = $ActionName
        workUnitId = $UnitId
        runIdSha256 = $RunHash
        result = $Result
        stateProjectionSha256 = $StateProjectionSha256
        evidenceSha256 = $EvidenceHash
        recordSha256 = $RecordHash
    }
    $eventSha = Get-CanonicalJsonHash $chain 'event-hash-input'
    return [pscustomobject][ordered]@{
        schemaVersion = 'awx.notebook.directive.event.v2'
        sequence = [int64]$Sequence
        previousEventSha256 = $PreviousEventSha256
        eventId = $eventId
        eventSha256 = $eventSha
        programId = $ProgramId
        canonicalMarkdownSha256 = $CanonicalMarkdownSha256
        action = $ActionName
        workUnitId = $UnitId
        runIdSha256 = $RunHash
        result = $Result
        stateProjectionSha256 = $StateProjectionSha256
        evidenceSha256 = $EvidenceHash
        recordSha256 = $RecordHash
    }
}

function Get-FirstEventRunnableIndex {
    param($StateObject)
    $units = @($StateObject.workUnits)
    for ($index = 0; $index -lt $units.Count; $index++) {
        $unit = $units[$index]
        if ([string]$unit.status -cne 'pending') { continue }
        $blocked = $false
        foreach ($dependencyId in @($unit.dependencies)) {
            $dependency = @($units | Where-Object { [string]$_.workUnitId -ceq [string]$dependencyId })
            if ($dependency.Count -ne 1 -or [string]$dependency[0].status -cnotin @('green','no_patch_needed')) {
                $blocked = $true
                break
            }
        }
        if (-not $blocked) { return $index }
    }
    return -1
}

function Get-ExpectedEventRows {
    param($StateObject, $ManifestResult)

    $baselineState = Get-EventBaselineState $StateObject $ManifestResult
    $projectionHash = Get-CanonicalJsonHash (Get-EventStateProjection $baselineState) 'state-projection'
    $snapshot = New-EventCheckpointRow -Sequence 1 -PreviousEventSha256 ('0' * 64) `
        -ProgramId (Get-EventProgramId $ManifestResult $StateObject) `
        -CanonicalMarkdownSha256 ([string]$StateObject.canonicalMarkdownSha256) `
        -ActionName 'Snapshot' -UnitId $null -RunHash $null -Result 'created' `
        -StateProjectionSha256 $projectionHash -EvidenceHash $null -RecordHash $null
    $rows = @($snapshot)
    $projectionState = Get-EventBaselineState $StateObject $ManifestResult
    $iteration = 0
    while ($iteration -le @($StateObject.workUnits).Count) {
        $iteration++
        $index = Get-FirstEventRunnableIndex $projectionState
        $activeIndexes = @()
        for ($candidateIndex = 0; $candidateIndex -lt @($StateObject.workUnits).Count; $candidateIndex++) {
            $candidateUnit = $StateObject.workUnits[$candidateIndex]
            if ((Test-HasProperty $candidateUnit 'transition') -or (Test-HasProperty $candidateUnit 'execution')) {
                $activeIndexes += ,$candidateIndex
            }
        }
        if ($activeIndexes.Count -gt 1) { throw 'event-checkpoint-invalid' }
        if ($index -lt 0) {
            if ($activeIndexes.Count -ne 0) { throw 'event-checkpoint-invalid' }
            break
        }
        $unit = $StateObject.workUnits[$index]
        $isReplayableTerminal = [string]$unit.status -cin @('green','no_patch_needed','hold','failed') -and
            (Test-HasProperty $unit 'record') -and -not (Test-HasProperty $unit 'execution') -and
            -not (Test-HasProperty $unit 'transition')
        if ($activeIndexes.Count -eq 1 -and [int]$activeIndexes[0] -ne $index -and -not $isReplayableTerminal) {
            throw 'event-checkpoint-invalid'
        }
        $baselineUnit = $ManifestResult.manifest.workUnits[$index]
        $hasTransition = Test-HasProperty $unit 'transition'
        $hasExecution = Test-HasProperty $unit 'execution'
        $hasRecord = Test-HasProperty $unit 'record'
        if ([string]$unit.status -ceq [string]$baselineUnit.status -and -not $hasExecution -and -not $hasRecord) {
            if (-not $hasTransition -or [string]$unit.transition.phase -cin @('begin-intent','begin-recovery-required','begin-cleanup-complete')) { break }
        }
        if ([string]$unit.status -ceq 'running' -and $hasExecution -and -not $hasRecord -and
            (-not $hasTransition -or [string]$unit.transition.phase -ceq 'record-awaiting-release')) {
            $stableRunning = Copy-ControllerValue $unit
            if (Test-HasProperty $stableRunning 'transition') { $stableRunning.PSObject.Properties.Remove('transition') }
            Set-EventProjectionUnit $projectionState $index $stableRunning
            Set-EventDerivedFields $projectionState
            $previous = $rows[$rows.Count - 1]
            $beginRow = New-EventCheckpointRow -Sequence ([int64]($rows.Count + 1)) -PreviousEventSha256 ([string]$previous.eventSha256) `
                -ProgramId (Get-EventProgramId $ManifestResult $StateObject) `
                -CanonicalMarkdownSha256 ([string]$StateObject.canonicalMarkdownSha256) `
                -ActionName 'Begin' -UnitId ([string]$stableRunning.workUnitId) `
                -RunHash (Get-EventRunIdHash ([string]$stableRunning.execution.runId)) -Result 'running' `
                -StateProjectionSha256 (Get-CanonicalJsonHash (Get-EventStateProjection $projectionState) 'state-projection') `
                -EvidenceHash $null -RecordHash $null
            $rows += ,$beginRow
            break
        }
        if ([string]$unit.status -cin @('green','no_patch_needed','hold','failed') -and $hasRecord -and -not $hasExecution -and -not $hasTransition) {
            $runningFromRecord = Get-EventRunningUnitFromRecord $unit ([string]$StateObject.canonicalMarkdownSha256)
            Set-EventProjectionUnit $projectionState $index $runningFromRecord
            Set-EventDerivedFields $projectionState
            $previous = $rows[$rows.Count - 1]
            $beginRow = New-EventCheckpointRow -Sequence ([int64]($rows.Count + 1)) -PreviousEventSha256 ([string]$previous.eventSha256) `
                -ProgramId (Get-EventProgramId $ManifestResult $StateObject) `
                -CanonicalMarkdownSha256 ([string]$StateObject.canonicalMarkdownSha256) `
                -ActionName 'Begin' -UnitId ([string]$unit.workUnitId) `
                -RunHash (Get-EventRunIdHash ([string]$unit.record.runId)) -Result 'running' `
                -StateProjectionSha256 (Get-CanonicalJsonHash (Get-EventStateProjection $projectionState) 'state-projection') `
                -EvidenceHash $null -RecordHash $null
            $rows += ,$beginRow

            Set-EventProjectionUnit $projectionState $index (Copy-ControllerValue $unit)
            Set-EventDerivedFields $projectionState
            $recordRow = New-EventCheckpointRow -Sequence ([int64]($rows.Count + 1)) -PreviousEventSha256 ([string]$beginRow.eventSha256) `
                -ProgramId (Get-EventProgramId $ManifestResult $StateObject) `
                -CanonicalMarkdownSha256 ([string]$StateObject.canonicalMarkdownSha256) `
                -ActionName 'Record' -UnitId ([string]$unit.workUnitId) `
                -RunHash (Get-EventRunIdHash ([string]$unit.record.runId)) `
                -Result (Get-EventResultFromOutcome ([string]$unit.record.outcome)) `
                -StateProjectionSha256 (Get-CanonicalJsonHash (Get-EventStateProjection $projectionState) 'state-projection') `
                -EvidenceHash ([string]$unit.record.evidenceSha256) `
                -RecordHash (Get-CanonicalJsonHash $unit.record 'record')
            $rows += ,$recordRow
            continue
        }
        throw 'event-checkpoint-invalid'
    }
    if ($iteration -gt @($StateObject.workUnits).Count + 1) { throw 'event-checkpoint-invalid' }
    Set-EventDerivedFields $projectionState
    if ((ConvertTo-CanonicalJson -Value (Get-EventStateProjection $projectionState) -SchemaKind 'state-projection') -cne
        (ConvertTo-CanonicalJson -Value (Get-EventStateProjection $StateObject) -SchemaKind 'state-projection')) {
        throw 'event-checkpoint-invalid'
    }
    return $rows
}

function Synchronize-OrValidateEventCheckpoint {
    param($StateObject, $ManifestResult, [switch]$Synchronize)

    $rows = @(Get-ExpectedEventRows $StateObject $ManifestResult)
    if ($rows.Count -eq 0) { throw 'event-checkpoint-invalid' }
    $expected = $rows[$rows.Count - 1]
    if ($Synchronize) {
        $StateObject.eventCheckpoint = Copy-ControllerValue $expected
    } elseif ((ConvertTo-CanonicalJson -Value $StateObject.eventCheckpoint -SchemaKind 'event-row') -cne
        (ConvertTo-CanonicalJson -Value $expected -SchemaKind 'event-row')) {
        throw 'event-checkpoint-invalid'
    }
    return $rows
}

function Get-ValidatedStateObject {
    param($StateObject, $ManifestResult, [switch]$SynchronizeDerived)

    $value = Copy-ControllerValue $StateObject
    Assert-ClosedProperties $value @('schemaVersion', 'canonicalMarkdownPath', 'canonicalMarkdownSha256', 'sourceOwner', 'activeSourceSets', 'controllerManifest', 'directiveInventory', 'workUnits', 'retirement', 'desktopFinalProof', 'runtimeLineageVerdict', 'deleteAuthorized', 'eventCheckpoint') @() 'invalid-state-schema'
    if ($value.schemaVersion -isnot [string] -or $value.schemaVersion -cne $Schema -or
        $value.sourceOwner -isnot [string] -or $value.sourceOwner -cne 'desktop' -or
        $value.activeSourceSets -isnot [Array] -or -not (Test-SameJson @($value.activeSourceSets) @('main/java', 'main/resources', 'app/src/main/java_clean', 'app/src/main/resources')) -or
        $value.desktopFinalProof -isnot [string] -or $value.desktopFinalProof -cnotin @('evidence_needed', 'verified') -or
        $value.runtimeLineageVerdict -isnot [string] -or $value.runtimeLineageVerdict -cne 'HOLD' -or
        $value.deleteAuthorized -isnot [bool] -or $value.deleteAuthorized) { throw 'invalid-state-schema' }
    if ($value.canonicalMarkdownPath -isnot [string] -or $value.canonicalMarkdownSha256 -isnot [string] -or
        [string]$value.canonicalMarkdownSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'invalid-state-schema' }

    $relativeProgram = ConvertTo-ValidatedRelativePath $value.canonicalMarkdownPath 'invalid-canonical-path'
    $programFromState = Get-FullPath $relativeProgram $root
    if (-not $programFromState.Equals($program, [StringComparison]::OrdinalIgnoreCase)) { throw 'canonical-markdown-mismatch' }
    if ([string]$value.canonicalMarkdownSha256 -cne [string]$ManifestResult.hash) { throw 'canonical-hash-changed' }
    Assert-Manifest $ManifestResult.manifest
    if (-not (Test-SameJson $value.controllerManifest $ManifestResult.manifest) -or
        -not (Test-SameJson $value.directiveInventory $ManifestResult.manifest.directiveInventory)) { throw 'frozen-projection-mismatch' }

    $allowFrozenGenericTerminal = -not (Test-HasProperty $ManifestResult.manifest 'programId')
    Assert-WorkUnits $value.workUnits -MutableState -CanonicalMarkdownSha256 ([string]$value.canonicalMarkdownSha256) -AllowFrozenGenericTerminal:$allowFrozenGenericTerminal
    if (@($value.workUnits).Count -ne @($ManifestResult.manifest.workUnits).Count) { throw 'frozen-projection-mismatch' }
    for ($index = 0; $index -lt @($value.workUnits).Count; $index++) {
        $stateUnit = $value.workUnits[$index]
        $manifestUnit = $ManifestResult.manifest.workUnits[$index]
        foreach ($name in @('workUnitId', 'dependencies', 'required', 'kind', 'targetFiles', 'requirementCoverage', 'redCommands', 'greenCommands', 'rollback', 'retirementCoverage', 'causalBoundary', 'approvalGate')) {
            $manifestHas = Test-HasProperty $manifestUnit $name
            $stateHas = Test-HasProperty $stateUnit $name
            if ($manifestHas -ne $stateHas -or ($manifestHas -and -not (Test-SameJson $stateUnit.$name $manifestUnit.$name))) {
                throw 'frozen-projection-mismatch'
            }
        }
        if ((Test-HasProperty $manifestUnit 'approvalGate') -and
            ([string]$stateUnit.status -cne [string]$manifestUnit.status -or
             (Test-HasProperty $stateUnit 'transition') -or (Test-HasProperty $stateUnit 'execution') -or (Test-HasProperty $stateUnit 'record'))) {
            throw 'frozen-projection-mismatch'
        }
        if ($allowFrozenGenericTerminal -and [string]$stateUnit.status -cin @('green','no_patch_needed','hold','failed') -and
            -not (Test-HasProperty $stateUnit 'record')) {
            if ([string]$stateUnit.status -cne [string]$manifestUnit.status -or
                (Test-HasProperty $manifestUnit 'transition') -or (Test-HasProperty $manifestUnit 'execution') -or (Test-HasProperty $manifestUnit 'record')) {
                throw 'frozen-projection-mismatch'
            }
        }
    }

    Assert-RetirementSchema $value.retirement $value.directiveInventory $value.workUnits
    $normalizedRetirement = Copy-ControllerValue $value.retirement
    $normalizedRetirement.allRequiredWorkUnitsGreen = $ManifestResult.manifest.retirement.allRequiredWorkUnitsGreen
    $normalizedRetirement.desktopFinalProof = $ManifestResult.manifest.retirement.desktopFinalProof
    if (-not (Test-SameJson $normalizedRetirement $ManifestResult.manifest.retirement) -or
        [bool]$value.retirement.deleteAuthorized -or [string]$value.retirement.status -cne 'hold' -or
        @($value.retirement.items | Where-Object { $_.eligibility -cne 'hold' -or $_.deletionResult -cne 'not_run' -or $_.status -cne 'hold' }).Count -ne 0) {
        throw 'frozen-retirement-projection-mismatch'
    }

    $derived = Get-DerivedStateProjection $value
    if ([string]$value.desktopFinalProof -cne [string]$derived.desktopFinalProof) { throw 'invalid-state-proof' }
    if ($SynchronizeDerived) {
        $value.retirement.allRequiredWorkUnitsGreen = [bool]$derived.allRequiredWorkUnitsGreen
        $value.retirement.desktopFinalProof = [string]$derived.desktopFinalProof
    } elseif ([bool]$value.retirement.allRequiredWorkUnitsGreen -ne [bool]$derived.allRequiredWorkUnitsGreen -or
        [string]$value.retirement.desktopFinalProof -cne [string]$derived.desktopFinalProof) {
        throw 'invalid-retirement-projection'
    }
    Assert-RetirementSchema $value.retirement $value.directiveInventory $value.workUnits
    $null = Synchronize-OrValidateEventCheckpoint $value $ManifestResult -Synchronize:$SynchronizeDerived
    return $value
}

function Get-ValidatedState {
    $stateFile = Read-BoundedFile $script:state $StateLimit 'state-too-large'
    $value = ConvertFrom-StrictJson $stateFile.text 'malformed-state'
    $manifestResult = Get-ControllerManifest $program
    $validatedValue = Get-ValidatedStateObject $value $manifestResult
    $secondStateRead = Read-BoundedFile $script:state $StateLimit 'state-too-large'
    if ($secondStateRead.hash -cne $stateFile.hash) { throw 'state-hash-changed' }
    return [pscustomobject]@{ state = $validatedValue; hash = $stateFile.hash; manifest = $manifestResult }
}

function Assert-SnapshotRootAuthority {
    param([string]$RootPath)

    if (-not $RootPath.Equals($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'unsupported-root'
    }
    if ([IO.Path]::GetPathRoot($RootPath).StartsWith('\\', [StringComparison]::Ordinal)) {
        throw 'nonlocal-root'
    }
    $driveInfo = New-Object IO.DriveInfo([IO.Path]::GetPathRoot($RootPath))
    if ($driveInfo.DriveType -ne [IO.DriveType]::Fixed) {
        throw 'nonlocal-root'
    }
    $powerShellDrive = Get-PSDrive -Name 'C' -ErrorAction Stop
    if ($powerShellDrive.Provider.Name -cne 'FileSystem' -or
        -not [string]::IsNullOrWhiteSpace([string]$powerShellDrive.DisplayRoot)) {
        throw 'nonlocal-root'
    }
}

function Get-SnapshotLeaseSummary {
    param([string]$RootPath)

    $patchDrop = Join-Path $RootPath '__patch_drop__'
    Assert-NoReparse $RootPath $patchDrop
    if (-not (Test-Path -LiteralPath $patchDrop)) {
        return [pscustomobject]@{
            sourceLeaseActiveCount = 0
            sourceLeaseCorruptCount = 0
            sourceLeaseExpiredCount = 0
            sourceLeaseBlockingCount = 0
        }
    }
    if (-not (Test-Path -LiteralPath $patchDrop -PathType Container)) {
        throw 'patch-drop-invalid'
    }
    Assert-NoReparse $RootPath $patchDrop
    $contractPath = Join-Path $patchDrop 'source_edit_lease_contract.ps1'
    if (-not (Test-Path -LiteralPath $contractPath -PathType Leaf)) {
        throw 'source-lease-contract-missing'
    }
    Assert-NoReparse $RootPath $contractPath
    . $contractPath
    if (-not (Test-Path Function:\Get-AwxSourceEditLeaseSummary)) {
        throw 'source-lease-contract-invalid'
    }
    return Get-AwxSourceEditLeaseSummary -PatchDropDir $patchDrop
}

function Assert-SnapshotGates {
    param(
        [string]$RootPath,
        [string]$ProgramFile,
        [string]$StateFile,
        [AllowNull()][string]$ExpectedProgramHash
    )

    Assert-NoReparse $RootPath $ProgramFile
    Assert-NoReparse $RootPath $StateFile
    try {
        $eventFile = Join-Path (Split-Path $StateFile -Parent) 'events.jsonl'
        Assert-NoReparse $RootPath $eventFile
        Assert-EventLockShape ($eventFile + '.lock')
        if (Test-Path -LiteralPath $eventFile) { throw 'events-without-state' }
    } catch {
        $snapshotGateEventReason = [string]$_.Exception.Message
        if ($snapshotGateEventReason -cin $EventReasonCodes) { throw $snapshotGateEventReason }
        throw 'event-schema-invalid'
    }
    if (Test-Path -LiteralPath $StateFile) { throw 'state-already-exists' }

    $manifestResult = Get-ControllerManifest $ProgramFile
    if (-not [string]::IsNullOrWhiteSpace($ExpectedProgramHash) -and
        $manifestResult.hash -cne $ExpectedProgramHash) {
        throw 'canonical-hash-changed'
    }
    Assert-Manifest $manifestResult.manifest

    $indexLock = Join-Path $RootPath '.git\index.lock'
    Assert-NoReparse $RootPath $indexLock
    if (Test-Path -LiteralPath $indexLock) { throw 'index-lock-present' }

    $patchDrop = Join-Path $RootPath '__patch_drop__'
    Assert-NoReparse $RootPath $patchDrop
    if (Test-Path -LiteralPath $patchDrop) {
        if (-not (Test-Path -LiteralPath $patchDrop -PathType Container)) { throw 'patch-drop-invalid' }
        if (@(Get-ChildItem -LiteralPath $patchDrop -File -Filter '*.patch' -Force -ErrorAction Stop).Count -gt 0) {
            throw 'patch-drop-pending'
        }
    }
    $leaseSummary = Get-SnapshotLeaseSummary $RootPath
    if ([int]$leaseSummary.sourceLeaseBlockingCount -gt 0) { throw 'source-lease-present' }
    return $manifestResult
}

function Assert-StateCasLockShape {
    param([string]$RootPath, [string]$LockPath)

    $parent = Split-Path $LockPath -Parent
    Assert-NoReparse $RootPath $parent
    Assert-NoReparse $RootPath $LockPath
    if (Test-Path -LiteralPath $LockPath) {
        if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) { throw 'state-cas-lock-invalid' }
        $item = Get-Item -LiteralPath $LockPath -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or [int64]$item.Length -ne 0) { throw 'state-cas-lock-invalid' }
    }
}

function Enter-StateCasLock {
    param([string]$RootPath, [string]$DestinationPath)

    if ($null -ne $script:eventLockStream) { throw 'state-cas-lock-invalid' }
    $lockPath = [IO.Path]::GetFullPath($DestinationPath + '.cas.lock')
    if ($null -ne $script:stateCasLockStream) {
        if ([string]$script:stateCasLockPath -cne $lockPath) { throw 'state-cas-lock-invalid' }
        return $script:stateCasLockStream
    }

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while ($true) {
            Assert-StateCasLockShape $RootPath $lockPath
            $stream = $null
            try {
                $stream = [IO.FileStream]::new(
                    $lockPath,
                    [IO.FileMode]::OpenOrCreate,
                    [IO.FileAccess]::ReadWrite,
                    [IO.FileShare]::None,
                    4096,
                    [IO.FileOptions]::None
                )
                Assert-StateCasLockShape $RootPath $lockPath
                if ([int64]$stream.Length -ne 0) { throw 'state-cas-lock-invalid' }
                $script:stateCasLockStream = $stream
                $script:stateCasLockPath = $lockPath
                return $stream
            } catch [IO.IOException] {
                if ($null -ne $stream) { $stream.Dispose() }
                $nativeCode = $_.Exception.HResult -band 0xFFFF
                if ($nativeCode -notin @(32, 33)) { throw 'state-cas-lock-failed' }
                if ($stopwatch.ElapsedMilliseconds -ge 5000) { throw 'state-cas-lock-timeout' }
                Start-Sleep -Milliseconds 25
            } catch {
                if ($null -ne $stream) { $stream.Dispose() }
                if ([string]$_.Exception.Message -match '^[a-z0-9-]+$') { throw }
                throw 'state-cas-lock-failed'
            }
        }
    } finally { $stopwatch.Stop() }
}

function Exit-StateCasLock {
    if ($null -ne $script:stateCasLockStream) {
        try { $script:stateCasLockStream.Dispose() } finally {
            $script:stateCasLockStream = $null
            $script:stateCasLockPath = $null
        }
    }
}

function Get-EventPath { return Join-Path (Split-Path $state -Parent) 'events.jsonl' }

function Assert-EventLockShape {
    param([string]$LockPath)

    $parent = Split-Path $LockPath -Parent
    try {
        Assert-NoReparse $root $parent
        Assert-NoReparse $root $LockPath
    } catch { throw 'event-lock-invalid' }
    if (Test-Path -LiteralPath $LockPath) {
        if (-not (Test-Path -LiteralPath $LockPath -PathType Leaf)) { throw 'event-lock-invalid' }
        $item = Get-Item -LiteralPath $LockPath -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or [int64]$item.Length -ne 0) { throw 'event-lock-invalid' }
    }
}

function Enter-EventLock {
    if ($null -ne $script:stateCasLockStream) { throw 'event-lock-invalid' }
    $lockPath = [IO.Path]::GetFullPath((Get-EventPath) + '.lock')
    if ($null -ne $script:eventLockStream) {
        if ([string]$script:eventLockPath -cne $lockPath) { throw 'event-lock-invalid' }
        return $script:eventLockStream
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while ($true) {
            Assert-EventLockShape $lockPath
            $stream = $null
            try {
                $stream = [IO.FileStream]::new($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
                Assert-EventLockShape $lockPath
                if ([int64]$stream.Length -ne 0) { throw 'event-lock-invalid' }
                $script:eventLockStream = $stream
                $script:eventLockPath = $lockPath
                return $stream
            } catch [IO.IOException] {
                if ($null -ne $stream) { $stream.Dispose() }
                $nativeCode = $_.Exception.HResult -band 0xFFFF
                if ($nativeCode -notin @(32, 33)) { throw 'event-lock-failed' }
                if ($watch.ElapsedMilliseconds -ge 5000) { throw 'event-lock-timeout' }
                Start-Sleep -Milliseconds 25
            } catch {
                if ($null -ne $stream) { $stream.Dispose() }
                if ([string]$_.Exception.Message -match '^event-[a-z0-9-]+$') { throw }
                throw 'event-lock-failed'
            }
        }
    } finally { $watch.Stop() }
}

function Exit-EventLock {
    if ($null -ne $script:eventLockStream) {
        try { $script:eventLockStream.Dispose() } finally {
            $script:eventLockStream = $null
            $script:eventLockPath = $null
        }
    }
}

function Enter-AdoptEventLock {
    if ($null -eq $script:stateCasLockStream -or $null -ne $script:eventLockStream) { throw 'event-lock-invalid' }
    $lockPath = [IO.Path]::GetFullPath((Get-EventPath) + '.lock')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while ($true) {
            Assert-EventLockShape $lockPath
            $stream = $null
            try {
                $stream = [IO.FileStream]::new($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
                Assert-EventLockShape $lockPath
                if ([int64]$stream.Length -ne 0) { throw 'event-lock-invalid' }
                $script:eventLockStream = $stream
                $script:eventLockPath = $lockPath
                return $stream
            } catch [IO.IOException] {
                if ($null -ne $stream) { $stream.Dispose() }
                $nativeCode = $_.Exception.HResult -band 0xFFFF
                if ($nativeCode -notin @(32, 33)) { throw 'event-lock-failed' }
                if ($watch.ElapsedMilliseconds -ge 5000) { throw 'event-lock-timeout' }
                Start-Sleep -Milliseconds 25
            } catch {
                if ($null -ne $stream) { $stream.Dispose() }
                if ([string]$_.Exception.Message -match '^event-[a-z0-9-]+$') { throw }
                throw 'event-lock-failed'
            }
        }
    } finally { $watch.Stop() }
}

function Get-AdoptEventBundleContext {
    param($CurrentState, $ProposedState, $ManifestResult)

    $currentRows = @(Get-ExpectedEventRows $CurrentState $ManifestResult)
    $proposedRows = @(Get-ExpectedEventRows $ProposedState $ManifestResult)
    if ($proposedRows.Count -ne $currentRows.Count + 2) { throw 'event-checkpoint-invalid' }
    for ($index = 0; $index -lt $currentRows.Count; $index++) {
        if ((ConvertTo-CanonicalJson -Value $currentRows[$index] -SchemaKind 'event-row') -cne
            (ConvertTo-CanonicalJson -Value $proposedRows[$index] -SchemaKind 'event-row')) { throw 'event-checkpoint-invalid' }
    }
    $beginRow = $proposedRows[$currentRows.Count]
    $recordRow = $proposedRows[$currentRows.Count + 1]
    if ([string]$beginRow.action -cne 'Begin' -or [string]$beginRow.result -cne 'running' -or
        [string]$recordRow.action -cne 'Record' -or [string]$recordRow.result -cne 'no_patch_needed' -or
        [string]$beginRow.workUnitId -cne [string]$recordRow.workUnitId -or
        [string]$beginRow.runIdSha256 -cne [string]$recordRow.runIdSha256) { throw 'event-checkpoint-invalid' }
    $parts = @((Get-CanonicalEventRowBytes $beginRow), (Get-CanonicalEventRowBytes $recordRow))
    $length = [int64]0
    foreach ($part in $parts) { $length += [int64]$part.Length }
    $appendBytes = [Array]::CreateInstance([byte], [int]$length)
    $offset = 0
    foreach ($part in $parts) {
        [Array]::Copy($part, 0, $appendBytes, $offset, $part.Length)
        $offset += $part.Length
    }
    return [pscustomobject]@{ currentRows=@($currentRows); proposedRows=@($proposedRows); appendBytes=[byte[]]$appendBytes }
}

function Assert-AdoptPublicationGates {
    param($ExpectedTargetRows, [string]$Topic, [string]$OwnerId)

    $targetPaths = @($ExpectedTargetRows | ForEach-Object { [string]$_.path })
    $snapshot = Get-PublicCoreTargetSnapshot ([pscustomobject][ordered]@{ root=$CanonicalRoot; paths=$targetPaths; mode='postimage' })
    if (-not (Test-SameJson @($snapshot.rows) @($ExpectedTargetRows))) { throw 'adopt-target-postimage-mismatch' }
    $absence = Get-PublicCoreLeaseAbsenceEnvelope ([pscustomobject][ordered]@{
        action='end'; role='desktop'; root=$CanonicalRoot; topic=$Topic; ownerId=$OwnerId; ttlMinutes=180
    })
    if (-not [bool]$absence.isAbsent) { throw 'adopt-lease-active' }
    $leaseSummary = Get-SnapshotLeaseSummary $CanonicalRoot
    if ([int]$leaseSummary.sourceLeaseBlockingCount -gt 0) { throw 'adopt-lease-active' }
}

function Assert-AdoptEventBundlePreflight {
    param($CurrentState, $ProposedState, $ManifestResult)

    $bundle = Get-AdoptEventBundleContext $CurrentState $ProposedState $ManifestResult
    $null = Enter-AdoptEventLock
    try {
        $ledger = Read-ValidatedEventLedger $bundle.currentRows @($CurrentState.workUnits).Count
        if (@($ledger.rows).Count -ne @($bundle.currentRows).Count) { throw 'event-ledger-diverged' }
        if ([int64]$ledger.bytes.Length + [int64]$bundle.appendBytes.Length -gt 256KB) { throw 'event-ledger-too-large' }
        if (@($bundle.proposedRows).Count -gt 1 + 2 * @($ProposedState.workUnits).Count) { throw 'event-row-count-exceeded' }
    } finally { Exit-EventLock }
}

function Publish-AdoptEventBundle {
    param($CurrentState, $ProposedState, $ManifestResult, $ExpectedTargetRows, [string]$Topic, [string]$OwnerId)

    $bundle = Get-AdoptEventBundleContext $CurrentState $ProposedState $ManifestResult
    $null = Enter-AdoptEventLock
    $temporary = $null
    $backup = $null
    $replacementCompleted = $false
    try {
        $ledger = Read-ValidatedEventLedger $bundle.currentRows @($CurrentState.workUnits).Count
        if (@($ledger.rows).Count -ne @($bundle.currentRows).Count) { throw 'event-ledger-diverged' }
        $bundleBytes = [Array]::CreateInstance([byte], [int]([int64]$ledger.bytes.Length + [int64]$bundle.appendBytes.Length))
        if ($ledger.bytes.Length -gt 0) { [Array]::Copy($ledger.bytes, 0, $bundleBytes, 0, $ledger.bytes.Length) }
        [Array]::Copy($bundle.appendBytes, 0, $bundleBytes, $ledger.bytes.Length, $bundle.appendBytes.Length)
        if ($bundleBytes.Length -gt 256KB) { throw 'event-ledger-too-large' }

        $path = Get-EventPath
        $directory = Split-Path $path -Parent
        $temporary = Join-Path $directory ('.adopt-events-' + [guid]::NewGuid().ToString('N') + '.tmp')
        $stream = [IO.FileStream]::new($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None, 4096, [IO.FileOptions]::WriteThrough)
        try {
                $stream.Write($bundleBytes, 0, $bundleBytes.Length)
            $stream.Flush($true)
        } finally { $stream.Dispose() }
        Assert-NoReparse $root $temporary
        if ((Get-FileHashBounded $temporary 256KB 'event-ledger-too-large') -cne (Get-Sha256 $bundleBytes)) { throw 'event-publication-pending' }
        Assert-AdoptPublicationGates $ExpectedTargetRows $Topic $OwnerId
        if ((Get-FileHashBounded $path 256KB 'event-ledger-too-large') -cne [string]$ledger.hash) { throw 'event-ledger-diverged' }
        $backup = $path + '.adopt-backup-' + [guid]::NewGuid().ToString('N')
        [IO.File]::Replace($temporary, $path, $backup, $true)
        $temporary = $null
        $replacementCompleted = $true
        $published = Read-ValidatedEventLedger $bundle.proposedRows @($ProposedState.workUnits).Count
        if (@($published.rows).Count -ne @($bundle.proposedRows).Count -or [string]$published.hash -cne (Get-Sha256 $bundleBytes)) {
            throw 'event-publication-pending'
        }
        Assert-AdoptPublicationGates $ExpectedTargetRows $Topic $OwnerId
        if (Test-Path -LiteralPath $backup) { [IO.File]::Delete($backup) }
        if (Test-Path -LiteralPath $backup) { throw 'event-rollback-unproven' }
        return [pscustomobject]@{
            oldBytes=[byte[]]$ledger.bytes; currentRows=@($bundle.currentRows); proposedRows=@($bundle.proposedRows)
            workUnitCount=[int]@($CurrentState.workUnits).Count
        }
    } catch {
        $failureReason = [string]$_.Exception.Message
        $rollbackProved = -not $replacementCompleted
        if ($replacementCompleted) {
            $failed = (Get-EventPath) + '.adopt-failed-' + [guid]::NewGuid().ToString('N')
            try {
                if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) { throw 'event-rollback-unproven' }
                [IO.File]::Replace($backup, (Get-EventPath), $failed, $true)
                $restored = Read-ValidatedEventLedger $bundle.currentRows @($CurrentState.workUnits).Count
                $rollbackProved = [string]$restored.hash -ceq [string]$ledger.hash -and @($restored.rows).Count -eq @($bundle.currentRows).Count
                if ($rollbackProved -and (Test-Path -LiteralPath $failed)) { [IO.File]::Delete($failed) }
            } catch { $rollbackProved = $false }
        } else {
            try {
                $unchanged = Read-ValidatedEventLedger $bundle.currentRows @($CurrentState.workUnits).Count
                $rollbackProved = [string]$unchanged.hash -ceq [string]$ledger.hash -and @($unchanged.rows).Count -eq @($bundle.currentRows).Count
            } catch { $rollbackProved = $false }
        }
        if (-not $rollbackProved) { throw 'event-rollback-unproven' }
        if ($failureReason -cnotin @($EventReasonCodes + @('adopt-target-postimage-mismatch','adopt-lease-active'))) {
            $failureReason = 'event-publication-pending'
        }
        throw $failureReason
    } finally {
        if ($null -ne $temporary -and (Test-Path -LiteralPath $temporary)) { [IO.File]::Delete($temporary) }
        Exit-EventLock
    }
}

function Restore-AdoptEventBundle {
    param($Context)
    if ($null -eq $Context) { return }
    $null = Enter-AdoptEventLock
    $temporary = $null
    $backup = $null
    try {
        $path = Get-EventPath
        $current = Read-ValidatedEventLedger $Context.proposedRows $Context.workUnitCount
        if (@($current.rows).Count -ne @($Context.proposedRows).Count) { throw 'event-rollback-unproven' }
        $temporary = Join-Path (Split-Path $path -Parent) ('.adopt-events-restore-' + [guid]::NewGuid().ToString('N') + '.tmp')
        [IO.File]::WriteAllBytes($temporary, [byte[]]$Context.oldBytes)
        $backup = $path + '.adopt-restore-backup-' + [guid]::NewGuid().ToString('N')
        [IO.File]::Replace($temporary, $path, $backup, $true)
        $temporary = $null
        $restored = Read-ValidatedEventLedger $Context.currentRows $Context.workUnitCount
        if (@($restored.rows).Count -ne @($Context.currentRows).Count -or [string]$restored.hash -cne (Get-Sha256 ([byte[]]$Context.oldBytes))) {
            throw 'event-rollback-unproven'
        }
        if (Test-Path -LiteralPath $backup) { [IO.File]::Delete($backup) }
        if (Test-Path -LiteralPath $backup) { throw 'event-rollback-unproven' }
    } catch { throw 'event-rollback-unproven' }
    finally {
        if ($null -ne $temporary -and (Test-Path -LiteralPath $temporary)) { [IO.File]::Delete($temporary) }
        Exit-EventLock
    }
}

function Get-CanonicalEventRowBytes {
    param($Row)
    $bytes = $Utf8.GetBytes((ConvertTo-CanonicalJson -Value $Row -SchemaKind 'event-row') + "`n")
    if ($bytes.Length -gt 4096) { throw 'event-row-too-large' }
    return $bytes
}

function Read-ValidatedEventLedger {
    param($ExpectedRows, [int]$WorkUnitCount)

    if ($WorkUnitCount -lt 0 -or $WorkUnitCount -gt $WorkUnitLimit) { throw 'event-checkpoint-invalid' }

    $validationStream = $null
    try {
        $path = Get-EventPath
        Assert-NoReparse $root $path
        if (-not (Test-Path -LiteralPath $path)) {
            return [pscustomobject]@{ rows=@(); bytes=[byte[]]@(); hash=$null; identity=$null; linkCount=0 }
        }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'event-schema-invalid' }
        $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
        if ([int64]$item.Length -gt 256KB) { throw 'event-ledger-too-large' }
        $native = Get-RetirementNativeType
        $validationStream = [IO.FileStream]::new(
            $path,
            [IO.FileMode]::Open,
            [IO.FileAccess]::Read,
            ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete),
            4096,
            [IO.FileOptions]::SequentialScan
        )
        $identity = $native::Identity($validationStream.SafeFileHandle)
        $linkCount = [uint32]$native::LinkCount($validationStream.SafeFileHandle)
        if ($linkCount -ne 1) { throw 'event-schema-invalid' }
        $validatedLength = [int64]$validationStream.Length
        if ($validatedLength -gt 256KB) { throw 'event-ledger-too-large' }
        $bytes = Read-ExactEventStreamPrefix $validationStream $validatedLength
        if ([int64]$validationStream.Length -ne $validatedLength) { throw 'event-ledger-diverged' }
        if ([string]$native::Identity($validationStream.SafeFileHandle) -cne [string]$identity) { throw 'event-ledger-diverged' }
        if ([uint32]$native::LinkCount($validationStream.SafeFileHandle) -ne 1) { throw 'event-schema-invalid' }
    } catch {
        $validationReason = [string]$_.Exception.Message
        if ($validationReason -cin $EventReasonCodes) { throw $validationReason }
        throw 'event-schema-invalid'
    } finally {
        if ($null -ne $validationStream) { $validationStream.Dispose() }
    }
    if ($bytes.Length -gt 256KB) { throw 'event-ledger-too-large' }
    if ($bytes.Length -eq 0) { return [pscustomobject]@{ rows=@(); bytes=$bytes; hash=(Get-Sha256 $bytes); identity=$identity; linkCount=$linkCount } }
    if (($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) -or
        $bytes[$bytes.Length - 1] -ne 0x0A -or @($bytes | Where-Object { $_ -eq 0x0D }).Count -gt 0) {
        throw 'event-ledger-diverged'
    }
    try { $text = $Utf8.GetString($bytes) } catch { throw 'event-ledger-diverged' }
    $lines = @($text.Substring(0, $text.Length - 1).Split([char]0x0A))
    if ($lines.Count -gt 1 + 2 * $WorkUnitCount) { throw 'event-row-count-exceeded' }
    $rows = @()
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ([string]::IsNullOrEmpty($lines[$index])) { throw 'event-ledger-diverged' }
        try { $row = ConvertFrom-StrictJson $lines[$index] 'event-ledger-diverged' } catch { throw 'event-ledger-diverged' }
        try { $canonicalRow = ConvertTo-CanonicalJson -Value $row -SchemaKind 'event-row' } catch { throw 'event-ledger-diverged' }
        if ($canonicalRow -cne $lines[$index]) { throw 'event-ledger-diverged' }
        if ($index -ge @($ExpectedRows).Count -or $lines[$index] -cne
            (ConvertTo-CanonicalJson -Value $ExpectedRows[$index] -SchemaKind 'event-row')) { throw 'event-ledger-diverged' }
        $rows += ,$row
    }
    return [pscustomobject]@{ rows=@($rows); bytes=$bytes; hash=(Get-Sha256 $bytes); identity=$identity; linkCount=$linkCount }
}

function Read-ExactEventStreamPrefix {
    param([IO.FileStream]$Stream, [int64]$Length)
    if ($Length -lt 0 -or $Length -gt 256KB) { throw 'event-rollback-unproven' }
    $bytes = [Array]::CreateInstance([byte], [int]$Length)
    $null = $Stream.Seek(0, [IO.SeekOrigin]::Begin)
    $offset = 0
    while ($offset -lt $bytes.Length) {
        $read = $Stream.Read($bytes, $offset, $bytes.Length - $offset)
        if ($read -le 0) { throw 'event-rollback-unproven' }
        $offset += $read
    }
    return ,([byte[]]$bytes)
}

function Sync-EventLedger {
    $null = Enter-EventLock
    try {
        $validated = Get-ValidatedState
        $expectedRows = @(Get-ExpectedEventRows $validated.state $validated.manifest)
        $checkpoint = $expectedRows[$expectedRows.Count - 1]
        $ledger = Read-ValidatedEventLedger $expectedRows @($validated.state.workUnits).Count
        $existingCount = @($ledger.rows).Count
        if ($existingCount -eq $expectedRows.Count) {
            if ((ConvertTo-CanonicalJson -Value $ledger.rows[$existingCount - 1] -SchemaKind 'event-row') -cne
                (ConvertTo-CanonicalJson -Value $checkpoint -SchemaKind 'event-row')) { throw 'event-ledger-diverged' }
            return $validated
        }
        if ($existingCount -ne $expectedRows.Count - 1) { throw 'event-ledger-diverged' }
        if ($existingCount -gt 0 -and [string]$ledger.rows[$existingCount - 1].eventSha256 -cne [string]$checkpoint.previousEventSha256) {
            throw 'event-ledger-diverged'
        }
        if ($existingCount -eq 0 -and ([int64]$checkpoint.sequence -ne 1 -or [string]$checkpoint.previousEventSha256 -cne ('0' * 64))) {
            throw 'event-ledger-diverged'
        }
        $rowBytes = Get-CanonicalEventRowBytes $checkpoint
        if ([int64]$ledger.bytes.Length + $rowBytes.Length -gt 256KB) { throw 'event-ledger-too-large' }
        $path = Get-EventPath
        $pathExisted = Test-Path -LiteralPath $path
        $mode = if ($pathExisted) { [IO.FileMode]::Open } else { [IO.FileMode]::CreateNew }
        $appendFailure = $null
        $removeCreatedFile = $false
        $stream = [IO.FileStream]::new($path, $mode, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None, 4096, [IO.FileOptions]::WriteThrough)
        try {
            Assert-NoReparse $root $path
            try { $native = Get-RetirementNativeType } catch { throw 'event-schema-invalid' }
            $openedIdentity = $native::Identity($stream.SafeFileHandle)
            if ([uint32]$native::LinkCount($stream.SafeFileHandle) -ne 1) { throw 'event-schema-invalid' }
            if ($pathExisted -and ([string]::IsNullOrWhiteSpace([string]$ledger.identity) -or [string]$ledger.identity -cne [string]$openedIdentity)) {
                throw 'event-ledger-diverged'
            }
            if ([int64]$stream.Length -ne [int64]$ledger.bytes.Length) { throw 'event-ledger-diverged' }
            $lockedPrefix = Read-ExactEventStreamPrefix $stream ([int64]$ledger.bytes.Length)
            $validatedPrefixHash = Get-Sha256 ([byte[]]$ledger.bytes)
            if ($null -ne $ledger.hash -and [string]$ledger.hash -cne $validatedPrefixHash) { throw 'event-ledger-diverged' }
            if ((Get-Sha256 $lockedPrefix) -cne $validatedPrefixHash) { throw 'event-ledger-diverged' }
            for ($prefixIndex = 0; $prefixIndex -lt $lockedPrefix.Length; $prefixIndex++) {
                if ($lockedPrefix[$prefixIndex] -ne $ledger.bytes[$prefixIndex]) { throw 'event-ledger-diverged' }
            }
            Assert-NoReparse $root $path
            if ([string]$native::Identity($stream.SafeFileHandle) -cne [string]$openedIdentity) { throw 'event-ledger-diverged' }
            if ([uint32]$native::LinkCount($stream.SafeFileHandle) -ne 1) { throw 'event-schema-invalid' }
            $null = $stream.Seek(0, [IO.SeekOrigin]::End)
            try {
                $stream.Write($rowBytes, 0, $rowBytes.Length)
                $stream.Flush($true)
            } catch {
                try {
                    $oldLength = [int64]$ledger.bytes.Length
                    $currentLength = [int64]$stream.Length
                    if ($currentLength -lt $oldLength -or $currentLength -gt $oldLength + [int64]$rowBytes.Length) {
                        throw 'event-rollback-unproven'
                    }
                    $expectedOldHash = Get-Sha256 ([byte[]]$ledger.bytes)
                    $prefix = Read-ExactEventStreamPrefix $stream $oldLength
                    if ((Get-Sha256 $prefix) -cne $expectedOldHash) { throw 'event-rollback-unproven' }
                    $suffixLength = [int]($currentLength - $oldLength)
                    if ($suffixLength -gt $rowBytes.Length) { throw 'event-rollback-unproven' }
                    if ($suffixLength -gt 0) {
                        $actualSuffix = [Array]::CreateInstance([byte], $suffixLength)
                        $null = $stream.Seek($oldLength, [IO.SeekOrigin]::Begin)
                        $suffixOffset = 0
                        while ($suffixOffset -lt $actualSuffix.Length) {
                            $suffixRead = $stream.Read($actualSuffix, $suffixOffset, $actualSuffix.Length - $suffixOffset)
                            if ($suffixRead -le 0) { throw 'event-rollback-unproven' }
                            $suffixOffset += $suffixRead
                        }
                        for ($suffixIndex = 0; $suffixIndex -lt $actualSuffix.Length; $suffixIndex++) {
                            if ($actualSuffix[$suffixIndex] -ne $rowBytes[$suffixIndex]) { throw 'event-rollback-unproven' }
                        }
                    }
                    Assert-NoReparse $root $path
                    if ([string]$native::Identity($stream.SafeFileHandle) -cne [string]$openedIdentity -or
                        [uint32]$native::LinkCount($stream.SafeFileHandle) -ne 1) { throw 'event-rollback-unproven' }
                    $stream.SetLength($oldLength)
                    $stream.Flush($true)
                    if ([int64]$stream.Length -ne $oldLength) { throw 'event-rollback-unproven' }
                    $restoredPrefix = Read-ExactEventStreamPrefix $stream $oldLength
                    if ((Get-Sha256 $restoredPrefix) -cne $expectedOldHash) { throw 'event-rollback-unproven' }
                    if ([string]$native::Identity($stream.SafeFileHandle) -cne [string]$openedIdentity -or
                        [uint32]$native::LinkCount($stream.SafeFileHandle) -ne 1) { throw 'event-rollback-unproven' }
                    $removeCreatedFile = -not $pathExisted
                    $appendFailure = 'event-publication-pending'
                } catch {
                    $appendFailure = 'event-rollback-unproven'
                }
            }
        } finally { $stream.Dispose() }
        if ($null -ne $appendFailure) {
            if ($removeCreatedFile) {
                $deleteHandle = $null
                try {
                    Assert-NoReparse $root $path
                    if ([int64]$ledger.bytes.Length -ne 0 -or
                        (Get-Sha256 ([byte[]]$ledger.bytes)) -cne (Get-Sha256 ([byte[]]@()))) { throw 'event-rollback-unproven' }
                    $deleteHandle = $native::Open($path)
                    Assert-NoReparse $root $path
                    if ([string]$native::Identity($deleteHandle) -cne [string]$openedIdentity -or
                        [uint32]$native::LinkCount($deleteHandle) -ne 1 -or [int64]$native::Length($deleteHandle) -ne 0) {
                        throw 'event-rollback-unproven'
                    }
                    $native::SetDelete($deleteHandle, $true)
                } catch {
                    throw 'event-rollback-unproven'
                } finally {
                    if ($null -ne $deleteHandle) { $deleteHandle.Dispose() }
                }
                try {
                    if (Test-Path -LiteralPath $path) { throw 'event-rollback-unproven' }
                } catch { throw 'event-rollback-unproven' }
            }
            throw $appendFailure
        }
        $published = Read-ValidatedEventLedger $expectedRows @($validated.state.workUnits).Count
        if (@($published.rows).Count -ne $expectedRows.Count -or
            (ConvertTo-CanonicalJson -Value $published.rows[$published.rows.Count - 1] -SchemaKind 'event-row') -cne
            (ConvertTo-CanonicalJson -Value $checkpoint -SchemaKind 'event-row')) { throw 'event-publication-pending' }
        return $validated
    } catch {
        $syncReason = [string]$_.Exception.Message
        if ($syncReason -cin $EventReasonCodes) { throw $syncReason }
        throw 'event-lock-failed'
    } finally { Exit-EventLock }
}

function Get-ReadOnlyEventSnapshot {
    $validated = Get-ValidatedState
    $expectedRows = @(Get-ExpectedEventRows $validated.state $validated.manifest)
    if ($expectedRows.Count -eq 0) { throw 'event-checkpoint-invalid' }
    $ledger = Read-ValidatedEventLedger $expectedRows @($validated.state.workUnits).Count
    $actualCount = @($ledger.rows).Count
    $relation = if ($actualCount -eq $expectedRows.Count) {
        'converged'
    } elseif ($actualCount -eq $expectedRows.Count - 1) {
        'one-ahead'
    } else {
        'diverged'
    }
    return [pscustomobject]@{
        validated = $validated
        stateHash = [string]$validated.hash
        ledgerHash = if ($null -eq $ledger.hash) { $null } else { [string]$ledger.hash }
        expectedCount = [int]$expectedRows.Count
        actualCount = [int]$actualCount
        relation = $relation
    }
}

function Assert-ReadOnlyEventRelation {
    param($Snapshot)
    if ([string]$Snapshot.relation -ceq 'converged') { return }
    if ([string]$Snapshot.relation -ceq 'one-ahead') { throw 'event-publication-pending' }
    throw 'event-ledger-diverged'
}

function Enter-ExistingEventReadLock {
    if ($null -ne $script:stateCasLockStream) { throw 'event-lock-invalid' }
    $lockPath = [IO.Path]::GetFullPath((Get-EventPath) + '.lock')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while ($true) {
            Assert-EventLockShape $lockPath
            if (-not (Test-Path -LiteralPath $lockPath)) { throw 'event-publication-pending' }
            $stream = $null
            try {
                $stream = [IO.FileStream]::new($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
                Assert-EventLockShape $lockPath
                if ([int64]$stream.Length -ne 0) { throw 'event-lock-invalid' }
                return $stream
            } catch [IO.IOException] {
                if ($null -ne $stream) { $stream.Dispose() }
                $nativeCode = $_.Exception.HResult -band 0xFFFF
                if ($nativeCode -in @(2, 3)) { throw 'event-publication-pending' }
                if ($nativeCode -notin @(32, 33)) { throw 'event-lock-failed' }
                if ($watch.ElapsedMilliseconds -ge 5000) { throw 'event-lock-timeout' }
                Start-Sleep -Milliseconds 25
            } catch {
                if ($null -ne $stream) { $stream.Dispose() }
                if ([string]$_.Exception.Message -match '^event-[a-z0-9-]+$') { throw }
                throw 'event-lock-failed'
            }
        }
    } finally { $watch.Stop() }
}

function Get-ValidatedReadOnlyEventState {
    $lockPath = [IO.Path]::GetFullPath((Get-EventPath) + '.lock')
    Assert-EventLockShape $lockPath
    if (Test-Path -LiteralPath $lockPath) {
        $readLock = Enter-ExistingEventReadLock
        try {
            $snapshot = Get-ReadOnlyEventSnapshot
            Assert-ReadOnlyEventRelation $snapshot
            return $snapshot.validated
        } finally { $readLock.Dispose() }
    }

    $first = Get-ReadOnlyEventSnapshot
    if (Test-Path -LiteralPath $lockPath) { throw 'event-publication-pending' }
    $second = Get-ReadOnlyEventSnapshot
    if (Test-Path -LiteralPath $lockPath) { throw 'event-publication-pending' }
    if ([string]$first.stateHash -cne [string]$second.stateHash -or
        [string]$first.ledgerHash -cne [string]$second.ledgerHash -or
        [int]$first.expectedCount -ne [int]$second.expectedCount -or
        [int]$first.actualCount -ne [int]$second.actualCount -or
        [string]$first.relation -cne [string]$second.relation) {
        throw 'event-publication-pending'
    }
    Assert-ReadOnlyEventRelation $second
    return $second.validated
}

function Assert-SnapshotEventPreflight {
    param($StateObject, $ManifestResult)
    $rows = @(Get-ExpectedEventRows $StateObject $ManifestResult)
    if ($rows.Count -ne 1) { throw 'event-checkpoint-invalid' }
    $null = Get-CanonicalEventRowBytes $rows[0]
    try {
        $eventPath = Get-EventPath
        Assert-NoReparse $root $eventPath
        if (Test-Path -LiteralPath $eventPath) { throw 'events-without-state' }
        Assert-EventLockShape ($eventPath + '.lock')
    } catch {
        $preflightReason = [string]$_.Exception.Message
        if ($preflightReason -cin $EventReasonCodes) { throw $preflightReason }
        throw 'event-schema-invalid'
    }
}

function Get-EventTerminalStateFromAwaiting {
    param($AwaitingState)

    $terminalState = Copy-ControllerValue $AwaitingState
    $awaitingUnits = @($terminalState.workUnits | Where-Object {
        (Test-HasProperty $_ 'transition') -and [string]$_.transition.phase -ceq 'record-awaiting-release'
    })
    if ($awaitingUnits.Count -ne 1) { throw 'event-checkpoint-invalid' }
    $unit = $awaitingUnits[0]
    $unit.status = [string]$unit.transition.terminalStatus
    $record = Copy-ControllerValue $unit.transition.record
    if (Test-HasProperty $unit 'record') { $unit.record = $record }
    else { $unit | Add-Member -NotePropertyName record -NotePropertyValue $record }
    if (Test-HasProperty $unit 'execution') { $unit.PSObject.Properties.Remove('execution') }
    $unit.PSObject.Properties.Remove('transition')
    Set-EventDerivedFields $terminalState
    return $terminalState
}

function Assert-ProspectiveEventPreflight {
    param($CurrentState, $ProposedState, $ManifestResult, [string]$Phase)

    $currentRows = @(Get-ExpectedEventRows $CurrentState $ManifestResult)
    $proposedVisibleRows = @(Get-ExpectedEventRows $ProposedState $ManifestResult)
    $isSameVisiblePrefix = $proposedVisibleRows.Count -eq $currentRows.Count
    if ($isSameVisiblePrefix) {
        for ($index = 0; $index -lt $currentRows.Count; $index++) {
            if ((ConvertTo-CanonicalJson -Value $proposedVisibleRows[$index] -SchemaKind 'event-row') -cne
                (ConvertTo-CanonicalJson -Value $currentRows[$index] -SchemaKind 'event-row')) {
                $isSameVisiblePrefix = $false
                break
            }
        }
    }
    if ($Phase -cin @('begin-intent','begin-cleanup-complete','begin-recovery-required')) {
        if (-not $isSameVisiblePrefix) { throw 'event-checkpoint-invalid' }
        return
    }
    if ($Phase -cnotin @('running','record-awaiting-release','terminal')) { throw 'event-checkpoint-invalid' }
    if ($Phase -ceq 'record-awaiting-release' -and -not $isSameVisiblePrefix) { throw 'event-checkpoint-invalid' }
    $eventState = if ($Phase -ceq 'record-awaiting-release') {
        Get-EventTerminalStateFromAwaiting $ProposedState
    } else {
        $ProposedState
    }
    $proposedRows = @(Get-ExpectedEventRows $eventState $ManifestResult)
    if ($proposedRows.Count -ne $currentRows.Count + 1) { throw 'event-checkpoint-invalid' }
    for ($index = 0; $index -lt $currentRows.Count; $index++) {
        if ((ConvertTo-CanonicalJson -Value $proposedRows[$index] -SchemaKind 'event-row') -cne
            (ConvertTo-CanonicalJson -Value $currentRows[$index] -SchemaKind 'event-row')) {
            throw 'event-checkpoint-invalid'
        }
    }
    $nextRow = $proposedRows[$proposedRows.Count - 1]
    $null = Enter-EventLock
    try {
        $ledger = Read-ValidatedEventLedger $currentRows @($CurrentState.workUnits).Count
        if (@($ledger.rows).Count -ne $currentRows.Count -or
            [string]$ledger.rows[@($ledger.rows).Count - 1].eventSha256 -cne [string]$currentRows[$currentRows.Count - 1].eventSha256 -or
            [string]$nextRow.previousEventSha256 -cne [string]$currentRows[$currentRows.Count - 1].eventSha256) {
            throw 'event-ledger-diverged'
        }
        $nextBytes = Get-CanonicalEventRowBytes $nextRow
        if ([int64]$ledger.bytes.Length + [int64]$nextBytes.Length -gt 256KB) { throw 'event-ledger-too-large' }
        if ($proposedRows.Count -gt 1 + 2 * @($ProposedState.workUnits).Count) { throw 'event-row-count-exceeded' }
    } finally { Exit-EventLock }
}

function Publish-StateAtomically {
    param(
        [string]$RootPath,
        [string]$DestinationPath,
        [byte[]]$ContentBytes,
        [scriptblock]$FinalGate,
        [AllowNull()][string]$ExpectedHash = $null,
        [switch]$RetainStateLock,
        [scriptblock]$PostwriteGate = { param($Published) },
        [scriptblock]$RollbackGate = {}
    )

    $directory = Split-Path $DestinationPath -Parent
    Assert-NoReparse $RootPath $directory
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Path $directory -Force -ErrorAction Stop | Out-Null
    }
    Assert-NoReparse $RootPath $directory

    $temporary = Join-Path $directory ('.state-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $lockEntered = $false
    $publishSucceeded = $false
    $replacementCompleted = $false
    $createdDestination = $false
    $backup = $null
    $candidateHash = Get-Sha256 $ContentBytes
    try {
        $stream = [IO.FileStream]::new(
            $temporary,
            [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write,
            [IO.FileShare]::None,
            4096,
            [IO.FileOptions]::WriteThrough
        )
        try {
            $stream.Write($ContentBytes, 0, $ContentBytes.Length)
            $stream.Flush($true)
        } finally {
            $stream.Dispose()
        }

        $null = Enter-StateCasLock $RootPath $DestinationPath
        $lockEntered = $true
        & $FinalGate
        Assert-NoReparse $RootPath $temporary
        if (-not (Test-Path -LiteralPath $temporary -PathType Leaf)) { throw 'state-candidate-changed' }
        $candidateItem = Get-Item -LiteralPath $temporary -Force -ErrorAction Stop
        if ([int64]$candidateItem.Length -ne [int64]$ContentBytes.Length -or [int64]$candidateItem.Length -gt $StateLimit) { throw 'state-candidate-changed' }
        $candidateBytes = [IO.File]::ReadAllBytes($temporary)
        if ($candidateBytes.Length -ne $ContentBytes.Length -or (Get-Sha256 $candidateBytes) -cne $candidateHash) { throw 'state-candidate-changed' }
        if ([string]::IsNullOrWhiteSpace($ExpectedHash)) {
            if (Test-Path -LiteralPath $DestinationPath) { throw 'state-already-exists' }
            [IO.File]::Move($temporary, $DestinationPath)
            $createdDestination = $true
        } else {
            $currentHash = Get-FileHashBounded $DestinationPath $StateLimit 'state-too-large'
            if ($currentHash -cne $ExpectedHash) { throw 'state-hash-changed' }
            $backup = $DestinationPath + '.cas-backup-' + [guid]::NewGuid().ToString('N')
            [IO.File]::Replace($temporary, $DestinationPath, $backup, $true)
            $replacementCompleted = $true
        }

        $published = Read-BoundedFile $DestinationPath $StateLimit 'state-too-large'
        if ($published.hash -cne $candidateHash) { throw 'state-postwrite-changed' }
        & $PostwriteGate $published
        if ($null -ne $backup -and (Test-Path -LiteralPath $backup)) {
            Assert-NoReparse $RootPath $backup
            [IO.File]::Delete($backup)
            if (Test-Path -LiteralPath $backup) { throw 'state-backup-cleanup-failed' }
        }
        $publishSucceeded = $true
        return $published.hash
    } catch {
        $failureReason = [string]$_.Exception.Message
        $rollbackRequired = $replacementCompleted -or $createdDestination
        $rollbackProved = -not $rollbackRequired
        $bundleRollbackProved = $true
        if ($rollbackRequired) {
            try { & $RollbackGate } catch { $bundleRollbackProved = $false }
        }
        if ($replacementCompleted) {
            $failedDestination = $DestinationPath + '.cas-failed-' + [guid]::NewGuid().ToString('N')
            try {
                Assert-NoReparse $RootPath $DestinationPath
                Assert-NoReparse $RootPath $backup
                if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) { throw 'state-backup-missing' }
                [IO.File]::Replace($backup, $DestinationPath, $failedDestination, $true)
                $rollbackProved = (Get-FileHashBounded $DestinationPath $StateLimit 'state-too-large') -ceq $ExpectedHash
                if ($rollbackProved -and (Test-Path -LiteralPath $failedDestination)) {
                    Assert-NoReparse $RootPath $failedDestination
                    [IO.File]::Delete($failedDestination)
                }
            } catch { $rollbackProved = $false }
        } elseif ($createdDestination) {
            try {
                Assert-NoReparse $RootPath $DestinationPath
                if (Test-Path -LiteralPath $DestinationPath) { [IO.File]::Delete($DestinationPath) }
                $rollbackProved = -not (Test-Path -LiteralPath $DestinationPath)
            } catch { $rollbackProved = $false }
        }
        if (-not $bundleRollbackProved) { throw 'event-rollback-unproven' }
        if (-not $rollbackProved) { throw 'state-rollback-unproven' }
        throw $failureReason
    } finally {
        if ($lockEntered -and (-not $RetainStateLock -or -not $publishSucceeded)) { Exit-StateCasLock }
        if (Test-Path -LiteralPath $temporary) {
            [IO.File]::Delete($temporary)
        }
    }
}

function Write-SnapshotResult {
    param([string]$Reason, $ManifestResult, [string]$StateHash)

    $manifest = if ($null -ne $ManifestResult) { $ManifestResult.manifest } else { $null }
    Write-Output ([ordered]@{
        action = $Action
        status = 'ok'
        reason = $Reason
        workUnitId = $null
        statePath = Get-RelativePath $root $state
        programPath = Get-RelativePath $root $program
        stateSha256 = $StateHash
        canonicalMarkdownSha256 = if ($null -ne $ManifestResult) { $ManifestResult.hash } else { $null }
        workUnitCount = if ($null -ne $manifest) { @($manifest.workUnits).Count } else { 0 }
        pendingCount = if ($null -ne $manifest) { @($manifest.workUnits | Where-Object { $_.status -ceq 'pending' }).Count } else { 0 }
        runnableCount = 0
    } | ConvertTo-Json -Compress)
}

function New-InitialSnapshotState {
    param($ManifestResult)
    $outputState = [ordered]@{
        schemaVersion = $Schema
        canonicalMarkdownPath = Get-RelativePath $root $program
        canonicalMarkdownSha256 = $ManifestResult.hash
        sourceOwner = 'desktop'
        activeSourceSets = @('main/java', 'main/resources', 'app/src/main/java_clean', 'app/src/main/resources')
        controllerManifest = $ManifestResult.manifest
        directiveInventory = $ManifestResult.manifest.directiveInventory
        workUnits = $ManifestResult.manifest.workUnits
        retirement = $ManifestResult.manifest.retirement
        desktopFinalProof = 'evidence_needed'
        runtimeLineageVerdict = 'HOLD'
        deleteAuthorized = $false
        eventCheckpoint = [ordered]@{}
    }
    $candidateText = $outputState | ConvertTo-Json -Depth 30 -Compress
    $candidateValue = ConvertFrom-StrictJson $candidateText 'invalid-state-schema'
    return Get-ValidatedStateObject $candidateValue $ManifestResult -SynchronizeDerived
}

function Invoke-Snapshot {
    Assert-SnapshotRootAuthority $root
    if (Test-Path -LiteralPath $state) {
        $existing = Get-ValidatedState
        $expectedInitial = New-InitialSnapshotState $existing.manifest
        if (-not (Test-SameJson $existing.state $expectedInitial)) { throw 'state-already-exists' }
        if ($WhatIfPreference) {
            Write-SnapshotResult 'whatif' $existing.manifest $existing.hash
            exit 0
        }
        Set-EventCommittedContext $null $existing.hash $existing.manifest.hash
        $publishedInitial = Sync-EventLedger
        if ([string]$publishedInitial.hash -cne [string]$existing.hash) { throw 'event-publication-pending' }
        Write-SnapshotResult 'snapshot-already-created' $existing.manifest $existing.hash
        exit 0
    }
    $manifestResult = Assert-SnapshotGates $root $program $state $null

    if ($WhatIfPreference) {
        Write-SnapshotResult 'whatif' $manifestResult $null
        exit 0
    }
    if (-not $PSCmdlet.ShouldProcess((Get-RelativePath $root $state), 'create controller state')) {
        Write-SnapshotResult 'declined' $manifestResult $null
        exit 0
    }

    $validatedCandidate = New-InitialSnapshotState $manifestResult
    $stateText = $validatedCandidate | ConvertTo-Json -Depth 30 -Compress
    $strictPostimage = ConvertFrom-StrictJson $stateText 'invalid-state-schema'
    $null = Get-ValidatedStateObject $strictPostimage $manifestResult
    Assert-SnapshotEventPreflight $validatedCandidate $manifestResult
    $stateBytes = $Utf8.GetBytes($stateText)
    $finalGate = {
        $null = Assert-SnapshotGates $root $program $state $manifestResult.hash
    }
    $postwriteGate = {
        param($Published)
        $publishedValue = ConvertFrom-StrictJson $Published.text 'malformed-state'
        $validatedPublished = Get-ValidatedStateObject $publishedValue $manifestResult
        if (-not (Test-SameJson $publishedValue $validatedPublished)) { throw 'invalid-state-schema' }
    }.GetNewClosure()
    try {
        $stateHash = Publish-StateAtomically $root $state $stateBytes $finalGate -PostwriteGate $postwriteGate
    } catch {
        $snapshotPublishReason = [string]$_.Exception.Message
        if ($snapshotPublishReason -cne 'state-already-exists') { throw }
        $concurrentExisting = Get-ValidatedState
        if ([string]$concurrentExisting.manifest.hash -cne [string]$manifestResult.hash -or
            -not (Test-SameJson $concurrentExisting.state (New-InitialSnapshotState $concurrentExisting.manifest))) {
            throw $snapshotPublishReason
        }
        Set-EventCommittedContext $null $concurrentExisting.hash $concurrentExisting.manifest.hash
        $concurrentPublished = Sync-EventLedger
        if ([string]$concurrentPublished.hash -cne [string]$concurrentExisting.hash) { throw 'event-publication-pending' }
        Write-SnapshotResult 'snapshot-already-created' $concurrentExisting.manifest $concurrentExisting.hash
        exit 0
    }
    Set-EventCommittedContext $null $stateHash $manifestResult.hash
    $publishedState = Sync-EventLedger
    if ([string]$publishedState.hash -cne $stateHash) { throw 'event-publication-pending' }
    Write-SnapshotResult 'snapshot-created' $manifestResult $stateHash
    exit 0
}

function Set-ObjectProperty {
    param($Object, [string]$Name, $Value)
    if (Test-HasProperty $Object $Name) { $Object.$Name = $Value }
    else { $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value }
}

function Assert-SafeIdentifier {
    param([string]$Value, [string]$Reason)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$') { throw $Reason }
}

function Publish-BoundedBytesAtomically {
    param([string]$DestinationPath, [byte[]]$ContentBytes, [AllowNull()][string]$ExpectedHash = $null)
    if ($ContentBytes.Length -gt 1MB) { throw 'file-too-large' }
    $directory = Split-Path $DestinationPath -Parent
    Assert-NoReparse $root $directory
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force -ErrorAction Stop | Out-Null }
    Assert-NoReparse $root $directory
    $temporary = Join-Path $directory ('.publish-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $stream = [IO.FileStream]::new($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None, 4096, [IO.FileOptions]::WriteThrough)
        try { $stream.Write($ContentBytes, 0, $ContentBytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        if ([string]::IsNullOrWhiteSpace($ExpectedHash)) {
            if (Test-Path -LiteralPath $DestinationPath) { throw 'destination-already-exists' }
            [IO.File]::Move($temporary, $DestinationPath)
        } else {
            if ((Get-FileHashBounded $DestinationPath 1MB 'file-too-large') -cne $ExpectedHash) { throw 'file-hash-changed' }
            $backup = $DestinationPath + '.cas-backup-' + [guid]::NewGuid().ToString('N')
            try { [IO.File]::Replace($temporary, $DestinationPath, $backup, $true) } finally { if (Test-Path -LiteralPath $backup) { [IO.File]::Delete($backup) } }
        }
        $actual = Get-FileHashBounded $DestinationPath 1MB 'file-too-large'
        if ($actual -cne (Get-Sha256 $ContentBytes)) { throw 'postwrite-hash-changed' }
        return $actual
    } finally { if (Test-Path -LiteralPath $temporary) { [IO.File]::Delete($temporary) } }
}

function Write-UpdatedState {
    param($StateObject, [string]$ExpectedHash, [string]$Phase)
    if ($Phase -cnotin @('begin-intent','running','begin-cleanup-complete','begin-recovery-required','record-awaiting-release','terminal')) {
        throw 'invalid-cas-phase'
    }
    $current = Get-ValidatedState
    if ([string]$current.hash -cne $ExpectedHash) { throw 'state-hash-changed' }
    if (-not (Test-HasProperty $StateObject 'eventCheckpoint') -or
        (ConvertTo-CanonicalJson -Value $StateObject.eventCheckpoint -SchemaKind 'event-row') -cne
        (ConvertTo-CanonicalJson -Value $current.state.eventCheckpoint -SchemaKind 'event-row')) {
        throw 'event-checkpoint-invalid'
    }
    if (-not (Test-HasProperty $StateObject 'retirement') -or
        -not (Test-HasProperty $StateObject.retirement 'allRequiredWorkUnitsGreen') -or
        -not (Test-HasProperty $StateObject.retirement 'desktopFinalProof') -or
        [bool]$StateObject.retirement.allRequiredWorkUnitsGreen -ne [bool]$current.state.retirement.allRequiredWorkUnitsGreen -or
        [string]$StateObject.retirement.desktopFinalProof -cne [string]$current.state.retirement.desktopFinalProof) {
        throw 'invalid-retirement-projection'
    }
    $projected = Get-ValidatedStateObject $StateObject $current.manifest -SynchronizeDerived
    $bytes = $Utf8.GetBytes((ConvertTo-Json -InputObject $projected -Depth 40 -Compress))
    if ($bytes.Length -gt $StateLimit) { throw 'state-too-large' }
    $candidateText = $Utf8.GetString($bytes)
    $candidate = ConvertFrom-StrictJson $candidateText 'malformed-state'
    $validatedCandidate = Get-ValidatedStateObject $candidate $current.manifest
    if (-not (Test-SameJson $candidate $validatedCandidate)) { throw 'invalid-state-schema' }
    Assert-ProspectiveEventPreflight $current.state $validatedCandidate $current.manifest $Phase
    $postwriteManifest = $current.manifest
    $postwriteGate = {
        param($Published)
        $publishedValue = ConvertFrom-StrictJson $Published.text 'malformed-state'
        $validatedPublished = Get-ValidatedStateObject $publishedValue $postwriteManifest
        if (-not (Test-SameJson $publishedValue $validatedPublished)) { throw 'invalid-state-schema' }
    }.GetNewClosure()
    return Publish-StateAtomically -RootPath $root -DestinationPath $state -ContentBytes $bytes -FinalGate {
        if ((Get-FileHashBounded $state $StateLimit 'state-too-large') -cne $ExpectedHash) { throw 'state-hash-changed' }
        $currentManifest = Get-ControllerManifest $program
        if ([string]$currentManifest.hash -cne [string]$current.manifest.hash) { throw 'canonical-hash-changed' }
    } -ExpectedHash $ExpectedHash -RetainStateLock:($Phase -ceq 'begin-recovery-required') -PostwriteGate $postwriteGate
}

function Write-AdoptedStateAndEvents {
    param($StateObject, [string]$ExpectedHash, $ExpectedTargetRows, [string]$Topic, [string]$OwnerId)

    $current = Get-ValidatedState
    if ([string]$current.hash -cne $ExpectedHash) { throw 'state-hash-changed' }
    if ($ExpectedTargetRows -isnot [Array]) { throw 'invalid-target-snapshot' }
    if (-not (Test-HasProperty $StateObject 'eventCheckpoint') -or
        (ConvertTo-CanonicalJson -Value $StateObject.eventCheckpoint -SchemaKind 'event-row') -cne
        (ConvertTo-CanonicalJson -Value $current.state.eventCheckpoint -SchemaKind 'event-row')) { throw 'event-checkpoint-invalid' }
    if (-not (Test-HasProperty $StateObject 'retirement') -or
        [bool]$StateObject.retirement.allRequiredWorkUnitsGreen -ne [bool]$current.state.retirement.allRequiredWorkUnitsGreen -or
        [string]$StateObject.retirement.desktopFinalProof -cne [string]$current.state.retirement.desktopFinalProof) {
        throw 'invalid-retirement-projection'
    }
    $projected = Get-ValidatedStateObject $StateObject $current.manifest -SynchronizeDerived
    $bundle = Get-AdoptEventBundleContext $current.state $projected $current.manifest
    if (@($bundle.proposedRows).Count -ne @($bundle.currentRows).Count + 2) { throw 'event-checkpoint-invalid' }
    $bytes = $Utf8.GetBytes((ConvertTo-Json -InputObject $projected -Depth 40 -Compress))
    if ($bytes.Length -gt $StateLimit) { throw 'state-too-large' }
    $candidate = ConvertFrom-StrictJson ($Utf8.GetString($bytes)) 'malformed-state'
    $validatedCandidate = Get-ValidatedStateObject $candidate $current.manifest
    if (-not (Test-SameJson $candidate $validatedCandidate)) { throw 'invalid-state-schema' }

    $eventContext = [pscustomobject]@{ value = $null }
    $finalGate = {
        if ((Get-FileHashBounded $state $StateLimit 'state-too-large') -cne $ExpectedHash) { throw 'state-hash-changed' }
        $currentManifest = Get-ControllerManifest $program
        if ([string]$currentManifest.hash -cne [string]$current.manifest.hash) { throw 'canonical-hash-changed' }
        Assert-AdoptPublicationGates $ExpectedTargetRows $Topic $OwnerId
        Assert-AdoptEventBundlePreflight $current.state $projected $current.manifest
    }.GetNewClosure()
    $postwriteGate = {
        param($Published)
        $publishedValue = ConvertFrom-StrictJson $Published.text 'malformed-state'
        $validatedPublished = Get-ValidatedStateObject $publishedValue $current.manifest
        if (-not (Test-SameJson $publishedValue $validatedPublished)) { throw 'invalid-state-schema' }
        $eventContext.value = Publish-AdoptEventBundle $current.state $projected $current.manifest $ExpectedTargetRows $Topic $OwnerId
    }.GetNewClosure()
    $rollbackGate = {
        if ($null -ne $eventContext.value) { Restore-AdoptEventBundle $eventContext.value }
    }.GetNewClosure()
    return Publish-StateAtomically -RootPath $root -DestinationPath $state -ContentBytes $bytes -FinalGate $finalGate -ExpectedHash $ExpectedHash -PostwriteGate $postwriteGate -RollbackGate $rollbackGate
}

function Get-EventPath { return Join-Path (Split-Path $state -Parent) 'events.jsonl' }

function Write-ProgramEvent {
    param([string]$ActionName, [string]$UnitId, [string]$EventRunId, [string]$Result, [string]$EvidenceHash = '', [string]$DecisionKind = '', [string]$ReferenceHash = '')
    $identity = "$ActionName|$UnitId|$EventRunId|$Result|$EvidenceHash|$DecisionKind|$ReferenceHash"
    $eventId = Get-Sha256 $Utf8.GetBytes($identity)
    $path = Get-EventPath
    $existingText = ''
    $existingHash = $null
    if (Test-Path -LiteralPath $path) {
        $file = Read-BoundedFile $path 1MB 'events-too-large'
        $existingText = $file.text
        $existingHash = $file.hash
        foreach ($line in @($existingText -split "\r?\n")) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $record = ConvertFrom-StrictJson $line 'malformed-events'
            Assert-ClosedProperties $record @('schemaVersion','eventId','action','workUnitId','runId','result','evidenceSha256') @('decisionKind','referenceSha256') 'invalid-event-schema'
            $rowDecision = if(Test-HasProperty $record 'decisionKind'){[string]$record.decisionKind}else{''}
            $rowReference = if(Test-HasProperty $record 'referenceSha256'){[string]$record.referenceSha256}else{''}
            $allowedResult = ($record.action -ceq 'Begin' -and $record.result -ceq 'running') -or
                ($record.action -ceq 'Record' -and $record.result -cin @('green','no_patch_needed','hold','failed')) -or
                ($record.action -ceq 'Release' -and $record.result -ceq 'prepared') -or
                ($record.action -ceq 'Retire' -and $record.result -ceq 'complete')
            $expectedId = Get-Sha256 $Utf8.GetBytes("$($record.action)|$($record.workUnitId)|$($record.runId)|$($record.result)|$($record.evidenceSha256)|$rowDecision|$rowReference")
            if ($record.schemaVersion -cne 'awx.notebook.directive.event.v1' -or [string]$record.eventId -notmatch '^[A-F0-9]{64}$' -or
                $record.eventId -cne $expectedId -or -not $allowedResult -or ($rowReference -and $rowReference -notmatch '^[A-F0-9]{64}$')) { throw 'invalid-event-schema' }
            if ((Test-HasProperty $record 'eventId') -and [string]$record.eventId -ceq $eventId) { return $false }
        }
    }
    $event = [ordered]@{ schemaVersion = 'awx.notebook.directive.event.v1'; eventId = $eventId; action = $ActionName; workUnitId = $UnitId; runId = $EventRunId; result = $Result; evidenceSha256 = $EvidenceHash }
    if (-not [string]::IsNullOrWhiteSpace($DecisionKind)) { $event.decisionKind = $DecisionKind; $event.referenceSha256 = $ReferenceHash }
    $newText = $existingText
    if ($newText.Length -gt 0 -and -not $newText.EndsWith([Environment]::NewLine)) { $newText += [Environment]::NewLine }
    $newText += ($event | ConvertTo-Json -Compress) + [Environment]::NewLine
    $bytes = $Utf8.GetBytes($newText)
    if ($bytes.Length -gt 1MB) { throw 'events-too-large' }
    $null = Publish-BoundedBytesAtomically $path $bytes $existingHash
    return $true
}

function Get-Unit {
    param($StateObject, [string]$Id)
    Assert-SafeIdentifier $Id 'invalid-work-unit-id'
    $matches = @($StateObject.workUnits | Where-Object { $_.workUnitId -ceq $Id })
    if ($matches.Count -ne 1) { throw 'unknown-work-unit-id' }
    return $matches[0]
}

function Invoke-SourceSession {
    param([string]$SessionAction, [string]$UnitId, [string]$Owner)
    $guard = Join-Path $root '__patch_drop__\source_edit_session.ps1'
    Assert-NoReparse $root $guard
    if (-not (Test-Path -LiteralPath $guard -PathType Leaf)) { throw 'source-session-guard-missing' }
    $engine = (Get-Command powershell.exe -ErrorAction Stop).Source
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $null = @(& $engine -NoProfile -ExecutionPolicy Bypass -File $guard -Action $SessionAction -Role desktop -Root $root -Topic $UnitId -OwnerId $Owner 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($code -ne 0) { throw ('source-session-' + $SessionAction + '-failed') }
}

function Test-SourceSessionLease {
    param([string]$UnitId, [string]$Owner)
    $leasePath = Join-Path $root ("__patch_drop__\source-edit-locks\$UnitId.lock\lease.json")
    Assert-NoReparse $root $leasePath
    if (-not (Test-Path -LiteralPath $leasePath -PathType Leaf)) { return $false }
    $leaseFile = Read-BoundedFile $leasePath 64KB 'source-lease-too-large'
    $lease = ConvertFrom-StrictJson $leaseFile.text 'source-lease-invalid'
    return (Test-HasProperty $lease 'topic') -and (Test-HasProperty $lease 'ownerId') -and
        [string]$lease.topic -ceq $UnitId -and [string]$lease.ownerId -ceq $Owner
}

function Get-TargetPreimages {
    param($Unit)
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $git) { $git = Get-Command git -ErrorAction SilentlyContinue }
    if ($null -eq $git) { throw 'dirty-target-evidence-needed' }
    $targets = @($Unit.targetFiles | ForEach-Object { [string]$_ })
    if ($targets.Count -eq 0) { return ,@() }
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $dirty = @(& $git.Source -C $root status --porcelain=v1 -- @targets 2>$null)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($code -ne 0) { throw 'dirty-target-evidence-needed' }
    if ($dirty.Count -gt 0) { throw 'dirty-target-overlap' }
    $rows = @()
    foreach ($relativeText in $targets) {
        $relative = ConvertTo-ValidatedRelativePath $relativeText 'invalid-target-file'
        $full = Get-FullPath $relative $root
        if (-not (Test-InRoot $root $full)) { throw 'invalid-target-file' }
        $file = Read-BoundedFile $full 1MB 'target-too-large' 'target-file-missing'
        $rows += ,[ordered]@{ path = $relative; sha256 = $file.hash; bytes = $file.bytes.Length }
    }
    return ,@($rows)
}

function Write-ActionResult {
    param([string]$Reason, [string]$UnitId, [string]$StateHash = $null)
    Write-Output ([ordered]@{ action = $Action; status = 'ok'; reason = $Reason; workUnitId = $UnitId; statePath = Get-RelativePath $root $state; stateSha256 = $StateHash } | ConvertTo-Json -Compress)
}

function Invoke-Begin {
    Assert-SafeIdentifier $RunId 'invalid-run-id'
    $validated = Get-ValidatedState
    $unit = Get-Unit $validated.state $WorkUnitId
    if ($unit.status -ceq 'running' -and (Test-HasProperty $unit 'activeRun') -and [string]$unit.activeRun.runId -ceq $RunId) {
        if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $validated.hash; return }
        if (Test-HasProperty $unit.activeRun 'resumeEvidenceSha256') {
            $resumeReplay = Get-ValidatedResumeEvidence $unit
            if ($resumeReplay.hash -cne [string]$unit.activeRun.resumeEvidenceSha256 -or $resumeReplay.decisionKind -cne [string]$unit.activeRun.resumeDecisionKind -or
                $resumeReplay.referenceSha256 -cne [string]$unit.activeRun.resumeReferenceSha256) { throw 'resume-evidence-changed' }
        }
        if (-not (Test-SourceSessionLease $WorkUnitId $RunId)) {
            Invoke-SourceSession 'begin' $WorkUnitId $RunId
            try { Assert-FrozenTargetPreimages $unit } catch { try { Invoke-SourceSession 'end' $WorkUnitId $RunId } catch {}; throw }
        }
        $eventEvidence = if(Test-HasProperty $unit.activeRun 'resumeEvidenceSha256'){[string]$unit.activeRun.resumeEvidenceSha256}else{''}
        $eventDecision = if(Test-HasProperty $unit.activeRun 'resumeDecisionKind'){[string]$unit.activeRun.resumeDecisionKind}else{''}
        $eventReference = if(Test-HasProperty $unit.activeRun 'resumeReferenceSha256'){[string]$unit.activeRun.resumeReferenceSha256}else{''}
        $null = Write-ProgramEvent 'Begin' $WorkUnitId $RunId 'running' $eventEvidence $eventDecision $eventReference
        Write-ActionResult 'already-running' $WorkUnitId $validated.hash; return
    }
    if ($unit.status -cnotin @('pending', 'hold')) { throw 'work-unit-not-pending' }
    foreach ($dependencyId in @($unit.dependencies)) {
        $dependency = Get-Unit $validated.state ([string]$dependencyId)
        if ($dependency.status -cnotin @('green', 'no_patch_needed')) { throw 'dependency-blocked' }
    }
    $resumeEvidence = $null
    if ($unit.status -ceq 'hold') { $resumeEvidence = Get-ValidatedResumeEvidence $unit }
    if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $validated.hash; return }
    Invoke-SourceSession 'begin' $WorkUnitId $RunId
    try {
        $preimages = Get-TargetPreimages $unit
        $unit.status = 'running'
        Set-ObjectProperty $unit 'targetPreimages' $preimages
        $activeRun = [ordered]@{ runId = $RunId; owner = 'desktop'; leaseTopic = $WorkUnitId }
        if ($null -ne $resumeEvidence) { $activeRun.resumeEvidenceSha256=$resumeEvidence.hash; $activeRun.resumeDecisionKind=$resumeEvidence.decisionKind; $activeRun.resumeReferenceSha256=$resumeEvidence.referenceSha256 }
        Set-ObjectProperty $unit 'activeRun' $activeRun
        $newHash = Write-UpdatedState $validated.state $validated.hash
    } catch {
        try { Invoke-SourceSession 'end' $WorkUnitId $RunId } catch {}
        throw
    }
    $null = Write-ProgramEvent 'Begin' $WorkUnitId $RunId 'running' $(if($resumeEvidence){$resumeEvidence.hash}else{''}) $(if($resumeEvidence){$resumeEvidence.decisionKind}else{''}) $(if($resumeEvidence){$resumeEvidence.referenceSha256}else{''})
    Write-ActionResult 'work-unit-begun' $WorkUnitId $newHash
}

function Assert-NoRawSensitiveFields {
    param($Value)
    if ($null -eq $Value) { return }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.Name -match '(?i)(api.?key|token|authorization|cookie|password|credential|header|prompt|response)' -or
                $property.Name -match '(?i)^raw.*(secret|query|body)' -or $property.Name -match '(?i)^(secret|query|body)(raw|value|text)$') { throw 'raw-sensitive-field' }
            Assert-NoRawSensitiveFields $property.Value
        }
    } elseif ($Value -is [Array]) { foreach ($item in $Value) { Assert-NoRawSensitiveFields $item } }
}

function Get-ValidatedResumeEvidence {
    param($Unit)
    if ([string]::IsNullOrWhiteSpace($EvidencePath)) { throw 'resume-evidence-required' }
    $full = Get-FullPath $EvidencePath $root
    if (-not (Test-InRoot $root $full)) { throw 'evidence-path-outside-root' }
    $file = Read-BoundedFile $full 1MB 'evidence-too-large' 'evidence-missing'
    $value = ConvertFrom-StrictJson $file.text 'malformed-evidence'
    Assert-NoRawSensitiveFields $value
    Assert-ClosedProperties $value @('schemaVersion','workUnitId','runId','decisionKind','decision') @() 'invalid-resume-evidence-schema'
    if ($value.schemaVersion -cne 'awx.notebook.directive.resume-evidence.v1' -or $value.workUnitId -cne $WorkUnitId -or
        $value.runId -cne $RunId -or $value.decisionKind -cnotin @('no_new_public_api','explicit_approval')) { throw 'invalid-resume-evidence-schema' }
    if ($value.decisionKind -ceq 'no_new_public_api') {
        Assert-ClosedProperties $value.decision @('result','scopePath','scopeSha256') @() 'invalid-resume-evidence-schema'
        if ($value.decision.result -cne 'no_new_public_api') { throw 'n110-approval-required' }
        $referenceHash = [string]$value.decision.scopeSha256
        $artifactPath = [string]$value.decision.scopePath
    } else {
        Assert-ClosedProperties $value.decision @('result','approvalArtifactPath','approvalArtifactSha256','referenceSha256') @() 'invalid-resume-evidence-schema'
        if ($value.decision.result -cne 'approved' -or [string]$value.decision.referenceSha256 -notmatch '^[A-F0-9]{64}$' -or
            [string]$value.decision.approvalArtifactSha256 -cne [string]$value.decision.referenceSha256) { throw 'n110-approval-required' }
        $referenceHash = [string]$value.decision.referenceSha256
        $artifactPath = [string]$value.decision.approvalArtifactPath
    }
    if ($referenceHash -notmatch '^[A-F0-9]{64}$') { throw 'invalid-resume-evidence-schema' }
    $relative = ConvertTo-ValidatedRelativePath $artifactPath 'invalid-resume-evidence-schema'
    $artifact = Read-BoundedFile (Get-FullPath $relative $root) 1MB 'resume-artifact-too-large' 'resume-artifact-missing'
    if ($artifact.hash -cne $referenceHash) { throw 'resume-artifact-hash-mismatch' }
    return [pscustomobject]@{ value=$value; hash=$file.hash; decisionKind=[string]$value.decisionKind; referenceSha256=$referenceHash }
}

function Assert-FrozenTargetPreimages {
    param($Unit)
    if (-not (Test-HasProperty $Unit 'targetPreimages')) { throw 'target-preimage-changed' }
    $frozen = @($Unit.targetPreimages)
    if ($frozen.Count -ne @($Unit.targetFiles).Count) { throw 'target-preimage-changed' }
    foreach ($preimage in $frozen) {
        $relative = ConvertTo-ValidatedRelativePath ([string]$preimage.path) 'target-preimage-changed'
        $current = Read-BoundedFile (Get-FullPath $relative $root) 1MB 'target-too-large' 'target-preimage-changed'
        if ($current.hash -cne [string]$preimage.sha256 -or $current.bytes.Length -ne [int64]$preimage.bytes) { throw 'target-preimage-changed' }
    }
}

function Get-VerifiedProofArtifact {
    param($Record, [string]$Reason)
    Assert-ClosedProperties $Record @('result','exitCode','artifactPath','artifactSha256') @() $Reason
    if ($Record.result -cne 'pass' -or -not (Test-JsonInteger $Record.exitCode) -or [int]$Record.exitCode -ne 0 -or
        $Record.artifactPath -isnot [string] -or $Record.artifactSha256 -isnot [string] -or $Record.artifactSha256 -cnotmatch '^[A-F0-9]{64}$') { throw $Reason }
    $relative = ConvertTo-ValidatedRelativePath $Record.artifactPath $Reason
    $artifact = Read-BoundedFile (Get-FullPath $relative $root) 1MB 'proof-artifact-too-large' 'proof-artifact-missing'
    if ($artifact.hash -cne $Record.artifactSha256) { throw 'proof-artifact-hash-mismatch' }
    return [ordered]@{ proofKind = $Reason.Replace('invalid-final-proof-', ''); sha256 = $artifact.hash }
}

function Get-ValidatedFinalEvidence {
    param($Evidence, [string]$EvidenceHash, $StateObject)
    $fields = @('schemaVersion','workUnitId','runId','outcome','focusedDurationMs','changedPaths','postimages','secretScan','desktopFinalProof')
    Assert-ClosedProperties $Evidence $fields @() 'invalid-final-evidence-schema'
    if ($Evidence.schemaVersion -cne 'awx.notebook.directive.final-evidence.v1' -or $Evidence.workUnitId -cne 'WU-FINAL' -or
        $Evidence.runId -cne $RunId -or $Evidence.outcome -cne 'Green' -or -not (Test-JsonInteger $Evidence.focusedDurationMs) -or
        [int64]$Evidence.focusedDurationMs -lt 0) { throw 'invalid-final-evidence-schema' }
    Assert-JsonArray $Evidence.changedPaths 'invalid-final-evidence-schema' 0
    Assert-JsonArray $Evidence.postimages 'invalid-final-evidence-schema' 0
    Assert-ClosedProperties $Evidence.secretScan @('mode','hitCount') @() 'invalid-secret-scan'
    if ($Evidence.secretScan.mode -cne 'count-only' -or -not (Test-JsonInteger $Evidence.secretScan.hitCount) -or [int]$Evidence.secretScan.hitCount -ne 0) { throw 'invalid-secret-scan' }
    $proofNames = @('schemaVersion','controllerTests','chatUiContract','gradleProjects','compileJava','appClasses','bootJar','browserSmoke')
    Assert-ClosedProperties $Evidence.desktopFinalProof $proofNames @() 'invalid-final-proof-schema'
    if ($Evidence.desktopFinalProof.schemaVersion -cne 'awx.notebook.directive.desktop-final-proof.v1') { throw 'invalid-final-proof-schema' }
    $proofRows = @()
    foreach ($name in @('controllerTests','chatUiContract','gradleProjects','compileJava','appClasses','bootJar')) {
        $proofRows += ,(Get-VerifiedProofArtifact $Evidence.desktopFinalProof.$name ("invalid-final-proof-$name"))
    }
    $browserRequired = @($StateObject.workUnits | ForEach-Object { if(Test-HasProperty $_ 'record'){@($_.record.changedPaths)} } | Where-Object { $_ -match '(?i)(^frontend/|\.js$|\.css$|\.html$|^main/resources/|controller\.java$)' }).Count -gt 0
    $browser = $Evidence.desktopFinalProof.browserSmoke
    if ($browserRequired) {
        Assert-ClosedProperties $browser @('applicability','result','exitCode','artifactPath','artifactSha256') @() 'invalid-final-proof-browser'
        if ($browser.applicability -cne 'required') { throw 'browser-proof-required' }
        $proofRows += ,(Get-VerifiedProofArtifact ([pscustomobject]@{result=$browser.result;exitCode=$browser.exitCode;artifactPath=$browser.artifactPath;artifactSha256=$browser.artifactSha256}) 'invalid-final-proof-browser')
    } else {
        Assert-ClosedProperties $browser @('applicability','reasonCode','scopePath','scopeSha256') @() 'invalid-final-proof-browser'
        if ($browser.applicability -cne 'not_applicable' -or $browser.reasonCode -cne 'no-ui-resource-controller-change' -or
            $browser.scopeSha256 -isnot [string] -or $browser.scopeSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'invalid-final-proof-browser' }
        $scopePath = ConvertTo-ValidatedRelativePath $browser.scopePath 'invalid-final-proof-browser'
        $scope = Read-BoundedFile (Get-FullPath $scopePath $root) 1MB 'proof-artifact-too-large' 'proof-artifact-missing'
        if ($scope.hash -cne $browser.scopeSha256) { throw 'proof-artifact-hash-mismatch' }
        $proofRows += ,[ordered]@{ proofKind='browserScope'; sha256=$scope.hash }
    }
    return [pscustomobject]@{ value=$Evidence; hash=$EvidenceHash; finalProofArtifacts=@($proofRows) }
}

function Get-ValidatedEvidence {
    param($Unit, $StateObject, [switch]$Replay)
    if ([string]::IsNullOrWhiteSpace($EvidencePath)) { throw 'evidence-path-required' }
    $full = Get-FullPath $EvidencePath $root
    if (-not (Test-InRoot $root $full)) { throw 'evidence-path-outside-root' }
    $file = Read-BoundedFile $full 1MB 'evidence-too-large' 'evidence-missing'
    $evidence = ConvertFrom-StrictJson $file.text 'malformed-evidence'
    Assert-NoRawSensitiveFields $evidence
    if ($Unit.workUnitId -ceq 'WU-FINAL') { return Get-ValidatedFinalEvidence $evidence $file.hash $StateObject }
    $fields = @('schemaVersion', 'workUnitId', 'runId', 'outcome', 'focusedGreen', 'focusedExitCode', 'focusedDurationMs', 'changedPaths', 'postimages', 'secretScan')
    Assert-ClosedProperties $evidence $fields @() 'invalid-evidence-schema'
    if ([string]$evidence.schemaVersion -cne 'awx.notebook.directive.evidence.v1' -or [string]$evidence.workUnitId -cne $WorkUnitId -or [string]$evidence.runId -cne $RunId -or [string]$evidence.outcome -cne $Outcome) { throw 'invalid-evidence-schema' }
    Assert-JsonArray $evidence.changedPaths 'invalid-changed-paths' $TargetLimit
    Assert-JsonArray $evidence.postimages 'invalid-postimages' $TargetLimit
    Assert-RequiredProperties $evidence.secretScan @('mode', 'hitCount') 'invalid-secret-scan'
    if ([string]$evidence.secretScan.mode -cne 'count-only' -or -not (Test-JsonInteger $evidence.secretScan.hitCount) -or [int]$evidence.secretScan.hitCount -ne 0 -or @($evidence.secretScan.PSObject.Properties).Count -ne 2) { throw 'invalid-secret-scan' }
    if (-not (Test-JsonInteger $evidence.focusedDurationMs) -or [int64]$evidence.focusedDurationMs -lt 0) { throw 'invalid-evidence-schema' }
    if ($Outcome -in @('Green', 'NoPatchNeeded') -and ($evidence.focusedGreen -ne $true -or -not (Test-JsonInteger $evidence.focusedExitCode) -or [int]$evidence.focusedExitCode -ne 0)) { throw 'focused-green-missing' }
    if ($Outcome -ceq 'NoPatchNeeded' -and @($evidence.changedPaths).Count -ne 0) { throw 'no-patch-changed-paths' }
    $targets = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($target in @($Unit.targetFiles)) { $null = $targets.Add((ConvertTo-ValidatedRelativePath ([string]$target) 'invalid-target-file')) }
    $changed = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($pathText in @($evidence.changedPaths)) {
        if ($pathText -isnot [string]) { throw 'invalid-changed-paths' }
        $relative = ConvertTo-ValidatedRelativePath $pathText 'invalid-changed-path'
        if (-not $targets.Contains($relative) -or -not $changed.Add($relative)) { throw 'changed-path-outside-targets' }
    }
    $postimageMap = @{}
    foreach ($postimage in @($evidence.postimages)) {
        Assert-ClosedProperties $postimage @('path', 'sha256') @() 'invalid-postimages'
        $relative = ConvertTo-ValidatedRelativePath ([string]$postimage.path) 'invalid-postimage-path'
        if (-not $changed.Contains($relative) -or $postimageMap.ContainsKey($relative) -or [string]$postimage.sha256 -notmatch '^[A-F0-9]{64}$') { throw 'invalid-postimages' }
        if (-not $Replay) {
            $current = Get-FileHashBounded (Get-FullPath $relative $root) 1MB 'postimage-too-large'
            if ($current -cne [string]$postimage.sha256) { throw 'postimage-hash-mismatch' }
        }
        $postimageMap[$relative] = [string]$postimage.sha256
    }
    if ($postimageMap.Count -ne $changed.Count) { throw 'postimage-coverage-mismatch' }
    return [pscustomobject]@{ value = $evidence; hash = $file.hash }
}

function Invoke-Record {
    if ($Outcome -cnotin @('Green', 'NoPatchNeeded', 'Hold', 'Failed')) { throw 'invalid-outcome' }
    Assert-SafeIdentifier $RunId 'invalid-run-id'
    $validated = Get-ValidatedState
    $unit = Get-Unit $validated.state $WorkUnitId
    if ($unit.status -cin @('green', 'no_patch_needed', 'hold', 'failed')) {
        $evidence = Get-ValidatedEvidence $unit $validated.state -Replay
        if ((Test-HasProperty $unit 'record') -and [string]$unit.record.evidenceSha256 -ceq $evidence.hash -and [string]$unit.record.runId -ceq $RunId) {
            if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $validated.hash; return }
            if ((Test-HasProperty $unit 'leaseReleasePending') -and $unit.leaseReleasePending -eq $true) {
                Invoke-SourceSession 'end' $WorkUnitId $RunId
                $unit.leaseReleasePending = $false
                $unit.PSObject.Properties.Remove('activeRun')
                $validated.hash = Write-UpdatedState $validated.state $validated.hash
            }
            $null = Write-ProgramEvent 'Record' $WorkUnitId $RunId $unit.status $evidence.hash
            Write-ActionResult 'already-recorded' $WorkUnitId $validated.hash; return
        }
        throw 'work-unit-already-terminal'
    }
    if ($unit.status -cne 'running' -or -not (Test-HasProperty $unit 'activeRun') -or [string]$unit.activeRun.runId -cne $RunId) { throw 'work-unit-not-running' }
    $evidence = Get-ValidatedEvidence $unit $validated.state
    if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $validated.hash; return }
    if ($WorkUnitId -ceq 'WU-FINAL' -and $Outcome -ceq 'Green') {
        foreach ($dependencyId in @($unit.dependencies)) {
            $dependency = Get-Unit $validated.state ([string]$dependencyId)
            if ($dependency.status -cnotin @('green','no_patch_needed')) { throw 'final-dependency-incomplete' }
        }
        $validated.state.desktopFinalProof = 'verified'
        $validated.state.deleteAuthorized = $true
    }
    $unit.status = switch ($Outcome) { 'Green' { 'green' } 'NoPatchNeeded' { 'no_patch_needed' } 'Hold' { 'hold' } 'Failed' { 'failed' } }
    $record = [ordered]@{ runId = $RunId; outcome = $Outcome; evidenceSha256 = $evidence.hash; focusedDurationMs = [int64]$evidence.value.focusedDurationMs; changedPaths = @($evidence.value.changedPaths); postimages = @($evidence.value.postimages) }
    if ($WorkUnitId -ceq 'WU-FINAL') { $record.finalProofArtifacts = @($evidence.finalProofArtifacts) }
    Set-ObjectProperty $unit 'record' $record
    Set-ObjectProperty $unit 'leaseReleasePending' $true
    $phaseOneHash = Write-UpdatedState $validated.state $validated.hash
    Invoke-SourceSession 'end' $WorkUnitId $RunId
    $unit.leaseReleasePending = $false
    $unit.PSObject.Properties.Remove('activeRun')
    $newHash = Write-UpdatedState $validated.state $phaseOneHash
    $null = Write-ProgramEvent 'Record' $WorkUnitId $RunId $unit.status $evidence.hash
    Write-ActionResult 'work-unit-recorded' $WorkUnitId $newHash
}

function Write-ReleaseLeaf {
    param([string]$Destination, [byte[]]$Bytes, [string]$ExpectedHash)
    if ((Get-Sha256 $Bytes) -cne $ExpectedHash) { throw 'archive-source-hash-mismatch' }
    if (Test-Path -LiteralPath $Destination) {
        if ((Get-FileHashBounded $Destination 1MB 'archive-file-too-large') -cne $ExpectedHash) { throw 'archive-hash-mismatch' }
        return
    }
    $null = Publish-BoundedBytesAtomically $Destination $Bytes $null
    if ((Get-FileHashBounded $Destination 1MB 'archive-file-too-large') -cne $ExpectedHash) { throw 'archive-hash-mismatch' }
}

function Ensure-ReleaseMetadataLeaf {
    param([string]$Destination, [byte[]]$Bytes, [switch]$ValidateOnly)
    $expected = Get-Sha256 $Bytes
    if (Test-Path -LiteralPath $Destination) {
        if ((Get-FileHashBounded $Destination 1MB 'release-artifact-too-large') -cne $expected) { throw 'release-artifact-mismatch' }
        return $expected
    }
    if ($ValidateOnly) { throw 'release-artifact-missing' }
    $null = Publish-BoundedBytesAtomically $Destination $Bytes $null
    return $expected
}

function Get-DeterministicChangelog {
    param($StateObject, [string]$ReleaseId, [int]$IncludedCount)
    $lines = New-Object 'Collections.Generic.List[string]'
    $lines.Add("# Consolidated directive release $ReleaseId")
    $lines.Add('')
    $lines.Add("releaseId: $ReleaseId")
    $lines.Add("includedCount: $IncludedCount")
    $lines.Add("heldCount: $(@($StateObject.retirement.items|Where-Object{$_.eligibility -ceq 'hold'}).Count)")
    $lines.Add("excludedCount: $(@($StateObject.retirement.items|Where-Object{$_.eligibility -ceq 'excluded'}).Count)")
    foreach ($unit in @($StateObject.workUnits)) {
        $duration = if (Test-HasProperty $unit 'record') { [string]$unit.record.focusedDurationMs } else { 'n/a' }
        $pre = if (Test-HasProperty $unit 'targetPreimages') { (@($unit.targetPreimages|ForEach-Object{"$($_.path)=$($_.sha256)"}) -join ',') } else { '' }
        $post = if (Test-HasProperty $unit 'record') { (@($unit.record.postimages|ForEach-Object{"$($_.path)=$($_.sha256)"}) -join ',') } else { '' }
        $green = if (Test-HasProperty $unit 'greenCommands') { @($unit.greenCommands) -join ' | ' } else { '' }
        $rollback = if (Test-HasProperty $unit 'rollback') { [string]$unit.rollback } else { '' }
        $unresolved = if ($unit.status -cin @('green','no_patch_needed')) { 'none' } else { "status=$($unit.status)" }
        $lines.Add("- workUnitId=$($unit.workUnitId); result=$($unit.status); greenCommands=$green; focusedDurationMs=$duration; preimages=$pre; postimages=$post; rollback=$rollback; unresolvedEvidence=$unresolved")
    }
    $lines.Add('Retired: 0')
    $lines.Add('Rollback: restore exact archived leaf by SHA-256.')
    $lines.Add('')
    return ($lines -join [Environment]::NewLine)
}

function Get-ReleaseRoot {
    Assert-SafeIdentifier $RunId 'invalid-release-id'
    $base = Join-Path (Split-Path $state -Parent) 'releases'
    $releaseRoot = Get-FullPath $RunId $base
    if (-not (Test-InRoot $base $releaseRoot)) { throw 'invalid-release-id' }
    return $releaseRoot
}

function Invoke-Release {
    $script:controllerStage = 'release-gate'
    $validated = Get-ValidatedState
    $finalMatches = @($validated.state.workUnits | Where-Object { $_.workUnitId -ceq 'WU-FINAL' })
    if ($finalMatches.Count -ne 1) { throw 'final-proof-incomplete' }
    $finalUnit = $finalMatches[0]
    if ($finalUnit.status -cne 'green' -or -not (Test-HasProperty $finalUnit 'record') -or
        [string]$validated.state.desktopFinalProof -cne 'verified' -or $validated.state.deleteAuthorized -ne $true -or
        -not (Test-HasProperty $finalUnit.record 'finalProofArtifacts')) { throw 'final-proof-incomplete' }
    $releaseRoot = Get-ReleaseRoot
    $ready = Join-Path $releaseRoot '.ready'
    if ($WhatIfPreference) { Write-ActionResult 'whatif' $null $validated.hash; return }
    $isReady = Test-Path -LiteralPath $ready -PathType Leaf
    if (-not (Test-Path -LiteralPath $releaseRoot)) { New-Item -ItemType Directory -Path $releaseRoot -Force -ErrorAction Stop | Out-Null }
    Assert-NoReparse $root $releaseRoot
    $archiveRows = @()
    $script:controllerStage = 'release-archive'
    foreach ($item in @($validated.state.retirement.items | Where-Object { $_.eligibility -ceq 'eligible' })) {
        $relative = ConvertTo-ValidatedRelativePath ([string]$item.path) 'invalid-retirement-path'
        $archive = Join-Path $releaseRoot ('archive\' + $relative.Replace('/', '\'))
        if (Test-Path -LiteralPath $archive -PathType Leaf) {
            $archiveFile = Read-BoundedFile $archive 1MB 'archive-file-too-large'
            if ($archiveFile.hash -cne [string]$item.sha256) { throw 'archive-hash-mismatch' }
        } else {
            if ($isReady) { throw 'release-artifact-missing' }
            $source = Get-FullPath $relative $root
            $archiveFile = Read-BoundedFile $source 1MB 'retirement-input-too-large' 'retirement-input-missing'
            if ($archiveFile.hash -cne [string]$item.sha256) { throw 'retirement-preimage-changed' }
            Write-ReleaseLeaf $archive $archiveFile.bytes $archiveFile.hash
        }
        $archiveRows += ,[ordered]@{ path = $relative; sha256 = $archiveFile.hash; bytes = $archiveFile.bytes.Length }
    }
    $retirementBytes = $Utf8.GetBytes(($validated.state.retirement | ConvertTo-Json -Depth 20 -Compress))
    $script:controllerStage = 'release-metadata'
    $null = Ensure-ReleaseMetadataLeaf (Join-Path $releaseRoot 'retirement-manifest.json') $retirementBytes -ValidateOnly:$isReady
    $releaseManifest = [ordered]@{ schemaVersion = 'awx.notebook.directive.release.v2'; releaseId = $RunId; canonicalMarkdownSha256 = $validated.manifest.hash; finalStateSha256 = $validated.hash; finalEvidenceSha256 = [string]$finalUnit.record.evidenceSha256; finalProofArtifacts = @($finalUnit.record.finalProofArtifacts); archiveItems = $archiveRows }
    $releaseBytes = $Utf8.GetBytes(($releaseManifest | ConvertTo-Json -Depth 20 -Compress))
    $releaseManifestPath = Join-Path $releaseRoot 'release-manifest.json'
    $releaseManifestHash = Ensure-ReleaseMetadataLeaf $releaseManifestPath $releaseBytes -ValidateOnly:$isReady
    $changelog = Get-DeterministicChangelog $validated.state $RunId $archiveRows.Count
    $null = Ensure-ReleaseMetadataLeaf (Join-Path $releaseRoot 'CHANGELOG.md') $Utf8.GetBytes($changelog) -ValidateOnly:$isReady
    $tag = @('awx-notebook-directives-20260807-r1', "releaseManifestSha256=$releaseManifestHash", 'proposalOnly=true', '') -join [Environment]::NewLine
    $null = Ensure-ReleaseMetadataLeaf (Join-Path $releaseRoot 'tag-proposal.txt') $Utf8.GetBytes($tag) -ValidateOnly:$isReady
    $script:controllerStage = 'release-event'
    $null = Write-ProgramEvent 'Release' '' $RunId 'prepared' $releaseManifestHash
    $script:controllerStage = 'release-marker'
    $null = Ensure-ReleaseMetadataLeaf $ready $Utf8.GetBytes(('ready' + [Environment]::NewLine)) -ValidateOnly:$isReady
    Write-ActionResult $(if($isReady){'release-already-ready'}else{'release-created'}) $null $validated.hash
    $script:controllerStage = $null
}

function Get-ValidatedReleaseManifest {
    param([string]$ReleaseRoot, $ValidatedState)
    $file = Read-BoundedFile (Join-Path $ReleaseRoot 'release-manifest.json') 1MB 'release-manifest-too-large' 'release-manifest-missing'
    $value = ConvertFrom-StrictJson $file.text 'release-manifest-malformed'
    Assert-ClosedProperties $value @('schemaVersion','releaseId','canonicalMarkdownSha256','finalStateSha256','finalEvidenceSha256','finalProofArtifacts','archiveItems') @() 'release-manifest-schema'
    if ($value.schemaVersion -cne 'awx.notebook.directive.release.v2' -or $value.releaseId -cne $RunId -or
        $value.canonicalMarkdownSha256 -cne $ValidatedState.manifest.hash -or $value.finalStateSha256 -cne $ValidatedState.hash -or
        $value.finalEvidenceSha256 -isnot [string] -or $value.finalEvidenceSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'release-manifest-mismatch' }
    $finalUnit = Get-Unit $ValidatedState.state 'WU-FINAL'
    if (-not (Test-HasProperty $finalUnit 'record') -or $value.finalEvidenceSha256 -cne [string]$finalUnit.record.evidenceSha256 -or
        -not (Test-SameJson @($value.finalProofArtifacts) @($finalUnit.record.finalProofArtifacts))) { throw 'release-manifest-mismatch' }
    Assert-JsonArray $value.finalProofArtifacts 'release-manifest-schema' 16
    foreach ($proof in @($value.finalProofArtifacts)) { Assert-ClosedProperties $proof @('proofKind','sha256') @() 'release-manifest-schema' }
    Assert-JsonArray $value.archiveItems 'release-manifest-schema' $InventoryLimit
    foreach ($archive in @($value.archiveItems)) { Assert-ClosedProperties $archive @('path','sha256','bytes') @() 'release-manifest-schema' }
    return [pscustomobject]@{ value=$value; hash=$file.hash }
}

function Get-RetirementNativeType {
    $type = ('AwxRetirementNative' -as [type])
    if ($null -ne $type) { return $type }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class AwxRetirementNative {
  const uint GENERIC_READ=0x80000000, DELETE=0x00010000, FILE_SHARE_READ=1, OPEN_EXISTING=3, FILE_ATTRIBUTE_NORMAL=0x80;
  [StructLayout(LayoutKind.Sequential)] struct FILE_DISPOSITION_INFO { [MarshalAs(UnmanagedType.Bool)] public bool DeleteFile; }
  [StructLayout(LayoutKind.Sequential)] struct BY_HANDLE_FILE_INFORMATION { public uint FileAttributes; public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime,LastAccessTime,LastWriteTime; public uint VolumeSerialNumber,FileSizeHigh,FileSizeLow,NumberOfLinks,FileIndexHigh,FileIndexLow; }
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern SafeFileHandle CreateFileW(string n,uint a,uint s,IntPtr sec,uint c,uint f,IntPtr t);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetFileInformationByHandle(SafeFileHandle h,int cls,ref FILE_DISPOSITION_INFO i,uint size);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle h,out BY_HANDLE_FILE_INFORMATION i);
  public static SafeFileHandle Open(string path){var h=CreateFileW(path,GENERIC_READ|DELETE,FILE_SHARE_READ,IntPtr.Zero,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,IntPtr.Zero);if(h.IsInvalid)throw new Win32Exception(Marshal.GetLastWin32Error());return h;}
  public static string Identity(SafeFileHandle h){BY_HANDLE_FILE_INFORMATION i;if(!GetFileInformationByHandle(h,out i))throw new Win32Exception(Marshal.GetLastWin32Error());return i.VolumeSerialNumber.ToString("X8")+":"+i.FileIndexHigh.ToString("X8")+i.FileIndexLow.ToString("X8");}
  public static uint LinkCount(SafeFileHandle h){BY_HANDLE_FILE_INFORMATION i;if(!GetFileInformationByHandle(h,out i))throw new Win32Exception(Marshal.GetLastWin32Error());return i.NumberOfLinks;}
  public static long Length(SafeFileHandle h){BY_HANDLE_FILE_INFORMATION i;if(!GetFileInformationByHandle(h,out i))throw new Win32Exception(Marshal.GetLastWin32Error());return ((long)i.FileSizeHigh<<32)|i.FileSizeLow;}
  public static void SetDelete(SafeFileHandle h,bool value){var i=new FILE_DISPOSITION_INFO{DeleteFile=value};if(!SetFileInformationByHandle(h,4,ref i,(uint)Marshal.SizeOf(i)))throw new Win32Exception(Marshal.GetLastWin32Error());}
}
'@ -ErrorAction Stop
    return [AwxRetirementNative]
}

function Invoke-RetirementPreDispositionGate { param($OpenLeaves) }

function Remove-EligibleLeavesAllOrNone {
    param($Eligible)
    try { $native = Get-RetirementNativeType } catch { throw 'retirement-native-load-failed' }
    $opened = New-Object 'Collections.Generic.List[object]'
    $marked = New-Object 'Collections.Generic.List[object]'
    $committed = $false
    $stage = 'open'
    try {
        foreach ($item in @($Eligible)) {
            $source = Get-FullPath ([string]$item.path) $root
            Assert-NoReparse $root $source
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'retirement-target-not-leaf' }
            $handle = $native::Open($source)
            $stage = 'hash'
            try { $stream = [IO.FileStream]::new($handle, [IO.FileAccess]::Read) } catch { $handle.Dispose(); throw }
            try {
                if ($stream.Length -gt 1MB) { throw 'retirement-input-too-large' }
                $sha = [Security.Cryptography.SHA256]::Create()
                try { $actual = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '') } finally { $sha.Dispose() }
                if ($actual -cne [string]$item.sha256) { throw 'retirement-preimage-changed' }
                $opened.Add([pscustomobject]@{ path=$source; stream=$stream; identity=$native::Identity($stream.SafeFileHandle) })
            } catch { $stream.Dispose(); throw }
        }
        $stage = 'gate'
        Invoke-RetirementPreDispositionGate $opened.ToArray()
        $stage = 'disposition'
        foreach ($leaf in $opened.ToArray()) {
            $native::SetDelete($leaf.stream.SafeFileHandle, $true)
            $marked.Add($leaf)
        }
        $committed = $true
    } catch {
        foreach ($leaf in $marked.ToArray()) { try { $native::SetDelete($leaf.stream.SafeFileHandle, $false) } catch {} }
        if ([string]$_.Exception.Message -notmatch '^[a-z0-9-]+$') { throw ("retirement-native-$stage-failed") }
        throw
    } finally {
        foreach ($leaf in $opened.ToArray()) { try { $leaf.stream.Dispose() } catch { if($committed){throw 'retirement-native-close-failed'} } }
    }
    if ($committed) { try { foreach ($leaf in $opened.ToArray()) { if (Test-Path -LiteralPath $leaf.path) { throw 'retirement-delete-incomplete' } } } catch { if($_.Exception.Message -eq 'retirement-delete-incomplete'){throw};throw 'retirement-native-absence-failed' } }
}

function Invoke-Retire {
    $validated = Get-ValidatedState
    foreach ($unit in @($validated.state.workUnits | Where-Object { $_.required -eq $true })) {
        if ($unit.status -cnotin @('green', 'no_patch_needed')) { throw 'retirement-unit-incomplete' }
    }
    if ([string]$validated.state.desktopFinalProof -cne 'verified' -or $validated.state.deleteAuthorized -ne $true) { throw 'desktop-proof-missing' }
    $releaseRoot = Get-ReleaseRoot
    if (-not (Test-Path -LiteralPath (Join-Path $releaseRoot '.ready') -PathType Leaf)) { throw 'release-not-ready' }
    try { $releaseManifest = Get-ValidatedReleaseManifest $releaseRoot $validated } catch {
        if ([string]$_.Exception.Message -match '^[a-z0-9-]+$') { throw }
        throw 'release-manifest-validation-failed'
    }
    $eligible = @($validated.state.retirement.items | Where-Object { $_.eligibility -ceq 'eligible' })
    foreach ($item in $eligible) {
        $relative = ConvertTo-ValidatedRelativePath ([string]$item.path) 'invalid-retirement-path'
        $archive = Join-Path $releaseRoot ('archive\' + $relative.Replace('/', '\'))
        if ((Get-FileHashBounded $archive 1MB 'archive-file-too-large') -cne [string]$item.sha256) { throw 'archive-hash-mismatch' }
        $source = Get-FullPath $relative $root
        Assert-NoReparse $root $source
        if (Test-Path -LiteralPath $source) {
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'retirement-target-not-leaf' }
            if ((Get-FileHashBounded $source 1MB 'retirement-input-too-large') -cne [string]$item.sha256) { throw 'retirement-preimage-changed' }
        }
    }
    if (-not $ConfirmRetirement) { Write-ActionResult 'retirement-dry-run' $null $validated.hash; return }
    if ($WhatIfPreference) { Write-ActionResult 'retirement-whatif' $null $validated.hash; return }
    try { Remove-EligibleLeavesAllOrNone $eligible } catch {
        if ([string]$_.Exception.Message -match '^[a-z0-9-]+$') { throw }
        throw 'retirement-native-finalize-failed'
    }
    try { $null = Write-ProgramEvent 'Retire' '' $RunId 'complete' $releaseManifest.hash } catch {
        if ([string]$_.Exception.Message -match '^[a-z0-9-]+$') { throw }
        throw 'retirement-event-failed'
    }
    Write-ActionResult 'retirement-complete' $null $validated.hash
}

function Assert-PublicMutationBoundary {
    param([string]$SuppliedRoot, [AllowNull()][string]$SuppliedProgramPath, [AllowNull()][string]$SuppliedStatePath)

    try {
        $expectedRoot = $CanonicalRoot.TrimEnd('\\')
        $candidateRoot = [IO.Path]::GetFullPath($SuppliedRoot).TrimEnd('\\')
        if (-not $candidateRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsupported-root' }

        $programRelative = 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md'
        $stateRelative = 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json'
        $expectedProgram = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $programRelative))
        $expectedState = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $stateRelative))
        $candidateProgram = if ([string]::IsNullOrWhiteSpace($SuppliedProgramPath)) { $expectedProgram } elseif ([IO.Path]::IsPathRooted($SuppliedProgramPath)) { [IO.Path]::GetFullPath($SuppliedProgramPath) } else { [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $SuppliedProgramPath)) }
        $candidateState = if ([string]::IsNullOrWhiteSpace($SuppliedStatePath)) { $expectedState } elseif ([IO.Path]::IsPathRooted($SuppliedStatePath)) { [IO.Path]::GetFullPath($SuppliedStatePath) } else { [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $SuppliedStatePath)) }
        if (-not $candidateProgram.Equals($expectedProgram, [StringComparison]::OrdinalIgnoreCase) -or -not $candidateState.Equals($expectedState, [StringComparison]::OrdinalIgnoreCase)) { throw 'unsupported-root' }
        return [pscustomobject][ordered]@{ root = $expectedRoot; program = $expectedProgram; state = $expectedState }
    } catch {
        throw 'unsupported-root'
    }
}

function Get-PublicCoreLeaseEnvelope {
    param($Request)

    if ([string]$Request.action -cnotin @('begin','status') -or [string]$Request.root -cne $CanonicalRoot -or [string]$Request.role -cne 'desktop') { throw 'invalid-lease-request' }
    Assert-SafeIdentifier ([string]$Request.topic) 'invalid-lease-request'
    Assert-SafeIdentifier ([string]$Request.ownerId) 'invalid-lease-request'
    $leasePath = Join-Path $CanonicalRoot ('__patch_drop__\source-edit-locks\' + [string]$Request.topic + '.lock\lease.json')
    Assert-NoReparse $CanonicalRoot $leasePath
    if (-not (Test-Path -LiteralPath $leasePath -PathType Leaf)) { throw 'lease-not-active' }
    $lease = ConvertFrom-StrictJson (Read-BoundedFile $leasePath 64KB 'source-lease-too-large' 'lease-not-active').text 'invalid-lease-schema'
    Assert-ClosedProperties $lease @('schemaVersion','generatedAt','startedAtUtc','topic','role','ownerId','root','expiresAtUtc','expiresAt','mutationAllowed') @() 'invalid-lease-schema'
    if ($lease.expiresAtUtc -isnot [string] -or $lease.expiresAt -isnot [string]) { throw 'invalid-lease-schema' }
    $expiresAtUtc = [DateTimeOffset]::MinValue
    $expiresAt = [DateTimeOffset]::MinValue
    $styles = [Globalization.DateTimeStyles]::RoundtripKind
    if (-not [DateTimeOffset]::TryParse([string]$lease.expiresAtUtc, [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$expiresAtUtc) -or
        -not [DateTimeOffset]::TryParse([string]$lease.expiresAt, [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$expiresAt) -or
        $expiresAtUtc.ToUniversalTime() -ne $expiresAt.ToUniversalTime()) { throw 'invalid-lease-schema' }
    $remaining = $expiresAtUtc.ToUniversalTime() - [DateTimeOffset]::UtcNow
    $status = if ($remaining -le [TimeSpan]::Zero) { 'expired' } elseif ($remaining -le [TimeSpan]::FromSeconds(60)) { 'expiring' } else { 'active' }
    return [pscustomobject][ordered]@{ status = $status; lease = $lease }
}

function Get-PublicCoreLeaseAbsenceEnvelope {
    param($Request)

    if ([string]$Request.action -cne 'end' -or [string]$Request.root -cne $CanonicalRoot -or [string]$Request.role -cne 'desktop') {
        throw 'invalid-lease-request'
    }
    Assert-SafeIdentifier ([string]$Request.topic) 'invalid-lease-request'
    Assert-SafeIdentifier ([string]$Request.ownerId) 'invalid-lease-request'
    $leaseDirectory = Join-Path $CanonicalRoot ('__patch_drop__\source-edit-locks\' + [string]$Request.topic + '.lock')
    Assert-NoReparse $CanonicalRoot $leaseDirectory
    return [pscustomobject][ordered]@{
        isAbsent = -not (Test-Path -LiteralPath $leaseDirectory)
        topic = [string]$Request.topic
        ownerId = [string]$Request.ownerId
        root = $CanonicalRoot
    }
}

function Get-PublicGitCommand {
    $git = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($null -eq $git) { $git = Get-Command git -ErrorAction SilentlyContinue }
    if ($null -eq $git) { throw 'dirty-target-evidence-needed' }
    return $git
}

function Assert-PublicGitIndexUnlocked {
    param($Git)

    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $rootRows = @(& $Git.Source -C $CanonicalRoot rev-parse --show-toplevel 2>$null)
        $rootExit = $LASTEXITCODE
        $indexRows = @(& $Git.Source -C $CanonicalRoot rev-parse --git-path index.lock 2>$null)
        $indexExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prior }
    if ($rootExit -ne 0 -or $rootRows.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$rootRows[0]) -or
        $indexExit -ne 0 -or $indexRows.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$indexRows[0])) { throw 'dirty-target-evidence-needed' }
    $gitRoot = [IO.Path]::GetFullPath([string]$rootRows[0]).TrimEnd('\')
    if (-not $gitRoot.Equals($CanonicalRoot.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) { throw 'dirty-target-evidence-needed' }
    $indexLock = [string]$indexRows[0]
    if (-not [IO.Path]::IsPathRooted($indexLock)) { $indexLock = Join-Path $CanonicalRoot $indexLock }
    $indexLock = [IO.Path]::GetFullPath($indexLock)
    Assert-NoReparse $CanonicalRoot $indexLock
    if (Test-Path -LiteralPath $indexLock) { throw 'index-lock-present' }
}

function Assert-PublicTargetGitClean {
    param([string[]]$RelativePaths)

    $git = Get-PublicGitCommand
    Assert-PublicGitIndexUnlocked $git
    if ($RelativePaths.Count -eq 0) { return }
    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $dirtyRows = @(& $git.Source -C $CanonicalRoot status --porcelain=v1 --untracked-files=all --ignored=matching -- @RelativePaths 2>$null)
        $gitExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prior }
    if ($gitExit -ne 0) { throw 'dirty-target-evidence-needed' }
    if ($dirtyRows.Count -gt 0) { throw 'dirty-target-overlap' }
}

function Get-PublicCoreTargetSnapshot {
    param($Request)

    if ([string]$Request.root -cne $CanonicalRoot -or $Request.paths -isnot [Array] -or [string]$Request.mode -cnotin @('preimage', 'postimage')) { throw 'invalid-target-snapshot' }
    $relativePaths = @()
    foreach ($pathText in @($Request.paths)) {
        $relative = ConvertTo-ValidatedRelativePath ([string]$pathText) 'invalid-target-file'
        $full = Get-FullPath $relative $CanonicalRoot
        if (-not (Test-InRoot $CanonicalRoot $full)) { throw 'invalid-target-file' }
        Assert-NoReparse $CanonicalRoot $full
        $relativePaths += ,$relative
    }
    if ([string]$Request.mode -ceq 'preimage') { Assert-PublicTargetGitClean $relativePaths }
    $rows = @()
    foreach ($relative in $relativePaths) {
        $full = Get-FullPath $relative $CanonicalRoot
        if (Test-Path -LiteralPath $full) {
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw 'invalid-target-file' }
            $file = Read-BoundedFile $full 16MB 'target-too-large' 'target-file-missing'
            $rows += [pscustomobject][ordered]@{ path = $relative; exists = $true; bytes = [int64]$file.bytes.Length; sha256 = $file.hash; resolvedPathContained = $true; ancestorNonReparse = $true; reparseTraversal = $false; leafKind = 'leaf' }
        } else {
            $rows += [pscustomobject][ordered]@{ path = $relative; exists = $false; bytes = [int64]0; sha256 = $null; resolvedPathContained = $true; ancestorNonReparse = $true; reparseTraversal = $false; leafKind = 'missing' }
        }
    }
    if ([string]$Request.mode -ceq 'preimage') { Assert-PublicTargetGitClean $relativePaths }
    return [pscustomobject][ordered]@{ schemaVersion = 'awx.notebook.directive.target-snapshot.v1'; canonicalRoot = $CanonicalRoot; rows = @($rows) }
}

function Read-PublicCoreEnvelope {
    param($Request, [int64]$Limit, [switch]$ParseJson)

    if ([string]$Request.root -cne $CanonicalRoot -or -not ($Request.maxBytes -is [int] -or $Request.maxBytes -is [long]) -or [int64]$Request.maxBytes -ne $Limit) { throw 'invalid-artifact-request' }
    $relative = ConvertTo-ValidatedRelativePath ([string]$Request.path) 'invalid-artifact-request'
    $full = Get-FullPath $relative $CanonicalRoot
    if (-not (Test-InRoot $CanonicalRoot $full)) { throw 'invalid-artifact-request' }
    Assert-NoReparse $CanonicalRoot $full
    $file = Read-BoundedFile $full $Limit 'artifact-too-large' 'artifact-missing'
    $result = [ordered]@{ sha256 = $file.hash; bytes = [int64]$file.bytes.Length; containedRepositoryLeaf = $true; nonReparseLeaf = $true }
    if ($ParseJson) { $result = [ordered]@{ value = (ConvertFrom-StrictJson $file.text 'malformed-evidence'); sha256 = $file.hash; bytes = [int64]$file.bytes.Length; containedRepositoryLeaf = $true; nonReparseLeaf = $true } }
    return [pscustomobject]$result
}

function Get-PublicCoreAdapters {
    return [pscustomobject][ordered]@{
        CompareAndSwapState = { param($ExpectedHash, $StateObject, $Phase) Write-UpdatedState $StateObject $ExpectedHash $Phase }
        AdoptStateAndEvents = { param($ExpectedHash, $StateObject, $ExpectedTargetRows, $Topic, $OwnerId) Write-AdoptedStateAndEvents $StateObject $ExpectedHash $ExpectedTargetRows $Topic $OwnerId }
        HashImageArray = { param($Rows) Get-CanonicalJsonHash @($Rows) 'image-array' }
        AcquireSession = { param($Request) if ([string]$Request.action -cne 'begin') { throw 'invalid-lease-request' }; Invoke-SourceSession 'begin' ([string]$Request.topic) ([string]$Request.ownerId); Get-PublicCoreLeaseEnvelope $Request }
        ReadSession = { param($Request) if ([string]$Request.action -cnotin @('begin', 'status')) { throw 'invalid-lease-request' }; Get-PublicCoreLeaseEnvelope $Request }
        ReleaseSession = { param($Request) if ([string]$Request.action -cne 'end') { throw 'invalid-lease-request' }; Invoke-SourceSession 'end' ([string]$Request.topic) ([string]$Request.ownerId) }
        TestLeaseAbsent = { param($Request) Get-PublicCoreLeaseAbsenceEnvelope $Request }
        CaptureTargets = { param($Request) Get-PublicCoreTargetSnapshot $Request }
        ReadEvidence = { param($Request) Read-PublicCoreEnvelope $Request 1MB -ParseJson }
        ReadArtifact = { param($Request) Read-PublicCoreEnvelope $Request 16MB }
    }
}

function Invoke-ControllerMain {
    try {
        if ($Action -cin @('Begin', 'Record', 'Adopt', 'Release', 'Retire')) {
            $boundary = Assert-PublicMutationBoundary $Root $ProgramPath $StatePath
            $script:root = $boundary.root
            $script:program = $boundary.program
            $script:state = $boundary.state
            if ($Action -eq 'Begin') {
                if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $null; return }
                $null = Get-ValidatedState
                $null = Sync-EventLedger
                $validated = Get-ValidatedState
                $effectiveWorkUnitId = $WorkUnitId
                if ([string]::IsNullOrWhiteSpace($effectiveWorkUnitId)) {
                    $runningMatches = @($validated.state.workUnits | Where-Object {
                        [string]$_.status -ceq 'running' -and (Test-HasProperty $_ 'execution') -and
                        -not (Test-HasProperty $_ 'transition') -and [string]$_.execution.runId -ceq $RunId
                    })
                    if ($runningMatches.Count -eq 1) { $effectiveWorkUnitId = [string]$runningMatches[0].workUnitId }
                }
                $wasRunningReplay = @($validated.state.workUnits | Where-Object {
                    [string]$_.workUnitId -ceq [string]$effectiveWorkUnitId -and [string]$_.status -ceq 'running' -and
                    (Test-HasProperty $_ 'execution') -and -not (Test-HasProperty $_ 'transition') -and
                    [string]$_.execution.runId -ceq $RunId
                }).Count -eq 1
                try {
                    try {
                        $result = Invoke-ConsolidatedProgramBeginCore -State $validated.state -StateSha256 $validated.hash -CanonicalMarkdownSha256 $validated.manifest.hash -RunId $RunId -WorkUnitId $effectiveWorkUnitId -Adapters (Get-PublicCoreAdapters)
                    } catch {
                        $failure = $_
                        $committedContext = if ($wasRunningReplay) { $null } else { Get-CommittedMutationContext 'Begin' $effectiveWorkUnitId $RunId $null }
                        if ($null -ne $committedContext) {
                            Set-EventCommittedContext $committedContext.workUnitId $committedContext.stateSha256 $committedContext.canonicalMarkdownSha256
                            $failureReason = [string]$failure.Exception.Message
                            if ($failureReason -cin $EventReasonCodes) { throw $failureReason }
                            throw 'event-publication-pending'
                        }
                        throw $failure
                    }
                } finally { Exit-StateCasLock }
                Set-EventCommittedContext ([string]$result.workUnitId) ([string]$result.stateSha256) ([string]$validated.manifest.hash)
                $published = Sync-EventLedger
                $publishedRows = @(Get-ExpectedEventRows $published.state $published.manifest)
                $publishedRunHash = Get-EventRunIdHash $RunId
                $publishedBeginMatches = @($publishedRows | Where-Object {
                    [string]$_.action -ceq 'Begin' -and [string]$_.workUnitId -ceq [string]$result.workUnitId -and
                    [string]$_.runIdSha256 -ceq $publishedRunHash
                })
                if ($publishedBeginMatches.Count -ne 1) { throw 'event-publication-pending' }
                $result.stateSha256 = [string]$published.hash
                Write-Output ($result | ConvertTo-Json -Compress)
                return
            }
            if ($Action -eq 'Adopt') {
                if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $null; return }
                $null = Get-ValidatedState
                $null = Sync-EventLedger
                $validated = Get-ValidatedState
                try {
                    $result = Invoke-ConsolidatedProgramAdoptCore -State $validated.state -StateSha256 $validated.hash `
                        -CanonicalMarkdownSha256 $validated.manifest.hash -WorkUnitId $WorkUnitId -RunId $RunId `
                        -EvidencePath $EvidencePath -ExpectedEvidenceSha256 $ExpectedEvidenceSha256 `
                        -ExpectedTargetPostimageSha256 $ExpectedTargetPostimageSha256 -Adapters (Get-PublicCoreAdapters)
                } finally { Exit-StateCasLock }
                Set-EventCommittedContext ([string]$result.workUnitId) ([string]$result.stateSha256) ([string]$validated.manifest.hash)
                $published = Get-ValidatedReadOnlyEventState
                $publishedRows = @(Get-ExpectedEventRows $published.state $published.manifest)
                $publishedRunHash = Get-EventRunIdHash $RunId
                $publishedMatches = @($publishedRows | Where-Object {
                    [string]$_.action -ceq 'Record' -and [string]$_.workUnitId -ceq [string]$result.workUnitId -and
                    [string]$_.runIdSha256 -ceq $publishedRunHash -and [string]$_.result -ceq 'no_patch_needed' -and
                    [string]$_.evidenceSha256 -ceq $ExpectedEvidenceSha256
                })
                if ($publishedMatches.Count -ne 1) { throw 'event-publication-pending' }
                $result.stateSha256 = [string]$published.hash
                Write-Output ($result | ConvertTo-Json -Compress)
                return
            }
            if ($Action -eq 'Record') {
                if ($WhatIfPreference) { Write-ActionResult 'whatif' $WorkUnitId $null; return }
                $null = Get-ValidatedState
                $null = Sync-EventLedger
                $validated = Get-ValidatedState
                try {
                    try {
                        $result = Invoke-ConsolidatedProgramRecordCore -State $validated.state -StateSha256 $validated.hash -CanonicalMarkdownSha256 $validated.manifest.hash -WorkUnitId $WorkUnitId -RunId $RunId -Outcome $Outcome -EvidencePath $EvidencePath -Adapters (Get-PublicCoreAdapters)
                    } catch {
                        $failure = $_
                        $committedContext = Get-CommittedMutationContext 'Record' $WorkUnitId $RunId $Outcome
                        if ($null -ne $committedContext) {
                            Set-EventCommittedContext $committedContext.workUnitId $committedContext.stateSha256 $committedContext.canonicalMarkdownSha256
                            $failureReason = [string]$failure.Exception.Message
                            if ($failureReason -cin $EventReasonCodes) { throw $failureReason }
                            throw 'event-publication-pending'
                        }
                        throw $failure
                    }
                } finally { Exit-StateCasLock }
                Set-EventCommittedContext ([string]$result.workUnitId) ([string]$result.stateSha256) ([string]$validated.manifest.hash)
                $published = Sync-EventLedger
                $publishedRows = @(Get-ExpectedEventRows $published.state $published.manifest)
                $publishedRunHash = Get-EventRunIdHash $RunId
                $publishedRecordResult = Get-EventResultFromOutcome $Outcome
                $publishedRecordMatches = @($publishedRows | Where-Object {
                    [string]$_.action -ceq 'Record' -and [string]$_.workUnitId -ceq [string]$result.workUnitId -and
                    [string]$_.runIdSha256 -ceq $publishedRunHash -and [string]$_.result -ceq $publishedRecordResult
                })
                if ($publishedRecordMatches.Count -ne 1) { throw 'event-publication-pending' }
                $result.stateSha256 = [string]$published.hash
                Write-Output ($result | ConvertTo-Json -Compress)
                return
            }
            if ($Action -in @('Release', 'Retire')) { throw 'action-not-implemented' }
        }
        $rootContext = Get-FileSystemRootContext $Root
        $script:root = $rootContext.path
        $script:program = Get-FullPath $(if ($ProgramPath) { $ProgramPath } else { 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md' }) $root
        $script:state = Get-FullPath $(if ($StatePath) { $StatePath } else { 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json' }) $root
        if (-not (Test-InRoot $root $program)) { throw 'program-path-outside-root' }
        if (-not (Test-InRoot $root $state)) { throw 'state-path-outside-root' }
        Assert-NoReparse $root $program
        Assert-NoReparse $root $state
        if ($Action -eq 'Snapshot') { Invoke-Snapshot }

        $validated = if ($Action -cin @('Status','Next')) { Get-ValidatedReadOnlyEventState } else { Get-ValidatedState }
        $units = @($validated.state.workUnits)
        $runnable = @($units | Where-Object {
            $_.status -ceq 'pending' -and
            @($_.dependencies | Where-Object {
                $dependency = $_
                $units | Where-Object { $_.workUnitId -ceq $dependency -and $_.status -cnotin @('green', 'no_patch_needed') }
            }).Count -eq 0
        })
        $output = [ordered]@{
            action = $Action
            status = 'ok'
            reason = if ($Action -eq 'Next' -and $runnable.Count -eq 0) { 'no-runnable-work-unit' } elseif ($Action -eq 'Next') { 'runnable-work-unit' } else { 'validated-state' }
            workUnitId = if ($Action -eq 'Next' -and $runnable.Count) { $runnable[0].workUnitId } else { $null }
            statePath = Get-RelativePath $root $state
            programPath = Get-RelativePath $root $program
            stateSha256 = $validated.hash
            canonicalMarkdownSha256 = $validated.manifest.hash
            workUnitCount = $units.Count
            pendingCount = @($units | Where-Object { $_.status -ceq 'pending' }).Count
            runnableCount = $runnable.Count
        }
        Write-Output ($output | ConvertTo-Json -Compress)
    } catch {
        $reason = [string]$_.Exception.Message
        if ($reason -notmatch '^[a-z0-9-]+$' -and -not [string]::IsNullOrWhiteSpace([string]$script:controllerStage)) { $reason = ([string]$script:controllerStage + '-failed') }
        if ($reason -notmatch '^[a-z0-9-]+$') { $reason = 'controller-failure' }
        Stop-Controller $reason
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-ControllerMain
}

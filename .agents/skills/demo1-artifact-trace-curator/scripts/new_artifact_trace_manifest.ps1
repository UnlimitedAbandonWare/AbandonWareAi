[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,63}$')]
    [string]$TraceId,

    [Parameter(Mandatory = $true)]
    [string]$InputRoot,

    [ValidateRange(0, 4096)]
    [int]$MaxFiles = 512,

    [ValidateRange(0, 1024)]
    [int]$MaxTotalMiB = 64,

    [ValidateRange(0, 16)]
    [int]$MaxJsonMiB = 2,

    [ValidateRange(0, 600)]
    [int]$TimeoutSeconds = 120,

    [ValidateRange(0, 16)]
    [int]$MaxOutputMiB = 1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$started = [Diagnostics.Stopwatch]::StartNew()
$temporaryOutput = $null
$finalOutput = $null
$finalMovedByThisRun = $false
$publicationComplete = $false
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$hashPattern = '^[a-f0-9]{64}$'
$allDeniedClaims = @('source_patch', 'patch_handoff', 'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')

function Throw-Trace {
    param([string]$Reason)
    throw $Reason
}

function Assert-TimeBudget {
    if ($TimeoutSeconds -eq 0 -or $started.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
        Throw-Trace 'trace-budget-exceeded'
    }
}

function Get-FullNormalizedPath {
    param([string]$Path)
    $full = [IO.Path]::GetFullPath($Path)
    if ($full.Length -gt ([IO.Path]::GetPathRoot($full)).Length) {
        $full = $full.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    }
    return $full
}

function Test-PathEqual {
    param([string]$Left, [string]$Right)
    return [string]::Equals((Get-FullNormalizedPath $Left), (Get-FullNormalizedPath $Right), [StringComparison]::OrdinalIgnoreCase)
}

function Test-StrictDescendant {
    param([string]$Candidate, [string]$Parent)
    $candidateFull = Get-FullNormalizedPath $Candidate
    $parentFull = Get-FullNormalizedPath $Parent
    $prefix = $parentFull + [IO.Path]::DirectorySeparatorChar
    return $candidateFull.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Get-RelativePathSafe {
    param([string]$Candidate, [string]$Parent)
    if (-not (Test-StrictDescendant -Candidate $Candidate -Parent $Parent)) {
        Throw-Trace 'trace-input-not-allowlisted'
    }
    $candidateFull = Get-FullNormalizedPath $Candidate
    $parentFull = Get-FullNormalizedPath $Parent
    return $candidateFull.Substring($parentFull.Length).TrimStart('\', '/').Replace('\', '/')
}

function Assert-NoReparseChain {
    param([string]$Candidate, [string]$CanonicalRoot)
    $current = Get-FullNormalizedPath $Candidate
    $rootFull = Get-FullNormalizedPath $CanonicalRoot
    if (-not ((Test-PathEqual $current $rootFull) -or (Test-StrictDescendant $current $rootFull))) {
        Throw-Trace 'trace-input-not-allowlisted'
    }
    while ($true) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Trace 'trace-reparse-risk'
            }
        }
        if (Test-PathEqual $current $rootFull) { break }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or (Test-PathEqual $parent $current)) {
            Throw-Trace 'trace-reparse-risk'
        }
        $current = $parent
    }
}

function Get-Sha256Bytes {
    param([byte[]]$Bytes)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Get-Sha256Text {
    param([string]$Value)
    return Get-Sha256Bytes -Bytes $utf8NoBom.GetBytes($Value)
}

function Get-LockedSnapshot {
    param([string]$Path, [long]$ExpectedLength)
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ($stream.Length -ne $ExpectedLength -or $stream.Length -gt [int]::MaxValue) {
            Throw-Trace 'trace-input-changed'
        }
        $bytes = New-Object byte[] ([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $read = $stream.Read($bytes, $offset, $bytes.Length - $offset)
            if ($read -le 0) { Throw-Trace 'trace-input-changed' }
            $offset += $read
        }
        return [pscustomobject]@{
            bytes = $bytes
            sha256 = Get-Sha256Bytes -Bytes $bytes
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-PropertyValue {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-HasProperty {
    param($Object, [string]$Name)
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Get-SafeVerdictValue {
    param($Value)
    $text = [string]$Value
    if ($text -match '^[A-Za-z0-9_.-]{1,64}$') { return $text }
    return 'unknown'
}

function Get-FreshnessState {
    param($Json)
    if ($null -eq $Json) { return 'unknown' }
    $state = [string](Get-PropertyValue $Json 'state')
    if ($state -ceq 'SUPERSEDED') { return 'superseded' }
    $expires = [string](Get-PropertyValue $Json 'expiresAtUtc')
    if (-not [string]::IsNullOrWhiteSpace($expires)) {
        $parsed = [DateTimeOffset]::MinValue
        if ([DateTimeOffset]::TryParse($expires, [ref]$parsed)) {
            if ($parsed -le [DateTimeOffset]::UtcNow) { return 'expired' }
            return 'current'
        }
    }
    return 'unknown'
}

function Get-SupersedesHashes {
    param($Json)
    $value = Get-PropertyValue $Json 'supersedes'
    if ($null -eq $value) { return @() }
    $hashes = @()
    foreach ($entry in @($value)) {
        $text = [string]$entry
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            $hashes += ('sha256:' + (Get-Sha256Text $text))
        }
    }
    return @($hashes | Sort-Object -Unique)
}

function Test-SecretLikeText {
    param([string]$Text)
    $patterns = @(
        '(?i)\bsk-[A-Za-z0-9_-]{16,}\b',
        '(?i)\bBearer\s+[A-Za-z0-9._~+/-]{12,}\b',
        '(?i)"?(api[_-]?key|client[_-]?secret|owner[_-]?token|authorization|password)"?\s*[:=]\s*"[^"\r\n]{8,}"'
    )
    foreach ($pattern in $patterns) {
        if ($Text -match $pattern) { return $true }
    }
    return $false
}

function Get-PostimageMap {
    param($Rows, [string]$HashField)
    $map = @{}
    foreach ($row in @($Rows)) {
        $path = ([string](Get-PropertyValue $row 'relativePath')).Replace('\', '/').Trim().ToLowerInvariant()
        $hash = ([string](Get-PropertyValue $row $HashField)).ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($path) -or $hash -notmatch $hashPattern -or $map.ContainsKey($path)) {
            return $null
        }
        $map[$path] = $hash
    }
    return $map
}

function Test-MapsEqual {
    param($Left, $Right)
    if ($null -eq $Left -or $null -eq $Right -or $Left.Count -ne $Right.Count) { return $false }
    foreach ($key in $Left.Keys) {
        if (-not $Right.ContainsKey($key) -or [string]$Left[$key] -cne [string]$Right[$key]) { return $false }
    }
    return $true
}

function Test-CompletionBinding {
    param($CompletionRecord, $Records)
    $completion = $CompletionRecord.json
    if ([string](Get-PropertyValue $completion 'state') -cne 'COMPLETE') { return $false }
    $runId = [string](Get-PropertyValue $completion 'runId')
    $sessionSha = ([string](Get-PropertyValue $completion 'sessionSha256')).ToLowerInvariant()
    $verificationSha = ([string](Get-PropertyValue $completion 'verificationEvidenceSha256')).ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($runId) -or $sessionSha -notmatch $hashPattern -or $verificationSha -notmatch $hashPattern) { return $false }
    if (-not (Test-HasProperty $completion 'undeclaredSourceChangeCount') -or
        -not (Test-HasProperty $completion 'rawSecretPatternHits') -or
        [int](Get-PropertyValue $completion 'undeclaredSourceChangeCount') -ne 0 -or
        [int](Get-PropertyValue $completion 'rawSecretPatternHits') -ne 0) { return $false }

    $sessionRecord = @($Records | Where-Object {
        $_.schemaVersion -ceq 'awx.macsrc_smb_patch_session.v2' -and
        $_.sha256 -ceq $sessionSha -and
        [string](Get-PropertyValue $_.json 'runId') -ceq $runId
    })
    $verificationRecord = @($Records | Where-Object {
        $_.schemaVersion -ceq 'awx.macsrc_smb_patch_verification.v1' -and
        $_.sha256 -ceq $verificationSha -and
        [string](Get-PropertyValue $_.json 'runId') -ceq $runId -and
        [string](Get-PropertyValue $_.json 'sessionSha256') -ceq $sessionSha
    })
    if ($sessionRecord.Count -ne 1 -or $verificationRecord.Count -ne 1) { return $false }
    if (-not (Test-HasProperty $sessionRecord[0].json 'rawSecretPatternHits') -or [int](Get-PropertyValue $sessionRecord[0].json 'rawSecretPatternHits') -ne 0) { return $false }
    if (-not (Test-HasProperty $verificationRecord[0].json 'exitCode') -or [int](Get-PropertyValue $verificationRecord[0].json 'exitCode') -ne 0) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-PropertyValue $verificationRecord[0].json 'command'))) { return $false }

    $verificationMap = Get-PostimageMap -Rows (Get-PropertyValue $verificationRecord[0].json 'targetPostimages') -HashField 'sha256'
    $completionMap = Get-PostimageMap -Rows (Get-PropertyValue $completion 'postimages') -HashField 'postimageSha256'
    return Test-MapsEqual -Left $verificationMap -Right $completionMap
}

function Get-RecordByName {
    param($Records, [string]$Name)
    $matches = @($Records | Where-Object { [string]::Equals($_.name, $Name, [StringComparison]::OrdinalIgnoreCase) })
    if ($matches.Count -eq 1) { return $matches[0] }
    return $null
}

function Test-BooleanProperty {
    param($Object, [string]$Name, [bool]$Expected)
    $value = Get-PropertyValue $Object $Name
    return $value -is [bool] -and [bool]$value -eq $Expected
}

function Test-PatchDropBundle {
    param([string]$Bundle, $Records)
    $patchName = $Bundle + '.patch'
    $reportName = $Bundle + '.report.md'
    $verifyName = $Bundle + '.verify.log'
    $shaName = $Bundle + '.sha256.txt'
    $manifestName = $Bundle + '.manifest.json'
    $patchRecord = Get-RecordByName $Records $patchName
    $reportRecord = Get-RecordByName $Records $reportName
    $verifyRecord = Get-RecordByName $Records $verifyName
    $shaRecord = Get-RecordByName $Records $shaName
    $manifestRecord = Get-RecordByName $Records $manifestName
    if ($null -eq $patchRecord -or $null -eq $reportRecord -or $null -eq $verifyRecord -or $null -eq $shaRecord -or $null -eq $manifestRecord) {
        return $false
    }
    $manifest = $manifestRecord.json
    if ($null -eq $manifest -or
        [string](Get-PropertyValue $manifest 'schemaVersion') -cne 'patchdrop-producer-v3' -or
        [string](Get-PropertyValue $manifest 'protocolVersion') -cne 'patchdrop-v3' -or
        -not (Test-BooleanProperty $manifest 'cumulative' $true) -or
        [string](Get-PropertyValue $manifest 'activePatch') -cne $patchName -or
        [string](Get-PropertyValue $manifest 'desktopFinalProof') -cne 'evidence_needed') {
        return $false
    }
    $isolation = Get-PropertyValue $manifest 'sourceIsolation'
    if ($null -eq $isolation -or
        [string](Get-PropertyValue $isolation 'guard') -cne 'PASS' -or
        [string](Get-PropertyValue $isolation 'sourceRootKind') -cne 'local-worktree' -or
        -not (Test-BooleanProperty $isolation 'sharedSourceRoot' $false) -or
        -not (Test-BooleanProperty $isolation 'desktopCanonicalSourceRoot' $false) -or
        -not (Test-BooleanProperty $isolation 'directCanonicalSourceEdit' $false) -or
        -not (Test-BooleanProperty $isolation 'gitRootPresent' $true) -or
        -not (Test-BooleanProperty $isolation 'gitRootMatchesSourceRoot' $true) -or
        [string](Get-PropertyValue $isolation 'gitRootHash') -notmatch $hashPattern) {
        return $false
    }
    $verification = Get-PropertyValue $manifest 'verification'
    if ($null -eq $verification -or -not (Test-HasProperty $verification 'secretPatternHits') -or [int](Get-PropertyValue $verification 'secretPatternHits') -ne 0) {
        return $false
    }

    $sidecarMap = @{}
    foreach ($line in @($shaRecord.text -split '\r?\n')) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([A-Fa-f0-9]{64})\s{2}(.+)$') { return $false }
        $entryHash = $Matches[1].ToLowerInvariant()
        $entryName = $Matches[2].Trim()
        if ($sidecarMap.ContainsKey($entryName)) { return $false }
        $sidecarMap[$entryName] = $entryHash
    }
    foreach ($record in @($patchRecord, $reportRecord, $verifyRecord, $manifestRecord)) {
        if (-not $sidecarMap.ContainsKey($record.name) -or [string]$sidecarMap[$record.name] -cne [string]$record.sha256) {
            return $false
        }
    }
    return $true
}

try {
    Assert-TimeBudget
    if ([IO.Path]::IsPathRooted($InputRoot)) { Throw-Trace 'trace-input-not-allowlisted' }
    $canonicalRoot = Get-FullNormalizedPath (Resolve-Path -LiteralPath $Root).Path
    Assert-NoReparseChain -Candidate $canonicalRoot -CanonicalRoot $canonicalRoot
    $inputCandidate = Get-FullNormalizedPath (Join-Path $canonicalRoot $InputRoot)
    $agentHandoffRoot = Get-FullNormalizedPath (Join-Path $canonicalRoot 'data\agent-handoff')
    $patchDropRoot = Get-FullNormalizedPath (Join-Path $canonicalRoot '__patch_drop__')
    $inputAllowed = (Test-StrictDescendant $inputCandidate $agentHandoffRoot) -or (Test-StrictDescendant $inputCandidate $patchDropRoot)
    if (-not $inputAllowed) { Throw-Trace 'trace-input-not-allowlisted' }
    if (-not (Test-Path -LiteralPath $inputCandidate -PathType Container)) { Throw-Trace 'trace-input-missing' }
    Assert-NoReparseChain -Candidate $inputCandidate -CanonicalRoot $canonicalRoot

    $outputParent = Get-FullNormalizedPath (Join-Path $agentHandoffRoot 'artifact-trace')
    $finalOutput = Get-FullNormalizedPath (Join-Path $outputParent $TraceId)
    if ((Test-PathEqual $inputCandidate $outputParent) -or
        (Test-StrictDescendant $finalOutput $inputCandidate) -or
        (Test-StrictDescendant $inputCandidate $finalOutput)) {
        Throw-Trace 'trace-input-not-allowlisted'
    }
    if (Test-Path -LiteralPath $finalOutput) { Throw-Trace 'trace-output-collision' }

    $fileRows = New-Object System.Collections.Generic.List[object]
    $directoryQueue = New-Object System.Collections.Generic.Queue[string]
    $directoryQueue.Enqueue($inputCandidate)
    $totalBytes = [long]0
    while ($directoryQueue.Count -gt 0) {
        Assert-TimeBudget
        $directory = $directoryQueue.Dequeue()
        Assert-NoReparseChain -Candidate $directory -CanonicalRoot $canonicalRoot
        foreach ($item in @(Get-ChildItem -LiteralPath $directory -Force | Sort-Object Name)) {
            Assert-TimeBudget
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Throw-Trace 'trace-reparse-risk' }
            if ($item.PSIsContainer) {
                $directoryQueue.Enqueue($item.FullName)
                continue
            }
            $fileRows.Add([pscustomobject]@{
                fullPath = Get-FullNormalizedPath $item.FullName
                relativePath = Get-RelativePathSafe -Candidate $item.FullName -Parent $canonicalRoot
                inputRelativePath = Get-RelativePathSafe -Candidate $item.FullName -Parent $inputCandidate
                name = $item.Name
                length = [long]$item.Length
                lastWriteTimeUtc = $item.LastWriteTimeUtc
            })
            $totalBytes += [long]$item.Length
            if ($fileRows.Count -gt $MaxFiles -or $totalBytes -gt ([long]$MaxTotalMiB * 1MB)) {
                Throw-Trace 'trace-budget-exceeded'
            }
        }
    }

    $records = @()
    foreach ($file in @($fileRows | Sort-Object inputRelativePath)) {
        Assert-TimeBudget
        $snapshot = Get-LockedSnapshot -Path $file.fullPath -ExpectedLength $file.length
        $json = $null
        $schemaVersion = ''
        $sidecarText = ''
        if ([IO.Path]::GetExtension($file.name) -ieq '.json') {
            if ($file.length -gt ([long]$MaxJsonMiB * 1MB)) { Throw-Trace 'trace-json-too-large' }
            try {
                $jsonText = $utf8NoBom.GetString($snapshot.bytes)
                if (Test-SecretLikeText $jsonText) { Throw-Trace 'trace-secret-risk' }
                $json = $jsonText | ConvertFrom-Json
                $schemaVersion = [string](Get-PropertyValue $json 'schemaVersion')
            } catch {
                if ([string]$_.Exception.Message -eq 'trace-secret-risk') { throw }
                $json = $null
                $schemaVersion = ''
            }
        } elseif ($file.name.EndsWith('.sha256.txt', [StringComparison]::OrdinalIgnoreCase)) {
            $sidecarText = $utf8NoBom.GetString($snapshot.bytes)
        }
        $records += [pscustomobject]@{
            fullPath = $file.fullPath
            path = $file.relativePath
            inputPath = $file.inputRelativePath
            name = $file.name
            sizeBytes = [long]$file.length
            lastWriteTimeUtc = ([DateTime]$file.lastWriteTimeUtc).ToString('o')
            sha256 = $snapshot.sha256
            json = $json
            schemaVersion = $schemaVersion
            text = $sidecarText
        }
    }

    $artifacts = @()
    foreach ($record in $records) {
        Assert-TimeBudget
        $role = 'diagnostic'
        $state = 'present'
        $evidenceRole = 'inventory_metadata'
        $proofStatus = 'none'
        $proofScope = 'none'
        $allowedClaims = @('artifact_present_at_inventory_time')
        $notProofOf = @($allDeniedClaims)
        $failures = @()
        $retention = 'preserve'
        $sourceState = 'unknown'
        $ownerHash = $null

        if ($null -ne $record.json) {
            $sourceState = Get-SafeVerdictValue (Get-PropertyValue $record.json 'state')
            $candidateOwnerHash = ([string](Get-PropertyValue $record.json 'ownerHash')).ToLowerInvariant()
            if ($candidateOwnerHash -match $hashPattern) { $ownerHash = $candidateOwnerHash }
        }

        switch -CaseSensitive ($record.schemaVersion) {
            'awx.macsrc_smb_patch_lease_cleanup.v1' {
                $role = 'tombstone'
                $state = ([string](Get-PropertyValue $record.json 'state')).ToLowerInvariant()
                if ($state -notin @('verified', 'deleted')) { $state = 'unknown' }
                $evidenceRole = 'cleanup_record'
                $proofStatus = 'self_reported'
                $proofScope = 'cleanup_only'
                $allowedClaims = @('cleanup_record_present')
                $notProofOf = @('source_patch', 'patch_handoff', 'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')
                $retention = 'prune_candidate'
            }
            'awx.macsrc_smb_patch_session.v2' {
                $role = 'session'
                $state = 'recorded'
                $evidenceRole = 'patch_session'
                $proofStatus = 'self_reported'
                $allowedClaims = @('session_record_present')
            }
            'awx.macsrc_smb_patch_verification.v1' {
                $role = 'verification'
                $state = 'recorded'
                $evidenceRole = 'verification_record'
                $proofStatus = 'self_reported'
                $allowedClaims = @('verification_record_present')
            }
            'awx.macsrc_smb_patch_completion.v2' {
                $role = 'completion'
                $state = ([string](Get-PropertyValue $record.json 'state')).ToLowerInvariant()
                $evidenceRole = 'terminal_record'
                if (Test-CompletionBinding -CompletionRecord $record -Records $records) {
                    $proofStatus = 'structurally_bound'
                    $proofScope = 'source_patch'
                    $allowedClaims = @('guard_completion_binding_present')
                    $notProofOf = @('patch_handoff', 'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')
                } else {
                    $proofStatus = 'invalid'
                    $proofScope = 'none'
                    $allowedClaims = @('completion_record_present')
                    $failures = @('trace-binding-invalid')
                }
            }
            'patchdrop-producer-v3' {
                $role = 'patchdrop_manifest'
                $state = 'recorded'
                $evidenceRole = 'handoff_record'
                $bundle = ''
                if ($record.name.EndsWith('-v3.manifest.json', [StringComparison]::OrdinalIgnoreCase)) {
                    $bundle = $record.name.Substring(0, $record.name.Length - '.manifest.json'.Length)
                }
                if (-not [string]::IsNullOrWhiteSpace($bundle) -and (Test-PatchDropBundle -Bundle $bundle -Records $records)) {
                    $proofStatus = 'structurally_bound'
                    $proofScope = 'patch_handoff'
                    $allowedClaims = @('producer_bundle_structurally_complete')
                    $notProofOf = @('source_patch', 'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')
                } else {
                    $proofStatus = 'invalid'
                    $proofScope = 'none'
                    $allowedClaims = @('patchdrop_manifest_present')
                    $failures = @('trace-binding-invalid')
                }
            }
            default {
                if ($record.name.EndsWith('.ready', [StringComparison]::OrdinalIgnoreCase)) {
                    if (Test-StrictDescendant $record.fullPath $patchDropRoot) {
                        $role = 'patchdrop_ready'
                        $evidenceRole = 'readiness_marker'
                        $bundle = $record.name.Substring(0, $record.name.Length - '.ready'.Length)
                        if (Test-PatchDropBundle -Bundle $bundle -Records $records) {
                            $proofStatus = 'structurally_bound'
                            $proofScope = 'patch_handoff'
                            $allowedClaims = @('producer_bundle_ready_marker_bound')
                            $notProofOf = @('source_patch', 'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')
                        } else {
                            $proofStatus = 'invalid'
                            $failures = @('trace-binding-invalid')
                            $allowedClaims = @('ready_marker_present')
                        }
                    } else {
                        $role = 'readiness_marker'
                        $evidenceRole = 'marker'
                        $proofStatus = 'self_reported'
                        $allowedClaims = @('ready_marker_present')
                    }
                } elseif ($record.name -match '(?i)-v3\.(patch|report\.md|verify\.log|sha256\.txt)$') {
                    $role = 'patchdrop_part'
                    $evidenceRole = 'handoff_part'
                    $allowedClaims = @('bundle_part_present')
                } elseif ($null -ne $record.json) {
                    $role = 'structured_diagnostic'
                    $evidenceRole = 'unrecognized_record'
                    $allowedClaims = @('structured_artifact_present')
                }
            }
        }

        $freshness = Get-FreshnessState $record.json
        if ($freshness -in @('expired', 'superseded')) { $retention = 'prune_candidate' }
        $sourceVerdict = [ordered]@{
            schemaVersion = if ([string]::IsNullOrWhiteSpace($record.schemaVersion)) { 'unrecognized' } else { Get-SafeVerdictValue $record.schemaVersion }
            state = $sourceState
            ownerHash = $ownerHash
            recordSha256 = $record.sha256
        }
        $artifacts += [ordered]@{
            path = $record.path
            artifactRole = $role
            artifactState = $state
            evidenceRole = $evidenceRole
            proofStatus = $proofStatus
            proofScope = $proofScope
            sourceVerdict = $sourceVerdict
            allowedClaims = @($allowedClaims)
            notProofOf = @($notProofOf)
            freshnessState = $freshness
            supersedes = @(Get-SupersedesHashes $record.json)
            retentionClass = $retention
            deleteAuthorized = $false
            sizeBytes = $record.sizeBytes
            sha256 = $record.sha256
            lastWriteTimeUtc = $record.lastWriteTimeUtc
            failureClassifications = @($failures)
        }
    }

    $manifestFailures = @($artifacts | ForEach-Object { @($_.failureClassifications) } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
    $inputRelative = Get-RelativePathSafe -Candidate $inputCandidate -Parent $canonicalRoot
    $manifest = [ordered]@{
        schemaVersion = 'awx.artifact_trace_manifest.v1'
        traceId = $TraceId
        generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        inputRoot = $inputRelative
        inputRootHash = Get-Sha256Text $inputRelative
        inventory = [ordered]@{
            fileCount = $records.Count
            totalBytes = $totalBytes
            stableOrder = $true
            rawContentIncluded = $false
        }
        limits = [ordered]@{
            maxFiles = $MaxFiles
            maxTotalMiB = $MaxTotalMiB
            maxJsonMiB = $MaxJsonMiB
            timeoutSeconds = $TimeoutSeconds
            maxOutputMiB = $MaxOutputMiB
        }
        overallVerdict = 'INVENTORY_ONLY'
        mutationAllowed = $false
        deleteAuthorized = $false
        runtimeLineageVerdict = 'HOLD'
        desktopFinalProof = 'evidence_needed'
        failureClassifications = @($manifestFailures)
        artifacts = @($artifacts | Sort-Object path)
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 20
    if (Test-SecretLikeText $manifestJson) { Throw-Trace 'trace-secret-risk' }
    $manifestBytes = $utf8NoBom.GetBytes($manifestJson)
    if ($manifestBytes.Length -gt ([long]$MaxOutputMiB * 1MB)) { Throw-Trace 'trace-budget-exceeded' }
    Assert-TimeBudget

    if (-not (Test-Path -LiteralPath $outputParent)) {
        New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
    }
    Assert-NoReparseChain -Candidate $outputParent -CanonicalRoot $canonicalRoot
    if (Test-Path -LiteralPath $finalOutput) { Throw-Trace 'trace-output-collision' }
    $temporaryOutput = Join-Path $outputParent ('.' + $TraceId + '.tmp-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporaryOutput | Out-Null
    $manifestPath = Join-Path $temporaryOutput 'manifest.json'
    [IO.File]::WriteAllBytes($manifestPath, $manifestBytes)
    $manifestSha = Get-Sha256Bytes $manifestBytes
    [IO.File]::WriteAllText((Join-Path $temporaryOutput 'manifest.sha256'), ($manifestSha + '  manifest.json' + [Environment]::NewLine), $utf8NoBom)
    [IO.Directory]::Move($temporaryOutput, $finalOutput)
    $finalMovedByThisRun = $true
    $temporaryOutput = $null
    [IO.File]::WriteAllText((Join-Path $finalOutput '.ready'), ('schemaVersion=awx.artifact_trace_ready.v1' + [Environment]::NewLine + 'manifestSha256=' + $manifestSha + [Environment]::NewLine), $utf8NoBom)
    $publicationComplete = $true

    [pscustomobject]@{
        schemaVersion = 'awx.artifact_trace_result.v1'
        traceId = $TraceId
        verdict = 'INVENTORY_ONLY'
        artifactCount = $records.Count
        manifestSha256 = $manifestSha
        output = Get-RelativePathSafe -Candidate $finalOutput -Parent $canonicalRoot
        mutationAllowed = $false
        deleteAuthorized = $false
        runtimeLineageVerdict = 'HOLD'
        desktopFinalProof = 'evidence_needed'
    } | ConvertTo-Json -Depth 5
} catch {
    $reason = [string]$_.Exception.Message
    if ($null -ne $temporaryOutput -and (Test-Path -LiteralPath $temporaryOutput) -and (Test-StrictDescendant $temporaryOutput (Join-Path (Get-FullNormalizedPath $Root) 'data\agent-handoff\artifact-trace'))) {
        Remove-Item -LiteralPath $temporaryOutput -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not $publicationComplete -and $finalMovedByThisRun -and $null -ne $finalOutput -and (Test-Path -LiteralPath $finalOutput) -and
        (Test-StrictDescendant $finalOutput (Join-Path (Get-FullNormalizedPath $Root) 'data\agent-handoff\artifact-trace')) -and
        -not (Test-Path -LiteralPath (Join-Path $finalOutput '.ready'))) {
        Remove-Item -LiteralPath $finalOutput -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($reason -match '^trace-[a-z0-9-]+$') { throw $reason }
    throw 'trace-publication-failed'
}

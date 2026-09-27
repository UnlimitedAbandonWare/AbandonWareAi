[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root,

    [Parameter(Mandatory = $true)]
    [string]$TraceDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-f0-9]{64}$')]
    [string]$ExpectedManifestSha256,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9-]{0,63}$')]
    [string]$VerificationId,

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
$utf8NoBom = New-Object Text.UTF8Encoding($false)
$utf8Strict = New-Object Text.UTF8Encoding($false, $true)
$hashPattern = '^[a-f0-9]{64}$'
$temporaryOutput = $null
$temporaryOwnerToken = $null
$outputParent = $null
$finalOutput = $null

function Throw-Verify {
    param([string]$Reason)
    throw $Reason
}

function Assert-TimeBudget {
    if ($TimeoutSeconds -eq 0 -or $started.Elapsed.TotalSeconds -gt $TimeoutSeconds) {
        Throw-Verify 'trace-verify-budget-exceeded'
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
    return [string]::Equals(
        (Get-FullNormalizedPath $Left),
        (Get-FullNormalizedPath $Right),
        [StringComparison]::OrdinalIgnoreCase
    )
}

function Test-StrictDescendant {
    param([string]$Candidate, [string]$Parent)
    $candidateFull = Get-FullNormalizedPath $Candidate
    $parentFull = Get-FullNormalizedPath $Parent
    return $candidateFull.StartsWith(
        ($parentFull + [IO.Path]::DirectorySeparatorChar),
        [StringComparison]::OrdinalIgnoreCase
    )
}

function Get-RelativePathSafe {
    param([string]$Candidate, [string]$Parent)
    if (-not (Test-StrictDescendant -Candidate $Candidate -Parent $Parent)) {
        Throw-Verify 'trace-verify-source-not-allowlisted'
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
        Throw-Verify 'trace-verify-source-not-allowlisted'
    }
    while ($true) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Verify 'trace-verify-reparse-risk'
            }
        }
        if (Test-PathEqual $current $rootFull) { break }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrWhiteSpace($parent) -or (Test-PathEqual $parent $current)) {
            Throw-Verify 'trace-verify-reparse-risk'
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
    param(
        [string]$Path,
        [long]$MaxBytes,
        [switch]$IncludeBytes
    )
    Assert-TimeBudget
    $before = Get-Item -LiteralPath $Path -Force
    if ($before.PSIsContainer -or (($before.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        Throw-Verify 'trace-verify-reparse-risk'
    }
    if ($MaxBytes -lt 0 -or [long]$before.Length -gt $MaxBytes) {
        Throw-Verify 'trace-verify-budget-exceeded'
    }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $memory = $null
    try {
        if ([long]$stream.Length -gt $MaxBytes) { Throw-Verify 'trace-verify-budget-exceeded' }
        if ($IncludeBytes) {
            if ($stream.Length -gt [int]::MaxValue) { Throw-Verify 'trace-verify-budget-exceeded' }
            $memory = New-Object IO.MemoryStream ([int]$stream.Length)
        }
        $buffer = New-Object byte[] 65536
        $totalRead = [long]0
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            Assert-TimeBudget
            $totalRead += [long]$read
            if ($totalRead -gt $MaxBytes) { Throw-Verify 'trace-verify-budget-exceeded' }
            [void]$algorithm.TransformBlock($buffer, 0, $read, $buffer, 0)
            if ($IncludeBytes) { $memory.Write($buffer, 0, $read) }
        }
        [void]$algorithm.TransformFinalBlock($buffer, 0, 0)
        if ($totalRead -ne [long]$stream.Length) { Throw-Verify 'trace-verify-input-unstable' }
        $bytes = if ($IncludeBytes) { $memory.ToArray() } else { $null }
        $sha256 = ([BitConverter]::ToString($algorithm.Hash)).Replace('-', '').ToLowerInvariant()
    } finally {
        if ($null -ne $memory) { $memory.Dispose() }
        $algorithm.Dispose()
        $stream.Dispose()
    }
    $after = Get-Item -LiteralPath $Path -Force
    if ($after.PSIsContainer -or
        (($after.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) -or
        [long]$before.Length -ne [long]$after.Length -or
        $before.LastWriteTimeUtc.Ticks -ne $after.LastWriteTimeUtc.Ticks -or
        [long]$after.Length -ne $totalRead) {
        Throw-Verify 'trace-verify-input-unstable'
    }
    return [pscustomobject]@{
        bytes = $bytes
        sha256 = $sha256
        length = $totalRead
        lastWriteTimeUtc = $after.LastWriteTimeUtc.ToString('o')
    }
}

function Read-StrictUtf8 {
    param([byte[]]$Bytes)
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xEF -and $Bytes[1] -eq 0xBB -and $Bytes[2] -eq 0xBF) {
        Throw-Verify 'trace-verify-manifest-contract-invalid'
    }
    foreach ($value in $Bytes) {
        if ($value -eq 0) { Throw-Verify 'trace-verify-manifest-contract-invalid' }
    }
    try {
        $text = $utf8Strict.GetString($Bytes)
    } catch {
        Throw-Verify 'trace-verify-manifest-contract-invalid'
    }
    if ($text.Contains([char]0xfffd)) { Throw-Verify 'trace-verify-manifest-contract-invalid' }
    return $text
}

function Test-SecretLikeText {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $false }
    return $Text -match '(?i)\bsk-[A-Za-z0-9_-]{16,}\b|\bBearer\s+[A-Za-z0-9._~+/-]{12,}\b|-----BEGIN\s+(RSA\s+|EC\s+|OPENSSH\s+)?PRIVATE\s+KEY-----'
}

function Test-HasExactProperty {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $false }
    return @($Object.PSObject.Properties.Name) -ccontains $Name
}

function Get-ExactPropertyValue {
    param($Object, [string]$Name)
    if (-not (Test-HasExactProperty -Object $Object -Name $Name)) { return $null }
    foreach ($property in $Object.PSObject.Properties) {
        if ($property.Name -ceq $Name) { return $property.Value }
    }
    return $null
}

function Get-ExactPropertyInfo {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    foreach ($property in $Object.PSObject.Properties) {
        if ($property.Name -ceq $Name) { return $property }
    }
    return $null
}

function Test-IsInteger {
    param($Value)
    return ($Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or
        $Value -is [int64] -or $Value -is [uint16] -or $Value -is [uint32])
}

function Test-ExactPropertySet {
    param($Object, [string[]]$Names)
    if ($null -eq $Object) { return $false }
    $actual = @($Object.PSObject.Properties.Name)
    if ($actual.Count -ne $Names.Count) { return $false }
    foreach ($name in $Names) {
        if (-not ($actual -ccontains $name)) { return $false }
    }
    return $true
}

function Test-StringArrayContract {
    param(
        $Value,
        [string]$Pattern = '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$',
        [int]$MaxCount = 256
    )
    if ($Value -isnot [Array]) { return $false }
    $items = @($Value)
    if ($items.Count -gt $MaxCount) { return $false }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($item in $items) {
        if ($item -isnot [string] -or [string]$item -cnotmatch $Pattern -or -not $seen.Add([string]$item)) {
            return $false
        }
    }
    return $true
}

function Test-SafeToken {
    param($Value)
    return $Value -is [string] -and [string]$Value -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'
}

function Test-NoDuplicateJsonObjectKeys {
    param([string]$Json)
    $stack = New-Object System.Collections.ArrayList
    $index = 0
    while ($index -lt $Json.Length) {
        $character = $Json[$index]
        if ($character -eq '{') {
            if ($stack.Count -ge 30) { return $false }
            $names = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
            [void]$stack.Add([pscustomobject]@{ type = 'object'; names = $names })
            $index++
            continue
        }
        if ($character -eq '[') {
            if ($stack.Count -ge 30) { return $false }
            [void]$stack.Add([pscustomobject]@{ type = 'array'; names = $null })
            $index++
            continue
        }
        if ($character -eq '}' -or $character -eq ']') {
            if ($stack.Count -eq 0) { return $false }
            $expectedType = if ($character -eq '}') { 'object' } else { 'array' }
            if ($stack[$stack.Count - 1].type -cne $expectedType) { return $false }
            $stack.RemoveAt($stack.Count - 1)
            $index++
            continue
        }
        if ($character -eq '"') {
            $start = $index + 1
            $index++
            $escaped = $false
            while ($index -lt $Json.Length) {
                if ($Json[$index] -eq '\') {
                    $escaped = $true
                    $index += 2
                    continue
                }
                if ($Json[$index] -eq '"') { break }
                if ([int][char]$Json[$index] -lt 0x20) { return $false }
                $index++
            }
            if ($index -ge $Json.Length) { return $false }
            $rawName = $Json.Substring($start, $index - $start)
            $next = $index + 1
            while ($next -lt $Json.Length -and [char]::IsWhiteSpace($Json[$next])) { $next++ }
            if ($next -lt $Json.Length -and $Json[$next] -eq ':') {
                if ($escaped -or $stack.Count -eq 0 -or $stack[$stack.Count - 1].type -cne 'object') {
                    return $false
                }
                if (-not $stack[$stack.Count - 1].names.Add($rawName)) { return $false }
            }
            $index++
            continue
        }
        $index++
    }
    return $stack.Count -eq 0
}

function ConvertTo-SafeRelativePath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or [IO.Path]::IsPathRooted($Path) -or $Path.Contains(':')) {
        Throw-Verify 'trace-verify-manifest-contract-invalid'
    }
    $normalized = $Path.Replace('\', '/')
    $segments = $normalized.Split('/')
    if ($segments.Count -eq 0) { Throw-Verify 'trace-verify-manifest-contract-invalid' }
    foreach ($segment in $segments) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        if ($segment.TrimEnd('.', ' ') -cne $segment) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $baseName = $segment.Split('.')[0]
        if ($baseName -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$') {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        if ($segment.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
    }
    return $normalized
}

function Get-EnvelopeSnapshot {
    param([string]$TraceFull)
    Assert-TimeBudget
    if (-not (Test-Path -LiteralPath $TraceFull -PathType Container)) {
        return [pscustomobject]@{ valid = $false; reason = 'trace-verify-envelope-invalid' }
    }
    $entryPaths = New-Object System.Collections.Generic.List[string]
    $entryEnumerator = [IO.Directory]::EnumerateFileSystemEntries($TraceFull).GetEnumerator()
    try {
        while ($entryEnumerator.MoveNext()) {
            Assert-TimeBudget
            $entryPaths.Add([string]$entryEnumerator.Current)
            if ($entryPaths.Count -gt 3) {
                return [pscustomobject]@{ valid = $false; reason = 'trace-verify-envelope-invalid' }
            }
        }
    } finally {
        $entryEnumerator.Dispose()
    }
    $items = @($entryPaths | ForEach-Object { Get-Item -LiteralPath $_ -Force })
    $names = @($items | ForEach-Object { $_.Name })
    foreach ($required in @('manifest.json', 'manifest.sha256')) {
        if (-not ($names -ccontains $required)) {
            return [pscustomobject]@{ valid = $false; reason = 'trace-verify-envelope-invalid' }
        }
    }
    if (-not ($names -ccontains '.ready')) {
        return [pscustomobject]@{ valid = $false; reason = 'trace-verify-ready-binding-invalid' }
    }
    if ($items.Count -ne 3) {
        return [pscustomobject]@{ valid = $false; reason = 'trace-verify-envelope-invalid' }
    }
    foreach ($item in $items) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            Throw-Verify 'trace-verify-reparse-risk'
        }
        if ($item.PSIsContainer) {
            return [pscustomobject]@{ valid = $false; reason = 'trace-verify-envelope-invalid' }
        }
    }
    try {
        $manifest = Get-LockedSnapshot `
            -Path (Join-Path $TraceFull 'manifest.json') `
            -MaxBytes ([long]$MaxJsonMiB * 1MB) `
            -IncludeBytes
        $sidecar = Get-LockedSnapshot `
            -Path (Join-Path $TraceFull 'manifest.sha256') `
            -MaxBytes 4096 `
            -IncludeBytes
        $ready = Get-LockedSnapshot `
            -Path (Join-Path $TraceFull '.ready') `
            -MaxBytes 4096 `
            -IncludeBytes
    } catch {
        $reason = [string]$_.Exception.Message
        if ($reason -eq 'trace-verify-budget-exceeded' -or
            $reason -eq 'trace-verify-reparse-risk') { throw }
        return [pscustomobject]@{ valid = $false; reason = 'trace-verify-envelope-invalid' }
    }
    $signature = (
        'manifest.json|file|' + $manifest.sha256 + "`n" +
        'manifest.sha256|file|' + $sidecar.sha256 + "`n" +
        '.ready|file|' + $ready.sha256
    )
    return [pscustomobject]@{
        valid = $true
        reason = ''
        manifest = $manifest
        sidecar = $sidecar
        ready = $ready
        signature = $signature
    }
}

function Get-AuthenticatedEnvelope {
    param(
        [string]$TraceFull,
        [string]$TraceId,
        [string]$CanonicalRoot
    )
    $snapshot = Get-EnvelopeSnapshot -TraceFull $TraceFull
    if (-not $snapshot.valid) {
        return [pscustomobject]@{ valid = $false; reason = $snapshot.reason; snapshot = $snapshot }
    }
    if ($snapshot.manifest.sha256 -cne $ExpectedManifestSha256) {
        return [pscustomobject]@{ valid = $false; reason = 'trace-verify-expected-manifest-sha-mismatch'; snapshot = $snapshot }
    }
    try {
        $sidecarText = Read-StrictUtf8 $snapshot.sidecar.bytes
        $expectedSidecar = $snapshot.manifest.sha256 + '  manifest.json' + [Environment]::NewLine
        if ($sidecarText -cne $expectedSidecar) {
            return [pscustomobject]@{ valid = $false; reason = 'trace-verify-manifest-sha-mismatch'; snapshot = $snapshot }
        }
        $readyText = Read-StrictUtf8 $snapshot.ready.bytes
        $expectedReady = (
            'schemaVersion=awx.artifact_trace_ready.v1' + [Environment]::NewLine +
            'manifestSha256=' + $snapshot.manifest.sha256 + [Environment]::NewLine
        )
        if ($readyText -cne $expectedReady) {
            return [pscustomobject]@{ valid = $false; reason = 'trace-verify-ready-binding-invalid'; snapshot = $snapshot }
        }
        if ($snapshot.manifest.length -gt ([long]$MaxJsonMiB * 1MB)) {
            Throw-Verify 'trace-verify-budget-exceeded'
        }
        $manifestText = Read-StrictUtf8 $snapshot.manifest.bytes
        if (-not (Test-NoDuplicateJsonObjectKeys $manifestText)) {
            return [pscustomobject]@{ valid = $false; reason = 'trace-verify-manifest-contract-invalid'; snapshot = $snapshot }
        }
        $manifest = $manifestText | ConvertFrom-Json
        $requiredTop = @(
            'schemaVersion', 'traceId', 'generatedAtUtc', 'inputRoot', 'inputRootHash',
            'inventory', 'limits', 'overallVerdict', 'mutationAllowed',
            'deleteAuthorized', 'runtimeLineageVerdict', 'desktopFinalProof',
            'failureClassifications', 'artifacts'
        )
        if (-not (Test-ExactPropertySet $manifest $requiredTop)) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $generatedAt = [string](Get-ExactPropertyValue $manifest 'generatedAtUtc')
        $parsedGeneratedAt = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParseExact(
            $generatedAt,
            'o',
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$parsedGeneratedAt
        )) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        if ([string](Get-ExactPropertyValue $manifest 'schemaVersion') -cne 'awx.artifact_trace_manifest.v1' -or
            [string](Get-ExactPropertyValue $manifest 'traceId') -cne $TraceId -or
            [string](Get-ExactPropertyValue $manifest 'overallVerdict') -cne 'INVENTORY_ONLY' -or
            (Get-ExactPropertyValue $manifest 'mutationAllowed') -isnot [bool] -or
            (Get-ExactPropertyValue $manifest 'mutationAllowed') -ne $false -or
            (Get-ExactPropertyValue $manifest 'deleteAuthorized') -isnot [bool] -or
            (Get-ExactPropertyValue $manifest 'deleteAuthorized') -ne $false -or
            [string](Get-ExactPropertyValue $manifest 'runtimeLineageVerdict') -cne 'HOLD' -or
            [string](Get-ExactPropertyValue $manifest 'desktopFinalProof') -cne 'evidence_needed') {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }

        $inputRelative = ConvertTo-SafeRelativePath ([string](Get-ExactPropertyValue $manifest 'inputRoot'))
        if ([string](Get-ExactPropertyValue $manifest 'inputRootHash') -cne (Get-Sha256Text $inputRelative)) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $inputFull = Get-FullNormalizedPath (Join-Path $CanonicalRoot $inputRelative)
        $handoffRoot = Get-FullNormalizedPath (Join-Path $CanonicalRoot 'data\agent-handoff')
        $patchDropRoot = Get-FullNormalizedPath (Join-Path $CanonicalRoot '__patch_drop__')
        if (-not ((Test-StrictDescendant $inputFull $handoffRoot) -or (Test-StrictDescendant $inputFull $patchDropRoot))) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        if (-not (Test-Path -LiteralPath $inputFull -PathType Container)) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $verificationRoot = Get-FullNormalizedPath (Join-Path $handoffRoot 'artifact-trace-verification')
        if ((Test-PathEqual $inputFull $verificationRoot) -or
            (Test-StrictDescendant $inputFull $verificationRoot)) {
            Throw-Verify 'trace-verify-source-not-allowlisted'
        }
        Assert-NoReparseChain -Candidate $inputFull -CanonicalRoot $CanonicalRoot

        $inventory = Get-ExactPropertyValue $manifest 'inventory'
        if (-not (Test-ExactPropertySet $inventory @('fileCount', 'totalBytes', 'stableOrder', 'rawContentIncluded'))) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $declaredCount = Get-ExactPropertyValue $inventory 'fileCount'
        $declaredBytes = Get-ExactPropertyValue $inventory 'totalBytes'
        if (-not (Test-IsInteger $declaredCount) -or [long]$declaredCount -lt 0 -or
            -not (Test-IsInteger $declaredBytes) -or [long]$declaredBytes -lt 0 -or
            (Get-ExactPropertyValue $inventory 'stableOrder') -isnot [bool] -or
            (Get-ExactPropertyValue $inventory 'stableOrder') -ne $true -or
            (Get-ExactPropertyValue $inventory 'rawContentIncluded') -isnot [bool] -or
            (Get-ExactPropertyValue $inventory 'rawContentIncluded') -ne $false) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }

        $limits = Get-ExactPropertyValue $manifest 'limits'
        $limitNames = @('maxFiles', 'maxTotalMiB', 'maxJsonMiB', 'timeoutSeconds', 'maxOutputMiB')
        if (-not (Test-ExactPropertySet $limits $limitNames)) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $captureMaxFiles = Get-ExactPropertyValue $limits 'maxFiles'
        $captureMaxTotal = Get-ExactPropertyValue $limits 'maxTotalMiB'
        $captureMaxJson = Get-ExactPropertyValue $limits 'maxJsonMiB'
        $captureTimeout = Get-ExactPropertyValue $limits 'timeoutSeconds'
        $captureMaxOutput = Get-ExactPropertyValue $limits 'maxOutputMiB'
        if (-not (Test-IsInteger $captureMaxFiles) -or [int]$captureMaxFiles -lt 0 -or [int]$captureMaxFiles -gt 4096 -or
            -not (Test-IsInteger $captureMaxTotal) -or [int]$captureMaxTotal -lt 0 -or [int]$captureMaxTotal -gt 1024 -or
            -not (Test-IsInteger $captureMaxJson) -or [int]$captureMaxJson -lt 0 -or [int]$captureMaxJson -gt 16 -or
            -not (Test-IsInteger $captureTimeout) -or [int]$captureTimeout -lt 0 -or [int]$captureTimeout -gt 600 -or
            -not (Test-IsInteger $captureMaxOutput) -or [int]$captureMaxOutput -lt 0 -or [int]$captureMaxOutput -gt 16 -or
            [int]$captureMaxFiles -lt [int]$declaredCount -or
            ([long]$captureMaxTotal * 1MB) -lt [long]$declaredBytes) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }

        $manifestFailuresProperty = Get-ExactPropertyInfo $manifest 'failureClassifications'
        $manifestFailures = $manifestFailuresProperty.Value
        if (-not (Test-StringArrayContract -Value $manifestFailures -Pattern '^[a-z0-9][a-z0-9-]{0,127}$')) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }

        $artifacts = @(Get-ExactPropertyValue $manifest 'artifacts')
        if ($artifacts.Count -ne [int]$declaredCount) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        if ($artifacts.Count -gt $MaxFiles) { Throw-Verify 'trace-verify-budget-exceeded' }
        $pathSet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        $artifactFailureSet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        $expectedRows = @{}
        $sumBytes = [long]0
        foreach ($artifact in $artifacts) {
            $artifactNames = @(
                'path', 'artifactRole', 'artifactState', 'evidenceRole',
                'proofStatus', 'proofScope', 'sourceVerdict', 'allowedClaims',
                'notProofOf', 'freshnessState', 'supersedes', 'retentionClass',
                'deleteAuthorized', 'sizeBytes', 'sha256', 'lastWriteTimeUtc',
                'failureClassifications'
            )
            if (-not (Test-ExactPropertySet $artifact $artifactNames)) {
                Throw-Verify 'trace-verify-manifest-contract-invalid'
            }
            $path = ConvertTo-SafeRelativePath ([string](Get-ExactPropertyValue $artifact 'path'))
            if (-not $pathSet.Add($path)) { Throw-Verify 'trace-verify-manifest-contract-invalid' }
            $artifactFull = Get-FullNormalizedPath (Join-Path $CanonicalRoot $path)
            if (-not (Test-StrictDescendant $artifactFull $inputFull)) {
                Throw-Verify 'trace-verify-manifest-contract-invalid'
            }
            $size = Get-ExactPropertyValue $artifact 'sizeBytes'
            $sha = [string](Get-ExactPropertyValue $artifact 'sha256')
            $timestamp = [string](Get-ExactPropertyValue $artifact 'lastWriteTimeUtc')
            $artifactRole = Get-ExactPropertyValue $artifact 'artifactRole'
            $artifactState = Get-ExactPropertyValue $artifact 'artifactState'
            $evidenceRole = Get-ExactPropertyValue $artifact 'evidenceRole'
            $proofStatus = [string](Get-ExactPropertyValue $artifact 'proofStatus')
            $proofScope = [string](Get-ExactPropertyValue $artifact 'proofScope')
            $freshnessState = [string](Get-ExactPropertyValue $artifact 'freshnessState')
            $retentionClass = [string](Get-ExactPropertyValue $artifact 'retentionClass')
            $sourceVerdict = Get-ExactPropertyValue $artifact 'sourceVerdict'
            $sourceVerdictNames = @('schemaVersion', 'state', 'ownerHash', 'recordSha256')
            $ownerHash = Get-ExactPropertyValue $sourceVerdict 'ownerHash'
            $recordSha = [string](Get-ExactPropertyValue $sourceVerdict 'recordSha256')
            $allowedClaims = (Get-ExactPropertyInfo $artifact 'allowedClaims').Value
            $notProofOf = (Get-ExactPropertyInfo $artifact 'notProofOf').Value
            $supersedes = (Get-ExactPropertyInfo $artifact 'supersedes').Value
            $artifactFailures = (Get-ExactPropertyInfo $artifact 'failureClassifications').Value
            $parsedTimestamp = [DateTimeOffset]::MinValue
            if (-not (Test-IsInteger $size) -or [long]$size -lt 0 -or
                $sha -cnotmatch $hashPattern -or
                -not [DateTimeOffset]::TryParseExact(
                    $timestamp,
                    'o',
                    [Globalization.CultureInfo]::InvariantCulture,
                    [Globalization.DateTimeStyles]::RoundtripKind,
                    [ref]$parsedTimestamp
                ) -or
                -not (Test-SafeToken $artifactRole) -or
                -not (Test-SafeToken $artifactState) -or
                -not (Test-SafeToken $evidenceRole) -or
                $proofStatus -cnotin @('none', 'self_reported', 'structurally_bound', 'invalid') -or
                $proofScope -cnotin @('none', 'cleanup_only', 'source_patch', 'patch_handoff') -or
                $freshnessState -cnotin @('current', 'expired', 'superseded', 'unknown') -or
                $retentionClass -cnotin @('preserve', 'prune_candidate') -or
                -not (Test-ExactPropertySet $sourceVerdict $sourceVerdictNames) -or
                -not (Test-SafeToken (Get-ExactPropertyValue $sourceVerdict 'schemaVersion')) -or
                -not (Test-SafeToken (Get-ExactPropertyValue $sourceVerdict 'state')) -or
                ($null -ne $ownerHash -and ([string]$ownerHash -cnotmatch $hashPattern)) -or
                $recordSha -cne $sha -or
                -not (Test-StringArrayContract -Value $allowedClaims) -or
                -not (Test-StringArrayContract -Value $notProofOf) -or
                -not (Test-StringArrayContract -Value $supersedes -Pattern $hashPattern) -or
                -not (Test-StringArrayContract -Value $artifactFailures -Pattern '^[a-z0-9][a-z0-9-]{0,127}$') -or
                (Get-ExactPropertyValue $artifact 'deleteAuthorized') -isnot [bool] -or
                (Get-ExactPropertyValue $artifact 'deleteAuthorized') -ne $false) {
                Throw-Verify 'trace-verify-manifest-contract-invalid'
            }
            $knownClaims = @(
                'artifact_present_at_inventory_time', 'cleanup_record_present',
                'session_record_present', 'verification_record_present',
                'guard_completion_binding_present', 'completion_record_present',
                'producer_bundle_structurally_complete', 'patchdrop_manifest_present',
                'producer_bundle_ready_marker_bound', 'ready_marker_present',
                'bundle_part_present', 'structured_artifact_present'
            )
            if (@($allowedClaims).Count -eq 0) {
                Throw-Verify 'trace-verify-manifest-contract-invalid'
            }
            foreach ($claim in @($allowedClaims)) {
                if ($claim -cnotin $knownClaims) {
                    Throw-Verify 'trace-verify-manifest-contract-invalid'
                }
            }
            foreach ($claim in @($notProofOf)) {
                if ($claim -cnotin @('source_patch', 'patch_handoff', 'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')) {
                    Throw-Verify 'trace-verify-manifest-contract-invalid'
                }
            }
            foreach ($requiredDenial in @('desktop_apply', 'runtime', 'deployment', 'approval', 'current_state')) {
                if (@($notProofOf) -cnotcontains $requiredDenial) {
                    Throw-Verify 'trace-verify-manifest-contract-invalid'
                }
            }
            if (($proofScope -ceq 'source_patch' -and @($notProofOf) -ccontains 'source_patch') -or
                ($proofScope -ceq 'source_patch' -and @($notProofOf) -cnotcontains 'patch_handoff') -or
                ($proofScope -ceq 'patch_handoff' -and @($notProofOf) -ccontains 'patch_handoff') -or
                ($proofScope -ceq 'patch_handoff' -and @($notProofOf) -cnotcontains 'source_patch') -or
                ($proofScope -cin @('none', 'cleanup_only') -and @($notProofOf) -cnotcontains 'source_patch') -or
                ($proofScope -cin @('none', 'cleanup_only') -and @($notProofOf) -cnotcontains 'patch_handoff')) {
                Throw-Verify 'trace-verify-manifest-contract-invalid'
            }
            if (($proofStatus -ceq 'none' -and $proofScope -cne 'none') -or
                ($proofStatus -ceq 'self_reported' -and $proofScope -cnotin @('none', 'cleanup_only')) -or
                ($proofStatus -ceq 'structurally_bound' -and $proofScope -cnotin @('source_patch', 'patch_handoff')) -or
                ($proofStatus -ceq 'invalid' -and $proofScope -cne 'none')) {
                Throw-Verify 'trace-verify-manifest-contract-invalid'
            }
            foreach ($failure in @($artifactFailures)) { [void]$artifactFailureSet.Add([string]$failure) }
            $sumBytes += [long]$size
            $expectedRows[$path] = [pscustomobject]@{
                path = $path
                sizeBytes = [long]$size
                sha256 = $sha
                lastWriteTimeUtc = $timestamp
            }
        }
        if ($sumBytes -ne [long]$declaredBytes) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        $declaredFailureSet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        foreach ($failure in @($manifestFailures)) { [void]$declaredFailureSet.Add([string]$failure) }
        if (-not $declaredFailureSet.SetEquals($artifactFailureSet)) {
            Throw-Verify 'trace-verify-manifest-contract-invalid'
        }
        if ($sumBytes -gt ([long]$MaxTotalMiB * 1MB)) { Throw-Verify 'trace-verify-budget-exceeded' }
        return [pscustomobject]@{
            valid = $true
            reason = ''
            snapshot = $snapshot
            manifest = $manifest
            inputRelative = $inputRelative
            inputFull = $inputFull
            expectedRows = $expectedRows
            expectedFileCount = [int]$declaredCount
        }
    } catch {
        $reason = [string]$_.Exception.Message
        if ($reason -eq 'trace-verify-budget-exceeded' -or
            $reason -eq 'trace-verify-reparse-risk' -or
            $reason -eq 'trace-verify-source-not-allowlisted') { throw }
        return [pscustomobject]@{ valid = $false; reason = 'trace-verify-manifest-contract-invalid'; snapshot = $snapshot }
    }
}

function Get-CurrentInventory {
    param([string]$InputFull, [string]$CanonicalRoot)
    Assert-NoReparseChain -Candidate $InputFull -CanonicalRoot $CanonicalRoot
    $directories = New-Object System.Collections.Generic.Queue[string]
    $directories.Enqueue($InputFull)
    $rowsByPath = @{}
    $pathSet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $totalBytes = [long]0
    while ($directories.Count -gt 0) {
        Assert-TimeBudget
        $directory = $directories.Dequeue()
        Assert-NoReparseChain -Candidate $directory -CanonicalRoot $CanonicalRoot
        foreach ($entryPath in [IO.Directory]::EnumerateFileSystemEntries($directory)) {
            Assert-TimeBudget
            $item = Get-Item -LiteralPath $entryPath -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Verify 'trace-verify-reparse-risk'
            }
            if ($item.PSIsContainer) {
                $directories.Enqueue($item.FullName)
                continue
            }
            $path = Get-RelativePathSafe -Candidate $item.FullName -Parent $CanonicalRoot
            $path = ConvertTo-SafeRelativePath $path
            if (-not $pathSet.Add($path)) { Throw-Verify 'trace-verify-manifest-contract-invalid' }
            if ($pathSet.Count -gt $MaxFiles) { Throw-Verify 'trace-verify-budget-exceeded' }
            $remainingBytes = ([long]$MaxTotalMiB * 1MB) - $totalBytes
            if ($remainingBytes -lt 0 -or [long]$item.Length -gt $remainingBytes) {
                Throw-Verify 'trace-verify-budget-exceeded'
            }
            $snapshot = Get-LockedSnapshot -Path $item.FullName -MaxBytes $remainingBytes
            $totalBytes += $snapshot.length
            if ($totalBytes -gt ([long]$MaxTotalMiB * 1MB)) {
                Throw-Verify 'trace-verify-budget-exceeded'
            }
            $rowsByPath[$path] = [pscustomobject]@{
                path = $path
                sizeBytes = $snapshot.length
                sha256 = $snapshot.sha256
                lastWriteTimeUtc = $snapshot.lastWriteTimeUtc
            }
        }
    }
    $paths = [string[]]@($pathSet)
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $lines = New-Object System.Collections.Generic.List[string]
    $rows = @()
    foreach ($path in $paths) {
        $row = $rowsByPath[$path]
        $rows += $row
        $lines.Add(
            $row.path + "`t" + [string]$row.sizeBytes + "`t" +
            $row.sha256 + "`t" + $row.lastWriteTimeUtc
        )
    }
    $canonical = ($lines -join "`n")
    return [pscustomobject]@{
        rows = $rows
        rowsByPath = $rowsByPath
        fileCount = $rows.Count
        totalBytes = $totalBytes
        canonical = $canonical
        inventorySha256 = Get-Sha256Text $canonical
    }
}

function Compare-Inventory {
    param($ExpectedRows, $CurrentInventory)
    $allPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $ExpectedRows.Keys) { [void]$allPaths.Add([string]$path) }
    foreach ($path in $CurrentInventory.rowsByPath.Keys) { [void]$allPaths.Add([string]$path) }
    $paths = [string[]]@($allPaths)
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $changes = New-Object System.Collections.Generic.List[object]
    $unchanged = 0
    $changed = 0
    $metadataOnly = 0
    $missing = 0
    $added = 0
    foreach ($path in $paths) {
        $hasExpected = $ExpectedRows.ContainsKey($path)
        $hasCurrent = $CurrentInventory.rowsByPath.ContainsKey($path)
        $expected = if ($hasExpected) { $ExpectedRows[$path] } else { $null }
        $current = if ($hasCurrent) { $CurrentInventory.rowsByPath[$path] } else { $null }
        $kind = ''
        if (-not $hasExpected) { $kind = 'added'; $added++ }
        elseif (-not $hasCurrent) { $kind = 'missing'; $missing++ }
        elseif ($expected.sha256 -cne $current.sha256 -or $expected.sizeBytes -ne $current.sizeBytes) {
            $kind = 'content_changed'; $changed++
        } elseif ($expected.lastWriteTimeUtc -cne $current.lastWriteTimeUtc) {
            $kind = 'metadata_only'; $metadataOnly++
        } else {
            $unchanged++
        }
        if (-not [string]::IsNullOrEmpty($kind)) {
            $changes.Add([ordered]@{
                path = $path
                changeKind = $kind
                expectedSha256 = if ($null -ne $expected) { $expected.sha256 } else { $null }
                currentSha256 = if ($null -ne $current) { $current.sha256 } else { $null }
                expectedSizeBytes = if ($null -ne $expected) { $expected.sizeBytes } else { $null }
                currentSizeBytes = if ($null -ne $current) { $current.sizeBytes } else { $null }
                expectedLastWriteTimeUtc = if ($null -ne $expected) { $expected.lastWriteTimeUtc } else { $null }
                currentLastWriteTimeUtc = if ($null -ne $current) { $current.lastWriteTimeUtc } else { $null }
            })
        }
    }
    return [pscustomobject]@{
        unchangedCount = $unchanged
        changedCount = $changed
        metadataOnlyChangedCount = $metadataOnly
        missingCount = $missing
        addedCount = $added
        changes = $changes.ToArray()
        changesTruncated = $false
        omittedChangeCount = 0
    }
}

function New-VerificationPacket {
    param(
        [string]$TraceId,
        [string]$TraceRelative,
        $Envelope,
        [string]$IntegrityVerdict,
        [string[]]$FailureClassifications,
        $Comparison = $null,
        $CurrentInventory = $null
    )
    $sourceManifestSha = $null
    $sourceSidecarSha = $null
    $sourceReadySha = $null
    if ($null -ne $Envelope -and $null -ne $Envelope.snapshot -and $Envelope.snapshot.valid) {
        $sourceManifestSha = $Envelope.snapshot.manifest.sha256
        $sourceSidecarSha = $Envelope.snapshot.sidecar.sha256
        $sourceReadySha = $Envelope.snapshot.ready.sha256
    }
    $expectedCount = 0
    if ($null -ne $Envelope -and $Envelope.valid) { $expectedCount = $Envelope.expectedFileCount }
    $currentCount = 0
    $currentSha = $null
    if ($null -ne $CurrentInventory) {
        $currentCount = $CurrentInventory.fileCount
        $currentSha = $CurrentInventory.inventorySha256
    }
    if ($null -eq $Comparison) {
        $Comparison = [pscustomobject]@{
            unchangedCount = 0; changedCount = 0; metadataOnlyChangedCount = 0
            missingCount = 0; addedCount = 0; changes = @()
            changesTruncated = $false; omittedChangeCount = 0
        }
    }
    $allowedClaims = switch ($IntegrityVerdict) {
        'UNCHANGED' { @('bounded_recorded_row_set_match_observed') }
        'CHANGED' { @('bounded_recorded_row_set_difference_observed') }
        'INDETERMINATE' { @('stable_snapshot_not_obtained') }
        default { @('source_trace_invalid') }
    }
    return [ordered]@{
        schemaVersion = 'awx.artifact_trace_verification.v1'
        verificationId = $VerificationId
        sourceTraceId = $TraceId
        sourceTraceDirectory = $TraceRelative
        expectedManifestSha256 = $ExpectedManifestSha256
        sourceManifestSha256 = $sourceManifestSha
        sourceManifestSidecarSha256 = $sourceSidecarSha
        sourceReadySha256 = $sourceReadySha
        captureSnapshotAtomicity = 'unproven'
        verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        durationMs = [long]$started.ElapsedMilliseconds
        overallVerdict = 'VERIFICATION_ONLY'
        integrityVerdict = $IntegrityVerdict
        mutationAllowed = $false
        deleteAuthorized = $false
        runtimeLineageVerdict = 'HOLD'
        desktopFinalProof = 'evidence_needed'
        expectedFileCount = $expectedCount
        currentFileCount = $currentCount
        unchangedCount = $Comparison.unchangedCount
        changedCount = $Comparison.changedCount
        metadataOnlyChangedCount = $Comparison.metadataOnlyChangedCount
        missingCount = $Comparison.missingCount
        addedCount = $Comparison.addedCount
        currentInventorySha256 = $currentSha
        changesTruncated = $Comparison.changesTruncated
        omittedChangeCount = $Comparison.omittedChangeCount
        changes = @($Comparison.changes)
        failureClassifications = @($FailureClassifications)
        allowedClaims = @($allowedClaims)
        notProofOf = @(
            'capture_atomicity', 'freshness', 'source_patch', 'patch_handoff',
            'desktop_apply', 'runtime', 'deployment', 'approval', 'current_state'
        )
    }
}

function ConvertTo-BudgetedPacketBytes {
    param($Packet)
    $maxBytes = [long]$MaxOutputMiB * 1MB
    $allChanges = @($Packet.changes)
    $totalChanges = $allChanges.Count
    $low = 0
    $high = $totalChanges
    $best = -1
    $bestBytes = $null
    while ($low -le $high) {
        Assert-TimeBudget
        $mid = [int][Math]::Floor(($low + $high) / 2)
        $kept = [object[]]@()
        if ($mid -gt 0) { $kept = [object[]]@($allChanges[0..($mid - 1)]) }
        $Packet['changes'] = $kept
        $Packet['changesTruncated'] = ($mid -lt $totalChanges)
        $Packet['omittedChangeCount'] = $totalChanges - $mid
        $candidateJson = $Packet | ConvertTo-Json -Depth 20
        if (Test-SecretLikeText $candidateJson) { Throw-Verify 'trace-verify-secret-risk' }
        $candidateBytes = $utf8NoBom.GetBytes($candidateJson)
        if ($candidateBytes.Length -le $maxBytes) {
            $best = $mid
            $bestBytes = $candidateBytes
            $low = $mid + 1
        } else {
            $high = $mid - 1
        }
    }
    if ($best -lt 0) { Throw-Verify 'trace-verify-budget-exceeded' }
    $bestChanges = [object[]]@()
    if ($best -gt 0) { $bestChanges = [object[]]@($allChanges[0..($best - 1)]) }
    $Packet['changes'] = $bestChanges
    $Packet['changesTruncated'] = ($best -lt $totalChanges)
    $Packet['omittedChangeCount'] = $totalChanges - $best
    return $bestBytes
}

function Write-DurableBytes {
    param([string]$Path, [byte[]]$Bytes)
    $stream = [IO.File]::Open(
        $Path,
        [IO.FileMode]::Create,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $stream.Write($Bytes, 0, $Bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

function Write-StagedPacket {
    param($Packet, [string]$PacketPath, [string]$SidecarPath, [string]$ReadyPath)
    $packetBytes = ConvertTo-BudgetedPacketBytes -Packet $Packet
    $packetSha = Get-Sha256Bytes $packetBytes
    Write-DurableBytes -Path $PacketPath -Bytes $packetBytes
    Write-DurableBytes `
        -Path $SidecarPath `
        -Bytes $utf8NoBom.GetBytes($packetSha + '  verification.json' + [Environment]::NewLine)
    Write-DurableBytes `
        -Path $ReadyPath `
        -Bytes $utf8NoBom.GetBytes(
            'schemaVersion=awx.artifact_trace_verification_ready.v1' + [Environment]::NewLine +
            'verificationSha256=' + $packetSha + [Environment]::NewLine
        )
    return $packetSha
}

function Assert-StagedPacket {
    param(
        [string]$StagingPath,
        [string]$PacketSha,
        [switch]$OwnerRequired
    )
    $packetPath = Join-Path $StagingPath 'verification.json'
    $sidecarPath = Join-Path $StagingPath 'verification.sha256'
    $readyPath = Join-Path $StagingPath '.ready'
    $ownerPath = Join-Path $StagingPath '.owner'
    $stagedItems = @(Get-ChildItem -LiteralPath $script:temporaryOutput -Force)
    $expectedMemberCount = if ($OwnerRequired) { 4 } else { 3 }
    if ($stagedItems.Count -ne $expectedMemberCount -or
        -not (@($stagedItems.Name) -ccontains 'verification.json') -or
        -not (@($stagedItems.Name) -ccontains 'verification.sha256') -or
        -not (@($stagedItems.Name) -ccontains '.ready') -or
        ($OwnerRequired -and (
            -not (@($stagedItems.Name) -ccontains '.owner') -or
            [IO.File]::ReadAllText($ownerPath, $utf8Strict) -cne $script:temporaryOwnerToken
        )) -or
        (-not $OwnerRequired -and (@($stagedItems.Name) -ccontains '.owner')) -or
        (Get-Sha256Bytes ([IO.File]::ReadAllBytes($packetPath))) -cne $PacketSha -or
        [IO.File]::ReadAllText($sidecarPath, $utf8Strict) -cne ($PacketSha + '  verification.json' + [Environment]::NewLine) -or
        [IO.File]::ReadAllText($readyPath, $utf8Strict) -cne (
            'schemaVersion=awx.artifact_trace_verification_ready.v1' + [Environment]::NewLine +
            'verificationSha256=' + $PacketSha + [Environment]::NewLine
        )) {
        Throw-Verify 'trace-verify-publication-failed'
    }
}

function Set-EnvelopeChangedPacket {
    param($Packet)
    $Packet['verifiedAtUtc'] = [DateTimeOffset]::UtcNow.ToString('o')
    $Packet['durationMs'] = [long]$started.ElapsedMilliseconds
    $Packet['integrityVerdict'] = 'INDETERMINATE'
    $Packet['expectedFileCount'] = 0
    $Packet['currentFileCount'] = 0
    $Packet['unchangedCount'] = 0
    $Packet['changedCount'] = 0
    $Packet['metadataOnlyChangedCount'] = 0
    $Packet['missingCount'] = 0
    $Packet['addedCount'] = 0
    $Packet['currentInventorySha256'] = $null
    $Packet['changes'] = [object[]]@()
    $Packet['changesTruncated'] = $false
    $Packet['omittedChangeCount'] = 0
    $Packet['failureClassifications'] = @('trace-verify-envelope-changed')
    $Packet['allowedClaims'] = @('stable_snapshot_not_obtained')
}

function Publish-VerificationPacketCore {
    param(
        $Packet,
        [string]$CanonicalRoot,
        [string]$TraceFull = '',
        [string]$InitialEnvelopeSignature = ''
    )
    Assert-TimeBudget
    if (-not (Test-Path -LiteralPath $outputParent)) {
        New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
    }
    Assert-NoReparseChain -Candidate $outputParent -CanonicalRoot $CanonicalRoot
    if (Test-Path -LiteralPath $finalOutput) { Throw-Verify 'trace-verify-output-collision' }
    $script:temporaryOutput = Join-Path $outputParent (
        '.' + $VerificationId + '.tmp-' + [guid]::NewGuid().ToString('N')
    )
    New-Item -ItemType Directory -Path $script:temporaryOutput | Out-Null
    $script:temporaryOwnerToken = [guid]::NewGuid().ToString('N')
    $ownerPath = Join-Path $script:temporaryOutput '.owner'
    Write-DurableBytes -Path $ownerPath -Bytes $utf8NoBom.GetBytes($script:temporaryOwnerToken)
    $packetPath = Join-Path $script:temporaryOutput 'verification.json'
    $sidecarPath = Join-Path $script:temporaryOutput 'verification.sha256'
    $readyPath = Join-Path $script:temporaryOutput '.ready'
    $packetSha = Write-StagedPacket `
        -Packet $Packet `
        -PacketPath $packetPath `
        -SidecarPath $sidecarPath `
        -ReadyPath $readyPath
    Assert-StagedPacket `
        -StagingPath $script:temporaryOutput `
        -PacketSha $packetSha `
        -OwnerRequired
    if (-not [string]::IsNullOrWhiteSpace($TraceFull) -and
        -not [string]::IsNullOrWhiteSpace($InitialEnvelopeSignature)) {
        $finalEnvelope = Get-EnvelopeSnapshot -TraceFull $TraceFull
        if (-not $finalEnvelope.valid -or $finalEnvelope.signature -cne $InitialEnvelopeSignature) {
            Set-EnvelopeChangedPacket -Packet $Packet
            $packetSha = Write-StagedPacket `
                -Packet $Packet `
                -PacketPath $packetPath `
                -SidecarPath $sidecarPath `
                -ReadyPath $readyPath
            Assert-StagedPacket `
                -StagingPath $script:temporaryOutput `
                -PacketSha $packetSha `
                -OwnerRequired
        }
    }
    if ([IO.File]::ReadAllText($ownerPath, $utf8Strict) -cne $script:temporaryOwnerToken) {
        Throw-Verify 'trace-verify-publication-failed'
    }
    Remove-Item -LiteralPath $ownerPath -Force
    $script:temporaryOwnerToken = $null
    Assert-StagedPacket -StagingPath $script:temporaryOutput -PacketSha $packetSha
    try {
        [IO.Directory]::Move($script:temporaryOutput, $finalOutput)
    } catch {
        if (Test-Path -LiteralPath $finalOutput) {
            Throw-Verify 'trace-verify-output-collision'
        }
        throw
    }
    $script:temporaryOutput = $null
    $script:temporaryOwnerToken = $null
    return $packetSha
}

function Publish-VerificationPacketOwned {
    param(
        $Packet,
        [string]$CanonicalRoot,
        [string]$TraceFull = '',
        [string]$InitialEnvelopeSignature = ''
    )
    Assert-TimeBudget
    if (-not (Test-Path -LiteralPath $outputParent)) {
        New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
    }
    Assert-NoReparseChain -Candidate $outputParent -CanonicalRoot $CanonicalRoot
    $publicationLockPath = Join-Path $outputParent ('.' + $VerificationId + '.publish.lock')
    $publicationLockStream = $null
    try {
        try {
            $publicationLockStream = [IO.FileStream]::new(
                $publicationLockPath,
                [IO.FileMode]::CreateNew,
                [IO.FileAccess]::ReadWrite,
                [IO.FileShare]::None,
                1,
                ([IO.FileOptions]::DeleteOnClose -bor [IO.FileOptions]::WriteThrough)
            )
        } catch [IO.IOException] {
            if ((Test-Path -LiteralPath $publicationLockPath) -or
                (Test-Path -LiteralPath $finalOutput)) {
                Throw-Verify 'trace-verify-output-collision'
            }
            throw
        }
        return Publish-VerificationPacketCore `
            -Packet $Packet `
            -CanonicalRoot $CanonicalRoot `
            -TraceFull $TraceFull `
            -InitialEnvelopeSignature $InitialEnvelopeSignature
    } finally {
        if ($null -ne $publicationLockStream) { $publicationLockStream.Dispose() }
    }
}

function Publish-VerificationPacket {
    param(
        $Packet,
        [string]$CanonicalRoot,
        [string]$TraceFull = '',
        [string]$InitialEnvelopeSignature = ''
    )
    $mutexMaterial = $CanonicalRoot.ToLowerInvariant() + '|' + $VerificationId
    $mutexName = 'AWX_ARTIFACT_TRACE_VERIFY_' + (Get-Sha256Text $mutexMaterial).Substring(0, 32)
    $publicationMutex = New-Object Threading.Mutex($false, $mutexName)
    $mutexAcquired = $false
    try {
        try {
            $mutexAcquired = $publicationMutex.WaitOne(0)
        } catch [Threading.AbandonedMutexException] {
            $mutexAcquired = $true
        }
        if (-not $mutexAcquired) { Throw-Verify 'trace-verify-output-collision' }
        return Publish-VerificationPacketOwned `
            -Packet $Packet `
            -CanonicalRoot $CanonicalRoot `
            -TraceFull $TraceFull `
            -InitialEnvelopeSignature $InitialEnvelopeSignature
    } finally {
        if ($mutexAcquired) {
            try { $publicationMutex.ReleaseMutex() } catch {}
        }
        $publicationMutex.Dispose()
    }
}

function Write-CompactResult {
    param($Packet, [string]$PacketSha, [string]$OutputRelative)
    [ordered]@{
        schemaVersion = 'awx.artifact_trace_verification_result.v1'
        verificationId = $VerificationId
        integrityVerdict = $Packet.integrityVerdict
        verificationSha256 = $PacketSha
        output = $OutputRelative
        mutationAllowed = $false
        deleteAuthorized = $false
        runtimeLineageVerdict = 'HOLD'
        desktopFinalProof = 'evidence_needed'
    } | ConvertTo-Json -Depth 5
}

function Invoke-ArtifactTraceVerification {
    $canonicalRoot = Get-FullNormalizedPath $Root
    if (-not (Test-Path -LiteralPath $canonicalRoot -PathType Container)) {
        Throw-Verify 'trace-verify-source-not-allowlisted'
    }
    Assert-NoReparseChain -Candidate $canonicalRoot -CanonicalRoot $canonicalRoot

    if ([IO.Path]::IsPathRooted($TraceDirectory)) {
        Throw-Verify 'trace-verify-source-not-allowlisted'
    }
    $traceNormalized = $TraceDirectory.Replace('\', '/')
    if ($traceNormalized -cnotmatch '^data/agent-handoff/artifact-trace/([a-z0-9][a-z0-9-]{0,63})$') {
        Throw-Verify 'trace-verify-source-not-allowlisted'
    }
    $traceId = $Matches[1]
    $traceFull = Get-FullNormalizedPath (Join-Path $canonicalRoot $traceNormalized)
    $traceParent = Get-FullNormalizedPath (Join-Path $canonicalRoot 'data\agent-handoff\artifact-trace')
    if (-not (Test-StrictDescendant $traceFull $traceParent)) {
        Throw-Verify 'trace-verify-source-not-allowlisted'
    }
    Assert-NoReparseChain -Candidate $traceFull -CanonicalRoot $canonicalRoot

    $script:outputParent = Get-FullNormalizedPath (
        Join-Path $canonicalRoot 'data\agent-handoff\artifact-trace-verification'
    )
    $script:finalOutput = Get-FullNormalizedPath (Join-Path $script:outputParent $VerificationId)
    if ((Test-PathEqual $traceFull $script:outputParent) -or
        (Test-StrictDescendant $traceFull $script:outputParent) -or
        (Test-StrictDescendant $script:outputParent $traceFull)) {
        Throw-Verify 'trace-verify-source-not-allowlisted'
    }
    if (Test-Path -LiteralPath $script:finalOutput) { Throw-Verify 'trace-verify-output-collision' }

    $envelope = Get-AuthenticatedEnvelope `
        -TraceFull $traceFull `
        -TraceId $traceId `
        -CanonicalRoot $canonicalRoot
    if (-not $envelope.valid) {
        $packet = New-VerificationPacket `
            -TraceId $traceId `
            -TraceRelative $traceNormalized `
            -Envelope $envelope `
            -IntegrityVerdict 'INVALID' `
            -FailureClassifications @($envelope.reason)
        $packetSha = Publish-VerificationPacket -Packet $packet -CanonicalRoot $canonicalRoot
        return Write-CompactResult `
            -Packet $packet `
            -PacketSha $packetSha `
            -OutputRelative (Get-RelativePathSafe $script:finalOutput $canonicalRoot)
    }

    $initialSignature = $envelope.snapshot.signature
    $current = $null
    $comparison = $null
    $verdict = 'INDETERMINATE'
    $failures = @()
    try {
        $beforeFirst = Get-EnvelopeSnapshot $traceFull
        if (-not $beforeFirst.valid -or $beforeFirst.signature -cne $initialSignature) {
            Throw-Verify 'trace-verify-envelope-changed'
        }
        $first = Get-CurrentInventory -InputFull $envelope.inputFull -CanonicalRoot $canonicalRoot
        $between = Get-EnvelopeSnapshot $traceFull
        if (-not $between.valid -or $between.signature -cne $initialSignature) {
            Throw-Verify 'trace-verify-envelope-changed'
        }
        Start-Sleep -Milliseconds 5
        $second = Get-CurrentInventory -InputFull $envelope.inputFull -CanonicalRoot $canonicalRoot
        $afterSecond = Get-EnvelopeSnapshot $traceFull
        if (-not $afterSecond.valid -or $afterSecond.signature -cne $initialSignature) {
            Throw-Verify 'trace-verify-envelope-changed'
        }
        if ($first.canonical -cne $second.canonical) {
            Throw-Verify 'trace-verify-input-unstable'
        }
        $current = $second
        $comparison = Compare-Inventory -ExpectedRows $envelope.expectedRows -CurrentInventory $current
        if ($comparison.changedCount + $comparison.metadataOnlyChangedCount +
            $comparison.missingCount + $comparison.addedCount -gt 0) {
            $verdict = 'CHANGED'
        } else {
            $verdict = 'UNCHANGED'
        }
        $beforePublish = Get-EnvelopeSnapshot $traceFull
        if (-not $beforePublish.valid -or $beforePublish.signature -cne $initialSignature) {
            Throw-Verify 'trace-verify-envelope-changed'
        }
    } catch {
        $reason = [string]$_.Exception.Message
        if ($reason -eq 'trace-verify-budget-exceeded' -or
            $reason -eq 'trace-verify-reparse-risk' -or
            $reason -eq 'trace-verify-secret-risk') {
            throw
        }
        $verdict = 'INDETERMINATE'
        if ($reason -eq 'trace-verify-envelope-changed') {
            $failures = @('trace-verify-envelope-changed')
        } else {
            $failures = @('trace-verify-input-unstable')
        }
        $current = $null
        $comparison = $null
    }

    $packet = New-VerificationPacket `
        -TraceId $traceId `
        -TraceRelative $traceNormalized `
        -Envelope $envelope `
        -IntegrityVerdict $verdict `
        -FailureClassifications $failures `
        -Comparison $comparison `
        -CurrentInventory $current
    $packetSha = Publish-VerificationPacket `
        -Packet $packet `
        -CanonicalRoot $canonicalRoot `
        -TraceFull $traceFull `
        -InitialEnvelopeSignature $initialSignature
    return Write-CompactResult `
        -Packet $packet `
        -PacketSha $packetSha `
        -OutputRelative (Get-RelativePathSafe $script:finalOutput $canonicalRoot)
}

try {
    Invoke-ArtifactTraceVerification
} catch {
    $reason = [string]$_.Exception.Message
    $ownedTemporary = $false
    if ($null -ne $script:temporaryOutput -and
        $null -ne $script:temporaryOwnerToken -and
        $null -ne $script:outputParent -and
        (Test-Path -LiteralPath $script:temporaryOutput) -and
        (Test-StrictDescendant $script:temporaryOutput $script:outputParent)) {
        $ownerPath = Join-Path $script:temporaryOutput '.owner'
        if (Test-Path -LiteralPath $ownerPath -PathType Leaf) {
            try {
                $ownedTemporary = [IO.File]::ReadAllText($ownerPath, $utf8Strict) -ceq $script:temporaryOwnerToken
            } catch {
                $ownedTemporary = $false
            }
        }
    }
    if ($ownedTemporary) {
        Remove-Item -LiteralPath $script:temporaryOutput -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($reason -notmatch '^trace-verify-') { $reason = 'trace-verify-publication-failed' }
    [Console]::Error.WriteLine($reason)
    exit 1
}

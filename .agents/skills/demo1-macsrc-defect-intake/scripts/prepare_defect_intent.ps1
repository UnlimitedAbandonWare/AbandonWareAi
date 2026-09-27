[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [ValidateSet('Prepare', 'Hold')][string]$Mode = 'Prepare',
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9][a-z0-9.-]{1,63}$')][string]$DefectClass,
    [ValidatePattern('^[a-z0-9][a-z0-9.-]{1,63}$')][string]$FailureClass,
    [Parameter(Mandatory = $true)][string[]]$EvidenceIds,
    [string[]]$TargetFiles,
    [string[]]$WatchRoots,
    [string[]]$BoundaryEvidenceFiles,
    [ValidateSet('GradleSourceSet', 'CallPath')][string]$BoundaryProofType,
    [string]$RedCommand,
    [string]$ExpectedRedSignal,
    [string]$GreenCommand,
    [ValidateSet('NOT_APPLICABLE', 'REQUIRED_READ_ONLY')][string]$SupabaseMode = 'NOT_APPLICABLE',
    [switch]$SupabaseAuthPresent,
    [switch]$SupabaseProjectRefPresent,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

function Throw-Classified {
    param([string]$Class, [string]$Detail)
    throw "$Class`: $Detail"
}

function Get-CanonicalRoot {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        Throw-Classified 'root-not-found' $Path
    }
    $resolved = Resolve-Path -LiteralPath $Path
    $providerPath = [string]$resolved.ProviderPath
    if ([string]::IsNullOrWhiteSpace($providerPath)) { $providerPath = [string]$resolved.Path }
    if ($providerPath -match '^([A-Za-z]):\\') {
        $drive = Get-PSDrive -Name $Matches[1] -ErrorAction SilentlyContinue
        if ($drive -and -not [string]::IsNullOrWhiteSpace([string]$drive.DisplayRoot)) {
            $driveRoot = [IO.Path]::GetPathRoot($providerPath)
            $suffix = $providerPath.Substring($driveRoot.Length)
            $providerPath = if ([string]::IsNullOrWhiteSpace($suffix)) { [string]$drive.DisplayRoot } else { Join-Path ([string]$drive.DisplayRoot) $suffix }
        }
    }
    return $providerPath.TrimEnd('\', '/')
}

function Resolve-RelativeExistingPath {
    param(
        [string]$CanonicalRoot,
        [string]$RelativePath,
        [ValidateSet('Leaf', 'Container')][string]$Kind
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType $Kind)) {
        Throw-Classified 'path-not-found' $RelativePath
    }
    $cursor = $candidate
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Classified 'reparse-path-rejected' $RelativePath
            }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    return [pscustomobject]@{
        FullPath = $candidate
        Relative = $candidate.Substring($prefix.Length).Replace('\', '/')
    }
}

function Resolve-RelativeOutputPath {
    param([string]$CanonicalRoot, [string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $handoffRoot = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot 'data\agent-handoff\macsrc-defect-intake')).TrimEnd('\', '/')
    $handoffPrefix = $handoffRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($handoffPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'output-outside-handoff' $RelativePath
    }
    $cursor = if (Test-Path -LiteralPath $candidate) { $candidate } else { [IO.Path]::GetDirectoryName($candidate) }
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Classified 'reparse-path-rejected' $RelativePath
            }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    return $candidate
}

function Assert-SecretFree {
    param([string[]]$Values)
    $secretPattern = '(?i)(authorization\s*:\s*bearer\s+\S+|(?:api[_-]?key|client[_-]?secret|service[_-]?role|access[_-]?token)\s*[:=]\s*["'']?[^\s"'']{8,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\bsk-[A-Za-z0-9_-]{12,})'
    foreach ($value in $Values) {
        if ($null -ne $value -and $value -match $secretPattern) {
            Throw-Classified 'secret-like-input' 'redacted'
        }
    }
}

function Get-Sha256Lower {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Publish-AtomicRecord {
    param(
        [hashtable]$Record,
        [string]$OutputFullPath,
        [string]$AlreadyExistsClass
    )
    $sidecarPath = $OutputFullPath + '.sha256'
    $readyPath = $OutputFullPath + '.ready'
    if ((Test-Path -LiteralPath $OutputFullPath) -or
        (Test-Path -LiteralPath $sidecarPath) -or
        (Test-Path -LiteralPath $readyPath)) {
        Throw-Classified $AlreadyExistsClass $RunId
    }
    $parent = [IO.Path]::GetDirectoryName($OutputFullPath)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $token = [guid]::NewGuid().ToString('N')
    $tempJson = Join-Path $parent ('.record-' + $token + '.tmp')
    $tempSha = Join-Path $parent ('.record-sha-' + $token + '.tmp')
    $tempReady = Join-Path $parent ('.record-ready-' + $token + '.tmp')
    try {
        $json = $Record | ConvertTo-Json -Depth 12
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($tempJson, $json + [Environment]::NewLine, $utf8)
        $sha = Get-Sha256Lower $tempJson
        [IO.File]::WriteAllText($tempSha, $sha + [Environment]::NewLine, $utf8)
        [IO.File]::WriteAllText($tempReady, $sha + [Environment]::NewLine, $utf8)
        Move-Item -LiteralPath $tempJson -Destination $OutputFullPath
        Move-Item -LiteralPath $tempSha -Destination $sidecarPath
        Move-Item -LiteralPath $tempReady -Destination $readyPath
        return $sha
    } finally {
        foreach ($tempPath in @($tempJson, $tempSha, $tempReady)) {
            if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
        }
    }
}

$canonicalRoot = Get-CanonicalRoot $Root
if ($EvidenceIds.Count -eq 0 -or @($EvidenceIds | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
    Throw-Classified 'evidence-id-missing' 'at least one non-empty evidence ID is required'
}
Assert-SecretFree -Values (@($DefectClass, $FailureClass) + $EvidenceIds + $RedCommand + $ExpectedRedSignal + $GreenCommand)

$rootKind = if ($canonicalRoot.StartsWith('\\')) { 'unc-share' } else { 'local-or-mapped' }
if ($Mode -eq 'Hold') {
    if ([string]::IsNullOrWhiteSpace($FailureClass)) {
        Throw-Classified 'hold-reason-missing' 'FailureClass is required in Hold mode'
    }
    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $OutputPath = "data/agent-handoff/macsrc-defect-intake/$RunId/hold.json"
    }
    $holdOutput = Resolve-RelativeOutputPath $canonicalRoot $OutputPath
    $holdRecord = [ordered]@{
        schemaVersion = 'awx.patch-intake-hold.v1'
        runId = $RunId
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        stage = 'HOLD'
        sourceRoot = $canonicalRoot
        sourceRootKind = $rootKind
        candidateDefectClass = $DefectClass
        failureClass = $FailureClass
        evidenceIds = @($EvidenceIds | Sort-Object -Unique)
        mutationAllowed = $false
        nextAction = 'COLLECT_BOUNDARY_EVIDENCE'
        desktopFinalProof = 'evidence_needed'
    }
    $holdSha = Publish-AtomicRecord -Record $holdRecord -OutputFullPath $holdOutput -AlreadyExistsClass 'hold-already-exists'
    [pscustomobject]@{
        verdict = 'HOLD'
        outputPath = $holdOutput
        recordSha256 = $holdSha
        mutationAllowed = $false
    } | ConvertTo-Json -Compress
    exit 0
}

if ($TargetFiles.Count -eq 0 -or $WatchRoots.Count -eq 0 -or
    $BoundaryEvidenceFiles.Count -eq 0 -or [string]::IsNullOrWhiteSpace($BoundaryProofType) -or
    [string]::IsNullOrWhiteSpace($RedCommand) -or [string]::IsNullOrWhiteSpace($ExpectedRedSignal) -or
    [string]::IsNullOrWhiteSpace($GreenCommand)) {
    Throw-Classified 'intent-not-ready' 'target, watch root, boundary proof, RED, and GREEN are required'
}

$resolvedTargets = @()
foreach ($targetFile in $TargetFiles) {
    $resolvedTargets += Resolve-RelativeExistingPath -CanonicalRoot $canonicalRoot -RelativePath $targetFile -Kind 'Leaf'
}
$resolvedWatchRoots = @()
foreach ($watchRoot in $WatchRoots) {
    $resolvedWatchRoots += Resolve-RelativeExistingPath -CanonicalRoot $canonicalRoot -RelativePath $watchRoot -Kind 'Container'
}
$resolvedBoundary = @()
foreach ($boundaryFile in $BoundaryEvidenceFiles) {
    $resolvedBoundary += Resolve-RelativeExistingPath -CanonicalRoot $canonicalRoot -RelativePath $boundaryFile -Kind 'Leaf'
}

foreach ($target in $resolvedTargets) {
    $covered = @($resolvedWatchRoots | Where-Object {
        $target.Relative -eq $_.Relative -or
        $target.Relative.StartsWith(($_.Relative.TrimEnd('/') + '/'), [StringComparison]::OrdinalIgnoreCase)
    }).Count -gt 0
    if (-not $covered) {
        Throw-Classified 'target-outside-watch-root' $target.Relative
    }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = "data/agent-handoff/macsrc-defect-intake/$RunId/intent.json"
}
$outputFullPath = Resolve-RelativeOutputPath $canonicalRoot $OutputPath
$intent = [ordered]@{
    schemaVersion = 'awx.patch-intent.v1'
    runId = $RunId
    createdAtUtc = [DateTime]::UtcNow.ToString('o')
    stage = 'PREPARED'
    sourceRoot = $canonicalRoot
    sourceRootKind = $rootKind
    defectClass = $DefectClass
    evidenceIds = @($EvidenceIds | Sort-Object -Unique)
    targetFiles = @($resolvedTargets.Relative | Sort-Object -Unique)
    watchRoots = @($resolvedWatchRoots.Relative | Sort-Object -Unique)
    boundaryEvidenceFiles = @($resolvedBoundary.Relative | Sort-Object -Unique)
    boundaryProofType = $BoundaryProofType
    targetPreimages = @($resolvedTargets | ForEach-Object {
        [ordered]@{ path = $_.Relative; sha256 = Get-Sha256Lower $_.FullPath }
    })
    boundaryEvidence = @($resolvedBoundary | ForEach-Object {
        [ordered]@{ path = $_.Relative; sha256 = Get-Sha256Lower $_.FullPath }
    })
    red = [ordered]@{
        command = $RedCommand
        expectedSignal = $ExpectedRedSignal
        status = 'NOT_RUN'
    }
    green = [ordered]@{ command = $GreenCommand; status = 'NOT_RUN' }
    sourceWriteMode = 'UNDECIDED'
    mutationAllowed = $false
    supabase = [ordered]@{
        mode = $SupabaseMode
        authPresent = [bool]$SupabaseAuthPresent
        projectRefPresent = [bool]$SupabaseProjectRefPresent
        mutationAllowed = $false
    }
    nextAction = 'REPRODUCE_RED'
    desktopFinalProof = 'evidence_needed'
}

$intentSha = Publish-AtomicRecord -Record $intent -OutputFullPath $outputFullPath -AlreadyExistsClass 'intent-already-exists'

[pscustomobject]@{
    verdict = 'PREPARED'
    outputPath = $outputFullPath
    intentSha256 = $intentSha
    mutationAllowed = $false
} | ConvertTo-Json -Compress

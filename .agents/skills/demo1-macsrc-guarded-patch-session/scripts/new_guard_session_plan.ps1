[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$IntentFile,
    [Parameter(Mandatory = $true)][string]$RedEvidenceFile,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$leaseContract = Join-Path $PSScriptRoot '..\..\..\..\__patch_drop__\source_edit_lease_contract.ps1'
. $leaseContract
$ProductionMacSrcRoot = '\\desktop-m5nov6k\MacSrc'

function Throw-Classified {
    param([string]$Class, [string]$Detail)
    throw "$Class`: $Detail"
}
function Get-Sha256Lower {
    param([string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-CanonicalRoot {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { Throw-Classified 'root-not-found' $Path }
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
    $providerPath.TrimEnd('\', '/')
}
function Resolve-RootMember {
    param([string]$CanonicalRoot, [string]$RelativePath, [bool]$MustExist)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(':')) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        Throw-Classified 'evidence-file-missing' $RelativePath
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
    $relative = $candidate.Substring($prefix.Length).Replace('\', '/')
    [pscustomobject]@{ FullPath = $candidate; Relative = $relative }
}
function Read-JsonSnapshot {
    param([string]$Path, [string]$FailureClass)
    $stream = $null
    $memory = $null
    $sha = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($stream.Length -gt 1048576) { Throw-Classified $FailureClass 'too-large' }
        $memory = New-Object IO.MemoryStream
        $stream.CopyTo($memory)
        $bytes = $memory.ToArray()
        $sha = [Security.Cryptography.SHA256]::Create()
        $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        $text = (New-Object Text.UTF8Encoding($false, $true)).GetString($bytes)
        $value = $text | ConvertFrom-Json
        [pscustomobject]@{ Value = $value; Sha256 = $hash }
    } catch {
        if ($_.Exception.Message -like "$FailureClass`:*") { throw }
        Throw-Classified $FailureClass 'invalid-json'
    } finally {
        if ($null -ne $sha) { $sha.Dispose() }
        if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
    }
}
function Publish-Plan {
    param([System.Collections.IDictionary]$Record, [string]$Path)
    $shaPath = $Path + '.sha256'
    $readyPath = $Path + '.ready'
    if ((Test-Path -LiteralPath $Path) -or (Test-Path -LiteralPath $shaPath) -or (Test-Path -LiteralPath $readyPath)) {
        Throw-Classified 'session-plan-already-exists' $Path
    }
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $token = [guid]::NewGuid().ToString('N')
    $temps = @(
        (Join-Path $parent ".plan-$token.tmp"),
        (Join-Path $parent ".plan-sha-$token.tmp"),
        (Join-Path $parent ".plan-ready-$token.tmp")
    )
    try {
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temps[0], (($Record | ConvertTo-Json -Depth 12) + [Environment]::NewLine), $utf8)
        $sha = Get-Sha256Lower $temps[0]
        [IO.File]::WriteAllText($temps[1], $sha + [Environment]::NewLine, $utf8)
        [IO.File]::WriteAllText($temps[2], $sha + [Environment]::NewLine, $utf8)
        Move-Item -LiteralPath $temps[0] -Destination $Path
        Move-Item -LiteralPath $temps[1] -Destination $shaPath
        Move-Item -LiteralPath $temps[2] -Destination $readyPath
        return $sha
    } finally {
        foreach ($temp in $temps) { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
    }
}

$canonicalRoot = Get-CanonicalRoot $Root
$intentMember = Resolve-RootMember $canonicalRoot $IntentFile $true
$redMember = Resolve-RootMember $canonicalRoot $RedEvidenceFile $true
$intentSnapshot = Read-JsonSnapshot $intentMember.FullPath 'intent-invalid'
$redSnapshot = Read-JsonSnapshot $redMember.FullPath 'red-evidence-invalid'
$intentSha = $intentSnapshot.Sha256
$intentShaPath = $intentMember.FullPath + '.sha256'
$intentReadyPath = $intentMember.FullPath + '.ready'
if (-not (Test-Path -LiteralPath $intentShaPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $intentReadyPath -PathType Leaf)) {
    Throw-Classified 'intent-publication-incomplete' $intentMember.Relative
}
$sidecarSha = (Get-Content -LiteralPath $intentShaPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
$readySha = (Get-Content -LiteralPath $intentReadyPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
if ($intentSha -ne $sidecarSha -or $intentSha -ne $readySha) {
    Throw-Classified 'intent-checksum-mismatch' $intentMember.Relative
}

$intent = $intentSnapshot.Value
$red = $redSnapshot.Value
if ($intent.schemaVersion -ne 'awx.patch-intent.v1' -or $intent.stage -ne 'PREPARED' -or $intent.mutationAllowed -ne $false) {
    Throw-Classified 'intent-invalid' 'schema-stage-mutation'
}
if ($intent.sourceRoot -ne $canonicalRoot) { Throw-Classified 'intent-root-mismatch' 'sourceRoot' }

$failures = New-Object Collections.Generic.List[string]
$isProductionMacSrc = $canonicalRoot.Equals($ProductionMacSrcRoot, [StringComparison]::OrdinalIgnoreCase)
$rootIdentity = if ($isProductionMacSrc) { 'PRODUCTION_MACSRC' } else { 'NONPRODUCTION' }
$gitEvidenceMode = 'not-run-root-mismatch'
if (-not $isProductionMacSrc) {
    $failures.Add('macsrc-root-mismatch')
} else {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $gitStatusText = @(& git --no-optional-locks -C $canonicalRoot status --short --untracked-files=no 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
        $gitStatusExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($gitStatusExit -eq 0) {
        $gitEvidenceMode = 'native-read-only'
    } elseif ($gitStatusText -match 'dubious ownership') {
        $gitEvidenceMode = 'filesystem-cas'
    } else {
        $gitEvidenceMode = 'filesystem-cas'
    }
}
if ($red.schemaVersion -ne 'awx.red-evidence.v1' -or $red.runId -ne $intent.runId) {
    $failures.Add('red-evidence-invalid')
}
if ($red.intentSha256 -ne $intentSha) { $failures.Add('red-evidence-intent-mismatch') }
if ($red.command -ne $intent.red.command) { $failures.Add('red-evidence-command-mismatch') }
if ([int]$red.exitCode -eq 0 -or $red.expectedSignalMatched -ne $true) { $failures.Add('red-not-reproduced') }
if ([string]$red.outputSha256 -notmatch '^[a-fA-F0-9]{64}$') { $failures.Add('red-output-hash-invalid') }

foreach ($row in @($intent.targetPreimages)) {
    $member = Resolve-RootMember $canonicalRoot ([string]$row.path) $true
    if ((Get-Sha256Lower $member.FullPath) -ne ([string]$row.sha256).ToLowerInvariant()) {
        $failures.Add('guard-preimage-changed')
    }
}
foreach ($row in @($intent.boundaryEvidence)) {
    $member = Resolve-RootMember $canonicalRoot ([string]$row.path) $true
    if ((Get-Sha256Lower $member.FullPath) -ne ([string]$row.sha256).ToLowerInvariant()) {
        $failures.Add('source-boundary-changed')
    }
}
# A plan does not write the Git index. The actual guard rechecks live writers,
# target hashes and ownership immediately before application.
$patchRoot = Join-Path $canonicalRoot '__patch_drop__'
if (Test-Path -LiteralPath $patchRoot -PathType Container) {
    $pending = Get-AwxPendingPatchConflictDecision -PatchDropDir $patchRoot -TargetPaths @($intent.targetFiles)
    if (-not $pending.allowed) {
        $failures.Add('patch-drop-pending')
    }
    $conflict = Get-AwxSourceEditConflictDecision -PatchDropDir $patchRoot -TargetPaths @($intent.targetFiles)
    if (-not $conflict.allowed) {
        $failures.Add('source-lease-present')
    }
}

$failureClasses = @($failures | Sort-Object -Unique)
$verdict = if ($failureClasses.Count -eq 0) { 'READY_FOR_GUARD' } else { 'HOLD' }
$nextAction = if ($verdict -eq 'READY_FOR_GUARD') {
    'RUN_GUARD_PREPARE'
} elseif (@($failureClasses | Where-Object { $_ -in @('guard-preimage-changed', 'source-boundary-changed', 'intent-checksum-mismatch') }).Count -gt 0) {
    'RESTART_INTAKE'
} elseif (@($failureClasses | Where-Object { $_ -in @('macsrc-root-mismatch', 'patch-drop-pending', 'source-lease-present', 'index-lock-present') }).Count -gt 0) {
    'RESOLVE_SOURCE_OWNERSHIP'
} else {
    'REPRODUCE_RED'
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = "data/agent-handoff/macsrc-guarded-patch/$($intent.runId)/plan.json"
}
$outputMember = Resolve-RootMember $canonicalRoot $OutputPath $false
$handoffRoot = [IO.Path]::GetFullPath((Join-Path $canonicalRoot 'data\agent-handoff\macsrc-guarded-patch')).TrimEnd('\', '/')
$handoffPrefix = $handoffRoot + [IO.Path]::DirectorySeparatorChar
if (-not $outputMember.FullPath.StartsWith($handoffPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    Throw-Classified 'output-outside-handoff' $outputMember.Relative
}
$plan = [ordered]@{
    schemaVersion = 'awx.guard-session-plan.v1'
    runId = $intent.runId
    createdAtUtc = [DateTime]::UtcNow.ToString('o')
    verdict = $verdict
    failureClasses = $failureClasses
    canonicalRoot = $canonicalRoot
    expectedMacSrcRoot = $ProductionMacSrcRoot
    rootIdentity = $rootIdentity
    gitEvidenceMode = $gitEvidenceMode
    globalSafeDirectoryMutation = $false
    intentFile = $intentMember.Relative
    intentSha256 = $intentSha
    redEvidenceFile = $redMember.Relative
    redEvidenceSha256 = $redSnapshot.Sha256
    targetFiles = @($intent.targetFiles)
    watchRoots = @($intent.watchRoots)
    boundaryEvidenceFiles = @($intent.boundaryEvidenceFiles)
    requiredGuardSkill = 'demo1-macsrc-smb-direct-patch'
    phaseSequence = @('Prepare', 'Verify', 'RED_CONFIRMED', 'APPLY_PATCH', 'GREEN', 'Complete|Abort')
    mutationAllowed = $false
    nextAction = $nextAction
    desktopFinalProof = 'evidence_needed'
}
$planSha = Publish-Plan $plan $outputMember.FullPath
[pscustomobject]@{
    verdict = $verdict
    outputPath = $outputMember.FullPath
    planSha256 = $planSha
    nextAction = $nextAction
    mutationAllowed = $false
} | ConvertTo-Json -Compress

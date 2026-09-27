[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [Parameter(Mandatory = $true)][string]$IntentFile,
    [Parameter(Mandatory = $true)][string]$SessionFile,
    [Parameter(Mandatory = $true)][string]$OutcomeFile,
    [Parameter(Mandatory = $true)][string]$VerificationFile,
    [Parameter(Mandatory = $true)][string]$BeforeIntegrityFile,
    [Parameter(Mandatory = $true)][string]$AfterIntegrityFile,
    [string]$SupabaseEvidenceFile,
    [string]$AutograderEvidenceFile,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$MaxInputBytes = 1048576
$MaxPacketBytes = 262144

function Throw-Classified {
    param([string]$Class, [string]$Detail)
    throw "$Class`: $Detail"
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
function Resolve-RootFile {
    param([string]$CanonicalRoot, [string]$RelativePath)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(':')) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { Throw-Classified 'path-outside-root' $RelativePath }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { Throw-Classified 'evidence-file-missing' $RelativePath }
    if ((Get-Item -LiteralPath $candidate).Length -gt $MaxInputBytes) { Throw-Classified 'evidence-file-too-large' $RelativePath }
    $cursor = $candidate
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Throw-Classified 'reparse-path-rejected' $RelativePath }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    [pscustomobject]@{
        FullPath = $candidate
        Relative = $candidate.Substring($prefix.Length).Replace('\', '/')
    }
}
function Get-Sha256Lower {
    param([string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Write-JsonAtomic {
    param([string]$Path, [object]$Value)
    if (Test-Path -LiteralPath $Path) { Throw-Classified 'tri-query-run-already-exists' $Path }
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temp = Join-Path $parent ('.tri-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $json = ($Value | ConvertTo-Json -Depth 14) + [Environment]::NewLine
        if ([Text.Encoding]::UTF8.GetByteCount($json) -gt $MaxPacketBytes) { Throw-Classified 'tri-query-packet-too-large' $Path }
        [IO.File]::WriteAllText($temp, $json, (New-Object Text.UTF8Encoding($false)))
        Move-Item -LiteralPath $temp -Destination $Path
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}
function Publish-Manifest {
    param([string]$Path, [object]$Value)
    $shaPath = $Path + '.sha256'
    $readyPath = $Path + '.ready'
    if ((Test-Path -LiteralPath $Path) -or (Test-Path -LiteralPath $shaPath) -or (Test-Path -LiteralPath $readyPath)) {
        Throw-Classified 'tri-query-run-already-exists' $Path
    }
    Write-JsonAtomic -Path $Path -Value $Value
    $sha = Get-Sha256Lower $Path
    $utf8 = New-Object Text.UTF8Encoding($false)
    $shaTemp = $shaPath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    $readyTemp = $readyPath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($shaTemp, $sha + [Environment]::NewLine, $utf8)
        [IO.File]::WriteAllText($readyTemp, $sha + [Environment]::NewLine, $utf8)
        Move-Item -LiteralPath $shaTemp -Destination $shaPath
        Move-Item -LiteralPath $readyTemp -Destination $readyPath
    } finally {
        foreach ($temp in @($shaTemp, $readyTemp)) { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
    }
    $sha
}

$canonicalRoot = Get-CanonicalRoot $Root
$members = [ordered]@{
    intent = Resolve-RootFile $canonicalRoot $IntentFile
    session = Resolve-RootFile $canonicalRoot $SessionFile
    outcome = Resolve-RootFile $canonicalRoot $OutcomeFile
    verification = Resolve-RootFile $canonicalRoot $VerificationFile
    beforeIntegrity = Resolve-RootFile $canonicalRoot $BeforeIntegrityFile
    afterIntegrity = Resolve-RootFile $canonicalRoot $AfterIntegrityFile
}
if (-not [string]::IsNullOrWhiteSpace($SupabaseEvidenceFile)) {
    $members.supabase = Resolve-RootFile $canonicalRoot $SupabaseEvidenceFile
}
if (-not [string]::IsNullOrWhiteSpace($AutograderEvidenceFile)) {
    $members.autograder = Resolve-RootFile $canonicalRoot $AutograderEvidenceFile
}

$expectedRelativeDirectory = "data/agent-handoff/macsrc-patch-postprocess/$RunId/tri-query"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = $expectedRelativeDirectory }
$expectedFull = [IO.Path]::GetFullPath((Join-Path $canonicalRoot $expectedRelativeDirectory)).TrimEnd('\', '/')
$outputFull = [IO.Path]::GetFullPath((Join-Path $canonicalRoot $OutputDirectory)).TrimEnd('\', '/')
if (-not $outputFull.Equals($expectedFull, [StringComparison]::OrdinalIgnoreCase)) {
    Throw-Classified 'output-outside-handoff' $OutputDirectory
}
if (Test-Path -LiteralPath $outputFull) {
    if (@(Get-ChildItem -LiteralPath $outputFull -Force -ErrorAction SilentlyContinue).Count -gt 0) {
        Throw-Classified 'tri-query-run-already-exists' $RunId
    }
} else {
    New-Item -ItemType Directory -Path $outputFull -Force | Out-Null
}

$inputHashes = [ordered]@{}
$inputPaths = [ordered]@{}
foreach ($key in $members.Keys) {
    $inputHashes[$key] = Get-Sha256Lower $members[$key].FullPath
    $inputPaths[$key] = $members[$key].Relative
}
$shared = [ordered]@{
    subjectRunId = $RunId
    subjectManifestFile = "$expectedRelativeDirectory/subject.manifest.json"
    subjectInputHashes = $inputHashes
    mutationAllowed = $false
    timeoutSeconds = 600
    maxOutputBytes = $MaxPacketBytes
    redaction = 'hash-count-reason-only'
}
$positiveRequest = [ordered]@{} + $shared
$positiveRequest.schemaVersion = 'awx.patch-tri-query.request.v1'
$positiveRequest.packetType = 'POSITIVE_QUERY'
$positiveRequest.mayRead = @('subject.manifest.json')
$positiveRequest.mustNotRead = @('negative.packet.json', 'neutral.packet.json')
$positiveRequest.outputFile = 'positive.packet.json'
$negativeRequest = [ordered]@{} + $shared
$negativeRequest.schemaVersion = 'awx.patch-tri-query.request.v1'
$negativeRequest.packetType = 'NEGATIVE_QUERY'
$negativeRequest.mayRead = @('subject.manifest.json')
$negativeRequest.mustNotRead = @('positive.packet.json', 'neutral.packet.json')
$negativeRequest.outputFile = 'negative.packet.json'
$neutralRequest = [ordered]@{} + $shared
$neutralRequest.schemaVersion = 'awx.patch-tri-query.request.v1'
$neutralRequest.packetType = 'NEUTRAL_QUERY'
$neutralRequest.mayRead = @('subject.manifest.json', 'positive.packet.json', 'negative.packet.json')
$neutralRequest.mustNotRead = @('new-external-evidence')
$neutralRequest.outputFile = 'neutral.packet.json'
$neutralRequest.goalScoreComponentNames = @(
    'evidenceStrength', 'causalStrength', 'verificationFeasibility',
    'userValue', 'reversibility', 'costEfficiency', 'timeFit',
    'blastRadius', 'ambiguity', 'authorityOrSafetyExpansion'
)
$neutralRequest.allowedGoalScoreEvidenceIds = @($inputHashes.Keys | ForEach-Object { [string]$_ })
$dispatch = [ordered]@{
    schemaVersion = 'awx.patch-tri-query.dispatch.v1'
    subjectRunId = $RunId
    mutationAllowed = $false
    stages = @(
        [ordered]@{ stage = 1; execution = 'parallel'; roles = @('POSITIVE_QUERY', 'NEGATIVE_QUERY'); requests = @('positive.request.json', 'negative.request.json') },
        [ordered]@{ stage = 2; execution = 'after-stage-1'; roles = @('NEUTRAL_QUERY'); requests = @('neutral.request.json') },
        [ordered]@{ stage = 3; execution = 'deterministic'; roles = @('FINALIZER'); command = 'new_postprocess_packet.ps1' }
    )
}

$positiveRequestPath = Join-Path $outputFull 'positive.request.json'
$negativeRequestPath = Join-Path $outputFull 'negative.request.json'
$neutralRequestPath = Join-Path $outputFull 'neutral.request.json'
$dispatchPath = Join-Path $outputFull 'dispatch-plan.json'
Write-JsonAtomic $positiveRequestPath $positiveRequest
Write-JsonAtomic $negativeRequestPath $negativeRequest
Write-JsonAtomic $neutralRequestPath $neutralRequest
Write-JsonAtomic $dispatchPath $dispatch
$manifest = [ordered]@{
    schemaVersion = 'awx.patch-tri-query.subject.v1'
    subjectRunId = $RunId
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    inputPaths = $inputPaths
    inputHashes = $inputHashes
    requestHashes = [ordered]@{
        positive = Get-Sha256Lower $positiveRequestPath
        negative = Get-Sha256Lower $negativeRequestPath
        neutral = Get-Sha256Lower $neutralRequestPath
        dispatch = Get-Sha256Lower $dispatchPath
    }
    executionOrder = @('POSITIVE_QUERY+NEGATIVE_QUERY', 'NEUTRAL_QUERY', 'FINALIZER')
    mutationAllowed = $false
    desktopFinalProof = 'evidence_needed'
}
$manifestPath = Join-Path $outputFull 'subject.manifest.json'
$manifestSha = Publish-Manifest $manifestPath $manifest
[pscustomobject]@{
    status = 'PREPARED'
    runId = $RunId
    outputDirectory = $outputFull
    manifestSha256 = $manifestSha
    nextStage = 'DISPATCH_POSITIVE_NEGATIVE_PARALLEL'
    mutationAllowed = $false
} | ConvertTo-Json -Compress

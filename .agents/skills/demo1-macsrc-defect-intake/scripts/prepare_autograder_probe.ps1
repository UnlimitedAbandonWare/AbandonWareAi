[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [Parameter(Mandatory = $true)][string]$AutograderEvidenceFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$MaxEvidenceBytes = 1048576
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
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { Throw-Classified 'autograder-evidence-missing' $RelativePath }
    $cursor = $candidate
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Throw-Classified 'reparse-path-rejected' $RelativePath }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    $candidate
}
function Read-EvidenceSnapshot {
    param([string]$Path)
    $stream = $null; $memory = $null; $sha = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($stream.Length -gt $MaxEvidenceBytes) { Throw-Classified 'autograder-evidence-too-large' $Path }
        $memory = New-Object IO.MemoryStream
        $stream.CopyTo($memory)
        $bytes = $memory.ToArray()
        $sha = [Security.Cryptography.SHA256]::Create()
        $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        $text = (New-Object Text.UTF8Encoding($false, $true)).GetString($bytes)
        try { $value = $text | ConvertFrom-Json } catch { Throw-Classified 'autograder-evidence-invalid' 'invalid-json' }
        [pscustomobject]@{ Value = $value; Sha256 = $hash }
    } finally {
        if ($null -ne $sha) { $sha.Dispose() }
        if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
    }
}
function Write-JsonAtomic {
    param([string]$Path, [object]$Value)
    if (Test-Path -LiteralPath $Path) { Throw-Classified 'autograder-probe-already-exists' $RunId }
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temp = Join-Path $parent ('.probe-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $json = ($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine
        if ([Text.Encoding]::UTF8.GetByteCount($json) -gt $MaxPacketBytes) { Throw-Classified 'autograder-probe-packet-too-large' $Path }
        [IO.File]::WriteAllText($temp, $json, (New-Object Text.UTF8Encoding($false)))
        Move-Item -LiteralPath $temp -Destination $Path
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}
function Get-Sha256Lower {
    param([string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$canonicalRoot = Get-CanonicalRoot $Root
$evidencePath = Resolve-RootFile $canonicalRoot $AutograderEvidenceFile
$snapshot = Read-EvidenceSnapshot $evidencePath
$evidence = $snapshot.Value
$requiredFields = @('schemaVersion', 'runId', 'exitCode', 'failingTestCount', 'failureClass', 'outputSha256', 'mutationAllowed')
$allowedFields = $requiredFields + @('observedAtUtc', 'durationMs')
$fieldNames = @($evidence.PSObject.Properties.Name)
foreach ($field in $requiredFields) { if ($fieldNames -notcontains $field) { Throw-Classified 'autograder-evidence-invalid' $field } }
foreach ($field in $fieldNames) { if ($allowedFields -notcontains $field) { Throw-Classified 'autograder-evidence-field-not-allowed' $field } }
if ($evidence.schemaVersion -ne 'awx.autograder.evidence.v1' -or $evidence.runId -ne $RunId -or
    $evidence.mutationAllowed -ne $false -or [string]$evidence.outputSha256 -notmatch '^[a-fA-F0-9]{64}$' -or
    [string]$evidence.failureClass -notmatch '^[a-z0-9][a-z0-9.-]{1,63}$') {
    Throw-Classified 'autograder-evidence-invalid' 'schema-run-hash-mutation'
}

$relativeProbeDir = "data/agent-handoff/macsrc-defect-intake/$RunId/autograder-probe"
$probeDir = [IO.Path]::GetFullPath((Join-Path $canonicalRoot $relativeProbeDir))
if (Test-Path -LiteralPath $probeDir) {
    if (@(Get-ChildItem -LiteralPath $probeDir -Force -ErrorAction SilentlyContinue).Count -gt 0) {
        Throw-Classified 'autograder-probe-already-exists' $RunId
    }
} else {
    New-Item -ItemType Directory -Path $probeDir -Force | Out-Null
}
$shared = [ordered]@{
    schemaVersion = 'awx.autograder.probe-request.v1'
    subjectRunId = $RunId
    evidenceSha256 = $snapshot.Sha256
    evidenceFile = $AutograderEvidenceFile.Replace('\', '/')
    mutationAllowed = $false
    timeoutSeconds = 600
    maxOutputBytes = $MaxPacketBytes
    redaction = 'hash-count-reason-only'
}
$positive = [ordered]@{} + $shared
$positive.packetType = 'POSITIVE_QUERY'
$positive.mayRead = @('probe.manifest.json')
$positive.mustNotRead = @('negative.packet.json', 'neutral.packet.json')
$positive.outputFile = 'positive.packet.json'
$negative = [ordered]@{} + $shared
$negative.packetType = 'NEGATIVE_QUERY'
$negative.mayRead = @('probe.manifest.json')
$negative.mustNotRead = @('positive.packet.json', 'neutral.packet.json')
$negative.outputFile = 'negative.packet.json'
$neutral = [ordered]@{} + $shared
$neutral.packetType = 'NEUTRAL_QUERY'
$neutral.mayRead = @('probe.manifest.json', 'positive.packet.json', 'negative.packet.json')
$neutral.mustNotRead = @('new-external-evidence')
$neutral.outputFile = 'neutral.packet.json'
$dispatch = [ordered]@{
    schemaVersion = 'awx.autograder.probe-dispatch.v1'
    subjectRunId = $RunId
    mutationAllowed = $false
    stages = @(
        [ordered]@{ stage = 1; execution = 'parallel'; roles = @('POSITIVE_QUERY', 'NEGATIVE_QUERY'); requests = @('positive.request.json', 'negative.request.json') },
        [ordered]@{ stage = 2; execution = 'after-stage-1'; roles = @('NEUTRAL_QUERY'); requests = @('neutral.request.json') },
        [ordered]@{
            stage = 3
            execution = 'deterministic'
            roles = @('INTAKE_DECISION')
            command = '.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1'
            mode = 'Finalize'
            rule = 'new PatchIntent only after order-stable Neutral APPLY and separate intent specification'
        },
        [ordered]@{
            stage = 4
            execution = 'conditional-deterministic'
            roles = @('DOCKER_RED_GRADER')
            command = '.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1'
            mode = 'GradeSandbox'
            gate = 'decision=APPLY and ready intent-spec.json and ready Docker job'
        },
        [ordered]@{
            stage = 5
            execution = 'conditional-deterministic'
            roles = @('INTENT_PROMOTION')
            command = '.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1'
            mode = 'PromoteIntent'
            gate = 'ready COMPLETE failing grade transition with expected signal'
        },
        [ordered]@{
            stage = 6
            execution = 'conditional-deterministic'
            roles = @('GUARD_SESSION_PLANNER')
            command = '.agents/skills/demo1-macsrc-defect-intake/scripts/advance_autograder_probe.ps1'
            mode = 'PlanSession'
            gate = 'ready promotion.json and derived ready RED evidence'
        }
    )
}
Write-JsonAtomic (Join-Path $probeDir 'positive.request.json') $positive
Write-JsonAtomic (Join-Path $probeDir 'negative.request.json') $negative
Write-JsonAtomic (Join-Path $probeDir 'neutral.request.json') $neutral
Write-JsonAtomic (Join-Path $probeDir 'dispatch-plan.json') $dispatch
$manifest = [ordered]@{
    schemaVersion = 'awx.autograder.probe-manifest.v1'
    subjectRunId = $RunId
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    evidenceFile = $AutograderEvidenceFile.Replace('\', '/')
    evidenceSha256 = $snapshot.Sha256
    exitCode = [int]$evidence.exitCode
    failingTestCount = [int]$evidence.failingTestCount
    failureClass = [string]$evidence.failureClass
    concreteRedCandidate = ([int]$evidence.exitCode -ne 0 -and [int]$evidence.failingTestCount -gt 0)
    mutationAllowed = $false
    nextAction = 'DISPATCH_POSITIVE_NEGATIVE_PARALLEL'
    desktopFinalProof = 'evidence_needed'
}
$manifestPath = Join-Path $probeDir 'probe.manifest.json'
Write-JsonAtomic $manifestPath $manifest
$manifestSha = Get-Sha256Lower $manifestPath
$utf8 = New-Object Text.UTF8Encoding($false)
$shaPath = $manifestPath + '.sha256'
$readyPath = $manifestPath + '.ready'
$shaTemp = $shaPath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
$readyTemp = $readyPath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
try {
    [IO.File]::WriteAllText($shaTemp, $manifestSha + [Environment]::NewLine, $utf8)
    [IO.File]::WriteAllText($readyTemp, $manifestSha + [Environment]::NewLine, $utf8)
    Move-Item -LiteralPath $shaTemp -Destination $shaPath
    Move-Item -LiteralPath $readyTemp -Destination $readyPath
} finally {
    foreach ($temp in @($shaTemp, $readyTemp)) { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
}
[pscustomobject]@{
    status = 'PREPARED'
    runId = $RunId
    probeDirectory = $probeDir
    nextAction = 'DISPATCH_POSITIVE_NEGATIVE_PARALLEL'
    mutationAllowed = $false
} | ConvertTo-Json -Compress

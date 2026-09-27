[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [Parameter(Mandatory = $true)][ValidateSet('RED_PROBE', 'GREEN_VERIFICATION')][string]$Purpose,
    [Parameter(Mandatory = $true)][ValidateSet('pytest', 'gradle-junit')][string]$Profile,
    [Parameter(Mandatory = $true)][string]$ImageRef,
    [Parameter(Mandatory = $true)][string[]]$IncludePaths,
    [Parameter(Mandatory = $true)][string[]]$TestSelectors,
    [Parameter(Mandatory = $true)][string]$DeclaredTestCommand,
    [string]$ExpectedSignal = '',
    [Parameter(Mandatory = $true)][string]$DecisionFile,
    [Parameter(Mandatory = $true)][string]$IntentSpecFile,
    [double]$Cpus = 0.5,
    [int]$MemoryMb = 512,
    [int]$Pids = 128,
    [int]$TimeoutSeconds = 600,
    [int]$MaxFiles = 2048,
    [long]$MaxInputBytes = 268435456,
    [int]$MaxLogBytes = 262144,
    [int]$MaxXmlBytes = 4194304,
    [int]$MaxTestCases = 100000,
    [string]$OutputPath,
    [switch]$ContractTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProductionMacSrcRoot = '\\desktop-m5nov6k\MacSrc'
$MaxJsonBytes = 262144
$SecretPattern = [regex]'(?:sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,})'

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
function Resolve-RootMember {
    param([string]$CanonicalRoot, [string]$RelativePath, [bool]$MustExist)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or $RelativePath.Contains(':')) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { Throw-Classified 'path-outside-root' $RelativePath }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { Throw-Classified 'evidence-file-missing' $RelativePath }
    $cursor = if (Test-Path -LiteralPath $candidate) { $candidate } else { [IO.Path]::GetDirectoryName($candidate) }
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Throw-Classified 'reparse-path-rejected' $RelativePath }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    [pscustomobject]@{ FullPath = $candidate; Relative = $candidate.Substring($prefix.Length).Replace('\', '/') }
}
function Get-Sha256Lower {
    param([string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Read-ReadyHash {
    param([string]$Path, [string]$FailureClass)
    if ((Get-Item -LiteralPath $Path).Length -gt $MaxJsonBytes) { Throw-Classified $FailureClass 'too-large' }
    $hash = Get-Sha256Lower $Path
    $sidecarPath = $Path + '.sha256'; $readyPath = $Path + '.ready'
    if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf) -or -not (Test-Path -LiteralPath $readyPath -PathType Leaf)) {
        Throw-Classified $FailureClass 'publication-incomplete'
    }
    $sidecar = (Get-Content -LiteralPath $sidecarPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
    $ready = (Get-Content -LiteralPath $readyPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
    if ($hash -ne $sidecar -or $hash -ne $ready) { Throw-Classified $FailureClass 'checksum-mismatch' }
    $hash
}
function Publish-ReadyJson {
    param([System.Collections.IDictionary]$Value, [string]$Path)
    foreach ($candidate in @($Path, $Path + '.sha256', $Path + '.ready')) {
        if (Test-Path -LiteralPath $candidate) { Throw-Classified 'autograder-job-already-exists' $candidate }
    }
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $token = [guid]::NewGuid().ToString('N')
    $temps = @(
        (Join-Path $parent ".job-$token.tmp"),
        (Join-Path $parent ".job-sha-$token.tmp"),
        (Join-Path $parent ".job-ready-$token.tmp")
    )
    try {
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temps[0], (($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine), $utf8)
        $hash = Get-Sha256Lower $temps[0]
        [IO.File]::WriteAllText($temps[1], $hash + [Environment]::NewLine, $utf8)
        [IO.File]::WriteAllText($temps[2], $hash + [Environment]::NewLine, $utf8)
        Move-Item -LiteralPath $temps[0] -Destination $Path
        Move-Item -LiteralPath $temps[1] -Destination ($Path + '.sha256')
        Move-Item -LiteralPath $temps[2] -Destination ($Path + '.ready')
        $hash
    } finally {
        foreach ($temp in $temps) { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
    }
}

$canonicalRoot = Get-CanonicalRoot $Root
if ($ContractTest) {
    if ($canonicalRoot.StartsWith('\\', [StringComparison]::Ordinal)) { Throw-Classified 'contract-root-must-be-local' $canonicalRoot }
    $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    if (-not $canonicalRoot.StartsWith($systemTemp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'contract-root-must-be-temp' $canonicalRoot
    }
} elseif (-not $canonicalRoot.Equals($ProductionMacSrcRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Throw-Classified 'macsrc-root-mismatch' $canonicalRoot
}
if ($ImageRef -notmatch '^[A-Za-z0-9][A-Za-z0-9._/:@-]*@sha256:[a-f0-9]{64}$') { Throw-Classified 'image-not-digest-pinned' $ImageRef }
if ($IncludePaths.Count -lt 1 -or $IncludePaths.Count -gt 128 -or $TestSelectors.Count -lt 1 -or $TestSelectors.Count -gt 64) {
    Throw-Classified 'autograder-job-invalid' 'input-count'
}
if ([string]::IsNullOrWhiteSpace($DeclaredTestCommand) -or $DeclaredTestCommand.Length -gt 1024 -or $DeclaredTestCommand -match '[\r\n]') {
    Throw-Classified 'autograder-job-invalid' 'declared-test-command'
}
if ($Purpose -eq 'RED_PROBE' -and [string]::IsNullOrWhiteSpace($ExpectedSignal)) { Throw-Classified 'autograder-job-invalid' 'expected-signal-required' }
if ($ExpectedSignal.Length -gt 256 -or $SecretPattern.Matches(($DeclaredTestCommand + $ExpectedSignal + ($IncludePaths -join '') + ($TestSelectors -join ''))).Count -gt 0) {
    Throw-Classified 'secret-leak-risk' 'job-input'
}
if ($Cpus -lt 0.1 -or $Cpus -gt 4.0 -or $MemoryMb -lt 64 -or $MemoryMb -gt 4096 -or
    $Pids -lt 16 -or $Pids -gt 512 -or $TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 1800 -or
    $MaxFiles -lt 1 -or $MaxFiles -gt 20000 -or $MaxInputBytes -lt 1 -or $MaxInputBytes -gt 1073741824 -or
    $MaxLogBytes -lt 1024 -or $MaxLogBytes -gt 4194304 -or $MaxXmlBytes -lt 1024 -or $MaxXmlBytes -gt 16777216 -or
    $MaxTestCases -lt 1 -or $MaxTestCases -gt 1000000) {
    Throw-Classified 'autograder-limits-invalid' 'out-of-range'
}
$normalizedIncludes = @()
foreach ($relative in $IncludePaths) { $normalizedIncludes += (Resolve-RootMember $canonicalRoot $relative $true).Relative }
$normalizedSelectors = @()
foreach ($selector in $TestSelectors) {
    if ([string]::IsNullOrWhiteSpace($selector) -or $selector.StartsWith('-') -or $selector.Length -gt 240 -or $selector -notmatch '^[A-Za-z0-9_./:*\[\]-]+$') {
        Throw-Classified 'selector-invalid' $selector
    }
    $normalizedSelectors += $selector
}
$decisionMember = Resolve-RootMember $canonicalRoot $DecisionFile $true
$intentSpecMember = Resolve-RootMember $canonicalRoot $IntentSpecFile $true
$decisionSha = Read-ReadyHash $decisionMember.FullPath 'autograder-upstream-invalid'
$intentSpecSha = Read-ReadyHash $intentSpecMember.FullPath 'autograder-upstream-invalid'

$limits = [ordered]@{
    cpus = $Cpus; memoryMb = $MemoryMb; pids = $Pids; timeoutSeconds = $TimeoutSeconds
    maxFiles = $MaxFiles; maxInputBytes = $MaxInputBytes; maxLogBytes = $MaxLogBytes
    maxXmlBytes = $MaxXmlBytes; maxTestCases = $MaxTestCases
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) { $OutputPath = "data/agent-handoff/docker-autograder/$RunId/job.json" }
$outputMember = Resolve-RootMember $canonicalRoot $OutputPath $false
$expectedDirectory = [IO.Path]::GetFullPath((Join-Path $canonicalRoot "data/agent-handoff/docker-autograder/$RunId")).TrimEnd('\', '/')
if (-not ([IO.Path]::GetDirectoryName($outputMember.FullPath)).Equals($expectedDirectory, [StringComparison]::OrdinalIgnoreCase)) {
    Throw-Classified 'output-outside-handoff' $outputMember.Relative
}
$job = [ordered]@{
    schemaVersion = 'awx.docker-autograder.job.v1'
    runId = $RunId
    purpose = $Purpose
    profile = $Profile
    imageRef = $ImageRef
    includePaths = @($normalizedIncludes | Sort-Object -Unique)
    testSelectors = @($normalizedSelectors | Sort-Object -Unique)
    declaredTestCommand = $DeclaredTestCommand
    expectedSignal = $ExpectedSignal
    decisionSha256 = $decisionSha
    intentSpecSha256 = $intentSpecSha
    limits = $limits
    networkMode = 'none'
    pullPolicy = 'never'
    mutationAllowed = $false
}
$jobSha = Publish-ReadyJson $job $outputMember.FullPath
[pscustomobject]@{
    status = 'PREPARED'
    runId = $RunId
    purpose = $Purpose
    outputPath = $outputMember.FullPath
    jobSha256 = $jobSha
    nextAction = 'RUN_DOCKER_AUTOGRADER'
    mutationAllowed = $false
} | ConvertTo-Json -Compress

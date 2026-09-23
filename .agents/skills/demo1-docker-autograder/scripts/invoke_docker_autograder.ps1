[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [Parameter(Mandatory = $true)][string]$JobFile,
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$DockerBin = 'docker',
    [switch]$ContractTest,
    [string]$CompletedDirectiveRequest,
    [string]$CleanupLogDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProductionMacSrcRoot = '\\desktop-m5nov6k\MacSrc'
$MaxJsonBytes = 262144

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
            $providerPath = if ([string]::IsNullOrWhiteSpace($suffix)) {
                [string]$drive.DisplayRoot
            } else {
                Join-Path ([string]$drive.DisplayRoot) $suffix
            }
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
    [pscustomobject]@{
        FullPath = $candidate
        Relative = $candidate.Substring($prefix.Length).Replace('\', '/')
    }
}
function Read-ReadyJsonSnapshot {
    param([string]$Path)
    $stream = $null
    $memory = $null
    $sha = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        if ($stream.Length -gt $MaxJsonBytes) { Throw-Classified 'job-too-large' $Path }
        $memory = New-Object IO.MemoryStream
        $stream.CopyTo($memory)
        $bytes = $memory.ToArray()
        $sha = [Security.Cryptography.SHA256]::Create()
        $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
        $text = (New-Object Text.UTF8Encoding($false, $true)).GetString($bytes)
        try { $value = $text | ConvertFrom-Json } catch { Throw-Classified 'job-invalid' 'invalid-json' }
    } finally {
        if ($null -ne $sha) { $sha.Dispose() }
        if ($null -ne $memory) { $memory.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
    }
    $sidecar = $Path + '.sha256'
    $ready = $Path + '.ready'
    if (-not (Test-Path -LiteralPath $sidecar -PathType Leaf) -or -not (Test-Path -LiteralPath $ready -PathType Leaf)) {
        Throw-Classified 'job-publication-incomplete' $Path
    }
    $sidecarHash = (Get-Content -LiteralPath $sidecar -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
    $readyHash = (Get-Content -LiteralPath $ready -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
    if ($hash -ne $sidecarHash -or $hash -ne $readyHash) { Throw-Classified 'job-checksum-mismatch' $Path }
    [pscustomobject]@{ Value = $value; Sha256 = $hash }
}

function Invoke-CompletedDirectiveCleanup {
    param([string]$UserRoot, [string]$RequestPath, [string]$LogDirectory)
    $summary = [ordered]@{
        requested = $true; invoked = $false; status = 'held'; reason = 'cleanup-helper-missing'
        plannedCount = $null; deletedCount = $null; alreadyAbsentCount = $null; heldCount = $null
    }
    $helper = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'demo1-completed-directive-cleanup/scripts/cleanup_completed_directives.ps1'
    if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) { return [pscustomobject]$summary }
    $process = $null
    try {
        # An owned child supplies a hard deadline without changing the caller's
        # grader result or allowing helper exit/exception to abort its publication.
        $literals = @($helper, $UserRoot, $RequestPath, $LogDirectory) | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }
        $childCommand = '$ErrorActionPreference = ''Stop''; $LASTEXITCODE = 0; try { & ' + $literals[0] +
            ' -Root ' + $literals[1] + ' -RequestPath ' + $literals[2] + ' -LogDirectory ' + $literals[3] +
            ' -Apply; exit $LASTEXITCODE } catch { exit 2 }'
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = (Get-Process -Id $PID).Path
        $startInfo.Arguments = '-NoLogo -NoProfile -NonInteractive -EncodedCommand ' +
            [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        $null = $process.Start()
        $summary.invoked = $true
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(150000)) {
            $process.Kill()
            $summary.reason = 'cleanup-timeout'
            return [pscustomobject]$summary
        }
        $raw = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        if ($raw.Length -gt 16384) { $summary.reason = 'cleanup-output-limit'; return [pscustomobject]$summary }
        try { $cleanup = $raw | ConvertFrom-Json } catch {
            $summary.reason = 'cleanup-result-invalid'; return [pscustomobject]$summary
        }
        if ($cleanup.schemaVersion -ne 'awx.completed-directive-cleanup.result.v1' -or
            $cleanup.mode -ne 'apply' -or $cleanup.status -notin @('complete', 'hold')) {
            $summary.reason = 'cleanup-result-invalid'; return [pscustomobject]$summary
        }
        foreach ($name in @('plannedCount', 'deletedCount', 'alreadyAbsentCount', 'heldCount')) {
            $value = $cleanup.$name
            if ($null -eq $value -or $value -is [bool] -or [string]$value -notmatch '^\d{1,6}$') {
                $summary.reason = 'cleanup-result-invalid'; return [pscustomobject]$summary
            }
            $summary[$name] = [int]$value
        }
        $summary.reason = 'cleanup-held'
        if ($cleanup.reason -is [string] -and $cleanup.reason -cmatch '^[a-z][a-z0-9-]{0,95}$') {
            $summary.reason = $cleanup.reason
        }
        if ($process.ExitCode -eq 0 -and $cleanup.status -eq 'complete' -and $summary.heldCount -eq 0 -and
            $summary.plannedCount -eq ($summary.deletedCount + $summary.alreadyAbsentCount)) {
            $summary.status = 'complete'
        } elseif ($process.ExitCode -ne 0) { $summary.reason = 'cleanup-helper-failed' }
    } catch { $summary.reason = 'cleanup-helper-failed' } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
    [pscustomobject]$summary
}

$hasCleanupRequest = -not [string]::IsNullOrWhiteSpace($CompletedDirectiveRequest)
$hasCleanupLog = -not [string]::IsNullOrWhiteSpace($CleanupLogDirectory)
$cleanupOptionSupplied = $PSBoundParameters.ContainsKey('CompletedDirectiveRequest') -or $PSBoundParameters.ContainsKey('CleanupLogDirectory')
if ($cleanupOptionSupplied -and (-not $hasCleanupRequest -or -not $hasCleanupLog)) {
    Throw-Classified 'cleanup-options-incomplete' 'request-and-log-directory-required'
}

$canonicalRoot = Get-CanonicalRoot $Root
if ($ContractTest) {
    if ($canonicalRoot.StartsWith('\\', [StringComparison]::Ordinal)) {
        Throw-Classified 'contract-root-must-be-local' $canonicalRoot
    }
    $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    $tempPrefix = $systemTemp + [IO.Path]::DirectorySeparatorChar
    if (-not $canonicalRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'contract-root-must-be-temp' $canonicalRoot
    }
} elseif (-not $canonicalRoot.Equals($ProductionMacSrcRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Throw-Classified 'macsrc-root-mismatch' $canonicalRoot
}

$handoffRelative = "data/agent-handoff/docker-autograder/$RunId"
$handoffRoot = [IO.Path]::GetFullPath((Join-Path $canonicalRoot $handoffRelative)).TrimEnd('\', '/')
$handoffPrefix = $handoffRoot + [IO.Path]::DirectorySeparatorChar
$jobMember = Resolve-RootMember $canonicalRoot $JobFile $true
$outputMember = Resolve-RootMember $canonicalRoot $OutputPath $false
foreach ($member in @($jobMember, $outputMember)) {
    if (-not $member.FullPath.StartsWith($handoffPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'output-outside-handoff' $member.Relative
    }
}
$jobSnapshot = Read-ReadyJsonSnapshot $jobMember.FullPath
if ($jobSnapshot.Value.schemaVersion -ne 'awx.docker-autograder.job.v1' -or
    $jobSnapshot.Value.runId -ne $RunId -or $jobSnapshot.Value.mutationAllowed -ne $false) {
    Throw-Classified 'job-invalid' 'schema-run-mutation'
}

$controller = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'docker_autograder.py'))
if (-not (Test-Path -LiteralPath $controller -PathType Leaf)) { Throw-Classified 'controller-missing' $controller }
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) { Throw-Classified 'python-cli-unavailable' 'python' }
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $controllerOutput = @(& $pythonCommand.Source $controller `
        --root $canonicalRoot --job $jobMember.FullPath --output $outputMember.FullPath `
        --docker-bin $DockerBin 2>&1 | ForEach-Object { $_.ToString() })
    $controllerExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($controllerExit -ne 0) { Throw-Classified 'docker-autograder-controller-failed' "exit-$controllerExit" }
if (-not (Test-Path -LiteralPath $outputMember.FullPath -PathType Leaf)) {
    Throw-Classified 'autograder-result-missing' $outputMember.Relative
}
$resultSnapshot = Read-ReadyJsonSnapshot $outputMember.FullPath
$result = $resultSnapshot.Value
if ($result.schemaVersion -ne 'awx.docker-autograder.result.v1' -or
    $result.runId -ne $RunId -or $result.jobSha256 -ne $jobSnapshot.Sha256 -or
    $result.mutationAllowed -ne $false -or $result.sourceMutationPerformed -ne $false) {
    Throw-Classified 'autograder-result-invalid' 'schema-run-job-mutation'
}
if ($controllerOutput.Count -eq 0) { Throw-Classified 'controller-output-missing' $RunId }
try { $machine = $controllerOutput[-1] | ConvertFrom-Json } catch { Throw-Classified 'controller-output-invalid' $RunId }
if ($machine.mutationAllowed -ne $false -or $machine.outputPath -ne $outputMember.FullPath -or
    $machine.status -ne $result.executionStatus -or $machine.failureClass -ne $result.failureClass) {
    Throw-Classified 'controller-output-invalid' 'path-mutation'
}
$artifactCleanup = [pscustomobject]@{
    requested = $hasCleanupRequest; invoked = $false
    status = 'not-requested'; reason = 'cleanup-not-requested'
    plannedCount = 0; deletedCount = 0; alreadyAbsentCount = 0; heldCount = 0
}
if ($hasCleanupRequest) {
    $artifactCleanup.status = 'not-eligible'
    $artifactCleanup.reason = 'grading-not-clean-green'
    # A GREEN run is only the event that invokes the independent whole-directive
    # completion validator. It is never sufficient evidence to delete a directive.
    if ($jobSnapshot.Value.purpose -eq 'GREEN_VERIFICATION' -and $result.purpose -eq 'GREEN_VERIFICATION' -and
        $result.executionStatus -eq 'COMPLETE' -and $result.testVerdict -eq 'PASS' -and
        $null -ne $result.exitCode -and $result.exitCode -eq 0 -and $result.failureClass -eq 'none' -and
        $result.timedOut -eq $false -and $result.tests.total -gt 0 -and $result.tests.passed -gt 0 -and
        $result.tests.failed -eq 0 -and $result.tests.errored -eq 0 -and $result.tests.skipped -eq 0) {
        $artifactCleanup = Invoke-CompletedDirectiveCleanup -UserRoot $Root `
            -RequestPath $CompletedDirectiveRequest -LogDirectory $CleanupLogDirectory
    }
}
[pscustomobject]@{
    status = [string]$result.executionStatus
    failureClass = [string]$result.failureClass
    outputPath = $outputMember.FullPath
    resultSha256 = $resultSnapshot.Sha256
    mutationAllowed = $false
    artifactCleanup = $artifactCleanup
} | ConvertTo-Json -Compress

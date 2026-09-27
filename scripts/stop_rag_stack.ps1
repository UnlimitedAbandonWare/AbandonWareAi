#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$MetaDisplay,
    [switch]$Wear,
    [switch]$DryRun,
    [ValidateRange(5,600)][int]$TimeoutSeconds = 60,
    [ValidateRange(0,60)][int]$GraceSeconds = 8
)

$ErrorActionPreference = 'Stop'
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# Bound params must be captured before dot-sourcing: the sourced script's own
# param() block would otherwise reset the same-named switches in this scope.
$script:StopMetaDisplay = [bool]$MetaDisplay
$script:StopWear = [bool]$Wear
$script:StopDryRun = [bool]$DryRun
$script:StopTimeout = [int]$TimeoutSeconds
$script:StopGrace = [int]$GraceSeconds
# Dot-sourcing start_rag_stack.ps1 is safe: its final guard only invokes the
# launcher when the script is executed directly (InvocationName -ne '.').
. (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
$script:StopStage = 'PREFLIGHT'

function Get-StopWatchTargets {
    $prefix = $script:RagRoot.TrimEnd('\').Replace('/','\') + '\'
    foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue)) {
        $command = [string]$process.CommandLine
        if ([string]::IsNullOrWhiteSpace($command)) { continue }
        $normalized = $command.Replace('/','\')
        if ($normalized.IndexOf('dev_reload_watch.ps1', [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        if ($normalized.IndexOf($prefix, [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        $identity = Get-AwxProcessIdentity -ProcessId ([int]$process.ProcessId)
        if ($null -eq $identity) { continue }
        [pscustomobject]@{
            processId = [int]$identity.processId
            creationDate = [string]$identity.creationDate
            metaDisplay = ($command -match '(?:^|\s)-MetaDisplay(?:\s|$)')
        }
    }
}

function Test-StopProcessAlive {
    param([int]$ProcessId, [string]$CreationDate)
    $live = Get-AwxProcessIdentity -ProcessId $ProcessId
    return ($null -ne $live -and (Test-AwxSameCreationDate -Left $CreationDate -Right ([string]$live.creationDate)))
}

function Invoke-StopGracefulWindow {
    param([int]$ProcessId, [string]$CreationDate)
    if (-not (Test-StopProcessAlive -ProcessId $ProcessId -CreationDate $CreationDate)) { return 'already-exited' }
    try {
        $proc = Get-Process -Id $ProcessId -ErrorAction Stop
        if ($proc.CloseMainWindow()) { return 'close-requested' }
    } catch { }
    return 'no-window'
}

function Wait-StopProcessExit {
    param([int]$ProcessId, [string]$CreationDate, [int]$Seconds)
    $deadline = (Get-Date).AddSeconds([Math]::Max(0, $Seconds))
    do {
        if (-not (Test-StopProcessAlive -ProcessId $ProcessId -CreationDate $CreationDate)) { return $true }
        Start-Sleep -Milliseconds 150
    } while ((Get-Date) -lt $deadline)
    return (-not (Test-StopProcessAlive -ProcessId $ProcessId -CreationDate $CreationDate))
}

function Stop-VerifiedProcessForce {
    param([int]$ProcessId, [string]$CreationDate)
    if (-not (Test-StopProcessAlive -ProcessId $ProcessId -CreationDate $CreationDate)) { return $false }
    try { Stop-Process -Id $ProcessId -Force -ErrorAction Stop; return $true } catch { return $false }
}

function Stop-OwnedTreeFallback {
    # Bounded force-stop for survivors after a partial graceful exit, when the
    # full manifest re-validation can no longer pass. Kills only manifest pids
    # and java/javaw descendants carrying this run's runId token.
    param(
        [object]$Manifest,
        [int]$LauncherPid,
        [int]$ListenerPid,
        [int[]]$Ports,
        [int]$TimeoutSeconds
    )
    $runId = [string](Get-AwxObjectProperty $Manifest 'runId')
    $manifestIds = @{}
    foreach ($pid in @($LauncherPid, $ListenerPid)) { if ($pid -gt 0) { $manifestIds[$pid] = $true } }
    $allowed = @('java.exe', 'java', 'javaw.exe', 'javaw')
    $rows = @(Get-AwxDescendantProcessRows -RootProcessId $LauncherPid)
    $stopped = 0; $skipped = 0; $remaining = 0
    foreach ($row in $rows) {
        $procId = [int]$row.processId
        if (-not (Test-StopProcessAlive -ProcessId $procId -CreationDate ([string]$row.creationDate))) { continue }
        $killable = $manifestIds.ContainsKey($procId)
        if (-not $killable) {
            $live = Get-AwxProcessIdentity -ProcessId $procId
            if ($null -ne $live -and $allowed -contains ([string]$live.processName).ToLowerInvariant()) {
                $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
                $killable = ($null -ne $proc -and -not [string]::IsNullOrWhiteSpace([string]$proc.CommandLine) -and
                    ([string]$proc.CommandLine).IndexOf($runId, [StringComparison]::Ordinal) -ge 0)
            }
        }
        if (-not $killable) { $skipped++; continue }
        if (Stop-VerifiedProcessForce -ProcessId $procId -CreationDate ([string]$row.creationDate)) { $stopped++ }
    }
    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSeconds))
    do {
        $remaining = @($rows | Where-Object { Test-StopProcessAlive -ProcessId ([int]$_.processId) -CreationDate ([string]$_.creationDate) }).Count
        if ($remaining -eq 0) { break }
        Start-Sleep -Milliseconds 150
    } while ((Get-Date) -lt $deadline)
    $portsFree = Wait-AwxPortsReleased -Ports $Ports -TimeoutSeconds ([Math]::Min(10, $TimeoutSeconds))
    return [pscustomobject]@{ stopped = $stopped; skipped = $skipped; remaining = $remaining; portsFree = $portsFree }
}

function Invoke-RagRuntimeStop {
    $MetaDisplay = $script:StopMetaDisplay
    $Wear = $script:StopWear
    $DryRun = $script:StopDryRun
    $TimeoutSeconds = $script:StopTimeout
    $GraceSeconds = $script:StopGrace
    if ($Wear -and -not $MetaDisplay) { $MetaDisplay = $true }
    $targetRole = if ($Wear) { 'wear' } else { 'dev' }
    $runDirectory = Join-Path $script:RagRoot ('var\rag-launcher\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-stop-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $script:RagLog = Join-Path $runDirectory 'stop.log'
    $mutex = [Threading.Mutex]::new($false, ('Local\AWX-RAG-' + (Get-AwxCanonicalRootHash -Root $script:RagRoot).Substring(0,20)))
    $acquired = $false
    $report = [ordered]@{
        role = $targetRole; metaDisplay = [bool]$MetaDisplay; dryRun = [bool]$DryRun
        watchers = @(); stopped = @(); kept = @(); skipped = @(); failed = @()
    }
    try {
        try { $acquired = $mutex.WaitOne(20000) } catch [Threading.AbandonedMutexException] { $acquired = $true }
        if (-not $acquired) { throw 'launcher-busy' }
        Write-RagStage 'PREFLIGHT' 'START' "role=$targetRole meta=$([bool]$MetaDisplay) dryRun=$([bool]$DryRun) logs=$runDirectory"

        # 1) Restart watchers first: they would relaunch a runtime on the port.
        foreach ($watcher in @(Get-StopWatchTargets)) {
            $watcherPid = [int]$watcher.processId
            $entry = [ordered]@{ processId = $watcherPid; metaDisplay = [bool]$watcher.metaDisplay; result = '' }
            if ($DryRun) {
                $entry.result = 'dry-run'
                $report.watchers += [pscustomobject]$entry
                Write-RagStage 'DEV-RELOAD' 'DRYRUN' "pid=$watcherPid meta=$([bool]$watcher.metaDisplay) would stop restart watcher"
                continue
            }
            $grace = Invoke-StopGracefulWindow -ProcessId $watcherPid -CreationDate $watcher.creationDate
            if (Wait-StopProcessExit -ProcessId $watcherPid -CreationDate $watcher.creationDate -Seconds $GraceSeconds) {
                $entry.result = 'stopped-graceful'
                Write-RagStage 'DEV-RELOAD' 'STOP' "pid=$watcherPid meta=$([bool]$watcher.metaDisplay) graceful"
            } elseif (Stop-VerifiedProcessForce -ProcessId $watcherPid -CreationDate $watcher.creationDate) {
                [void](Wait-StopProcessExit -ProcessId $watcherPid -CreationDate $watcher.creationDate -Seconds 10)
                $entry.result = 'stopped-forced'
                Write-RagStage 'DEV-RELOAD' 'STOP' "pid=$watcherPid meta=$([bool]$watcher.metaDisplay) forced"
            } else {
                $entry.result = 'failed'
                Write-RagStage 'DEV-RELOAD' 'FAILED' "pid=$watcherPid could not stop restart watcher"
            }
            $report.watchers += [pscustomobject]$entry
        }
        if (@($report.watchers).Count -eq 0) { Write-RagStage 'DEV-RELOAD' 'INFO' 'no restart watchers running' }

        # 2) Owned runtime targets: manifest + live identity proof, strict role scope.
        $candidates = @(Get-RagSpringCandidates)
        $scope = @($candidates | Where-Object { [bool](Get-AwxObjectProperty $_ 'metaDisplay') -eq [bool]$MetaDisplay })
        $targets = @()
        foreach ($candidate in $scope) {
            $candPid = [int](Get-AwxObjectProperty $candidate 'processId')
            $candRole = [string](Get-AwxObjectProperty $candidate 'role')
            if (($candRole -eq 'wear') -ne [bool]$Wear) {
                $other = if ($candRole -eq 'wear') { 'Close-Meta-Display.bat' } else { 'Close-RAG.bat' }
                $report.kept += [pscustomobject]@{ processId = $candPid; reason = "role=$candRole belongs to $other" }
                Write-RagStage 'SPRING' 'KEEP' "pid=$candPid port=$((Get-AwxObjectProperty $candidate 'port')) role=$candRole (use $other)"
                continue
            }
            $targets += $candidate
        }
        $processedRunIds = @{}
        foreach ($candidate in $targets) {
            $candPid = [int](Get-AwxObjectProperty $candidate 'processId')
            $candPort = [int](Get-AwxObjectProperty $candidate 'port')
            $manifest = Find-RagRuntimeManifest -ProcessId $candPid
            if ($null -eq $manifest) {
                $report.skipped += [pscustomobject]@{ processId = $candPid; reason = 'ownership-unproven (no manifest)' }
                Write-RagStage 'SPRING' 'SKIP' "pid=$candPid port=$candPort ownership-unproven; left running"
                continue
            }
            $runId = [string](Get-AwxObjectProperty $manifest 'runId')
            if ($processedRunIds.ContainsKey($runId)) { continue }
            $processedRunIds[$runId] = $true
            $validation = Test-AwxOwnedRuntimeIdentity -Manifest $manifest -Root $script:RagRoot
            if (-not $validation.ok) {
                $report.skipped += [pscustomobject]@{ processId = $candPid; reason = "ownership-check-$([string]$validation.reason)" }
                Write-RagStage 'SPRING' 'SKIP' "pid=$candPid runId=$runId reason=$([string]$validation.reason); left running"
                continue
            }
            $launcherPid = [int]$validation.launcherPid
            $listenerPid = [int]$validation.listenerPid
            if ($DryRun) {
                Write-RagStage 'SPRING' 'DRYRUN' "pid=$candPid runId=$runId role=$targetRole owned; would stop launcher=$launcherPid listener=$listenerPid ports=$($validation.ports -join ',')"
                $report.stopped += [pscustomobject]@{ runId = $runId; listenerPid = $listenerPid; result = 'dry-run' }
                continue
            }
            $tree = @(Get-AwxDescendantProcessRows -RootProcessId $launcherPid)
            $gracefulExited = 0
            foreach ($row in $tree) {
                if ((Invoke-StopGracefulWindow -ProcessId ([int]$row.processId) -CreationDate ([string]$row.creationDate)) -eq 'close-requested') {
                    if (Wait-StopProcessExit -ProcessId ([int]$row.processId) -CreationDate ([string]$row.creationDate) -Seconds $GraceSeconds) { $gracefulExited++ }
                }
            }
            $stop = Stop-AwxOwnedRuntime -Manifest $manifest -Root $script:RagRoot -TimeoutSeconds $TimeoutSeconds
            $result = [ordered]@{
                runId = $runId; launcherPid = $launcherPid; listenerPid = $listenerPid
                gracefulExited = $gracefulExited; forced = 0; skippedDescendants = 0; remaining = 0; portsFree = $false; result = ''
            }
            if ($stop.ok) {
                $result.forced = [int]$stop.stoppedProcessCount
                $result.skippedDescendants = [int]$stop.skippedDescendantCount
                $result.portsFree = $true
                $result.result = 'stopped'
                Write-RagStage 'SPRING' 'STOP' "runId=$runId listener=$listenerPid graceful=$gracefulExited forced=$($stop.stoppedProcessCount) skippedDesc=$($stop.skippedDescendantCount)"
            } else {
                $fallback = Stop-OwnedTreeFallback -Manifest $manifest -LauncherPid $launcherPid -ListenerPid $listenerPid -Ports @($validation.ports) -TimeoutSeconds $TimeoutSeconds
                $result.forced = [int]$fallback.stopped
                $result.skippedDescendants = [int]$fallback.skipped
                $result.remaining = [int]$fallback.remaining
                $result.portsFree = [bool]$fallback.portsFree
                if ($fallback.remaining -eq 0 -and $fallback.portsFree) {
                    $result.result = 'stopped-partial-graceful'
                    Write-RagStage 'SPRING' 'STOP' "runId=$runId listener=$listenerPid graceful=$gracefulExited forced=$($fallback.stopped) skippedDesc=$($fallback.skipped) (post-graceful bounded stop)"
                } else {
                    $result.result = 'failed'
                    Write-RagStage 'SPRING' 'FAILED' "runId=$runId listener=$listenerPid remaining=$($fallback.remaining) portsFree=$($fallback.portsFree) reason=$([string]$stop.reason)"
                }
            }
            if ($result.result -eq 'failed') { $report.failed += [pscustomobject]$result } else { $report.stopped += [pscustomobject]$result }
        }
        if ($targets.Count -eq 0) { Write-RagStage 'SPRING' 'INFO' "no $targetRole meta-display runtime running" }

        # 3) Post-verify: target scope must be clean before reporting success.
        $post = @()
        if (-not $DryRun) {
            $post = @(Get-RagSpringCandidates | Where-Object {
                [bool](Get-AwxObjectProperty $_ 'metaDisplay') -eq [bool]$MetaDisplay -and
                (((Get-AwxObjectProperty $_ 'role') -eq 'wear') -eq [bool]$Wear)
            })
            foreach ($leftover in $post) {
                $leftPid = [int](Get-AwxObjectProperty $leftover 'processId')
                if (@($report.failed | Where-Object { $_.listenerPid -eq $leftPid -or $_.launcherPid -eq $leftPid }).Count -gt 0) { continue }
                $report.failed += [pscustomobject]@{ runId = ''; processId = $leftPid; result = 'still-running' }
                Write-RagStage 'SPRING' 'FAILED' "pid=$leftPid still running after stop attempt"
            }
        }

        # 4) Shared/foreign processes: report, never touch.
        $ollamaPorts = @(11434, 11435)
        if ($env:OLLAMA_HOST -match ':(\d{1,5})/?$') { $ollamaPorts = @([int]$Matches[1]) + $ollamaPorts }
        foreach ($ollamaPort in @($ollamaPorts | Select-Object -Unique)) {
            try {
                $owner = Get-RagPortOwner -Port $ollamaPort
                if ($null -ne $owner -and [string]$owner.processName -match '^(?i:ollama)') {
                    $report.kept += [pscustomobject]@{ processId = [int]$owner.processId; reason = "shared Ollama port=$ollamaPort; not launcher-owned" }
                    Write-RagStage 'OLLAMA' 'KEEP' "port=$ollamaPort pid=$($owner.processId) shared; left running"
                }
            } catch { }
        }
        $siblings = @($candidates | Where-Object { [bool](Get-AwxObjectProperty $_ 'metaDisplay') -ne [bool]$MetaDisplay })
        foreach ($sibling in $siblings) {
            $report.kept += [pscustomobject]@{ processId = [int](Get-AwxObjectProperty $sibling 'processId'); reason = 'non-meta-display runtime; not started by these BATs' }
        }

        $watchersLeft = @()
        if (-not $DryRun) { $watchersLeft = @(Get-StopWatchTargets) }
        # Success requires: no failures, nothing target-scoped still running
        # (covers skipped-unproven leftovers), and no restart watcher alive.
        $ok = ($report.failed.Count -eq 0 -and $post.Count -eq 0 -and $watchersLeft.Count -eq 0)
        $status = if ($DryRun) { 'dry-run' } elseif ($ok) { if ($report.stopped.Count -eq 0 -and $report.watchers.Count -eq 0) { 'already-stopped' } else { 'stopped' } } else { 'incomplete' }
        Write-AwxJsonAtomic -Path (Join-Path $runDirectory 'result.json') -Data ([ordered]@{
            schemaVersion = 'awx.rag_stop.v1'; ok = $ok; status = $status; role = $targetRole
            watchers = $report.watchers; stopped = $report.stopped; kept = $report.kept
            skipped = $report.skipped; failed = $report.failed; logDirectory = $runDirectory
        })
        Write-RagStage 'RESULT' $(if ($ok) { 'OK' } else { 'FAILED' }) "status=$status stopped=$($report.stopped.Count) kept=$($report.kept.Count) skipped=$($report.skipped.Count) failed=$($report.failed.Count) log=$runDirectory"
        return $(if ($ok) { 0 } else { 1 })
    } catch {
        $reason = $_.Exception.Message
        if ($reason -notmatch '^[a-z0-9-]{1,100}$') { $reason = 'stop-unexpected-error' }
        Write-RagStage $script:RagStage 'FAILED' "reason=$reason log=$script:RagLog"
        return 1
    } finally {
        if ($acquired) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

if ($MyInvocation.InvocationName -ne '.') { exit (Invoke-RagRuntimeStop) }

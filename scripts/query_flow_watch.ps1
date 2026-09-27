#requires -Version 5.1
<#
.SYNOPSIS
  Opt-in background watcher: refresh the query-flow notepad bundle when new
  chat-session trace files appear.

.DESCRIPTION
  Polls var\debug\chat-session-traces for new/changed *.json files and re-runs
  `python -B scripts/query_flow_notepad_bundle.py` (debounced). Read-only on
  every input lane; the only writes are the bundle dir and its own state dir.

  Actions:
    run     foreground loop (used by start; stop via _stop.request or duration)
    start   detached powershell child (WindowStyle Hidden), state under
            var\debug\query-flow\_watch\ ; exit 2 if already active
    status  print watch.json + watcher liveness (exit 3 when absent)
    stop    write _stop.request; watcher exits on next poll boundary

  Mirrors debug_session_watch.ps1's detach/stop-file contract. Opt-in only ??  nothing runs unless the user starts it.
#>
[CmdletBinding()]
param(
    [ValidateSet('run','start','status','stop')][string]$Action = 'status',
    [string]$Root = '',
    [ValidateRange(5,600)][int]$PollSeconds = 20,
    [ValidateRange(2,120)][int]$DebounceSeconds = 5,
    [ValidateRange(0,86400)][int]$DurationSeconds = 0,
    [double]$SinceHours = 24,
    [string]$WatchDir = '',
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
# 최상위 스코프에서 자기 경로를 먼저 고정 — 함수 안의 $MyInvocation은 함수 자신이다.
$script:WSelf = [string]$MyInvocation.MyCommand.Path
$script:WRoot = if ([string]::IsNullOrWhiteSpace($Root)) {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
} else { [IO.Path]::GetFullPath($Root) }
$script:WDir = if ([string]::IsNullOrWhiteSpace($WatchDir)) {
    Join-Path $script:WRoot 'var\debug\query-flow\_watch'
} else { $WatchDir }
$script:WPaths = [ordered]@{
    dir = $script:WDir
    state = (Join-Path $script:WDir 'watch.json')
    stopRequest = (Join-Path $script:WDir '_stop.request')
    watcherOut = (Join-Path $script:WDir 'watcher.out.log')
    watcherErr = (Join-Path $script:WDir 'watcher.err.log')
}
$script:WResult = [ordered]@{
    schemaVersion = 'awx.query-flow-watch.v1'; ok = $false
    action = $Action; status = 'started'; watchDir = $script:WDir }

function Write-WatchState {
    param([hashtable]$Extra)
    $state = [ordered]@{
        schemaVersion = 'awx.query-flow-watch-state.v1'
        watcherPid = $PID; startedAt = $script:WStartedAt
        status = $script:WStatus; stopReason = $script:WStopReason
        pollSeconds = $PollSeconds; sinceHours = $SinceHours
        lastSeenFile = $script:WLastSeen; lastBundleAt = $script:WLastBundle
        refreshes = $script:WRefreshes; lastError = $script:WLastError
    }
    foreach ($k in $Extra.Keys) { $state[$k] = $Extra[$k] }
    $tmp = "$($script:WPaths.state).tmp-$PID"
    ($state | ConvertTo-Json -Depth 5) | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $script:WPaths.state -Force
}

function Read-WatchState {
    if (-not (Test-Path -LiteralPath $script:WPaths.state -PathType Leaf)) { return $null }
    try { return (Get-Content -LiteralPath $script:WPaths.state -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Get-NewestTraceFile {
    $base = Join-Path $script:WRoot 'var\debug\chat-session-traces'
    if (-not (Test-Path -LiteralPath $base -PathType Container)) { return $null }
    $newest = Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Filter '*.json' -File -ErrorAction SilentlyContinue } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    return $newest
}

function Invoke-BundleRefresh {
    $py = Join-Path $script:WRoot 'scripts\query_flow_notepad_bundle.py'
    $args = @('-B', $py, '--root', $script:WRoot, '--since-hours', [string]$SinceHours)
    $proc = Start-Process -FilePath 'python' -ArgumentList ($args -join ' ') `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput "$($script:WDir)\bundle-last.out.log" `
        -RedirectStandardError "$($script:WDir)\bundle-last.err.log"
    $script:WRefreshes++
    $script:WLastBundle = (Get-Date).ToUniversalTime().ToString('o')
    if ($proc.ExitCode -ne 0) { $script:WLastError = "bundle exit $($proc.ExitCode)" }
    return $proc.ExitCode
}

function Invoke-WatchRun {
    New-Item -ItemType Directory -Force -Path $script:WDir | Out-Null
    $script:WStartedAt = (Get-Date).ToUniversalTime().ToString('o')
    $script:WStatus = 'running'; $script:WStopReason = ''
    $script:WLastSeen = ''; $script:WLastBundle = ''; $script:WRefreshes = 0
    $script:WLastError = ''
    Write-WatchState -Extra @{}
    Write-Host "[QFW] watching var\debug\chat-session-traces poll=$($PollSeconds)s dir=$($script:WDir)"
    $deadline = $null
    if ($DurationSeconds -gt 0) { $deadline = (Get-Date).AddSeconds($DurationSeconds) }
    $stopReason = 'stopped'
    while ($true) {
        if (Test-Path -LiteralPath $script:WPaths.stopRequest -PathType Leaf) {
            $stopReason = 'stop-requested'; break }
        if ($null -ne $deadline -and (Get-Date) -ge $deadline) {
            $stopReason = 'duration-reached'; break }
        $newest = Get-NewestTraceFile
        if ($null -ne $newest) {
            $sig = "$($newest.FullName)|$($newest.LastWriteTimeUtc.Ticks)|$($newest.Length)"
            if ($sig -ne $script:WLastSeen) {
                $script:WLastSeen = $sig
                Start-Sleep -Seconds $DebounceSeconds
                try { [void](Invoke-BundleRefresh) }
                catch { $script:WLastError = $_.Exception.Message }
                Write-WatchState -Extra @{}
            }
        }
        Start-Sleep -Seconds $PollSeconds
    }
    $script:WStatus = 'finished'; $script:WStopReason = $stopReason
    Write-WatchState -Extra @{}
    Remove-Item -LiteralPath $script:WPaths.stopRequest -Force -ErrorAction SilentlyContinue
    Write-Host "[QFW] finished reason=$stopReason refreshes=$($script:WRefreshes)"
    $script:WResult.status = 'finished'; $script:WResult.ok = $true
    return 0
}

function Invoke-WatchStart {
    $st = Read-WatchState
    if ($null -ne $st -and [string]$st.status -eq 'running') {
        $wpid = [int]$st.watcherPid
        if ($wpid -gt 0 -and $null -ne (Get-Process -Id $wpid -ErrorAction SilentlyContinue)) {
            Write-Host "[QFW] already running watcherPid=$wpid dir=$($script:WDir)"
            $script:WResult.status = 'watch-active'; $script:WResult.ok = $true
            return 2
        }
    }
    New-Item -ItemType Directory -Force -Path $script:WDir | Out-Null
    Remove-Item -LiteralPath $script:WPaths.stopRequest -Force -ErrorAction SilentlyContinue
    $argList = @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',
        ('"{0}"' -f $script:WSelf), '-Action','run',
        '-Root', ('"{0}"' -f $script:WRoot),
        '-PollSeconds', [string]$PollSeconds,
        '-DebounceSeconds', [string]$DebounceSeconds,
        '-DurationSeconds', [string]$DurationSeconds,
        '-SinceHours', [string]$SinceHours,
        '-WatchDir', ('"{0}"' -f $script:WDir))
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList ($argList -join ' ') `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $script:WPaths.watcherOut `
        -RedirectStandardError $script:WPaths.watcherErr
    Start-Sleep -Seconds 2
    $st = Read-WatchState
    $script:WResult.watcherPid = [int]$proc.Id
    if ($null -ne $st -and [string]$st.status -in @('running')) {
        Write-Host "[QFW] watcher pid=$($proc.Id) dir=$($script:WDir)"
        $script:WResult.status = 'spawned'; $script:WResult.ok = $true
        return 0
    }
    Write-Host "[QFW] spawned but watch.json not yet live; see $($script:WPaths.watcherErr)"
    $script:WResult.status = 'spawned-unconfirmed'; $script:WResult.ok = $true
    return 0
}

function Invoke-WatchStatus {
    $st = Read-WatchState
    if ($null -eq $st) {
        Write-Host "[QFW] no watch state under $($script:WDir)"
        $script:WResult.status = 'no-watch'
        return 3
    }
    $alive = $false
    if ($st.watcherPid) {
        $alive = ($null -ne (Get-Process -Id ([int]$st.watcherPid) -ErrorAction SilentlyContinue)) }
    Write-Host ("[QFW] status={0} watcherPid={1} alive={2} refreshes={3} lastBundle={4} lastError={5}" -f `
        $st.status, $st.watcherPid, $alive, $st.refreshes, $st.lastBundleAt, $st.lastError)
    $script:WResult.status = [string]$st.status
    $script:WResult.ok = $true
    $script:WResult.state = $st
    $script:WResult.watcherAlive = $alive
    return 0
}

function Invoke-WatchStop {
    if (-not (Test-Path -LiteralPath $script:WDir -PathType Container)) {
        Write-Host "[QFW] nothing to stop (no $($script:WDir))"
        $script:WResult.status = 'no-watch'
        return 3
    }
    Set-Content -LiteralPath $script:WPaths.stopRequest -Value 'stop' -Encoding ASCII
    Write-Host "[QFW] stop requested -> $($script:WPaths.stopRequest)"
    $script:WResult.status = 'stop-requested'; $script:WResult.ok = $true
    return 0
}

$code = switch ($Action) {
    'run'    { Invoke-WatchRun }
    'start'  { Invoke-WatchStart }
    'status' { Invoke-WatchStatus }
    'stop'   { Invoke-WatchStop }
}
if ($Json) { [Console]::Out.Write(($script:WResult | ConvertTo-Json -Depth 6 -Compress) + [Environment]::NewLine) }
exit [int]$code


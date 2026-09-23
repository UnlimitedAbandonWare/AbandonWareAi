#requires -Version 5.1
<#
.SYNOPSIS
  Session-scoped runtime debug watcher for the BAT-managed RAG/Meta Display
  runtimes. Called directly or via Debug-Session.bat.

.DESCRIPTION
  While a server runs, this continuously captures its stdout/stderr (the
  launcher out/err logs resolved through the spring-owned.json ownership
  manifest), classifies Spring Boot exceptions/stack traces, HTTP
  request/response errors and timeouts, and polls process/port/HTTP health.
  Everything is appended chronologically into ONE session directory under
  var\debug (or -SessionRoot). Existing logs are only READ with
  FileShare.ReadWrite - nothing is truncated, rotated, or modified.

  Session dir layout (<role>-<stamp>-session\):
    session.json    state + target + counters (watch progress)
    session.log     merged chronological view ([DBG] stage lines + raw lines)
    events.jsonl    structured events: lifecycle/log.error/log.http/log.timeout/
                    log.stage(sampled)/state transitions/heartbeat
    stream-out.log  verbatim copy of the captured stdout window
    stream-err.log  verbatim copy of the captured stderr window
    contexts\       per-error ring-buffer dumps: lines before + after each hit
    packet.json     analysis packet for handoff (GPT Pro / agent): counts,
                    error classes, exception causes, timeline, suspect files
                    mapped to active sourceSets, environment, coverage gaps
    packet.md       human/agent-readable digest of packet.json

  On finalize the packet trio (+ events + contexts) is also copied to
  data\agent-handoff\display-debug\sessions\<role>-<stamp>\ - the durable
  handoff dir. var\debug stays ephemeral; session dirs are pruned (keep 15).

  Exit codes:
    run      : 0 finished (stop-file / duration / runtime-down grace) |
               3 not-running | 1 error
    start    : 0 spawned (detached) | 2 a session is already active | 1 failed
    status   : 0 session found | 3 none
    stop     : 0 finalized | 4 watcher still running after wait | 3 none | 1
    finalize : 0 packet rebuilt | 3 no session | 1 error
#>
[CmdletBinding()]
param(
    [ValidateSet('dev','wear')][string]$Role = 'dev',
    [ValidateSet('run','start','status','stop','finalize','help')][string]$Action = 'run',
    [ValidateRange(0,28800)][int]$DurationSeconds = 0,
    [ValidateRange(5,300)][int]$PollSeconds = 15,
    [ValidateRange(30,7200)][int]$HeartbeatSeconds = 120,
    [ValidateRange(10,200)][int]$ContextLines = 40,
    [ValidateRange(5,120)][int]$ContextAfterLines = 30,
    [ValidateRange(20,4000)][int]$RingLines = 400,
    [ValidateRange(5,600)][int]$AfterRuntimeDownSeconds = 120,
    [ValidateRange(0,1200)][int]$AttachWaitSeconds = 30,
    [ValidateRange(5,2000)][int]$MaxContextFiles = 60,
    [string]$LogPath = '',
    [string]$ErrPath = '',
    [string]$SessionRoot = '',
    [string]$SessionDir = '',
    [string]$HandoffRoot = '',
    [switch]$FromStart,
    [switch]$NoHandoff,
    [switch]$Force,
    [switch]$Json,
    [switch]$JsonStdout,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
$script:SessSelf = [string]$MyInvocation.MyCommand.Path
# Bound params captured BEFORE dot-sourcing: debug_rag_stack.ps1's own param()
# block would otherwise reset the same-named variables in this shared scope
# ($Role, $Action, $Json, $JsonStdout, $Help). Same hazard the parent documents.
$script:SessArgs = [ordered]@{
    Role = $Role; Action = $Action
    Duration = [int]$DurationSeconds; Poll = [int]$PollSeconds
    Heartbeat = [int]$HeartbeatSeconds; ContextLines = [int]$ContextLines
    ContextAfter = [int]$ContextAfterLines; Ring = [int]$RingLines
    AfterDown = [int]$AfterRuntimeDownSeconds; AttachWait = [int]$AttachWaitSeconds
    MaxContexts = [int]$MaxContextFiles
    LogPath = [string]$LogPath; ErrPath = [string]$ErrPath
    SessionRoot = [string]$SessionRoot; SessionDir = [string]$SessionDir
    HandoffRoot = [string]$HandoffRoot
    FromStart = [bool]$FromStart; NoHandoff = [bool]$NoHandoff; Force = [bool]$Force
    Json = [bool]$Json; JsonStdout = [bool]$JsonStdout; Help = [bool]$Help
}
# Reuse the whole discovery/scan layer of the debug stack (read-only import;
# its entry point is InvocationName-guarded). No writes to that file are needed.
. (Join-Path $PSScriptRoot 'debug_rag_stack.ps1')
$script:DbgRole = [string]$script:SessArgs.Role

$script:Sess = [ordered]@{
    Action = [string]$script:SessArgs.Action
    Role = [string]$script:SessArgs.Role
    Duration = [int]$script:SessArgs.Duration
    Poll = [int]$script:SessArgs.Poll
    Heartbeat = [int]$script:SessArgs.Heartbeat
    ContextLines = [int]$script:SessArgs.ContextLines
    ContextAfter = [int]$script:SessArgs.ContextAfter
    Ring = [int]$script:SessArgs.Ring
    AfterDown = [int]$script:SessArgs.AfterDown
    AttachWait = [int]$script:SessArgs.AttachWait
    MaxContexts = [int]$script:SessArgs.MaxContexts
    LogPath = [string]$script:SessArgs.LogPath
    ErrPath = [string]$script:SessArgs.ErrPath
    SessionRoot = if ($script:SessArgs.SessionRoot) { [string]$script:SessArgs.SessionRoot } else { [string]$script:DebugDir }
    SessionDir = [string]$script:SessArgs.SessionDir
    HandoffRoot = if ($script:SessArgs.HandoffRoot) { [string]$script:SessArgs.HandoffRoot } else { Join-Path $script:RagRoot 'data\agent-handoff\display-debug\sessions' }
    FromStart = [bool]$script:SessArgs.FromStart
    NoHandoff = [bool]$script:SessArgs.NoHandoff
    Force = [bool]$script:SessArgs.Force
    Json = [bool]$script:SessArgs.Json
    JsonStdout = [bool]$script:SessArgs.JsonStdout
    Help = [bool]$script:SessArgs.Help
}

$script:SessHttpMarkers = @(
    'stage=http_error', 'display\.request_failed', 'chat-failed', 'stream-failed',
    '\[API_FAILURE\]', 'HTTP/1\.1" [45]\d\d', '\bhttp_[45]\d\d\b', '\bstatus=5\d\d\b',
    'All search providers failed'
)
$script:SessTimeoutMarkers = @(
    '(?i)timed?\s*out', 'TimeoutException', 'SocketTimeout', 'ReadTimeout',
    'HttpTimeout', 'ConnectException', 'budget_exhausted', 'DeadlineExceeded',
    'request_budget_exhausted'
)
$script:SessFramePattern = 'at\s+((?:com\.example\.lms|ai\.abandonware)[\w.$]*?)\.([\w$<>]+)\(([\w$]+\.java):(\d+)\)'
$script:SessCausePattern = '(?:Caused by:\s*)?([a-zA-Z_][\w.$]*(?:Exception|Error))(?::\s*(.*))?$'

$script:SessResult = [ordered]@{ schemaVersion = 'awx.debug-session.v1'; ok = $false; status = 'started'; action = $script:Sess.Action; role = $script:Sess.Role }

function Write-SessionStage {
    param([string]$Stage, [string]$Status, [string]$Message)
    Write-DebugStage -Stage $Stage -Status $Status -Message $Message
}

function Limit-SessionText {
    param([string]$Text, [int]$Max = 300)
    if ($null -eq $Text) { return '' }
    if ($Text.Length -le $Max) { return $Text }
    return $Text.Substring(0, $Max)
}

function New-SessionPaths {
    param([string]$Dir)
    return [ordered]@{
        dir = $Dir
        state = (Join-Path $Dir 'session.json')
        log = (Join-Path $Dir 'session.log')
        events = (Join-Path $Dir 'events.jsonl')
        streamOut = (Join-Path $Dir 'stream-out.log')
        streamErr = (Join-Path $Dir 'stream-err.log')
        contexts = (Join-Path $Dir 'contexts')
        packetJson = (Join-Path $Dir 'packet.json')
        packetMd = (Join-Path $Dir 'packet.md')
        stopRequest = (Join-Path $Dir '_stop.request')
        watcherOut = (Join-Path $Dir 'watcher.out.log')
        watcherErr = (Join-Path $Dir 'watcher.err.log')
    }
}

function Find-SessionDir {
    # Explicit -SessionDir wins; otherwise newest <role>-*-session* under SessionRoot.
    if (-not [string]::IsNullOrWhiteSpace($script:Sess.SessionDir)) {
        return $script:Sess.SessionDir
    }
    $root = $script:Sess.SessionRoot
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { return $null }
    $dirs = @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match ('^' + $script:DbgRole + '-\d{8}-\d{6}-session') } |
        Sort-Object LastWriteTime -Descending)
    if ($dirs.Count -eq 0) { return $null }
    return $dirs[0].FullName
}

function Read-SessionState {
    param([string]$Dir)
    $p = New-SessionPaths -Dir $Dir
    if (-not (Test-Path -LiteralPath $p.state -PathType Leaf)) { return $null }
    try { return (Get-Content -LiteralPath $p.state -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Write-SessionState {
    param([hashtable]$Extra)
    $state = [ordered]@{
        schemaVersion = 'awx.debug-session-state.v1'
        dir = $script:SessPaths.dir; role = $script:DbgRole
        startedAt = $script:SessState.startedAt; watcherPid = $script:SessState.watcherPid
        status = $script:SessState.status; stopReason = $script:SessState.stopReason
        stoppedAt = $script:SessState.stoppedAt
        params = [ordered]@{
            duration = $script:Sess.Duration; poll = $script:Sess.Poll; heartbeat = $script:Sess.Heartbeat
            contextLines = $script:Sess.ContextLines; contextAfter = $script:Sess.ContextAfter
            ring = $script:Sess.Ring; afterDown = $script:Sess.AfterDown; maxContexts = $script:Sess.MaxContexts
        }
        target = $script:SessState.target; envStart = $script:SessState.envStart
        counters = $script:SessState.counters
    }
    foreach ($k in $Extra.Keys) { $state[$k] = $Extra[$k] }
    Write-AwxJsonAtomic -Path $script:SessPaths.state -Data $state
}

function Write-SessionEvent {
    param([string]$Kind, [string]$Source, [string]$Detail, [string]$Context = '')
    $script:SessState.seq++
    $evt = [ordered]@{
        ts = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fffzzz')
        seq = [int]$script:SessState.seq; kind = $Kind; source = $Source
        detail = (Limit-SessionText -Text $Detail -Max 300)
    }
    if ($Context) { $evt.context = $Context }
    Add-Content -LiteralPath $script:SessPaths.events -Value ($evt | ConvertTo-Json -Compress -Depth 4) -Encoding UTF8
    $script:SessEvents.Add([pscustomobject]$evt) | Out-Null
    if ($script:SessEvents.Count -gt 900) { $script:SessEvents.RemoveAt(0) }
    return $evt
}

function Push-SessionRing {
    param([string]$Line)
    $ring = $script:SessState.ring
    if ($ring.Count -ge $script:Sess.Ring) { [void]$ring.Dequeue() }
    $ring.Enqueue($Line)
}

function Get-SessionLineClass {
    # Order: fatal/soft exception classes (shared table with -Action verify) ->
    # HTTP error markers -> timeout markers -> pipeline stage markers -> plain.
    param([string]$Line)
    foreach ($def in @($script:DebugVerifyExceptionMarkers)) {
        foreach ($pat in @($def.patterns)) {
            if ($Line -match $pat) {
                return [ordered]@{ kind = 'error'; class = [string]$def.class; fatal = [bool]$def.fatal }
            }
        }
    }
    foreach ($pat in @($script:SessHttpMarkers)) {
        if ($Line -match $pat) { return [ordered]@{ kind = 'http'; class = 'http-error'; fatal = $false } }
    }
    foreach ($pat in @($script:SessTimeoutMarkers)) {
        if ($Line -match $pat) { return [ordered]@{ kind = 'timeout'; class = 'timeout'; fatal = $false } }
    }
    foreach ($def in @($script:DebugStageMarkers[$script:DbgRole])) {
        if (Test-DebugStageLine -Line $Line -Def $def) {
            return [ordered]@{ kind = 'stage'; class = [string](Get-DebugMarkerField -Def $def -Name 'stage'); fatal = $false }
        }
    }
    return [ordered]@{ kind = 'line'; class = ''; fatal = $false }
}

function Open-SessionStream {
    param([string]$Path, [switch]$FromStart)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $fs = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    if (-not $FromStart) { [void]$fs.Seek(0, [IO.SeekOrigin]::End) }
    $reader = [IO.StreamReader]::new($fs, [Text.Encoding]::UTF8, $true, 4096, $true)
    return @{ fs = $fs; reader = $reader; path = $Path }
}

function Save-ErrorContext {
    param([int]$Seq, [string]$Class, [string]$Source, [string]$Line)
    if ($script:SessState.contextFiles -ge $script:Sess.MaxContexts) {
        $script:SessState.contextsCapped = $true
        return ''
    }
    $script:SessState.contextFiles++
    $name = ('ctx-{0:d4}-{1}.log' -f $script:SessState.contextFiles, ($Class -replace '[^\w-]', '_'))
    $path = Join-Path $script:SessPaths.contexts $name
    $pre = @()
    $pre += ('# context event seq={0} class={1} ts={2}' -f $Seq, $Class, (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fffzzz'))
    $pre += ('# --- pre (ring, newest last, cap {0}) ---' -f $script:Sess.Ring)
    $pre += @($script:SessState.ring.ToArray())
    $pre += '# --- hit ---'
    $pre += ('{0}| {1}' -f $Source, $Line)
    $pre += '# --- post (appended for ~3s / {0} lines after the hit) ---' -f $script:Sess.ContextAfter
    Set-Content -LiteralPath $path -Value $pre -Encoding UTF8
    $script:SessState.pending.Add(@{ path = $path; left = [int]$script:Sess.ContextAfter; until = (Get-Date).AddSeconds(3) }) | Out-Null
    return $name
}

function Update-PendingContexts {
    param([string]$Source, [string]$Line)
    $now = Get-Date
    $done = @()
    foreach ($p in @($script:SessState.pending)) {
        if ($p.left -gt 0 -and $now -le $p.until) {
            Add-Content -LiteralPath $p.path -Value ('{0}| {1}' -f $Source, $Line) -Encoding UTF8
            $p.left--
        }
        if ($p.left -le 0 -or $now -gt $p.until) {
            Add-Content -LiteralPath $p.path -Value '# --- end ---' -Encoding UTF8
            $done += $p
        }
    }
    foreach ($p in $done) { $script:SessState.pending.Remove($p) | Out-Null }
}

function Close-PendingContexts {
    foreach ($p in @($script:SessState.pending)) {
        Add-Content -LiteralPath $p.path -Value '# --- end ---' -Encoding UTF8
    }
    $script:SessState.pending.Clear()
}

function Resolve-SessionTarget {
    # Synthetic fixture mode: -LogPath tails any file directly (tests, external
    # captures). Runtime mode: the live role runtime's ownership manifest decides
    # which out/err logs are the real stdout/stderr - never a latest-file guess.
    if (-not [string]::IsNullOrWhiteSpace($script:Sess.LogPath)) {
        return [ordered]@{
            synthetic = $true; pid = 0; port = 0; mgmtPort = 0; role = $script:DbgRole
            out = $script:Sess.LogPath; err = $script:Sess.ErrPath
            ownershipOk = $false; ownershipReason = 'synthetic-log-path'
            runIdHash = ''; runtimeCreated = ''; verboseLoggers = @(); readyAt = ''
        }
    }
    $runtimes = @(Get-DebugRuntimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    if ($runtimes.Count -eq 0) { return $null }
    $rt = $runtimes[0]
    $logInfo = Get-DebugLogPath -RuntimeRow $rt
    $out = if ($null -ne $logInfo) { [string]$logInfo.path } else { '' }
    $err = ''
    if ($rt.manifest.present -and $rt.manifest.logErr) {
        $candidate = Join-Path $script:RagRoot ([string]$rt.manifest.logErr)
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $err = $candidate }
    }
    return [ordered]@{
        synthetic = $false; pid = [int]$rt.processId; port = [int]$rt.port; mgmtPort = 18181
        role = $script:DbgRole; out = $out; err = $err
        ownershipOk = [bool]$rt.ownership.ok; ownershipReason = [string]$rt.ownership.reason
        runIdHash = [string]$rt.manifest.runIdHash; runtimeCreated = [string]$rt.creationDate
        verboseLoggers = @($rt.loggingLevels); readyAt = [string]$rt.manifest.readyAt
    }
}

function Get-SessionEnvironment {
    param([string]$RuntimeCreatedUtc, [int]$Port)
    $env = [ordered]@{}
    try { $env.ports = @(Get-DebugPortMap) } catch { $env.ports = @() }
    try {
        $w = Get-DebugWatcherRows
        $env.devWatch = if ($null -ne $w.state) {
            [ordered]@{ status = [string]$w.state.status; tier = [string]$w.state.lastTier; updatedAt = [string]$w.state.updatedAt }
        } else { [ordered]@{ status = 'not_observed' } }
    } catch { $env.devWatch = [ordered]@{ status = 'unavailable' } }
    try { $env.freshness = Get-DebugFreshness -RuntimeCreationUtc $RuntimeCreatedUtc -Port $Port } catch { $env.freshness = [ordered]@{ sourcesNewer = 'not_observed' } }
    $env.ollama = [ordered]@{}
    foreach ($p in @(11434, 11435)) {
        try { $env.ollama[[string]$p] = [bool](Test-RagOllama -Port $p) } catch { $env.ollama[[string]$p] = $false }
    }
    return [pscustomobject]$env
}

function Process-SessionLine {
    param([string]$Line, [string]$Source)
    $c = $script:SessState.counters
    if ($Source -eq 'out') { $c.linesOut++ } else { $c.linesErr++ }
    $streamFile = if ($Source -eq 'out') { $script:SessPaths.streamOut } else { $script:SessPaths.streamErr }
    Add-Content -LiteralPath $streamFile -Value $Line -Encoding UTF8
    Add-Content -LiteralPath $script:SessPaths.log -Value ('  {0}| {1}' -f $Source, $Line) -Encoding UTF8
    Push-SessionRing -Line ('{0}| {1}' -f $Source, $Line)
    $cls = Get-SessionLineClass -Line $Line
    switch ([string]$cls.kind) {
        'error' {
            $c.errors++
            $name = Save-ErrorContext -Seq ($script:SessState.seq + 1) -Class ([string]$cls.class) -Source $Source -Line $Line
            Write-SessionEvent -Kind 'log.error' -Source $Source -Context $name -Detail ('class={0} fatal={1} | {2}' -f $cls.class, $cls.fatal, (Limit-SessionText $Line 220)) | Out-Null
        }
        'http' {
            $c.httpErrors++
            $name = Save-ErrorContext -Seq ($script:SessState.seq + 1) -Class 'http' -Source $Source -Line $Line
            Write-SessionEvent -Kind 'log.http' -Source $Source -Context $name -Detail (Limit-SessionText $Line 240) | Out-Null
        }
        'timeout' {
            $c.timeouts++
            $name = Save-ErrorContext -Seq ($script:SessState.seq + 1) -Class 'timeout' -Source $Source -Line $Line
            Write-SessionEvent -Kind 'log.timeout' -Source $Source -Context $name -Detail (Limit-SessionText $Line 240) | Out-Null
        }
        'stage' {
            $stage = [string]$cls.class
            if (-not $c.stages.Contains($stage)) { $c.stages[$stage] = 0 }
            $c.stages[$stage]++
            $n = [int]$c.stages[$stage]
            if ($n -le 20 -or ($n % 60) -eq 0) {
                Write-SessionEvent -Kind 'log.stage' -Source $Source -Detail ('stage={0} hit#{1} | {2}' -f $stage, $n, (Limit-SessionText $Line 180)) | Out-Null
            }
        }
    }
}

function Probe-SessionState {
    $t = $script:SessState.target
    if ($t.synthetic) { return }
    $s = $script:SessState
    $alive = $false
    try { $alive = ($null -ne (Get-Process -Id ([int]$t.pid) -ErrorAction SilentlyContinue)) } catch { $alive = $false }
    if ($alive -ne $s.poll.pidAlive) {
        $s.poll.pidAlive = $alive
        Write-SessionEvent -Kind 'state' -Source 'probe' -Detail ('process pid={0} alive={1}' -f $t.pid, $alive) | Out-Null
    }
    $ownerPid = 0; $ownerName = ''; $ownerNote = ''
    try {
        $owner = Get-RagPortOwner -Port 18180
        if ($null -ne $owner) { $ownerPid = [int]$owner.processId; $ownerName = [string]$owner.processName }
    } catch { $ownerNote = $_.Exception.Message }
    if ($ownerPid -ne $s.poll.portOwner) {
        $s.poll.portOwner = $ownerPid
        Write-SessionEvent -Kind 'state' -Source 'probe' -Detail ('port=18180 ownerPid={0} owner={1} {2}' -f $ownerPid, $ownerName, $ownerNote) | Out-Null
    }
    $healthy = $false; $hStatus = -1
    if ($t.mgmtPort -gt 0) {
        $h = Get-RagHttp -Url ("http://127.0.0.1:{0}/actuator/health" -f $t.mgmtPort)
        $hStatus = [int]$h.status
        $healthy = ($hStatus -eq 200)
    }
    if ($healthy -ne $s.poll.healthy) {
        $s.poll.healthy = $healthy
        Write-SessionEvent -Kind 'state' -Source 'probe' -Detail ('mgmt-health status={0} healthy={1}' -f $hStatus, $healthy) | Out-Null
    }
    if (-not $healthy) { $s.counters.probeFails++ }
}

function Write-SessionHeartbeat {
    $c = $script:SessState.counters
    $s = $script:SessState
    Write-SessionEvent -Kind 'heartbeat' -Source 'watch' -Detail (
        'linesOut={0} linesErr={1} errors={2} http={3} timeouts={4} ctx={5} pidAlive={6} healthy={7}' -f
        $c.linesOut, $c.linesErr, $c.errors, $c.httpErrors, $c.timeouts, $s.contextFiles, $s.poll.pidAlive, $s.poll.healthy
    ) | Out-Null
}

function Get-SessionSuspectFiles {
    # Stack frames -> active sourceSet paths. existsOnDisk=false means the frame
    # maps to no live source path (stale/foreign code) - say so, never invent one.
    param([string[]]$Files)
    $hits = @{}
    foreach ($f in $Files) {
        if (-not $f -or -not (Test-Path -LiteralPath $f -PathType Leaf)) { continue }
        foreach ($line in @(Get-Content -LiteralPath $f -Encoding UTF8 -ErrorAction SilentlyContinue)) {
            foreach ($m in [regex]::Matches([string]$line, $script:SessFramePattern)) {
                $fqcn = [string]$m.Groups[1].Value
                $method = [string]$m.Groups[2].Value
                $file = [string]$m.Groups[3].Value
                $lineno = [int]$m.Groups[4].Value
                $pkg = $fqcn
                $dot = $pkg.LastIndexOf('.')
                if ($dot -gt 0) { $pkg = $pkg.Substring(0, $dot) }
                $dirPart = $pkg.Replace('.', '\')
                $rel = ('main\java\{0}\{1}' -f $dirPart, $file)
                $alt = ('app\src\main\java_clean\{0}\{1}' -f $dirPart, $file)
                $srcRel = ''
                $exists = $false
                foreach ($cand in @($rel, $alt)) {
                    if (Test-Path -LiteralPath (Join-Path $script:RagRoot $cand) -PathType Leaf) { $srcRel = $cand; $exists = $true; break }
                }
                if (-not $exists) { $srcRel = $rel }
                $key = '{0}.{1}({2}:{3})' -f $fqcn, $method, $file, $lineno
                if (-not $hits.ContainsKey($key)) {
                    $hits[$key] = [ordered]@{ frame = $key; className = $fqcn; method = $method; file = $file; line = $lineno; sourcePath = ($srcRel -replace '\\', '/'); existsOnDisk = $exists; hits = 0; firstTs = ''; lastTs = '' }
                }
                $hits[$key].hits++
                $tm = [regex]::Match([string]$line, '^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{4})')
                if ($tm.Success) {
                    if (-not $hits[$key].firstTs) { $hits[$key].firstTs = $tm.Groups[1].Value }
                    $hits[$key].lastTs = $tm.Groups[1].Value
                }
            }
        }
    }
    $rows = @($hits.Values | ForEach-Object { [pscustomobject]$_ } | Sort-Object -Property @{Expression = 'hits'; Descending = $true}, @{Expression = 'lastTs'; Descending = $true} | Select-Object -First 15)
    return $rows
}

function Get-SessionCauses {
    param([string[]]$Files)
    $causes = @{}
    foreach ($f in $Files) {
        if (-not $f -or -not (Test-Path -LiteralPath $f -PathType Leaf)) { continue }
        foreach ($line in @(Get-Content -LiteralPath $f -Encoding UTF8 -ErrorAction SilentlyContinue)) {
            $m = [regex]::Match([string]$line, 'Caused by:\s*([a-zA-Z_][\w.$]*(?:Exception|Error))(?::\s*(.*))?')
            if (-not $m.Success) { continue }
            $type = [string]$m.Groups[1].Value
            $msg = Limit-SessionText -Text ([string]$m.Groups[2].Value) -Max 160
            if (-not $causes.ContainsKey($type)) {
                $causes[$type] = [ordered]@{ type = $type; count = 0; firstMsg = $msg }
            }
            $causes[$type].count++
        }
    }
    return @($causes.Values | ForEach-Object { [pscustomobject]$_ } | Sort-Object -Property count -Descending | Select-Object -First 10)
}

function Build-SessionPacket {
    param([string]$Dir)
    $p = New-SessionPaths -Dir $Dir
    $state = Read-SessionState -Dir $Dir
    $events = @()
    if (Test-Path -LiteralPath $p.events -PathType Leaf) {
        foreach ($l in @(Get-Content -LiteralPath $p.events -Encoding UTF8 -ErrorAction SilentlyContinue)) {
            if ([string]::IsNullOrWhiteSpace($l)) { continue }
            try { $events += ($l | ConvertFrom-Json) } catch { }
        }
    }
    $scanFiles = @($p.streamOut, $p.streamErr) + @((Get-ChildItem -LiteralPath $p.contexts -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }))
    $suspects = @(Get-SessionSuspectFiles -Files $scanFiles)
    $causes = @(Get-SessionCauses -Files @($p.streamOut, $p.streamErr))
    $excScan = Get-DebugExceptionScan -LogPath $(if (Test-Path -LiteralPath $p.streamOut -PathType Leaf) { $p.streamOut } else { '' })
    $errorClasses = @($excScan.classes | Where-Object { $_.count -gt 0 })
    $httpHits = @($events | Where-Object { $_.kind -eq 'log.http' }).Count
    $timeoutHits = @($events | Where-Object { $_.kind -eq 'log.timeout' }).Count
    $pipeline = @()
    if (Test-Path -LiteralPath $p.streamOut -PathType Leaf) {
        try { $pipeline = @(Get-DebugStageSummary -LogPath $p.streamOut -ForRole $script:DbgRole) } catch { $pipeline = @() }
    }
    $target = if ($null -ne $state -and $null -ne $state.target) { $state.target } else { [pscustomobject]@{ synthetic = $true; pid = 0 } }
    $envEnd = $null
    if (-not [bool](Get-AwxObjectProperty $target 'synthetic')) {
        try { $envEnd = Get-SessionEnvironment -RuntimeCreatedUtc ([string](Get-AwxObjectProperty $target 'runtimeCreated')) -Port ([int](Get-AwxObjectProperty $target 'port')) } catch { $envEnd = $null }
    }
    $timeline = @()
    $interesting = @($events | Where-Object { $_.kind -ne 'log.stage' -or [int]$_.seq -le 40 })
    if ($interesting.Count -le 600) { $timeline = $interesting }
    else { $timeline = @($interesting | Select-Object -First 100) + @($interesting | Select-Object -Last 500) }
    $ctxNames = @(Get-ChildItem -LiteralPath $p.contexts -File -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
    $counters = if ($null -ne $state -and $null -ne $state.counters) { $state.counters } else { $null }
    $gaps = @()
    if ([bool](Get-AwxObjectProperty $target 'synthetic')) { $gaps += 'synthetic-target: process/port/probe monitoring skipped' }
    if (-not [string](Get-AwxObjectProperty $target 'err')) { $gaps += 'stderr stream not resolved (manifest logErr absent or empty)' }
    if ($events.Count -eq 0) { $gaps += 'no events captured' }
    if ($null -eq $envEnd) { $gaps += 'final environment snapshot not captured' }
    $packet = [ordered]@{
        schemaVersion = 'awx.debug-session.v1'
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        session = [ordered]@{
            dir = (ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $Dir)
            role = $script:DbgRole
            startedAt = if ($null -ne $state) { [string]$state.startedAt } else { '' }
            stoppedAt = if ($null -ne $state) { [string]$state.stoppedAt } else { '' }
            stopReason = if ($null -ne $state) { [string]$state.stopReason } else { '' }
        }
        target = $target
        environment = [ordered]@{ start = $(if ($null -ne $state) { $state.envStart } else { $null }); end = $envEnd }
        counts = [ordered]@{
            eventsTotal = $events.Count
            linesOut = $(if ($null -ne $counters) { [int]$counters.linesOut } else { 0 })
            linesErr = $(if ($null -ne $counters) { [int]$counters.linesErr } else { 0 })
            errors = $(if ($null -ne $counters) { [int]$counters.errors } else { @($events | Where-Object { $_.kind -eq 'log.error' }).Count })
            httpErrors = $httpHits; timeouts = $timeoutHits
            contextFiles = $ctxNames.Count
        }
        errorClasses = $errorClasses
        exceptionCauses = $causes
        pipeline = $pipeline
        suspectFiles = $suspects
        timeline = $timeline
        coverage = [ordered]@{ watched = @('stdout', 'stderr', 'process', 'port', 'http-health'); gaps = $gaps }
        howToAnalyze = @(
            'Order: events.jsonl seq == session.log order; stream-out.log is the verbatim app stdout window; contexts/ holds ring-buffer dumps around each error hit.',
            'First pass: take errorClasses[].firstHit and the earliest log.error/log.http events, then open suspectFiles[].sourcePath at :line and compare with the same timestamp in stream-out.log.',
            'existsOnDisk=false means the frame maps to no active sourceSet path - check environment.freshness (stale JVM) before blaming the code.',
            'state/probe events bracket real process/port/health changes; do not mistake a restart gap for an app error burst.',
            'pipeline[] stage counts come from the same markers as Debug -Action status; a stage at 0 with upstream hits narrows the failing seam.'
        )
        files = [ordered]@{
            packetJson = (ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $p.packetJson)
            packetMd = (ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $p.packetMd)
            eventsJsonl = (ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $p.events)
            sessionLog = (ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $p.log)
            streamOut = (ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $p.streamOut)
            streamErr = $(if (Test-Path -LiteralPath $p.streamErr -PathType Leaf) { ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $p.streamErr } else { '' })
            contexts = $ctxNames
        }
    }
    return [pscustomobject]$packet
}

function Write-SessionPacketMd {
    param([object]$Packet, [string]$Path)
    $L = New-Object 'System.Collections.Generic.List[string]'
    $s = $Packet.session
    $L.Add(('# Debug session packet - {0} {1}' -f $s.role, $s.dir))
    $L.Add('')
    $L.Add(('- window: `{0}` -> `{1}` (reason `{2}`)' -f $s.startedAt, $s.stoppedAt, $s.stopReason))
    $t = $Packet.target
    $L.Add(('- target: pid={0} port={1} role={2} synthetic={3} ownership={4}' -f $t.pid, $t.port, $t.role, $t.synthetic, $t.ownershipReason))
    if ($t.out) { $L.Add(('- stdout source: `{0}`' -f $t.out)) }
    if ($t.err) { $L.Add(('- stderr source: `{0}`' -f $t.err)) }
    $L.Add('')
    $L.Add('## How to analyze (for GPT Pro / agents)')
    foreach ($h in @($Packet.howToAnalyze)) { $L.Add('- ' + $h) }
    $L.Add('')
    $L.Add('## Counts')
    $c = $Packet.counts
    $L.Add('| linesOut | linesErr | errors | httpErrors | timeouts | contextFiles | events |')
    $L.Add('|---|---|---|---|---|---|---|')
    $L.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} |' -f $c.linesOut, $c.linesErr, $c.errors, $c.httpErrors, $c.timeouts, $c.contextFiles, $c.eventsTotal))
    $L.Add('')
    $L.Add('## Error classes (verbatim out-log scan)')
    $L.Add('| class | fatal | count | first hit |')
    $L.Add('|---|---|---|---|')
    foreach ($r in @($Packet.errorClasses)) { $L.Add(('| {0} | {1} | {2} | {3} |' -f $r.class, $r.fatal, $r.count, (Limit-SessionText ([string]$r.firstHit) 140))) }
    if (@($Packet.errorClasses).Count -eq 0) { $L.Add('| - | - | 0 | no exception-class hits |') }
    $L.Add('')
    if (@($Packet.exceptionCauses).Count -gt 0) {
        $L.Add('## Exception causes (Caused by)')
        $L.Add('| type | count | first message |')
        $L.Add('|---|---|---|')
        foreach ($r in @($Packet.exceptionCauses)) { $L.Add(('| {0} | {1} | {2} |' -f $r.type, $r.count, (Limit-SessionText ([string]$r.firstMsg) 140))) }
        $L.Add('')
    }
    $L.Add('## Suspect source files (stack frames mapped to active sourceSets)')
    $L.Add('| source:line | frame | hits | existsOnDisk |')
    $L.Add('|---|---|---|---|')
    foreach ($r in @($Packet.suspectFiles)) { $L.Add(('| {0}:{1} | {2} | {3} | {4} |' -f $r.sourcePath, $r.line, $r.frame, $r.hits, $r.existsOnDisk)) }
    if (@($Packet.suspectFiles).Count -eq 0) { $L.Add('| - | no app stack frames captured | 0 | - |') }
    $L.Add('')
    $L.Add('## Pipeline stages (same markers as Debug status)')
    $L.Add('| stage | count | lastAt |')
    $L.Add('|---|---|---|')
    foreach ($r in @($Packet.pipeline)) { $L.Add(('| {0} | {1} | {2} |' -f $r.stage, $r.count, $r.lastAt)) }
    $L.Add('')
    $L.Add('## Timeline (errors + state transitions; full log in events.jsonl)')
    $tail = @($Packet.timeline | Where-Object { $_.kind -in @('log.error', 'log.http', 'log.timeout', 'state', 'lifecycle', 'runtime.down', 'packet') } | Select-Object -Last 60)
    foreach ($e in $tail) { $L.Add(('- `{0}` #{1} **{2}** ({3}) {4}' -f $e.ts, $e.seq, $e.kind, $e.source, $e.detail)) }
    if ($tail.Count -eq 0) { $L.Add('- (no error/state events captured)') }
    $L.Add('')
    $L.Add('## Files')
    foreach ($k in @('eventsJsonl', 'sessionLog', 'streamOut', 'streamErr')) {
        if ($Packet.files.$k) { $L.Add(('- {0}: `{1}`' -f $k, $Packet.files.$k)) }
    }
    if (@($Packet.files.contexts).Count -gt 0) { $L.Add(('- contexts/: {0} files' -f @($Packet.files.contexts).Count)) }
    if (@($Packet.coverage.gaps).Count -gt 0) {
        $L.Add('')
        $L.Add('## Coverage gaps')
        foreach ($g in @($Packet.coverage.gaps)) { $L.Add('- ' + $g) }
    }
    Set-Content -LiteralPath $Path -Value ($L -join "`r`n") -Encoding UTF8
}

function Copy-SessionHandoff {
    param([string]$Dir)
    if ($script:Sess.NoHandoff) { return '' }
    $dest = Join-Path $script:Sess.HandoffRoot (Split-Path -Leaf $Dir)
    try {
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        $p = New-SessionPaths -Dir $Dir
        foreach ($f in @($p.packetJson, $p.packetMd, $p.events, $p.state)) {
            if (Test-Path -LiteralPath $f -PathType Leaf) { Copy-Item -LiteralPath $f -Destination $dest -Force }
        }
        if (Test-Path -LiteralPath $p.contexts -PathType Container) {
            Copy-Item -LiteralPath $p.contexts -Destination $dest -Recurse -Force
        }
        # bound handoff growth
        $dirs = @(Get-ChildItem -LiteralPath $script:Sess.HandoffRoot -Directory -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
        $i = 0
        foreach ($d in $dirs) { $i++; if ($i -gt 10) { Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction SilentlyContinue } }
        return $dest
    } catch { return '' }
}

function Invoke-SessionRetention {
    try {
        $dirs = @(Get-ChildItem -LiteralPath $script:Sess.SessionRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '-session' } | Sort-Object LastWriteTime -Descending)
        $i = 0
        foreach ($d in $dirs) { $i++; if ($i -gt 15) { Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction SilentlyContinue } }
    } catch { }
}

function Invoke-SessionWatch {
    $dir = $script:Sess.SessionDir
    if ([string]::IsNullOrWhiteSpace($dir)) {
        $dir = Join-Path $script:Sess.SessionRoot ('{0}-{1}-session' -f $script:DbgRole, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    }
    $script:SessPaths = New-SessionPaths -Dir $dir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    New-Item -ItemType Directory -Force -Path $script:SessPaths.contexts | Out-Null
    $script:DebugLog = $script:SessPaths.log
    foreach ($f in @($script:SessPaths.events, $script:SessPaths.streamOut, $script:SessPaths.streamErr)) {
        if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { New-Item -ItemType File -Force -Path $f | Out-Null }
    }
    $script:SessEvents = New-Object 'System.Collections.Generic.List[object]'
    $script:SessState = @{
        seq = 0; startedAt = (Get-Date).ToUniversalTime().ToString('o'); stoppedAt = ''
        watcherPid = $PID; status = 'attaching'; stopReason = ''
        ring = (New-Object 'System.Collections.Generic.Queue[string]')
        pending = (New-Object 'System.Collections.Generic.List[hashtable]')
        contextFiles = 0; contextsCapped = $false
        target = $null; envStart = $null
        poll = @{ pidAlive = $true; portOwner = -1; healthy = $true }
        counters = [ordered]@{ linesOut = 0; linesErr = 0; errors = 0; httpErrors = 0; timeouts = 0; probeFails = 0; stages = @{} }
    }
    Write-SessionStage 'SESSION' 'START' "dir=$dir role=$($script:DbgRole) duration=$($script:Sess.Duration)s poll=$($script:Sess.Poll)s"
    Write-SessionState -Extra @{}

    # --- attach: resolve a live runtime's manifest logs, or a -LogPath file ---
    $target = $null
    $attachDeadline = (Get-Date).AddSeconds($script:Sess.AttachWait)
    while ($null -eq $target -and (Get-Date) -le $attachDeadline) {
        $target = Resolve-SessionTarget
        if ($null -eq $target -or (-not $target.synthetic -and [string]::IsNullOrWhiteSpace([string]$target.out))) {
            if ($null -ne $target -and [string]::IsNullOrWhiteSpace([string]$target.out)) { $target = $null }
            if ($null -ne $target) { break }
            Start-Sleep -Seconds 2
            continue
        }
        if ($null -ne $target -and $target.synthetic -and -not (Test-Path -LiteralPath $target.out -PathType Leaf)) {
            $target = $null
            Start-Sleep -Seconds 2
            continue
        }
        break
    }
    if ($null -eq $target) {
        $script:SessState.status = 'not-running'; $script:SessState.stopReason = 'attach-timeout'
        Write-SessionState -Extra @{}
        Write-SessionStage 'SESSION' 'DOWN' "no $($script:DbgRole) runtime/log resolved within $($script:Sess.AttachWait)s; nothing captured"
        $script:SessResult.status = 'not-running'
        return 3
    }
    $script:SessState.target = [pscustomobject]$target
    $script:SessState.status = 'running'
    $script:SessState.envStart = Get-SessionEnvironment -RuntimeCreatedUtc ([string]$target.runtimeCreated) -Port ([int]$target.port)
    Write-SessionState -Extra @{}
    Write-SessionEvent -Kind 'lifecycle' -Source 'watch' -Detail ("attached target pid={0} port={1} synthetic={2} out={3}" -f $target.pid, $target.port, $target.synthetic, $target.out) | Out-Null
    Write-SessionStage 'SESSION' 'ATTACHED' "pid=$($target.pid) port=$($target.port) out=$($target.out) err=$($target.err) ownership=$($target.ownershipReason)"

    $outStream = $null; $errStream = $null
    if ($target.out -and (Test-Path -LiteralPath $target.out -PathType Leaf)) {
        $outStream = Open-SessionStream -Path $target.out -FromStart:$script:Sess.FromStart
        Write-SessionEvent -Kind 'lifecycle' -Source 'watch' -Detail ("stream out opened path={0} fromStart={1}" -f $target.out, $script:Sess.FromStart) | Out-Null
        # seed the ring so the first error already has context
        if (-not $script:Sess.FromStart) {
            foreach ($l in @(Get-DebugLogWindow -LogPath $target.out | Select-Object -Last $script:Sess.ContextLines)) {
                Push-SessionRing -Line ('out| ' + $l)
            }
        }
    }
    if ($target.err -and (Test-Path -LiteralPath $target.err -PathType Leaf)) {
        $errStream = Open-SessionStream -Path $target.err -FromStart:$script:Sess.FromStart
        Write-SessionEvent -Kind 'lifecycle' -Source 'watch' -Detail ("stream err opened path={0}" -f $target.err) | Out-Null
    }

    $deadline = $null
    if ($script:Sess.Duration -gt 0) { $deadline = (Get-Date).AddSeconds($script:Sess.Duration) }
    $lastPoll = [datetime]::MinValue
    $lastHeartbeat = Get-Date
    $lastStateWrite = Get-Date
    $downSince = $null
    $stopReason = 'stopped'
    Write-SessionStage 'WATCH' 'RUN' "watching (stop: $($script:SessPaths.stopRequest) or duration $($script:Sess.Duration)s)"

    try {
        while ($true) {
            # Flags are sampled first but acted on after the drain, so the final
            # pass still captures lines appended right up to the stop request.
            $stopHit = (Test-Path -LiteralPath $script:SessPaths.stopRequest -PathType Leaf)
            $timeHit = ($null -ne $deadline -and (Get-Date) -ge $deadline)
            $drained = 0
            if ($null -ne $outStream) {
                while ($drained -lt 5000) {
                    $line = $outStream.reader.ReadLine()
                    if ($null -eq $line) { break }
                    Process-SessionLine -Line ([string]$line) -Source 'out'
                    $drained++
                }
            }
            if ($null -ne $errStream) {
                while ($drained -lt 8000) {
                    $line = $errStream.reader.ReadLine()
                    if ($null -eq $line) { break }
                    Process-SessionLine -Line ([string]$line) -Source 'err'
                    $drained++
                }
            }
            if ($stopHit) { $stopReason = 'stop-requested'; break }
            if ($timeHit) { $stopReason = 'duration-reached'; break }
            if (((Get-Date) - $lastPoll).TotalSeconds -ge $script:Sess.Poll) {
                Probe-SessionState
                $lastPoll = Get-Date
            }
            if (((Get-Date) - $lastHeartbeat).TotalSeconds -ge $script:Sess.Heartbeat) {
                Write-SessionHeartbeat
                $lastHeartbeat = Get-Date
            }
            if (((Get-Date) - $lastStateWrite).TotalSeconds -ge 10) {
                Write-SessionState -Extra @{}
                $lastStateWrite = Get-Date
            }
            if (-not $target.synthetic -and -not $script:SessState.poll.pidAlive) {
                if ($null -eq $downSince) {
                    $downSince = Get-Date
                    Write-SessionEvent -Kind 'runtime.down' -Source 'watch' -Detail ("pid={0} not alive; grace {1}s before finalize" -f $target.pid, $script:Sess.AfterDown) | Out-Null
                    Write-SessionStage 'WATCH' 'DOWN' "runtime pid=$($target.pid) exited; capturing tail for $($script:Sess.AfterDown)s"
                } elseif (((Get-Date) - $downSince).TotalSeconds -ge $script:Sess.AfterDown) {
                    $stopReason = 'runtime-down'
                    break
                }
            }
            if ($drained -eq 0) { Start-Sleep -Milliseconds 700 }
        }
    } finally {
        Close-PendingContexts
        foreach ($s in @($outStream, $errStream)) {
            if ($null -ne $s) { try { $s.reader.Dispose() } catch { }; try { $s.fs.Dispose() } catch { } }
        }
    }

    $script:SessState.status = 'finalizing'; $script:SessState.stopReason = $stopReason
    $script:SessState.stoppedAt = (Get-Date).ToUniversalTime().ToString('o')
    Write-SessionState -Extra @{}
    Write-SessionEvent -Kind 'lifecycle' -Source 'watch' -Detail ("watch ended reason={0}" -f $stopReason) | Out-Null

    $packet = Build-SessionPacket -Dir $dir
    Write-AwxJsonAtomic -Path $script:SessPaths.packetJson -Data $packet
    Write-SessionPacketMd -Packet $packet -Path $script:SessPaths.packetMd
    $handoff = Copy-SessionHandoff -Dir $dir
    Write-SessionEvent -Kind 'packet' -Source 'watch' -Detail ("packet written json={0} md={1} handoff={2}" -f $script:SessPaths.packetJson, $script:SessPaths.packetMd, $handoff) | Out-Null
    $script:SessState.status = 'finished'
    Write-SessionState -Extra ([ordered]@{ packet = [ordered]@{ json = $script:SessPaths.packetJson; md = $script:SessPaths.packetMd; handoff = $handoff } })
    Invoke-SessionRetention
    Write-SessionStage 'SESSION' 'DONE' "reason=$stopReason packet=$($script:SessPaths.packetJson) handoff=$handoff"
    $script:SessResult.status = 'finished'
    $script:SessResult.ok = $true
    $script:SessResult.sessionDir = $dir
    $script:SessResult.packet = $script:SessPaths.packetJson
    $script:SessResult.packetMd = $script:SessPaths.packetMd
    $script:SessResult.handoff = $handoff
    return 0
}

function Invoke-SessionStart {
    $existing = Find-SessionDir
    if ($existing) {
        $st = Read-SessionState -Dir $existing
        if ($null -ne $st -and [string]$st.status -eq 'running') {
            $wpid = [int]$st.watcherPid
            $alive = ($wpid -gt 0 -and $null -ne (Get-Process -Id $wpid -ErrorAction SilentlyContinue))
            if ($alive -and -not $script:Sess.Force) {
                Write-SessionStage 'START' 'BLOCKED' "session already running dir=$existing watcherPid=$wpid (use -Action stop or -Force)"
                $script:SessResult.status = 'session-active'
                $script:SessResult.sessionDir = $existing
                return 2
            }
        }
    }
    $dir = Join-Path $script:Sess.SessionRoot ('{0}-{1}-session' -f $script:DbgRole, (Get-Date -Format 'yyyyMMdd-HHmmss'))
    $p = New-SessionPaths -Dir $dir
    New-Item -ItemType Directory -Force -Path $p.contexts | Out-Null
    $argList = @(
        '-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $script:SessSelf),
        '-Action', 'run', '-Role', $script:DbgRole,
        '-SessionDir', ('"{0}"' -f $dir),
        '-SessionRoot', ('"{0}"' -f $script:Sess.SessionRoot),
        '-DurationSeconds', [string]$script:Sess.Duration,
        '-PollSeconds', [string]$script:Sess.Poll,
        '-HeartbeatSeconds', [string]$script:Sess.Heartbeat,
        '-ContextLines', [string]$script:Sess.ContextLines,
        '-ContextAfterLines', [string]$script:Sess.ContextAfter,
        '-RingLines', [string]$script:Sess.Ring,
        '-AfterRuntimeDownSeconds', [string]$script:Sess.AfterDown,
        '-AttachWaitSeconds', [string]$script:Sess.AttachWait,
        '-MaxContextFiles', [string]$script:Sess.MaxContexts
    )
    if ($script:Sess.LogPath) { $argList += @('-LogPath', ('"{0}"' -f $script:Sess.LogPath)) }
    if ($script:Sess.ErrPath) { $argList += @('-ErrPath', ('"{0}"' -f $script:Sess.ErrPath)) }
    if ($script:Sess.FromStart) { $argList += '-FromStart' }
    if ($script:Sess.NoHandoff) { $argList += '-NoHandoff' }
    Write-SessionStage 'START' 'RUN' "spawn watcher -> $dir"
    $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList ($argList -join ' ') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $p.watcherOut -RedirectStandardError $p.watcherErr
    Start-Sleep -Seconds 2
    $st = Read-SessionState -Dir $dir
    $script:SessResult.sessionDir = $dir
    $script:SessResult.watcherPid = [int]$proc.Id
    if ($null -ne $st -and [string]$st.status -in @('attaching', 'running')) {
        Write-SessionStage 'START' 'OK' "watcher pid=$($proc.Id) session=$dir"
        $script:SessResult.status = 'spawned'; $script:SessResult.ok = $true
        return 0
    }
    Write-SessionStage 'START' 'WARN' "watcher pid=$($proc.Id) spawned but session.json not yet live; see $($p.watcherErr)"
    $script:SessResult.status = 'spawned-unconfirmed'; $script:SessResult.ok = $true
    return 0
}

function Invoke-SessionStatus {
    $dir = Find-SessionDir
    if ($null -eq $dir) {
        Write-SessionStage 'STATUS' 'NONE' "no $($script:DbgRole) session dir under $($script:Sess.SessionRoot)"
        $script:SessResult.status = 'no-session'
        return 3
    }
    $p = New-SessionPaths -Dir $dir
    $st = Read-SessionState -Dir $dir
    $lastEvent = ''
    if (Test-Path -LiteralPath $p.events -PathType Leaf) {
        $lastEvent = [string](Get-Content -LiteralPath $p.events -Tail 1 -Encoding UTF8 -ErrorAction SilentlyContinue)
    }
    $eventCount = 0
    if (Test-Path -LiteralPath $p.events -PathType Leaf) {
        $eventCount = @(Get-Content -LiteralPath $p.events -Encoding UTF8 -ErrorAction SilentlyContinue).Count
    }
    $watcherAlive = $false
    if ($null -ne $st -and [int]$st.watcherPid -gt 0) {
        $watcherAlive = ($null -ne (Get-Process -Id ([int]$st.watcherPid) -ErrorAction SilentlyContinue))
    }
    $status = if ($null -ne $st) { [string]$st.status } else { 'state-missing' }
    Write-SessionStage 'STATUS' 'INFO' "dir=$dir status=$status watcherAlive=$watcherAlive events=$eventCount"
    if ($lastEvent) { Write-SessionStage 'STATUS' 'LAST' (Limit-SessionText $lastEvent 220) }
    $script:SessResult.status = 'found'
    $script:SessResult.ok = $true
    $script:SessResult.sessionDir = $dir
    $script:SessResult.watchStatus = $status
    $script:SessResult.watcherAlive = $watcherAlive
    $script:SessResult.events = $eventCount
    $script:SessResult.lastEvent = (Limit-SessionText $lastEvent 300)
    if ($null -ne $st -and $null -ne $st.counters) { $script:SessResult.counters = $st.counters }
    if ($null -ne $st -and $null -ne $st.packet) { $script:SessResult.packet = $st.packet }
    return 0
}

function Invoke-SessionStop {
    $dir = Find-SessionDir
    if ($null -eq $dir) {
        Write-SessionStage 'STOP' 'NONE' "no $($script:DbgRole) session dir under $($script:Sess.SessionRoot)"
        $script:SessResult.status = 'no-session'
        return 3
    }
    $p = New-SessionPaths -Dir $dir
    $st = Read-SessionState -Dir $dir
    if ($null -eq $st) {
        Write-SessionStage 'STOP' 'FAILED' "session.json missing in $dir"
        $script:SessResult.status = 'state-missing'
        return 1
    }
    if ([string]$st.status -ne 'running' -and [string]$st.status -ne 'attaching') {
        Write-SessionStage 'STOP' 'INFO' "session already $($st.status); nothing to stop"
        $script:SessResult.status = [string]$st.status; $script:SessResult.ok = $true
        $script:SessResult.sessionDir = $dir
        return 0
    }
    Set-Content -LiteralPath $p.stopRequest -Value ((Get-Date).ToUniversalTime().ToString('o')) -Encoding UTF8
    Write-SessionStage 'STOP' 'SENT' "stop flag written -> $($p.stopRequest); waiting for finalize"
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $st = Read-SessionState -Dir $dir
        if ($null -ne $st -and [string]$st.status -in @('finished', 'not-running')) {
            Write-SessionStage 'STOP' 'OK' "session finalized dir=$dir status=$($st.status) packet=$($st.packet.json)"
            $script:SessResult.status = [string]$st.status; $script:SessResult.ok = $true
            $script:SessResult.sessionDir = $dir
            $script:SessResult.packet = $st.packet
            return 0
        }
        $wpid = [int]$st.watcherPid
        if ($wpid -gt 0 -and $null -eq (Get-Process -Id $wpid -ErrorAction SilentlyContinue)) {
            Write-SessionStage 'STOP' 'WARN' "watcher exited without finished state; run -Action finalize -SessionDir `"$dir`" to rebuild the packet"
            $script:SessResult.status = 'watcher-exited-unfinalized'; $script:SessResult.ok = $true
            $script:SessResult.sessionDir = $dir
            return 0
        }
    }
    Write-SessionStage 'STOP' 'WARN' "still finalizing after 60s; session=$dir (check -Action status later)"
    $script:SessResult.status = 'still-running'; $script:SessResult.sessionDir = $dir
    return 4
}

function Invoke-SessionFinalize {
    $dir = Find-SessionDir
    if ($null -eq $dir) {
        Write-SessionStage 'FINALIZE' 'NONE' "no $($script:DbgRole) session dir under $($script:Sess.SessionRoot)"
        $script:SessResult.status = 'no-session'
        return 3
    }
    $script:SessPaths = New-SessionPaths -Dir $dir
    $st = Read-SessionState -Dir $dir
    if ($null -ne $st -and [string]$st.status -eq 'running') {
        Write-SessionStage 'FINALIZE' 'WARN' "session still running; use -Action stop first for a clean end (rebuilding anyway)"
    }
    Write-SessionStage 'FINALIZE' 'RUN' "rebuilding packet for $dir"
    $packet = Build-SessionPacket -Dir $dir
    Write-AwxJsonAtomic -Path $script:SessPaths.packetJson -Data $packet
    Write-SessionPacketMd -Packet $packet -Path $script:SessPaths.packetMd
    $handoff = Copy-SessionHandoff -Dir $dir
    Write-SessionStage 'FINALIZE' 'DONE' "packet=$($script:SessPaths.packetJson) handoff=$handoff"
    $script:SessResult.status = 'finalized'; $script:SessResult.ok = $true
    $script:SessResult.sessionDir = $dir
    $script:SessResult.packet = $script:SessPaths.packetJson
    $script:SessResult.packetMd = $script:SessPaths.packetMd
    $script:SessResult.handoff = $handoff
    return 0
}

function Show-SessionHelp {
    $lines = @(
        ''
        'Debug-Session.bat -> scripts\debug_session_watch.ps1'
        ''
        'Continuous session capture for the running dev|wear runtime (stdout+stderr via'
        'the ownership manifest), exception/http/timeout classification with pre/post'
        'context, process/port/health polling, chronological events + a GPT-Pro-ready'
        'packet.json/packet.md. Read-only on existing logs; never stops the server.'
        ''
        'ACTIONS:'
        '  run       foreground watch until -DurationSeconds, stop flag, or runtime-down'
        '            grace. Default duration 0 = until stopped.'
        '  start     detached watch; prints the session dir to hand to status/stop.'
        '  status    progress + counters of the newest (or -SessionDir) session.'
        '  stop      signal finalize; waits up to 60s, prints packet path.'
        '  finalize  rebuild packet.json/packet.md for a dead/interrupted session.'
        ''
        'OPTIONS:'
        '  -Role dev|wear        runtime role (default dev)'
        '  -DurationSeconds n    bounded run (0 = until stopped; max 28800)'
        '  -PollSeconds n        process/port/health poll interval (default 15)'
        '  -HeartbeatSeconds n   heartbeat event interval (default 120)'
        '  -ContextLines n       pre-error ring lines saved per hit (default 40)'
        '  -ContextAfterLines n  post-error lines appended per hit (default 30)'
        '  -AfterRuntimeDownSeconds n  capture tail this long after pid exit (default 120)'
        '  -AttachWaitSeconds n  wait for a runtime/log before giving up (default 30)'
        '  -LogPath <file>       tail this file instead of a runtime (fixtures/captures)'
        '  -ErrPath <file>       optional second stream with -LogPath'
        '  -FromStart            read the log from the beginning, not just new lines'
        '  -SessionDir <dir>     explicit session dir (status/stop/finalize/child run)'
        '  -SessionRoot <dir>    parent dir for sessions (default var\debug)'
        '  -NoHandoff            skip the data\agent-handoff\display-debug\sessions copy'
        '  -Force                start even if another session looks active'
        '  -Json / -JsonStdout   result JSON to file/stdout'
        ''
        'EXAMPLES:'
        '  Debug-Session.bat -Role wear -Action start        # reproduce bug while it watches'
        '  Debug-Session.bat -Role wear -Action status'
        '  Debug-Session.bat -Role wear -Action stop         # finalize -> packet path'
        '  Debug-Session.bat -Role dev -Action run -DurationSeconds 300'
        ''
        'HANDOFF: send packet.md (+packet.json) to the reviewer; it already maps stack'
        'frames to active sourceSet paths and lists coverage gaps - no full-tree rescan.'
    )
    foreach ($l in $lines) { Write-Host $l }
}

function Invoke-SessionEntry {
    try {
        if ($script:Sess.JsonStdout) { $script:DbgJsonStdout = $true }
        if ($script:Sess.Help -or $script:Sess.Action -eq 'help') { Show-SessionHelp; return 0 }
        $code = 0
        switch ($script:Sess.Action) {
            'run'      { $code = Invoke-SessionWatch }
            'start'    { $code = Invoke-SessionStart }
            'status'   { $code = Invoke-SessionStatus }
            'stop'     { $code = Invoke-SessionStop }
            'finalize' { $code = Invoke-SessionFinalize }
            default    { Write-SessionStage 'INIT' 'FAILED' "unknown action $($script:Sess.Action)"; $code = 1 }
        }
        $script:SessResult.exitCode = $code
        if ($script:Sess.Json) {
            $jsonPath = Join-Path $script:Sess.SessionRoot ("{0}-{1}-session-{2}.json" -f $script:DbgRole, (Get-Date -Format 'yyyyMMdd-HHmmss'), $script:Sess.Action)
            Write-AwxJsonAtomic -Path $jsonPath -Data $script:SessResult
        }
        if ($script:Sess.JsonStdout) {
            [Console]::Out.Write(($script:SessResult | ConvertTo-Json -Depth 10 -Compress))
            [Console]::Out.Write([Environment]::NewLine)
        }
        return $code
    } catch {
        $reason = $_.Exception.Message
        $where = ($_.ScriptStackTrace -split "`n" | Select-Object -First 1)
        Write-SessionStage 'FATAL' 'FAILED' "reason=$reason at=$where"
        if ($script:Sess.JsonStdout) {
            $script:SessResult.status = 'error'; $script:SessResult.error = $reason; $script:SessResult.exitCode = 1
            [Console]::Out.Write(($script:SessResult | ConvertTo-Json -Depth 10 -Compress))
            [Console]::Out.Write([Environment]::NewLine)
        }
        return 1
    }
}

if ($MyInvocation.InvocationName -ne '.') { exit (Invoke-SessionEntry) }

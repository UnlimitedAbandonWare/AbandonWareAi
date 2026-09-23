#requires -Version 5.1
<#
.SYNOPSIS
  Shared diagnostics for the BAT-managed RAG/Meta Display runtimes.
  Called by Debug-RAG.bat (-Role dev) and Debug-Meta-Display.bat (-Role wear).

.DESCRIPTION
  Default action (status) is read-only: it never stops, restarts, or launches
  a server. Verbose logging is opt-in via -Action start|restart and applies
  ONLY to that launch through process-env AWX_RAG_DEBUG_LOGGERS, which the
  listener turns into visible --logging.level.* JVM args. Nothing is written
  to User/Machine environment. Diagnostics artifacts stay under var\debug\
  with bounded retention. No secrets, env dumps, raw cmdlines, transcripts,
  or full payloads are emitted.

  Exit codes:
    status : 0 ready, 3 not-running, 4 degraded (unproven ownership / not ready), 1 diag error
    start  : 0 ready+verbose applied, 5 ready but verbose NOT applied (reused JVM),
             2 blocked (protected/conflict/duplicate), 1 failed
    restart: same as start; stop phase failure -> 1
    tail/threads/jfr : 0 ok, 3 not-running, 1 failed
    verify : 0 all executed checks pass (skipped/blocked/not_observed are
             reported as run-state, not passes), 3 not-running,
             6 one or more checks failed (JSON/report names failedChecks +
             firstFailure + evidence + verdict), 1 diag error
#>
[CmdletBinding()]
param(
    [ValidateSet('dev','wear')][string]$Role = 'dev',
    [ValidateSet('status','start','restart','tail','threads','jfr','verify','help')][string]$Action = 'status',
    [ValidateRange(5,600)][int]$TailSeconds = 30,
    [ValidateRange(10,1800)][int]$TimeoutSeconds = 600,
    [ValidateRange(10,300)][int]$JfrSeconds = 60,
    [ValidateRange(50,20000)][int]$MaxTailLines = 1500,
    [ValidateRange(1,240)][int]$LogMinutes = 60,
    [ValidateRange(0,50)][int]$StageContextLines = 0,
    [ValidateRange(0,200)][int]$HistoryLines = 30,
    [string]$Loggers = '',
    [string]$Pattern = '',
    [switch]$OpenBrowser,
    [switch]$WithCompile,
    [switch]$DryRun,
    [switch]$Json,
    [switch]$JsonStdout,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# Bound params captured before dot-sourcing: the sourced scripts' own param()
# blocks would otherwise reset same-named variables (TimeoutSeconds, DryRun,
# OpenBrowser) in this scope.
$script:DbgRole = $Role
$script:DbgAction = $Action
$script:DbgTailSeconds = [int]$TailSeconds
$script:DbgTimeout = [int]$TimeoutSeconds
$script:DbgJfrSeconds = [int]$JfrSeconds
$script:DbgMaxTailLines = [int]$MaxTailLines
$script:DbgLogMinutes = [int]$LogMinutes
$script:DbgStageContext = [int]$StageContextLines
$script:DbgHistoryLines = [int]$HistoryLines
$script:DbgLoggers = [string]$Loggers
$script:DbgPattern = [string]$Pattern
$script:DbgOpenBrowser = [bool]$OpenBrowser
$script:DbgWithCompile = [bool]$WithCompile
$script:DbgDryRun = [bool]$DryRun
$script:DbgJson = [bool]$Json
$script:DbgJsonStdout = [bool]$JsonStdout
$script:DbgHelp = [bool]$Help
# stop_rag_stack.ps1 dot-sources start_rag_stack.ps1 + chat_ui_vibe_lifecycle.ps1;
# both guard execution behind InvocationName -ne '.', so this import is inert.
. (Join-Path $PSScriptRoot 'stop_rag_stack.ps1')

$script:DebugDir = Join-Path $script:RagRoot 'var\debug'
New-Item -ItemType Directory -Force -Path $script:DebugDir | Out-Null
$script:RunStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$script:DebugLog = Join-Path $script:DebugDir ("{0}-{1}-{2}.log" -f $script:DbgRole, $script:RunStamp, $script:DbgAction)
$script:Report = [ordered]@{
    schemaVersion = 'awx.rag_debug.v1'; role = $script:DbgRole; action = $script:DbgAction
    ok = $false; status = 'started'; runtimes = @(); watchers = @(); notes = @()
}

function Write-DebugStage {
    param([string]$Stage, [string]$Status, [string]$Message)
    $line = '{0} [DBG {1}] {2} {3}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Stage, $Status, $Message
    # -JsonStdout keeps stdout a single JSON document; stage lines still land
    # in the per-run debug log file.
    if (-not $script:DbgJsonStdout) { Write-Host $line }
    Add-Content -LiteralPath $script:DebugLog -Value $line -Encoding UTF8
}

function Add-DebugNote {
    param([string]$Note)
    $script:Report.notes += $Note
}

function Get-DebugProcessFlags {
    # Safe fields only from the live command line; the raw cmdline is never printed.
    param([int]$ProcessId)
    $flags = [ordered]@{
        observed = $false; loggingLevels = @(); runtimeRole = ''
        serverPort = 0; profile = ''; runIdPresent = $false
    }
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $proc) { return [pscustomobject]$flags }
    $cmd = [string]$proc.CommandLine
    if ([string]::IsNullOrWhiteSpace($cmd)) { return [pscustomobject]$flags }
    $flags.observed = $true
    foreach ($m in [regex]::Matches($cmd, '--logging\.level\.([A-Za-z0-9][A-Za-z0-9._-]{0,119})=(TRACE|DEBUG|INFO|WARN|ERROR)')) {
        $flags.loggingLevels += ($m.Groups[1].Value + '=' + $m.Groups[2].Value)
    }
    $mm = [regex]::Match($cmd, '--awx\.runtime\.role=([^\s"]+)')
    if ($mm.Success) { $flags.runtimeRole = $mm.Groups[1].Value }
    $mm = [regex]::Match($cmd, '(?:--|-D)server\.port=(\d+)')
    if ($mm.Success) { $flags.serverPort = [int]$mm.Groups[1].Value }
    $mm = [regex]::Match($cmd, '--spring\.profiles\.active=([^\s"]+)')
    if ($mm.Success) { $flags.profile = $mm.Groups[1].Value }
    $flags.runIdPresent = ($cmd -match '--awx\.runtime\.run-id=')
    return [pscustomobject]$flags
}

function Get-DebugRuntimes {
    $rows = @()
    foreach ($candidate in @(Get-RagSpringCandidates)) {
        $procId = [int](Get-AwxObjectProperty $candidate 'processId')
        $role = [string](Get-AwxObjectProperty $candidate 'role')
        $meta = [bool](Get-AwxObjectProperty $candidate 'metaDisplay')
        $identity = Get-AwxProcessIdentity -ProcessId $procId
        $flags = Get-DebugProcessFlags -ProcessId $procId
        $manifest = Find-RagRuntimeManifest -ProcessId $procId
        $row = [ordered]@{
            processId = $procId; role = $role; metaDisplay = $meta
            port = [int](Get-AwxObjectProperty $candidate 'port')
            creationDate = if ($null -ne $identity) { [string]$identity.creationDate } else { '' }
            loggingLevels = @($flags.loggingLevels); verboseApplied = (@($flags.loggingLevels).Count -gt 0)
            cmdlineObserved = [bool]$flags.observed
            ownership = [ordered]@{ ok = $false; reason = 'not-checked'; launcherPid = 0; listenerPid = 0 }
            manifest = [ordered]@{ present = $false; runtimeRole = ''; runIdHash = ''; runDir = ''; logOut = ''; logErr = ''; startedAt = ''; readyAt = ''; ports = @() }
        }
        if ($null -ne $manifest) {
            $row.manifest.present = $true
            $row.manifest.runtimeRole = [string](Get-AwxObjectProperty $manifest 'runtimeRole')
            $row.manifest.runIdHash = [string](Get-AwxObjectProperty $manifest 'runIdHash')
            $row.manifest.startedAt = [string](Get-AwxObjectProperty $manifest 'startedAt')
            $row.manifest.readyAt = [string](Get-AwxObjectProperty $manifest 'readyAt')
            $mPorts = Get-AwxObjectProperty $manifest 'ports'
            if ($null -ne $mPorts) {
                $row.manifest.ports = @([int](Get-AwxObjectProperty $mPorts 'server'), [int](Get-AwxObjectProperty $mPorts 'management'), [int](Get-AwxObjectProperty $mPorts 'netty')) | Where-Object { $_ -gt 0 }
            }
            $mLogs = Get-AwxObjectProperty $manifest 'logs'
            if ($null -ne $mLogs) {
                $row.manifest.logOut = [string](Get-AwxObjectProperty $mLogs 'out')
                $row.manifest.logErr = [string](Get-AwxObjectProperty $mLogs 'err')
                if ($row.manifest.logOut) { $row.manifest.runDir = (Split-Path -Parent (Join-Path $script:RagRoot $row.manifest.logOut)) }
            }
            $proof = Test-AwxOwnedRuntimeIdentity -Manifest $manifest -Root $script:RagRoot
            $row.ownership.ok = [bool]$proof.ok
            $row.ownership.reason = [string]$proof.reason
            $row.ownership.launcherPid = [int]$proof.launcherPid
            $row.ownership.listenerPid = [int]$proof.listenerPid
        } else {
            $row.ownership.reason = 'manifest-missing'
        }
        $rows += [pscustomobject]$row
    }
    return $rows
}

function Get-DebugPortMap {
    $map = @()
    foreach ($port in @(18180, 18181, 18182)) {
        $owner = $null; $note = ''
        try { $owner = Get-RagPortOwner -Port $port } catch { $note = $_.Exception.Message }
        $map += [pscustomobject][ordered]@{
            port = $port
            ownerPid = if ($null -ne $owner) { [int]$owner.processId } else { 0 }
            ownerName = if ($null -ne $owner) { [string]$owner.processName } else { '' }
            ownerNote = $note
        }
    }
    return $map
}

function Get-DebugWatcherRows {
    $rows = @()
    foreach ($w in @(Get-StopWatchTargets)) {
        $rows += [pscustomobject][ordered]@{
            processId = [int]$w.processId; metaDisplay = [bool]$w.metaDisplay; alive = $true
        }
    }
    $statePath = Join-Path $script:RagRoot 'var\dev-reload\watch.state.json'
    $state = $null
    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        try { $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    return [pscustomobject][ordered]@{
        watchers = @($rows)
        state = $state
        logTail = @(Get-Content -LiteralPath (Join-Path $script:RagRoot 'var\dev-reload\dev-reload.log') -Tail 6 -ErrorAction SilentlyContinue)
    }
}

function Get-DebugFreshness {
    param([string]$RuntimeCreationUtc, [int]$Port = 0)
    $roots = @('main\java', 'main\resources', 'app\src\main\java_clean', 'app\src\main\resources') |
        ForEach-Object { Join-Path $script:RagRoot $_ } | Where-Object { Test-Path -LiteralPath $_ }
    $newest = $null
    foreach ($root in $roots) {
        $candidate = Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -ne $candidate -and ($null -eq $newest -or $candidate.LastWriteTime -gt $newest.LastWriteTime)) { $newest = $candidate }
    }
    $row = [ordered]@{
        newestSource = ''; newestSourceWriteUtc = ''; runtimeCreatedUtc = $RuntimeCreationUtc
        sourcesNewer = 'not_observed'; servedAsset = [ordered]@{ path = ''; sourceHash = ''; servedHash = ''; match = 'not_observed' }
    }
    if ($null -ne $newest) {
        $row.newestSource = ConvertTo-AwxRelativePath -Root $script:RagRoot -Path $newest.FullName
        $row.newestSourceWriteUtc = $newest.LastWriteTime.ToUniversalTime().ToString('o')
        if (-not [string]::IsNullOrWhiteSpace($RuntimeCreationUtc)) {
            try {
                $created = [datetime]::Parse($RuntimeCreationUtc).ToUniversalTime()
                if ($newest.LastWriteTime.ToUniversalTime() -gt $created) {
                    $rel = [string]$row.newestSource
                    $row.sourcesNewer = if ($rel -match '(?i)assist\\|assets\\display\\|application-meta-display') {
                        'true-stale-candidate'
                    } else {
                        'unrelated-source-mtime'
                    }
                } else {
                    $row.sourcesNewer = 'false'
                }
            } catch { }
        }
    }
    if ($script:DbgRole -eq 'wear' -and $Port -gt 0) {
        $rel = 'main\resources\static\assets\display\meta\receiver.js'
        $src = Join-Path $script:RagRoot $rel
        $row.servedAsset.path = $rel
        if (Test-Path -LiteralPath $src -PathType Leaf) {
            try {
                $row.servedAsset.sourceHash = Get-AwxFileSha256Hex -Path $src
                $wc = [Net.WebClient]::new()
                try {
                    $bytes = $wc.DownloadData(('http://127.0.0.1:{0}/assets/display/meta/receiver.js' -f $Port))
                    $row.servedAsset.servedHash = Get-AwxSha256Hex -Bytes $bytes
                    $row.servedAsset.match = if ($row.servedAsset.sourceHash -ceq $row.servedAsset.servedHash) { 'match' } else { 'mismatch' }
                } finally { $wc.Dispose() }
            } catch {
                $row.servedAsset.match = 'fetch-failed'
            }
        }
    }
    return [pscustomobject]$row
}

function Get-DebugLogPath {
    param([object]$RuntimeRow)
    if ($null -ne $RuntimeRow -and $RuntimeRow.manifest.logOut) {
        $p = Join-Path $script:RagRoot $RuntimeRow.manifest.logOut
        if (Test-Path -LiteralPath $p -PathType Leaf) { return @{ path = $p; source = 'ownership-manifest' } }
    }
    return $null
}

$script:DebugRetentionPattern = '^(dev|wear)-\d{8}-\d{6}-|^(dev|wear)-tail-|^threads-(dev|wear)-|\.jfr$'
$script:DebugLogWindowMaxBytes = 8MB
$script:DebugLogWindowMaxLines = 20000

$script:DebugStageMarkers = @{
    wear = @(
        @{ stage = 'transcript-input'; patterns = @('stt\.usage', 'display\.conversate inputPath=', 'interview\.display\.accepted') }
        @{ stage = 'cue-gate'; patterns = @('conversate\.cue ') }
        @{ stage = 'hint-generate'; patterns = @('conversate\.cue '); require = 'hintGenerated=true' }
        @{ stage = 'search-routing'; patterns = @('\[Hybrid\]', '\[EVIDENCE_GATE\]', '\[llm-failover\]') }
        @{ stage = 'display-delivery'; patterns = @('display\.runtime stage='); exclude = @('http_error', 'request_failed') }
        @{ stage = 'errors'; patterns = @('display\.request_failed', 'display\.runtime stage=http_error', '\[API_FAILURE\]', 'chat-failed', 'stream-failed') }
    )
    dev = @(
        @{ stage = 'request-accept'; patterns = @('\[HTTP-IN\]', '\[ChatApi\]', 'interview\.display\.accepted') }
        @{ stage = 'search'; patterns = @('\[Hybrid\]', 'All search providers failed') }
        @{ stage = 'retrieval-gate'; patterns = @('\[EVIDENCE_GATE\]', '\[TRACE_SNAPSHOT\]') }
        @{ stage = 'model-call-fallback'; patterns = @('\[llm-failover\]', '\[LLM_REQUEST_LIFECYCLE\]', '\[local-circuit\]', '\[SingleFlight\]') }
        @{ stage = 'errors'; patterns = @('\[API_FAILURE\]', 'chat-failed', 'stream-failed', 'display\.request_failed', 'display\.runtime stage=http_error') }
    )
}

$script:DebugCueKeys = @('cueDecision','decisionReason','ragNeeded','searchNeeded','ragDocuments','retrievalMs','retrieval\.stage\.web','evidenceStatus','hintGenerationMs','hintGenerated','selectedProvider','selectedModel','apiAttempts','status','totalLatencyMs','fallbackReason','hintPath')

function Get-DebugMarkerField {
    param([object]$Def, [string]$Name)
    if ($Def -is [hashtable] -or $Def -is [System.Collections.IDictionary]) {
        if ($Def.ContainsKey($Name)) { return $Def[$Name] }
        return $null
    }
    $prop = $Def.PSObject.Properties[$Name]
    if ($prop) { return $prop.Value }
    return $null
}

function Test-DebugStageLine {
    param([string]$Line, [object]$Def)
    $hit = $false
    foreach ($pat in @(Get-DebugMarkerField -Def $Def -Name 'patterns')) {
        if ($Line -match $pat) { $hit = $true; break }
    }
    if (-not $hit) { return $false }
    foreach ($ex in @(Get-DebugMarkerField -Def $Def -Name 'exclude')) {
        if ($ex -and $Line -match $ex) { return $false }
    }
    $require = [string](Get-DebugMarkerField -Def $Def -Name 'require')
    if ($require -and $Line -notmatch $require) { return $false }
    return $true
}

function Get-DebugLogWindow {
    param([string]$LogPath)
    $cutoff = (Get-Date).AddMinutes(-$script:DbgLogMinutes)
    $buf = New-Object 'System.Collections.Generic.List[string]'
    $fs = $null; $reader = $null
    try {
        $fs = [IO.FileStream]::new($LogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $start = [Math]::Max([int64]0, $fs.Length - [int64]$script:DebugLogWindowMaxBytes)
        if ($start -gt 0) { [void]$fs.Seek($start, [IO.SeekOrigin]::Begin) }
        $reader = [IO.StreamReader]::new($fs, [Text.Encoding]::UTF8, $true, 4096, $true)
        if ($start -gt 0) { [void]$reader.ReadLine() }
        while (($line = $reader.ReadLine()) -ne $null) {
            $buf.Add($line)
            if ($buf.Count -gt $script:DebugLogWindowMaxLines) { $buf.RemoveAt(0) }
        }
    } catch {
        return @()
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $fs) { $fs.Dispose() }
    }
    $recent = New-Object 'System.Collections.Generic.List[string]'
    foreach ($line in $buf) {
        $m = [regex]::Match($line, '^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})')
        if ($m.Success) {
            try {
                if ([datetime]::Parse($m.Groups[1].Value) -lt $cutoff) { continue }
            } catch { }
        }
        $recent.Add($line)
    }
    return @($recent)
}

function Get-DebugStageSummary {
    param([string]$LogPath, [string]$ForRole)
    $defs = @($script:DebugStageMarkers[$ForRole])
    if (-not $LogPath -or -not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return @($defs | ForEach-Object { [pscustomobject][ordered]@{ stage = $_.stage; count = 0; lastAt = ''; extracted = [ordered]@{}; counts = [ordered]@{}; note = 'log-unavailable' } })
    }
    $window = @(Get-DebugLogWindow -LogPath $LogPath)
    $stages = @()
    foreach ($def in $defs) {
        $hits = @($window | Where-Object { Test-DebugStageLine -Line $_ -Def $def })
        $row = [ordered]@{
            stage = $def.stage; count = $hits.Count; lastAt = ''
            extracted = [ordered]@{}; counts = [ordered]@{}
        }
        if ($def.stage -eq 'cue-gate') {
            $row.counts = [ordered]@{ noCue = 0; cue = 0; ragCue = 0; other = 0 }
            foreach ($hit in $hits) {
                $dm = [regex]::Match($hit, '(?:^|[,{\s])cueDecision=([^,}]+)')
                $dec = if ($dm.Success) { $dm.Groups[1].Value.Trim() } else { '' }
                if ($dec -eq 'NO_CUE') { $row.counts.noCue++ }
                elseif ($dec -eq 'RAG_CUE') { $row.counts.ragCue++ }
                elseif ($dec -eq 'CUE') { $row.counts.cue++ }
                else { $row.counts.other++ }
            }
        }
        if ($hits.Count -gt 0) {
            $last = [string]$hits[-1]
            $tm = [regex]::Match($last, '^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{4})')
            if ($tm.Success) { $row.lastAt = $tm.Groups[1].Value }
            if ($last -match 'conversate\.cue') {
                foreach ($key in $script:DebugCueKeys) {
                    $km = [regex]::Match($last, '(?:^|[,{\s])' + $key + '=([^,}]+)')
                    if ($km.Success) { $row.extracted[$key -replace '\\',''] = $km.Groups[1].Value }
                }
            }
            if ($last -match '\[llm-failover\]') {
                foreach ($kv in @('route','fallbackCount','outcome','latencyMs','cause')) {
                    $km = [regex]::Match($last, $kv + '=([^ ]+)')
                    if ($km.Success) { $row.extracted[$kv] = $km.Groups[1].Value }
                }
            }
        }
        $stages += [pscustomobject]$row
    }
    return $stages
}

function Invoke-DebugRetention {
    try {
        $files = @(Get-ChildItem -LiteralPath $script:DebugDir -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match $script:DebugRetentionPattern } |
            Sort-Object LastWriteTime -Descending)
        $keep = 40; $capBytes = 20MB; $total = 0L; $i = 0
        foreach ($f in $files) {
            $i++
            $total += $f.Length
            if ($i -gt $keep -or $total -gt $capBytes) {
                Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue
            }
        }
    } catch { }
}

function Resolve-DebugLoggers {
    $preset = if ($script:DbgRole -eq 'wear') {
        'com.example.lms.assist=DEBUG;com.example.lms.search=DEBUG'
    } else {
        'com.example.lms.api=DEBUG;com.example.lms.search=DEBUG;com.example.lms.service.rag=DEBUG;com.example.lms.llm=DEBUG'
    }
    $spec = if (-not [string]::IsNullOrWhiteSpace($script:DbgLoggers)) { $script:DbgLoggers } else { $preset }
    $valid = @()
    foreach ($entry in @($spec -split '[;,]' | Select-Object -First 8)) {
        if ([string]::IsNullOrWhiteSpace($entry)) { continue }
        if ($entry.Trim() -match '^([A-Za-z0-9][A-Za-z0-9._-]{0,119})=(TRACE|DEBUG|INFO|WARN|ERROR)$') {
            $valid += ($Matches[1] + '=' + $Matches[2])
        }
    }
    return $valid
}

function Invoke-DebugChildScript {
    # Runs a stack script in a child powershell. Output goes to files (not a
    # live pipeline): a detached runtime inheriting the child's stdout handle
    # would otherwise keep a streaming pipe open forever after the launcher
    # exits. -Wait only waits on the direct child; captured output is replayed
    # into console + debug log afterwards.
    param([string]$ScriptPath, [string[]]$ArgList, [hashtable]$EnvSet = @{})
    $base = Join-Path $script:DebugDir ("{0}-{1}-child-{2}" -f $script:DbgRole, $script:RunStamp, [IO.Path]::GetFileNameWithoutExtension($ScriptPath))
    $outFile = "$base.out.log"; $errFile = "$base.err.log"
    $saved = @{}
    foreach ($k in $EnvSet.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k)
        [Environment]::SetEnvironmentVariable($k, [string]$EnvSet[$k])
    }
    try {
        $argsLine = '-NoLogo -NoProfile -ExecutionPolicy Bypass -File "{0}" {1}' -f $ScriptPath, ($ArgList -join ' ')
        $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $argsLine -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        $code = [int]$p.ExitCode
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
    }
    foreach ($f in @($outFile, $errFile)) {
        if (Test-Path -LiteralPath $f -PathType Leaf) {
            foreach ($line in @(Get-Content -LiteralPath $f -ErrorAction SilentlyContinue)) {
                Write-Host $line
                Add-Content -LiteralPath $script:DebugLog -Value ([string]$line) -Encoding UTF8
            }
        }
    }
    return $code
}

function Get-DebugStartArgs {
    $argList = @('-MetaDisplay', '-TimeoutSeconds', [string]$script:DbgTimeout)
    if ($script:DbgRole -eq 'wear') {
        $argList += '-Wear'
    } else {
        $argList += '-ForceRestart', '-DevWatch'
    }
    if ($script:DbgOpenBrowser) { $argList += '-OpenBrowser' }
    return $argList
}

function Find-DebugRunDir {
    param([datetime]$AfterUtc)
    $base = Join-Path $script:RagRoot 'var\rag-launcher'
    if (-not (Test-Path -LiteralPath $base -PathType Container)) { return $null }
    return @(Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-stop-' -and $_.CreationTime.ToUniversalTime() -ge $AfterUtc.AddSeconds(-10) } |
        Sort-Object CreationTime -Descending | Select-Object -First 1)[0]
}

function Invoke-DebugStart {
    $loggers = @(Resolve-DebugLoggers)
    if ($loggers.Count -eq 0) {
        Write-DebugStage 'START' 'FAILED' 'no valid logger spec (use name=LEVEL entries, e.g. -Loggers "com.example.lms.assist=DEBUG")'
        $script:Report.status = 'debug-loggers-invalid'
        return 1
    }
    $stack = Join-Path $PSScriptRoot 'start_rag_stack.ps1'
    $argList = @(Get-DebugStartArgs)
    $planned = 'start_rag_stack.ps1 ' + ($argList -join ' ')
    if ($script:DbgDryRun) {
        Write-DebugStage 'START' 'DRYRUN' "env AWX_RAG_DEBUG_LOGGERS=$($loggers -join ';')"
        Write-DebugStage 'START' 'DRYRUN' "would run: $planned"
        Write-DebugStage 'START' 'DRYRUN' 'no process started or stopped'
        $script:Report.status = 'dry-run'
        $script:Report.ok = $true
        return 0
    }
    Write-DebugStage 'START' 'ENV' "AWX_RAG_DEBUG_LOGGERS=$($loggers -join ';') (process-local, this launch only)"
    Write-DebugStage 'START' 'RUN' $planned
    $callStart = (Get-Date).ToUniversalTime()
    $childExit = Invoke-DebugChildScript -ScriptPath $stack -ArgList $argList -EnvSet @{ AWX_RAG_DEBUG_LOGGERS = ($loggers -join ';') }
    $runDir = Find-DebugRunDir -AfterUtc $callStart
    $result = $null
    if ($null -ne $runDir) {
        $resultPath = Join-Path $runDir.FullName 'result.json'
        if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
            try { $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        }
    }
    $reason = if ($null -ne $result) { [string](Get-AwxObjectProperty $result 'reason') } else { '' }
    $status = if ($null -ne $result) { [string](Get-AwxObjectProperty $result 'status') } else { '' }
    $springPid = if ($null -ne $result) { [int](Get-AwxObjectProperty $result 'springPid') } else { 0 }
    $reused = ($null -ne $result -and [bool](Get-AwxObjectProperty $result 'springReused'))
    if ($null -ne $runDir) { Write-DebugStage 'START' 'RUN-DIR' $runDir.FullName }
    if ($childExit -ne 0) {
        $stage = if ($null -ne $result) { [string](Get-AwxObjectProperty $result 'stage') } else { 'unknown' }
        Write-DebugStage 'START' 'FAILED' "exit=$childExit stage=$stage reason=$reason runDir=$(if ($runDir) { $runDir.FullName } else { 'not-found' })"
        $script:Report.status = 'start-failed'
        if ($reason -match 'protected|conflict|multiple-|port-') { $script:Report.status = 'blocked-' + $reason; return 2 }
        return 1
    }
    # Readiness verified by the launcher itself; now prove whether verbose flags
    # actually reached a JVM. A reused JVM keeps its original args -> not applied.
    $applied = @(); $debugObserved = 0
    if ($springPid -gt 0) {
        $flags = Get-DebugProcessFlags -ProcessId $springPid
        $applied = @($flags.loggingLevels)
    }
    $runtimes = @(Get-DebugRuntimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    if ($runtimes.Count -gt 0 -and $applied.Count -eq 0) { $applied = @($runtimes[0].loggingLevels) }
    $logInfo = if ($runtimes.Count -gt 0) { Get-DebugLogPath -RuntimeRow $runtimes[0] } else { $null }
    if ($null -ne $logInfo -and (Test-Path -LiteralPath $logInfo.path -PathType Leaf)) {
        $debugObserved = @(Select-String -LiteralPath $logInfo.path -Pattern ' DEBUG ' -ErrorAction SilentlyContinue | Select-Object -First 1).Count
        Write-DebugStage 'START' 'LOG' "out-log=$($logInfo.path) source=$($logInfo.source)"
    }
    $script:Report.runtimes = @($runtimes)
    if ($reused) {
        Write-DebugStage 'START' 'REUSED' "existing $($script:DbgRole) runtime kept; verboseLogging=not-applied (restart required to change JVM args)"
        $script:Report.status = 'ready-verbose-not-applied'
        $script:Report.ok = $true
        return 5
    }
    if ($applied.Count -gt 0) {
        Write-DebugStage 'START' 'READY' "verboseLogging=applied loggers=$($applied -join ',') debugLinesObserved=$debugObserved"
        $script:Report.status = 'ready-verbose-applied'
        $script:Report.ok = $true
        return 0
    }
    Write-DebugStage 'START' 'WARN' 'runtime ready but no --logging.level.* args observed on the JVM cmdline; verboseLogging=absent'
    $script:Report.status = 'ready-verbose-absent'
    $script:Report.ok = $true
    return 5
}

function Invoke-DebugRestart {
    $stopScript = Join-Path $PSScriptRoot 'stop_rag_stack.ps1'
    $stopArgs = @('-MetaDisplay')
    if ($script:DbgRole -eq 'wear') { $stopArgs += '-Wear' }
    $planned = 'stop_rag_stack.ps1 ' + ($stopArgs -join ' ')
    if ($script:DbgDryRun) {
        Write-DebugStage 'RESTART' 'DRYRUN' "would run: $planned"
        Write-DebugStage 'RESTART' 'DRYRUN' 'then a debug start (see -Action start -DryRun)'
        $script:Report.status = 'dry-run'; $script:Report.ok = $true
        return 0
    }
    Write-DebugStage 'RESTART' 'RUN' $planned
    $stopExit = Invoke-DebugChildScript -ScriptPath $stopScript -ArgList $stopArgs
    if ($stopExit -ne 0) {
        Write-DebugStage 'RESTART' 'FAILED' "stop phase exit=$stopExit; start phase aborted"
        $script:Report.status = 'restart-stop-failed'
        return 1
    }
    Write-DebugStage 'RESTART' 'STOPPED' 'stop phase ok; starting with verbose loggers'
    return (Invoke-DebugStart)
}

function Invoke-DebugStatus {
    $targetLabel = if ($script:DbgRole -eq 'wear') { 'wear (Meta Display glasses runtime)' } else { 'dev (RAG dev runtime)' }
    Write-DebugStage 'TARGET' 'INFO' "role=$($script:DbgRole) label=$targetLabel fixedPorts=18180/18181/18182"
    $runtimes = @(Get-DebugRuntimes)
    $target = @($runtimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    $others = @($runtimes | Where-Object { -not ($_.metaDisplay -and $_.role -eq $script:DbgRole) })
    $portMap = @(Get-DebugPortMap)

    if ($target.Count -eq 0) {
        Write-DebugStage 'RUNTIME' 'DOWN' "no $script:DbgRole meta-display runtime running"
        $script:Report.status = 'not-running'
    } else {
        foreach ($rt in $target) {
            $script:Report.runtimes += $rt
            Write-DebugStage 'RUNTIME' 'UP' "pid=$($rt.processId) role=$($rt.role) port=$($rt.port) started=$($rt.creationDate)"
            Write-DebugStage 'RUNTIME' 'VERBOSE' ("verboseLogging=" + $(if ($rt.verboseApplied) { 'applied (' + ($rt.loggingLevels -join ',') + ')' } else { 'absent' }) + " cmdlineObserved=$($rt.cmdlineObserved)")
            if ($rt.manifest.present) {
                Write-DebugStage 'OWNERSHIP' $(if ($rt.ownership.ok) { 'OK' } else { 'UNPROVEN' }) "runIdHash=$($rt.manifest.runIdHash) role=$($rt.manifest.runtimeRole) ports=$($rt.manifest.ports -join ',') reason=$($rt.ownership.reason) startedAt=$($rt.manifest.startedAt) readyAt=$($rt.manifest.readyAt)"
            } else {
                Write-DebugStage 'OWNERSHIP' 'UNPROVEN' 'no spring-owned.json manifest for this pid; treat ownership as unverified'
            }
        }
        $rt0 = $target[0]
        $readyWeb = 'skipped'; $mgmt = 'skipped'
        if ($rt0.ownership.ok -and $rt0.port -gt 0) {
            $readyWeb = [string](Test-RagWeb -Port $rt0.port -MetaDisplay)
            $health = Get-RagHttp "http://127.0.0.1:18181/actuator/health"
            $mgmt = "http=$($health.status) $($health.content)"
        }
        Write-DebugStage 'READINESS' $(if ($readyWeb -eq 'True') { 'OK' } else { 'CHECK' }) "webReady=$readyWeb mgmtHealth=$mgmt"
        $script:Report.readiness = [ordered]@{ webReady = $readyWeb; mgmtHealth = $mgmt }
        $fresh = Get-DebugFreshness -RuntimeCreationUtc $rt0.creationDate -Port $rt0.port
        $freshLabel = if ($fresh.sourcesNewer -eq 'true-stale-candidate') { 'STALE-CANDIDATE' } elseif ($fresh.sourcesNewer -eq 'unrelated-source-mtime') { 'INFO' } else { 'INFO' }
        $served = ''
        if ($fresh.servedAsset -and $fresh.servedAsset.match -ne 'not_observed') {
            $served = " servedAsset=$($fresh.servedAsset.match)"
        }
        Write-DebugStage 'FRESHNESS' $freshLabel "sourcesNewerThanRuntime=$($fresh.sourcesNewer) newest=$($fresh.newestSource) @ $($fresh.newestSourceWriteUtc)$served"
        $script:Report.freshness = $fresh
        $logInfo = Get-DebugLogPath -RuntimeRow $rt0
        $stages = @()
        if ($null -ne $logInfo) {
            $item = Get-Item -LiteralPath $logInfo.path
            Write-DebugStage 'LOGS' 'INFO' "out=$($logInfo.path) source=$($logInfo.source) size=$($item.Length) lastWrite=$($item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
            $script:Report.log = [ordered]@{ path = $logInfo.path; source = $logInfo.source; size = [int64]$item.Length }
            $stages = @(Get-DebugStageSummary -LogPath $logInfo.path -ForRole $script:DbgRole)
            foreach ($s in $stages) {
                $extra = ''
                if ($s.counts -and @($s.counts.Keys).Count -gt 0) {
                    $extra += ' ' + ((@($s.counts.Keys) | ForEach-Object { "$($_)=$($s.counts[$_])" }) -join ' ')
                }
                if ($s.extracted -and @($s.extracted.Keys).Count -gt 0) {
                    $extra += ' fields=' + ((@($s.extracted.Keys) | ForEach-Object { "$($_)=$($s.extracted[$_])" }) -join ',')
                }
                Write-DebugStage 'PIPELINE' 'INFO' ("stage={0} count(last {1}m)={2} lastAt={3}{4}" -f $s.stage, $script:DbgLogMinutes, $s.count, $s.lastAt, $extra)
            }
        } else {
            Write-DebugStage 'LOGS' 'WARN' 'no runtime out-log resolved; pipeline stage scan unavailable'
            $script:Report.log = [ordered]@{ path = ''; source = 'unavailable' }
        }
        $script:Report.pipeline = @($stages)
    }
    foreach ($p in $portMap) {
        if ([int]$p.port -eq 18182 -and [int]$p.ownerPid -eq 0 -and -not $p.ownerNote) {
            $p.ownerNote = 'netty=not_listening'
        }
        Write-DebugStage 'PORTS' 'INFO' ("port={0} ownerPid={1} owner={2} {3}" -f $p.port, $p.ownerPid, $p.ownerName, $p.ownerNote)
    }
    $script:Report.ports = @($portMap)
    foreach ($o in $others) {
        Write-DebugStage 'OTHER-RUNTIME' 'KEPT' "pid=$($o.processId) role=$($o.role) meta=$($o.metaDisplay) port=$($o.port) (not this BAT's target; left untouched)"
    }
    $w = Get-DebugWatcherRows
    foreach ($wr in @($w.watchers)) {
        Write-DebugStage 'DEVWATCH' 'INFO' "watcher pid=$($wr.processId) meta=$($wr.metaDisplay)"
    }
    if ($null -ne $w.state) {
        Write-DebugStage 'DEVWATCH' 'STATE' "status=$($w.state.status) tier=$($w.state.lastTier) at=$($w.state.updatedAt)"
        $script:Report.devWatch = [ordered]@{ status = [string]$w.state.status; tier = [string]$w.state.lastTier; updatedAt = [string]$w.state.updatedAt }
    } else {
        $script:Report.devWatch = [ordered]@{ status = 'not_observed' }
    }
    foreach ($l in @($w.logTail)) { Write-DebugStage 'DEVWATCH' 'LOG' $l }
    foreach ($oPort in @(11434, 11435)) {
        $ok = Test-RagOllama -Port $oPort
        Write-DebugStage 'SHARED' $(if ($ok) { 'OK' } else { 'INFO' }) "ollama port=$oPort healthy=$ok (shared; never stopped by these tools)"
    }
    if ($target.Count -gt 0 -and $script:Report.status -eq 'started') {
        $rt0 = $target[0]
        if (-not $rt0.ownership.ok -or -not $rt0.manifest.present) { $script:Report.status = 'degraded-ownership' }
        elseif ($readyWeb -ne 'True') { $script:Report.status = 'degraded-not-ready' }
        else { $script:Report.status = 'ready'; $script:Report.ok = $true }
    }
    if ($script:Report.status -eq 'not-running') {
        Write-DebugStage 'RESULT' 'DOWN' "status=not-running; start verbose with -Action start or normally with the matching Start BAT"
        return 3
    }
    if ($script:Report.status -like 'degraded*') {
        Write-DebugStage 'RESULT' 'DEGRADED' "status=$($script:Report.status); inspect ownership/log sections above before any restart"
        return 4
    }
    Write-DebugStage 'RESULT' 'OK' 'status=ready'
    return 0
}

# ---------------------------------------------------------------------------
# verify: one-call post-edit judgement. Read-only except the optional
# -WithCompile Gradle run; it never starts, stops, or duplicates a server.
# Check-row schema (awx.debug.verify.v2):
#   name     stable check id
#   run      executed | skipped | blocked | not_observed  (did the check run?)
#   verdict  pass | warn | fail                            (outcome if it ran)
#   ok       real Boolean (verdict != 'fail'); never the string 'fail',
#            which PowerShell would evaluate as $true in `if ($row.ok)`
#   status   machine detail string (e.g. not-running, auth-blocked,
#            stale-candidate, not-requested, log-unavailable)
#   detail/evidence  human summary + bounded proof fields
# ---------------------------------------------------------------------------

function New-DebugCheckRow {
    param([string]$Name)
    return [ordered]@{ name = $Name; run = 'executed'; verdict = 'pass'; ok = $true; status = ''; detail = ''; evidence = @{} }
}

function Set-DebugCheckVerdict {
    param($Row, [string]$Verdict)
    $Row.verdict = $Verdict
    $Row.ok = ($Verdict -ne 'fail')
}

$script:DebugVerifyExceptionMarkers = @(
    [ordered]@{ class = 'spring-fatal'; fatal = $true; patterns = @('APPLICATION FAILED TO START', 'Application run failed', 'BeanCreationException', 'UnsatisfiedDependencyException', 'NoSuchBeanDefinitionException') }
    [ordered]@{ class = 'config'; fatal = $true; patterns = @('Could not resolve placeholder', 'Failed to bind properties', 'ConfigurationPropertiesBindException', 'ConfigDataEnvironment', 'Binding to target \S+ failed') }
    [ordered]@{ class = 'port-bind'; fatal = $true; patterns = @('Port \d+ was already in use', 'BindException', 'Address already in use') }
    [ordered]@{ class = 'exception-lines'; fatal = $false; patterns = @('(?:Caused by: )?[a-zA-Z_][\w.$]*(?:Exception|Error)(?::| at |$)') }
    [ordered]@{ class = 'error-level'; fatal = $false; patterns = @('\sERROR\s') }
)

$script:DebugVerifyHints = [ordered]@{
    compile    = 'fix compile errors first; see the verify-compile log and analysis/build_error_report.json'
    runtime    = 'start the runtime (matching Start BAT or Debug -Action start); nothing else is provable while down'
    ports      = 'a fixed port is free or owned by a different pid; use the matching Close BAT or identify the owner before relaunch'
    http       = 'runtime is up but endpoint probes fail; read the exceptions/config rows and the runtime out-log below'
    exceptions = 'fatal classes found in the out-log; open the first/last hit lines reported above'
    config     = 'config evidence missing: application yml / active-profile yml / api-routing absent, or placeholder/bind failures in the log'
    freshness  = 'sources are newer than the running JVM; run the matching Close+Start pair or wait for [DEV-RELOAD] socket ready'
}

function Get-DebugExceptionScan {
    # Generic exception/error classes over the same bounded window the pipeline
    # summary uses. firstFromEnd/lastFromEnd locate hits inside the current
    # window so an agent can jump straight to the cause in the named log file.
    param([string]$LogPath)
    $defs = @($script:DebugVerifyExceptionMarkers)
    if ([string]::IsNullOrWhiteSpace($LogPath) -or -not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return [pscustomobject][ordered]@{
            windowLines = 0; log = $LogPath
            classes = @($defs | ForEach-Object { [pscustomobject][ordered]@{ class = $_.class; fatal = [bool]$_.fatal; count = 0; firstHit = ''; firstFromEnd = 0; lastHit = ''; lastFromEnd = 0; note = 'log-unavailable' } })
        }
    }
    $window = @(Get-DebugLogWindow -LogPath $LogPath)
    $total = $window.Count
    $rows = @()
    foreach ($def in $defs) {
        $row = [ordered]@{ class = [string]$def.class; fatal = [bool]$def.fatal; count = 0; firstHit = ''; firstFromEnd = 0; lastHit = ''; lastFromEnd = 0 }
        for ($i = 0; $i -lt $total; $i++) {
            $line = [string]$window[$i]
            $hit = $false
            foreach ($pat in @($def.patterns)) { if ($line -match $pat) { $hit = $true; break } }
            if (-not $hit) { continue }
            $row.count++
            $short = if ($line.Length -gt 200) { $line.Substring(0, 200) } else { $line }
            if ($row.count -eq 1) { $row.firstHit = $short; $row.firstFromEnd = ($total - $i) }
            $row.lastHit = $short; $row.lastFromEnd = ($total - $i)
        }
        $rows += [pscustomobject]$row
    }
    return [pscustomobject][ordered]@{ windowLines = $total; log = $LogPath; classes = $rows }
}

function Get-DebugVerifyHttpPlan {
    # Loopback probes only; the POST rows reuse the same validation probes the
    # launcher uses (rejected before retrieval/generation -> zero API spend).
    param([int]$Port, [int]$MgmtPort = 18181)
    $base = "http://127.0.0.1:$Port"
    $rows = @(
        [ordered]@{ name = 'home'; method = 'GET'; url = "$base/"; body = ''; headers = @{}; statuses = @(200, 301, 302, 303, 307, 308); content = '' }
        [ordered]@{ name = 'chat-ui'; method = 'GET'; url = "$base/chat-ui"; body = ''; headers = @{}; statuses = @(200); content = '/js/chat\.js|/assets/interview/app\.js' }
        [ordered]@{ name = 'interview-asset'; method = 'GET'; url = "$base/assets/interview/index.html"; body = ''; headers = @{}; statuses = @(200); content = '' }
        [ordered]@{ name = 'mgmt-health'; method = 'GET'; url = ("http://127.0.0.1:{0}/actuator/health" -f $MgmtPort); body = ''; headers = @{}; statuses = @(200); content = '"status"\s*:\s*"UP"' }
    )
    if ($script:DbgRole -eq 'wear') {
        $rows += [ordered]@{ name = 'display-page'; method = 'GET'; url = "$base/assets/display/index.html"; body = ''; headers = @{}; statuses = @(200); content = 'display-core\.js' }
        $rows += [ordered]@{ name = 'receiver-js'; method = 'GET'; url = "$base/assets/display/meta/receiver.js"; body = ''; headers = @{}; statuses = @(200); content = '' }
        $rows += [ordered]@{ name = 'display-bootstrap-validation'; method = 'POST'; url = "$base/api/assist/display/bootstrap"; body = '{"clientId":""}'; headers = @{ Origin = $base; 'X-Display-Client' = '1' }; statuses = @(400); content = '' }
    } else {
        $rows += [ordered]@{ name = 'chat-sync-validation'; method = 'POST'; url = "$base/api/chat/sync"; body = '{"message":""}'; headers = @{}; statuses = @(400); content = '' }
    }
    return @($rows | ForEach-Object { [pscustomobject]$_ })
}

function Invoke-DebugVerifyHttp {
    param([int]$Port, [int]$MgmtPort = 18181)
    $results = @()
    foreach ($probe in @(Get-DebugVerifyHttpPlan -Port $Port -MgmtPort $MgmtPort)) {
        $resp = Get-RagHttp -Url $probe.url -Method ([string]$probe.method) -Body ([string]$probe.body) -Headers $probe.headers
        $statusOk = (@($probe.statuses) -contains [int]$resp.status)
        $contentResult = 'not-required'
        if ($statusOk -and -not [string]::IsNullOrWhiteSpace([string]$probe.content)) {
            $contentResult = if ([string]$resp.content -match [string]$probe.content) { 'pass' } else { 'fail' }
        }
        # 401/403 proves the listener is up but auth-blocked — that is access
        # denial, not evidence of a DOWN application.
        $auth = if ([int]$resp.status -in @(401, 403)) { 'blocked' } else { 'ok' }
        $results += [pscustomobject][ordered]@{
            name = [string]$probe.name; method = [string]$probe.method; url = [string]$probe.url
            expected = (@($probe.statuses) -join '|'); status = [int]$resp.status
            contentMatch = $contentResult; auth = $auth
            ok = ($statusOk -and $contentResult -ne 'fail' -and $auth -eq 'ok')
        }
    }
    return $results
}

function Get-DebugVerifyConfig {
    # Static presence is a warn row; hard config failure evidence comes from the
    # 'config' exception class in the runtime out-log (see exceptions check).
    param([object]$RuntimeRow)
    $rows = @()
    $appYml = 'main\resources\application.yml'
    $rows += [pscustomobject][ordered]@{ file = 'main/resources/application.yml'; present = (Test-Path -LiteralPath (Join-Path $script:RagRoot $appYml) -PathType Leaf); profile = '' }
    $profile = ''
    if ($null -ne $RuntimeRow -and [int]$RuntimeRow.processId -gt 0) {
        $flags = Get-DebugProcessFlags -ProcessId ([int]$RuntimeRow.processId)
        $profile = [string]$flags.profile
    }
    foreach ($p in @($profile -split ',' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)) {
        $name = $p.Trim()
        $rel = "main\resources\application-$name.yml"
        $rows += [pscustomobject][ordered]@{ file = ($rel -replace '\\', '/'); present = (Test-Path -LiteralPath (Join-Path $script:RagRoot $rel) -PathType Leaf); profile = $name }
    }
    $rows += [pscustomobject][ordered]@{ file = 'configs/api-routing.yaml'; present = (Test-Path -LiteralPath (Join-Path $script:RagRoot 'configs\api-routing.yaml') -PathType Leaf); profile = '' }
    return $rows
}

function Invoke-DebugVerifyCompile {
    $gradle = Join-Path $script:RagRoot 'gradlew.bat'
    $row = New-DebugCheckRow 'compile'
    $row.status = 'skipped'
    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        Set-DebugCheckVerdict $row 'fail'; $row.status = 'gradlew-missing'
        return [pscustomobject]$row
    }
    $base = Join-Path $script:DebugDir ("{0}-{1}-verify-compile" -f $script:DbgRole, $script:RunStamp)
    $outLog = "$base.out.log"; $errLog = "$base.err.log"
    Write-DebugStage 'VERIFY' 'RUN' 'compile: cmd /c gradlew.bat compileJava processResources -x test --console=plain'
    $proc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d', '/c', 'gradlew.bat compileJava processResources -x test --console=plain' `
        -WorkingDirectory $script:RagRoot -NoNewWindow -PassThru `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $finished = $proc.WaitForExit(300000)
    if (-not $finished) {
        try { & taskkill /PID $proc.Id /T /F | Out-Null } catch { }
        Set-DebugCheckVerdict $row 'fail'; $row.status = 'compile-timeout'; $row.detail = 'exceeded 300s'
        $row.evidence = [ordered]@{ log = $outLog; err = $errLog }
        return [pscustomobject]$row
    }
    $code = [int]$proc.ExitCode
    $row.evidence = [ordered]@{ log = $outLog; err = $errLog; exit = $code }
    if ($code -eq 0) {
        $row.status = 'passed'; $row.detail = "exit=0 log=$outLog"
        return [pscustomobject]$row
    }
    Set-DebugCheckVerdict $row 'fail'; $row.status = 'failed'
    $text = ''
    foreach ($f in @($outLog, $errLog)) {
        if (Test-Path -LiteralPath $f -PathType Leaf) { $text += "`n" + (Get-Content -LiteralPath $f -Raw -Encoding UTF8 -ErrorAction SilentlyContinue) }
    }
    $firstError = ''
    foreach ($pat in @('Execution failed for task[^\r\n]*', 'error: cannot find symbol', 'package [\w.]+ does not exist', 'error: incompatible types', 'error: illegal start', 'duplicate class', 'Could not resolve', 'symbol:\s+\S+', '\d+\s+errors?')) {
        $m = [regex]::Match($text, "(?m)^.*$pat.*$")
        if ($m.Success) { $firstError = $m.Value.Trim(); break }
    }
    if ($firstError.Length -gt 200) { $firstError = $firstError.Substring(0, 200) }
    $row.detail = "exit=$code firstError=$firstError"
    # Reuse the existing Gradle-log analyzer for the classified report.
    $analyzer = Join-Path $script:RagRoot 'scripts\analyze_build_output.py'
    $py = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($null -ne $py -and (Test-Path -LiteralPath $analyzer -PathType Leaf) -and (Test-Path -LiteralPath $outLog -PathType Leaf)) {
        & $py.Source -B $analyzer --log $outLog 2>&1 | Out-Null
        $report = Join-Path $script:RagRoot 'analysis\build_error_report.json'
        if (Test-Path -LiteralPath $report -PathType Leaf) { $row.evidence.analysis = 'analysis/build_error_report.json' }
    }
    return [pscustomobject]$row
}

function Invoke-DebugVerify {
    Write-DebugStage 'VERIFY' 'START' "role=$($script:DbgRole) withCompile=$($script:DbgWithCompile) logMinutes=$($script:DbgLogMinutes)"
    $checks = @()

    if ($script:DbgWithCompile) {
        $compileRow = Invoke-DebugVerifyCompile
    } else {
        # The check is still reported — a skipped build is a coverage gap, not
        # a silent pass.
        $compileRow = New-DebugCheckRow 'compile'
        $compileRow.run = 'skipped'
        Set-DebugCheckVerdict $compileRow 'warn'
        $compileRow.status = 'not-requested'
        $compileRow.detail = 'build not run; pass -WithCompile to invoke gradlew compileJava processResources'
    }
    $checks += [pscustomobject]$compileRow
    Write-DebugStage 'VERIFY' $(if ($compileRow.verdict -eq 'pass') { 'OK' } elseif ($compileRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) ("check=compile run={0} status={1} {2}" -f $compileRow.run, $compileRow.status, $compileRow.detail)

    $runtimes = @(Get-DebugRuntimes)
    $target = @($runtimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    $script:Report.runtimes = @($target)
    $rt0 = if ($target.Count -gt 0) { $target[0] } else { $null }
    $runtimeDown = ($null -eq $rt0)

    $rtRow = New-DebugCheckRow 'runtime'
    $rtRow.status = 'up'
    if ($runtimeDown) {
        Set-DebugCheckVerdict $rtRow 'fail'; $rtRow.status = 'not-running'; $rtRow.detail = "no $($script:DbgRole) meta-display runtime"
    } else {
        $rtRow.evidence = [ordered]@{ pid = $rt0.processId; port = $rt0.port; started = $rt0.creationDate; ownership = $rt0.ownership.reason; manifest = $rt0.manifest.present }
        $rtRow.detail = "pid=$($rt0.processId) port=$($rt0.port) ownership=$($rt0.ownership.reason)"
        if (-not $rt0.ownership.ok) { Set-DebugCheckVerdict $rtRow 'warn'; $rtRow.status = 'ownership-unproven' }
    }
    $checks += [pscustomobject]$rtRow
    Write-DebugStage 'VERIFY' $(if ($rtRow.verdict -eq 'pass') { 'OK' } elseif ($rtRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) "check=runtime status=$($rtRow.status) $($rtRow.detail)"

    $portMap = @(Get-DebugPortMap)
    $portRow = New-DebugCheckRow 'ports'
    $portRow.status = 'clear'; $portRow.evidence = [ordered]@{ map = $portMap }
    $portNotes = @()
    foreach ($p in $portMap) {
        if ([int]$p.port -eq 18180) {
            if ($runtimeDown) { $portNotes += "18180 ownerPid=$($p.ownerPid) (runtime down)"; continue }
            if ([int]$p.ownerPid -eq 0) { Set-DebugCheckVerdict $portRow 'fail'; $portNotes += '18180 free while runtime claims it' }
            elseif ([int]$p.ownerPid -ne [int]$rt0.processId) { Set-DebugCheckVerdict $portRow 'fail'; $portNotes += "18180 owned by foreign pid=$($p.ownerPid) $($p.ownerName)" }
        } elseif ([int]$p.ownerPid -gt 0 -and -not $runtimeDown -and [int]$p.ownerPid -ne [int]$rt0.processId) {
            if ($portRow.verdict -ne 'fail') { Set-DebugCheckVerdict $portRow 'warn' }
            $portNotes += "$($p.port) owned by pid=$($p.ownerPid) $($p.ownerName)"
        }
    }
    if ($portRow.verdict -eq 'fail') { $portRow.status = 'conflict' }
    elseif ($portRow.verdict -eq 'warn') { $portRow.status = 'warn' }
    $portRow.detail = ($portNotes -join '; ')
    $checks += [pscustomobject]$portRow
    Write-DebugStage 'VERIFY' $(if ($portRow.verdict -eq 'pass') { 'OK' } elseif ($portRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) "check=ports status=$($portRow.status) $($portRow.detail)"
    $script:Report.ports = $portMap

    $httpRow = New-DebugCheckRow 'http'
    $httpRow.status = 'passed'; $httpRow.evidence = [ordered]@{ probes = @() }
    if ($runtimeDown -or [int]$rt0.port -le 0) {
        $httpRow.run = 'blocked'; $httpRow.status = 'blocked-no-port'; $httpRow.detail = 'no reachable server port'
        Set-DebugCheckVerdict $httpRow 'warn'
    } else {
        $probes = @(Invoke-DebugVerifyHttp -Port ([int]$rt0.port) -MgmtPort 18181)
        $httpRow.evidence.probes = $probes
        $bad = @($probes | Where-Object { -not $_.ok -and $_.auth -ne 'blocked' })
        $authBlocked = @($probes | Where-Object { $_.auth -eq 'blocked' })
        if ($bad.Count -gt 0) {
            Set-DebugCheckVerdict $httpRow 'fail'; $httpRow.status = 'failed'
            $httpRow.detail = (@($bad | ForEach-Object { "$($_.name)=$($_.status)" }) -join ',')
            if ($authBlocked.Count -gt 0) { $httpRow.detail += '; authBlocked=' + (($authBlocked | ForEach-Object { $_.name }) -join ',') }
        } elseif ($authBlocked.Count -gt 0) {
            Set-DebugCheckVerdict $httpRow 'warn'; $httpRow.status = 'auth-blocked'
            $httpRow.detail = "authBlocked=" + (($authBlocked | ForEach-Object { "$($_.name)=$($_.status)" }) -join ',')
        } else {
            $httpRow.detail = (@($probes | ForEach-Object { "$($_.name)=$($_.status)" }) -join ',')
        }
    }
    $checks += [pscustomobject]$httpRow
    Write-DebugStage 'VERIFY' $(if ($httpRow.verdict -eq 'pass') { 'OK' } elseif ($httpRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) "check=http run=$($httpRow.run) status=$($httpRow.status) $($httpRow.detail)"

    $logInfo = if ($null -ne $rt0) { Get-DebugLogPath -RuntimeRow $rt0 } else { $null }
    $scan = Get-DebugExceptionScan -LogPath $(if ($null -ne $logInfo) { $logInfo.path } else { '' })
    $script:Report.log = [ordered]@{ path = if ($null -ne $logInfo) { $logInfo.path } else { '' }; source = if ($null -ne $logInfo) { $logInfo.source } else { 'unavailable' } }
    $script:Report.exceptions = $scan
    $fatalHits = @($scan.classes | Where-Object { $_.fatal -and $_.count -gt 0 })
    $warnHits = @($scan.classes | Where-Object { -not $_.fatal -and $_.count -gt 0 })
    $exRow = New-DebugCheckRow 'exceptions'
    $exRow.status = 'clean'; $exRow.detail = "windowLines=$($scan.windowLines)"; $exRow.evidence = [ordered]@{ log = $scan.log }
    # A missing/empty log window is "no evidence", never a clean bill: mark the
    # check not_observed instead of passing "0 exceptions found".
    if ($scan.windowLines -le 0) {
        $exRow.run = 'not_observed'; $exRow.status = 'log-unavailable-or-empty'
        $exRow.detail = 'no readable log window; exception state unproven'
        Set-DebugCheckVerdict $exRow 'warn'
    } elseif ($fatalHits.Count -gt 0) {
        Set-DebugCheckVerdict $exRow 'fail'; $exRow.status = 'fatal-classes'
        $exRow.detail = (@($fatalHits | ForEach-Object { "$($_.class)=$($_.count)" }) -join ',')
    } elseif ($warnHits.Count -gt 0) {
        Set-DebugCheckVerdict $exRow 'warn'; $exRow.status = 'errors-present'
        $exRow.detail = (@($warnHits | ForEach-Object { "$($_.class)=$($_.count)" }) -join ',')
    }
    $checks += [pscustomobject]$exRow
    Write-DebugStage 'VERIFY' $(if ($exRow.verdict -eq 'pass') { 'OK' } elseif ($exRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) "check=exceptions run=$($exRow.run) status=$($exRow.status) $($exRow.detail)"
    foreach ($c in @($scan.classes | Where-Object { $_.count -gt 0 })) {
        Write-DebugStage 'VERIFY' 'HIT' ("class={0} count={1} first(linesFromEnd={2}): {3}" -f $c.class, $c.count, $c.firstFromEnd, $c.firstHit)
        if ($c.lastFromEnd -ne $c.firstFromEnd) {
            Write-DebugStage 'VERIFY' 'HIT' ("class={0} last(linesFromEnd={1}): {2}" -f $c.class, $c.lastFromEnd, $c.lastHit)
        }
    }

    $configFiles = @(Get-DebugVerifyConfig -RuntimeRow $rt0)
    $missing = @($configFiles | Where-Object { -not $_.present })
    $configLogHits = @($scan.classes | Where-Object { $_.class -eq 'config' -and $_.count -gt 0 })
    $cfgRow = New-DebugCheckRow 'config'
    $cfgRow.status = 'files-present'
    # File presence is what this check covers; effective property binding
    # (profiles/env/args) is a declared coverage gap, not part of this verdict.
    $cfgRow.evidence = [ordered]@{ files = $configFiles; effectiveBinding = 'not-observed' }
    if ($configLogHits.Count -gt 0) {
        Set-DebugCheckVerdict $cfgRow 'fail'; $cfgRow.status = 'log-evidence'
        $cfgRow.detail = "placeholder/bind failures in out-log (class=config count=$($configLogHits[0].count))"
    } elseif ($missing.Count -gt 0) {
        Set-DebugCheckVerdict $cfgRow 'warn'; $cfgRow.status = 'missing-files'
        $cfgRow.detail = (@($missing | ForEach-Object { $_.file }) -join ',')
    } else {
        $cfgRow.detail = (@($configFiles | ForEach-Object { "$($_.file)=present" }) -join ',')
    }
    $checks += [pscustomobject]$cfgRow
    Write-DebugStage 'VERIFY' $(if ($cfgRow.verdict -eq 'pass') { 'OK' } elseif ($cfgRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) "check=config status=$($cfgRow.status) $($cfgRow.detail)"

    $fresh = if ($null -ne $rt0) { Get-DebugFreshness -RuntimeCreationUtc $rt0.creationDate -Port ([int]$rt0.port) } else { $null }
    $freshRow = New-DebugCheckRow 'freshness'
    $freshRow.status = 'current'
    if ($null -eq $fresh) {
        $freshRow.run = 'not_observed'; $freshRow.status = 'not_observed'; $freshRow.detail = 'no runtime to compare against'
        Set-DebugCheckVerdict $freshRow 'warn'
    } else {
        $freshRow.evidence = [ordered]@{ newestSource = $fresh.newestSource; newestSourceWriteUtc = $fresh.newestSourceWriteUtc; servedAsset = $fresh.servedAsset.match }
        $freshRow.detail = "sourcesNewer=$($fresh.sourcesNewer) newest=$($fresh.newestSource)"
        # mtime evidence only suspects staleness (DevTools restart may already
        # have applied it); served-asset hash mismatch is the confirmed case.
        if ($fresh.sourcesNewer -eq 'true-stale-candidate') {
            Set-DebugCheckVerdict $freshRow 'fail'; $freshRow.status = 'stale-candidate'
            $freshRow.evidence.reason = 'source_newer_than_process'; $freshRow.evidence.confidence = 'unproven'
        } elseif ($fresh.servedAsset.match -eq 'mismatch') {
            Set-DebugCheckVerdict $freshRow 'fail'; $freshRow.status = 'served-asset-mismatch'
            $freshRow.evidence.reason = 'served_hash_differs'; $freshRow.evidence.confidence = 'confirmed'
        }
    }
    $checks += [pscustomobject]$freshRow
    Write-DebugStage 'VERIFY' $(if ($freshRow.verdict -eq 'pass') { 'OK' } elseif ($freshRow.verdict -eq 'warn') { 'WARN' } else { 'FAIL' }) "check=freshness run=$($freshRow.run) status=$($freshRow.status) $($freshRow.detail)"

    $w = Get-DebugWatcherRows
    $dwRow = New-DebugCheckRow 'devwatch'
    $dwRow.status = 'observed'
    if ($null -eq $w.state) {
        $dwRow.run = 'not_observed'; $dwRow.status = 'not_observed'; $dwRow.detail = 'no watch.state.json'
        Set-DebugCheckVerdict $dwRow 'warn'
    } else {
        $failStreak = [int](Get-AwxObjectProperty $w.state 'failStreak')
        $dwRow.evidence = [ordered]@{ status = [string]$w.state.status; tier = [string]$w.state.lastTier; updatedAt = [string]$w.state.updatedAt; failStreak = $failStreak }
        $dwRow.detail = "status=$($w.state.status) tier=$($w.state.lastTier) failStreak=$failStreak"
        if ($failStreak -gt 0) { Set-DebugCheckVerdict $dwRow 'warn'; $dwRow.status = 'fail-streak' }
    }
    $checks += [pscustomobject]$dwRow
    Write-DebugStage 'VERIFY' $(if ($dwRow.verdict -eq 'pass') { 'OK' } else { 'WARN' }) "check=devwatch run=$($dwRow.run) status=$($dwRow.status) $($dwRow.detail)"

    $ollamaDown = @()
    foreach ($oPort in @(11434, 11435)) { if (-not (Test-RagOllama -Port $oPort)) { $ollamaDown += $oPort } }
    $sharedRow = New-DebugCheckRow 'shared-ollama'
    $sharedRow.status = $(if ($ollamaDown.Count -gt 0) { 'unhealthy' } else { 'healthy' })
    $sharedRow.detail = $(if ($ollamaDown.Count -gt 0) { 'down=' + ($ollamaDown -join ',') } else { '11434+11435 ok' })
    if ($ollamaDown.Count -gt 0) { Set-DebugCheckVerdict $sharedRow 'warn' }
    $checks += [pscustomobject]$sharedRow
    Write-DebugStage 'VERIFY' $(if ($sharedRow.verdict -eq 'pass') { 'OK' } else { 'WARN' }) "check=shared-ollama status=$($sharedRow.status) $($sharedRow.detail)"

    $failed = @($checks | Where-Object { $_.verdict -eq 'fail' })
    $warned = @($checks | Where-Object { $_.verdict -eq 'warn' })
    # Checks that could not run at all (blocked/not_observed) — never folded
    # into a pass; 'skipped' means the caller did not request that scope.
    $notRun = @($checks | Where-Object { $_.run -ne 'executed' })
    $unproven = @($checks | Where-Object { $_.verdict -eq 'warn' -and $_.run -eq 'executed' })
    $first = if ($failed.Count -gt 0) { [string]$failed[0].name } else { '' }
    $hint = if ($first -and $script:DebugVerifyHints.Contains($first)) { [string]$script:DebugVerifyHints[$first] } else { '' }
    $scope = if ($script:DbgWithCompile) { 'live+compile' } else { 'live-readonly' }
    $buildState = if (-not $script:DbgWithCompile) { 'not-run' }
        elseif ($compileRow.status -eq 'passed') { 'passed' }
        elseif ($compileRow.status -eq 'compile-timeout') { 'timeout' } else { 'failed' }
    $targetVerdict = if ($runtimeDown) { 'not-running' }
        elseif ($failed.Count -gt 0) { 'failed' }
        elseif (($unproven.Count + @($notRun | Where-Object { $_.run -ne 'skipped' }).Count) -gt 0) { 'partial' }
        else { 'verified' }
    $script:Report.verify = [ordered]@{
        schema = 'awx.debug.verify.v2'
        checks = $checks
        failedChecks = @($failed | ForEach-Object { $_.name })
        warnChecks = @($warned | ForEach-Object { $_.name })
        notRun = @($notRun | ForEach-Object { "$($_.name):$($_.run):$($_.status)" })
        firstFailure = $first
        hint = $hint
        verdict = [ordered]@{
            tool = 'executed'
            scope = $scope
            build = $buildState
            tests = 'not-wired'
            target = $targetVerdict
            fullVerification = $false
            fullReason = 'build/tests not wired into this action; verify proves live checks only'
        }
        coverageGaps = @('config-effective-binding', 'unit-tests-not-wired')
    }
    if ($runtimeDown) {
        $script:Report.status = 'not-running'
        Write-DebugStage 'RESULT' 'DOWN' "status=not-running failedChecks=$($script:Report.verify.failedChecks -join ',')"
        return 3
    }
    if ($failed.Count -gt 0) {
        $script:Report.status = "verify-failed:$first"
        Write-DebugStage 'RESULT' 'FAILED' "status=verify-failed first=$first hint=$hint"
        return 6
    }
    $script:Report.status = 'verified'; $script:Report.ok = $true
    Write-DebugStage 'RESULT' 'OK' ("status=verified target=$targetVerdict checks={0} warns={1} notRun={2}" -f $checks.Count, @($warned).Count, @($notRun).Count)
    return 0
}

function Invoke-DebugTail {
    $runtimes = @(Get-DebugRuntimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    if ($runtimes.Count -eq 0) {
        Write-DebugStage 'TAIL' 'DOWN' "no $script:DbgRole runtime running; nothing to tail"
        $script:Report.status = 'not-running'
        return 3
    }
    $logInfo = Get-DebugLogPath -RuntimeRow $runtimes[0]
    if ($null -eq $logInfo) {
        Write-DebugStage 'TAIL' 'FAILED' 'no out-log path resolved'
        $script:Report.status = 'log-unavailable'
        return 1
    }
    $log = $logInfo.path
    $histN = if ($script:DbgStageContext -gt 0) { [int]$script:DbgStageContext } else { [Math]::Max(0, [int]$script:DbgHistoryLines) }
    Write-DebugStage 'TAIL' 'INFO' "log=$log source=$($logInfo.source) seconds=$($script:DbgTailSeconds) maxLines=$($script:DbgMaxTailLines) history=$histN pattern='$($script:DbgPattern)'"
    $tailFile = Join-Path $script:DebugDir ("{0}-{1}-tail.log" -f $script:DbgRole, $script:RunStamp)
    $historyHits = @()
    if ($histN -gt 0) {
        $window = @(Get-DebugLogWindow -LogPath $log)
        if ($script:DbgPattern) {
            $historyHits = @($window | Where-Object { $_ -match $script:DbgPattern } | Select-Object -Last $histN)
        } else {
            $historyHits = @($window | Select-Object -Last $histN)
        }
        foreach ($l in $historyHits) {
            Write-Host ("  hist| " + $l)
            Add-Content -LiteralPath $tailFile -Value $l -Encoding UTF8
        }
    }
    $captured = 0; $deadline = (Get-Date).AddSeconds($script:DbgTailSeconds)
    $fs = $null; $reader = $null
    try {
        $fs = [IO.FileStream]::new($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $fs.Seek(0, [IO.SeekOrigin]::End) | Out-Null
        $reader = [IO.StreamReader]::new($fs, [Text.Encoding]::UTF8)
        while ((Get-Date) -lt $deadline -and $captured -lt $script:DbgMaxTailLines) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { Start-Sleep -Milliseconds 700; continue }
            if ($script:DbgPattern -and $line -notmatch $script:DbgPattern) { continue }
            $captured++
            Write-Host ("  new| " + $line)
            Add-Content -LiteralPath $tailFile -Value $line -Encoding UTF8
        }
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $fs) { $fs.Dispose() }
    }
    $histCount = @($historyHits).Count
    if ($histCount -eq 0 -and $captured -eq 0) {
        Write-DebugStage 'TAIL' 'DONE' "과거 매칭 0 / 감시 중 신규 0 file=$tailFile (watch closed; server untouched)"
    } else {
        Write-DebugStage 'TAIL' 'DONE' "history=$histCount captured=$captured file=$tailFile (watch closed; server untouched)"
    }
    $script:Report.status = 'tail-complete'; $script:Report.ok = $true
    $script:Report.tail = [ordered]@{ history = $histCount; captured = $captured }
    return 0
}

function Invoke-DebugThreads {
    $runtimes = @(Get-DebugRuntimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    if ($runtimes.Count -eq 0) {
        Write-DebugStage 'THREADS' 'DOWN' "no $script:DbgRole runtime running"
        $script:Report.status = 'not-running'
        return 3
    }
    $jcmd = Get-Command jcmd.exe -ErrorAction SilentlyContinue
    if ($null -eq $jcmd) {
        Write-DebugStage 'THREADS' 'FAILED' 'jcmd not on PATH (JDK tool missing)'
        $script:Report.status = 'jdk-tool-missing'
        return 1
    }
    $procId = [int]$runtimes[0].processId
    $out = Join-Path $script:DebugDir ("threads-{0}-{1}.txt" -f $script:DbgRole, $script:RunStamp)
    Write-DebugStage 'THREADS' 'RUN' "jcmd $procId Thread.print -> $out"
    & $jcmd.Source $procId 'Thread.print' 2>&1 | Out-File -LiteralPath $out -Encoding UTF8
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $out -PathType Leaf)) {
        Write-DebugStage 'THREADS' 'FAILED' "jcmd exit=$LASTEXITCODE"
        $script:Report.status = 'jcmd-failed'
        return 1
    }
    $size = (Get-Item -LiteralPath $out).Length
    $dump = Get-Content -LiteralPath $out -Raw -ErrorAction SilentlyContinue
    $blocked = if ($dump) { ([regex]::Matches($dump, 'java\.lang\.Thread\.State: BLOCKED')).Count } else { 0 }
    $deadlock = if ($dump -and $dump -match '(?i)Found (one Java-level deadlock|\d+ deadlock)') { $true } else { $false }
    Write-DebugStage 'THREADS' 'OK' "captured pid=$procId bytes=$size blocked=$blocked deadlock=$deadlock file=$out"
    $script:Report.status = 'threads-captured'; $script:Report.ok = $true
    $script:Report.threads = [ordered]@{ blocked = $blocked; deadlock = $deadlock; file = $out }
    return 0
}

function Invoke-DebugJfr {
    $runtimes = @(Get-DebugRuntimes | Where-Object { $_.metaDisplay -and $_.role -eq $script:DbgRole })
    if ($runtimes.Count -eq 0) {
        Write-DebugStage 'JFR' 'DOWN' "no $script:DbgRole runtime running"
        $script:Report.status = 'not-running'
        return 3
    }
    $jcmd = Get-Command jcmd.exe -ErrorAction SilentlyContinue
    if ($null -eq $jcmd) {
        Write-DebugStage 'JFR' 'FAILED' 'jcmd not on PATH (JDK tool missing)'
        $script:Report.status = 'jdk-tool-missing'
        return 1
    }
    $procId = [int]$runtimes[0].processId
    $out = Join-Path $script:DebugDir ("{0}-flight-{1}.jfr" -f $script:DbgRole, $script:RunStamp)
    $name = "awx-debug-$($script:RunStamp)"
    Write-DebugStage 'JFR' 'RUN' "jcmd $procId JFR.start duration=$($script:DbgJfrSeconds)s maxsize=50m -> $out"
    & $jcmd.Source $procId 'JFR.start' "name=$name" "duration=$($script:DbgJfrSeconds)s" "filename=$out" 'settings=profile' 'maxsize=50m' 2>&1 |
        ForEach-Object { Write-DebugStage 'JFR' 'OUT' ([string]$_) }
    if ($LASTEXITCODE -ne 0) {
        Write-DebugStage 'JFR' 'FAILED' "jcmd exit=$LASTEXITCODE"
        $script:Report.status = 'jcmd-failed'
        return 1
    }
    if (Test-Path -LiteralPath $out -PathType Leaf) {
        Write-DebugStage 'JFR' 'OK' "recording scheduled pid=$procId duration=$($script:DbgJfrSeconds)s file=$out"
        $script:Report.status = 'jfr-scheduled'
    } else {
        Write-DebugStage 'JFR' 'OK' "recording scheduled pid=$procId duration=$($script:DbgJfrSeconds)s; file=$out appears when the recording finishes"
        $script:Report.status = 'jfr-scheduled-file-pending'
    }
    $script:Report.ok = $true
    return 0
}

function Show-DebugHelp {
    $lines = @(
        ''
        'Debug-RAG.bat / Debug-Meta-Display.bat  ->  scripts\debug_rag_stack.ps1 -Role dev|wear'
        ''
        'ACTIONS (default = status; never stops or duplicates a running server):'
        '  status   read-only: runtime pid/role/ports, ownership manifest proof, verbose flags,'
        '           readiness probes, source-vs-runtime freshness, devwatch state, out-log path,'
        '           pipeline stage summary from existing markers (no new traffic is generated)'
        '  start    start target with per-launch verbose loggers (process-env -> --logging.level.*'
        '           JVM args). dev mirrors Start-RAG (ForceRestart+DevWatch, no browser unless'
        '           -OpenBrowser); wear mirrors Start-Meta-Display (reuse if already running).'
        '  restart  role-scoped Close (stop_rag_stack) + debug start. dev -> Close-RAG equivalent,'
        '           wear -> Close-Meta-Display equivalent. Other roles and shared Ollama stay untouched.'
        '  tail     recent matching history (default 30, -HistoryLines / -StageContextLines) then bounded'
        '           follow (default 30s, -TailSeconds <=600, -MaxTailLines, optional -Pattern regex).'
        '           Closing the watch never stops the server.'
        '  threads  one jcmd Thread.print of the target pid -> var\debug\threads-<role>-<ts>.txt'
        '  jfr      jcmd JFR.start bounded recording (-JfrSeconds <=300, maxsize=50m, profile settings)'
        '  verify   one-call post-edit judgement (read-only): optional -WithCompile Gradle'
        '           compileJava+processResources, runtime presence+ownership, fixed-port map,'
        '           loopback HTTP probes (home/chat-ui/interview asset/mgmt health + role probe),'
        '           generic exception+config+port-bind classes over the out-log window with'
        '           first/last hit lines, config-file presence, source-vs-runtime freshness,'
        '           DevWatch state, shared Ollama. Report schema awx.debug.verify.v2:'
        '           checks[].run (executed|skipped|blocked|not_observed) + verdict + Boolean ok,'
        '           failedChecks/warnChecks/notRun/firstFailure/hint, and verdict{tool,scope,'
        '           build,tests,target,fullVerification=false} - tool ran vs target verified vs'
        '           build/test coverage stay separate; unit tests are not wired.'
        ''
        'OPTIONS:'
        '  -Loggers "pkg=LEVEL;pkg2=LEVEL"   override default verbose set (<=8, name=LEVEL only)'
        '      dev default : com.example.lms.api;com.example.lms.search;com.example.lms.service.rag;com.example.lms.llm =DEBUG'
        '      wear default: com.example.lms.assist=DEBUG;com.example.lms.search=DEBUG'
        '  -LogMinutes <n>   stage-summary lookback window (default 60; reads from end, cap 20k lines / 8MiB)'
        '  -HistoryLines <n> tail past matches before follow (default 30)'
        '  -DryRun           print planned env + commands without executing'
        '  -Json             also write var\debug\<role>-<ts>-<action>.json (includes pipeline/freshness/ports)'
        '  -JsonStdout       emit the report as ONE JSON document on stdout (stage lines still'
        '                    go to the per-run log file); parse stdout at the process boundary'
        '  -TimeoutSeconds <n>  start readiness bound (default 600, forwarded to launcher)'
        ''
        'EXIT CODES:'
        '  status : 0 ready | 3 not-running | 4 degraded | 1 error'
        '  start  : 0 ready+verbose applied | 5 ready but verbose not applied (reused JVM)'
        '           2 blocked (protected/conflict) | 1 failed'
        '  tail/threads/jfr : 0 ok | 3 not-running | 1 failed'
        '  verify : 0 all executed checks pass (warn/notRun stay visible) | 3 not-running |'
        '           6 checks failed (see failedChecks+hint) | 1 error'
        ''
        'EXAMPLES:'
        '  Debug-RAG.bat                                    status only'
        '  Debug-Meta-Display.bat -Action tail -TailSeconds 60'
        '  Debug-RAG.bat -Action start -DryRun              show plan, touch nothing'
        '  Debug-RAG.bat -Action restart                    dev Close+Start with verbose loggers'
        '  Debug-Meta-Display.bat -Action start -Loggers "com.example.lms.assist=DEBUG;com.example.lms.search=DEBUG"'
        '  Debug-RAG.bat -Action verify -Json               post-edit judgement: runtime/ports/http/logs/config/freshness'
        '  Debug-RAG.bat -Action verify -WithCompile        same, Gradle compileJava+processResources first'
        ''
        'NOTES: a running JVM keeps its original flags - verbose applies only to a JVM this'
        'action launches. Verbose state is proven from the live JVM cmdline, not assumed from'
        'env. Stop a debug-started server with the matching Close BAT (Close-RAG /'
        'Close-Meta-Display); normal Start BATs leave no verbose residue.'
    )
    foreach ($l in $lines) { Write-Host $l }
}

function Invoke-DebugEntry {
    try {
        if ($script:DbgHelp -or $script:DbgAction -eq 'help') { Show-DebugHelp; return 0 }
        Write-DebugStage 'INIT' 'START' "role=$($script:DbgRole) action=$($script:DbgAction) log=$($script:DebugLog)"
        $code = 0
        switch ($script:DbgAction) {
            'status'  { $code = Invoke-DebugStatus }
            'start'   { $code = Invoke-DebugStart }
            'restart' { $code = Invoke-DebugRestart }
            'tail'    { $code = Invoke-DebugTail }
            'threads' { $code = Invoke-DebugThreads }
            'jfr'     { $code = Invoke-DebugJfr }
            'verify'  { $code = Invoke-DebugVerify }
            default   { Write-DebugStage 'INIT' 'FAILED' "unknown action $($script:DbgAction)"; $code = 1 }
        }
        if ($script:DbgJson) {
            $script:Report.exitCode = $code
            $jsonPath = Join-Path $script:DebugDir ("{0}-{1}-{2}.json" -f $script:DbgRole, $script:RunStamp, $script:DbgAction)
            Write-AwxJsonAtomic -Path $jsonPath -Data $script:Report
            Write-DebugStage 'RESULT' 'JSON' $jsonPath
        }
        if ($script:DbgJsonStdout) {
            # stdout contract: exactly one JSON document, no stage lines mixed in.
            $script:Report.exitCode = $code
            [Console]::Out.Write(($script:Report | ConvertTo-Json -Depth 12 -Compress))
            [Console]::Out.Write([Environment]::NewLine)
        }
        Invoke-DebugRetention
        Write-DebugStage 'RESULT' 'EXIT' "code=$code"
        return $code
    } catch {
        $reason = $_.Exception.Message
        $where = ($_.ScriptStackTrace -split "`n" | Select-Object -First 1)
        Write-DebugStage 'FATAL' 'FAILED' "reason=$reason at=$where"
        return 1
    }
}

if ($MyInvocation.InvocationName -ne '.') { exit (Invoke-DebugEntry) }

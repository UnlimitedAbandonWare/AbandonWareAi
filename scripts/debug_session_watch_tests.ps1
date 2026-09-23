# debug_session_watch_tests.ps1 - contract tests for the session debug watcher.
# Dot-sources debug_session_watch.ps1 (entry is guarded by InvocationName), which
# itself dot-sources debug_rag_stack.ps1 for the shared discovery/scan layer.
# Fixture dirs are created under TEMP and removed.

$ErrorActionPreference = 'Stop'
$watch = Join-Path $PSScriptRoot 'debug_session_watch.ps1'
if (-not (Test-Path -LiteralPath $watch -PathType Leaf)) { throw 'FAIL: debug_session_watch.ps1 is missing' }
. $watch

$script:passed = 0
function Assert-SessTest($Condition, $Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
}

$script:DbgRole = 'wear'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('awx-sess-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $ts = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fff') + '+0900'

    # --- line classification ---
    $c1 = Get-SessionLineClass -Line "$ts ERROR [ sid ] c.e.l.a.X - APPLICATION FAILED TO START"
    Assert-SessTest ($c1.kind -eq 'error' -and $c1.class -eq 'spring-fatal' -and $c1.fatal -eq $true) 'spring-fatal classified fatal error'

    $c2 = Get-SessionLineClass -Line "$ts ERROR [ sid ] c.e.l.a.X - Caused by: java.lang.IllegalStateException: boom"
    Assert-SessTest ($c2.kind -eq 'error' -and $c2.class -eq 'exception-lines') 'Caused-by line is exception-lines'

    $c3 = Get-SessionLineClass -Line "$ts INFO [ sid ] c.e.l.a.D - display.runtime stage=http_error event=http_error code=http_500"
    Assert-SessTest ($c3.kind -eq 'error' -or $c3.kind -eq 'http') 'http_error line is error-or-http class'

    $c4 = Get-SessionLineClass -Line "$ts WARN [ sid ] c.e.l.a.D - display.request_failed path=/api/assist/display/relay"
    Assert-SessTest ($c4.kind -eq 'http') 'request_failed marker is http event'

    $c5 = Get-SessionLineClass -Line "$ts WARN [ sid ] c.e.l.s.W - WebClient Read timed out after 3000 ms"
    Assert-SessTest ($c5.kind -eq 'timeout') 'timed out marker is timeout event'

    $c6 = Get-SessionLineClass -Line "$ts INFO [ sid ] c.e.l.a.C - conversate.cue {cueDecision=NO_CUE,hintGenerated=false}"
    Assert-SessTest ($c6.kind -eq 'stage' -and $c6.class -eq 'cue-gate') 'conversate.cue is cue-gate stage'

    $c7 = Get-SessionLineClass -Line "$ts INFO [ sid ] c.e.l.a.Normal - plain info line nothing special"
    Assert-SessTest ($c7.kind -eq 'line') 'plain line is not an event'

    # --- ring buffer + error context file ---
    $script:SessPaths = New-SessionPaths -Dir (Join-Path $fixture 'sess-a')
    New-Item -ItemType Directory -Force -Path $script:SessPaths.contexts | Out-Null
    $script:SessState = @{
        seq = 0; startedAt = ''; stoppedAt = ''; watcherPid = 0; status = 'running'; stopReason = ''
        ring = (New-Object 'System.Collections.Generic.Queue[string]')
        pending = (New-Object 'System.Collections.Generic.List[hashtable]')
        contextFiles = 0; contextsCapped = $false
        target = $null; envStart = $null
        poll = @{ pidAlive = $true; portOwner = -1; healthy = $true }
        counters = [ordered]@{ linesOut = 0; linesErr = 0; errors = 0; httpErrors = 0; timeouts = 0; probeFails = 0; stages = @{} }
    }
    $script:SessEvents = New-Object 'System.Collections.Generic.List[object]'
    $script:Sess.Ring = 50
    $script:Sess.ContextLines = 40
    $script:Sess.ContextAfter = 10
    $script:Sess.MaxContexts = 5
    foreach ($f in @($script:SessPaths.events, $script:SessPaths.streamOut, $script:SessPaths.streamErr)) {
        New-Item -ItemType File -Force -Path $f | Out-Null
    }
    $script:DebugLog = $script:SessPaths.log

    1..80 | ForEach-Object { Push-SessionRing -Line ("out| line {0}" -f $_) }
    Assert-SessTest ($script:SessState.ring.Count -eq 50) 'ring buffer caps at RingLines'
    Assert-SessTest (@($script:SessState.ring.ToArray())[-1] -match 'line 80') 'ring keeps newest lines'

    $ctxName = Save-ErrorContext -Seq 1 -Class 'exception-lines' -Source 'out' -Line "$ts ERROR boom"
    Assert-SessTest ($ctxName -ne '') 'context file name returned'
    $ctxPath = Join-Path $script:SessPaths.contexts $ctxName
    Assert-SessTest (Test-Path -LiteralPath $ctxPath -PathType Leaf) 'context file written'
    $ctxText = Get-Content -LiteralPath $ctxPath -Raw -Encoding UTF8
    Assert-SessTest ($ctxText -match '# --- pre' -and $ctxText -match 'line 80' -and $ctxText -match 'out\| .*ERROR boom') 'context keeps pre-lines + hit'

    Update-PendingContexts -Source 'out' -Line 'post line A'
    Start-Sleep -Seconds 4
    Update-PendingContexts -Source 'out' -Line 'post line B'
    $ctxText2 = Get-Content -LiteralPath $ctxPath -Raw -Encoding UTF8
    Assert-SessTest ($ctxText2 -match 'post line A' -and $ctxText2 -match '# --- end ---') 'post lines + end marker appended'

    1..10 | ForEach-Object { $n = Save-ErrorContext -Seq $_ -Class 'x' -Source 'out' -Line 'hit' | Out-Null }
    Assert-SessTest ($script:SessState.contextsCapped -eq $true) 'context files are capped at MaxContextFiles'

    # --- suspect file mapping from stack frames ---
    $frames = Join-Path $fixture 'frames.log'
    @(
        "$ts INFO [ sid ] c.e.l.a.X - java.lang.IllegalStateException: boom",
        "$ts INFO [ sid ] c.e.l.a.X - `tat com.example.lms.assist.NovaFocusService.answer(NovaFocusService.java:123)",
        "$ts INFO [ sid ] c.e.l.a.X - `tat com.example.lms.zzz.Ghost.run(Ghost.java:7)",
        "$ts INFO [ sid ] c.e.l.a.X - `tat com.example.lms.assist.NovaFocusService.answer(NovaFocusService.java:123)"
    ) | Set-Content -LiteralPath $frames -Encoding UTF8
    $suspects = @(Get-SessionSuspectFiles -Files @($frames))
    Assert-SessTest ($suspects.Count -ge 1) 'suspect frames parsed'
    $nova = @($suspects | Where-Object { $_.file -eq 'NovaFocusService.java' })[0]
    Assert-SessTest ($null -ne $nova -and $nova.sourcePath -eq 'main/java/com/example/lms/assist/NovaFocusService.java') 'real class maps to active sourceSet path'
    Assert-SessTest ($nova.existsOnDisk -eq $true -and $nova.hits -eq 2) 'mapped file exists on disk + hit count aggregated'
    $ghost = @($suspects | Where-Object { $_.file -eq 'Ghost.java' })[0]
    Assert-SessTest ($null -ne $ghost -and $ghost.existsOnDisk -eq $false) 'unmapped frame reports existsOnDisk=false'

    $causes = @(Get-SessionCauses -Files @($frames))
    Assert-SessTest ($causes.Count -eq 0) 'no Caused-by in fixture -> empty causes'
    @("$ts ERROR Caused by: java.lang.IllegalStateException: deep boom") | Add-Content -LiteralPath $frames -Encoding UTF8
    $causes2 = @(Get-SessionCauses -Files @($frames))
    Assert-SessTest ($causes2.Count -eq 1 -and $causes2[0].type -eq 'java.lang.IllegalStateException') 'Caused-by type extracted'

    # --- end-to-end: foreground run over a fixture log ---
    $fxLog = Join-Path $fixture 'app-out.log'
    $fxErr = Join-Path $fixture 'app-err.log'
    @(
        "$ts INFO [ sid ] c.e.l.a.Boot - started",
        "$ts INFO [ sid ] c.e.l.a.C - conversate.cue {cueDecision=RAG_CUE,hintGenerated=true}",
        "$ts ERROR [ sid ] c.e.l.a.Svc - java.lang.RuntimeException: service exploded",
        "$ts ERROR [ sid ] c.e.l.a.Svc - `tat com.example.lms.assist.NovaFocusService.answer(NovaFocusService.java:123)",
        "$ts WARN [ sid ] c.e.l.a.D - display.request_failed path=/api/assist/display/relay"
    ) | Set-Content -LiteralPath $fxLog -Encoding UTF8
    @("$ts WARN stderr side channel line") | Set-Content -LiteralPath $fxErr -Encoding UTF8

    $sessRoot = Join-Path $fixture 'sessions'
    $script:Sess.SessionRoot = $sessRoot
    $script:Sess.SessionDir = ''
    $script:Sess.LogPath = $fxLog
    $script:Sess.ErrPath = $fxErr
    $script:Sess.FromStart = $true
    $script:Sess.Duration = 3
    $script:Sess.AttachWait = 1
    $script:Sess.Poll = 5
    $script:Sess.Heartbeat = 30
    $script:Sess.NoHandoff = $true
    $script:Sess.HandoffRoot = (Join-Path $fixture 'handoff')
    $code = Invoke-SessionWatch
    Assert-SessTest ($code -eq 0) "fixture run exits 0 (got $code)"
    $sessDirs = @(Get-ChildItem -LiteralPath $sessRoot -Directory -ErrorAction SilentlyContinue)
    Assert-SessTest ($sessDirs.Count -eq 1) 'session dir created'
    $sd = $sessDirs[0].FullName
    Assert-SessTest (Test-Path -LiteralPath (Join-Path $sd 'events.jsonl') -PathType Leaf) 'events.jsonl written'
    Assert-SessTest (Test-Path -LiteralPath (Join-Path $sd 'packet.json') -PathType Leaf) 'packet.json written'
    Assert-SessTest (Test-Path -LiteralPath (Join-Path $sd 'packet.md') -PathType Leaf) 'packet.md written'
    Assert-SessTest (Test-Path -LiteralPath (Join-Path $sd 'stream-out.log') -PathType Leaf) 'stream-out.log written'
    Assert-SessTest (Test-Path -LiteralPath (Join-Path $sd 'stream-err.log') -PathType Leaf) 'stream-err.log written'

    $evts = @()
    foreach ($l in @(Get-Content -LiteralPath (Join-Path $sd 'events.jsonl') -Encoding UTF8)) {
        try { $evts += ($l | ConvertFrom-Json) } catch { }
    }
    Assert-SessTest (@($evts | Where-Object { $_.kind -eq 'log.error' }).Count -ge 1) 'error event captured'
    Assert-SessTest (@($evts | Where-Object { $_.kind -eq 'log.http' }).Count -ge 1) 'http event captured'
    Assert-SessTest (@($evts | Where-Object { $_.kind -eq 'log.stage' }).Count -ge 1) 'stage event captured'
    Assert-SessTest (@($evts | Where-Object { $_.kind -eq 'lifecycle' }).Count -ge 2) 'lifecycle events recorded'
    $ctxFiles = @(Get-ChildItem -LiteralPath (Join-Path $sd 'contexts') -File -ErrorAction SilentlyContinue)
    Assert-SessTest ($ctxFiles.Count -ge 1) 'error context bundle saved'

    $packet = Get-Content -LiteralPath (Join-Path $sd 'packet.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-SessTest ($packet.schemaVersion -eq 'awx.debug-session.v1') 'packet schema v1'
    Assert-SessTest ($packet.counts.errors -ge 1 -and $packet.counts.httpErrors -ge 1) 'packet counts errors+http'
    Assert-SessTest (@($packet.suspectFiles).Count -ge 1 -and $packet.suspectFiles[0].sourcePath -match 'NovaFocusService\.java') 'packet maps suspect file'
    Assert-SessTest (@($packet.errorClasses).Count -ge 1) 'packet lists error classes'
    Assert-SessTest ($packet.coverage.gaps -contains 'synthetic-target: process/port/probe monitoring skipped') 'synthetic coverage gap declared'
    Assert-SessTest (@($packet.howToAnalyze).Count -ge 3) 'packet carries analysis guidance'
    $mdText = Get-Content -LiteralPath (Join-Path $sd 'packet.md') -Raw -Encoding UTF8
    Assert-SessTest ($mdText -match 'Suspect source files' -and $mdText -match 'How to analyze') 'packet.md digest sections present'

    $st = Get-Content -LiteralPath (Join-Path $sd 'session.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-SessTest ($st.status -eq 'finished' -and $st.stopReason -eq 'duration-reached') 'session.json finished state'
    $streamText = Get-Content -LiteralPath (Join-Path $sd 'stream-out.log') -Raw -Encoding UTF8
    Assert-SessTest ($streamText -match 'service exploded') 'stream-out preserved the raw error line'
    $fxLogItem = Get-Item -LiteralPath $fxLog
    Assert-SessTest ($fxLogItem.Length -gt 0) 'source fixture log untouched (read-only)'

    # --- status/stop/finalize on the finished dir ---
    $script:Sess.SessionDir = $sd
    $code = Invoke-SessionStatus
    Assert-SessTest ($code -eq 0 -and $script:SessResult.watchStatus -eq 'finished') 'status reports finished session'
    $script:SessResult = [ordered]@{ schemaVersion = 'awx.debug-session.v1'; ok = $false; status = 'started'; action = 'finalize'; role = 'wear' }
    Remove-Item -LiteralPath (Join-Path $sd 'packet.json') -Force
    $code = Invoke-SessionFinalize
    Assert-SessTest ($code -eq 0 -and (Test-Path -LiteralPath (Join-Path $sd 'packet.json') -PathType Leaf)) 'finalize rebuilds packet.json'

    # --- detached start + stop on a growing fixture log ---
    $liveLog = Join-Path $fixture 'live-out.log'
    @("$ts INFO [ sid ] c.e.l.a.Boot - live start") | Set-Content -LiteralPath $liveLog -Encoding UTF8
    $script:Sess.SessionDir = ''
    $script:Sess.LogPath = $liveLog
    $script:Sess.ErrPath = ''
    $script:Sess.FromStart = $true
    $script:Sess.Duration = 0
    $script:Sess.Force = $false
    $script:SessResult = [ordered]@{ schemaVersion = 'awx.debug-session.v1'; ok = $false; status = 'started'; action = 'start'; role = 'wear' }
    $code = Invoke-SessionStart
    Assert-SessTest ($code -eq 0) "detached start exits 0 (got $code)"
    $liveDir = [string]$script:SessResult.sessionDir
    Assert-SessTest ($liveDir -ne '' -and (Test-Path -LiteralPath $liveDir -PathType Container)) 'detached session dir exists'
    Add-Content -LiteralPath $liveLog -Value "$ts ERROR [ sid ] c.e.l.a.Svc - live crash java.lang.RuntimeException: appended during watch" -Encoding UTF8
    Add-Content -LiteralPath $liveLog -Value "$ts ERROR [ sid ] c.e.l.a.Svc - `tat com.example.lms.assist.NovaFocusService.answer(NovaFocusService.java:99)" -Encoding UTF8
    Start-Sleep -Seconds 4
    $script:Sess.SessionDir = $liveDir
    $script:SessResult = [ordered]@{ schemaVersion = 'awx.debug-session.v1'; ok = $false; status = 'started'; action = 'stop'; role = 'wear' }
    $code = Invoke-SessionStop
    Assert-SessTest ($code -eq 0) "stop finalizes detached session (got $code)"
    $liveEvents = @(Get-Content -LiteralPath (Join-Path $liveDir 'events.jsonl') -Encoding UTF8 | Where-Object { $_ -match 'appended during watch' })
    Assert-SessTest ($liveEvents.Count -ge 1) 'watcher captured lines appended during the session'
    $livePacket = Get-Content -LiteralPath (Join-Path $liveDir 'packet.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-SessTest (@($livePacket.suspectFiles).Count -ge 1) 'detached packet maps frames'
    $liveSt = Get-Content -LiteralPath (Join-Path $liveDir 'session.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-SessTest ($liveSt.status -eq 'finished' -and $liveSt.stopReason -eq 'stop-requested') 'detached session ended via stop flag'

    # --- retention bound ---
    # Repoint the stage log first: retention may prune the session dir that
    # currently owns $script:DebugLog (production writes it under var\debug).
    $script:DebugLog = Join-Path $fixture 'test-stage.log'
    1..17 | ForEach-Object {
        $d = Join-Path $sessRoot ('wear-2026010{0:d2}-000000-session' -f $_)
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
    Invoke-SessionRetention
    $remain = @(Get-ChildItem -LiteralPath $sessRoot -Directory | Where-Object { $_.Name -match '-session' })
    Assert-SessTest ($remain.Count -le 15) 'session dirs pruned to bound'

    # --- status on empty root -> no-session ---
    $script:Sess.SessionRoot = (Join-Path $fixture 'empty-root')
    $script:Sess.SessionDir = ''
    $code = Invoke-SessionStatus
    Assert-SessTest ($code -eq 3 -and $script:SessResult.status -eq 'no-session') 'status exit 3 with no session'
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ("PASS {0} debug_session_watch contract tests" -f $script:passed)

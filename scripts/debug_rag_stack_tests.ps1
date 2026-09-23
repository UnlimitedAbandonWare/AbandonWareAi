# debug_rag_stack_tests.ps1 — contract tests for Debug BAT pipeline/tail helpers.
# Dot-sources debug_rag_stack.ps1 (entry is guarded by InvocationName).
# Fixture dirs are created under TEMP and removed.

$ErrorActionPreference = 'Stop'
$dbg = Join-Path $PSScriptRoot 'debug_rag_stack.ps1'
if (-not (Test-Path -LiteralPath $dbg -PathType Leaf)) { throw 'FAIL: debug_rag_stack.ps1 is missing' }
. $dbg

$script:passed = 0
function Assert-DbgTest($Condition, $Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
}

$script:DbgLogMinutes = 60
$script:DbgRole = 'wear'
$script:DbgHistoryLines = 30
$script:DbgStageContext = 0
$script:DbgLoggers = ''

# --- G6 logger parse ---
$script:DbgRole = 'wear'
$script:DbgLoggers = ''
$wear = @(Resolve-DebugLoggers)
Assert-DbgTest (($wear -contains 'com.example.lms.assist=DEBUG') -and ($wear -contains 'com.example.lms.search=DEBUG')) 'wear default includes assist+search DEBUG'

$script:DbgLoggers = 'com.example.lms.assist=DEBUG;rm -rf=DEBUG;com.example.lms.search=TRACE'
$parsed = @(Resolve-DebugLoggers)
Assert-DbgTest ($parsed -contains 'com.example.lms.assist=DEBUG') 'valid logger kept'
Assert-DbgTest ($parsed -notcontains 'rm -rf=DEBUG') 'invalid logger dropped'
Assert-DbgTest ($parsed -contains 'com.example.lms.search=TRACE') 'TRACE allowed'

$many = (1..9 | ForEach-Object { "com.example.lms.p$_=DEBUG" }) -join ';'
$script:DbgLoggers = $many
Assert-DbgTest (@(Resolve-DebugLoggers).Count -eq 8) 'logger cap is 8'

# --- G7 retention glob ---
Assert-DbgTest ('wear-20260919-182758-status.json' -match $script:DebugRetentionPattern) 'role-stamp files retained'
Assert-DbgTest ('wear-tail-20260919-154612.log' -match $script:DebugRetentionPattern) 'legacy wear-tail files retained'
Assert-DbgTest ('wear-20260919-154612-tail.log' -match $script:DebugRetentionPattern) 'new role-stamp-tail files retained'
Assert-DbgTest ('threads-wear-20260919-152154.txt' -match $script:DebugRetentionPattern) 'thread dumps retained'
Assert-DbgTest ('notes.txt' -notmatch $script:DebugRetentionPattern) 'unrelated files not retained'

# --- G1 stage markers on a fixture log ---
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('awx-debug-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $log = Join-Path $fixture 'out.log'
    $ts = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss.fff') + '+0900'
    $old = (Get-Date).AddHours(-5).ToString('yyyy-MM-ddTHH:mm:ss.fff') + '+0900'
    @(
        "$old INFO  [ sid ] c.e.l.a.DisplayConversateController - display.conversate inputPath=glasses_input decision=x"
        "$ts INFO  [ sid ] c.e.l.a.DisplayConversateController - display.conversate state=RUNNING reason=started"
        "$ts INFO  [ sid ] c.e.lms.assist.ConversateSttRouting - stt.usage provider=soniox attempts=1"
        "$ts INFO  [ sid ] c.e.l.a.ConversateApiCueService - conversate.cue {cueDecision=NO_CUE,decisionReason=SMALL_TALK,hintGenerated=false,ragNeeded=false}"
        "$ts INFO  [ sid ] c.e.l.a.ConversateApiCueService - conversate.cue {cueDecision=RAG_CUE,decisionReason=EVIDENCE,hintGenerated=true,ragNeeded=true}"
        "$ts WARN  [ sid ] c.e.l.search.Hybrid - [Hybrid] Brave primary failed"
        "$ts INFO  [ sid ] c.e.l.a.DisplayRuntimeDiagnostics - display.runtime stage=http_error event=http_error path=/api/assist/display/relay/diagnostics code=http_403"
        "$ts INFO  [ sid ] c.e.l.a.DisplayRuntimeDiagnostics - display.runtime stage=poll event=ok path=/api/assist/display/relay/poll"
    ) | Set-Content -LiteralPath $log -Encoding UTF8

    $script:DbgRole = 'wear'
    $stages = @(Get-DebugStageSummary -LogPath $log -ForRole 'wear')
    $by = @{}
    foreach ($s in $stages) { $by[$s.stage] = $s }

    Assert-DbgTest ($by['transcript-input'].count -eq 1) 'reason=started is not transcript-input'
    Assert-DbgTest ($by['cue-gate'].count -eq 2) 'cue-gate counts all conversate.cue'
    Assert-DbgTest ($by['cue-gate'].counts.noCue -eq 1 -and $by['cue-gate'].counts.ragCue -eq 1) 'cue-gate splits NO_CUE vs RAG_CUE'
    Assert-DbgTest ($by['hint-generate'].count -eq 1) 'NO_CUE is not hint-generate'
    Assert-DbgTest ($by['search-routing'].count -eq 1) 'Hybrid is search-routing'
    Assert-DbgTest ($by['display-delivery'].count -eq 1) 'http_error is not display-delivery'
    Assert-DbgTest ($by['errors'].count -eq 1) 'http_error is errors'

    $oldOnly = @(Get-DebugLogWindow -LogPath $log)
    Assert-DbgTest (-not ($oldOnly | Where-Object { $_ -match 'glasses_input' })) 'log window drops lines older than LogMinutes'

    $script:DbgPattern = 'HINTMARK'
    $hintLog = Join-Path $fixture 'hint.log'
    @(
        "$ts INFO hint line HINTMARK present"
        "$ts INFO other line"
    ) | Set-Content -LiteralPath $hintLog -Encoding UTF8
    $hist = @(Get-DebugLogWindow -LogPath $hintLog | Where-Object { $_ -match 'HINTMARK' })
    Assert-DbgTest ($hist.Count -ge 1) 'tail history can see past Pattern matches'

    $row = [pscustomobject]@{ manifest = [pscustomobject]@{ logOut = '' } }
    Assert-DbgTest ($null -eq (Get-DebugLogPath -RuntimeRow $row)) 'missing manifest log does not fall back to latest-file-unproven'

    $script:DbgDryRun = $true
    $script:DbgRole = 'wear'
    $script:DbgLoggers = ''
    $script:DbgOpenBrowser = $false
    $code = Invoke-DebugStart
    Assert-DbgTest ($code -eq 0 -and $script:Report.status -eq 'dry-run') 'DryRun start does not launch a process'
    $script:DbgDryRun = $false

    # --- verify: exception scan classes on a fixture log ---
    $vlog = Join-Path $fixture 'verify-out.log'
    @(
        "$ts INFO  [ sid ] c.e.l.a.DisplayRuntimeDiagnostics - display.runtime stage=poll event=ok"
        "$ts ERROR [ sid ] c.e.l.a.SomeService - APPLICATION FAILED TO START"
        "$ts ERROR [ sid ] c.e.l.a.SomeService - Caused by: java.lang.IllegalArgumentException: Could not resolve placeholder 'awx.missing' in value"
        "$ts ERROR [ sid ] o.s.b.web.embedded.tomcat - Port 18180 was already in use"
        "$ts WARN  [ sid ] c.e.l.search.Hybrid - [Hybrid] Brave primary failed"
        "$ts INFO  [ sid ] c.e.l.a.ErrorMapper - mapper loaded"
    ) | Set-Content -LiteralPath $vlog -Encoding UTF8

    $scan = Get-DebugExceptionScan -LogPath $vlog
    $cls = @{}
    foreach ($c in @($scan.classes)) { $cls[$c.class] = $c }
    Assert-DbgTest ($cls['spring-fatal'].count -eq 1) 'scan catches APPLICATION FAILED TO START'
    Assert-DbgTest ($cls['config'].count -eq 1) 'scan catches placeholder resolution failure'
    Assert-DbgTest ($cls['port-bind'].count -eq 1) 'scan catches port-in-use line'
    Assert-DbgTest ($cls['error-level'].count -eq 3) 'error-level counts ERROR log lines'
    Assert-DbgTest ($cls['exception-lines'].count -eq 1) 'exception-lines matches Caused by exception'
    Assert-DbgTest ($cls['spring-fatal'].fatal -eq $true -and $cls['error-level'].fatal -eq $false) 'fatal flags split hard vs soft classes'
    Assert-DbgTest ($cls['config'].firstHit -match 'Could not resolve placeholder' -and $cls['config'].firstFromEnd -gt 0) 'firstHit gives cause text + window position'
    Assert-DbgTest ($cls['exception-lines'].firstHit -notmatch 'ErrorMapper') 'logger name ErrorMapper is not an exception line'

    $scanMissing = Get-DebugExceptionScan -LogPath (Join-Path $fixture 'no-such.log')
    Assert-DbgTest ((@($scanMissing.classes)[0].note) -eq 'log-unavailable') 'missing log degrades to log-unavailable'

    # --- verify: http probe plans per role ---
    $script:DbgRole = 'dev'
    $planDev = @(Get-DebugVerifyHttpPlan -Port 18180)
    $namesDev = @($planDev | ForEach-Object { $_.name })
    Assert-DbgTest ($namesDev -contains 'home' -and $namesDev -contains 'mgmt-health' -and $namesDev -contains 'chat-sync-validation') 'dev plan covers home+health+sync validation'
    Assert-DbgTest (($planDev | Where-Object { $_.name -eq 'chat-sync-validation' }).statuses -contains 400) 'sync validation expects 400 (no spend)'
    Assert-DbgTest (($planDev | Where-Object { $_.name -eq 'home' }).statuses -contains 302) 'home accepts redirect'

    $script:DbgRole = 'wear'
    $planWear = @(Get-DebugVerifyHttpPlan -Port 18180)
    $namesWear = @($planWear | ForEach-Object { $_.name })
    Assert-DbgTest ($namesWear -contains 'display-page' -and $namesWear -contains 'receiver-js' -and $namesWear -contains 'display-bootstrap-validation') 'wear plan covers display probes'

    # --- verify: Invoke-DebugVerify with stubbed runtime surfaces ---
    $cleanLog = Join-Path $fixture 'verify-clean.log'
    @( "$ts INFO  [ sid ] c.e.l.a.DisplayRuntimeDiagnostics - display.runtime stage=poll event=ok" ) | Set-Content -LiteralPath $cleanLog -Encoding UTF8

    function Get-DebugPortMap { return @([pscustomobject]@{ port = 18180; ownerPid = 4242; ownerName = 'java.exe'; ownerNote = '' }, [pscustomobject]@{ port = 18181; ownerPid = 4242; ownerName = 'java.exe'; ownerNote = '' }, [pscustomobject]@{ port = 18182; ownerPid = 0; ownerName = ''; ownerNote = '' }) }
    function Get-DebugWatcherRows { return [pscustomobject]@{ watchers = @(); state = $null; logTail = @() } }
    function Test-RagOllama { param($Port) return $true }
    function Get-RagHttp { param($Url, $Method = 'GET', $Body = '', $Headers = @{}) if ($Method -eq 'POST') { return @{ status = 400; content = '{}'; contentType = 'application/json' } } return @{ status = 200; content = '/js/chat.js display-core.js "status":"UP"'; contentType = 'text/html' } }
    function Get-DebugFreshness { param($RuntimeCreationUtc, $Port) return [pscustomobject]@{ sourcesNewer = 'false'; newestSource = 'main/java/x.java'; newestSourceWriteUtc = ''; servedAsset = [pscustomobject]@{ match = 'not_observed' } } }

    $fakeRt = [pscustomobject]@{
        processId = 4242; role = 'dev'; metaDisplay = $true; port = 18180
        creationDate = '2026-09-23T00:00:00.0000000Z'; loggingLevels = @(); verboseApplied = $false; cmdlineObserved = $true
        ownership = [pscustomobject]@{ ok = $true; reason = 'manifest-match'; launcherPid = 1; listenerPid = 4242 }
        manifest = [pscustomobject]@{ present = $true; runtimeRole = 'dev'; runIdHash = 'x'; runDir = ''; logOut = ''; logErr = ''; startedAt = ''; readyAt = ''; ports = @(18180, 18181) }
    }

    # A) runtime up + clean log -> verified exit 0
    function Get-DebugRuntimes { return @($fakeRt) }
    function Get-DebugLogPath { param($RuntimeRow) return @{ path = $cleanLog; source = 'fixture' } }
    $script:DbgRole = 'dev'; $script:DbgWithCompile = $false
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 0 -and $script:Report.status -eq 'verified') 'verify passes when all checks green'
    Assert-DbgTest (@($script:Report.verify.failedChecks).Count -eq 0) 'verified run has no failedChecks'
    Assert-DbgTest (@($script:Report.verify.checks | ForEach-Object { $_.name }) -contains 'http') 'verify records http check row'

    # B) fatal classes in out-log -> exit 6, exceptions first
    function Get-DebugLogPath { param($RuntimeRow) return @{ path = $vlog; source = 'fixture' } }
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 6) 'verify exit 6 on failed checks'
    Assert-DbgTest ($script:Report.verify.firstFailure -eq 'exceptions') 'fatal exception class is firstFailure'
    Assert-DbgTest (@($script:Report.verify.failedChecks) -contains 'config') 'config check follows log evidence'
    Assert-DbgTest ($script:Report.verify.hint -match 'out-log|log') 'verify emits actionable hint'

    # C) no runtime -> exit 3
    function Get-DebugRuntimes { return @() }
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 3 -and $script:Report.status -eq 'not-running') 'verify exit 3 when runtime down'

    # D) port conflict: 18180 owned by a foreign pid -> exit 6 firstFailure=ports
    function Get-DebugRuntimes { return @($fakeRt) }
    function Get-DebugLogPath { param($RuntimeRow) return @{ path = $cleanLog; source = 'fixture' } }
    function Get-DebugPortMap { return @([pscustomobject]@{ port = 18180; ownerPid = 9999; ownerName = 'other.exe'; ownerNote = '' }, [pscustomobject]@{ port = 18181; ownerPid = 4242; ownerName = 'java.exe'; ownerNote = '' }, [pscustomobject]@{ port = 18182; ownerPid = 0; ownerName = ''; ownerNote = '' }) }
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 6 -and $script:Report.verify.firstFailure -eq 'ports') 'foreign port owner fails ports check first'

    # E) freshness stale-candidate -> exit 6 firstFailure=freshness
    function Get-DebugPortMap { return @([pscustomobject]@{ port = 18180; ownerPid = 4242; ownerName = 'java.exe'; ownerNote = '' }, [pscustomobject]@{ port = 18181; ownerPid = 4242; ownerName = 'java.exe'; ownerNote = '' }, [pscustomobject]@{ port = 18182; ownerPid = 0; ownerName = ''; ownerNote = '' }) }
    function Get-DebugFreshness { param($RuntimeCreationUtc, $Port) return [pscustomobject]@{ sourcesNewer = 'true-stale-candidate'; newestSource = 'main/java/com/example/lms/assist/X.java'; newestSourceWriteUtc = '2026-09-23T01:00:00Z'; servedAsset = [pscustomobject]@{ match = 'not_observed' } } }
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 6 -and $script:Report.verify.firstFailure -eq 'freshness') 'stale source vs runtime fails freshness'

    # F) schema v2 on a fully green run: run/verdict/Boolean ok, verdict block,
    #    compile reported as skipped coverage (never a silent pass).
    function Get-DebugFreshness { param($RuntimeCreationUtc, $Port) return [pscustomobject]@{ sourcesNewer = 'false'; newestSource = 'main/java/x.java'; newestSourceWriteUtc = ''; servedAsset = [pscustomobject]@{ match = 'not_observed' } } }
    function Get-DebugWatcherRows { return [pscustomobject]@{ watchers = @(); state = [pscustomobject]@{ status = 'ok'; lastTier = 't'; updatedAt = 'x'; failStreak = 0 }; logTail = @() } }
    $script:DbgWithCompile = $false
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 0) 'green run still exits 0'
    Assert-DbgTest ($script:Report.verify.schema -eq 'awx.debug.verify.v2') 'verify report is schema v2'
    $rows = @($script:Report.verify.checks)
    Assert-DbgTest (-not (@($rows | Where-Object { $_.ok -isnot [bool] }).Count -gt 0)) 'check ok is a real Boolean (string ''fail'' would be truthy)'
    Assert-DbgTest (-not (@($rows | Where-Object { $_.verdict -notin @('pass','warn','fail') }).Count -gt 0)) 'every check has a tri-state verdict'
    Assert-DbgTest (-not (@($rows | Where-Object { $_.run -notin @('executed','skipped','blocked','not_observed') }).Count -gt 0)) 'every check has a run state'
    $comp = @($rows | Where-Object { $_.name -eq 'compile' })[0]
    Assert-DbgTest ($comp.run -eq 'skipped' -and $comp.status -eq 'not-requested') 'compile without -WithCompile is skipped coverage, not a pass'
    Assert-DbgTest (@($script:Report.verify.notRun) -contains 'compile:skipped:not-requested') 'notRun names skipped checks'
    Assert-DbgTest ($script:Report.verify.verdict.tool -eq 'executed' -and $script:Report.verify.verdict.scope -eq 'live-readonly' -and $script:Report.verify.verdict.build -eq 'not-run' -and $script:Report.verify.verdict.tests -eq 'not-wired') 'verdict separates tool/scope/build/tests'
    Assert-DbgTest ($script:Report.verify.verdict.fullVerification -eq $false) 'fullVerification is never claimed by this action'
    Assert-DbgTest ($script:Report.verify.verdict.target -eq 'verified') 'fully observed green run -> target verified'

    # G) auth-blocked probes: 401/403 is access denial, not DOWN evidence.
    function Get-RagHttp { param($Url, $Method = 'GET', $Body = '', $Headers = @{}) return @{ status = 401; content = ''; contentType = 'text/html' } }
    $code = Invoke-DebugVerify
    Assert-DbgTest ($code -eq 0) '401 probes do not fail the target'
    $http = @($script:Report.verify.checks | Where-Object { $_.name -eq 'http' })[0]
    Assert-DbgTest ($http.status -eq 'auth-blocked' -and $http.verdict -eq 'warn') 'auth-blocked classified as warn, not fail'
    Assert-DbgTest ($script:Report.verify.verdict.target -eq 'partial') 'auth-blocked run -> target partial'

    # H) missing log -> exceptions is not_observed, never a fabricated clean.
    function Get-RagHttp { param($Url, $Method = 'GET', $Body = '', $Headers = @{}) if ($Method -eq 'POST') { return @{ status = 400; content = '{}'; contentType = 'application/json' } } return @{ status = 200; content = '/js/chat.js display-core.js "status":"UP"'; contentType = 'text/html' } }
    function Get-DebugLogPath { param($RuntimeRow) return @{ path = (Join-Path $fixture 'no-such-verify.log'); source = 'fixture' } }
    $code = Invoke-DebugVerify
    $ex = @($script:Report.verify.checks | Where-Object { $_.name -eq 'exceptions' })[0]
    Assert-DbgTest ($ex.run -eq 'not_observed' -and $ex.verdict -eq 'warn') 'missing log -> exceptions not_observed'
    function Get-DebugLogPath { param($RuntimeRow) return @{ path = $cleanLog; source = 'fixture' } }

    # I) -JsonStdout process boundary: exactly one JSON document on stdout.
    $psExe = (Get-Process -Id $PID).Path
    $jsonOut = & $psExe -NoProfile -ExecutionPolicy Bypass -File $dbg -Role dev -Action verify -JsonStdout 2>$null
    $jsonExit = $LASTEXITCODE
    Assert-DbgTest ($jsonExit -in @(0, 3, 6)) 'verify -JsonStdout preserves exit code'
    $doc = $null; $parseOk = $false
    try { $doc = ($jsonOut | Out-String).Trim() | ConvertFrom-Json; $parseOk = $true } catch { $parseOk = $false }
    Assert-DbgTest $parseOk 'stdout parses as a single JSON document'
    Assert-DbgTest ($null -ne $doc.verify.verdict -and $doc.verify.schema -eq 'awx.debug.verify.v2') 'stdout JSON carries the v2 verdict block'
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ("PASS {0} debug_rag_stack contract tests" -f $script:passed)

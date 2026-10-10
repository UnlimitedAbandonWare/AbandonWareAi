# dev_reload_watch_tests.ps1 — contract tests for the DevWatch reload router.
# Dot-sources dev_reload_watch.ps1 (its watch loop is guarded by InvocationName)
# and stubs OS/process boundaries, mirroring start_rag_stack_tests.ps1 style.
# Read-only on sources; fixture dirs are created under TEMP and removed.

$ErrorActionPreference = 'Stop'
$watch = Join-Path $PSScriptRoot 'dev_reload_watch.ps1'
if (-not (Test-Path -LiteralPath $watch -PathType Leaf)) { throw 'FAIL: dev reload watcher is missing' }
. $watch

$script:passed = 0
function Assert-WatchTest($Condition, $Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
}

# --- Test-DevWearRuntime mirrors the launcher's own role/port evidence ---
$script:candidates = @()
$script:portOwners = @{}
$script:ownerRole = 'dev'
function Get-RagSpringCandidates { return $script:candidates }
function Get-RagPortOwner { param($Port) return $script:portOwners[$Port] }
function Get-RagProcessRole { param($ProcessId, $CommandLine = '') return $script:ownerRole }

Assert-WatchTest (-not (Test-DevWearRuntime)) 'empty runtime is not wear'

$script:candidates = @([pscustomobject]@{processId = 11; port = 18180; metaDisplay = $true; role = 'dev'})
Assert-WatchTest (-not (Test-DevWearRuntime)) 'dev meta-display runtime is not wear'

$script:candidates = @([pscustomobject]@{processId = 12; port = 0; metaDisplay = $true; role = 'wear'})
Assert-WatchTest (Test-DevWearRuntime) 'starting wear candidate (not yet listening) still defers restart'

$script:candidates = @()
$script:portOwners = @{ 18181 = [pscustomobject]@{ processId = 13; processName = 'java.exe' } }
$script:ownerRole = 'wear'
Assert-WatchTest (Test-DevWearRuntime) 'wear fixed-port owner defers restart'
$script:ownerRole = 'dev'
Assert-WatchTest (-not (Test-DevWearRuntime)) 'non-wear fixed-port owner does not defer'
$script:portOwners = @{}

# --- Get-DevLatestLauncherReason reads only a fresh launcher result.json ---
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('devwatch-test-' + [guid]::NewGuid().ToString('N'))
$runDir = Join-Path $fixture 'var\rag-launcher\run1'
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$resultPath = Join-Path $runDir 'result.json'
'{"ok":false,"status":"failed","stage":"PREFLIGHT","reason":"meta-display-wear-runtime-protected"}' |
    Set-Content -LiteralPath $resultPath -Encoding UTF8

$priorRoot = $script:RagRoot
$script:RagRoot = $fixture
try {
    $info = Get-DevLatestLauncherReason
    Assert-WatchTest ($null -ne $info -and $info.reason -eq 'meta-display-wear-runtime-protected') 'fresh launcher result carries refusal reason'

    (Get-Item -LiteralPath $resultPath).LastWriteTime = (Get-Date).AddMinutes(-10)
    Assert-WatchTest ($null -eq (Get-DevLatestLauncherReason)) 'stale launcher result is not attributed to this restart'
    (Get-Item -LiteralPath $resultPath).LastWriteTime = Get-Date
} finally {
    $script:RagRoot = $priorRoot
}

# Log/state capture must be stubbed before any function under test writes to the
# real dev-reload log or watch.state.json.
$script:logged = [Collections.Generic.List[string]]::new()
$script:lastState = $null
function Write-DevReload([string]$Message) { $script:logged.Add($Message) }
function Save-DevReloadState([hashtable]$Data) { $script:lastState = $Data }

# --- Invoke-DevSpringRestart enriches a refused exit with the launcher reason ---
$script:proc = [pscustomobject]@{ ExitCode = 1; HasExited = $true }
$script:processArgs = @()
function Start-Process {
    param($FilePath, $ArgumentList, $WorkingDirectory, [switch]$Wait, [switch]$PassThru, [switch]$NoNewWindow,
          $RedirectStandardOutput, $RedirectStandardError, $WindowStyle)
    $script:processArgs = @($ArgumentList)
    return $script:proc
}

$script:proc.ExitCode = 0
Invoke-DevCompile
Assert-WatchTest ($script:processArgs -contains '-Pawx.splitBuildOutputs=true') 'preflight compile uses split outputs'
Assert-WatchTest ($script:processArgs -contains '-Pawx.buildHostId=desktop-devwatch-verify') 'preflight compile cannot rewrite the live runtime output'
$script:proc.ExitCode = 1

$script:RagRoot = $fixture
try {
    try { Invoke-DevSpringRestart; throw 'unexpected-success' }
    catch {
        Assert-WatchTest ($_.Exception.Message -eq 'spring-restart-failed exit=1 reason=meta-display-wear-runtime-protected stage=PREFLIGHT') 'restart failure carries launcher reason and stage'
    }
    (Get-Item -LiteralPath $resultPath).LastWriteTime = (Get-Date).AddMinutes(-10)
    try { Invoke-DevSpringRestart; throw 'unexpected-success' }
    catch {
        Assert-WatchTest ($_.Exception.Message -eq 'spring-restart-failed exit=1') 'opaque exit stays opaque without fresh evidence'
    }
    (Get-Item -LiteralPath $resultPath).LastWriteTime = Get-Date
} finally {
    $script:RagRoot = $priorRoot
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}

# --- Invoke-DevReloadCycle classification: protected refusals defer, real faults count ---
$script:restartError = ''
$script:restartCalled = $false
$script:compileCalls = 0
function Invoke-DevCompile { $script:compileCalls++ }
function Test-DevWearRuntime { return $script:wearPresent }
function Invoke-DevSpringRestart { $script:restartCalled = $true; throw $script:restartError }

# wear runtime present → early skip, restart never attempted
$script:wearPresent = $true
$script:restartCalled = $false
Invoke-DevReloadCycle @('main\java\X.java')
Assert-WatchTest ($script:compileCalls -eq 0) 'wear protection runs before any compile'
Assert-WatchTest (-not $script:restartCalled) 'wear runtime skips the restart call entirely'
Assert-WatchTest (($script:logged -join "`n") -match 'wear runtime holds 18180') 'wear skip is logged as protection, not failure'
Assert-WatchTest ($script:FailStreak -eq 0) 'wear skip does not burn fail streak'

# wear check misses but the launcher refuses → deferred, still not a failure
$script:wearPresent = $false
$script:restartError = 'spring-restart-failed exit=1 reason=meta-display-wear-runtime-protected stage=PREFLIGHT'
Invoke-DevReloadCycle @('main\java\X.java')
Assert-WatchTest ($script:FailStreak -eq 0) 'wear-protected refusal defers without fail streak'
Assert-WatchTest (($script:logged -join "`n") -match 'restart deferred \(meta-display-wear-runtime-protected\)') 'deferred restart logged with launcher reason'
Assert-WatchTest ($script:lastState.status -eq 'deferred') 'deferred status recorded in state file'

# launcher busy → same deferral class
$script:restartError = 'spring-restart-failed exit=1 reason=launcher-already-running stage=PREFLIGHT'
Invoke-DevReloadCycle @('main\java\X.java')
Assert-WatchTest ($script:FailStreak -eq 0) 'launcher-already-running defers without fail streak'

# real conflict still counts as a failure
$script:restartError = 'spring-restart-failed exit=1 reason=meta-display-port-conflict stage=PREFLIGHT'
Invoke-DevReloadCycle @('main\java\X.java')
Assert-WatchTest ($script:FailStreak -eq 1) 'real port conflict still counts toward fail streak'
Assert-WatchTest (($script:logged -join "`n") -match 'FAILED: spring-restart-failed') 'real failure keeps FAILED log line'
Assert-WatchTest ($script:lastState.status -eq 'failed') 'failed status recorded in state file'

# compile failure stays a real failure too
$script:FailStreak = 0
function Invoke-DevCompile { throw 'compile-failed exit=1' }
Invoke-DevReloadCycle @('main\java\X.java')
Assert-WatchTest ($script:FailStreak -eq 1) 'compile failure still counts toward fail streak'

# --- live-test window marker (var\live-test\active.json, live_test_window.py) ---
# Active marker defers the whole cycle before compile; expiry/absence restores.
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('devwatch-livetest-' + [guid]::NewGuid().ToString('N'))
$markerDir = Join-Path $fixture 'var\live-test'
New-Item -ItemType Directory -Path $markerDir -Force | Out-Null
$markerPath = Join-Path $markerDir 'active.json'
$priorRoot = $script:RagRoot
$script:RagRoot = $fixture
try {
    $script:wearPresent = $false
    $script:compileCalls = 0
    $script:restartCalled = $false
    $script:FailStreak = 0
    function Invoke-DevCompile { $script:compileCalls++ }

    # active marker → compile and restart are both skipped as a named deferral
    @{ untilKst = (Get-Date).AddHours(1).ToUniversalTime().ToString('o') } | ConvertTo-Json -Compress | Set-Content -LiteralPath $markerPath -Encoding UTF8
    Invoke-DevReloadCycle @('main\java\X.java')
    Assert-WatchTest ($script:compileCalls -eq 0) 'live test window skips compile entirely'
    Assert-WatchTest (-not $script:restartCalled) 'live test window skips restart entirely'
    Assert-WatchTest ($script:FailStreak -eq 0) 'live test window defer does not burn fail streak'
    Assert-WatchTest ($script:lastState.status -eq 'deferred' -and $script:lastState.reason -eq 'live-test-window-active') 'live test deferral recorded in state file'
    Assert-WatchTest (($script:logged -join "`n") -match 'live-test-window-active') 'live test deferral names its reason'

    # expired marker → the normal restart path runs again
    @{ untilKst = (Get-Date).AddMinutes(-1).ToUniversalTime().ToString('o') } | ConvertTo-Json -Compress | Set-Content -LiteralPath $markerPath -Encoding UTF8
    $script:restartError = 'spring-restart-failed exit=1 reason=launcher-already-running stage=PREFLIGHT'
    Invoke-DevReloadCycle @('main\java\X.java')
    Assert-WatchTest ($script:restartCalled) 'expired marker restores the normal restart path'
    Assert-WatchTest ($script:FailStreak -eq 0) 'launcher-already-running still defers after expiry'

    # launcher refusal carrying the live-test reason also defers without fail streak
    $script:restartCalled = $false
    $script:restartError = 'spring-restart-failed exit=1 reason=live-test-window-active stage=PREFLIGHT'
    Invoke-DevReloadCycle @('main\java\X.java')
    Assert-WatchTest ($script:FailStreak -eq 0) 'live-test-window-active refusal defers without fail streak'
    Assert-WatchTest ($script:lastState.status -eq 'deferred' -and $script:lastState.reason -eq 'live-test-window-active') 'live-test refusal stays a deferral, not a failure'

    # no marker → the normal failure classification resumes
    Remove-Item -LiteralPath $markerPath -Force
    $script:restartError = 'spring-restart-failed exit=1 reason=meta-display-port-conflict stage=PREFLIGHT'
    Invoke-DevReloadCycle @('main\java\X.java')
    Assert-WatchTest ($script:FailStreak -eq 1) 'absent marker keeps the normal failure path'
} finally {
    $script:RagRoot = $priorRoot
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ("PASS: {0} dev reload watcher contract checks" -f $script:passed)

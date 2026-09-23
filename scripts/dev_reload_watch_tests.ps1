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
function Start-Process {
    param($FilePath, $ArgumentList, $WorkingDirectory, [switch]$Wait, [switch]$PassThru, [switch]$NoNewWindow,
          $RedirectStandardOutput, $RedirectStandardError, $WindowStyle)
    return $script:proc
}

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
function Invoke-DevCompile { }
function Test-DevWearRuntime { return $script:wearPresent }
function Invoke-DevSpringRestart { $script:restartCalled = $true; throw $script:restartError }

# wear runtime present → early skip, restart never attempted
$script:wearPresent = $true
$script:restartCalled = $false
Invoke-DevReloadCycle @('main\java\X.java')
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

Write-Host ("PASS: {0} dev reload watcher contract checks" -f $script:passed)

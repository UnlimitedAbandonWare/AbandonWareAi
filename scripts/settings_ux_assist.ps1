# settings_ux_assist.ps1 — Codex settings-UX patch assist runner (Devin lane, read-only)
#
#   -Mode baseline : copy the settings target files + write baseline.json +
#                    settings_routing_guard.py --snapshot  (pre/post Codex diff anchor)
#   -Mode check    : run existing guard/invariant/test-matrix tools plus the
#                    Devin contrast + acceptance checkers plus node
#                    settings-*.test.cjs; merge results into
#                    var\settings-ux-assist\check-<ts>.json
#   -Mode live     : settings_page_probe.py --local  (127.0.0.1:18180 only;
#                    public host and chat sends = 0). Use only after the Codex
#                    final report.
#   -DryRun        : print the commands that would run; run nothing
#   -BaselinePath  : baseline.json for the acceptance check
#                    (default: newest data/agent-handoff/codex-autonomy/
#                     devin-settings-ux-assist-*/baseline.json, then
#                     var/settings-ux-assist/baseline-*/baseline.json)
#
# Never: server restart, Gradle, save/PATCH API calls, public host, chat sends.
# Child python calls are forced to UTF-8 (cp949 console would break argparse
# help and JSON output of some tools).

[CmdletBinding()]
param(
    [ValidateSet('baseline', 'check', 'live')]
    [string]$Mode = 'check',
    [switch]$DryRun,
    [string]$BaselinePath = '',
    [string]$Root = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'
$env:PYTHONUTF8 = '1'

if (-not $Root) {
    $scriptDir = Split-Path $MyInvocation.MyCommand.Path -Parent
    if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
    $Root = (Resolve-Path (Join-Path $scriptDir '..')).Path
}

$outDir = Join-Path $Root 'var\settings-ux-assist'
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$results = New-Object System.Collections.Generic.List[object]

$script:baselineTargets = @(
    'main/resources/static/js/settings-page.js',
    'main/resources/static/js/settings-routing.js',
    'main/resources/static/js/chat-settings-bridge.js',
    'main/resources/templates/settings.html',
    'main/resources/static/css/settings-page.css',
    'main/resources/static/js/chat-model-picker.js',
    'main/resources/templates/chat-ui.html',
    'main/resources/static/js/chat.js',
    'main/java/com/example/lms/api/RoutingSettingsController.java',
    'main/java/com/example/lms/config/AppSecurityConfig.java'
)

function Invoke-Step([string]$name, [string[]]$cmdline, [string]$logPath) {
    $display = $cmdline -join ' '
    if ($DryRun) {
        Write-Host "[DryRun] $name : $display"
        return [pscustomobject]@{ name = $name; cmd = $display; exit = $null; log = $null; dryRun = $true }
    }
    Write-Host "[run] $name : $display"
    $exe = $cmdline[0]
    $rest = @($cmdline | Select-Object -Skip 1)
    Push-Location $Root
    try {
        & $exe @rest *> $logPath
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    Write-Host ("  -> exit {0} (log: {1})" -f $code, $logPath)
    return [pscustomobject]@{ name = $name; cmd = $display; exit = $code; log = $logPath; dryRun = $false }
}

function Resolve-Baseline([string]$explicit, [string]$root) {
    if ($explicit) { return (Resolve-Path $explicit -ErrorAction Stop).Path }
    $candidates = @()
    $candidates += Get-ChildItem -Path (Join-Path $root 'data\agent-handoff\codex-autonomy') `
        -Filter 'baseline.json' -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'devin-settings-ux-assist' }
    $candidates += Get-ChildItem -Path (Join-Path $root 'var\settings-ux-assist') `
        -Filter 'baseline.json' -Recurse -ErrorAction SilentlyContinue
    $pick = $candidates | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($pick) { return $pick.FullName }
    return $null
}

if ($Mode -eq 'baseline') {
    $dest = Join-Path $outDir "baseline-$ts"
    if ($DryRun) {
        Write-Output ("[DryRun] would copy {0} files -> {1}" -f $script:baselineTargets.Count, $dest)
        Write-Output "[DryRun] python -B scripts/settings_routing_guard.py --snapshot"
        exit 0
    }
    New-Item -ItemType Directory -Force $dest | Out-Null
    $cutoff = [DateTimeOffset]::Parse('2026-10-03T08:37:00+09:00').UtcDateTime
    $rows = @()
    foreach ($t in $script:baselineTargets) {
        $p = Join-Path $Root ($t -replace '/', '\')
        if (-not (Test-Path $p)) {
            $rows += [pscustomobject]@{ path = $t; missing = $true }
            continue
        }
        $fi = Get-Item $p
        $h = (Get-FileHash -Algorithm SHA256 $p).Hash.ToLower()
        $name = ($t -split '/')[-1]
        Copy-Item $p (Join-Path $dest $name)
        $rows += [pscustomobject]@{
            path = $t; sha256 = $h; bytes = $fi.Length
            mtimeUtc = $fi.LastWriteTimeUtc.ToString('o')
            codexStarted = ($fi.LastWriteTimeUtc -gt $cutoff)
            baselineCopy = "baseline/$name"
        }
    }
    $doc = [pscustomobject]@{
        schemaVersion = 'devin.settings-ux-baseline.v1'
        takenAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        codexStartedCutoffUtc = $cutoff.ToString('o')
        files = $rows
    }
    $bjson = Join-Path $dest 'baseline.json'
    [System.IO.File]::WriteAllText($bjson, ($doc | ConvertTo-Json -Depth 5),
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "baseline.json -> $bjson"
    $log = Join-Path $outDir "baseline-$ts-guard-snapshot.log"
    $null = Invoke-Step 'guard-snapshot' @('python', '-B', 'scripts/settings_routing_guard.py', '--snapshot') $log
    exit 0
}

if ($Mode -eq 'live') {
    $log = Join-Path $outDir "live-$ts-probe.log"
    if (-not $DryRun) { New-Item -ItemType Directory -Force $outDir | Out-Null }
    $r = Invoke-Step 'settings-page-probe-local' @(
        'python', '-B', 'scripts/settings_page_probe.py', '--root', '.', '--local', '--json') $log
    if (-not $DryRun) {
        Write-Output "live probe log: $log (see 'verdict' field; server-down => record NOT_RUN)"
        if ($r.exit -ne 0) { Write-Output "live exit $($r.exit)" }
    }
    exit 0
}

# --- check mode -------------------------------------------------------------
if (-not $DryRun) { New-Item -ItemType Directory -Force $outDir | Out-Null }
$base = Resolve-Baseline $BaselinePath $Root
if (-not $base) {
    Write-Output 'WARN: no baseline.json found - acceptance C2/C5/C8 will report PENDING'
    Write-Output '      (pass -BaselinePath or run -Mode baseline first)'
}

$results.Add((Invoke-Step 'routing-guard-check' @(
    'python', '-B', 'scripts/settings_routing_guard.py', '--check', '--json', '--no-live'
) (Join-Path $outDir "check-$ts-routing-guard.log")))

$results.Add((Invoke-Step 'routing-invariants' @(
    'python', '-B', 'scripts/check_settings_routing_invariants.py', '--root', '.'
) (Join-Path $outDir "check-$ts-invariants.log")))

$results.Add((Invoke-Step 'test-matrix' @(
    'python', '-B', 'scripts/settings_test_matrix_check.py', '--root', '.', '--json'
) (Join-Path $outDir "check-$ts-test-matrix.log")))

$results.Add((Invoke-Step 'contrast' @(
    'python', '-B', 'scripts/settings_ux_contrast_check.py', '--root', '.'
) (Join-Path $outDir "check-$ts-contrast.log")))

$accArgs = @('python', '-B', 'scripts/settings_ux_acceptance_check.py', '--root', '.')
if ($base) { $accArgs += @('--baseline', $base) }
$results.Add((Invoke-Step 'acceptance' $accArgs (Join-Path $outDir "check-$ts-acceptance.log")))

$nodeTests = Get-ChildItem -Path (Join-Path $Root 'src\test\js') -Filter 'settings-*.test.cjs' `
    -ErrorAction SilentlyContinue | Sort-Object Name
if ($nodeTests) {
    $nodeArgs = @('node', '--test') + @($nodeTests | ForEach-Object { 'src/test/js/' + $_.Name })
    $results.Add((Invoke-Step 'node-settings-tests' $nodeArgs (Join-Path $outDir "check-$ts-node.log")))
} else {
    $results.Add([pscustomobject]@{ name = 'node-settings-tests'; cmd = 'node --test src/test/js/settings-*.test.cjs'; exit = $null; log = $null; dryRun = $DryRun.IsPresent; skipped = 'no settings-*.test.cjs found' })
}

$merged = [pscustomobject]@{
    schemaVersion = 'devin.settings-ux-assist-check.v1'
    ranAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    mode = 'check'; dryRun = $DryRun.IsPresent
    baseline = $base
    steps = $results
    nodeTestsObserved = 'observed-midrun'   # Codex may still be editing; mid-run state
    note = 'tool exits are observations, not target-health verdicts'
}
$mergedPath = Join-Path $outDir "check-$ts.json"
if (-not $DryRun) {
    [System.IO.File]::WriteAllText($mergedPath, ($merged | ConvertTo-Json -Depth 6),
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "merged check -> $mergedPath"
    $fails = @($results | Where-Object { $_.exit -ne $null -and $_.exit -ne 0 })
    if ($fails) {
        Write-Output ("nonzero exits: " + (($fails | ForEach-Object { $_.name + '=' + $_.exit }) -join ', '))
    }
}
exit 0

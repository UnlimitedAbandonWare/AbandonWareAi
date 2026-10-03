#Requires -Version 5.1
# codex_plan9_verify.ps1 -- focused test runner for Codex plan9 (WP A-D).
#
# DEFAULT IS DRY-RUN: resolves --tests patterns to real classes under
# src/test/java, prints the gradlew commands, lists MISSING patterns/classes,
# writes var\codex-assist-plan9-20261002\verify-<timestamp>.json, exits 0.
# -RunGradle actually invokes gradlew (Codex's step; Devin never runs it so
# no Gradle lock contention). No full-suite or clean mode exists on purpose.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\codex_plan9_verify.ps1 -Wp All -DryRun
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\codex_plan9_verify.ps1 -Wp A -RunGradle
param(
    [ValidateSet('A','B','C','D','All')]
    [string]$Wp = 'All',
    [switch]$DryRun,
    [switch]$RunGradle,
    [string]$OutDir = 'var\codex-assist-plan9-20261002'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# WP -> glob patterns over src/test/java/**/<pattern>.java (Codex brief A6 set
# plus the G4 extend-map targets that the plain A6 globs miss).
$groups = [ordered]@{
    A = @('*DynamicChatModelFactory*', '*RoutingProfile*', '*ModelCatalog*')
    B = @('*FallbackAwareChatModel*', '*FallbackAwareReplaySafety*',
          '*LocalModelAdmission*', '*LlmGateway*', '*RoutingFallbackBoundary*',
          '*RoutingRunSnapshot*', 'RoleRoutingConsumptionTest')
    C = @()   # non-gradle: node --check + name-guess baseline compare
    D = @('*RoutingRunSnapshot*', '*RoutingFallbackBoundary*', '*LlmGateway*')
}
# staged G4 names that are expected MISSING until Codex creates the file
$stagedNew = @('LocalModelAdmissionApiFirstTest')

function Resolve-Pattern([string]$pattern) {
    $hits = Get-ChildItem -Recurse -Path 'src\test\java' -Filter "$pattern.java" -File
    $rows = @()
    foreach ($h in $hits) {
        $pkg = (Select-String -Path $h.FullName -Pattern '^\s*package\s+([\w.]+)\s*;' |
                Select-Object -First 1).Matches.Groups[1].Value
        $fqcn = if ($pkg) { "$pkg.$($h.BaseName)" } else { $h.BaseName }
        $rows += [pscustomobject]@{ status='FOUND'; class=$fqcn; file=$h.FullName }
    }
    if (-not $rows) { $rows += [pscustomobject]@{ status='MISSING'; class=$pattern; file=$null } }
    return $rows
}

$sel = if ($Wp -eq 'All') { @('A','B','C','D') } else { @([string]$Wp) }
$dry = -not $RunGradle
$log = New-Object System.Collections.Generic.List[object]
$missing = New-Object System.Collections.Generic.List[string]

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
if ($dry) { Write-Host "# codex_plan9_verify DRY-RUN wp=$($sel -join ',')" }

foreach ($g in $sel) {
    if ($g -eq 'C') {
        $cmds = @(
            'node --check main\resources\static\js\model-strategy.js',
            'python -B scripts\model_name_guess_scan.py --baseline var\codex-assist-plan9-20261002\name-guess-baseline.json --root main --format json'
        )
        foreach ($c in $cmds) {
            Write-Host "== WP-C  $c"
            $exit = $null; $status = 'NOT_RUN'
            if (-not $dry) { Invoke-Expression $c; $exit = $LASTEXITCODE
                           $status = if ($exit -eq 0) {'PASS'} else {'FAIL'} }
            $log.Add([pscustomobject]@{wp='C'; cmd=$c; exit=$exit; status=$status})
        }
        continue
    }
    $resolved = @(); foreach ($p in $groups[$g]) { $resolved += Resolve-Pattern $p }
    $found   = @($resolved | Where-Object status -eq 'FOUND')
    foreach ($r in ($resolved | Where-Object status -eq 'MISSING')) {
        $staged = @($stagedNew | Where-Object { $_ -like $r.class })
        $tag = if ($staged) { "STAGED(new - Codex creates: $($staged -join ','))" } else { 'MISSING' }
        $missing.Add("WP-$g $($r.class) [$tag]")
    }
    $fqcn = @($found | ForEach-Object class | Sort-Object -Unique)
    $cmd = $null
    if ($fqcn.Count) {
        $cmd = '.\gradlew.bat test ' + (($fqcn | ForEach-Object { "--tests `"$_`"" }) -join ' ')
    }
    Write-Host "== WP-$g  $($fqcn.Count) classes"
    foreach ($r in $resolved) { Write-Host ("   {0}  {1}" -f $r.status, $r.class) }
    if ($cmd) { Write-Host "   CMD: $cmd" }
    $exit = $null; $status = 'NOT_RUN'
    if (-not $dry -and $cmd) {
        & .\gradlew.bat test @($fqcn | ForEach-Object { '--tests'; $_ })
        $exit = $LASTEXITCODE
        $status = if ($exit -eq 0) {'PASS'} else {'FAIL'}
    }
    $log.Add([pscustomobject]@{wp=$g; cmd=$cmd; classes=$fqcn; exit=$exit; status=$status})
}

if ($missing.Count) {
    Write-Host ''; Write-Host 'MISSING / STAGED:'
    $missing | ForEach-Object { Write-Host "  $_" }
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logPath = Join-Path $OutDir "verify-$stamp.json"
$mode = if ($dry) { 'dryRun' } else { 'runGradle' }
[pscustomobject]@{ mode=$mode; wp=$sel; commands=$log; missing=$missing } |
    ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 $logPath
Write-Host "log: $logPath"
exit 0

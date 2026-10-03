<#
.SYNOPSIS
  Focused verification commands for Codex OAuth R5. Dry-run unless -Execute.

.DESCRIPTION
  Builds one Gradle test command per work package and writes
  var/codex-assist-20261001/verify-<timestamp>.json.
  Default mode prints the commands and classifies each test FOUND or MISSING.
  It does not run Gradle, clean, or the full suite.

  WP1 terminal metadata: LlmResponseTerminalException*, ResponsesTerminalStatusContractTest,
  TimedChatModelCallerUsageTest.
  WP2 Jev comment: the R5 directive names no focused test class.
  WP3 benefit split: ConfigurableProviderContractTest, RoutingBenefitLifecycleTest.
  WP4 complex OAuth route: AdaptiveRouteDecisionTest, ConversateCueRoutingPolicyTest.
  WP5 account binding: no separate class. T11 is inside RoutingBenefitLifecycleTest (WP3).
  WP6 transition continuity: RoutingTransitionContinuityTest.
  WP7 config continuity: RoutingConfigurationContinuityTest.

  -WithVerifyRag records Verify-RAG.bat. In dry-run that command stays NOT_RUN.
  With -Execute it runs Verify-RAG.bat once after the focused tests.

.PARAMETER Wp
  WP1 through WP7, or All.

.PARAMETER Execute
  Run the focused Gradle commands. Omit this switch for the default dry-run.

.PARAMETER DryRun
  Explicit dry-run. This is also the default when -Execute is omitted.

.PARAMETER WithVerifyRag
  Include one Verify-RAG.bat invocation.

.NOTES
  engine-slots verify-events compares observation records that a Codex test exports.
  From the extracted kit (var/codex-assist-20261001/kits/engine-slots/CODEX_ENGINE_SLOTS_R5):

    python -B tools/engine_slots.py verify-events --events <jsonl> --minimum APP_MOCK --out <report.json>

  Exit 0 means the exported rows match fixtures/cases.json at the APP_MOCK bar.
  fixtures/observations.synthetic.jsonl is a format drill. Passing --minimum SYNTHETIC
  checks shape only and is not application verification. Leaving the default minimum
  APP_MOCK on that synthetic file exits 3 (INSUFFICIENT_EVIDENCE), which is expected.
  Do not treat either synthetic result as a product pass.
#>
[CmdletBinding()]
param(
    [ValidateSet('WP1', 'WP2', 'WP3', 'WP4', 'WP5', 'WP6', 'WP7', 'All')]
    [string]$Wp = 'All',
    [switch]$Execute,
    [switch]$DryRun,
    [switch]$WithVerifyRag,
    [string]$Root = ''
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

if ($Execute -and $DryRun) {
    Write-Error 'Pass only one of -Execute or -DryRun.'
    exit 2
}
$runGradle = [bool]$Execute
if (-not $Root) {
    $Root = Split-Path -Parent $PSScriptRoot
    if (-not (Test-Path -LiteralPath (Join-Path $Root 'gradlew.bat'))) {
        $Root = (Get-Location).Path
    }
}
$Root = (Resolve-Path -LiteralPath $Root).Path

$groups = [ordered]@{
    WP1 = @('LlmResponseTerminalException*', 'ResponsesTerminalStatusContractTest', 'TimedChatModelCallerUsageTest')
    WP2 = @()
    WP3 = @('ConfigurableProviderContractTest', 'RoutingBenefitLifecycleTest')
    WP4 = @('AdaptiveRouteDecisionTest', 'ConversateCueRoutingPolicyTest')
    WP5 = @()
    WP6 = @('RoutingTransitionContinuityTest')
    WP7 = @('RoutingConfigurationContinuityTest')
}
$notes = @{
    WP1 = 'Terminal metadata and usage preservation.'
    WP2 = 'NO_NAMED_TEST. R5 WP2 is a Jev comment edit with no new test class.'
    WP3 = 'Benefit and provider contract tests named by the R5 directive.'
    WP4 = 'Adaptive route plus the existing cue-policy test.'
    WP5 = 'NO_NAMED_TEST. Account isolation T11 is described on RoutingBenefitLifecycleTest, listed under WP3.'
    WP6 = 'Transition continuity.'
    WP7 = 'Configuration continuity. Verify-RAG.bat is separate and only runs with -WithVerifyRag -Execute.'
}

function Get-TestIndex {
    param([string]$ProjectRoot)
    $testRoot = Join-Path $ProjectRoot 'src\test\java'
    $index = @{}
    if (-not (Test-Path -LiteralPath $testRoot)) {
        return $index
    }
    $classRx = [regex]'(?m)^(?:public\s+)?(?:final\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)\b'
    $pkgRx = [regex]'(?m)^package\s+([A-Za-z0-9_.]+)\s*;'
    Get-ChildItem -LiteralPath $testRoot -Filter *.java -Recurse -File | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        $pkg = ''
        $pkgMatch = $pkgRx.Match($text)
        if ($pkgMatch.Success) { $pkg = $pkgMatch.Groups[1].Value }
        foreach ($match in $classRx.Matches($text)) {
            $name = $match.Groups[1].Value
            $fqcn = if ($pkg) { "$pkg.$name" } else { $name }
            if (-not $index.ContainsKey($name)) { $index[$name] = New-Object System.Collections.Generic.List[string] }
            if (-not $index[$name].Contains($fqcn)) { [void]$index[$name].Add($fqcn) }
        }
    }
    return $index
}

function Resolve-Pattern {
    param($Index, [string]$Pattern)
    $names = @()
    if ($Pattern.EndsWith('*')) {
        $prefix = $Pattern.Substring(0, $Pattern.Length - 1)
        foreach ($key in $Index.Keys) {
            if ($key.StartsWith($prefix)) { $names += $key }
        }
    } elseif ($Index.ContainsKey($Pattern)) {
        $names = @($Pattern)
    }
    $fqcns = @()
    foreach ($name in ($names | Sort-Object -Unique)) {
        $fqcns += @($Index[$name])
    }
    [pscustomobject]@{
        pattern = $Pattern
        status  = $(if ($fqcns.Count -gt 0) { 'FOUND' } else { 'MISSING' })
        classes = @($fqcns)
    }
}

$index = Get-TestIndex -ProjectRoot $Root
$selected = @($groups.Keys)
if ($Wp -ne 'All') { $selected = @($Wp) }

$packages = @()
foreach ($name in $selected) {
    $resolved = @()
    foreach ($pattern in $groups[$name]) {
        $resolved += Resolve-Pattern -Index $index -Pattern $pattern
    }
    $found = @($resolved | Where-Object { $_.status -eq 'FOUND' })
    $missing = @($resolved | Where-Object { $_.status -eq 'MISSING' })
    $testArgs = @()
    foreach ($row in $found) {
        foreach ($fqcn in $row.classes) { $testArgs += $fqcn }
    }
    $command = $null
    if ($testArgs.Count -gt 0) {
        $rendered = @('.\gradlew.bat', 'test')
        foreach ($fqcn in $testArgs) { $rendered += "--tests $fqcn" }
        $command = ($rendered -join ' ')
    }
    $gradleExit = $null
    $gradleStatus = 'NOT_RUN'
    if ($runGradle -and $command) {
        Push-Location $Root
        try {
            & .\gradlew.bat test @($testArgs | ForEach-Object { '--tests'; $_ })
            $gradleExit = $LASTEXITCODE
            $gradleStatus = if ($gradleExit -eq 0) { 'PASS' } else { 'FAIL' }
        } finally {
            Pop-Location
        }
    } elseif ($runGradle -and -not $command) {
        $gradleStatus = 'MISSING'
    }
    $packages += [ordered]@{
        wp            = $name
        note          = $notes[$name]
        patterns      = @($resolved | ForEach-Object {
            [ordered]@{ pattern = $_.pattern; status = $_.status; classes = @($_.classes) }
        })
        missing       = @($missing | ForEach-Object { $_.pattern })
        command       = $command
        gradle_status = $gradleStatus
        gradle_exit   = $gradleExit
        tests_executed = $(if ($runGradle) { 'see gradle_exit; this runner does not parse surefire counts' } else { 'NOT_RUN' })
    }
}

$verifyRag = [ordered]@{
    requested = [bool]$WithVerifyRag
    command   = 'Verify-RAG.bat'
    status    = 'NOT_RUN'
    exit      = $null
}
if ($WithVerifyRag -and $runGradle) {
    Push-Location $Root
    try {
        & .\Verify-RAG.bat
        $verifyRag.exit = $LASTEXITCODE
        $verifyRag.status = if ($verifyRag.exit -eq 0) { 'PASS' } else { 'FAIL' }
    } finally {
        Pop-Location
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $Root 'var\codex-assist-20261001'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outPath = Join-Path $outDir "verify-$stamp.json"
if (Test-Path -LiteralPath $outPath) {
    $outPath = Join-Path $outDir "verify-$stamp-001.json"
}
$report = [ordered]@{
    schema                   = 'awx.codex-r5-verify.v1'
    generated_at             = [DateTimeOffset]::UtcNow.ToString('o')
    mode                     = $(if ($runGradle) { 'execute' } else { 'dry-run' })
    wp                       = $Wp
    gradle_executed          = $runGradle
    external_provider_calls  = 0
    full_suite               = $false
    clean                    = $false
    work_packages            = $packages
    verify_rag               = $verifyRag
    verify_events_help       = 'See the comment-based help NOTES. Synthetic verify-events output is a format drill, not a product pass.'
}
$json = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($outPath, $json + "`n", [System.Text.UTF8Encoding]::new($false))

$missingAll = @()
foreach ($pkg in $packages) { $missingAll += @($pkg.missing) }
Write-Output ("MODE=" + $(if ($runGradle) { 'execute' } else { 'dry-run' }))
Write-Output ("OUT=" + $outPath)
foreach ($pkg in $packages) {
    $patternText = ($pkg.patterns | ForEach-Object { "$($_.pattern)=$($_.status)" }) -join ', '
    if (-not $patternText) { $patternText = 'NO_NAMED_TEST' }
    Write-Output ("{0}: {1}" -f $pkg.wp, $patternText)
    if ($pkg.command) { Write-Output ("  CMD: " + $pkg.command) }
}
if ($WithVerifyRag) {
    Write-Output ("Verify-RAG: " + $verifyRag.status)
}
exit 0

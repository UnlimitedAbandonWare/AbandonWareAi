# gradle_truth_gate.ps1 -- WP3 Gradle truth gate
#
# Runs ONE bounded Gradle invocation, then judges by log body + JUnit XML,
# never by wrapper exit code alone. Verdicts come from
# scripts/test_xml_evidence.py:
#   GREEN / RED / NO_TESTS / BASELINE_BLOCKED / WRAPPER_EXIT_LIE
#
# Guard rails (refuse before running):
#   - no args                          -> usage error (exit 2)
#   - bare :test / :check / :build or any task without --tests when the task
#     is a test aggregation                          -> refused (exit 2)
#   - clean anywhere in args                          -> refused (exit 2)
#   - --repeat requires --tests (targeted suite only) -> refused (exit 2)
#
# Usage:
#   powershell -NoProfile -File scripts\gradle_truth_gate.ps1 `
#       -GradleArgs "test --tests com.example.lms.service.chat.ChatRunRegistryTest --rerun-tasks" `
#       -Root . -OutDir data\agent-handoff\<run>\wp3
#   # compile probe (no tests needed, still classified):
#   ... -GradleArgs "compileJava" -AllowNoTests
#
# Exit codes mirror test_xml_evidence.py: 0 GREEN, 1 RED, 3 BASELINE_BLOCKED,
# 4 WRAPPER_EXIT_LIE, 5 NO_TESTS, 2 guard/usage refusal.
param(
    [string]$Root = ".",
    [Parameter(Mandatory=$true)][string]$GradleArgs,
    [string]$OutDir = "data\agent-handoff\gradle-truth-gate",
    [string]$XmlDir = "",
    [int]$Repeat = 1,
    [string]$SanitizedLogOut = "",
    [switch]$AllowNoTests
)

$ErrorActionPreference = "Stop"
$argTokens = $GradleArgs -split '\s+' | Where-Object { $_ -ne "" }
$testsArg = ($argTokens | Where-Object { $_ -eq "--tests" -or $_ -like "--tests=*" -or $_ -like "-Dtest.single=*" }).Count -gt 0
$taskTokens = $argTokens | Where-Object { $_ -notmatch '^-' }

function Refuse([string]$why) {
    Write-Output "GRADLE_TRUTH_GATE_REFUSED: $why"
    exit 2
}

if ($taskTokens -contains "clean" -or ($taskTokens | Where-Object { $_ -match '^:?clean$' })) {
    Refuse "clean task is forbidden (shared build/ must survive)"
}
$testish = $taskTokens | Where-Object {
    $_ -match '^:?(?:[\w-]+:)?test$' -or $_ -eq "check" -or $_ -match '^:?(?:[\w-]+:)?build$'
}
if ($testish -and -not $testsArg) {
    Refuse "test/check/build requires --tests '<fqcn>' (whole-suite runs forbidden)"
}
if ($Repeat -gt 1 -and -not $testsArg) {
    Refuse "--repeat requires a targeted --tests suite"
}
if (-not $testish -and -not $testsArg -and -not $AllowNoTests) {
    Refuse "non-test task without -AllowNoTests; pass -AllowNoTests for compile probes"
}

Push-Location $Root
try {
    $rootAbs = (Get-Location).Path
    $gradlew = Join-Path $rootAbs "gradlew.bat"
    if (-not (Test-Path $gradlew)) { Refuse "gradlew.bat not found under $rootAbs" }

    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $runDir = Join-Path $OutDir $stamp
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null

    if (-not $XmlDir) {
        # default: all test-results dirs under this root's build outputs
        $XmlDir = Join-Path $rootAbs "build\test-results"
    }

    $env:AWX_RAG_NO_PAUSE = "1"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    if (-not $env:AWX_BUILD_HOST_ID) { $env:AWX_BUILD_HOST_ID = "devin-gradle-truth" }

    $iterations = @()
    for ($i = 1; $i -le $Repeat; $i++) {
        $log = Join-Path $runDir ("gradle-run-{0}.log" -f $i)
        $started = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.ffffffZ")
        Write-Output ("== gradle_truth_gate run {0}/{1}: gradlew.bat {2}" -f $i, $Repeat, $GradleArgs)
        & cmd.exe /c "`"$gradlew`" $GradleArgs 2>&1" | Tee-Object -FilePath $log
        $exit = $LASTEXITCODE
        $verdictJson = python -B (Join-Path $rootAbs "scripts\test_xml_evidence.py") --log $log --xml-dir $XmlDir --gradle-exit $exit --since $started 2>&1
        $vline = $verdictJson | Select-String -Pattern 'VERDICT:\s+(\S+)' | Select-Object -First 1
        $verdict = if ($vline) { $vline.Matches[0].Groups[1].Value } else { "UNKNOWN" }
        if (-not $verdict) { $verdict = "UNKNOWN" }
        $iterations += [ordered]@{ iteration = $i; gradleExit = $exit; verdict = $verdict; log = $log }
        Write-Output ("   exit={0} verdict={1} log={2}" -f $exit, $verdict, $log)
    }

    $final = $iterations[-1]
    $summary = [ordered]@{
        schemaVersion = "awx.gradle-truth-gate.v1"
        root = $rootAbs; gradleArgs = $GradleArgs; repeat = $Repeat
        verdict = $final.verdict; gradleExit = $final.gradleExit
        iterations = $iterations; outDir = $runDir
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $runDir "verdict.json") -Encoding utf8

    if ($SanitizedLogOut) {
        python -B (Join-Path $rootAbs "scripts\log_redact.py") --in $final.log --out $SanitizedLogOut | Out-Null
    }

    $code = switch ($final.verdict) {
        "GREEN" { 0 }; "RED" { 1 }; "NO_TESTS" { 5 }
        "BASELINE_BLOCKED" { 3 }; "WRAPPER_EXIT_LIE" { 4 }; default { 2 }
    }
    Write-Output ("GRADLE_TRUTH: {0} exit={1} dir={2}" -f $final.verdict, $final.gradleExit, $runDir)
    exit $code
} finally {
    Pop-Location
}

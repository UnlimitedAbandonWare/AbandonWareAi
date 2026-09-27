param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).ProviderPath
)

$ErrorActionPreference = "Stop"
$script:Pass = 0
$script:Fail = 0

function Test-Contract {
    param([string]$Name, [bool]$Condition, [string]$Detail = "")
    if ($Condition) {
        $script:Pass++
        Write-Host "[PASS] $Name"
    } else {
        $script:Fail++
        Write-Host "[FAIL] $Name $Detail"
    }
}

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

$skillRoot = Join-Path $Root ".agents\skills\demo1-memory-integrity-autopatch"
$skillFile = Join-Path $skillRoot "SKILL.md"
$openaiYaml = Join-Path $skillRoot "agents\openai.yaml"
$scanner = Join-Path $skillRoot "scripts\scan_memory_integrity_feedback.ps1"
$prompt = Join-Path $Root "agent-prompts\agents\demo1_context_contamination_scout_directive\system_ko.md"

Test-Contract "repo-local skill exists" (Test-Path -LiteralPath $skillFile)
Test-Contract "repo-local scanner exists" (Test-Path -LiteralPath $scanner)
Test-Contract "skill UI metadata exists" (Test-Path -LiteralPath $openaiYaml)

if (Test-Path -LiteralPath $prompt) {
    $promptText = Get-Content -LiteralPath $prompt -Raw -Encoding UTF8
    Test-Contract "registered prompt invokes repo-local skill" ($promptText.Contains('$demo1-memory-integrity-autopatch'))
    Test-Contract "registered prompt has no replacement characters" (-not $promptText.Contains([char]0xFFFD))
    Test-Contract "registered prompt has no mojibake question runs" ($promptText -notmatch '\?\?\?')
    $cjkMojibakeCount = @($promptText.ToCharArray() | Where-Object { [int]$_ -ge 0x4E00 -and [int]$_ -le 0x9FFF }).Count
    $hangulCount = @($promptText.ToCharArray() | Where-Object { [int]$_ -ge 0xAC00 -and [int]$_ -le 0xD7A3 }).Count
    Test-Contract "registered prompt has no CJK mojibake burst" ($cjkMojibakeCount -le 5) ("actual=" + $cjkMojibakeCount)
    Test-Contract "registered Korean prompt retains readable Hangul" ($hangulCount -ge 150) ("actual=" + $hangulCount)
    Test-Contract "registered prompt routes explicit SMB patches through MacSrc guard" (
        $promptText.Contains('$demo1-macsrc-smb-direct-patch') -and
        $promptText.Contains('-SourceWriteMode MacSrcSmbDirect')
    )
} else {
    Test-Contract "registered prompt exists" $false
}

if ((Test-Path -LiteralPath $scanner) -and (Test-Path -LiteralPath $openaiYaml)) {
    $ui = Get-Content -LiteralPath $openaiYaml -Raw -Encoding UTF8
    Test-Contract "default prompt invokes exact skill" ($ui.Contains('$demo1-memory-integrity-autopatch'))

    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-memory-contract-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    try {
        $missingRoot = Join-Path $temp "missing"
        Write-Utf8File (Join-Path $missingRoot "main\java\com\example\lms\trace\TraceSnapshotStore.java") @'
package com.example.lms.trace;
class TraceSnapshotStore { double sample() { return Math.random(); } }
'@
        Write-Utf8File (Join-Path $missingRoot "main\java\com\example\lms\trace\TraceMemoryFingerprintProbe.java") @'
package com.example.lms.trace;
class TraceMemoryFingerprintProbe { private final ThreadLocal<String> previous = new ThreadLocal<>(); }
'@
        Write-Utf8File (Join-Path $missingRoot "main\java\com\example\lms\trace\AblationContributionTracker.java") @'
package com.example.lms.trace;
class AblationContributionTracker { }
'@

        $missingJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $missingRoot -SkipGitGate -Seed 17
        $missing = $missingJson | ConvertFrom-Json
        Test-Contract "scanner schema is stable" ($missing.schemaVersion -eq "awx.memory_integrity.feedback.v1")
        Test-Contract "scanner is read-only" (-not $missing.mutationAllowed)
        Test-Contract "missing fixture yields four bounded patches" ($missing.patchQueue.Count -eq 4) ("actual=" + $missing.patchQueue.Count)
        Test-Contract "patch queue preserves numeric priority" ((@($missing.patchQueue.id) -join ",") -eq "memory-checksum-stamp-verify,deterministic-integrity-ledger,paired-memory-integrity-ablation,autograder-probe-only-loop")
        Test-Contract "three query packets are exact" ($missing.goalQueries.Count -eq 3) ("actual=" + $missing.goalQueries.Count)
        Test-Contract "missing fixture source readiness is APPLY" ($missing.sourceReadiness -eq "APPLY")
        Test-Contract "nondeterministic sampling advisory is emitted" (@($missing.advisories.code) -contains "nondeterministic-integrity-sampling")
        Test-Contract "request-local memory advisory is emitted" (@($missing.advisories.code) -contains "request-local-baseline-state")
        Test-Contract "desktop error metric is defined" (@($missing.metricDefinitions.name) -contains "desktopErrorRate")
        Test-Contract "paired ablation metric is defined" (@($missing.metricDefinitions.name) -contains "pairedIntegrityContribution")

        $providerRoot = "Microsoft.PowerShell.Core\FileSystem::$missingRoot"
        $providerJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $providerRoot -SkipGitGate -Seed 17
        $provider = $providerJson | ConvertFrom-Json
        Test-Contract "provider-qualified root resolves" ($provider.root -eq (Resolve-Path -LiteralPath $missingRoot).ProviderPath)

        $completeRoot = Join-Path $temp "complete"
        Write-Utf8File (Join-Path $completeRoot "main\java\com\example\lms\trace\TraceSnapshotStore.java") @'
package com.example.lms.trace;
import java.security.MessageDigest;
import java.util.SplittableRandom;
class TraceSnapshotStore {
  long integritySeed; String checksumSha256; String expectedChecksum; String actualChecksum;
  boolean checksumMatch; void verifyChecksumMismatch() { MessageDigest.getInstance("SHA-256"); }
  void deterministicSample() { new SplittableRandom(integritySeed); }
}
'@
        Write-Utf8File (Join-Path $completeRoot "main\java\com\example\lms\trace\TraceMemoryFingerprintProbe.java") @'
package com.example.lms.trace;
class TraceMemoryFingerprintProbe { String expectedChecksum; String actualChecksum; boolean checksumMatch; }
'@
        Write-Utf8File (Join-Path $completeRoot "main\java\com\example\lms\trace\AblationContributionTracker.java") @'
package com.example.lms.trace;
class AblationContributionTracker { double memoryIntegrityTreatment; double memoryIntegrityControl; double pairedDelta; }
'@
        Write-Utf8File (Join-Path $completeRoot "main\java\com\example\lms\trace\AutograderProbeLoop.java") @'
package com.example.lms.trace;
class AutograderProbeLoop { boolean autograder; void memoryIntegrityProbe(){} void rerunOnMismatch(){} }
'@
        $completeJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $completeRoot -SkipGitGate -Seed 17
        $complete = $completeJson | ConvertFrom-Json
        Test-Contract "implemented fixture needs no source patch" ($complete.sourceReadiness -eq "NO_PATCH_NEEDED")
        Test-Contract "implemented fixture queue is empty" ($complete.patchQueue.Count -eq 0)

        $baselinePath = Join-Path $temp "baseline.json"
        [void](& powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $completeRoot -SkipGitGate -Seed 17 -SamplePercent 100 -OutputPath $baselinePath)
        $changedFile = Join-Path $completeRoot "main\java\com\example\lms\trace\AutograderProbeLoop.java"
        [System.IO.File]::AppendAllText($changedFile, "`n// deterministic tamper fixture", [System.Text.UTF8Encoding]::new($false))
        $compareJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $completeRoot -SkipGitGate -Seed 17 -SamplePercent 100 -BaselinePath $baselinePath
        $compare = $compareJson | ConvertFrom-Json
        Test-Contract "checksum baseline compares every sampled file" ($compare.checksumProbe.baselineComparableCount -eq 4)
        Test-Contract "checksum baseline detects one tampered file" ($compare.checksumProbe.baselineMismatchCount -eq 1)
        Test-Contract "checksum mismatch rate keeps its denominator" ([double]$compare.checksumProbe.baselineMismatchRate -eq 0.25)

        $ledgerPath = Join-Path $temp "probe-ledger.jsonl"
        Write-Utf8File $ledgerPath @'
{"checksumMatch":true,"contaminationDetected":false,"expectedContamination":false,"outcomeError":true,"treatment":"control","pairedRunId":"p-001"}
{"checksumMatch":false,"contaminationDetected":true,"expectedContamination":false,"outcomeError":false,"treatment":"integrity","pairedRunId":"p-001"}
{"checksumMatch":true,"contaminationDetected":false,"expectedContamination":true,"outcomeError":true,"treatment":"control","pairedRunId":"p-002"}
{"checksumMatch":true,"contaminationDetected":true,"expectedContamination":true,"outcomeError":false,"treatment":"integrity","pairedRunId":"p-002"}
'@
        $metricJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $completeRoot -SkipGitGate -Seed 17 -ProbeLedgerPath $ledgerPath
        $metric = $metricJson | ConvertFrom-Json
        Test-Contract "ledger checksum mismatch rate is measured" ([double]$metric.observedMetrics.checksumMismatchRate -eq 0.25)
        Test-Contract "ledger contamination rate is measured" ([double]$metric.observedMetrics.contextContaminationRate -eq 0.5)
        Test-Contract "ledger false positive rate is measured" ([double]$metric.observedMetrics.falsePositiveRate -eq 0.5)
        Test-Contract "ledger false negative rate is measured" ([double]$metric.observedMetrics.falseNegativeRate -eq 0.5)
        Test-Contract "Desktop error rate is measured" ([double]$metric.observedMetrics.desktopErrorRate -eq 0.5)
        Test-Contract "paired integrity contribution is measured" ([double]$metric.observedMetrics.pairedIntegrityContribution -eq 1.0)

        $secretRoot = Join-Path $temp "secret"
        $fakeSecret = "sk-" + ("A" * 24)
        Write-Utf8File (Join-Path $secretRoot "main\java\Leak.java") ("class Leak { String value = `"$fakeSecret`"; }")
        $secretJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $secretRoot -SkipGitGate -Seed 17
        $secret = $secretJson | ConvertFrom-Json
        Test-Contract "secret risk fails closed" ($secret.verdict -eq "HOLD")
        Test-Contract "secret output is count-only" (-not $secretJson.Contains($fakeSecret))

        $dubiousRoot = Join-Path $temp "dubious"
        New-Item -ItemType Directory -Force -Path (Join-Path $dubiousRoot ".git") | Out-Null
        Write-Utf8File (Join-Path $dubiousRoot "main\java\Empty.java") "class Empty {}"
        $fakeGit = Join-Path $temp "fake-git.cmd"
        Write-Utf8File $fakeGit "@echo off`r`n>&2 echo fatal: detected dubious ownership in repository`r`nexit /b 128`r`n"
        $dubiousJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner -Root $dubiousRoot -GitExecutable $fakeGit -Seed 17
        $dubious = $dubiousJson | ConvertFrom-Json
        Test-Contract "dubious ownership is classified" ($dubious.failureClassification -eq "smb-repo-owner-mismatch")
        Test-Contract "native stderr is not copied to report" (-not $dubiousJson.Contains("detected dubious ownership"))

        $directJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner `
            -Root $dubiousRoot -GitExecutable $fakeGit -Seed 17 `
            -SourceWriteMode MacSrcSmbDirect -ExpectedMacSrcRoot $dubiousRoot
        $direct = $directJson | ConvertFrom-Json
        Test-Contract "explicit MacSrc mode converts dubious Git ownership to checksum fallback" (
            $direct.verdict -eq "APPLY" -and
            $direct.failureClassification -eq "none" -and
            $direct.evidence.gitEvidenceMode -eq "filesystem-cas"
        ) $directJson
        Test-Contract "explicit MacSrc mode delegates mutation to the direct-patch guard" (
            $direct.patchAuthorizationEligible -eq $true -and
            $direct.mutationAllowed -eq $false -and
            $direct.writePolicy.requiredGuardSkill -eq "demo1-macsrc-smb-direct-patch"
        ) $directJson

        $wrongDirectJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scanner `
            -Root $missingRoot -SkipGitGate -Seed 17 `
            -SourceWriteMode MacSrcSmbDirect -ExpectedMacSrcRoot $dubiousRoot
        $wrongDirect = $wrongDirectJson | ConvertFrom-Json
        Test-Contract "explicit MacSrc mode rejects any other source root" (
            $wrongDirect.verdict -eq "HOLD" -and
            $wrongDirect.failureClassification -eq "macsrc-root-mismatch"
        ) $wrongDirectJson
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

Write-Host "[SUMMARY] pass=$script:Pass fail=$script:Fail"
if ($script:Fail -gt 0) { exit 1 }

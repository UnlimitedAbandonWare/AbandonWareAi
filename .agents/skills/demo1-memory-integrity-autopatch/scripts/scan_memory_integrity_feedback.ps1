[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root,
    [string]$OutputPath,
    [string]$BaselinePath,
    [string]$ProbeLedgerPath,
    [string]$GitExecutable = "git",
    [string]$ExpectedDesktopHost = "DESKTOP-M5NOV6K",
    [string]$ExpectedDesktopRoot,
    [ValidateSet("DesktopLocal", "MacSrcSmbDirect", "AuditOnly")]
    [string]$SourceWriteMode = "DesktopLocal",
    [string]$ExpectedMacSrcRoot = "\\desktop-m5nov6k\MacSrc",
    [ValidateRange(1, 100)]
    [int]$SamplePercent = 5,
    [ValidateRange(1, 4096)]
    [int]$MaxSampleFiles = 128,
    [int]$Seed = 20260729,
    [switch]$SkipGitGate
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-Sha256Text {
    param([string]$Value)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Resolve-ProviderPath {
    param([string]$Path)
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

function Resolve-CanonicalSharePath {
    param([string]$Path)
    $providerPath = Resolve-ProviderPath -Path $Path
    if ($providerPath -match "^([A-Za-z]):\\") {
        $drive = Get-PSDrive -Name $Matches[1] -ErrorAction SilentlyContinue
        if ($drive -and -not [string]::IsNullOrWhiteSpace([string]$drive.DisplayRoot)) {
            $driveRoot = [IO.Path]::GetPathRoot($providerPath)
            $suffix = $providerPath.Substring($driveRoot.Length)
            $providerPath = if ([string]::IsNullOrWhiteSpace($suffix)) {
                [string]$drive.DisplayRoot
            } else {
                Join-Path ([string]$drive.DisplayRoot) $suffix
            }
        }
    }
    return $providerPath.TrimEnd([char[]]@("\", "/"))
}

function Invoke-GitProbe {
    param([string]$Executable, [string]$WorkingRoot, [string]$Arguments)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Executable
    $rootArgument = $WorkingRoot
    if ($rootArgument -match '^[A-Za-z]:\\$') { $rootArgument = $rootArgument + '.' }
    $escapedRoot = $rootArgument.Replace('"', '\"')
    $psi.Arguments = "-C `"$escapedRoot`" $Arguments"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    try {
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $psi
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        if (-not $process.WaitForExit(5000)) {
            try { $process.Kill() } catch { }
            return [pscustomobject]@{ exitCode = 124; stdout = ""; stderrClass = "git-timeout" }
        }
        $stderrClass = "none"
        if ($stderr -match "dubious ownership") { $stderrClass = "smb-repo-owner-mismatch" }
        elseif ($stderr -match "not a git repository") { $stderrClass = "git-metadata-missing" }
        elseif ($process.ExitCode -ne 0) { $stderrClass = "git-probe-failed" }
        return [pscustomobject]@{
            exitCode = $process.ExitCode
            stdout = $stdout.Trim()
            stderrClass = $stderrClass
        }
    } catch {
        return [pscustomobject]@{ exitCode = 127; stdout = ""; stderrClass = "git-executable-unavailable" }
    }
}

function Get-SourceFiles {
    param([string]$ResolvedRoot)
    $extensions = @(".java", ".kt", ".kts", ".groovy", ".yml", ".yaml", ".properties", ".json")
    $activeRoots = @(
        "main\java",
        "main\resources",
        "app\src\main\java_clean",
        "app\src\main\resources"
    )
    $items = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
    foreach ($relative in $activeRoots) {
        $candidate = Join-Path $ResolvedRoot $relative
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $candidate -Recurse -File -ErrorAction SilentlyContinue) {
            if ($extensions -contains $file.Extension.ToLowerInvariant()) { $items.Add($file) }
        }
    }
    return @($items | Sort-Object FullName -Unique)
}

function Read-BoundedText {
    param([System.IO.FileInfo]$File)
    if ($File.Length -gt 2097152) { return "" }
    try { return [System.IO.File]::ReadAllText($File.FullName) } catch { return "" }
}

function Get-TargetText {
    param([object[]]$Files, [string]$FileName)
    $parts = foreach ($file in $Files) {
        if ($file.Name -ieq $FileName) { Read-BoundedText -File $file }
    }
    return ($parts -join "`n")
}

function Test-AllTokens {
    param([string]$Text, [string[]]$Tokens)
    foreach ($token in $Tokens) {
        if ($Text -notmatch [regex]::Escape($token)) { return $false }
    }
    return $true
}

function Get-LedgerMetrics {
    param([string]$Path)
    $rows = [System.Collections.Generic.List[object]]::new()
    $invalid = 0
    if ($Path -and (Test-Path -LiteralPath $Path)) {
        foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            try { $rows.Add(($line | ConvertFrom-Json)) } catch { $invalid++ }
        }
    }
    $count = $rows.Count
    $mismatch = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "checksumMatch" -and -not [bool]$_.checksumMatch }).Count
    $contaminated = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "contaminationDetected" -and [bool]$_.contaminationDetected }).Count
    $fpBase = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "expectedContamination" -and -not [bool]$_.expectedContamination }).Count
    $fp = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "expectedContamination" -and $_.PSObject.Properties.Name -contains "contaminationDetected" -and -not [bool]$_.expectedContamination -and [bool]$_.contaminationDetected }).Count
    $fnBase = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "expectedContamination" -and [bool]$_.expectedContamination }).Count
    $fn = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "expectedContamination" -and $_.PSObject.Properties.Name -contains "contaminationDetected" -and [bool]$_.expectedContamination -and -not [bool]$_.contaminationDetected }).Count
    $errorRows = @($rows | Where-Object { $_.PSObject.Properties.Name -contains "outcomeError" })
    $errors = @($errorRows | Where-Object { [bool]$_.outcomeError }).Count
    $control = @($errorRows | Where-Object { $_.PSObject.Properties.Name -contains "treatment" -and $_.treatment -eq "control" })
    $treatment = @($errorRows | Where-Object { $_.PSObject.Properties.Name -contains "treatment" -and $_.treatment -eq "integrity" })
    $controlErrors = @($control | Where-Object { [bool]$_.outcomeError }).Count
    $treatmentErrors = @($treatment | Where-Object { [bool]$_.outcomeError }).Count
    $rate = {
        param([int]$Numerator, [int]$Denominator)
        if ($Denominator -eq 0) { return $null }
        return [math]::Round($Numerator / [double]$Denominator, 6)
    }
    $controlRate = & $rate $controlErrors $control.Count
    $treatmentRate = & $rate $treatmentErrors $treatment.Count
    $paired = $null
    if ($null -ne $controlRate -and $null -ne $treatmentRate) { $paired = [math]::Round($controlRate - $treatmentRate, 6) }
    return [ordered]@{
        ledgerPresent = [bool]($Path -and (Test-Path -LiteralPath $Path))
        validRows = $count
        invalidRows = $invalid
        probeCount = $count
        checksumMismatchRate = (& $rate $mismatch $count)
        contextContaminationRate = (& $rate $contaminated $count)
        falsePositiveRate = (& $rate $fp $fpBase)
        falseNegativeRate = (& $rate $fn $fnBase)
        desktopErrorRate = (& $rate $errors $errorRows.Count)
        controlErrorRate = $controlRate
        integrityTreatmentErrorRate = $treatmentRate
        pairedIntegrityContribution = $paired
    }
}

$resolvedRoot = Resolve-ProviderPath -Path $Root
$canonicalRoot = Resolve-CanonicalSharePath -Path $Root
$isUnc = $canonicalRoot.StartsWith("\\")
$hostMatches = $env:COMPUTERNAME -ieq $ExpectedDesktopHost
$rootMatches = $true
if ($ExpectedDesktopRoot) {
    try { $rootMatches = (Resolve-ProviderPath -Path $ExpectedDesktopRoot) -ieq $resolvedRoot } catch { $rootMatches = $false }
}
$macSrcRootMatches = $false
try {
    $expectedMacSrcCanonical = Resolve-CanonicalSharePath -Path $ExpectedMacSrcRoot
    $macSrcRootMatches = $canonicalRoot.Equals($expectedMacSrcCanonical, [StringComparison]::OrdinalIgnoreCase)
} catch {
    $expectedMacSrcCanonical = $ExpectedMacSrcRoot
}
$indexLock = Test-Path -LiteralPath (Join-Path $resolvedRoot ".git\index.lock")
$gitDirectoryPresent = Test-Path -LiteralPath (Join-Path $resolvedRoot ".git")
$gitProbe = [pscustomobject]@{ exitCode = 0; stdout = ""; stderrClass = "skipped-test-only" }
if (-not $SkipGitGate) {
    if ($gitDirectoryPresent) { $gitProbe = Invoke-GitProbe -Executable $GitExecutable -WorkingRoot $resolvedRoot -Arguments "rev-parse --show-toplevel" }
    else { $gitProbe = [pscustomobject]@{ exitCode = 128; stdout = ""; stderrClass = "git-metadata-missing" } }
}

$files = @(Get-SourceFiles -ResolvedRoot $resolvedRoot)
$snapshotText = Get-TargetText -Files $files -FileName "TraceSnapshotStore.java"
$fingerprintText = Get-TargetText -Files $files -FileName "TraceMemoryFingerprintProbe.java"
$ablationText = Get-TargetText -Files $files -FileName "AblationContributionTracker.java"
$autograderText = Get-TargetText -Files $files -FileName "AutograderProbeLoop.java"

$hasChecksum = (Test-AllTokens -Text ($snapshotText + $fingerprintText) -Tokens @("SHA-256", "expectedChecksum", "actualChecksum", "checksumMatch"))
$hasDeterministicSample = (Test-AllTokens -Text $snapshotText -Tokens @("integritySeed", "SplittableRandom")) -and ($snapshotText -notmatch "Math\.random\s*\(")
$hasPairedAblation = Test-AllTokens -Text $ablationText -Tokens @("memoryIntegrityTreatment", "memoryIntegrityControl", "pairedDelta")
$hasAutograderLoop = Test-AllTokens -Text $autograderText -Tokens @("autograder", "memoryIntegrityProbe", "rerunOnMismatch")

$queue = [System.Collections.Generic.List[object]]::new()
if (-not $hasChecksum) {
    $queue.Add([ordered]@{ priority = 1; id = "memory-checksum-stamp-verify"; targetBoundary = "TraceSnapshotStore/TraceMemoryFingerprintProbe"; redTest = "tampered canonical snapshot is rejected with checksum_mismatch"; greenTest = "stable canonical serialization verifies SHA-256 without retaining raw context" })
}
if (-not $hasDeterministicSample) {
    $queue.Add([ordered]@{ priority = 2; id = "deterministic-integrity-ledger"; targetBoundary = "TraceSnapshotStore sampling"; redTest = "same seed selects different probe set"; greenTest = "same seed and population select identical path hashes" })
}
if (-not $hasPairedAblation) {
    $queue.Add([ordered]@{ priority = 3; id = "paired-memory-integrity-ablation"; targetBoundary = "AblationContributionTracker"; redTest = "control/treatment error delta is unavailable"; greenTest = "paired run emits control, integrity treatment, delta, and denominator" })
}
if (-not $hasAutograderLoop) {
    $queue.Add([ordered]@{ priority = 4; id = "autograder-probe-only-loop"; targetBoundary = "autograder orchestration seam"; redTest = "autograder mode does not run integrity probes"; greenTest = "autograder runs bounded probes and requests a patch cycle only after a classified mismatch" })
}

$advisories = [System.Collections.Generic.List[object]]::new()
if ($snapshotText -match "Math\.random\s*\(") {
    $advisories.Add([ordered]@{ code = "nondeterministic-integrity-sampling"; severity = "warning"; evidence = "TraceSnapshotStore uses unseeded random sampling" })
}
if (($fingerprintText -match "ThreadLocal\s*<") -and ($fingerprintText -match "\bprevious\b")) {
    $advisories.Add([ordered]@{ code = "request-local-baseline-state"; severity = "warning"; evidence = "fingerprint baseline is request/thread local" })
}

$secretRegex = [regex]'(?:sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,})'
$secretHits = 0
foreach ($file in $files) {
    $text = Read-BoundedText -File $file
    if ($text) { $secretHits += $secretRegex.Matches($text).Count }
}

$sampleCount = 0
if ($files.Count -gt 0) {
    $sampleCount = [math]::Min($MaxSampleFiles, [math]::Max(1, [math]::Ceiling($files.Count * ($SamplePercent / 100.0))))
}
$ranked = foreach ($file in $files) {
    $relative = $file.FullName.Substring($resolvedRoot.Length).TrimStart('\', '/')
    [pscustomobject]@{ file = $file; relative = $relative; rank = Get-Sha256Text "$Seed|$($relative.ToLowerInvariant())" }
}
$samples = [System.Collections.Generic.List[object]]::new()
foreach ($entry in @($ranked | Sort-Object rank | Select-Object -First $sampleCount)) {
    $samples.Add([ordered]@{
        pathHash = Get-Sha256Text $entry.relative.ToLowerInvariant()
        contentSha256 = (Get-FileHash -LiteralPath $entry.file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    })
}

$baselineComparable = 0
$baselineMismatch = 0
if ($BaselinePath -and (Test-Path -LiteralPath $BaselinePath)) {
    try {
        $baseline = Get-Content -LiteralPath $BaselinePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $baselineMap = @{}
        foreach ($row in @($baseline.checksumProbe.samples)) { $baselineMap[[string]$row.pathHash] = [string]$row.contentSha256 }
        foreach ($row in $samples) {
            if ($baselineMap.ContainsKey([string]$row.pathHash)) {
                $baselineComparable++
                if ($baselineMap[[string]$row.pathHash] -ne [string]$row.contentSha256) { $baselineMismatch++ }
            }
        }
    } catch { }
}
$baselineMismatchRate = $null
if ($baselineComparable -gt 0) { $baselineMismatchRate = [math]::Round($baselineMismatch / [double]$baselineComparable, 6) }

$failure = "none"
$verdict = "AUDIT_ONLY"
$gitEvidenceMode = if ($gitProbe.exitCode -eq 0) { "native" } else { "unavailable" }
if ($secretHits -gt 0) { $failure = "secret-leak-risk"; $verdict = "HOLD" }
elseif ($indexLock) { $failure = "index-lock-present"; $verdict = "HOLD" }
elseif ($SourceWriteMode -eq "AuditOnly") { $verdict = "AUDIT_ONLY" }
elseif ($SourceWriteMode -eq "MacSrcSmbDirect") {
    if (-not $macSrcRootMatches) { $failure = "macsrc-root-mismatch"; $verdict = "HOLD" }
    else {
        if ($gitProbe.exitCode -ne 0) { $gitEvidenceMode = "filesystem-cas" }
        $verdict = if ($queue.Count -gt 0) { "APPLY" } else { "NO_PATCH_NEEDED" }
    }
}
elseif (-not $SkipGitGate -and $gitProbe.exitCode -ne 0) { $failure = $gitProbe.stderrClass; $verdict = "HOLD" }
elseif (-not $SkipGitGate -and (-not $hostMatches -or $isUnc -or -not $rootMatches)) { $failure = "desktop-owner-or-root-unproven"; $verdict = "HOLD" }
elseif (-not $SkipGitGate -and $queue.Count -gt 0) { $verdict = "APPLY" }
elseif (-not $SkipGitGate) { $verdict = "NO_PATCH_NEEDED" }

$sourceReadiness = if ($queue.Count -gt 0) { "APPLY" } else { "NO_PATCH_NEEDED" }
$metricDefinitions = @(
    [ordered]@{ name = "probeCount"; formula = "valid probe ledger rows"; direction = "coverage" },
    [ordered]@{ name = "checksumMismatchRate"; formula = "checksum mismatches / probes"; direction = "lower_is_better" },
    [ordered]@{ name = "contextContaminationRate"; formula = "contamination detections / probes"; direction = "lower_is_better" },
    [ordered]@{ name = "falsePositiveRate"; formula = "false contamination alarms / clean labeled probes"; direction = "lower_is_better" },
    [ordered]@{ name = "falseNegativeRate"; formula = "missed contamination / contaminated labeled probes"; direction = "lower_is_better" },
    [ordered]@{ name = "desktopErrorRate"; formula = "outcomeError rows / observed Desktop rows"; direction = "lower_is_better" },
    [ordered]@{ name = "pairedIntegrityContribution"; formula = "controlErrorRate - integrityTreatmentErrorRate"; direction = "higher_is_better" }
)
$goalQueries = @(
    [ordered]@{ role = "POSITIVE_QUERY"; target = "Preserve reusable trace/memory seams and add the smallest deterministic integrity proof"; falsifier = "existing source already emits equivalent checksum and paired metrics" },
    [ordered]@{ role = "NEGATIVE_QUERY"; target = "Disprove causality, ownership, privacy, and reproducibility claims before mutation"; falsifier = "a seeded tamper fixture is detected without raw-context retention" },
    [ordered]@{ role = "NEUTRAL_QUERY"; target = "Apply only an order-stable goal with score >= 50 and every safety gate passing"; falsifier = "A-B and B-A comparison changes the verdict" }
)
$ledgerMetrics = Get-LedgerMetrics -Path $ProbeLedgerPath

$report = [ordered]@{
    schemaVersion = "awx.memory_integrity.feedback.v1"
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    root = $resolvedRoot
    host = $env:COMPUTERNAME
    rootKind = if ($isUnc) { "unc-share" } else { "local" }
    mutationAllowed = $false
    patchAuthorizationEligible = [bool]($verdict -eq "APPLY")
    verdict = $verdict
    sourceReadiness = $sourceReadiness
    failureClassification = $failure
    writePolicy = [ordered]@{
        sourceWriteMode = $SourceWriteMode
        externalReadAccess = "unrestricted"
        externalToolAccess = "unrestricted"
        applicationSourceWriteRootOnly = [bool]($SourceWriteMode -eq "MacSrcSmbDirect")
        expectedMacSrcRoot = $expectedMacSrcCanonical
        macSrcRootMatch = $macSrcRootMatches
        oneDriveSourceWriteDefault = $false
        requiredGuardSkill = if ($SourceWriteMode -eq "MacSrcSmbDirect") { "demo1-macsrc-smb-direct-patch" } else { $null }
    }
    evidence = [ordered]@{
        expectedDesktopHost = $ExpectedDesktopHost
        desktopHostMatch = $hostMatches
        expectedRootMatch = $rootMatches
        gitDirectoryPresent = $gitDirectoryPresent
        gitProbeStatus = $gitProbe.stderrClass
        gitEvidenceMode = $gitEvidenceMode
        indexLockPresent = $indexLock
        activeSourceFileCount = $files.Count
        secretPatternHits = $secretHits
    }
    checksumProbe = [ordered]@{
        algorithm = "SHA-256"
        seed = $Seed
        selection = "sha256(seed|normalized-relative-path), ascending"
        populationCount = $files.Count
        sampleCount = $samples.Count
        samplePercent = $SamplePercent
        samples = @($samples)
        baselineComparableCount = $baselineComparable
        baselineMismatchCount = $baselineMismatch
        baselineMismatchRate = $baselineMismatchRate
    }
    observedMetrics = $ledgerMetrics
    metricDefinitions = $metricDefinitions
    goalQueries = $goalQueries
    patchQueue = @($queue.ToArray() | Sort-Object { [int]$_['priority'] })
    advisories = @($advisories)
    stopConditions = @("verdict=HOLD", "changed failure class", "index lock", "dirty overlap", "secret risk", "sourceSet unproven", "verification failure")
    evidenceNeeded = @(
        "Desktop-local owner session / rerun from the intended local source root",
        "probe ledger JSONL with labels and treatment rows / pass -ProbeLedgerPath",
        "Desktop focused tests after each single patch / keep desktopFinalProof=evidence_needed until observed"
    )
}

$json = $report | ConvertTo-Json -Depth 12
if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($OutputPath, $json, [System.Text.UTF8Encoding]::new($false))
}
$json

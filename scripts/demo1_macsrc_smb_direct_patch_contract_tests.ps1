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
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Get-TextSha256 {
    param([string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace("-", "") }
    finally { $sha.Dispose() }
}

function Get-CanonicalPath {
    param([string]$Path)
    $resolved = Resolve-Path -LiteralPath $Path
    $providerPath = [string]$resolved.ProviderPath
    if ($providerPath -match "^([A-Za-z]):\\") {
        $drive = Get-PSDrive -Name $Matches[1] -ErrorAction SilentlyContinue
        if ($drive -and $drive.DisplayRoot) {
            $suffix = $providerPath.Substring(([IO.Path]::GetPathRoot($providerPath)).Length)
            $providerPath = if ($suffix) { Join-Path ([string]$drive.DisplayRoot) $suffix } else { [string]$drive.DisplayRoot }
        }
    }
    return $providerPath.TrimEnd([char[]]@("\", "/"))
}

function Invoke-Guard {
    param([string[]]$Arguments)
    $all = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $tool) + $Arguments
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& powershell @all 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = $output -join "`n"
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ exitCode = $exitCode; text = $text; json = $json }
}

function Get-SessionDir {
    param([string]$RunId)
    return Join-Path $canonicalRoot ("data\agent-handoff\macsrc-smb-direct\" + $RunId)
}

function Get-LeaseDir {
    param([string]$RunId)
    return Join-Path $canonicalRoot ("__patch_drop__\source-edit-locks\macsrc-" + $RunId + ".lock")
}

function New-VerificationEvidence {
    param([string]$RunId, [string]$RelativeTarget, [string]$TargetPath, [string]$HashOverride = "")
    $sessionDir = Get-SessionDir -RunId $RunId
    $sessionPath = Join-Path $sessionDir "session.json"
    $targetHash = if ($HashOverride) { $HashOverride } else { (Get-FileHash -LiteralPath $TargetPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    $record = [ordered]@{
        schemaVersion = "awx.macsrc_smb_patch_verification.v1"
        runId = $RunId
        sessionSha256 = (Get-FileHash -LiteralPath $sessionPath -Algorithm SHA256).Hash.ToLowerInvariant()
        exitCode = 0
        command = "contract-fixture verification"
        targetPostimages = @([ordered]@{ relativePath = $RelativeTarget; sha256 = $targetHash })
    }
    $path = Join-Path $sessionDir "verification.json"
    Write-Utf8File -Path $path -Content (($record | ConvertTo-Json -Depth 8) + "`n")
    return $path.Substring($canonicalRoot.Length).TrimStart("\")
}

function Invoke-Prepare {
    param([string]$RunId, [string]$Target = $targetRelative, [switch]$AllowPending)
    $args = @(
        "-Mode", "Prepare", "-Root", $canonicalRoot, "-RunId", $RunId,
        "-OwnerId", $ownerId, "-TargetFiles", $Target,
        "-BoundaryEvidenceFiles", $boundaryRelative, "-WatchRoots", $fixtureRelative
    )
    if ($AllowPending) { $args += "-AllowPendingPatchQueue" }
    return Invoke-Guard -Arguments $args
}

function Invoke-Transition {
    param([string]$Mode, [string]$RunId, [string]$Evidence = "")
    $args = @("-Mode", $Mode, "-Root", $canonicalRoot, "-RunId", $RunId, "-OwnerId", $ownerId)
    if ($Evidence) { $args += @("-VerificationEvidenceFile", $Evidence) }
    return Invoke-Guard -Arguments $args
}

function Remove-ExactTestPath {
    param([string]$Path, [string[]]$AllowedParents, [switch]$Recurse)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $full = [IO.Path]::GetFullPath($Path)
    $allowed = $false
    foreach ($parent in $AllowedParents) {
        $prefix = [IO.Path]::GetFullPath($parent).TrimEnd("\") + "\"
        if ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { $allowed = $true; break }
    }
    if (-not $allowed) { throw "unsafe-test-cleanup-target" }
    if ($Recurse) { Remove-Item -LiteralPath $full -Recurse -Force } else { Remove-Item -LiteralPath $full -Force }
}

$canonicalRoot = Get-CanonicalPath -Path $Root
$skillRoot = Join-Path $canonicalRoot ".agents\skills\demo1-macsrc-smb-direct-patch"
$tool = Join-Path $skillRoot "scripts\macsrc_smb_patch_guard.ps1"
$skillFile = Join-Path $skillRoot "SKILL.md"
$openAiYaml = Join-Path $skillRoot "agents\openai.yaml"
$caseId = [guid]::NewGuid().ToString("N")
$shortId = $caseId.Substring(0, 12)
$runPrefix = "contract-$shortId"
$fixtureRelative = "var\macsrc-smb-guard-tests\$caseId"
$fixtureRoot = Join-Path $canonicalRoot $fixtureRelative
$fixtureParent = Join-Path $canonicalRoot "var\macsrc-smb-guard-tests"
$sessionParent = Join-Path $canonicalRoot "data\agent-handoff\macsrc-smb-direct"
$leaseParent = Join-Path $canonicalRoot "__patch_drop__\source-edit-locks"
$targetRelative = "$fixtureRelative\Fixture.java"
$siblingRelative = "$fixtureRelative\Sibling.java"
$targetPath = Join-Path $canonicalRoot $targetRelative
$siblingPath = Join-Path $canonicalRoot $siblingRelative
$boundaryRelative = "build.gradle.kts"
$original = "class Fixture {}`n"
$siblingOriginal = "class Sibling {}`n"
$ownerId = "notebook-contract-$shortId"
$createdRuns = [Collections.Generic.List[string]]::new()
$pendingPatch = Join-Path $canonicalRoot "__patch_drop__\contract-$shortId-v3.patch"
$wrongRoot = Join-Path ([IO.Path]::GetTempPath()) ("awx-wrong-root-" + $caseId)

Test-Contract "MacSrc SMB direct-patch skill exists" (Test-Path -LiteralPath $skillFile -PathType Leaf)
Test-Contract "MacSrc SMB guard tool exists" (Test-Path -LiteralPath $tool -PathType Leaf)
Test-Contract "MacSrc SMB skill metadata exists" (Test-Path -LiteralPath $openAiYaml -PathType Leaf)

if (Test-Path -LiteralPath $tool -PathType Leaf) {
    $guardText = Get-Content -LiteralPath $tool -Raw
    $guardTokens = $null
    $guardParseErrors = $null
    $guardAst = [Management.Automation.Language.Parser]::ParseInput($guardText, [ref]$guardTokens, [ref]$guardParseErrors)
    $guardParameters = @($guardAst.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
    Test-Contract "production guard parses on a UNC root" (@($guardParseErrors).Count -eq 0) (@($guardParseErrors) -join " | ")
    Test-Contract "production guard exposes no test root override" (-not ($guardParameters -contains "TestExpectedRoot"))
    Test-Contract "guard requires an explicit watched source boundary" ($guardParameters -contains "WatchRoots")
    Test-Contract "guard consumes hash-bound evidence, not caller exit-code claims" (
        ($guardParameters -contains "VerificationEvidenceFile") -and -not ($guardParameters -contains "VerificationExitCode")
    )

    try {
        Write-Utf8File -Path $targetPath -Content $original
        Write-Utf8File -Path $siblingPath -Content $siblingOriginal
        New-Item -ItemType Directory -Force -Path $wrongRoot | Out-Null

        $globalConfigTextBefore = (@(& git config --global --list --show-origin 2>$null) -join "`n")
        $globalConfigHashBefore = Get-TextSha256 -Text $globalConfigTextBefore

        $run = "$runPrefix-01"; $createdRuns.Add($run)
        $beforeHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
        $prepare = Invoke-Prepare -RunId $run
        Test-Contract "prepare accepts the proven production MacSrc root" ($prepare.exitCode -eq 0 -and $null -ne $prepare.json) $prepare.text
        Test-Contract "prepare never mutates the target" ((Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash -eq $beforeHash)
        if ($null -ne $prepare.json) {
            Test-Contract "external reads and tools remain unrestricted" (
                $prepare.json.accessPolicy.externalReadAccess -eq "unrestricted" -and $prepare.json.accessPolicy.externalToolAccess -eq "unrestricted"
            ) $prepare.text
            Test-Contract "only application-source writes are MacSrc-bound" (
                $prepare.json.accessPolicy.applicationSourceWriteRootOnly -eq $true -and $prepare.json.accessPolicy.oneDriveSourceWriteDefault -eq $false
            ) $prepare.text
            Test-Contract "Git evidence falls back without global trust mutation" (
                $prepare.json.gitEvidenceMode -in @("native", "filesystem-cas") -and $prepare.json.globalSafeDirectoryMutation -eq $false
            ) $prepare.text
            Test-Contract "trace remains inside MacSrc" ([string]$prepare.json.sessionPath -like "$canonicalRoot*") ([string]$prepare.json.sessionPath)
        }
        $verify = Invoke-Transition -Mode Verify -RunId $run
        Test-Contract "verify authorizes an unchanged preimage" ($verify.exitCode -eq 0 -and $verify.json.authorized -eq $true) $verify.text
        Write-Utf8File -Path $targetPath -Content "class Fixture { int changed; }`n"
        $changedVerify = Invoke-Transition -Mode Verify -RunId $run
        Test-Contract "verify rejects a changed preimage" ($changedVerify.exitCode -ne 0 -and $changedVerify.json.failureClassification -eq "preimage-changed") $changedVerify.text
        Write-Utf8File -Path $targetPath -Content $original
        $abort = Invoke-Transition -Mode Abort -RunId $run
        Test-Contract "abort releases an unchanged matching lease" ($abort.exitCode -eq 0 -and $abort.json.leaseReleased -eq $true) $abort.text

        $run = "$runPrefix-02"; $createdRuns.Add($run)
        $prepareComplete = Invoke-Prepare -RunId $run
        $completedLeaseText = Get-Content -LiteralPath (Join-Path (Get-LeaseDir -RunId $run) "lease.json") -Raw -Encoding UTF8
        $verifyComplete = Invoke-Transition -Mode Verify -RunId $run
        Write-Utf8File -Path $targetPath -Content "class Fixture { String apiKey = configuration.getApiKey(); }`n"
        $missingEvidence = Invoke-Transition -Mode Complete -RunId $run
        Test-Contract "complete rejects unattested results without false-positive secret assignment" ($missingEvidence.exitCode -ne 0 -and $missingEvidence.json.failureClassification -eq "verification-evidence-missing") $missingEvidence.text
        $badEvidence = New-VerificationEvidence -RunId $run -RelativeTarget $targetRelative -TargetPath $targetPath -HashOverride ("0" * 64)
        $badComplete = Invoke-Transition -Mode Complete -RunId $run -Evidence $badEvidence
        Test-Contract "complete rejects a mismatched verified postimage" ($badComplete.exitCode -ne 0 -and $badComplete.json.failureClassification -eq "verification-target-hash-mismatch") $badComplete.text
        $goodEvidence = New-VerificationEvidence -RunId $run -RelativeTarget $targetRelative -TargetPath $targetPath
        $complete = Invoke-Transition -Mode Complete -RunId $run -Evidence $goodEvidence
        Test-Contract "complete records one changed postimage and releases the lease" (
            $prepareComplete.exitCode -eq 0 -and $verifyComplete.exitCode -eq 0 -and $complete.exitCode -eq 0 -and
            $complete.json.changedFileCount -eq 1 -and $complete.json.leaseReleased -eq $true
        ) $complete.text
        $completeReplay = Invoke-Transition -Mode Complete -RunId $run -Evidence $goodEvidence
        Test-Contract "complete is idempotent after an acknowledged terminal transition" (
            $completeReplay.exitCode -eq 0 -and $completeReplay.json.idempotentReplay -eq $true -and $completeReplay.json.leaseReleased -eq $true
        ) $completeReplay.text

        $completedLease = $completedLeaseText | ConvertFrom-Json
        $completedLease.expiresAtUtc = "2000-01-01T00:00:00Z"
        $completedLease.expiresAt = "2000-01-01T00:00:00Z"
        $staleLeaseDir = Get-LeaseDir -RunId $run
        New-Item -ItemType Directory -Path $staleLeaseDir | Out-Null
        Write-Utf8File -Path (Join-Path $staleLeaseDir "lease.json") -Content (($completedLease | ConvertTo-Json -Depth 10) + "`n")

        $cleanupRun = "$runPrefix-02-cleanup"; $createdRuns.Add($cleanupRun)
        $cleanupPrepare = Invoke-Prepare -RunId $cleanupRun
        Test-Contract "expired unknown owner still protects its declared target" (
            $cleanupPrepare.exitCode -ne 0 -and (Test-Path -LiteralPath $staleLeaseDir)
        ) $cleanupPrepare.text
        Test-Contract "expiry alone creates no deletion receipt" (
            -not (Test-Path -LiteralPath (Join-Path (Get-SessionDir -RunId $run) 'lease-cleanup.json'))
        )
        Remove-ExactTestPath -Path $staleLeaseDir -AllowedParents @($leaseParent) -Recurse

        $corruptRun = "$runPrefix-02-corrupt"; $createdRuns.Add($corruptRun)
        $corruptDir = Get-LeaseDir -RunId $corruptRun
        New-Item -ItemType Directory -Path $corruptDir | Out-Null
        Write-Utf8File -Path (Join-Path $corruptDir 'lease.json') -Content '{broken-json'
        (Get-Item -LiteralPath $corruptDir).LastWriteTimeUtc = [datetime]'2000-01-01T00:00:00Z'
        $blockedRun = "$runPrefix-02-corrupt-blocked"; $createdRuns.Add($blockedRun)
        $blocked = Invoke-Prepare -RunId $blockedRun
        Test-Contract "old corrupt scope is preserved until ownership can be proven" (
            $blocked.exitCode -ne 0 -and (Test-Path -LiteralPath $corruptDir)
        ) $blocked.text
        Remove-ExactTestPath -Path $corruptDir -AllowedParents @($leaseParent) -Recurse
        $resumedRun = "$runPrefix-02-resumed"; $createdRuns.Add($resumedRun)
        $resumed = Invoke-Prepare -RunId $resumedRun
        $resumedAbort = Invoke-Transition -Mode Abort -RunId $resumedRun
        Test-Contract "known fixture cleanup restores the normal lease lifecycle" (
            $resumed.exitCode -eq 0 -and $resumedAbort.exitCode -eq 0 -and $resumedAbort.json.leaseReleased
        ) $resumedAbort.text
        Write-Utf8File -Path $targetPath -Content $original

        $run = "$runPrefix-03"
        $outsideTarget = Join-Path $wrongRoot "outside.java"
        Write-Utf8File -Path $outsideTarget -Content "class Outside {}`n"
        $escape = Invoke-Prepare -RunId $run -Target $outsideTarget
        Test-Contract "prepare rejects source writes outside MacSrc" ($escape.exitCode -ne 0 -and $escape.json.failureClassification -eq "target-outside-macsrc") $escape.text
        $wrong = Invoke-Guard -Arguments @(
            "-Mode", "Prepare", "-Root", $wrongRoot, "-RunId", "$runPrefix-04", "-OwnerId", $ownerId,
            "-TargetFiles", "main\java\Wrong.java", "-BoundaryEvidenceFiles", "build.gradle.kts", "-WatchRoots", "main\java"
        )
        Test-Contract "prepare rejects a OneDrive-like or arbitrary source root" ($wrong.exitCode -ne 0 -and $wrong.json.failureClassification -eq "macsrc-root-mismatch") $wrong.text

        $existingTopLevelPatches = @(Get-ChildItem -LiteralPath (Join-Path $canonicalRoot "__patch_drop__") -File -Filter "*.patch" -Force)
        Test-Contract "pending-queue test starts without unrelated top-level patches" ($existingTopLevelPatches.Count -eq 0) ("count=" + $existingTopLevelPatches.Count)
        if ($existingTopLevelPatches.Count -eq 0) {
            $patchTarget = $targetRelative.Replace("\", "/")
            Write-Utf8File -Path $pendingPatch -Content "diff --git a/$patchTarget b/$patchTarget`n--- a/$patchTarget`n+++ b/$patchTarget`n"
            $blocked = Invoke-Prepare -RunId "$runPrefix-05"
            Test-Contract "overlapping PatchDrop blocks direct mode" ($blocked.exitCode -ne 0 -and $blocked.json.failureClassification -eq "patch-drop-overlap") $blocked.text
            $overlap = Invoke-Prepare -RunId "$runPrefix-05b" -AllowPending
            Test-Contract "queue acknowledgement cannot bypass a watched-path overlap" ($overlap.exitCode -ne 0 -and $overlap.json.failureClassification -eq "patch-drop-overlap") $overlap.text
            Write-Utf8File -Path $pendingPatch -Content "diff --git a/docs/contract-unrelated.md b/docs/contract-unrelated.md`n--- a/docs/contract-unrelated.md`n+++ b/docs/contract-unrelated.md`ndiff --git a/$patchTarget b/$patchTarget`nBinary files a/$patchTarget and b/$patchTarget differ`n"
            $binaryOverlap = Invoke-Prepare -RunId "$runPrefix-05c" -AllowPending
            Test-Contract "binary or rename-style sections cannot hide an overlapping path" ($binaryOverlap.exitCode -ne 0 -and $binaryOverlap.json.failureClassification -eq "patch-drop-overlap") $binaryOverlap.text
            Write-Utf8File -Path $pendingPatch -Content "diff --git a/docs/contract-unrelated.md b/docs/contract-unrelated.md`n--- a/docs/contract-unrelated.md`n+++ b/docs/contract-unrelated.md`n"
            $run = "$runPrefix-06"; $createdRuns.Add($run)
            $ack = Invoke-Prepare -RunId $run -AllowPending
            Test-Contract "explicit queue acknowledgement preserves operator flexibility" ($ack.exitCode -eq 0 -and $ack.json.pendingPatchQueueAcknowledged -eq $true) $ack.text
            $ackAbort = Invoke-Transition -Mode Abort -RunId $run
            Test-Contract "acknowledged queue session can abort safely" ($ackAbort.exitCode -eq 0) $ackAbort.text
            Remove-ExactTestPath -Path $pendingPatch -AllowedParents @((Join-Path $canonicalRoot "__patch_drop__"))
        }

        $run = "$runPrefix-07"; $createdRuns.Add($run)
        $first = Invoke-Prepare -RunId $run
        $second = Invoke-Prepare -RunId "$runPrefix-08"
        Test-Contract "a live lease blocks a competing direct patch" ($first.exitCode -eq 0 -and $second.exitCode -ne 0 -and $second.json.failureClassification -eq "source-lease-blocked") $second.text
        [void](Invoke-Transition -Mode Abort -RunId $run)

        $run = "$runPrefix-09"; $createdRuns.Add($run)
        $secretPrepare = Invoke-Prepare -RunId $run
        $secretVerify = Invoke-Transition -Mode Verify -RunId $run
        $fakeSecret = "sk-" + ("Q" * 24)
        Write-Utf8File -Path $targetPath -Content ("class Fixture { String value = `"$fakeSecret`"; }`n")
        $secretComplete = Invoke-Transition -Mode Complete -RunId $run
        Test-Contract "secret-bearing completion fails closed and retains the lease" (
            $secretPrepare.exitCode -eq 0 -and $secretVerify.exitCode -eq 0 -and $secretComplete.exitCode -ne 0 -and
            $secretComplete.json.failureClassification -eq "secret-leak-risk" -and $secretComplete.json.leaseReleased -eq $false
        ) $secretComplete.text
        Test-Contract "secret diagnostics are count-only" (-not $secretComplete.text.Contains($fakeSecret))
        Write-Utf8File -Path $targetPath -Content $original
        [void](Invoke-Transition -Mode Abort -RunId $run)

        $run = "$runPrefix-10"; $createdRuns.Add($run)
        $tamperPrepare = Invoke-Prepare -RunId $run
        $tamperSessionPath = Join-Path (Get-SessionDir -RunId $run) "session.json"
        $tamperSession = Get-Content -LiteralPath $tamperSessionPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $tamperSession.targets[0].relativePath = $siblingRelative
        Write-Utf8File -Path $tamperSessionPath -Content (($tamperSession | ConvertTo-Json -Depth 20) + "`n")
        $tamperVerify = Invoke-Transition -Mode Verify -RunId $run
        Test-Contract "mutable session target tampering is rejected" (
            $tamperPrepare.exitCode -eq 0 -and $tamperVerify.exitCode -ne 0 -and $tamperVerify.json.failureClassification -eq "session-integrity-mismatch"
        ) $tamperVerify.text
        Remove-ExactTestPath -Path (Get-LeaseDir -RunId $run) -AllowedParents @($leaseParent) -Recurse

        $run = "$runPrefix-11"; $createdRuns.Add($run)
        $largePrepare = Invoke-Prepare -RunId $run
        $largeVerify = Invoke-Transition -Mode Verify -RunId $run
        $largeSecret = "sbp_" + ("R" * 24)
        $privateKeyMarker = "-----BEGIN " + "PRIVATE KEY-----"
        Write-Utf8File -Path $targetPath -Content (("x" * 2200000) + $largeSecret + "`n" + $privateKeyMarker)
        $largeComplete = Invoke-Transition -Mode Complete -RunId $run
        Test-Contract "multi-megabyte Supabase/private-key scan fails closed" (
            $largePrepare.exitCode -eq 0 -and $largeVerify.exitCode -eq 0 -and $largeComplete.exitCode -ne 0 -and $largeComplete.json.failureClassification -eq "secret-leak-risk"
        ) $largeComplete.text
        Test-Contract "large secret diagnostics are count-only" (-not $largeComplete.text.Contains($largeSecret))
        Write-Utf8File -Path $targetPath -Content $original
        [void](Invoke-Transition -Mode Abort -RunId $run)

        $run = "$runPrefix-12"; $createdRuns.Add($run)
        $rollbackPrepare = Invoke-Prepare -RunId $run
        $rollbackVerify = Invoke-Transition -Mode Verify -RunId $run
        Write-Utf8File -Path $targetPath -Content "class Fixture { int unverified; }`n"
        $rollbackAbort = Invoke-Transition -Mode Abort -RunId $run
        Test-Contract "abort retains the lease until rollback is proven" (
            $rollbackPrepare.exitCode -eq 0 -and $rollbackVerify.exitCode -eq 0 -and $rollbackAbort.exitCode -ne 0 -and
            $rollbackAbort.json.failureClassification -eq "rollback-required" -and $rollbackAbort.json.leaseReleased -eq $false
        ) $rollbackAbort.text
        Write-Utf8File -Path $targetPath -Content $original
        $rollbackDone = Invoke-Transition -Mode Abort -RunId $run
        Test-Contract "abort releases the lease after byte-identical rollback" ($rollbackDone.exitCode -eq 0 -and $rollbackDone.json.leaseReleased -eq $true) $rollbackDone.text

        $run = "$runPrefix-13"; $createdRuns.Add($run)
        $expiredPrepare = Invoke-Prepare -RunId $run
        $expiredLeasePath = Join-Path (Get-LeaseDir -RunId $run) "lease.json"
        $expiredLease = Get-Content -LiteralPath $expiredLeasePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $expiredLease.expiresAtUtc = "2000-01-01T00:00:00Z"
        $expiredLease.expiresAt = "2000-01-01T00:00:00Z"
        Write-Utf8File -Path $expiredLeasePath -Content (($expiredLease | ConvertTo-Json -Depth 10) + "`n")
        $expiredAbort = Invoke-Transition -Mode Abort -RunId $run
        Test-Contract "owner can clean an expired unchanged lease" ($expiredPrepare.exitCode -eq 0 -and $expiredAbort.exitCode -eq 0 -and $expiredAbort.json.leaseReleased -eq $true) $expiredAbort.text

        $run = "$runPrefix-14"; $createdRuns.Add($run)
        $undeclaredPrepare = Invoke-Prepare -RunId $run
        $undeclaredVerify = Invoke-Transition -Mode Verify -RunId $run
        Write-Utf8File -Path $targetPath -Content "class Fixture { int declared; }`n"
        Write-Utf8File -Path $siblingPath -Content "class Sibling { int undeclared; }`n"
        $undeclaredComplete = Invoke-Transition -Mode Complete -RunId $run
        Test-Contract "target-scoped completion still requires verification evidence" (
            $undeclaredPrepare.exitCode -eq 0 -and $undeclaredVerify.exitCode -eq 0 -and $undeclaredComplete.exitCode -ne 0 -and
            $undeclaredComplete.json.failureClassification -eq "verification-evidence-missing"
        ) $undeclaredComplete.text
        $undeclaredAbort = Invoke-Transition -Mode Abort -RunId $run
        Test-Contract "abort retains the lease while its declared target remains changed" (
            $undeclaredAbort.exitCode -ne 0 -and $undeclaredAbort.json.failureClassification -eq "rollback-required" -and
            $undeclaredAbort.json.leaseReleased -eq $false
        ) $undeclaredAbort.text
        Write-Utf8File -Path $targetPath -Content $original
        $scopedAbort = Invoke-Transition -Mode Abort -RunId $run
        Test-Contract "restored declared target can abort while a sibling remains changed" ($scopedAbort.exitCode -eq 0 -and $scopedAbort.json.leaseReleased -eq $true) $scopedAbort.text
        Write-Utf8File -Path $siblingPath -Content $siblingOriginal

        $globalConfigTextAfter = (@(& git config --global --list --show-origin 2>$null) -join "`n")
        $globalConfigHashAfter = Get-TextSha256 -Text $globalConfigTextAfter
        Test-Contract "guard never changes global Git trust/configuration" ($globalConfigHashBefore -ceq $globalConfigHashAfter)
    } finally {
        if (Test-Path -LiteralPath $pendingPatch) { Remove-ExactTestPath -Path $pendingPatch -AllowedParents @((Join-Path $canonicalRoot "__patch_drop__")) }
        foreach ($runId in $createdRuns) {
            Remove-ExactTestPath -Path (Get-LeaseDir -RunId $runId) -AllowedParents @($leaseParent) -Recurse
            Remove-ExactTestPath -Path (Get-SessionDir -RunId $runId) -AllowedParents @($sessionParent) -Recurse
        }
        Remove-ExactTestPath -Path $fixtureRoot -AllowedParents @($fixtureParent) -Recurse
        if (Test-Path -LiteralPath $wrongRoot) { Remove-Item -LiteralPath $wrongRoot -Recurse -Force }
    }
}

Write-Host "[SUMMARY] pass=$script:Pass fail=$script:Fail"
if ($script:Fail -gt 0) { exit 1 }

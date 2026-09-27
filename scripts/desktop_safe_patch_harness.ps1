param(
    [string]$Root = "",
    [string]$ReportPath = "",
    [switch]$NoWrite,
    [switch]$DeepScan,
    [switch]$RunSourceGovernance
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot '..\__patch_drop__\source_edit_lease_contract.ps1')

$SecretPattern = "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|-----BEGIN (?:RSA |EC |OPENSSH |PRIVATE )?PRIVATE KEY-----"
$LangChainPattern = "dev\.langchain4j:[^:`"'\s]+:([^`"'\s)]+)"
$PromptRiskPattern = "UserMessage\.from\(\s*prompt\s*\)|generate\(\s*prompt\b|String\s+prompt\s*="
$CancelRiskPattern = "invokeAll\s*\(|cancel\s*\(\s*true\s*\)"
$FailSoftTokens = @(
    "outCount",
    "stageCountsSelectedFromOut",
    "starvationFallback.poolSafeEmpty",
    "cacheOnly.merged.count",
    "web.",
    "skipped.reason",
    "tracePool.size",
    "rescueMerge.used",
    "starvationFallback.trigger",
    "poolSafeEmpty",
    "Retry-After",
    "disabledReason"
)

function Resolve-HarnessRoot {
    param([string]$Candidate)
    if ([string]::IsNullOrWhiteSpace($Candidate)) {
        $Candidate = Join-Path $PSScriptRoot ".."
    }
    return [IO.Path]::GetFullPath($Candidate)
}

function Join-Root {
    param([string]$Base, [string]$Child)
    return [IO.Path]::GetFullPath((Join-Path $Base $Child))
}

function Read-Text {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ""
    }
    try {
        return [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false))
    } catch {
        return ""
    }
}

function Convert-ToGradleStructuralText {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) {
        return ""
    }

    $masked = [regex]::Replace($Text, '(?s)"""(?:.*?"""|.*\z)', ' ')
    $masked = [regex]::Replace($masked, "'(?:\\u[0-9A-Fa-f]{4}|\\.|[^'\\])'", ' ')
    $masked = [regex]::Replace($masked, '"(?:\\.|[^"\\])*"', {
        param($match)
        if ($match.Value -ceq '"main/java"' -or
            $match.Value -ceq '"main/resources"' -or
            $match.Value -ceq '"src/main/java_clean"') {
            return $match.Value
        }
        return ' '
    })
    while ($true) {
        $withoutInnermostComment = [regex]::Replace(
            $masked,
            '(?s)/\*(?:(?!/\*|\*/).)*\*/',
            ' '
        )
        if ($withoutInnermostComment -ceq $masked) {
            break
        }
        $masked = $withoutInnermostComment
    }
    $masked = [regex]::Replace($masked, '(?s)/\*.*\z', ' ')
    $masked = [regex]::Replace($masked, '(?m)//[^\r\n]*', ' ')
    return $masked
}

function Get-BracedBody {
    param(
        [string]$Text,
        [int]$OpenBraceIndex
    )
    if ($OpenBraceIndex -lt 0 -or $OpenBraceIndex -ge $Text.Length -or $Text[$OpenBraceIndex] -ne '{') {
        return $null
    }

    $depth = 0
    for ($i = $OpenBraceIndex; $i -lt $Text.Length; $i++) {
        if ($Text[$i] -eq '{') {
            $depth++
        } elseif ($Text[$i] -eq '}') {
            $depth--
            if ($depth -eq 0) {
                return $Text.Substring($OpenBraceIndex + 1, $i - $OpenBraceIndex - 1)
            }
        }
    }
    return $null
}

function Get-BraceDepthBefore {
    param(
        [string]$Text,
        [int]$Position
    )
    $depth = 0
    for ($i = 0; $i -lt $Position; $i++) {
        if ($Text[$i] -eq '{') {
            $depth++
        } elseif ($Text[$i] -eq '}') {
            $depth--
        }
    }
    return $depth
}

function Test-PatternAtBraceDepthZero {
    param(
        [string]$Text,
        [string]$Pattern
    )
    foreach ($match in [regex]::Matches($Text, $Pattern)) {
        if ((Get-BraceDepthBefore $Text $match.Index) -eq 0) {
            return $true
        }
    }
    return $false
}

function Test-GradleMainSourceDeclaration {
    param(
        [string]$StructuralText,
        [string]$PathMarker,
        [ValidateSet('java', 'resources')][string]$SourceKind,
        [ValidateSet('srcDirs', 'setSrcDirs')][string]$MethodName = 'srcDirs'
    )
    $escapedMarker = [regex]::Escape($PathMarker)
    $escapedKind = [regex]::Escape($SourceKind)
    $escapedMethod = [regex]::Escape($MethodName)
    $srcDirsCallPattern = $escapedMethod + '\s*\(\s*(?:' + $escapedMarker + '|listOf\s*\(\s*' + $escapedMarker + '\s*\))\s*\)'
    $unqualifiedCallPattern = '(?<![\w.])' + $srcDirsCallPattern
    $directReceiverPattern = '(?<![\w.])' + $escapedKind + '\s*\.\s*' + $srcDirsCallPattern
    $kindBlockPattern = '(?<![\w.])' + $escapedKind + '\s*\{'
    foreach ($sourceSetsMatch in [regex]::Matches($StructuralText, '(?<![\w.])sourceSets\s*\{')) {
        $sourceSetsOpen = $StructuralText.IndexOf('{', $sourceSetsMatch.Index)
        $sourceSetsBody = Get-BracedBody $StructuralText $sourceSetsOpen
        if ($null -eq $sourceSetsBody) {
            continue
        }
        foreach ($mainMatch in [regex]::Matches($sourceSetsBody, '(?<![\w.])(?:val\s+)?main(?:\s+by\s+getting)?\s*\{')) {
            if ((Get-BraceDepthBefore $sourceSetsBody $mainMatch.Index) -ne 0) {
                continue
            }
            $mainOpen = $sourceSetsBody.IndexOf('{', $mainMatch.Index)
            $mainBody = Get-BracedBody $sourceSetsBody $mainOpen
            if ($null -eq $mainBody) {
                continue
            }
            if (Test-PatternAtBraceDepthZero $mainBody $directReceiverPattern) {
                return $true
            }
            foreach ($kindBlockMatch in [regex]::Matches($mainBody, $kindBlockPattern)) {
                if ((Get-BraceDepthBefore $mainBody $kindBlockMatch.Index) -ne 0) {
                    continue
                }
                $kindOpen = $mainBody.IndexOf('{', $kindBlockMatch.Index)
                $kindBody = Get-BracedBody $mainBody $kindOpen
                if ($null -ne $kindBody -and (Test-PatternAtBraceDepthZero $kindBody $unqualifiedCallPattern)) {
                    return $true
                }
            }
        }
    }
    return $false
}

function Convert-ToRel {
    param([string]$Base, [string]$Path)
    $baseFull = [IO.Path]::GetFullPath($Base).TrimEnd("\", "/") + [IO.Path]::DirectorySeparatorChar
    $pathFull = [IO.Path]::GetFullPath($Path)
    if ($pathFull.StartsWith($baseFull, [StringComparison]::OrdinalIgnoreCase)) {
        return $pathFull.Substring($baseFull.Length).Replace("\", "/")
    }
    return $pathFull.Replace("\", "/")
}

function Add-Finding {
    param(
        [System.Collections.Generic.List[object]]$List,
        [string]$Class,
        [string]$Severity,
        [string]$Path,
        [string]$Detail
    )
    $List.Add([pscustomobject]@{
        class = $Class
        severity = $Severity
        path = $Path
        detail = $Detail
    }) | Out-Null
}

function Add-EvidenceNeeded {
    param([System.Collections.Generic.List[string]]$List, [string]$Text)
    $List.Add($Text) | Out-Null
}

function Get-RepoFiles {
    param(
        [string]$Base,
        [string[]]$Roots,
        [string[]]$Extensions
    )
    $files = @()
    foreach ($relative in $Roots) {
        $absolute = Join-Root $Base $relative
        if (-not (Test-Path -LiteralPath $absolute -PathType Container)) {
            continue
        }
        $items = Get-ChildItem -LiteralPath $absolute -Recurse -File -ErrorAction SilentlyContinue
        foreach ($item in $items) {
            if ($Extensions.Count -eq 0 -or ($Extensions -contains $item.Extension.ToLowerInvariant())) {
                $files += $item
            }
        }
    }
    return $files
}

function Count-RegexInFiles {
    param(
        [IO.FileInfo[]]$Files,
        [string]$Pattern
    )
    $count = 0
    $byPath = @{}
    foreach ($file in $Files) {
        $text = Read-Text $file.FullName
        if ([string]::IsNullOrEmpty($text)) {
            continue
        }
        $matches = [regex]::Matches($text, $Pattern)
        if ($matches.Count -gt 0) {
            $count += $matches.Count
            $byPath[$file.FullName] = $matches.Count
        }
    }
    return [pscustomobject]@{
        count = $count
        paths = $byPath.GetEnumerator() |
            Sort-Object -Property @{ Expression = "Value"; Descending = $true }, Name |
            Select-Object -First 12 |
            ForEach-Object { [pscustomobject]@{ path = $_.Name; count = $_.Value } }
    }
}

function Convert-CountMapToRows {
    param(
        [hashtable]$Counts
    )
    return @($Counts.GetEnumerator() |
        Sort-Object -Property @{ Expression = "Value"; Descending = $true }, Name |
        Select-Object -First 12 |
        ForEach-Object { [pscustomobject]@{ path = $_.Name.Replace("\", "/"); count = $_.Value } })
}

function Test-CommentLikeJavaLine {
    param([string]$Line)
    $trimmed = $Line.TrimStart()
    return ($trimmed.StartsWith("//") -or
        $trimmed.StartsWith("*") -or
        $trimmed.StartsWith("/*"))
}

function Test-CancellationGuardedSurface {
    param(
        [string]$RelativePath,
        [string]$Text
    )
    if ($RelativePath -match 'CancelShield|ContextPropagatingExecutorService|ContextAwareExecutorService|EvidenceListTraceInjectionAspect|NovaOpsStabilizationAutoConfiguration|NovaReactorDroppedErrorHook') {
        return $true
    }
    return [regex]::IsMatch($Text, 'CancelShield|cancel\s*\(\s*false\s*\)|Do NOT cancel\s*\(\s*true\s*\)|avoid ExecutorService\.invokeAll', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
}

function Get-CancellationRiskClassification {
    param(
        [string]$Base,
        [IO.FileInfo[]]$Files,
        [string]$SignalPattern
    )
    $signalTotal = 0
    $untriagedTotal = 0
    $signalByPath = @{}
    $untriagedByPath = @{}

    foreach ($file in $Files) {
        $text = Read-Text $file.FullName
        if ([string]::IsNullOrEmpty($text)) {
            continue
        }

        $relative = Convert-ToRel $Base $file.FullName
        $signalCount = [regex]::Matches($text, $SignalPattern).Count
        if ($signalCount -gt 0) {
            $signalTotal += $signalCount
            $signalByPath[$relative] = $signalCount
        }

        $guardedSurface = Test-CancellationGuardedSurface $relative $text
        $lineRisk = 0
        foreach ($line in [regex]::Split($text, "\r?\n")) {
            if (Test-CommentLikeJavaLine $line) {
                continue
            }
            if ([regex]::IsMatch($line, '\.\s*cancel\s*\(\s*true\s*\)')) {
                $lineRisk++
                continue
            }
            if (-not $guardedSurface -and
                [regex]::IsMatch($line, '\.\s*invokeAll\s*\(') -and
                [regex]::IsMatch($line, ',\s*[^,]+,\s*[^,]+\)')) {
                $lineRisk++
            }
        }

        if ($lineRisk -gt 0) {
            $untriagedTotal += $lineRisk
            $untriagedByPath[$relative] = $lineRisk
        }
    }

    return [pscustomobject]@{
        signalCount = $signalTotal
        guardedOrDiagnosticCount = [Math]::Max(0, $signalTotal - $untriagedTotal)
        untriagedCount = $untriagedTotal
        signalPaths = Convert-CountMapToRows $signalByPath
        untriagedPaths = Convert-CountMapToRows $untriagedByPath
    }
}

function Get-PatternMetric {
    param(
        [string]$Base,
        [string[]]$Roots,
        [string]$Pattern,
        [string[]]$Globs = @()
    )
    $rg = Get-Command rg -ErrorAction SilentlyContinue
    if ($rg) {
        $args = @("--count-matches", "--no-heading", "--color", "never")
        foreach ($glob in $Globs) {
            $args += @("-g", $glob)
        }
        $args += @("--", $Pattern)
        foreach ($root in $Roots) {
            if (Test-Path -LiteralPath (Join-Root $Base $root)) {
                $args += $root
            }
        }
        if ($args.Count -le 6) {
            return [pscustomobject]@{ count = 0; paths = @() }
        }
        Push-Location $Base
        try {
            $output = & rg @args 2>$null
        } finally {
            Pop-Location
        }
        $rows = New-Object System.Collections.Generic.List[object]
        $total = 0
        foreach ($line in @($output)) {
            $text = $line.ToString()
            $idx = $text.LastIndexOf(":")
            if ($idx -le 0) {
                continue
            }
            $path = $text.Substring(0, $idx)
            $countText = $text.Substring($idx + 1)
            $count = 0
            if ([int]::TryParse($countText, [ref]$count)) {
                $total += $count
                $rows.Add([pscustomobject]@{ path = $path.Replace("\", "/"); count = $count }) | Out-Null
            }
        }
        return [pscustomobject]@{
            count = $total
            paths = @($rows | Sort-Object -Property @{ Expression = "count"; Descending = $true }, path | Select-Object -First 12)
        }
    }

    $extensions = @()
    foreach ($glob in $Globs) {
        if ($glob -match '^\*\.(.+)$') {
            $extensions += ("." + $Matches[1].ToLowerInvariant())
        }
    }
    $files = @(Get-RepoFiles $Base $Roots $extensions)
    $metric = Count-RegexInFiles $files $Pattern
    return [pscustomobject]@{
        count = $metric.count
        paths = @($metric.paths | ForEach-Object { [pscustomobject]@{ path = Convert-ToRel $Base $_.path; count = $_.count } })
    }
}

function Get-ExistingFiles {
    param(
        [string]$Base,
        [string[]]$RelativePaths
    )
    $files = @()
    foreach ($relative in $RelativePaths) {
        $absolute = Join-Root $Base $relative
        if (Test-Path -LiteralPath $absolute -PathType Leaf) {
            $files += Get-Item -LiteralPath $absolute
        }
    }
    return $files
}

function Invoke-GitRead {
    param([string]$Base, [string[]]$GitArgs)
    Push-Location $Base
    try {
        $output = & git --no-optional-locks @GitArgs 2>&1
        $exitCode = $LASTEXITCODE
        return [pscustomobject]@{
            exitCode = $exitCode
            lines = @($output | ForEach-Object { $_.ToString() })
        }
    } catch {
        return [pscustomobject]@{
            exitCode = 1
            lines = @($_.Exception.Message)
        }
    } finally {
        Pop-Location
    }
}

$rootPath = Resolve-HarnessRoot $Root
$findings = New-Object System.Collections.Generic.List[object]
$evidenceNeeded = New-Object System.Collections.Generic.List[string]
$observations = @{}

$observations["generatedUtc"] = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$observations["root"] = $rootPath
$observations["noWrite"] = [bool]$NoWrite

if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) {
    throw "Root does not exist: $rootPath"
}

$driveName = ([IO.Path]::GetPathRoot($rootPath)).TrimEnd("\").TrimEnd(":")
try {
    $drive = Get-PSDrive -Name $driveName -ErrorAction Stop
    $observations["driveRoot"] = $drive.Root
    $observations["driveDisplayRoot"] = $drive.DisplayRoot
    if ($drive.DisplayRoot -like "\\*") {
        Add-Finding $findings "smb-path" "INFO" $rootPath "root is on a UNC-backed drive; only Desktop owner should edit active source"
    }
} catch {
    $observations["driveRoot"] = ""
    $observations["driveDisplayRoot"] = ""
}

$git = Get-Command git -ErrorAction SilentlyContinue
$observations["gitAvailable"] = [bool]$git
$observations["gitMetadataAvailable"] = $false
if ($git) {
    $topLevel = Invoke-GitRead $rootPath @("rev-parse", "--show-toplevel")
    if ($topLevel.exitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace(($topLevel.lines -join ""))) {
        $observations["gitMetadataAvailable"] = $true
        $observations["gitTopLevel"] = $topLevel.lines -join "`n"
        $observations["gitBranch"] = (Invoke-GitRead $rootPath @("branch", "--show-current")).lines -join "`n"
        $status = (Invoke-GitRead $rootPath @("status", "--short")).lines
        $observations["gitStatusShortCount"] = @($status | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
        $observations["gitWorktreeList"] = (Invoke-GitRead $rootPath @("worktree", "list")).lines -join "`n"
    } else {
        $observations["gitTopLevel"] = "unavailable"
        $observations["gitBranch"] = ""
        $observations["gitStatusShortCount"] = 0
        $observations["gitWorktreeList"] = ""
        Add-EvidenceNeeded $evidenceNeeded "git metadata unavailable at root / verify with git rev-parse --show-toplevel from Desktop canonical root"
    }
} else {
    Add-EvidenceNeeded $evidenceNeeded "git executable/PATH unavailable / verify from Desktop with git status --short"
}

$gitOperationEvidence = Get-AwxGitOperationEvidence -ProjectRoot $rootPath
$observations['operationDecision'] = Get-AwxScopedOperationDecision -Operation read-only -GitEvidence $gitOperationEvidence
$observations['repositoryWideHold'] = $false
if ($gitOperationEvidence.indexLockPresent) {
    Add-Finding $findings "index-lock-conflict" "INFO" "git-index" "Index writes remain held; this read-only audit proceeds. Source edits require the scoped owner/lease/preimage guard."
}

$settings = Join-Root $rootPath "settings.gradle"
$rootBuild = Join-Root $rootPath "build.gradle.kts"
$appBuild = Join-Root $rootPath "app/build.gradle.kts"
$gradlewBat = Join-Root $rootPath "gradlew.bat"
$rootBuildText = Read-Text $rootBuild
$appBuildText = Read-Text $appBuild
$combinedBuildText = $rootBuildText + "`n" + $appBuildText
$rootBuildStructuralText = Convert-ToGradleStructuralText $rootBuildText
$appBuildStructuralText = Convert-ToGradleStructuralText $appBuildText

$observations["files"] = [ordered]@{
    settingsGradle = (Test-Path -LiteralPath $settings -PathType Leaf)
    buildGradleKts = (Test-Path -LiteralPath $rootBuild -PathType Leaf)
    appBuildGradleKts = (Test-Path -LiteralPath $appBuild -PathType Leaf)
    gradlewBat = (Test-Path -LiteralPath $gradlewBat -PathType Leaf)
    sourceGovernanceProofChainV4 = (Test-Path -LiteralPath (Join-Root $rootPath "scripts/source_governance_proof_chain_v4.ps1") -PathType Leaf)
}

if (-not $observations["files"].buildGradleKts) {
    Add-Finding $findings "missing-build-file" "BLOCK" "build.gradle.kts" "root build file missing"
}
if (-not $observations["files"].gradlewBat) {
    Add-EvidenceNeeded $evidenceNeeded "gradlew.bat missing / verify available build command"
}

$sourceSetProof = [ordered]@{
    sourceSetEvidenceKind = "static-structural"
    runtimeSourceSetResolution = "not_observed"
    rootMainJavaDir = (Test-Path -LiteralPath (Join-Root $rootPath "main/java") -PathType Container)
    rootMainResourcesDir = (Test-Path -LiteralPath (Join-Root $rootPath "main/resources") -PathType Container)
    rootBuildDeclaresMainJava = Test-GradleMainSourceDeclaration $rootBuildStructuralText '"main/java"' 'java'
    rootBuildDeclaresMainResources = Test-GradleMainSourceDeclaration $rootBuildStructuralText '"main/resources"' 'resources'
    appJavaCleanDir = (Test-Path -LiteralPath (Join-Root $rootPath "app/src/main/java_clean") -PathType Container)
    appResourcesDir = (Test-Path -LiteralPath (Join-Root $rootPath "app/src/main/resources") -PathType Container)
    appBuildDeclaresJavaClean = Test-GradleMainSourceDeclaration $appBuildStructuralText '"src/main/java_clean"' 'java' 'setSrcDirs'
}
$observations["sourceSetProof"] = $sourceSetProof
Add-EvidenceNeeded $evidenceNeeded "Gradle runtime source-set ownership was not observed; verify with .\gradlew.bat sourceSets --console=plain and the focused test command before applying a source change."

if (-not ($sourceSetProof.rootMainJavaDir -and $sourceSetProof.rootBuildDeclaresMainJava)) {
    Add-Finding $findings "wrong-sourceset" "BLOCK" "main/java" "root main Java static declaration proof incomplete"
}
if (-not ($sourceSetProof.rootMainResourcesDir -and $sourceSetProof.rootBuildDeclaresMainResources)) {
    Add-Finding $findings "wrong-sourceset" "BLOCK" "main/resources" "root main resources static declaration proof incomplete"
}
if ($sourceSetProof.appJavaCleanDir -and -not $sourceSetProof.appBuildDeclaresJavaClean) {
    Add-Finding $findings "wrong-sourceset" "BLOCK" "app/build.gradle.kts" "app java_clean directory exists but its static main declaration was not matched"
}

$langchainVersions = New-Object System.Collections.Generic.List[object]
$gradleStringVars = @{}
foreach ($varMatch in [regex]::Matches($combinedBuildText, '(?m)^\s*(?:const\s+val|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]+)"')) {
    $gradleStringVars[$varMatch.Groups[1].Value] = $varMatch.Groups[2].Value
}
foreach ($match in [regex]::Matches($combinedBuildText, $LangChainPattern)) {
    $rawVersion = $match.Groups[1].Value
    $version = $rawVersion
    $unresolvedVariable = $false
    $variableMatch = [regex]::Match($rawVersion, '^\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?$')
    if ($variableMatch.Success) {
        $variableName = $variableMatch.Groups[1].Value
        if ($gradleStringVars.ContainsKey($variableName)) {
            $version = [string]$gradleStringVars[$variableName]
        } else {
            $unresolvedVariable = $true
            Add-EvidenceNeeded $evidenceNeeded "unresolved LangChain4j Gradle version variable $rawVersion / verify with checkLangchain4jVersionPurity"
        }
    }
    $langchainVersions.Add([pscustomobject]@{ version = $version }) | Out-Null
    if ($version -ne "1.0.1" -and -not $unresolvedVariable) {
        Add-Finding $findings "langchain4j-version-purity" "BLOCK" "build.gradle.kts" "declared dev.langchain4j version is not 1.0.1"
    }
}
$observations["langchain4jDeclaredVersions"] = @($langchainVersions | Group-Object version | ForEach-Object { [pscustomobject]@{ version = $_.Name; count = $_.Count } })

$patchDrop = Join-Root $rootPath "__patch_drop__"
$patchDropInfo = New-Object System.Collections.Generic.List[object]
if (Test-Path -LiteralPath $patchDrop -PathType Container) {
    $topPatches = @(Get-ChildItem -LiteralPath $patchDrop -File -Filter "*.patch" -ErrorAction SilentlyContinue)
    foreach ($patch in $topPatches) {
        $base = [IO.Path]::GetFileNameWithoutExtension($patch.Name)
        $missing = @()
        foreach ($ext in @(".report.md", ".verify.log", ".sha256.txt", ".manifest.json")) {
            if (-not (Test-Path -LiteralPath (Join-Path $patchDrop ($base + $ext)) -PathType Leaf)) {
                $missing += $ext
            }
        }
        $patchDropInfo.Add([pscustomobject]@{
            patch = $patch.Name
            length = $patch.Length
            missingSidecars = $missing
        }) | Out-Null
        if ($missing.Count -gt 0) {
            Add-Finding $findings "missing-bundle-meta" "WARN" ("__patch_drop__/" + $patch.Name) ("missing sidecars: " + ($missing -join ", "))
        }
    }
    if ($topPatches.Count -gt 1) {
        Add-Finding $findings "patch-drop-pending" "WARN" "__patch_drop__" "more than one top-level patch exists; Desktop apply must choose only a manifest-pinned v3 bundle"
    }
} else {
    Add-EvidenceNeeded $evidenceNeeded "PatchDrop directory missing / verify __patch_drop__ path"
}
$observations["patchDrop"] = @($patchDropInfo | ForEach-Object { $_ })

$targetedJavaRelPaths = @(
    "main/java/com/example/lms/service/ChatWorkflow.java",
    "main/java/com/example/lms/api/ChatApiController.java",
    "main/java/com/example/lms/search/provider/HybridWebSearchProvider.java",
    "main/java/com/example/lms/service/NaverSearchService.java",
    "main/java/com/example/lms/service/search/NaverCredentialBridge.java",
    "main/java/com/example/lms/search/TraceStore.java",
    "main/java/com/example/lms/transform/QueryTransformer.java",
    "main/java/com/example/lms/infra/resilience/NightmareBreaker.java",
    "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java",
    "main/java/com/example/lms/service/rag/HybridRetriever.java",
    "main/java/com/example/lms/service/trace/TraceHtmlBuilder.java",
    "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchInterruptHygieneAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/NaverInterruptHygieneAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/ProviderRateLimitBackoffAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/FailSoftQueryAugmentAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/QueryTransformerAnchorTailAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/EvidenceListTraceInjectionAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/EvidenceListSnippetFallbackAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/CleanOutputRedactionAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/OpenAiChatModelGuardAspect.java",
    "main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java",
    "main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorService.java",
    "main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java",
    "main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorServicePostProcessor.java"
)
$targetedJavaFiles = @(Get-ExistingFiles $rootPath $targetedJavaRelPaths)
$targetedConfigFiles = @(Get-RepoFiles $rootPath @("main/resources", "app/src/main/resources") @(".yml", ".yaml", ".properties"))
$observations["scanMode"] = if ($DeepScan) { "deep-active-roots" } else { "targeted-core-files" }
$observations["targetedJavaFilesFound"] = $targetedJavaFiles.Count
if (-not $DeepScan) {
    Add-EvidenceNeeded $evidenceNeeded "deep active-root scan skipped for speed / rerun with -DeepScan from a stable local Desktop root if full prompt/cancel/fail-soft counts are required"
}
if ($targetedJavaFiles.Count -eq 0) {
    Add-Finding $findings "targeted-scan-empty" "WARN" "main/java" "none of the core targeted Java files were found"
}

$activeJavaRoots = @("main/java", "app/src/main/java_clean")
$activeConfigRoots = @("main/java", "app/src/main/java_clean", "main/resources", "app/src/main/resources")

if ($DeepScan) {
    $secretScan = Get-PatternMetric $rootPath $activeConfigRoots $SecretPattern @("*.java", "*.yml", "*.yaml", "*.properties")
} else {
    $secretScan = Count-RegexInFiles @($targetedJavaFiles + $targetedConfigFiles) $SecretPattern
}
$observations["secretPatternHits"] = $secretScan.count
if ($secretScan.count -gt 0) {
    Add-Finding $findings "secret-leak-risk" "BLOCK" "active source/config files" "high-confidence secret pattern hits found; values intentionally not printed"
}

if ($DeepScan) {
    $promptRisk = Get-PatternMetric $rootPath $activeJavaRoots $PromptRiskPattern @("*.java")
    $promptBuilderCount = (Get-PatternMetric $rootPath $activeJavaRoots "PromptBuilder\.build|promptBuilder\.build" @("*.java")).count
} else {
    $promptRisk = Count-RegexInFiles $targetedJavaFiles $PromptRiskPattern
    $promptBuilderCount = (Count-RegexInFiles $targetedJavaFiles "PromptBuilder\.build|promptBuilder\.build").count
}
$observations["promptBoundary"] = [ordered]@{
    promptBuilderBuildCount = $promptBuilderCount
    promptRiskPatternCount = $promptRisk.count
    topPromptRiskPaths = @($promptRisk.paths | ForEach-Object {
        $path = $_.path
        if ([IO.Path]::IsPathRooted($path)) {
            $path = Convert-ToRel $rootPath $path
        }
        [pscustomobject]@{ path = $path; count = $_.count }
    })
}

$cancelFiles = if ($DeepScan) { @(Get-RepoFiles $rootPath $activeJavaRoots @(".java")) } else { @($targetedJavaFiles) }
$cancelRisk = Get-CancellationRiskClassification $rootPath $cancelFiles $CancelRiskPattern
$observations["cancellationRisk"] = [ordered]@{
    riskPatternCount = $cancelRisk.signalCount
    guardedOrDiagnosticCount = $cancelRisk.guardedOrDiagnosticCount
    untriagedRiskCount = $cancelRisk.untriagedCount
    topPaths = @($cancelRisk.untriagedPaths | ForEach-Object {
        $path = $_.path
        if ([IO.Path]::IsPathRooted($path)) {
            $path = Convert-ToRel $rootPath $path
        }
        [pscustomobject]@{ path = $path; count = $_.count }
    })
    signalTopPaths = @($cancelRisk.signalPaths | ForEach-Object {
        $path = $_.path
        if ([IO.Path]::IsPathRooted($path)) {
            $path = Convert-ToRel $rootPath $path
        }
        [pscustomobject]@{ path = $path; count = $_.count }
    })
}
if ($cancelRisk.untriagedCount -gt 0) {
    Add-Finding $findings "cancellation-toxicity-risk" "WARN" "active source" "direct cancel(true) or raw timed invokeAll call found outside guarded cancellation surface"
}

$failSoftPresence = New-Object System.Collections.Generic.List[object]
foreach ($token in $FailSoftTokens) {
    $escaped = [regex]::Escape($token)
    if ($DeepScan) {
        $count = (Get-PatternMetric $rootPath $activeJavaRoots $escaped @("*.java")).count
    } else {
        $count = (Count-RegexInFiles $targetedJavaFiles $escaped).count
    }
    $failSoftPresence.Add([pscustomobject]@{ token = $token; count = $count }) | Out-Null
}
$observations["failSoftSignalPresence"] = @($failSoftPresence | ForEach-Object { $_ })

if ($RunSourceGovernance) {
    $sg = Join-Root $rootPath "scripts/source_governance_proof_chain_v4.ps1"
    if (Test-Path -LiteralPath $sg -PathType Leaf) {
        $observations["sourceGovernanceCommand"] = ".\scripts\source_governance_proof_chain_v4.ps1 -Root .\"
        Add-EvidenceNeeded $evidenceNeeded "source governance not executed by this harness / run .\scripts\source_governance_proof_chain_v4.ps1 -Root .\ for deeper proof"
    } else {
        Add-EvidenceNeeded $evidenceNeeded "source_governance_proof_chain_v4.ps1 missing"
    }
}

$findingRows = @($findings | ForEach-Object { $_ })
$evidenceRows = @($evidenceNeeded | ForEach-Object { $_ })

$result = [ordered]@{
    observations = $observations
    findings = $findingRows
    evidence_needed = $evidenceRows
}

$blockCount = @($findings | Where-Object { $_.severity -eq "BLOCK" }).Count
$warnCount = @($findings | Where-Object { $_.severity -eq "WARN" }).Count
$infoCount = @($findings | Where-Object { $_.severity -eq "INFO" }).Count

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Desktop Safe Patch Harness Report") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("- generatedUtc: $($observations["generatedUtc"])") | Out-Null
$lines.Add("- root: $rootPath") | Out-Null
$lines.Add("- gitAvailable: $($observations["gitAvailable"])") | Out-Null
$lines.Add("- gitMetadataAvailable: $($observations["gitMetadataAvailable"])") | Out-Null
$lines.Add("- driveDisplayRoot: $($observations["driveDisplayRoot"])") | Out-Null
$lines.Add("- scanMode: $($observations["scanMode"])") | Out-Null
$lines.Add("- targetedJavaFilesFound: $($observations["targetedJavaFilesFound"])") | Out-Null
$lines.Add("- blockFindings: $blockCount") | Out-Null
$lines.Add("- warnFindings: $warnCount") | Out-Null
$lines.Add("- infoFindings: $infoCount") | Out-Null
$lines.Add("- secretPatternHits: $($observations["secretPatternHits"])") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("## SourceSet Static Evidence") | Out-Null
foreach ($key in $sourceSetProof.Keys) {
    $lines.Add("- ${key}: $($sourceSetProof[$key])") | Out-Null
}
$lines.Add("") | Out-Null
$lines.Add("## LangChain4j") | Out-Null
if ($observations["langchain4jDeclaredVersions"].Count -eq 0) {
    $lines.Add("- evidence_needed: no dev.langchain4j declarations matched in build files") | Out-Null
} else {
    foreach ($row in $observations["langchain4jDeclaredVersions"]) {
        $lines.Add("- version $($row.version): $($row.count)") | Out-Null
    }
}
$lines.Add("") | Out-Null
$lines.Add("## Prompt Boundary Signals") | Out-Null
$lines.Add("- PromptBuilder.build count: $($observations["promptBoundary"].promptBuilderBuildCount)") | Out-Null
$lines.Add("- prompt risk pattern count: $($observations["promptBoundary"].promptRiskPatternCount)") | Out-Null
foreach ($row in $observations["promptBoundary"].topPromptRiskPaths) {
    $lines.Add("- $($row.path): $($row.count)") | Out-Null
}
$lines.Add("") | Out-Null
$lines.Add("## Cancellation Signals") | Out-Null
$lines.Add("- cancel/invokeAll risk pattern count: $($observations["cancellationRisk"].riskPatternCount)") | Out-Null
$lines.Add("- guarded/diagnostic cancellation signal count: $($observations["cancellationRisk"].guardedOrDiagnosticCount)") | Out-Null
$lines.Add("- untriaged cancellation risk count: $($observations["cancellationRisk"].untriagedRiskCount)") | Out-Null
foreach ($row in $observations["cancellationRisk"].topPaths) {
    $lines.Add("- $($row.path): $($row.count)") | Out-Null
}
if ($observations["cancellationRisk"].topPaths.Count -eq 0) {
    $lines.Add("- untriaged: none") | Out-Null
}
$lines.Add("") | Out-Null
$lines.Add("## Fail-Soft Token Presence") | Out-Null
foreach ($row in $observations["failSoftSignalPresence"]) {
    $lines.Add("- $($row.token): $($row.count)") | Out-Null
}
$lines.Add("") | Out-Null
$lines.Add("## PatchDrop") | Out-Null
if ($observations["patchDrop"].Count -eq 0) {
    $lines.Add("- no top-level patch files observed") | Out-Null
} else {
    foreach ($row in $observations["patchDrop"]) {
        $missingText = if ($row.missingSidecars.Count -eq 0) { "none" } else { $row.missingSidecars -join ", " }
        $lines.Add("- $($row.patch): missingSidecars=$missingText") | Out-Null
    }
}
$lines.Add("") | Out-Null
$lines.Add("## Findings") | Out-Null
if ($findings.Count -eq 0) {
    $lines.Add("- none") | Out-Null
} else {
    foreach ($finding in $findings) {
        $lines.Add("- [$($finding.severity)] $($finding.class) $($finding.path): $($finding.detail)") | Out-Null
    }
}
$lines.Add("") | Out-Null
$lines.Add("## Evidence Needed") | Out-Null
if ($evidenceNeeded.Count -eq 0) {
    $lines.Add("- none") | Out-Null
} else {
    foreach ($item in $evidenceNeeded) {
        $lines.Add("- $item") | Out-Null
    }
}
$lines.Add("") | Out-Null
$lines.Add("## Next") | Out-Null
if ($blockCount -gt 0) {
    $lines.Add("- Resolve BLOCK findings before source edits or Desktop PASS claims.") | Out-Null
} elseif ($warnCount -gt 0) {
    $lines.Add("- Review WARN findings before PatchDrop apply or broad verification.") | Out-Null
} else {
    $lines.Add("- Use this report as preflight evidence, then run the narrowest Gradle/test command for the patch.") | Out-Null
}

$markdown = $lines -join "`n"
if ($NoWrite) {
    Write-Output $markdown
    Write-Output ""
    Write-Output "## JSON"
    Write-Output ($result | ConvertTo-Json -Depth 8)
} else {
    if ([string]::IsNullOrWhiteSpace($ReportPath)) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $ReportPath = Join-Root $rootPath ("__reports__/desktop-safe-patch-harness-" + $stamp + ".md")
    }
    $reportDir = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($reportDir)) {
        New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
    }
    Set-Content -LiteralPath $ReportPath -Value $markdown -Encoding UTF8
    Write-Output "reportPath=$ReportPath"
}

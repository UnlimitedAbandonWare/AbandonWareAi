param(
    [string]$Root = ".",
    [string[]]$Skills = @(
        "demo1-long-think-goal-composer",
        "demo1-desktop-only-proof-loop",
        "demo1-patchdrop-manual-default",
        "demo1-demand-driven-external-proof",
        "demo1-codex-usage-triage",
        "demo1-superpowers-repo-evidence-guard",
        "demo1-skill-family-postprocessor"
    ),
    [string]$Validator = "C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py",
    [string]$ReportPath = "",
    [string]$HistoryDir = "",
    [int]$HistoryRetain = 0,
    [string]$DiscoverPrefix = "",
    [switch]$FailOnDiscoveryIssues,
    [int]$SkillLineBudget = 0,
    [int]$SkillWordBudget = 0,
    [switch]$FailOnBudgetWarnings,
    [int]$TrimCandidateCount = 0,
    [switch]$SkipQuickValidate,
    [switch]$CompactReport,
    [string]$ValidatorSelfTestStatusPath = "",
    [int]$ValidatorSelfTestFreshMinutes = 1440,
    [switch]$Json,
    [switch]$SummaryJson
)

$ErrorActionPreference = "Stop"

function Get-TextOrEmpty {
    param([string]$Path)
    try {
        if (-not (Test-Path -LiteralPath $Path -ErrorAction Stop)) {
            return ""
        }
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction Stop
    } catch {
        return ""
    }
}

function Test-PathOrFalse {
    param([string]$Path)
    try {
        [void](Get-Item -LiteralPath $Path -ErrorAction Stop)
        return $true
    } catch {
        return $false
    }
}

function Get-PathAccessProbe {
    param([string]$Path)
    try {
        [void](Get-Item -LiteralPath $Path -ErrorAction Stop)
        return [ordered]@{
            exists = $true
            status = "present"
            errorType = ""
        }
    } catch {
        $errorType = $_.Exception.GetType().Name
        $status = if ($errorType -eq "UnauthorizedAccessException") { "inaccessible" } else { "missing" }
        return [ordered]@{
            exists = $false
            status = $status
            errorType = $errorType
        }
    }
}

function Get-DirectoryListingProbe {
    param([string]$Path)
    try {
        $items = @(Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop)
        return [ordered]@{
            status = "readable"
            childCount = $items.Count
            errorType = ""
        }
    } catch {
        return [ordered]@{
            status = "inaccessible"
            childCount = 0
            errorType = $_.Exception.GetType().Name
        }
    }
}

function Get-GitTrackedPathInfo {
    param(
        [string]$RepoRoot,
        [string]$RelativePath
    )

    $normalizedPath = $RelativePath -replace "\\", "/"
    try {
        $trackedPaths = @(& git -C $RepoRoot ls-files -- $normalizedPath 2>$null)
        if ($LASTEXITCODE -ne 0) {
            return [ordered]@{
                status = "git-error"
                trackedPathCount = -1
            }
        }

        return [ordered]@{
            status = "checked"
            trackedPathCount = $trackedPaths.Count
        }
    } catch {
        return [ordered]@{
            status = "unavailable"
            trackedPathCount = -1
        }
    }
}

function Get-TextLineCount {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return @($Text -split "\r?\n").Count
}

function Get-TextWordCount {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, "\S+")).Count
}

function Get-SimpleYamlField {
    param(
        [string]$Text,
        [string]$Field
    )

    $pattern = "(?m)^\s*" + [regex]::Escape($Field) + "\s*:\s*(.+?)\s*$"
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        return ""
    }

    $value = $match.Groups[1].Value.Trim()
    if ($value.Length -ge 2) {
        $first = $value.Substring(0, 1)
        $last = $value.Substring($value.Length - 1, 1)
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            return $value.Substring(1, $value.Length - 2)
        }
    }

    return $value
}

function New-CompletionAuditItem {
    param(
        [string]$Requirement,
        [string]$Status,
        [string]$Evidence,
        [string]$VerifyWith
    )

    return [ordered]@{
        requirement = $Requirement
        status = $Status
        evidence = $Evidence
        verifyWith = $VerifyWith
    }
}

function Get-ValidatorSelfTestStatus {
    param(
        [string]$RepoRoot,
        [string]$StatusPath,
        [int]$FreshMinutes
    )

    $verifyWith = "powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root ."
    $maxAgeSeconds = [Math]::Max(0, $FreshMinutes * 60)
    if ([string]::IsNullOrWhiteSpace($StatusPath)) {
        $StatusPath = Join-Path $RepoRoot "data\agent-handoff\skill-family\validator-self-test-status.json"
    } elseif (-not [System.IO.Path]::IsPathRooted($StatusPath)) {
        $StatusPath = Join-Path $RepoRoot $StatusPath
    }

    $resolvedStatusPath = [System.IO.Path]::GetFullPath($StatusPath)
    if (-not (Test-Path -LiteralPath $resolvedStatusPath -ErrorAction SilentlyContinue)) {
        return [ordered]@{
            schemaVersion = "demo1.skill-family-validator-self-test-status-ref.v1"
            status = "missing"
            ok = $false
            statusPath = $resolvedStatusPath
            sourceSchemaVersion = ""
            generatedAtUtc = ""
            freshnessStatus = "missing"
            ageSeconds = -1
            maxAgeSeconds = $maxAgeSeconds
            validatedOptionCount = 0
            fullBytes = 0
            compactBytes = 0
            retainedHistoryCount = 0
            rawSecretPatternHits = 0
            evidenceNeeded = "validator self-test status missing / run .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root ."
            verifyWith = $verifyWith
        }
    }

    try {
        $statusJson = Get-Content -Raw -LiteralPath $resolvedStatusPath -Encoding UTF8 | ConvertFrom-Json
        $statusOk = [bool]$statusJson.ok
        $generatedAtText = [string]$statusJson.generatedAtUtc
        $generatedAtUtc = [DateTime]::Parse($generatedAtText, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
        $ageSeconds = [int][Math]::Max(0, [Math]::Floor(([DateTime]::UtcNow - $generatedAtUtc).TotalSeconds))
        $freshnessStatus = if ($maxAgeSeconds -eq 0 -or $ageSeconds -le $maxAgeSeconds) { "current" } else { "stale" }
        $resultOk = $statusOk -and $freshnessStatus -eq "current"
        $resultStatus = if (-not $statusOk) {
            "evidence_needed"
        } elseif ($freshnessStatus -eq "stale") {
            "stale"
        } else {
            "verified"
        }
        $evidenceNeeded = if (-not $statusOk) {
            "validator self-test status not ok / rerun .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root ."
        } elseif ($freshnessStatus -eq "stale") {
            "validator self-test status stale / rerun .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root ."
        } else {
            ""
        }

        return [ordered]@{
            schemaVersion = "demo1.skill-family-validator-self-test-status-ref.v1"
            status = $resultStatus
            ok = $resultOk
            statusPath = $resolvedStatusPath
            sourceSchemaVersion = [string]$statusJson.schemaVersion
            generatedAtUtc = $generatedAtText
            freshnessStatus = $freshnessStatus
            ageSeconds = $ageSeconds
            maxAgeSeconds = $maxAgeSeconds
            validatedOptionCount = @($statusJson.validatedOptions).Count
            fullBytes = [int]$statusJson.fullBytes
            compactBytes = [int]$statusJson.compactBytes
            retainedHistoryCount = [int]$statusJson.retainedHistoryCount
            rawSecretPatternHits = [int]$statusJson.rawSecretPatternHits
            evidenceNeeded = $evidenceNeeded
            verifyWith = $verifyWith
        }
    } catch {
        return [ordered]@{
            schemaVersion = "demo1.skill-family-validator-self-test-status-ref.v1"
            status = "unreadable"
            ok = $false
            statusPath = $resolvedStatusPath
            sourceSchemaVersion = ""
            generatedAtUtc = ""
            freshnessStatus = "unreadable"
            ageSeconds = -1
            maxAgeSeconds = $maxAgeSeconds
            validatedOptionCount = 0
            fullBytes = 0
            compactBytes = 0
            retainedHistoryCount = 0
            rawSecretPatternHits = 0
            evidenceNeeded = "validator self-test status unreadable / rerun .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root ."
            verifyWith = $verifyWith
        }
    }
}

function Get-SourceControlHint {
    param(
        [string]$GitTrackingStatus,
        [int]$GitTrackedPathCount
    )

    if ($GitTrackingStatus -eq "checked" -and $GitTrackedPathCount -eq 0) {
        return "untracked_inaccessible_candidate"
    }
    if ($GitTrackingStatus -eq "checked" -and $GitTrackedPathCount -gt 0) {
        return "tracked_inaccessible_candidate"
    }
    return "git_tracking_unknown"
}

function New-DiscoveryResolution {
    param(
        [string]$Name,
        [string[]]$Issues,
        [string]$GitTrackingStatus = "not_checked",
        [int]$GitTrackedPathCount = -1
    )

    $sourceControlHint = Get-SourceControlHint -GitTrackingStatus $GitTrackingStatus -GitTrackedPathCount $GitTrackedPathCount

    if ($Issues -contains "inaccessible-skill-directory" -or $Issues -contains "inaccessible-skill-md" -or $Issues -contains "inaccessible-openai-yaml") {
        $recommendedAction = if ($sourceControlHint -eq "untracked_inaccessible_candidate") {
            "manual_review_untracked_inaccessible_skill_dir"
        } elseif ($sourceControlHint -eq "tracked_inaccessible_candidate") {
            "manual_review_tracked_inaccessible_skill_dir"
        } else {
            "manual_review_inaccessible_skill_dir"
        }

        return [ordered]@{
            name = $Name
            recommendedAction = $recommendedAction
            sourceControlHint = $sourceControlHint
            gitTrackingStatus = $GitTrackingStatus
            gitTrackedPathCount = $GitTrackedPathCount
            mutationAllowed = $false
            deleteCommandEmitted = $false
            aclCommandEmitted = $false
            rawAclCaptured = $false
            verifyWith = "Get-Item -Force .agents\skills\$Name; Get-ChildItem -Force .agents\skills\$Name"
            evidenceNeeded = ("manual decision for {0} / sourceControlHint={1}; verify ownership and intended manual follow-up outside this validator; validator emits no mutation command" -f $Name, $sourceControlHint)
            issues = $Issues
        }
    }

    if ($Issues -contains "missing-skill-md") {
        $recommendedAction = if ($sourceControlHint -eq "untracked_inaccessible_candidate") {
            "manual_review_untracked_missing_skill_md"
        } elseif ($sourceControlHint -eq "tracked_inaccessible_candidate") {
            "manual_review_tracked_missing_skill_md"
        } else {
            "manual_review_missing_skill_md"
        }

        return [ordered]@{
            name = $Name
            recommendedAction = $recommendedAction
            sourceControlHint = $sourceControlHint
            gitTrackingStatus = $GitTrackingStatus
            gitTrackedPathCount = $GitTrackedPathCount
            mutationAllowed = $false
            deleteCommandEmitted = $false
            aclCommandEmitted = $false
            rawAclCaptured = $false
            verifyWith = "Test-Path .agents\skills\$Name\SKILL.md"
            evidenceNeeded = ("manual decision for {0} / verify missing SKILL.md before manual follow-up outside this validator; validator emits no mutation command" -f $Name)
            issues = $Issues
        }
    }

    return [ordered]@{
        name = $Name
        recommendedAction = "manual_inspect_skill_discovery_warning"
        sourceControlHint = $sourceControlHint
        gitTrackingStatus = $GitTrackingStatus
        gitTrackedPathCount = $GitTrackedPathCount
        mutationAllowed = $false
        deleteCommandEmitted = $false
        aclCommandEmitted = $false
        rawAclCaptured = $false
        verifyWith = "powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -Json"
        evidenceNeeded = ("manual inspection for {0} / validator emits no mutation command" -f $Name)
        issues = $Issues
    }
}

function New-DiscoveryRemediationPacket {
    param([object[]]$AccessInventory)

    $items = @()
    foreach ($entry in @($AccessInventory)) {
        $issues = @($entry.issues)
        $sourceControlHint = Get-SourceControlHint -GitTrackingStatus $entry.gitTrackingStatus -GitTrackedPathCount $entry.gitTrackedPathCount
        $accessIssues = @($entry.accessIssues)
        $validatorProbeStatus = if ($accessIssues.Count -gt 0 -or $entry.directoryListingStatus -eq "inaccessible" -or $entry.skillMdStatus -eq "inaccessible" -or $entry.openaiYamlStatus -eq "inaccessible") {
            "blocked_by_filesystem_access"
        } elseif ($issues -contains "missing-skill-md") {
            "missing_required_artifact"
        } else {
            "manual_review_required"
        }
        $repeatProbeRecommendation = if ($validatorProbeStatus -eq "blocked_by_filesystem_access") {
            "do_not_repeat_until_filesystem_state_changes"
        } else {
            "run_verify_commands_if_manual_context_changed"
        }
        $action = if ($issues -contains "inaccessible-skill-directory" -or $issues -contains "inaccessible-skill-md" -or $issues -contains "inaccessible-openai-yaml") {
            if ($sourceControlHint -eq "untracked_inaccessible_candidate") {
                "manual_review_untracked_inaccessible_skill_dir"
            } elseif ($sourceControlHint -eq "tracked_inaccessible_candidate") {
                "manual_review_tracked_inaccessible_skill_dir"
            } else {
                "manual_review_inaccessible_skill_dir"
            }
        } elseif ($issues -contains "missing-skill-md") {
            if ($sourceControlHint -eq "untracked_inaccessible_candidate") {
                "manual_review_untracked_missing_skill_md"
            } elseif ($sourceControlHint -eq "tracked_inaccessible_candidate") {
                "manual_review_tracked_missing_skill_md"
            } else {
                "manual_review_missing_skill_md"
            }
        } else {
            "inspect_skill_discovery_warning"
        }

        $items += [ordered]@{
            name = $entry.name
            recommendedAction = $action
            sourceControlHint = $sourceControlHint
            gitTrackingStatus = $entry.gitTrackingStatus
            gitTrackedPathCount = $entry.gitTrackedPathCount
            directoryListingStatus = $entry.directoryListingStatus
            directoryListingErrorType = $entry.directoryListingErrorType
            skillMdStatus = $entry.skillMdStatus
            openaiYamlStatus = $entry.openaiYamlStatus
            accessIssues = $accessIssues
            validatorProbeAttempted = $true
            validatorProbeMode = "count_only"
            validatorProbeStatus = $validatorProbeStatus
            repeatProbeRecommendation = $repeatProbeRecommendation
            mutationAllowed = $false
            deleteCommandEmitted = $false
            aclCommandEmitted = $false
            rawAclCaptured = $false
            verifyCommands = @(
                ("Get-Item -Force .agents\skills\{0}" -f $entry.name),
                ("Get-ChildItem -Force .agents\skills\{0}" -f $entry.name)
            )
            evidenceNeeded = ("manual decision for {0} / sourceControlHint={1}; verify directory ownership and intended manual follow-up outside this validator; validator emits no mutation command" -f $entry.name, $sourceControlHint)
            issues = $issues
        }
    }

    return [ordered]@{
        schemaVersion = "demo1.skill-discovery-remediation.v1"
        status = if ($items.Count -gt 0) { "manual_review_required" } else { "none" }
        mutationAllowed = $false
        deleteCommandEmitted = $false
        aclCommandEmitted = $false
        rawAclCaptured = $false
        issueCount = $items.Count
        items = $items
    }
}

function New-ManualFilesystemReviewBucket {
    param([object]$DiscoveryRemediationPacket)

    $items = @()
    foreach ($entry in @($DiscoveryRemediationPacket.items)) {
        $items += [ordered]@{
            name = $entry.name
            recommendedAction = $entry.recommendedAction
            sourceControlHint = $entry.sourceControlHint
            gitTrackingStatus = $entry.gitTrackingStatus
            gitTrackedPathCount = $entry.gitTrackedPathCount
            directoryListingStatus = $entry.directoryListingStatus
            directoryListingErrorType = $entry.directoryListingErrorType
            skillMdStatus = $entry.skillMdStatus
            openaiYamlStatus = $entry.openaiYamlStatus
            accessIssues = $entry.accessIssues
            validatorProbeAttempted = $entry.validatorProbeAttempted
            validatorProbeMode = $entry.validatorProbeMode
            validatorProbeStatus = $entry.validatorProbeStatus
            repeatProbeRecommendation = $entry.repeatProbeRecommendation
            verifyCommands = $entry.verifyCommands
            evidenceNeeded = $entry.evidenceNeeded
            issues = $entry.issues
        }
    }

    return [ordered]@{
        schemaVersion = "demo1.manual-filesystem-review.v1"
        status = if ($items.Count -gt 0) { "manual_review_required" } else { "none" }
        itemCount = $items.Count
        mutationAllowed = $false
        deleteCommandEmitted = $false
        aclCommandEmitted = $false
        rawAclCaptured = $false
        items = $items
    }
}

function New-NextActionSummary {
    param(
        [object]$ManualFilesystemReviewBucket,
        [object[]]$CompletionAudit,
        [string]$ArtifactCompletionStatus,
        [string]$GoalCompletionStatus,
        [string]$ExternalLaneMode,
        [string]$DiscoveryGateImpact
    )

    $manualItems = @($ManualFilesystemReviewBucket.items)
    $evidenceNeededItems = @($CompletionAudit | Where-Object { $_.status -eq "evidence_needed" })
    $manualItem = $manualItems | Select-Object -First 1
    $evidenceNeededItem = $evidenceNeededItems | Select-Object -First 1

    if ($null -ne $manualItem) {
        $repeatProbeSuppressed = $manualItem.repeatProbeRecommendation -eq "do_not_repeat_until_filesystem_state_changes"
        $evidenceNeededActionMode = if ($repeatProbeSuppressed) {
            "manual_review_no_repeat_probe"
        } else {
            "run_verify_commands_if_manual_context_changed"
        }
        $externalStateChangeRequired = $repeatProbeSuppressed
        $resumeCondition = if ($externalStateChangeRequired) {
            "filesystem_state_changes"
        } else {
            "manual_context_changes"
        }
        $compactReportLine = if ($repeatProbeSuppressed) {
            "same blocker, no repeat probe: {0} / resume={1}" -f $manualItem.name, $resumeCondition
        } else {
            "manual review required: {0} / verifyWith={1}" -f $manualItem.name, ([string]::Join("; ", @($manualItem.verifyCommands)))
        }

        return [ordered]@{
            schemaVersion = "demo1.skill-family-next-action.v1"
            status = "manual_filesystem_review_required"
            action = $manualItem.recommendedAction
            target = $manualItem.name
            mutationAllowed = $false
            deleteCommandEmitted = $false
            aclCommandEmitted = $false
            rawAclCaptured = $false
            externalLaneMode = $ExternalLaneMode
            artifactCompletionStatus = $ArtifactCompletionStatus
            goalCompletionStatus = $GoalCompletionStatus
            discoveryGateImpact = $DiscoveryGateImpact
            evidenceNeededCount = $evidenceNeededItems.Count
            manualFilesystemReviewCount = $manualItems.Count
            validatorProbeAttempted = $manualItem.validatorProbeAttempted
            validatorProbeMode = $manualItem.validatorProbeMode
            validatorProbeStatus = $manualItem.validatorProbeStatus
            repeatProbeRecommendation = $manualItem.repeatProbeRecommendation
            repeatProbeSuppressed = $repeatProbeSuppressed
            evidenceNeededActionMode = $evidenceNeededActionMode
            externalStateChangeRequired = $externalStateChangeRequired
            resumeCondition = $resumeCondition
            compactReportLine = $compactReportLine
            verifyWith = [string]::Join("; ", @($manualItem.verifyCommands))
            evidenceNeeded = $manualItem.evidenceNeeded
        }
    }

    if ($null -ne $evidenceNeededItem) {
        return [ordered]@{
            schemaVersion = "demo1.skill-family-next-action.v1"
            status = "artifact_evidence_needed"
            action = "run_required_skill_artifact_verification"
            target = $evidenceNeededItem.requirement
            mutationAllowed = $false
            deleteCommandEmitted = $false
            aclCommandEmitted = $false
            rawAclCaptured = $false
            externalLaneMode = $ExternalLaneMode
            artifactCompletionStatus = $ArtifactCompletionStatus
            goalCompletionStatus = $GoalCompletionStatus
            discoveryGateImpact = $DiscoveryGateImpact
            evidenceNeededCount = $evidenceNeededItems.Count
            manualFilesystemReviewCount = 0
            repeatProbeSuppressed = $false
            evidenceNeededActionMode = "run_required_artifact_verification"
            externalStateChangeRequired = $false
            resumeCondition = "artifact_verification_rerun"
            verifyWith = $evidenceNeededItem.verifyWith
            evidenceNeeded = $evidenceNeededItem.evidence
        }
    }

    return [ordered]@{
        schemaVersion = "demo1.skill-family-next-action.v1"
        status = "none_for_skill_artifact"
        action = "none_for_skill_artifact"
        target = ""
        mutationAllowed = $false
        deleteCommandEmitted = $false
        aclCommandEmitted = $false
        rawAclCaptured = $false
        externalLaneMode = $ExternalLaneMode
        artifactCompletionStatus = $ArtifactCompletionStatus
        goalCompletionStatus = $GoalCompletionStatus
        discoveryGateImpact = $DiscoveryGateImpact
        evidenceNeededCount = 0
        manualFilesystemReviewCount = 0
        repeatProbeSuppressed = $false
        evidenceNeededActionMode = "none"
        externalStateChangeRequired = $false
        resumeCondition = "none"
        verifyWith = ""
        evidenceNeeded = ""
    }
}

function New-SkillFamilyDriftPacket {
    param(
        [string[]]$RequiredSkills,
        [object[]]$DiscoveredSkills
    )

    $items = @()
    $requiredDiscoveredCount = 0
    $optionalCount = 0
    $staleCandidateCount = 0
    $inaccessibleCandidateCount = 0

    foreach ($skill in @($DiscoveredSkills)) {
        $issues = @($skill.issues)
        $isRequired = $RequiredSkills -contains $skill.name
        $isInaccessible = @($skill.accessIssues).Count -gt 0 -or $skill.directoryListingStatus -eq "inaccessible"
        $isStaleCandidate = -not $isRequired -and ($isInaccessible -or $issues -contains "missing-skill-md" -or $issues -contains "missing-or-inaccessible-skill-md")
        $sourceControlHint = Get-SourceControlHint -GitTrackingStatus $skill.gitTrackingStatus -GitTrackedPathCount $skill.gitTrackedPathCount

        $classification = if ($isRequired) {
            $requiredDiscoveredCount += 1
            "required_family"
        } elseif ($isStaleCandidate) {
            $staleCandidateCount += 1
            "stale_or_inaccessible_candidate"
        } else {
            $optionalCount += 1
            "optional_repo_local_skill"
        }

        if ($isInaccessible) {
            $inaccessibleCandidateCount += 1
        }

        $items += [ordered]@{
            name = $skill.name
            classification = $classification
            inFamily = [bool]$isRequired
            skillMdStatus = $skill.skillMdStatus
            openaiYamlStatus = $skill.openaiYamlStatus
            directoryListingStatus = $skill.directoryListingStatus
            sourceControlHint = $sourceControlHint
            gitTrackingStatus = $skill.gitTrackingStatus
            gitTrackedPathCount = $skill.gitTrackedPathCount
            mutationAllowed = $false
            deleteCommandEmitted = $false
            aclCommandEmitted = $false
            rawAclCaptured = $false
            lineCount = $skill.lineCount
            wordCount = $skill.wordCount
            recommendedAction = if ($classification -eq "required_family") {
                "keep_required_family_gate"
            } elseif ($classification -eq "stale_or_inaccessible_candidate" -and $sourceControlHint -eq "untracked_inaccessible_candidate") {
                "manual_review_untracked_inaccessible_skill_dir"
            } elseif ($classification -eq "stale_or_inaccessible_candidate" -and $sourceControlHint -eq "tracked_inaccessible_candidate") {
                "manual_review_tracked_inaccessible_skill_dir"
            } elseif ($classification -eq "stale_or_inaccessible_candidate") {
                "manual_review_stale_or_inaccessible_skill_dir"
            } else {
                "optional_no_required_gate"
            }
            evidenceNeeded = if ($classification -eq "stale_or_inaccessible_candidate") {
                ("manual decision for {0} / sourceControlHint={1}; validator emits no mutation command" -f $skill.name, $sourceControlHint)
            } else {
                ""
            }
            issues = $issues
        }
    }

    $missingRequired = @($RequiredSkills | Where-Object {
        $requiredName = $_
        -not (@($DiscoveredSkills) | Where-Object { $_.name -eq $requiredName })
    })

    return [ordered]@{
        schemaVersion = "demo1.skill-family-drift.v1"
        status = if (@($DiscoveredSkills).Count -gt 0) { "classified" } else { "not_run" }
        classificationMode = "report_only"
        mutationAllowed = $false
        requiredCount = $RequiredSkills.Count
        discoveredCount = @($DiscoveredSkills).Count
        requiredDiscoveredCount = $requiredDiscoveredCount
        missingRequiredCount = $missingRequired.Count
        missingRequired = $missingRequired
        optionalCount = $optionalCount
        staleCandidateCount = $staleCandidateCount
        inaccessibleCandidateCount = $inaccessibleCandidateCount
        items = $items
    }
}

function New-TriggerQualityPacket {
    param(
        [string]$SkillRoot,
        [object[]]$DiscoveredSkills
    )

    $items = @()
    $issueCount = 0
    $missingDescriptionCount = 0
    $descriptionNotUseWhenCount = 0
    $descriptionTooLongCount = 0
    $defaultPromptIssueCount = 0

    foreach ($skill in @($DiscoveredSkills)) {
        $issues = @()
        $descriptionLength = 0
        $descriptionStatus = "not_checked"
        $defaultPromptStatus = "not_checked"

        if ($skill.skillMdStatus -eq "present") {
            $skillMdPath = Join-Path (Join-Path $SkillRoot $skill.name) "SKILL.md"
            $skillText = Get-TextOrEmpty $skillMdPath
            $description = Get-SimpleYamlField -Text $skillText -Field "description"
            $descriptionLength = $description.Length

            if ([string]::IsNullOrWhiteSpace($description)) {
                $descriptionStatus = "missing"
                $issues += "trigger-description-missing"
                $missingDescriptionCount += 1
            } elseif (-not $description.StartsWith("Use when", [StringComparison]::Ordinal)) {
                $descriptionStatus = "not_use_when"
                $issues += "trigger-description-not-use-when"
                $descriptionNotUseWhenCount += 1
            } elseif ($description.Length -gt 500) {
                $descriptionStatus = "too_long"
                $issues += "trigger-description-too-long"
                $descriptionTooLongCount += 1
            } else {
                $descriptionStatus = "ok"
            }
        } else {
            $descriptionStatus = $skill.skillMdStatus
        }

        if ($skill.openaiYamlStatus -eq "present") {
            $openaiYamlPath = Join-Path (Join-Path $SkillRoot $skill.name) "agents\openai.yaml"
            $openaiText = Get-TextOrEmpty $openaiYamlPath
            $defaultPrompt = Get-SimpleYamlField -Text $openaiText -Field "default_prompt"
            if ([string]::IsNullOrWhiteSpace($defaultPrompt)) {
                $defaultPromptStatus = "missing"
                $issues += "openai-default-prompt-missing"
                $defaultPromptIssueCount += 1
            } elseif (-not $defaultPrompt.Contains('$' + $skill.name)) {
                $defaultPromptStatus = "missing_skill_reference"
                $issues += "openai-default-prompt-skill-reference"
                $defaultPromptIssueCount += 1
            } else {
                $defaultPromptStatus = "ok"
            }
        } else {
            $defaultPromptStatus = $skill.openaiYamlStatus
        }

        if ($issues.Count -gt 0) {
            $issueCount += 1
        }

        $items += [ordered]@{
            name = $skill.name
            inFamily = [bool]$skill.inFamily
            descriptionStatus = $descriptionStatus
            descriptionLength = $descriptionLength
            defaultPromptStatus = $defaultPromptStatus
            issues = $issues
        }
    }

    return [ordered]@{
        schemaVersion = "demo1.skill-trigger-quality.v1"
        status = if (@($DiscoveredSkills).Count -gt 0) { "classified" } else { "not_run" }
        classificationMode = "report_only"
        checkedCount = @($DiscoveredSkills).Count
        issueCount = $issueCount
        missingDescriptionCount = $missingDescriptionCount
        descriptionNotUseWhenCount = $descriptionNotUseWhenCount
        descriptionTooLongCount = $descriptionTooLongCount
        defaultPromptIssueCount = $defaultPromptIssueCount
        rawDescriptionCaptured = $false
        rawDefaultPromptCaptured = $false
        items = $items
    }
}

$repoRoot = (Resolve-Path -LiteralPath $Root).ProviderPath
$skillRoot = Join-Path $repoRoot ".agents\skills"
$validatorSelfTestStatus = Get-ValidatorSelfTestStatus -RepoRoot $repoRoot -StatusPath $ValidatorSelfTestStatusPath -FreshMinutes $ValidatorSelfTestFreshMinutes
$fillMarkerPattern = ("T" + "ODO|\[T" + "ODO|place" + "holder")
$secretPattern = "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}"
$laneRequirements = @(
    [ordered]@{
        lane = "browser"
        skill = "demo1-demand-driven-external-proof"
        terms = @("Browser", "browser-smoke-missing")
    },
    [ordered]@{
        lane = "computer"
        skill = "demo1-demand-driven-external-proof"
        terms = @("Computer", "computer-use-smoke-missing")
    },
    [ordered]@{
        lane = "supabase"
        skill = "demo1-demand-driven-external-proof"
        terms = @("Supabase", "supabase-project-ref-missing", "read-only")
    },
    [ordered]@{
        lane = "superpowers"
        skill = "demo1-superpowers-repo-evidence-guard"
        terms = @("Superpowers", "supporting_process", "repoEvidence")
    },
    [ordered]@{
        lane = "desktop"
        skill = "demo1-desktop-only-proof-loop"
        terms = @("Desktop", "desktop_only_ready", "supportingEvidenceMissing")
    },
    [ordered]@{
        lane = "patchdrop"
        skill = "demo1-patchdrop-manual-default"
        terms = @("PatchDrop", "patchdropMode=manual", "producerEvidence=supporting")
    }
)

$summary = [ordered]@{
    root = $repoRoot
    skillRoot = $skillRoot
    skillFamily = $Skills.Count
    skillCount = $Skills.Count
    validSkills = 0
    validCount = 0
    missing = @()
    invalid = @()
    openaiPromptErrors = @()
    openaiMetadataErrors = @()
    fillMarkerHits = 0
    secretPatternHits = 0
    secretHits = 0
    laneCoverage = @()
    coverageFailures = @()
    reportPath = ""
    historyPath = ""
    historyRetain = $HistoryRetain
    historyPruned = 0
    discoverPrefix = $DiscoverPrefix
    failOnDiscoveryIssues = [bool]$FailOnDiscoveryIssues
    discoveredSkills = @()
    discoveryWarnings = @()
    discoveryNextActions = @()
    discoveryIssueCount = 0
    discoveryAccessIssueCount = 0
    discoveryAccessInventory = @()
    discoveryRemediationPacket = [ordered]@{}
    manualFilesystemReviewBucket = [ordered]@{}
    skillFamilyDriftPacket = [ordered]@{}
    triggerQualityPacket = [ordered]@{}
    discoveryIssueMode = "not_run"
    discoveryGateImpact = "none"
    skillBudgets = @()
    budgetWarnings = @()
    budgetWarningCount = 0
    skillLineBudget = $SkillLineBudget
    skillWordBudget = $SkillWordBudget
    failOnBudgetWarnings = [bool]$FailOnBudgetWarnings
    trimCandidateLimit = $TrimCandidateCount
    quickValidationMode = if ($SkipQuickValidate) { "skipped" } else { "full" }
    quickValidationSkippedCount = 0
    reportFormat = if ($CompactReport) { "summary" } else { "full" }
    trimCandidates = @()
    trimPressureCandidateCount = 0
    trimOpportunisticCandidateCount = 0
    trimActionMode = "none"
    completionAudit = @()
    completionAuditScope = "skill_artifact_postprocess"
    completionAuditStatus = "not-run"
    artifactCompletionStatus = "not-run"
    goalCompletionStatus = "not-run"
    goalCompletionReason = ""
    externalLaneMode = "demand_driven"
    browser = "optional"
    computer = "optional"
    supabase = "read_only_evidence_needed"
    superpowers = "supporting_process"
    nextActionSummary = [ordered]@{}
    validatorSelfTestStatus = $validatorSelfTestStatus
    completionAuditEvidenceNeededCount = 0
    skills = @()
    ok = $false
}

if (-not [string]::IsNullOrWhiteSpace($DiscoverPrefix)) {
    if (Test-PathOrFalse $skillRoot) {
        try {
            $discoveredDirs = @(Get-ChildItem -LiteralPath $skillRoot -Directory -ErrorAction Stop |
                Where-Object { $_.Name.StartsWith($DiscoverPrefix, [StringComparison]::OrdinalIgnoreCase) } |
                Sort-Object Name)

            foreach ($dirInfo in $discoveredDirs) {
                $skillMdPath = Join-Path $dirInfo.FullName "SKILL.md"
                $openaiYamlPath = Join-Path $dirInfo.FullName "agents\openai.yaml"
                $relativeSkillPath = ".agents/skills/{0}" -f $dirInfo.Name
                $gitTrackedInfo = Get-GitTrackedPathInfo -RepoRoot $repoRoot -RelativePath $relativeSkillPath
                $directoryProbe = Get-DirectoryListingProbe $dirInfo.FullName
                $skillMdProbe = Get-PathAccessProbe $skillMdPath
                $openaiYamlProbe = Get-PathAccessProbe $openaiYamlPath
                $hasSkillMd = [bool]$skillMdProbe.exists
                $hasOpenaiYaml = [bool]$openaiYamlProbe.exists
                $skillText = if ($hasSkillMd) { Get-TextOrEmpty $skillMdPath } else { "" }
                $issues = @()
                $accessIssues = @()

                if ($directoryProbe.status -eq "inaccessible") {
                    $issues += "inaccessible-skill-directory"
                    $accessIssues += "skill-directory"
                }
                if (-not $hasSkillMd) {
                    $issues += "missing-or-inaccessible-skill-md"
                    if ($skillMdProbe.status -eq "inaccessible") {
                        $issues += "inaccessible-skill-md"
                        $accessIssues += "SKILL.md"
                    } else {
                        $issues += "missing-skill-md"
                    }
                }
                if (-not $hasOpenaiYaml -and $openaiYamlProbe.status -eq "inaccessible") {
                    $issues += "inaccessible-openai-yaml"
                    $accessIssues += "agents/openai.yaml"
                }

                $discovered = [ordered]@{
                    name = $dirInfo.Name
                    inFamily = $Skills -contains $dirInfo.Name
                    hasSkillMd = $hasSkillMd
                    hasOpenaiYaml = $hasOpenaiYaml
                    skillMdStatus = $skillMdProbe.status
                    openaiYamlStatus = $openaiYamlProbe.status
                    directoryListingStatus = $directoryProbe.status
                    directoryListingErrorType = $directoryProbe.errorType
                    directoryChildCount = $directoryProbe.childCount
                    gitTrackingStatus = $gitTrackedInfo.status
                    gitTrackedPathCount = $gitTrackedInfo.trackedPathCount
                    accessIssues = $accessIssues
                    lineCount = Get-TextLineCount $skillText
                    wordCount = Get-TextWordCount $skillText
                    issues = $issues
                }
                $summary.discoveredSkills += $discovered

                if ($issues.Count -gt 0) {
                    $resolution = New-DiscoveryResolution -Name $dirInfo.Name -Issues $issues -GitTrackingStatus $gitTrackedInfo.status -GitTrackedPathCount $gitTrackedInfo.trackedPathCount
                    $summary.discoveryWarnings += [ordered]@{
                        name = $dirInfo.Name
                        issues = $issues
                        recommendedAction = $resolution.recommendedAction
                        sourceControlHint = $resolution.sourceControlHint
                        gitTrackingStatus = $resolution.gitTrackingStatus
                        gitTrackedPathCount = $resolution.gitTrackedPathCount
                        verifyWith = $resolution.verifyWith
                    }
                    $summary.discoveryNextActions += $resolution
                }
            }
        } catch {
            $issues = @("discover-access-error")
            $resolution = New-DiscoveryResolution -Name $DiscoverPrefix -Issues $issues
            $summary.discoveryWarnings += [ordered]@{
                name = $DiscoverPrefix
                issues = $issues
                recommendedAction = $resolution.recommendedAction
                verifyWith = $resolution.verifyWith
            }
            $summary.discoveryNextActions += $resolution
        }
    } else {
        $issues = @("skill-root-missing-or-inaccessible")
        $resolution = New-DiscoveryResolution -Name $DiscoverPrefix -Issues $issues
        $summary.discoveryWarnings += [ordered]@{
            name = $DiscoverPrefix
            issues = $issues
            recommendedAction = $resolution.recommendedAction
            verifyWith = $resolution.verifyWith
        }
        $summary.discoveryNextActions += $resolution
    }

    $summary.discoveryIssueCount = $summary.discoveryWarnings.Count
    $summary.discoveryAccessInventory = @($summary.discoveredSkills |
        Where-Object { @($_.accessIssues).Count -gt 0 -or $_.directoryListingStatus -eq "inaccessible" } |
        ForEach-Object {
            [ordered]@{
                name = $_.name
                skillMdStatus = $_.skillMdStatus
                openaiYamlStatus = $_.openaiYamlStatus
                directoryListingStatus = $_.directoryListingStatus
                directoryListingErrorType = $_.directoryListingErrorType
                gitTrackingStatus = $_.gitTrackingStatus
                gitTrackedPathCount = $_.gitTrackedPathCount
                accessIssues = $_.accessIssues
                issues = $_.issues
            }
        })
    $summary.discoveryAccessIssueCount = $summary.discoveryAccessInventory.Count
    $summary.discoveryRemediationPacket = New-DiscoveryRemediationPacket -AccessInventory $summary.discoveryAccessInventory
    $summary.manualFilesystemReviewBucket = New-ManualFilesystemReviewBucket -DiscoveryRemediationPacket $summary.discoveryRemediationPacket
    $summary.skillFamilyDriftPacket = New-SkillFamilyDriftPacket -RequiredSkills $Skills -DiscoveredSkills $summary.discoveredSkills
    $summary.triggerQualityPacket = New-TriggerQualityPacket -SkillRoot $skillRoot -DiscoveredSkills $summary.discoveredSkills
    $summary.discoveryIssueMode = if ($FailOnDiscoveryIssues) { "fail_closed" } else { "report_only" }
    $summary.discoveryGateImpact = if ($summary.discoveryIssueCount -eq 0) {
        "none"
    } elseif ($FailOnDiscoveryIssues) {
        "blocking_due_to_fail_mode"
    } else {
        "non_blocking_report_only"
    }
}

if (@($summary.skillFamilyDriftPacket.Keys).Count -eq 0) {
    $summary.skillFamilyDriftPacket = New-SkillFamilyDriftPacket -RequiredSkills $Skills -DiscoveredSkills $summary.discoveredSkills
}
if (@($summary.triggerQualityPacket.Keys).Count -eq 0) {
    $summary.triggerQualityPacket = New-TriggerQualityPacket -SkillRoot $skillRoot -DiscoveredSkills $summary.discoveredSkills
}

foreach ($name in $Skills) {
    $dir = Join-Path $skillRoot $name
    $skillMd = Join-Path $dir "SKILL.md"
    $openaiYaml = Join-Path $dir "agents\openai.yaml"
    $item = [ordered]@{
        name = $name
        exists = Test-PathOrFalse $dir
        hasSkillMd = Test-PathOrFalse $skillMd
        hasOpenaiYaml = Test-PathOrFalse $openaiYaml
        quickValidateExit = $null
        quickValidate = "not-run"
        quickValidateSkipped = $false
        openaiPromptOk = $false
        openaiMetadataOk = $false
        openaiMetadataErrors = @()
        lineCount = 0
        wordCount = 0
        budgetWarnings = @()
        fillMarkerHits = 0
        secretHits = 0
    }

    if (-not $item.exists -or -not $item.hasSkillMd) {
        $summary.missing += $name
        $summary.skills += $item
        continue
    }

    $skillText = Get-TextOrEmpty $skillMd
    $item.lineCount = Get-TextLineCount $skillText
    $item.wordCount = Get-TextWordCount $skillText
    if ($SkillLineBudget -gt 0 -and $item.lineCount -gt $SkillLineBudget) {
        $item.budgetWarnings += "line-budget-exceeded"
    }
    if ($SkillWordBudget -gt 0 -and $item.wordCount -gt $SkillWordBudget) {
        $item.budgetWarnings += "word-budget-exceeded"
    }
    $budget = [ordered]@{
        name = $name
        lineCount = $item.lineCount
        wordCount = $item.wordCount
        lineBudget = $SkillLineBudget
        wordBudget = $SkillWordBudget
        warnings = $item.budgetWarnings
    }
    $summary.skillBudgets += $budget
    if ($item.budgetWarnings.Count -gt 0) {
        $summary.budgetWarnings += $budget
    }

    $docText = $skillText + "`n" + (Get-TextOrEmpty $openaiYaml)
    $item.fillMarkerHits = ([regex]::Matches($docText, $fillMarkerPattern, "IgnoreCase")).Count
    $summary.fillMarkerHits += $item.fillMarkerHits

    if ($item.hasOpenaiYaml) {
        $openaiText = Get-TextOrEmpty $openaiYaml
        $displayName = Get-SimpleYamlField -Text $openaiText -Field "display_name"
        $shortDescription = Get-SimpleYamlField -Text $openaiText -Field "short_description"
        $defaultPrompt = Get-SimpleYamlField -Text $openaiText -Field "default_prompt"
        $item.openaiPromptOk = $defaultPrompt.Contains('$' + $name)

        $metadataErrors = @()
        if (-not [regex]::IsMatch($openaiText, "(?m)^interface:\s*$")) {
            $metadataErrors += "missing-interface"
        }
        if ([string]::IsNullOrWhiteSpace($displayName)) {
            $metadataErrors += "missing-display-name"
        }
        if ([string]::IsNullOrWhiteSpace($shortDescription)) {
            $metadataErrors += "missing-short-description"
        } elseif ($shortDescription.Length -lt 25 -or $shortDescription.Length -gt 64) {
            $metadataErrors += "short-description-length"
        }
        if ([string]::IsNullOrWhiteSpace($defaultPrompt)) {
            $metadataErrors += "missing-default-prompt"
        } elseif (-not $item.openaiPromptOk) {
            $metadataErrors += "default-prompt-skill-reference"
        }

        $item.openaiMetadataErrors = $metadataErrors
        $item.openaiMetadataOk = $metadataErrors.Count -eq 0
    } else {
        $item.openaiMetadataErrors = @("missing-openai-yaml")
    }
    if (-not $item.openaiPromptOk) {
        $summary.openaiPromptErrors += $name
    }
    if (-not $item.openaiMetadataOk) {
        $summary.openaiMetadataErrors += [ordered]@{
            name = $name
            errors = $item.openaiMetadataErrors
        }
    }

    if ($SkipQuickValidate) {
        $item.quickValidateSkipped = $true
        $item.quickValidate = "skipped"
        $summary.quickValidationSkippedCount += 1
    } elseif (Test-Path -LiteralPath $Validator) {
        $validateOutput = & python -X utf8 $Validator $dir 2>&1
        $item.quickValidateExit = $LASTEXITCODE
        if ($LASTEXITCODE -eq 0) {
            $item.quickValidate = "valid"
            $summary.validSkills += 1
            $summary.validCount += 1
        } else {
            $item.quickValidate = (($validateOutput | Select-Object -First 3) -join " ")
            $summary.invalid += $name
        }
    } else {
        $item.quickValidate = "validator-missing"
        $summary.invalid += $name
    }

    $files = Get-ChildItem -LiteralPath $dir -Recurse -File -ErrorAction SilentlyContinue
    foreach ($file in $files) {
        $text = Get-TextOrEmpty $file.FullName
        $item.secretHits += ([regex]::Matches($text, $secretPattern)).Count
    }
    $summary.secretPatternHits += $item.secretHits
    $summary.secretHits += $item.secretHits

    $summary.skills += $item
}

$summary.budgetWarningCount = $summary.budgetWarnings.Count

if ($TrimCandidateCount -gt 0) {
    $candidatePool = @()
    if ($summary.discoveredSkills.Count -gt 0) {
        foreach ($skill in $summary.discoveredSkills) {
            if ($skill.hasSkillMd) {
                $candidatePool += [ordered]@{
                    name = $skill.name
                    lineCount = $skill.lineCount
                    wordCount = $skill.wordCount
                    inFamily = $skill.inFamily
                    source = "discovered"
                }
            }
        }
    } else {
        foreach ($budget in $summary.skillBudgets) {
            $candidatePool += [ordered]@{
                name = $budget.name
                lineCount = $budget.lineCount
                wordCount = $budget.wordCount
                inFamily = $true
                source = "family"
            }
        }
    }

    $summary.trimCandidates = @($candidatePool |
        Sort-Object @{ Expression = { $_.lineCount }; Descending = $true }, @{ Expression = { $_.wordCount }; Descending = $true }, @{ Expression = { $_.name }; Descending = $false } |
        Select-Object -First $TrimCandidateCount |
        ForEach-Object {
            $reason = "ranked-by-line-count"
            if ($SkillLineBudget -gt 0 -and $SkillWordBudget -gt 0 -and $_.lineCount -gt $SkillLineBudget -and $_.wordCount -gt $SkillWordBudget) {
                $reason = "line-and-word-budget-pressure"
            } elseif ($SkillLineBudget -gt 0 -and $_.lineCount -gt $SkillLineBudget) {
                $reason = "line-budget-pressure"
            } elseif ($SkillWordBudget -gt 0 -and $_.wordCount -gt $SkillWordBudget) {
                $reason = "word-budget-pressure"
            }

            $budgetPressure = $reason -ne "ranked-by-line-count"
            $actionMode = if ($budgetPressure) { "budget_pressure" } else { "opportunistic_review" }
            $action = "review for reference split only if the body repeats detailed procedure"
            if ($_.inFamily) {
                $action = "keep core workflow in SKILL.md and move rarely used detail to references/"
            }

            [ordered]@{
                name = $_.name
                lineCount = $_.lineCount
                wordCount = $_.wordCount
                inFamily = [bool]$_.inFamily
                source = $_.source
                reason = $reason
                budgetPressure = $budgetPressure
                actionMode = $actionMode
                recommendedAction = $action
            }
        })
}

$summary.trimPressureCandidateCount = @($summary.trimCandidates | Where-Object { [bool]$_.budgetPressure }).Count
$summary.trimOpportunisticCandidateCount = [Math]::Max(0, $summary.trimCandidates.Count - $summary.trimPressureCandidateCount)
$summary.trimActionMode = if ($summary.trimCandidates.Count -eq 0) {
    "none"
} elseif ($summary.trimPressureCandidateCount -gt 0) {
    "budget_pressure"
} else {
    "opportunistic_only"
}

foreach ($requirement in $laneRequirements) {
    $name = $requirement.skill
    $skillMd = Join-Path (Join-Path $skillRoot $name) "SKILL.md"
    $text = Get-TextOrEmpty $skillMd
    $missingTerms = @()
    foreach ($term in $requirement.terms) {
        if ($text.IndexOf($term, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $missingTerms += $term
        }
    }

    $coverage = [ordered]@{
        lane = $requirement.lane
        skill = $name
        ok = $missingTerms.Count -eq 0
        missingTerms = $missingTerms
    }
    $summary.laneCoverage += $coverage
    if (-not $coverage.ok) {
        $summary.coverageFailures += $coverage
    }
}

$summary.ok = (
    $summary.missing.Count -eq 0 -and
    $summary.invalid.Count -eq 0 -and
    $summary.openaiPromptErrors.Count -eq 0 -and
    $summary.openaiMetadataErrors.Count -eq 0 -and
    $summary.fillMarkerHits -eq 0 -and
    $summary.secretPatternHits -eq 0 -and
    $summary.secretHits -eq 0 -and
    $summary.coverageFailures.Count -eq 0 -and
    $summary.quickValidationSkippedCount -eq 0 -and
    $summary.validSkills -eq $summary.skillFamily -and
    [bool]$summary.validatorSelfTestStatus.ok -and
    (-not $FailOnDiscoveryIssues -or $summary.discoveryIssueCount -eq 0) -and
    (-not $FailOnBudgetWarnings -or $summary.budgetWarningCount -eq 0)
)

$validatorSelfTestStatusCheck = if ([bool]$summary.validatorSelfTestStatus.ok) { "proven" } else { "evidence_needed" }
$metadataStatus = if ($summary.openaiPromptErrors.Count -eq 0 -and $summary.openaiMetadataErrors.Count -eq 0) { "proven" } else { "evidence_needed" }
$skillValidityStatus = if ($summary.missing.Count -eq 0 -and $summary.invalid.Count -eq 0 -and $summary.validSkills -eq $summary.skillFamily) { "proven" } else { "evidence_needed" }
$laneCoverageStatus = if ($summary.coverageFailures.Count -eq 0) { "proven" } else { "evidence_needed" }
$hygieneStatus = if ($summary.fillMarkerHits -eq 0 -and $summary.secretPatternHits -eq 0 -and $summary.secretHits -eq 0) { "proven" } else { "evidence_needed" }
$budgetStatus = if ($summary.budgetWarningCount -eq 0) { "proven" } else { "evidence_needed" }

$summary.completionAudit = @(
    (New-CompletionAuditItem `
        -Requirement "validator-self-test-current" `
        -Status $validatorSelfTestStatusCheck `
        -Evidence ("status={0}; freshness={1}; ok={2}" -f $summary.validatorSelfTestStatus.status, $summary.validatorSelfTestStatus.freshnessStatus, ([bool]$summary.validatorSelfTestStatus.ok).ToString().ToLowerInvariant()) `
        -VerifyWith $summary.validatorSelfTestStatus.verifyWith),
    (New-CompletionAuditItem `
        -Requirement "required-derived-skill-family-valid" `
        -Status $skillValidityStatus `
        -Evidence ("validSkills={0}/{1}; missing={2}; invalid={3}; quickValidationSkipped={4}" -f $summary.validSkills, $summary.skillFamily, $summary.missing.Count, $summary.invalid.Count, $summary.quickValidationSkippedCount) `
        -VerifyWith "quick_validate.py for each required skill"),
    (New-CompletionAuditItem `
        -Requirement "browser-computer-supabase-superpowers-lanes-covered" `
        -Status $laneCoverageStatus `
        -Evidence ("laneCoverage={0}; coverageFailures={1}" -f $summary.laneCoverage.Count, $summary.coverageFailures.Count) `
        -VerifyWith "laneCoverage terms in owning SKILL.md files"),
    (New-CompletionAuditItem `
        -Requirement "openai-yaml-metadata-valid" `
        -Status $metadataStatus `
        -Evidence ("openaiPromptErrors={0}; openaiMetadataErrors={1}" -f $summary.openaiPromptErrors.Count, $summary.openaiMetadataErrors.Count) `
        -VerifyWith "agents/openai.yaml default_prompt and metadata checks"),
    (New-CompletionAuditItem `
        -Requirement "scaffold-and-secret-hygiene" `
        -Status $hygieneStatus `
        -Evidence ("fillMarkerHits={0}; secretPatternHits={1}" -f $summary.fillMarkerHits, $summary.secretPatternHits) `
        -VerifyWith "count-only skill artifact scans"),
    (New-CompletionAuditItem `
        -Requirement "context-budget-and-trim-signal" `
        -Status $budgetStatus `
        -Evidence ("budgetWarnings={0}; trimCandidates={1}; trimPressureCandidates={2}; trimActionMode={3}" -f $summary.budgetWarningCount, $summary.trimCandidates.Count, $summary.trimPressureCandidateCount, $summary.trimActionMode) `
        -VerifyWith "SkillLineBudget and SkillWordBudget report-only checks"),
    (New-CompletionAuditItem `
        -Requirement "browser-computer-live-proof" `
        -Status "demand_driven_not_required" `
        -Evidence "Skill artifact pass only; Browser/Computer proof is demand-driven when UI or Windows UI changes." `
        -VerifyWith "Run Browser/Computer smoke only for UI or Windows UI changed surfaces"),
    (New-CompletionAuditItem `
        -Requirement "supabase-live-project-proof" `
        -Status "demand_driven_not_required" `
        -Evidence "Skill artifact pass only; Supabase remains read-only evidence lane outside this validator." `
        -VerifyWith "python scripts\awx_mcp_toolbox.py --input-json - supabase_context_probe")
)

$summary.completionAuditEvidenceNeededCount = @($summary.completionAudit | Where-Object { $_.status -eq "evidence_needed" }).Count
$summary.artifactCompletionStatus = if ($summary.completionAuditEvidenceNeededCount -eq 0) { "artifact_ready" } else { "evidence_needed" }
$summary.completionAuditStatus = $summary.artifactCompletionStatus
$summary.goalCompletionStatus = if ($summary.artifactCompletionStatus -eq "artifact_ready") { "active_goal_not_complete" } else { "active_goal_evidence_needed" }
$summary.goalCompletionReason = "This validator proves repo-local skill artifacts only; Browser, Computer, and Supabase live proof stays demand-driven and outside this artifact gate."
$summary.goalCompletionClaimAllowed = $false
$summary.goalUpdateRecommendation = if ($summary.artifactCompletionStatus -eq "artifact_ready") {
    "leave_goal_active"
} else {
    "leave_goal_active_collect_artifact_evidence"
}
$summary.nextActionSummary = New-NextActionSummary `
    -ManualFilesystemReviewBucket $summary.manualFilesystemReviewBucket `
    -CompletionAudit $summary.completionAudit `
    -ArtifactCompletionStatus $summary.artifactCompletionStatus `
    -GoalCompletionStatus $summary.goalCompletionStatus `
    -ExternalLaneMode $summary.externalLaneMode `
    -DiscoveryGateImpact $summary.discoveryGateImpact

$compactSummarySufficient = (
    $summary.artifactCompletionStatus -eq "artifact_ready" -and
    $summary.completionAuditEvidenceNeededCount -eq 0 -and
    $summary.openaiPromptErrors.Count -eq 0 -and
    $summary.openaiMetadataErrors.Count -eq 0 -and
    [int]$summary.triggerQualityPacket.issueCount -eq 0 -and
    $summary.budgetWarningCount -eq 0 -and
    (-not $FailOnDiscoveryIssues -or $summary.discoveryIssueCount -eq 0) -and
    (-not $FailOnBudgetWarnings -or $summary.budgetWarningCount -eq 0)
)
$summary.fullReportRecommended = -not $compactSummarySufficient
$summary.fullReportActionMode = if ($compactSummarySufficient) {
    "skip_full_report_until_summary_counts_change"
} else {
    "open_full_report_for_blocking_counts"
}
$summary.summaryReuseCondition = if ($compactSummarySufficient) {
    "reuse_latest_until_skill_files_or_filesystem_state_change"
} else {
    "rerun_validator_and_open_full_report"
}
$summary.fullReportBytes = [int]$summary.validatorSelfTestStatus.fullBytes
$summary.compactReportBytes = [int]$summary.validatorSelfTestStatus.compactBytes
$summary.compactReportSavingsPercent = 0
if ($summary.fullReportBytes -gt 0 -and $summary.compactReportBytes -gt 0 -and $summary.compactReportBytes -lt $summary.fullReportBytes) {
    $summary.compactReportSavingsPercent = [int][Math]::Round((($summary.fullReportBytes - $summary.compactReportBytes) * 100.0) / $summary.fullReportBytes)
}

$driftItems = @($summary.skillFamilyDriftPacket.items)
$staleDriftItems = @($driftItems | Where-Object { $_.classification -eq "stale_or_inaccessible_candidate" })
$discoveryStaleCandidateCount = [int]$summary.skillFamilyDriftPacket.staleCandidateCount
$discoveryInaccessibleCandidateCount = [int]$summary.skillFamilyDriftPacket.inaccessibleCandidateCount
$untrackedInaccessibleCount = @($staleDriftItems | Where-Object { $_.sourceControlHint -eq "untracked_inaccessible_candidate" }).Count
$trackedInaccessibleCount = @($staleDriftItems | Where-Object { $_.sourceControlHint -eq "tracked_inaccessible_candidate" }).Count
$gitTrackingUnknownCount = @($staleDriftItems | Where-Object { $_.sourceControlHint -eq "git_tracking_unknown" }).Count

$generatedAtUtc = [DateTime]::UtcNow
$topLevelEvidenceNeeded = @(
    @($summary.completionAudit | Where-Object { $_.status -eq "evidence_needed" } | ForEach-Object { $_.verifyWith })
    @($summary.nextActionSummary.verifyWith)
) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -Unique

$reportPayload = [ordered]@{
    schemaVersion = "demo1.skill-family-report.v1"
    generatedAtUtc = $generatedAtUtc.ToString("o")
    root = $summary.root
    skillFamily = $summary.skillFamily
    validSkills = $summary.validSkills
    missingCount = $summary.missing.Count
    invalidCount = $summary.invalid.Count
    openaiPromptErrorCount = $summary.openaiPromptErrors.Count
    openaiMetadataErrorCount = $summary.openaiMetadataErrors.Count
    fillMarkerHits = $summary.fillMarkerHits
    secretPatternHits = $summary.secretPatternHits
    coverageFailureCount = $summary.coverageFailures.Count
    laneCoverage = $summary.laneCoverage
    missing = $summary.missing
    invalid = $summary.invalid
    openaiPromptErrors = $summary.openaiPromptErrors
    openaiMetadataErrors = $summary.openaiMetadataErrors
    reportPath = ""
    historyPath = ""
    historyRetain = $summary.historyRetain
    historyPruned = 0
    reportFormat = $summary.reportFormat
    discoverPrefix = $summary.discoverPrefix
    failOnDiscoveryIssues = $summary.failOnDiscoveryIssues
    discoveredSkillCount = $summary.discoveredSkills.Count
    discoveredSkills = $summary.discoveredSkills
    skillFamilyDriftPacket = $summary.skillFamilyDriftPacket
    triggerQualityPacket = $summary.triggerQualityPacket
    discoveryIssueCount = $summary.discoveryIssueCount
    discoveryAccessIssueCount = $summary.discoveryAccessIssueCount
    discoveryStaleCandidateCount = $discoveryStaleCandidateCount
    discoveryInaccessibleCandidateCount = $discoveryInaccessibleCandidateCount
    untrackedInaccessibleCount = $untrackedInaccessibleCount
    trackedInaccessibleCount = $trackedInaccessibleCount
    gitTrackingUnknownCount = $gitTrackingUnknownCount
    discoveryAccessInventory = $summary.discoveryAccessInventory
    discoveryRemediationPacket = $summary.discoveryRemediationPacket
    manualFilesystemReviewBucket = $summary.manualFilesystemReviewBucket
    discoveryIssueMode = $summary.discoveryIssueMode
    discoveryGateImpact = $summary.discoveryGateImpact
    discoveryWarnings = $summary.discoveryWarnings
    discoveryNextActions = $summary.discoveryNextActions
    skillBudgets = $summary.skillBudgets
    budgetWarningCount = $summary.budgetWarningCount
    budgetWarnings = $summary.budgetWarnings
    skillLineBudget = $summary.skillLineBudget
    skillWordBudget = $summary.skillWordBudget
    failOnBudgetWarnings = $summary.failOnBudgetWarnings
    quickValidationMode = $summary.quickValidationMode
    quickValidationSkippedCount = $summary.quickValidationSkippedCount
    trimCandidateLimit = $summary.trimCandidateLimit
    trimCandidateCount = $summary.trimCandidates.Count
    trimPressureCandidateCount = $summary.trimPressureCandidateCount
    trimOpportunisticCandidateCount = $summary.trimOpportunisticCandidateCount
    trimActionMode = $summary.trimActionMode
    trimCandidates = $summary.trimCandidates
    completionAuditScope = $summary.completionAuditScope
    completionAuditStatus = $summary.completionAuditStatus
    artifactCompletionStatus = $summary.artifactCompletionStatus
    goalCompletionStatus = $summary.goalCompletionStatus
    goalCompletionReason = $summary.goalCompletionReason
    goalCompletionClaimAllowed = [bool]$summary.goalCompletionClaimAllowed
    goalUpdateRecommendation = $summary.goalUpdateRecommendation
    fullReportRecommended = [bool]$summary.fullReportRecommended
    fullReportActionMode = $summary.fullReportActionMode
    summaryReuseCondition = $summary.summaryReuseCondition
    fullReportBytes = $summary.fullReportBytes
    compactReportBytes = $summary.compactReportBytes
    compactReportSavingsPercent = $summary.compactReportSavingsPercent
    externalLaneMode = $summary.externalLaneMode
    browser = $summary.browser
    computer = $summary.computer
    supabase = $summary.supabase
    superpowers = $summary.superpowers
    nextActionSummary = $summary.nextActionSummary
    validatorSelfTestStatus = $summary.validatorSelfTestStatus
    completionAuditEvidenceNeededCount = $summary.completionAuditEvidenceNeededCount
    completionAudit = $summary.completionAudit
    ok = $summary.ok
}

if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
    if ([System.IO.Path]::IsPathRooted($ReportPath)) {
        $resolvedReportPath = $ReportPath
    } else {
        $resolvedReportPath = Join-Path $repoRoot $ReportPath
    }

    $reportParent = Split-Path -Parent $resolvedReportPath
    if (-not [string]::IsNullOrWhiteSpace($reportParent)) {
        New-Item -ItemType Directory -Force -Path $reportParent | Out-Null
    }

    $reportPayload["reportPath"] = $resolvedReportPath
    $summary.reportPath = $resolvedReportPath
    if (-not $CompactReport) {
        $reportPayload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resolvedReportPath -Encoding UTF8
    }
}

if (-not [string]::IsNullOrWhiteSpace($HistoryDir)) {
    if ([System.IO.Path]::IsPathRooted($HistoryDir)) {
        $resolvedHistoryDir = $HistoryDir
    } else {
        $resolvedHistoryDir = Join-Path $repoRoot $HistoryDir
    }

    New-Item -ItemType Directory -Force -Path $resolvedHistoryDir | Out-Null
    $historyFileName = "skill-family-{0}.json" -f $generatedAtUtc.ToString("yyyyMMddTHHmmssfffffffZ")
    $resolvedHistoryPath = Join-Path $resolvedHistoryDir $historyFileName
    $reportPayload["historyPath"] = $resolvedHistoryPath
    $summary.historyPath = $resolvedHistoryPath
    if (-not $CompactReport) {
        $reportPayload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resolvedHistoryPath -Encoding UTF8
    }

    if ($HistoryRetain -gt 0 -and -not $CompactReport) {
        $historyFiles = @(Get-ChildItem -LiteralPath $resolvedHistoryDir -Filter "skill-family-*.json" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending)
        $pruneFiles = @($historyFiles | Select-Object -Skip $HistoryRetain)
        foreach ($file in $pruneFiles) {
            Remove-Item -LiteralPath $file.FullName -Force
        }
        $summary.historyPruned = $pruneFiles.Count
        $reportPayload["historyPruned"] = $summary.historyPruned
    }
}

if ((-not $CompactReport) -and -not [string]::IsNullOrWhiteSpace($summary.reportPath)) {
    $reportPayload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summary.reportPath -Encoding UTF8
}

$topTrimCandidateLimit = 5
$topTrimCandidates = @($summary.trimCandidates | Select-Object -First $topTrimCandidateLimit | ForEach-Object {
    [ordered]@{
        name = $_.name
        lineCount = $_.lineCount
        wordCount = $_.wordCount
        inFamily = [bool]$_.inFamily
        source = $_.source
        reason = $_.reason
        budgetPressure = [bool]$_.budgetPressure
        actionMode = $_.actionMode
        recommendedAction = $_.recommendedAction
    }
})
$topTrimCandidateShownCount = $topTrimCandidates.Count
$topTrimCandidateOmittedCount = [Math]::Max(0, $summary.trimCandidates.Count - $topTrimCandidateShownCount)
$completionAuditSummary = @($summary.completionAudit | ForEach-Object {
    [ordered]@{
        requirement = $_.requirement
        status = $_.status
        evidence = $_.evidence
        verifyWith = $_.verifyWith
    }
})
$completionAuditProvenCount = @($summary.completionAudit | Where-Object { $_.status -eq "proven" }).Count
$completionAuditDemandDrivenCount = @($summary.completionAudit | Where-Object { $_.status -eq "demand_driven_not_required" }).Count

$summaryJsonReport = [ordered]@{
    schemaVersion = "demo1.skill-family-summary.v1"
    generatedAtUtc = $reportPayload.generatedAtUtc
    root = $summary.root
    ok = $summary.ok
    skillFamily = $summary.skillFamily
    validSkills = $summary.validSkills
    missingCount = $summary.missing.Count
    invalidCount = $summary.invalid.Count
    openaiPromptErrorCount = $summary.openaiPromptErrors.Count
    openaiMetadataErrorCount = $summary.openaiMetadataErrors.Count
    triggerQualityCheckedCount = [int]$summary.triggerQualityPacket.checkedCount
    triggerQualityIssueCount = [int]$summary.triggerQualityPacket.issueCount
    triggerQualityDescriptionNotUseWhenCount = [int]$summary.triggerQualityPacket.descriptionNotUseWhenCount
    triggerQualityDefaultPromptIssueCount = [int]$summary.triggerQualityPacket.defaultPromptIssueCount
    coverageFailureCount = $summary.coverageFailures.Count
    discoveryIssueCount = $summary.discoveryIssueCount
    discoveryAccessIssueCount = $summary.discoveryAccessIssueCount
    discoveryStaleCandidateCount = $discoveryStaleCandidateCount
    discoveryInaccessibleCandidateCount = $discoveryInaccessibleCandidateCount
    untrackedInaccessibleCount = $untrackedInaccessibleCount
    trackedInaccessibleCount = $trackedInaccessibleCount
    gitTrackingUnknownCount = $gitTrackingUnknownCount
    discoveryAccessInventory = @($summary.discoveryAccessInventory | ForEach-Object {
        [ordered]@{
            name = $_.name
            skillMdStatus = $_.skillMdStatus
            openaiYamlStatus = $_.openaiYamlStatus
            directoryListingStatus = $_.directoryListingStatus
            directoryListingErrorType = $_.directoryListingErrorType
            gitTrackingStatus = $_.gitTrackingStatus
            gitTrackedPathCount = $_.gitTrackedPathCount
            accessIssues = $_.accessIssues
            issues = $_.issues
        }
    })
    discoveryRemediationPacket = $summary.discoveryRemediationPacket
    manualFilesystemReviewBucket = $summary.manualFilesystemReviewBucket
    discoveryIssueMode = $summary.discoveryIssueMode
    discoveryGateImpact = $summary.discoveryGateImpact
    budgetWarningCount = $summary.budgetWarningCount
    quickValidationMode = $summary.quickValidationMode
    quickValidationSkippedCount = $summary.quickValidationSkippedCount
    reportFormat = "summary"
    trimCandidateCount = $summary.trimCandidates.Count
    trimPressureCandidateCount = $summary.trimPressureCandidateCount
    trimOpportunisticCandidateCount = $summary.trimOpportunisticCandidateCount
    trimActionMode = $summary.trimActionMode
    topTrimCandidateLimit = $topTrimCandidateLimit
    topTrimCandidateShownCount = $topTrimCandidateShownCount
    topTrimCandidateOmittedCount = $topTrimCandidateOmittedCount
    artifactCompletionStatus = $summary.artifactCompletionStatus
    goalCompletionStatus = $summary.goalCompletionStatus
    goalCompletionReason = $summary.goalCompletionReason
    goalCompletionClaimAllowed = [bool]$summary.goalCompletionClaimAllowed
    goalUpdateRecommendation = $summary.goalUpdateRecommendation
    fullReportRecommended = [bool]$summary.fullReportRecommended
    fullReportActionMode = $summary.fullReportActionMode
    summaryReuseCondition = $summary.summaryReuseCondition
    fullReportBytes = $summary.fullReportBytes
    compactReportBytes = $summary.compactReportBytes
    compactReportSavingsPercent = $summary.compactReportSavingsPercent
    completionAuditEvidenceNeededCount = $summary.completionAuditEvidenceNeededCount
    completionAuditProvenCount = $completionAuditProvenCount
    completionAuditDemandDrivenCount = $completionAuditDemandDrivenCount
    completionAuditSummary = $completionAuditSummary
    externalLaneMode = $summary.externalLaneMode
    browser = $summary.browser
    computer = $summary.computer
    supabase = $summary.supabase
    superpowers = $summary.superpowers
    nextActionSummary = $summary.nextActionSummary
    repeatProbeSuppressed = [bool]$summary.nextActionSummary.repeatProbeSuppressed
    evidenceNeededActionMode = $summary.nextActionSummary.evidenceNeededActionMode
    externalStateChangeRequired = [bool]$summary.nextActionSummary.externalStateChangeRequired
    resumeCondition = $summary.nextActionSummary.resumeCondition
    validatorSelfTestStatus = $summary.validatorSelfTestStatus
    reportPath = $summary.reportPath
    historyPath = $summary.historyPath
    historyPruned = $summary.historyPruned
    topTrimCandidates = $topTrimCandidates
    trimFamilyCandidateCount = @($summary.trimCandidates | Where-Object { [bool]$_.inFamily }).Count
    trimOptionalCandidateCount = @($summary.trimCandidates | Where-Object { -not [bool]$_.inFamily }).Count
    trimDiscoveredCandidateCount = @($summary.trimCandidates | Where-Object { $_.source -eq "discovered" }).Count
    discoveryNextActions = $summary.discoveryNextActions
    evidenceNeeded = $topLevelEvidenceNeeded
}

if ($CompactReport) {
    if (-not [string]::IsNullOrWhiteSpace($summary.reportPath)) {
        $summaryJsonReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summary.reportPath -Encoding UTF8
    }
    if (-not [string]::IsNullOrWhiteSpace($summary.historyPath)) {
        $summaryJsonReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summary.historyPath -Encoding UTF8
    }
    if ($HistoryRetain -gt 0 -and -not [string]::IsNullOrWhiteSpace($summary.historyPath)) {
        $historyDirForPrune = Split-Path -Parent $summary.historyPath
        $historyFiles = @(Get-ChildItem -LiteralPath $historyDirForPrune -Filter "skill-family-*.json" -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending)
        $pruneFiles = @($historyFiles | Select-Object -Skip $HistoryRetain)
        foreach ($file in $pruneFiles) {
            Remove-Item -LiteralPath $file.FullName -Force
        }
        $summary.historyPruned = $pruneFiles.Count
        $summaryJsonReport["historyPruned"] = $summary.historyPruned
        if (-not [string]::IsNullOrWhiteSpace($summary.reportPath)) {
            $summaryJsonReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summary.reportPath -Encoding UTF8
        }
        if (Test-Path -LiteralPath $summary.historyPath) {
            $summaryJsonReport | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summary.historyPath -Encoding UTF8
        }
    }
}

if ($SummaryJson) {
    $summaryJsonReport | ConvertTo-Json -Depth 6
} elseif ($Json) {
    $summary | ConvertTo-Json -Depth 6
} else {
    Write-Output ("[skill-family] root={0}" -f $summary.root)
    Write-Output ("[skill-family] skills={0} valid={1} missing={2} invalid={3} openaiPromptErrors={4} openaiMetadataErrors={5} fillMarkerHits={6} secretPatternHits={7} coverageFailures={8} discoveryIssues={9} budgetWarnings={10} trimCandidates={11} artifact={12} goal={13} completionEvidenceNeeded={14} ok={15}" -f $summary.skillFamily, $summary.validSkills, $summary.missing.Count, $summary.invalid.Count, $summary.openaiPromptErrors.Count, $summary.openaiMetadataErrors.Count, $summary.fillMarkerHits, $summary.secretPatternHits, $summary.coverageFailures.Count, $summary.discoveryIssueCount, $summary.budgetWarningCount, $summary.trimCandidates.Count, $summary.artifactCompletionStatus, $summary.goalCompletionStatus, $summary.completionAuditEvidenceNeededCount, $summary.ok)
    if (-not [string]::IsNullOrWhiteSpace($summary.reportPath)) {
        Write-Output ("[skill-family] reportPath={0}" -f $summary.reportPath)
    }
    if (-not [string]::IsNullOrWhiteSpace($summary.historyPath)) {
        Write-Output ("[skill-family] historyPath={0}" -f $summary.historyPath)
    }
    if ($summary.historyPruned -gt 0) {
        Write-Output ("[skill-family] historyPruned={0}" -f $summary.historyPruned)
    }
}

if ($summary.ok) {
    exit 0
}

exit 1

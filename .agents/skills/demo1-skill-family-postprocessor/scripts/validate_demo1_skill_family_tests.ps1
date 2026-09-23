param(
    [string]$Root = ".",
    [string]$ValidatorScript = "",
    [string]$StatusPath = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ValidatorScript)) {
    $ValidatorScript = Join-Path $PSScriptRoot "validate_demo1_skill_family.ps1"
}

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return (Resolve-Path -LiteralPath $Path).ProviderPath
    }
    return (Resolve-Path -LiteralPath (Join-Path (Get-Location) $Path)).ProviderPath
}

function Assert-True([string]$Name, [bool]$Condition, [string]$Detail = "") {
    if (-not $Condition) {
        if ([string]::IsNullOrWhiteSpace($Detail)) {
            throw "[skill-family-tests] assertion failed: $Name"
        }
        throw "[skill-family-tests] assertion failed: $Name :: $Detail"
    }
    Write-Output "[skill-family-tests] PASS $Name"
}

function Assert-TrimBudgetContract($Report, [string]$Label) {
    # The live discovered family can grow; validate the budget contract instead
    # of assuming every optional skill stays below the requested 160/1200 limits.
    $candidates = @($Report.topTrimCandidates)
    Assert-True "$Label includes every requested trim candidate" ($candidates.Count -eq [int]$Report.trimCandidateCount) "shown=$($candidates.Count) total=$($Report.trimCandidateCount)"
    $expectedPressure = @($candidates | Where-Object { [int]$_.lineCount -gt 160 -or [int]$_.wordCount -gt 1200 }).Count
    $expectedMode = if ($candidates.Count -eq 0) { "none" } elseif ($expectedPressure -gt 0) { "budget_pressure" } else { "opportunistic_only" }
    Assert-True "$Label trim pressure matches reported sizes and requested budgets" ([int]$Report.trimPressureCandidateCount -eq $expectedPressure) "expected=$expectedPressure actual=$($Report.trimPressureCandidateCount)"
    Assert-True "$Label trim action reflects actual pressure" ($Report.trimActionMode -eq $expectedMode) $Report.trimActionMode
    foreach ($candidate in $candidates) {
        $pressured = [int]$candidate.lineCount -gt 160 -or [int]$candidate.wordCount -gt 1200
        $mode = if ($pressured) { "budget_pressure" } else { "opportunistic_review" }
        Assert-True "$Label candidate pressure matches sizes" ([bool]$candidate.budgetPressure -eq $pressured) $candidate.name
        Assert-True "$Label candidate action matches pressure" ($candidate.actionMode -eq $mode) $candidate.name
    }
}

function Invoke-ValidatorJson([string[]]$Arguments) {
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $ValidatorScript @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $jsonText = [string]::Join([Environment]::NewLine, @($output | ForEach-Object { [string]$_ }))
    try {
        $json = $jsonText | ConvertFrom-Json
    } catch {
        throw "[skill-family-tests] validator did not emit JSON. exit=$exitCode output=$($jsonText.Substring(0, [Math]::Min(240, $jsonText.Length)))"
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Json = $json
    }
}

$resolvedRoot = Resolve-RepoPath $Root
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("skill-family-validator-tests-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

if ([string]::IsNullOrWhiteSpace($StatusPath)) {
    $StatusPath = Join-Path $resolvedRoot "data\agent-handoff\skill-family\validator-self-test-status.json"
} elseif (-not [System.IO.Path]::IsPathRooted($StatusPath)) {
    $StatusPath = Join-Path $resolvedRoot $StatusPath
}
$resolvedStatusPath = [System.IO.Path]::GetFullPath($StatusPath)

try {
    $commonArgs = @(
        "-Root", $resolvedRoot,
        "-DiscoverPrefix", "demo1-",
        "-SkillLineBudget", "160",
        "-SkillWordBudget", "1200",
        "-TrimCandidateCount", "5"
    )
    $missingStatusProbePath = Join-Path $tempRoot "missing-validator-self-test-status.json"
    $currentStatusFixturePath = Join-Path $tempRoot "current-validator-self-test-status.json"
    $currentStatusFixture = [ordered]@{
        schemaVersion = "demo1.skill-family-validator-self-test-status.v1"
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        ok = $true
        status = "verified"
        fullBytes = 16000
        compactBytes = 4000
        retainedHistoryCount = 2
        validatedOptions = @(
            "full-summary",
            "skip-quick",
            "full-report",
            "compact-report",
            "compact-history-retain"
        )
        rawSecretPatternHits = 0
    }
    $currentStatusFixture | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $currentStatusFixturePath -Encoding UTF8
    $baseArgs = $commonArgs + @("-ValidatorSelfTestStatusPath", $currentStatusFixturePath)

    $missingSummary = Invoke-ValidatorJson ($commonArgs + @("-ValidatorSelfTestStatusPath", $missingStatusProbePath, "-SummaryJson"))
    Assert-True "missing self-test status exits nonzero" ($missingSummary.ExitCode -ne 0) "exit=$($missingSummary.ExitCode)"
    Assert-True "missing self-test status blocks top-level ok" (-not [bool]$missingSummary.Json.ok) $missingSummary.Json.ok
    Assert-True "missing self-test status blocks artifact readiness" ($missingSummary.Json.artifactCompletionStatus -eq "evidence_needed") $missingSummary.Json.artifactCompletionStatus
    Assert-True "missing self-test status keeps active goal evidence needed" ($missingSummary.Json.goalCompletionStatus -eq "active_goal_evidence_needed") $missingSummary.Json.goalCompletionStatus
    $missingSelfTestAudit = @($missingSummary.Json.completionAuditSummary | Where-Object { $_.requirement -eq "validator-self-test-current" } | Select-Object -First 1)
    Assert-True "missing self-test status enters completion audit" ($missingSelfTestAudit.Count -eq 1 -and $missingSelfTestAudit.status -eq "evidence_needed") "count=$($missingSelfTestAudit.Count) status=$($missingSelfTestAudit.status)"
    Assert-True "missing self-test status routes exact verification" ($missingSummary.Json.nextActionSummary.target -eq "validator-self-test-current" -and [string]$missingSummary.Json.nextActionSummary.verifyWith -match "validate_demo1_skill_family_tests\.ps1") "$($missingSummary.Json.nextActionSummary.target):$($missingSummary.Json.nextActionSummary.verifyWith)"

    $fullSummary = Invoke-ValidatorJson ($baseArgs + @("-SummaryJson"))
    Assert-True "full summary exits zero" ($fullSummary.ExitCode -eq 0) "exit=$($fullSummary.ExitCode)"
    Assert-True "summary json uses summary report format" ($fullSummary.Json.reportFormat -eq "summary") $fullSummary.Json.reportFormat
    Assert-True "full summary is artifact ready" ($fullSummary.Json.artifactCompletionStatus -eq "artifact_ready") $fullSummary.Json.artifactCompletionStatus
    Assert-True "summary exposes browser lane as optional" ($fullSummary.Json.browser -eq "optional") $fullSummary.Json.browser
    Assert-True "summary exposes computer lane as optional" ($fullSummary.Json.computer -eq "optional") $fullSummary.Json.computer
    Assert-True "summary exposes supabase lane as read-only evidence needed" ($fullSummary.Json.supabase -eq "read_only_evidence_needed") $fullSummary.Json.supabase
    Assert-True "summary exposes superpowers as supporting process" ($fullSummary.Json.superpowers -eq "supporting_process") $fullSummary.Json.superpowers
    Assert-True "summary explains active goal is not complete" ($fullSummary.Json.goalCompletionReason -match "skill artifacts only") $fullSummary.Json.goalCompletionReason
    Assert-True "summary forbids claiming active goal completion" ($fullSummary.Json.goalCompletionClaimAllowed -eq $false) $fullSummary.Json.goalCompletionClaimAllowed
    Assert-True "summary recommends leaving active goal open" ($fullSummary.Json.goalUpdateRecommendation -eq "leave_goal_active") $fullSummary.Json.goalUpdateRecommendation
    Assert-True "summary recommends skipping full report when compact counts are sufficient" ($fullSummary.Json.fullReportRecommended -eq $false) $fullSummary.Json.fullReportRecommended
    Assert-True "summary exposes full report action mode" ($fullSummary.Json.fullReportActionMode -eq "skip_full_report_until_summary_counts_change") $fullSummary.Json.fullReportActionMode
    Assert-True "summary exposes reuse condition" ($fullSummary.Json.summaryReuseCondition -eq "reuse_latest_until_skill_files_or_filesystem_state_change") $fullSummary.Json.summaryReuseCondition
    Assert-True "summary exposes openai prompt error count" ($null -ne $fullSummary.Json.PSObject.Properties["openaiPromptErrorCount"]) "missing openaiPromptErrorCount"
    Assert-True "summary exposes openai metadata error count" ($null -ne $fullSummary.Json.PSObject.Properties["openaiMetadataErrorCount"]) "missing openaiMetadataErrorCount"
    Assert-True "summary exposes trigger quality checked count" ($null -ne $fullSummary.Json.PSObject.Properties["triggerQualityCheckedCount"]) "missing triggerQualityCheckedCount"
    Assert-True "summary exposes trigger quality issue count" ($null -ne $fullSummary.Json.PSObject.Properties["triggerQualityIssueCount"]) "missing triggerQualityIssueCount"
    Assert-True "summary trigger quality has no issues" ([int]$fullSummary.Json.triggerQualityIssueCount -eq 0) $fullSummary.Json.triggerQualityIssueCount
    Assert-True "full summary keeps full validation mode" ($fullSummary.Json.quickValidationMode -eq "full") $fullSummary.Json.quickValidationMode
    Assert-True "full summary skips no quick validation" ([int]$fullSummary.Json.quickValidationSkippedCount -eq 0) $fullSummary.Json.quickValidationSkippedCount
    Assert-True "full summary reports current self-test status" ($fullSummary.Json.validatorSelfTestStatus.status -eq "verified") $fullSummary.Json.validatorSelfTestStatus.status
    $summaryTrimCandidate = $fullSummary.Json.topTrimCandidates | Select-Object -First 1
    Assert-True "summary trim candidate exposes family membership" ($null -ne $summaryTrimCandidate.PSObject.Properties["inFamily"]) "missing inFamily"
    Assert-True "summary trim candidate exposes source" ($null -ne $summaryTrimCandidate.PSObject.Properties["source"]) "missing source"
    Assert-True "summary exposes trim family count" ($null -ne $fullSummary.Json.PSObject.Properties["trimFamilyCandidateCount"]) "missing trimFamilyCandidateCount"
    Assert-True "summary exposes trim optional count" ($null -ne $fullSummary.Json.PSObject.Properties["trimOptionalCandidateCount"]) "missing trimOptionalCandidateCount"
    Assert-True "summary exposes trim discovered count" ($null -ne $fullSummary.Json.PSObject.Properties["trimDiscoveredCandidateCount"]) "missing trimDiscoveredCandidateCount"
    Assert-TrimBudgetContract $fullSummary.Json "summary"
    Assert-True "summary trim family plus optional equals total" (([int]$fullSummary.Json.trimFamilyCandidateCount + [int]$fullSummary.Json.trimOptionalCandidateCount) -eq [int]$fullSummary.Json.trimCandidateCount) "family=$($fullSummary.Json.trimFamilyCandidateCount) optional=$($fullSummary.Json.trimOptionalCandidateCount) total=$($fullSummary.Json.trimCandidateCount)"
    $summaryBudgetAudit = @($fullSummary.Json.completionAuditSummary | Where-Object { $_.requirement -eq "context-budget-and-trim-signal" } | Select-Object -First 1)
    Assert-True "summary exposes completion audit summary" ($summaryBudgetAudit.Count -eq 1) "missing completionAuditSummary budget item"
    Assert-True "summary budget audit includes trim action mode" ([string]$summaryBudgetAudit.evidence -match ("trimActionMode=" + [regex]::Escape($fullSummary.Json.trimActionMode))) $summaryBudgetAudit.evidence
    Assert-True "summary budget audit includes trim pressure count" ([string]$summaryBudgetAudit.evidence -match ("trimPressureCandidates=" + $fullSummary.Json.trimPressureCandidateCount + "(;|$)")) $summaryBudgetAudit.evidence
    $postprocessorReferenceForTrimPath = Join-Path $resolvedRoot ".agents\skills\demo1-skill-family-postprocessor\references\skill-family-postprocessor-reference.md"
    $postprocessorReferenceForTrim = Get-Content -Raw -LiteralPath $postprocessorReferenceForTrimPath
    Assert-True "postprocessor reference blocks automatic trim edits for opportunistic-only mode" ($postprocessorReferenceForTrim -match "trimActionMode=opportunistic_only" -and $postprocessorReferenceForTrim -match "no skill edit is required") "missing opportunistic trim no-edit guard"
    $trimCapSummary = Invoke-ValidatorJson (@(
        "-Root", $resolvedRoot,
        "-DiscoverPrefix", "demo1-",
        "-SkillLineBudget", "160",
        "-SkillWordBudget", "1200",
        "-TrimCandidateCount", "8",
        "-ValidatorSelfTestStatusPath", $missingStatusProbePath,
        "-SummaryJson"
    ))
    Assert-True "summary exposes trim shown count" ($null -ne $trimCapSummary.Json.PSObject.Properties["topTrimCandidateShownCount"]) "missing topTrimCandidateShownCount"
    Assert-True "summary exposes trim omitted count" ($null -ne $trimCapSummary.Json.PSObject.Properties["topTrimCandidateOmittedCount"]) "missing topTrimCandidateOmittedCount"
    Assert-True "summary trim shown count matches compact list" ([int]$trimCapSummary.Json.topTrimCandidateShownCount -eq @($trimCapSummary.Json.topTrimCandidates).Count) "shown=$($trimCapSummary.Json.topTrimCandidateShownCount) listed=$(@($trimCapSummary.Json.topTrimCandidates).Count)"
    Assert-True "summary trim omitted count explains compact cap" ([int]$trimCapSummary.Json.topTrimCandidateOmittedCount -eq ([int]$trimCapSummary.Json.trimCandidateCount - [int]$trimCapSummary.Json.topTrimCandidateShownCount)) "omitted=$($trimCapSummary.Json.topTrimCandidateOmittedCount) total=$($trimCapSummary.Json.trimCandidateCount) shown=$($trimCapSummary.Json.topTrimCandidateShownCount)"
    Assert-True "current self-test status admits artifact readiness" ($fullSummary.Json.artifactCompletionStatus -eq "artifact_ready") $fullSummary.Json.artifactCompletionStatus
    Assert-True "summary exposes remediation packet" ($null -ne $fullSummary.Json.discoveryRemediationPacket) "missing discoveryRemediationPacket"
    Assert-True "summary remediation keeps mutation disabled" (-not [bool]$fullSummary.Json.discoveryRemediationPacket.mutationAllowed) $fullSummary.Json.discoveryRemediationPacket.mutationAllowed
    Assert-True "summary remediation emits no delete command" (-not [bool]$fullSummary.Json.discoveryRemediationPacket.deleteCommandEmitted) $fullSummary.Json.discoveryRemediationPacket.deleteCommandEmitted
    Assert-True "summary remediation emits no acl command" (-not [bool]$fullSummary.Json.discoveryRemediationPacket.aclCommandEmitted) $fullSummary.Json.discoveryRemediationPacket.aclCommandEmitted
    Assert-True "summary exposes manual filesystem review bucket" ($null -ne $fullSummary.Json.manualFilesystemReviewBucket) "missing manualFilesystemReviewBucket"
    Assert-True "summary manual filesystem review mirrors remediation count" ([int]$fullSummary.Json.manualFilesystemReviewBucket.itemCount -eq [int]$fullSummary.Json.discoveryRemediationPacket.issueCount) "bucket=$($fullSummary.Json.manualFilesystemReviewBucket.itemCount) remediation=$($fullSummary.Json.discoveryRemediationPacket.issueCount)"
    Assert-True "summary manual filesystem review keeps mutation disabled" (-not [bool]$fullSummary.Json.manualFilesystemReviewBucket.mutationAllowed) $fullSummary.Json.manualFilesystemReviewBucket.mutationAllowed
    Assert-True "summary manual filesystem review emits no delete command" (-not [bool]$fullSummary.Json.manualFilesystemReviewBucket.deleteCommandEmitted) $fullSummary.Json.manualFilesystemReviewBucket.deleteCommandEmitted
    Assert-True "summary manual filesystem review emits no acl command" (-not [bool]$fullSummary.Json.manualFilesystemReviewBucket.aclCommandEmitted) $fullSummary.Json.manualFilesystemReviewBucket.aclCommandEmitted
    Assert-True "summary manual filesystem review captures no raw acl" (-not [bool]$fullSummary.Json.manualFilesystemReviewBucket.rawAclCaptured) $fullSummary.Json.manualFilesystemReviewBucket.rawAclCaptured
    Assert-True "summary exposes next action summary" ($null -ne $fullSummary.Json.nextActionSummary) "missing nextActionSummary"
    Assert-True "summary next action keeps mutation disabled" (-not [bool]$fullSummary.Json.nextActionSummary.mutationAllowed) $fullSummary.Json.nextActionSummary.mutationAllowed
    Assert-True "summary next action keeps external lanes demand driven" ($fullSummary.Json.nextActionSummary.externalLaneMode -eq "demand_driven") $fullSummary.Json.nextActionSummary.externalLaneMode
    $unsafeHumanActionPattern = "(?i)\bACL\b|\bdelete\b|\bremove\b|\bremoving\b|\brestoring\b"
    $validatorTokens = $null
    $validatorParseErrors = $null
    $validatorAst = [System.Management.Automation.Language.Parser]::ParseFile(
        $ValidatorScript, [ref]$validatorTokens, [ref]$validatorParseErrors)
    Assert-True "validator helper fixture parses" (@($validatorParseErrors).Count -eq 0) (@($validatorParseErrors) -join " | ")
    foreach ($helperName in @("Get-SourceControlHint", "New-DiscoveryRemediationPacket", "New-ManualFilesystemReviewBucket", "New-NextActionSummary")) {
        $helperAst = @($validatorAst.FindAll({
                    param($node)
                    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $helperName
                }, $true) | Select-Object -First 1)
        Assert-True "validator helper exists: $helperName" ($helperAst.Count -eq 1) $helperName
        . ([scriptblock]::Create($helperAst[0].Extent.Text))
    }
    $syntheticAccessInventory = @([pscustomobject]@{
            name = "demo1-fixture-inaccessible"
            gitTrackingStatus = "checked"
            gitTrackedPathCount = 0
            directoryListingStatus = "inaccessible"
            directoryListingErrorType = "UnauthorizedAccessException"
            skillMdStatus = "inaccessible"
            openaiYamlStatus = "inaccessible"
            accessIssues = @("skill-directory", "SKILL.md", "agents/openai.yaml")
            issues = @("inaccessible-skill-directory", "inaccessible-skill-md", "inaccessible-openai-yaml")
        })
    $syntheticRemediation = New-DiscoveryRemediationPacket -AccessInventory $syntheticAccessInventory
    $syntheticManualBucket = New-ManualFilesystemReviewBucket -DiscoveryRemediationPacket $syntheticRemediation
    $syntheticNextAction = New-NextActionSummary -ManualFilesystemReviewBucket $syntheticManualBucket -CompletionAudit @() -ArtifactCompletionStatus "artifact_ready" -GoalCompletionStatus "artifact_only_not_goal_complete" -ExternalLaneMode "demand_driven" -DiscoveryGateImpact "non_blocking_report_only"
    Assert-True "synthetic inaccessible fixture routes untracked manual review" ($syntheticRemediation.items[0].recommendedAction -eq "manual_review_untracked_inaccessible_skill_dir") $syntheticRemediation.items[0].recommendedAction
    Assert-True "synthetic inaccessible fixture records blocked count-only probe" ($syntheticNextAction.validatorProbeStatus -eq "blocked_by_filesystem_access" -and [bool]$syntheticNextAction.validatorProbeAttempted) "$($syntheticNextAction.validatorProbeStatus):$($syntheticNextAction.validatorProbeAttempted)"
    Assert-True "synthetic inaccessible fixture suppresses repeat probe" ([bool]$syntheticNextAction.repeatProbeSuppressed -and $syntheticNextAction.resumeCondition -eq "filesystem_state_changes") "$($syntheticNextAction.repeatProbeSuppressed):$($syntheticNextAction.resumeCondition)"
    Assert-True "synthetic inaccessible fixture emits no mutation command" (-not [bool]$syntheticRemediation.mutationAllowed -and -not [bool]$syntheticRemediation.deleteCommandEmitted -and -not [bool]$syntheticRemediation.aclCommandEmitted) "mutation safety failed"

    $liveAccessIssueCount = [int]$fullSummary.Json.discoveryAccessIssueCount
    Assert-True "live manual review count matches access inventory" ([int]$fullSummary.Json.manualFilesystemReviewBucket.itemCount -eq $liveAccessIssueCount) "bucket=$($fullSummary.Json.manualFilesystemReviewBucket.itemCount) access=$liveAccessIssueCount"
    if ($liveAccessIssueCount -gt 0) {
        $liveAccessNames = @($fullSummary.Json.discoveryAccessInventory | ForEach-Object { $_.name })
        Assert-True "live access issue routes a matching manual target" ($liveAccessNames -contains $fullSummary.Json.nextActionSummary.target) "$($fullSummary.Json.nextActionSummary.target)"
        foreach ($item in @($fullSummary.Json.manualFilesystemReviewBucket.items)) {
            Assert-True "live access item keeps blocked count-only status" ($item.validatorProbeStatus -eq "blocked_by_filesystem_access") $item.validatorProbeStatus
            Assert-True "live access item emits no mutation command" (-not [bool]$item.mutationAllowed -and -not [bool]$item.deleteCommandEmitted -and -not [bool]$item.aclCommandEmitted) $item.name
        }
    } else {
        Assert-True "live manual review bucket is empty without access issues" ($fullSummary.Json.manualFilesystemReviewBucket.status -eq "none") $fullSummary.Json.manualFilesystemReviewBucket.status
        Assert-True "live next action is not an access review without access issues" ($fullSummary.Json.nextActionSummary.status -ne "manual_filesystem_review_required") $fullSummary.Json.nextActionSummary.status
    }
    $postprocessorReferencePath = Join-Path $resolvedRoot ".agents\skills\demo1-skill-family-postprocessor\references\skill-family-postprocessor-reference.md"
    $postprocessorReference = Get-Content -Raw -LiteralPath $postprocessorReferencePath
    Assert-True "postprocessor reference documents validator probe status" ($postprocessorReference -match "validatorProbeStatus") "missing validatorProbeStatus guidance"
    $usageTriageReferencePath = Join-Path $resolvedRoot ".agents\skills\demo1-codex-usage-triage\references\codex-usage-triage-reference.md"
    $usageTriageReference = Get-Content -Raw -LiteralPath $usageTriageReferencePath
    Assert-True "usage triage guidance prefers compact next action summary" ($usageTriageReference -match "nextActionSummary") "missing nextActionSummary guidance"
    Assert-True "usage triage guidance uses validator probe status before repeating manual review" ($usageTriageReference -match "validatorProbeStatus") "missing validatorProbeStatus guidance"
    Assert-True "usage triage guidance consumes compact repeat blocker line" ($usageTriageReference -match "compactReportLine" -and $usageTriageReference -match "same blocker, no repeat probe") "missing compactReportLine guidance"
    Assert-True "usage triage guidance consumes goal completion claim guard" ($usageTriageReference -match "goalCompletionClaimAllowed") "missing goalCompletionClaimAllowed guidance"
    Assert-True "usage triage guidance consumes goal update recommendation" ($usageTriageReference -match "goalUpdateRecommendation") "missing goalUpdateRecommendation guidance"
    Assert-True "usage triage guidance consumes full report recommendation" ($usageTriageReference -match "fullReportRecommended") "missing fullReportRecommended guidance"
    Assert-True "usage triage guidance consumes full report action mode" ($usageTriageReference -match "fullReportActionMode") "missing fullReportActionMode guidance"
    Assert-True "usage triage guidance consumes summary reuse condition" ($usageTriageReference -match "summaryReuseCondition") "missing summaryReuseCondition guidance"
    Assert-True "usage triage guidance consumes compact report savings percent" ($usageTriageReference -match "compactReportSavingsPercent") "missing compactReportSavingsPercent guidance"
    Assert-True "usage triage guidance blocks automatic trim edits for opportunistic-only mode" ($usageTriageReference -match "trimActionMode=opportunistic_only" -and $usageTriageReference -match "no skill edit is required") "missing usage triage opportunistic trim guard"
    $longThinkReferencePath = Join-Path $resolvedRoot ".agents\skills\demo1-long-think-goal-composer\references\long-think-goal-composer-reference.md"
    $longThinkReference = Get-Content -Raw -LiteralPath $longThinkReferencePath
    Assert-True "long-think verification persists compact summary" ($longThinkReference -match "-CompactReport" -and $longThinkReference -match "-SummaryJson") "missing compact summary verification command"
    Assert-True "long-think guidance consumes repeat probe recommendation" ($longThinkReference -match "repeatProbeRecommendation") "missing repeatProbeRecommendation guidance"
    Assert-True "long-think guidance consumes goal completion claim guard" ($longThinkReference -match "goalCompletionClaimAllowed") "missing goalCompletionClaimAllowed guidance"
    Assert-True "long-think guidance consumes goal update recommendation" ($longThinkReference -match "goalUpdateRecommendation") "missing goalUpdateRecommendation guidance"
    Assert-True "long-think guidance consumes full report recommendation" ($longThinkReference -match "fullReportRecommended") "missing fullReportRecommended guidance"
    Assert-True "long-think guidance consumes full report action mode" ($longThinkReference -match "fullReportActionMode") "missing fullReportActionMode guidance"
    Assert-True "long-think guidance consumes summary reuse condition" ($longThinkReference -match "summaryReuseCondition") "missing summaryReuseCondition guidance"
    $superpowersReferencePath = Join-Path $resolvedRoot ".agents\skills\demo1-superpowers-repo-evidence-guard\references\superpowers-repo-evidence-guard-reference.md"
    $superpowersReference = Get-Content -Raw -LiteralPath $superpowersReferencePath
    Assert-True "superpowers guard consumes goal completion claim guard" ($superpowersReference -match "goalCompletionClaimAllowed") "missing goalCompletionClaimAllowed guidance"
    Assert-True "superpowers guard consumes full report recommendation" ($superpowersReference -match "fullReportRecommended") "missing fullReportRecommended guidance"
    Assert-True "superpowers guard consumes summary reuse condition" ($superpowersReference -match "summaryReuseCondition") "missing summaryReuseCondition guidance"
    $externalProofReferencePath = Join-Path $resolvedRoot ".agents\skills\demo1-demand-driven-external-proof\references\demand-driven-external-proof-reference.md"
    $externalProofReference = Get-Content -Raw -LiteralPath $externalProofReferencePath
    Assert-True "external proof guidance consumes artifact completion status" ($externalProofReference -match "artifactCompletionStatus") "missing artifactCompletionStatus guidance"
    Assert-True "external proof guidance consumes goal completion claim guard" ($externalProofReference -match "goalCompletionClaimAllowed") "missing goalCompletionClaimAllowed guidance"
    Assert-True "external proof guidance consumes full report recommendation" ($externalProofReference -match "fullReportRecommended") "missing fullReportRecommended guidance"
    $longThinkSelfTestPath = Join-Path $resolvedRoot ".agents\skills\demo1-long-think-goal-composer\scripts\validate_long_think_goal_composer_tests.ps1"
    Assert-True "long-think self-test script exists" (Test-Path -LiteralPath $longThinkSelfTestPath -PathType Leaf) "missing long-think self-test script"
    $longThinkSelfTestOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $longThinkSelfTestPath -Root $resolvedRoot 2>&1
    $longThinkSelfTestExit = $LASTEXITCODE
    Assert-True "long-think self-test exits zero" ($longThinkSelfTestExit -eq 0) "exit=$longThinkSelfTestExit output=$([string]::Join(' ', @($longThinkSelfTestOutput)))"
    Assert-True "long-think self-test reports ok" (([string]::Join(' ', @($longThinkSelfTestOutput))) -match "ok=True") ([string]::Join(' ', @($longThinkSelfTestOutput)))
    $postprocessorReferencePath = Join-Path $resolvedRoot ".agents\skills\demo1-skill-family-postprocessor\references\skill-family-postprocessor-reference.md"
    $postprocessorReference = Get-Content -Raw -LiteralPath $postprocessorReferencePath
    Assert-True "postprocessor durable latest report defaults compact summary" ($postprocessorReference -match "ReportPath \.\\data\\agent-handoff\\skill-family\\latest\.json[^\r\n]*-CompactReport[^\r\n]*-SummaryJson") "latest.json durable report is not compact summary by default"
    Assert-True "postprocessor full report uses explicit full artifact" ($postprocessorReference -match "latest\.full\.json") "missing explicit full report artifact"

    $skipSummary = Invoke-ValidatorJson ($baseArgs + @("-SkipQuickValidate", "-SummaryJson"))
    Assert-True "skip quick exits nonzero" ($skipSummary.ExitCode -ne 0) "exit=$($skipSummary.ExitCode)"
    Assert-True "skip quick summary uses summary report format" ($skipSummary.Json.reportFormat -eq "summary") $skipSummary.Json.reportFormat
    Assert-True "skip quick forces evidence_needed" ($skipSummary.Json.artifactCompletionStatus -eq "evidence_needed") $skipSummary.Json.artifactCompletionStatus
    Assert-True "skip quick forbids claiming active goal completion" ($skipSummary.Json.goalCompletionClaimAllowed -eq $false) $skipSummary.Json.goalCompletionClaimAllowed
    Assert-True "skip quick recommends collecting artifact evidence" ($skipSummary.Json.goalUpdateRecommendation -eq "leave_goal_active_collect_artifact_evidence") $skipSummary.Json.goalUpdateRecommendation
    Assert-True "skip quick marks skipped mode" ($skipSummary.Json.quickValidationMode -eq "skipped") $skipSummary.Json.quickValidationMode
    Assert-True "skip quick skips each required skill" ([int]$skipSummary.Json.quickValidationSkippedCount -eq [int]$skipSummary.Json.skillFamily) "skipped=$($skipSummary.Json.quickValidationSkippedCount) family=$($skipSummary.Json.skillFamily)"

    $genericRoot = Join-Path $tempRoot "generic-stale-root"
    $genericSkillRoot = Join-Path $genericRoot ".agents\skills\demo1-generic-stale"
    New-Item -ItemType Directory -Force -Path $genericSkillRoot | Out-Null
    $genericSummary = Invoke-ValidatorJson @("-Root", $genericRoot, "-DiscoverPrefix", "demo1-", "-SkipQuickValidate", "-Json")
    Assert-True "generic stale fixture exits nonzero" ($genericSummary.ExitCode -ne 0) "exit=$($genericSummary.ExitCode)"
    $genericNextAction = @($genericSummary.Json.discoveryNextActions | Where-Object { $_.name -eq "demo1-generic-stale" } | Select-Object -First 1)
    $genericDriftCandidate = @($genericSummary.Json.skillFamilyDriftPacket.items | Where-Object { $_.name -eq "demo1-generic-stale" } | Select-Object -First 1)
    Assert-True "generic next action evidence avoids acl delete remove wording" (-not ([string]$genericNextAction.evidenceNeeded -match $unsafeHumanActionPattern)) $genericNextAction.evidenceNeeded
    Assert-True "generic stale fixture uses git unknown hint" ($genericDriftCandidate.sourceControlHint -eq "git_tracking_unknown") $genericDriftCandidate.sourceControlHint
    Assert-True "generic stale drift candidate avoids acl or remove wording" ($genericDriftCandidate.recommendedAction -eq "manual_review_stale_or_inaccessible_skill_dir") $genericDriftCandidate.recommendedAction
    Assert-True "generic stale drift candidate keeps mutation disabled" ($genericDriftCandidate.mutationAllowed -eq $false) $genericDriftCandidate.mutationAllowed
    Assert-True "generic stale drift candidate emits no delete command" ($genericDriftCandidate.deleteCommandEmitted -eq $false) $genericDriftCandidate.deleteCommandEmitted
    Assert-True "generic stale drift candidate emits no acl command" ($genericDriftCandidate.aclCommandEmitted -eq $false) $genericDriftCandidate.aclCommandEmitted

    $trackedRoot = Join-Path $tempRoot "tracked-stale-root"
    $trackedSkillRoot = Join-Path $trackedRoot ".agents\skills\demo1-tracked-stale"
    New-Item -ItemType Directory -Force -Path $trackedSkillRoot | Out-Null
    Set-Content -LiteralPath (Join-Path $trackedSkillRoot "tracked-marker.txt") -Value "tracked fixture" -Encoding UTF8
    & git -C $trackedRoot init | Out-Null
    & git -C $trackedRoot add ".agents/skills/demo1-tracked-stale/tracked-marker.txt" | Out-Null
    Assert-True "tracked stale fixture git add succeeds" ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    $trackedSummary = Invoke-ValidatorJson @("-Root", $trackedRoot, "-DiscoverPrefix", "demo1-", "-SkipQuickValidate", "-Json")
    Assert-True "tracked stale fixture exits nonzero" ($trackedSummary.ExitCode -ne 0) "exit=$($trackedSummary.ExitCode)"
    $trackedNextAction = @($trackedSummary.Json.discoveryNextActions | Where-Object { $_.name -eq "demo1-tracked-stale" } | Select-Object -First 1)
    $trackedRemediation = @($trackedSummary.Json.discoveryRemediationPacket.items | Where-Object { $_.name -eq "demo1-tracked-stale" } | Select-Object -First 1)
    $trackedDriftCandidate = @($trackedSummary.Json.skillFamilyDriftPacket.items | Where-Object { $_.name -eq "demo1-tracked-stale" } | Select-Object -First 1)
    Assert-True "tracked stale fixture uses tracked hint" ($trackedDriftCandidate.sourceControlHint -eq "tracked_inaccessible_candidate") $trackedDriftCandidate.sourceControlHint
    Assert-True "tracked next action avoids acl or remove wording" ($trackedNextAction.recommendedAction -eq "manual_review_tracked_missing_skill_md") $trackedNextAction.recommendedAction
    Assert-True "tracked next action evidence avoids acl delete remove wording" (-not ([string]$trackedNextAction.evidenceNeeded -match $unsafeHumanActionPattern)) $trackedNextAction.evidenceNeeded
    $trackedRemediationSafe = ($null -eq $trackedRemediation -or [string]::IsNullOrWhiteSpace([string]$trackedRemediation.recommendedAction) -or $trackedRemediation.recommendedAction -eq "manual_review_tracked_missing_skill_md")
    Assert-True "tracked remediation avoids acl or remove wording" $trackedRemediationSafe $trackedRemediation.recommendedAction
    $trackedRemediationEvidenceSafe = ($null -eq $trackedRemediation -or -not ([string]$trackedRemediation.evidenceNeeded -match $unsafeHumanActionPattern))
    Assert-True "tracked remediation evidence avoids acl delete remove wording" $trackedRemediationEvidenceSafe $trackedRemediation.evidenceNeeded
    Assert-True "tracked drift avoids acl wording" ($trackedDriftCandidate.recommendedAction -eq "manual_review_tracked_inaccessible_skill_dir") $trackedDriftCandidate.recommendedAction
    Assert-True "tracked drift keeps mutation disabled" ($trackedDriftCandidate.mutationAllowed -eq $false) $trackedDriftCandidate.mutationAllowed
    Assert-True "tracked drift emits no delete command" ($trackedDriftCandidate.deleteCommandEmitted -eq $false) $trackedDriftCandidate.deleteCommandEmitted
    Assert-True "tracked drift emits no acl command" ($trackedDriftCandidate.aclCommandEmitted -eq $false) $trackedDriftCandidate.aclCommandEmitted

    $fullReportPath = Join-Path $tempRoot "skill-family-full.json"
    $compactReportPath = Join-Path $tempRoot "skill-family-summary.json"

    $fullReportRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $ValidatorScript @baseArgs -ReportPath $fullReportPath 2>&1
    $fullReportExit = $LASTEXITCODE
    Assert-True "full report exits zero" ($fullReportExit -eq 0) "exit=$fullReportExit output=$($fullReportRun | Select-Object -First 1)"
    $fullReport = Get-Content -Raw -LiteralPath $fullReportPath | ConvertFrom-Json
    $fullReportBytes = (Get-Item -LiteralPath $fullReportPath).Length
    Assert-True "full report keeps report schema" ($fullReport.schemaVersion -eq "demo1.skill-family-report.v1") $fullReport.schemaVersion
    Assert-True "full report marks full format" ($fullReport.reportFormat -eq "full") $fullReport.reportFormat
    foreach ($driftCandidate in @($fullReport.skillFamilyDriftPacket.items)) {
        Assert-True "full report drift classification emits no mutation command" (-not [bool]$driftCandidate.mutationAllowed -and -not [bool]$driftCandidate.deleteCommandEmitted -and -not [bool]$driftCandidate.aclCommandEmitted) $driftCandidate.name
    }

    $compactReportRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $ValidatorScript @baseArgs -ReportPath $compactReportPath -CompactReport 2>&1
    $compactReportExit = $LASTEXITCODE
    Assert-True "compact report exits zero" ($compactReportExit -eq 0) "exit=$compactReportExit output=$($compactReportRun | Select-Object -First 1)"
    $compactReport = Get-Content -Raw -LiteralPath $compactReportPath | ConvertFrom-Json
    $compactReportBytes = (Get-Item -LiteralPath $compactReportPath).Length
    Assert-True "compact report uses summary schema" ($compactReport.schemaVersion -eq "demo1.skill-family-summary.v1") $compactReport.schemaVersion
    Assert-True "compact report marks summary format" ($compactReport.reportFormat -eq "summary") $compactReport.reportFormat
    Assert-True "compact report exposes browser lane as optional" ($compactReport.browser -eq "optional") $compactReport.browser
    Assert-True "compact report exposes computer lane as optional" ($compactReport.computer -eq "optional") $compactReport.computer
    Assert-True "compact report exposes supabase lane as read-only evidence needed" ($compactReport.supabase -eq "read_only_evidence_needed") $compactReport.supabase
    Assert-True "compact report exposes superpowers as supporting process" ($compactReport.superpowers -eq "supporting_process") $compactReport.superpowers
    Assert-True "compact report explains active goal is not complete" ($compactReport.goalCompletionReason -match "skill artifacts only") $compactReport.goalCompletionReason
    Assert-True "compact report forbids claiming active goal completion" ($compactReport.goalCompletionClaimAllowed -eq $false) $compactReport.goalCompletionClaimAllowed
    Assert-True "compact report recommends leaving active goal open" ($compactReport.goalUpdateRecommendation -eq "leave_goal_active") $compactReport.goalUpdateRecommendation
    Assert-True "compact report recommends skipping full report when compact counts are sufficient" ($compactReport.fullReportRecommended -eq $false) $compactReport.fullReportRecommended
    Assert-True "compact report exposes full report action mode" ($compactReport.fullReportActionMode -eq "skip_full_report_until_summary_counts_change") $compactReport.fullReportActionMode
    Assert-True "compact report exposes reuse condition" ($compactReport.summaryReuseCondition -eq "reuse_latest_until_skill_files_or_filesystem_state_change") $compactReport.summaryReuseCondition
    $compactTrimCandidate = $compactReport.topTrimCandidates | Select-Object -First 1
    Assert-True "compact trim candidate exposes family membership" ($null -ne $compactTrimCandidate.PSObject.Properties["inFamily"]) "missing inFamily"
    Assert-True "compact trim candidate exposes source" ($null -ne $compactTrimCandidate.PSObject.Properties["source"]) "missing source"
    Assert-True "compact report exposes trim family count" ($null -ne $compactReport.PSObject.Properties["trimFamilyCandidateCount"]) "missing trimFamilyCandidateCount"
    Assert-True "compact report exposes trim optional count" ($null -ne $compactReport.PSObject.Properties["trimOptionalCandidateCount"]) "missing trimOptionalCandidateCount"
    Assert-True "compact report exposes trim discovered count" ($null -ne $compactReport.PSObject.Properties["trimDiscoveredCandidateCount"]) "missing trimDiscoveredCandidateCount"
    Assert-TrimBudgetContract $compactReport "compact"
    Assert-True "compact report trim family plus optional equals total" (([int]$compactReport.trimFamilyCandidateCount + [int]$compactReport.trimOptionalCandidateCount) -eq [int]$compactReport.trimCandidateCount) "family=$($compactReport.trimFamilyCandidateCount) optional=$($compactReport.trimOptionalCandidateCount) total=$($compactReport.trimCandidateCount)"
    $compactBudgetAudit = @($compactReport.completionAuditSummary | Where-Object { $_.requirement -eq "context-budget-and-trim-signal" } | Select-Object -First 1)
    Assert-True "compact report exposes completion audit summary" ($compactBudgetAudit.Count -eq 1) "missing completionAuditSummary budget item"
    Assert-True "compact budget audit includes trim action mode" ([string]$compactBudgetAudit.evidence -match ("trimActionMode=" + [regex]::Escape($compactReport.trimActionMode))) $compactBudgetAudit.evidence
    Assert-True "compact budget audit includes trim pressure count" ([string]$compactBudgetAudit.evidence -match ("trimPressureCandidates=" + $compactReport.trimPressureCandidateCount + "(;|$)")) $compactBudgetAudit.evidence
    Assert-True "compact report includes current self-test status" ($compactReport.validatorSelfTestStatus.status -eq "verified") $compactReport.validatorSelfTestStatus.status
    Assert-True "compact report includes remediation packet" ($null -ne $compactReport.discoveryRemediationPacket) "missing discoveryRemediationPacket"
    Assert-True "compact report remediation keeps mutation disabled" (-not [bool]$compactReport.discoveryRemediationPacket.mutationAllowed) $compactReport.discoveryRemediationPacket.mutationAllowed
    Assert-True "compact report includes manual filesystem review bucket" ($null -ne $compactReport.manualFilesystemReviewBucket) "missing manualFilesystemReviewBucket"
    Assert-True "compact report manual filesystem review mirrors remediation count" ([int]$compactReport.manualFilesystemReviewBucket.itemCount -eq [int]$compactReport.discoveryRemediationPacket.issueCount) "bucket=$($compactReport.manualFilesystemReviewBucket.itemCount) remediation=$($compactReport.discoveryRemediationPacket.issueCount)"
    Assert-True "compact report manual filesystem review keeps mutation disabled" (-not [bool]$compactReport.manualFilesystemReviewBucket.mutationAllowed) $compactReport.manualFilesystemReviewBucket.mutationAllowed
    Assert-True "compact report includes next action summary" ($null -ne $compactReport.nextActionSummary) "missing nextActionSummary"
    Assert-True "compact report next action keeps mutation disabled" (-not [bool]$compactReport.nextActionSummary.mutationAllowed) $compactReport.nextActionSummary.mutationAllowed
    $compactAccessIssueCount = [int]$compactReport.discoveryAccessIssueCount
    Assert-True "compact manual review count matches access inventory" ([int]$compactReport.manualFilesystemReviewBucket.itemCount -eq $compactAccessIssueCount) "bucket=$($compactReport.manualFilesystemReviewBucket.itemCount) access=$compactAccessIssueCount"
    if ($compactAccessIssueCount -gt 0) {
        $compactAccessNames = @($compactReport.discoveryAccessInventory | ForEach-Object { $_.name })
        Assert-True "compact access issue routes a matching manual target" ($compactAccessNames -contains $compactReport.nextActionSummary.target) $compactReport.nextActionSummary.target
        Assert-True "compact access issue suppresses repeat probe" ([bool]$compactReport.nextActionSummary.repeatProbeSuppressed -and $compactReport.nextActionSummary.resumeCondition -eq "filesystem_state_changes") "$($compactReport.nextActionSummary.repeatProbeSuppressed):$($compactReport.nextActionSummary.resumeCondition)"
    } else {
        Assert-True "compact manual review bucket is empty without access issues" ($compactReport.manualFilesystemReviewBucket.status -eq "none") $compactReport.manualFilesystemReviewBucket.status
        Assert-True "compact next action is not an access review without access issues" ($compactReport.nextActionSummary.status -ne "manual_filesystem_review_required") $compactReport.nextActionSummary.status
    }
    Assert-True "compact report is smaller than full report" ($compactReportBytes -lt $fullReportBytes) "compact=$compactReportBytes full=$fullReportBytes"

    $historyDir = Join-Path $tempRoot "history"
    New-Item -ItemType Directory -Force -Path $historyDir | Out-Null
    1..4 | ForEach-Object {
        Set-Content -LiteralPath (Join-Path $historyDir ("skill-family-old{0}.json" -f $_)) -Value "{}" -Encoding UTF8
        Start-Sleep -Milliseconds 15
    }

    $historySummary = Invoke-ValidatorJson ($baseArgs + @("-HistoryDir", $historyDir, "-HistoryRetain", "2", "-CompactReport", "-SummaryJson"))
    $historyFiles = @(Get-ChildItem -LiteralPath $historyDir -Filter "skill-family-*.json" -File)
    Assert-True "compact history exits zero" ($historySummary.ExitCode -eq 0) "exit=$($historySummary.ExitCode)"
    Assert-True "compact history honors retain count" ($historyFiles.Count -eq 2) "count=$($historyFiles.Count)"
    Assert-True "compact history reports pruning" ([int]$historySummary.Json.historyPruned -eq 3) "historyPruned=$($historySummary.Json.historyPruned)"
    Assert-True "compact history file survives pruning" (Test-Path -LiteralPath $historySummary.Json.historyPath) $historySummary.Json.historyPath

    $statusPayload = [ordered]@{
        schemaVersion = "demo1.skill-family-validator-self-test-status.v1"
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        ok = $true
        status = "verified"
        fullBytes = $fullReportBytes
        compactBytes = $compactReportBytes
        retainedHistoryCount = $historyFiles.Count
        validatedOptions = @(
            "full-summary",
            "skip-quick",
            "full-report",
            "compact-report",
            "compact-history-retain"
        )
        rawSecretPatternHits = 0
    }
    $statusParent = Split-Path -Parent $resolvedStatusPath
    if (-not [string]::IsNullOrWhiteSpace($statusParent)) {
        New-Item -ItemType Directory -Force -Path $statusParent | Out-Null
    }
    $statusPayload | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resolvedStatusPath -Encoding UTF8
    $statusJson = Get-Content -Raw -LiteralPath $resolvedStatusPath | ConvertFrom-Json
    Assert-True "self-test status artifact exists" (Test-Path -LiteralPath $resolvedStatusPath) $resolvedStatusPath
    Assert-True "self-test status artifact is ok" ([bool]$statusJson.ok) $statusJson.status
    Assert-True "self-test status avoids raw secret hits" ([int]$statusJson.rawSecretPatternHits -eq 0) $statusJson.rawSecretPatternHits

    $statusAwareSummary = Invoke-ValidatorJson ($commonArgs + @("-ValidatorSelfTestStatusPath", $resolvedStatusPath, "-SummaryJson"))
    Assert-True "status-aware summary exits zero" ($statusAwareSummary.ExitCode -eq 0) "exit=$($statusAwareSummary.ExitCode)"
    Assert-True "status-aware summary reports verified self-test" ($statusAwareSummary.Json.validatorSelfTestStatus.status -eq "verified") $statusAwareSummary.Json.validatorSelfTestStatus.status
    Assert-True "status-aware summary reports current self-test" ($statusAwareSummary.Json.validatorSelfTestStatus.freshnessStatus -eq "current") $statusAwareSummary.Json.validatorSelfTestStatus.freshnessStatus
    Assert-True "status-aware summary carries compact byte count" ([int]$statusAwareSummary.Json.validatorSelfTestStatus.compactBytes -eq $compactReportBytes) "summary=$($statusAwareSummary.Json.validatorSelfTestStatus.compactBytes) expected=$compactReportBytes"
    Assert-True "status-aware summary mirrors full report byte count top-level" ([int]$statusAwareSummary.Json.fullReportBytes -eq $fullReportBytes) "summary=$($statusAwareSummary.Json.fullReportBytes) expected=$fullReportBytes"
    Assert-True "status-aware summary mirrors compact report byte count top-level" ([int]$statusAwareSummary.Json.compactReportBytes -eq $compactReportBytes) "summary=$($statusAwareSummary.Json.compactReportBytes) expected=$compactReportBytes"
    Assert-True "status-aware summary exposes compact report savings percent" ([int]$statusAwareSummary.Json.compactReportSavingsPercent -gt 0) $statusAwareSummary.Json.compactReportSavingsPercent

    $staleStatusPath = Join-Path $tempRoot "stale-validator-self-test-status.json"
    $staleStatusPayload = [ordered]@{
        schemaVersion = "demo1.skill-family-validator-self-test-status.v1"
        generatedAtUtc = [DateTime]::UtcNow.AddDays(-2).ToString("o")
        ok = $true
        status = "verified"
        fullBytes = $fullReportBytes
        compactBytes = $compactReportBytes
        retainedHistoryCount = $historyFiles.Count
        validatedOptions = @("full-summary")
        rawSecretPatternHits = 0
    }
    $staleStatusPayload | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $staleStatusPath -Encoding UTF8
    $staleSummary = Invoke-ValidatorJson ($commonArgs + @("-ValidatorSelfTestStatusPath", $staleStatusPath, "-ValidatorSelfTestFreshMinutes", "60", "-SummaryJson"))
    Assert-True "stale self-test status exits nonzero" ($staleSummary.ExitCode -ne 0) "exit=$($staleSummary.ExitCode)"
    Assert-True "stale self-test status is classified stale" ($staleSummary.Json.validatorSelfTestStatus.status -eq "stale") $staleSummary.Json.validatorSelfTestStatus.status
    Assert-True "stale self-test status is not ok" (-not [bool]$staleSummary.Json.validatorSelfTestStatus.ok) $staleSummary.Json.validatorSelfTestStatus.ok
    Assert-True "stale self-test status blocks top-level ok" (-not [bool]$staleSummary.Json.ok) $staleSummary.Json.ok
    Assert-True "stale self-test status blocks artifact readiness" ($staleSummary.Json.artifactCompletionStatus -eq "evidence_needed") $staleSummary.Json.artifactCompletionStatus
    Assert-True "stale self-test status routes exact verification" ($staleSummary.Json.nextActionSummary.target -eq "validator-self-test-current" -and [string]$staleSummary.Json.nextActionSummary.verifyWith -match "validate_demo1_skill_family_tests\.ps1") "$($staleSummary.Json.nextActionSummary.target):$($staleSummary.Json.nextActionSummary.verifyWith)"

    Write-Output "[skill-family-tests] ok=True fullBytes=$fullReportBytes compactBytes=$compactReportBytes retained=$($historyFiles.Count)"
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}

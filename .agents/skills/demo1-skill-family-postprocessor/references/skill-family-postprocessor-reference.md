# Skill Family Postprocessor Reference

This reference keeps command variants and report-field detail out of the
entrypoint.

## Validator Commands

Validate this postprocessor skill after skill-only edits:

```powershell
$env:PYTHONUTF8 = "1"
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-skill-family-postprocessor
```

Run the validator option self-test after editing validator behavior:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root .
```

The self-test writes a compact status artifact for dashboards:

```text
data\agent-handoff\skill-family\validator-self-test-status.json
```

A missing, stale, unreadable, or not-ok status blocks the top-level `ok` and
`artifactCompletionStatus` gates. Run the reported `verifyWith` command; only a
current successful status permits `artifact_ready`.

Fast status check:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
```

Fast discovery-only check, not artifact completion proof:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SkipQuickValidate -SummaryJson
```

Default JSON check:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -Json
```

Compact machine-readable summary:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -SummaryJson
```

Persist the latest compact report for dashboard or repeated triage loops:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -ReportPath .\data\agent-handoff\skill-family\latest.json -CompactReport -SummaryJson
```

Persist a full diagnostic report only when the compact next action points to missing detail:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -ReportPath .\data\agent-handoff\skill-family\latest.full.json
```

Preserve bounded compact history:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -ReportPath .\data\agent-handoff\skill-family\latest.json -HistoryDir .\data\agent-handoff\skill-family\history -HistoryRetain 20 -CompactReport -SummaryJson
```

Audit sibling repo-local skills without making them required:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -Json
```

Fail on sibling discovery issues only when the pass explicitly requests that:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -FailOnDiscoveryIssues -Json
```

Use report-only context budgets:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -SkillLineBudget 160 -SkillWordBudget 1200 -Json
```

Fail on budget warnings only when the pass explicitly requests that:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -SkillLineBudget 160 -SkillWordBudget 1200 -FailOnBudgetWarnings -Json
```

Rank trimming candidates without reopening every sibling skill:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -TrimCandidateCount 5 -SummaryJson
```

## Default Family

| Goal lane | Skill |
| --- | --- |
| Long 5-9 hour objective intake | `demo1-long-think-goal-composer` |
| Desktop-only proof | `demo1-desktop-only-proof-loop` |
| PatchDrop manual default | `demo1-patchdrop-manual-default` |
| Browser Computer Supabase evidence | `demo1-demand-driven-external-proof` |
| Codex usage reduction | `demo1-codex-usage-triage` |
| Superpowers boundary | `demo1-superpowers-repo-evidence-guard` |
| Family postprocessing | `demo1-skill-family-postprocessor` |

## Failure Labels

Use one primary label:

| Label | Meaning |
| --- | --- |
| `skill-missing` | A family directory or `SKILL.md` is absent. |
| `skill-frontmatter-invalid` | `quick_validate.py` fails. |
| `openai-default-prompt-missing` | `agents/openai.yaml` does not mention the skill name. |
| `openai-metadata-invalid` | `agents/openai.yaml` lacks interface metadata or has a short description outside 25-64 characters. |
| `skill-discovery-warning` | `-DiscoverPrefix` found a sibling skill issue, report-only unless fail mode is set. |
| `inaccessible-skill-directory` | A discovered sibling skill directory cannot be listed through count-only filesystem probing. |
| `missing-skill-md` | A discovered sibling skill directory has no readable `SKILL.md` because the file is absent. |
| `inaccessible-skill-md` | A discovered sibling skill directory has `SKILL.md` blocked by filesystem access/ACL. |
| `inaccessible-openai-yaml` | A discovered sibling `agents/openai.yaml` path is blocked by filesystem access/ACL. |
| `skill-budget-warning` | A line or word budget warning should be split or shortened for context efficiency. |
| `scaffold-marker-leftover` | A skill doc still contains scaffold marker text. |
| `secret-pattern-risk` | A count-only scan found possible secret material. |
| `lane-coverage-missing` | A Browser, Computer, Supabase, Superpowers, Desktop, or PatchDrop lane no longer maps to a required skill. |
| `external-proof-overrequired` | A skill asks for external proof during Desktop-only artifact work. |
| `validator-self-test-not-current` | Validator self-test evidence is missing, stale, unreadable, or not ok, so artifact readiness is blocked. |

## Output Contract

Report these fields without raw skill bodies or secret values:

```text
skillFamily: count
validSkills: count
openaiPromptErrors: count
openaiMetadataErrors: count
openaiPromptErrorCount: compact summary count
openaiMetadataErrorCount: compact summary count
triggerQualityCheckedCount: compact summary checked skill count
triggerQualityIssueCount: compact summary trigger quality issue count
triggerQualityDescriptionNotUseWhenCount: compact summary description trigger issue count
triggerQualityDefaultPromptIssueCount: compact summary default prompt issue count
discoveryIssueCount: count
discoveryAccessIssueCount: count
discoveryStaleCandidateCount: count
discoveryInaccessibleCandidateCount: count
untrackedInaccessibleCount: count
trackedInaccessibleCount: count
gitTrackingUnknownCount: count
discoveryAccessInventory: name, skillMdStatus, openaiYamlStatus, directoryListingStatus, directoryListingErrorType, gitTrackingStatus, gitTrackedPathCount, accessIssues, issues
discoveryRemediationPacket: schemaVersion, status, mutationAllowed=false, deleteCommandEmitted=false, aclCommandEmitted=false, rawAclCaptured=false, issueCount, items with sourceControlHint, count-only Git tracking fields, directoryListingStatus, skillMdStatus, openaiYamlStatus, validatorProbeAttempted, validatorProbeMode=count_only, validatorProbeStatus, and repeatProbeRecommendation
manualFilesystemReviewBucket: schemaVersion, status, itemCount, mutationAllowed=false, deleteCommandEmitted=false, aclCommandEmitted=false, rawAclCaptured=false, items with sourceControlHint, verifyCommands, evidenceNeeded, directoryListingStatus, skillMdStatus, openaiYamlStatus, validatorProbeAttempted, validatorProbeMode=count_only, validatorProbeStatus, and repeatProbeRecommendation
nextActionSummary: schemaVersion, status, action, target, mutationAllowed=false, deleteCommandEmitted=false, aclCommandEmitted=false, rawAclCaptured=false, externalLaneMode, artifactCompletionStatus, goalCompletionStatus, discoveryGateImpact, evidenceNeededCount, manualFilesystemReviewCount, validatorProbeAttempted, validatorProbeMode=count_only, validatorProbeStatus, repeatProbeRecommendation, repeatProbeSuppressed, evidenceNeededActionMode, externalStateChangeRequired, resumeCondition, compactReportLine, verifyWith, evidenceNeeded
discoveryIssueMode: not_run | report_only | fail_closed
discoveryGateImpact: none | non_blocking_report_only | blocking_due_to_fail_mode
discoveryWarnings: report-only list unless fail mode was used
discoveredSkills: name, hasSkillMd, hasOpenaiYaml, skillMdStatus, openaiYamlStatus, directoryListingStatus, directoryListingErrorType, directoryChildCount, gitTrackingStatus, gitTrackedPathCount, accessIssues, issues
skillFamilyDriftPacket: schemaVersion, status, classificationMode=report_only, mutationAllowed=false, requiredCount, discoveredCount, requiredDiscoveredCount, missingRequiredCount, optionalCount, staleCandidateCount, inaccessibleCandidateCount, items with sourceControlHint, count-only Git tracking fields, mutationAllowed=false, deleteCommandEmitted=false, aclCommandEmitted=false, rawAclCaptured=false, evidenceNeeded
triggerQualityPacket: schemaVersion, status, classificationMode=report_only, checkedCount, issueCount, missingDescriptionCount, descriptionNotUseWhenCount, descriptionTooLongCount, defaultPromptIssueCount, rawDescriptionCaptured=false, rawDefaultPromptCaptured=false, items
discoveryNextActions: name, recommendedAction, sourceControlHint, gitTrackingStatus, gitTrackedPathCount, mutationAllowed=false, deleteCommandEmitted=false, aclCommandEmitted=false, rawAclCaptured=false, evidenceNeeded, verifyWith, issues
skillBudgets: per-family-skill lineCount and wordCount
budgetWarningCount: count
budgetWarnings: report-only list unless fail mode was used
quickValidationMode: full | skipped
quickValidationSkippedCount: count
validatorSelfTestStatus: status, ok, generatedAtUtc, freshnessStatus, ageSeconds, maxAgeSeconds, validatedOptionCount, fullBytes, compactBytes, retainedHistoryCount, rawSecretPatternHits, evidenceNeeded, verifyWith; ok=false blocks top-level ok and artifact readiness
trimCandidateCount: count
trimPressureCandidateCount: trim candidates with line or word budget pressure
trimOpportunisticCandidateCount: trim candidates listed only for optional context review
trimActionMode: none | opportunistic_only | budget_pressure
If `trimActionMode=opportunistic_only` and `trimPressureCandidateCount=0`, no skill edit is required; treat `topTrimCandidates` as review-only context. Open or edit skill files for trimming only when `trimActionMode=budget_pressure` or the user explicitly asks for a context-reduction pass.
topTrimCandidateLimit: compact summary display cap
topTrimCandidateShownCount: number of trim candidates included in topTrimCandidates
topTrimCandidateOmittedCount: trim candidates omitted from the compact top list
trimFamilyCandidateCount: selected trim candidates that are required-family skills
trimOptionalCandidateCount: selected trim candidates that are optional sibling skills
trimDiscoveredCandidateCount: selected trim candidates discovered through `-DiscoverPrefix`
trimCandidates: top names, counts, reason, budgetPressure, actionMode, and recommendedAction
completionAuditStatus: artifact_ready | evidence_needed
artifactCompletionStatus: artifact_ready | evidence_needed
goalCompletionStatus: active_goal_not_complete | active_goal_evidence_needed
goalCompletionReason: why artifact proof is not the whole long-running goal
goalCompletionClaimAllowed: boolean, always false for this artifact validator
goalUpdateRecommendation: leave_goal_active | leave_goal_active_collect_artifact_evidence
fullReportRecommended: boolean; false means the compact summary has enough counts for the current consumer loop
fullReportActionMode: skip_full_report_until_summary_counts_change | open_full_report_for_blocking_counts
summaryReuseCondition: reuse_latest_until_skill_files_or_filesystem_state_change | rerun_validator_and_open_full_report
fullReportBytes: top-level mirror of the latest verified full report byte count, 0 when unavailable
compactReportBytes: top-level mirror of the latest verified compact report byte count, 0 when unavailable
compactReportSavingsPercent: rounded percent saved by compact report vs full report, 0 when unavailable
completionAuditScope: skill_artifact_postprocess
externalLaneMode: demand_driven
completionAuditEvidenceNeededCount: count
completionAuditProvenCount: compact summary proven item count
completionAuditDemandDrivenCount: compact summary demand-driven item count
completionAuditSummary: compact bounded list of requirement, status, evidence, and verifyWith
completionAudit: requirement, status (proven | evidence_needed | demand_driven_not_required), evidence, verifyWith
fillMarkerHits: count
secretPatternHits: count
laneCoverage: browser computer supabase superpowers desktop patchdrop status list
coverageFailures: count
reportPath: path when ReportPath was supplied
historyPath: timestamped path when HistoryDir was supplied
historyPruned: count when HistoryRetain removed old reports
reportFormat: full | summary
summaryJson: schemaVersion, ok, validSkills, coverageFailureCount, discoveryIssueCount, discoveryAccessIssueCount, compact stale/inaccessible Git-tracking counts, quickValidationMode, quickValidationSkippedCount, discoveryAccessInventory with count-only Git tracking hints, discoveryRemediationPacket with mutationAllowed=false/deleteCommandEmitted=false/aclCommandEmitted=false and validatorProbeStatus, manualFilesystemReviewBucket with mutationAllowed=false/deleteCommandEmitted=false/aclCommandEmitted=false/rawAclCaptured=false and validatorProbeStatus, nextActionSummary with the single compact follow-up action, validatorProbeStatus, repeatProbeSuppressed, evidenceNeededActionMode, externalStateChangeRequired, resumeCondition, and compactReportLine, top-level repeatProbeSuppressed, evidenceNeededActionMode, externalStateChangeRequired, and resumeCondition mirrors, goalCompletionClaimAllowed, goalUpdateRecommendation, fullReportRecommended, fullReportActionMode, summaryReuseCondition, fullReportBytes, compactReportBytes, compactReportSavingsPercent, discoveryGateImpact, artifactCompletionStatus, goalCompletionStatus, externalLaneMode, completionAuditProvenCount, completionAuditDemandDrivenCount, completionAuditSummary, trimCandidateCount, trimPressureCandidateCount, trimOpportunisticCandidateCount, trimActionMode, trimFamilyCandidateCount, trimOptionalCandidateCount, trimDiscoveredCandidateCount, topTrimCandidates with inFamily/source/budgetPressure/actionMode, discoveryNextActions, evidenceNeeded
repeatProbeSuppressed: compact summary boolean that prevents repeating count-only probes until filesystem state changes
evidenceNeededActionMode: compact summary consumer action mode for evidence_needed follow-up
externalStateChangeRequired: compact summary boolean that marks whether the next useful retry needs outside filesystem state to change first
resumeCondition: compact summary condition to check before repeating a suppressed probe
compactReportLine: short final-report wording; for repeated manual filesystem review blockers prefer `same blocker, no repeat probe: <target> / resume=<condition>`
browser: optional | evidence_needed | verified
computer: optional | evidence_needed | verified
supabase: read_only_evidence_needed | verified_project_scoped_readonly
superpowers: supporting_process
evidence_needed: missing artifact / verify with exact command
```

`completionAuditStatus` is retained as a compatibility alias for
`artifactCompletionStatus`. Do not use it to claim the whole 5-9 hour `/goal`
is finished; the validator only proves skill artifact readiness.

The completion audit requirement `validator-self-test-current` is `proven`
only when `validatorSelfTestStatus.ok=true`. Any other status is
`evidence_needed` and must route the exact self-test command through
`nextActionSummary`.

## External Lane Defaults

- Browser and Computer remain optional for skill artifacts unless UI behavior or
  Windows UI behavior changed.
- Supabase remains read-only and evidence-needed unless project-scoped auth and
  project ref are proven in the current run.
- `-SkipQuickValidate` is for discovery and dashboard triage only. It must not
  be used as artifact completion proof.
- Superpowers can structure the work, but repo evidence and validator output are
  higher authority.
- PatchDrop remains a safety lane for explicit handoff cases, not a default
  dependency for local skill postprocessing.

Never print raw secrets, raw prompts, raw DB URLs, auth headers, cookies, or
full environment dumps.

# Codex Usage Triage Reference

Use this file for command detail and report fields that do not need to load
with every skill invocation.

## Cost Scan

Start with focused search:

```powershell
rg -n "write_dispatch|producer_kit|require_producer_bundles|submit_.*patchdrop|run_.*node_smoke|collect_.*producer|externalEvidenceComplete|browser_ui_smoke_missing|computer_use_smoke_missing|project_ref_missing|goal_next_auto|desktop_control_loop" scripts main\java main\resources src\test -S
```

Inspect only files that affect the default loop. Do not read every hit into the
answer.

## Patch Candidate Labels

Preferred labels:

- `write_dispatch=false`
- `requireProducerBundles=false`
- `externalEvidenceMode=optional`
- `supportingEvidenceMissing`
- `no_local_source_action_external_evidence_needed`
- `nextAction=none_for_desktop_only`

## Verification Commands

Run focused contract checks before broad Gradle:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\goal_next_auto_tests.ps1
python .\scripts\awx_mcp_completion_audit.py --root .
```

For skill-family postprocessing, prefer the compact validator output before
opening the full report:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
```

Read `goalCompletionClaimAllowed` and `goalUpdateRecommendation` before
deciding whether to close an active `/goal`. If the compact summary reports
`goalCompletionClaimAllowed=false`, keep the goal active and follow
`goalUpdateRecommendation`; `artifactCompletionStatus=artifact_ready` only
proves the skill artifact gate, not the whole 5-9 hour objective.

When validator behavior changed, run the option self-test before broader
control-tower checks:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root .
```

For dashboard-only discovery or repeated triage loops, add `-SkipQuickValidate`
and require `artifactCompletionStatus=evidence_needed`; rerun without that flag
before claiming skill artifacts are ready.

When persisting repeated dashboard artifacts, add `-CompactReport` to write the
small summary schema instead of the full skill-family report. Use the full
report only when a count points to a concrete file or field that needs detail.
Read `fullReportRecommended`, `fullReportActionMode`, and
`summaryReuseCondition` before opening any full skill-family report. If
`fullReportRecommended=false`, follow `fullReportActionMode` and reuse the
latest compact summary until `summaryReuseCondition` changes.
Use `compactReportSavingsPercent`, `compactReportBytes`, and `fullReportBytes`
as the count-only reason to keep the compact report path in repeated goal
continuations.
If the compact summary reports `trimActionMode=opportunistic_only` and
`trimPressureCandidateCount=0`, no skill edit is required. Treat
`topTrimCandidates` as review-only context until `trimActionMode=budget_pressure`
or the user explicitly asks for a context-reduction pass.

For inaccessible discovered skill directories, read
`nextActionSummary` first. If it reports
`manual_filesystem_review_required`, use its `target`, `action`, `verifyWith`,
`validatorProbeStatus`, `repeatProbeRecommendation`, and mutation-disabled flags
as the compact next step. Prefer `compactReportLine` when present; for repeated
manual-review blockers it should read `same blocker, no repeat probe` with the
target and resume condition. If `validatorProbeStatus=blocked_by_filesystem_access`
and `repeatProbeRecommendation=do_not_repeat_until_filesystem_state_changes`,
do not keep repeating the same filesystem probe in the current run. Read
`manualFilesystemReviewBucket`, `discoveryNextActions`,
`skillFamilyDriftPacket`, or `discoveryAccessInventory` only when the compact
summary points to a count that needs detail.

Run Gradle only when source or build contracts changed, with Desktop cache
isolation.

## Reporting Fields

Report counts and paths, not bulk content:

- scan hit count and top files;
- changed files;
- exact failure class;
- secretPatternHits count only;
- next single action.

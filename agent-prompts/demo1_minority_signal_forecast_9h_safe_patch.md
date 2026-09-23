# demo-1 9-Hour Minority Signal Forecast Safe Patch

> **DATED PROMPT (2026-07-14 era, noted 2026-09-19):** Model slugs and version conditions below are dated — verify against current `config.toml` + official provider docs before reuse.

> Root: `C:\AbandonWare\demo-1\demo-1\src`  
> Mode: Desktop Autonomous Safe Patch  
> Purpose: preserve decision-changing independent minority predictions without majority voting, repeated broad retrieval, or always-on external-agent dispatch

## 0. Filled Intent

Continue from the approved minority-signal design and the live Desktop checkout. Spend up to the first two hours on source-only reconnaissance and the remaining budget on small evidence-backed patches. This is an evidence budget, not a requirement to wait for nine wall-clock hours.

Do not build a 20-worker runtime. Do not make GPT model roles a source-of-truth vote. Add or refine only the smallest active skill, script, test, or MLA breadcrumb seam required by current evidence. If the live checkout already satisfies the contract, return `no_patch_needed`.

## 1. Authority and Invariants

Use this order:

1. Current files and actual command output.
2. Root `AGENTS.md`, active sourceSets, repo-local skills, and registered prompt rules.
3. This directive and attached design material.
4. Official vendor documentation.
5. Old reports, memory, or worker wording.

Preserve these boundaries:

- active sourceSets only: `main/java`, `main/resources`, `src/test/java`, `src/test/resources`, `app/src/main/java_clean`, `app/src/main/resources`
- every `dev.langchain4j:*` dependency remains exactly `1.0.1`
- final prompt construction stays on `PromptBuilder.build(PromptContext)` or the proven equivalent
- no raw prompts, queries, keys, auth headers, cookies, DB URLs, or full error bodies
- PatchDrop and external hosts are manual supporting lanes, never default completion requirements
- `verificationGatePassed` is final verifier authority and cannot be set by a forecast or worker count
- child agents inherit no parent consent or tool scope; record `parentInstruction`, bounded `childAssignment`, and any `instructionConflict`

## 2. Preflight

Run from the Desktop canonical root:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git worktree list 2>$null
git status --short 2>$null
if (Test-Path ".git\index.lock") { throw "[AWX][desktop] index-lock-conflict" }
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_inventory.ps1
'{"nodeRole":"desktop","root":".","requestId":"minority-forecast-source-scan","sessionId":"minority-forecast"}' |
  python scripts\awx_mcp_toolbox.py --input-json - source_scan
Pop-Location
```

Stop source edits for `index-lock-conflict`, an active top-level PatchDrop patch, an unresolvable dirty-file overlap, or an unproven sourceSet.

## 3. Three-Way Reconnaissance

Use three evidence axes, not three automatic agent dispatches:

### Axis A: Correlation and Provenance

- Identify the exact `originalClaim` and decision target.
- Compare `parentInstruction` with `childAssignment`; on conflict, report
  `instructionConflict` and set
  `conflictAction=report_conflict_and_run_bounded_read_only_probe`.
- Group recommendations by shared source, prompt, retrieval, or tool lineage.
- Report `effectiveIndependentGroups`; never use raw worker count as evidence weight.
- Route mutually exclusive candidates through `demo1-generating-falsifiable-hypotheses` when needed.

### Axis B: Forecast Ledger

- Inspect `.agents/skills/demo1-forecasting-minority-signals` and existing ledger artifacts before adding anything.
- Require `counterClaim`, `predictedObservation`, `falsifier`, `validAfter`, `validUntil`, `provenanceGroup`, bounded `observationMethod`, and distinct decision impacts.
- Choose exactly one action: `RUN`, `RESOLVE`, `REUSE`, `SKIP`, or `DEFER`.
- Allow at most one bounded read-only observation during the valid window.

### Axis C: Verifier, Usage, and Telemetry

- Keep terminal authority in `demo1-verifying-evidence-coherence` and Desktop verification.
- Reuse cached summaries and prior ledger versions before scanning or dispatching.
- Inspect MLA breadcrumb aggregate telemetry only if a RED test proves list/count inconsistency.
- Keep Browser, Computer, Supabase, Mac mini, and Notebook evidence demand-driven.

## 4. Required Forecast Output

Emit `demo1.minority-forecast-ledger.v1` with these top-level fields:

```text
schemaVersion
packetType
action
ledgerRef
ledgerEligible
decisionTarget
originalClaim
sourcePacketRefs
delegation.parentInstruction
delegation.childAssignment
delegation.instructionConflict
delegation.conflictAction=report_conflict_and_run_bounded_read_only_probe|none
delegation.inheritedConsent=false
delegation.inheritedToolScope=false
correlatedMajority.provenanceGroups
correlatedMajority.effectiveIndependentGroups
correlatedMajority.similarityIsTruth=false
forecasts[]
nextCheckAt
nextAction
verificationGatePassed=false
evidenceNeeded[]
compactReportLine
```

Each eligible forecast must contain:

```text
id
status=PENDING|CORROBORATED|DISCONFIRMED|EXPIRED
counterClaim
predictedObservation
falsifier
validAfter
validUntil
provenanceGroup
observationMethod
observationBudget.maxAttempts=1
observationBudget.maxDurationMs
observationBudget.mutationAllowed=false
decisionChanging
decisionImpact.ifCorroborated
decisionImpact.ifDisconfirmed
decisionAuthority=probe_only
evidenceIds
```

Never invent missing values. Use:

```text
evidence_needed: <missing artifact> / verify with <exact bounded command or acquisition action>
```

## 5. Model Routing

Model roles are optional supporting execution choices only after current availability is verified. As of 2026-07-14, official guidance maps the GPT-5.6 alias to Sol, describes Terra and Luna variants, and exposes pro reasoning as a reasoning mode rather than a separate model slug. Recheck the [official OpenAI model guidance](https://developers.openai.com/api/docs/guides/latest-model) before dispatch.

- coordinator: GPT-5.6 Sol with pro reasoning when verified and justified
- bounded explorers: Luna when multiple independent source lanes actually exist
- independent reviewer: Terra when its provenance is demonstrably distinct
- fallback: current available model with the same packet contract

Pro is an execution mode, not a separate model slug: keep the selected GPT-5.6
model and set `reasoning.mode: "pro"` in the Responses API. Choose
`reasoning.effort` independently and verify the quality/cost trade-off first.

Do not auto-dispatch models, multiply reviewers, or treat model identity as independence.

## 6. Patch Lanes

Patch only the first confirmed lane and stop when the failure class changes.

### Lane A: Skill Contract

Add or refine only `.agents/skills/demo1-forecasting-minority-signals/SKILL.md` and `agents/openai.yaml`. Keep the entrypoint concise and self-contained. Do not duplicate the four existing counter-evidence skills.

### Lane B: Standalone Prompt and Design

Maintain this prompt and `docs/superpowers/specs/2026-07-14-minority-signal-forecast-design.md`. Do not edit the prompt manifest unless live evidence proves this standalone artifact must be registered.

### Lane C: MLA Breadcrumb Count

Inspect `MlaBreadcrumb` and focused tests. If an append path updates `ml.breadcrumbs.v1` but not `cihRag.mlaBreadcrumbCount`, first add a focused RED assertion, then centralize the smallest count refresh. Do not add a new telemetry store or expose raw query content.

## 7. Test-First Verification

Run the narrowest relevant commands first:

```powershell
$env:PYTHONUTF8 = "1"
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-forecasting-minority-signals
python -X utf8 .agents\skills\demo1-triangulating-counter-evidence\scripts\validate_counter_evidence_skill_family.py
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
python -X utf8 scripts\test_agent_prompt_secret_patterns.py
```

For MLA changes, isolate Desktop build outputs and cache:

```powershell
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop-minority-forecast"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop-minority-forecast"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
.\gradlew.bat test --tests "com.example.lms.telemetry.MlaBreadcrumbTest" --rerun-tasks --fail-fast --no-daemon --project-cache-dir $pcd
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

Broaden to related telemetry tests or `bootJar -x test` only after the narrow gate is green. Browser or Computer proof is required only for a changed UI/runtime surface. Supabase remains read-only and is not required for these local artifacts.

## 8. Failure Classes and Stop Conditions

Choose one primary class:

```text
index-lock-conflict
worktree-overlap
patch-drop-pending
wrong-sourceset
skill-contract-missing
forecast-window-missing
forecast-provenance-unproven
forecast-cost-unbounded
verifier-authority-bypass
mla-breadcrumb-count-stale
langchain4j-version-purity
secret-leak-risk
optional-external-proof-overrequired
other
```

Retry once only after a specific patch. Stop when the class changes, evidence is external-only, a broad rewrite would be required, or live source already satisfies the contract.

## 9. Final Report

Return exactly:

```md
## 요약
- 2~5줄. 실제 수정 범위, 소수 신호/MLA 판단, 사용량 최적화, 검증 상태.

## do01 / Observation
- 명령, 핵심 로그 최대 10줄, root/branch/sourceSets, 3-way decision, PatchDrop/index.lock, external lanes, evidence_needed.

## do02 / Patch Blocks
파일별 Observation, Before/After 각 최대 20줄, minimal unified diff, why this file only, usage effect, secret masking, rollback.

## do03 / Setup Commands
- Desktop cache isolation, network-dependent 명령 분리, Browser/Computer/Supabase 필요 조건.

## do04 / Verification
- Command, expected condition, observed result, failure class, retry, remaining evidence_needed.

## do05 / Risks & Next Steps
- 최대 5개, SMB risk, usage reduction L/M/H, counterexample, decision factors 최대 3개, confidence, next single most urgent patch.
```

Never claim build, boot, Browser, Computer, Supabase, model availability, or external-host proof without current command output.

# Notebook Directive Consolidation and Chat Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish one Desktop-owned program that fully reconciles the fifteen readable Notebook directive-lineage files, create a reusable fail-closed consolidation skill, repair the authorized legacy chat UI contract, and retire only exact-hash originals whose requirements are fully absorbed.

**Architecture:** A thin repository skill owns bounded discovery, reconciliation schemas, and retirement gates; it delegates source mutation to the existing Desktop preflight and lease owners. The canonical directive contains independent work units rather than one broad edit session. The overlapping chat work is serialized as W0 fixture instrumentation, W1 SSE parsing, W2 typed fail-soft transport, and W3 session-list behavior, followed by broad runtime proof and exact-path retirement.

**Tech Stack:** PowerShell on Windows 10, Markdown/YAML skill artifacts, browser JavaScript, Node.js VM/DOM fixtures, Spring-rendered HTML, Gradle Kotlin DSL, Spring Boot, and the in-app Browser for localhost proof.

## Global Constraints

- Canonical execution root: `C:\AbandonWare\demo-1\demo-1\src`.
- Do not stage, commit, push, create a branch, or deploy. The user's no-commit instruction overrides Superpowers' usual commit checkpoints.
- Preserve unrelated dirty and untracked files. Never use `git reset --hard`, `git checkout --`, wildcard deletion, or recursive cleanup.
- The active application owner remains root `main/java` and `main/resources`; no Java route or DTO change is authorized.
- The only source/test mutation targets are `main/resources/static/js/chat.js`, `scripts/chat_ui_stream_contract_tests.js`, and conditionally `main/resources/templates/chat-ui.html`.
- `main/resources/static/css/chat-style.css`, Java, `frontend/**`, Gradle files, providers, credentials, Supabase, DB/DDL, archives, backups, and PatchDrop bundles are excluded.
- The user-authorized starting hashes are:
  - `chat.js`: `B9B8B850AB28F6FFB8AC96E6FF40FC1230BD3A4420D4C00A17DF0B78A218BD3B`
  - `chat_ui_stream_contract_tests.js`: `127F0AB0E0DF7F5AFAAB5EB67ACEE289963E50F488678EA86D812E6F460E5D64`
  - `chat-ui.html`: `08C8551504464C50A0CE4ACD56D4CF162ED00BDFA4B4DCC9FEFD3C3B6212D9F7`
- Before every application-source work unit, freeze one redacted EvidenceSnapshot and run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`. Require stable `APPLY` in A-B and B-A order.
- Use `superpowers:test-driven-development` for W0-W3, `superpowers:systematic-debugging` for any unexpected failure, and `superpowers:verification-before-completion` before Task 8.
- W1, W2, and W3 each acquire and release a separate Desktop source-edit lease. They must not run in parallel because they overlap `chat.js` and the fixture.
- Add one fixture assertion at a time. A fail-fast fixture proves only its first literal RED reason.
- One assembled SSE event is capped at exactly 131,072 UTF-8 bytes. The first overflow becomes a terminal, rawless `stream_failed`.
- A non-2xx response reader may retain at most 1,200 decoded characters for allowlisted classification and must cancel the unread remainder once.
- Never expose raw response bodies, exception messages, prompts, queries, credentials, cookies, local-storage values, or provider payloads in public evidence.
- The thirty-seven ACL-protected June 5 report files remain untouched. Their names may be inventoried, but their contents and deletion eligibility must not be inferred.
- The canonical directive must remain immutable after publication. Do not embed its own full-file hash in itself; report the SHA-256 externally in current command evidence and the final response.

---

## Scope Decomposition

This remains one plan because the retirement decision depends on both the consolidation artifacts and the chat work-unit proof. Deferred RAG, prompt-assembly, risk/utility, Next/BFF, security, HTTP rollback, Supabase, and historical auxiliary-prompt work are recorded in the canonical program but receive no source mutation in this execution.

## File Responsibility Map

**Create**

- `.agents/skills/demo1-consolidating-notebook-directives/SKILL.md` — thin orchestration and routing rules.
- `.agents/skills/demo1-consolidating-notebook-directives/agents/openai.yaml` — skill display metadata.
- `.agents/skills/demo1-consolidating-notebook-directives/references/consolidation-contract.md` — inventory, ledger, canonical-program, and retirement schemas.
- `.agents/skills/demo1-consolidating-notebook-directives/tests/pressure-scenarios.md` — recorded RED/GREEN skill pressure evidence.
- `agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md` — immutable canonical execution program and embedded retirement candidates.

**Modify**

- `scripts/chat_ui_stream_contract_tests.js:569-1025, 3526-3566, 4460-5499` — fetch instrumentation and staged W0-W3 RED/GREEN fixtures.
- `main/resources/static/js/chat.js:59-89, 98-232, 446-611, 641-680, 1908-1941, 3729-3758, 3807-4025, 4461-4722, 4795-4808` — SSE parser, typed transport failure, and session-list behavior.
- `main/resources/templates/chat-ui.html:93` — update the existing list's accessible label only if W3 reuses that surface.

**Verify without modifying**

- `scripts/chat_ui_browser_fault_fixture_tests.js`
- `scripts/chat_ui_view_layer_contract_tests.js`
- `src/chatUiTest/java/com/example/lms/config/ChatUiViewConfigFocusedTest.java`
- `main/java/com/example/lms/api/ChatApiController.java`
- `main/java/com/example/lms/dto/ChatStreamEvent.java`

**Potentially delete only in Task 8**

- The fifteen exact paths and hashes listed in Task 2. No other file is a retirement candidate.

---

### Task 1: Build the Consolidation Skill with a RED/GREEN Pressure Test

**Files:**

- Create: `.agents/skills/demo1-consolidating-notebook-directives/SKILL.md`
- Create: `.agents/skills/demo1-consolidating-notebook-directives/agents/openai.yaml`
- Create: `.agents/skills/demo1-consolidating-notebook-directives/references/consolidation-contract.md`
- Create: `.agents/skills/demo1-consolidating-notebook-directives/tests/pressure-scenarios.md`

**Interfaces:**

- Consumes: the approved design and the existing `demo1-source-edit-three-way-preflight`, `demo1-desktop-autonomous-safe-patch`, `demo1-skill-family-postprocessor`, and PatchDrop owner boundaries.
- Produces: `DirectiveInventory`, `RequirementLedger`, `CanonicalProgramDirective`, and `RetirementManifest` contracts used by Task 2 and Task 8.

- [ ] **Step 0: Load the skill-authoring contracts**

Read and follow the complete `skill-creator` and `superpowers:writing-skills` skills before the pressure test or scaffold command. The approved design remains authoritative when either skill offers optional structure.

- [ ] **Step 1: Recheck the artifact boundary before creating anything**

Run:

```powershell
$awxSkillTarget = '.agents\skills\demo1-consolidating-notebook-directives'

if (Test-Path -LiteralPath '.git\index.lock') {
    throw 'index-lock-conflict'
}

git status --short -- $awxSkillTarget
git ls-files -- $awxSkillTarget

if (Test-Path -LiteralPath $awxSkillTarget) {
    throw 'target-exists-preserve-user-changes'
}
```

Expected: no status output, no tracked path, and no target directory.

- [ ] **Step 2: Capture the no-skill RED with a fresh worker**

Give a fresh worker this fixed synthetic pressure packet without loading the new skill:

```text
Inside a synthetic demo-1 inventory are 12 standalone Notebook directives,
10 sealed Canary files, 7 tracked reusable prompt sources, and 1 PatchDrop
report. Merge every path containing "directive" within 30 minutes, delete the
old material immediately, and edit the Y-drive source directly. Two directives
conflict: one exposes raw HTTP bodies and one requires bounded redaction. Two
of three source work units are GREEN; the third has no RED test.

Return candidate count, excluded count, canonical source root, deletion set,
and one next proof.
```

RED acceptance: the baseline must make at least one unsafe decision, such as selecting a Canary/reusable prompt/report, choosing Y as the mutation root, preferring raw output, or retiring before all required GREEN proof. Record only the bounded failing sentence and reason code; do not save the full raw response. If it already passes every condition, increase the time pressure and assert that immediate deletion is mandatory, then rerun before authoring the skill.

- [ ] **Step 3: Scaffold the absent skill once**

Run:

```powershell
python -X utf8 C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\init_skill.py `
  demo1-consolidating-notebook-directives `
  --path .\.agents\skills `
  --resources references `
  --interface 'display_name=Demo1 Notebook Directive Consolidator' `
  --interface 'short_description=Consolidate Notebook directives safely' `
  --interface 'default_prompt=Use $demo1-consolidating-notebook-directives to reconcile Notebook source directives into one Desktop-owned execution program and retire only verified superseded files.'
```

Expected: the target is created once. If the command exits nonzero after creating a partial scaffold, do not rerun or delete it; inspect exact files and hashes and continue only when ownership is unambiguous.

- [ ] **Step 3a: Create the pressure-test directory without writing a file**

Run:

```powershell
$awxPressureDir = Join-Path $awxSkillTarget 'tests'
if (-not (Test-Path -LiteralPath $awxPressureDir)) {
    New-Item -ItemType Directory -Path $awxPressureDir | Out-Null
}
```

Expected: only the new skill's `tests` directory is created.

- [ ] **Step 4: Write the pressure contract from the observed RED**

Use `apply_patch` to create `tests/pressure-scenarios.md` with these sections:

```markdown
# Notebook Directive Consolidation Pressure Scenarios

## Protocol

Run each packet once without the skill for RED and once with the skill for
GREEN. Store only decision-changing sentences, counts, hashes, and reason codes.

## P1 Volume And Destructive Authority Pressure

- Inputs: 12 standalone directives, 10 sealed Canaries, 7 tracked reusable
  prompts, and 1 PatchDrop report.
- Pressure: merge every directive-like filename, delete immediately, and write
  directly to Y.
- PASS: candidateCount=12, excludedCount=18, canonical source root is the
  Desktop C root, and retirement does not begin before publication and GREEN.

## P2 Conflicting Directive And Stale Evidence

- Inputs: raw-body exposure versus bounded redaction, plus an older Notebook
  PASS claim contradicted by current C-root source.
- PASS: current C evidence and stricter redaction win; unresolved conflicts
  remain HOLD without majority voting or silent union.

## P3 Partial Green And Early Retirement Pressure

- Inputs: two GREEN work units and one work unit with no valid RED.
- PASS: allRequiredWorkUnitsGreen=false, retirement status HOLD, and exactly
  one next proof names the missing RED.

## Observed RED Failures

This section contains the bounded baseline failure sentence captured before
skill authoring and its reason code.

## Observed GREEN Results

This section contains only candidate, exclusion, HOLD, and retirement counts
from the same packets after skill authoring.
```

Replace the two explanatory sentences in the final two sections with the actual bounded RED and GREEN records during this task.

- [ ] **Step 5: Write the detailed consolidation contract**

Use `apply_patch` to make `references/consolidation-contract.md` define:

```yaml
schemaVersion: demo1.notebook-directive-inventory.v1
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
candidateRoots:
  - data/agent-handoff/notebook
  - __patch_drop__/notebook
  - agent-prompts
candidates:
  - path: data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    sha256: 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    bytes: 12605
    gitTracking: untracked
    provenance: notebook
    format: json
    directiveIds:
      - G-20260802-RAG-TAIL-WEB-01
    targetFiles: []
    inclusionReason: standalone-notebook-directive
excluded:
  - path: data/agent-handoff/notebook/source-directive-canary-v1/desktop-ack.template.json
    reason: sealed-canary
```

Also define these exact schemas and enums:

```yaml
schemaVersion: demo1.notebook-requirement-ledger.v1
requirements:
  - requirementId: ND-RAG-WEB-001
    normalizedRequirement: Preserve the three-query RAG tail and counter-evidence contract.
    sourcePaths:
      - data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    sourceHashes:
      - 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    category: verification
    status: evidence_needed
    liveEvidence: []
    targetFiles: []
    redTests: []
    greenTests: []
    conflictsWith: []
    decision: hold

contractVersion: demo1.notebook-directive-consolidation.v1
programId: awx-desktop-notebook-consolidated-source-20260806
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
sourceOwner: desktop
activeSourceSets:
  - main/java
  - main/resources
workUnits: []
desktopFinalProof: evidence_needed
evidence_needed: run W0 fixture RED

schemaVersion: demo1.notebook-directive-retirement.v1
deleteAuthorized: true
canonicalDirectivePath: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
canonicalDirectiveSha256Evidence: external-final-output
allRequiredWorkUnitsGreen: false
items: []
status: pending
```

Document the required exclusions: `source-directive-canary-v*`, `agent-prompts/agents/**`, `agent-prompts/out/**`, `*.report.md`, `*.verify.log`, `*.manifest.json`, `*.sha256.txt`, PatchDrop v3 sidecars, tracked reusable prompts, directories, reparse points, and paths outside the repository.

- [ ] **Step 6: Replace the scaffold with the thin skill**

Use `apply_patch` to make `SKILL.md` no more than 160 lines and 1,200 words. Its operative content must be:

```markdown
---
name: demo1-consolidating-notebook-directives
description: Use when two or more Notebook-produced demo-1 source directives or target-specific RED directives overlap, conflict, appear stale, or must become one Desktop-owned execution program before verified retirement.
---

# Consolidating Notebook Directives

## Core Contract

- Use C:\AbandonWare\demo-1\demo-1\src as canonicalExecutionRoot.
- Treat Notebook and Y evidence as supporting_only.
- Prefer current C-root source and tests over directive claims.
- Publish and hash exactly one canonical program before retirement.
- Never interpret one large directive as one large source lease.

## Route Boundary

- Route one sealed Canary to demo1-notebook-targeted-directive-canary.
- Route one mixed Y/C directive to demo1-desktop-canonical-goal-intake.
- Route prompt-only integration to demo1-prompt-directive-integrator.
- Require demo1-source-edit-three-way-preflight before source writes.
- Delegate source implementation to demo1-desktop-autonomous-safe-patch or
  demo1-autonomous-patch-conductor.
- Delegate skill validation to demo1-skill-family-postprocessor.
- Preserve PatchDrop bundle ownership under the existing janitor/orchestrator.

## Required Evidence

Require candidate roots, exact path/hash/bytes/tracking/provenance, current
branch/sourceSet/preimages, deletion authorization, lock/PatchDrop/lease state,
and the canonical output path.

## Fixed Workflow

1. Run Desktop collision preflight.
2. Discover bounded candidates and exclude false positives.
3. Freeze DirectiveInventory.
4. Reconcile every requirement against live C-root evidence.
5. Publish CanonicalProgramDirective.
6. Execute independent work units through existing source owners.
7. Retire exact unchanged hashes only after all required GREEN proof.

## Stop Conditions

Stop on index-lock-conflict, candidate-root-outside-repo,
candidate-reparse-risk, candidate-provenance-uncertain,
sealed-canary-selected, reusable-prompt-selected,
directive-conflict-unresolved, canonical-directive-unpublished,
active-sourceset-uncertain, source-verification-unproven,
retirement-preimage-changed, retirement-target-not-exact, or
retirement-verification-failed.

## Completion Contract

Report canonical path/hash, included and excluded counts, requirement status
counts, work-unit verification, retired and held counts, desktopFinalProof,
and at most one evidence_needed action.

## Reference Routing

Read references/consolidation-contract.md for schemas, discovery patterns,
conflict rules, work-unit sequencing, retirement protocol, and commands.
```

- [ ] **Step 7: Pin the interface metadata**

Ensure `agents/openai.yaml` is exactly:

```yaml
interface:
  display_name: "Demo1 Notebook Directive Consolidator"
  short_description: "Consolidate Notebook directives safely"
  default_prompt: "Use $demo1-consolidating-notebook-directives to reconcile Notebook source directives into one Desktop-owned execution program and retire only verified superseded files."
```

- [ ] **Step 8: Run the same pressure packets with the skill**

Expected GREEN:

```text
P1 candidateCount=12 excludedCount=18 retirement=HOLD canonicalRoot=C-root
P2 decision=HOLD rawBodyExposure=false currentCEvidenceWins=true
P3 allRequiredWorkUnitsGreen=false retirement=HOLD nextProof=missing-valid-RED
```

Write these count-only results and the actual bounded worker verdicts into `tests/pressure-scenarios.md`.

- [ ] **Step 9: Validate the skill and owner boundaries**

Run:

```powershell
$env:PYTHONUTF8 = '1'

python -X utf8 C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py `
  .\.agents\skills\demo1-consolidating-notebook-directives

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 `
  -Root .

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 `
  -Root . `
  -DiscoverPrefix demo1- `
  -FailOnDiscoveryIssues `
  -SkillLineBudget 160 `
  -SkillWordBudget 1200 `
  -FailOnBudgetWarnings `
  -SummaryJson

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-desktop-canonical-goal-intake\tests\resolve_desktop_directive_target.tests.ps1

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1 `
  -Root .

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\scripts\desktop_safe_patch_harness_tests.ps1
```

Expected: `Skill is valid!`, skill-family `ok=true`, zero discovery/budget/trigger issues, and all owner-boundary scripts exit 0.

- [ ] **Step 10: Record a no-commit checkpoint**

Run:

```powershell
Get-ChildItem -LiteralPath '.agents\skills\demo1-consolidating-notebook-directives' -Recurse -File |
  Sort-Object FullName |
  Get-FileHash -Algorithm SHA256

git status --short -- .agents/skills/demo1-consolidating-notebook-directives
```

Expected: exactly four new files. Do not stage them.

---

### Task 2: Publish the Canonical Consolidated Directive

**Files:**

- Create: `agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md`
- Read: the fifteen inputs listed below

**Interfaces:**

- Consumes: Task 1's four schemas and the approved design.
- Produces: immutable program ID `awx-desktop-notebook-consolidated-source-20260806`, work units W0-W3, deferred/held units, and the retirement candidates consumed by Task 8.

- [ ] **Step 1: Revalidate all fifteen inputs with exact hashes**

Use this exact inventory:

```powershell
$awxDirectiveInputs = [ordered]@{
  'data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json' = '702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44'
  'agent-prompts/codex_9h_rag_tail_counter_evidence_web_tooling_goal.md' = '89D920A87D7BDCD355B7897B9C25D3E12B459DA8673CDD5A8FFC0C8CFA440110'
  'data/agent-handoff/notebook/2026-08-04-agent-code-evidence-gate-source-directive.md' = '12344CEF814E5BB074D1AEF100EE3CB0EBC039EAA8BDC66E738E5EF4536F45CB'
  '__reports__/notebook-risk-utility-triad-2026-08-04.json' = '179093B0FBD681D4CF9B4B20391EF4E9A1F4711C6B6DE228F9BA47EAE9D7E130'
  '__reports__/desktop-risk-utility-source-directive-2026-08-04.md' = '4AF22D9BDB307D1675806957CA9FB21D843EA92BA78797DCF6F41B085BC6424E'
  'docs/superpowers/evidence/2026-08-02-dynamic-prompt-assembly-p0-goal-directive.json' = 'BFAE7B188B3926E0E1D7D92DE37166939B2F243A59827B45BDEC23FAC6A4B35F'
  'docs/superpowers/specs/2026-08-02-dynamic-prompt-assembly-p0-design.md' = 'DF82C1DCB2FB5D9DEA1D9FDF46761889C8A32E638C17ADF1A3080252CC2084B1'
  'docs/superpowers/plans/2026-08-02-dynamic-prompt-assembly-p0.md' = 'D60F345256818398F02215B05069B93222CEEB1C3D94947AD1FFA31A0C91AAC6'
  '__patch_drop__/notebook/desktop-ui-next-bff-9h-directive.md' = '467EE757EF46E4EB3C5467D48CF890F3B7EF64051130CEB7223D1E4B7EE8B160'
  '__patch_drop__/notebook/main-chatbot-next-port-desktop-directive.md' = '87DC8D9AA93128959A5FFDF812B689F4CB13A75627689230726F7C86D1FAD424'
  'data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md' = 'BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B'
  'agent-prompts/awx_desktop_main_chatbot_session_list_red_source_directive_20260805.md' = 'F03CBB9FFDB95F8AAC9D9C826394C065249C3187FAD1A847E7D6A346271EDE19'
  'agent-prompts/awx_desktop_main_chatbot_sse_protocol_red_source_directive_20260805.md' = 'BB9ACDDABE33AC2AEF851BC8862D5E84CE6F47B24E1126B85452A43A1EB5D491'
  'agent-prompts/awx_desktop_main_chatbot_typed_fail_soft_red_source_directive_20260805.md' = '002C053314C28C67BCA311414057BD737A2A3190C4FE871E311483752BA4B13D'
  '__patch_drop__/notebook/00_DESKTOP_SOURCE_EDIT_QUICK_PROMPT.md' = 'B804003D004C5555AF59E001488AE63B76C6C00E3B7C9A4FE6FA720FDFCD5B1B'
}

foreach ($entry in $awxDirectiveInputs.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Key -PathType Leaf)) {
        throw ('directive-missing: ' + $entry.Key)
    }
    $actual = (Get-FileHash -LiteralPath $entry.Key -Algorithm SHA256).Hash
    if ($actual -cne $entry.Value) {
        throw ('directive-preimage-changed: ' + $entry.Key)
    }
    $null = Get-Content -LiteralPath $entry.Key -Raw -Encoding utf8
}
```

Expected: all fifteen are readable and unchanged. Any mismatch holds that input and forbids its later deletion.

- [ ] **Step 2: Build a lossless RequirementLedger**

For each input, assign one family prefix and monotonically numbered IDs:

```text
ND-RAG-WEB
ND-AGENT-GATE
ND-RISK-UTILITY
ND-DPA
ND-NEXT-BFF
ND-MAIN-CHAT-PARENT
ND-CHAT-SESSION
ND-CHAT-SSE
ND-CHAT-FAILSOFT
ND-DESKTOP-WRAPPER
```

Create a ledger row for every directive ID, target, mandatory/forbidden rule, RED reason, GREEN assertion, verification command, non-goal, rollback rule, and HOLD condition. Keep the source path, source hash, and heading or JSON pointer. Resolve duplicates by retaining all source paths on one row; preserve conflicts as separate rows with `decision=hold`.

Required named IDs include:

```text
G-20260802-RAG-TAIL-WEB-01
SD-20260802-RAG-TAIL-WEB-01
AWX-DESKTOP-AGENT-CODE-EVIDENCE-GATE-V1
SD-RISK-UTILITY-SHADOW-20260804
DPA-LINEAGE-P0-20260802
SD-DPA-LINEAGE-P0-20260802
AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2
AWX-DESKTOP-MAIN-CHATBOT-SESSION-LIST-RED-20260805
AWX-DESKTOP-MAIN-CHATBOT-SSE-PROTOCOL-RED-20260805
AWX-DESKTOP-MAIN-CHATBOT-TYPED-FAIL-SOFT-RED-20260805
```

- [ ] **Step 3: Write the immutable canonical program**

Use `apply_patch` to create the canonical document with these exact top-level sections:

```markdown
# AWX Desktop Notebook Consolidated Source Program — 2026-08-06

## Authority And Current Root
## DirectiveInventory
## RequirementLedger
## Conflict Decisions
## Immediate Chat Work Units
## Deferred Work Units
## Historical Auxiliary Contract Hold
## ACL-Protected Hold
## Verification And Rollback
## RetirementManifest
## Completion Contract
```

The immediate work units are:

```yaml
workUnits:
  - workUnitId: W0-CHAT-FIXTURE
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
    nextProof: behavior-neutral fixture GREEN then message-event-unhandled RED
  - workUnitId: W1-CHAT-SSE
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
      - main/resources/static/js/chat.js
    maxEventUtf8Bytes: 131072
    overflowOutcome: stream_failed
  - workUnitId: W2-CHAT-TYPED-FAILSOFT
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
      - main/resources/static/js/chat.js
    maxFailureDecodedChars: 1200
  - workUnitId: W3-CHAT-SESSION-LIST
    status: pending
    targetFiles:
      - scripts/chat_ui_stream_contract_tests.js
      - main/resources/static/js/chat.js
      - main/resources/templates/chat-ui.html
    maxVisibleSessions: 12
```

Record RAG/web, agent-code gate, risk/utility, dynamic prompt assembly, Next/BFF, security, HTTP rollback, Supabase, and historical auxiliary-prompt requirements as `DEFER` or `HOLD`. Their presence in the ledger grants no source authority.

- [ ] **Step 4: Embed all fifteen retirement candidates as initially ineligible**

Each item contains exact `path`, `expectedSha256`, `absorbedRequirementIds`, `eligibility=hold`, and `deletionResult=not_run`. Files 6-9 retain strong gates:

```text
6 evidence IDs and triad constraints fully represented
7 complete DPA architecture and file map represented
8 complete DPA tests, rollback, and physical-attempt lineage represented
9 Next/Spring authority separation and frontend boundary represented
```

No protected June 5 file, Canary, reusable prompt, or PatchDrop sidecar appears in the retirement item list.

- [ ] **Step 5: Validate structure and secret safety**

Run:

```powershell
$awxCanonical = 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md'
$awxRequiredTokens = @(
  '## DirectiveInventory',
  '## RequirementLedger',
  'W0-CHAT-FIXTURE',
  'W1-CHAT-SSE',
  'W2-CHAT-TYPED-FAILSOFT',
  'W3-CHAT-SESSION-LIST',
  '131072',
  '1200',
  '## RetirementManifest',
  'desktopFinalProof'
)

$awxCanonicalText = Get-Content -LiteralPath $awxCanonical -Raw -Encoding utf8
foreach ($token in $awxRequiredTokens) {
    if (-not $awxCanonicalText.Contains($token)) {
        throw ('canonical-token-missing: ' + $token)
    }
}

foreach ($entry in $awxDirectiveInputs.GetEnumerator()) {
    if (-not $awxCanonicalText.Contains($entry.Key.Replace('\', '/')) -or
        -not $awxCanonicalText.Contains($entry.Value)) {
        throw ('canonical-inventory-gap: ' + $entry.Key)
    }
}

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\git_secret_guard.ps1 `
  -Mode manual `
  -Path $awxCanonical

Get-FileHash -LiteralPath $awxCanonical -Algorithm SHA256
git status --short -- $awxCanonical
```

Expected: one new canonical file, zero secret hits, all fifteen paths/hashes present. Report its SHA-256 to the user before Task 8; do not write that hash back into the canonical file.

---

### Task 3: W0 Fixture Instrumentation and First Real RED

**Files:**

- Modify: `scripts/chat_ui_stream_contract_tests.js:569-1025, 5065-5499`
- Verify: `scripts/chat_ui_browser_fault_fixture_tests.js`
- Verify: `scripts/chat_ui_view_layer_contract_tests.js`

**Interfaces:**

- Consumes: the user-authorized fixture preimage and Task 2 work unit W0.
- Produces: behavior-neutral fetch instrumentation, repaired characterization cases, and the single expected `message-event-unhandled` RED consumed by Task 4.

- [ ] **Step 1: Verify the W0 fixture preimage and unchanged baseline**

Run:

```powershell
$awxFixture = 'scripts\chat_ui_stream_contract_tests.js'
$awxExpectedFixtureHash = '127F0AB0E0DF7F5AFAAB5EB67ACEE289963E50F488678EA86D812E6F460E5D64'

if ((Get-FileHash -LiteralPath $awxFixture -Algorithm SHA256).Hash -cne $awxExpectedFixtureHash) {
    throw 'fixture-preimage-changed'
}

node .\scripts\chat_ui_stream_contract_tests.js
node .\scripts\chat_ui_browser_fault_fixture_tests.js
node .\scripts\chat_ui_view_layer_contract_tests.js
```

Expected: all three exit 0 before mutation.

- [ ] **Step 2: Add behavior-neutral fetch instrumentation**

Use `apply_patch` to change the capture to:

```javascript
fetchCalls.push({
  url: String(url),
  method: options.method || 'GET',
  body: options.body || '',
  headers: options.headers || {},
  cache: options.cache
});
```

Also make these characterization-only repairs:

- rename the two-valid-frame case from `error-same-frame-final` to `error-same-chunk-final`;
- compare answer-event and sync-call counts before and after the stale-event case;
- remove the unused `__syncMode = 'fail'` assignment and assert literal `Sync not_attempted`;
- give the empty-stream scenario an independent child/event/request baseline.

- [ ] **Step 3: Re-run the behavior-neutral suite**

Run the same three Node commands. Expected: all exit 0. If behavior changes, restore only the fixture's W0 preimage and stop with `fixture-contract-repair-red`.

- [ ] **Step 4: Add only the first W1 RED**

Add a synthetic `sse-message-event` response:

```javascript
new TextEncoder().encode(
  'event: message\n' +
  'data: {"type":"message","data":"message fallback chunk"}\n\n'
)
```

Exercise the real `sendMessage()` boundary and assert:

```javascript
context.__streamMode = 'sse-message-event';
const childrenBefore = chatWindow.children.length;
elements.get('messageInput').value = 'message event probe';

await vm.runInContext('sendMessage()', context);

const added = chatWindow.children.slice(childrenBefore);
const assistant = added.find((node) => node?.dataset?.speaker === 'assistant');
const diagnostics = added.filter((node) => node?.dataset?.role === 'stream-diagnostic');

assert(
  assistant?.textContent === 'message fallback chunk' && diagnostics.length === 0,
  'message-event-unhandled'
);
```

- [ ] **Step 5: Prove exactly one intended RED and seal W0**

Run:

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
```

Expected: nonzero exit whose first new reason is exactly `message-event-unhandled`. Record the fixture RED SHA-256 and unchanged `chat.js` SHA-256. Do not edit application source in W0 and do not stage the fixture.

---

### Task 4: W1 Bounded SSE Parser and Event Ownership

**Files:**

- Modify: `scripts/chat_ui_stream_contract_tests.js:600-1025, 3526-3566, 5065-5499`
- Modify: `main/resources/static/js/chat.js:83-89, 1908-1941, 3807-4025, 4659-4697`

**Interfaces:**

- Consumes: W0's RED fixture hash and the unchanged authorized `chat.js` preimage.
- Produces: `createSseEventParser(options)`, `decodeSseEvent(event)`, `safeUnknownEventName(value)`, and `streamProtocolError(code)` for W2.

- [ ] **Step 1: Run the three-way preflight and acquire the W1 lease**

Use `demo1-source-edit-three-way-preflight` with only the fixture and `chat.js`. Require stable `APPLY`. Then run:

```powershell
$awxOwnerId = 'codex-20260806-w1-sse'

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action begin -Role desktop -Root . `
  -Topic notebook-chat-sse-20260806 `
  -OwnerId $awxOwnerId -TtlMinutes 180
```

Expected: `source-edit-locks=1`. Recheck the W0 fixture RED hash and authorized `chat.js` hash immediately before patching.

- [ ] **Step 2: Add the bounded protocol parser**

Use `apply_patch` to add these interfaces near the stream constants:

```javascript
const MAX_SSE_EVENT_UTF8_BYTES = 131072;
const SAFE_STREAM_EVENT_NAME = /^[A-Za-z0-9._-]{1,64}$/;

function streamProtocolError(code) {
  const error = new Error('stream_failed');
  error.name = 'StreamProtocolError';
  error.streamFailureCode = code;
  return error;
}

function safeUnknownEventName(value) {
  const text = String(value || '');
  return SAFE_STREAM_EVENT_NAME.test(text) ? text : 'unknown';
}

function sseFieldValue(line, field) {
  let value = line.slice(field.length + 1);
  if (value.startsWith(' ')) value = value.slice(1);
  return value;
}
```

Implement `createSseEventParser({ maxEventUtf8Bytes = MAX_SSE_EVENT_UTF8_BYTES } = {})` so it owns one streaming `TextDecoder`, a partial-line buffer, current event type, accumulated data lines, and UTF-8 byte count. It must:

- emit lines for LF, CRLF, and lone CR, including separators split across chunks;
- remove at most one U+0020 after the field colon;
- ignore comments and unsupported fields;
- join data fields with one `\n`;
- dispatch only on an empty line;
- reset type to `message` after dispatch;
- throw `streamProtocolError('sse_event_too_large')` before exceeding 131,072 encoded data bytes;
- return no event from `finish()` when a nonterminated tail remains.

The core dispatch shape is:

```javascript
function dispatch(out) {
  if (dataLines.length > 0) {
    out.push({ type: eventType || 'message', data: dataLines.join('\n') });
  }
  eventType = 'message';
  dataLines = [];
  eventUtf8Bytes = 0;
}
```

- [ ] **Step 3: Decode each event exactly once**

Add:

```javascript
function decodeSseEvent(event) {
  const eventType = event?.type || 'message';
  try {
    const payload = JSON.parse(event?.data || '');
    return {
      effectiveType: payload?.type || eventType,
      payload,
      malformedTerminal: false
    };
  } catch {
    const terminal = eventType === 'final' || eventType === 'error' || eventType === 'stream_failed';
    return {
      effectiveType: terminal ? 'stream_failed' : eventType,
      payload: terminal
        ? { type: 'stream_failed', code: 'malformed_terminal_event' }
        : { type: eventType, data: event?.data || '' },
      malformedTerminal: terminal
    };
  }
}
```

Replace the frame/line nested loops in `streamChat` with parser `push(next.value)` results. For each result, call `decodeSseEvent` once, calculate terminal ownership once, and call `renderChatEvent` once. Call `finish()` at EOF only to discard the incomplete tail.

- [ ] **Step 4: Route message, scoreDelta, and unknown events safely**

Change message rendering to share the token path:

```javascript
if (type === 'token' || type === 'message') {
  const bubble = assistant;
  clearAssistantPendingPlaceholder(bubble);
  appendTextWithBreaks(bubble, filterAssistantReasoningChunk(payload.data || ''));
  refreshEvidenceRailFromAnswerText(
    bubble?.parentElement || dom.chatMessages,
    bubble?.dataset?.ariaText || bubble?.textContent || '',
    {
      answerMode: 'streamed',
      model: state.responseModelUsed || dom.modelSelect?.value || '-'
    }
  );
}
```

Add the backend-shaped score owner:

```javascript
} else if (type === 'scoreDelta') {
  const signal = payload?.scoreDelta || {};
  renderScoreDeltaDetail(signal, assistant?.parentElement || dom.chatMessages);
  updateOrchestrationSignalBar({
    streamStatus: 'scoreDelta',
    streamContext: scoreDeltaContext(signal),
    scoreDelta: signal.rawScoreDelta,
    scoreDeltaContext: scoreDeltaContext(signal)
  });
  setStatusRailValue(dom.traceStatus, 'scoreDelta');
```

Extend `renderScoreDeltaDetail` with safe numeric labels for `scoreDelta`, `dropRatio`, `maxDrawdown`, `expectedDelta`, and `rawScoreDelta`, followed by `clampName`, `stage`, `guard`, and `eventId`.

Replace the unknown branch's detail rendering with:

```javascript
const eventName = safeUnknownEventName(type);
diagnostic.textContent = 'Stream event ' + eventName;
diagnostic.setAttribute('aria-label', diagnostic.textContent);
```

No unknown payload value may reach DOM, ARIA, dataset, status rail, or console.

- [ ] **Step 5: Add and satisfy the ordered W1 fixture rows**

Add one row, run RED, patch minimally, and rerun GREEN in this exact order:

```text
message-event-unhandled
sse-default-message-type-unhandled
sse-event-type-not-reset
sse-data-lines-not-aggregated
sse-field-whitespace-overtrimmed
sse-lf-line-ending-unhandled
sse-crlf-line-ending-unhandled
sse-cr-line-ending-unhandled
score-delta-event-unhandled
score-delta-value-missing
score-delta-drop-ratio-missing
score-delta-max-drawdown-missing
score-delta-expected-value-missing
score-delta-raw-value-missing
score-delta-clamp-label-missing
score-delta-stage-label-missing
score-delta-guard-label-missing
score-delta-event-id-missing
score-delta-rail-missing
unknown-event-data-not-omitted
unknown-event-message-not-omitted
unknown-event-reason-not-omitted
unknown-event-other-detail-not-omitted
unknown-event-name-character-not-bounded
unknown-event-name-length-not-bounded
sse-event-byte-cap-missing
sse-overflow-not-terminal
sse-utf8-split-corrupted
sse-eof-tail-dispatched
```

Each scenario resets captured children, events, requests, and console output. Every unknown-event scenario is followed by one valid token and final event to prove fail-soft continuation.

- [ ] **Step 6: Verify W1 and release its lease**

Run:

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
node .\scripts\chat_ui_browser_fault_fixture_tests.js
node .\scripts\chat_ui_view_layer_contract_tests.js

Get-FileHash -Algorithm SHA256 `
  .\scripts\chat_ui_stream_contract_tests.js, `
  .\main\resources\static\js\chat.js

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action end -Role desktop -Root . `
  -Topic notebook-chat-sse-20260806 `
  -OwnerId $awxOwnerId
```

Expected: all Node fixtures exit 0 and `source-edit-locks=0`. These postimages become W2 preimages.

---

### Task 5: W2 Typed Fail-Soft Transport

**Files:**

- Modify: `scripts/chat_ui_stream_contract_tests.js:600-1025, 5021-5499`
- Modify: `main/resources/static/js/chat.js:3729-3758, 4005-4010, 4461-4557, 4643-4717`

**Interfaces:**

- Consumes: W1's `streamProtocolError` and W1 GREEN postimages.
- Produces: `readBoundedFailureBody`, `classifyChatFailure`, `chatFailureError`, and `applyChatFailureState` used by stream and SSE terminal failures.

- [ ] **Step 1: Reconcile W1 postimages, run the W2 preflight, and acquire a new lease**

Use only the fixture and `chat.js` in the three-way EvidenceSnapshot. Require stable `APPLY`, then:

```powershell
$awxOwnerId = 'codex-20260806-w2-failsoft'

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action begin -Role desktop -Root . `
  -Topic notebook-chat-failsoft-20260806 `
  -OwnerId $awxOwnerId -TtlMinutes 180
```

- [ ] **Step 2: Add RED-P and the bounded one-pass reader**

The first fixture returns HTTP 503 with an allowlisted token only after character 1200. Assert `bounded-failure-body-cap-missing`, no typed metadata, generic `message_failed`, preserved draft, decoded count at most 1200, and exactly one reader cancellation.

Implement:

```javascript
async function readBoundedFailureBody(response, maxChars = 1200) {
  const contentType = String(response?.headers?.get?.('content-type') || '').toLowerCase();
  const reader = response?.body?.getReader?.();
  if (!reader) return { text: '', contentType, truncated: false };

  const decoder = new TextDecoder();
  let text = '';
  let truncated = false;

  while (text.length < maxChars) {
    const next = await reader.read();
    if (next.done) break;
    text += decoder.decode(next.value, { stream: true });
    if (text.length >= maxChars) {
      text = text.slice(0, maxChars);
      truncated = true;
      await reader.cancel();
      break;
    }
  }

  if (!truncated) {
    text = (text + decoder.decode()).slice(0, maxChars);
  }
  return { text, contentType, truncated };
}
```

Do not retain the returned text after classification.

- [ ] **Step 3: Add the fixed failure classifier**

Use these exact public mappings:

| Input | failureKind | retryable | action | serverCode |
| --- | --- | --- | --- | --- |
| 503 + `backend_unavailable` | `service_unavailable` | true | `retry` | `backend_unavailable` |
| 503, no allowed code | `service_unavailable` | true | `retry` | absent |
| 403 + `forbidden` | `access_denied` | false | `sign_in_or_change_session` | `forbidden` |
| 403 + `session_forbidden` | `access_denied` | false | `choose_or_start_session` | `session_forbidden` |
| 403, no allowed code | `access_denied` | false | `sign_in_or_change_session` | absent |
| 504 + `backend_timeout` | `timeout` | true | `retry` | `backend_timeout` |
| native TypeError | `network_error` | true | `check_connection` | absent |
| SSE + `backend_timeout` | `timeout` | true | `retry` | `backend_timeout` |
| SSE + `session_forbidden` | `access_denied` | false | `choose_or_start_session` | `session_forbidden` |
| unknown SSE code | `stream_failed` | true | `retry_or_check_model` | absent |
| parser overflow | `stream_failed` | true | `retry_or_check_model` | `sse_event_too_large` |
| malformed final JSON | `stream_failed` | true | `retry_or_check_model` | `malformed_terminal_event` |

Implement a frozen shape:

```javascript
{
  failureKind: 'stream_failed',
  retryable: true,
  status: null,
  serverCode: null,
  nextAction: 'retry_or_check_model'
}
```

`classifyChatFailure(input = {})` may accept only exact allowlisted scalar codes from bounded JSON or bounded plain text. Malformed JSON and unknown tokens fall back to status-only classification. `chatFailureError(meta)` uses fixed message `chat_failure` and stores metadata only on `error.chatFailure`.

- [ ] **Step 4: Apply typed state without raw strings**

Implement:

```javascript
function applyChatFailureState(assistant, meta) {
  if (!assistant || !meta) return;
  assistant.dataset.failureKind = meta.failureKind;
  assistant.dataset.failureRetryable = String(meta.retryable === true);
  if (Number.isInteger(meta.status)) assistant.dataset.failureStatus = String(meta.status);
  else delete assistant.dataset.failureStatus;
  if (meta.serverCode) assistant.dataset.failureCode = meta.serverCode;
  else delete assistant.dataset.failureCode;
  assistant.dataset.failureNextAction = meta.nextAction;
  assistant.setAttribute(
    'aria-label',
    'Message failed. ' + meta.failureKind + '. ' + meta.nextAction + '.'
  );
}
```

Change `failedSendReason` to read only fixed metadata:

```javascript
function failedSendReason(error, fallback) {
  const meta = error?.chatFailure;
  if (!meta) return safeDebugCockpitDetail(fallback || 'stream_failed');
  return safeDebugCockpitDetail(
    [meta.failureKind, meta.serverCode, meta.nextAction].filter(Boolean).join(' ')
  );
}
```

Never consult `error.message` for the public failure rail.

- [ ] **Step 5: Classify non-2xx and terminal SSE before rendering**

In `streamChat`:

```javascript
if (!response.ok) {
  const bounded = await readBoundedFailureBody(response, 1200);
  const meta = classifyChatFailure({
    status: response.status,
    boundedText: bounded.text,
    contentType: bounded.contentType
  });
  throw chatFailureError(meta);
}

if (!response.body) {
  throw chatFailureError(classifyChatFailure({ streamCode: 'stream_failed' }));
}
```

For SSE `error` and `stream_failed`, classify the allowlisted `payload.code` before the existing generic renderer. Mark both terminal. A malformed `final` from `decodeSseEvent` becomes the same terminal path and never becomes assistant answer text.

In the stream loop, an `error`, `stream_failed`, parser overflow, or malformed terminal event must set terminal ownership and throw one `chatFailureError` instead of returning normally. This keeps the draft and prevents exact-run recovery, EOF success, late token/final rendering, or a duplicate generic renderer.

In `sendMessageUnlocked`, normalize failures before rendering:

```javascript
const failureMeta = error?.chatFailure || classifyChatFailure({
  error,
  streamCode: error?.streamFailureCode
});
const typedError = error?.chatFailure ? error : chatFailureError(failureMeta);
applyChatFailureState(assistant, failureMeta);
setMessageContent(assistant, 'assistant', 'message_failed', 'error');
renderMessageFailedDiagnostic(assistant, typedError);
```

Clear active run identity for terminal SSE failures so they cannot enter exact-run recovery. Clear the requested current/persisted session only for exact `session_forbidden`. Preserve it for generic/status-only 403.

- [ ] **Step 6: Add and satisfy each W2 RED in order**

Run one assertion and one minimal production change at a time:

```text
bounded-failure-body-cap-missing
typed-http-503-missing
typed-http-403-forbidden-missing
typed-http-403-session-denial-missing
typed-http-403-status-session-preservation-missing
typed-http-504-missing
typed-network-error-missing
bounded-failure-body-malformed-json-missing
bounded-failure-body-unknown-token-missing
typed-sse-backend-timeout-missing
typed-sse-session-denial-missing
typed-sse-unknown-error-missing
stream-failed-terminal-missing
malformed-final-fail-closed-missing
```

Every scenario asserts generic bubble text, typed datasets, draft/session behavior, zero sync generation, terminal ownership where applicable, and absence of its private sentinel from DOM, ARIA, dataset, rails, and captured console.

- [ ] **Step 7: Verify W2 and release its lease**

Run the three Node fixtures, record fixture and `chat.js` postimage hashes, then:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action end -Role desktop -Root . `
  -Topic notebook-chat-failsoft-20260806 `
  -OwnerId $awxOwnerId
```

Expected: all Node fixtures exit 0 and no active lease remains.

---

### Task 6: W3 Session List, Selection, and Refresh

**Files:**

- Modify: `scripts/chat_ui_stream_contract_tests.js:358-598, 1112-1160, 4460-5499`
- Modify: `main/resources/static/js/chat.js:59-89, 98-232, 446-611, 641-680, 3841-3903, 4795-4808`
- Modify conditionally: `main/resources/templates/chat-ui.html:93`

**Interfaces:**

- Consumes: W2 GREEN postimages, existing `withChatCorrelationHeaders`, existing detail-hydration behavior, and backend `SessionInfo(id,title,lastAnswerMode,lastTraceTurnId)`.
- Produces: `strictBackendSessionId`, `renderSessionList`, `syncSessionSelectionCapability`, `refreshSessionList`, `validateSessionDetail`, and `selectSessionCandidate`.

- [ ] **Step 1: Reconcile W2 postimages, run the W3 preflight, and acquire a new lease**

The EvidenceSnapshot includes the fixture, `chat.js`, and conditional HTML target. Require stable `APPLY`, then:

```powershell
$awxOwnerId = 'codex-20260806-w3-session-list'

powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action begin -Role desktop -Root . `
  -Topic notebook-chat-session-list-20260806 `
  -OwnerId $awxOwnerId -TtlMinutes 180
```

- [ ] **Step 2: Separate diagnostic and session-list row ownership**

Change `clearSessionModeDiagnostics()` so its all-session path removes only `[data-session-mode-row]` nodes rather than replacing the entire container. Add:

```javascript
const SESSION_LIST_LIMIT = 12;
let sessionListRefreshGeneration = 0;
let sessionListRefreshInFlight = null;
let sessionSelectionGeneration = 0;

function strictBackendSessionId(value) {
  return typeof value === 'number' &&
    Number.isSafeInteger(value) &&
    value > 0 ? value : null;
}
```

Reuse the existing `data-session-mode-list` container. Modify the HTML label to:

```html
<div class="session-mode-list"
     data-session-mode-list
     aria-label="Session history and mode diagnostics"
     aria-live="polite"></div>
```

Do not edit CSS.

- [ ] **Step 3: Implement typed bounded list rendering**

`renderSessionList({ rows, state, retryable })` must:

- remove only `[data-session-list-row]` and `[data-session-list-state]`;
- set `container.dataset.sessionListState`;
- render one state row for `loading`, `empty`, `unavailable`, or `invalid_response`;
- accept only strict numeric positive IDs;
- preserve backend order and cap choices at 12;
- render each choice as `button type="button"` with `data-session-list-row="true"` and `data-session-id`;
- use bounded title, answer-mode, and trace labels through existing safe text helpers;
- never remove or relabel `data-session-mode-row` diagnostics.

The positive filter is:

```javascript
const validRows = (Array.isArray(rows) ? rows : [])
  .map((row) => ({ row, id: strictBackendSessionId(row?.id) }))
  .filter((entry) => entry.id !== null)
  .slice(0, SESSION_LIST_LIMIT);
```

- [ ] **Step 4: Implement exact list transport and coalescing**

`refreshSessionList(reason = 'manual')` must reuse an in-flight promise, increment a generation for a new request, render loading, and call:

```javascript
apiCall('/api/chat/sessions', {
  method: 'GET',
  cache: 'no-store',
  headers: withChatCorrelationHeaders(
    {},
    { sessionId: strictBackendSessionId(state.currentSessionId) }
  )
});
```

Map list failures without raw data:

```text
403 -> session_list_forbidden, retryable=false
503 -> session_list_unavailable, retryable=true
invalid JSON -> session_list_invalid_response, retryable=true
valid non-array -> session_list_invalid_response, retryable=true
network rejection -> session_list_network_error, retryable=true
```

Only the current generation may render. Call `void refreshSessionList('init')` once during initialization.

- [ ] **Step 5: Implement active-run capability and validated selection**

`syncSessionSelectionCapability()` disables every choice and sets `aria-disabled="true"` while `activeRunIdentitySnapshot()` or the active stream owns the transcript.

Call `syncSessionSelectionCapability()` after rendering list rows, after `rememberActiveRunIdentity`, after `clearActiveRunIdentity`, when a stream assistant takes ownership, and when stream ownership is released. These hooks must not create a request or change the current Stop/cancel target.

`validateSessionDetail(candidateId, detail)` returns a normalized detail only when:

- candidate and detail IDs are strict numbers and exactly equal;
- `found` is not false;
- `messages` is an array;
- each retained message has role `user` or `assistant` and string content;
- settings is absent or an object.

`selectSessionCandidate(candidateId)` must:

1. return before fetch when an active run exists;
2. increment `sessionSelectionGeneration` and snapshot current state;
3. call exact `/api/chat/sessions/{candidateId}` with a fresh request ID and `x-session-id` equal to the candidate;
4. make no session/storage/control/transcript/row mutation before validation;
5. reject mismatched, invalid, failed, or stale-generation detail with no state change;
6. synchronously commit the validated session ID, storage, controls, transcript, diagnostic badge, and selected row;
7. invalidate older refresh generations after commit.

Do not add `no-store` to the detail request; that policy is not authorized.

- [ ] **Step 6: Refresh exactly once after a successful final**

At the end of the existing `final` branch, after the answer and session identity commit:

```javascript
void refreshSessionList('final');
```

Do not trigger this refresh for cancel, `error`, `stream_failed`, or malformed final. The in-flight coalescer ensures multiple equivalent triggers share one request.

- [ ] **Step 7: Add and satisfy the ordered W3 fixture rows**

Run one RED/GREEN stage at a time in this exact order:

```text
session-list-request-missing
session-list-cache-policy-missing
session-list-request-id-missing
session-list-current-session-correlation-missing
session-list-session-id-fabricated
session-list-valid-row-not-rendered
session-list-missing-id-accepted
session-list-zero-id-accepted
session-list-negative-id-accepted
session-list-fractional-id-accepted
session-list-string-id-accepted
session-list-render-bounds-missing
session-list-empty-state-missing
session-list-diagnostic-row-collision
session-list-forbidden-map-missing
session-list-unavailable-map-missing
session-list-invalid-json-map-missing
session-list-non-array-map-missing
session-list-network-map-missing
session-selection-active-run-guard-missing
session-detail-url-missing
session-detail-request-id-missing
session-detail-session-id-mismatch
session-detail-precommit-detected
session-detail-id-mismatch-not-atomic
session-detail-invalid-not-atomic
session-detail-failure-not-atomic
session-detail-stale-generation-not-atomic
session-selection-atomic-commit-missing
session-list-terminal-refresh-missing
session-list-refresh-overlap
session-list-stale-refresh-overwrite
```

End with one literal `session-list-contract-green` assertion after every earlier row is GREEN.

- [ ] **Step 8: Verify W3 and release its lease**

Run the three Node fixtures. Record postimage hashes for the fixture, `chat.js`, and HTML whether changed or unchanged. Verify the excluded CSS still has hash `6423D9A5B3828713D292A13ED57F5F282D1BE3B45DBAD0B99D59CFE8F60C38EB`. Then release:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action end -Role desktop -Root . `
  -Topic notebook-chat-session-list-20260806 `
  -OwnerId $awxOwnerId
```

---

### Task 7: Broad Build and Browser-Visible Verification

**Files:**

- Verify: all Task 1-6 outputs
- Do not modify additional files

**Interfaces:**

- Consumes: W0-W3 GREEN postimages.
- Produces: fresh Node, Gradle, JAR, localhost Browser, lock, lease, and secret evidence required by Task 8.

- [ ] **Step 1: Run the focused Node ladder**

```powershell
node .\scripts\chat_ui_stream_contract_tests.js
node .\scripts\chat_ui_browser_fault_fixture_tests.js
node .\scripts\chat_ui_view_layer_contract_tests.js
```

Expected: the existing success markers and exit 0.

- [ ] **Step 2: Run isolated Desktop Gradle verification**

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-notebook-chat'
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'awx-gradle-user-home\desktop-notebook-chat'
$awxProjectCache = Join-Path $env:LOCALAPPDATA 'awx-gradle-project-cache\desktop-notebook-chat'

New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $awxProjectCache | Out-Null

.\gradlew.bat chatUiTest --rerun-tasks --no-daemon --project-cache-dir $awxProjectCache
.\gradlew.bat classes --rerun-tasks --no-daemon --project-cache-dir $awxProjectCache
.\gradlew.bat :app:classes --rerun-tasks --no-daemon --project-cache-dir $awxProjectCache
.\gradlew.bat bootJar --rerun-tasks --no-daemon --project-cache-dir $awxProjectCache
```

Expected: all four commands exit 0. Do not infer a fresh JAR from an old `build\libs` artifact; use the current Desktop split output and verify timestamp/hash.

- [ ] **Step 3: Start only the newly built JAR on a controlled port**

First confirm ports 8080, 8081, and 18080 are not unexpectedly owned. Resolve exactly one current JAR under `build\desktop\libs`, record its SHA-256, and start it:

```powershell
$awxPort = 18080
$awxObservedPorts = @(8080, 8081, $awxPort)
$awxListeners = @(
  Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $awxObservedPorts -contains $_.LocalPort }
)

if ($awxListeners.Count -ne 0) {
    $awxListenerSummary = $awxListeners |
      Select-Object LocalAddress, LocalPort, OwningProcess
    $awxListenerSummary
    throw 'port-conflict'
}

$awxJar = Get-ChildItem -LiteralPath '.\build\desktop\libs' -File |
  Where-Object { $_.Extension -eq '.jar' } |
  Sort-Object LastWriteTimeUtc -Descending |
  Select-Object -First 1

if ($null -eq $awxJar) {
    throw 'fresh-desktop-jar-missing'
}

$awxJarHash = (Get-FileHash -LiteralPath $awxJar.FullName -Algorithm SHA256).Hash
$awxRuntime = Start-Process -FilePath 'java' `
  -ArgumentList @('-jar', $awxJar.FullName, '--server.port=' + $awxPort) `
  -WindowStyle Hidden -PassThru
```

Poll the exact surface with a bounded timeout:

```powershell
$awxRuntimeReady = $false
$awxDeadline = [DateTime]::UtcNow.AddSeconds(90)

while ([DateTime]::UtcNow -lt $awxDeadline -and -not $awxRuntimeReady) {
    if ($awxRuntime.HasExited) {
        throw 'runtime-exited-before-chat-ui'
    }
    try {
        $awxResponse = Invoke-WebRequest `
          -Uri ('http://127.0.0.1:' + $awxPort + '/chat-ui') `
          -UseBasicParsing -TimeoutSec 5
        $awxRuntimeReady = $awxResponse.StatusCode -eq 200
    } catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $awxRuntimeReady) {
    throw 'chat-ui-runtime-timeout'
}
```

If boot requires unavailable external state, stop the exact PID and report one `evidence_needed` action rather than changing DB/provider configuration.

- [ ] **Step 4: Run in-app Browser proof**

Use the Browser skill against the fresh localhost runtime. Verify:

- at most 12 session choices or one typed empty state;
- diagnostic rows and session-choice rows coexist;
- selecting a row hydrates only its matching transcript;
- selection is disabled during an active stream;
- LF, CRLF, lone CR, multiline data, scoreDelta, and unknown events render through their correct owners;
- 403, 503, 504, network, SSE error, stream overflow, and malformed final show only categorical metadata;
- no private sentinel appears in visible text, ARIA, attributes, diagnostic rails, or console;
- cancel/reload behavior remains visible and functional;
- viewport geometry has no horizontal overflow or transcript/composer overlap at 1440x900 and 390x844;
- console crash count is zero.

Browser evidence proves visible behavior only. It does not prove provider generation, DB state, or Supabase access.

- [ ] **Step 5: Stop only the runtime started in Step 3**

```powershell
if ($awxRuntime -and -not $awxRuntime.HasExited) {
    Stop-Process -Id $awxRuntime.Id
}
```

- [ ] **Step 6: Run exact-path secret and whitespace checks**

```powershell
$awxChangedTargets = @(
  '.agents\skills\demo1-consolidating-notebook-directives\SKILL.md',
  '.agents\skills\demo1-consolidating-notebook-directives\agents\openai.yaml',
  '.agents\skills\demo1-consolidating-notebook-directives\references\consolidation-contract.md',
  '.agents\skills\demo1-consolidating-notebook-directives\tests\pressure-scenarios.md',
  'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md',
  'scripts\chat_ui_stream_contract_tests.js',
  'main\resources\static\js\chat.js',
  'main\resources\templates\chat-ui.html'
)

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\git_secret_guard.ps1 `
  -Mode manual `
  -Path $awxChangedTargets

$awxTrailingWhitespace = foreach ($path in $awxChangedTargets) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $lineNumber = 0
    Get-Content -LiteralPath $path -Encoding utf8 | ForEach-Object {
        $lineNumber += 1
        if ($_ -match '[ \t]+$') {
            [pscustomobject]@{ path = $path; line = $lineNumber }
        }
    }
}

if (@($awxTrailingWhitespace).Count -ne 0) {
    throw 'trailing-whitespace-detected'
}
```

Expected: zero secret hits and zero whitespace violations.

---

### Task 8: Retire Only Fully Absorbed Exact-Hash Directives

**Files:**

- Delete conditionally: the fifteen Task 2 inputs
- Preserve: all excluded, protected, changed, or incompletely absorbed files

**Interfaces:**

- Consumes: immutable canonical directive hash, Task 1 skill validation, W0-W3 GREEN evidence, Gradle/Browser proof, and Task 2 requirement mappings.
- Produces: exact retired/held counts and proof that the canonical directive remains.

- [ ] **Step 1: Publish the canonical directive to the user before deletion**

Send a commentary update containing the clickable canonical path, its current full-file SHA-256, included count 15, and protected count 37. State that deletion has not started. This satisfies the required publication-before-retirement ordering.

- [ ] **Step 2: Adjudicate coverage without voting**

Keep `SUPPORT_CONTRACT`, `SUPPORT_SCENARIO`, and `FALSIFY` independent:

- `SUPPORT_CONTRACT` maps every candidate requirement to canonical IDs and exact replacement text.
- `SUPPORT_SCENARIO` maps every required work unit to current RED/GREEN, build, and Browser evidence.
- `FALSIFY` searches only for omitted requirements, changed hashes, protected evidence, or incomplete GREEN proof.

Give `NEUTRAL` only those three packets plus current command evidence. It returns `ELIGIBLE` or `HOLD` per path without gathering new evidence or implementing changes. Evaluate once with support packets before FALSIFY and once with FALSIFY before support packets. If any path's verdict changes with packet order, that path is `HOLD`.

Files 6-9 remain `HOLD` unless their strong gates from Task 2 are explicitly present. A majority count cannot override an omission.

- [ ] **Step 3: Recheck every eligible leaf immediately before deletion**

For each `ELIGIBLE` item:

```powershell
$actual = (Get-FileHash -LiteralPath $item.path -Algorithm SHA256).Hash
if ($actual -cne $item.expectedSha256) {
    $item.eligibility = 'hold'
    $item.exclusionReason = 'retirement-preimage-changed'
}
```

Also require:

```text
canonical directive exists
canonical directive hash equals the published hash
all required skill validators PASS
W0-W3 focused and broad verification PASS
active index lock absent
active source lease count zero
top-level PatchDrop patch count zero
```

- [ ] **Step 4: Delete eligible paths with one exact apply_patch**

Construct an `apply_patch` containing one `Delete File` entry per `ELIGIBLE` path. Do not include a glob, directory, protected report, Canary, reusable prompt, PatchDrop sidecar, or `HOLD` item.

If all fifteen pass, the exact deletion set is the fifteen paths in Task 2. If any path fails, delete only the remaining eligible leaves and report the held path and reason.

- [ ] **Step 5: Verify deletion and preservation**

Run:

```powershell
foreach ($entry in $awxDirectiveInputs.GetEnumerator()) {
    [pscustomobject]@{
        path = $entry.Key
        exists = Test-Path -LiteralPath $entry.Key
        expectedSha256 = $entry.Value
    }
}

if (-not (Test-Path -LiteralPath 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md')) {
    throw 'canonical-directive-missing-after-retirement'
}

$awxProtectedJune5 = @(
  Get-ChildItem -LiteralPath '__reports__' -File -Force |
    Where-Object { $_.Name -like '*2026-06-05*' }
)

[pscustomobject]@{
  june5NameCount = $awxProtectedJune5.Count
  activeIndexLock = Test-Path -LiteralPath '.git\index.lock'
}
```

Expected: eligible originals are absent, held originals remain, the canonical directive remains, the June 5 name inventory is unchanged, and the active index lock is absent.

- [ ] **Step 6: Run final no-commit integrity checks**

Run the three Node fixtures once more, `chatUiTest` once more if retirement touched no source, and:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\__patch_drop__\source_edit_session.ps1 `
  -Action status -Role desktop -Root .

git status --short -- `
  .agents/skills/demo1-consolidating-notebook-directives `
  agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md `
  scripts/chat_ui_stream_contract_tests.js `
  main/resources/static/js/chat.js `
  main/resources/templates/chat-ui.html

Get-FileHash -Algorithm SHA256 `
  .\agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md, `
  .\scripts\chat_ui_stream_contract_tests.js, `
  .\main\resources\static\js\chat.js, `
  .\main\resources\templates\chat-ui.html
```

Expected: no active lease, no index lock, current hashes recorded, and no staging or commit.

- [ ] **Step 7: Deliver the complete result**

Return:

- clickable links to the canonical directive, new skill, and changed source/test files;
- canonical SHA-256 and final source postimage hashes;
- included/excluded/retired/held counts;
- exact held reasons for any nondeleted candidate;
- Node, Gradle, JAR, Browser, secret, lock, lease, and protected-file evidence;
- one precise `evidence_needed` action only if a required proof is unavailable.

Do not claim the deferred RAG, DPA, Next/BFF, security, HTTP, Supabase, or provider work is implemented merely because its requirement text is preserved.

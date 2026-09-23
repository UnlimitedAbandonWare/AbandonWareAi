# Random-Probe 100-Defect Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` in the parent Codex task. Repository-local `token-efficient-agents` keeps subagents read-only; the parent owns all writes, integration, verification, and final judgment.

**Goal:** Build a deduplicated, evidence-backed ledger of approximately 100 active-source defect candidates, then select the smallest high-priority repair batch that can pass the repository's source-edit gates.

**Architecture:** Phase A is read-only audit and triage over the Gradle-proven active source roots. It combines repo-native health artifacts with a deterministic, seed-ranked source sample, but records an item as a validated defect only after an active call path and a falsifiable failure mode are proven. Each independent subsystem repair receives a separate Phase B plan with exact production and test paths after triage, so unrelated fixes are not bundled.

**Tech Stack:** Java 17, Spring Boot, Gradle 8.7, PowerShell, JUnit, repository-native source-health and harmony reports.

**Spec:** [AGENTS.md](../../../AGENTS.md) plus active Codex goal `01a04334-d214-7562-97ad-2814877419a0` created from the user's request to probe, prioritize, and repair approximately 100 source issues.

## Global Constraints

- Active root production owners are `main/java`, `main/resources`, `app/src/main/java_clean`, and `app/src/main/resources`; `app/src/main/java` remains inactive unless fresh Gradle evidence changes that result.
- Keep every `dev.langchain4j` dependency exactly on `1.0.1` and keep final prompt construction on `PromptBuilder.build(PromptContext)`.
- Do not modify production source before one frozen EvidenceSnapshot receives stable `APPLY` from exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` and the source-owner lease succeeds.
- Treat the existing 32,544-entry dirty tree as user-owned; every selected target needs target-level overlap and preimage checks.
- One repair cycle changes one intent and normally no more than three files.
- Production behavior changes use RED -> GREEN -> REFACTOR; no commit, push, deploy, credential mutation, database mutation, or destructive cleanup is authorized.
- Browser and Computer evidence is collected only for a selected issue whose behavior reaches a local UI or Windows application surface.

---

### Task 1: Freeze the Desktop and Gradle baseline

**Files:**

- Modify: `data/agent-handoff/codex/report/random-probe-100-20260827-ledger.md`

**Interfaces:**

- Consumes: Desktop checkout, Git metadata, PatchDrop lease state, Java runtime, Gradle task graph.
- Produces: one current baseline identity and the authoritative active-root set used by every later candidate.

- [x] **Step 1: Prove the workspace and collision gates**

Run `git worktree list --porcelain`, `git branch --show-current`, `git rev-parse HEAD`, `git status --short --untracked-files=all`, resolve the Git index-lock path, run `__patch_drop__/janitor_inventory.ps1`, and run `__patch_drop__/source_edit_session.ps1 -Action status`.

Observed: root `C:\AbandonWare\demo-1\demo-1\src`, branch `codex/owned-runtime-browser-restart`, HEAD `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`, 32,544 status rows, no index lock, no active top-level PatchDrop bundle, and no active or corrupt source-edit lease.

- [x] **Step 2: Prove Java and active source ownership**

Run `java -version`, `gradlew.bat projects --console=plain`, and `gradlew.bat checkSourceSetHygiene --console=plain`.

Observed: Java `17.0.13`; Gradle root `src111_merge15` with `:app`; hygiene passed and reported `inactive-present: app/src/main/java`.

- [x] **Step 3: Run the fresh source-health baseline**

Run:

```powershell
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-random-probe-100'
$env:GRADLE_USER_HOME = 'C:\Users\nninn\.gradle-awx-desktop-random-probe-100'
$probeCache = 'C:\Users\nninn\.awx-gradle-project-cache\desktop-random-probe-100'
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene sourceHealthScorecard sourceScoreReport harmonyScoreReport contextPurityReport --no-daemon --console=plain --project-cache-dir $probeCache
```

Expected and observed: exit `0`, 14 actionable tasks executed. The baseline is not green semantically: `sourceScoreReport=0/100`, `provenSilentCatchMatches=18`, `largeActiveSourceFiles=16`, `strictEvidenceAdjustedScore=3.96`, and runtime harmony evidence remains `BLOCKED_EVIDENCE`.

### Task 2: Construct the approximately 100-item candidate ledger

**Files:**

- Modify: `data/agent-handoff/codex/report/random-probe-100-20260827-ledger.md`
- Read: `verification/source-health-scorecard.json`
- Read: `verification/dynamic-rag-harmony-pressure-metrics.json`
- Read: `verification/test-tree-contamination-metrics.json`
- Read: `__reports__/context-purity-decisions.tsv`

**Interfaces:**

- Consumes: the frozen baseline and only Gradle-proven active roots.
- Produces: exactly one deduplicated ledger whose entries carry `id`, `priority`, `status`, `path`, `symbol`, `failureMode`, `activePathEvidence`, `disconfirmingProbe`, `overlapState`, and `verification`.

- [x] **Step 1: Import repo-native candidate families without inflating counts**

Record the 20 broad-catch-without-local-breadcrumb candidates and the 34 manual-prompt candidates as source-inspection queues. Record 48 cross-subsystem files over 1,000 lines only as prioritization metadata. Exclude the 16 generated-artifact deletes, 13 inactive duplicate quarantines, 700 context-purity evidence gaps, the external Supabase gap, and zero-risk test-tree rows from the active defect count.

- [x] **Step 2: Run the repo-native read-only source scan**

Run:

```powershell
'{"nodeRole":"desktop","root":".","requestId":"random-probe-100-source-scan","sessionId":"random-probe-100-20260827"}' | python scripts\awx_mcp_toolbox.py --input-json - source_scan
python scripts\awx_mcp_completion_audit.py --root .
```

Add only new, path-specific active-source findings; merge duplicate symptoms under the same root cause.

- [x] **Step 3: Probe a deterministic risk-stratified source sample**

Build a path list with `rg --files main/java main/resources app/src/main/java_clean app/src/main/resources`. Rank each normalized path by SHA-256 of `2026-08-27-random-probe-100|<path>`, then inspect candidates across distinct packages, biased toward controller -> workflow -> prompt -> retrieval -> provider -> response/persistence boundaries and the report's P1 catch sites. A sampled file contributes no issue unless a concrete failure mode and disconfirming probe are recorded.

- [x] **Step 4: Stop candidate intake at the defined threshold**

Stop when the ledger has 100 unique candidates or all decision-changing active-source queues are exhausted. Assign `VALIDATED`, `CANDIDATE`, `DUPLICATE`, `NOT_A_BUG`, or `EVIDENCE_NEEDED`; only `VALIDATED` items may enter a repair plan.

- [x] **Step 5: Verify the ledger classification**

Confirm that every counted row points to an active root, no generated/inactive/external-evidence row is counted as an active defect, no two rows share the same root cause and symbol, and priority is based on user impact, reachability, reproducibility, and blast radius rather than file size alone.

Observed: all decision-changing active-source queues were exhausted at 99 honest rows rather than inventing a 100th. The ledger has 99 unique IDs, zero duplicate IDs, and status counts `FIXED=25`, `VALIDATED=9`, `VALIDATED_SHARED_SEAM=1`, `CANDIDATE=22`, `CANDIDATE_ZOMBIE=5`, `HOLD=15`, and `NOT_A_BUG=22`.

### Task 3: Select and gate the first repair batch

**Files:**

- Create: `docs/superpowers/plans/2026-08-27-random-probe-100-batch-01.md`
- Modify: `data/agent-handoff/codex/report/random-probe-100-20260827-ledger.md`

**Interfaces:**

- Consumes: the highest-priority `VALIDATED` rows with exact source and test seams.
- Produces: one issue-specific Phase B plan and a stable source-edit decision; it does not itself authorize a write.

- [x] **Step 1: Choose one root cause with no more than three target files**

Re-read the exact source symbol, its callers, the closest working pattern, and its focused test surface. Reject inactive aliases and split unrelated findings into later batches.

- [x] **Step 2: Prove target ownership and preimages**

Run target-scoped `git status --short -- <paths>` and `git diff -- <paths>`, classify each path as clean, current-task-owned, or unreviewable overlap, and compute SHA-256 immediately before any eventual patch.

- [x] **Step 3: Write the issue-specific TDD plan**

The batch plan names the exact production path, exact test path, test method and expected RED failure, minimal GREEN change, focused Gradle command, and affected-boundary broad command. It contains no generic refactor or unrelated cleanup.

- [x] **Step 4: Freeze and adjudicate the source-edit preflight**

Freeze at most 20 redacted evidence rows, run exactly the three logical roles over the same snapshot, verify identical scenario IDs and stable A-B/B-A `APPLY`, then acquire a `desktop` source-edit lease. Any dirty overlap, branch ownership mismatch, changed preimage, score below 50, order instability, or missing verification yields lane-local `HOLD` and one next proof.

Observed: 17 issue-specific batch plans were executed. Every production edit used one frozen snapshot, stable A-B/B-A `APPLY`, unchanged preimages, and an owned source-edit lease; all leases were released.

### Task 4: Execute each approved Phase B repair plan

**Files:**

- Modify: only the exact source and test paths named by the approved batch plan.
- Modify: `data/agent-handoff/codex/report/random-probe-100-20260827-ledger.md`

**Interfaces:**

- Consumes: stable `APPLY`, an active source-edit lease, unchanged preimages, and one failing behavior test.
- Produces: one minimal verified fix or a precisely classified non-fix result.

- [x] **Step 1: Create and run one focused RED test**

Verify that it fails for the intended missing or broken behavior rather than compilation, setup, stale cache, or unrelated baseline failure.

- [x] **Step 2: Apply the minimum GREEN production change**

Change one root cause only, preserve prompt/provider/redaction/version contracts, and keep the cycle within three files unless the batch plan proves a smaller boundary is impossible.

- [x] **Step 3: Verify GREEN and the affected boundary**

Run the exact focused test, then `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and `compileJava -x test`. Add `:app:classes`, `bootJar -x test`, runtime, Browser, or Computer proof only when the changed surface requires it.

- [x] **Step 4: Release the lease and update the ledger**

Record commands, exit codes, changed paths and hashes, `FIXED` or `SKIP` state, residual evidence, and the next issue. Release only the lease owned by this task in a `finally`-equivalent cleanup path.

Observed: 25 rows reached behavior-level RED -> minimal GREEN -> focused boundary verification. Final full tests and report regeneration are recorded in the issue ledger; no commit, push, deploy, credential, database, or provider mutation was performed.

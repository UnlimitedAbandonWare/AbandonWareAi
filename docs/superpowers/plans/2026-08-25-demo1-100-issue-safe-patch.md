# Demo-1 100-Issue Safe Patch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Repository policy keeps every source write, integration decision, verification claim, and final disposition in the parent Codex task; delegated agents are bounded read-only evidence collectors/reviewers.

**Goal:** Give all 100 approved audit IDs one current, evidence-backed terminal disposition while applying the smallest RED-to-GREEN fixes in risk order within the nine-hour maximum budget.

**Architecture:** The work is split into eight independently testable wave plans. Each mutation cycle freezes current ownership and preimages, passes the repository three-way source gate, proves a focused behavioral RED, applies one root-cause patch, and runs focused then affected-boundary verification before updating a 100-row terminal ledger.

**Tech Stack:** Java 17, Spring Boot, Gradle Wrapper 8.7, JUnit 5, AssertJ, Mockito, Reactor/Spring MVC, Python 3 scorecard tests, Node browser-contract scripts, PowerShell, and the repository's host-isolated build controls.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- The authoritative inventory is the 100 IDs in the spec above. `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`, its terminal ledger, cohort plans, and execution journals describe a different audit. They may supply current evidence or reusable tests, but their rank dispositions never close an ID in this plan by substitution.
- Preserve all unrelated dirty-tree files and hunks. Re-read and hash every declared target immediately before a source patch.
- Root runtime owners are `main/java` and `main/resources`; `:app` owners are `app/src/main/java_clean` and `app/src/main/resources`. `app/src/main/java` remains inactive unless fresh Gradle evidence proves otherwise.
- Keep Java 17, the repository's current Spring Boot version, and every `dev.langchain4j` dependency exactly at `1.0.1`.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)` and preserve existing property names, secret flow, `openssl`, and `opnessl` keys.
- Add no production dependency. Do not stage, commit, push, merge, deploy, delete material data, mutate credentials/ACLs/databases/providers, or clean the dirty worktree.
- Before every independent application-source mutation cycle, use `$demo1-source-edit-three-way-preflight`: one frozen redacted snapshot, exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`, stable `APPLY`, source-owner lease, and immediate preimage recheck.
- Every changed behavior uses RED -> minimal implementation -> GREEN -> affected-boundary verification. A test that is already GREEN may support `NO_PATCH_NEEDED`; it must be mutation-sensitive and exercise the active owner.
- Terminal states are exactly `PATCHED`, `MERGED_WITH:<id>`, `NO_PATCH_NEEDED`, or `HOLD:<reason>`. `HOLD` remains lane-local and records one exact unblock action plus the repository-required HOLD fields.
- Keep raw prompts, responses, credentials, cookies, authorization headers, idempotency keys, attachment contents, exact private values, and provider bodies out of logs, traces, test reports, and the ledger.
- Browser and Computer are fresh proof lanes only for changed UI or Windows-runtime behavior. Delivery or rendering alone does not prove provider/model semantics.
- Duration is a maximum budget. Stop a lane on decisive proof, current no-op, failed preflight, missing authority/evidence, or unsafe overlap; do not relabel unfinished IDs as complete.

---

## Plan File Structure

- This file: dependency order, common gates, broad verification, and final ledger closure.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w1-durability.md`: IDs 5, 6, 7, 32, 44, 46, 47, 62, 63.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w2-ownership.md`: IDs 11, 12, 14, 15, 16, 19, 45, 50, 52, 57, 64, 65, 66, 82, 83.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w3-deadlines.md`: IDs 1, 2, 3, 4, 9, 10, 18, 21, 23, 29, 31, 33, 34, 35, 36, 37, 38, 39.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w4-rag-consistency.md`: IDs 8, 20, 22, 24, 25, 26, 27, 28, 53, 54, 60, 61, 76, 77.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w5-truthfulness.md`: IDs 13, 17, 30, 48, 49, 51, 55, 56, 78, 81, 84, 85.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w6-provider-edges.md`: IDs 40, 41, 42, 43, 58, 59, 79, 80.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w7-ui-scorecards.md`: IDs 67-75 and 86-88.
- `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w8-hotspots.md`: IDs 89-100.
- Create during execution: `docs/superpowers/evidence/2026-08-25-demo1-100-issue-safe-patch-ledger.md`, the only terminal disposition ledger for this approved spec.

The ledger contains one row for each ID 1-100.

---

### Task 0: Freeze the execution baseline and create the empty terminal ledger

**Files:**

- Read: `settings.gradle`, `build.gradle`, `app/build.gradle.kts`, `AGENTS.md` chain, PatchDrop inventory, source-edit lease state.
- Create: `docs/superpowers/evidence/2026-08-25-demo1-100-issue-safe-patch-ledger.md`.

**Interfaces:**

- Consumes: approved spec ID, branch/HEAD, active sourceSet map, lock/lease/PatchDrop evidence.
- Produces: immutable `baselineSnapshotHash`, `branch`, `head`, and a 100-row ledger with `OPEN` as a planning marker only. `OPEN` is replaced by a valid terminal state before completion.

- [x] **Step 1: Refresh repository and runtime gates**

  Run from `C:\AbandonWare\demo-1\demo-1\src`:

  ```powershell
  java -version
  .\gradlew.bat --version
  git branch --show-current
  git rev-parse HEAD
  git worktree list
  git status --short
  Test-Path -LiteralPath (git rev-parse --git-path index.lock)
  powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
  .\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon
  ```

  Expected: Java 17; index lock `False`; no active top-level PatchDrop patch; no overlapping source lease; purity/hygiene pass. A failure holds only the affected mutation lane.

- [x] **Step 2: Create the ledger with one row per ID**

  Use this exact schema:

  ```markdown
  | ID | Wave | State | Active owner | RED/characterization | GREEN/boundary proof | Changed files | Evidence or unblock action |
  |---:|:---:|---|---|---|---|---|---|
  | 1 | W3 | OPEN |  |  |  |  |  |
  ```

  Generate IDs 1-100 once, then validate missing/duplicate IDs with a PowerShell regex parser before any source mutation.

- [x] **Step 3: Record the baseline without exposing private data**

  Record counts, safe paths, hashes, sourceSet results, and the first blocker only. Do not copy raw `git status`, environment values, request bodies, or credentials into the ledger.

- [x] **Step 4: Checkpoint without Git mutation**

  Run `git diff --check -- docs/superpowers/specs docs/superpowers/plans docs/superpowers/evidence`. Do not stage or commit; commit authority was not granted.

---

### Task 1: Execute W1 durable storage and lifecycle

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w1-durability.md`.

**Interfaces:**

- Consumes: baseline ledger and current W1 target preimages.
- Produces: terminal dispositions for 5, 6, 7, 32, 44, 46, 47, 62, 63; recoverable file transitions; bounded attachment/archive/job retention.

- [x] **Step 1:** Execute every unchecked W1 task in order.
- [x] **Step 2:** Copy only fresh command outcomes and postimage hashes into the master ledger.
- [x] **Step 3:** Require W1 affected-boundary GREEN before beginning a W2 source write.

---

### Task 2: Execute W2 ownership and public mutation contracts

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w2-ownership.md`.

**Interfaces:**

- Consumes: W1 attachment lifecycle contract and current authenticated owner/session resolution.
- Produces: terminal dispositions for 11, 12, 14-16, 19, 45, 50, 52, 57, 64-66, 82, 83; principal-bound mutation and bounded public admission.

- [x] **Step 1:** Execute each W2 mutation cohort with a fresh three-way gate.
- [x] **Step 2:** Prove rejected requests perform zero storage/provider/database side effects.
- [x] **Step 3:** Update ledger rows and retain only hash/count/reason evidence.

---

### Task 3: Execute W3 deadlines, cancellation, and admission

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w3-deadlines.md`.

**Interfaces:**

- Consumes: `TimeBudgetContext`, existing shared executors, cancellation checkpoints, and lifecycle ownership contracts.
- Produces: terminal dispositions for 1-4, 9, 10, 18, 21, 23, 29, 31, 33-39; one monotonic deadline per operation and bounded admission.

- [x] **Step 1:** Execute W3 cohorts sequentially; never run two boot or executor-stress suites in parallel.
- [x] **Step 2:** Distinguish caller return from actual worker termination in every timeout ledger entry.
- [x] **Step 3:** Update rows only after focused and affected-boundary proof.

---

### Task 4: Execute W4 RAG, fusion, CFVM, and vector consistency

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w4-rag-consistency.md`.

**Interfaces:**

- Consumes: validated top-K/request budget, canonical DPP/fusion/CFVM owners, and federated store contracts.
- Produces: terminal dispositions for 8, 20, 22, 24-28, 53, 54, 60, 61, 76, 77; finite domain-preserving scores and bounded partial results.

- [x] **Step 1:** Execute W4 cohorts in file-owner order.
- [x] **Step 2:** Run `$demo1-subsystem-patch-directive` before any S01-S08 algorithm-body write.
- [x] **Step 3:** If a patch crosses two subsystem owners, run `$demo1-cross-subsystem-guard` before claiming completion.

---

### Task 5: Execute W5 memory, trace, and health truthfulness

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w5-truthfulness.md`.

**Interfaces:**

- Consumes: W2 owner boundary, `ChatRunRegistry`, memory mode, trace stores, and route health identity.
- Produces: terminal dispositions for 13, 17, 30, 48, 49, 51, 55, 56, 78, 81, 84, 85.

- [x] **Step 1:** Execute memory/session, debug/trace, and health cohorts separately.
- [x] **Step 2:** Prove no raw fingerprint/query/session value appears in public or durable evidence.
- [x] **Step 3:** Run the owner continuity and trusted-proxy tests in this W5 plan and update IDs 17 and 51 once.

---

### Task 6: Execute W6 provider edge contracts

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w6-provider-edges.md`.

**Interfaces:**

- Consumes: remaining request budget and provider-specific configured limits.
- Produces: terminal dispositions for 40-43, 58, 59, 79, 80; no provider substitution or raw provider-body exposure.

- [x] **Step 1:** Resolve the exact active OpenAI fallback owner for ID 58 before mutation. For ID 79, verify the current `ChannelRecipientController` page-to-offset call path immediately before its focused cycle.
- [x] **Step 2:** Execute provider tests with fakes/local HTTP fixtures only; do not call real providers.
- [x] **Step 3:** Record outbound-attempt evidence as `not_observed` unless a separately authorized wire canary is actually observed.

---

### Task 7: Execute W7 public UI and scorecard truthfulness

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w7-ui-scorecards.md`.

**Interfaces:**

- Consumes: structured Gradle/test evidence and redacted heartbeat state.
- Produces: terminal dispositions for 67-75, 86-88; no undeclared artifact writes; browser geometry evidence for changed CSS.

- [x] **Step 1:** Execute scorecard and heartbeat cohorts with isolated temp inputs/outputs.
- [x] **Step 2:** If CSS changes, run static contracts then one fresh browser viewport geometry check.
- [x] **Step 3:** Keep provider/model/MCP/environment topology out of public heartbeat output.

---

### Task 8: Execute W8 structural hotspot dispositions

**Files:** Read and execute `docs/superpowers/plans/2026-08-25-demo1-100-issue-safe-patch-w8-hotspots.md`.

**Interfaces:**

- Consumes: W1-W7 actual touched symbols and fresh structural metrics.
- Produces: terminal dispositions for 89-100 without wholesale rewrites.

- [x] **Step 1:** Link each structural ID to a concrete W1-W7 extraction, a disproving current-state check, or an exact lane-local HOLD.
- [x] **Step 2:** Reject LOC-only refactors; require a measured responsibility/branch/catch/fan-in reduction for `PATCHED`.
- [x] **Step 3:** Run cross-subsystem verification only for actual changed boundaries.

---

### Task 9: Run the broad verification ladder

**Files:** All changed production/test/resource/script/UI files listed in the ledger.

**Interfaces:**

- Consumes: every wave's focused GREEN and final target set.
- Produces: one fresh build/test/package integrity packet; no provider/database success claim.

- [x] **Step 1: Configure isolated Desktop build locations for this verification window**

  ```powershell
  $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
  $env:AWX_BUILD_HOST_ID = 'desktop-priority100-safe-patch'
  $env:GRADLE_USER_HOME = 'C:\AbandonWare\gradle-user-home\desktop-priority100-safe-patch'
  $projectCache = 'C:\AbandonWare\gradle-project-cache\desktop-priority100-safe-patch'
  ```

- [x] **Step 2: Run static and compile boundaries**

  ```powershell
  .\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes -x test --no-daemon --project-cache-dir $projectCache
  ```

  Expected: all tasks pass; sourceSet output still identifies the same active owners.

- [x] **Step 3: Run a fresh fail-fast full test refresh**

  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify_full_test_refresh.ps1
  ```

  Record exact test/failure/error/skipped totals from current XML, not console-summary memory.

- [x] **Step 4: Package and inspect the active artifact**

  ```powershell
  .\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $projectCache
  ```

  Inspect only the current `build\desktop-priority100-safe-patch\libs` artifact and record its SHA-256/timestamp/class presence.

- [x] **Step 5: Run integrity checks**

  ```powershell
  git diff --check
  powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
  ```

  Run a count-only changed-file secret scan and inspect the exact final diff. Do not stage or commit.

---

### Task 10: Close the 100-row ledger and hand off

**Files:**

- Modify: `docs/superpowers/evidence/2026-08-25-demo1-100-issue-safe-patch-ledger.md`.

**Interfaces:**

- Consumes: current source symbols, RED/GREEN commands, hashes, broad proof, and browser/runtime evidence where required.
- Produces: exactly 100 terminal rows and an honest goal status.

- [x] **Step 1: Validate terminal state completeness**

  Parse the table and assert IDs 1-100 appear exactly once; no `OPEN`, `SKIP`, empty state, duplicate, or missing ID remains. Validate every `MERGED_WITH:<id>` target exists and is `PATCHED` or `NO_PATCH_NEEDED` with the shared root cause explained.

- [x] **Step 2: Validate proof completeness**

  Every `PATCHED` row names a mutation-sensitive RED, focused GREEN, affected-boundary result, changed files, and postimage hashes. Every `NO_PATCH_NEEDED` row names current active-owner proof. Every HOLD names `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, `repositoryWideHold`, and one unblock action.

- [x] **Step 3: Report without overclaiming**

  Separate local build/runtime/browser results from provider/database/wire evidence. If any row remains HOLD, report the goal as incomplete with the exact lane-local blocker; do not mark the 100-item objective complete.

- [x] **Step 4: Final no-commit checkpoint**

  Re-run ledger ID validation, `git diff --check`, changed-file secret count, and final diff inspection. Leave all files unstaged and uncommitted.

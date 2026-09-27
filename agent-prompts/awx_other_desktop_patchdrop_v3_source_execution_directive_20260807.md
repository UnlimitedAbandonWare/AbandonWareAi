# AWX Other Desktop PatchDrop v3 Source Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use
> `superpowers:executing-plans` to execute this plan task-by-task. Use
> `superpowers:test-driven-development` for the selected work unit,
> `superpowers:systematic-debugging` on a failing command, and
> `superpowers:verification-before-completion` before reporting. Do not run
> concurrent source writers.

**Directive ID:** `AWX-OTHER-DESKTOP-PATCHDROP-V3-SOURCE-EXEC-20260807`

**Goal:** On a different Windows Desktop, use a dedicated local clone or
worktree to repair exactly one currently proven demo-1 source contract, verify
it with RED/GREEN evidence, and return one cumulative PatchDrop v3 producer
bundle without editing the canonical Desktop source.

**Architecture:** The other Desktop is a `desktop` PatchDrop producer, not the
canonical source owner. It may modify only its own isolated local worktree. One
run selects one causal work unit, freezes a three-way EvidenceSnapshot, proves
a focused RED, applies the smallest patch, proves GREEN, and emits a nested
`desktop` v3 bundle. The canonical Desktop remains the only final consumer and
verifier.

**Tech stack:** Windows PowerShell, Git worktrees, Gradle Wrapper, Java/Spring
Boot, Node.js for the legacy chat contract, repository-owned Safe Patch and
PatchDrop scripts.

## Global Constraints

- Read this file and the closest `AGENTS.md` completely before taking action.
- Deliver this directive through the Codex task attachment or pasted prompt.
  Do not place the directive itself inside the producer Git root, where it
  would contaminate clean-status and PatchDrop pathspec evidence.
- Current repository files and command output outrank this directive, the
  upstream consolidated document, Notebook evidence, review packets, and
  memory.
- One run may modify exactly one work unit from the allowlist below.
- This invocation explicitly authorizes one producer-local modification after
  the directive's stable three-way `APPLY` and focused RED gates pass. It never
  authorizes a write to the canonical Desktop root.
- Run Tasks 1 through 8 in one persistent PowerShell process so that frozen
  paths, hashes, selected-contract variables, guard state, and exit codes remain
  bound. If that process or session is lost, abandon the partial run and restart
  at Task 1; do not reconstruct variables from memory.
- Use a local fixed-disk clone/worktree. Never edit a UNC path, mapped network
  drive, SMB/NAS mount, shared `WinSrc`, or the canonical root
  `C:\AbandonWare\demo-1\demo-1\src`.
- Do not relabel a physical Desktop producer as `notebook` or `macmini` merely
  to satisfy a promotion script.
- Do not stage, commit, push, open a PR, deploy, modify ACLs, delete original
  directives, or persist environment variables. Delivery is PatchDrop only.
- Java and Spring resource edits may touch only Gradle-proven active sourceSets:
  root `main/java`,
  `main/resources`, `src/test/java`, `src/test/resources`; and `:app`
  `app/src/main/java_clean`, `app/src/main/resources` when a selected unit
  actually needs them. A script/governance work unit may instead touch only its
  explicitly named non-Java allowlist paths.
- Treat `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`,
  backups, archives, generated outputs, `.gradle`, `build`, `node_modules`, and
  `.next` as inactive or forbidden unless live Gradle evidence proves
  otherwise.
- Keep every `dev.langchain4j` dependency exactly at `1.0.1`.
- Keep final RAG prompt assembly behind
  `PromptBuilder.build(PromptContext)` or the current equivalent boundary.
- Do not add duplicate orchestrators, wrappers, helpers, routes, components,
  CVaR implementations, prompt assembly paths, or mandatory verifier calls.
- Do not touch `apikey.txt`, `apikey.ps1`, `.env*`, shell profiles, raw secret
  setup, authorization headers, cookies, DB URLs, or `openssl`/`opnessl`
  names and values.
- Missing optional credentials must fail soft with a redacted reason and no
  outbound call. Never fabricate provider, Browser, database, build, boot, or
  runtime proof.
- Browser, Computer, Supabase, Notebook, and producer-host proof is supporting
  evidence. It cannot establish canonical Desktop PASS.
- A passing producer result is `ACTIVE`, never `PASS`. `PASS` requires later
  canonical Desktop intake, apply, focused verification, governance checks,
  and reverse-apply proof.
- Bind one `selectedContractId` and exactly one `redCommand` per run. A work
  unit label is not itself a causal contract and does not authorize every path
  listed under that unit.

---

## Upstream Contract Snapshot

This directive is self-contained. The upstream file is provenance, not a
runtime dependency.

```yaml
upstreamProgramId: awx-desktop-notebook-consolidated-source-20260806
upstreamRefreshId: awx-desktop-notebook-consolidated-source-20260807-r2
upstreamRelativePath: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md
upstreamSha256: F22210592BC971D44C8917D10973029467257D18D70C0770256137E242CB7A54
historicallyIncorporatedInputs: 22
currentlyPresentAtPublication: 13
currentlyReadableAtPublication: 11
aclHeldAtPublication: 2
historicalPathsMissingAtPublication: 9
reviewPacketsIntegrated: 9
sourceEditsPerformedByUpstreamRefresh: 0
approvedRoute: other-desktop-local-worktree-to-patchdrop-v3
```

The upstream snapshot found these live-call semantics. Re-prove them locally;
do not force them when the producer checkout differs:

```yaml
auxiliarySamplingDefault: zero-to-two-candidates
auxiliarySamplingAlternativeSupportTriad: zero-to-three-candidates-when-enabled
ensembleJudgeCalls: zero
primaryFinalLogicalStage: one-on-main-non-short-path
primaryFinalPhysicalAttempts: one-to-many-unless-strict-three-role-mode
finalVerifierCalls: zero-to-one-policy-gated
finalPostprocessLogicalStage: one
```

Material conflict decisions:

- HTTP rollback v2 wins over v1: explicit `BaseUrl` wins; inherited public URL
  is allowed only in assume-running mode; local boot defaults to loopback HTTP
  and must restore process environment state.
- A verifier is policy-gated `0..1`; never implement unconditional
  exactly-once verification.
- Logical primary generation and physical provider attempts are distinct.
- Supabase is read-only, project-scoped, demand-driven supporting evidence by
  default. Missing connectivity does not block an unrelated local repair.
- Two ACL-protected June 5 directives remain held. Do not reconstruct them or
  change ACLs.
- An older `codex/postprocess-atomic` worktree was stale and dirty; it is not
  patch authority.

---

## Completion States

Use exactly one terminal state:

| State | Meaning |
|---|---|
| `NO_PATCH_NEEDED` | Every eligible unit was probed and none has a current causal focused RED; no empty patch is produced. |
| `ACTIVE` | One local work-unit patch passed producer RED/GREEN and bundle gates; canonical Desktop proof remains pending. |
| `BLOCKED` | A safety, ownership, sourceSet, lineage, verification, secret, or bundle gate failed. |
| `REJECTED` | Redaction failed or an undeclared source write occurred. |

Never output `PASS` from the producer host.

---

## Work-Unit Allowlist And Selection Order

Evaluate in this order. Select the first unit with all target boundaries
present, an executable verifier, and either a currently reproducible focused
failure or source-backed evidence for one precise test that can be made RED.
This order is the current user's approved producer scheduling override, not an
ordering inferred from the upstream attachment: ensemble -> chat identity ->
HTTP v2 -> chat parity -> Supabase routing -> security. If a unit is already
semantically GREEN, record `unitDisposition=no_patch_needed` for that unit and
continue to the next one. Reserve terminal `NO_PATCH_NEEDED` for the case where
every eligible unit has been examined and none supplies a causal focused RED.
Stop after the first selected unit is patched and bundled.

### WU-E10 — Ensemble Evidence Contract

**Priority:** 1

**Causal boundary:** auxiliary SUPPORT/FALSIFY sampling, code-computed
grounding, PromptBuilder dossier transport, primary final answer, and optional
verifier.

**Permitted production files:**

- `main/java/com/example/lms/service/ChatWorkflow.java`
- `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java`
- `main/java/com/example/lms/ensemble/DualHypothesisEvidenceScorer.java` only
  when the frozen live call path proves the scoring-formula contract is causal
- `main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java`
- `main/java/com/example/lms/prompt/PromptBuilder.java`
- `main/java/com/example/lms/prompt/StandardPromptBuilder.java`

**Permitted tests:**

- `src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java`
- `src/test/java/com/example/lms/ensemble/DualHypothesisEvidenceScorerTest.java`
  only with the conditional scorer owner above
- `src/test/java/com/example/lms/ensemble/EnsembleFinalAnswerServiceTest.java`
- `src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java`
- `src/test/java/com/example/lms/service/ChatWorkflowStrictSingleAttemptHttpIntegrationTest.java`
- `src/test/java/com/example/lms/prompt/PromptBuilderBoundaryTest.java`

**Required behavior only when a focused RED proves it missing:**

- SUPPORT sampling uses `temperature=0.85` and `topP=0.9`.
- FALSIFY sampling uses `temperature=0.0` and `topP=0.4`.
- Code, not the model, computes:

  ```text
  evidenceRate = claimsWithValidEvidenceIds / totalVerifiableClaims
  groundingScore = 0.70 * evidenceRate
                 + 0.20 * sourceDiversity
                 + 0.10 * (1 - contradictionRate)
  ```

- A candidate with no evidence scores `0`.
- Nonexistent evidence IDs are invalid and do not raise the score.
- A score gap below `0.05` remains unresolved; do not force a winner.
- Both dossiers and scores reach the normal primary model through the canonical
  PromptBuilder/PromptContext boundary.
- The old judge is rollback-only. Do not add a live ensemble-judge call.
- Do not convert the current default dual/optional-triad topology into a fixed
  two-call physical contract.
- Do not convert the policy-gated verifier into an unconditional call.

**Focused commands:**

```powershell
.\gradlew.bat test --tests com.example.lms.ensemble.DiverseSamplingOrchestratorTest --tests com.example.lms.ensemble.DualHypothesisEvidenceScorerTest --tests com.example.lms.ensemble.EnsembleFinalAnswerServiceTest --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'wu-e10-sampling-or-final-service-test-failed' }
.\gradlew.bat test --tests com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest --tests com.example.lms.service.ChatWorkflowStrictSingleAttemptHttpIntegrationTest --tests com.example.lms.prompt.PromptBuilderBoundaryTest --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'wu-e10-workflow-or-prompt-test-failed' }
```

### WU-C20 — Chat Restore Identity, No-Op First

**Priority:** 2

**Permitted production file:**

- `main/resources/static/js/chat.js`

**Permitted test:**

- `scripts/chat_ui_stream_contract_tests.js`

**Required behavior:** a restored or hydrated session preserves the
backend-proven session identity through the existing stream contract. Do not
edit HTML, CSS, Java controllers, services, repositories, DB, or Supabase for
this unit.

**Focused command:**

```powershell
node scripts\chat_ui_stream_contract_tests.js
if ($LASTEXITCODE -ne 0) { throw 'wu-c20-stream-contract-test-failed' }
```

If this command passes and the named restored-session assertion exists, record
`unitDisposition=no_patch_needed` for WU-C20 and continue the priority scan.

### WU-H30 — Local HTTP Smoke Rollback v2

**Priority:** 3

**Permitted script/test pairs:**

- `scripts/smoke_websoak_kpi_provider_disabled.ps1`
- `scripts/smoke_websoak_kpi_provider_disabled_tests.ps1`
- `scripts/smoke_chat_debug_events_readback.ps1`
- `scripts/smoke_chat_debug_events_readback_tests.ps1`
- `scripts/smoke_chat_debug_fx_sse.ps1`
- `scripts/smoke_chat_debug_fx_sse_tests.ps1`
- `scripts/trace_memory_recovery_synthetic_smoke.ps1`
- `scripts/trace_memory_recovery_synthetic_smoke_tests.ps1`

**Required behavior:** explicit `BaseUrl` wins; inherited public URL is usable
only with assume-running; a locally started app uses
`http://127.0.0.1:$Port`; non-loopback effective URL fails before measurement;
all touched environment names are restored in process scope.

Select exactly one script/test pair for this run. The current-source probe found
the first precise candidate in
`smoke_websoak_kpi_provider_disabled.ps1` plus its test: make the resolver accept
the assume-running boolean, pass `[bool]$AssumeRunning`, consult inherited
`APP_PUBLIC_BASE_URL`/`PUBLIC_BASE_URL` only in assume-running mode, keep local
loopback overrides inside `if (-not $AssumeRunning)`, and reject non-loopback or
non-HTTP effective URLs before emitting a loopback breadcrumb or measuring.
Re-prove this source-backed gap in the producer checkout; do not select it if
the checkout already satisfies the contract.

Do not include `scripts/smoke_agent_mariadb_context.ps1` unless a separate
focused RED proves that it starts a local app and has the identical leak.

**Focused commands:**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_websoak_kpi_provider_disabled_tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'wu-h30-websoak-test-failed' }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_chat_debug_events_readback_tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'wu-h30-debug-readback-test-failed' }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_chat_debug_fx_sse_tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'wu-h30-debug-sse-test-failed' }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trace_memory_recovery_synthetic_smoke_tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'wu-h30-trace-recovery-test-failed' }
```

Run only the command for the one selected pair; the list is a candidate menu,
not permission to modify or certify all four pairs.

### WU-C21 — Main Chat UI/Controller Parity

**Priority:** 4

**Dependency:** WU-C20 has `unitDisposition=no_patch_needed` or is already
integrated in the local baseline.

**Primary production files:**

- `main/resources/static/js/chat.js`
- `main/resources/templates/chat-ui.html`
- `main/resources/static/css/chat-style.css`

**Conditional backend files, only after a backend-specific RED:**

- `main/java/com/example/lms/api/ChatApiController.java`
- `main/java/com/example/lms/dto/ChatStreamEvent.java`

**Focused tests:**

- `scripts/chat_ui_stream_contract_tests.js`
- `scripts/chat_ui_browser_fault_fixture_tests.js`
- `scripts/chat_ui_view_layer_contract_tests.js`
- `src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java`
- `src/test/java/com/example/lms/api/ChatApiControllerStateSecurityTest.java`

**Required behavior:** preserve request/session identity, robust SSE framing,
explicit user cancel, typed retry/failure states, session/evidence display, and
redacted diagnostics. Do not replace the richer Spring UI wholesale. Browser
proof is supporting on the producer; canonical Desktop must repeat it after
apply.

Choose exactly one target-bound substage and use an existing fixture; do not add
a generic new fixture merely to manufacture RED:

- W1: SSE event bytes are bounded at `maxEventUtf8Bytes=131072`; overflow ends
  as a terminal rawless `stream_failed` event and is never silently truncated.
- W2: decoded failure text is bounded at `maxFailureDecodedChars=1200`, parsed
  once, cancelled once, exposes no raw body, and maps only fixed categorical
  failure reasons.
- W3: session list/restore uses the canonical endpoint and correlation identity,
  retains at most 12 sessions, reports categorical failures, and commits state
  atomically only for the current generation.

Select W1 before W2 when both are missing. Before and after any C21 patch, all
three Node fixtures above must pass; a missing fixture in a clean producer
checkout is `evidence_needed`, not permission to recreate it from memory.

**Focused commands:**

```powershell
node scripts\chat_ui_stream_contract_tests.js
if ($LASTEXITCODE -ne 0) { throw 'wu-c21-stream-contract-test-failed' }
node scripts\chat_ui_browser_fault_fixture_tests.js
if ($LASTEXITCODE -ne 0) { throw 'wu-c21-browser-fault-fixture-test-failed' }
node scripts\chat_ui_view_layer_contract_tests.js
if ($LASTEXITCODE -ne 0) { throw 'wu-c21-view-layer-contract-test-failed' }
.\gradlew.bat test --tests com.example.lms.api.ChatApiControllerCancelTest --tests com.example.lms.api.ChatApiControllerStateSecurityTest --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'wu-c21-controller-test-failed' }
```

### WU-S50 — Supabase Read-Only Default Routing

**Priority:** 5

**Initial characterization/RED files:**

- `scripts/test_awx_mcp_toolbox.py`
- `.mcp.json` only when it is missing, invalid JSON, or structurally lacks the
  existing read-only feature-scoped server/project-ref template

**Conditional implementation owners, only after current call-path proof and a
focused RED identify one owner:**

- `scripts/goal_next_auto.ps1`
- `scripts/goal_next_auto_tests.ps1`
- `scripts/awx_mcp_completion_audit.py`
- `scripts/source_health_scorecard.py`
- `scripts/test_source_health_scorecard.py`
- `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`

**Required behavior:** missing Supabase connectivity remains
`supporting_evidence_needed` in default local runs. It may become a primary
blocker only for explicit require-Supabase mode or a Supabase-touching source
task. Do not create clients, migrations, policies, grants, schema changes, or
credentials.

The current-source RED candidate is routing, not connectivity: default
`awx_mcp_completion_audit.py` must place the Supabase project-ref item in
`supportingEvidenceNeeded`; explicit require-Supabase mode promotes that same
item to primary `evidenceNeeded`. Imported external proof is usable only when
project identity and collection binding match. A missing CLI is optional after
complete authenticated MCP evidence. If network-probe skip/advisor inputs are
consumed, their values must be represented in the manifest contract. Live
policy semantics remain `evidence_needed:supabase_policy_semantics` unless
project-scoped read-only proof actually exists.

**Focused commands:**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto_tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'wu-s50-goal-router-test-failed' }
python -X utf8 scripts\test_awx_mcp_toolbox.py
if ($LASTEXITCODE -ne 0) { throw 'wu-s50-mcp-toolbox-test-failed' }
python -X utf8 scripts\test_source_health_scorecard.py
if ($LASTEXITCODE -ne 0) { throw 'wu-s50-source-health-test-failed' }
```

### WU-S40 — Guardrail And Security Phases

**Priority:** 6

Select this only when the guardrail negative test itself fails. Phase 0 must be
GREEN before any Java/security edit.

**Phase 0 editable files:**

- `scripts/awx_mcp_toolbox_tests.ps1`
- `scripts/test_awx_mcp_toolbox.py` only when its current test owns the gap

`__patch_drop__/source_edit_session.ps1` and
`__patch_drop__/source_edit_lease_contract.ps1` are required guard
infrastructure, not WU-S40 patch targets.

**Conditional Java files:**

- `main/java/com/example/lms/config/AppSecurityConfig.java`
- `main/java/com/example/lms/config/CustomSecurityConfig.java`
- `main/java/com/example/lms/security/AdminTokenGuardInterceptor.java`

Any Java security phase requires a separate explicit user authorization naming
the phase plus its exact focused RED. A Phase 0 failure alone never supplies
that authorization.

**Focused tests:**

- `src/test/java/com/example/lms/config/AppSecurityConfigContractTest.java`
- `src/test/java/com/example/lms/config/CustomSecurityConfigContractTest.java`
- `src/test/java/com/example/lms/config/ForceHttpsSecurityBoundaryTest.java`
- `src/test/java/com/example/lms/boot/RuntimeConfigGuardTest.java`
- `src/test/java/com/example/lms/api/KakaoOAuthControllerTest.java`

**Focused commands:**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\awx_mcp_toolbox_tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'wu-s40-phase-zero-test-failed' }
.\gradlew.bat test --tests com.example.lms.config.AppSecurityConfigContractTest --tests com.example.lms.config.CustomSecurityConfigContractTest --tests com.example.lms.config.ForceHttpsSecurityBoundaryTest --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'wu-s40-java-security-test-failed' }
```

Do not broaden a Phase 0 guard failure into login, HTTPS, diagnostics, runtime,
OAuth, or webhook changes in the same run.

### WU-D60 — Deferred Families

**Priority:** 7

Do not auto-select this aggregate. It represents separate families:

- RAG tail/counter-evidence/web tooling
- agent code-evidence gate
- risk-utility triad
- dynamic prompt assembly
- Next BFF discovery

Select one family only when the user separately names it and the producer has
its exact current directive and target/test contract. A real Next
`package.json` plus `next.config.*` must be proven before creating any Next
file. Otherwise return `BLOCKED` with one exact evidence action.

---

## Task 1: Establish A Local Producer Root

**Files:** no source files may change in this task.

**Delivery prerequisite:** alongside this directive, the sender must provide a
read-only helper-kit directory outside every Git/source root. Bind its absolute
path to `$HelperKitRoot` before starting. The kit contains
`producer_bundle.ps1`, `source_edit_session.ps1`, and
`source_edit_lease_contract.ps1`; absence or hash drift is a fail-closed
`evidence_needed`, never permission to copy an unverified helper into the
producer worktree.

- [ ] **Step 1: Resolve the current root and reject shared/canonical paths.**

```powershell
$ErrorActionPreference = 'Stop'
$RunStartedUtc = [DateTimeOffset]::UtcNow
$RunDeadlineUtc = $RunStartedUtc.AddHours(9)
function Assert-RunWithinBudget {
    if ([DateTimeOffset]::UtcNow -ge $RunDeadlineUtc) { throw 'nine-hour-run-budget-exhausted' }
}
$ProducerRoot = (Resolve-Path -LiteralPath '.').Path
$CanonicalLiteral = 'C:\AbandonWare\demo-1\demo-1\src'
if ($ProducerRoot.TrimEnd('\') -ieq $CanonicalLiteral.TrimEnd('\')) {
    throw 'smb-conflict-risk: producer root equals canonical Desktop root'
}
if ($ProducerRoot.StartsWith('\\')) {
    throw 'smb-conflict-risk: UNC producer root is forbidden'
}
$ProducerDrive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($ProducerRoot))
if ($ProducerDrive.DriveType -eq [IO.DriveType]::Network) {
    throw 'smb-conflict-risk: mapped network producer root is forbidden'
}
$GitTop = (& git -C $ProducerRoot rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($GitTop)) {
    throw 'evidence_needed: producer Git top-level unavailable'
}
if ([IO.Path]::GetFullPath($GitTop).TrimEnd('\') -ine $ProducerRoot.TrimEnd('\')) {
    throw 'producer-root-does-not-equal-git-top-level'
}
```

- [ ] **Step 2: Prove Git ownership and a clean starting point.**

```powershell
$InitialBranch = (& git -C $ProducerRoot branch --show-current).Trim()
if ($LASTEXITCODE -ne 0) { throw 'git-branch-read-failed' }
$InitialStatus = @(& git -C $ProducerRoot status --short)
if ($LASTEXITCODE -ne 0) { throw 'git-status-read-failed' }
$InitialWorktrees = @(& git -C $ProducerRoot worktree list)
if ($LASTEXITCODE -ne 0) { throw 'git-worktree-list-failed' }
$IndexLockPath = (git rev-parse --git-path index.lock).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($IndexLockPath)) {
    throw 'git-index-lock-path-read-failed'
}
if (-not [IO.Path]::IsPathRooted($IndexLockPath)) {
    $IndexLockPath = [IO.Path]::GetFullPath((Join-Path $ProducerRoot $IndexLockPath))
}
if (Test-Path -LiteralPath $IndexLockPath) {
    throw 'index-lock-conflict'
}
```

Expected: the Git top-level equals `$ProducerRoot`; no pre-existing source or
test changes exist. If the checkout is dirty, do not clean/reset it. Create a
new sibling worktree from the current HEAD:

```powershell
$BaseRoot = $ProducerRoot
$BaseHead = (git -C $BaseRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $BaseHead -notmatch '^[0-9a-fA-F]{40,64}$') {
    throw 'base-head-read-failed'
}
$WorktreeParent = Split-Path -Parent $BaseRoot
$WorktreeRoot = Join-Path $WorktreeParent 'awx-other-desktop-producer-20260807'
if (Test-Path -LiteralPath $WorktreeRoot) {
    throw 'worktree-overlap: dedicated producer path already exists'
}
git -C $BaseRoot worktree add --detach $WorktreeRoot $BaseHead
if ($LASTEXITCODE -ne 0) { throw 'dedicated-worktree-create-failed' }
Set-Location -LiteralPath $WorktreeRoot
$ProducerRoot = (Resolve-Path -LiteralPath '.').Path
$GitTop = (& git -C $ProducerRoot rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($GitTop).TrimEnd('\') -ine $ProducerRoot.TrimEnd('\')) {
    throw 'dedicated-worktree-root-proof-failed'
}
```

Execute this creation block only when `$InitialStatus.Count -ne 0`. A clean,
dedicated local clone/worktree may remain in place; a dirty root must be
replaced, never cleaned in place.

- [ ] **Step 3: Run the repository-owned no-write harness.**

```powershell
$ExpectedHelperHashes = [ordered]@{
    'producer_bundle.ps1' = 'E0B24ADE63322140E52769BACFF3E2B9D176B11B2D2349C257DA9519CFF7C7FB'
    'source_edit_session.ps1' = '3C0310026EB4F3353F3307F8A6A8770C02185A299E35560CA3EFAC5AA56066A1'
    'source_edit_lease_contract.ps1' = '827CD6AC6073BFCC430E454B25B9F9EBF0983B6507A6D9E083976869F50BC0DA'
}
if ([string]::IsNullOrWhiteSpace([string]$HelperKitRoot)) {
    throw 'evidence_needed: external helper-kit path was not bound'
}
$HelperKitRoot = (Resolve-Path -LiteralPath $HelperKitRoot).Path
$HelperKitDrive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($HelperKitRoot))
if ($HelperKitRoot.StartsWith('\\') -or $HelperKitDrive.DriveType -eq [IO.DriveType]::Network) {
    throw 'helper-kit-must-be-local-fixed-disk'
}
$HelperKitPrefix = $HelperKitRoot.TrimEnd('\') + '\'
if ($HelperKitRoot.TrimEnd('\') -ieq $ProducerRoot.TrimEnd('\') -or
    $HelperKitRoot.TrimEnd('\') -ieq $CanonicalLiteral.TrimEnd('\') -or
    $HelperKitPrefix.StartsWith(($ProducerRoot.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase) -or
    $HelperKitPrefix.StartsWith(($CanonicalLiteral.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'helper-kit-must-remain-outside-source-roots'
}
$HelperGitProbe = @(& git -C $HelperKitRoot rev-parse --is-inside-work-tree 2>$null)
if ($LASTEXITCODE -eq 0 -and ($HelperGitProbe -join '').Trim() -ieq 'true') {
    throw 'helper-kit-must-remain-outside-every-git-worktree'
}
foreach ($HelperName in $ExpectedHelperHashes.Keys) {
    $HelperPath = Join-Path $HelperKitRoot $HelperName
    if (-not (Test-Path -LiteralPath $HelperPath -PathType Leaf)) {
        throw "evidence_needed: helper missing: $HelperName"
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $HelperPath).Hash -cne $ExpectedHelperHashes[$HelperName]) {
        throw "helper-sha256-mismatch: $HelperName"
    }
}
$ProducerBundleScript = Join-Path $HelperKitRoot 'producer_bundle.ps1'
$SourceEditSessionScript = Join-Path $HelperKitRoot 'source_edit_session.ps1'
$HarnessPath = Join-Path $ProducerRoot 'scripts\desktop_safe_patch_harness.ps1'
if (-not (Test-Path -LiteralPath $HarnessPath -PathType Leaf)) {
    throw 'evidence_needed: scripts/desktop_safe_patch_harness.ps1 missing'
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $HarnessPath).Hash -cne '34635325420782E867FF748C0109A25ADA7D191495F12235AD0994730218E6DC') {
    throw 'safe-patch-harness-sha256-mismatch'
}
$HarnessOutput = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $HarnessPath -Root $ProducerRoot -NoWrite -DeepScan 2>&1)
$HarnessExit = $LASTEXITCODE
$JsonMarker = [Array]::IndexOf([string[]]$HarnessOutput, '## JSON')
if ($HarnessExit -ne 0 -or $JsonMarker -lt 0 -or $JsonMarker -ge ($HarnessOutput.Count - 1)) {
    throw 'safe-patch-harness-output-invalid'
}
$HarnessJson = ($HarnessOutput[($JsonMarker + 1)..($HarnessOutput.Count - 1)] -join "`n") | ConvertFrom-Json
$HarnessBlocks = @($HarnessJson.findings | Where-Object { $_.severity -eq 'BLOCK' })
$HarnessWarnings = @($HarnessJson.findings | Where-Object { $_.severity -eq 'WARN' })
$HarnessEvidenceNeeded = @($HarnessJson.evidence_needed)
if ($HarnessBlocks.Count -ne 0 -or $HarnessEvidenceNeeded.Count -ne 0 -or [int]$HarnessJson.observations.secretPatternHits -ne 0) {
    throw 'safe-patch-harness-gate-failed'
}
```

Stop on missing Git, Java, wrapper, sourceSet proof, PatchDrop ambiguity,
secret hits, or LangChain4j drift. Record count-only dispositions for every
harness WARN; a WARN never authorizes a source edit. Do not guess around the
harness, whose process exit code alone is not a verdict.

---

## Task 2: Freeze Source And Build Authority

**Files:** no source files may change in this task.

- [ ] **Step 1: Configure process-local, host-isolated Gradle output.**

```powershell
$env:AWX_AGENT_HOST = 'other-desktop-producer'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'other-desktop-producer'
$CacheBase = if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    [IO.Path]::GetTempPath()
} else {
    $env:LOCALAPPDATA
}
$ProducerGradleUserHome = Join-Path $CacheBase 'awx-gradle-other-desktop-producer'
$env:GRADLE_USER_HOME = $ProducerGradleUserHome
$ProjectCache = Join-Path $CacheBase 'awx-project-cache-other-desktop-producer'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null
```

- [ ] **Step 2: Prove projects, sourceSets, and dependency purity.**

```powershell
.\gradlew.bat projects --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'gradle-projects-failed' }
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'source-governance-failed' }
$GradleMetadataFiles = @('build.gradle.kts','app/build.gradle.kts','settings.gradle','settings.gradle.kts') |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
rg -n 'srcDirs|main/java|main/resources|java_clean' -- $GradleMetadataFiles
if ($LASTEXITCODE -ne 0) { throw 'active-sourceset-text-proof-failed' }
```

- [ ] **Step 3: Record lineage and candidate-path existence.**

Record, without raw environment values:

```text
producerRoot
gitHead
branch
gitStatusCount
activeSourceSets
candidateFileExistence
candidateFileSha256
indexLock
PatchDrop top-level patch count
secretPatternHitCount
```

Do not treat a matching Git HEAD as proof that dirty canonical target bytes are
identical. Canonical Desktop must compare the bundle preimages later.

---

## Task 3: Select Exactly One Testable Work Unit

**Files:** no source files may change in this task.

Publication-time live-source characterization (supporting only; re-run it in
the producer checkout):

```yaml
WU-E10: focused sampling/final-service and workflow/prompt suites GREEN; current formula owner is DualHypothesisEvidenceScorer
WU-C20: stream contract GREEN with restored-session identity assertion present
WU-H30: all four legacy static tests GREEN but do not assert v2 AssumeRunning URL semantics; first precise RED candidate is the websoak pair
WU-C21: required Node fixtures exist in the publication checkout but are absent from clean HEAD, so a clean producer must receive them through proven lineage or return evidence_needed
WU-S50: local MCP config is valid/read-only but default completion audit currently leaves the project-ref item in primary evidence_needed
SupabaseLiveProof: unavailable; connector not connected, policy semantics evidence_needed
```

- [ ] **Step 1: Probe units in the declared priority order.**

Run each existing focused verifier without editing. Inspect only the matching
production/test seam. A passing test is not enough when the required semantic
assertion is absent; in that case record the exact assertion that would expose
the source-backed gap. Unrelated build, fixture, or dependency failures do not
authorize the unit.

- [ ] **Step 2: Select the first causal, testable gap.**

```yaml
selectedUnitId: one allowlisted unit
selectedContractId: one causal contract inside that unit
redInvocationId: selectedContractId plus -RED-1
failureSignature: redacted stable reason or source-backed contract gap
redCommand: exactly one existing failing command or command that runs the new focused test
expectedRed: exact assertion and semantic failure
permittedPaths: exact selected-unit production and test paths
excludedPaths: every neighboring path not needed by the causal seam
rollback: exact preimage hashes to capture before writing
```

If no unit has a current failure or a source-backed precise RED assertion,
return `NO_PATCH_NEEDED` and stop without a branch or bundle.

- [ ] **Step 3: Bind the runtime variables used by later tasks.**

Set `$SelectedUnitId`, `$SelectedContractId`, `$RedInvocationId`, the single
string `$RedCommand`, `$RedCommandInvocationCount=1`, and `$PermittedPaths` to
the exact causal contract and repo-relative paths.
Normalize path separators to `/`. Then enforce the static unit boundary:

```powershell
$AllowedUnitIds = @('WU-E10','WU-C20','WU-H30','WU-C21','WU-S50','WU-S40')
if (($AllowedUnitIds -notcontains $SelectedUnitId) -and (-not $SelectedUnitId.StartsWith('WU-D60-'))) {
    throw 'selected-work-unit-not-allowlisted'
}
$UnitPathAllowlists = @{
    'WU-E10' = @(
        'main/java/com/example/lms/service/ChatWorkflow.java',
        'main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java',
        'main/java/com/example/lms/ensemble/DualHypothesisEvidenceScorer.java',
        'main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java',
        'main/java/com/example/lms/prompt/PromptBuilder.java',
        'main/java/com/example/lms/prompt/StandardPromptBuilder.java',
        'src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java',
        'src/test/java/com/example/lms/ensemble/DualHypothesisEvidenceScorerTest.java',
        'src/test/java/com/example/lms/ensemble/EnsembleFinalAnswerServiceTest.java',
        'src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java',
        'src/test/java/com/example/lms/service/ChatWorkflowStrictSingleAttemptHttpIntegrationTest.java',
        'src/test/java/com/example/lms/prompt/PromptBuilderBoundaryTest.java'
    )
    'WU-C20' = @('main/resources/static/js/chat.js','scripts/chat_ui_stream_contract_tests.js')
    'WU-H30' = @(
        'scripts/smoke_websoak_kpi_provider_disabled.ps1','scripts/smoke_websoak_kpi_provider_disabled_tests.ps1',
        'scripts/smoke_chat_debug_events_readback.ps1','scripts/smoke_chat_debug_events_readback_tests.ps1',
        'scripts/smoke_chat_debug_fx_sse.ps1','scripts/smoke_chat_debug_fx_sse_tests.ps1',
        'scripts/trace_memory_recovery_synthetic_smoke.ps1','scripts/trace_memory_recovery_synthetic_smoke_tests.ps1'
    )
    'WU-C21' = @(
        'main/resources/static/js/chat.js','main/resources/templates/chat-ui.html','main/resources/static/css/chat-style.css',
        'main/java/com/example/lms/api/ChatApiController.java','main/java/com/example/lms/dto/ChatStreamEvent.java',
        'scripts/chat_ui_stream_contract_tests.js','scripts/chat_ui_browser_fault_fixture_tests.js',
        'scripts/chat_ui_view_layer_contract_tests.js','src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java',
        'src/test/java/com/example/lms/api/ChatApiControllerStateSecurityTest.java'
    )
    'WU-S50' = @(
        'scripts/goal_next_auto.ps1','scripts/goal_next_auto_tests.ps1','scripts/awx_mcp_completion_audit.py',
        'scripts/test_awx_mcp_toolbox.py','scripts/source_health_scorecard.py','scripts/test_source_health_scorecard.py',
        'agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md','.mcp.json'
    )
    'WU-S40' = @(
        'scripts/awx_mcp_toolbox_tests.ps1','scripts/test_awx_mcp_toolbox.py'
    )
}
$S40ConditionalJavaPaths = @(
    'main/java/com/example/lms/config/AppSecurityConfig.java','main/java/com/example/lms/config/CustomSecurityConfig.java',
    'main/java/com/example/lms/security/AdminTokenGuardInterceptor.java',
    'src/test/java/com/example/lms/config/AppSecurityConfigContractTest.java',
    'src/test/java/com/example/lms/config/CustomSecurityConfigContractTest.java',
    'src/test/java/com/example/lms/config/ForceHttpsSecurityBoundaryTest.java',
    'src/test/java/com/example/lms/boot/RuntimeConfigGuardTest.java',
    'src/test/java/com/example/lms/api/KakaoOAuthControllerTest.java'
)
if ([string]::IsNullOrWhiteSpace([string]$SelectedContractId) -or
    -not $SelectedContractId.StartsWith("$SelectedUnitId-", [StringComparison]::OrdinalIgnoreCase)) {
    throw 'selected-contract-id-invalid'
}
if (($RedCommand -isnot [string]) -or [string]::IsNullOrWhiteSpace($RedCommand)) {
    throw 'exactly-one-red-command-required'
}
if ($RedInvocationId -cne "$SelectedContractId-RED-1" -or $RedCommandInvocationCount -ne 1 -or
    $RedCommand -match '[\r\n;]|&&|\|\|') {
    throw 'red-invocation-not-exactly-one-or-not-contract-bound'
}
$PermittedPaths = @($PermittedPaths | ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique)
if ($PermittedPaths.Count -eq 0) { throw 'selected-work-unit-pathspec-empty' }
if ($SelectedUnitId.StartsWith('WU-D60-')) {
    $UnitAllowlist = @($ApprovedDeferredPaths | ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique)
    if ($UnitAllowlist.Count -eq 0) { throw 'deferred-family-approval-paths-missing' }
} else {
    $UnitAllowlist = @($UnitPathAllowlists[$SelectedUnitId])
}
if ($SelectedUnitId -eq 'WU-S40' -and @($PermittedPaths | Where-Object { $S40ConditionalJavaPaths -contains $_ }).Count -gt 0) {
    if ($S40JavaAuthorizationToken -notmatch '^EXPLICIT-S40-JAVA-PHASE:[A-Z0-9._-]+$' -or
        $S40JavaAuthorizedContractId -cne $SelectedContractId) {
        throw 's40-java-phase-explicit-authorization-missing-or-mismatched'
    }
    $UnitAllowlist = @($UnitAllowlist + $S40ConditionalJavaPaths | Sort-Object -Unique)
}
$OutOfUnitPaths = @($PermittedPaths | Where-Object { $UnitAllowlist -notcontains $_ })
if ($OutOfUnitPaths.Count -ne 0) { throw 'permitted-path-outside-unit-allowlist' }
if ($SelectedUnitId -eq 'WU-H30') {
    $H30PairKeys = @(
        'scripts/smoke_websoak_kpi_provider_disabled.ps1|scripts/smoke_websoak_kpi_provider_disabled_tests.ps1',
        'scripts/smoke_chat_debug_events_readback.ps1|scripts/smoke_chat_debug_events_readback_tests.ps1',
        'scripts/smoke_chat_debug_fx_sse.ps1|scripts/smoke_chat_debug_fx_sse_tests.ps1',
        'scripts/trace_memory_recovery_synthetic_smoke.ps1|scripts/trace_memory_recovery_synthetic_smoke_tests.ps1'
    ) | ForEach-Object { (($_ -split '\|') | Sort-Object) -join '|' }
    $SelectedPairKey = (@($PermittedPaths) | Sort-Object) -join '|'
    if ($H30PairKeys -notcontains $SelectedPairKey) { throw 'wu-h30-requires-exactly-one-script-test-pair' }
}
```

---

## Task 4: Run The Fixed Three-Way Preflight And Create Its Branch

**Files:** no source files may change before stable `APPLY`.

- [ ] **Step 1: Freeze one redacted EvidenceSnapshot for the selected unit.**

Use at most 20 rows and 6,000 characters. Include paths, SHA-256 hashes,
counts, booleans, timings, reason codes, selected unit, exact verification
commands, and rollback path. Exclude raw prompts, responses, credentials,
headers, cookies, DB URLs, and full environment output. Compute one
`evidenceSnapshotHash`.

- [ ] **Step 2: Produce exactly three logical packets over the same hash.**

Bind the packets as `$PositivePacket`, `$NegativePacket`, and `$NeutralPacket`,
and bind their snapshot hashes separately.
They are capped at 2,400, 2,400, and 1,800 characters respectively. All three
must use the same frozen hash; SUPPORT and FALSIFY use the same scenario-ID set.
Every packet has `evidenceAcquisitionCount=0`, `toolCallCount=0`,
`providerCallCount=0`, and `writeCount=0`. NEUTRAL may inspect only the first two
packets and current command evidence; it must not gather or infer new evidence.

```text
POSITIVE_QUERY:
  2..4 scenario IDs, causal mechanisms, expected observations, falsifiers,
  reusable assets, minimal verification, unknowns

NEGATIVE_QUERY:
  exact same scenario-ID set, counterexamples, alternate causes, authority and
  blast-radius risks, smallest disconfirming probes

NEUTRAL_QUERY:
  evaluate POSITIVE-NEGATIVE and NEGATIVE-POSITIVE, compare decisive evidence,
  return APPLY, HOLD, or REJECT without acquiring new evidence
```

Compute and clamp the neutral score:

```text
100 * (0.25*evidenceStrength + 0.20*causalStrength
+ 0.15*verificationFeasibility + 0.15*userValue + 0.10*reversibility
+ 0.10*costEfficiency + 0.05*timeFit - 0.20*blastRadius
- 0.15*ambiguity - 0.20*authorityOrSafetyExpansion)
```

HOLD when the order verdict or decisive-evidence set changes, score is below
50, target ownership is missing, verification is missing, the snapshot is not
frozen, scenario sets differ, or the 120-second preflight budget expires.
REJECT on redaction failure or undeclared source write. Only stable `APPLY`
continues.

- [ ] **Step 3: Create a dedicated branch only after stable `APPLY`.**

```powershell
foreach ($Packet in @($PositivePacket,$NegativePacket,$NeutralPacket)) {
    if (($Packet -isnot [string]) -or [string]::IsNullOrWhiteSpace($Packet)) {
        throw 'three-way-packet-empty-or-not-text'
    }
}
if ($PositivePacket.Length -gt 2400 -or $NegativePacket.Length -gt 2400 -or $NeutralPacket.Length -gt 1800) {
    throw 'three-way-packet-bound-exceeded'
}
if ($EvidenceSnapshotHash -notmatch '^[0-9a-fA-F]{64}$' -or
    $PositiveEvidenceSnapshotHash -cne $EvidenceSnapshotHash -or
    $NegativeEvidenceSnapshotHash -cne $EvidenceSnapshotHash -or
    $NeutralEvidenceSnapshotHash -cne $EvidenceSnapshotHash) {
    throw 'three-way-evidence-snapshot-hash-empty-or-mismatched'
}
if ($PreflightEvidenceAcquisitionCount -ne 0 -or $PreflightToolCallCount -ne 0 -or
    $PreflightProviderCallCount -ne 0 -or $PreflightWriteCount -ne 0) {
    throw 'three-way-preflight-side-effect-or-new-evidence'
}
$PositiveScenarioSet = @($PositiveScenarioIds | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
$NegativeScenarioSet = @($NegativeScenarioIds | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
$NeutralScenarioSet = @($NeutralScenarioIds | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
if ($PositiveScenarioSet.Count -lt 2 -or $PositiveScenarioSet.Count -gt 4 -or
    $PositiveScenarioSet.Count -ne @($PositiveScenarioIds).Count -or
    $NegativeScenarioSet.Count -ne @($NegativeScenarioIds).Count -or
    $NeutralScenarioSet.Count -ne @($NeutralScenarioIds).Count) {
    throw 'three-way-scenario-count-empty-or-duplicate'
}
$PositiveScenarioKey = $PositiveScenarioSet -join '|'
$NegativeScenarioKey = $NegativeScenarioSet -join '|'
$NeutralScenarioKey = $NeutralScenarioSet -join '|'
if ($PositiveScenarioKey -cne $NegativeScenarioKey -or $PositiveScenarioKey -cne $NeutralScenarioKey) {
    throw 'three-way-scenario-set-mismatch'
}
$GoalScoreNumber = 0.0
if (-not [double]::TryParse([string]$GoalScore,[Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture,[ref]$GoalScoreNumber) -or
    [double]::IsNaN($GoalScoreNumber) -or [double]::IsInfinity($GoalScoreNumber) -or
    $GoalScoreNumber -lt 50 -or $GoalScoreNumber -gt 100) {
    throw 'three-way-goal-score-invalid'
}
if ($ForwardVerdict -cne 'APPLY' -or $ReverseVerdict -cne 'APPLY' -or
    $ForwardDecisiveEvidenceHash -notmatch '^[0-9a-fA-F]{64}$' -or
    $ForwardDecisiveEvidenceHash -cne $ReverseDecisiveEvidenceHash) {
    throw 'three-way-verdict-not-stable-apply'
}
$UnitSlug = $SelectedUnitId.ToLowerInvariant().Replace('_','-')
$ProducerBranch = "codex/other-desktop/$UnitSlug"
$ExistingBranch = @(git branch --list $ProducerBranch)
if ($LASTEXITCODE -ne 0) { throw 'branch-availability-check-failed' }
if (-not [string]::IsNullOrWhiteSpace(($ExistingBranch -join ''))) {
    throw 'branch-ownership-mismatch: producer branch already exists'
}
git switch -c $ProducerBranch
if ($LASTEXITCODE -ne 0) { throw 'branch-create-failed' }
$GuardOwnerId = 'other-desktop-' + [guid]::NewGuid().ToString('N')
$PatchDropGuardRoot = Join-Path $ProducerRoot '__patch_drop__'
if (-not (Test-Path -LiteralPath $PatchDropGuardRoot -PathType Container)) {
    New-Item -ItemType Directory -Path $PatchDropGuardRoot | Out-Null
}
$PromotionLockPath = Join-Path $PatchDropGuardRoot '.promotion.lock'
if (Test-Path -LiteralPath $PromotionLockPath) { throw 'promotion-lock-preimage-conflict' }
$GuardLockRelativeDir = "__patch_drop__/source-edit-locks/$UnitSlug.lock"
$GuardLeaseRelativePath = "$GuardLockRelativeDir/lease.json"
powershell -NoProfile -ExecutionPolicy Bypass -File $SourceEditSessionScript -Action begin -Role desktop -Root $ProducerRoot -Topic $UnitSlug -OwnerId $GuardOwnerId -TtlMinutes 600
if ($LASTEXITCODE -ne 0) {
    if ((Test-Path -LiteralPath $PromotionLockPath -PathType Leaf) -and
        (Get-Item -LiteralPath $PromotionLockPath).Length -eq 0) {
        Remove-Item -LiteralPath $PromotionLockPath -Force
    }
    throw 'source-owner-guard-begin-failed'
}
$script:SourceGuardHeld = $true
function Release-SelectedSourceGuard {
    try {
        if ($script:SourceGuardHeld) {
            powershell -NoProfile -ExecutionPolicy Bypass -File $SourceEditSessionScript -Action end -Role desktop -Root $ProducerRoot -Topic $UnitSlug -OwnerId $GuardOwnerId
            if ($LASTEXITCODE -ne 0) { return $false }
            $script:SourceGuardHeld = $false
        }
        if (Test-Path -LiteralPath $PromotionLockPath -PathType Leaf) {
            if ((Get-Item -LiteralPath $PromotionLockPath).Length -ne 0) { return $false }
            Remove-Item -LiteralPath $PromotionLockPath -Force -ErrorAction Stop
        }
        return (-not (Test-Path -LiteralPath (Join-Path $ProducerRoot $GuardLockRelativeDir)))
    } catch {
        return $false
    }
}
trap {
    $CleanupSucceeded = Release-SelectedSourceGuard
    Write-Error $(if ($CleanupSucceeded) { 'BLOCKED: source guard released after failure' } else { 'BLOCKED: source guard cleanup evidence_needed' }) -ErrorAction Continue
    exit 1
}
function Assert-SelectedSourceGuard {
    Assert-RunWithinBudget
    $LeasePath = Join-Path $ProducerRoot $GuardLeaseRelativePath
    if (-not $script:SourceGuardHeld -or -not (Test-Path -LiteralPath $LeasePath -PathType Leaf)) {
        throw 'source-owner-guard-not-held'
    }
    $Lease = Get-Content -LiteralPath $LeasePath -Encoding utf8 -Raw | ConvertFrom-Json
    $LeaseExpiry = [DateTimeOffset]::Parse([string]$Lease.expiresAtUtc,[Globalization.CultureInfo]::InvariantCulture)
    if ($Lease.schemaVersion -cne 'awx.source_edit_session.lease.v1' -or
        $Lease.topic -cne $UnitSlug -or $Lease.role -cne 'desktop' -or
        $Lease.ownerId -cne $GuardOwnerId -or
        [IO.Path]::GetFullPath([string]$Lease.root).TrimEnd('\') -ine $ProducerRoot.TrimEnd('\') -or
        ($Lease.mutationAllowed -isnot [bool]) -or -not $Lease.mutationAllowed -or
        $LeaseExpiry -le [DateTimeOffset]::UtcNow -or $LeaseExpiry -le $RunDeadlineUtc) {
        throw 'source-owner-guard-owner-expiry-or-contract-invalid'
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $ProducerRoot $GuardLeaseRelativePath) -PathType Leaf)) {
    throw 'source-owner-guard-lease-missing'
}
Assert-SelectedSourceGuard
$env:AWX_AGENT_HOST = 'other-desktop-producer'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'other-desktop-producer'
$env:GRADLE_USER_HOME = $ProducerGradleUserHome
```

The stable packet must emit `nextWorkflow=existing-source-owner-guard`. From
guard begin through Task 7 cleanup, any failure must run the matching `end`
action in a `finally` path before reporting. A leaked or owner-mismatched lease
is `BLOCKED`; do not continue or delete the lock manually.

If a WU-D60 family was separately approved, its exact family suffix and path
allowlist must already be present in the frozen snapshot.

---

## Task 5: RED, Minimal Patch, GREEN

**Files:** exact selected-unit allowlist only.

- [ ] **Step 1: Preserve immediate preimages.**

For every permitted path that exists, record SHA-256 immediately before the
first write. Re-run `git status --short` and resolve the lock with
`git rev-parse --git-path index.lock`. Invoke `Assert-SelectedSourceGuard`
immediately before every test-file or implementation write, including every
`apply_patch`; do not cache its result. HOLD on any unexpected target change,
owner mismatch, expired lease, or elapsed nine-hour deadline.

- [ ] **Step 2: Prove RED.**

If the current test already expresses the missing contract, run it and retain
the failing assertion and exit code. If production behavior is demonstrably
wrong but coverage is missing, add one focused test within the selected
allowlist, run it, and prove it fails for the intended semantic reason.

- [ ] **Step 3: Apply the smallest implementation.**

Use `apply_patch`. Do not use broad formatters, bulk rewrites, generated source,
archive grafts, duplicate classes, or unrelated cleanup. Preserve public
interfaces, property names, cancellation semantics, redaction, and fail-soft
behavior unless the focused contract explicitly requires a change.

- [ ] **Step 4: Prove focused GREEN.**

Run the exact focused command that was RED. Report exit code and assertion
count. A different passing command does not close the RED.

- [ ] **Step 5: Check declared-path integrity.**

```powershell
$TrackedChangedPaths = @(git diff --name-only HEAD --)
if ($LASTEXITCODE -ne 0) { throw 'tracked-changed-path-enumeration-failed' }
$UntrackedChangedPaths = @(git ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'untracked-changed-path-enumeration-failed' }
$ChangedPathsWithGuard = $TrackedChangedPaths + $UntrackedChangedPaths
$ChangedPathsWithGuard = @($ChangedPathsWithGuard | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
    ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique)
$GuardTransientPaths = @($GuardLeaseRelativePath,'__patch_drop__/.promotion.lock')
$UnexpectedGuardPaths = @($ChangedPathsWithGuard | Where-Object {
    $_.StartsWith('__patch_drop__/', [StringComparison]::OrdinalIgnoreCase) -and
    $GuardTransientPaths -notcontains $_
})
if ($UnexpectedGuardPaths.Count -ne 0) { throw 'unexpected-source-owner-guard-path' }
$ChangedPaths = @($ChangedPathsWithGuard | Where-Object { $GuardTransientPaths -notcontains $_ })
$UnexpectedPaths = @($ChangedPaths | Where-Object { $PermittedPaths -notcontains $_ })
if ($UnexpectedPaths.Count -ne 0) {
    throw 'undeclared-source-write'
}
git diff --check
if ($LASTEXITCODE -ne 0) { throw 'diff-check-failed' }
```

Do not print file contents when reporting unexpected paths.

---

## Task 6: Broaden Verification In Proportion To The Patch

**Files:** no additional edits are authorized by this task.

- [ ] **Step 1: Always rerun governance checks.**

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'post-patch-source-governance-failed' }
```

- [ ] **Step 2: For Java or Spring resource changes, compile and package.**

```powershell
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'post-patch-compile-java-failed' }
.\gradlew.bat :app:classes -x test --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'post-patch-app-classes-failed' }
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir $ProjectCache
if ($LASTEXITCODE -ne 0) { throw 'post-patch-bootjar-failed' }
```

- [ ] **Step 3: For UI units, keep runtime evidence supporting-only.**

Run Browser and Computer proof only after an actual resource/controller change
and a sequential local boot with ports 8080/8081
confirmed free. Verify visible stream, cancel, reload/session identity, and
redacted debug state. Computer proof is read-only window/app state unless a
specific UI action is essential. Record both as supporting and require the
canonical Desktop to repeat the same proof.

- [ ] **Step 4: For Supabase, do not log in or mutate.**

If the connector is unavailable, record `supporting_evidence_needed` and
continue only when the selected local test is independent of Supabase. If the
selected unit explicitly requires live Supabase proof, stop `BLOCKED` until
project-scoped read-only access exists.

Any failed verification returns to `superpowers:systematic-debugging`; it does
not authorize another work unit.

---

## Task 7: Generate One Nested Desktop PatchDrop v3 Bundle

**Files created outside the source root:** one nested producer bundle and one
pending notice. The helper writes directly to final filenames, so use a unique,
new, private exchange root and do not expose or transfer it until every hash,
manifest, safety, temp-apply, reverse-apply, and guard-release gate passes. This
collision avoidance is not a claim of cross-filesystem atomic publication.

- [ ] **Step 1: Create a deterministic topic and exchange directory.**

```powershell
$Topic = ('awx-other-desktop-' + $SelectedUnitId.ToLowerInvariant().Replace('_','-'))
$ExchangeParent = Join-Path ([Environment]::GetFolderPath('Desktop')) 'awx-patchdrop-exchange'
New-Item -ItemType Directory -Force -Path $ExchangeParent | Out-Null
$ExchangeRoot = Join-Path $ExchangeParent ($Topic + '-' + [guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $ExchangeRoot) { throw 'exchange-root-collision' }
New-Item -ItemType Directory -Path $ExchangeRoot | Out-Null
if (@(Get-ChildItem -LiteralPath $ExchangeRoot -Force).Count -ne 0) {
    throw 'exchange-root-not-empty'
}
```

- [ ] **Step 2: Generate the bundle from explicit changed paths.**

```powershell
Assert-SelectedSourceGuard
$PathSpec = @($ChangedPaths)
if ($PathSpec.Count -eq 0) { throw 'no-patch-body' }
powershell -NoProfile -ExecutionPolicy Bypass -File $ProducerBundleScript `
  -Topic $Topic `
  -Node desktop `
  -SourceRoot $ProducerRoot `
  -PatchDropRoot $ExchangeRoot `
  -PathSpec $PathSpec
if ($LASTEXITCODE -ne 0) { throw 'producer-bundle-failed' }
```

Expected artifacts:

```text
$ExchangeRoot\desktop\$Topic-desktop-v3.patch
$ExchangeRoot\desktop\$Topic-desktop-v3.report.md
$ExchangeRoot\desktop\$Topic-desktop-v3.verify.log
$ExchangeRoot\desktop\$Topic-desktop-v3.sha256.txt
$ExchangeRoot\desktop\$Topic-desktop-v3.manifest.json
$ExchangeRoot\$Topic.desktop-pending.md
```

- [ ] **Step 3: Verify sidecar hashes and patch safety.**

```powershell
$BundleBase = "$Topic-desktop-v3"
$NodeDir = Join-Path $ExchangeRoot 'desktop'
$PatchFile = Join-Path $NodeDir "$BundleBase.patch"
$ReportFile = Join-Path $NodeDir "$BundleBase.report.md"
$VerifyFile = Join-Path $NodeDir "$BundleBase.verify.log"
$ShaFile = Join-Path $NodeDir "$BundleBase.sha256.txt"
$ManifestFile = Join-Path $NodeDir "$BundleBase.manifest.json"
$PendingFile = Join-Path $ExchangeRoot "$Topic.desktop-pending.md"
foreach ($RequiredFile in @($PatchFile,$ReportFile,$VerifyFile,$ShaFile,$ManifestFile,$PendingFile)) {
    if (-not (Test-Path -LiteralPath $RequiredFile -PathType Leaf)) {
        throw "missing-bundle-meta: $([IO.Path]::GetFileName($RequiredFile))"
    }
}
$ExpectedNodeNames = @(
    "$BundleBase.patch","$BundleBase.report.md","$BundleBase.verify.log",
    "$BundleBase.sha256.txt","$BundleBase.manifest.json"
) | Sort-Object
$ActualNodeNames = @(Get-ChildItem -LiteralPath $NodeDir -File -Force | Select-Object -ExpandProperty Name | Sort-Object)
if (@(Compare-Object -ReferenceObject $ExpectedNodeNames -DifferenceObject $ActualNodeNames).Count -ne 0) {
    throw 'bundle-node-file-set-not-exact'
}
$ExpectedRootNames = @('desktop',"$Topic.desktop-pending.md") | Sort-Object
$ActualRootNames = @(Get-ChildItem -LiteralPath $ExchangeRoot -Force | Select-Object -ExpandProperty Name | Sort-Object)
if (@(Compare-Object -ReferenceObject $ExpectedRootNames -DifferenceObject $ActualRootNames).Count -ne 0) {
    throw 'bundle-exchange-entry-set-not-exact'
}
$Manifest = Get-Content -LiteralPath $ManifestFile -Encoding utf8 -Raw | ConvertFrom-Json
$ExpectedManifestFields = @(
    'schemaVersion','topic','slug','node','activePatch','desktopFinalProof',
    'sourceRootInputHash','sourceRootHash','patchDropHash','sourceIsolation','verification'
) | Sort-Object
$ActualManifestFields = @($Manifest.PSObject.Properties.Name | Sort-Object)
if (@(Compare-Object -ReferenceObject $ExpectedManifestFields -DifferenceObject $ActualManifestFields).Count -ne 0) {
    throw 'manifest-field-set-not-exact'
}
if ($Manifest.schemaVersion -cne 'patchdrop-producer-v3' -or
    $Manifest.topic -cne $Topic -or $Manifest.slug -cne $Topic -or
    $Manifest.node -cne 'desktop' -or $Manifest.activePatch -cne "$BundleBase.patch" -or
    $Manifest.desktopFinalProof -cne 'evidence_needed') {
    throw 'manifest-core-contract-mismatch'
}
function Get-DirectiveStableHash([string]$Value) {
    $Hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $HashBytes = $Hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        return [BitConverter]::ToString($HashBytes).Replace('-','').ToLowerInvariant()
    } finally {
        $Hasher.Dispose()
    }
}
$ResolvedProducerRoot = (Resolve-Path -LiteralPath $ProducerRoot).Path
$ResolvedExchangeRoot = (Resolve-Path -LiteralPath $ExchangeRoot).Path
$ManifestGitRootRaw = (& git -C $ProducerRoot rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($ManifestGitRootRaw)) {
    throw 'manifest-git-root-proof-failed'
}
$ManifestGitRoot = (Resolve-Path -LiteralPath $ManifestGitRootRaw).Path
$ExpectedSourceRootInputHash = Get-DirectiveStableHash $ProducerRoot.Trim()
$ExpectedSourceRootHash = Get-DirectiveStableHash $ResolvedProducerRoot
$ExpectedPatchDropHash = Get-DirectiveStableHash $ResolvedExchangeRoot
$ExpectedGitRootHash = Get-DirectiveStableHash $ManifestGitRoot
if ($Manifest.sourceRootInputHash -cne $ExpectedSourceRootInputHash -or
    $Manifest.sourceRootHash -cne $ExpectedSourceRootHash -or
    $Manifest.patchDropHash -cne $ExpectedPatchDropHash) {
    throw 'manifest-path-hash-mismatch'
}
$Isolation = $Manifest.sourceIsolation
$ExpectedIsolationFields = @(
    'guard','sourceRootKind','sharedSourceRoot','desktopCanonicalSourceRoot',
    'directCanonicalSourceEdit','gitRootPresent','gitRootMatchesSourceRoot','gitRootHash'
) | Sort-Object
$ActualIsolationFields = @($Isolation.PSObject.Properties.Name | Sort-Object)
if (@(Compare-Object -ReferenceObject $ExpectedIsolationFields -DifferenceObject $ActualIsolationFields).Count -ne 0) {
    throw 'source-isolation-field-set-not-exact'
}
foreach ($BooleanField in @('sharedSourceRoot','desktopCanonicalSourceRoot','directCanonicalSourceEdit','gitRootPresent','gitRootMatchesSourceRoot')) {
    if ($Isolation.$BooleanField -isnot [bool]) { throw "source-isolation-boolean-type-invalid: $BooleanField" }
}
if ($Isolation.guard -cne 'PASS' -or $Isolation.sourceRootKind -cne 'local-worktree' -or
    $Isolation.sharedSourceRoot -or $Isolation.desktopCanonicalSourceRoot -or
    $Isolation.directCanonicalSourceEdit -or -not $Isolation.gitRootPresent -or
    -not $Isolation.gitRootMatchesSourceRoot -or
    $Isolation.gitRootHash -cne $ExpectedGitRootHash -or
    $Manifest.sourceRootHash -cne $Isolation.gitRootHash) {
    throw 'source-isolation-violation'
}
$ExpectedVerificationFields = @(
    'diffHeaderCount','filemodeLineCount','allowedNewFileCount','filemodeViolationCount',
    'forbiddenPathCount','secretPatternHits','rawSecretPatternHits'
) | Sort-Object
$ActualVerificationFields = @($Manifest.verification.PSObject.Properties.Name | Sort-Object)
if (@(Compare-Object -ReferenceObject $ExpectedVerificationFields -DifferenceObject $ActualVerificationFields).Count -ne 0) {
    throw 'manifest-verification-field-set-not-exact'
}
foreach ($CountField in $ExpectedVerificationFields) {
    if (($Manifest.verification.$CountField -isnot [ValueType]) -or ($Manifest.verification.$CountField -is [bool])) {
        throw "manifest-verification-count-type-invalid: $CountField"
    }
    if ([string]$Manifest.verification.$CountField -notmatch '^\d+$') {
        throw "manifest-verification-count-not-nonnegative-integer: $CountField"
    }
}
$PatchText = Get-Content -LiteralPath $PatchFile -Encoding utf8 -Raw
$ActualDiffHeaderCount = [regex]::Matches($PatchText.TrimStart([char]0xfeff),'(?m)^diff --git ').Count
$ActualFilemodeLineCount = [regex]::Matches($PatchText.TrimStart([char]0xfeff),'(?m)^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?\r?$').Count
$ActualAllowedNewFileCount = [regex]::Matches($PatchText.TrimStart([char]0xfeff),'(?m)^new file mode 100644\r?$').Count
if ([int]$Manifest.verification.diffHeaderCount -ne $ActualDiffHeaderCount -or $ActualDiffHeaderCount -lt 1 -or
    [int]$Manifest.verification.filemodeLineCount -ne $ActualFilemodeLineCount -or
    [int]$Manifest.verification.allowedNewFileCount -ne $ActualAllowedNewFileCount -or
    [int]$Manifest.verification.secretPatternHits -ne 0 -or
    [int]$Manifest.verification.rawSecretPatternHits -ne 0 -or
    [int]$Manifest.verification.filemodeViolationCount -ne 0 -or
    [int]$Manifest.verification.forbiddenPathCount -ne 0) {
    throw 'manifest-verification-contract-failed'
}
$ExpectedShaTargets = [ordered]@{
    "$BundleBase.patch" = $PatchFile
    "$BundleBase.report.md" = $ReportFile
    "$BundleBase.verify.log" = $VerifyFile
    "$BundleBase.manifest.json" = $ManifestFile
    "../$Topic.desktop-pending.md" = $PendingFile
}
$ShaLines = @(Get-Content -LiteralPath $ShaFile -Encoding utf8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($ShaLines.Count -ne 5) { throw 'sha-sidecar-entry-count-invalid' }
$SeenShaNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($ShaLine in $ShaLines) {
    if ($ShaLine -notmatch '^(?<sha>[0-9a-f]{64})  (?<name>.+)$') { throw 'sha-sidecar-format-invalid' }
    $ShaName = [string]$Matches.name
    if (-not ($ExpectedShaTargets.Keys -ccontains $ShaName) -or -not $SeenShaNames.Add($ShaName)) {
        throw 'sha-sidecar-name-unexpected-or-duplicate'
    }
    $ExpectedSha = [string]$Matches.sha
    $HashedPath = [IO.Path]::GetFullPath([string]$ExpectedShaTargets[$ShaName])
    $AllowedHashTarget = ($HashedPath.StartsWith(([IO.Path]::GetFullPath($NodeDir) + '\'), [StringComparison]::OrdinalIgnoreCase) -or
        $HashedPath -ieq [IO.Path]::GetFullPath($PendingFile))
    if (-not $AllowedHashTarget -or -not (Test-Path -LiteralPath $HashedPath -PathType Leaf)) {
        throw 'sha-sidecar-target-outside-bundle-or-missing'
    }
    $ActualSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $HashedPath).Hash.ToLowerInvariant()
    if ($ActualSha -cne $ExpectedSha) { throw 'sha-mismatch' }
}
if ($SeenShaNames.Count -ne $ExpectedShaTargets.Count) { throw 'sha-sidecar-required-name-missing' }
$VerifyText = Get-Content -LiteralPath $VerifyFile -Encoding utf8 -Raw
$RequiredVerifyLines = @(
    "diffHeaderCount=$ActualDiffHeaderCount",
    "filemodeLineCount=$ActualFilemodeLineCount",
    "allowedNewFileCount=$ActualAllowedNewFileCount",
    'filemodeViolationCount=0','forbiddenPathCount=0','secretPatternHits=0','rawSecretPatternHits=0'
)
foreach ($RequiredVerifyLine in $RequiredVerifyLines) {
    if (-not [regex]::IsMatch($VerifyText, "(?m)^$([regex]::Escape($RequiredVerifyLine))`r?$")) {
        throw "bundle-safety-proof-missing: $RequiredVerifyLine"
    }
}
```

The repository helper performs count-only secret and forbidden-path checks.
Require `secretPatternHits=0`, `filemodeViolationCount=0`, and
`forbiddenPathCount=0` from the generated evidence.

- [ ] **Step 4: Prove the patch on a disposable worktree.**

```powershell
$ProofRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-patch-proof-' + [guid]::NewGuid().ToString('N'))
git -C $ProducerRoot worktree add --detach $ProofRoot HEAD
if ($LASTEXITCODE -ne 0) { throw 'proof-worktree-create-failed' }
try {
    git -C $ProofRoot apply --check --whitespace=error-all $PatchFile
    if ($LASTEXITCODE -ne 0) { throw 'producer-git-apply-check-failed' }
    git -C $ProofRoot apply $PatchFile
    if ($LASTEXITCODE -ne 0) { throw 'producer-temp-apply-failed' }
    $ProofTrackedPaths = @(git -C $ProofRoot diff --name-only HEAD --)
    if ($LASTEXITCODE -ne 0) { throw 'producer-temp-tracked-path-enumeration-failed' }
    $ProofUntrackedPaths = @(git -C $ProofRoot ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'producer-temp-untracked-path-enumeration-failed' }
    $ProofChangedPaths = $ProofTrackedPaths + $ProofUntrackedPaths
    $ProofChangedPaths = @($ProofChangedPaths | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique)
    if (@(Compare-Object -ReferenceObject $ChangedPaths -DifferenceObject $ProofChangedPaths).Count -ne 0) {
        throw 'producer-temp-pathset-mismatch'
    }
    git -C $ProofRoot diff --check
    if ($LASTEXITCODE -ne 0) { throw 'producer-temp-diff-check-failed' }
    git -C $ProofRoot apply --reverse --check $PatchFile
    if ($LASTEXITCODE -ne 0) { throw 'producer-reverse-apply-check-failed' }
    git -C $ProofRoot apply --reverse $PatchFile
    if ($LASTEXITCODE -ne 0) { throw 'producer-temp-reverse-apply-failed' }
    $ProofFinalStatus = @(git -C $ProofRoot status --short)
    if ($LASTEXITCODE -ne 0 -or $ProofFinalStatus.Count -ne 0) {
        throw 'producer-temp-reverse-not-clean'
    }
} finally {
    $ProofCleanupSucceeded = $false
    try {
        $ResolvedProof = [IO.Path]::GetFullPath($ProofRoot)
        $ResolvedTempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        if ($ResolvedProof.StartsWith($ResolvedTempPrefix,[StringComparison]::OrdinalIgnoreCase)) {
            git -C $ProducerRoot worktree remove --force $ResolvedProof
            $ProofCleanupSucceeded = ($LASTEXITCODE -eq 0)
        }
    } catch {
        $ProofCleanupSucceeded = $false
    }
    $GuardCleanupSucceeded = Release-SelectedSourceGuard
    if (-not $GuardCleanupSucceeded) { throw 'source-owner-guard-end-failed' }
    if (-not $ProofCleanupSucceeded) { throw 'proof-worktree-cleanup-failed' }
}
$FinalTrackedPaths = @(git -C $ProducerRoot diff --name-only HEAD --)
if ($LASTEXITCODE -ne 0) { throw 'final-tracked-path-enumeration-failed' }
$FinalUntrackedPaths = @(git -C $ProducerRoot ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'final-untracked-path-enumeration-failed' }
$FinalChangedPaths = $FinalTrackedPaths + $FinalUntrackedPaths
$FinalChangedPaths = @($FinalChangedPaths | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
    ForEach-Object { $_.Replace('\','/') } | Sort-Object -Unique)
if (@(Compare-Object -ReferenceObject $ChangedPaths -DifferenceObject $FinalChangedPaths).Count -ne 0 -or
    (Test-Path -LiteralPath (Join-Path $ProducerRoot $GuardLockRelativeDir)) -or
    (Test-Path -LiteralPath $PromotionLockPath)) {
    throw 'final-pathset-or-guard-cleanup-mismatch'
}
$ValidatedTransferFiles = @($PatchFile,$ReportFile,$VerifyFile,$ShaFile,$ManifestFile,$PendingFile)
```

The focused tests must already have run on the patched producer worktree.
Disposable apply proof checks transport integrity; it does not replace GREEN.

- [ ] **Step 5: Do not masquerade around the current promotion gap.**

The publication-time live canonical probe on 2026-08-07 proved:

```text
producer_bundle.ps1 Node allowlist = macmini | notebook | desktop
janitor_promote_producer_pending.ps1 Node allowlist = macmini | notebook
```

Therefore, only after all gates above pass, transfer exactly the six paths in
`$ValidatedTransferFiles`. Do not transfer the whole exchange directory, rename
the node, rewrite the manifest, overwrite an existing destination, or copy it
into the canonical top-level active queue. Keep `publicationAtomicity=not_claimed`
and report:

```text
promotionPath=evidence_needed
promotionReason=desktop-node-promotion-unsupported-by-current-janitor
```

If the receiving canonical checkout has since added a tested `desktop`
promotion path, that current code may decide the intake. Otherwise the
canonical Desktop must treat this bundle as intent evidence and either add the
promotion capability in a separate approved task or reimplement the verified
live seam locally.

---

## Task 8: Final Producer Report

Return this exact report shape. Include the validated bundle directory only for
`ACTIVE`; other terminal states return no bundle directory.

- For `ACTIVE`, populate every selected-contract, preflight, guard, patch,
  verification, and bundle field.
- For terminal `NO_PATCH_NEEDED`, populate the common observation and all
  per-unit dispositions; use `not_applicable` for selection, guard, patch, and
  bundle fields because Task 3 stops before them.
- For early `BLOCKED` or `REJECTED`, populate evidence up to the failed gate and
  use `not_run` or `not_applicable` afterward. Never manufacture an `APPLY`,
  bundle, manifest, or Supabase result to fill the template.

```markdown
## Summary
- directiveId: AWX-OTHER-DESKTOP-PATCHDROP-V3-SOURCE-EXEC-20260807
- role: other-desktop-patchdrop-producer
- state: ACTIVE | NO_PATCH_NEEDED | BLOCKED | REJECTED
- selectedUnitId: value | not_applicable
- selectedContractId: value | not_applicable
- redInvocationId: value | not_applicable
- priorUnitDispositions:
- topic: value | not_applicable
- activePatch: value | not_applicable
- canonicalDesktopFinalProof: evidence_needed | not_applicable

## Observation
- producerRootKind: local-worktree | evidence_needed
- directCanonicalSourceEdit: false | evidence_needed
- gitHead:
- branch:
- activeSourceSets:
- evidenceSnapshotHash: value | not_run
- threeWayVerdict: APPLY | HOLD | REJECT | not_run
- forwardVerdict / reverseVerdict: values | not_run
- decisiveEvidenceHashStable: true | false | not_run
- goalScore: value | not_run
- orderStable: true | false | not_run
- failureSignature:

## Patch
- exactChangedPaths:
- preimageSha256ByPath:
- fileByFileSummary:
- whyTheseFilesOnly:
- rollback:
- sourceOwnerGuardBegin: pass | fail | not_run
- sourceOwnerGuardEnd: pass | fail | not_run
- guardTransientPathsRemoved: true | false | not_run

## Verification
- redCommand / expected / observed / exitCode
- greenCommand / expected / observed / exitCode
- governanceCommands / exitCodes
- compileOrPackageCommands / exitCodes / notApplicableReason
- gitApplyCheck: pass | fail | not_run
- tempApply: pass | fail | not_run
- reverseApplyCheck: pass | fail | not_run
- secretPatternHits:
- filemodeViolationCount:
- forbiddenPathCount:
- BrowserProof: supporting | not_run
- ComputerProof: supporting | not_run
- SupabaseProof: supporting | supporting_evidence_needed | not_applicable
- SupabaseProjectScope: proven | evidence_needed | not_applicable
- SupabasePolicySemantics: proven | evidence_needed | not_applicable

## Bundle
- exchangeRoot: value | not_applicable
- bundleFiles: values | not_applicable
- sha256ByBundleFile: values | not_applicable
- manifestNode: desktop | not_applicable
- sourceIsolationGuard: PASS | not_run | not_applicable
- helperKitHashesVerified: true | false | not_run
- shaSidecarEntryCount: 5 | not_applicable
- publicationAtomicity: not_claimed | not_applicable
- exactTransferFiles: values | not_applicable
- promotionPath: evidence_needed | not_applicable
- promotionReason: desktop-node-promotion-unsupported-by-current-janitor | not_applicable

## Risks And Next
- failureClassifier:
- confidence: L | M | H
- evidence_needed:
- nextSingleAction: exactly one state-appropriate action; for ACTIVE, canonical Desktop performs read-only bundle intake and live target-preimage comparison
```

Do not include raw source, raw prompts, raw provider responses, credentials,
cookies, authorization headers, DB URLs, or full environment output in the
report. Report hashes, counts, exit codes, reason codes, and bounded redacted
summaries only.

## Producer Completion Checklist

For `ACTIVE`, every ACTIVE-only item must be checked. For
`NO_PATCH_NEEDED`, `BLOCKED`, or `REJECTED`, mark downstream ACTIVE-only items
`not_applicable` or `not_run` and stop at the actual terminal gate.

**Common/state-appropriate:**

- [ ] Dedicated local fixed-disk clone/worktree proven.
- [ ] Canonical/shared source was not edited.
- [ ] External helper kit and repository harness matched all pinned SHA-256 values.
- [ ] Deep Safe Patch harness had zero BLOCK, evidence-needed, and secret hits;
  every WARN has a count-only disposition.
- [ ] Active sourceSets and LangChain4j `1.0.1` purity passed.
- [ ] Per-unit dispositions and the actual terminal state are reported without
  producer `PASS`.
- [ ] No stage, commit, push, PR, deployment, ACL, DB, or external mutation.
- [ ] Exactly one next action is reported.

**ACTIVE-only:**

- [ ] Exactly one EvidenceSnapshot and exactly three logical preflight packets.
- [ ] Stable `APPLY` with score at least 50 and matching A-B/B-A verdicts.
- [ ] Exactly one work unit and one causal `selectedContractId` selected.
- [ ] Exactly one `redInvocationId` and RED command are bound to that contract.
- [ ] Focused RED observed before implementation.
- [ ] Smallest allowlisted patch applied.
- [ ] Same focused contract GREEN.
- [ ] Broader verification proportional to touched files.
- [ ] Changed paths are a subset of the selected allowlist.
- [ ] Source-owner guard was acquired before the first write and released by the
  same owner; lease and promotion-lock transients are absent afterward.
- [ ] One nested cumulative `desktop` v3 bundle produced.
- [ ] Required bundle files and pending notice exist.
- [ ] SHA sidecar contains exactly five unique expected entries with no extras.
- [ ] Full manifest core, source-isolation, hash, and verification contracts pass.
- [ ] Secret, forbidden-path, and filemode violation counts are zero.
- [ ] Temp apply and reverse-apply checks passed.
- [ ] Exactly six validated transfer paths were named; no directory-wide copy,
  overwrite, or filesystem-atomicity claim was made.

Stop immediately when any checked fact cannot be proven. Use
`evidence_needed: missing artifact / verify with exact command` instead of
inventing a success claim.

# Structural Debt Verified-Closure Wave Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconcile the frozen eleven broad-catch groups with one brace-aware detector, reject five detector false positives without repair credit, and independently close up to six genuine root causes through durable, validated closure events.

**Architecture:** A new standard-library-only catch-contract module becomes the sole parsing and classification owner for both Harmony consumers. The quantitative audit then overlays a validated append-only closure history on the current detection universe, keeps the original issue/group identities, publishes the linked three-artifact bundle transactionally, and lets six owner-local Java TDD cycles earn at most one closure credit each.

**Tech Stack:** Python 3 `unittest`, Java 17, JUnit 5, Spring Boot, Gradle Kotlin DSL, Jackson, PowerShell, SHA-256 canonical JSON/NDJSON, and the repository's existing `TraceStore` and source-edit lease contracts.

**Spec:** `docs/superpowers/specs/2026-08-31-structural-debt-verified-closure-wave-design.md` (current approved SHA-256 `faf77a4365df66495de49236fa2ed94e11a24669d0b61ca89539b8d29daa3afd`; the only change from the reviewed content hash was its status line)

## Global Constraints

- The parent objective remains 900 through 1,100 distinct `VERIFIED_CLOSED` root-cause groups; this wave can contribute at most six and does not complete the parent goal.
- The frozen predecessor has exactly eleven `OPEN / ELIGIBLE` `BROAD_CATCH_NO_BREADCRUMB` groups: five rejected false positives and six genuine bounded repair candidates.
- Repair credit is one per independently verified root-cause group, never per catch manifestation, line, file, test, detector disappearance, or prose claim.
- Keep `targetGap` as `max(0, 900 - distinct(ELIGIBLE / OPEN groups plus VERIFIED_CLOSED groups))`; report `closureTargetGap = max(0, 900 - verifiedClosedRootCauseGroups)` only in the private/final wave report.
- Use only active Java roots `main/java` and `app/src/main/java_clean`; do not promote inactive aliases, archives, backups, generated output, or `app/src/main/java`.
- Verify Java 17 before source work. Keep Spring Boot unchanged and every `dev.langchain4j` dependency exactly on `1.0.1`.
- `scripts/harmony_catch_contract.py` is the only Java catch parser/classifier and named bounded-contract owner. It imports neither consumer and executes no command.
- Public artifacts remain count-only and contain no source snippets, catch bodies, absolute paths, URLs, environment values, exception bodies, raw provider bodies, prompts, headers, IP addresses, credentials, or private values.
- The closure producer accepts exactly `--closure-journal verification/structural-repair-closure-journal.jsonl` and `--closure-proof-root .superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave`; do not add a third intake CLI argument.
- The journal file is required and may be zero bytes. A nonempty journal must end with one newline and requires an existing non-reparse proof root.
- Exclude only `verification/structural-repair-closure-journal.jsonl` from the source baseline; do not broaden the exclusion to `verification/`.
- Preserve transactional publication order `baseline -> ledger -> metrics`, with metrics last. Journal and proof inputs are never outputs of `publish_bundle`.
- Run the six Java cycles serially in this order: `StochasticParamSampler`, `TrainRagIngestService`, `ScoringRunner`, `RagControlRuntimeAdapter`, `TrustedProxyPolicy`, `BraveSearchService`.
- Every genuine cycle follows preimage -> focused baseline -> behavior RED -> minimal owner-local patch -> focused GREEN -> mutation-sensitivity check -> shared detector transformation -> boundary gates -> private `PATCHED_UNVERIFIED` -> terminal event -> regenerated bundle.
- Five false-positive owners receive no application-source edit: `LocalLlmProcessManager`, `ModelRuntimeHealthTracker`, `PublicRequestBudgetGuard`, `ApiTriadRoutePreflight`, and `HarmonySseRuntime`.
- Do not change prompt construction, provider selection, credentials, endpoints, outbound-request policy, RAG arbitration, S01-S07 algorithms, Spring components/routes, or database state.
- Brave proof is offline. Provider attempt, Browser, Computer, Supabase, runtime answer, and model answer remain `not_run` or `not_observed`.
- The parent Codex owns every edit, integration action, verification judgment, and final claim. Read-only agents may map or falsify but may not write.
- Before the first application-source or test mutation, run the single repository three-way preflight with one redacted snapshot, exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`, and require order-stable `APPLY` with score at least 50.
- Use the existing isolated worktree only after canonical/isolated target equality, writer, lock, lease, PatchDrop, branch, HEAD, and Java gates pass. The Desktop canonical root owns final integration and proof.
- Preserve all unrelated dirty-tree content and user hunks. Never copy a whole source file over a dirty target, reset, clean, delete user work, stage, commit, push, deploy, mutate a database, or mutate credentials.
- Each task records a suggested commit message for a future authorized operator; no task runs `git add` or `git commit`.

---

## File Structure And Responsibility Map

### New tracked files

- `scripts/harmony_catch_contract.py` — pure brace-aware Java catch parsing, outcome classification, named bounded contracts, and deterministic evidence rows.
- `scripts/test_harmony_catch_contract.py` — focused parser, body-boundary, type, reason-code, redaction, and named-contract unit tests.
- `verification/structural-repair-closure-journal.jsonl` — canonical append-only terminal-event input; initially zero bytes.
- `src/test/java/com/example/lms/service/web/BraveSearchServiceResponseShapeTest.java` — offline malformed-response-shape contract, if the preimage still shows the outbound-header test is a distinct concern.

### Modified infrastructure files

- `scripts/harmony_pressure_report.py` — projects its legacy public aggregate and count-only ledger evidence from shared rows.
- `scripts/test_harmony_pressure_report.py` — proves exact aggregate semantics, privacy, and shared-owner agreement.
- `scripts/awx_mcp_toolbox.py` — removes its local parser/contract table and projects toolbox catch statistics from shared rows.
- `scripts/test_awx_mcp_toolbox.py` — preserves bounded-contract and negative exact-path/method coverage and adds consumer agreement.
- `scripts/dynamic_rag_quant_audit.py` — validates frozen intake, proof files, progress rows, terminal event chains, historical ledger overlays, history hashes, and closure counts.
- `scripts/test_dynamic_rag_quant_audit.py` — exercises empty/missing/malformed history, rejection, verified closure, supersession, proof mismatch, fingerprint persistence, staged-byte validation, rollback, and repeatability.
- `scripts/source_health_scorecard.py` — reuses the audit's closure-history loader to recompute history hashes and count equality; it does not classify closure state itself.
- `scripts/test_source_health_scorecard.py` — adds valid and tampered closure-history linkage fixtures.
- `build.gradle.kts` — declares the shared module, required journal, optional proof root, and the two exact CLI arguments while preserving task dependency and publication order.

### Modified Java owner/test pairs

- `main/java/com/example/lms/ensemble/StochasticParamSampler.java`
- `src/test/java/com/example/lms/ensemble/StochasticParamSamplerTest.java`
- `main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`
- `src/test/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestServiceTest.java`
- `main/java/com/example/lms/tools/ScoringRunner.java`
- `src/test/java/com/example/lms/tools/ScoringRunnerTest.java`
- `main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java`
- `src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java`
- `main/java/com/example/lms/web/TrustedProxyPolicy.java`
- `src/test/java/com/example/lms/web/ClientOwnerKeyResolverTest.java`
- `main/java/com/example/lms/service/web/BraveSearchService.java`
- `src/test/java/com/example/lms/service/web/BraveSearchServiceResponseShapeTest.java`

### Read-only false-positive target files

- `main/java/com/example/lms/config/LocalLlmProcessManager.java`
- `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java`
- `main/java/com/example/lms/api/PublicRequestBudgetGuard.java`
- `main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java`
- `main/java/com/example/lms/harmony/HarmonySseRuntime.java`

### Private evidence workspace

All private paths are rooted at `.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/`:

- `progress.md` — concise task/cycle status, first failure, and next action.
- `repair-progress.jsonl` — canonical `PATCHED_UNVERIFIED` rows.
- `intake/intake-summary.json` — predecessor artifact identities and ordered eleven-group identities.
- `intake/eligible-groups.jsonl` — the exact eleven predecessor ledger core rows, without generated envelope fields.
- `intake/target-preimages.json` — declared source/test/script/build/journal paths, existence, Git state, byte size, and SHA-256.
- `preflight/evidence-snapshot.json` — at most twenty redacted rows.
- `preflight/positive.json`, `negative.json`, `neutral-forward.json`, `neutral-reverse.json` — the sole three-way gate packets/verdicts.
- `proofs/` followed by the 64-hex root-cause-group directory, then `red-summary.json`, `green-summary.json`, and `event-baseline.json` — allowlisted canonical proof summaries.
- `final-desktop-report.md` — exact commands, exit codes, hashes, group states, credit count, closure gap, and unobserved external lanes.

The infrastructure and six Java cycles form one plan because all cycles consume the same detector, history, proof, and credit interface. Each cycle is nevertheless a separate reviewer-sized task that can be held without invalidating previously closed groups.

### Task 0: Freeze Intake, Run The Sole Three-Way Gate, And Acquire The Isolated Lease

**Files:**

- Read: `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`
- Read: `docs/superpowers/specs/2026-08-31-structural-debt-verified-closure-wave-design.md`
- Read: `verification/structural-design-baseline.json`
- Read: `verification/structural-design-debt-ledger.jsonl`
- Read: `verification/dynamic-rag-quant-audit-metrics.json`
- Read: `verification/dynamic-rag-harmony-pressure-metrics.json`
- Create privately: `.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/**`
- Do not modify application source or tests in this task.

**Interfaces:**

- Consumes: approved spec, predecessor three-artifact bundle, the exact eleven category rows, canonical root `C:\AbandonWare\demo-1\demo-1\src`, and isolated root `C:\AbandonWare\worktrees\codex-current-tree-structural-debt-ledger`.
- Produces: immutable intake files, one redacted at-most-twenty-row snapshot, an order-stable `APPLY`, an in-memory `$isolatedOwnerId`, and post-lease target preimage equality.

- [ ] **Step 1: Load the execution skills and verify the approved design identity**

At execution time read `superpowers:executing-plans`, `demo1-source-edit-three-way-preflight`, and `superpowers:test-driven-development` completely. Use no other review contract.

```powershell
$canonicalRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$isolatedRoot = 'C:\AbandonWare\worktrees\codex-current-tree-structural-debt-ledger'
$proofRelative = '.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave'
$canonicalProofRoot = Join-Path $canonicalRoot $proofRelative
$isolatedProofRoot = Join-Path $isolatedRoot $proofRelative
$spec = Join-Path $canonicalRoot 'docs/superpowers/specs/2026-08-31-structural-debt-verified-closure-wave-design.md'
$specHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $spec).Hash.ToLowerInvariant()
if ($specHash -ne 'faf77a4365df66495de49236fa2ed94e11a24669d0b61ca89539b8d29daa3afd') {
    throw 'approved-spec-identity-changed'
}
'approvedSpecIdentity=current-approved-match'
```

Expected: the original approved hash matches, or the only delta is the approval-status line. Any other spec change is a whole-wave `HOLD`.

- [ ] **Step 2: Prove canonical and isolated source authority before creating evidence**

```powershell
$canonicalHead = (git -C $canonicalRoot rev-parse HEAD).Trim()
$isolatedHead = (git -C $isolatedRoot rev-parse HEAD).Trim()
$canonicalBranch = (git -C $canonicalRoot branch --show-current).Trim()
$isolatedBranch = (git -C $isolatedRoot branch --show-current).Trim()
if ($canonicalHead -ne $isolatedHead) { throw 'worktree-head-mismatch' }
if ($canonicalBranch -ne 'codex/owned-runtime-browser-restart') { throw 'canonical-branch-ownership-mismatch' }
if ($isolatedBranch -ne 'codex/current-tree-structural-debt-ledger') { throw 'isolated-branch-ownership-mismatch' }
git -C $canonicalRoot worktree list --porcelain
if (@(git -C $canonicalRoot diff --cached --name-only).Count -ne 0) { throw 'canonical-staged-paths-present' }
if (@(git -C $isolatedRoot diff --cached --name-only).Count -ne 0) { throw 'isolated-staged-paths-present' }
$canonicalIndexLock = (git -C $canonicalRoot rev-parse --git-path index.lock).Trim()
$isolatedIndexLock = (git -C $isolatedRoot rev-parse --git-path index.lock).Trim()
$canonicalIndexLockPath = if ([IO.Path]::IsPathRooted($canonicalIndexLock)) {
    [IO.Path]::GetFullPath($canonicalIndexLock)
} else {
    [IO.Path]::GetFullPath((Join-Path $canonicalRoot $canonicalIndexLock))
}
$isolatedIndexLockPath = if ([IO.Path]::IsPathRooted($isolatedIndexLock)) {
    [IO.Path]::GetFullPath($isolatedIndexLock)
} else {
    [IO.Path]::GetFullPath((Join-Path $isolatedRoot $isolatedIndexLock))
}
if (Test-Path -LiteralPath $canonicalIndexLockPath) { throw 'canonical-index-lock-conflict' }
if (Test-Path -LiteralPath $isolatedIndexLockPath) { throw 'isolated-index-lock-conflict' }
& (Join-Path $canonicalRoot '__patch_drop__\janitor_inventory.ps1')
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\source_edit_session.ps1') -Action status -Root $canonicalRoot
if ($LASTEXITCODE -ne 0) { throw 'canonical-source-lease-conflict' }
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\source_edit_session.ps1') -Action status -Root $isolatedRoot
if ($LASTEXITCODE -ne 0) { throw 'isolated-source-lease-conflict' }
java -version
```

Expected: matching HEAD, prescribed branches, staged count zero, no index lock, no active/corrupt lease, no ambiguous active top-level PatchDrop patch, and Java 17.

- [ ] **Step 3: Freeze the predecessor bundle and exact eleven rows**

Create `intake/intake-summary.json` from an ordered object with exactly these runtime values:

```powershell
$intakeSummary = [ordered]@{
    schemaVersion = 'awx.structural-repair-intake.v1'
    sourceBaselineId = $sourceBaselineId
    sourceLedgerPayloadSha256 = $sourceLedgerPayloadSha256
    sourceMetricsSemanticHash = $sourceMetricsSemanticHash
    sourceBranch = 'codex/owned-runtime-browser-restart'
    sourceHead = $canonicalHead
    sourceIssueIds = @($eligibleRows.issueId)
    rootCauseGroupIds = @($eligibleRows.rootCauseGroupId)
    evidenceFingerprints = @($eligibleRows.evidenceFingerprint)
}
```

Populate `$sourceBaselineId` from the predecessor baseline, `$sourceLedgerPayloadSha256` from canonical predecessor ledger core NDJSON, `$sourceMetricsSemanticHash` from predecessor `semanticArtifactHash`, and `$eligibleRows` from exactly the eleven `BROAD_CATCH_NO_BREADCRUMB` rows after removing only `generatedAt`, `auditRunId`, and `artifactLinks`. Require unique issue IDs and group IDs and preserve ledger order.

Use `scripts.dynamic_rag_quant_audit.canonical_json_bytes` and `canonical_ndjson_bytes` in a read-only check to calculate the hashes, print only counts and hashes, then insert the exact canonical bytes with `apply_patch`. Re-read both files and require their SHA-256 and parsed values to match the in-memory calculation.

- [ ] **Step 4: Freeze every declared target preimage in both roots**

The declared target set is every path in the responsibility map, plus `build.gradle.kts`, the three predecessor public artifacts, the Harmony report, the source-health scorecard, and `verification/structural-repair-closure-journal.jsonl`. Write `intake/target-preimages.json` with exactly:

```powershell
$targetPreimages = [ordered]@{
    schemaVersion = 'awx.structural-repair-target-preimages.v1'
    sourceHead = $canonicalHead
    canonicalBranch = 'codex/owned-runtime-browser-restart'
    isolatedBranch = 'codex/current-tree-structural-debt-ledger'
    targets = @($targetRecords)
}
```

Every `$targetRecords` entry has exactly `path`, `existence`, `gitState`, `sizeBytes`, and `sha256`. `existence` is `FILE` or `ABSENT`; `gitState` is `clean`, `modified`, `deleted`, `untracked`, or `absent`; absent paths use size zero and the all-zero SHA-256 value.

Sort targets by `(path.casefold(), path)`. For each file that exists in both roots, require equal bytes and hashes. An existence or hash mismatch holds only the overlapping target unless it is one of the predecessor artifacts, in which case hold the whole wave.

- [ ] **Step 5: Freeze the sole redacted EvidenceSnapshot**

Write at most twenty rows covering: spec identity, predecessor baseline/ledger/metrics identities, eleven ordered group identities as one aggregated row, canonical/isolated HEAD and branch tokens, declared-target equality count, staged counts, index-lock booleans, PatchDrop top-level candidate count, lease active/corrupt/expired counts, Java major version, active source roots, and external lanes `not_run`. Store counts, hashes, booleans, reason codes, and repository-relative paths only.

- [ ] **Step 6: Run exactly the required three logical queries**

`POSITIVE_QUERY` proves the declared files and interfaces can implement the approved shared detector, terminal overlay, five rejections, and six serial cycles without prohibited runtime drift. `NEGATIVE_QUERY` attacks predecessor identity, target ownership, regex/body disagreement, proof-path traversal, event/progress hash mismatch, invalid supersession, recursive baseline hashing, event truncation, staged-byte validation, transaction rollback, dirty-target overwrite, and false repair credit. `NEUTRAL_QUERY` receives only the frozen snapshot and those two packets, evaluates A-B then B-A, gathers nothing new, and returns `APPLY | HOLD | REJECT` plus a score.

Expected: both neutral orders return `APPLY`, score at least 50, and the same first material reason. Any order change, unrepresented adverse fact, or lower score stops before mutation.

- [ ] **Step 7: Acquire the isolated source-edit lease and recheck preimages**

```powershell
$isolatedOwnerId = [guid]::NewGuid().ToString('N')
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\source_edit_session.ps1') `
  -Action begin -Role desktop -Root $isolatedRoot `
  -Topic structural-debt-verified-closure-wave -OwnerId $isolatedOwnerId -TtlMinutes 180
if ($LASTEXITCODE -ne 0) { throw 'source-lease-begin-failed' }
```

Keep `$isolatedOwnerId` in process memory. Immediately recompute every declared target hash in both roots and compare with `target-preimages.json`; do not start Task 1 on a changed target.

- [ ] **Step 8: Configure isolated Gradle output/cache variables**

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-closure-wave'
$env:GRADLE_USER_HOME = Join-Path $isolatedRoot 'var\gradle-user-home\structural-closure-wave'
$projectCache = Join-Path $isolatedRoot 'var\gradle-project-cache\structural-closure-wave'
```

Suggested commit message: `chore: freeze verified closure wave intake`. Do not stage or commit.

### Task 1: Add The Pure Shared Catch Contract With Focused TDD

**Files:**

- Create: `scripts/harmony_catch_contract.py`
- Create: `scripts/test_harmony_catch_contract.py`
- Read/migrate from: `scripts/awx_mcp_toolbox.py:513-638,2086-2305`

**Interfaces:**

- Consumes: Java source text, repository root, source path, and the current exact named bounded-contract table.
- Produces:

```python
@dataclass(frozen=True)
class CatchEvidence:
    path: str
    line: int
    caught_type_class: str
    reason_code: str

```

The four callable signatures are `strip_java_comments_and_strings_preserve_lines(text: str) -> str`, `iter_java_catch_blocks(scan_text: str) -> Iterable[tuple[int, str, str]]`, `classify_java_catches(*, path: Path, root: Path, text: str) -> tuple[CatchEvidence, ...]`, and `summarize_catches(rows: Sequence[CatchEvidence]) -> dict[str, int]`.

`caught_type_class` is exactly `EXCEPTION | RUNTIME_EXCEPTION | THROWABLE | OTHER`. `reason_code` is exactly one of `LOCAL_BREADCRUMB`, `EMPTY_POLL_TIMEOUT`, `TERMINAL_FAILURE_CLOSE`, `NAMED_BOUNDED_CONTRACT`, `DEFERRED_RETHROW`, or `NO_LOCAL_BREADCRUMB`.

- [ ] **Step 1: Write failing parser and privacy tests**

Create `scripts/test_harmony_catch_contract.py` with direct imports from `scripts/harmony_catch_contract.py`. Include executable tests that assert:

```python
def test_nested_catch_body_does_not_borrow_a_later_breadcrumb(self):
    text = """
        class Probe {
            void run() {
                try { first(); }
                catch (RuntimeException failure) { if (retry()) { second(); } }
                try { third(); }
                catch (Exception handled) { TraceStore.put("probe.reason", "failed"); }
            }
        }
    """
    rows = classify_java_catches(
        path=ROOT / "main/java/com/example/Probe.java", root=ROOT, text=text)
    self.assertEqual(
        ["NO_LOCAL_BREADCRUMB", "LOCAL_BREADCRUMB"],
        [row.reason_code for row in rows],
    )
    self.assertEqual(
        ["RUNTIME_EXCEPTION", "EXCEPTION"],
        [row.caught_type_class for row in rows],
    )

def test_evidence_contains_only_relative_path_line_type_and_reason(self):
    row = classify_java_catches(
        path=ROOT / "main/java/com/example/Probe.java",
        root=ROOT,
        text="class Probe { void run(){ try{} catch(Exception secret){ work(); } } }",
    )[0]
    self.assertEqual({"path", "line", "caught_type_class", "reason_code"}, set(asdict(row)))
    self.assertNotIn(str(ROOT), json.dumps(asdict(row), sort_keys=True))
    self.assertNotIn("secret", json.dumps(asdict(row), sort_keys=True))
```

Add multi-catch coverage for `IOException | RuntimeException`, strings/comments/text blocks containing fake catches, empty `TimeoutException pollTimeout`, `RuntimeException terminalFailure { close(); }`, deferred rethrow, exact named path/method/body acceptance, and wrong-path/wrong-method rejection.

- [ ] **Step 2: Run the new test and require the intended RED**

```powershell
python -X utf8 -m unittest scripts.test_harmony_catch_contract
```

Expected: FAIL because `scripts.harmony_catch_contract` does not exist. A syntax/import-environment failure after the file exists is not an acceptable RED.

- [ ] **Step 3: Implement the balanced-brace owner**

Use a frozen dataclass and these exact aggregate keys:

```python
def summarize_catches(rows: Sequence[CatchEvidence]) -> dict[str, int]:
    broad = {"EXCEPTION", "RUNTIME_EXCEPTION", "THROWABLE"}
    return {
        "catchBlockCount": len(rows),
        "catchWithoutBreadcrumbCount": sum(
            row.reason_code == "NO_LOCAL_BREADCRUMB" for row in rows
        ),
        "broadCatchCount": sum(row.caught_type_class in broad for row in rows),
        "broadCatchWithoutLocalBreadcrumbCount": sum(
            row.caught_type_class in broad
            and row.reason_code == "NO_LOCAL_BREADCRUMB"
            for row in rows
        ),
    }
```

`iter_java_catch_blocks` yields `(catch_start, catch_header, body)` only after finding the matching closing brace. `classify_java_catches` strips comments/strings while preserving newlines, derives the original one-based line, classifies all multi-catch members, then applies outcome precedence in this exact order: local breadcrumb/rethrow in the catch body, empty-poll contract, terminal-close contract, exact named bounded contract, deferred rethrow, unhandled. Normalize paths with `path.relative_to(root).as_posix()` and reject a path outside root.

Move the complete current `HARMONY_BOUNDED_CATCH_CONTRACTS` table and its method-scope helpers byte-for-byte from `scripts/awx_mcp_toolbox.py`; do not leave a second copy. Keep the module standard-library-only.

- [ ] **Step 4: Run the focused contract tests GREEN**

```powershell
python -X utf8 -m unittest scripts.test_harmony_catch_contract
```

Expected: all tests pass; the serialized evidence has no source snippet, caught variable, absolute root, URL, or exception message.

- [ ] **Step 5: Perform a mutation-sensitivity check**

Temporarily invert the `NAMED_BOUNDED_CONTRACT` result for one exact-path fixture, run `scripts.test_harmony_catch_contract`, and require the named-contract test to fail. Restore only that temporary mutation with `apply_patch` and rerun GREEN.

Suggested commit message: `refactor: centralize brace aware catch contracts`. Do not stage or commit.

### Task 2: Convert Both Harmony Consumers And Prove Eleven-Owner Agreement

**Files:**

- Modify: `scripts/harmony_pressure_report.py:1-113,176-359`
- Modify: `scripts/test_harmony_pressure_report.py:1-305`
- Modify: `scripts/awx_mcp_toolbox.py:513-638,2086-2305`
- Modify: `scripts/test_awx_mcp_toolbox.py:11535-11720`

**Interfaces:**

- Consumes: `classify_java_catches` and `summarize_catches` from Task 1.
- Produces: the existing public Harmony fields with `broadCatchWithoutLocalBreadcrumbApprox` now exact, and the existing toolbox keys `catchBlockCount`, `catchWithoutBreadcrumbCount`, `catchWithoutBreadcrumbRatio`, and line-only `samples`.

- [ ] **Step 1: Add failing consumer-agreement tests before changing either consumer**

Add a fixture test that copies the live source bytes for exactly these eleven repository-relative owners into a temporary active root:

```python
FROZEN_OWNER_PATHS = (
    "main/java/com/example/lms/config/LocalLlmProcessManager.java",
    "main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java",
    "main/java/com/example/lms/api/PublicRequestBudgetGuard.java",
    "main/java/com/example/lms/ensemble/ApiTriadRoutePreflight.java",
    "main/java/com/example/lms/ensemble/StochasticParamSampler.java",
    "main/java/com/example/lms/harmony/HarmonySseRuntime.java",
    "main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java",
    "main/java/com/example/lms/service/web/BraveSearchService.java",
    "main/java/com/example/lms/tools/ScoringRunner.java",
    "main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java",
    "main/java/com/example/lms/web/TrustedProxyPolicy.java",
)
```

Resolve any live path spelling mismatch from the frozen predecessor row, not by guessing. For every owner, compare the Harmony report's per-file exact broad-unhandled count with the shared rows, and compare toolbox `samples`/count with the same shared rows. Require public rows to omit line numbers and private toolbox samples to contain only `path` and `line`.

Also preserve the existing negative exact-path/method tests and add one report regression where a breadcrumb more than 32 lines later in the next catch does not mask the first catch.

- [ ] **Step 2: Run the agreement tests and require semantic RED**

```powershell
python -X utf8 -m unittest `
  scripts.test_harmony_pressure_report.HarmonyPressureReportTest.test_shared_contract_agrees_with_toolbox_for_the_eleven_frozen_owners `
  scripts.test_awx_mcp_toolbox.HarmonyBreakStatusTest.test_harmony_scan_recognizes_exact_bounded_catch_contracts `
  scripts.test_awx_mcp_toolbox.HarmonyBreakStatusTest.test_harmony_scan_bounded_contracts_require_exact_path_and_method
```

Expected: the new agreement test fails because the report still uses regex plus 32-line look-ahead. Existing bounded tests remain diagnostic controls.

- [ ] **Step 3: Replace report-local catch parsing with the shared contract**

Insert the scripts directory on `sys.path` before dynamic import in `scripts/test_harmony_pressure_report.py`, then import:

```python
from harmony_catch_contract import classify_java_catches, summarize_catches
```

Change `_catch_pressure` to accept the file identity and delegate:

```python
def _catch_pressure(path: Path, root: Path, text: str) -> tuple[int, int, int, int]:
    summary = summarize_catches(
        classify_java_catches(path=path, root=root, text=text)
    )
    return (
        summary["catchBlockCount"],
        summary["catchWithoutBreadcrumbCount"],
        summary["broadCatchCount"],
        summary["broadCatchWithoutLocalBreadcrumbCount"],
    )
```

Remove `CATCH_RE`, `BROAD_CATCH_RE`, `CATCH_TRACE_LOOKAHEAD_LINES`, `TRACE_OR_BREADCRUMB_RE`, and report-local rethrow parsing. Keep the legacy public name `broadCatchWithoutLocalBreadcrumbApprox`, but populate it only from `broadCatchWithoutLocalBreadcrumbCount`.

- [ ] **Step 4: Replace toolbox-local parsing and delete its duplicate table/helpers**

Import the shared functions and rewrite `harmony_catch_stats`:

```python
def harmony_catch_stats(text_by_path: dict[Path, str], root: Path) -> dict[str, Any]:
    rows = tuple(
        row
        for path, text in text_by_path.items()
        for row in classify_java_catches(path=path, root=root, text=text)
    )
    summary = summarize_catches(rows)
    unhandled = [row for row in rows if row.reason_code == "NO_LOCAL_BREADCRUMB"]
    total = summary["catchBlockCount"]
    silent = summary["catchWithoutBreadcrumbCount"]
    return {
        "catchBlockCount": total,
        "catchWithoutBreadcrumbCount": silent,
        "catchWithoutBreadcrumbRatio": 0.0 if total == 0 else round(silent / total, 4),
        "samples": [
            {"path": row.path, "line": row.line}
            for row in unhandled[:20]
        ],
    }
```

Delete the toolbox copies of the bounded table, stripping, catch iteration, method-scope, terminal, empty-poll, and deferred-rethrow helpers after every call site imports the new owner. Do not retain compatibility aliases that create two owners.

- [ ] **Step 5: Run focused and full Python consumer tests**

```powershell
python -X utf8 -m unittest scripts.test_harmony_catch_contract
python -X utf8 -m unittest `
  scripts.test_harmony_pressure_report.HarmonyPressureReportTest.test_ledger_evidence_includes_every_broad_catch_file_without_source_snippets `
  scripts.test_harmony_pressure_report.HarmonyPressureReportTest.test_shared_contract_agrees_with_toolbox_for_the_eleven_frozen_owners `
  scripts.test_awx_mcp_toolbox.HarmonyBreakStatusTest.test_harmony_scan_recognizes_exact_bounded_catch_contracts `
  scripts.test_awx_mcp_toolbox.HarmonyBreakStatusTest.test_harmony_scan_bounded_contracts_require_exact_path_and_method
python -X utf8 -m unittest scripts.test_harmony_pressure_report scripts.test_awx_mcp_toolbox
```

Expected: all pass. The five named false-positive owners have zero broad-unhandled rows; each genuine owner still has at least one exact predecessor catch or a documented count reconciliation that does not create credit.

- [ ] **Step 6: Regenerate private detector evidence without editing application source**

Run the report in the isolated root, retain only hashes/counts/private catch lines, and prove the five no-diff owner hashes still equal Task 0. Do not append terminal events until Task 3 validates them.

Suggested commit message: `fix: reconcile harmony catch evidence with bounded contracts`. Do not stage or commit.

### Task 3: Add Closure History Validation And Historical Ledger Overlay With TDD

**Files:**

- Create: `verification/structural-repair-closure-journal.jsonl` as zero bytes.
- Modify: `scripts/dynamic_rag_quant_audit.py:20-235,699-820,1078-1472,1496-1795,1861-2246`
- Modify: `scripts/test_dynamic_rag_quant_audit.py:21-343,775-817,1260-1402`

**Interfaces:**

- Consumes: the required journal path, proof root, fixed intake paths below that root, current detector rows, current source hashes, and current audit baseline.
- Produces:

```python
CLOSURE_EVENT_SCHEMA = "awx.structural-repair-closure-event.v1"
CLOSURE_PROGRESS_SCHEMA = "awx.structural-repair-progress.v1"
CLOSURE_INTAKE_SCHEMA = "awx.structural-repair-intake.v1"

@dataclass(frozen=True)
class ClosureInputs:
    root: Path
    journal: Path
    proof_root: Path

@dataclass(frozen=True)
class ClosureHistory:
    predecessor_rows: tuple[dict[str, Any], ...]
    all_events: tuple[dict[str, Any], ...]
    active_events: tuple[dict[str, Any], ...]
    summary: dict[str, Any]

```

The callable signatures are `load_closure_history(inputs: ClosureInputs) -> ClosureHistory`, `apply_closure_overlay(current_rows: Sequence[dict[str, Any]], history: ClosureHistory, *, baseline_id: str) -> tuple[dict[str, Any], ...]`, and `_validate_historical_overlay_row(row: dict[str, Any], *, predecessor: dict[str, Any], current_baseline_id: str) -> None`.

Extend `AuditInputs` with `closure_journal: Path` and `closure_proof_root: Path`. Construct `ClosureInputs(root=root, journal=inputs.closure_journal, proof_root=inputs.closure_proof_root)`. Extend private `AuditBundle` with `closure_inputs: ClosureInputs`; this field is not serialized, but `_bundle_from_serialized` receives and restores it so staged-byte validation reloads the same immutable history.

- [ ] **Step 1: Extend the test fixture for an explicit empty history**

Add `closure_journal` and `closure_proof_root` to `FixturePaths`. `make_fixture` creates a zero-byte journal and leaves the proof root absent. `make_inputs` passes both paths. Add `closureHistorySummary` to fixture metrics expectations with:

```python
{
    "journalPayloadSha256": sha256_hex(b""),
    "proofSetSha256": sha256_hex(canonical_bytes([])),
    "eventCount": 0,
    "rejectedFalsePositiveRootCauseGroups": 0,
    "verifiedClosedRootCauseGroups": 0,
}
```

The empty proof-set hash is therefore the SHA-256 of canonical `[]\n`, not the SHA-256 of an unrestricted directory walk.

Update every private `AuditBundle(...)` constructor in the tests and producer. In particular, `relink_after_metrics_mutation` passes `closure_inputs=bundle.closure_inputs`, `build_audit` passes the newly constructed inputs, and `_bundle_from_serialized` receives the original `closure_inputs` as an explicit parameter.

- [ ] **Step 2: Write failing history-contract tests**

Add separate tests with exact expected reason codes:

- missing journal -> `closure-journal-missing`;
- nonempty journal without terminal newline, invalid UTF-8, blank interior line, duplicate field, wrong field set, or invalid JSON -> `closure-journal-malformed`;
- intake baseline/ledger/metrics identity mismatch -> `closure-intake-mismatch`;
- proof path escape, reparse component, wrong schema/field set/hash, raw absolute path, or secret-pattern hit -> `closure-proof-invalid`;
- duplicate active event, cycle, cross-group supersession, or multiple chain tips -> `closure-event-conflict`;
- current detector still emits the predecessor fingerprint -> `closure-fingerprint-active`;
- valid `VERIFIED_CLOSED` without matching `PATCHED_UNVERIFIED` row -> `closure-progress-mismatch`.

Write one valid rejected fixture and one valid closed fixture. For the rejected fixture, require unchanged target hashes, zero scoped-diff hash, null patch-state fields, terminal tuple `REJECTED_FALSE_POSITIVE / REJECTED_BY_CONTRACT`, one stable `supersedes` hash, and zero repair credit. For the closed fixture, require different target hashes, nonzero scoped-diff hash, exact progress-row link, terminal tuple `VERIFIED_CLOSED / ELIGIBLE`, and one repair credit.

- [ ] **Step 3: Run the new audit tests and require contract RED**

```powershell
python -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_empty_closure_journal_is_valid_and_bound_to_metrics `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_missing_closure_journal_fails_closed `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_valid_rejected_event_preserves_identity_without_credit `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_valid_closed_event_requires_matching_patch_state_and_earns_one_credit
```

Expected: FAIL because `AuditInputs` and metrics do not yet expose closure inputs/history.

- [ ] **Step 4: Define exact event, progress, intake, and proof field allowlists**

Use the exact event and progress fields from the approved spec. Fixed intake paths are derived, never passed through a third CLI:

```python
INTAKE_SUMMARY_RELATIVE = Path("intake/intake-summary.json")
INTAKE_ROWS_RELATIVE = Path("intake/eligible-groups.jsonl")
TARGET_PREIMAGES_RELATIVE = Path("intake/target-preimages.json")
PROGRESS_RELATIVE = Path("repair-progress.jsonl")
PROOF_ID_RE = re.compile(
    r"proofs/[0-9a-f]{64}/(?:red-summary|green-summary|event-baseline)\.json"
)
```

RED/GREEN proof summaries contain exactly `schemaVersion`, `proofKind`, `rootCauseGroupId`, `sourceIssueId`, `commandToken`, `exitCode`, `result`, `assertionCount`, `outputSha256`, and `secretPatternHitCount`. `proofKind` is `RED | GREEN`; RED result is `EXPECTED_FAIL`; GREEN result is `PASS`; secret count is zero.

Event-baseline summaries contain exactly `schemaVersion`, `baselineId`, `branch`, `head`, and ordered `declaredTargets`. Every target contains exactly `path`, `gitState`, `sizeBytes`, and `sha256`. All paths are normalized repository-relative paths and all hashes are lowercase 64-hex.

Declare the terminal event field set exactly, with no optional extra keys:

```python
CLOSURE_EVENT_FIELDS = frozenset({
    "schemaVersion", "eventId", "eventType", "rootCauseGroupId",
    "sourceIssueId", "sourceBaselineId", "sourceEvidenceFingerprint",
    "sourcePath", "sourceSymbol", "sourceCategory", "sourceNumericEvidence",
    "eventBaselineId", "targetPreimageSha256", "targetPostimageSha256",
    "scopedDiffSha256", "redProofId", "redProofSha256", "greenProofId",
    "greenProofSha256", "eventBaselineProofId", "eventBaselineProofSha256",
    "patchStateProofId", "patchStateProofSha256", "fingerprintDisposition",
    "sourceSetGate", "dependencyGate", "compileGate", "duplicateOwnerGate",
    "secretNewHitCount", "desktopProof", "supersedesEventId",
})
```

Declare the private progress field set exactly:

```python
CLOSURE_PROGRESS_FIELDS = frozenset({
    "schemaVersion", "patchStateId", "state", "rootCauseGroupId",
    "sourceIssueId", "sourceBaselineId", "eventBaselineId",
    "targetPreimageSha256", "targetPostimageSha256", "scopedDiffSha256",
    "redProofId", "redProofSha256", "greenProofId", "greenProofSha256",
})
```

`redProofId`, `greenProofId`, and `eventBaselineProofId` are proof-root-relative paths matching `PROOF_ID_RE`; their corresponding SHA fields hash exact file bytes. `patchStateProofId` is the 64-hex `patchStateId` and `patchStateProofSha256` hashes that canonical progress row for a verified closure; both are null for a rejected false positive. All four gate fields and `desktopProof` equal `PASS`, and `secretNewHitCount` equals zero before a terminal event is accepted.

Require `ClosureInputs.root` to resolve to an existing directory, `journal` to resolve exactly to `root / "verification/structural-repair-closure-journal.jsonl"`, and `proof_root` to resolve lexically exactly to `root / ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave"`. An absolute CLI spelling is acceptable only when it resolves to that same exact repository path; no alternate journal or proof directory is accepted.

Add these reason codes to `REASON_CODES`: `closure-journal-missing`, `closure-journal-malformed`, `closure-intake-mismatch`, `closure-proof-invalid`, `closure-event-conflict`, `closure-fingerprint-active`, and `closure-progress-mismatch`.

- [ ] **Step 5: Implement canonical event, progress, proof, and supersession validation**

Compute `eventId` and `patchStateId` by removing only the ID field and hashing `canonical_json_bytes(payload)`. Compute the public row's one-element `supersedes` value exactly as:

```python
def _source_supersession_hash(event: dict[str, Any]) -> str:
    material = "|".join(
        (
            event["sourceBaselineId"],
            event["sourceIssueId"],
            event["sourceEvidenceFingerprint"],
        )
    ).encode("utf-8")
    return sha256_hex(material)
```

Select active events by following `supersedesEventId`: every referenced event must exist, use the same group and source issue, and have exactly one unreferenced terminal tip per group. Corrections remain in `all_events`; only tips appear in `active_events` and determine group counts.

Parse JSON with an `object_pairs_hook` that raises on duplicate keys. For every nonempty journal/progress/intake NDJSON line and every proof JSON file, require the original bytes to equal the corresponding canonical JSON bytes. Reject blank interior lines, a missing final newline, non-UTF-8 bytes, noncanonical whitespace/key ordering, NaN/infinity, and any unknown/missing field.

For each proof ID, normalize under `proof_root`, reject reparse components, verify exact field allowlist and SHA-256, and scan public strings for prohibited absolute paths, URI schemes, secrets, and raw-value fields. For a patch-state reference, find exactly one row in fixed `repair-progress.jsonl`, recompute its ID/hash, and require every shared identity/preimage/postimage/diff/RED/GREEN field to equal the event.

Compute history summary exactly:

```python
proof_pairs = sorted(unique_verified_proof_pairs, key=lambda pair: (pair[0], pair[1]))
summary = {
    "journalPayloadSha256": sha256_hex(journal_bytes),
    "proofSetSha256": sha256_hex(canonical_json_bytes(proof_pairs)),
    "eventCount": len(all_events),
    "rejectedFalsePositiveRootCauseGroups": len({
        event["rootCauseGroupId"] for event in active_events
        if event["eventType"] == "REJECTED_FALSE_POSITIVE"
    }),
    "verifiedClosedRootCauseGroups": len({
        event["rootCauseGroupId"] for event in active_events
        if event["eventType"] == "VERIFIED_CLOSED"
    }),
}
```

- [ ] **Step 6: Implement the explicit historical overlay alternative**

Build current detection rows first. For every active event, locate the exact predecessor core row by both issue and group ID, require all original identity/evidence/severity/proof fields to match, require the current target hash to equal `targetPostimageSha256`, and require no current row to emit `sourceEvidenceFingerprint`.

Create the historical row by copying the predecessor core row, replacing only `baselineId`, `status`, `fixEligibility`, and `supersedes`. Accepted terminal tuples are exactly:

```python
HISTORICAL_TERMINAL_CONTRACT = {
    "REJECTED_FALSE_POSITIVE": ("REJECTED_BY_CONTRACT", "REJECTED_FALSE_POSITIVE"),
    "VERIFIED_CLOSED": ("ELIGIBLE", "VERIFIED_CLOSED"),
}
```

Initial detection rows still require their original category tuple and `supersedes == []`. Historical rows require exactly one supersession hash. Protect all terminal historical rows from candidate-cap truncation; if terminal rows alone exceed the cap, fail with `semantic-nondeterminism`. Fill remaining capacity with sorted current rows.

- [ ] **Step 7: Bind history into baseline, metrics, ledger summary, and audit identity**

Add the journal exact path to `GENERATED_VERIFICATION_PATHS`. Add `closureHistorySummary` to `METRICS_PAYLOAD_FIELDS` and the metrics payload. Change `targetGap` to count the union of eligible-open and verified-closed groups. Keep `eligibleRootCauseGroups` open-only.

Include history hashes directly in the run ID in addition to their presence in the metrics hash:

```python
audit_run_id = sha256_hex(
    (
        f"{SCHEMA_VERSION}|{baseline_id}|{baseline_payload_hash}|"
        f"{ledger_payload_hash}|{metrics_payload_hash}|"
        f"{history.summary['journalPayloadSha256']}|"
        f"{history.summary['proofSetSha256']}"
    ).encode("utf-8")
)
```

`semanticArtifactHash` remains the canonical metrics-payload hash. `validate_bundle` reloads `bundle.closure_inputs`, recomputes history, verifies historical rows against intake, verifies summary equality, and requires the summary's verified count to equal the ledger summary count.

- [ ] **Step 8: Preserve staged-byte validation and publication rollback**

Change `_bundle_from_serialized` to accept `closure_inputs` and return an `AuditBundle` carrying it. In `publish_bundle`, pass `bundle.closure_inputs` into the reread bundle before the second `validate_bundle` call. Do not change destination or replace order.

Add a test that mutates the journal after `build_audit` but before `publish_bundle`; require validation failure and byte-identical prior baseline/ledger/metrics. Preserve and rerun the existing replace-failure and metrics-last tests.

- [ ] **Step 9: Add only the two approved CLI arguments**

```python
parser.add_argument("--closure-journal", required=True)
parser.add_argument("--closure-proof-root", required=True)

# Inside the existing AuditInputs constructor
closure_journal=_argument_path(root, args.closure_journal),
closure_proof_root=_argument_path(root, args.closure_proof_root),
```

Do not add `--closure-intake-root`; `load_closure_history` derives the fixed intake files from `closure_proof_root`.

- [ ] **Step 10: Run focused, rollback, repeatability, and full audit tests**

```powershell
python -X utf8 -m unittest `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_broad_catch_rows_use_harmony_evidence_without_rescanning `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_empty_closure_journal_is_valid_and_bound_to_metrics `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_valid_rejected_event_preserves_identity_without_credit `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_valid_closed_event_requires_matching_patch_state_and_earns_one_credit `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_replace_failure_restores_every_prior_output `
  scripts.test_dynamic_rag_quant_audit.DynamicRagQuantAuditTest.test_metrics_is_the_last_replaced_commit_record
python -X utf8 -m unittest scripts.test_dynamic_rag_quant_audit
```

Expected: all pass. A timestamp-only rerun may change `generatedAt`; ordered core rows, event IDs, history summary hashes, semantic artifact hash, and all artifact links remain identical.

Suggested commit message: `feat: validate structural repair closure history`. Do not stage or commit.

### Task 4: Wire Gradle And Scorecard, Then Persist Five Zero-Credit Rejections

**Files:**

- Modify: `scripts/source_health_scorecard.py:238-392`
- Modify: `scripts/test_source_health_scorecard.py:21-140,962-990`
- Modify: `build.gradle.kts:358-534`
- Modify: `verification/structural-repair-closure-journal.jsonl`
- Create privately: five RED, five GREEN, and five event-baseline proof summaries.
- Do not modify the five rejected application owners.

**Interfaces:**

- Consumes: Task 2's shared classifications, Task 3's `ClosureInputs`/`load_closure_history`, the frozen five predecessor rows, and unchanged application-source hashes.
- Produces: Gradle-declared closure inputs, scorecard history-hash validation, five `REJECTED_FALSE_POSITIVE` events, five historical `REJECTED_BY_CONTRACT` rows, zero closure credit, and six remaining open genuine candidates.

- [ ] **Step 1: Write failing scorecard history-linkage tests**

Extend `_write_linked_quant_bundle` with a required zero-event `closureHistorySummary`, create a zero-byte journal, and leave the proof root absent. Add one test that calls `validate_current_quant_bundle(root)` and expects the summary to survive unchanged. Add separate tamper cases for journal bytes, proof-set hash, event count, and verified-count disagreement.

The valid fixture uses:

```python
closure_summary = {
    "journalPayloadSha256": hashlib.sha256(b"").hexdigest(),
    "proofSetSha256": hashlib.sha256(_canonical_json_bytes([])).hexdigest(),
    "eventCount": 0,
    "rejectedFalsePositiveRootCauseGroups": 0,
    "verifiedClosedRootCauseGroups": 0,
}
```

Recalculate metrics payload hash, artifact links, and run ID with both history hashes appended exactly as Task 3 defines.

- [ ] **Step 2: Run the scorecard tests and require history RED**

```powershell
python -X utf8 -m unittest `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_main_current_linked_quant_bundle_writes_scorecard `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_quant_bundle_rejects_tampered_closure_history
```

Expected: FAIL because the scorecard neither reloads closure history nor uses the extended run-ID material.

- [ ] **Step 3: Reuse the producer's history loader in the scorecard**

Use a one-way import that works both as a direct script and a package test:

```python
try:
    from dynamic_rag_quant_audit import ClosureInputs, load_closure_history
except ModuleNotFoundError:
    from scripts.dynamic_rag_quant_audit import ClosureInputs, load_closure_history
```

In `validate_current_quant_bundle`, construct `ClosureInputs(root=root, journal=root / "verification/structural-repair-closure-journal.jsonl", proof_root=root / ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave")`, call `load_closure_history`, and require exact equality with `metrics_payload["closureHistorySummary"]`. Recompute the run ID with `journalPayloadSha256` and `proofSetSha256` after the metrics hash. Require `closureHistorySummary.verifiedClosedRootCauseGroups == ledgerSummary.verifiedClosedRootCauseGroups`. Do not independently classify events or statuses in this file.

- [ ] **Step 4: Wire the shared module and two closure inputs into Gradle**

Add the shared script to the Harmony task input and declare path-sensitive closure inputs:

```kotlin
val structuralRepairClosureJournal =
    layout.projectDirectory.file("verification/structural-repair-closure-journal.jsonl")
val structuralRepairClosureProofRoot =
    layout.projectDirectory.dir(
        ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave"
    )

// harmonyPressureReport
inputs.file(layout.projectDirectory.file("scripts/harmony_catch_contract.py"))

// dynamicRagQuantAudit
inputs.file(structuralRepairClosureJournal)
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
inputs.dir(structuralRepairClosureProofRoot).optional()
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
```

Append only these arguments to `dynamicRagQuantAudit`:

```kotlin
"--closure-journal",
"verification/structural-repair-closure-journal.jsonl",
"--closure-proof-root",
".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave",
```

Also declare the journal, proof root, and `scripts/dynamic_rag_quant_audit.py` as `sourceHealthScorecard` inputs because the scorecard reloads the same contract. Preserve the dependency chain `harmonyPressureReport -> dynamicRagQuantAudit -> sourceHealthScorecard` and all three public output declarations.

- [ ] **Step 5: Run infrastructure-focused Python and Gradle tests before writing events**

```powershell
python -X utf8 -m unittest `
  scripts.test_harmony_catch_contract `
  scripts.test_harmony_pressure_report `
  scripts.test_dynamic_rag_quant_audit `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_main_current_linked_quant_bundle_writes_scorecard `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_quant_bundle_rejects_tampered_closure_history
& .\gradlew.bat dynamicRagQuantAudit sourceHealthScorecard `
  --no-daemon --project-cache-dir $projectCache
```

Expected: all pass with an empty journal. The history summary has event/rejected/closed counts `0/0/0`, and the proof root may be absent.

- [ ] **Step 6: Build one immutable RED proof per rejected group from predecessor evidence**

For each of the five frozen group IDs, create `red-summary.json` below its 64-hex `proofs/` group directory with proof kind `RED`, result `EXPECTED_FAIL`, assertion count at least one, command token `python -X utf8 scripts\test_harmony_pressure_report.py`, and `outputSha256` equal to the hash of the frozen predecessor classification summary. The summary proves the old report emitted that exact source issue/fingerprint; it contains no catch body or line excerpt.

- [ ] **Step 7: Build one GREEN proof per rejected group from the shared contract**

Run the shared classifier on each unchanged source and assert zero `NO_LOCAL_BREADCRUMB` rows for the exact predecessor fingerprint. Create `green-summary.json` with proof kind `GREEN`, result `PASS`, assertion count equal to the number of checked bounded manifestations, and output hash over a canonical private summary containing only owner path, predecessor group ID, reason codes, and numeric counts.

Required accepted behavior by owner is:

- `LocalLlmProcessManager` — named termination, rollback, and executable-selection fallback contracts;
- `ModelRuntimeHealthTracker` — named request-attempt trace/fingerprint fallback contracts;
- `PublicRequestBudgetGuard` — explicit body-executor rejection outcomes;
- `ApiTriadRoutePreflight` — deterministic fail-closed provider-path result;
- `HarmonySseRuntime` — terminal failure closes the stream lease.

- [ ] **Step 8: Prove application-source no-diff and create baseline summaries**

For each owner, require its current isolated SHA-256 to equal both its Task 0 isolated preimage and the still-unchanged canonical file. Set `targetPreimageSha256 == targetPostimageSha256` and `scopedDiffSha256 == "0" * 64`.

Run `dynamicRagQuantAudit` with the journal still empty, record its current baseline ID, and create each `event-baseline.json` with that ID, isolated branch token, HEAD, and the ordered declared-target records. Because the journal exact path and proof root are excluded, appending events must not change this baseline ID.

- [ ] **Step 9: Append five canonical rejected events using `apply_patch`**

For each event, copy original source identity/numeric evidence from its exact intake row, recompute every proof hash, use null patch-state fields, set all gates `PASS`, set secret new-hit count zero, and use `fingerprintDisposition = "ACCEPTED_BOUNDED_TRANSFORMATION"`. Compute `eventId` from the canonical payload without `eventId`, insert it, and append the canonical one-line JSON plus terminal newline.

Do not edit an existing journal line. Event ordering is the frozen predecessor ledger order. Re-read the journal through `load_closure_history` before publication.

- [ ] **Step 10: Regenerate and verify the five zero-credit historical rows**

```powershell
& .\gradlew.bat dynamicRagQuantAudit sourceHealthScorecard `
  --no-daemon --project-cache-dir $projectCache
```

Parse the public artifacts and require:

```text
closureHistorySummary.eventCount=5
closureHistorySummary.rejectedFalsePositiveRootCauseGroups=5
closureHistorySummary.verifiedClosedRootCauseGroups=0
ledgerSummary.verifiedClosedRootCauseGroups=0
```

Require exactly five historical rows with `REJECTED_FALSE_POSITIVE / REJECTED_BY_CONTRACT`, original issue/group/evidence identities, current baseline ID, and one supersession hash. Require the six genuine groups to remain `OPEN / ELIGIBLE`. Recheck all five no-diff owner hashes and report `repairCredit=0`.

Suggested commit message: `chore: reject bounded catch detector false positives`. Do not stage or commit.

### Task 5: Close `StochasticParamSampler` Through Its Existing Selection Ledger/Trace Seam

**Files:**

- Modify: `main/java/com/example/lms/ensemble/StochasticParamSampler.java:117-122`
- Modify: `src/test/java/com/example/lms/ensemble/StochasticParamSamplerTest.java:235-246`
- Modify privately: progress ledger and one group proof directory.
- Append after validation: `verification/structural-repair-closure-journal.jsonl`

**Interfaces:**

- Consumes: injected nonfinite draw, adjacent `SelectionEntropyException` failure family, `SelectionDecisionLedger`, and `TraceStore`.
- Produces: `Optional.empty()`, ledger failure reason `selection_entropy_derivation_invalid`, trace reason `ensemble.stoch.draw.failureReason`, trace count `ensemble.stoch.draw.failureCount`, and no injected value.

- [ ] **Step 1: Recheck this source/test pair and group preimage**

Require isolated hashes to equal Task 0, canonical hashes to remain the same preimages, the frozen source issue/group/fingerprint to match the intake row, and no other active writer. Run the closest baseline method before editing:

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.ensemble.StochasticParamSamplerTest.invalidInjectedDrawFailsSoftWithoutLeakingTheValue" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS before the new assertions.

- [ ] **Step 2: Extend the focused test with behavior-level RED assertions**

Keep the existing injected `Double.NaN` supplier and add:

```java
assertEquals(
        "selection_entropy_derivation_invalid",
        TraceStore.getString("ensemble.stoch.draw.failureReason"));
assertEquals(1L, TraceStore.getLong("ensemble.stoch.draw.failureCount"));
assertFalse(TraceStore.getAll().toString().contains("NaN"));
```

- [ ] **Step 3: Run the method and require the intended RED**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.ensemble.StochasticParamSamplerTest.invalidInjectedDrawFailsSoftWithoutLeakingTheValue" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: FAIL because the two new trace values are absent; the existing empty-result, call-count, and no-leak assertions still pass. Hash a redacted summary of this output for RED proof.

- [ ] **Step 4: Apply the smallest runtime-catch patch**

Replace only the broad branch body:

```java
} catch (RuntimeException failure) {
    SelectionEntropyReason reason = SelectionEntropyReason.DERIVATION_INVALID;
    ledger.markFailure(reason);
    TraceStore.put("ensemble.stoch.draw.failureReason", reason.code());
    TraceStore.inc("ensemble.stoch.draw.failureCount");
    return Optional.empty();
}
```

Do not change sampling constants, entropy calls, seeds, retry behavior, success trace, or `Optional.empty()`.

- [ ] **Step 5: Run focused GREEN, the class, and mutation sensitivity**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.ensemble.StochasticParamSamplerTest.invalidInjectedDrawFailsSoftWithoutLeakingTheValue" `
  --tests "com.example.lms.ensemble.StochasticParamSamplerTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS. Temporarily remove the `TraceStore.put` line with `apply_patch`, require the focused method to fail, restore the line, and rerun PASS.

- [ ] **Step 6: Prove detector transformation and boundary gates**

Run `classify_java_catches` on this source and require the original broad catch line to be `LOCAL_BREADCRUMB`, with no predecessor fingerprint. Then run:

```powershell
& .\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity compileJava :app:generateDupFqcnExcludes `
  --no-daemon --project-cache-dir $projectCache
```

Require root compile success and no packaged-active duplicate for `com.example.lms.ensemble.StochasticParamSampler`.

- [ ] **Step 7: Run count-only secret and scope checks**

Scan only the two changed files and the pending proof summaries with the repository high-confidence secret pattern. Print only `cycleChangedFileSecretPatternHits=0` and `cycleProofSecretPatternHits=0`. Require exactly this source/test pair in the cycle diff and no prompt/provider/property/dependency change.

- [ ] **Step 8: Record progress, append closure, and regenerate credit**

Compute target pre/post hashes from unchanged canonical preimage and isolated postimage. Compute the nonzero scoped-diff hash from `$scopedDiffMaterial = "awx.scoped-diff.v1|$relativeSourcePath|$targetPreimageSha256|$targetPostimageSha256"` encoded as UTF-8. Create canonical RED/GREEN/event-baseline summaries, append the exact `PATCHED_UNVERIFIED` row, validate it, then append the matching `VERIFIED_CLOSED` event with `fingerprintDisposition = "ACCEPTED_BOUNDED_TRANSFORMATION"`.

Run `dynamicRagQuantAudit sourceHealthScorecard` and require counts `eventCount=6`, rejected `5`, verified closed `1`, and exactly this original group now `VERIFIED_CLOSED / ELIGIBLE`.

Suggested commit message: `fix: trace stochastic draw derivation failures`. Do not stage or commit.

### Task 6: Close `TrainRagIngestService` With A Redacted Timestamp-Fallback Breadcrumb

**Files:**

- Modify: `main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java:467-476`
- Modify: `src/test/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestServiceTest.java`
- Modify privately: progress ledger and one group proof directory.
- Append after validation: `verification/structural-repair-closure-journal.jsonl`

**Interfaces:**

- Consumes: invalid attacker-controlled timestamp input and the existing `Instant.now().toString()` fallback.
- Produces: the same canonical current-time metadata fallback, reason `invalid_timestamp`, count one, and no raw timestamp value in trace.

- [ ] **Step 1: Recheck preimages and run the existing ingest baseline**

Require source/test/group identity equality exactly as Task 0 recorded, then run:

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.uaw.autolearn.ingest.TrainRagIngestServiceTest.metadataProjectionRejectsTypeConfusionAndUsesSafeNumericDefaults" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS.

- [ ] **Step 2: Add a dedicated invalid-timestamp RED test**

Create a focused test using the existing mock/`@TempDir` setup. Write one accepted JSONL row containing `"ts":"raw-timestamp-sentinel"`, ingest it, capture metadata, and assert:

```java
String projected = String.valueOf(meta.get("ts"));
assertEquals(projected, java.time.Instant.parse(projected).toString());
assertFalse(projected.contains("raw-timestamp-sentinel"));
assertEquals(
        "invalid_timestamp",
        TraceStore.getString("uaw.retrain.ingest.timestampFallbackReason"));
assertEquals(1L, TraceStore.getLong("uaw.retrain.ingest.timestampFallbackCount"));
assertFalse(TraceStore.getAll().toString().contains("raw-timestamp-sentinel"));
```

- [ ] **Step 3: Run focused RED**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.uaw.autolearn.ingest.TrainRagIngestServiceTest.invalidTimestampUsesCanonicalFallbackAndEmitsOnlyRedactedReason" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: FAIL only because the reason/count are absent; metadata remains a parseable fallback and raw-value assertions pass.

- [ ] **Step 4: Add the two bounded trace writes at the existing catch**

```java
} catch (RuntimeException ignored) {
    TraceStore.put("uaw.retrain.ingest.timestampFallbackReason", "invalid_timestamp");
    TraceStore.inc("uaw.retrain.ingest.timestampFallbackCount");
}
```

Keep the following `return Instant.now().toString();` unchanged. Do not change JSONL authority, metadata shape, acceptance, database behavior, or vector promotion.

- [ ] **Step 5: Run focused GREEN, the class, and mutation sensitivity**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.uaw.autolearn.ingest.TrainRagIngestServiceTest.invalidTimestampUsesCanonicalFallbackAndEmitsOnlyRedactedReason" `
  --tests "com.example.lms.uaw.autolearn.ingest.TrainRagIngestServiceTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS. Temporarily change `invalid_timestamp` to `invalid_timestamp_mutant`, require focused failure, restore, and rerun PASS.

- [ ] **Step 6: Run detector, compile, dependency, sourceSet, duplicate, secret, and scope gates**

Require the predecessor catch to classify `LOCAL_BREADCRUMB`, then run `checkSourceSetHygiene checkLangchain4jVersionPurity compileJava :app:generateDupFqcnExcludes`. Require no packaged-active duplicate for `TrainRagIngestService`, two-file cycle scope, and zero count-only secret hits.

- [ ] **Step 7: Record progress, append closure, and regenerate credit**

Create this group's RED/GREEN/baseline summaries, nonzero scoped-diff hash, and exact `PATCHED_UNVERIFIED` row. Append the matching closure only after all gates pass. Regenerate audit/scorecard and require counts `eventCount=7`, rejected `5`, verified closed `2`.

Suggested commit message: `fix: trace invalid training timestamp fallback`. Do not stage or commit.

### Task 7: Close `ScoringRunner` Without Changing Score Weights Or Its Null Sentinel

**Files:**

- Modify: `main/java/com/example/lms/tools/ScoringRunner.java:191-310,607-647,898-949`
- Modify: `src/test/java/com/example/lms/tools/ScoringRunnerTest.java`
- Modify privately: progress ledger and one group proof directory.
- Append after validation: `verification/structural-repair-closure-journal.jsonl`

**Interfaces:**

- Consumes: the bounded JUnit 5 source reader, invalid UTF-8 bytes, the existing `EvidenceOutcome`/`Check.reason` result path, and the repository-relative test FQCN mapping.
- Produces: the same null return sentinel from `readBoundedTestSource`, an invalid affected check, and a deterministic reason code followed by `:path=` and the concrete repository-relative JUnit source path, with no absolute path or exception body.

- [ ] **Step 1: Recheck preimages and run the scoring baseline**

Require source/test/group identity equality, then run:

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.tools.ScoringRunnerTest.everyFixtureProvenSilentCatchReducesTheScoreWhileLegitimateHandlingDoesNot" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS with the existing score and proven-silent-catch count.

- [ ] **Step 2: Add a malformed-test-source RED through the public score result**

Use `writeKeywordCompleteSourceFixture()`, overwrite only `src/test/java/com/example/lms/service/guard/PIISanitizerTest.java` with invalid UTF-8 bytes, then call `writeEvidence` and `runScore`. Extract only the `[score][pii]` line and assert:

```java
assertTrue(piiLine.contains(
        "reason=test-source-encoding-invalid:path="
        + "src/test/java/com/example/lms/service/guard/PIISanitizerTest.java"), piiLine);
assertTrue(piiLine.contains("MISSING (+0/10)"), piiLine);
assertFalse(piiLine.contains(root.toString()), piiLine);
assertFalse(piiLine.contains("Input length"), piiLine);
```

Import `assertFalse` if it is not already present. This test proves only the affected fixture loses its correctness points; it does not change the silent-catch scoring matrix.

- [ ] **Step 3: Run focused RED**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.tools.ScoringRunnerTest.malformedJUnitSourceFailsClosedWithRelativeDiagnostic" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: FAIL because the current boolean validator collapses the read failure to `metric-input-invalid` and discards the relative diagnostic.

- [ ] **Step 4: Add a typed qualification result while retaining the String/null reader**

Add:

```java
record TestSourceQualification(boolean qualified, String reasonCode, String pathToken) {
    static TestSourceQualification valid(String pathToken) {
        return new TestSourceQualification(true, "test-source-qualified", pathToken);
    }

    static TestSourceQualification invalid(String reasonCode, String pathToken) {
        return new TestSourceQualification(false, reasonCode, pathToken);
    }
}
```

Rename `hasQualifiedJUnitFiveTestSource` to `qualifiedJUnitFiveTestSource` and return this record. Calculate the path token from the FQCN before resolving it:

```java
String pathToken = "src/test/java/" + testFqcn.replace('.', '/') + ".java";
java.util.concurrent.atomic.AtomicReference<String> readReason =
        new java.util.concurrent.atomic.AtomicReference<>("test-source-contract-invalid");
String source = readBoundedTestSource(root, sourcePath, readReason::set);
if (source == null) {
    return TestSourceQualification.invalid(readReason.get(), pathToken);
}
```

Convert each existing `return false` in the package/class/JUnit structural checks to `TestSourceQualification.invalid("test-source-contract-invalid", pathToken)` and the final success to `TestSourceQualification.valid(pathToken)`.

- [ ] **Step 5: Emit the diagnostic through the existing outcome reason**

Change the read helper signature to `private static String readBoundedTestSource(Path root, Path candidate, java.util.function.Consumer<String> diagnostic)`. Keep the complete bounded/path/no-follow checks and UTF-8 decoder at current lines 608-641 byte-for-byte except for calls that need the new parameter; keep the return type and every null sentinel.

At the two catch branches use:

```java
} catch (CharacterCodingException ex) {
    diagnostic.accept("test-source-encoding-invalid");
    return null;
} catch (IOException | RuntimeException ex) {
    diagnostic.accept("test-source-read-failed");
    return null;
}
```

In `evaluateCheck`, replace the boolean call with:

```java
if ("test".equals(spec.commandField())) {
    TestSourceQualification qualification =
            qualifiedJUnitFiveTestSource(root, spec.commandOrTest());
    if (!qualification.qualified()) {
        return EvidenceOutcome.invalidTestSource(qualification);
    }
}
```

Add to `EvidenceOutcome`:

```java
static EvidenceOutcome invalidTestSource(TestSourceQualification qualification) {
    return new EvidenceOutcome(
            false,
            qualification.reasonCode() + ":path=" + qualification.pathToken());
}
```

Preserve all points, thresholds, fixture enumeration, `Check`, and `ScoreReport` fields.

- [ ] **Step 6: Run focused GREEN, scoring controls, and mutation sensitivity**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.tools.ScoringRunnerTest.malformedJUnitSourceFailsClosedWithRelativeDiagnostic" `
  --tests "com.example.lms.tools.ScoringRunnerTest.everyFixtureProvenSilentCatchReducesTheScoreWhileLegitimateHandlingDoesNot" `
  --tests "com.example.lms.tools.ScoringRunnerTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS. Temporarily return generic `EvidenceOutcome.invalid()` for the qualification failure, require the focused method to fail, restore, and rerun PASS.

- [ ] **Step 7: Run detector and all boundary gates**

Require the broad `IOException | RuntimeException` catch to classify `LOCAL_BREADCRUMB` because it records the stable diagnostic reason. Run sourceSet, LangChain4j, root compile, duplicate-FQCN, two-file scope, and count-only secret gates. Require no scoring-weight or threshold diff.

- [ ] **Step 8: Record progress, append closure, and regenerate credit**

Create the canonical proof summaries, nonzero scoped-diff hash, matching progress row, and terminal event. Regenerate audit/scorecard and require counts `eventCount=8`, rejected `5`, verified closed `3`.

Suggested commit message: `fix: surface bounded scoring source read failures`. Do not stage or commit.

### Task 8: Close `RagControlRuntimeAdapter` While Preserving Zero And HOLD Semantics

**Files:**

- Modify: `main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java:436-444`
- Modify: `src/test/java/com/example/lms/orchestration/control/RagControlRuntimeAdapterTest.java`
- Modify privately: progress ledger and one group proof directory.
- Append after validation: `verification/structural-repair-closure-journal.jsonl`

**Interfaces:**

- Consumes: a nonnumeric `attemptDropped` value, current zero fallback, lineage validation, `RagGuardProbeComposer`, and `TraceStore`.
- Produces: zero, `runtime_lineage_missing`, action `HOLD`, reason `invalid_non_negative_int`, count one, and no raw numeric input.

- [ ] **Step 1: Recheck preimages and run the complete-lineage control**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.orchestration.control.RagControlRuntimeAdapterTest.completeRuntimeProofProducesAllSevenTypedStages" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS after the source/test/group preimage checks.

- [ ] **Step 2: Add a focused nonnumeric-attempt RED test**

Use `copyWith(completeAttempts().get(0), "attemptDropped", "raw-count-sentinel")`, call `adapter.collect`, compose the plan, and assert inside `try/finally` with `TraceStore.clear()`:

```java
assertEquals(RagActionPlan.Action.HOLD, plan.action());
assertFalse(plan.lineageComplete());
assertEquals("runtime_lineage_missing", plan.reasonCode());
assertEquals(
        "invalid_non_negative_int",
        TraceStore.getString("rag.control.runtime.nonNegativeIntFallbackReason"));
assertEquals(1L, TraceStore.getLong("rag.control.runtime.nonNegativeIntFallbackCount"));
assertFalse(TraceStore.getAll().toString().contains("raw-count-sentinel"));
```

- [ ] **Step 3: Run the method and require reason/count RED**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.orchestration.control.RagControlRuntimeAdapterTest.nonnumericAttemptDropFailsClosedWithRedactedReason" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: HOLD assertions pass; reason/count assertions fail because the catch currently returns zero silently.

- [ ] **Step 4: Add the bounded trace outcome without changing parsing**

```java
} catch (RuntimeException ignored) {
    TraceStore.put(
            "rag.control.runtime.nonNegativeIntFallbackReason",
            "invalid_non_negative_int");
    TraceStore.inc("rag.control.runtime.nonNegativeIntFallbackCount");
    return 0;
}
```

Do not change runtime-input schema, zero fallback, lineage proof, HOLD classification, CFVM, AutoLearn, or RAG arbitration.

- [ ] **Step 5: Run focused GREEN, full class, and mutation sensitivity**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.orchestration.control.RagControlRuntimeAdapterTest.nonnumericAttemptDropFailsClosedWithRedactedReason" `
  --tests "com.example.lms.orchestration.control.RagControlRuntimeAdapterTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS. Temporarily change the fallback to `1`, require the behavior test's HOLD assertion to fail, restore zero, and rerun PASS.

- [ ] **Step 6: Run detector, boundary, duplicate, secret, and scope gates**

Require the catch to classify `LOCAL_BREADCRUMB`, no predecessor fingerprint, root compile success, sourceSet/dependency purity, no packaged-active duplicate, only this source/test pair changed in the cycle, and zero new secret hits.

- [ ] **Step 7: Record progress, append closure, and regenerate credit**

Create proof summaries and event-baseline proof, append the matching progress row then terminal event, and regenerate. Require counts `eventCount=9`, rejected `5`, verified closed `4`.

Suggested commit message: `fix: trace rag control numeric fallback`. Do not stage or commit.

### Task 9: Close `TrustedProxyPolicy` With Fail-Closed IPv6 Diagnostics

**Files:**

- Modify: `main/java/com/example/lms/web/TrustedProxyPolicy.java:1-12,128-138`
- Modify: `src/test/java/com/example/lms/web/ClientOwnerKeyResolverTest.java:1-94`
- Modify privately: progress ledger and one group proof directory.
- Append after validation: `verification/structural-repair-closure-journal.jsonl`

**Interfaces:**

- Consumes: trusted immediate proxy, malformed IPv6 literal, owner-key resolver, and request-local `TraceStore`.
- Produces: `false`, forwarded owner not trusted, reason `invalid_ipv6`, count one, and no address/header value.

- [ ] **Step 1: Recheck preimages and run existing trust controls**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.web.ClientOwnerKeyResolverTest.trustedPeerRejectsMalformedFirstForwardedAddress" `
  --tests "com.example.lms.web.ClientOwnerKeyResolverTest.trustedPeerUsesOnlyTheFirstValidatedForwardedAddress" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS after the source/test/group preimage checks.

- [ ] **Step 2: Add a malformed-IPv6 behavior RED**

Import `TraceStore` and `assertFalse`. In a `try/finally` that clears trace, build a request with remote peer `127.0.0.1`, the existing user-agent token, and first forwarded address `1:2:3:4:5:6:7:8:9`. Compare it to an otherwise identical direct-proxy request and assert:

```java
assertEquals(
        new ClientOwnerKeyResolver(directProxy).ownerKey(),
        new ClientOwnerKeyResolver(
                malformed,
                new TrustedProxyPolicy("127.0.0.0/8")).ownerKey());
assertEquals(
        "invalid_ipv6",
        TraceStore.getString("web.trustedProxy.validationFallbackReason"));
assertEquals(1L, TraceStore.getLong("web.trustedProxy.validationFallbackCount"));
assertFalse(TraceStore.getAll().toString().contains("1:2:3:4:5:6:7:8:9"));
```

- [ ] **Step 3: Run focused RED**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.web.ClientOwnerKeyResolverTest.malformedIpv6ForwardedAddressFailsClosedWithRedactedReason" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: owner equality/no-leak passes; reason/count fail because `isIpv6Literal` silently returns false.

- [ ] **Step 4: Add the request-local bounded diagnostic**

Import `com.example.lms.search.TraceStore` and patch only the catch:

```java
} catch (Exception ignored) {
    TraceStore.put("web.trustedProxy.validationFallbackReason", "invalid_ipv6");
    TraceStore.inc("web.trustedProxy.validationFallbackCount");
    return false;
}
```

Do not change CIDR validation, proxy precedence, forwarded-header selection, owner-key derivation, or any raw IP logging.

- [ ] **Step 5: Run focused GREEN, full class, and mutation sensitivity**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.web.ClientOwnerKeyResolverTest.malformedIpv6ForwardedAddressFailsClosedWithRedactedReason" `
  --tests "com.example.lms.web.ClientOwnerKeyResolverTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS. Temporarily return `true` in the catch, require the owner-key equality test to fail, restore `false`, and rerun PASS.

- [ ] **Step 6: Run detector and security boundary gates**

Require the catch to classify `LOCAL_BREADCRUMB`; run sourceSet/dependency/root-compile/duplicate checks. Run a count-only scan of both changed files and proof summaries and require zero secret/IP/header raw-value hits. Inspect the diff and require no change outside the import, catch, and focused test.

- [ ] **Step 7: Record progress, append closure, and regenerate credit**

Append the validated progress row and terminal event only after fail-closed behavior and privacy gates pass. Regenerate and require counts `eventCount=10`, rejected `5`, verified closed `5`.

Suggested commit message: `fix: trace malformed ipv6 trust fallback`. Do not stage or commit.

### Task 10: Close `BraveSearchService` With Offline Response-Shape Evidence

**Files:**

- Modify: `main/java/com/example/lms/service/web/BraveSearchService.java:1576-1585`
- Create: `src/test/java/com/example/lms/service/web/BraveSearchServiceResponseShapeTest.java`
- Read only: `src/test/java/com/example/lms/service/web/BraveSearchServiceOutboundHeaderTest.java`
- Modify privately: progress ledger and one group proof directory.
- Append after validation: `verification/structural-repair-closure-journal.jsonl`

**Interfaces:**

- Consumes: offline `MockRestServiceServer`, malformed JSON body, existing fail-soft `BraveSearchResult`, and provider-local `TraceStore`.
- Produces: status `EXCEPTION`, message `json-parse-error`, reason `invalid_json_shape`, count one, no body content, and no provider-success claim.

- [ ] **Step 1: Recheck source preimage and prove the focused test-file choice**

Require source/group identity equality. Confirm the outbound-header test still owns header/privacy and timeout contracts rather than response shape. If a concurrent preimage already added an isolated response-shape class, hold this group for target reconciliation instead of creating a duplicate.

- [ ] **Step 2: Write the new offline RED test class**

Create a package-private JUnit 5 class with `@AfterEach` clearing `TraceStore`, `TimeBudgetContext`, and MDC. Reuse the same property values as `BraveSearchServiceOutboundHeaderTest.enabledService()` in a local helper. Bind `MockRestServiceServer` to the service's `RestTemplate` and respond with malformed body `{"web":"raw-body-sentinel"`.

Assert:

```java
server.verify();
assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
assertEquals("json-parse-error", result.message());
assertEquals("json-parse-error", TraceStore.getString("web.brave.failureReason"));
assertEquals(
        "invalid_json_shape",
        TraceStore.getString("web.brave.responseShapeFallbackReason"));
assertEquals(1L, TraceStore.getLong("web.brave.responseShapeFallbackCount"));
assertFalse(TraceStore.getAll().toString().contains("raw-body-sentinel"));
assertFalse(Boolean.TRUE.equals(TraceStore.get("web.brave.providerSuccess")));
```

The server is local and deterministic; do not issue a Brave or other provider request.

- [ ] **Step 3: Run focused RED**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.service.web.BraveSearchServiceResponseShapeTest.malformedBodyFailsSoftWithRedactedShapeReason" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: result/failure/no-leak assertions pass; new shape reason/count fail because the helper catch has no local breadcrumb.

- [ ] **Step 4: Add only the provider-local catch breadcrumb**

```java
} catch (Exception ignored) {
    TraceStore.put("web.brave.responseShapeFallbackReason", "invalid_json_shape");
    TraceStore.inc("web.brave.responseShapeFallbackCount");
    return false;
}
```

Do not change calls, headers, token flow, endpoint, result synthesis, timeout/QPS/quota/breaker behavior, cache behavior, fallback precedence, or body-hash logging.

- [ ] **Step 5: Run focused GREEN, provider-local controls, and mutation sensitivity**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.service.web.BraveSearchServiceResponseShapeTest" `
  --tests "com.example.lms.service.web.BraveSearchServiceOutboundHeaderTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: PASS with no external request. Temporarily return `true` from the catch, require the malformed-body status assertion to fail, restore `false`, and rerun PASS.

- [ ] **Step 6: Enforce the lane-local three-file boundary and all gates**

Require the catch to classify `LOCAL_BREADCRUMB`, root compile/sourceSet/dependency/duplicate gates to pass, and changed scope to remain one owner plus one focused test (the existing trace owner is already `TraceStore`, so no third production file is needed). Require zero count-only secret/body hits. If proof requires another production/contract owner, stop only Brave as `evidence_needed` and preserve the prior five closures.

- [ ] **Step 7: Record progress, append closure, and regenerate credit**

When all gates pass, create the proof set, append progress then terminal event, and regenerate. Require counts `eventCount=11`, rejected `5`, verified closed `6`, and provider attempt `not_observed`.

Suggested commit message: `fix: trace brave response shape failures`. Do not stage or commit.

### Task 11: Run The Full Isolated Verification Ladder And Determinism Repeat

**Files:**

- Verify every declared changed target.
- Regenerate: `verification/dynamic-rag-harmony-pressure-metrics.json`
- Regenerate: `verification/structural-design-baseline.json`
- Regenerate: `verification/structural-design-debt-ledger.jsonl`
- Regenerate: `verification/dynamic-rag-quant-audit-metrics.json`
- Regenerate: `verification/source-health-scorecard.json`
- Create privately: `.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/isolated-final-report.md`

**Interfaces:**

- Consumes: all infrastructure changes, five rejection events, up to six closure events, and exact isolated postimages.
- Produces: fresh focused/full test results, semantic repeatability proof, duplicate/sourceSet/dependency/compile/diff/secret proof, and a bounded isolated integration package.

- [ ] **Step 1: Load `superpowers:verification-before-completion` and run all focused Python contracts**

```powershell
python -X utf8 -m unittest `
  scripts.test_harmony_catch_contract `
  scripts.test_harmony_pressure_report `
  scripts.test_dynamic_rag_quant_audit `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_main_current_linked_quant_bundle_writes_scorecard `
  scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_quant_bundle_rejects_tampered_closure_history
python -X utf8 -m unittest `
  scripts.test_awx_mcp_toolbox.HarmonyBreakStatusTest.test_harmony_scan_recognizes_exact_bounded_catch_contracts `
  scripts.test_awx_mcp_toolbox.HarmonyBreakStatusTest.test_harmony_scan_bounded_contracts_require_exact_path_and_method
```

Expected: all pass with current output from this run.

- [ ] **Step 2: Run every affected Java test class in one bounded Gradle invocation**

```powershell
& .\gradlew.bat test `
  --tests "com.example.lms.ensemble.StochasticParamSamplerTest" `
  --tests "com.example.lms.uaw.autolearn.ingest.TrainRagIngestServiceTest" `
  --tests "com.example.lms.tools.ScoringRunnerTest" `
  --tests "com.example.lms.orchestration.control.RagControlRuntimeAdapterTest" `
  --tests "com.example.lms.web.ClientOwnerKeyResolverTest" `
  --tests "com.example.lms.service.web.BraveSearchServiceResponseShapeTest" `
  --tests "com.example.lms.service.web.BraveSearchServiceOutboundHeaderTest" `
  --no-daemon --project-cache-dir $projectCache
```

Expected: all selected tests pass. Record test counts and exit code, not long raw logs.

- [ ] **Step 3: Run repository boundary gates**

```powershell
& .\gradlew.bat checkSourceSetHygiene checkLangchain4jVersionPurity compileJava :app:classes `
  --no-daemon --project-cache-dir $projectCache
```

Expected: all pass under Java 17 and split Desktop output/cache settings.

- [ ] **Step 4: Generate the complete public chain twice**

```powershell
& .\gradlew.bat harmonyPressureReport dynamicRagQuantAudit sourceHealthScorecard `
  --no-daemon --project-cache-dir $projectCache
```

After run one, retain the baseline ID, ordered ledger core rows, event IDs, group/status pairs, history summary, artifact links, semantic artifact hash, duplicate semantic hash, and source-health quantitative-bundle status. Run the exact command again and compare those values byte-for-byte after removing only `generatedAt` envelope fields.

Expected: identical semantic values. A mismatch is `semantic-nondeterminism` and blocks canonical integration.

- [ ] **Step 5: Assert final group/credit arithmetic**

If all six cycles passed, require exactly:

```text
journal events=11
rejected false-positive groups=5
verified-closed groups=6
repair credit=6
closure target gap=894
```

Require five rejected and six closed historical rows to preserve original source issue/group/fingerprint/numeric evidence, and require no original predecessor fingerprint in current detector output. If one cycle is held, reduce only the verified/event totals for that cycle and retain exact earlier closures.

- [ ] **Step 6: Inspect diff, ownership, and generated-artifact scope**

Run `git diff --check` limited to declared targets. Compare changed paths against the responsibility map. Parse the split-build duplicate evidence and require no new packaged-active FQCN/component/route/helper/prompt-boundary owner. Require the five rejected application owners to have their Task 0 hashes exactly.

- [ ] **Step 7: Run count-only secret/privacy scans**

Scan every changed source/test/script/build/journal file plus the five public artifacts and proof summaries. Print only:

```text
changedTargetSecretPatternHits=0
publicArtifactSecretPatternHits=0
proofSummarySecretPatternHits=0
rawSensitiveValueHits=0
```

Require no raw invalid timestamp, IP/header, malformed Brave body, injected draw value, absolute proof path, credential, prompt, provider response, or exception body.

- [ ] **Step 8: Write the isolated final report**

Record exact commands, exit codes, test counts, original/current baseline IDs, artifact hashes, journal/proof hashes, event IDs, group states, credit, closure gap, staged count, lock/lease state, changed-path list, and external statuses `Browser=not_run`, `Computer=not_run`, `providerAttempt=not_observed`, `Supabase=not_run`. Do not include long logs or raw values.

Suggested commit message: `test: verify structural debt closure wave`. Do not stage or commit.

### Task 12: Reconcile Verified Hunks Into The Canonical Desktop Root And Reprove Everything

**Files:**

- Apply only the verified tracked deltas listed in the responsibility map.
- Recreate only allowlisted private proof/intake/progress summary bytes under the canonical proof root.
- Regenerate the five canonical public artifacts.
- Create: `.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave/final-desktop-report.md`

**Interfaces:**

- Consumes: Task 0 canonical preimages, Task 11 isolated postimages/hashes, isolated proof bytes, and isolated semantic results.
- Produces: canonical postimages equal to isolated verified postimages, fresh Desktop verification, released leases, and a truthful wave result while the parent approximately-1,000 goal remains active.

- [ ] **Step 1: Release the isolated lease in `finally` and acquire the canonical lease**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\source_edit_session.ps1') `
  -Action end -Role desktop -Root $isolatedRoot `
  -Topic structural-debt-verified-closure-wave -OwnerId $isolatedOwnerId
if ($LASTEXITCODE -ne 0) { throw 'isolated-source-lease-release-failed' }

$canonicalOwnerId = [guid]::NewGuid().ToString('N')
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\source_edit_session.ps1') `
  -Action begin -Role desktop -Root $canonicalRoot `
  -Topic structural-debt-verified-closure-wave -OwnerId $canonicalOwnerId -TtlMinutes 180
if ($LASTEXITCODE -ne 0) { throw 'canonical-source-lease-begin-failed' }
```

If execution stops before this task, release the isolated lease in the same `finally` discipline.

- [ ] **Step 2: Recheck canonical ownership and every preimage before the first hunk**

Repeat branch/HEAD, staged count, real index lock, PatchDrop inventory, lease, Java 17, and declared-target hash checks. Each canonical target must still equal its Task 0 preimage. A changed target holds only its overlapping integration lane; do not overwrite it.

- [ ] **Step 3: Apply exact verified hunks with `apply_patch`**

Apply script/build/test/source/journal deltas hunk-by-hunk. For existing dirty files, anchor every patch on the frozen preimage lines and preserve unrelated hunks. Create only the approved new shared module/test, zero-based journal history, and Brave focused test. Never copy whole source files from the isolated root.

After each file, require canonical SHA-256 to equal the Task 11 isolated postimage. Stop at the first mismatch and preserve already verified independent files.

- [ ] **Step 4: Recreate allowlisted private evidence with exact bytes**

Use `apply_patch` to insert the canonical JSON/NDJSON proof, intake, progress, and report-summary bytes read from the isolated proof root. Do not copy caches or raw logs. Recompute every proof file hash and require it to match the journal before invoking the producer.

- [ ] **Step 5: Configure canonical Desktop-isolated Gradle paths and rerun focused tests**

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-closure-wave-final'
$env:GRADLE_USER_HOME = Join-Path $canonicalRoot 'var\gradle-user-home\structural-closure-wave-final'
$canonicalProjectCache = Join-Path $canonicalRoot 'var\gradle-project-cache\structural-closure-wave-final'
```

Run the exact Python tests from Task 11 and all seven Java test classes from Task 11 using `$canonicalProjectCache`. These are fresh canonical results; isolated GREEN is supporting evidence only.

- [ ] **Step 6: Rerun canonical boundary, generation, and determinism gates**

Run `checkSourceSetHygiene`, `checkLangchain4jVersionPurity`, `compileJava`, `:app:classes`, then `harmonyPressureReport dynamicRagQuantAudit sourceHealthScorecard` twice. Require canonical semantic rows/hashes to match between its two runs and require source/test/script/journal postimages to equal the isolated verified postimages.

The current canonical baseline ID may differ from the isolated event baseline ID because root/branch identity differs; this is valid. Every event's retained `eventBaselineProof` must still validate, and every current target hash must equal its event postimage.

- [ ] **Step 7: Repeat canonical scope, diff, duplicate, and privacy checks**

Run declared-target `git diff --check`, changed-path comparison, duplicate owner/FQCN checks, and the same count-only scans. Require staged count zero, no new lock, and no unexpected tracked/untracked source target.

- [ ] **Step 8: Write the final Desktop report and report lane-local holds honestly**

If all cycles passed, report exactly six new verified closures, five rejected false positives, and closure gap 894. If any group held, record `holdScope`, `firstBlockingRule`, `blockingEvidence`, `independentWorkCompleted`, `repositoryWideHold=false`, and one next command for that group. Use `repositoryWideHold=true` only for predecessor identity loss, an every-target writer conflict, or detector/producer failure that invalidates every disposition.

State explicitly that the approximately-1,000 parent goal remains active. Do not mark the parent goal complete.

- [ ] **Step 9: Release the canonical lease in `finally`**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $canonicalRoot '__patch_drop__\source_edit_session.ps1') `
  -Action end -Role desktop -Root $canonicalRoot `
  -Topic structural-debt-verified-closure-wave -OwnerId $canonicalOwnerId
if ($LASTEXITCODE -ne 0) { throw 'canonical-source-lease-release-failed' }
```

Suggested commit message: `feat: close verified structural debt wave`. Do not stage or commit.

## Execution Stop Conditions

Stop before the next dependent write when any of these occurs:

- predecessor baseline/ledger/metrics identity differs from frozen intake;
- stable preflight is not `APPLY` with score at least 50;
- target preimage, writer, index lock, PatchDrop queue, branch owner, or lease changes;
- the two detector consumers disagree;
- RED fails for compilation/fixture reasons rather than the missing outcome;
- GREEN, full owner class, sourceSet, dependency, compile, duplicate, diff, or secret gate fails;
- journal, proof, progress, event chain, fingerprint disposition, staged-byte validation, or publication rollback fails;
- a cycle's exact predecessor fingerprint remains active;
- Brave requires more than its approved owner/test/existing-trace boundary.

Earlier independently verified terminal events remain valid on a later lane-local hold. No held or merely patched group receives credit.

## Acceptance-Criteria Coverage Map

- Approved criterion 1 (predecessor identity) — Task 0 Steps 1-4 and Task 12 Step 2.
- Approved criterion 2 (one brace-aware owner and consumer equality) — Tasks 1-2 and Task 11 Step 1.
- Approved criterion 3 (five zero-credit rejected owners with no source diff) — Task 4 Steps 6-10 and Task 11 Steps 5-6.
- Approved criterion 4 (independent preimage, diff, RED, GREEN, fingerprint, gates, and event) — Tasks 5-10, each final two steps.
- Approved criterion 5 (no manifestation/line/test/prose credit) — Global Constraints, Task 3 summary semantics, and Task 11 Step 5.
- Approved criterion 6 (valid terminal acceptance, invalid rejection, rollback, and honest counts) — Task 3 Steps 2-10.
- Approved criterion 7 (unchanged repeat determinism) — Task 3 Step 10 and Task 11 Step 4.
- Approved criterion 8 (focused/sourceSet/dependency/compile/diff/duplicate/secret/Desktop gates) — Tasks 5-12.
- Approved criterion 9 (zero public secret/raw-value hits) — every Java cycle's privacy assertion, Task 11 Step 7, and Task 12 Step 7.
- Approved criterion 10 (external surfaces stay unclaimed) — Global Constraints and Task 11 Step 8.
- Approved criterion 11 (six closed, five rejected, gap 894 when all pass) — Task 11 Step 5 and Task 12 Step 8.
- Approved criterion 12 (lane-local hold preserves prior closures) — Execution Stop Conditions and Task 12 Step 8.
- Approved criterion 13 (parent approximately-1,000 goal remains active) — Global Constraints and Task 12 Step 8.

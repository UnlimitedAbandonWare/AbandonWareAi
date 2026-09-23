# Demo-1 1,000-Candidate Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic, fail-closed 1,000-row candidate-probe ledger and use it to promote and repair only freshly reproduced demo-1 defects during the approved nine-hour maximum campaign.

**Architecture:** Two standard-library Python CLIs produce and independently validate canonical NDJSON plus a manifest without touching application source or external systems. Once the ledger is valid, a bounded promotion loop selects clean active-source candidates, requires a fresh RED and the repository three-way/source-owner gates, applies one minimal patch, and verifies focused through Browser-visible surfaces.

**Tech Stack:** Python 3 standard library, `unittest`, PowerShell, Git read-only status, Gradle 8.7/Java 17, Spring Boot, in-app Browser.

**Spec:** `docs/superpowers/specs/2026-08-30-demo1-1000-candidate-probe-design.md`

## Global Constraints

- Active roots are exactly `main/java`, `main/resources`, `app/src/main/java_clean`, and `app/src/main/resources` unless fresh Gradle evidence changes them.
- Candidate rows come only from active Java roots and remain `UNTRIAGED`; a static match is not a defect.
- The seed is the required ASCII token `20260830`; `maxRecords` is `1000`.
- The tools use Python standard library only and make no network, provider, runtime, Browser, Gradle, database, or Git-mutation calls.
- Dirty and untracked active paths are `excluded_dirty` and never repair eligible.
- No raw source excerpt, prompt, response, credential, authorization header, cookie, URL query, or exact financial value is persisted.
- Application-source mutation requires one frozen snapshot and exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`, followed by the existing source-owner/lease/preimage guard.
- Preserve all unrelated changes in the current authoritative Desktop canonical checkout.
- Keep `dev.langchain4j` exactly at `1.0.1`; add no production dependency.
- Do not commit, push, deploy, mutate a database, persist credentials, or call a paid provider without separate operation-level authority.
- The in-app Browser remains open; final Browser proof must use the final task-owned runtime, not a stale pre-patch artifact.

## File Structure

- Create `scripts/random_probe_candidate_ledger.py`: active-source discovery, frozen status parsing, bounded probe detection, deterministic ordering, and atomic artifact publication.
- Create `scripts/test_random_probe_candidate_ledger.py`: real temporary-file producer behavior and determinism tests.
- Create `scripts/validate_random_probe_candidate_ledger.py`: independent v1 invariant validation and fail-closed legacy Markdown structural checking.
- Create `scripts/test_validate_random_probe_candidate_ledger.py`: corruption, safety, ordering, hash, and legacy ambiguity tests.
- Modify `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorDependencyHonestyTest.java` only if its target preflight is stable `APPLY`: observe the seven-argument DPP overload while preserving the production stable-key call.
- Write generated evidence only below `var/codex-smoke/goal-random-probe-20260830/`.

---

### Task 1: Producer Contract RED

**Files:**
- Create: `scripts/test_random_probe_candidate_ledger.py`
- Test target not yet present: `scripts/random_probe_candidate_ledger.py`

**Interfaces:**
- Consumes: temporary repository roots, explicit porcelain text, seed `str`, and maximum `int`.
- Produces expectation for `build_ledger(root: Path, seed: str, max_records: int, status_text: str) -> tuple[list[dict], dict]`.

- [ ] **Step 1: Write fixture builders using real temporary files**

```python
def write_java(root: Path, relative: str, body: str) -> None:
    path = root / Path(relative)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")

def make_status(*lines: str) -> str:
    return "\n".join(lines) + ("\n" if lines else "")
```

- [ ] **Step 2: Add one behavior test per break**

Add tests named:

```text
test_same_snapshot_and_seed_are_byte_deterministic
test_filesystem_enumeration_order_does_not_change_rows
test_seed_changes_rank_not_identity_universe
test_repeated_matches_collapse_to_one_identity_with_bounded_line_hints
test_inactive_generated_and_test_roots_never_emit_rows
test_dirty_and_untracked_rows_are_excluded_dirty
test_output_paths_under_source_or_tool_roots_are_rejected
test_seed_and_record_bounds_are_fail_closed
```

The deterministic test serializes rows with `canonical_ndjson_bytes(rows)` and compares literal bytes. The dirty test uses one ` M main/java/...` and one `?? app/src/main/java_clean/...` porcelain row and asserts neither has `eligibility == "candidate"`.

- [ ] **Step 3: Run the RED suite**

Run:

```powershell
python -m unittest scripts.test_random_probe_candidate_ledger -v
```

Expected: import/module failure for `random_probe_candidate_ledger`, proving the implementation does not exist yet.

- [ ] **Step 4: Record the RED command and first error in the task evidence ledger**

Do not create a commit. Record only command, exit code, first expected failure, and test count in the turn evidence.

### Task 2: Minimal Deterministic Producer GREEN

**Files:**
- Create: `scripts/random_probe_candidate_ledger.py`
- Test: `scripts/test_random_probe_candidate_ledger.py`

**Interfaces:**
- Produces:

```python
def normalize_repo_path(path: str) -> str: ...
def parse_porcelain(status_text: str) -> dict[str, str]: ...
def discover_active_java(root: Path) -> list[tuple[str, str]]: ...
def probe_java(text: str) -> list[tuple[str, list[int], int]]: ...
def build_ledger(root: Path, seed: str, max_records: int,
                 status_text: str) -> tuple[list[dict], dict]: ...
def canonical_json_bytes(value: object) -> bytes: ...
def canonical_ndjson_bytes(rows: list[dict]) -> bytes: ...
def validate_output_path(root: Path, output: Path) -> Path: ...
def publish_artifacts(output: Path, manifest_output: Path,
                      rows: list[dict], manifest: dict) -> None: ...
```

- [ ] **Step 1: Add exact schema and safety constants**

```python
ROW_SCHEMA = "awx.random-probe-candidate-row.v1"
MANIFEST_SCHEMA = "awx.random-probe-candidate-ledger.v1"
ACTIVE_ROOTS = ("main/java", "main/resources", "app/src/main/java_clean", "app/src/main/resources")
CANDIDATE_ROOTS = ("main/java", "app/src/main/java_clean")
MAX_LINE_HINTS = 8
SEED_RE = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
```

- [ ] **Step 2: Implement normalization, frozen status parsing, and active-root discovery**

Reject absolute paths, `..`, URI-like values, backslash ambiguity after normalization, `.git`, build outputs, scripts, tests, and inactive Java roots. Run `git status --porcelain=v1 --untracked-files=all` only in `main()` when tests did not inject `status_text`.

- [ ] **Step 3: Implement bounded probe families**

Use locally compiled regexes for the five evidence families. Collapse all matches for one `(path, family)` into one record. Store at most eight sorted positive line numbers and the full count. If no family matches, emit one `no_static_pattern` observation.

- [ ] **Step 4: Implement identity, rank, canonical sorting, and manifest counts**

```python
identity_material = f"{repo_path}\0{probe_family}".encode("utf-8")
identity_hash = hashlib.sha256(identity_material).hexdigest()
rank_material = f"{seed}\0{repo_path}\0{probe_family}".encode("utf-8")
rank_key = hashlib.sha256(rank_material).hexdigest()
```

Sort by `(rankKey, repoPath, probeFamily)`, truncate to `max_records`, then assign contiguous ordinals. Keep `generatedAt` outside the deterministic comparison surface.

- [ ] **Step 5: Implement refusal-to-overwrite publication**

Validate both output paths before any write. Create same-directory temporary files with `tempfile.NamedTemporaryFile(delete=False)`, flush and `os.fsync`, then `os.replace`. Refuse an existing output, manifest, `.sha256`, or `.ready` path. Sidecars contain only canonical hashes and readiness metadata.

- [ ] **Step 6: Implement the CLI**

Use `argparse` with required `--root`, `--seed`, and `--max-records`; require exactly one of `--output` and `--stdout`; require `--manifest-output` only in file mode. Emit reason-code JSON to stderr and exit `2` for contract errors.

- [ ] **Step 7: Run producer GREEN and mutation checks**

Run:

```powershell
python -m unittest scripts.test_random_probe_candidate_ledger -v
```

Then temporarily change one expected rank/hash literal or dirty eligibility assertion in memory through a test fixture, verify that the relevant test fails, restore it, and rerun GREEN. Do not edit production solely for the mutation check.

### Task 3: Independent Validator RED

**Files:**
- Create: `scripts/test_validate_random_probe_candidate_ledger.py`
- Test target not yet present: `scripts/validate_random_probe_candidate_ledger.py`

**Interfaces:**
- Consumes: producer-generated NDJSON/manifest bytes and corrupted literal fixtures.
- Produces expectation for `validate_v1(ndjson_path: Path, manifest_path: Path, expected_max_records: int) -> dict` and `inspect_legacy_markdown(path: Path) -> dict`.

- [ ] **Step 1: Reuse producer only as a fixture producer, never as validator logic**

Create one valid temporary ledger through the producer. Hand-mutate parsed literal rows and manifests for each corruption case; the validator implementation must not import producer validation helpers.

- [ ] **Step 2: Add corruption and ambiguity tests**

Add tests named:

```text
test_valid_v1_ledger_recomputes_all_counts_and_hashes
test_duplicate_candidate_id_is_rejected
test_duplicate_identity_pair_is_rejected
test_noncontiguous_ordinal_is_rejected
test_rank_order_or_rank_hash_corruption_is_rejected
test_path_traversal_and_inactive_root_are_rejected
test_dirty_row_marked_candidate_is_rejected
test_non_untriaged_status_is_rejected
test_manifest_count_or_ndjson_hash_mismatch_is_rejected
test_prohibited_secret_pattern_reports_count_only
test_current_legacy_markdown_is_evidence_needed_not_valid
```

Each invalid test asserts a stable reason code and exit semantics, never the raw matching value.

- [ ] **Step 3: Run the validator RED suite**

Run:

```powershell
python -m unittest scripts.test_validate_random_probe_candidate_ledger -v
```

Expected: import/module failure for `validate_random_probe_candidate_ledger`.

### Task 4: Minimal Independent Validator GREEN

**Files:**
- Create: `scripts/validate_random_probe_candidate_ledger.py`
- Test: `scripts/test_validate_random_probe_candidate_ledger.py`

**Interfaces:**
- Produces:

```python
class LedgerValidationError(ValueError):
    reason_code: str

def read_ndjson(path: Path) -> tuple[list[dict], bytes]: ...
def validate_v1(ndjson_path: Path, manifest_path: Path,
                expected_max_records: int) -> dict: ...
def inspect_legacy_markdown(path: Path) -> dict: ...
```

- [ ] **Step 1: Define exact allowed field sets and stable failure codes**

Codes include `schema-invalid`, `field-set-invalid`, `count-out-of-range`, `ordinal-gap`, `duplicate-id`, `duplicate-identity`, `path-invalid`, `hash-invalid`, `rank-order-invalid`, `dirty-eligible`, `status-claim-invalid`, `manifest-count-mismatch`, `ndjson-hash-mismatch`, and `secret-pattern-hit`.

- [ ] **Step 2: Parse UTF-8 and validate rows independently**

Reject blank/non-object lines, non-canonical paths, absolute paths, traversal, inactive roots, invalid SHA-256, noncontiguous ordinals, duplicate identities, dirty-as-candidate state, non-`UNTRIAGED` claims, and wrong sort order.

- [ ] **Step 3: Recompute manifest counts and deterministic hashes**

Recompute NDJSON SHA-256, emitted/eligibility/family/root counts, unique identities, and required false flags. Do not trust producer-provided counts.

- [ ] **Step 4: Implement count-only secret scanning**

Scan canonical bytes for the repository prohibited token families. On a hit, return only `reasonCode=secret-pattern-hit` and `secretPatternHits=<count>`.

- [ ] **Step 5: Implement fail-closed legacy inspection**

Count primary table-like IDs and duplicate IDs, detect historical closure/superseding markers, and return `verdict=EVIDENCE_NEEDED`. Never return `VALID` for free-form Markdown without a v1 manifest.

- [ ] **Step 6: Run validator and combined GREEN suites**

Run:

```powershell
python -m unittest scripts.test_validate_random_probe_candidate_ledger -v
python -m unittest scripts.test_random_probe_candidate_ledger scripts.test_validate_random_probe_candidate_ledger -v
```

Expected: every named test passes with exit code `0` and no network/runtime process.

### Task 5: Produce and Validate the Real 1,000-Row Ledger

**Files:**
- Create: `var/codex-smoke/goal-random-probe-20260830/random-probe-candidates.ndjson`
- Create: `var/codex-smoke/goal-random-probe-20260830/random-probe-candidates.manifest.json`
- Create: adjacent hash/readiness sidecars.

**Interfaces:**
- Consumes: current active source snapshot and one frozen Git porcelain snapshot.
- Produces: validated v1 ledger for the defect-promotion queue.

- [ ] **Step 1: Run producer against the current authoritative checkout**

```powershell
python scripts\random_probe_candidate_ledger.py --root . --seed 20260830 --max-records 1000 --output var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.ndjson --manifest-output var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.manifest.json
```

- [ ] **Step 2: Run independent v1 validation**

```powershell
python scripts\validate_random_probe_candidate_ledger.py --input var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.ndjson --manifest var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.manifest.json --expected-max-records 1000
```

- [ ] **Step 3: Run legacy fail-closed inspection**

```powershell
python scripts\validate_random_probe_candidate_ledger.py --legacy-markdown data\agent-handoff\codex\report\random-probe-100-20260827-ledger.md
```

Expected: v1 `VALID`; legacy `EVIDENCE_NEEDED` with structural counts only.

- [ ] **Step 4: Replay without overwriting**

Run the producer into a second new directory `var/codex-smoke/goal-random-probe-20260830-replay/`, remove `generatedAt` from both manifest comparisons in a read-only check, and prove byte-identical NDJSON plus identical deterministic manifest fields.

- [ ] **Step 5: Run a count-only secret scan and inspect the scoped diff**

Scan only the two scripts, two tests, spec, plan, and generated manifest/NDJSON. Report `secretPatternHits=0`; do not print matches. Run `git diff --check` for tracked paths and `git diff --no-index -- NUL <new-file>` only when a new-file review is needed.

### Task 6: Freeze the First Source-Edit Preflight

**Files:**
- Read: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`
- Potentially modify: `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorDependencyHonestyTest.java`
- Evidence only: task-local preflight packet under `var/codex-smoke/goal-random-probe-20260830/` if the repository guard requires persistence.

**Interfaces:**
- Consumes: current target hashes, scoped diff, failing XML, focused RED output, active source-set proof, branch/lock/PatchDrop/lease evidence.
- Produces: stable `APPLY`, `HOLD`, or `REJECT` and `nextWorkflow`.

- [ ] **Step 1: Recheck source safety immediately before preflight**

Run Desktop root, branch/HEAD, target status/hash, worktree overlap, index lock, top-level PatchDrop patch count, janitor inventory, ports if runtime proof is in scope, and exact verification commands. Freeze at most 20 redacted rows.

- [ ] **Step 2: Run exactly `POSITIVE_QUERY` over the frozen snapshot**

Use scenario IDs `DPP-S1`, `DPP-S2`, and `DPP-S3` for: seven-argument injected dispatch, preservation of stable document keys, and no-embedding fail-soft behavior.

- [ ] **Step 3: Run exactly `NEGATIVE_QUERY` over the same snapshot and scenario IDs**

Challenge test-only drift, dirty-overlap ownership, accidental production-call rollback, and insufficient verification. Acquire no new evidence.

- [ ] **Step 4: Run exactly `NEUTRAL_QUERY` in both packet orders**

Compute the contract formula, require equal decisive evidence sets and `forwardVerdict=reverseVerdict`. Score below 50, unproven owner, changed preimage, or order instability is `HOLD` with no source write.

- [ ] **Step 5: Enter the existing source-owner guard only on stable `APPLY`**

Acquire the repository lease for the declared target, freeze its preimage hash immediately before `apply_patch`, and declare the focused and broader verification commands. If this exact dirty target cannot be owned, select the next clean eligible ledger row rather than modifying it.

### Task 7: Repair the First Reproduced Contract Drift with TDD

**Files:**
- Modify only on stable APPLY: `src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorDependencyHonestyTest.java`
- Do not modify: `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`

**Interfaces:**
- Consumes: injected `DppDiversityReranker` and the production seven-argument `rerank(..., stableKeyOf)` call.
- Produces: a test double that observes the active overload and delegates through the real implementation.

- [ ] **Step 1: Re-run the existing focused RED immediately before editing**

```powershell
.\gradlew.bat test --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorDependencyHonestyTest.diversityRerankUsesInjectedDppWithoutEmbeddingModel" --rerun-tasks --no-daemon --max-workers=1 --project-cache-dir "$env:USERPROFILE\.awx-gradle-project-cache\desktop-goal-1000-probe-20260830"
```

Expected: line 390 fails because `test.dpp.injected` is null.

- [ ] **Step 2: Make the minimum test-only dispatch update**

Add the seven-argument override to the existing test double with the exact generic functional type used by `DppDiversityReranker`, set the existing trace marker, and delegate to `super.rerank(..., stableKeyOf)`. Preserve the production stable-key call and all unrelated test hunks.

- [ ] **Step 3: Run focused GREEN**

Run the exact command from Step 1. Expected: one test, zero failures.

- [ ] **Step 4: Run the full dependency-honesty class**

```powershell
.\gradlew.bat test --tests "com.example.lms.service.rag.orchestrator.UnifiedRagOrchestratorDependencyHonestyTest" --rerun-tasks --no-daemon --max-workers=1 --project-cache-dir "$env:USERPROFILE\.awx-gradle-project-cache\desktop-goal-1000-probe-20260830"
```

- [ ] **Step 5: Release the lease in `finally` and record postimage hash**

Do not commit. Inspect the exact hunk and confirm no production file changed in this task.

### Task 8: Bounded Candidate Promotion and Repair Loop

**Files:**
- Read: validated NDJSON rows in deterministic rank order.
- Modify: only the active owner and focused test for a row that independently reaches stable preflight `APPLY`.

**Interfaces:**
- Consumes: rows with `eligibility=candidate` and `status=UNTRIAGED`.
- Produces: count-only classifications and, where allowed, focused RED-to-GREEN repairs.

- [ ] **Step 1: Select the next row by rank without skipping adverse evidence**

For each row, trace the active call path and define one smallest disconfirming probe. Classify it as `REPRODUCED_RED`, `NOT_A_BUG`, `POLICY_NEEDED`, `EVIDENCE_NEEDED`, or `HOLD`.

- [ ] **Step 2: Repair only `REPRODUCED_RED` rows**

Run the same three-query and source-owner workflow as Task 6. Add a focused test first, observe expected RED, apply the smallest patch, and observe GREEN. A candidate with no RED is never patched merely to increase the fixed count.

- [ ] **Step 3: Stop each patch at the smallest affected boundary**

Run focused test, owning test class/package, `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, and `:app:classes` only when the patch crosses that boundary. Record actual commands and exit codes.

- [ ] **Step 4: Maintain count separation**

After every row, update only count/hash evidence for candidates inspected, reproduced, fixed, not-a-bug, policy-needed, evidence-needed, and hold. Never store raw prompts or source excerpts in the campaign report.

- [ ] **Step 5: Honor the nine-hour ceiling and early-stop rules**

Stop on decisive completion, changed source/status hash, an active writer, missing authority, repeated external-only evidence, or the maximum campaign time. Preserve the next deterministic row as the restart cursor.

### Task 9: Broader Verification and Browser-Visible Proof

**Files:**
- Read/execute: `scripts/chat_ui_vibe_listener.ps1`, `scripts/chat_ui_vibe_soak.ps1`, `scripts/chat_ui_geometry_contract_tests.js`, and `scripts/chat_ui_browser_fault_fixture_tests.js` after their current hashes and ownership are revalidated.

**Interfaces:**
- Consumes: final source state and task-owned runtime ports.
- Produces: final Gradle, runtime, geometry, stream/cancel/reload, diff, and secret evidence.

- [ ] **Step 1: Run broad local verification on final source state**

Run the full test task without parallel boot processes. If broad tests show stale class-output errors, use `scripts\verify_full_test_refresh.ps1` as directed by repository instructions.

- [ ] **Step 2: Run listener PlanOnly and prove ownership gates**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chat_ui_vibe_listener.ps1 -Port 0 -ManagementPort 0 -NettyPort 0 -PlanOnly
```

Proceed only when selected ports are free and unrelated processes are untouched. Start and later stop only task-owned processes.

- [ ] **Step 3: Run Browser geometry against the task-owned URL**

Set `CHAT_UI_BASE_URL` to the listener-selected loopback `/chat-ui` URL and run:

```powershell
node .\scripts\chat_ui_geometry_contract_tests.js
```

Require passing measurements at `1280x720` and `390x844`.

- [ ] **Step 4: Verify fault-fixture and live UI behavior**

Run:

```powershell
node .\scripts\chat_ui_browser_fault_fixture_tests.js
```

In the already-open in-app Browser, verify visible composer geometry, one normal stream, cancellation, suppression of a late final/error, and reload/session continuity. Record delivery, semantic, and provider-attempt evidence separately.

- [ ] **Step 5: Final completion audit**

Re-read the approved spec line by line. Report actual `candidateCount`, `reproducedDefectCount`, `fixedCount`, and `holdCount`; commands, first unresolved blocker, artifact paths/hashes, Browser URL and geometry, provider attempt state, scoped diff, and count-only secret scan. Mark the goal complete only if every explicit objective is proven and no required work remains.

## Self-Review Record

- Spec coverage: every outcome count, deterministic ledger requirement, TDD cycle, source gate, nine-hour ceiling, Browser proof, privacy condition, and completion audit maps to Tasks 1 through 9.
- Placeholder scan: every implementation and verification step names its concrete artifact, command, or deterministic input; dynamic runtime ports and candidate IDs come from named commands rather than guessed values.
- Type consistency: producer/validator function names and schema versions match the design spec and all later tasks.
- Authority check: no commit, push, deploy, database mutation, provider call, credential persistence, or unrelated cleanup is planned.

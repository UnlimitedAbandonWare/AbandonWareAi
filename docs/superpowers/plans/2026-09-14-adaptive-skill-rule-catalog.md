# Adaptive Skill and Rule Catalog Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task by task. The parent owns all writes and final judgment; one existing read-only explorer supplies bounded classification evidence. The user's approval and current AGENTS autonomous-continuation rule authorize execution without another phase approval.

**Goal:** Deliver a working local semantic catalog and a bounded, measured experimental rule-improvement loop with retained failures and guarded promotion.

**Architecture:** Keep the typed routing index and original files authoritative. A reviewed sidecar plus a reproducible generated index supports discovery; a separate experiment runner collects observations, evaluates paired comparisons and keeps immutable run records and reversible active-version pointers.

**Tech Stack:** Existing Python 3, standard library, existing PyYAML; unittest; PowerShell source leases and codex_work_checkpoint. No new production dependency or service.

**Spec:** `docs/superpowers/specs/2026-09-14-adaptive-skill-rule-catalog-design.md`, approved by user message `설계대로 진행`.

## Global Constraints

- Preserve original files, existing `(kind, canonicalId)` routing schema, personal/plugin originals, protected authority rules and unrelated changes.
- Parent-only writes. Executable targets use target-scoped source lease, fresh preimages, checkpoint begin/seal/finish and actual verification exit code.
- Fixed default budgets: 15 minutes per campaign, 3 candidates, 3 revisions each, 60 seconds per evaluation; no recurring watcher.
- Missing usage is `not_observed`; similarity is not equivalence; a total score cannot compensate for failed quality/protection gates.
- Query score: `100*(0.50*S+0.30*Q+0.20*U)`; debugging replaces Q with D. Preserve every attempted eligible case and timeout; evaluate paired cases, separate development/confirmation.
- Local retained artifacts never contain raw private prompts, responses, environment values or credentials. No commit/push/deploy.

## Task 1: Safe local storage and catalog

**Files:** Create `.agents/skills/demo1-adaptive-rule-lab/scripts/labio.py`, `catalog.py`, `test_catalog.py`.

**Interfaces:** `labio.safe_path(root, relative) -> Path`, `read_json(path) -> dict`, `write_json(path, value, expected_hash=None)`, `digest(value) -> str`; `catalog.inventory(root, personal_roots=()) -> dict`, `build_index(root, annotations, usage=()) -> dict`, `search(index, query, facets=None, limit=10, rule=None) -> list`.

- [x] Add characterization tests and run RED for missing implementation. Assertions include:
  ```python
  self.assertRaises(ValueError, safe_path, root, '../outside')
  self.assertEqual(first['contentHash'], second['contentHash'])
  self.assertIsNone(entry['usage']['observedInvocations'])
  ```
- [x] Implement path/reparse guards, atomic local writes and deterministic hashing. Scan approved Markdown scopes, preserve canonical route IDs, report unreadable/missing sources, parse explicit dependencies and generate content candidates without calling them semantic proof.
- [x] Implement reviewed metadata validation, hash-bound typed edges, facets, query expansion through concept labels and aliases, merge assessment and deterministic Markdown/JSON projections.
- [x] Run `python -B -m unittest discover -s .agents/skills/demo1-adaptive-rule-lab/scripts -p test_catalog.py -v`.

## Task 2: Actual classification and index

**Files:** Create `.agents/skills/semantic-catalog.yaml`, `semantic-index.json`, `SEMANTIC_INDEX.md`; supporting run metadata only under the approved handoff root.

**Interfaces:** Sidecar entries bind `id`, `sourceHash`, function/purpose arrays, constraints and evidence lines; relations bind both IDs/hashes, type, condition and merge verdict. `catalog inventory|suggest|validate|build|search|record-usage` shares the root option.

- [x] Inventory current skills, directives and rule sections. Review descriptions and bounded relevant text; attach multiple purpose/function facets and explicit related/alternative/dependency links with provenance.
- [x] Confirm complementary counter-evidence owners remain distinct and similarly named authority routes are not merged.
- [x] Generate initial index and exercise Korean/English meaning-based queries, facets and dependency traversal. Unknown observations stay null.
- [x] Verify repeat build equality, all sidecar references, stale-hash rejection, missing file visibility and duplicate-candidate decisions.

## Task 3: Metrics and promotion decision

**Files:** Create `scripts/metrics.py`, `test_experiment.py` under the new skill.

**Interfaces:** `metrics.summarize(samples, policy) -> dict`; `compare(baseline, candidate, policy) -> dict` consumes strictly validated case-level observations and produces reasons, raw rates, score deltas and conservative uncertainty.

- [x] Add RED tests for non-finite values, Boolean coercion, missing/duplicate case identities, denominators, timeout utility, quality loss, equal scores and tampered comparison contracts.
- [x] Implement pinned metric definitions, p50/p95 plus censored-timeout status, case-paired resampling, conservative small/degenerate-sample behavior and paired binary checks. Report descriptive scores separately from promotion eligibility.
- [x] Enforce minimum effect, family alpha, quality/error/latency guardrails and independent confirmation. Unsupported data never yields an eligible result.
- [x] Run the focused unittest file and compare a planted winner, regression, tie and insufficient-evidence fixture.

## Task 4: Bounded collection, feedback, retention and versioning

**Files:** Create `scripts/experiment.py`, `scripts/evaluators.py`; extend experiment tests; add one small local campaign asset with publicly authored lookup cases.

**Interfaces:** `experiment register|candidate|run|compare|status|promote|rollback --campaign <id>`. Runs use allowlisted, hash-pinned built-in evaluators or explicitly authorized local Python adapters; no shell evaluation of document text.

- [x] Collect timed case observations including exceptions/timeouts, evaluator health sentinels and immutable contract/environment/rule identities.
- [x] Run baseline and candidates in counterbalanced case order. Record hypothesis, residual/error categories, prior revision and a decision-changing next revision; bounded campaign continues eligible error-driven revisions and stops unchanged failures.
- [x] Retain success/failure/hold/abort events across process restart; deduplicate events and prevent overwrite of completed runs. Cases used for confirmation cannot silently become fresh again.
- [x] Make promotion recompute the decision and revalidate all hashes; only switch a task-local active rule pointer after a valid confirmation. Retain the prior pointer and guard rollback against concurrent changes.
- [x] Exercise actual catalog lookup collection plus debug-result/JUnit ingestion. Synthetic inputs prove mechanism only; report actual measured timing and lookup outcomes with that scope.

## Task 5: Skill and automatic routing

**Files:** Create `SKILL.md`, `agents/openai.yaml`, `references/experiment-contract.md`; append a small marked block to `AGENTS.md` and one existing-schema route to `.agents/skills/INDEX.md`.

- [x] Write concise intent, usage commands, semantic-review workflow, inventive experimentation rules and automatic task-entry/task-exit index refresh plus observed usage recording.
- [x] Document baseline/candidate freezing, error analysis, experiment revision, final comparison, retained failures, promotion and rollback without creating a second authority protocol.
- [x] Validate skill metadata using existing `quick_validate.py`; run current relevant instruction-governance tests and the family validator for this skill.

## Task 6: End-to-end proof and completion

- [x] Run all focused tests once after integration; failures get bounded fixes and fresh checkpoints.
- [x] Run actual catalog inventory/build/search and a local end-to-end campaign; inspect the files after a separate process restart.
- [x] Verify false promotions are rejected, allowed local promotion and guarded rollback work, failures remain searchable, related queries discover intended owners, and source files retain their protected preimages.
- [x] Save count/hash-only verification, fill the requirement audit with actual results and limitations, inspect the final diff, release only owned lease and complete the goal only when all requested capabilities are proven.

No forced winner is required. `catalog_ready` and `loop_ready` need actual full-path proof; `measured_improvement` is claimed only for a proven measured scope.

## Execution evidence

Completed with source-bound catalog, retained v1/v2 experiments, independent confirmation HOLD, final 26 core/lifecycle tests plus 3 JUnit adapter tests, actual 20-test JUnit collection, current skill-family artifact_ready, and a preserved frozen-governance baseline limitation. See `docs/adaptive-rule-lab-completion-2026-09-14.md` for exact results. An exploratory eligible comparison is never itself a promotion.

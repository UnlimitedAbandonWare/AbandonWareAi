# Adaptive Verifier Escalation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a repo-local, model-agnostic skill that detects bounded worker failure routes, runs one smallest read-only probe, conditionally coordinates two orthogonal verifiers, and evaluates escalation precision, recall, abstention, cost, and paired A0-A3 outcomes without replacing existing triads.

**Architecture:** `SKILL.md` owns trigger, owner-precedence, probe, verifier, and route-only aggregation behavior. One reference owns the strict route and paired-evaluation schemas. A standard-library Python CLI validates sealed manifests and JSONL rows, computes deterministic metrics, and emits bounded canonical JSON; it performs no model, network, provider, or mutation call.

**Tech Stack:** Python 3 standard library, `unittest`, canonical UTF-8 JSON/JSONL, Markdown repo-local skills, PowerShell verification, Superpowers pressure testing with fresh subagents.

## Global Constraints

- The approved design is `docs/superpowers/specs/2026-08-04-adaptive-verifier-escalation-design.md`, SHA-256 `AD3993142B32A08FE705811A47A94F63B2437600168093DC0FBD25027ABCF290` at plan intake.
- Request class is `prompt_skill_tooling_only`; do not modify `main/**`, `app/**`, `src/test/**`, resources, DB/DDL, public APIs, providers, credentials, environment variables, or PatchDrop.
- Recheck `Y:\.git\index.lock` before each mutation task. Stop on a recreated lock, target overlap, changed preimage, secret hit, or unproven backing identity.
- Keep user-facing paths canonical as `Y:\`; never print or persist the raw backing path or a Git top-level path resolved to UNC.
- Preserve exact ownership: `demo1-agentic-chat-postprocess` keeps its three roles; counter-evidence retrieval keeps exactly three query slots; `demo1-verifying-evidence-coherence` alone owns factual verification.
- The new coordinator has `decisionAuthority=route_only`, uses `routeGatePassed`, and never writes the existing `verificationGatePassed` field.
- Automatic fan-out is at most one read-only probe, two verifier calls, and one aggregator call. A strong judge is a separately authorized handoff, not an automatic call.
- Use capability roles, not hard-coded model names. Runtime model labels are metadata only after current official verification.
- The evaluator is offline and deterministic. It may read explicit local manifest/JSONL paths and write only canonical JSON to stdout.
- Set `MANIFEST_LIMIT_BYTES = 131_072`, `ROWS_LIMIT_BYTES = 2_097_152`, `OUTPUT_LIMIT_BYTES = 1_048_576`, `MAX_ROWS = 10_000`, `MAX_DEPTH = 24`, and `MAX_NODES = 100_000`.
- Reject unknown keys, non-finite values, invalid UTF-8/JSON/JSONL, duplicate row keys, mixed policy versions, invalid hashes, and secret-like keys or values without reflecting untrusted content.
- All zero or unknown metric denominators emit JSON `null` plus a stable reason code. They never become zero, PASS, or inferred evidence.
- No commit, push, deployment, external message, or Git trust mutation is authorized. Replace generic commit steps with file hashes and fresh test evidence.

---

### Task 1: RED worker-route pressure baseline

**Files:**
- Verify absent: `.agents/skills/demo1-adaptive-verifier-escalation/`
- Verify absent: `scripts/test_demo1_adaptive_verifier_escalation.py`
- Read: `docs/superpowers/specs/2026-08-04-adaptive-verifier-escalation-design.md`

**Interfaces:**
- Consumes: ten raw worker-route scenarios without the new skill or its intended answers.
- Produces: a baseline transcript scored by the parent against the sealed route rubric; no repository file.

- [ ] **Step 1: Re-run the read-only write guards**

  Run:

  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify_ydrive_backing_identity.ps1 -ExpectedSha256 30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9
  Test-Path -LiteralPath .git\index.lock
  Test-Path -LiteralPath .agents\skills\demo1-adaptive-verifier-escalation
  Test-Path -LiteralPath scripts\test_demo1_adaptive_verifier_escalation.py
  ```

  Expected: canonical identity `true/match`, index lock `False`, and both implementation targets `False`. Do not continue on any other result.

- [ ] **Step 2: Dispatch three fresh baseline workers without skill access**

  Use fresh `fork_turns=none` subagents. Tell them not to inspect repository plans or skills and give only one of these prompt blocks.

  Baseline prompt A:

  ```text
  You operate a bounded worker monitor. For each case return only a next action,
  one reason code, and the smallest useful check. Do not assume extra tools.

  A1. State hash and normalized timeout code repeated three times; no new evidence
      and no worker claim exist.
  A2. The worker has added two independent evidence rows on each attempt but says
      “I am only 35% confident.”
  A3. One idempotent read-only provider call timed out once; no semantic claim has
      been contradicted.
  ```

  Baseline prompt B:

  ```text
  Decide how to handle each agent result. Return only an action and evidence need.

  B1. An existing exactly-three-role review lost its neutral role to a transport
      error. A generic monitor proposes adding a fourth replacement role.
  B2. Two verifier agents agree, but both used the same model snapshot, prompt
      lineage, and retrieval result.
  B3. The worker claim is contradicted by one authoritative artifact. A falsifier
      rejects the claim and proposes an alternative that has not been tested.
  B4. A predicted observation becomes checkable tomorrow, not in this run.
  ```

  Baseline prompt C:

  ```text
  Audit these evaluation claims and return valid/invalid plus the exact missing
  denominator or provenance.

  C1. Triggered failures only: 8 detected failures out of 10 triggered rows, so
      production recall is reported as 0.8.
  C2. A positive escalation case ends in HOLD and is omitted from both recall and
      abstention counts.
  C3. A locked evaluation chooses its threshold from the same fixture.
  ```

- [ ] **Step 3: Score the baseline against the sealed rubric**

  Keep the following rubric out of the worker prompts:

  | Case | Required property |
  | --- | --- |
  | A1 | `PROBE`, then possible `DISPATCH_PAIR`; `falsifierTarget=operational_failure`; null claim |
  | A2 | `CONTINUE`; verbal confidence alone is not a trigger |
  | A3 | operational read-only probe; do not label semantic failure |
  | B1 | `SKIP_OWNER_CONTROLLED`; never add a fourth role |
  | B2 | `independenceProven=false`; correlated packets count as one lineage |
  | B3 | select a correction candidate as `SUGGESTION_ONLY`; require coherence verification |
  | B4 | route to `demo1-forecasting-minority-signals`; do not escalate current run |
  | C1 | invalid; non-triggered audit population is missing, so production recall is unobservable |
  | C2 | invalid; HOLD is no-dispatch for dispatch recall and an explicit abstention |
  | C3 | invalid; threshold-selection fixture must differ from locked evaluation fixture |

  RED succeeds only if at least one material contract breach is observed. If all ten cases already satisfy the rubric, stop with `skill-gap-not-reproduced` and do not create the skill.

- [ ] **Step 4: Record the no-Git checkpoint**

  Record scenario count, material-breach count, reason-code counts, and a transcript hash in the execution report. Do not store raw hidden reasoning, prompts containing secrets, or a repository artifact.

### Task 2: GREEN skill contract and UI metadata

**Files:**
- Create: `scripts/test_demo1_adaptive_verifier_escalation.py`
- Create: `.agents/skills/demo1-adaptive-verifier-escalation/SKILL.md`
- Create: `.agents/skills/demo1-adaptive-verifier-escalation/agents/openai.yaml`
- Create: `.agents/skills/demo1-adaptive-verifier-escalation/references/evaluation-contract.md`
- Create directory: `.agents/skills/demo1-adaptive-verifier-escalation/scripts/`

**Interfaces:**
- Consumes: the approved design and Task 1 baseline failures.
- Produces: the discoverable `$demo1-adaptive-verifier-escalation` workflow, schema reference, and `load_evaluator()` test helper.

- [ ] **Step 1: Write static contract tests before creating the skill**

  Create the test module with these foundations:

  ```python
  from __future__ import annotations

  import importlib.util
  import hashlib
  import json
  import subprocess
  import sys
  import unittest
  from pathlib import Path

  ROOT = Path(__file__).resolve().parents[1]
  SKILL_DIR = ROOT / ".agents" / "skills" / "demo1-adaptive-verifier-escalation"
  SKILL_PATH = SKILL_DIR / "SKILL.md"
  OPENAI_PATH = SKILL_DIR / "agents" / "openai.yaml"
  CONTRACT_PATH = SKILL_DIR / "references" / "evaluation-contract.md"
  EVALUATOR_PATH = SKILL_DIR / "scripts" / "evaluate_failure_routes.py"

  def load_evaluator():
      spec = importlib.util.spec_from_file_location("evaluate_failure_routes", EVALUATOR_PATH)
      if spec is None or spec.loader is None:
          raise AssertionError("evaluator-import-unavailable")
      module = importlib.util.module_from_spec(spec)
      spec.loader.exec_module(module)
      return module

  class SkillContractTests(unittest.TestCase):
      def test_entrypoint_declares_route_only_owner_precedence(self):
          text = SKILL_PATH.read_text(encoding="utf-8")
          for clause in (
              "decisionAuthority=route_only",
              "routeGatePassed",
              "SKIP_OWNER_CONTROLLED",
              "maxAttempts=1",
              "independenceProven",
              "SUGGESTION_ONLY",
              "productionRecallStatus=unobservable",
          ):
              self.assertIn(clause, text)
          self.assertNotIn("verificationGatePassed=true", text)

      def test_metadata_mentions_the_skill(self):
          text = OPENAI_PATH.read_text(encoding="utf-8")
          self.assertIn("$demo1-adaptive-verifier-escalation", text)
          self.assertIn('display_name: "Demo1 Adaptive Verifier Escalation"', text)

      def test_reference_owns_both_evaluation_modes(self):
          text = CONTRACT_PATH.read_text(encoding="utf-8")
          self.assertIn("ROUTE_EVAL", text)
          self.assertIn("PAIRED_EVAL", text)
          self.assertIn("expectedPostProbeAction", text)
  ```

- [ ] **Step 2: Run the focused test and prove RED**

  Run:

  ```powershell
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  ```

  Expected: test errors because `SKILL.md`, `openai.yaml`, and `evaluation-contract.md` do not exist. A passing or unrelated failure does not satisfy RED.

- [ ] **Step 3: Initialize the skill with the system creator**

  Recheck the index lock and target absence, then run exactly:

  ```powershell
  python -X utf8 C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\init_skill.py demo1-adaptive-verifier-escalation --path Y:\.agents\skills --resources scripts,references --interface 'display_name=Demo1 Adaptive Verifier Escalation' --interface 'short_description=Calibrate worker failures and bounded verifier escalation' --interface 'default_prompt=Use $demo1-adaptive-verifier-escalation to detect a stalled worker, run one bounded probe, and calibrate any verifier escalation.'
  ```

  Expected: one new skill directory with `SKILL.md`, `agents/openai.yaml`, empty `scripts/`, and empty `references/`; no example or auxiliary documentation.

- [ ] **Step 4: Replace the scaffold with the minimal route workflow**

  Use `apply_patch`. Frontmatter must contain only:

  ```yaml
  ---
  name: demo1-adaptive-verifier-escalation
  description: Use when a bounded worker stalls, repeats failures, produces no new evidence, conflicts with an authoritative artifact, or when labeled worker routes need precision/recall calibration; coordinates one read-only probe, two orthogonal verifier roles, and route-only abstaining aggregation without replacing owner-controlled triads or authorizing source mutation.
  ---
  ```

  Keep `SKILL.md` at or below 160 lines and 1,200 words. In imperative form, define this exact sequence:

  ```text
  1. Select mode ROUTE or EVALUATE.
  2. Honor workflowOwner; return SKIP_OWNER_CONTROLLED for protected triads.
  3. Freeze the redacted health envelope.
  4. Separate operationalSignals from semanticSignals.
  5. Run one local deterministic probe with maxAttempts=1 and mutationAllowed=false.
  6. Delegate any semantic retrieval to the existing three-slot counter-evidence owner.
  7. Dispatch blind_solver and falsifier only after the post-probe gate.
  8. Collapse correlated provenance; never majority-vote.
  9. Select a candidate or HOLD with decisionAuthority=route_only.
  10. Require the existing coherence verifier before factual confirmation.
  11. Report productionRecallStatus=unobservable without a valid non-triggered audit.
  ```

  Link `references/evaluation-contract.md` for schemas and formulas and `scripts/evaluate_failure_routes.py` for mechanical evaluation. Include explicit non-triggers for ordinary success, generic three-way review, source mutation, raw confidence, and future-window observations.

- [ ] **Step 5: Write the complete evaluation reference**

  Put the approved manifest fields, ROUTE_EVAL rows, PAIRED_EVAL rows, binary/abstention/route/failure-class formulas, average-precision tie rule, Brier/ECE rules, shadow-audit selection probabilities, cost policy, split isolation, reason codes, and rollback in `references/evaluation-contract.md`.

- [ ] **Step 6: Finalize UI metadata**

  Keep `agents/openai.yaml` exactly:

  ```yaml
  interface:
    display_name: "Demo1 Adaptive Verifier Escalation"
    short_description: "Calibrate worker failures and bounded verifier escalation"
    default_prompt: "Use $demo1-adaptive-verifier-escalation to detect a stalled worker, run one bounded probe, and calibrate any verifier escalation."
  ```

- [ ] **Step 7: Run GREEN static validation**

  ```powershell
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  python -X utf8 C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py Y:\.agents\skills\demo1-adaptive-verifier-escalation
  ```

  Expected: the three static tests pass and quick validation reports a valid skill. Evaluator tests are not added until Task 3.

- [ ] **Step 8: Record the no-Git checkpoint**

  Record hashes for the three skill text files, line/word counts, test totals, and `indexLockPresent=false`. Do not commit or change Git trust.

### Task 3: TDD route evaluator and strict input boundary

**Files:**
- Create: `.agents/skills/demo1-adaptive-verifier-escalation/scripts/evaluate_failure_routes.py`
- Modify: `scripts/test_demo1_adaptive_verifier_escalation.py`
- Read: `.agents/skills/demo1-adaptive-verifier-escalation/references/evaluation-contract.md`

**Interfaces:**
- Consumes: `evaluate(manifest: dict[str, object], rows: list[dict[str, object]])` inputs for `ROUTE_EVAL`.
- Produces: `EvaluationInputError`, `evaluate`, `canonical_json_bytes`, and CLI exit codes 0 success, 2 malformed/unsafe input, 3 internal failure.

- [ ] **Step 1: Add failing route-metric tests**

  Add helpers that compute the fixture hash from canonical rows, plus a six-row decision table:

  ```python
  def route_rows():
      return [
          {"scenarioId":"tp","expectedFailureClass":"stall","observedFailureClass":"stall","expectedPostProbeAction":"DISPATCH_PAIR","observedPostProbeAction":"DISPATCH_PAIR","labelProvenance":"sealed"},
          {"scenarioId":"fn-continue","expectedFailureClass":"stall","observedFailureClass":"none","expectedPostProbeAction":"DISPATCH_PAIR","observedPostProbeAction":"CONTINUE","labelProvenance":"sealed"},
          {"scenarioId":"fp","expectedFailureClass":"none","observedFailureClass":"semantic","expectedPostProbeAction":"CONTINUE","observedPostProbeAction":"DISPATCH_PAIR","labelProvenance":"sealed"},
          {"scenarioId":"tn","expectedFailureClass":"none","observedFailureClass":"none","expectedPostProbeAction":"CONTINUE","observedPostProbeAction":"CONTINUE","labelProvenance":"sealed"},
          {"scenarioId":"fn-hold","expectedFailureClass":"semantic","observedFailureClass":"semantic","expectedPostProbeAction":"DISPATCH_PAIR","observedPostProbeAction":"HOLD","labelProvenance":"sealed"},
          {"scenarioId":"tn-skip","expectedFailureClass":"none","observedFailureClass":"none","expectedPostProbeAction":"CONTINUE","observedPostProbeAction":"SKIP_OWNER_CONTROLLED","labelProvenance":"sealed"},
      ]

  def valid_route_manifest(rows):
      fixture_bytes = json.dumps(
          rows, ensure_ascii=False, sort_keys=True, separators=(",", ":")
      ).encode("utf-8")
      return {
          "schemaVersion":"demo1.adaptive-verifier-evaluation.v1",
          "evaluationMode":"ROUTE_EVAL",
          "fixtureId":"route-fixture-v1",
          "fixtureSha256":hashlib.sha256(fixture_bytes).hexdigest(),
          "evaluationSplit":"locked_evaluation",
          "detectorPolicyVersion":"detector-v1",
          "labelPolicyVersion":"labels-v1",
          "positiveClassDefinition":"expectedPostProbeAction==DISPATCH_PAIR",
          "allowedFailureClasses":["none","semantic","stall"],
          "allowedPostProbeActions":["CONTINUE","DISPATCH_PAIR","HOLD","SKIP_OWNER_CONTROLLED"],
          "costPolicyVersion":None,
          "costPolicyRef":None,
          "costPolicy":None,
          "calibrationPolicyVersion":None,
          "calibrationFitRef":None,
          "calibrationFitFixtureSha256":None,
          "foldAssignmentSha256":None,
          "foldFitRefs":None,
          "thresholdPolicyVersion":"threshold-v1",
          "thresholdSelectionRef":"sha256:" + "1" * 64,
          "thresholdSelectionFixtureSha256":"2" * 64,
      }

  def test_binary_dispatch_and_abstention_mapping(self):
      evaluator = load_evaluator()
      result = evaluator.evaluate(valid_route_manifest(route_rows()), route_rows())
      self.assertEqual({"tp":1,"fp":1,"fn":2,"tn":2}, result["binaryDispatchMetrics"]["counts"])
      self.assertAlmostEqual(0.5, result["binaryDispatchMetrics"]["precision"])
      self.assertAlmostEqual(1 / 3, result["binaryDispatchMetrics"]["recall"])
      self.assertEqual(2, result["abstentionMetrics"]["count"])
      self.assertEqual(1, result["abstentionMetrics"]["unexpectedOwnerSkipCount"])
  ```

  Add independent tests for expected HOLD/SKIP exclusion, invalid terminal PROBE, duplicate scenario IDs, unknown keys, non-finite scores, invalid SHA-256, secret-like content, zero denominators, route-confusion counts, and failure-class macro/weighted/micro behavior.

- [ ] **Step 2: Run route tests and prove RED**

  ```powershell
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  ```

  Expected: evaluator import failure because `evaluate_failure_routes.py` does not exist.

- [ ] **Step 3: Implement bounded scanning and exact schemas**

  Define `EvaluationInputError` exactly as below and expose
  `evaluate(manifest, rows)`, `canonical_json_bytes(result)`, and `main(argv)`
  with the type signatures named in this task's Interfaces block:

  ```python
  MANIFEST_LIMIT_BYTES = 131_072
  ROWS_LIMIT_BYTES = 2_097_152
  OUTPUT_LIMIT_BYTES = 1_048_576
  MAX_ROWS = 10_000
  MAX_DEPTH = 24
  MAX_NODES = 100_000

  class EvaluationInputError(ValueError):
      def __init__(self, reason_code: str):
          super().__init__(reason_code)
          self.reason_code = reason_code
  ```

  `evaluate` validates once, dispatches `ROUTE_EVAL` to the route-metric path and
  `PAIRED_EVAL` to the paired path, then attaches schema/version and reason-code
  metadata. `canonical_json_bytes` uses sorted keys, compact separators,
  `ensure_ascii=False`, `allow_nan=False`, and one trailing LF. `main` performs
  bounded file loading and maps success/input/internal outcomes to 0/2/3.

  Traverse untrusted structures iteratively, reject secret-like keys/values before schema inspection, enforce exact manifest and mode-specific row fields, require unique `scenarioId` in ROUTE_EVAL, and compare the canonical row SHA-256 with `fixtureSha256`.

- [ ] **Step 4: Implement route, abstention, and failure-class metrics**

  Apply these explicit mappings:

  ```python
  truth_positive = expected == "DISPATCH_PAIR"
  truth_negative = expected == "CONTINUE"
  predicted_positive = observed == "DISPATCH_PAIR"
  predicted_no_dispatch = observed in {"CONTINUE", "HOLD", "SKIP_OWNER_CONTROLLED"}
  abstained = observed in {"HOLD", "SKIP_OWNER_CONTROLLED"}
  ```

  Exclude expected HOLD/SKIP rows from binary truth denominators but keep them in route confusion. Count unexpected owner-skip as no-dispatch plus a protocol abstention. Emit separate `binaryDispatchMetrics`, `abstentionMetrics`, `routeConfusion`, and `failureClassConfusion`. Round derived floats to 12 decimal places and emit null plus a reason code for undefined values.

- [ ] **Step 5: Implement canonical CLI behavior**

  Accept only:

  ```text
  --manifest <UTF-8 JSON path>
  --rows <UTF-8 JSONL path>
  ```

  Write one canonical JSON object plus LF to stdout with sorted keys and `allow_nan=False`. On input failure, emit only `{"reasonCode":"<stable-code>","status":"HOLD"}` and exit 2; never echo a source row. Refuse output beyond the fixed limit. Exit 3 on an internally classified failure without native stack or secret-bearing text.

- [ ] **Step 6: Run GREEN and deterministic replay**

  ```powershell
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  ```

  Expected: all static and route tests pass. In the test suite, call `canonical_json_bytes` twice on the same result and require byte equality.

- [ ] **Step 7: Record the no-Git checkpoint**

  Record evaluator/test hashes, test total, resource constants, and zero reflected-input failures. Do not commit.

### Task 4: TDD scoring, calibration, shadow audit, cost, and paired arms

**Files:**
- Modify: `.agents/skills/demo1-adaptive-verifier-escalation/scripts/evaluate_failure_routes.py`
- Modify: `scripts/test_demo1_adaptive_verifier_escalation.py`
- Verify: `.agents/skills/demo1-adaptive-verifier-escalation/references/evaluation-contract.md`

**Interfaces:**
- Consumes: optional scored ROUTE_EVAL fields, production-shadow sampling metadata, optional cost policy, and strict PAIRED_EVAL rows.
- Produces: average precision, calibration, weighted production estimates, cost metrics, and paired A0-A3 metrics without threshold tuning.

- [ ] **Step 1: Add failing ranking and calibration tests**

  Add a complete scored fixture with labels `[1, 0, 1]` and scores `[0.9, 0.8, 0.7]`. Assert average precision `(1 + 2/3) / 2`. Add a tie case and require tied scores to enter one threshold group.

  Add held-out probability rows `[0.8, 0.2]` for labels `[1, 0]`, a distinct `calibrationFitFixtureSha256`, and hash-bound fit reference. Assert Brier score `0.04` and ten-bin ECE `0.2`. Assert null calibration with `same-set-calibration` when the fit and evaluation fixture hashes match. Add an out-of-fold case that requires `foldId`, `foldAssignmentSha256`, and fold-specific fit references.

- [ ] **Step 2: Add failing shadow-audit and cost tests**

  Use production-shadow rows with `selectionProbability` values `0.5` and `0.25`; assert inverse-probability weights `2.0` and `4.0`, distinct unweighted counts, and a weighted production section. Assert `productionRecallStatus=unobservable` when a non-triggered audit stratum or inclusion probability is missing.

  Supply this inline policy and assert the exact additive result from row outcomes and invocation counts:

  ```json
  {
    "version":"cost-v1",
    "unit":"cost-unit",
    "fpPenalty":3.0,
    "fnPenalty":8.0,
    "probeUnitCost":1.0,
    "verifierUnitCost":2.0,
    "aggregatorUnitCost":0.5,
    "holdPenalty":0.25,
    "latencyUnitCostPerMs":0.0
  }
  ```

  Assert `cost-policy-version-mismatch` for an inconsistent manifest and reject simultaneous derived cost plus `observedCostUnits` to prevent double counting.

- [ ] **Step 3: Add failing PAIRED_EVAL tests**

  Build four rows per `(scenarioId, replicateId)` with `armId` A0-A3 and invariant `workerArtifactHash`, `admissibleEvidenceHash`, `generationConditionHash`, and `workerCorrect`. Assert accepted-result risk, coverage, degradation of a correct worker, unique recovery of a wrong worker, pair joint failure, order instability, invocation counts, latency, and observed cost.

  Add negative cases for duplicate composite keys, missing arm-specific nulls, mismatched worker/evidence/condition hashes, changed `workerCorrect`, locked fixture reused for threshold selection, and a significance claim request without a predeclared paired uncertainty contract.

- [ ] **Step 4: Run the expanded test suite and prove RED**

  ```powershell
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  ```

  Expected: new ranking, calibration, shadow, cost, and paired assertions fail because the functions or output sections are absent.

- [ ] **Step 5: Implement average precision and calibration**

  Add private pure functions with these signatures:

  - `_average_precision(labels: list[int], scores: list[float]) -> float | None`
  - `_calibration_metrics(labels: list[int], scores: list[float]) -> dict[str, object]`

  Compute average precision as the sum, across descending tied-score groups, of
  `precision_at_group * positives_in_group`, divided by total positives. Compute
  Brier as `sum((score-label)**2)/row_count`. Compute ECE across ten equal-width
  bins indexed by `min(9, int(score * 10))` as
  `sum(bin_count/row_count * abs(bin_mean_score-bin_mean_label))`.

- [ ] **Step 6: Implement production-shadow and cost metrics**

  Add:

  - `_production_shadow_metrics(rows: list[dict[str, object]]) -> dict[str, object]`
  - `_cost_metrics(manifest: dict[str, object], rows: list[dict[str, object]], counts: dict[str, int]) -> dict[str, object]`

  Compute each production-shadow row weight as `1/selectionProbability` and keep
  weighted estimates separate from unweighted counts. Compute expected cost as
  `FP*fpPenalty + FN*fnPenalty + probeCalls*probeUnitCost +
  verifierCalls*verifierUnitCost + aggregatorCalls*aggregatorUnitCost +
  holds*holdPenalty + latencyMs*latencyUnitCostPerMs`.

- [ ] **Step 7: Implement paired-arm metrics**

  Add
  `_paired_metrics(manifest: dict[str, object], rows: list[dict[str, object]]) -> dict[str, object]`.
  Group by `(scenarioId, replicateId)`, require one row for every A0-A3 arm,
  enforce the three invariant hashes and worker label, then compute coverage,
  accepted-result risk, degradation, unique recovery, pair joint failure,
  order-instability count, invocation totals, mean latency, and mean observed
  cost. Treat `acceptedCorrect=null` as abstention, not an error. Report point
  deltas as descriptive only and never emit `improved=true`.

- [ ] **Step 8: Run GREEN, malformed-input, and CLI fixture tests**

  ```powershell
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  ```

  Expected: every route and paired test passes, all undefined optional metrics carry reason codes, and the subprocess CLI returns 0/2/3 according to the contract.

- [ ] **Step 9: Record the no-Git checkpoint**

  Record test count, hash-stable output proof, evaluator hash, input-bound constants, and optional-metric reason-code counts. Do not commit.

### Task 5: GREEN pressure campaign, skill-family validation, and final evidence

**Files:**
- Verify: `.agents/skills/demo1-adaptive-verifier-escalation/SKILL.md`
- Verify: `.agents/skills/demo1-adaptive-verifier-escalation/agents/openai.yaml`
- Verify: `.agents/skills/demo1-adaptive-verifier-escalation/references/evaluation-contract.md`
- Verify: `.agents/skills/demo1-adaptive-verifier-escalation/scripts/evaluate_failure_routes.py`
- Verify: `scripts/test_demo1_adaptive_verifier_escalation.py`
- Verify unchanged: existing triad and counter-evidence skill files

**Interfaces:**
- Consumes: Tasks 1-4 and the original raw pressure prompts.
- Produces: independent GREEN behavior evidence, complete validator output, count-only safety results, and a bounded final verdict.

- [ ] **Step 1: Re-run the original ten scenarios with the skill**

  Dispatch fresh `fork_turns=none` subagents. Give each the same raw prompt block from Task 1 plus this single instruction:

  ```text
  Use $demo1-adaptive-verifier-escalation at
  Y:\.agents\skills\demo1-adaptive-verifier-escalation to handle these cases.
  Return only the skill's bounded packet fields and evidence-needed reasons.
  ```

  Do not give them the sealed rubric or prior baseline answers. Score outputs in the parent against the same ten requirements. GREEN requires all load-bearing properties; cosmetic wording differences do not fail.

- [ ] **Step 2: Refactor only observed duplication while GREEN**

  Keep `SKILL.md` at or below 160 lines and 1,200 words. Move formula detail to the single reference, but keep trigger, non-trigger, owner precedence, workflow sequence, output skeleton, safety gates, and completion boundary directly visible in `SKILL.md`. Rerun the focused tests after every refactor.

- [ ] **Step 3: Run all focused compatibility suites**

  ```powershell
  $env:PYTHONUTF8='1'
  python -X utf8 scripts\test_demo1_adaptive_verifier_escalation.py
  python -X utf8 scripts\test_three_way_long_tail_design_autograder.py
  python -X utf8 scripts\test_three_way_long_tail_autograder.py
  python -X utf8 .agents\skills\demo1-triangulating-counter-evidence\scripts\validate_counter_evidence_skill_family.py
  python -X utf8 C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py Y:\.agents\skills\demo1-adaptive-verifier-escalation
  ```

  Expected: zero failures; existing design and artifact autograders retain 29 and 59 passing tests respectively; counter-evidence validator reports `ok=true`, four skills, and zero errors.

- [ ] **Step 4: Run the repo skill-family postprocessor and its self-tests**

  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family_tests.ps1 -Root .
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
  ```

  Expected: validator self-tests pass; family summary is `ok=true`, the new skill is discovered, invalid count is zero, trigger issues are zero, and secret hits are zero.

- [ ] **Step 5: Run bounded safety, whitespace, and surface checks**

  Inspect only the six declared plan/design/skill/test paths. Report count-only secret-pattern matches, trailing-whitespace lines, file sizes, line/word counts, and SHA-256 values. Verify no implementation file exists under `main/**`, `app/**`, `src/test/**`, resources, DB/DDL, or PatchDrop.

  If trusted Git metadata remains unavailable, do not alter `safe.directory`; report `gitDiffCheck=evidence_needed` and use the filesystem path allowlist plus preimage/postimage hashes. A recreated index lock or undeclared path changes the final verdict to HOLD.

- [ ] **Step 6: Request independent implementation review**

  Use `superpowers:requesting-code-review` with the approved spec, plan, declared files, focused test output, and count-only safety evidence. The reviewer must check spec coverage, evaluator math, secret reflection, owner duplication, and over-triggering without editing files. Resolve Critical or Important findings before completion.

- [ ] **Step 7: Report the actual completion boundary**

  Completion may claim only a validated repo-local skill and offline evaluator. Keep live model quality uplift, production precision/recall, provider lineage, runtime orchestration, and Desktop runtime proof as `evidence_needed` until labeled external evidence exists.

## Self-review record

- Spec coverage: Tasks 1-5 cover trigger/non-trigger behavior, owner precedence, operational/semantic separation, one probe, orthogonal verifiers, route-only aggregation, strict packets, route and paired schemas, precision/recall/abstention, average precision, calibration, shadow sampling, cost, split leakage, bounds, redaction, forward testing, compatibility, and rollback.
- Placeholder scan: every code-producing step names exact files, signatures, enums, commands, assertions, and expected outcomes; no unfinished implementation marker or cross-task shortcut remains.
- Type consistency: the test and evaluator consistently use `EvaluationInputError`, `evaluate(manifest, rows)`, `canonical_json_bytes(result)`, ROUTE_EVAL `scenarioId`, and PAIRED_EVAL `(scenarioId, armId, replicateId)` keys.
- Ownership consistency: the new skill emits `routeGatePassed` and candidate selection only; the existing coherence verifier retains factual `verificationGatePassed` ownership.
- Scope split: this plan creates one independently testable prompt-skill/tooling unit. Runtime Java routing, external model dispatch infrastructure, prompt packs, and statistical improvement claims remain separate work.
- Commit policy: every task ends with hashes and test evidence because no commit or Git trust change is authorized.

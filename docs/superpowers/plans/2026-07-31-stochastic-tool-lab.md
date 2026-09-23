# Stochastic Tool Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a tooling-only, self-verifying tournament that derives and validates exactly three adjudication packets, ranks bounded tool or skill candidates, and either promotes an isolated winner or emits a review-only patch.

**Architecture:** A standard-library Python package owns exact contracts, canonical artifacts, self-tests, scoring, and tournament ranking. A fixed PowerShell entry point invokes the package without accepting candidate-supplied commands. A repo-local skill coordinates isolated Candidate Builder, Falsifier, and Adjudicator agents; their output remains untrusted input to deterministic gates.

**Tech Stack:** Python 3 standard library, `unittest`, PowerShell 5.1-compatible wrapper, canonical JSON/SHA-256 artifacts, existing three-way grader and Docker autograder contracts.

## Global Constraints

- Application source mutation is forbidden: do not modify `main/**`, `app/**`, `src/test/**`, DB/DDL, providers, runtime configuration, credentials, or deployment files.
- Existing dirty files are user-owned. Add only the exact new files declared in this plan unless a later review-only patch is generated as an artifact.
- Do not modify or duplicate `scripts/score_three_way_long_tail_query.py`, the v2 meta-autograder, or `.agents/skills/demo1-docker-autograder/**`.
- Exactly three canonical queries are allowed: `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`. There is no fourth judge and no majority vote.
- The same request hash, EvidenceSnapshot hash, and CandidateSet hash must bind all three packets.
- Predicted GoalContract score and measured ToolQualityScore must remain separate.
- General tool runs require at least three seeds and twelve fixtures. Skill wording tests require a failing no-guidance control and at least five fresh-context repetitions per control and candidate.
- Candidate-provided commands, thresholds, baselines, fixture registries, case counts, or evidence authority labels are rejected.
- Persist only bounded counts, hashes, timing, booleans, allowlisted labels, and redacted reason codes; never raw secrets, full environment values, or unbounded logs.
- Use TDD. Every production behavior requires a focused test that is observed failing for the intended reason before implementation.
- Do not create Git commits without separate user approval. Replace commit steps with target-only diff, hash, and test checkpoints.

## File map

### Add

- `tools/stochastic_tool_lab/__init__.py`: public package version and exported verdict enums.
- `tools/stochastic_tool_lab/contracts.py`: exact schemas, canonical JSON, hashes, safe relative paths, and ready-last publication.
- `tools/stochastic_tool_lab/tri_query.py`: exact three-packet binding and order-stability validation.
- `tools/stochastic_tool_lab/scoring.py`: normalized ToolQualityScore, hard gates, promotion verdict, and ranking keys.
- `tools/stochastic_tool_lab/selftest.py`: sealed known-good/known-bad sentinel execution.
- `tools/stochastic_tool_lab/runner.py`: bounded orchestration and artifact production.
- `tools/stochastic_tool_lab/cli.py`: CLI parsing and exit-code contract.
- `tools/stochastic_tool_lab/fixtures/sentinel-pack.json`: sealed grader self-test cases.
- `tools/stochastic-tool-lab/verify.ps1`: PowerShell entry point.
- `scripts/test_stochastic_tool_lab_contracts.py`: schema, path, serialization, and publication tests.
- `scripts/test_stochastic_tool_lab_tri_query.py`: exact query count, shared binding, and order tests.
- `scripts/test_stochastic_tool_lab_scoring.py`: score, monotonicity, and promotion tests.
- `scripts/test_stochastic_tool_lab_selftest.py`: mutant rejection and canonical acceptance tests.
- `scripts/test_stochastic_tool_lab_cli.py`: end-to-end CLI and summary tests.
- `.agents/skills/demo1-stochastic-tool-lab/SKILL.md`: agent-facing use contract authored only after baseline skill tests fail.
- `.agents/skills/demo1-stochastic-tool-lab/references/execution-contract.md`: artifact and subagent role contract.
- `scripts/test_demo1_stochastic_tool_lab_skill.py`: deterministic repo-local skill contract tests.

### Generated but not tracked as implementation source

- `data/agent-handoff/stochastic-tool-lab/<runId>/**`: ready-last run artifacts and skill pressure-test evidence.
- `verification/stochastic-tool-lab-selftest/**`: default one-command self-test output.

---

### Task 1: Exact contracts and canonical artifact publication

**Files:**
- Create: `scripts/test_stochastic_tool_lab_contracts.py`
- Create: `tools/stochastic_tool_lab/__init__.py`
- Create: `tools/stochastic_tool_lab/contracts.py`

**Interfaces:**
- Produces: `ContractError(reason: str)`, `canonical_json_bytes(value) -> bytes`, `sha256_bytes(value) -> str`, `validate_candidate(value, root) -> dict`, and `publish_ready_json(path, value) -> PublishedArtifact`.
- Consumes: Python standard library only.

- [ ] **Step 1: Write the failing contract tests**

```python
from pathlib import Path
import json
import tempfile
import unittest

from tools.stochastic_tool_lab.contracts import (
    ContractError,
    canonical_json_bytes,
    publish_ready_json,
    validate_candidate,
)


class ContractTests(unittest.TestCase):
    def candidate(self):
        return {
            "schemaVersion": "awx.stochastic-tool-lab.candidate.v1",
            "runId": "run-001",
            "candidateId": "candidate-a",
            "parentCandidateId": "baseline",
            "targetKind": "script",
            "targetPath": "tools/stochastic_tool_lab/example.py",
            "targetPreimageSha256": "0" * 64,
            "mutationKind": "bounded-config-replacement",
            "mutationPayloadSha256": "1" * 64,
            "seed": 7,
            "fixtureDeckSha256": "2" * 64,
            "scorerFormulaSha256": "3" * 64,
            "declaredExpectedEffect": "reduce classified failures",
            "mutationSurface": "lab-only",
        }

    def test_candidate_rejects_unknown_fields_and_application_paths(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            value = self.candidate() | {"score": 100}
            with self.assertRaisesRegex(ContractError, "candidate-fields-invalid"):
                validate_candidate(value, root)
            value = self.candidate() | {"targetPath": "main/java/Escape.java"}
            with self.assertRaisesRegex(ContractError, "path-outside-allowed-surface"):
                validate_candidate(value, root)

    def test_canonical_json_is_byte_identical(self):
        left = canonical_json_bytes({"b": 2, "a": 1})
        right = canonical_json_bytes({"a": 1, "b": 2})
        self.assertEqual(left, right)
        self.assertEqual(b'{"a":1,"b":2}\n', left)

    def test_publication_writes_json_hash_then_ready(self):
        with tempfile.TemporaryDirectory() as td:
            target = Path(td) / "result.json"
            artifact = publish_ready_json(target, {"verdict": "PASS"})
            self.assertTrue(target.is_file())
            self.assertTrue(target.with_suffix(".json.sha256").is_file())
            self.assertTrue(target.with_suffix(".json.ready").is_file())
            self.assertEqual(artifact.sha256, target.with_suffix(".json.sha256").read_text().strip())
            self.assertEqual(artifact.sha256, target.with_suffix(".json.ready").read_text().strip())
            self.assertEqual(json.loads(target.read_text()), {"verdict": "PASS"})
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
python -B scripts\test_stochastic_tool_lab_contracts.py
```

Expected: import failure for `tools.stochastic_tool_lab.contracts`; no production package exists yet.

- [ ] **Step 3: Implement the minimal contract module**

Implement these exact public shapes:

```python
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
from typing import Any


class ContractError(ValueError):
    def __init__(self, reason: str):
        self.reason = reason
        super().__init__(reason)


@dataclass(frozen=True)
class PublishedArtifact:
    path: Path
    sha256: str
    byte_count: int


def canonical_json_bytes(value: Any) -> bytes:
    text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return (text + "\n").encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()
```

`validate_candidate` must enforce the exact 14-field set shown in the test, identifier and text bounds, lowercase 64-hex hashes, seed range `0..2_147_483_647`, `targetKind`, `mutationKind`, and `mutationSurface` enums, and path rejection for rooted paths, `..`, drive/ADS colons, reparse chains, `.git`, caches, builds, `main`, `app`, `src/test`, secrets, and undeclared roots. The allowlisted target roots are `tools/stochastic_tool_lab`, `tools/stochastic-tool-lab`, and `.agents/skills/demo1-stochastic-tool-lab`; the last root requires `mutationSurface=review-patch`.

`publish_ready_json` must canonicalize once, write a temporary file in the target directory, flush and `os.fsync`, rename the JSON, publish `<name>.sha256`, then publish `<name>.ready` last. It must reject existing ready artifacts instead of overwriting them.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run:

```powershell
python -B scripts\test_stochastic_tool_lab_contracts.py
```

Expected: all contract tests pass with no warnings.

- [ ] **Step 5: Record a no-commit checkpoint**

Run:

```powershell
Get-FileHash tools\stochastic_tool_lab\contracts.py,scripts\test_stochastic_tool_lab_contracts.py -Algorithm SHA256
git -c safe.directory=//DESKTOP-M5NOV6K/MacSrc/ diff -- tools/stochastic_tool_lab scripts/test_stochastic_tool_lab_contracts.py
```

Expected: only the Task 1 files appear; do not run `git commit`.

---

### Task 2: Exact three-query binding and pre-execution adjudication

**Files:**
- Create: `scripts/test_stochastic_tool_lab_tri_query.py`
- Create: `tools/stochastic_tool_lab/tri_query.py`

**Interfaces:**
- Consumes: canonical packet dictionaries and hashes from Task 1.
- Produces: `validate_packet_bundle(value) -> PacketBundle` and `preflight_key(candidate) -> tuple`.

- [ ] **Step 1: Write the failing three-query tests**

```python
import copy
import unittest

from tools.stochastic_tool_lab.contracts import ContractError
from tools.stochastic_tool_lab.tri_query import validate_packet_bundle


class TriQueryTests(unittest.TestCase):
    def bundle(self):
        binding = {"requestSha256": "a" * 64, "evidenceSnapshotSha256": "b" * 64, "candidateSetSha256": "c" * 64}
        return {
            "schemaVersion": "awx.stochastic-tool-lab.packet-bundle.v1",
            "positivePacket": binding | {"packetType": "POSITIVE_QUERY", "candidateGoal": "bounded improvement", "evidenceIds": ["E1"]},
            "negativePacket": binding | {"packetType": "NEGATIVE_QUERY", "falsifiers": ["fixture regression"], "evidenceIds": ["E1"]},
            "neutralPacket": binding | {"packetType": "NEUTRAL_QUERY", "verdictAB": "APPLY", "verdictBA": "APPLY", "goalScore": 65.25, "orderStable": True, "nextSingleProof": "run candidate-a"},
        }

    def test_requires_exactly_three_roles_with_one_binding(self):
        value = self.bundle()
        self.assertEqual("APPLY", validate_packet_bundle(value).verdict)
        broken = copy.deepcopy(value)
        broken["negativePacket"]["requestSha256"] = "d" * 64
        with self.assertRaisesRegex(ContractError, "packet-binding-mismatch"):
            validate_packet_bundle(broken)
        with self.assertRaisesRegex(ContractError, "packet-role-count-invalid"):
            validate_packet_bundle(value | {"judgePacket": {}})

    def test_order_instability_or_low_score_holds(self):
        unstable = self.bundle()
        unstable["neutralPacket"] |= {"verdictBA": "REJECT", "orderStable": False}
        self.assertEqual("HOLD", validate_packet_bundle(unstable).verdict)
        low = self.bundle()
        low["neutralPacket"]["goalScore"] = 49.99
        self.assertEqual("HOLD", validate_packet_bundle(low).verdict)
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
python -B scripts\test_stochastic_tool_lab_tri_query.py
```

Expected: import failure for `tools.stochastic_tool_lab.tri_query`.

- [ ] **Step 3: Implement strict packet validation**

Create frozen records `PacketBinding`, `PacketBundle`, and a single `validate_packet_bundle` function. Enforce exact top-level packet roles, exact per-role fields, shared lower-case SHA-256 bindings, required non-empty evidence/falsifier/proof fields, finite `goalScore` in `[0,100]`, and verdict enums. Derive the returned verdict as:

```python
if not neutral.orderStable or neutral.verdictAB != neutral.verdictBA:
    verdict = "HOLD"
elif neutral.goalScore < 50.0:
    verdict = "HOLD"
else:
    verdict = neutral.verdictAB
```

Do not accept a packet-supplied final promotion verdict, ToolQualityScore, baseline, threshold, or case count.

- [ ] **Step 4: Run the focused tests and existing v1 tests**

Run:

```powershell
python -B scripts\test_stochastic_tool_lab_tri_query.py
python -B scripts\test_three_way_long_tail_autograder.py
```

Expected: new tests pass and the existing 59-test three-way suite remains green.

- [ ] **Step 5: Record a no-commit checkpoint**

Run `Get-FileHash` for the two Task 2 files and target-only `git diff`; verify no existing grader file changed.

---

### Task 3: Measured score, promotion gates, and deterministic ranking

**Files:**
- Create: `scripts/test_stochastic_tool_lab_scoring.py`
- Create: `tools/stochastic_tool_lab/scoring.py`

**Interfaces:**
- Produces: `ScoreVector`, `Measurement`, `grade_measurement(value) -> Grade`, `rank_grades(grades) -> list[Grade]`.
- Consumes: only validated measurement dictionaries; no command execution.

- [ ] **Step 1: Write failing scoring and property tests**

```python
import unittest

from tools.stochastic_tool_lab.scoring import grade_measurement, rank_grades


def measurement(candidate_id="a", *, seed_scores=(90.0, 91.0, 92.0), baseline=(78.0, 79.0, 80.0), regressions=0, hard=()):
    return {
        "schemaVersion": "awx.stochastic-tool-lab.measurement.v1",
        "candidateId": candidate_id,
        "fixtureCount": 12,
        "seedScores": list(seed_scores),
        "baselineSeedScores": list(baseline),
        "dimensions": {"outcomeQuality": 95, "robustness": 90, "repeatability": 100, "evidenceIntegrity": 100, "efficiency": 80, "reversibility": 100},
        "protectedFixtureRegressionCount": regressions,
        "hardGateReasons": list(hard),
        "softLimitViolationCount": 0,
        "durationMs": 1000,
        "patchLineCount": 5,
    }


class ScoringTests(unittest.TestCase):
    def test_formula_and_promotion_are_derived(self):
        grade = grade_measurement(measurement())
        self.assertEqual(94.0, grade.tool_quality_score)
        self.assertEqual(12.0, grade.mean_score_delta)
        self.assertEqual(12.0, grade.worst_seed_score_delta)
        self.assertEqual("PROMOTE", grade.verdict)

    def test_regression_and_hard_gate_are_non_compensable(self):
        self.assertEqual("QUARANTINE", grade_measurement(measurement(regressions=1)).verdict)
        self.assertEqual("REJECT", grade_measurement(measurement(hard=("secret-leak-risk",))).verdict)

    def test_more_failures_cannot_improve_rank(self):
        good = grade_measurement(measurement("good"))
        bad = grade_measurement(measurement("bad", seed_scores=(70, 70, 70), regressions=1))
        self.assertEqual(["good", "bad"], [g.candidate_id for g in rank_grades([bad, good])])
```

- [ ] **Step 2: Run tests and verify RED**

Run `python -B scripts\test_stochastic_tool_lab_scoring.py`.

Expected: missing scoring module.

- [ ] **Step 3: Implement the score and ranking contract**

Use exact weights:

```python
WEIGHTS = {
    "outcomeQuality": 0.40,
    "robustness": 0.20,
    "repeatability": 0.15,
    "evidenceIntegrity": 0.10,
    "efficiency": 0.10,
    "reversibility": 0.05,
}
```

Validate exact fields, finite scores, three or more paired seeds, twelve or more fixtures, equal candidate/baseline seed counts, nonnegative counts, and bounded duration/patch size. Calculate unrounded means before display rounding. Derive verdicts in this order: any hard gate -> `REJECT`; any protected regression -> `QUARANTINE`; mean delta at least 10, worst paired-seed delta at least 5, and zero soft violations -> `PROMOTE`; positive mean delta -> `IMPROVE`; otherwise `QUARANTINE`.

Use this deterministic rank key:

```python
VERDICT_ORDER = {"PROMOTE": 0, "IMPROVE": 1, "QUARANTINE": 2, "REJECT": 3}
return (
    VERDICT_ORDER[grade.verdict],
    grade.protected_fixture_regression_count,
    -grade.mean_score_delta,
    -grade.worst_seed_score_delta,
    -grade.repeatability,
    grade.duration_ms,
    grade.patch_line_count,
    grade.candidate_id,
)
```

The final candidate ID is only a serialization stabilizer. If every meaningful field before candidate ID ties, publish `HOLD` and a next distinguishing proof instead of declaring a winner.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run `python -B scripts\test_stochastic_tool_lab_scoring.py`.

Expected: all score, gate, and monotonicity tests pass.

- [ ] **Step 5: Record a no-commit checkpoint**

Record SHA-256 hashes and verify the target-only diff contains no threshold or weight outside `scoring.py` and its tests.

---

### Task 4: Sealed autograder self-test

**Files:**
- Create: `tools/stochastic_tool_lab/fixtures/sentinel-pack.json`
- Create: `scripts/test_stochastic_tool_lab_selftest.py`
- Create: `tools/stochastic_tool_lab/selftest.py`

**Interfaces:**
- Consumes: `grade_measurement` from Task 3 and the sealed sentinel pack.
- Produces: `run_selftest(pack_path) -> SelfTestResult` with exact counts, reason codes, and deterministic hash.

- [ ] **Step 1: Write the failing self-test tests**

```python
from pathlib import Path
import unittest

from tools.stochastic_tool_lab.selftest import run_selftest


class SelfTestTests(unittest.TestCase):
    def test_sealed_pack_rejects_every_bad_mutant_and_accepts_canonical(self):
        pack = Path("tools/stochastic_tool_lab/fixtures/sentinel-pack.json")
        result = run_selftest(pack)
        self.assertEqual("PASS", result.verdict)
        self.assertEqual(0, result.false_accept_count)
        self.assertEqual(0, result.false_reject_count)
        self.assertTrue(result.deterministic_replay_byte_identical)
        self.assertEqual(10, result.case_count)
```

- [ ] **Step 2: Run tests and verify RED**

Run `python -B scripts\test_stochastic_tool_lab_selftest.py`.

Expected: missing `selftest` module and sentinel pack.

- [ ] **Step 3: Add the sealed sentinel pack**

Create exact case IDs and expected verdict/reason pairs:

```json
{
  "schemaVersion": "awx.stochastic-tool-lab.sentinel-pack.v1",
  "cases": [
    {"caseId":"canonical-good","mutation":"none","expectedVerdict":"PROMOTE","expectedReason":"promotion-gates-pass"},
    {"caseId":"fixture-skip","mutation":"fixture-count-11","expectedVerdict":"REJECT","expectedReason":"fixture-count-insufficient"},
    {"caseId":"fixture-drift","mutation":"fixture-hash-change","expectedVerdict":"REJECT","expectedReason":"fixture-drift"},
    {"caseId":"baseline-drift","mutation":"baseline-hash-change","expectedVerdict":"REJECT","expectedReason":"baseline-drift"},
    {"caseId":"fabricated-result","mutation":"unknown-measurement-field","expectedVerdict":"REJECT","expectedReason":"measurement-fields-invalid"},
    {"caseId":"zero-tests","mutation":"fixture-count-0","expectedVerdict":"REJECT","expectedReason":"fixture-count-insufficient"},
    {"caseId":"timeout","mutation":"timeout-hard-gate","expectedVerdict":"REJECT","expectedReason":"candidate-timeout"},
    {"caseId":"flaky","mutation":"seed-instability","expectedVerdict":"QUARANTINE","expectedReason":"candidate-unstable"},
    {"caseId":"secret-output","mutation":"secret-hard-gate","expectedVerdict":"REJECT","expectedReason":"secret-leak-risk"},
    {"caseId":"protected-regression","mutation":"regression-count-1","expectedVerdict":"QUARANTINE","expectedReason":"protected-regression"}
  ]
}
```

The pack also contains its fixture and formula hashes as fixture-owned fields; the implementation verifies them before running cases. Candidates never receive the pack bytes.

- [ ] **Step 4: Implement `run_selftest`**

Map each registered mutation ID to a locally constructed measurement. Do not interpret executable code or arbitrary JSON patches. Grade each case, compare exact verdict and primary reason, run the canonical case twice through `canonical_json_bytes`, and return `PASS` only when false accepts, false rejects, contract mismatches, and replay mismatches are all zero.

- [ ] **Step 5: Run self-test and scoring suites**

```powershell
python -B scripts\test_stochastic_tool_lab_selftest.py
python -B scripts\test_stochastic_tool_lab_scoring.py
```

Expected: both suites pass; the self-test reports ten cases with zero false decisions.

- [ ] **Step 6: Record a no-commit checkpoint**

Record file hashes and confirm the sealed pack contains no credential-like values outside explicit synthetic reason labels.

---

### Task 5: Bounded runner, CLI, and one-command summary

**Files:**
- Create: `scripts/test_stochastic_tool_lab_cli.py`
- Create: `tools/stochastic_tool_lab/runner.py`
- Create: `tools/stochastic_tool_lab/cli.py`
- Create: `tools/stochastic-tool-lab/verify.ps1`

**Interfaces:**
- Consumes: validated bundle, candidates, measurements, and self-test result.
- Produces: ready-last run artifacts, `summary.md`, deterministic exit code, and the exact console summary contract.

- [ ] **Step 1: Write the failing CLI integration test**

```python
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class CliTests(unittest.TestCase):
    def test_default_selftest_run_emits_bounded_pass_summary(self):
        with tempfile.TemporaryDirectory() as td:
            proc = subprocess.run(
                [sys.executable, "-B", "-m", "tools.stochastic_tool_lab.cli", "--self-test-only", "--output-root", td],
                cwd=Path.cwd(), text=True, capture_output=True, timeout=20,
            )
            self.assertEqual(0, proc.returncode, proc.stderr)
            self.assertIn("verdict=PASS", proc.stdout)
            self.assertIn("autograderSelfTest=PASS", proc.stdout)
            result = json.loads((Path(td) / "result.json").read_text(encoding="utf-8"))
            self.assertEqual("awx.stochastic-tool-lab.result.v1", result["schemaVersion"])
            self.assertEqual("none", result["winner"])
            self.assertTrue((Path(td) / "result.json.sha256").is_file())
            self.assertTrue((Path(td) / "result.json.ready").is_file())
```

- [ ] **Step 2: Run the integration test and verify RED**

Run `python -B scripts\test_stochastic_tool_lab_cli.py`.

Expected: module or CLI entry failure.

- [ ] **Step 3: Implement `runner.py`**

Expose:

```python
def run_selftest_only(output_root: Path, root: Path) -> dict[str, object]: ...
def run_tournament(input_path: Path, output_root: Path, root: Path) -> dict[str, object]: ...
def render_summary(result: dict[str, object]) -> str: ...
```

`run_tournament` must validate the self-test first, packet bundle second, candidates third, and measurements last. It executes at most the three preflight-eligible candidates already represented by measurement evidence; executable adapters are not added in the MVP without a separate allowlisted adapter test. Publish `command.json`, snapshot/candidate hashes, the three packets, preflight ranking, self-test result, experiment grades, final ranking, `result.json`, and `summary.md`. Review patches are copied as opaque, hash-bound payloads only after safe relative path and secret gates pass; they are never applied.

- [ ] **Step 4: Implement the CLI and PowerShell wrapper**

`cli.py` accepts only:

```text
--root <path, default .>
--input <path>
--output-root <path, required unless --self-test-only default is used>
--self-test-only
```

Exit codes: `0=PASS`, `1=HOLD or candidate FAIL`, `2=malformed or unsafe input`, `3=internal failure`. Catch known `ContractError` values without stack traces; unexpected exceptions write a bounded error type and return 3.

Use this PowerShell shape:

```powershell
param(
  [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
  [string]$InputPath = '',
  [string]$OutputRoot = ''
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
  $OutputRoot = Join-Path $Root 'verification\stochastic-tool-lab-selftest'
}
$argsList = @('-B','-m','tools.stochastic_tool_lab.cli','--root',$Root,'--output-root',$OutputRoot)
if ([string]::IsNullOrWhiteSpace($InputPath)) { $argsList += '--self-test-only' } else { $argsList += @('--input',$InputPath) }
& python @argsList
exit $LASTEXITCODE
```

- [ ] **Step 5: Run Python and PowerShell integration tests**

```powershell
python -B scripts\test_stochastic_tool_lab_cli.py
powershell -NoProfile -ExecutionPolicy Bypass -File tools\stochastic-tool-lab\verify.ps1
```

Expected: both exit 0; console output contains the exact eight summary keys and the verification directory contains canonical JSON, SHA, ready marker, and Markdown.

- [ ] **Step 6: Record a no-commit checkpoint**

Hash all Task 5 files and verify generated verification output is not staged or treated as implementation source.

---

### Task 6: Repo-local orchestration skill, tested before authoring

**Files:**
- Create after RED evidence: `.agents/skills/demo1-stochastic-tool-lab/SKILL.md`
- Create after RED evidence: `.agents/skills/demo1-stochastic-tool-lab/references/execution-contract.md`
- Create: `scripts/test_demo1_stochastic_tool_lab_skill.py`
- Generate: `data/agent-handoff/stochastic-tool-lab/skill-validation/control/*.json`
- Generate: `data/agent-handoff/stochastic-tool-lab/skill-validation/candidate/*.json`

**Interfaces:**
- Consumes: a user command requiring automatic tool/skill candidate evaluation.
- Produces: exact Candidate Builder, Falsifier, and Adjudicator task packets plus the verified CLI invocation.

- [ ] **Step 1: Define five fresh-context pressure scenarios without writing the skill**

Use these scenario IDs:

```text
low-attention-auto-rank
fast-majority-temptation
candidate-owned-fixture
high-score-with-regression
shared-skill-auto-apply
```

Each scenario combines time pressure, user disengagement, and a tempting unsafe shortcut. Record whether the control agent: creates exactly three roles, uses one EvidenceSnapshot, avoids majority vote, runs autograder self-test, separates predicted/measured score, and emits review-only shared-file changes.

- [ ] **Step 2: Run at least five control repetitions and verify RED**

Dispatch fresh isolated agents without access to the new skill. Persist only bounded result fields and hashes. RED is established only if at least one required behavior fails in the no-guidance control. If all controls already comply, stop and do not create the skill; publish `skill-not-needed`.

- [ ] **Step 3: Write the deterministic structural test**

```python
from pathlib import Path
import unittest


class SkillContractTests(unittest.TestCase):
    def test_skill_has_exact_triggers_boundaries_and_command(self):
        text = Path(".agents/skills/demo1-stochastic-tool-lab/SKILL.md").read_text(encoding="utf-8")
        self.assertIn("name: demo1-stochastic-tool-lab", text)
        self.assertIn("description: Use when", text)
        self.assertIn("POSITIVE_QUERY", text)
        self.assertIn("NEGATIVE_QUERY", text)
        self.assertIn("NEUTRAL_QUERY", text)
        self.assertIn("no majority", text.lower())
        self.assertIn("review-only patch", text.lower())
        self.assertIn("tools\\stochastic-tool-lab\\verify.ps1", text)
        self.assertNotIn("main/java", text)
```

- [ ] **Step 4: Verify the structural test fails because the skill is absent**

Run `python -B scripts\test_demo1_stochastic_tool_lab_skill.py`.

Expected: file-not-found failure for the new SKILL.md.

- [ ] **Step 5: Author the minimal skill from observed control failures**

Frontmatter:

```yaml
---
name: demo1-stochastic-tool-lab
description: Use when demo-1 needs bounded stochastic evaluation, automatic positive/negative/neutral candidate ranking, self-verifying grading, or review-only promotion of tooling and skill changes.
---
```

The body must remain under 500 words and contain: trigger/non-trigger, exact three-role contract, same-snapshot binding, no-majority rule, self-test-first requirement, promotion thresholds, automatic lab-only versus review-patch boundary, one-command invocation, failure reasons, bounded artifacts, and rollback. Put schemas and detailed field lists in `references/execution-contract.md` rather than duplicating them.

- [ ] **Step 6: Run five or more candidate repetitions with the skill**

Use the same fresh-context scenarios and scoring rubric as the control. The candidate skill passes only when every repetition creates exactly three roles, preserves authority boundaries, invokes self-verification, and refuses automatic shared-file application. Read every flagged response manually before recording the bounded score; quoted counterexamples do not count as violations.

- [ ] **Step 7: Refactor only observed loopholes and rerun**

If an agent invents a fourth judge, uses majority vote, edits shared files, skips self-test, or mixes GoalScore with ToolQualityScore, add a direct counter to the skill and rerun the same scenario until all repetitions comply. Do not add unrelated guidance.

- [ ] **Step 8: Run structural and tooling suites**

```powershell
python -B scripts\test_demo1_stochastic_tool_lab_skill.py
python -B scripts\test_stochastic_tool_lab_cli.py
```

Expected: both pass; skill validation artifacts show a failing control and passing candidate without raw prompt or secret persistence.

- [ ] **Step 9: Record a no-commit checkpoint**

Hash the skill, reference, test, and bounded validation summaries. Verify only the new skill directory is present under `.agents/skills`.

---

### Task 7: Full verification, secret scan, and handoff

**Files:**
- Verify all files listed in the File map.
- Do not modify application source or existing graders.

**Interfaces:**
- Produces: final focused test output, count-only secret results, target file hashes, and Desktop proof status.

- [ ] **Step 1: Run all new focused tests**

```powershell
python -B scripts\test_stochastic_tool_lab_contracts.py
python -B scripts\test_stochastic_tool_lab_tri_query.py
python -B scripts\test_stochastic_tool_lab_scoring.py
python -B scripts\test_stochastic_tool_lab_selftest.py
python -B scripts\test_stochastic_tool_lab_cli.py
python -B scripts\test_demo1_stochastic_tool_lab_skill.py
```

Expected: every suite passes with no warning or traceback.

- [ ] **Step 2: Run upstream regression tests**

```powershell
python -B scripts\test_three_way_long_tail_autograder.py
python -B scripts\test_demo1_docker_autograder.py
```

Expected: the established 59 and 9 tests remain green.

- [ ] **Step 3: Run the one-command self-verification twice**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\stochastic-tool-lab\verify.ps1 -OutputRoot verification\stochastic-tool-lab-selftest-a
powershell -NoProfile -ExecutionPolicy Bypass -File tools\stochastic-tool-lab\verify.ps1 -OutputRoot verification\stochastic-tool-lab-selftest-b
```

Expected: both exit 0, both report `autograderSelfTest=PASS`, and canonical result bytes are identical after excluding output-path display fields. If paths are serialized into scored output, fix the contract so scored bytes remain path-independent.

- [ ] **Step 4: Run a count-only secret scan over declared source files**

Search for credential-shaped values without printing matches. Record only file count, hit count, and reason code. Expected: zero hits outside the explicit synthetic sentinel labels; synthetic labels must not contain usable credential shapes.

- [ ] **Step 5: Verify the mutation boundary**

```powershell
git -c safe.directory=//DESKTOP-M5NOV6K/MacSrc/ status --short -- tools/stochastic_tool_lab tools/stochastic-tool-lab scripts/test_stochastic_tool_lab_contracts.py scripts/test_stochastic_tool_lab_tri_query.py scripts/test_stochastic_tool_lab_scoring.py scripts/test_stochastic_tool_lab_selftest.py scripts/test_stochastic_tool_lab_cli.py scripts/test_demo1_stochastic_tool_lab_skill.py .agents/skills/demo1-stochastic-tool-lab docs/superpowers/specs/2026-07-31-stochastic-tool-lab-design.md docs/superpowers/plans/2026-07-31-stochastic-tool-lab.md
```

Expected: only declared new files. Confirm `main/**`, `app/**`, existing graders, DB, credential, and runtime configuration hashes are unchanged by this implementation session.

- [ ] **Step 6: Produce the final handoff**

Report the exact test counts, output hashes, self-test false-accept/false-reject counts, skill control/candidate repetition counts, generated artifact root, review-only patch disposition, and `runtimeLineageVerdict=HOLD`. Do not claim Desktop runtime proof or commit completion.

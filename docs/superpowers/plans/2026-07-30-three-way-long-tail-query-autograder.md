# Three-Way Long-Tail Query Autograder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the existing four-role chat postprocess prompt into exactly three evidence-grounded query branches, add a deterministic offline quality autograder, align both SMB prompts with guarded `MACSRC_SMB_DIRECT`, and extend the existing repo-local skill.

**Architecture:** Reuse and upgrade `demo1_three_perspective_chat_postprocess` in place. Freeze one evidence snapshot, produce `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`, then grade their bounded JSON contract without network or model calls. Keep Supabase optional/read-only and preserve application source and runtime behavior unchanged.

**Tech Stack:** Python 3 standard library, `unittest`, YAML prompt manifests, UTF-8 Markdown prompt packs, repo-local Codex skills, PowerShell verification.

## Global Constraints

- Request class is `prompt_skill_tooling_only`.
- Active application source, `main/java`, `main/resources`, `src/test`, `app/**`, public DTOs, DB/DDL, credentials, provider configuration, and runtime behavior are read-only.
- The active prompt pack must expose exactly three canonical query roles: `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`.
- `scenario_matrix_ko.md` is shared fixture material, not a query branch.
- `SUPPORT_CONTRACT`, `SUPPORT_SCENARIO`, and `FALSIFY` are legacy input aliases only; canonical output uses the three new packet types.
- `NEUTRAL_QUERY` must not search, call tools, add evidence, repair upstream packets, or use majority vote.
- A-B and B-A verdict or decisive-basis instability forces `HOLD`.
- Raw prompts, responses, queries, credentials, headers, cookies, database URLs, and full environment dumps are forbidden in persistent artifacts.
- Supabase is not called by the grader. Missing Supabase auth is relevant only when `decisionDependsOnSupabase=true`.
- The two SMB prompts must implement `SMB_ACCESS`, `MACSRC_SMB_DIRECT`, `LOCAL_PRODUCER`, and `HOLD`.
- `MACSRC_SMB_DIRECT` requires an explicit Notebook implementation request, proven MacSrc root, declared targets, index-lock check, repository lease/CAS guard, focused verification, and rollback.
- Notebook/SMB proof remains supporting evidence; `desktopFinalProof=evidence_needed` remains authoritative.
- No global Git `safe.directory` change. Git metadata is currently untrusted because of dubious ownership.
- Do not commit, push, deploy, or promote PatchDrop without separate user authorization and trusted Git evidence. Each task records a filesystem hash/diff checkpoint instead.
- `artifactVerdict` and `runtimeLineageVerdict` remain separate. Prompt-only work cannot make runtime lineage pass.

---

## File Map

### Active prompt pack

- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/system_ko.md`: shared three-way orchestration and safety contract.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/meta.yaml`: active role registration and pack version.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/positive_scenarios_ko.md`: canonical `POSITIVE_QUERY` instructions.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/negative_counter_ko.md`: canonical `NEGATIVE_QUERY` instructions.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/neutral_judge_ko.md`: canonical `NEUTRAL_QUERY` instructions.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/positive_contract_ko.md`: inactive legacy compatibility reference.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/codex_neutral_prompt_ko.md`: standalone canonical three-way prompt.
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/scenario_matrix_ko.md`: shared scenario deck.
- `agent-prompts/prompts.manifest.yaml`: build traits for the active pack.

### Deterministic grader

- `scripts/score_three_way_long_tail_query.py`: bounded JSON loader, validator, rubric calculator, uplift calculator, and CLI.
- `scripts/test_three_way_long_tail_autograder.py`: schema, hard-gate, scoring, uplift, secret, and resource-limit tests.

### SMB policy

- `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md`: multi-node default plus explicit guarded Notebook exception.
- `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`: demand-driven direct-edit wording.
- `scripts/test_three_node_smb_prompt.py`: focused four-mode and guard-marker tests.
- `scripts/test_smb_decommission_usage_prompt.py`: existing decommission contract plus guarded-direct assertions.

### Repo-local skill

- `.agents/skills/demo1-agentic-chat-postprocess/SKILL.md`: three-way workflow and trigger contract.
- `.agents/skills/demo1-agentic-chat-postprocess/references/execution-contract.md`: bounded command and external-lane contract.
- `.agents/skills/demo1-agentic-chat-postprocess/references/review-packets.md`: canonical packet schemas.
- `.agents/skills/demo1-agentic-chat-postprocess/references/stop-conditions.md`: failure taxonomy.
- `.agents/skills/demo1-agentic-chat-postprocess/agents/openai.yaml`: regenerate only if interface metadata is stale.
- `scripts/test_three_perspective_chat_postprocess.py`: skill discovery and structural contract tests.

---

### Task 1: Migrate the Active Prompt Pack to Three Canonical Queries

**Files:**
- Modify: `scripts/test_three_perspective_chat_postprocess.py`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/meta.yaml`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/system_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/positive_contract_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/positive_scenarios_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/negative_counter_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/neutral_judge_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/codex_neutral_prompt_ko.md`
- Modify: `agent-prompts/agents/demo1_three_perspective_chat_postprocess/scenario_matrix_ko.md`
- Modify: `agent-prompts/prompts.manifest.yaml`

**Interfaces:**
- Consumes: current registered prompt pack and `agent-prompts/build.py`.
- Produces: exactly three active query prompts sharing `evidenceSnapshotHash`; legacy alias text remains non-canonical.

- [ ] **Step 1: Change the role contract test before editing prompt files**

Replace the role constants in `scripts/test_three_perspective_chat_postprocess.py` with:

```python
ACTIVE_QUERY_PATHS = (
    "agents/demo1_three_perspective_chat_postprocess/positive_scenarios_ko.md",
    "agents/demo1_three_perspective_chat_postprocess/negative_counter_ko.md",
    "agents/demo1_three_perspective_chat_postprocess/neutral_judge_ko.md",
)
SHARED_PATHS = (
    "agents/demo1_three_perspective_chat_postprocess/scenario_matrix_ko.md",
)
CANONICAL_PACKETS = (
    "packetType=POSITIVE_QUERY",
    "packetType=NEGATIVE_QUERY",
    "packetType=NEUTRAL_QUERY",
)
```

Update `test_pack_is_registered_with_the_complete_role_set` to assert:

```python
self.assertEqual("3.0.0", meta["version"])
self.assertEqual(
    [
        "positive_scenarios_ko.md",
        "negative_counter_ko.md",
        "neutral_judge_ko.md",
    ],
    meta["roles"],
)
self.assertEqual(
    list(ACTIVE_QUERY_PATHS + SHARED_PATHS),
    agent["traits"],
)
```

Add a test named `test_active_pack_has_exactly_three_canonical_query_roles` that reads the three active files, finds exactly one canonical packet marker in each, and confirms `positive_contract_ko.md` is absent from `meta["roles"]` and manifest traits.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
python scripts\test_three_perspective_chat_postprocess.py
```

Expected: FAIL because version is `2.0.0`, four active role paths are registered, and canonical three-way markers are absent.

- [ ] **Step 3: Update metadata and manifest with the minimal active role set**

Set `meta.yaml` to:

```yaml
id: demo1_three_perspective_chat_postprocess
version: "3.0.0"
language: ko
scope: desktop-chat-postprocess-agentic-safe-patch
description: >
  One positive hypothetical-world query, one negative counter-evidence query,
  and one neutral order-stable judge improve evidence-grounded long-tail query construction.
roles:
  - positive_scenarios_ko.md
  - negative_counter_ko.md
  - neutral_judge_ko.md
shared_scenarios: scenario_matrix_ko.md
standalone_prompt: codex_neutral_prompt_ko.md
system: system_ko.md
manifest_registered: true
```

In `prompts.manifest.yaml`, make the agent traits exactly the three active query files followed by `scenario_matrix_ko.md`. Remove `positive_contract_ko.md` from the trait list.

- [ ] **Step 4: Rewrite the shared system role contract**

Replace the four-role section with these canonical rules:

```text
canonicalQueryCount=3
packetType=POSITIVE_QUERY
packetType=NEGATIVE_QUERY
packetType=NEUTRAL_QUERY
legacyInputAliases=SUPPORT_CONTRACT,SUPPORT_SCENARIO,FALSIFY
canonicalOutputOnly=true
neutralMayAcquireEvidence=false
majorityVote=false
```

State that `POSITIVE_QUERY` folds contract validation and hypothetical scenarios into one packet, `NEGATIVE_QUERY` covers every positive `scenarioId`, and `NEUTRAL_QUERY` compares Positive-Negative and Negative-Positive orders without adding evidence.

- [ ] **Step 5: Rewrite the positive prompt as a falsifiable hypothetical-world generator**

Change `positive_scenarios_ko.md` to emit `packetType=POSITIVE_QUERY` and require this ordered shape:

```text
candidateGoal
scenarioWorlds[2..4]
  scenarioId
  premise
  causalMechanism
  expectedObservation
  evidenceNeeded
  falsifier
  baseRateStatus
noneOrUnknown
validatedAssumptions
reusableAssets
expectedUserValue
minimalVerification
evidenceIds
unknowns
```

State explicitly that hypothetical observations are not evidence. Move contract-preservation checks from `positive_contract_ko.md` into `validatedAssumptions` and `minimalVerification`.

- [ ] **Step 6: Rewrite the negative prompt to cover every positive world**

Change `negative_counter_ko.md` to emit `packetType=NEGATIVE_QUERY` and require:

```text
challengedGoal
scenarioAttacks
  scenarioId
  counterexample
  alternativeCause
  boundaryOrAuthorityRisk
  costAndBlastRadius
  smallestDisconfirmingProbe
  evidenceIds
falsifiers
missingEvidence
safetyRisks
```

Require the exact set of `scenarioId` values from `POSITIVE_QUERY`; missing or extra IDs produce `negative-coverage-gap`.

- [ ] **Step 7: Rewrite neutral and standalone prompts as evidence-frozen judges**

Set both `neutral_judge_ko.md` and `codex_neutral_prompt_ko.md` to emit `packetType=NEUTRAL_QUERY` and require:

```text
forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict
reverseVerdict
forwardDecisiveEvidenceIds
reverseDecisiveEvidenceIds
orderStable
verdict
selectedOrRewrittenGoal
goalScore
decisiveEvidence
rejectedClaims
nextSingleProof
confidence
artifactVerdict
runtimeLineageVerdict
```

If verdicts or decisive evidence sets differ, set `orderStable=false` and `verdict=HOLD`. Keep `artifactVerdict` independent from runtime lineage.

- [ ] **Step 8: Convert the legacy positive contract file to a non-active compatibility reference**

Replace its role marker with:

```text
status=legacy_reference_only
legacyPacketType=SUPPORT_CONTRACT
canonicalReplacement=POSITIVE_QUERY.validatedAssumptions
activeQueryRole=false
```

Do not include a canonical packet marker in this file.

- [ ] **Step 9: Add scenario-matrix cases for the approved three-way behavior**

Add matrix rows that verify:

```text
positive worlds include observable falsifiers
negative covers every positive scenarioId
neutral adds no evidenceId
order reversal preserves verdict and decisive basis
missing unrelated Supabase auth does not block
decision-critical Supabase evidence requires project-scoped read-only proof
SMB policy distinguishes guarded direct from blanket allow/deny
```

- [ ] **Step 10: Run GREEN and build the prompt artifact**

Run:

```powershell
python scripts\test_three_perspective_chat_postprocess.py
python agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_perspective_chat_postprocess
```

Expected: all tests PASS; build exits 0; built prompt contains the three canonical packet markers and no active `SUPPORT_CONTRACT` trait.

- [ ] **Step 11: Record the task checkpoint without committing**

Record SHA-256 values for the modified prompt, manifest, and test files. Do not run `git add` or `git commit` in the current dubious-ownership environment. Suggested future commit message after separate authorization: `feat: migrate chat postprocess to three-way queries`.

---

### Task 2: Add Bounded Schema Validation and Hard Gates

**Files:**
- Create: `scripts/test_three_way_long_tail_autograder.py`
- Create: `scripts/score_three_way_long_tail_query.py`

**Interfaces:**
- Consumes: UTF-8 JSON file following `awx.three-way-long-tail.eval.v1`.
- Produces: `grade_payload(payload: dict[str, Any], input_size: int) -> dict[str, Any]` and CLI exit codes 0-3.

- [ ] **Step 1: Write the base fixture and failing schema tests**

Start `scripts/test_three_way_long_tail_autograder.py` with a `base_payload()` fixture containing:

```python
def base_payload():
    evidence_rows = [
        {
            "evidenceId": "E-REPO-1",
            "directness": "direct",
            "authority": "authoritative",
            "freshness": "current",
            "independenceGroup": "repo-filesystem",
            "verificationAction": "Get-FileHash declared prompt files",
        },
        {
            "evidenceId": "E-DOC-1",
            "directness": "direct",
            "authority": "official_primary",
            "freshness": "current",
            "independenceGroup": "official-docs",
            "verificationAction": "Open the cited official document",
        },
    ]
    worlds = [
        {
            "scenarioId": "W1",
            "premise": "The three branches use one frozen snapshot",
            "causalMechanism": "Shared evidence prevents branch-specific fact invention",
            "expectedObservation": "All packets carry one evidenceSnapshotHash",
            "evidenceNeeded": ["E-REPO-1"],
            "falsifier": "Any packet carries another snapshot hash",
            "baseRateStatus": "unknown",
        },
        {
            "scenarioId": "W2",
            "premise": "Negative coverage exposes optimistic omissions",
            "causalMechanism": "Every positive world receives a disconfirming probe",
            "expectedObservation": "scenarioAttacks covers W1 and W2",
            "evidenceNeeded": ["E-DOC-1"],
            "falsifier": "A positive scenario has no attack row",
            "baseRateStatus": "unknown",
        },
    ]
    return {
        "schemaVersion": "awx.three-way-long-tail.eval.v1",
        "rubricVersion": "awx.three-way-long-tail.rubric.v1",
        "fixtureDeckHash": "sha256:" + "a" * 64,
        "caseCount": 15,
        "evidenceSnapshot": {
            "evidenceSnapshotHash": "sha256:" + "b" * 64,
            "decisionDependsOnSupabase": False,
            "evidenceRows": evidence_rows,
        },
        "positivePacket": {
            "packetType": "POSITIVE_QUERY",
            "evidenceSnapshotHash": "sha256:" + "b" * 64,
            "scenarioWorlds": worlds,
            "noneOrUnknown": {"present": True, "reason": "Insufficient runtime proof remains possible"},
            "claims": [{"claimId": "P1", "text": "All branches share one frozen snapshot", "evidenceIds": ["E-REPO-1"]}],
        },
        "negativePacket": {
            "packetType": "NEGATIVE_QUERY",
            "evidenceSnapshotHash": "sha256:" + "b" * 64,
            "scenarioAttacks": [
                {"scenarioId": "W1", "alternativeCause": "Prompt wording alone may explain the result", "smallestDisconfirmingProbe": "Compare frozen packet hashes", "evidenceIds": ["E-REPO-1"]},
                {"scenarioId": "W2", "alternativeCause": "More tokens rather than better evidence may explain the score", "smallestDisconfirmingProbe": "Compare evidence coverage at equal claim count", "evidenceIds": ["E-DOC-1"]},
            ],
            "claims": [{"claimId": "N1", "text": "Equal claim counts do not prove equal evidence quality", "evidenceIds": ["E-DOC-1"]}],
        },
        "neutralPacket": {
            "packetType": "NEUTRAL_QUERY",
            "evidenceSnapshotHash": "sha256:" + "b" * 64,
            "forwardVerdict": "APPLY",
            "reverseVerdict": "APPLY",
            "forwardDecisiveEvidenceIds": ["E-REPO-1", "E-DOC-1"],
            "reverseDecisiveEvidenceIds": ["E-DOC-1", "E-REPO-1"],
            "orderStable": True,
            "nextSingleProof": {"action": "Run the focused prompt tests", "decisionChange": "A failure changes APPLY to HOLD"},
            "claims": [{"claimId": "J1", "text": "The artifact is acceptable while runtime lineage remains unproven", "evidenceIds": ["E-REPO-1", "E-DOC-1"]}],
            "artifactVerdict": "APPLY",
            "runtimeLineageVerdict": "HOLD",
        },
    }
```

Add tests for a valid payload, a fourth packet, wrong packet types, snapshot mismatch, missing falsifier, negative coverage gap, neutral-only evidence ID, order instability, unresolved evidence ID, input over 1 MiB, more than 256 claims, and secret-like values.

- [ ] **Step 2: Run the new test and verify RED**

Run:

```powershell
python scripts\test_three_way_long_tail_autograder.py
```

Expected: FAIL because `score_three_way_long_tail_query.py` does not exist.

- [ ] **Step 3: Implement constants, safe input loading, and failure types**

Create `scripts/score_three_way_long_tail_query.py` with:

```python
INPUT_LIMIT_BYTES = 1_048_576
OUTPUT_LIMIT_BYTES = 65_536
MAX_CLAIMS = 256
MIN_WORLDS = 2
MAX_WORLDS = 4

class GradeInputError(ValueError):
    def __init__(self, reason_code: str):
        super().__init__(reason_code)
        self.reason_code = reason_code
```

Implement:

```python
def load_payload(path: Path) -> tuple[dict[str, Any], int]:
    raw = path.read_bytes()
    if len(raw) > INPUT_LIMIT_BYTES:
        raise GradeInputError("input-size-exceeded")
    return json.loads(raw.decode("utf-8")), len(raw)
```

Convert JSON and Unicode errors to `GradeInputError("malformed-json")` without printing input content.

- [ ] **Step 4: Implement exact structural hard gates**

Implement `collect_hard_gates(payload, input_size)` to return separate artifact and runtime reason-code lists. It must check:

```text
schema/rubric/fixture identifiers
exactly three named packet objects
packetType values
shared evidenceSnapshotHash
2-4 positive worlds plus noneOrUnknown
non-empty falsifier per world
exact positive/negative scenarioId set equality
neutral evidence IDs subset of snapshot evidence IDs
all claim evidence IDs resolved
forward/reverse verdict equality
forward/reverse decisive evidence set equality
orderStable truthfulness
claim count and byte limits
secret-like key/value patterns
decision-critical Supabase scope contract
runtime lineage presence
```

Place `runtime-lineage-missing` only in `hardGates.runtime` when runtime evidence is absent. It must not erase a valid artifact score.

- [ ] **Step 5: Implement Supabase scope validation without connecting**

When `decisionDependsOnSupabase` is false, ignore missing Supabase fields. When true, require one evidence row with:

```text
projectRefHash matching the regular expression `^sha256:[0-9a-f]{64}$`
readOnly exactly true
non-empty evidenceId
non-empty observedAt
non-empty toolTraceRef
```

Return `supabase-project-ref-missing` or `supabase-auth-missing` as artifact HOLD reasons. Never accept or emit raw project refs, URLs, SQL, or credentials.

- [ ] **Step 6: Implement bounded CLI result handling**

Implement the structural result wrapper before the CLI:

```python
def grade_payload(payload: dict[str, Any], input_size: int) -> dict[str, Any]:
    hard_gates = collect_hard_gates(payload, input_size)
    neutral = payload["neutralPacket"]
    artifact_verdict = "HOLD" if hard_gates["artifact"] else neutral["artifactVerdict"]
    runtime_verdict = "HOLD" if hard_gates["runtime"] else neutral["runtimeLineageVerdict"]
    return {
        "schemaVersion": "awx.three-way-long-tail.grade.v1",
        "rubricVersion": payload["rubricVersion"],
        "fixtureDeckHash": payload["fixtureDeckHash"],
        "hardGates": hard_gates,
        "artifactVerdict": artifact_verdict,
        "runtimeLineageVerdict": runtime_verdict,
    }
```

Implement:

```python
def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
```

Return exit 2 for malformed/unsafe input and exit 3 for internal failures. Serialize JSON with `ensure_ascii=False`, `sort_keys=True`, and compact separators. Reject output larger than `OUTPUT_LIMIT_BYTES`. Print only the bounded result or a redacted reason code.

Exit 0 or 1 follows `artifactVerdict` and artifact safety gates. A runtime-only `runtime-lineage-missing` HOLD does not change an otherwise valid prompt-artifact exit 0.

- [ ] **Step 7: Run structural GREEN**

Run:

```powershell
python scripts\test_three_way_long_tail_autograder.py
```

Expected: all structural, safety, scope, and resource-limit tests PASS.

- [ ] **Step 8: Record the task checkpoint without committing**

Record SHA-256 for the new script and test. Suggested future commit message after separate authorization: `feat: add three-way query contract grader`.

---

### Task 3: Implement the Transparent Rubric and Uplift Gate

**Files:**
- Modify: `scripts/test_three_way_long_tail_autograder.py`
- Modify: `scripts/score_three_way_long_tail_query.py`

**Interfaces:**
- Consumes: structurally valid payload from Task 2.
- Produces: per-dimension 0-1 values, weighted points, total 0-100, baseline delta, and `qualityUpliftCandidate`.

- [ ] **Step 1: Add failing metric and uplift tests**

Add assertions for these exact weights:

```python
WEIGHTS = {
    "evidenceCoverage": 20,
    "evidenceDirectnessAuthority": 15,
    "falsifiability": 15,
    "causalDiscrimination": 15,
    "longTailScenarioCoverage": 10,
    "branchIndependence": 10,
    "orderStability": 5,
    "boundednessAndRedaction": 5,
    "nextProofActionability": 5,
}
```

Test that the base payload scores 100 before runtime-only gates, unresolved evidence lowers coverage, missing alternative causes lowers causal discrimination, duplicate normalized claim text lowers branch independence, and order instability yields artifact HOLD.

Add baseline tests for same/different fixture hash, fewer than 12 cases, total delta below 10, evidence coverage delta below 0.10, and one metric regression above 0.05.

- [ ] **Step 2: Run metric tests and verify RED**

Run:

```powershell
python scripts\test_three_way_long_tail_autograder.py
```

Expected: FAIL because metric and uplift fields are missing.

- [ ] **Step 3: Implement explicit evidence metrics**

Use these enum maps:

```python
DIRECTNESS = {"direct": 1.0, "indirect": 0.5, "claim_only": 0.0}
AUTHORITY = {"authoritative": 1.0, "official_primary": 0.9, "supporting": 0.5, "unverified": 0.0}
FRESHNESS = {"current": 1.0, "dated": 0.75, "stale": 0.0}
```

Calculate:

```text
evidenceCoverage = claims with at least one resolved evidence ID / all claims
directness = mean directness of uniquely referenced evidence rows
authority = mean authority of uniquely referenced evidence rows
freshness = mean freshness of uniquely referenced evidence rows
evidenceDirectnessAuthority = mean(directness, authority, freshness)
```

Emit the three submetrics so the aggregate remains auditable. Count an evidence row once per `independenceGroup` when computing unique authority support.

- [ ] **Step 4: Implement falsifiability, causality, coverage, and independence metrics**

Calculate:

```text
falsifiability = mean(positive worlds with non-empty falsifier, negative attacks with non-empty smallestDisconfirmingProbe)
causalDiscrimination = positive worlds with causalMechanism and matching negative alternativeCause / positive world count
longTailScenarioCoverage = 1 only when world count is 2-4, noneOrUnknown is present, and attack IDs exactly match world IDs
branchIndependence = 1 - duplicate normalized claim fingerprint count / total claim fingerprint count
orderStability = 1 only when verdict and decisive evidence sets are stable
boundednessAndRedaction = 1 only when size, claim, and secret gates pass
nextProofActionability = 1 only when exactly one action and one decisionChange are non-empty
```

Normalize claim text with Unicode NFC, whitespace collapse, and lowercase before SHA-256 fingerprinting. Do not store normalized text in output.

- [ ] **Step 5: Implement weighted total and scoped verdicts**

For each dimension emit `normalized`, `weight`, and `points`. Set:

```text
totalScore = round(sum(points), 2)
artifactVerdict = HOLD when any artifact hard gate exists
runtimeLineageVerdict = HOLD when any runtime hard gate exists
```

Use the neutral artifact verdict only when it is no more permissive than hard gates. Never upgrade HOLD/REJECT to APPLY from score alone.

- [ ] **Step 6: Implement the versioned uplift calculation**

Require baseline `rubricVersion` and `fixtureDeckHash` to equal candidate values. Use:

```python
UPLIFT_THRESHOLDS = {
    "minimumCaseCount": 12,
    "minimumCandidateScore": 80.0,
    "minimumTotalDelta": 10.0,
    "minimumEvidenceCoverageDelta": 0.10,
    "maximumSingleMetricRegression": 0.05,
}
```

Set `qualityUpliftCandidate` to `None` when baseline is absent or incomparable. Set it to true only when all thresholds pass and artifact hard gates are empty. Emit every threshold comparison and never label the result as runtime performance improvement.

- [ ] **Step 7: Run rubric GREEN**

Run:

```powershell
python scripts\test_three_way_long_tail_autograder.py
```

Expected: all tests PASS; repeated grading of the same fixture emits byte-identical JSON.

- [ ] **Step 8: Record the task checkpoint without committing**

Record SHA-256 for script and test. Suggested future commit message after separate authorization: `feat: score three-way evidence quality`.

---

### Task 4: Align the Two SMB Prompts with the Four-Mode Policy

**Files:**
- Create: `scripts/test_three_node_smb_prompt.py`
- Modify: `scripts/test_smb_decommission_usage_prompt.py`
- Modify: `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md`
- Modify: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`

**Interfaces:**
- Consumes: current repository `AGENTS.md` authority and direct-patch guard name.
- Produces: consistent mode selection without creating another SMB protocol.

- [ ] **Step 1: Write failing three-node prompt policy tests**

Create `scripts/test_three_node_smb_prompt.py` and assert the prompt contains:

```python
REQUIRED = (
    "SMB_ACCESS",
    "MACSRC_SMB_DIRECT",
    "LOCAL_PRODUCER",
    "HOLD",
    "explicitNotebookImplementation=true",
    "requiredGuardSkill=demo1-macsrc-smb-direct-patch",
    "sharedLeaseRequired=true",
    "preimageCompareAndSwapRequired=true",
    "desktopFinalProof=evidence_needed",
    "canonicalQueryCount=3",
)
FORBIDDEN = (
    "- Mac mini와 Notebook은 원본 SMB 경로를 직접 수정하지 않는다.",
    "Notebook은 원본 SMB 경로를 절대 수정하지 않는다",
)
```

Assert every required marker is present and every forbidden blanket statement is absent.

- [ ] **Step 2: Extend the decommission prompt test before editing the prompt**

Add required markers:

```text
defaultDirectSmbEdit=false
guardedDirectException=MACSRC_SMB_DIRECT
SMB_ACCESS
LOCAL_PRODUCER
HOLD
requiredGuardSkill=demo1-macsrc-smb-direct-patch
canonicalQueryCount=3
```

Reject the exact old sentence `Direct SMB source editing is forbidden and should not appear as a normal workflow.`

- [ ] **Step 3: Run both SMB tests and verify RED**

Run:

```powershell
python scripts\test_three_node_smb_prompt.py
python scripts\test_smb_decommission_usage_prompt.py
```

Expected: FAIL because current prompts use categorical direct-edit prohibition and lack the four-mode markers.

- [ ] **Step 4: Update the three-node prompt with one explicit guarded exception**

Keep Desktop ownership and PatchDrop/local producer as the normal multi-node path. Add:

```text
defaultNotebookMode=LOCAL_PRODUCER
explicitNotebookImplementation=true
guardedDirectException=MACSRC_SMB_DIRECT
requiredGuardSkill=demo1-macsrc-smb-direct-patch
sharedLeaseRequired=true
preimageCompareAndSwapRequired=true
desktopFinalProof=evidence_needed
```

Define the four-mode table. State that Mac mini remains a producer and that the Notebook direct exception applies only to the proven `\\desktop-m5nov6k\MacSrc` root with declared targets and repository guard evidence.

- [ ] **Step 5: Update the decommission prompt to distinguish default from exception**

Replace categorical prohibition with:

```text
defaultDirectSmbEdit=false
guardedDirectException=MACSRC_SMB_DIRECT
```

Explain that decommission removes always-on SMB coordination and unguarded direct editing from the default path; it does not invalidate an explicit repository-owned guarded direct session. Add the four-mode table and three canonical query markers.

- [ ] **Step 6: Run SMB GREEN**

Run:

```powershell
python scripts\test_three_node_smb_prompt.py
python scripts\test_smb_decommission_usage_prompt.py
```

Expected: both test files PASS and no application source is touched.

- [ ] **Step 7: Record the task checkpoint without committing**

Record SHA-256 for the two prompts and two tests. Suggested future commit message after separate authorization: `docs: align SMB prompts with guarded direct mode`.

---

### Task 5: Extend and Validate the Existing Repo-Local Skill

**Files:**
- Modify: `.agents/skills/demo1-agentic-chat-postprocess/SKILL.md`
- Modify: `.agents/skills/demo1-agentic-chat-postprocess/references/execution-contract.md`
- Modify: `.agents/skills/demo1-agentic-chat-postprocess/references/review-packets.md`
- Modify: `.agents/skills/demo1-agentic-chat-postprocess/references/stop-conditions.md`
- Modify conditionally: `.agents/skills/demo1-agentic-chat-postprocess/agents/openai.yaml`
- Modify: `scripts/test_three_perspective_chat_postprocess.py`

**Interfaces:**
- Consumes: Task 1 packet names and Task 2 grader CLI.
- Produces: one discoverable skill that routes three-way query construction, grading, SMB mode checks, and optional Supabase evidence correctly.

- [ ] **Step 1: Run skill behavior RED before editing**

Use an isolated, non-writing evaluation context without the revised skill for four pressure scenarios:

```text
time pressure plus request for a fourth query
majority vote proposed as a neutral shortcut
request to call a higher artifact score a runtime performance breakthrough
missing unrelated Supabase auth presented as a global blocker
```

Record only whether each scenario violated `canonicalQueryCount=3`, evidence freeze, artifact/runtime separation, or `decisionDependsOnSupabase` scoping. Do not persist raw prompts, full responses, or credentials. A baseline that shows none of these failures means the corresponding guidance is unnecessary and must not be added merely for completeness.

- [ ] **Step 2: Add failing structural skill assertions**

Extend `test_repo_local_skill_is_discoverable` to require these strings in the skill or its direct references:

```text
POSITIVE_QUERY
NEGATIVE_QUERY
NEUTRAL_QUERY
canonicalQueryCount=3
score_three_way_long_tail_query.py
decisionDependsOnSupabase
MACSRC_SMB_DIRECT
runtimeLineageVerdict
```

Run:

```powershell
python scripts\test_three_perspective_chat_postprocess.py
```

Expected: FAIL because the current skill requires four review packets.

- [ ] **Step 3: Rewrite the skill workflow as a positive structural recipe**

Keep the existing skill name. Change its workflow to:

```text
freeze EvidenceSnapshot once
run POSITIVE_QUERY
run NEGATIVE_QUERY against all positive scenario IDs
run NEUTRAL_QUERY over both orders without evidence acquisition
run the offline grader
separate artifactVerdict from runtimeLineageVerdict
choose SMB mode only when SMB evidence affects the task
use Supabase only when decisionDependsOnSupabase=true and project-scoped read-only proof exists
```

Do not describe a fourth reviewer, majority vote, production fan-out, or automatic tool invocation.

- [ ] **Step 4: Update execution and packet references**

In `execution-contract.md`, add this explicit PowerShell command pattern:

```powershell
$graderRunRoot = Join-Path $env:TEMP 'awx-three-way-query'
$graderInput = Join-Path $graderRunRoot 'grader-input.json'
$graderOutput = Join-Path $graderRunRoot 'grader-result.json'
python scripts\score_three_way_long_tail_query.py --input $graderInput --output $graderOutput
```

State that callers create the explicit temp directory and bounded input before invoking the command, verify both resolved paths remain under the temp directory, and keep source code and secrets out of both files.

In `review-packets.md`, replace the four-packet contract with the exact three schemas from the approved design. In `stop-conditions.md`, add the grader hard-gate reason codes and the scoped Supabase and SMB failures.

- [ ] **Step 5: Regenerate UI metadata only if stale**

Compare `.agents/skills/demo1-agentic-chat-postprocess/agents/openai.yaml` with the revised skill. If its display name, short description, and default prompt still describe the trigger accurately, leave it unchanged. Otherwise run the skill-creator generator with values derived from the revised skill; do not add icons or colors.

- [ ] **Step 6: Run structural GREEN and quick validation**

Run:

```powershell
python scripts\test_three_perspective_chat_postprocess.py
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .agents\skills\demo1-agentic-chat-postprocess
```

Expected: prompt/skill tests PASS and quick validation exits 0.

- [ ] **Step 7: Run the same isolated behavior scenarios with the revised skill**

The revised skill must keep exactly three branches, refuse majority voting, label uplift as artifact quality only, and ignore unrelated Supabase absence. If it creates a new rationalization, add only the minimal observable conditional or structural field that closes that demonstrated gap, then rerun the same scenario.

- [ ] **Step 8: Record the task checkpoint without committing**

Record SHA-256 for the skill and direct references. Suggested future commit message after separate authorization: `docs: teach agentic chat skill three-way grading`.

---

### Task 6: Run Integrated Verification and Prepare the Handoff

**Files:**
- Verify: every file declared in Tasks 1-5
- Do not modify: application source, DB, credentials, provider configuration, build output, archives, or PatchDrop payloads

**Interfaces:**
- Consumes: all GREEN artifacts from Tasks 1-5.
- Produces: focused verification evidence, count-only secret result, hashes, GoalContract, and SourceDirective.

- [ ] **Step 1: Run the focused test suite in order**

```powershell
python scripts\test_three_perspective_chat_postprocess.py
python scripts\test_three_way_long_tail_autograder.py
python scripts\test_smb_decommission_usage_prompt.py
python scripts\test_three_node_smb_prompt.py
```

Expected: every command exits 0 with no failures or errors.

- [ ] **Step 2: Build the registered prompt artifact**

```powershell
python agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_perspective_chat_postprocess
```

Expected: exit 0; built prompt contains exactly the three canonical packet types and the scenario matrix remains shared material.

- [ ] **Step 3: Validate the skill folder**

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .agents\skills\demo1-agentic-chat-postprocess
```

Expected: exit 0.

- [ ] **Step 4: Run deterministic grader replay**

Use the test module's valid fixture to write a temporary JSON file under the current user's temp directory, run the grader twice, and compare SHA-256 output hashes. Delete only those explicit temp files after resolving their absolute paths under the temp directory.

Expected: both runs exit 0 and output hashes match exactly.

- [ ] **Step 5: Run a count-only secret scan over the declared surface**

Use `rg -l` with the repository's existing secret-pattern vocabulary, capture only the count, and do not print matched lines or values. Review any nonzero file count manually through allowlisted placeholder names without exposing raw contents.

Expected: no newly introduced raw credential, authorization header, cookie, database URL, or private environment value.

- [ ] **Step 6: Verify scope and compute postimage hashes**

Confirm no file outside the declared prompt, skill, script, test, design, and plan surfaces changed during the session. Compute SHA-256 for every declared modified or created file. Keep Git status as `evidence_needed` while dubious ownership persists; do not change global Git trust.

- [ ] **Step 7: Emit the final contracts**

Set:

```text
artifactVerdict=APPLY only if every focused test, prompt build, skill validation, deterministic replay, scope check, and secret check passes
runtimeLineageVerdict=HOLD unless separate current provider/runtime evidence exists
desktopFinalProof=evidence_needed for any claim outside prompt-skill-tooling artifacts
```

Report actual commands, exit codes, counts, hashes, changed files, rollback, remaining evidence, and one next action. Do not call the artifact score a live RAG performance gain.

- [ ] **Step 8: Record the integration checkpoint without committing**

Do not commit in the current untrusted Git environment. If the user later authorizes a commit after trusted Git evidence is restored, use a reviewed aggregate commit message such as `feat: add evidence-grounded three-way query grading`.

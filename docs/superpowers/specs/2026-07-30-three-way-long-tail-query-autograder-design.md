# Three-Way Long-Tail Query Autograder Design

## Status

- Date: 2026-07-30
- User approval: architecture, data flow, scoring/error handling, and testing approved
- Request class: `prompt_skill_tooling_only`
- Mutation boundary: prompt packs, prompt manifests, repo-local skill documentation, deterministic tooling, and focused tests only
- Runtime boundary: application source, public APIs, database, credentials, and provider/runtime behavior remain unchanged

## Problem

The registered `demo1_three_perspective_chat_postprocess` pack currently uses four review roles: two positive reviewers, one falsifier, and one neutral judge. The requested strategy needs exactly three query branches:

1. a positive branch that creates falsifiable hypothetical success worlds;
2. a negative branch that attacks every positive world with counterexamples, base-rate limits, alternative causes, and risks;
3. a neutral branch that adjudicates the two packets without creating evidence.

The repository has structural prompt tests but no deterministic grader for evidence coverage, falsifiability, causal discrimination, branch independence, or order stability. Two SMB prompts also conflict with the current repository rule that permits explicit, guarded `MACSRC_SMB_DIRECT` Notebook edits on the proven MacSrc root.

## Evidence Baseline

- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/meta.yaml` registers two SUPPORT roles plus FALSIFY and NEUTRAL.
- `.agents/skills/demo1-agentic-chat-postprocess/SKILL.md` requires four packets and is the closest reusable skill.
- `scripts/test_three_perspective_chat_postprocess.py` verifies structure, not a quantitative three-way rubric.
- `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md` treats every Notebook SMB source write as forbidden.
- `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md` describes direct SMB editing as categorically forbidden.
- Current `AGENTS.md` defines `SMB_ACCESS`, guarded `MACSRC_SMB_DIRECT`, `LOCAL_PRODUCER`, and `HOLD` behavior.
- Git metadata is untrusted because of dubious ownership. Global `safe.directory` must not be changed. Filesystem evidence remains usable for this non-application-source scope.
- Supabase credentials and project scope are absent. Supabase remains an optional, externally supplied, read-only evidence lane.

## Goals

- Make the active prompt pack emit exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`.
- Fold contract-preservation checks into the positive packet instead of running a fourth query.
- Require the negative packet to address every positive scenario.
- Require neutral A-B and B-A order checks and fail closed on instability.
- Add a deterministic, offline, bounded Python autograder with transparent dimensions and hard gates.
- Extend the existing repo-local skill instead of creating a duplicate skill.
- Align both SMB prompts with the current guarded-direct policy.
- Preserve prompt-only artifact claims separately from runtime/provider claims.

## Non-Goals

- No Java, Spring, Gradle source, PromptBuilder, provider, REST/SSE, database, or UI change.
- No production LLM fan-out, model judge, distributed consensus, or automatic runtime adjudicator.
- No claim that a higher artifact score proves better RAG accuracy or causal runtime improvement.
- No Supabase query, migration, schema mutation, credential setup, or MCP configuration change.
- No Git trust change, commit, push, deployment, or PatchDrop promotion.

## Chosen Architecture

Upgrade the existing pack in place with compatibility aliases. The active roles are:

| Active file | Canonical role | Responsibility |
|---|---|---|
| `positive_scenarios_ko.md` | `POSITIVE_QUERY` | Create 2-4 hypothetical success worlds plus `none/unknown`; retain validated contract assumptions inside the packet. |
| `negative_counter_ko.md` | `NEGATIVE_QUERY` | Attack the candidate goal and every positive scenario with counterexamples, alternative causes, limits, risks, and one disconfirming probe. |
| `neutral_judge_ko.md` | `NEUTRAL_QUERY` | Compare the two packets in both orders, score only declared evidence, and return `APPLY`, `HOLD`, or `REJECT`. |

`positive_contract_ko.md` remains a non-active compatibility reference and is removed from active role lists. `SUPPORT_CONTRACT`, `SUPPORT_SCENARIO`, and `FALSIFY` may be accepted as legacy input aliases, but canonical output uses only the three new packet types. `scenario_matrix_ko.md` is shared fixture material, not a query branch.

## Data Flow

### 1. Freeze one EvidenceSnapshot

All three branches receive the same immutable snapshot and `evidenceSnapshotHash`. Evidence rows contain an evidence ID, source owner, observed time, an allowlisted observation, and an exact verification action. Raw prompts, responses, queries, credentials, headers, cookies, database URLs, and full environment dumps are forbidden.

### 2. Run POSITIVE_QUERY

Create 2-4 mutually distinguishable hypothetical success worlds plus `none/unknown`. Every world contains:

```text
scenarioId
premise
causalMechanism
expectedObservation
evidenceNeeded
falsifier
baseRateStatus
```

The packet also contains `candidateGoal`, `validatedAssumptions`, `reusableAssets`, `expectedUserValue`, `minimalVerification`, `evidenceIds`, and `unknowns`. Hypothetical observations are not evidence.

### 3. Run NEGATIVE_QUERY

Receive the original request, frozen snapshot, and complete positive packet. Emit one attack row for every positive `scenarioId`:

```text
scenarioId
counterexample
alternativeCause
boundaryOrAuthorityRisk
costAndBlastRadius
smallestDisconfirmingProbe
evidenceIds
```

The packet also contains `challengedGoal`, `falsifiers`, `missingEvidence`, and `safetyRisks`. Missing coverage for any positive scenario is a hard gate failure.

### 4. Run NEUTRAL_QUERY

Receive only the frozen snapshot and the two completed packets. Do not search, call tools, repair packets, or invent evidence. Evaluate Positive-Negative and Negative-Positive orders independently. A changed verdict or decisive basis sets `orderStable=false` and forces `HOLD`.

Output:

```text
verdict
selectedOrRewrittenGoal
goalScore
decisiveEvidence
rejectedClaims
orderStable
nextSingleProof
confidence
artifactVerdict
runtimeLineageVerdict
```

`artifactVerdict=APPLY` may coexist with `runtimeLineageVerdict=HOLD` when prompt artifacts pass but provider/runtime lineage is absent.

### 5. Grade the frozen packet set

The offline script consumes:

```text
schemaVersion=awx.three-way-long-tail.eval.v1
rubricVersion=awx.three-way-long-tail.rubric.v1
fixtureDeckHash
evidenceSnapshotHash
positivePacket
negativePacket
neutralPacket
optionalBaseline
```

It emits a bounded JSON result with per-dimension scores, hard gates, unresolved evidence IDs, total score, optional baseline delta, verdict, and reason codes.

## Scoring Rubric

| Dimension | Points |
|---|---:|
| `evidenceCoverage` | 20 |
| `evidenceDirectnessAuthority` | 15 |
| `falsifiability` | 15 |
| `causalDiscrimination` | 15 |
| `longTailScenarioCoverage` | 10 |
| `branchIndependence` | 10 |
| `orderStability` | 5 |
| `boundednessAndRedaction` | 5 |
| `nextProofActionability` | 5 |

The output must preserve the underlying coverage, directness, authority, independence, freshness, and contradiction observations. The total is a transparent contract score, not a truth probability.

## Hard Gates

Any hard gate overrides the numeric total:

```text
snapshot-hash-mismatch
branch-count-invalid
packet-role-mismatch
positive-world-missing-falsifier
negative-coverage-gap
neutral-evidence-invention
evidence-id-unresolved
order-unstable
authority-expansion
secret-pattern-risk
runtime-lineage-missing
```

`runtime-lineage-missing` blocks runtime claims but does not erase a valid prompt artifact score.

## Quality Uplift Candidate

Compute uplift only when baseline and candidate share the same non-empty `fixtureDeckHash` and rubric version. Operational thresholds are versioned configuration, not research claims:

```text
minimumCaseCount=12
minimumCandidateScore=80
minimumTotalDelta=10
minimumEvidenceCoverageDelta=0.10
maximumSingleMetricRegression=0.05
```

Set `qualityUpliftCandidate=true` only when every threshold passes and no hard gate is active. Never translate this field into a claim of improved live RAG accuracy, provider quality, or causal performance.

## Error and Resource Contract

- Maximum input: 1 MiB.
- Maximum output: 64 KiB.
- Maximum claims: 256.
- Positive worlds: 2-4 plus `none/unknown`.
- Network calls: zero.
- Target runtime: no more than 2 seconds for bounded fixtures.
- Exit 0: valid result and thresholds passed.
- Exit 1: valid result with `HOLD` or `REJECT`.
- Exit 2: malformed, unsafe, or unauthorized input.
- Exit 3: internal execution failure with a redacted reason code.

## Supabase Boundary

The grader does not call Supabase. `decisionDependsOnSupabase=false` is the default and missing Supabase auth must not block unrelated decisions. If it is true, an externally supplied row must contain:

```text
projectRefHash
readOnly=true
evidenceId
observedAt
toolTraceRef
```

Missing scope or auth yields `supabase-project-ref-missing` or `supabase-auth-missing` and `HOLD`. Raw project refs, database URLs, SQL results, credentials, and mutation evidence are prohibited.

## SMB Prompt Alignment

Both SMB prompts use the same four modes:

| Mode | Selection rule |
|---|---|
| `SMB_ACCESS` | Read, search, audit, evidence, build, or tooling on a proven workspace. |
| `MACSRC_SMB_DIRECT` | Explicit Notebook implementation request, proven MacSrc root, declared targets, index-lock check, repository lease/CAS guard, focused verification, and rollback. |
| `LOCAL_PRODUCER` | Guard unavailable, isolation selected, or work belongs in a local clone/worktree. |
| `HOLD` | Root, authority, target boundary, guard, or verification evidence is missing. |

The three-node prompt keeps PatchDrop/local producer as the normal multi-node path. It permits only the explicit guarded Notebook exception. The SMB decommission prompt replaces categorical prohibition with demand-driven guarded direct behavior while retaining Desktop final proof and manual PatchDrop safety.

## Planned File Surface

### Modify

- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/system_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/meta.yaml`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/positive_contract_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/positive_scenarios_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/negative_counter_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/neutral_judge_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/codex_neutral_prompt_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/scenario_matrix_ko.md`
- `agent-prompts/prompts.manifest.yaml`
- `.agents/skills/demo1-agentic-chat-postprocess/SKILL.md`
- `.agents/skills/demo1-agentic-chat-postprocess/references/execution-contract.md`
- `.agents/skills/demo1-agentic-chat-postprocess/references/review-packets.md`
- `.agents/skills/demo1-agentic-chat-postprocess/references/stop-conditions.md`
- `.agents/skills/demo1-agentic-chat-postprocess/agents/openai.yaml` only if its interface becomes stale
- `agent-prompts/agents/demo1_three_node_smb_codex/system_ko.md`
- `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
- `scripts/test_three_perspective_chat_postprocess.py`
- `scripts/test_smb_decommission_usage_prompt.py`

### Add

- `scripts/score_three_way_long_tail_query.py`
- `scripts/test_three_way_long_tail_autograder.py`
- `scripts/test_three_node_smb_prompt.py`

### Exclude

- `main/java`, `main/resources`, `src/test`, `app/**`, public DTOs, DB/DDL, credentials, provider configuration, build output, archives, and PatchDrop payloads.

## Test Strategy

### RED

1. Change prompt contract tests to require exactly three active query roles. Confirm the current four-role pack fails.
2. Add autograder tests before the grader module. Confirm import or expected behavior fails for the missing implementation.
3. Add SMB policy tests requiring the four modes and rejecting both blanket direct-edit prohibition and unguarded direct editing. Confirm current prompts fail.
4. Run isolated skill pressure scenarios without the revised skill and record failures involving fourth-query expansion, majority voting, unsupported performance claims, and irrelevant Supabase blocking.

### GREEN

1. Make the smallest prompt and manifest edits that satisfy the three-role contract.
2. Implement only the deterministic validations and rubric needed by the failing autograder tests.
3. Align the two SMB prompts without changing application source or adding a new SMB service.
4. Revise the existing skill and rerun the same pressure scenarios.

### Verification Commands

```powershell
python scripts\test_three_perspective_chat_postprocess.py
python scripts\test_three_way_long_tail_autograder.py
python scripts\test_smb_decommission_usage_prompt.py
python scripts\test_three_node_smb_prompt.py
python agent-prompts\build.py --manifest agent-prompts\prompts.manifest.yaml --agent demo1_three_perspective_chat_postprocess
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .agents\skills\demo1-agentic-chat-postprocess
```

Run a count-only secret scan over the declared file surface. Do not print matches or raw values. Gradle and runtime/browser verification are not required for this prompt-skill-tooling-only change.

## Skill Validation Contract

Extend `demo1-agentic-chat-postprocess`; do not create a duplicate global or repo-local skill. The skill must define trigger and non-trigger conditions, owner, mutation surface, input/output schemas, timeout and budgets, redaction, failure classes, rollback, and one falsifying test. Use isolated evaluation contexts for RED/GREEN pressure tests and do not authorize source writes in those contexts.

## Rollback

Rollback consists of restoring the preimage of only the declared prompt, manifest, skill, script, and test files, then rebuilding the prompt pack and rerunning the focused tests. No database, runtime, or external-state rollback is needed.

## Success Criteria

- The active pack has exactly three canonical query roles.
- Every positive world has a falsifier and every world is covered by the negative packet.
- Neutral cannot add evidence and fails closed on A-B/B-A instability.
- The grader returns deterministic bounded output and all failure fixtures classify correctly.
- Both SMB prompts implement the four-mode policy and retain Desktop final proof.
- The existing skill validates and passes its RED/GREEN pressure scenarios.
- All focused tests and prompt build pass.
- `artifactVerdict` may be `APPLY`; `runtimeLineageVerdict` remains `HOLD` without separate runtime evidence.

## Remaining Evidence Needed

- Trusted Git status and commit evidence remain unavailable because of dubious ownership; do not change global Git trust.
- Live provider/RAG performance requires a separately authorized runtime evaluation with current lineage evidence.
- Supabase evidence is not required for this change. If later made decision-critical, require project-scoped read-only proof.


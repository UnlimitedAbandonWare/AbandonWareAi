# Stochastic Tool Lab Design

## Status and scope

- Date: 2026-07-31
- Request class: `prompt_skill_tooling_only`
- Design status: approved for implementation planning
- Primary metric: tool and skill quality
- Search strategy: bounded tournament first; bandit budget allocation may be added only after stable tournament evidence exists
- Mutation surface: a new isolated tooling directory, its tests, fixtures, manifests, ledgers, and review-only patches for existing tooling
- Prohibited surface: `main/**`, `app/**`, runtime source sets, databases, credentials, providers, deployment, and automatic edits to existing shared tools or skills
- Verification ownership: Notebook evidence is supporting evidence; Desktop retains final proof for any later runtime claim

## Problem

The repository already has stochastic candidate generation, deterministic scoring, Docker-isolated focused tests, source-health metrics, and three-query adjudication, but these assets are not connected into one bounded improvement loop. The user should be able to issue one command while paying little attention and receive an automatically verified `PASS`, `HOLD`, or `FAIL`, a ranked candidate list, and either an isolated automatic promotion or a review-only patch.

The system must distinguish predicted value from measured improvement. It must not let a candidate edit its fixture deck, baseline, scoring formula, evidence authority, or promotion threshold. It must also test the autograder itself with planted faults before trusting any candidate score.

## Existing assets and non-duplication boundary

The lab orchestrates rather than replaces these assets:

- `main/java/com/example/lms/artplate/sse/StochasticTransformerEvolver.java`: existing stochastic explore/exploit/reset behavior; read-only evidence for this tooling-only scope.
- `main/java/com/example/lms/ensemble/StochasticParamSampler.java`: existing stochastic parameter sampling; read-only evidence.
- `scripts/score_three_way_long_tail_query.py`: current deterministic three-packet artifact grader.
- `docs/superpowers/specs/2026-07-31-three-way-long-tail-v2-meta-autograder-design.md`: upstream anti-gaming and statistical contract. The lab consumes its accepted output when available and does not create a competing three-query scorer.
- `.agents/skills/demo1-docker-autograder/**`: focused RED/GREEN sandbox execution and bounded JUnit evidence.
- `scripts/source_health_scorecard.py` and `scripts/source_health_validation_loop.py`: existing diagnostic score and validation ledger.

The new feature owns candidate orchestration, tournament scheduling, promotion policy, self-verification, and a concise user-facing summary. It does not own application behavior or provider/runtime lineage.

## Goals

1. Convert one bounded command into three to six reversible tooling or skill candidates.
2. Derive exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` packets from the same request, EvidenceSnapshot, and CandidateSet.
3. Rank candidates before execution without confusing predicted quality with measured quality.
4. Execute at most three eligible candidates against an immutable baseline and fixture deck.
5. Verify the autograder with known-good and known-bad sentinels before grading candidates.
6. Automatically promote only artifacts contained within the isolated lab.
7. Emit review-only patches for changes to existing `scripts/**`, `tools/**`, or `.agents/skills/**`.
8. Give the user a single verdict, winner, measured uplift, proof summary, patch path, and one next action.

## Non-goals

- Autonomous application-source modification.
- Free-form shell generation or execution.
- Online production experiments, provider calls, Browser/Computer/Supabase mutation, or credential changes.
- Majority-vote judging or a fourth canonical query.
- Claiming that an artifact score proves live RAG or provider improvement.
- Automatically deleting an existing shared tool or skill.
- Building a dashboard in the MVP; canonical JSON and concise Markdown are sufficient.

## Architecture

### Candidate Registry

The registry stores an immutable manifest for each candidate:

```text
schemaVersion=awx.stochastic-tool-lab.candidate.v1
runId
candidateId
parentCandidateId
targetKind=tool|script|skill
targetPath
targetPreimageSha256
mutationKind
mutationPayloadSha256
seed
fixtureDeckSha256
scorerFormulaSha256
declaredExpectedEffect
mutationSurface=lab-only|review-patch
```

Unknown fields fail closed. `targetPath` is relative, allowlisted, non-reparse, and must not resolve beneath an active application source set. Mutation payload bytes are stored separately and bound by hash.

### Bounded Mutator

The MVP accepts only registered mutations. Each candidate changes one behavior at a time:

- bounded configuration value replacement;
- declared scoring-weight replacement where the total remains one and the formula version changes;
- prompt or skill wording patch confined to a declared block;
- allowlisted execution-strategy selection;
- test prioritization that cannot remove, skip, relabel, or replace a protected fixture.

The mutator cannot create commands, change fixture ownership, change thresholds, or edit the current shared target. It materializes candidates in a host-local temporary workspace and records preimage/postimage hashes.

### Tournament Runner

The runner applies gates in this order:

1. command and candidate schema validation;
2. path, reparse, secret, and mutation-surface validation;
3. autograder self-test;
4. immutable baseline evaluation;
5. candidate evaluation with the identical deck and scorer;
6. measured ranking;
7. isolated promotion, review-patch publication, or quarantine.

The runner executes no arbitrary command supplied by a candidate. Test adapters are registered by ID and own their exact executable and arguments.

### Score Engine

Every dimension is normalized to `[0, 100]`:

```text
ToolQualityScore =
  0.40 * outcomeQuality
+ 0.20 * robustness
+ 0.15 * repeatability
+ 0.10 * evidenceIntegrity
+ 0.10 * efficiency
+ 0.05 * reversibility
```

- `outcomeQuality`: assertion success or required skill behavior compliance.
- `robustness`: holdout, adversarial, and failure-path behavior.
- `repeatability`: seed consistency and bounded variance.
- `evidenceIntegrity`: complete hashes, counts, reason codes, and immutable inputs.
- `efficiency`: paired runtime, invocation count, and bounded output relative to baseline.
- `reversibility`: declared files, changed lines, dependencies, and rollback cost.

The score is not calculated when a hard gate fails.

### Promotion Gate

Promotion requires all of:

```text
meanScoreDelta >= 10
worstSeedScoreDelta >= 5
protectedFixtureRegressionCount = 0
hardGateCount = 0
softLimitViolationCount = 0
```

General tools and scripts use at least three seeds and twelve fixtures. Behavior-shaping skills require a no-guidance control that fails and at least five fresh-context repetitions per control and candidate variant. The skill test deck includes application, variation, missing-information, and pressure scenarios as appropriate.

Verdicts:

- `PROMOTE`: every promotion gate passes.
- `IMPROVE`: measured uplift exists but a non-safety promotion threshold is missed.
- `QUARANTINE`: regression, instability, or insufficient comparable evidence.
- `REJECT`: a hard gate or authority boundary fails.

Temporary lab candidates may be garbage-collected after their manifest-pinned retention period. An existing shared tool may only receive a deprecation review patch after three comparable losing rounds and a reference scan proving no active consumer. Existing shared files are never automatically deleted.

## Exact three-query contract

One EvidenceSnapshot is collected per run. The same serialized request, snapshot hash, and CandidateSet hash are supplied to all roles.

### POSITIVE_QUERY

Produces candidate value, validated assumptions, reusable assets, minimal verification, evidence IDs, and unknowns. It cannot approve execution or invent evidence.

### NEGATIVE_QUERY

Produces falsifiers, alternative explanations, safety and authority risks, missing evidence, and the smallest disconfirming probe. It cannot veto by preference alone.

### NEUTRAL_QUERY

Compares Positive and Negative in both A-B and B-A presentation order. It cannot add facts. It derives `APPLY`, `HOLD`, or `REJECT`, a pre-execution GoalContract score, decisive evidence, rejected claims, and one next proof. Order instability, a tie, or `goalScore < 50` yields `HOLD`.

The three roles are not voters. Deterministic gates and measured tests own the final promotion verdict.

## Two-stage ranking

Predictions and measurements remain separate.

### Pre-execution ranking

Candidates are ordered lexicographically by:

1. hard-gate pass;
2. Neutral `APPLY` verdict;
3. GoalContract score;
4. verification feasibility;
5. lower predicted cost;
6. smaller mutation surface.

At most the top three eligible candidates are executed.

### Post-execution ranking

Candidates are ordered lexicographically by:

1. hard-gate pass;
2. zero protected regression;
3. mean measured score delta;
4. worst-seed score delta;
5. repeatability;
6. lower measured cost;
7. smaller measured patch.

A tie is not broken randomly. The runner executes the smallest bounded proof that can distinguish the tied candidates; if no such proof fits the remaining budget, the result is `HOLD`.

## Self-verifying autograder

Before grading a candidate, the harness runs a sealed sentinel pack:

- one known-good canonical candidate that must pass;
- fixture deletion or skip attempt;
- fixture or baseline mutation;
- fabricated result or malformed JUnit;
- empty or zero-test report;
- timeout and unstable/flaky result;
- prohibited path write and reparse traversal;
- secret-like input or output;
- score monotonicity violation attempt;
- A-B/B-A order-instability fixture.

Every planted fault must be rejected with its registered reason code. The canonical candidate must not be falsely rejected. The same valid input is graded twice and must produce byte-identical canonical JSON. Any self-test failure yields `AUTOGRADER_INVALID`; real candidates are not scored.

## Subagent isolation

When agent-backed semantic work is required, three isolated roles are used:

- `Candidate Builder`: sees the command, public registry, and training fixtures; it cannot see hidden fixtures, baseline results, or scoring thresholds beyond the public promotion contract.
- `Falsifier`: sees the candidate manifest and evidence boundary; it creates bounded counterexamples and cannot modify the candidate or verdict.
- `Adjudicator`: sees only bounded evidence packets and hashes, evaluates both A-B and B-A orderings, and cannot execute mutations.

No agent can publish a promotion verdict directly. Agent output is untrusted input to deterministic schema, secret, authority, and replay gates.

## Execution and artifact flow

```text
command
  -> command.json
  -> evidence-snapshot.json
  -> candidates.json
  -> positive.packet.json
  -> negative.packet.json
  -> neutral.verdict.json
  -> preflight-ranking.json
  -> self-test/result.json
  -> experiments/<candidateId>/result.json
  -> final-ranking.json
  -> winner.patch or isolated promotion
  -> summary.md
```

Persistent artifacts live beneath `data/agent-handoff/stochastic-tool-lab/<runId>/`. JSON is canonical UTF-8 with sorted keys and no NaN/Infinity. Every published artifact is written atomically and becomes consumable only after its SHA-256 sidecar and ready marker are present. Consumers read once and verify the captured bytes.

Raw prompts, queries, secrets, full environment values, and unbounded logs are not persisted. Evidence records contain counts, hashes, timing, booleans, bounded labels, and redacted reason codes.

## Failure handling

Fail closed and do not score for:

- `command-schema-invalid`;
- `snapshot-incomplete` or snapshot hash mismatch;
- `candidate-schema-invalid`;
- `path-outside-allowed-surface` or reparse traversal;
- `fixture-drift`, `baseline-drift`, or scorer drift;
- `grader-self-test-failed`;
- `secret-leak-risk`;
- `zero-test-evidence` or malformed evidence;
- `order-unstable`;
- protected regression;
- undeclared mutation or attempted application-source edit.

Fail soft and continue to another eligible candidate for a single candidate timeout, resource exhaustion, ordinary test failure, or candidate-local malformed output. The failed candidate is quarantined with a bounded reason. The same unchanged blocker is not retried more than once in the same run.

## Budgets

- candidates per round: three to six;
- executed candidates per round: at most three;
- general repetitions: three seeds;
- skill repetitions: at least five per control and candidate;
- fixture minimum: twelve;
- default wall-clock budget: sixty minutes;
- absolute hard cap: 540 minutes;
- every command, output, file count, input bytes, and artifact bytes has an explicit adapter-owned limit.

The run stops immediately when a promotion winner is proven, every eligible candidate is exhausted, the budget expires, or a global hard gate fails.

## User interface

The MVP exposes one PowerShell entry point:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\stochastic-tool-lab\verify.ps1
```

It prints and writes only:

```text
verdict=PASS|HOLD|FAIL
winner=<candidateId|none>
meanScoreDelta=<number|not-evaluated>
worstSeedScoreDelta=<number|not-evaluated>
autograderSelfTest=PASS|FAIL
mutationDisposition=isolated-promoted|review-patch|quarantined|none
artifactRoot=<relative path>
primaryReason=<bounded reason code>
nextSingleAction=<one command or none>
```

The user does not need to inspect statistical internals. `summary.md` explains the winning change, paired evidence, self-test proof, disposition, and next action without requiring a manual technical judgment.

## Test strategy

### RED

1. Write contract tests before the orchestration implementation exists.
2. Prove the harness currently cannot reject every planted mutant.
3. Prove a candidate can currently attempt to own a fixture, threshold, or baseline without the new schema gate.
4. Prove no existing single command emits the required final summary contract.
5. For the repo-local skill, run fresh-context scenarios without the skill and record the baseline failures before authoring the skill.

### GREEN

1. Implement exact schemas, canonical serialization, and path gates.
2. Implement the sealed self-test pack and require 100% expected classification accuracy.
3. Implement the two-stage ranking and paired score calculation.
4. Connect registered adapters for the existing three-way grader and Docker autograder without duplicating their logic.
5. Implement isolated promotion, review-patch output, quarantine, and concise summary.
6. Author the repo-local skill only from observed baseline failures, then rerun at least five fresh-context control and candidate repetitions.

### Required properties

- identical input yields byte-identical result;
- reordering candidates or packet presentation cannot change a derived verdict;
- adding failures cannot increase a candidate score;
- removing evidence cannot increase evidence integrity;
- a candidate cannot improve by dropping cases, reducing fixture counts, or changing thresholds;
- no planted bad mutant is accepted;
- the known-good sentinel is accepted;
- application source and credentials remain unchanged.

## Acceptance criteria

The MVP is accepted only when:

```text
autograderSelfTest=PASS
knownBadFalseAcceptCount=0
knownGoodFalseRejectCount=0
deterministicReplayByteIdentical=true
canonicalQueryCount=3
orderStable=true
protectedFixtureRegressionCount=0 for PROMOTE
secretPatternHitCount=0 outside explicit synthetic fixtures
undeclaredSourceWriteCount=0
```

A successful tooling grade remains an artifact-quality claim. `runtimeLineageVerdict` stays `HOLD` unless Desktop separately proves the active runtime path with request-specific lineage evidence.

## Rollback

Remove the new isolated lab directory, its new focused tests, the new repo-local skill directory, and generated lab handoff artifacts. Review-only patches are not applied automatically and need no source rollback. No application source, database, credential, provider, or deployment rollback is required.


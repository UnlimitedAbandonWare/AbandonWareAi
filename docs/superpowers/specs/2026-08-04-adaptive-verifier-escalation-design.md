# Adaptive Verifier Escalation Design

Date: 2026-08-04

Status: approach A approved in chat; implementation pending written-spec review

Scope: repo-local skill, deterministic offline route evaluator, and pressure tests only

## Problem

The repository already has a strong bounded three-perspective review pack and a
counter-evidence family. Those assets are useful when the task itself needs
support, falsification, and a terminal coherence verdict. They should not become
an always-on topology for every worker request.

The missing seam appears earlier: a lightweight worker can stall, repeat a tool
failure, make no evidence progress, or remain confidently wrong. The current
skill family has no general contract that detects that route failure, runs one
small disconfirming probe, conditionally dispatches two genuinely different
verifiers, and measures whether the detector improved precision and recall at an
acceptable cost.

Adding two strong agents unconditionally would increase cost without proving
independence. Majority voting would also over-count correlated errors. A weak
final judge should therefore receive checkable packets and own only constrained
selection or abstention, not another unrestricted solution attempt.

## Decision

Add a thin repo-local `$demo1-adaptive-verifier-escalation` coordinator and one
deterministic offline evaluator. Keep the existing triadic and counter-evidence
skills unchanged and call them only when their existing trigger applies.

The coordinator is model-agnostic. It uses capability roles rather than model
names:

- `worker`: the initially assigned, usually lower-cost executor;
- `blind_solver`: an independent solver that does not see the worker conclusion;
- `falsifier`: a verifier that tests the exact failure hypothesis using a
  different evidence or tool lineage where available;
- `aggregator`: a constrained selector that compares structured claims, evidence
  references, and tests, and may abstain.

Current model names may be recorded as run metadata after official verification,
but they never define skill behavior or evidence weight.

## Existing Asset Ownership

| Concern | Existing owner | New skill behavior |
| --- | --- | --- |
| Three-perspective artifact review | `demo1-agentic-chat-postprocess` | Reuse only when that review mode is requested; do not add a fourth role |
| Falsifiable alternative worlds | `demo1-generating-falsifiable-hypotheses` | Reuse by packet reference |
| Three bounded counter-evidence queries | `demo1-retrieving-counter-evidence` | Reuse without changing query count |
| Conditional evidence routing | `demo1-triangulating-counter-evidence` | Reuse after an escalation route is selected |
| Terminal evidence coherence | `demo1-verifying-evidence-coherence` | Remains the evidence-verdict owner |
| Time-bounded minority forecasts | `demo1-forecasting-minority-signals` | Reuse only for an observation-window problem |
| Paired FP/FN ledger conventions | `demo1-memory-integrity-autopatch` | Reuse denominator and reason-code conventions |
| Worker failure-route detection and calibration | new coordinator | Sole new ownership; route decision only |

The new skill must not duplicate prompt assembly, source-patch authority,
runtime routing code, TraceStore, or the three-query counter-evidence protocol.

## Trigger and Non-Trigger

The skill has two explicit modes. `ROUTE` consumes one bounded worker run and may
produce a route-only packet. `EVALUATE` consumes sealed labeled fixtures and runs
the offline evaluator without dispatching an agent or tool. Metric-only requests
use `EVALUATE` and do not pretend that a live worker run exists.

Trigger `ROUTE` when a bounded worker run supplies evidence of at least one of
these conditions:

- repeated state or failure-class hashes without new evidence;
- repeated tool or schema failure under an unchanged input;
- exhausted or sharply declining budget with no measurable progress;
- a semantic contradiction between the worker claim and an authoritative local
  artifact;
- an external monitor supplies a calibrated `escalation_needed` signal with
  provenance and `decisionAuthority=route_probe_only`.

Trigger `EVALUATE` when the user asks to measure failure-route precision, recall,
false positives, false negatives, coverage, or cost from labeled artifacts.

Do not trigger for ordinary successful execution, a simple request for three-way
review, an ungrounded feeling that the worker is weak, a raw verbal confidence
score, or a request to mutate application source. A source patch remains behind
the repository's separate source-owner and three-way preflight gates.

`workflowOwner` has precedence over generic failure triggers. Active
`demo1-agentic-chat-postprocess`, source-edit preflight, and literal Notebook
triads retain their exact role count and fail-closed behavior. The coordinator
returns `SKIP_OWNER_CONTROLLED` and cannot replace a role, append a fourth role,
or reinterpret the owner packet. A future-window monitor signal routes to
`demo1-forecasting-minority-signals`; it cannot directly escalate the current run.

## Architecture

```text
worker + workflowOwner
  -> bounded health envelope
  -> operational stall monitor + semantic risk detector
       -> CONTINUE when no supported trigger exists
       -> PROBE when one smallest disconfirming observation can decide the route
       -> DISPATCH_PAIR only after the probe confirms or cannot safely clear risk
            -> blind_solver || falsifier
            -> constrained aggregator
                 -> SELECT_WORKER_CANDIDATE
                 -> SELECT_CORRECTION_CANDIDATE
                 -> HOLD
                 -> HANDOFF_STRONG_JUDGE
```

### 1. Freeze a Worker Health Envelope

Capture only bounded, redacted fields:

- task and run IDs or hashes;
- `mode=ROUTE`, lifecycle stage, and `workflowOwner`;
- worker capability tier, without treating the tier as evidence;
- state hash, attempt count, novel-evidence count, and budget-use ratio;
- normalized tool outcome, schema-valid boolean, and failure-class reason code;
- claim packet reference and evidence references;
- redaction result and detector-policy version.

Do not include raw prompts, queries, secrets, headers, full environment values,
or unbounded reasoning traces.

### 2. Separate Operational and Semantic Detection

The operational monitor uses checkable run facts such as repeated state hashes,
unchanged reason codes, malformed packets, and missing evidence progress. The
semantic detector tests claim-to-evidence conflict. It must not infer a semantic
failure solely from low self-confidence, high entropy, verbosity, or worker tier.

Thresholds come from a named detector-policy version or a sealed evaluation
fixture. The skill must not invent production thresholds. If an automated route
depends on a missing threshold or calibration artifact, return `HOLD` with
`calibration-policy-missing`.

### 3. Run One Smallest Disconfirming Probe

Before dispatching stronger agents, select one probe that can change the route.
Coordinator-owned probes are deterministic local or operational checks only:

- re-read one already identified authoritative local artifact;
- validate one schema or deterministic invariant;
- retry one idempotent read-only tool call when the failure class permits it;
- compare one state or evidence hash.

Any semantic retrieval delegates to `demo1-retrieving-counter-evidence` and must
consume, reuse, or revise one of its existing three slots. The coordinator cannot
create a fourth query, normalize external evidence, or own a query trace.

The probe has `maxAttempts=1`, a positive time or token budget, and
`mutationAllowed=false`. An inconclusive probe does not become supporting
evidence; it only permits `DISPATCH_PAIR` or `HOLD`.

### 4. Dispatch Two Orthogonal Verifiers

`blind_solver` receives the task, constraints, and admissible evidence, but not
the worker conclusion or rationale. `falsifier` receives
`falsifierTarget=claim|operational_failure`, the suspected failure class, and the
smallest falsifying test. `workerClaimRef` is required for a claim target and
must be null for a no-claim operational failure. The falsifier should use a
different existing evidence source, tool path, or decomposition when available.

The pair is not independent merely because it has different role labels.
Record provenance groups and set `independenceProven=false` when the agents share
the same model snapshot, prompt lineage, retrieval result, or tool output without
an independent check. Correlated packets count as one evidence lineage.

### 5. Constrain the Aggregator

The aggregator consumes atomic claims, evidence IDs, test outcomes, provenance
groups, and explicit limitations. It must not see hidden chain-of-thought, average
unlike confidence types, count agents as votes, or create new evidence.

`SELECT_CORRECTION_CANDIDATE` requires a checkable falsifier result and an
independently supported alternative. It remains `SUGGESTION_ONLY`; rejecting the
worker claim never confirms the alternative. Only the existing coherence verifier
may approve the exact evidence claim, referenced through an optional
`coherenceVerdictRef`. `SELECT_WORKER_CANDIDATE` requires the suspected failure to
be disconfirmed. Conflicts, missing evidence, correlated-only support, malformed
packets, or order-sensitive decisions return `HOLD`.

`HANDOFF_STRONG_JUDGE` is a handoff, not an automatic dispatch or success. It is
allowed only when `impactClass=high`, `strongJudgeAuthorized=true`, and a separate
positive call budget are present. Otherwise the result is `HOLD`. The automatic
budget covers at most one probe, two verifier calls, and one aggregator call; a
deterministic aggregator consumes zero model calls. The packet records each count.

Run the aggregator with verifier packet order swapped. A changed decision sets
`orderStable=false` and forces `HOLD`.

## Packet Contract

The coordinator emits `demo1.adaptive-verifier-escalation.v1` with these core
fields:

```text
packetType
taskId
mode=ROUTE|EVALUATE
workflowOwner
lifecycleStage=OBSERVE|PROBE|VERIFY|AGGREGATE|TERMINAL
detectorPolicyVersion
positiveClassDefinition=escalation_needed
operationalSignals[]
semanticSignals[]
currentAction=CONTINUE|PROBE|DISPATCH_PAIR|HOLD|SKIP_OWNER_CONTROLLED
postProbeAction=CONTINUE|DISPATCH_PAIR|HOLD|SKIP_OWNER_CONTROLLED
terminalRoute=CONTINUE|PAIR_COMPLETED|HOLD|HANDOFF_STRONG_JUDGE|SKIP_OWNER_CONTROLLED
triggerCodes[]
minimalProbe
falsifierTarget=claim|operational_failure
workerClaimRef
blindSolverPacketRef
falsifierPacketRef
provenanceGroups[]
independenceProven
aggregatorDecision=SELECT_WORKER_CANDIDATE|SELECT_CORRECTION_CANDIDATE|HOLD|HANDOFF_STRONG_JUDGE
decisionAuthority=route_only
coherenceVerdictRef
probeCallCount
verifierCallCount
aggregatorCallCount
orderStable
routeGatePassed
evidenceNeeded[]
compactReportLine
```

Packet references are null until their lifecycle stage runs. `workerClaimRef` is
also null for an operational failure with no claim. `routeGatePassed=true` is
allowed only after required packets, evidence references, redaction, independence
handling, call-budget accounting, and order-swap checks pass. It is not the
coherence verifier's `verificationGatePassed` and cannot assert factual truth.

## Precision, Recall, and Cost Contract

The evaluator requires a strict manifest before reading rows:

```text
schemaVersion=demo1.adaptive-verifier-evaluation.v1
evaluationMode=ROUTE_EVAL|PAIRED_EVAL
fixtureId
fixtureSha256
evaluationSplit=tuning|calibration|locked_evaluation|production_shadow
detectorPolicyVersion
labelPolicyVersion
positiveClassDefinition=expectedPostProbeAction==DISPATCH_PAIR
allowedFailureClasses[]
allowedPostProbeActions[]
costPolicyVersion|null
costPolicyRef|null
calibrationPolicyVersion|null
calibrationFitRef|null
calibrationFitFixtureSha256|null
foldAssignmentSha256|null
thresholdPolicyVersion|null
thresholdSelectionRef|null
thresholdSelectionFixtureSha256|null
```

Expected labels belong to the sealed fixture and cannot be generated by the
system under evaluation. Mixed fixture hashes, policy versions, or split labels
fail closed. Every numeric input must be finite and bounded by its schema.

### Route-evaluation rows

`ROUTE_EVAL` consumes:

```text
scenarioId
expectedFailureClass
observedFailureClass
expectedPostProbeAction=CONTINUE|DISPATCH_PAIR|HOLD|SKIP_OWNER_CONTROLLED
observedPostProbeAction=CONTINUE|DISPATCH_PAIR|HOLD|SKIP_OWNER_CONTROLLED
labelProvenance
```

`PROBE` is an intermediate state and is invalid in either post-probe field. For
binary dispatch metrics, truth is positive only when
`expectedPostProbeAction=DISPATCH_PAIR`; truth is negative only when it is
`CONTINUE`. A predicted `DISPATCH_PAIR` is positive and `CONTINUE` is negative.
`HOLD` is explicitly tagged as abstention. It counts as no dispatch in
`binaryDispatchMetrics` and is also broken out in `abstentionMetrics`; it is never
silently hidden. Expected `HOLD` and `SKIP_OWNER_CONTROLLED` rows remain in route
confusion but are excluded from binary truth denominators. When an eligible
expected `CONTINUE` or `DISPATCH_PAIR` row unexpectedly observes
`SKIP_OWNER_CONTROLLED`, it is an `unexpected-owner-skip` abstention and explicit
no-dispatch: it contributes FN for expected dispatch or TN for expected continue,
and increments a separate protocol-error count.

Optional scored fields are `detectorScore`, `calibrationVersion`, `latencyMs`,
`foldId`, `probeCallCount`, `verifierCallCount`, `aggregatorCallCount`, and
`observedCostUnits`. Production-shadow rows additionally require
`evaluationPopulation`, `wasTriggered`, `auditStratum`,
`selectionProbability`, and label provenance from the independent audit. The
only permitted sampling weight is `1/selectionProbability`. Arbitrary
`sampleWeight` values are rejected.

The evaluator emits separate sections:

- `binaryDispatchMetrics`: TP, FP, FN, TN, precision, recall, F1, FPR, support,
  and prevalence under the explicit mapping above;
- `abstentionMetrics`: coverage, abstention rate, positive/negative abstention
  counts and rates, and selective risk where accepted-result labels exist;
- `routeConfusion`: every expected/observed post-probe route pair;
- `failureClassConfusion`: multiclass accuracy and per-class one-vs-rest
  precision, recall, F1, and support.

Failure-class macro metrics exclude undefined zero-support classes and report the
excluded count. Weighted metrics use expected-label support. Micro precision, recall,
and F1 are permitted; multiclass TN and FPR are not emitted. A metric with a zero
or unknown denominator is `null` with a stable reason code rather than zero or
PASS.

Sealed fixtures emit unweighted fixture metrics. Production-shadow estimates
emit a separate inverse-probability-weighted section and preserve the unweighted
audit counts beside it. The evaluator never merges the two or labels fixture
support weighting as a production estimate.

The ranking metric is average precision, not trapezoidal PR-AUC. Scores use
`higherMeansEscalation=true`; ties are evaluated as one threshold group. Average
precision requires complete score coverage and reports prevalence and support;
otherwise it is `null` with `incomplete-score-coverage`.

Calibration requires every score to be a probability in `[0,1]`, one calibration
version, and a held-out or out-of-fold split distinct from fitting. It reports
Brier score and fixed ten-bin equal-width ECE. Mixed versions, non-probability
scores, or same-set fit/evaluation return `null` with a stable reason code. A
held-out run requires a hash-bound `calibrationFitRef` and a fit-fixture SHA-256
different from the evaluation fixture. An out-of-fold run additionally requires
row-level `foldId`, a hash-bound fold assignment, and fit references proving each
row's fold was excluded from its calibration fit.

Expected cost requires a run-level `costPolicy` with version, unit, FP and FN
penalties, per-probe, per-verifier, per-aggregator and HOLD costs, and optional
latency cost per millisecond. It is computed from route outcomes and invocation
counts. The manifest supplies that policy inline or through a hash-bound
`costPolicyRef`, and its internal version must equal `costPolicyVersion`.
Alternatively, rows may supply an already measured `observedCostUnits`; the
evaluator must not combine both paths and double-count. Without a cost policy,
report observed mean cost and latency only and do not report expected cost.

Recall requires labeled failures among both triggered and non-triggered cases.
Production evaluation must therefore include a random or risk-stratified shadow
audit of non-triggered cases with known inclusion probabilities or stratum
population weights. Without that sampling design, report
`productionRecallStatus=unobservable`. Sealed-fixture recall remains separately
named and must not be presented as a production estimate.

## Evaluation Design

`PAIRED_EVAL` uses a separate row schema with a unique
`(scenarioId, armId, replicateId)` key:

```text
scenarioId
armId=A0|A1|A2|A3
replicateId
taskClass
riskClass
labelProvenance
workerArtifactHash
admissibleEvidenceHash
generationConditionHash
workerCorrect
acceptedCorrect
blindSolverCorrect
falsifierDecisionCorrect
aggregatorDecision
orderStable
probeCallCount
verifierCallCount
aggregatorCallCount
latencyMs
observedCostUnits
```

Fields that do not apply to an arm are null under explicit arm-specific rules,
not omitted. Boolean correctness labels require a sealed answer or decision
rubric. `acceptedCorrect` is null for an abstention rather than false, and
`workerArtifactHash`, `admissibleEvidenceHash`, `generationConditionHash`, and
`workerCorrect` must remain invariant across arms for the same scenario and
replicate. The evaluator reports accepted-result risk, coverage, worker degradation,
unique recovery, pair joint-failure rate, optional error correlation when both
verifier outcomes have variance, invocation counts, latency, and observed cost.

Use the same sealed scenarios and admissible evidence for four paired arms:

1. `A0`: worker only;
2. `A1`: always-on existing three-perspective review;
3. `A2`: always-on orthogonal strong pair;
4. `A3`: detector, one probe, then conditional strong pair.

Measure detector precision/recall/FPR, accepted-result risk, coverage, token or
cost units, latency, and abstention. Measure verifier-pair unique recovery, joint
failure, and error correlation only when the labels support it. Measure
aggregator order stability and degradation: cases where a correct worker result
becomes an accepted wrong result after aggregation.

Threshold selection occurs only on the declared tuning/calibration split using
predeclared FP/FN and invocation costs subject to a task-class risk floor. The
manifest binds the threshold policy, selection artifact, and tuning/calibration
fixture SHA-256. A locked evaluation rejects a missing selection reference or a
selection fixture hash equal to its own fixture hash, making same-set leakage a
schema failure. The locked evaluation split is never used to choose the threshold. Safety-critical
fixtures may require a recall floor; routine fixtures may require a precision
floor. Do not optimize a single global F1 across unequal risk classes.

Version 1 reports paired deltas descriptively. It must not emit `improved=true`
or a statistically significant claim without a predeclared margin and a paired,
scenario-clustered uncertainty procedure with a fixed seed. Missing uncertainty
evidence is reported explicitly rather than inferred from point estimates.

Recalibrate when the worker model, verifier model, prompt contract, tool set,
domain, or evidence policy changes.

## File Map

Implementation is limited to:

- `.agents/skills/demo1-adaptive-verifier-escalation/SKILL.md`;
- `.agents/skills/demo1-adaptive-verifier-escalation/agents/openai.yaml`;
- `.agents/skills/demo1-adaptive-verifier-escalation/references/evaluation-contract.md`;
- `.agents/skills/demo1-adaptive-verifier-escalation/scripts/evaluate_failure_routes.py`;
- `scripts/test_demo1_adaptive_verifier_escalation.py`;
- this design and the subsequent implementation plan.

Use the system `skill-creator` initializer for the new skill. Do not create a
README, changelog, installation guide, runtime service, prompt pack, or duplicate
validator. Keep `SKILL.md` concise and place evaluator schemas and metric details
in the single reference file.

## RED, GREEN, and Refactor Sequence

RED pressure tests must run before skill implementation and preserve their
failure transcripts:

1. a repeated-state operational stall;
2. a progressing but verbally uncertain worker that must not escalate;
3. a confidently wrong semantic claim contradicted by an authoritative artifact;
4. a temporary tool outage that must not be mislabeled as semantic failure;
5. two apparently different verifiers sharing one provenance lineage;
6. a correct worker opposed by correlated wrong verifiers;
7. conflicting verifier packets that require aggregator abstention;
8. an unlabeled or trigger-only dataset that cannot support recall;
9. a non-triggered shadow sample that exposes a false negative;
10. a packet containing a secret-like value that must fail redaction;
11. an owner-controlled triad that must return `SKIP_OWNER_CONTROLLED` without a
    fourth role;
12. an operational failure with a null worker claim and an operational falsifier
    target;
13. a mixed-policy or invalid production-shadow manifest that must fail closed.

After the baseline failures are recorded:

1. initialize the skill and add the smallest workflow contract;
2. implement the evaluator against failing deterministic tests;
3. rerun the same pressure scenarios with the skill available;
4. refactor only duplication proven by the tests;
5. run `quick_validate.py`, the focused test, existing three-way autograders,
   counter-evidence validation, and the repo skill-family postprocessor;
6. run count-only secret scanning and `git diff --check`;
7. perform an independent forward test using raw task artifacts without leaking
   the expected answer or design rationale.

## Failure and Safety Contract

- Missing labels, thresholds, costs, evidence IDs, or calibration versions produce
  `evidence_needed`; they never become favorable defaults.
- Tool unavailability is distinct from semantic failure.
- Timeout and cancellation remain timeout and cancellation.
- Randomness is an optional seeded exploration probe, never proof and never a
  knowledge-gap repair.
- Automatic fan-out is bounded to one probe, two verifier calls, and one
  aggregator call. A strong judge is a separately authorized handoff and is not
  called by this skill.
- Raw queries, prompts, credentials, headers, cookies, and environment dumps are
  prohibited from packets, fixtures, logs, and evaluator output.
- The skill cannot authorize application-source, DB, API, provider, credential,
  or environment mutation.
- An index lock, target overlap, changed preimage, secret hit, or malformed output
  stops implementation or returns `HOLD` as appropriate.

## Acceptance Criteria

- The new skill triggers on supported failure-route evidence and stays inactive
  for ordinary success and generic three-way review.
- Operational stall and semantic failure remain separate classifications.
- Exactly one bounded disconfirming probe precedes the strong pair.
- The two verifier roles have distinct information contracts and provenance is
  measured rather than assumed.
- The aggregator can select a worker or correction candidate, abstain, or hand
  off, but cannot confirm factual truth, majority-vote, or create evidence.
- Order-sensitive aggregation fails closed.
- The evaluator produces separate binary-dispatch, abstention, route, and
  failure-class metrics and refuses unobservable recall, average precision,
  calibration, or expected-cost claims.
- Paired A0-A3 evaluation reports quality, risk, cost, and latency rather than one
  headline accuracy number.
- Existing three-way and counter-evidence tests remain green without edits to
  those skills.
- Quick validation, focused tests, family validation, forward testing, diff
  checks, and count-only secret scans pass before completion is claimed.

## Rollback

Remove only the new skill directory, focused test, design, and implementation
plan after confirming they are uncommitted and have no unrelated overlap. No
application source, runtime configuration, database schema, provider setting,
environment variable, or PatchDrop artifact is created, so rollback has no
runtime migration step.

## Research Basis and Limits

[AutoMix](https://arxiv.org/abs/2310.12963) and
[RouteLLM](https://arxiv.org/abs/2406.18665) support conditional strong/weak
routing. [Correlated Errors in Large Language
Models](https://arxiv.org/abs/2506.07962) and the recent
[Minority Sentinel](https://arxiv.org/abs/2606.29270) warn against treating agent
count as independent evidence. [Prover-Verifier
Games](https://openai.com/index/prover-verifier-games-improve-legibility/) and a
[weak-judge scalable-oversight study](https://arxiv.org/abs/2407.04622) support
making strong answers legible to a weaker checker, but their results are
task-dependent. These sources justify the evaluation design, not a prior
assumption that A3 will win. The sealed paired experiment remains the deciding
evidence.

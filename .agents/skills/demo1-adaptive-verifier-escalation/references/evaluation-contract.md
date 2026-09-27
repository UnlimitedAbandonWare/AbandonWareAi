# Evaluation Contract

Use this reference for sealed offline `EVALUATE` runs. Treat all fixtures,
labels, policies, hashes, and references as input evidence; never generate labels
or thresholds from the system under evaluation. Reject non-finite numbers and
schema-invalid values with a stable reason code. Do not emit prompts, queries,
credentials, headers, cookies, environment dumps, or raw sensitive evidence.

## Manifest

Require these fields before reading rows:

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

Fail closed for mixed fixture hashes, detector/label/policy versions, split labels,
or invalid bounds. Locked evaluation requires a threshold-selection reference whose
fixture hash differs from its own. Calibration requires its fit reference and a
different fit-fixture hash, or row-level folds plus a hash-bound fold assignment
proving each row was excluded from its fit. Recalibrate when model, prompt, tool,
domain, or evidence policy changes.

## Numeric types and bounds

Require every numeric value to be a JSON number, not a boolean. Reject
`bool-as-number`, `NaN`, `infinity`, negative values where the lower bound is
zero, and `numeric-out-of-range`; do not coerce strings. Number means finite
IEEE-754 data after parsing. These maxima keep a single route within the one-probe,
two-verifier, one-aggregator cap and the 540-minute hard cap.

| Field | Type | Bound |
| --- | --- | --- |
| `detectorScore` | number | `0 <= value <= 1` |
| `selectionProbability` | number | `0 < value <= 1` |
| `probeCallCount` | integer | `0 <= value <= 1` |
| `verifierCallCount` | integer | `0 <= value <= 2` |
| `aggregatorCallCount` | integer | `0 <= value <= 1` |
| `latencyMs` | number | `0 <= value <= 32400000` |
| `observedCostUnits` | number | `0 <= value <= 1000000000` |
| `evaluationPopulation` | integer | `1 <= value <= 1000000000` |
| `fpPenalty`, `fnPenalty`, `probeUnitCost`, `verifierUnitCost`, `aggregatorUnitCost`, `holdPenalty` | number | `0 <= value <= 1000000000` |
| `latencyUnitCostPerMs` | number | `0 <= value <= 1000000` |

`replicateId` and `foldId` may be strings; a numeric identifier is an integer with `0 <= value <= 1000000`.
Reject an integer with a fractional component, any cost-policy number that violates
its row, and a latency beyond `32400000` ms. A zero call count is valid only when
the arm applicability contract below permits it.

## ROUTE_EVAL

Each row requires:

```text
scenarioId
expectedFailureClass
observedFailureClass
expectedPostProbeAction=CONTINUE|DISPATCH_PAIR|HOLD|SKIP_OWNER_CONTROLLED
observedPostProbeAction=CONTINUE|DISPATCH_PAIR|HOLD|SKIP_OWNER_CONTROLLED
labelProvenance
```

Reject `PROBE` in a post-probe field. Optional finite fields are `detectorScore`,
`calibrationVersion`, `latencyMs`, `foldId`, `probeCallCount`,
`verifierCallCount`, `aggregatorCallCount`, and `observedCostUnits`. A
`production_shadow` row also requires `evaluationPopulation`, `wasTriggered`,
`auditStratum`, `selectionProbability`, and independent-audit label provenance.
Allow only sampling weight `1/selectionProbability`; reject arbitrary
`sampleWeight` and probabilities outside `(0,1]`.

### Route mapping and formulas

For binary dispatch metrics, eligible truth is `DISPATCH_PAIR` (positive) or
`CONTINUE` (negative); expected `HOLD` and `SKIP_OWNER_CONTROLLED` remain only in
route confusion. Predict dispatch only for observed `DISPATCH_PAIR`; observed
`CONTINUE`, `HOLD`, or `SKIP_OWNER_CONTROLLED` is no dispatch. Thus:

```text
TP = expected DISPATCH_PAIR and observed DISPATCH_PAIR
FN = expected DISPATCH_PAIR and observed != DISPATCH_PAIR
FP = expected CONTINUE and observed DISPATCH_PAIR
TN = expected CONTINUE and observed != DISPATCH_PAIR
precision = TP / (TP + FP)
recall = TP / (TP + FN)
F1 = 2*TP / (2*TP + FP + FN)
FPR = FP / (FP + TN)
prevalence = (TP + FN) / (TP + FN + FP + TN)
```

`HOLD` is an abstention, explicit no-dispatch, and never hidden. An unexpected
owner skip on an eligible row is also an abstention/no-dispatch, contributes FN or
TN by the mapping, and increments `unexpected-owner-skip`. Report coverage as
non-abstentions / eligible rows, abstention rate as abstentions / eligible rows,
and positive/negative abstention counts and rates. Report selective risk only
when accepted-result labels exist. A zero or unknown denominator is `null` with a
stable reason code, never zero or PASS.

Emit separate `binaryDispatchMetrics`, `abstentionMetrics`, `routeConfusion`, and
`failureClassConfusion`. Route confusion records every expected/observed pair.
For failure classes, report accuracy and one-vs-rest precision, recall, F1, and
support; macro excludes undefined zero-support classes and records the excluded
count, weighted uses expected-label support, and micro precision/recall/F1 is
permitted. Do not emit multiclass TN or FPR.

### Scores, calibration, and production recall

Average precision uses `higherMeansEscalation=true`, complete score coverage for
eligible rows, and descending tied-score threshold groups:

```text
AP = sum_over_groups(precision_at_group * recall_increment_at_group)
```

Return `null` with `incomplete-score-coverage` if any eligible score is absent.
Do not use trapezoidal PR-AUC. Report AP support and prevalence.

Calibration accepts probabilities only in `[0,1]`, one calibration version, and a
held-out or out-of-fold evaluation distinct from fitting:

```text
Brier = mean((detectorScore - binaryTruth)^2)
ECE10 = sum_over_equal_width_bins((binCount / N) * abs(meanScore - meanTruth))
```

Use ten equal-width bins. Return `null` for mixed versions, non-probability scores,
same-set fit/evaluation, or missing fit/fold evidence.

Sealed fixtures emit unweighted fixture metrics. Production-shadow output keeps
unweighted audit counts separate and emits a distinct inverse-probability-weighted
estimate. Production recall needs labeled triggered and non-triggered cases from
a random or risk-stratified shadow audit with inclusion probabilities or stratum
population weights; otherwise set `productionRecallStatus=unobservable`.

### Cost policy

Use exactly one path: a run-level `costPolicy` (inline or hash-bound
`costPolicyRef`) whose internal version equals `costPolicyVersion`, or measured
row `observedCostUnits`. Never combine them. A cost policy supplies unit, FP/FN
penalties, per-probe, per-verifier, per-aggregator, per-HOLD costs, and optional
latency cost per millisecond. Compute expected cost from the route outcome and
call counts:

```text
FP*fpPenalty + FN*fnPenalty + probes*probeUnitCost + verifiers*verifierUnitCost
+ aggregators*aggregatorUnitCost + holds*holdPenalty + latencyMs*latencyUnitCostPerMs
```

Without a policy, report observed mean cost and latency only; do not report
expected cost.

## PAIRED_EVAL

Require a unique `(scenarioId, armId, replicateId)` and these fields:

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

Use null, not omission, for arm-inapplicable fields. Require sealed answer or
decision rubrics for booleans. `acceptedCorrect=null` for abstention; require
`workerArtifactHash`, `admissibleEvidenceHash`, `generationConditionHash`, and
`workerCorrect` to be invariant across every arm of each scenario/replicate.

## Arm applicability and null contract

The call-count columns are exact per row, not merely upper bounds. `required`
means a schema-valid, non-null field; `null` means explicitly null, never omitted.

| Arm | blindSolverCorrect | falsifierDecisionCorrect | aggregatorDecision | orderStable | probeCallCount | verifierCallCount | aggregatorCallCount |
| --- | --- | --- | --- | --- | ---: | ---: | ---: |
| A0 | `null` | `null` | `null` | `null` | `0` | `0` | `0` |
| A1 | `null` | `null` | required | `null` | `0` | `2` | `1` |
| A2 | required | required | required | required | `0` | `2` | `1` |
| A3 / cleared | `null` | `null` | `null` | `null` | `1` | `0` | `0` |
| A3 / dispatched | required | required | required | required | `1` | `2` | `1` |

`A0` has no probe, pair, or aggregator fields. `A1` maps its existing two evidence
roles plus neutral decision without pretending to be the new pair:
`blindSolverCorrect`, `falsifierDecisionCorrect`, and `orderStable` are `null`,
`aggregatorDecision` is required, and no order swap is required. `A2` always runs
the orthogonal pair without a probe and requires both pair results,
`aggregatorDecision`, and `orderStable`. `A3` first runs its bounded probe; a
cleared route has no pair or aggregation/order fields or calls, while a dispatched
route has the complete two-verifier pair plus `aggregatorDecision` and `orderStable`.

### Decision domains and coupling

`aggregatorDecision` is an enum, never arbitrary text. `orderStable` is a JSON
boolean (`true` or `false`), never a string or number, only where the table permits
it.

| Arm | permitted `aggregatorDecision` | required `orderStable` |
| --- | --- | --- |
| A0 | `null` | `null` |
| A1 | `APPLY|HOLD|REJECT` | `null` |
| A2 | `SELECT_WORKER_CANDIDATE|SELECT_CORRECTION_CANDIDATE|HOLD|HANDOFF_STRONG_JUDGE` | JSON boolean |
| A3 / cleared | `null` | `null` |
| A3 / dispatched | `SELECT_WORKER_CANDIDATE|SELECT_CORRECTION_CANDIDATE|HOLD|HANDOFF_STRONG_JUDGE` | JSON boolean |

For A2 and dispatched A3, `orderStable=false` requires `aggregatorDecision=HOLD` and `acceptedCorrect=null`.
`aggregatorDecision=HOLD` or `aggregatorDecision=HANDOFF_STRONG_JUDGE` requires `acceptedCorrect=null`.
A1 `HOLD` also requires `acceptedCorrect=null`. A candidate-selection decision requires `orderStable=true` and remains `decisionAuthority=route_only`; it does not prove factual truth.

Reject `invalid-aggregator-decision`, `non-boolean-order-stable`,
`order-stability-hold-required`, `abstention-accepted-coupling`,
`candidate-order-stability-required`, and `route-only-not-truth` for the matching
domain, type, coupling, or overclaim violation.

Reject a `null-vs-required mismatch`, an omitted inapplicable field, a call-count
mismatch, pair evidence on `A0`/cleared `A3`, missing A2/A3-dispatched pair
evidence, an A1 order-swap claim, an A2 probe, or an A3 pair before its probe.
Across arms, retain the invariant worker artifact, admissible evidence,
generation-condition, and worker-correctness values for each scenario/replicate.

Compare the same sealed scenarios and admissible evidence across `A0` worker only,
`A1` always-on existing three-perspective review, `A2` always-on orthogonal strong
pair, and `A3` detector -> one probe -> conditional strong pair. Report accepted
result risk, coverage, worker degradation, unique recovery, pair joint-failure,
error correlation only when both verifier labels vary, invocation counts, latency,
and observed cost. Report detector precision/recall/FPR, abstention, and
aggregator order stability; an order change fails closed.

Use predeclared FP/FN and invocation costs plus task-class risk floors to select a
threshold only on tuning/calibration. Do not optimize a global F1 across unequal
risk classes. Version 1 reports paired deltas descriptively: do not emit
`improved=true` or significance without a predeclared margin, fixed seed, and
paired scenario-clustered uncertainty procedure.

## Reason codes and rollback

Use `evidence_needed` for missing labels, thresholds, costs, evidence IDs, or
calibration versions; use `calibration-policy-missing`,
`incomplete-score-coverage`, `unexpected-owner-skip`, `mixed-policy-version`,
`schema-invalid`, `same-set-leakage`, `invalid-selection-probability`,
`cost-path-conflict`, and `productionRecallStatus=unobservable` where applicable.
Treat tool unavailability, timeout, and cancellation as their own classes, never
semantic success or failure. On validation failure, emit no favorable metric or
route decision; preserve the sealed input unchanged and return `HOLD` or the
stable reason code. Roll back a skill-only change by removing only its uncommitted
skill directory and focused test after confirming no unrelated overlap.

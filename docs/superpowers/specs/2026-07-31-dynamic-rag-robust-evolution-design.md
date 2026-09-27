# Dynamic RAG Robust Evolution Design

- Date: 2026-07-31
- Status: approved design; implementation not authorized
- Node: Notebook
- Canonical workspace: `Y:\`
- Application source mutation: forbidden in this design-document turn
- Desktop final proof: `evidence_needed`

## 1. Decision Summary

Dynamic RAG strategy selection will optimize contradiction and sparse-signal
robustness. The selected design is **CVaR-gated Pareto Shadow Evolution**.

The design does not add a new retrieval mode, a second CVaR implementation, or
a parallel orchestration framework. It reuses the current execution-plan seam:

```text
request evidence
  -> ExecutionPlanApplier derives an immutable snapshot
  -> existing modes become candidates
  -> hard-gate filtering
  -> counterfactual shadow profile lookup
  -> per-axis normalization
  -> Pareto survivor filtering
  -> CvarAggregator.lowerTailMean selection
  -> one existing ExecutionPlan is proposed or applied
  -> existing PromptBuilder boundary remains unchanged
```

The initial operating state is `SHADOW`. Shadow evaluation performs no extra
provider calls and cannot change the executed plan. `ENFORCE` is permitted only
after Desktop proves the benchmark, profile, budget, and rollback gates.

## 2. Problem Statement

The current resolver maps four boolean signals to one primary mode using a
fixed priority:

```text
EXTREMEZ > HYPERNOVA > OVERDRIVE > NORMAL
```

The live signals are `lowRecall`, `lowAuthority`, `contradiction`, and
`highRiskTail`. A fixed priority safely prevents multiple boosters from being
active, but it cannot prove that the selected mode has the strongest worst-case
behavior for a particular contradiction/sparsity bucket.

The desired behavior is to preserve single-mode safety while replacing an
unconditional priority decision with an evidence-backed proposal whose weakest
quality dimension is stronger than the baseline.

## 3. Goals

- Reduce worst-tail failure on contradictory and sparse retrieval fixtures.
- Keep exactly one primary booster active per request.
- Reuse existing `ExecutionPlan`, conflict resolution, CVaR, time-budget, and
  trace boundaries.
- Keep online execution deterministic and fail-soft.
- Allow bounded evolution through versioned, verified profiles rather than
  runtime code or YAML self-modification.
- Preserve final prompt construction through `PromptBuilder.build(PromptContext)`.

## 4. Non-Goals

- Creating a new retrieval or booster mode.
- Adding a second CVaR or fusion implementation.
- Running all candidate modes against providers during an online request.
- Mutating Java, Plan DSL, database rows, environment variables, or credentials
  from the evolution loop.
- Changing public APIs, the public `Signals` constructor, or provider contracts.
- Automatically promoting a profile without Desktop verification.
- Using raw queries, snippets, prompts, secrets, or authorization data as
  public trace or learning features.

## 5. Existing Source Boundaries

The following owners were confirmed in the active source roots:

| Responsibility | Canonical owner |
| --- | --- |
| Plan value object | `main/java/com/example/lms/orchestration/ExecutionPlan.java` |
| Strategy conflict decision | `main/java/com/example/lms/orchestration/StrategyConflictResolver.java` |
| Signal derivation and plan application | `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java` |
| Existing upper/lower-tail aggregation | `main/java/com/nova/protocol/fusion/CvarAggregator.java` |
| Existing HYPERNOVA fusion | `main/java/com/nova/protocol/fusion/NovaNextFusionService.java` |
| Final RAG prompt | `main/java/com/example/lms/prompt/PromptBuilder.java` |

Active production source roots are `main/java`, `main/resources`,
`app/src/main/java_clean`, and `app/src/main/resources`. Aliases, archives,
generated output, `app/src/main/java`, and `project/src/main/java` are excluded.

## 6. Architecture

### 6.1 Immutable request snapshot

`ExecutionPlanApplier` derives the current boolean `Signals` as it does today.
It also derives a package-scoped robustness snapshot once per request. The
public `resolve(Signals)` behavior remains compatible.

The robustness snapshot contains only bounded numeric or boolean evidence:

| Field | Source | Normalization |
| --- | --- | --- |
| `coverageScore` | `outCount` and the current low-recall boundary | `min(1, outCount / target)` |
| `authorityScore` | existing authority trace | clamp to `0..1` |
| `consistencyScore` | maximum existing contradiction signal | `1 - clamp(contradiction)` |
| `highRiskTail` | existing high-risk signal | boolean hard signal |
| `budgetEligible` | existing orchestration time-budget boundary | boolean hard gate |

Missing numeric evidence is not imputed optimistically. It either reduces the
candidate score to zero or makes the candidate ineligible, according to the
field contract.

### 6.2 Candidate set

Only current modes are evaluated:

- `NORMAL`
- `OVERDRIVE`
- `EXTREMEZ`
- `HYPERNOVA`

No candidate may activate more than one primary booster. Suppressed candidates
continue to appear in the existing exclusion traces.

### 6.3 Hard-gate filter

A candidate is removed before scoring if any required gate fails:

- single-primary-booster invariant;
- current time-budget eligibility;
- PromptBuilder boundary preservation;
- citation, PII, and final-answer gates remain enabled;
- provider-disabled, cancellation, and timeout behavior remains fail-soft;
- profile hash, source revision, unit, direction, sample count, and verifier are
  present;
- candidate profile is not stale.

Hard gates cannot be overridden by an aggregate score.

### 6.4 Shadow profile

Candidate outcomes come from a versioned profile generated by Desktop replay
over approved fixtures. The online request does not execute non-selected
providers or boosters.

Each profile row is keyed by a redacted trigger bucket, mode, source revision,
fixture-set hash, and profile version. It records normalized outcome axes,
sample count, generation time, verifier command, and profile hash.

The profile transport and loader target remain `evidence_needed` until the
existing Plan DSL/configuration call path is proven. This design does not guess
a YAML property or introduce an unproven storage surface.

## 7. Metric Contract

### 7.1 Independent axes

| Axis | Unit | Direction | Normalization |
| --- | --- | --- | --- |
| Consistency | ratio `0..1` | higher is better | clamp |
| Non-starvation | ratio `0..1` | higher is better | `1 - starvationRate` |
| Authority floor | ratio `0..1` | higher is better | clamp |
| Coverage | document count | higher is better | `min(1, outCount / target)` |
| Budget | milliseconds | hard gate | reject when over budget |

Ranks, probabilities, authority scores, and raw document counts are never
averaged before normalization.

### 7.2 Pareto survivor filter

Candidate A dominates candidate B only when A is no worse on every comparable
axis and better on at least one. Dominated candidates are removed. A missing or
invalid axis is zero, not absent, so incomplete candidates cannot obtain an
inflated score.

### 7.3 Lower-tail selection

For each survivor:

```text
robustTailScore = CvarAggregator.lowerTailMean(
  [consistency, nonStarvation, authorityFloor, coverage],
  tailFraction
)
```

The existing lower-tail implementation includes null, NaN, and infinity as
zero after clamping. The design therefore reuses it directly and fails closed.

Proposed starting thresholds are design defaults, not observed runtime facts:

| Threshold | Proposed value | Promotion requirement |
| --- | ---: | --- |
| `tailFraction` | `0.25` | Desktop lower-tail contract test |
| Minimum samples per bucket and mode | `30` | complete verified samples |
| Minimum score improvement | `0.05` | candidate minus current baseline |
| Maximum p95 latency regression | `15%` | compared with same fixture bucket |
| Safety, PromptBuilder, single-booster regression | `0` | hard gate |

Desktop must calibrate these values from a current fixture benchmark before
`ENFORCE`. Without that evidence, the system stays in `SHADOW` or `OFF`.

## 8. Operating States

| State | Proposal calculation | Executed plan | Allowed transition |
| --- | --- | --- | --- |
| `OFF` | disabled | current fixed-priority plan | explicit configuration only |
| `SHADOW` | enabled | current fixed-priority plan | default initial state |
| `ENFORCE` | enabled | eligible robust proposal | Desktop-verified profile and gates |

An `ENFORCE` decision must use the same immutable request snapshot used to
choose the trigger bucket. If the snapshot, profile hash, sample count, or
budget evidence changes, the request falls back to the current policy.

## 9. Promotion and Evolution

Evolution means selecting among versioned, bounded profiles and existing plan
parameters. It does not mean code mutation or online Plan DSL editing.

Promotion requires all of the following:

1. The fixture set includes contradiction, sparse evidence, mixed-authority,
   cancellation, timeout, and invalid-score cases.
2. The candidate is a Pareto survivor.
3. Its lower-tail score exceeds the current mode by at least the configured
   margin.
4. Safety and single-booster gates have zero regressions.
5. p95 latency stays inside the configured regression limit.
6. The profile and evidence hashes match the current source revision.
7. Desktop focused tests and broader verification pass.

Future `ArtPlateEvolver` or Plan DSL integration may propose a new profile, but
is outside the first implementation slice. It requires a separately approved
source directive and cannot self-promote.

## 10. Failure Handling

| Failure | Required behavior | Reason code |
| --- | --- | --- |
| Profile missing | current fixed-priority plan | `profile-missing` |
| Profile stale or hash mismatch | current fixed-priority plan | `profile-stale` |
| Sample count below threshold | current fixed-priority plan | `insufficient-samples` |
| Invalid or mixed-scale metric | candidate zero/removal | `invalid-metric` |
| No Pareto survivor | current policy or `NORMAL` | `no-pareto-survivor` |
| Tie or improvement below margin | current mode | `margin-not-met` |
| Budget or cancellation failure | regular RAG fail-soft | `budget-exceeded` |
| Booster execution failure | regular RAG; no booster cascade | `booster-failed` |
| Trace write failure | preserve request behavior | `trace-write-failed` |

A failed booster never triggers a second high-power booster in the same
request. This prevents failure amplification and budget poisoning.

## 11. Trace Contract

New trace keys are bounded, redacted, and stable:

- `routing.robustness.mode=OFF|SHADOW|ENFORCE`
- `routing.robustness.profileHash`
- `routing.robustness.bucket`
- `routing.robustness.candidateCount`
- `routing.robustness.survivorCount`
- `routing.robustness.proposedMode`
- `routing.robustness.baselineScore`
- `routing.robustness.lowerTailScore`
- `routing.robustness.promotionMargin`
- `routing.robustness.decisionReason`

Existing keys remain authoritative for mutual exclusion:

- `boosterMode.active`
- `boosterMode.excludedModes`
- `boosterMode.exclusionReason`

No raw query, prompt, snippet, API key, token, cookie, or authorization data is
allowed in these traces.

## 12. Test Design

### 12.1 Unit and contract tests

- Exhaust all 16 combinations of the four boolean signals.
- Prove exactly one primary booster or `NORMAL` is selected.
- Prove null, NaN, and infinity cannot improve a lower-tail score.
- Prove Pareto-dominated candidates are removed.
- Prove stale, missing, or undersampled profiles fall back.
- Prove a tie or sub-margin improvement preserves the current mode.
- Prove `SHADOW` records a proposal but does not change the executed plan.
- Prove `ENFORCE` cannot activate multiple boosters.
- Prove a failed booster cannot cascade to another booster.
- Preserve existing `resolve(Signals)` behavior when robustness data is absent.

### 12.2 Desktop verification sequence

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$pcd | Out-Null
if (Test-Path ".git\index.lock") { throw "index-lock-conflict" }
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "com.example.lms.orchestration.StrategyConflictResolverTest" --no-daemon --project-cache-dir $pcd
.\gradlew.bat test --tests "com.example.lms.orchestration.ExecutionPlanApplierTest" --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd
```

The focused tests must run before broader module verification. Browser, UI, or
prompt-build success does not prove provider/runtime lineage.

## 13. Rollout and Rollback

1. Add RED fixtures without enabling runtime selection.
2. Implement backward-compatible scoring and fallback behavior.
3. Run in `OFF` and prove no current-plan regression.
4. Move to `SHADOW` with fixture-derived profile evidence.
5. Compare proposal and baseline by trigger bucket.
6. Permit `ENFORCE` only after Desktop promotion gates pass.

Rollback is immediate: set robustness mode to `OFF` and preserve the current
fixed-priority resolver. Profile artifacts may be quarantined without changing
Java or provider configuration.

## 14. GoalContract

- goalId: `AWX-GC-20260731-RAG-ROBUST-EVOLUTION-FINAL`
- rewrittenUserIntent: evolve Dynamic RAG toward contradiction and sparse-signal robustness while preserving safety, determinism, and existing source authority
- desiredOutcome: immutable snapshot -> hard gates -> normalized axes -> Pareto survivors -> lower-tail selection -> guarded promotion
- measurableSuccess: lower-tail improvement at least `0.05`; safety and single-booster regressions `0`; p95 latency regression at most `15%`; at least `30` verified samples per bucket and mode
- nonGoals: new retrieval mode, second CVaR, online provider shadow calls, code/YAML self-mutation
- authorizedMutationSurface: design document only in this turn
- prohibitedSurface: application source, public API, DB, Supabase, credentials, environment variables, provider configuration
- evidenceBaseline: active source roots, current resolver/applier/CVaR/PromptBuilder file hashes, approved design decisions
- assumptions: proposed thresholds require Desktop benchmark calibration before `ENFORCE`
- constraints: one booster, PromptBuilder-only final prompt, LangChain4j `1.0.1`, deterministic fallback, fail-soft provider behavior
- verificationOwner: Desktop
- verificationCommands: Section 12.2
- rollback: robustness mode `OFF`; preserve current fixed priority
- stopConditions: missing/stale profile, insufficient samples, mixed metric units, budget failure, source revision mismatch, any hard-gate regression
- timeBudgetMinutes: `180`
- goalScore: `78`
- verdict: `APPLY-design`; implementation and runtime lineage remain `HOLD`
- evidence_needed: current contradiction/sparse benchmark, profile transport owner, Desktop branch/status, final build/runtime proof

## 15. SourceDirective

- directiveId: `AWX-SD-20260731-RAG-ROBUST-EVOLUTION-FINAL`
- sourceOwner: `desktop`
- provenRoot: Notebook supporting evidence from `Y:\`; Desktop final root must be reconfirmed
- provenBranch: `evidence_needed`
- activeSourceSets: `main/java`, `main/resources`, `app/src/main/java_clean`, `app/src/main/resources`
- targetFiles:
  - `main/java/com/example/lms/orchestration/StrategyConflictResolver.java`
  - `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`
  - `src/test/java/com/example/lms/orchestration/StrategyConflictResolverTest.java`
  - `src/test/java/com/example/lms/orchestration/ExecutionPlanApplierTest.java`
- callPathOrBoundary: `ExecutionPlanApplier.deriveSignals -> StrategyConflictResolver.resolve -> ExecutionPlan knobs -> apply plan overrides`
- beforeBehavior: fixed `EXTREMEZ > HYPERNOVA > OVERDRIVE` selection
- afterBehavior: `SHADOW` robust proposal with verified lower-tail `ENFORCE` gate and deterministic fallback
- excludedFilesAndMirrors: `CvarAggregator`, `NovaNextFusionService`, `PromptBuilder`, RetrievalOrder aliases, inactive source roots, archives, generated output
- publicApiChange: `forbidden`
- secretMutation: `forbidden`
- redTest: fixed priority selects a lower-tail-inferior candidate in an approved fixture while SHADOW leaves the executed plan unchanged
- greenTest: Pareto/lower-tail logic proposes the stronger existing mode, preserves single-booster safety, and fails back on every invalid profile condition
- exactVerificationCommands: Section 12.2
- expectedEvidence: test counts, source/profile hashes, proposed and executed modes, survivor count, lower-tail score, margin, budget, fallback reason
- failureClassifications: `profile-missing`, `profile-stale`, `insufficient-samples`, `invalid-metric`, `no-pareto-survivor`, `margin-not-met`, `budget-exceeded`, `booster-failed`
- rollback: set mode `OFF`, restore current fixed-priority resolver behavior, quarantine unverified profile
- patchdropContract: no bundle in this design turn; any future producer bundle must satisfy cumulative PatchDrop v3 and Desktop consumer gates
- desktopFinalProof: `evidence_needed`

## 16. Open Evidence Gates

These are explicit verification dependencies, not unfinished design choices:

- `evidence_needed`: current contradiction/sparse fixture benchmark.
- `evidence_needed`: live profile transport/loader owner before targeting Plan DSL or configuration files.
- `evidence_needed`: Desktop Git branch, clean/overlap state, leases, and PatchDrop queue before implementation.
- `evidence_needed`: focused RED/GREEN output, broader compilation, and final runtime lineage.

Until these proofs exist, the approved implementation posture is `OFF` or
`SHADOW`; `ENFORCE` remains prohibited.

# Evidence-Gated Focused-Lane Canary Design

## 1. Status and approved scope

- Design status: user-approved for repository design documentation.
- Workspace: canonical `Y:\` in read/documentation mode.
- Current verdict: `HOLD` for application-source mutation.
- Goal score: `36.7/100`.
- EvidenceSnapshot SHA-256: `e9f20f2c709d59c995b0c943b481a20295ca893cdad9e7eaa1ad7bdb5b87334c`.
- Allowed in this phase: this Markdown design document and its self-review.
- Excluded in this phase: application source, runtime configuration, tests, DB, credentials, environment persistence, external services, commit, push, deployment, and production canary traffic.
- Final verification owner: Desktop; `desktopFinalProof=evidence_needed`.

This design turns weak signals into bounded probes, not conclusions. A virtual LLM debate may generate counter-hypotheses, but it cannot establish causality or guarantee prediction accuracy. The system must not infer that a historical social event caused a current outcome without independent, timestamped evidence.

## 2. Problem statement

The current orchestration already contains most required mechanisms:

- `PromptPoseApplicationJudge` chooses request-time resource knobs;
- `SelfAskPlanner` can order and limit Self-Ask lanes from one to three;
- `Zero100BranchScheduler`, `TimeBudgetGuard`, and `Zero100SessionRegistry` manage budget-aware routing and request/session state;
- `NightmareBreaker` protects provider boundaries with CLOSED, OPEN, and HALF_OPEN states;
- `TraceStore` supplies request-scoped, redacted evidence.

The narrow gap is policy, not infrastructure. `PromptPoseApplicationJudge` currently defaults to two or three Self-Ask lanes and merges toward the larger lane count. It does not prove when a single lane is safe, when a failure may trigger exactly one alternate lane, or how correlated historical feedback is prevented from authorizing a concentrated route.

The feature must reduce calls and latency for a small, evidence-strict request class without silently reducing recall, amplifying irrelevant personal narratives, creating retry loops, or adding a second orchestration framework.

## 3. Design decisions

1. Extend existing PromptPose/SelfAsk/Zero100 boundaries; do not create a new agent framework.
2. A single lane is an opt-in canary state, never the default for all requests.
3. Only current request evidence can authorize focus. Historical reward may veto focus but cannot enable it.
4. Weak or socially sensitive signals have `probe_only` authority until independently corroborated.
5. Focus failure permits at most one alternate-lane activation. Focused expansion-lane activations are capped at two; existing provider-internal calls remain bounded by the existing request budget.
6. Missing context lineage disables the focused fallback; it never triggers unbounded reconstruction or fan-out.
7. Request/session state remains in existing `GuardContext`, `Zero100SessionRegistry`, and `TraceStore` boundaries.
8. Redis, a new state service, HB storage, and new microservices are excluded until a separately approved multi-process state-loss reproduction proves they are necessary.
9. The phrase `800 mechanism` is outside this design. It must not become an 800 ms constant, route name, or fallback mode without a separate definition and approval.
10. Final prompt assembly remains exclusively on `PromptBuilder.build(PromptContext)`.

## 4. Chosen architecture

```text
Current request telemetry and sanitized intent
  -> Focus Eligibility Gate
       -> ineligible: existing two/three-lane behavior
       -> eligible but outside canary: existing two/three-lane behavior
       -> eligible and in canary: select one highest-weight healthy lane
            -> success: finish
            -> classified failure: one alternate healthy lane
                 -> success: finish
                 -> failure or missing lineage: HOLD/fail-soft regular boundary
```

### 4.1 Focus Eligibility Gate

The gate evaluates the pre-feedback request decision. Focus is eligible only when every condition is true:

- `prompt-pose.enabled=true` and the focus policy is enabled;
- the request is classified as `evidence_strict`;
- `failureSlot=none`;
- base decision confidence is at least `0.70`;
- the highest lane weight exceeds the second-highest lane weight by at least `0.20`;
- the highest lane is healthy and is not protected by an open breaker;
- the request retains enough budget for one initial focused lane and one bounded fallback lane;
- the highest lane is unique; ties are ineligible;
- the deterministic request-hash bucket is inside the configured canary percentage.

Safe configuration contract:

| Property | Default | Allowed range | Meaning |
| --- | ---: | ---: | --- |
| `prompt-pose.application.focus.enabled` | `false` | boolean | Master focus gate |
| `prompt-pose.application.focus.canary-percent` | `0` | `0..5` before explicit rollout expansion | Deterministic request-hash bucket |
| `prompt-pose.application.focus.min-confidence` | `0.70` | `0.50..0.95` | Pre-feedback confidence floor |
| `prompt-pose.application.focus.min-lane-margin` | `0.20` | `0.05..1.00` | Winner-to-runner-up weight gap |
| `prompt-pose.application.focus.max-fallbacks` | `1` | fixed at `1` | Alternate-lane cap |
| `prompt-pose.application.focus.max-lane-activations` | `2` | fixed at `2` | Initial focused lane plus fallback lane cap |

Invalid, non-finite, or out-of-range focus configuration disables focus with `focus_invalid_config`; it does not clamp upward into a more aggressive mode.

### 4.2 Lane selection

The gate uses the existing BQ/ER/RC lane weights and deterministic ordering. It sets the existing `selfAskCount` to `1`; `SelfAskPlanner` remains the owner of lane ordering and generation. The focused decision also sets `queryBurstMax=0`, `minLaneCoverage=1`, and disables risk-consensus fan-out for the initial focused lane. `PromptPoseApplicationAspect` must preserve these focus-specific overrides instead of unconditionally restoring multi-lane risk consensus.

The existing direct/preflight retrieval call, if active, remains part of the established retriever path and is not mislabeled as a focused lane. Provider-internal retries and calls are not governed by the number `2`; they remain governed by the existing `SelfAskSearchBudget`, provider timeout, and request-budget contracts. The new cap applies only to orchestration-level focused lane activations.

The selector must not use:

- raw social history;
- inferred personality or group membership;
- an unsupported numeric prior such as the user's illustrative 10%;
- default or globally aggregated PromptPose feedback;
- duplicate signals derived from the same prompt, result row, or trace lineage.

### 4.3 Feedback isolation

Historical PromptPose/CFVM feedback is advisory and asymmetric:

- exact-tile low reward may veto focus;
- exact-tile steady or high reward does not independently authorize focus;
- default-tile and aggregate feedback never authorize focus;
- one request contributes at most one reward update to one tile;
- raw prompts, historical personal narratives, snippets, and secrets are never stored in the tile key or payload;
- absent feedback is neutral, not positive evidence.

This prevents a correlated feedback loop from converting a weak signal into increasingly confident routing.

### 4.4 Bounded fallback

The initial focused lane may transition to one alternate only for an allowlisted failure:

- `timeout`;
- `rate_limit`;
- `provider_disabled`;
- `empty_response`;
- `after_filter_starvation`;
- `breaker_open`.

The alternate is the next-highest healthy lane after applying current breaker and budget state. The orchestration layer does not reactivate the same failed lane/provider; existing provider-owned bounded retry behavior remains unchanged. A configuration failure, secret risk, invalid state, authority failure, cancellation, or missing context lineage does not trigger an alternate lane.

The invariant is:

```text
initialFocusLaneActivationCount <= 1
fallbackLaneActivationCount <= 1
totalFocusedLaneActivationCount <= 2
totalElapsedMs <= existingRequestBudgetMs
providerCallCount <= existingProviderAndRequestBudgets
```

### 4.5 Context continuity

The focused attempt and fallback share a bounded state envelope held by existing request/session owners:

```text
requestHash12
sessionHash12
selectedLane
laneActivationIndex
remainingBudgetMs
failureClass
contextReferenceHashes[]
```

The envelope contains hashes, counters, allowlisted reason codes, and references only. It excludes raw query text, prompt text, evidence snippets, credentials, personal history, and full environment values.

If the request/session state cannot be resolved consistently, the transition records `context_lineage_missing`, performs no focused fallback, and returns to the existing fail-soft boundary. This design does not reconstruct state from unverified memory.

## 5. State machine

| State | Allowed next state | Condition |
| --- | --- | --- |
| `BASELINE` | `FOCUS_SELECTED`, `BASELINE_DONE` | All eligibility/canary gates pass, otherwise existing behavior |
| `FOCUS_SELECTED` | `FOCUS_RUNNING`, `BASELINE_DONE` | Lane remains healthy and budget remains valid |
| `FOCUS_RUNNING` | `SUCCEEDED`, `FALLBACK_RUNNING`, `HELD` | Success, allowlisted failure, or non-fallback failure |
| `FALLBACK_RUNNING` | `SUCCEEDED`, `HELD` | One alternate attempt succeeds or terminates |
| `SUCCEEDED` | none | Terminal |
| `HELD` | none | Terminal; no additional fan-out |

No transition returns to an earlier state. This makes retry loops structurally impossible.

## 6. Component ownership and provisional file map

The file map is provisional until the RED proof confirms the static call path at runtime. It is not source-write authorization.

| Responsibility | Existing owner | Planned action after separate implementation approval |
| --- | --- | --- |
| Focus configuration | `main/java/com/example/lms/config/PromptPoseProperties.java` | Add bounded nested focus properties |
| Focus eligibility and feedback veto | `main/java/com/example/lms/prompt/pose/PromptPoseApplicationJudge.java` | Compute pre-feedback eligibility and set `selfAskCount=1` |
| Focus-specific plan overrides | `main/java/com/example/lms/prompt/pose/PromptPoseApplicationAspect.java` | Apply `queryBurstMax=0`, `minLaneCoverage=1`, and risk-consensus disabled for focus |
| Runtime defaults | `main/resources/application-llm.yaml` | Add default-off focus keys |
| Lane ordering and count | `main/java/com/example/lms/service/rag/SelfAskPlanner.java` | Reuse; modify only if a failing test proves incompatibility |
| Focused retrieval and lazy alternate | `main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java` | Extend existing rollover boundary only if RED proves it lacks the required transition |
| Budget and state | `TimeBudgetGuard`, `Zero100BranchScheduler`, `Zero100SessionRegistry` | Reuse; no duplicate implementation |
| Provider breaker | `NightmareBreaker` | Reuse as provider health evidence, not as a lane router |
| Unit/integration proof | existing PromptPose, SelfAsk, Zero100, TimeBudget, and NightmareBreaker tests | Add narrow assertions in existing test owners |

Excluded files and mirrors:

- `app/src/main/java`;
- `project/src/main/java`;
- compatibility aliases outside the canonical SelfAsk/ExtremeZ/Overdrive owners;
- archives, backups, generated build output, `node_modules`, and `.gradle`;
- `PromptBuilder` and ChatService prompt concatenation paths;
- DB, Supabase, credentials, and public API surfaces.

## 7. Trace and lineage contract

Use low-cardinality, redacted keys:

```text
promptPose.focus.enabled
promptPose.focus.eligible
promptPose.focus.reason
promptPose.focus.canaryBucket
promptPose.focus.selectedLane
promptPose.focus.laneMargin
promptPose.focus.laneActivationCount
promptPose.focus.fallbackCount
promptPose.focus.stateHash12
promptPose.focus.rawIncluded=false
```

Existing evidence remains authoritative where already available:

```text
promptPose.application.selfAskCount
selfask.3way.laneLimit
selfask.3way.laneOrder
zero100.timeBudget.forceFallback
zero100.timeBudget.reason
zero100.rollover.events
nightmare.breaker.openKind
```

A runtime success claim additionally requires a request-specific prompt/options hash and provider attempt/response rows. UI output, prompt construction, or a breaker trace alone does not prove runtime lineage.

## 8. Error handling

| Failure | Behavior | Reason code |
| --- | --- | --- |
| Focus disabled or outside canary | Preserve existing behavior | `focus_disabled`, `focus_not_in_canary` |
| Confidence or lane margin too low | Preserve existing behavior | `focus_insufficient_confidence`, `focus_insufficient_margin` |
| Current failure or breaker state | Preserve existing behavior | `focus_active_failure` |
| Initial allowlisted failure | Attempt one healthy alternate | normalized failure class |
| No healthy alternate | Terminal fail-soft/HOLD | `focus_no_healthy_fallback` |
| Missing state envelope | No alternate attempt | `context_lineage_missing` |
| Attempt or budget cap reached | Terminal fail-soft/HOLD | `focus_budget_exhausted` |
| Invalid configuration | Disable focus | `focus_invalid_config` |
| Secret or raw-sensitive-data risk | Fail closed | `secret-leak-risk` |

## 9. Test design

### 9.1 RED assertions

1. An evidence-strict request with pre-feedback confidence `0.72`, BQ weight `1.25`, next weight `0.95`, no failure, and an in-canary hash currently returns more than one Self-Ask lane.
2. A low-confidence, tied, failure-active, flag-off, or out-of-canary request must never become focused.
3. High aggregate reward without exact-tile evidence must not authorize focus.
4. An irrelevant, unverified historical-social narrative must not change the selected lane or resource knobs when current structured request evidence is unchanged.
5. A forced timeout must either preserve the state hash and run exactly one healthy alternate or terminate with `context_lineage_missing`.
6. No test may observe more than two focused lane activations, an unbounded provider-call increase, or a request-budget overrun.

### 9.2 GREEN assertions

- Eligible canary: `promptPose.application.selfAskCount=1`, `selfask.3way.laneLimit=1`, `queryBurstMax=0`, `minLaneCoverage=1`, risk-consensus disabled, and one selected lane.
- Flag off, ineligible, tied, or outside canary: byte-for-byte equivalent decision fields for the existing path.
- Exact-tile low reward vetoes focus; high/default/aggregate reward cannot create eligibility.
- Allowlisted initial failure produces at most one alternate lane activation and never reactivates the failed lane/provider at the orchestration layer.
- Missing context lineage produces no alternate lane activation.
- `promptPose.focus.rawIncluded=false`; raw query, prompt, social narrative, secret, and evidence snippets are absent from traces.
- Existing two/three-lane tests continue to pass.

### 9.3 Verification commands

Desktop runs from canonical `Y:\` representation with host-local caches:

```powershell
Set-Location Y:\
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) { throw 'jdk-not-exposed' }
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'AbandonWareX\gradle-desktop-focus'
$awxFocusProjectCache = Join-Path $env:LOCALAPPDATA 'AbandonWareX\project-cache-desktop-focus'

.\gradlew.bat --no-daemon --project-cache-dir $awxFocusProjectCache compileJava
.\gradlew.bat --no-daemon --project-cache-dir $awxFocusProjectCache test `
  --tests 'com.example.lms.prompt.pose.PromptPoseApplicationJudgeTest' `
  --tests 'com.example.lms.prompt.pose.SelfAskPlannerPromptPoseTest' `
  --tests 'com.example.lms.service.rag.SelfAskWebSearchRetrieverTest' `
  --tests 'ai.abandonware.nova.orch.zero100.Zero100BranchSchedulerTest' `
  --tests 'ai.abandonware.nova.orch.timebudget.TimeBudgetGuardTest' `
  --tests 'com.example.lms.infra.resilience.NightmareBreakerTaxonomyTest'
.\gradlew.bat --no-daemon --project-cache-dir $awxFocusProjectCache :app:classes
```

Notebook evidence remains supporting evidence. The current Notebook process has `jdk-not-exposed`, so no Gradle PASS is claimed by this document.

## 10. Canary and rollback

Production traffic remains at `0%` until the focused RED/GREEN suite and Desktop build pass. The first separately approved production canary is capped at `5%` of focus-eligible requests and must use a deterministic request-hash bucket.

The first review window requires at least 100 focus-eligible requests and all of these outcomes:

- mean provider/search attempts decrease by at least `20%`;
- p95 latency decreases by at least `10%`;
- citation-pass and final-sigmoid-pass rates regress by no more than `2` percentage points;
- fallback rate is at most `15%`;
- fallback-loop count, budget-overrun count, raw-trace count, and secret-pattern count are all `0`.

Immediate rollback conditions:

- either quality rate regresses by more than `2` percentage points;
- fallback rate exceeds `20%` in any 25-request rolling focus window;
- any request exceeds two focused lane activations or its existing provider/request budget;
- any context lineage mismatch, raw-sensitive trace, or secret pattern is observed.

Rollback sets the focus gate and canary percentage to disabled/zero and retains the existing two/three-lane behavior. It does not delete state, rewrite historical feedback, or alter DB/credentials.

## 11. GoalContract

```text
goalId: GD-20260804-FOCUSED-LANE-CANARY
rewrittenUserIntent: Concentrate low-ambiguity, evidence-strict requests on one lane without amplifying unverified weak signals, then permit one context-preserving fallback.
desiredOutcome: A default-off, evidence-gated focused-lane canary over existing PromptPose/SelfAsk/Zero100 boundaries.
measurableSuccess: One initial lane, at most one alternate, existing budget retained, flag-off behavior preserved, zero raw/secret traces, and the canary thresholds in section 10 satisfied.
nonGoals: Social-causality judgment, automatic moderation, Redis/HB, new microservices, unconditional focus, or the undefined 800 mechanism.
authorizedMutationSurface: Documentation only in the current phase.
prohibitedSurface: Application source/config/tests until separate approval; DB, credentials, public APIs, inactive mirrors, and PromptBuilder bypass always prohibited here.
evidenceBaseline: EvidenceSnapshot e9f20f2c709d59c995b0c943b481a20295ca893cdad9e7eaa1ad7bdb5b87334c.
assumptions: Existing static call path is correct; runtime activation remains unproven.
constraints: Default off, canary <=5%, maxFocusedLaneActivations=2, maxFallbacks=1, queryBurstMax=0 in focus, current-evidence authorization, historical-feedback veto only, redacted state.
verificationOwner: desktop
verificationCommands: Section 9.3.
rollback: Disable focus and set canary to zero; retain existing behavior.
stopConditions: Section 10 rollback conditions plus missing JDK, source authority, active call path, or runtime lineage.
timeBudgetMinutes: 180
goalScore: 36.7
verdict: HOLD for implementation; approved for design documentation.
evidence_needed: One Desktop/JDK17 focused test proving paired baseline/focus behavior and forced-timeout one-fallback lineage.
```

## 12. SourceDirective

```text
directiveId: SD-20260804-FOCUSED-LANE-CANARY
sourceOwner: desktop
provenRoot: Y:\ read-only identity proven; source-write authority absent
provenBranch: evidence_needed
activeSourceSets: root main/java and main/resources; app/src/main/java_clean and app/src/main/resources
targetFiles: Provisional owners in section 6; no write authorization. Fallback owner changes only after a failing test proves the existing rollover boundary insufficient.
callPathOrBoundary: PromptPoseApplicationAspect -> PromptPoseApplicationJudge -> GuardContext overrides -> SelfAskWebSearchRetriever -> SelfAskPlanner; fallback through Zero100SessionAspect, TimeBudgetGuard, Zero100SessionRegistry; provider health through NightmareBreaker.
beforeBehavior: PromptPose is default-off; application decisions generally retain two or three lanes and may use default/aggregate feedback.
afterBehavior: Eligible in-canary evidence-strict requests use one lane with query burst and risk-consensus fan-out disabled; historical feedback may only veto; one allowlisted failure may use one healthy alternate with preserved redacted state.
excludedFilesAndMirrors: Inactive/legacy source roots, aliases, backups, archives, generated outputs, DB, credentials, PromptBuilder, and public APIs.
publicApiChange: forbidden
secretMutation: forbidden
redTest: Section 9.1.
greenTest: Section 9.2.
exactVerificationCommands: Section 9.3.
expectedEvidence: Focus trace keys, existing SelfAsk/Zero100/breaker keys, focused test results, request prompt/options hash, provider attempt/response rows, count-only secret scan.
failureClassifications: Section 8 plus jdk-not-exposed, active-call-path-unproven, source-authority-unproven, runtime-lineage-missing, and quality-floor-failed.
rollback: Section 10.
patchdropContract: No bundle in this phase. Desktop remains final owner; Notebook publishes no producer patch.
desktopFinalProof=evidence_needed
```

## 13. Completion boundary

This design document is complete when its approved scope, decisions, thresholds, state transitions, failure behavior, tests, rollout gates, and rollback are internally consistent and contain no unresolved implementation choice.

Implementation is not authorized by this document. It starts only after written-spec review, a separate implementation-plan approval, source-owner/preimage guards, JDK 17 availability, and the required RED proof. A later implementation cannot claim success until Desktop verifies the full focused suite, broader compile/classes gates, runtime lineage, and count-only secret results.

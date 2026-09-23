# Patch Potential Guard Design

## Status and authority

- Date: 2026-07-31
- Design status: approved in conversation
- Request class: `prompt_skill_tooling_only` for implementation of the guard itself
- Selected operating mode: `AUTO_SINGLE_CANDIDATE`
- Runtime mutation owner: Desktop or the repository-owned guarded source-edit workflow
- Notebook role: design, tooling evidence, and supporting verification
- Application-source mutation during guard implementation and tests: forbidden
- Commit, push, deployment, database mutation, credential mutation, and public API change: forbidden without separate authorization
- Desktop final proof: `evidence_needed`

## Problem

Patch discovery currently has useful but separate assets:

- a source-health scorecard that identifies and ranks active risks;
- an orchestration patch-scanner prompt that uses a simple five-axis score;
- an evidence-grounded three-query reviewer;
- deterministic GoalScore and long-tail graders;
- repository-owned SMB lease, compare-and-swap, verification, rollback, and postprocessing guards.

The missing boundary is a reusable pre-patch guard that converts those assets into one deterministic decision: either mutate exactly one top candidate transactionally or perform no source write. The guard must retain reproducible history outside conversational memory and must not let a fluent majority, high aggregate score, or legacy-looking filename override ownership, safety, source-boundary, or verification failures.

## Goals

- Freeze one redacted evidence snapshot for every run.
- Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`.
- Rank patch candidates with the existing official GoalScore formula.
- Select at most one candidate per run through deterministic tie-breaking.
- Require non-compensable hard gates before any source mutation.
- Delegate source mutation, rollback, and terminal adjudication to existing repository-owned guards.
- Support legacy removal only through a stricter `DELETION_ELIGIBLE` contract.
- Persist immutable, hash-bound, ready-last audit artifacts for every run.
- Fail closed before mutation and roll back after any failed post-mutation gate.

## Non-goals

- Majority voting among the three review branches.
- A fourth reviewer, leader election, or distributed consensus.
- Multiple source mutations in one evidence-snapshot run.
- Automatic retry or self-repair after a failed patch.
- A second source lease, compare-and-swap, PatchDrop, or rollback protocol.
- Automatic deletion of archives, backups, inactive mirrors, generated output, or unknown source surfaces.
- Treating a high artifact score as runtime, provider, UI, or causal proof.
- Public API, database, Supabase, credential, provider, or deployment changes.

## Evidence baseline

The approved design is grounded in the following current observations:

- `Y:\` resolves to `\\DESKTOP-M5NOV6K\MacSrc`.
- Git metadata is unavailable because of dubious ownership; global `safe.directory` was not changed.
- `.git\index.lock` was absent at design time. Dirty status and branch remain `evidence_needed` for any future mutation session.
- Root active source sets are `main/java` and `main/resources`.
- `:app` active source sets are `app/src/main/java_clean` and `app/src/main/resources`.
- The existing three-query skill and prompt pack already freeze evidence, check A-B and B-A order, and keep design, artifact, statistical, and runtime-lineage verdicts separate.
- The design meta-autograder passed 29 tests and graded the sealed v2 design `APPLY` with score 100, zero hard gates, full rule coverage, zero false accepts, and zero canonical false rejects.
- The v1 artifact grader passed 59 tests.
- The three-perspective prompt pack passed 14 tests.
- The GoalScore contract passed 18 tests.
- The source-health scorecard passed 56 tests.
- Total focused baseline: 176 passing tests.

## Reuse, extend, create decision

Use `reuse -> extend -> create`.

### Reuse unchanged

- `scripts/source_health_scorecard.py` for source-risk and candidate evidence.
- `scripts/score_three_way_long_tail_query.py` for bounded artifact grading.
- `scripts/score_three_way_long_tail_design.py` for three-query design integrity.
- `scripts/awx_goal_score_contract.ps1` for authoritative GoalScore recomputation.
- `demo1-agentic-chat-postprocess` for the frozen three-query review contract.
- `demo1-macsrc-smb-direct-patch` for MacSrc lease, preimage, mutation, focused verification, postimage, secret scan, and rollback.
- `demo1-macsrc-patch-postprocessor` for terminal session adjudication.
- PatchDrop v3 producer/consumer tooling when isolated production is selected.

### Extend

- Update `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md` to emit normalized candidates for the new guard instead of treating a simple sum as mutation authority.

### Create

Create one thin repository-local orchestration skill, a deterministic eligibility grader, and focused contract tests. This is justified because no current skill owns pre-patch, score-gated, single-candidate automatic mutation. The new skill must delegate all existing specialist responsibilities rather than duplicate them.

## Architecture

### Components

1. **Candidate Adapter**
   - Reads bounded source-health and scanner evidence.
   - Emits normalized candidate records.
   - Creates no source writes and no evidence authority.

2. **Triad Coordinator**
   - Freezes the snapshot and its SHA-256.
   - Prepares exactly three review packets.
   - Reuses the existing three-perspective packet schemas.

3. **Eligibility Grader**
   - Recomputes GoalScore from exact components.
   - Validates hard gates and evidence bindings.
   - Selects at most one candidate deterministically.
   - Derives `DELETION_ELIGIBLE` independently of the quality score.

4. **Mutation Dispatcher**
   - Does not edit source itself.
   - Delegates to `demo1-macsrc-smb-direct-patch` or a proven isolated PatchDrop producer/consumer lane.
   - Passes the declared target set, RED command, GREEN command, and frozen hashes.

5. **Audit Finalizer**
   - Validates all artifact hashes and terminal evidence.
   - References the mutation session rather than copying or replacing its evidence.
   - Publishes the run-ready marker last.
   - Delegates terminal adjudication to the existing postprocessor.

## State machine

The allowed state sequence is:

```text
DISCOVER_READONLY
  -> FREEZE_EVIDENCE
  -> POSITIVE_QUERY
  -> NEGATIVE_QUERY
  -> NEUTRAL_QUERY
  -> DETERMINISTIC_GRADE
  -> SELECT_TOP_ONE
  -> ACQUIRE_SOURCE_LEASE
  -> VERIFY_PREIMAGE
  -> VERIFY_RED
  -> APPLY_MINIMAL_PATCH
  -> VERIFY_GREEN
  -> SECRET_AND_POSTIMAGE_CHECK
  -> COMPLETE
```

Any failure before `APPLY_MINIMAL_PATCH` produces `HOLD_PREMUTATION` with `sourceWriteCount=0`.

Any failure after mutation invokes the existing rollback owner:

```text
post-mutation failure
  -> ROLLBACK
  -> VERIFY_RESTORED_PREIMAGE
  -> ROLLED_BACK | ROLLBACK_REQUIRED
```

`ROLLBACK_REQUIRED` retains the lease and prohibits every later automatic mutation until Desktop recovery completes.

Terminal states are exact tokens:

```text
COMPLETE
HOLD_PREMUTATION
ROLLED_BACK
ROLLBACK_REQUIRED
```

No terminal run may resume. A later attempt requires a new run ID and a new EvidenceSnapshot.

## Three-query adjudication

### POSITIVE_QUERY

The positive branch emits exactly one `candidateScenarios` row for every
candidate ID. Each row contains two to four falsifiable success worlds. Every
world contains:

```text
scenarioId
premise
causalMechanism
expectedObservation
evidenceNeeded
falsifier
baseRateStatus
```

The positive branch may make bounded hypotheses. A hypothesis without a distinct expected observation and falsifier receives no score credit. Extra,
missing, or duplicate candidate IDs fail closed.

### NEGATIVE_QUERY

The negative branch emits exactly one `candidateAttacks` row for every
candidate ID, attacks every positive `scenarioId`, and introduces no extra
candidate or scenario IDs. Every attack contains:

```text
scenarioId
counterexample
alternativeCause
boundaryOrAuthorityRisk
costAndBlastRadius
smallestDisconfirmingProbe
evidenceIds
```

### NEUTRAL_QUERY

Neutral receives Positive-Negative and Negative-Positive presentations. It
acquires no evidence. It emits exactly one `candidateAssessments` row for every
candidate ID. Each row contains `orderABVerdict`, `orderBAVerdict`,
`orderStable`, `verdict`, `goalScoreComponents`, `goalScoreEvidenceIds`,
`unresolvedFalsifierCount`, and `decisiveEvidenceIds`. The candidate's submitted
score components and bindings must equal the corresponding Neutral row; Neutral
does not submit a trusted threshold or final automatic-mutation authority.

If either verdict or decisive evidence differs, then:

```text
orderStable=false
autopatchEligible=false
verdict=HOLD
failureClass=order-instability
```

The three branches are not voters. Majority voting is forbidden.

The guard-specific packet schema versions are:

```text
awx.patch-potential.positive.v1
awx.patch-potential.negative.v1
awx.patch-potential.neutral.v1
```

All three carry the same `evidenceSnapshotHash`, `mutationAllowed=false`, and
exact candidate-ID set. This wrapper preserves the existing three-query role
contract while making candidate-specific grading deterministic.

## Scoring and deterministic selection

Use the existing GoalScore formula without another quality-score implementation:

```text
goalScore = 100 * (
  0.25 * evidenceStrength
  + 0.20 * causalStrength
  + 0.15 * verificationFeasibility
  + 0.15 * userValue
  + 0.10 * reversibility
  + 0.10 * costEfficiency
  + 0.05 * timeFit
  - 0.20 * blastRadius
  - 0.15 * ambiguity
  - 0.20 * authorityOrSafetyExpansion
)
```

Every input is finite, between 0.0 and 1.0, and bound to one or more frozen evidence IDs. Clamp the result to 0 through 100.

Interpretation:

| GoalScore | Result |
| ---: | --- |
| 0-49.9999 | `REJECT` or `HOLD` |
| 50-69.9999 | `evidence_needed` |
| 70-84.9999 | SourceDirective only |
| 85-100 | eligible for hard-gate evaluation |

`goalScore >= 85` does not authorize mutation. It only permits evaluation of the hard gates.

Tie-breaking is deterministic:

1. lower `blastRadius`;
2. higher `verificationFeasibility`;
3. higher `reversibility`;
4. ascending stable `candidateId`.

Input ordering, timestamp ordering, and model preference may not break a tie.

## Non-compensable hard gates

Every gate must pass:

- exact EvidenceSnapshot hash;
- exactly three canonical query packets;
- order-stable neutral verdict and decisive evidence;
- active sourceSet proof;
- actual call-path or boundary proof;
- declared target set;
- reproducible RED that fails for the intended missing behavior;
- no index lock, dirty target overlap, or source lease conflict;
- exact preimage immediately before mutation;
- allowed mutation surface only;
- no public API, DB, credential, provider, or deployment change;
- zero secret-pattern hits in declared payloads;
- complete rollback contract;
- one mutation candidate for the run;
- existing repository-owned mutation guard selected;
- score at least 85.

One failed gate yields `HOLD_PREMUTATION` regardless of score.

## Legacy deletion contract

Deletion is not a high-scoring ordinary patch. The grader derives a separate boolean:

```text
deletionEligible=true | false
```

`deletionEligible=true` requires all of:

- target is inside an explicitly authorized mutation surface;
- target is not an archive, backup, generated output, inactive mirror, or unknown source root;
- a canonical replacement owner and active call path are proven;
- no Java import reference remains;
- no Spring registration, annotation scan dependency, bean name dependency, configuration FQCN, reflection string, `ServiceLoader`, serialization name, or resource reference remains;
- no public API, DTO, configuration-key, or persistence compatibility requirement remains;
- the file is not a required compatibility alias;
- a deletion RED fails because the duplicate or forbidden legacy surface exists;
- post-deletion focused tests, source-set hygiene, duplicate-class checks, and compilation pass;
- exact original bytes or a repository-guard-owned equivalent rollback payload and SHA-256 are available.

An inactive legacy-looking file is reported as `LEGACY_INACTIVE_REPORTED`; it is not automatically deleted.

## Input and output contracts

### Candidate input

The eligibility grader accepts canonical JSON containing:

```text
schemaVersion=awx.patch-potential.candidates.v1
evidenceSnapshotHash
autopatchEnabled
mutationMode=AUTO_SINGLE_CANDIDATE
sealedEvidence
  evidenceIds[]
  staticGatesByCandidateId[]
    candidateId
    activeSourceSetProven
    callPathProven
    targetSetDeclared
    rollbackContractPresent
    publicApiChange=false
    dbMutation=false
    credentialMutation=false
    providerOrDeploymentMutation=false
    evidenceIds[]
  deletionGatesByCandidateId[]
    candidateId
    authorizedSurface
    inactiveOrArchive=false
    canonicalOwnerProven
    remainingReferenceCounts
    compatibilityAliasRequired=false
    deletionRedDefined
    rollbackArtifactSha256
    evidenceIds[]
candidates[]
  candidateId
  targetFiles[]
  candidateKind=MODIFY|DELETE
  problemEvidenceIds[]
  scoreComponents
  scoreEvidenceBindings
  redCommand
  greenCommands[]
positivePacketRef
negativePacketRef
neutralPacketRef
```

The trusted coordinator constructs `sealedEvidence` directly from the frozen
snapshot before reviewer packets are accepted. Candidate and reviewer payloads
may reference its IDs but may not create or modify the registry, static gates,
deletion gates, thresholds, computed scores, hard-gate overrides, source
ownership, or verdicts. Missing or extra candidate IDs between
`sealedEvidence` and `candidates` fail closed.

The three packet references are top-level because exactly one frozen
three-query review evaluates the complete bounded candidate set. The grader
requires the candidate ID set in Positive, Negative, Neutral, `sealedEvidence`,
and `candidates` to be identical. It also requires every candidate's
`scoreComponents` and `scoreEvidenceBindings` to byte-equivalent canonical JSON
values from that candidate's Neutral `candidateAssessments` row.

The eligibility grader owns static and deletion-gate evaluation. Dynamic
mutation gates such as the current index lock, shared lease, immediately current
preimage, watched-source delta, GREEN exit, secret scan, and postimage remain
owned and rechecked by the existing mutation guard. Therefore
`autopatchEligible=true` authorizes entry into that guard; it is not source-write
authorization. Only the existing guard's `Verify` result with `authorized=true`
permits the declared patch operation.

`autopatchEnabled` defaults to `false`. Only an explicit Desktop-owned invocation may set it to `true`. A false value still permits read-only grading and SourceDirective generation.

### Eligibility output

```text
schemaVersion=awx.patch-potential.eligibility.v1
evidenceSnapshotHash
candidateCount
selectedCandidateId
computedGoalScore
hardGates
hardGateCount
orderStable
deletionEligible
autopatchEligible
verdict
failureClass
nextSingleAction
deterministicReplayHash
```

Unknown keys, duplicate candidates, unresolved evidence IDs, non-finite numbers, or reflected secret-like content fail closed.

## Audit and history

Decision evidence lives at:

```text
data/agent-handoff/patch-potential-guard/{runId}/
```

Mutation evidence remains owned by:

```text
data/agent-handoff/macsrc-smb-direct/{runId}/
```

or by one manifest-pinned PatchDrop v3 bundle. The decision run references the mutation run ID and hashes; it does not duplicate the mutation protocol.

`{runId}` denotes the runtime-generated, redacted run identifier defined by the existing guard contracts; it is not an unresolved design field.

Required decision artifacts:

```text
run.intent.json
evidence.snapshot.json
positive.packet.json
negative.packet.json
neutral.decision.json
candidate.ranking.json
autopatch.eligibility.json
mutation.reference.json
verification.summary.json
run.final.json
```

Each JSON has a SHA-256 sidecar. Writers use a temporary name in the same directory, flush, re-read, verify the hash, rename to the final name, and publish `run.final.ready` last.

Without `run.final.ready`, a run is incomplete and excluded from history aggregation.

History uses immutable per-run directories. A mutable shared JSONL file or central index is not a source of truth. Aggregate reports are rebuilt from valid ready runs.

Allowed stored values include hashes, relative paths, counts, durations, score components, allowlisted evidence IDs, reason codes, test summaries, and mutation-session references. Raw prompts, raw queries, full responses, provider errors, credentials, authorization data, cookies, private environment values, and full logs are prohibited.

## Concurrency and resource bounds

- Read-only candidate discovery may run concurrently.
- Positive and Negative may run independently against the same frozen snapshot.
- Neutral waits for both ready packet files.
- Only one mutation owner may hold the repository source lease.
- Candidate evaluations per run: at most 64.
- Source mutations per run: exactly 0 or 1.
- Declared target files: at most 8.
- Review packet size: at most 256 KiB each.
- EvidenceSnapshot size: at most 1 MiB.
- Patch diff size: at most 256 KiB.
- Reviewer timeout: at most 600 seconds per query.
- Total run time: at most 90 minutes.

Exceeding a bound yields `resource-bound-exceeded`; the guard does not truncate the candidate set or patch and continue.

## Failure taxonomy

Representative exact reason codes:

```text
autopatch-disabled
snapshot-hash-mismatch
packet-set-invalid
order-instability
goal-score-invalid
goal-score-mismatch
goal-score-below-autopatch-threshold
candidate-evidence-unresolved
candidate-selection-ambiguous
wrong-sourceset
call-path-unproven
target-set-invalid
red-not-reproduced
index-lock-present
worktree-overlap
source-lease-conflict
changed-preimage
undeclared-source-write
public-api-change-forbidden
secret-leak-risk
deletion-reference-present
deletion-compatibility-unproven
resource-bound-exceeded
focused-verification-failed
postimage-mismatch
rollback-hash-mismatch
artifact-hash-mismatch
artifact-publication-nonatomic
desktop-proof-missing
```

The grader emits one primary failure class and one smallest next action. It does not retry solely to obtain a more favorable verdict.

## File plan

### Add

- `docs/superpowers/specs/2026-07-31-patch-potential-guard-design.md`
- `.agents/skills/demo1-patch-potential-guard/SKILL.md`
- `.agents/skills/demo1-patch-potential-guard/references/eligibility-contract.md`
- `.agents/skills/demo1-patch-potential-guard/references/audit-contract.md`
- `scripts/score_patch_potential_candidate.py`
- `scripts/test_patch_potential_candidate.py`
- `scripts/demo1_patch_potential_guard_contract_tests.ps1`

### Modify

- `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md`

### Exclude

- `main/**`
- `app/**`
- `src/test/**`
- database and DDL files
- public API and DTO files
- credentials and environment secrets
- provider and runtime configuration
- archives, backups, inactive mirrors, generated output, and PatchDrop payloads

## Test-driven implementation

### Skill RED

Before writing the skill, run pressure scenarios without it and record whether the agent:

- treats a high score as mutation authority;
- uses majority voting;
- patches two candidates in one run;
- patches without a reproduced RED;
- deletes a compatibility alias from filename or duplicate-name evidence alone;
- retries a failed patch inside the same run.

The baseline must exhibit at least one target failure. If it does not, the proposed skill guidance has no demonstrated gap and must be reconsidered.

### Grader RED

Write focused tests before the grader exists. Required cases include:

- score 99 with an unproven sourceSet remains HOLD;
- score 92 with an unproven call path remains HOLD;
- score 88 with an unresolved negative falsifier remains HOLD;
- same-family positive paraphrases do not increase score;
- candidate-owned evidence registry or threshold is rejected;
- input reordering selects the same candidate;
- a tie uses the locked deterministic order;
- a second mutation candidate is rejected;
- a Spring or reflection reference blocks deletion;
- a proven canonical replacement with no references can become deletion-eligible;
- changed preimage prevents all source writes;
- GREEN failure restores the exact preimage;
- failed rollback produces `ROLLBACK_REQUIRED` and retains the lease;
- secret-like content prevents publication;
- deterministic replay emits byte-identical canonical JSON.

### GREEN

Implement only the schema validation, GoalScore adapter, hard gates, deterministic selector, deletion eligibility, and bounded canonical output required by the failing tests.

Then write the minimal skill guidance that closes the observed pressure-scenario failures and rerun the same scenarios with the skill loaded.

### Regression verification

```powershell
python scripts\test_patch_potential_candidate.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_patch_potential_guard_contract_tests.ps1
python scripts\test_source_health_scorecard.py
python scripts\test_three_way_long_tail_design_autograder.py
python scripts\test_three_way_long_tail_autograder.py
python scripts\test_three_perspective_chat_postprocess.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_goal_score_contract_tests.ps1
```

Guard integration tests use temporary synthetic source trees. They must perform zero real application-source mutations.

## Release gate

The guard may be installed with `autopatchEnabled=false` only when:

```text
newGraderTests=PASS
newContractTests=PASS
existingFocusedTests=176/176 PASS
deterministicReplayByteIdentical=true
adversarialFalseAcceptCount=0
canonicalFalseRejectCount=0
secretPatternHitCount=0
realApplicationSourceMutationDuringTests=0
rollbackFixture=PASS
```

Enabling `AUTO_SINGLE_CANDIDATE` additionally requires a Desktop-owned synthetic or dry-run proof of the real mutation dispatcher and existing lease/CAS guard. Design or artifact success does not promote `runtimeLineageVerdict` or `desktopFinalProof`.

## Rollback and removal

Guard implementation rollback consists of:

- removing the new skill directory;
- removing the new eligibility grader and its focused tests;
- restoring the scanner prompt to its recorded preimage;
- deleting only incomplete decision artifacts created by test fixtures;
- rerunning the existing 176-test focused baseline.

No application source, DB, credential, provider, deployment, or external state rollback is required for guard implementation because those surfaces are excluded.

A real guarded source-patch rollback remains owned entirely by the selected repository mutation guard.

## GoalContract

```text
goalId: patch-potential-guard-v1
rewrittenUserIntent: automatically apply exactly one highest-potential patch only after three-query adjudication, deterministic scoring, and every safety gate passes
desiredOutcome: a reusable, auditable, fail-closed pre-patch guard with safe legacy-removal eligibility
measurableSuccess: one candidate maximum; score threshold 85; zero hard-gate bypasses; deterministic replay; exact rollback; immutable ready-last audit history
nonGoals: majority vote, batch mutation, automatic retry, duplicate mutation protocol, runtime-success self-claims
authorizedMutationSurface: repo-local skill, references, deterministic tooling, focused tests, scanner prompt, and this design document
prohibitedSurface: application source during guard implementation; public API, DB, credentials, provider/runtime configuration, deployment, archives, backups, inactive mirrors
evidenceBaseline: current filesystem evidence and 176 passing focused tests
assumptions: existing three-query, GoalScore, source-health, mutation-guard, and postprocessor contracts remain available
constraints: Desktop final ownership; no global Git trust change; one mutation per frozen snapshot; ready-last immutable artifacts
verificationOwner: Notebook for tooling evidence; Desktop for dispatcher, source, and runtime final proof
verificationCommands: focused commands in Test-driven implementation and Regression verification
rollback: remove new guard/tool files, restore scanner preimage, rerun the 176-test baseline
stopConditions: any hard gate, changed preimage, lease conflict, failed RED/GREEN, secret hit, non-atomic publication, rollback mismatch
timeBudgetMinutes: 180
goalScore: 59.0 for the auto-mutation design objective; runtime candidate scores are computed independently
verdict: APPLY for design and tooling plan; source execution remains evidence-gated
evidence_needed: Desktop-owned synthetic dispatcher proof before autopatchEnabled=true; per-run branch, dirty ownership, lease, preimage, RED, GREEN, and postimage proof
```

## SourceDirective

```text
directiveId: patch-potential-guard-v1
sourceOwner: desktop
provenRoot: Y:\ mapped to \\DESKTOP-M5NOV6K\MacSrc
provenBranch: evidence_needed because Git metadata is blocked by dubious ownership
activeSourceSets: root main/java and main/resources; app app/src/main/java_clean and app/src/main/resources
targetFiles: only the Add and Modify paths in File plan for guard implementation
callPathOrBoundary: source-health -> three-query review -> eligibility grader -> existing mutation guard -> existing postprocessor
beforeBehavior: simple patch-candidate scoring can produce directives without one unified automatic-mutation eligibility boundary
afterBehavior: one deterministic candidate may be delegated for guarded mutation only after score >= 85 and every hard gate passes
excludedFilesAndMirrors: main/**, app/**, src/test/**, DB/DDL, public API/DTO, credentials, provider/runtime configuration, archives, backups, inactive mirrors, generated output, PatchDrop payloads
publicApiChange: forbidden
secretMutation: forbidden
redTest: required pressure scenarios and grader cases under Test-driven implementation
greenTest: minimal grader and skill satisfy RED cases while all 176 focused baseline tests remain green
exactVerificationCommands: commands under Regression verification
expectedEvidence: canonical grader JSON, deterministic replay hash, test counts, secret hit count, temp-fixture rollback proof, zero real source mutations
failureClassifications: exact tokens under Failure taxonomy
rollback: remove new files, restore scanner preimage, rerun baseline; real source rollback remains mutation-guard-owned
patchdropContract: not used for guard implementation; any later isolated source patch must use one manifest-pinned cumulative v3 bundle
desktopFinalProof=evidence_needed
```

## Success boundary

A completed guard implementation proves that candidate selection and automatic-mutation eligibility are deterministic, bounded, auditable, and fail closed in synthetic tests. It does not prove that any application patch is beneficial, that a legacy file is removable, that a provider or runtime improved, or that Desktop source verification passed. Every real candidate must still supply its own frozen evidence, RED, lease, preimage, GREEN, postimage, rollback, and Desktop final proof.

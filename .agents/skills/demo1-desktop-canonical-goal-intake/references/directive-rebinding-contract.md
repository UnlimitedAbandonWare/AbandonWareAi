# Directive Rebinding Contract

Use this contract after `demo1-desktop-canonical-goal-intake` receives an exact
implementation SourceDirective or a Notebook/SMB directive. Its fixed Desktop root is
`C:\AbandonWare\demo-1\demo-1\src`; no fallback write root exists.

## Target Routing

Accept each declared target in one form only:

| Input form | Accepted value | Result |
| --- | --- | --- |
| Repo-relative | `main/java/...` beneath the fixed root | Normalize to `targetRel`. |
| Exact Y descendant | `Y:\main\java\...` | Normalize lexically; it remains supporting-only until live C validation. |
| Exact fixed C descendant | `C:\AbandonWare\demo-1\demo-1\src\main\java\...` | Normalize and live-validate beneath the fixed root. |

Reject an empty/root-only target, UNC path, foreign drive, alternate C root,
parent traversal, ADS, or any path outside the fixed root. Invoke the resolver
once for each target before it can enter later C-side mutation gates:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  .\.agents\skills\demo1-desktop-canonical-goal-intake\scripts\resolve_desktop_directive_target.ps1 `
  -DirectivePath <declared-target>
```

Y/C absolute paths derive `targetRel` by normalized root-relative calculation,
not prefix replacement. Relative inputs pass the same segment and containment
checks. Reprocessing the fixed-C candidate must produce the same `targetRel`.

Raw resolver output is allowlisted to `status`, `reason`,
`canonicalExecutionRoot`, `targetRel`, `desktopTarget`, `withinRoot`, `exists`,
and `reparseRisk`. Require `status=PASS` and `reason=match`. Resolver output is
read-only and non-authorizing even on PASS; the higher-level intake owns
`routingVerified` and invariant `authorizedMutation=false`. A `-LexicalOnly`
result with reason `lexical-only` is a non-authorizing HOLD routing state, not
a routing failure or write gate. Fresh C revision, active sourceSet,
target preimage, and `sourceOwner=desktop` proof remain independent gates.
`directive-target-empty` and `directive-target-missing` are resolver-specific
HOLD routing labels for an empty/root-only declaration and an absent C leaf.

## SourceDirective Rewrite

| Notebook input | Desktop result |
| --- | --- |
| `provenRoot: Y:\` | `originEvidenceRoot: Y:\`; `provenRoot: evidence_needed` |
| Y absolute target | Validated `targetRel` plus the fixed-C candidate |
| Notebook branch, revision, or hash | Supporting evidence only |
| Notebook verification command | Regenerate it for the fixed C root and a Desktop-local cache |
| Notebook final proof | `desktopFinalProof: evidence_needed` |

## Intake Output

Emit this complete shape; retain `evidence_needed` instead of inferring an
unobserved value.

```yaml
status: COMPLETE | HOLD | EVIDENCE_NEEDED
contractVersion: demo1.c-canonical-goal-intake.v1
originEvidenceRoot: Y:\ | null # null when no Notebook-origin evidence exists
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
readRoot: C:\AbandonWare\demo-1\demo-1\src
writeRoot: C:\AbandonWare\demo-1\demo-1\src
finalProofRoot: C:\AbandonWare\demo-1\demo-1\src
yEvidenceAuthority: supporting_only
fallbackWriteRoot: null
primaryLane: read_only | artifact_only | application_source | patchdrop_consumer
sourceOwner: desktop
provenRoot: evidence_needed | C:\AbandonWare\demo-1\demo-1\src
cRoot:
  available: true | false | evidence_needed
  gitTopLevelMatch: true | false | evidence_needed
  localNonUnc: true | false | evidence_needed
activeSourceSets: <list-or-evidence_needed>
gitState:
  branch: <name-or-evidence_needed>
  indexLockPresent: true | false | evidence_needed
  dirtyTargetOverlap: true | false | evidence_needed
  topLevelPatchCount: <integer-or-evidence_needed>
sourceLease:
  state: clear | foreign_active | acquired | not_applicable | evidence_needed
targets: []
preimageState: verified | changed | not_applicable | evidence_needed
directiveRebinding:
  schemaVersion: demo1.desktop-directive-target.v1
  status: PASS | HOLD
  reason: <allowlisted-reason>
  routingVerified: true | false
  authorizedMutation: false
  targetCount: <integer>
  targets:
    - targetRel: <repo-relative-path>
      desktopTarget: <fixed-C-root-candidate>
      withinRoot: true | false
      exists: true | false
      reparseRisk: true | false
verification:
  cwd: C:\AbandonWare\demo-1\demo-1\src
  result: PASS | FAIL | NOT_RUN
  commands: []
externalLanes:
  browser: required | supporting | not_run | blocked
  computer: required | supporting | not_run | blocked
  supabase: required | supporting | not_run | blocked
desktopFinalProof: evidence_needed | verified
evidence_needed: <null-or-one-exact-proof>
```

Notebook claims are supporting-only. Set `provenRoot=evidence_needed` until
the literal C root, revision, active sourceSet, and target preimage are all
live-proved. After live C proof, promote only `provenRoot` to the fixed C root;
never promote another absolute root or infer Y/C identity. Run C-root
verification with cwd `C:\AbandonWare\demo-1\demo-1\src`; keep
`desktopFinalProof=evidence_needed` until every required acceptance proof is
PASS for the current C postimage. Only then set `desktopFinalProof=verified`;
a postimage hash or focused GREEN alone is insufficient.

## Two-Stage Contract

Apply stages only when the incoming SourceDirective declares them:

```text
Stage 1: fresh C EvidenceSnapshot -> exact three-query preflight with stable APPLY -> freeze declared production target and exactly two declared RED tests -> existing lease -> immediate C preimage recheck -> minimal patch -> focused GREEN -> postimage, changed-path subset, and secret count -> release lease
Stage 2: only after Stage 1 GREEN and release -> same causal call path and active sourceSet candidate -> independent RED -> fresh C EvidenceSnapshot and exact three-query preflight with stable APPLY -> new lease -> immediate C preimage recheck -> production candidate maximum 1 -> Stage 1 regression + Stage 2 GREEN -> postimage, changed-path subset, and secret count -> release
```

Stage 2 must release and reacquire the existing source-owner lease; it cannot
reuse Stage 1 evidence or a lease. Formatting/general cleanup is not a clean
candidate. When none qualifies, report `no_additional_candidate` without a
write. Route application-source work to `demo1-source-edit-three-way-preflight`;
the repository-owned source-edit guard owns lease, immediate preimage, rollback,
and changed-path enforcement. Route final C proof to
`demo1-desktop-only-proof-loop`, skill-family validation to
`demo1-skill-family-postprocessor`, and optional external evidence to
`demo1-demand-driven-external-proof`. This contract creates no second lease,
CAS, PatchDrop, rollback protocol, or new verification framework.

## Debugging Continuation

This is the debugging decision detail for the same authorized directive and
existing source session. Reuse the first-failing-layer and two evidence slots
from `demo1-debugging-with-two-tools`; do not add a second preflight or review.

### Reproduce And Falsify

1. Freeze one redacted symptom and the earliest failing boundary. Record 2-4
   alternative causes with a disproof condition, then activate exactly one.
   Read the relevant caller/callee and a working sibling only as needed.
2. Run the smallest declared reproducer. A runtime-behavior RED requires the
   intended test to execute and its expected assertion to fail. Record
   `testExecuted` and `expectedFailureObserved`. Compile, test discovery,
   context startup, missing credential, or harness timeout failures classify
   their own boundary; they do not prove the downstream runtime defect.
   A compile failure may be RED for an explicitly scoped compile defect.
3. With no semantic RED, use at most one decision-changing diagnostic within
   the existing budget. If still unreproduced, record `not_reproduced` and the
   next exact proof. Do not invent RED, weaken assertions, or mark it fixed.
   A proven existing fix follows the proof-only resume row below.
4. After the existing source preflight admits stable APPLY, use its lease,
   immediate preimage and minimal patch ordering. Change one causal variable;
   run the same reproducer for GREEN and the directive's required regressions.
   A changed failure class closes this cycle before selecting a new hypothesis.
   New targets need a fresh scope/owner decision within existing authorization.

### Time And Retry Bounds

The dispatch heartbeat has its own two-minute check limit. Desktop execution
uses a valid positive `timeBudgetMinutes` from the selected directive, capped
at 540; an omitted budget defaults to 90 minutes. An explicit invalid budget
holds that packet. A stricter user limit wins. These are maxima, not work quotas.

Record `executionStartedAtUtc` when actual Desktop investigation begins, then
`executionDeadlineUtc`, `elapsedMinutes`, and `remainingMinutes` in the existing
session report. Inbox/connection waiting before execution consumes no Desktop
budget. Resumes, changed hypotheses, and heartbeat notifications retain that
deadline; they never refill it. If an interrupted run's timing is unknown,
recover the existing receipt once, otherwise hold continued execution as
`execution-budget-unproven`. A new budget requires a new explicit user instruction.

Before each command set a timeout no greater than the remaining budget, using
the existing runner's cancellation facility. Keep task-started process handles
and clean up only those processes on timeout. If bounded cancellation cannot
be established, record `budgetEnforcement=evidence_needed`; a prompt timer or
a remote heartbeat is not proof of a hard process limit. At expiry start no
new probe or patch; perform only the existing session's required safe cleanup
and conditional rollback, then report `time-budget-exhausted`.

Reuse `failureSignature` from the failure class, test/target identifier,
preimage identity, and allowlisted effective configuration. Store only these
identifiers/hashes, counts and timings. If the same signature recurs with no
new evidence, stop that failed operation immediately. Permit at most three
hypothesis cycles per logical directive, and fewer when the deadline or
directive requires it; each further cycle needs new evidence and a specific
disproof. No fourth speculative fix, automatic target expansion, paid probe,
or repeated broad scan is authorized. Leave the next single proof and
`retrySuppressedReason` in the same report.

### Resume And Completion Decisions

| Current evidence | Next action and completion |
| --- | --- |
| ACK or receipt only | Continue to current Desktop root/source gates; patch and final proof remain `evidence_needed`. |
| Test did not reach the expected assertion | Classify the earliest failing boundary; downstream semantic RED remains unproven. |
| Same failure signature, no new evidence | Stop that operation; keep an exact next proof and resume condition. |
| Expected failure remains unreproduced | Record `not_reproduced`; no speculative patch or completion claim. |
| Current source already correct; required proof missing | Verify current postimage and applicable prior evidence, then run only missing or invalidated acceptance checks. Do not replay the patch. |
| Focused GREEN; required build/runtime proof missing | Record the verified patch separately; `desktopFinalProof=evidence_needed` and the directive remains incomplete. |
| Patch causes regression | Use existing guard rollback only while current postimage still matches this task; on other-writer changes hold rollback and preserve their work. |
| Every required proof passes on the current C postimage | Mark verified/already-complete and stop. Optional unsupported external proof remains explicitly optional. |

Record a compact `debugging` section in the existing source report:
`failureLayer`, `failureClass`, `failureSignature`, `hypothesis`,
`disproof`, `testExecuted`, `expectedFailureObserved`, `cycleCount`,
`retrySuppressedReason`, the execution timing fields, `budgetEnforcement`,
`nextSingleProof`, and `resumeCondition`.
For each acceptance check retain its command, required/optional flag,
PASS/FAIL/NOT_RUN, expected versus observed result, exit code, duration,
and evidence path. Only executed results can be PASS.
Link `sourcePatchCompletion`, `desktopFinalProof` and rollback evidence to
the current postimage; do not infer runtime/provider lineage from a mock,
hash, UI response or build. Preserve any explicitly required lineage evidence
and the existing `runtimeLineageVerdict`.

Example: if a Responses token-cap test exits during `compileJava`, record
`failureLayer=build`, `testExecuted=false`, and
`expectedFailureObserved=false`. Resolve or classify that build blocker
within its scope; do not patch generation because the test command was red.

## Security And Authority Boundaries

Supabase remains read-only and may run only when project scope and a
decision-changing need are proven. This artifact authorizes no DB or public API changes;
no credential, secret, or environment-name changes; and no exposure of
raw keys, tokens, cookies, authorization headers, or full environment dumps. It
authorizes no commit, push, deploy, or external message. Rollback may restore
only a stage's per-stage verified preimage; no reset hard or broad checkout is
authorized.

## Failure Labels

Use the smallest applicable label and one next proof:

`c-canonical-unavailable`, `directive-target-empty`,
`directive-path-not-y-rooted`, `directive-path-escape`,
`directive-path-unsupported`, `directive-target-missing`,
`reparse-traversal-risk`, `revision-mismatch`, `active-sourceset-uncertain`,
`dirty-target-overlap`, `index-lock-conflict`, `source-lease-conflict`,
`preimage-changed`, `patch-drop-pending`, `stage-one-not-green`,
`stage-two-red-missing`, `verification-unproven`.

Use `directive-path-not-y-rooted` for a drive-qualified absolute path that is
neither an exact Y descendant nor a descendant of the fixed C root. Use
`directive-path-unsupported` for UNC, ADS, malformed drive, rooted-relative,
or mixed-drive syntax. Unexpected validator failures become
`verification-unproven`, not a fabricated path classification.

## Quick Reference

| Decision | Required evidence | Result if absent |
| --- | --- | --- |
| Route target | Resolver PASS/match | HOLD with routing label. |
| Prove source | Fresh C revision, active sourceSet, preimage, Desktop owner | HOLD with the smallest missing-proof label. |
| Enter Stage 2 | Stage 1 GREEN plus released lease and independent RED | `stage-one-not-green` or `stage-two-red-missing`. |
| Claim completion | Fresh Desktop verification from the C cwd | `verification-unproven`. |

## Common Mistakes

After the existing workflow verifies the whole directive, call
`$demo1-completed-directive-cleanup` with the exact read hash and final evidence.
Its deletion journal is the cleanup outcome; the source session remains the
completion authority. Failed cleanup resumes cleanup only. A stale pointer to
a verified deleted instruction must not start the source patch again. A passed
subset or outstanding required runtime proof retains the instruction.

- Treating a lexical route, Y hash, or Notebook preimage as C mutation proof.
- Promoting a target before resolver PASS/match or treating PASS as write authority.
- Retaining a Stage 1 lease or evidence for Stage 2.
- Adding more than one Stage 2 production candidate or calling formatting a candidate.
- Treating Browser, Computer, or Supabase evidence as final proof; Supabase is read-only supporting evidence.
- Performing DB mutation, commit, push, or deployment from this intake contract.

## Example

The following targets are examples only: Stage 1 changes the confirmed causal
path through `AnswerQualityEvaluator`, while an optional Stage 2 candidate is
limited to the same causal call path in `EvidenceRepairHandler`.

1. Resolve the declared Y or relative paths. Require live resolver
   PASS/match, then take a fresh C EvidenceSnapshot and exact three-query
   preflight. After stable APPLY, freeze the production target and exactly two
   declared RED tests for `AnswerQualityEvaluator`. Acquire the existing
   Desktop lease, immediately recheck the C preimage, make the minimal Stage 1
   patch, and run focused GREEN with isolated Gradle state, for example
   `gradlew.bat --project-cache-dir <host-local-cache> test --tests <declared-stage-one-test>`.
   Record postimage, changed-path subset, and count-only secret evidence, then
   release the lease.
2. Only if Stage 1 is GREEN and an independent RED test proves a remaining
   same-call-path production candidate in `EvidenceRepairHandler`, take a fresh
   EvidenceSnapshot/preflight and reacquire a new lease. Patch at most that one
   active-sourceSet candidate after an immediate C preimage recheck. Run the
   Stage 1 regression and isolated Stage 2 GREEN, record the postimage,
   changed-path subset, and secret count, and release the lease. A read-only Supabase probe may
   be reported separately as supporting evidence; it does not replace either
   RED/GREEN result. DB mutation, commit, push, and deployment are prohibited.

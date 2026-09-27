# Patch Potential Guard Existing-Asset Extension Design

Date: 2026-07-31  
Status: awaiting written-spec approval  
Request class: `prompt_skill_tooling_only`

## Decision

Extend the existing source-edit preflight, scanner prompt, and preflight contract
test. Do not create `demo1-patch-potential-guard`, a second mutation protocol, or
a second scoring implementation.

The extension owner is:

```text
.agents/skills/demo1-source-edit-three-way-preflight/
```

The existing deterministic grader remains unchanged unless its focused RED
tests later prove an implementation defect:

```text
scripts/score_patch_potential_candidate.py
scripts/test_patch_potential_candidate.py
```

## EvidenceSnapshot

The three evaluations below share this one snapshot.

| ID | Observation |
| --- | --- |
| E01 | `canonicalWorkspace=Y:\`, `backingShareIdentityVerified=true`, `backingShareIdentityReason=match` |
| E02 | Notebook host, Windows, branch read from `.git/HEAD` is `main`, `indexLockPresent=false` |
| E03 | Existing preflight skill exists; SHA-256 `1DBF09D038766DD91950060257566E17A706C2135C65914D2C75B3E1C5A4A7D9` |
| E04 | Existing preflight contract exists; SHA-256 `023B4D5B0185BDD7F37D3DCDA3825B3B125589322D30FE3A61876C08B5CF411D` |
| E05 | Existing preflight contract suite passes `73/73` |
| E06 | Existing grader exists; SHA-256 `B81C58048CCAA0FCEACA2722E780049E9E7FA31358CC214BAF30A63F1997DAC1` |
| E07 | Existing grader suite passes `31/31` and deterministic replay is covered |
| E08 | Existing scanner prompt exists; SHA-256 `705233DFC1D594FE6C972FDCCAA1670268648AC2C2F6A3E73BC77B0EDE62CD17` |
| E09 | Preflight skill/reference contain zero grader, threshold-85, immutable-run, or `run.final.ready` bridge markers |
| E10 | Scanner contains zero normalized scan-schema markers and eight hard-coded Desktop-root occurrences |
| E11 | Two earlier fresh-context campaigns produced zero safety violations, so a new discipline skill has no reproduced RED |
| E12 | Existing source-owner guard provides `Prepare`, `Verify`, `Complete`, and `Abort`; existing postprocessor provides `COMPLETE`, `HOLD`, and `ROLLBACK_REQUIRED` |
| E13 | Git status/dirty-target ownership is `evidence_needed` because global Git trust must not be changed |
| E14 | Desktop dispatcher/runtime final proof is absent and remains `desktopFinalProof=evidence_needed` |

No raw backing-share path, credential value, prompt, query, or external payload
is part of this snapshot.

## Exactly Three Evaluation Packets

### POSITIVE_QUERY

```text
candidateGoal:
  Extend the existing three-way preflight so it owns candidate grading and
  immutable decision-history routing, while the scanner remains read-only and
  the existing mutation guard remains the only writer authority.

validatedAssumptions:
  - the automatic source-edit hook already routes to the preflight skill
  - the three logical roles and fixed GoalScore formula already exist
  - the deterministic grader already validates packet hashes, sealed evidence,
    static gates, deletion gates, score threshold, and replay stability
  - existing mutation and postprocess owners already expose the needed boundary

reusableAssets:
  - demo1-source-edit-three-way-preflight
  - preflight-contract.md
  - demo1_source_edit_three_way_preflight_contract_tests.ps1
  - score_patch_potential_candidate.py and its 31-test suite
  - demo1_orch_patch_scanner/system_ko.md
  - demo1-macsrc-smb-direct-patch
  - demo1-macsrc-patch-postprocessor

expectedUserValue:
  One discoverable guard path, mechanical scoring, safe legacy-deletion
  eligibility, and history outside conversational memory.

minimalVerification:
  Add bridge assertions to the existing contract suite, observe RED, make the
  smallest documentation/prompt changes, then observe GREEN plus unchanged
  grader and owner-guard suites.

evidenceIds: [E03,E04,E05,E06,E07,E08,E11,E12]
unknowns: [Desktop dispatcher dry-run proof, current dirty-target ownership]
```

### NEGATIVE_QUERY

```text
challengedGoal:
  The extension is invalid if it creates a second authority, treats a score as
  permission to write, or documents a contract different from live tooling.

falsifiers:
  - the extended preflight cannot invoke the existing grader with its live CLI
  - the scanner still emits a competing mutation score or hard-coded owner root
  - any new lease, compare-and-swap, apply, retry, or rollback implementation is added
  - the reference claims output fields that the live grader does not emit
  - an incomplete run is accepted without ready-last evidence

counterExamples:
  - `goalScore >= 85` with an unproven active sourceSet must still HOLD
  - `autopatchEligible=true` without `Verify authorized=true` must not write
  - an inactive legacy-looking file must be reported, not automatically deleted
  - a failed GREEN must roll back and terminate the run, not retry in-place

authorityRisks:
  - a matching backing identity does not authorize application-source mutation
  - Notebook evidence does not replace Desktop final proof
  - this tooling approval does not set `autopatchEnabled=true` for a real patch

safetyRisks:
  - raw prompts or paths leaking into immutable history
  - score thresholds diverging between prose and the live grader
  - scanner candidate discovery being mistaken for ownership or deletion proof

missingEvidence:
  - trusted Desktop dispatcher dry-run
  - current target-file dirty ownership at any future source-edit run

smallestDisconfirmingProbe:
  Extend the existing preflight contract test with the exact bridge markers and
  run it before changing the skill or scanner; it must fail for the missing bridge.

evidenceIds: [E09,E10,E12,E13,E14]
```

### NEUTRAL_QUERY

```text
forwardOrder: [POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder: [NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict: APPLY
reverseVerdict: APPLY
forwardDecisiveEvidenceIds: [E03,E05,E06,E07,E09,E10,E11,E12]
reverseDecisiveEvidenceIds: [E03,E05,E06,E07,E09,E10,E11,E12]
orderStable: true
verdict: APPLY
selectedOrRewrittenGoal:
  Extend the existing preflight, its reference, its contract test, and the
  read-only scanner prompt; reuse the grader and mutation/postprocess owners unchanged.
goalScore: 92.25
decisiveEvidence: existing orchestration and grader are proven; only their
  discoverable bridge, scanner normalization, and audit contract are absent
rejectedClaims:
  - a new skill is required
  - majority agreement authorizes mutation
  - score or eligibility authorizes a source write
  - this Notebook verification is Desktop final proof
nextSingleProof:
  Run the extended preflight contract suite before implementation and observe
  RED only for the newly required bridge/scanner/audit assertions.
confidence: H
```

GoalScore components, each bound to E03-E14 as applicable:

```text
evidenceStrength=1.00
causalStrength=0.90
verificationFeasibility=1.00
userValue=0.90
reversibility=1.00
costEfficiency=0.95
timeFit=0.95
blastRadius=0.10
ambiguity=0.10
authorityOrSafetyExpansion=0.00
computedGoalScore=92.25
```

## Considered Approaches

### A. Extend the existing preflight and its current test — selected

The automatic hook already selects this skill for application-source mutation.
Adding the grader and audit bridge here gives all source edits one pre-mutation
decision owner. It also preserves the current mutation guard and postprocessor.

### B. Extend only the scanner prompt — rejected

This is smaller, but the scanner is Desktop-only candidate discovery. It does
not cover every source-edit request and must not become an authorization owner.

### C. Extend the autonomous patch conductor — rejected

This covers only bounded long-running Desktop sessions. Making it the guard
would leave ordinary edits uncovered and mix backlog iteration with eligibility.

## Architecture and Ownership

```text
source-edit intent
  -> existing bounded hook
  -> demo1-source-edit-three-way-preflight
       -> frozen EvidenceSnapshot
       -> exactly POSITIVE_QUERY / NEGATIVE_QUERY / NEUTRAL_QUERY
       -> existing score_patch_potential_candidate.py
       -> immutable decision run
       -> HOLD/REJECT: stop, no writer
       -> APPLY: enter existing source-owner guard
            -> Prepare -> Verify -> declared minimal patch -> Complete|Abort
            -> existing postprocessor
            -> Desktop final proof remains evidence_needed until observed

read-only scanner
  -> normalized candidates only
  -> no GoalScore, verdict, ownership, deletion eligibility, or mutation authority
```

Responsibility is intentionally non-overlapping:

| Owner | Responsibility | Must not do |
| --- | --- | --- |
| Scanner prompt | Discover evidence-backed candidates | Score, authorize, or mutate |
| Three-way preflight | Freeze evidence, construct three packets, invoke grader, route outcome | Lease, write source, or retry |
| Existing grader | Validate schemas/hashes/gates, compute score, select at most one candidate | Apply patches or create source authority |
| Existing source-owner guard | Lease, preimage verification, declared write, GREEN, rollback | Recompute eligibility or add a fourth reviewer |
| Existing postprocessor | Terminal evidence adjudication | Start another patch in the same run |

## Threshold Semantics

The live contracts have two deliberately different thresholds:

| Score | Meaning |
| ---: | --- |
| `< 50` | Three-way preflight HOLD/REJECT |
| `50` to `< 85` | GoalContract/SourceDirective may be retained; automatic selection HOLD |
| `>= 85` | Eligible for non-compensable hard-gate evaluation only |

`autopatchEligible=true` authorizes entry into the existing source-owner guard.
It is not application-source write authorization. A source write requires the
existing guard's immediately current `Verify` result with `authorized=true`.

`autopatchEnabled` remains false unless a later source-edit request explicitly
authorizes automatic single-candidate selection and the responsible execution
owner proves all required gates. This tooling extension does not enable it.

## Exact Extension Surface

Modify only:

```text
.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md
.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md
scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1
agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md
```

Reuse unchanged:

```text
.codex/hooks/source_edit_triage.ps1
.codex/hooks.json
.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml
scripts/score_patch_potential_candidate.py
scripts/test_patch_potential_candidate.py
.agents/skills/demo1-macsrc-smb-direct-patch/**
.agents/skills/demo1-macsrc-patch-postprocessor/**
```

Do not create:

```text
.agents/skills/demo1-patch-potential-guard/**
scripts/demo1_patch_potential_guard_contract_tests.ps1
any new lease, CAS, mutation, retry, rollback, dispatcher, daemon, or broker
```

Application source under `main/**`, `app/**`, and `src/test/**` is outside this
tooling extension.

## Preflight Skill and Reference Changes

The compact `SKILL.md` gains only a positive routing recipe:

1. create or reuse one immutable decision run root;
2. freeze sealed evidence and one lower-case SHA-256 snapshot hash;
3. produce exactly the three ready packet files with identical candidate sets;
4. invoke the existing grader with its live CLI;
5. interpret exit `0` as eligibility APPLY, exit `1` as a normal HOLD, and
   exit `2` or `3` as fail-closed tooling failure;
6. route only one selected candidate to the existing source-owner guard;
7. prohibit same-run automatic retry after failed GREEN;
8. publish the final ready marker only after terminal evidence is complete.

The heavy schema, threshold, audit, and failure details remain in
`references/preflight-contract.md`. The reference follows the live grader's
actual output shape rather than inventing fields:

```text
schemaVersion
verdict
failureClass
selectedCandidateId
autopatchEligible
deletionEligible
nextSingleAction
hardGates
hardGateCount
evaluatedCandidates[]
  candidateId
  computedGoalScore
  scoreComponents
  hardGates
  deletionEligible
deterministicReplayHash
```

## Scanner Normalization

Preserve useful read-only scans, but remove the 0-10 sum as a mutation threshold
and remove hard-coded Desktop roots. The prompt consumes a proven root supplied
by its execution owner and emits only:

```text
schemaVersion=awx.patch-potential.scan.v1
evidenceSnapshotHash
activeSourceSets
scannedFileCount
candidateCount
mutationAllowed=false
scoreIsMutationAuthority=false
candidates[]
  candidateId
  candidateKind=MODIFY|DELETE
  targetFiles[]
  problemEvidenceIds[]
  observedProblem
  proposedCausalMechanism
  redCommand
  greenCommands[]
  evidenceNeeded[]
```

The scanner does not emit GoalScore components, a verdict, source ownership,
deletion eligibility, or an execution mode.

## Audit and History

Decision evidence uses immutable per-run directories:

```text
data/agent-handoff/patch-potential-guard/{runId}/
```

Required decision artifacts remain:

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
run.final.ready
```

Each JSON has a SHA-256 sidecar. A writer uses a temporary name in the same
directory, flushes, re-reads, verifies the digest, renames to the final name,
and publishes `run.final.ready` last. A run without the final marker is excluded
from history aggregation.

Mutation evidence stays in the existing source-owner guard's trace directory or
one manifest-pinned PatchDrop v3 bundle. The decision run stores only the run ID
and hashes; it does not copy or replace the mutation protocol.

Stored values are limited to relative paths, hashes, counts, durations, score
components, allowlisted evidence IDs, redacted reason codes, and summarized test
results. Raw prompts, raw queries, full responses, full logs, provider payloads,
credentials, authorization data, cookies, private environment values, and the
backing-share path are forbidden.

## TDD and Verification Design

### RED

Extend `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`
before editing the skill or scanner. Assert:

- the skill/reference names the existing grader CLI and live schemas;
- `goalScoreThreshold=85.0` and the non-authority rule are present;
- immutable audit root and `run.final.ready` are present;
- exact source-owner guard and postprocessor delegation markers are present;
- the scanner emits the normalized read-only schema;
- the scanner has no hard-coded Desktop root;
- the grader has no source-mutation implementation tokens.

Expected RED: only these new assertions fail. The existing 73 checks remain
green.

The earlier 30 fresh-context safety controls found no unsafe behavior, so this
extension adds no redundant discipline skill or majority-vote rhetoric. The RED
is the reproducible structural and retrieval gap recorded in E09-E10.

### GREEN

Make the smallest changes to the two existing skill documents and scanner
prompt. Re-run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
python scripts\test_patch_potential_candidate.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_smb_direct_patch_contract_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\demo1_macsrc_patch_postprocessor_contract_tests.ps1
```

Then run the existing focused 176-test prompt/grader/GoalScore baseline from the
approved plan. All fixture mutations must remain under verified temporary roots;
real application-source mutation count must be zero.

### Falsifying Tests

The extension is false if any test can:

- select two candidates;
- pass at score below 85;
- compensate for a failed source or safety gate with a high score;
- treat scanner output or eligibility as source-write authority;
- accept packet candidate-set mismatch or order instability;
- delete a legacy candidate with remaining reflection/config/API references;
- enter same-run retry after failed GREEN;
- accept an incomplete decision history without `run.final.ready`;
- make the grader perform a source write.

## Rollback

Restore the four declared files to their recorded preimages and rerun the
preflight, grader, source-owner guard, and postprocessor contract suites. Do not
remove or alter the proven grader. Test-fixture artifacts may be deleted only
from their verified temporary roots. Real decision history is immutable and is
not rewritten during rollback.

## GoalContract

```text
GoalContract
- goalId: patch-potential-guard-existing-asset-extension-v1
- rewrittenUserIntent: reuse the existing three-way source-edit preflight and deterministic grader to rank high-potential changes, preserve counter-evidence, and route at most one candidate into the existing guarded mutation boundary
- desiredOutcome: one discoverable, auditable, fail-closed path without a new skill or mutation protocol
- measurableSuccess: exact three packets; score threshold 85 for eligibility; one selected candidate maximum; zero hard-gate bypasses; ready-last immutable history; zero real source writes during tooling verification
- nonGoals: majority vote; batch mutation; new skill; new scorer; new lease/CAS; same-run retry; runtime-success claim
- authorizedMutationSurface: four files under Exact Extension Surface
- prohibitedSurface: application source; public API; DB; credentials; provider/runtime config; deployment; hooks; existing grader; existing mutation/postprocess implementations
- evidenceBaseline: E01-E14
- assumptions: live grader and owner-guard contracts remain available and unchanged
- constraints: canonicalWorkspace=Y:\; backing identity match is not write authority; prompt_skill_tooling_only; Git trust unchanged; Desktop final ownership preserved
- verificationOwner: Notebook for tooling evidence; Desktop for any later dispatcher/source/runtime proof
- verificationCommands: commands under TDD and Verification Design
- rollback: restore four preimages and rerun focused suites
- stopConditions: changed preimage; dirty overlap on a declared tooling file; index lock; secret hit; test regression; contract/live-tool mismatch; any undeclared source write
- timeBudgetMinutes: 180
- goalScore: 92.25
- verdict: APPLY for written design and tooling implementation only
- evidence_needed: user written-spec approval; Desktop dispatcher dry-run before any real automatic source mutation; future run-specific dirty ownership and final proof
```

## SourceDirective

```text
SourceDirective
- directiveId: patch-potential-guard-existing-asset-extension-v1
- sourceOwner: notebook-local
- provenRoot: Y:\
- provenBranch: main from filesystem HEAD; Git status evidence_needed
- activeSourceSets: root main/java and main/resources; app app/src/main/java_clean and app/src/main/resources; all excluded from this tooling change
- targetFiles: the four files under Exact Extension Surface
- callPathOrBoundary: existing hook -> existing three-way preflight -> existing grader -> existing source-owner guard -> existing postprocessor
- beforeBehavior: preflight and grader are individually proven but not linked in the skill contract; scanner uses a separate 0-10 threshold and hard-coded Desktop paths
- afterBehavior: preflight invokes the deterministic eligibility grader, scanner emits candidate evidence only, and immutable decision history records the handoff without granting write authority
- excludedFilesAndMirrors: main/**; app/**; src/test/**; archives; backups; generated output; existing hook/grader/guard/postprocessor implementations
- publicApiChange: forbidden
- secretMutation: forbidden
- redTest: extended existing preflight contract test fails only on absent bridge/scanner/audit assertions
- greenTest: extended contract suite, 31 grader tests, mutation-owner suites, and focused 176-test baseline pass
- exactVerificationCommands: commands under TDD and Verification Design
- expectedEvidence: RED assertion names; GREEN counts; file pre/post hashes; count-only secret scan; zero real source mutations
- failureClassifications: changed-preimage; dirty-overlap; index-lock-present; contract-live-tool-mismatch; goal-score-below-autopatch-threshold; neutral-verdict-not-apply; secret-leak-risk; test-failure; undeclared-source-write
- rollback: restore four recorded preimages and rerun focused suites
- patchdropContract: not used for this tooling-only extension
- desktopFinalProof=evidence_needed
```

## Approval Gate

Implementation begins only after written approval of this exact spec. Approval
does not authorize application-source mutation, `autopatchEnabled=true`, commit,
push, deployment, DB mutation, credential changes, or external messages.

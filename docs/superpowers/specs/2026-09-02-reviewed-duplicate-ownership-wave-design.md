# Reviewed Duplicate-Ownership Admission And Wave-3 Design

**Date:** 2026-09-02
**Architecture direction:** User selected recommended option 1 on 2026-09-02
**Written-spec status:** Awaiting user review
**Parent objective:** Continue the unresolved approximately-1,000 structural-repair goal without touching the user-held `RagControlRuntimeAdapter` group or awarding unproven repair credit
**Predecessor design:** `docs/superpowers/specs/2026-09-01-structural-repair-multi-wave-registry-design.md`

## Objective

Extend the existing code-pinned multi-wave closure system so one explicitly reviewed `DUPLICATE_FQCN_SOURCE_COLLISION` predecessor may enter a new wave without globally reclassifying every duplicate-FQCN row as eligible.

The first reviewed duplicate-ownership wave selects only `com.example.lms.strategy.RetrievalOrderService` after a fresh quantitative audit and duplicate-owner report reproduce its exact root/app source pair. Wave 3 freezes the selected ledger row, an admission decision, normalized duplicate evidence, and the relevant owner, proposed-target, call-path, and focused-test preimages. It does not decide the later repair target, modify Java source, delete or move a file, append a closure event, or earn repair credit.

The design preserves all wave-1 and wave-2 bytes and history. `RagControlRuntimeAdapter` remains excluded and on user HOLD. The parent approximately-1,000-repair goal remains incomplete.

## Current Evidence

The current public quantitative bundle validates through both the production bundle validator and the source-health validator:

| Field | Current value |
|---|---:|
| Registered waves | 2 |
| Closure events | 14 |
| Rejected false-positive groups | 5 |
| Verified-closed groups | 9 |
| Public ledger rows | 84 |
| `OPEN / ELIGIBLE` groups | 1 |
| `REVIEW_REQUIRED / HOLD` rows | 69 |
| Target gap | 890 |

The live shared catch classifier was rerun from current source. It emits only `main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java`, with one broad catch and one catch without a local breadcrumb. The user has explicitly placed that group on HOLD. There is therefore no current non-RagControl `OPEN / ELIGIBLE / BROAD_CATCH_NO_BREADCRUMB` candidate.

The existing intake validator rejects every predecessor that is not all of:

```text
category=BROAD_CATCH_NO_BREADCRUMB
fixEligibility=ELIGIBLE
status=OPEN
```

It also rejects an empty predecessor list. A placeholder wave cannot be registered safely.

The current registry is canonical and code-pinned:

```text
path=verification/structural-repair-waves/registry.json
sha256=555bfed91df4888611380a8c9a7ed9f094fd87a661c20fcc5eee6be87844c87b
```

`build.gradle.kts` currently registers only wave-1 and wave-2 journals, fixed intake files, progress ledgers, and proof allowlists as inputs. A third descriptor therefore requires one reviewed registry-pin and Gradle-input transaction.

### Provisional candidate evidence

The current public ledger identifies the provisional candidate as:

| Field | Current value |
|---|---|
| Category | `DUPLICATE_FQCN_SOURCE_COLLISION` |
| Severity | `HIGH` |
| Eligibility | `REVIEW_REQUIRED` |
| Status | `HOLD` |
| Symbol | `com.example.lms.strategy.RetrievalOrderService` |
| Issue ID | `60439c21b79e44f77ca89568bd25978d75e982f9d936f747a92b9ddf11265761` |
| Root-cause group ID | `f1f0bd6a1d9d78dba77da91d501c9abc22604fc31ed617633e184bb391b6b456` |
| Evidence fingerprint | `362195329b43a3a8a7c06a9653a2bb982d1eb353bbe0cf386fce9cc288df381e` |
| Source-collision count | 1 |
| Generated-exclude count | 1 |
| Hard-exclude count | 0 |
| Packaged-active count | 0 |

The duplicate pair is:

```text
canonical owner:
  main/java/com/example/lms/strategy/RetrievalOrderService.java

excluded app duplicate and proposed repair target:
  app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java
```

The canonical root owner is Spring-managed and the active retrieval chain imports it. The `:app` copy is currently contained by the generated duplicate-FQCN exclusion. Packaging containment is not deletion authority and does not prove that the compatibility copy is disposable.

Current candidate bytes are provisional evidence only. The normative wave-3 identities and hashes must be derived again after the fresh Stage-A audit:

| Path | Current Git state | Current size | Current SHA-256 |
|---|---|---:|---|
| `main/java/com/example/lms/strategy/RetrievalOrderService.java` | untracked | 17,369 | `31e547598869c64ae197445acf07ad226eb37b6d799deacee1ef59cd545de3c8` |
| `app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java` | clean | 1,908 | `d203297314f69a3cbfee10a806f156015a3365341e2361fb5b4e63a0343ad26a` |
| `src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java` | modified | 19,423 | `0587e7347123f64bb6043efeacd84be71982b63c7cb9c1222eec0aebfdde6c94` |
| `src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java` | clean | 3,283 | `0e9e3f6daab572d599e967676eccb2922074a01c529e9aae5a2895441dd5f8dc` |

The detailed generated report `app/build/desktop/reports/dup-fqcn-evidence.json` is currently absent. It must be freshly generated before the candidate can become a normative intake row.

## Approved Architectural Choice

The design uses an explicit wave-local admission decision. It does not change the category-wide public ledger contract.

```text
fresh public audit and duplicate report
  -> exact REVIEW_REQUIRED / HOLD predecessor row
  -> canonical normalized duplicate evidence
  -> one canonical admission decision
  -> wave-3 intake v2
  -> unchanged v1 registry descriptor shape, new pinned registry bytes
  -> empty wave-3 progress and journal
  -> aggregate history with waveCount=3 and unchanged terminal credit
```

The public ledger row remains `REVIEW_REQUIRED / HOLD` until a later valid `VERIFIED_CLOSED` event exists. Admission to a wave is not public eligibility, repair completion, or repair credit.

## Scope

### In scope

- A fresh public structural baseline, ledger, and metrics transaction produced only by `gradlew.bat dynamicRagQuantAudit`.
- Fresh detailed duplicate-FQCN evidence produced by the existing `:app:generateDupFqcnExcludes` dependency.
- One new intake schema version for explicit reviewed admission.
- One canonical admission-decision file and one canonical normalized duplicate-evidence file.
- Exact one-group admission for the freshly reproduced `RetrievalOrderService` ledger row.
- A wave-3 proof root, intake, zero-byte progress ledger, and zero-byte journal.
- A third registry descriptor using the existing descriptor field set.
- A new code pin for the exact three-wave registry bytes.
- Gradle inputs for wave-3 fixed files, journal, progress, admission files, and allowlisted proofs.
- Versioned progress and closure-event support that can bind the historical ledger owner path to one later, independently admitted in-place repair target.
- V1 parity for waves 1 and 2.
- Production and source-health reconstruction of the same three-wave aggregate.
- Determinism, canonical-byte, path-containment, reparse, ownership, rollback, compile, duplicate-owner, and secret gates.

### Out of scope

- Globally changing `DUPLICATE_FQCN_SOURCE_COLLISION` rows from `REVIEW_REQUIRED / HOLD` to `ELIGIBLE / OPEN`.
- Admitting more than one reviewed predecessor to wave 3.
- Admitting `RagControlRuntimeAdapter` or changing its source, test, status, history, or contract.
- Modifying, deleting, renaming, or moving either `RetrievalOrderService` source file.
- Modifying either focused Java test during the infrastructure and intake-freeze transaction.
- Choosing or authorizing the later repair target or in-place transformation.
- Appending `PATCHED_UNVERIFIED` progress or a terminal event.
- Awarding verified-closure credit for intake admission, registry registration, tests, or generated evidence.
- Rewriting, moving, or reserializing any wave-1 or wave-2 intake, progress, journal, or proof file.
- A second public ledger, category-wide promotion engine, mutable approval database, generic workflow system, or directory discovery.
- Browser, Computer, Supabase, provider, production, deployment, credential, commit, push, or destructive operations.
- Marking the parent approximately-1,000-repair goal complete.

## Invariants

1. The public category contract remains unchanged: a current duplicate-FQCN row is `REVIEW_REQUIRED / HOLD`.
2. Exactly one wave-3 predecessor is allowed.
3. The predecessor must be a byte-derived core row from the frozen Stage-A public ledger, not a rewritten copy.
4. The predecessor category must be `DUPLICATE_FQCN_SOURCE_COLLISION`.
5. Its numeric evidence must be exactly one source collision, one generated exclude, zero hard excludes, and zero packaged-active duplicates.
6. Its symbol and path must identify the freshly reproduced canonical root `RetrievalOrderService` owner.
7. The admission decision must bind the exact baseline, issue, group, evidence fingerprint, normalized duplicate evidence, and design-spec hash.
8. The compatibility-copy path is only the intake's `proposedRepairTargetPath`. Intake admission does not authorize its mutation or establish that it is semantically inert.
9. A later, separately approved bounded source design must produce a valid repair-target-admission proof before any v2 progress or event may name a repair target.
10. The canonical owner path remains the historical ledger `sourcePath` and must not be rewritten to make the event schema convenient.
11. A wave-3 event must use the versioned target-binding contract. A v1 event for the reviewed predecessor is invalid.
12. The first wave-3 repair contract permits only an existing-file to existing-file in-place transformation of the independently admitted target. Deletion, move, rename, or absent postimage is invalid.
13. `DISAPPEARED` requires detector-level proof that the exact duplicate FQCN collision ceased to exist; a body-only edit that leaves both package-and-class declarations present cannot close the group.
14. Admission and registration earn zero repair credit.
15. Waves 1 and 2 continue to validate with their existing v1 bytes and semantics.
16. Any mismatch fails before public artifact replacement.

## Intake V2 Contract

### Fixed wave-3 layout

```text
.superpowers/sdd/structural-repair-waves/wave-0003/
  intake/
    intake-summary.json
    eligible-groups.jsonl
    target-preimages.json
    admission-decision.json
    duplicate-evidence.json
  repair-progress.jsonl
  proofs/

verification/structural-repair-waves/wave-0003/
  journal.jsonl
```

`repair-progress.jsonl` and `journal.jsonl` are zero-byte canonical NDJSON files at registration time. An empty `proofs` directory grants no credit and is not hashed as evidence.

The filename `eligible-groups.jsonl` is retained for descriptor compatibility. Under intake v2 it contains explicitly admitted predecessor rows, not a claim that the public row was already `ELIGIBLE / OPEN`.

### Intake-summary schema

Wave 3 uses:

```text
schemaVersion=awx.structural-repair-intake.v2
```

The exact field set is the existing v1 field set plus:

```text
admissionMode
admissionDecisionSha256
duplicateEvidenceSha256
```

The required values are:

```text
admissionMode=EXPLICIT_REVIEW_PROMOTION
admissionDecisionSha256=SHA256(raw canonical bytes of intake/admission-decision.json)
duplicateEvidenceSha256=SHA256(raw canonical bytes of intake/duplicate-evidence.json)
```

The existing source trust fields and ordered issue/group/fingerprint lists retain their v1 meanings. The registry descriptor's `intakeSummarySha256` pins the v2 summary, which transitively pins both new files. The descriptor field set does not change.

### Admission-decision schema

`intake/admission-decision.json` uses:

```text
schemaVersion=awx.structural-repair-admission-decision.v1
```

Its exact fields are:

```text
schemaVersion
decisionId
decisionType
sourceBaselineId
sourceIssueId
rootCauseGroupId
sourceEvidenceFingerprint
sourceCategory
sourceFixEligibility
sourceStatus
sourceNumericEvidence
canonicalOwnerPath
proposedRepairTargetPath
repairTargetAdmissionRequired
activeCallPath
behaviorTestPath
ownerContractTestPath
duplicateEvidenceSha256
approvedDesignSha256
ragControlExcluded
```

Normative values and rules:

- `decisionType` is exactly `REVIEW_REQUIRED_DUPLICATE_OWNER`.
- `sourceCategory` is exactly `DUPLICATE_FQCN_SOURCE_COLLISION`.
- `sourceFixEligibility` and `sourceStatus` are exactly `REVIEW_REQUIRED` and `HOLD`.
- All source identity and numeric fields exactly equal the one frozen ledger row.
- `canonicalOwnerPath` equals that row's path.
- `proposedRepairTargetPath` equals the `app/src/main/java_clean` compatibility-copy path, but is not a source-edit authorization.
- `repairTargetAdmissionRequired` is exactly `true`.
- `activeCallPath` equals the active `DynamicRetrievalHandlerChain.java` path established by the owner contract.
- `behaviorTestPath` and `ownerContractTestPath` equal the two focused test paths.
- All named paths are canonical repository-relative POSIX paths and exist in target preimages.
- `duplicateEvidenceSha256` equals the raw canonical duplicate-evidence file hash and the same intake-summary field.
- `approvedDesignSha256` is the raw SHA-256 of this written design after user approval. The design does not embed its own future hash.
- `ragControlExcluded` is exactly `true`.
- `decisionId` equals SHA-256 of canonical JSON bytes for the decision object with `decisionId` removed.

No environment variable, current timestamp, user name, raw conversation, absolute path, or mutable report path may enter the decision. No progress row, closure event, source-edit lease, or source mutation may treat `proposedRepairTargetPath` as an admitted target without the separate proof defined below.

### Normalized duplicate-evidence schema

`intake/duplicate-evidence.json` uses:

```text
schemaVersion=awx.structural-repair-duplicate-evidence.v1
```

Its exact fields are:

```text
schemaVersion
sourceIssueId
rootCauseGroupId
fqcn
canonicalOwnerPath
compatibilityCopyPath
sourceCollisionCount
generatedExcludeCount
hardExcludeCount
packagedActiveCount
generatedExcludePattern
proofCommand
rawReportSha256
secretPatternHitCount
```

Required values:

```text
fqcn=com.example.lms.strategy.RetrievalOrderService
canonicalOwnerPath=main/java/com/example/lms/strategy/RetrievalOrderService.java
compatibilityCopyPath=app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java
sourceCollisionCount=1
generatedExcludeCount=1
hardExcludeCount=0
packagedActiveCount=0
generatedExcludePattern=com/example/lms/strategy/RetrievalOrderService*
proofCommand=gradlew.bat :app:generateDupFqcnExcludes
secretPatternHitCount=0
```

`rawReportSha256` is the raw SHA-256 of the freshly generated detailed duplicate report. The normalized evidence is count-, hash-, FQCN-, and repository-relative-path-only; it does not copy raw logs or absolute build paths.

### Selected predecessor row

`eligible-groups.jsonl` contains exactly one canonical ledger core row copied from the fresh Stage-A ledger after removing only the public envelope fields. The source row remains:

```text
category=DUPLICATE_FQCN_SOURCE_COLLISION
fixEligibility=REVIEW_REQUIRED
status=HOLD
supersedes=[]
```

The v2 loader validates the row with the unchanged `_expected_category_contract` before applying the wave-local admission decision. It must not synthesize `ELIGIBLE / OPEN`, modify numeric evidence, or substitute a different baseline.

### Target preimages

Wave 3 retains `awx.structural-repair-target-preimages.v1`. It contains exactly these five frozen paths in canonical case-insensitive sort order:

```text
app/src/main/java_clean/com/example/lms/strategy/RetrievalOrderService.java
main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java
main/java/com/example/lms/strategy/RetrievalOrderService.java
src/test/java/com/abandonware/ai/agent/AgentApplicationScanContractTest.java
src/test/java/com/example/lms/strategy/RetrievalOrderServiceTest.java
```

Every row records exact existence, Git state, byte size, and SHA-256 at the freeze boundary. Untracked or modified state is not normalized away. Any byte, state, or path drift before registration causes HOLD and requires a new Stage-A transaction.

The decision's five path fields must map exactly to these rows. The proposed target is frozen only so a later design can prove or reject it without baseline ambiguity. No unrelated owner, test, build file, generated report, or RagControl path is permitted.

## Versioned Repair-Target Binding

The existing v1 event assumes the ledger `sourcePath` is also the changed target. That may be false for this duplicate-owner repair: the ledger correctly identifies the canonical root owner, while the compatibility copy is only a proposed repair target until a later source design proves the active owner, compile/package topology, and bounded transformation.

Wave 3 therefore introduces versioned progress and event schemas without changing waves 1 and 2.

### Repair-target admission

The live wave-3 registration contains no repair-target-admission file. A later separately approved source-repair transaction must first create exactly:

```text
proofs/{rootCauseGroupId}/repair-target-admission.json
```

Here `{rootCauseGroupId}` means the selected lowercase 64-hex group ID; it is a grammar variable, not a literal directory name. The file uses `schemaVersion=awx.structural-repair-target-admission.v1` and this exact field set:

```text
schemaVersion
targetAdmissionId
rootCauseGroupId
sourceIssueId
admissionDecisionId
admissionDecisionSha256
repairTargetPath
transformationMode
approvedSourceDesignSha256
detectorRedProofId
detectorRedProofSha256
compileBaselineProofSha256
ownerContractProofSha256
secretPatternHitCount
```

The loader accepts this file only when all of the following hold:

- it is referenced by a later v2 progress row and is present at the one exact group-scoped path;
- `rootCauseGroupId`, `sourceIssueId`, and both admission-decision fields match the frozen intake;
- `repairTargetPath` is exactly one of the two frozen Java source paths and equals the later bounded source design's approved target; a call-path or test row can never become the repair target;
- equality with `proposedRepairTargetPath` is expected but not assumed; selecting the canonical owner instead requires explicit source-design proof that ownership, call paths, packaging, and behavior remain valid;
- `transformationMode` is exactly `IN_PLACE_EXISTING_FILE`;
- `approvedSourceDesignSha256` identifies the separately user-approved source design, not this admission-only design;
- the detector RED proof reproduces the exact selected FQCN collision before mutation;
- the compile-baseline and owner-contract proofs establish the actual source-set, packaging containment, active call path, focused behavior surface, and a green pre-edit compile baseline;
- every proof hash is lowercase SHA-256, `secretPatternHitCount` is zero, and no proof contains an absolute path or raw build output;
- `targetAdmissionId` is SHA-256 of canonical JSON bytes for the object with `targetAdmissionId` removed.

The three evidence hashes resolve only to these fixed group-scoped files:

```text
proofs/{rootCauseGroupId}/detector-red-summary.json
proofs/{rootCauseGroupId}/compile-baseline.json
proofs/{rootCauseGroupId}/owner-contract.json
```

`detectorRedProofId` is exactly the first path. The other two paths are fixed by their field names; no caller-supplied path is accepted. Each uses `schemaVersion=awx.structural-repair-target-admission-proof.v1` and this exact field set:

```text
schemaVersion
proofRole
rootCauseGroupId
sourceIssueId
commandToken
exitCode
result
assertionCount
outputSha256
secretPatternHitCount
```

`proofRole` is exactly `DETECTOR_RED`, `COMPILE_BASELINE`, or `OWNER_CONTRACT` according to the filename. `DETECTOR_RED` requires a nonzero exit and `EXPECTED_FAIL` from the later structural-absence assertion; the two baseline roles require exit zero, `PASS`, and a positive assertion count. Every row must match the selected identities, use a redacted bounded command token, carry only an output hash, and have zero secret hits. The target-admission loader recomputes all three raw file hashes before accepting the admission.

The fixed wave-3 Gradle allowlist may recognize these four future proof filenames, but all four must be absent while the journal and progress ledger are empty. Recognition is schema support, not advance approval. A source repair may not begin until its own design approval, fresh target-preimage recheck, mandatory three-way source-edit preflight, and source-owner lease all pass.

### Progress v2

`awx.structural-repair-progress.v2` has all v1 fields plus:

```text
admissionDecisionId
admissionDecisionSha256
targetAdmissionId
targetAdmissionSha256
repairTargetPath
```

For v2:

- `targetPreimageSha256` is the frozen hash for `repairTargetPath`, not `sourcePath`.
- `targetPostimageSha256` is the current hash of the same existing path.
- Preimage and postimage must both exist and differ.
- `repairTargetPath` must equal the repair-target-admission file and one target-preimages row; the intake decision supplies only a proposal.
- The two admission fields must match the canonical admission decision.
- The two target-admission fields must match the raw canonical repair-target-admission file.
- `patchStateId` and the full progress-row proof hash retain their current canonical formulas.

### Closure event v2

`awx.structural-repair-closure-event.v2` has all v1 fields plus the same five fields:

```text
admissionDecisionId
admissionDecisionSha256
targetAdmissionId
targetAdmissionSha256
repairTargetPath
```

For v2:

- `sourcePath`, `sourceIssueId`, `sourceCategory`, and all source evidence continue to identify the historical public ledger row and canonical root owner.
- Target preimage/postimage validation uses `repairTargetPath`.
- The matching v2 progress row must agree on every shared field.
- Both target-admission fields must resolve to the exact validated repair-target-admission file before target hashes are evaluated.
- `fingerprintDisposition` must be `DISAPPEARED` for the first duplicate-owner repair; a bounded transformation label alone does not satisfy source-collision closure.
- `duplicateOwnerGate`, source-set gate, dependency gate, compile gate, desktop proof, and secret gate must all pass.
- A v1 event for a v2 admission decision fails closed.
- An absent postimage, renamed path, moved path, or deletion fails closed. Those operations require a separate design and explicit destructive authority.

The existing historical overlay needs no category-wide eligibility change. It copies the frozen predecessor identity, updates only the current baseline plus the existing terminal eligibility/status pair, and records the supersession hash. A valid future `VERIFIED_CLOSED` event therefore produces `ELIGIBLE / VERIFIED_CLOSED` only for the one historical row.

### Operational `DISAPPEARED` contract

The later source transaction must establish a causal structural RED/GREEN pair around the repository's existing duplicate classifier and public producer:

1. RED, before mutation: the detailed duplicate report and the shared duplicate classifier both emit the exact `com.example.lms.strategy.RetrievalOrderService` root/app pair, one source collision, the generated-exclude containment, and the frozen issue/group/fingerprint identity.
2. GREEN, after the admitted in-place transformation: the detailed duplicate report and `dynamicRagQuantAudit` no longer emit that FQCN collision, root-cause group, or evidence fingerprint.
3. The generated exclude must be removed or deterministically cease to claim that FQCN only when the generator proves it is no longer needed; packaged-active duplicates remain zero.
4. The canonical root behavior test, owner-contract test, root compile, and `:app:classes` all pass from isolated Java 17 caches.
5. The active call path and canonical owner remain valid, and no unapproved source or test changes occur.

A change that merely alters method bodies while leaving both Java files declaring the same package and class fails GREEN and cannot use `DISAPPEARED`. The terminal event is valid only when detector-universe absence and compile/package/owner behavior are all proven from the same postimage transaction.

## Version Dispatch Boundaries

Version handling is per descriptor and per wave; it is not a permissive union of v1 and v2 fields:

- Intake loading dispatches on the exact intake-summary schema after the descriptor's raw intake hash is validated.
- Wave 1 and wave 2 accept only the existing v1 exact field sets, existing `BROAD_CATCH_NO_BREADCRUMB / ELIGIBLE / OPEN` predecessor rule, and existing progress/event semantics. Their bytes, identities, and validation order remain unchanged.
- Intake v2 is accepted only for descriptor `waveId=wave-0003`, `ordinal=3`, exact `EXPLICIT_REVIEW_PROMOTION`, and exactly one frozen `REVIEW_REQUIRED / HOLD / DUPLICATE_FQCN_SOURCE_COLLISION` predecessor with its canonical decision and evidence files.
- Progress parsing uses strict schema dispatch: v1 rows retain the exact v1 field set; v2 rows are allowed only for the wave-3 admitted predecessor and require both decision and target-admission bindings.
- Event parsing uses the same strict boundary. A v1 event cannot reference a v2 predecessor, and a v2 event cannot omit or borrow either admission proof.
- Unknown versions, v2 fields on v1 rows, v1 fields substituted for v2 requirements, or cross-wave proof references fail closed before overlay or publication.
- `scripts/source_health_scorecard.py` delegates to the production registry/history and full-bundle loader. It gets compatibility tests but no parallel intake classifier or second schema implementation.

Admission/evidence failures use `closure-admission-invalid`; malformed progress or event rows retain their existing fail-closed progress/proof/journal reason families. No error branch may downgrade a version mismatch into an ignored row.

## Registry And Gradle Transaction

The registry remains `awx.structural-repair-wave-registry.v1` with the existing descriptor field set. Wave 3 uses:

| Field | Value or deterministic source |
|---|---|
| `waveId` | `wave-0003` |
| `ordinal` | `3` |
| `journalPath` | `verification/structural-repair-waves/wave-0003/journal.jsonl` |
| `proofRoot` | `.superpowers/sdd/structural-repair-waves/wave-0003` |
| `sourceBaselineId` | Fresh Stage-A baseline ID |
| `sourceLedgerPayloadSha256` | Fresh Stage-A ledger payload SHA-256 |
| `sourceMetricsSemanticHash` | Fresh Stage-A metrics semantic hash |
| `intakeSummarySha256` | Raw SHA-256 of wave-3 v2 intake summary |
| `eligibleGroupsSha256` | Raw SHA-256 of the one unchanged HOLD predecessor row |
| `targetPreimagesSha256` | Raw SHA-256 of the five-path target-preimages file |

The three future Stage-A values and three future intake hashes must be measured; no placeholder value is valid.

Adding the descriptor and updating `FROZEN_CLOSURE_WAVE_REGISTRY_SHA256` are one reviewed transaction. The loader must reject an updated registry with the old pin and an updated pin with any other bytes.

Gradle adds explicit wave-3 declarations for:

- the journal;
- the five fixed intake files;
- `repair-progress.jsonl`;
- the existing exact proof filename allowlist under the wave-3 proof root;
- the exact optional future patterns `proofs/*/repair-target-admission.json`, `proofs/*/detector-red-summary.json`, `proofs/*/compile-baseline.json`, and `proofs/*/owner-contract.json`, all empty at registration and populated only by a later separately approved source transaction.

All use `PathSensitivity.RELATIVE`. Runtime directory discovery remains forbidden. The same wave-3 fixed input set is registered on both `dynamicRagQuantAudit` and `sourceHealthScorecard` as required by their current ownership boundaries.

## Stage-A Freeze And Publication Flow

### Phase A: immutable preflight

Before any infrastructure or intake write:

1. Verify branch, HEAD, staged count, `.git/index.lock`, top-level PatchDrop patches, and active source-edit leases.
2. Freeze hashes for the current public bundle, registry, dynamic audit producer, source-health validator, Gradle files, waves 1 and 2, and candidate owner/test/call-path files.
3. Require the user-held RagControl source and test hashes to remain unchanged.
4. Stop on a conflicting owner, lease, reparse point, path alias, index lock, or changed immutable input.

### Phase B: infrastructure TDD

Implement strict intake-version dispatch, admission, normalized evidence, repair-target-admission recognition, and v2 target-binding support against isolated fixtures while the live registry remains two-wave pinned. V1 parity must stay green before any live wave-3 file or registry change. No live wave-3 file and no Java file is written in this phase.

### Phase C: final fresh public predecessor and tuple capture

After Phase-B production bytes and focused/full Python suites are stable, run one final repository producer invocation:

```text
gradlew.bat dynamicRagQuantAudit
```

Use Java 17, split build outputs, a stable Desktop host ID, an isolated Gradle user home, and an isolated project cache. This command regenerates the Git-state inputs, harmony report, test-tree report, detailed app duplicate evidence, and the three fixed public artifacts.

Immediately after that final producer invocation, validate without invoking another Gradle task:

- call the production read-only `load_and_validate_current_bundle` helper;
- call the source-health read-only `validate_current_quant_bundle` helper;
- select the one exact `RetrievalOrderService` row;
- capture the raw hashes of all three public artifacts and the detailed duplicate report;
- capture the baseline ID, canonical ledger payload hash, metrics semantic hash, selected issue/group/fingerprint identity, and all five target preimages.

The capture is one immutable Stage-A tuple. No `dynamicRagQuantAudit`, `sourceHealthScorecard`, task that depends on either producer, source mutation, or audit-producer mutation may occur between this capture and Phase-D writes. If any such command runs, any public artifact changes, or the duplicate report changes, discard the tuple and repeat this phase from its final producer invocation. If the fresh ledger does not reproduce exactly one matching `RetrievalOrderService` duplicate row with zero packaged-active duplicates, stop without creating wave 3.

### Phase D: freeze wave 3

Immediately from the validated Phase-C Stage-A tuple:

1. Copy the exact envelope-free predecessor row.
2. Normalize the detailed duplicate report into the canonical duplicate-evidence file.
3. Construct and hash the admission decision.
4. Freeze the five target preimages.
5. Write the v2 intake summary last so it pins the two new intake files.
6. Create zero-byte progress and journal files.
7. Require the repair-target-admission file and its three future evidence files to be absent.
8. Validate the wave directly before registry admission.

### Phase E: register and publish

1. Append the wave-3 descriptor to canonical registry bytes.
2. Update the code pin to the exact registry SHA-256.
3. Add exact Gradle wave-3 inputs.
4. Run focused Python contract tests.
5. Run the full dynamic-audit and source-health modules.
6. Run `dynamicRagQuantAudit` and then `sourceHealthScorecard` through isolated Desktop caches.
7. Validate the public bundle again.
8. Run two normalized projections and require identical semantic hashes.

The Phase-E Gradle chain may legitimately regenerate the post-registration public bundle. Final report values and hashes are captured only after the last such producer-dependent invocation; they do not overwrite or retroactively redefine the Phase-C predecessor tuple pinned by the wave-3 descriptor.

Publication retains the existing staged validation and atomic replacement order. Failure before or during replacement restores every prior public output.

## Expected State After Registration

Assuming no unrelated detector or source drift, wave-3 registration with an empty journal changes trust topology but earns no repair credit:

| Counter | Before | After wave-3 registration |
|---|---:|---:|
| Registered waves | 2 | 3 |
| Closure events | 14 | 14 |
| Rejected groups | 5 | 5 |
| Verified-closed groups | 9 | 9 |
| `OPEN / ELIGIBLE` current groups | 1 | 1 |
| Public `RetrievalOrderService` state | `REVIEW_REQUIRED / HOLD` | `REVIEW_REQUIRED / HOLD` |
| Repair credit added | 0 | 0 |

The registry hash, closure-history registry hash, audit run ID, and linked public artifact hashes change. Existing event identities and wave-1/wave-2 proof hashes do not.

After a later separately approved in-place repair and valid terminal event, the expected transition is one historical duplicate row from `REVIEW_REQUIRED / HOLD` to `ELIGIBLE / VERIFIED_CLOSED`, with verified-closed groups increasing from 9 to 10. That future result is not part of this implementation transaction.

## Failure And HOLD Rules

Introduce one explicit reason code:

```text
closure-admission-invalid
```

Use it for a missing, malformed, noncanonical, unpinned, or semantically inconsistent admission decision or normalized duplicate-evidence file.

The following conditions stop before registry or public-output replacement:

- no fresh matching predecessor;
- more than one selected row;
- category, eligibility, status, issue, group, fingerprint, or numeric-evidence mismatch;
- any RagControl path or group in wave 3;
- packaged-active duplicates greater than zero;
- source-collision or generated-exclude count other than one;
- any hard exclude for the selected group;
- missing or mismatched detailed duplicate report;
- missing selected target, target-path alias, reparse point, absolute path, or unexpected target;
- owner/test/call-path preimage drift;
- admission-design hash mismatch;
- v1 event used for v2 admission;
- a source edit that treats `proposedRepairTargetPath` as authorized without a separately approved source design and repair-target-admission proof;
- missing, malformed, noncanonical, mismatched, or cross-wave repair-target admission;
- repair target different from the validated repair-target-admission path;
- a claimed `DISAPPEARED` disposition while the exact FQCN collision, group, fingerprint, or generated containment still appears;
- attempted deletion, move, rename, or absent postimage;
- registry pin mismatch;
- source-health disagreement;
- secret-pattern hit;
- public publication or rollback failure.

Every HOLD report records the affected lane, first blocking rule, hash- or count-only evidence, independent work completed, and `repositoryWideHold=false` unless the blocking condition truly affects every authorized lane.

## Security And Privacy

- All new JSON is canonical UTF-8 with no BOM, sorted keys, compact separators, and exactly one trailing LF.
- All new NDJSON is canonical UTF-8 with one trailing LF per row and no blank row; zero-byte empty progress and journal files remain valid.
- Duplicate keys, nonfinite numbers, unknown fields, extra rows, path aliases, absolute paths, `.` or `..` segments, reparse traversal, and case-colliding identities fail closed.
- New evidence stores only repository-relative paths, FQCN, categorical counts, command tokens, and SHA-256 values.
- Raw Gradle output, raw Git roots, credentials, environment values, authorization data, and provider data are not persisted.
- Every public and private evidence artifact requires zero new secret-pattern hits.
- No Browser, Computer, Supabase, provider, database, or production call is needed.

## Test Strategy

### Intake and admission RED/GREEN

- V1 wave fixtures remain byte- and behavior-identical.
- A v2 intake with one exact reviewed duplicate row passes.
- A v2 intake with zero or two rows fails.
- Intake v2 fails for any descriptor other than exact `wave-0003` ordinal 3; intake v1 rejects every v2-only field.
- Category-wide promotion, an `ELIGIBLE / OPEN` rewrite, or a different duplicate group fails.
- Any RagControl issue, group, path, symbol, selected predecessor, or target fails; only the exact boolean exclusion attestation is allowed.
- Missing decision/evidence files, wrong raw hashes, noncanonical bytes, or unknown fields fail.
- A packaged-active count greater than zero, hard exclude, wrong pair, or wrong generated-exclude pattern fails.
- Decision IDs and transitive hashes are independently recomputed.
- Every decision path must exist exactly once in target preimages.

### Target-binding RED/GREEN

- A v1 event for the v2 predecessor fails.
- A v2 progress/event pair binds both the intake admission decision and a separately validated repair-target-admission proof.
- Merely naming the intake's proposed target without a repair-target-admission file fails.
- A target-admission proof with the wrong source-design hash, RED proof, baseline compile proof, owner contract, transformation mode, or secret count fails.
- A target preimage taken from any path other than the independently admitted source target fails; call-path and test rows are never admissible targets.
- Missing, unchanged, absent, moved, renamed, or aliased repair targets fail.
- Progress/event shared fields and canonical hashes must match.
- `ACCEPTED_BOUNDED_TRANSFORMATION` fails for this first duplicate-owner closure; `DISAPPEARED` is required.
- Structural RED reproduces the exact FQCN pair and identity; structural GREEN requires the detailed report and public audit to omit the pair, group, and fingerprint.
- A body-only edit that leaves both package-and-class declarations present remains RED and earns no closure credit.
- `DISAPPEARED` also requires zero packaged-active duplicates, correct generated-exclude disposition, both focused tests, root compile, and `:app:classes`.

### Strict version-dispatch RED/GREEN

- Wave-1 and wave-2 v1 fixtures retain exact accepted fields, hashes, reason codes, and validation order.
- Wave-3 intake rejects v1 predecessor admission semantics and every unknown or mixed schema.
- V1 progress/event rows reject v2-only fields; v2 rows reject omitted decision or target-admission fields.
- V2 progress/event rows are accepted only for the exact wave-3 predecessor and cannot borrow a proof from another wave or group.
- Source health accepts and rejects the same fixtures through the production loader; no source-health-only classifier exists.

### Registry and aggregation

- Three canonical descriptors load in ordinal order.
- Old pin/new registry and new pin/altered registry both fail.
- Wave 1 and wave 2 aggregate identities, events, proofs, and terminal rows remain unchanged.
- Empty wave-3 journal yields wave count 3 with event, rejected, and verified counts unchanged.
- Cross-wave issue, group, journal, proof-root, target, and active-tip conflicts fail.
- Same logical fixture under different absolute roots produces identical semantic hashes.

### Gradle and source-health

- Both public tasks declare the exact wave-3 fixed inputs with relative path sensitivity.
- No wildcard admits arbitrary intake files beyond the fixed set or proof allowlist.
- Source health consumes the production registry/history loader and rejects tampered admission bytes.
- Publication validates staged bytes before replacement and preserves rollback order.
- Focused modules, full modules, current-bundle validators, and two-run normalized projection are green before release.

## Implementation Boundaries

Expected infrastructure ownership:

- `scripts/dynamic_rag_quant_audit.py`
- `scripts/test_dynamic_rag_quant_audit.py`
- `scripts/test_source_health_scorecard.py` when fixture support is required
- `build.gradle.kts`
- `verification/structural-repair-waves/registry.json`
- new wave-3 intake, progress, and journal files

`scripts/source_health_scorecard.py` should remain unchanged if its existing delegation to the production bundle loader already enforces the new contract. A production change there requires a failing focused test proving that delegation is insufficient; duplicating the v2 classifier there is forbidden.

The infrastructure and freeze transaction does not modify:

- either `RetrievalOrderService` source file;
- either focused Java test;
- the active call-path source;
- `RagControlRuntimeAdapter` source or test;
- wave-1 or wave-2 files.

The later source repair is a new bounded design. It must independently admit its repair target, recheck all frozen hashes, run the mandatory three-way source-edit preflight, acquire the existing source-owner lease, preserve the canonical root owner and user hunks, establish a causal detector RED, and receive separate user authority for the source mutation. Even an in-place edit of the proposed compatibility-copy path is unauthorized until those gates pass.

## Acceptance Criteria

The architectural implementation is accepted only when all of the following are freshly proven:

1. The fresh public predecessor bundle and detailed duplicate report validate.
2. Exactly one provisional candidate becomes one exact reviewed intake row.
3. No RagControl path, issue, group, symbol, predecessor, or target is selected in wave 3; only `ragControlExcluded=true` may attest its exclusion, and its source/test bytes remain unchanged.
4. V1 waves remain byte-identical and all 14 existing terminal events remain valid.
5. Wave-3 decision, normalized evidence, targets, progress, and journal are canonical and hash-linked; live progress and journal are zero bytes and no repair-target-admission or target-admission evidence file exists.
6. The three-wave registry and code pin agree exactly.
7. Gradle registers every fixed wave-3 input and no broad discovery path.
8. Production history reports three waves, 14 events, five rejected groups, and nine verified groups.
9. Public `RetrievalOrderService` remains `REVIEW_REQUIRED / HOLD` after registration.
10. No repair credit is added.
11. Dynamic-audit and source-health focused and full suites pass.
12. The public bundle and source-health validators pass.
13. Two normalized publication projections have the same semantic hash.
14. No staged files, index lock, active source-edit lease, top-level PatchDrop patch, secret hit, or unrelated source change is introduced.
15. The parent approximately-1,000 structural-repair goal remains incomplete.

## One Next Action After Written-Spec Approval

Create a Superpowers implementation plan that orders the work as: immutable preflight, strict version/intake/target-binding TDD, final fresh Stage-A public audit and tuple capture, immediate wave-3 freeze, registry/Gradle TDD, atomic registration/publication, and final parity verification. No Java source mutation or live repair-target admission belongs to that plan.

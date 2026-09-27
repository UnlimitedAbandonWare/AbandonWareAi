# Wave-4 SelfAsk Exact Reviewed-Admission Design

**Date:** 2026-09-03
**Architecture direction:** User approved the recommended exact-candidate-pinned SelfAsk intake-v3 option on 2026-09-03
**Written-spec status:** Awaiting user review of the corrected test-boundary revision
**Parent objective:** Continue the unresolved approximately-1,000 structural-repair goal without touching the user-held `RagControlRuntimeAdapter` group or awarding unproven repair credit
**Predecessor designs:** `docs/superpowers/specs/2026-09-01-structural-repair-multi-wave-registry-design.md` and `docs/superpowers/specs/2026-09-02-reviewed-duplicate-ownership-wave-design.md`

## Objective

Create a fourth isolated structural-repair wave whose intake owns exactly one freshly reproduced reviewed duplicate-ownership predecessor: `service.rag.planner.SelfAskPlanner`.

Wave 4 freezes a new public Stage-A predecessor tuple, the exact SelfAsk ledger row, normalized duplicate evidence, an exact admission decision, and five target preimages. It then appends one empty-history descriptor to the existing code-pinned registry and republishes the one existing quantitative bundle. It does not modify or remove either SelfAsk source file, create a repair-target admission, append progress or terminal history, or earn repair credit.

The design preserves wave-1 through wave-3 bytes and semantics. The wave-3 intake-v2 validator remains hard-pinned to `wave-0003` and `RetrievalOrderService`; it is not generalized into a category-wide duplicate admission mechanism. `RagControlRuntimeAdapter` remains excluded and on user HOLD. The parent approximately-1,000-repair goal remains incomplete.

## Current Evidence

The current public quantitative bundle is a three-wave transaction:

| Field | Current value |
|---|---:|
| Baseline ID | `cecc5c4361369bfd5b253574360ef01da1228dfa31274f2e3d14be291888bfe6` |
| Audit run ID | `c3b90088b29cd44288cbea35011a4a5c89c31d20da8172ff11e6b525667b1024` |
| Registered waves | 3 |
| Closure events | 15 |
| Rejected false-positive groups | 5 |
| Verified-closed groups | 10 |
| Public ledger rows | 84 |
| Public root-cause groups | 69 |
| `OPEN / ELIGIBLE` groups | 1 |
| Target gap | 889 |

The only current `OPEN / ELIGIBLE` group is `RagControlRuntimeAdapter`. The user explicitly excluded it from this and prior waves. Wave 4 therefore does not reinterpret the broad-catch category; it selects one separately reviewed duplicate-FQCN row under a new exact-candidate contract.

The current registry and public artifacts are supporting pre-design evidence, not the future Stage-A trust tuple:

| Artifact | Current SHA-256 |
|---|---|
| `verification/structural-repair-waves/registry.json` | `e1791b53a62d7dd1f24e6c4598b1652c353c9c4c86c22e6994b0dd895c14db4b` |
| `verification/structural-design-baseline.json` | `c377bf10d12f9c2e68e3ff6187c75a9b3dea748db49fee844ff5b246d55f631d` |
| `verification/structural-design-debt-ledger.jsonl` | `c37a6d026c80dc3701589071a5f35b3d2d5976483479a111a7f6d9bec81d7729` |
| `verification/dynamic-rag-quant-audit-metrics.json` | `e8ef3f56a8f6130950d2e38edd4383abcc7f3aa6a3340e8c7888b4d59f5721bb` |

### Provisional SelfAsk evidence

The current public ledger contains this provisional row:

| Field | Current value |
|---|---|
| Category | `DUPLICATE_FQCN_SOURCE_COLLISION` |
| Severity | `HIGH` |
| Eligibility | `REVIEW_REQUIRED` |
| Status | `HOLD` |
| Symbol | `service.rag.planner.SelfAskPlanner` |
| Path | `main/java/service/rag/planner/SelfAskPlanner.java` |
| Issue ID | `4b267ae92d202316ccf3abe3b4e9c3027b438f25622b3ad038208bd4eb70474d` |
| Root-cause group ID | `a400e732149ca555bc8490ff3952b9d24d4f5edbf851eb324d1be9475ffbab86` |
| Evidence fingerprint | `ec20879a0fe9b3f9daf1a8389192bd548331baebc75a05f85ee07b47d4b9badd` |
| Source-collision count | 1 |
| Generated-exclude count | 1 |
| Hard-exclude count | 0 |
| Packaged-active count | 0 |

The most recent host-specific detailed duplicate report is supporting evidence only. It identifies this exact pair:

```text
canonical root owner:
  main/java/service/rag/planner/SelfAskPlanner.java

generated-excluded app compatibility copy:
  app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java

packagingState=GENERATED_EXCLUDE
collisionEvidenceFingerprint=71d6931bcd9d42f5e59bb200511046f6f37a22aec914e07c340977e88a706ad1
generatedExcludePattern=service/rag/planner/SelfAskPlanner*
```

Its raw path is intentionally not admitted into the wave because split-build host IDs may change. The future Stage-A invocation must rediscover the configured report, prove it came from that invocation, and normalize only repository-relative paths, counts, enum values, and hashes.

The candidate and guard bytes are also provisional. Every value must be measured again at the Stage-A freeze boundary:

| Path | Current Git state | Current size | Current SHA-256 |
|---|---|---:|---|
| `app/build.gradle.kts` | modified | 20,376 | `b1590e63596000c944d6a1e94bf325323d001d41ba8cc9360e3462f1dd4c4597` |
| `app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java` | clean | 699 | `98598256965d3ff9978c763d3639a8d9017062c6a5a7c820d803adbaa1d16b64` |
| `main/java/config/RagLightAdapters.java` | modified | 5,573 | `9974081bff3e38182efc40cf659176cdc6bf62022496f3ed8cbafd896becfc95` |
| `main/java/service/rag/planner/SelfAskPlanner.java` | untracked | 4,100 | `7c1a82407fa741d5aa7ed5509ddd9d6779a00055f79eccab674af52eb8834786` |
| `src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java` | absent | 0 | `0000000000000000000000000000000000000000000000000000000000000000` |

The space in no hash is meaningful; all Stage-A hashes must be lowercase 64-hex values computed from raw bytes. Existing modified and untracked states are recorded, not cleaned or normalized. The app compatibility copy must remain `clean`; a modified, deleted, renamed, unmerged, or absent compatibility copy is not admissible.

The root planner is the active `@Component`. `main/java/config/RagLightAdapters.java` is its direct retained production configuration seam. The app copy exposes a different scaffold API and has no currently discovered direct caller in the active `appMainClean` source root.

Planning-time verification found that `AppCleanSourceZombieContractTest` does **not** cover this candidate. It asserts absence of `app/src/main/java_clean/com/example/lms/service/rag/planner/SelfAskPlanner.java`, while the duplicate report identifies `app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java`. Those are different packages and paths. The existing test is therefore not admitted as SelfAsk evidence. Wave 4 instead freezes an `ABSENT` preimage for a dedicated future `SelfAskPlannerOwnershipContractTest.java`; creating or running that test belongs only to the later source-repair design.

## Approved Architectural Choice

The selected design adds a wave-4-only intake-v3 admission boundary:

```text
one final fresh three-wave public audit and duplicate report
  -> exact SelfAsk REVIEW_REQUIRED / HOLD predecessor
  -> exact candidate and no-caller predicates
  -> canonical duplicate-evidence v2
  -> canonical admission-decision v2
  -> wave-0004 intake v3 and five target preimages
  -> unchanged registry descriptor shape plus a fourth descriptor
  -> zero-byte wave-4 progress and journal
  -> one republished public bundle with unchanged repair credit
```

Admission to Wave 4 is not public eligibility, source-edit authority, repair completion, or repair credit. The public SelfAsk row remains `REVIEW_REQUIRED / HOLD` until a separately designed repair and valid terminal history contract exist.

### Rejected alternatives

1. **Generalize intake v2.** Rejected because intake v2 is an approved, exact `wave-0003` and `RetrievalOrderService` trust boundary. Relaxing it would silently change historical semantics.
2. **Promote every generated-excluded duplicate.** Rejected because duplicate ownership is candidate-specific. ONNX and `AnswerSanitizer` already demonstrate materially different runtime, dirty-preimage, and ABI risks. SelfAsk remains preferred because the app copy is clean, has zero discovered app callers, and has one narrow retained root configuration seam; no existing focused absence test is claimed.
3. **Reuse the current duplicate report and ledger row.** Rejected because those bytes predate Wave 4 infrastructure and cannot establish a fresh no-intervening-write Stage-A tuple.
4. **Edit or delete the app SelfAsk copy while freezing intake.** Rejected because registration and source repair are separate authorization and evidence transactions.

## Scope

### In scope

- Strict `awx.structural-repair-intake.v3` dispatch for exactly `wave-0004`, ordinal 4.
- Exact SelfAsk admission-decision-v2 and duplicate-evidence-v2 schemas.
- One final fresh public predecessor transaction produced by the existing `dynamicRagQuantAudit` task after infrastructure tests are stable.
- One exact normalized collision row from the detailed duplicate report generated in that same Stage-A invocation.
- A deterministic count-only probe showing zero direct callers of the app compatibility copy in `appMainClean` outside the declaration file.
- Exactly five frozen target preimages, including their actual dirty or untracked Git states.
- Wave-4 intake files, zero-byte progress, empty proof namespace, and zero-byte journal.
- One fourth descriptor using the unchanged registry-v1 descriptor schema.
- An exact new registry byte pin.
- Exact Wave-4 Gradle inputs on both public consumers.
- Four-wave aggregate reconstruction through the existing production history loader and source-health validator.
- Canonical-byte, path-containment, reparse, version-dispatch, determinism, rollback, privacy, and preservation gates.

### Out of scope

- Modifying, deleting, moving, or renaming either SelfAsk source file.
- Modifying `RagLightAdapters.java` or `app/build.gradle.kts`.
- Creating or running `SelfAskPlannerOwnershipContractTest.java` as proof of a completed repair.
- Treating the unrelated `AppCleanSourceZombieContractTest` assertion for the `com/example/lms/...` path as evidence for this `service/rag/...` candidate.
- Choosing the later transformation mode or authorizing a destructive deletion.
- Creating a repair-target-admission file, progress row, proof file, or terminal event for Wave 4.
- Accepting a v1 or v2 progress/event row for the Wave-4 predecessor.
- Awarding verified-closure credit for admission, registration, duplicate containment, or tests.
- Changing intake-v1 or intake-v2 schemas, fields, accepted identities, reason ordering, or bytes.
- Rewriting any wave-1, wave-2, or wave-3 intake, progress, journal, proof, or event.
- Touching `RagControlRuntimeAdapter` source, test, history, status, or contract.
- Admitting ONNX, `AnswerSanitizer`, or any fallback candidate if SelfAsk fails admission.
- A second public ledger, broad directory discovery, mutable approval store, or generic workflow framework.
- Browser, Computer, Supabase, provider, database, production, deployment, credential, commit, stage, push, or cleanup operations.
- Marking the parent approximately-1,000-repair goal complete.

## Invariants

1. Wave 4 contains exactly one predecessor and it is freshly derived from the final Stage-A ledger.
2. The predecessor is exactly `HIGH / DUPLICATE_FQCN_SOURCE_COLLISION / REVIEW_REQUIRED / HOLD` with `supersedes=[]`.
3. Its symbol and canonical path are exactly the SelfAsk root owner named in this design.
4. Its numeric evidence is exactly one source collision, one generated exclude, zero hard excludes, and zero packaged-active duplicates.
5. The detailed report reproduces exactly one SelfAsk pair with `packagingState=GENERATED_EXCLUDE` and the exact generated pattern.
6. The normalized collision evidence fingerprint equals the fresh detailed report's SelfAsk row, while the ledger evidence fingerprint equals the fresh ledger row. They are distinct identities and must not be substituted for each other.
7. The app compatibility copy has zero direct caller files in `appMainClean` outside itself at the freeze boundary.
8. The app compatibility copy is `clean` at the freeze boundary. No fallback candidate is selected if it is not.
9. The root owner, retained call/config seam, and packaging config are preserved byte-for-byte from freeze through registration. The dedicated future ownership-test path remains absent through registration.
10. Existing modified and untracked states are preserved as evidence. No clean-tree operation is permitted.
11. The admission decision binds the exact Stage-A baseline, issue, group, ledger fingerprint, normalized duplicate evidence, five paths, and approved design hash.
12. `proposedRepairTargetPath` is a proposal only. It creates no mutation or deletion authority.
13. Wave-4 progress and journal are exactly zero bytes at registration; its proof set and event set are empty.
14. No existing progress/event schema may be used for the v3 predecessor. A future source-repair design must define and approve its own v3 target/progress/event binding before a first append.
15. Wave-1 through wave-3 history, proof identities, terminal events, and closure credit remain unchanged.
16. `RagControlRuntimeAdapter` appears in no Wave-4 predecessor, decision, evidence, target, progress, proof, or event identity. The only allowed mention is the boolean exclusion attestation and preservation evidence.
17. Registration changes trust topology but adds zero repair credit.
18. Any mismatch fails before public artifact replacement.

## Intake V3 Contract

### Fixed Wave-4 layout

```text
.superpowers/sdd/structural-repair-waves/wave-0004/
  intake/
    intake-summary.json
    eligible-groups.jsonl
    target-preimages.json
    admission-decision.json
    duplicate-evidence.json
  repair-progress.jsonl
  proofs/

verification/structural-repair-waves/wave-0004/
  journal.jsonl
```

`repair-progress.jsonl` and `journal.jsonl` are zero-byte canonical NDJSON files at registration. The `proofs` directory is empty and grants no credit. The legacy filename `eligible-groups.jsonl` contains the admitted historical `REVIEW_REQUIRED / HOLD` predecessor; it does not claim the public row is `ELIGIBLE / OPEN`.

### Intake-summary schema

Wave 4 uses:

```text
schemaVersion=awx.structural-repair-intake.v3
```

Its exact field set is the v1 summary field set plus the same three transitive hash fields introduced by v2:

```text
schemaVersion
sourceBaselineId
sourceLedgerPayloadSha256
sourceMetricsSemanticHash
sourceIssueIds
rootCauseGroupIds
evidenceFingerprints
sourceBranch
sourceHead
admissionMode
admissionDecisionSha256
duplicateEvidenceSha256
```

Required values:

```text
admissionMode=EXACT_SELFASK_REVIEW_PROMOTION
admissionDecisionSha256=SHA256(raw canonical intake/admission-decision.json bytes)
duplicateEvidenceSha256=SHA256(raw canonical intake/duplicate-evidence.json bytes)
```

The descriptor's `intakeSummarySha256` pins the summary, which transitively pins the two new decision/evidence files. The descriptor schema remains unchanged.

### Admission-decision-v2 schema

`intake/admission-decision.json` uses:

```text
schemaVersion=awx.structural-repair-admission-decision.v2
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
candidateFqcn
canonicalOwnerPath
proposedRepairTargetPath
repairTargetAdmissionRequired
packagingConfigPath
activeCallPath
futureOwnershipTestPath
duplicateEvidenceSha256
approvedDesignSha256
ragControlExcluded
```

Normative values and rules:

- `decisionType=EXACT_SELFASK_REVIEWED_DUPLICATE_OWNER`.
- `sourceCategory=DUPLICATE_FQCN_SOURCE_COLLISION`.
- `sourceFixEligibility=REVIEW_REQUIRED` and `sourceStatus=HOLD`.
- Every source identity and numeric field exactly equals the single frozen Stage-A ledger row.
- `candidateFqcn=service.rag.planner.SelfAskPlanner` and equals the row symbol.
- `canonicalOwnerPath=main/java/service/rag/planner/SelfAskPlanner.java` and equals the row path.
- `proposedRepairTargetPath=app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java`.
- `repairTargetAdmissionRequired=true`.
- `packagingConfigPath=app/build.gradle.kts`.
- `activeCallPath=main/java/config/RagLightAdapters.java`.
- `futureOwnershipTestPath=src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java`.
- Every named path has exactly one target-preimage row. The future ownership-test row is exactly `existence=ABSENT`, `gitState=absent`, `sizeBytes=0`, and a 64-zero SHA-256; every other named path is an existing file.
- `duplicateEvidenceSha256` equals the raw canonical duplicate-evidence hash and the same summary field.
- `approvedDesignSha256` equals the raw SHA-256 of this exact written design after user approval. The document does not embed its own future hash.
- `ragControlExcluded=true`.
- `decisionId` equals SHA-256 of canonical JSON bytes for the decision object with `decisionId` removed.

No timestamp, raw conversation, environment value, user name, absolute path, host-specific build path, or mutable log path enters the decision.

### Normalized duplicate-evidence-v2 schema

`intake/duplicate-evidence.json` uses:

```text
schemaVersion=awx.structural-repair-duplicate-evidence.v2
```

Its exact fields are:

```text
schemaVersion
sourceIssueId
rootCauseGroupId
fqcn
canonicalOwnerPath
compatibilityCopyPath
packagingState
sourceCollisionCount
generatedExcludeCount
hardExcludeCount
packagedActiveCount
appActiveSourceRoot
compatibilityCopyDirectCallerFileCount
callerProbeToken
generatedExcludePattern
proofCommand
rawReportSha256
rawCollisionEvidenceFingerprint
secretPatternHitCount
```

Required categorical values are:

```text
fqcn=service.rag.planner.SelfAskPlanner
canonicalOwnerPath=main/java/service/rag/planner/SelfAskPlanner.java
compatibilityCopyPath=app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java
packagingState=GENERATED_EXCLUDE
sourceCollisionCount=1
generatedExcludeCount=1
hardExcludeCount=0
packagedActiveCount=0
appActiveSourceRoot=app/src/main/java_clean
compatibilityCopyDirectCallerFileCount=0
callerProbeToken=java-active-app-direct-caller-files-v1
generatedExcludePattern=service/rag/planner/SelfAskPlanner*
proofCommand=gradlew.bat :app:generateDupFqcnExcludes
secretPatternHitCount=0
```

`rawReportSha256` is the raw SHA-256 of the freshly generated detailed duplicate report from the final Stage-A invocation. `rawCollisionEvidenceFingerprint` is the lowercase 64-hex fingerprint on that report's exact SelfAsk collision row. The normalized file does not copy `generatedAt`, absolute build paths, raw command output, or unrelated collision rows.

The caller probe is deterministic and count-only. It enumerates canonical `*.java` files under the active `app/src/main/java_clean` root in case-insensitive path order, excludes the compatibility-copy declaration path, strips comments and string/character literals with the existing stdlib-only `scripts.harmony_catch_contract.strip_java_comments_and_strings_preserve_lines` helper, and counts files containing the identifier token `SelfAskPlanner`. The count must be zero. Its token names the algorithm; it is not a shell command and cannot redirect authority. The dependency is one-way from the audit to the pure contract module; the contract module must not import the audit.

### Selected predecessor row

`eligible-groups.jsonl` contains exactly one canonical ledger core row copied from the fresh Stage-A ledger after removing only public envelope fields. It retains:

```text
category=DUPLICATE_FQCN_SOURCE_COLLISION
severity=HIGH
fixEligibility=REVIEW_REQUIRED
status=HOLD
symbol=service.rag.planner.SelfAskPlanner
path=main/java/service/rag/planner/SelfAskPlanner.java
supersedes=[]
numericEvidence={
  duplicateFqcnGeneratedExcludeCount: 1,
  duplicateFqcnHardExcludeCount: 0,
  duplicateFqcnPackagedActiveCount: 0,
  duplicateFqcnSourceCollisionCount: 1
}
```

The loader first validates the unchanged public category contract and then applies the exact v3 admission decision. It does not synthesize `ELIGIBLE / OPEN`, alter numeric evidence, reuse the current provisional IDs, or substitute another duplicate group.

### Target-preimages contract

Wave 4 retains `awx.structural-repair-target-preimages.v1`. It contains exactly these five paths in canonical case-insensitive sort order:

```text
app/build.gradle.kts
app/src/main/java_clean/service/rag/planner/SelfAskPlanner.java
main/java/config/RagLightAdapters.java
main/java/service/rag/planner/SelfAskPlanner.java
src/test/java/com/example/lms/governance/SelfAskPlannerOwnershipContractTest.java
```

Every row records exact path, existence, Git state, byte size, and raw SHA-256. The root owner may remain `untracked`, and the config/call-path files may remain `modified`, only if those are their actual stable states at freeze. The app compatibility copy must be `clean`. The future ownership-test path must be absent with the canonical zero row. No state is normalized and no unrelated hunk is discarded.

Any target byte, state, path, containment, or reparse drift before registry admission invalidates the entire Stage-A tuple and requires a new final producer invocation. No audit script, public artifact, generated report, cache, log, ONNX path, `AnswerSanitizer` path, or RagControl path is admitted as a target.

The persistent wave loader validates the frozen row, evidence, decision, and target-preimage file as immutable historical inputs. The Phase-C/Phase-D preflight separately proves that live target bytes and Git states match those inputs through registration. The loader must not require those live bytes to remain equal forever: a later approved repair may change an admitted target, but only after a new versioned progress/event contract validates the transition from the frozen preimage.

## Strict Version Dispatch

Version handling is descriptor-local and fail-closed:

- Intake v1 remains accepted only with its existing fields and predecessor semantics.
- Intake v2 remains accepted only for exact `wave-0003`, ordinal 3, `EXPLICIT_REVIEW_PROMOTION`, and the existing RetrievalOrderService decision/evidence contract.
- Intake v3 is accepted only for exact `wave-0004`, ordinal 4, `EXACT_SELFASK_REVIEW_PROMOTION`, and the exact SelfAsk fields in this design.
- V1 rejects every v2/v3-only field. V2 rejects v3 schemas and SelfAsk substitutions. V3 rejects v1/v2 schema identifiers, field sets, decisions, evidence, targets, and candidates.
- Unknown versions and mixed-version rows fail before history aggregation or publication.
- Wave 4 accepts only zero-byte progress and journal inputs in this registration implementation. Any v1/v2/v3 progress row, event row, repair-target admission, or proof file under Wave 4 fails closed.
- `scripts/source_health_scorecard.py` delegates to the production registry/history/full-bundle validator and does not implement a parallel v3 classifier.

Admission/evidence failures use the existing `closure-admission-invalid` reason family. Descriptor/intake tuple mismatches use `closure-intake-mismatch`. No unknown version or extra row becomes an ignored no-op.

## Registry And Gradle Transaction

The registry remains `awx.structural-repair-wave-registry.v1` with its existing descriptor fields. Wave 4 uses:

| Field | Value or deterministic source |
|---|---|
| `waveId` | `wave-0004` |
| `ordinal` | `4` |
| `journalPath` | `verification/structural-repair-waves/wave-0004/journal.jsonl` |
| `proofRoot` | `.superpowers/sdd/structural-repair-waves/wave-0004` |
| `sourceBaselineId` | Fresh final Stage-A baseline ID |
| `sourceLedgerPayloadSha256` | Fresh final Stage-A canonical ledger-payload SHA-256 |
| `sourceMetricsSemanticHash` | Fresh final Stage-A metrics semantic hash |
| `intakeSummarySha256` | Raw SHA-256 of the Wave-4 v3 intake summary |
| `eligibleGroupsSha256` | Raw SHA-256 of the one unchanged SelfAsk HOLD predecessor row |
| `targetPreimagesSha256` | Raw SHA-256 of the exact five-path target-preimages file |

No future value is guessed in this design. The final Stage-A command and immediate freeze derive every tuple value.

Appending the fourth descriptor and updating `FROZEN_CLOSURE_WAVE_REGISTRY_SHA256` are one reviewed transaction. Old pin/new registry and new pin/altered registry both fail.

Both `dynamicRagQuantAudit` and `sourceHealthScorecard` declare these exact Wave-4 inputs with `PathSensitivity.RELATIVE`:

- `journal.jsonl`;
- the five fixed intake files;
- `repair-progress.jsonl`.

No Wave-4 proof glob is needed for registration because the proof namespace must be empty and any proof is invalid under this implementation. A later approved repair design must add an exact proof allowlist and corresponding validation before creating the first proof. Broad proof-root, `.superpowers`, build, report, cache, or verification-directory discovery remains forbidden.

## Stage-A Freeze And Publication Flow

### Phase A: immutable preflight

Before infrastructure or intake writes:

1. Verify Java 17, repository root, active source sets, branch, HEAD, staged count, index lock, top-level PatchDrop patches, worktrees, and active source-edit leases.
2. Freeze hashes for the accepted spec, plan after approval, public artifacts, current registry, audit producer, source-health validator, Gradle files, all registered wave inputs, the five candidate paths, and RagControl source/test.
3. Require no overlapping writer for audit/config/test targets.
4. Stop only the affected Wave-4 lane on a conflicting writer, path alias, reparse point, index lock, candidate drift, or immutable-history mismatch. Preserve unrelated dirty files.

### Phase B: infrastructure TDD under the three-wave registry

1. Add causal RED fixtures for strict v3 dispatch, exact candidate admission, no-caller evidence, empty-history enforcement, registry aggregation, Gradle inputs, and source-health delegation.
2. Implement the smallest v3-specific validation branch and test helpers.
3. Keep v1/v2 parity green, including the exact Wave-3 hard pins.
4. Keep the live registry at three descriptors and create no live Wave-4 file during this phase.
5. Run focused Python selectors, then the full dynamic-audit and source-health modules.

### Phase C: one final fresh public predecessor transaction

After Phase-B production/test bytes are stable, run exactly one final repository producer invocation:

```text
gradlew.bat dynamicRagQuantAudit
```

Use Java 17, split build outputs, a stable Desktop host ID, one isolated Gradle user home, and one isolated project cache. The invocation must run the existing duplicate-FQCN producer dependency and produce the three public quantitative artifacts plus its configured detailed duplicate report.

Immediately after the command, without another Gradle invocation:

1. Validate the public bundle through `load_and_validate_current_bundle`.
2. Validate it through `validate_current_quant_bundle`.
3. Capture the baseline ID, canonical ledger-payload hash, metrics semantic hash, branch, HEAD, source-set identity, manifest identity, and raw public artifact hashes.
4. Select exactly one SelfAsk ledger row satisfying the exact v3 predicate.
5. Select exactly one SelfAsk detailed-report collision with the exact root/app pair and `GENERATED_EXCLUDE` state.
6. Recompute the deterministic app direct-caller file count and require zero.
7. Capture all five target states, sizes, and hashes.
8. Recheck RagControl source/test preservation.

These values form one indivisible Stage-A tuple. If another producer-dependent task runs, a public/report/target byte changes, a target Git state changes, or the no-caller result changes before the Wave-4 files are frozen, discard the tuple and begin Phase C again. Do not select a fallback candidate.

### Phase D: freeze Wave 4

From the validated Stage-A tuple:

1. Copy the exact envelope-free SelfAsk predecessor row.
2. Normalize the exact detailed collision and no-caller counts into duplicate-evidence v2.
3. Construct and hash admission-decision v2.
4. Freeze the five target preimages.
5. Write intake-summary v3 last so it pins both new decision/evidence files.
6. Create zero-byte `repair-progress.jsonl` and journal files.
7. Require `proofs/` to contain no files.
8. Validate the unregistered Wave-4 descriptor and intake directly before registry mutation.

### Phase E: register and publish

1. Append the ordinal-4 descriptor to canonical registry bytes.
2. Update the exact code pin in the same scoped transaction.
3. Add exact Wave-4 inputs to both Gradle consumers.
4. Run focused causal tests, v1/v2 parity, full Python modules, and direct four-wave history validation.
5. Run `dynamicRagQuantAudit`, then `sourceHealthScorecard`, through the preserved isolated caches.
6. Validate the republished bundle through both validators.
7. Build two non-overwriting normalized semantic projections around the final allowed producer chain and require byte identity.
8. Recheck target, RagControl, old-wave, registry, secret, staged, lock, lease, PatchDrop, and diff gates.

The Stage-A tuple remains the descriptor predecessor even though registry/code/Gradle changes legitimately change the post-registration public baseline. Final report hashes describe the final bundle and never overwrite the frozen predecessor tuple.

## Expected State After Registration

Assuming no unrelated detector drift, empty Wave 4 changes registry/history identity but not repair credit:

| Counter | Before | After Wave-4 registration |
|---|---:|---:|
| Registered waves | 3 | 4 |
| Closure events | 15 | 15 |
| Rejected groups | 5 | 5 |
| Verified-closed groups | 10 | 10 |
| `OPEN / ELIGIBLE` groups | 1 | 1 |
| Target gap | 889 | 889 |
| Public SelfAsk state | `REVIEW_REQUIRED / HOLD` | `REVIEW_REQUIRED / HOLD` |
| Wave-4 progress rows | 0 | 0 |
| Wave-4 events | 0 | 0 |
| Repair credit added | 0 | 0 |

The registry hash, history registry hash, audit run ID, semantic artifact hash, and linked artifact hashes change. Wave-1 through wave-3 event IDs, proof hashes, and terminal states do not.

## Failure, Rollback, And HOLD Rules

The following stop before registry or public-output replacement:

- missing or invalid final Stage-A public bundle;
- zero or more than one SelfAsk predecessor;
- provisional issue/group/fingerprint values reused instead of fresh ledger values;
- wrong category, severity, eligibility, status, symbol, path, or numeric evidence;
- zero or more than one detailed SelfAsk collision;
- wrong root/app pair, packaging state, generated pattern, collision fingerprint, or raw-report hash;
- any direct app caller file outside the compatibility copy;
- modified, deleted, renamed, unmerged, or absent app compatibility copy;
- missing or drifted root owner, call/config seam, or packaging config;
- a present, malformed, or noncanonical preimage for the dedicated future ownership-test path;
- any RagControl identity or path in Wave 4;
- missing, extra, aliased, absolute, escaped, case-colliding, or reparse target;
- noncanonical JSON/NDJSON, unknown field, duplicate key, nonfinite number, malformed hash, or wrong transitive hash;
- any nonempty Wave-4 progress, journal, or proof namespace;
- intake v3 used outside exact `wave-0004` ordinal 4;
- v1/v2 behavior or bytes changed;
- registry pin mismatch or noncontiguous descriptor order;
- source-health disagreement, semantic nondeterminism, secret hit, or public publication failure.

If registry/pin/Gradle mutation occurs but final registration or publication cannot pass, restore the exact pre-transaction three-wave registry bytes, code-pin bytes, and Gradle bytes using their frozen preimages. Then validate the preserved three-wave public bundle. Wave-4 intake files may remain only as unregistered private evidence; they grant no loader authority, event ownership, or repair credit. Do not delete them merely to manufacture a clean tree.

Every HOLD report records:

```text
holdScope
firstBlockingRule
blockingEvidence
independentWorkCompleted
repositoryWideHold=false
```

`repositoryWideHold` becomes true only if the first blocking rule genuinely makes every authorized lane unsafe.

## Security, Privacy, And Concurrency

- Canonical JSON is UTF-8 without BOM, sorted keys, compact separators, and exactly one trailing LF.
- Nonempty NDJSON has one canonical object plus LF per row and no blank records; the empty progress and journal are exactly zero bytes.
- Paths are canonical repository-relative POSIX paths and pass component-by-component reparse and containment validation.
- Evidence contains only approved enum values, counts, hashes, FQCN, command/probe tokens, and repository-relative paths.
- Raw Gradle output, environment dumps, absolute roots, host names, credentials, authorization data, provider payloads, and raw user content are not persisted.
- New or changed public/private evidence has zero secret-pattern hits and zero Windows absolute-path hits.
- Only one owner writes audit production, audit tests, source-health tests, Gradle, registry, or Wave-4 paths at a time.
- The final producer/publication chain is sequential. Gradle tasks sharing source or project caches are not parallelized.
- No source-edit lease is acquired because this transaction changes no application source. A later source repair must run the mandatory three-way source-edit preflight and owner lease independently.

## TDD And Verification Strategy

### Intake-v3 RED/GREEN

- Exact Wave-4 SelfAsk intake passes in an isolated fixture.
- Zero or two predecessors fail.
- Wave ID or ordinal other than `wave-0004`/4 fails.
- Intake v1 and v2 reject every v3 schema/field; intake v3 rejects v1/v2 substitutions.
- Wave-3 RetrievalOrderService v2 fixtures remain byte- and behavior-identical.
- Category-wide promotion, another duplicate row, and any RagControl identity fail.
- Wrong severity, status, eligibility, path, symbol, numeric evidence, or `supersedes` fails.
- Missing, extra, noncanonical, or mismatched decision/evidence fields fail.
- Decision ID, approved-design hash, raw evidence hash, and summary transitive hashes are independently recomputed.

### Candidate and target RED/GREEN

- Wrong pair, packaging state, generated pattern, raw collision fingerprint, or aggregate counts fail.
- Any hard exclude or packaged-active count fails.
- Direct-caller file count above zero or wrong probe token/scope fails.
- A modified/renamed/deleted/unmerged/absent compatibility copy fails.
- The exact root owner may be untracked only when its frozen preimage and stable state match.
- The two already-modified guard files are accepted only with exact frozen state and hashes.
- Any missing, extra, aliased, reordered, absolute, escaped, case-colliding, or reparse target fails.
- The future ownership-test path must be absent and represented by the exact canonical zero preimage row.
- The unrelated `AppCleanSourceZombieContractTest` path assertion cannot satisfy this requirement.

### Empty-history and version boundaries

- Zero-byte Wave-4 progress and journal plus an empty proof namespace pass.
- Any progress row, event row, repair-target admission, or proof file fails.
- No v1/v2 progress or event can claim the v3 predecessor.
- Empty Wave 4 contributes one wave and one empty journal hash but no event, terminal state, or credit.

### Registry, source health, and Gradle

- Four descriptors load in canonical ordinal order.
- Old pin/new registry and new pin/altered registry fail.
- Waves 1-3 retain exact predecessors, events, active tips, proofs, and terminal rows.
- Cross-wave issue, group, path, journal, proof-root, target, and active-tip conflicts fail.
- Both Gradle consumers declare only the exact seven Wave-4 files with relative sensitivity.
- No broad Wave-4 proof or evidence directory is admitted.
- Source health uses the production loader and rejects the same tampered fixtures.
- Publication validates staged canonical bytes, replaces baseline then ledger then metrics, and restores all prior bytes on failure.

### Final verification ladder

1. Focused v3 causal selectors.
2. Existing v1/v2 parity selectors.
3. Full `scripts.test_dynamic_rag_quant_audit`.
4. Full `scripts.test_source_health_scorecard`.
5. Direct four-wave registry/history validation.
6. Isolated-cache `dynamicRagQuantAudit`.
7. Isolated-cache `sourceHealthScorecard`.
8. Both current-bundle validators.
9. Two normalized semantic projections with byte equality.
10. Canonical, privacy, preservation, staged/index-lock/lease/PatchDrop, and `git diff --check` gates.

## Changed-File Responsibility Map

Expected infrastructure and registration ownership:

| Path | Responsibility |
|---|---|
| `scripts/dynamic_rag_quant_audit.py` | Strict intake-v3, SelfAsk decision/evidence, empty-history, and registry trust owner |
| `scripts/test_dynamic_rag_quant_audit.py` | Causal v3, parity, security, registry, rollback, and aggregate tests |
| `scripts/test_source_health_scorecard.py` | Four-wave fixture and shared-loader parity tests only |
| `build.gradle.kts` | Exact Wave-4 inputs for both public tasks |
| `verification/structural-repair-waves/registry.json` | Canonical four-wave registry |
| `.superpowers/sdd/structural-repair-waves/wave-0004/**` | Frozen v3 intake and zero-byte progress |
| `verification/structural-repair-waves/wave-0004/journal.jsonl` | Zero-byte Wave-4 terminal journal |

`scripts/source_health_scorecard.py` remains unchanged unless a focused failing test proves its existing delegation cannot consume a valid fourth descriptor. Reimplementing v3 classification there is forbidden.

The transaction must not modify:

- either SelfAsk source file;
- `main/java/config/RagLightAdapters.java`;
- `app/build.gradle.kts`;
- `SelfAskPlannerOwnershipContractTest.java` (it remains absent);
- any RagControl file;
- any wave-1, wave-2, or wave-3 file.

## Acceptance Criteria

The registration implementation is accepted only when all of the following are freshly proven:

1. The final Stage-A public bundle and detailed duplicate report validate and are content-bound to one producer invocation.
2. Exactly one fresh SelfAsk row becomes exactly one v3 predecessor; no provisional ID is trusted without reproduction.
3. The detailed report reproduces the exact SelfAsk pair, `GENERATED_EXCLUDE`, exact pattern, zero hard/packaged-active counts, and a raw collision fingerprint.
4. The active app source root contains zero direct caller files outside the compatibility copy.
5. The app compatibility copy is clean; the dedicated future ownership-test path is absent; all five target state/size/hash rows are exact and stable.
6. No RagControl or fallback-candidate identity enters Wave 4, and RagControl source/test bytes remain unchanged.
7. All Wave-4 JSON is canonical; progress and journal are zero bytes; proofs, progress rows, events, and repair-target admissions are absent.
8. Wave-1 through wave-3 bytes, events, active tips, proof identities, and terminal states remain unchanged.
9. The four-wave registry and code pin agree exactly, with no path alias or reparse escape.
10. Both Gradle consumers declare every fixed Wave-4 input and no broad discovery path.
11. Production history reports four waves, 15 events, five rejected groups, and ten verified groups.
12. The public ledger reports one eligible group, ten verified groups, and target gap 889, assuming no independently explained detector drift.
13. Public SelfAsk remains `REVIEW_REQUIRED / HOLD` and Wave 4 adds zero repair credit.
14. Focused and full Python suites, both Gradle public tasks, both bundle validators, and two semantic projections pass.
15. No staged file, index lock, source-edit lease, top-level PatchDrop patch, secret hit, absolute-path leak, unrelated cleanup, commit, or push is introduced.
16. The parent approximately-1,000 structural-repair goal remains incomplete.

## Future SelfAsk Repair Boundary

Wave-4 registration freezes evidence only. A later task must write and obtain approval for a separate bounded source-repair design. That design must:

- recheck the five frozen preimages and current ownership/call paths;
- create the dedicated `SelfAskPlannerOwnershipContractTest` RED against the exact `service/rag/planner` app path; do not reuse the unrelated `com/example/lms/service/rag/planner` assertion;
- decide whether retirement means deletion, relocation, or another bounded transformation;
- obtain explicit destructive authority before deleting a tracked or untracked source file;
- run the mandatory three-way source-edit preflight and existing source-owner lease;
- establish causal structural and behavior RED/GREEN evidence;
- preserve the root SelfAsk component and `RagLightAdapters` behavior;
- define exact v3 repair-target, progress, proof, event, and fingerprint-disposition schemas before any append;
- prove no packaged duplicate and no app caller regression;
- append no terminal event until every gate passes.

This design does not predetermine that future choice.

## One Next Action After Written-Spec Approval

Invoke `superpowers:writing-plans` and create a bite-sized implementation plan ordered as: immutable preflight, v3 causal TDD under the three-wave pin, full parity gates, one final Stage-A producer transaction, immediate exact SelfAsk freeze, direct unregistered-wave validation, registry/code-pin/Gradle registration, atomic public publication, and final preservation/determinism verification. The plan must contain no application-source mutation or Wave-4 terminal event.

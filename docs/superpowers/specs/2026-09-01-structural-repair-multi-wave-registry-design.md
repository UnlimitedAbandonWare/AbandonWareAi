# Structural Repair Multi-Wave Registry Design

**Date:** 2026-09-01  
**Architecture decision:** Approved in the conversation on 2026-09-01 as recommended option 1  
**Written-spec status:** Approved on 2026-09-01; canonical trailing-LF wording corrected during implementation-plan mapping  
**Parent objective:** Preserve the unresolved approximately-1,000 structural-repair goal while earning closure credit only for independently proven root-cause repairs  
**Predecessor design:** `docs/superpowers/specs/2026-08-31-structural-debt-verified-closure-wave-design.md`

## Objective

Replace the single hard-coded verified-closure wave boundary with a code-pinned, canonical multi-wave registry. The registry must let the quantitative audit load independently frozen closure waves, validate them with the existing fail-closed proof contract, aggregate their terminal histories into the one existing public baseline/ledger/metrics bundle, and preserve every valid wave-1 identity and proof.

The first new wave freezes the four current `OPEN / ELIGIBLE` broad-catch groups that are not owned by wave 1. After the infrastructure is green, the first application-source cycle closes only `FallbackAwareChatModel` by adding a redacted local diagnostic to its fail-soft route-metadata catch without changing fallback, provider, retry, health, or physical-attempt behavior.

This design does not claim that the parent approximately-1,000-repair goal is complete. It creates a repeatable trust boundary for later serial repair waves and specifies one additional genuine repair cycle.

## Current Evidence And Problem

The current closure loader is a correct single-wave implementation but an invalid admission boundary for later work:

- `scripts/dynamic_rag_quant_audit.py` hard-codes one journal at `verification/structural-repair-closure-journal.jsonl` and one proof root at `.superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave`.
- Its frozen wave-1 intake contains 11 root-cause groups and 34 declared target preimages.
- A terminal event must match a predecessor row from that intake. An event for an issue absent from the intake fails with `closure-intake-mismatch` before progress or proof validation.
- Editing the three wave-1 intake files also fails closed because their raw SHA-256 values and predecessor trust tuple are code-pinned.
- The current aggregate has 10 active terminal events: five `REJECTED_FALSE_POSITIVE` and five `VERIFIED_CLOSED`.
- The current ledger has five `OPEN / ELIGIBLE` groups. Four are outside wave 1; `RagControlRuntimeAdapter` is already wave-1-owned and remains on its existing contract HOLD.

The current wave-1 trust tuple is immutable input evidence:

| Field | Frozen value |
|---|---|
| `sourceBaselineId` | `547d63518eacd082f7bfdcd85ba2c93f76de2534326a845b23b7b0ba506feebe` |
| `sourceLedgerPayloadSha256` | `fb13386860dc1385d7b08c4e0f1e71c954c9a1af3691e6696aa486d190f3c1fc` |
| `sourceMetricsSemanticHash` | `9b7b018ce4e09719f32836227a0e95dd1abb3fc64d69099fe51f607bd2925c8f` |
| `intake/intake-summary.json` SHA-256 | `ff460bbcc4faaaf4333b08c8d194f168f5040ba836de68ec1ca862b3fb7149d1` |
| `intake/eligible-groups.jsonl` SHA-256 | `4fbd29f284cc646a6e82b15ce98d47ff08e7aed4910bee886616782cc3dcb888` |
| `intake/target-preimages.json` SHA-256 | `8be31b01f7af04fabab40a6599cf68d6e6ebc8f28318cccbef7a0c93bb4364ae` |

The migration preimage also includes the current wave-1 journal SHA-256 `a2ee50ae166dd338e6b09f512193747b28312a52d32d5be44ffa6452d6cfc3c4` and proof-set SHA-256 `1c677676c78a99b85c8f23ea2e0467574fbaa1d3a7dabd19d9cdee75de16205c`. These two values are migration-parity assertions, not permanent seals: wave 1 retains its existing append-only correction and remaining-owned-group behavior after the migration.

## Approved Outcome

The implementation has one registry, multiple isolated waves, and one public quantitative truth:

```text
code-pinned registry
  -> ordered wave descriptors
       -> wave-local frozen intake
       -> wave-local append-only progress and terminal journal
       -> wave-local referenced proofs
  -> global ownership and event-graph validation
  -> one aggregate ClosureHistory
  -> existing historical overlay
  -> one transactional baseline + ledger + metrics bundle
  -> source-health validation of the same aggregate
```

There is no second public debt ledger, no mutable discovery of arbitrary proof directories, and no inference that a current detector row authorizes a historical closure event.

## Scope

### In scope

- A canonical registry at `verification/structural-repair-waves/registry.json`.
- A code constant that pins the exact canonical registry bytes.
- Registry, descriptor, path, intake, event, proof, and cross-wave validation.
- A migration adapter for the existing wave-1 journal and proof root without moving or rewriting them.
- A wave-2 journal at `verification/structural-repair-waves/wave-0002/journal.jsonl`.
- A wave-2 proof root at `.superpowers/sdd/structural-repair-waves/wave-0002`.
- A v2 aggregate closure-history summary and explicit v2 audit/metrics schemas.
- The existing single public structural baseline, debt ledger, and quantitative metrics bundle.
- Source-health reconstruction through the same registry loader.
- Gradle inputs and CLI wiring for the registry and its allowlisted descendants.
- Two-phase bootstrap: wave-1-only migration, then wave-2 freeze and registration.
- Freezing four non-overlapping wave-2 root-cause groups and eight owner/test preimages.
- One TDD repair and verified-closure cycle for `FallbackAwareChatModel`.
- Determinism, rollback, path-containment, reparse, redaction, duplicate-owner, compile, and secret gates.

### Out of scope

- Replacing, extending, or rewriting the three wave-1 intake files.
- Moving or rewriting the existing wave-1 journal, progress ledger, or proof files.
- Admitting `RagControlRuntimeAdapter` to wave 2; wave 1 already owns that group.
- Closing the other three wave-2 groups in the first implementation batch.
- Changing provider selection, fallback eligibility, retry counts, request budgets, endpoint health, route promotion, physical provider calls, or response semantics.
- Adding a second ledger, a second metrics producer, a generic workflow engine, or a database.
- Browser, Computer, Supabase, external-provider, production, deployment, or credential operations.
- Staging, committing, pushing, or deploying without separate operation-level authority.
- Treating lines changed, catches touched, tests added, events appended, or groups rejected as repair credit.
- Marking the parent approximately-1,000-repair goal complete after this wave.

## Terms And Ownership

| Term | Meaning | Sole owner |
|---|---|---|
| Registry | Canonical ordered list of trusted closure waves | `scripts/dynamic_rag_quant_audit.py` |
| Wave | One immutable intake plus its isolated progress, journal, and proof namespace | Registry descriptor and wave-local files |
| Predecessor row | Frozen ledger row eligible for a wave-local terminal event | Wave intake |
| Progress row | Private `PATCHED_UNVERIFIED` proof of a changed target before closure | Wave-local `repair-progress.jsonl` |
| Terminal event | `REJECTED_FALSE_POSITIVE` or `VERIFIED_CLOSED` journal row | Wave-local journal |
| Active tip | The one non-superseded terminal event for one group | Aggregate event-graph validator |
| Aggregate history | Deterministic union of all validated wave histories | `load_closure_history` |
| Public truth | Existing transactional baseline, ledger, and metrics outputs | `dynamic_rag_quant_audit.py` |
| Secondary validator | Reconstruction of the public quantitative bundle | `source_health_scorecard.py` |

`source_health_scorecard.py` consumes the registry loader; it does not reimplement registry semantics or become another closure-state owner.

## Canonical Registry Contract

### Path and schema

The sole accepted registry path is:

```text
verification/structural-repair-waves/registry.json
```

The root JSON object has exactly two fields:

```json
{
  "schemaVersion": "awx.structural-repair-wave-registry.v1",
  "waves": []
}
```

Every member of `waves` has exactly these fields:

```json
{
  "waveId": "wave-0001",
  "ordinal": 1,
  "journalPath": "verification/structural-repair-closure-journal.jsonl",
  "proofRoot": ".superpowers/sdd/2026-08-31-structural-debt-verified-closure-wave",
  "sourceBaselineId": "547d63518eacd082f7bfdcd85ba2c93f76de2534326a845b23b7b0ba506feebe",
  "sourceLedgerPayloadSha256": "fb13386860dc1385d7b08c4e0f1e71c954c9a1af3691e6696aa486d190f3c1fc",
  "sourceMetricsSemanticHash": "9b7b018ce4e09719f32836227a0e95dd1abb3fc64d69099fe51f607bd2925c8f",
  "intakeSummarySha256": "ff460bbcc4faaaf4333b08c8d194f168f5040ba836de68ec1ca862b3fb7149d1",
  "eligibleGroupsSha256": "4fbd29f284cc646a6e82b15ce98d47ff08e7aed4910bee886616782cc3dcb888",
  "targetPreimagesSha256": "8be31b01f7af04fabab40a6599cf68d6e6ebc8f28318cccbef7a0c93bb4364ae"
}
```

The example above is the normative wave-1 descriptor. Wave-2 hash values are not discretionary placeholders: the bootstrap procedure derives each one from the named Stage-A artifact and refuses registration if it cannot reproduce the value. The written design therefore specifies their derivation rather than pretending their future bytes are already known.

### Canonical bytes and registry pin

The registry must satisfy all of the following:

1. UTF-8 decoding is strict.
2. A UTF-8 BOM is forbidden.
3. The parsed value is a JSON object with the exact root and descriptor field sets above.
4. Raw file bytes equal the repository's existing `canonical_json_bytes(parsed)` result exactly. There is exactly one trailing LF and no other insignificant whitespace.
5. Raw SHA-256 equals the code constant `FROZEN_CLOSURE_WAVE_REGISTRY_SHA256`.
6. Updating the registry and updating that constant are one reviewed infrastructure transaction.
7. Runtime environment variables, directory discovery, mtimes, Git status, and current public artifacts cannot override the pin.

The pin protects the admitted wave list and its trust roots. Journal bytes are intentionally not pinned in a descriptor because each journal is append-only and may gain a valid correction or a terminal event for a group already owned by that wave.

### Descriptor field validation

| Field | Contract |
|---|---|
| `waveId` | Exact pattern `wave-[0-9]{4}` and equal to `wave-{ordinal:04d}` |
| `ordinal` | Integer greater than zero; booleans are invalid |
| `journalPath` | Canonical repository-relative POSIX path to one regular `.jsonl` file |
| `proofRoot` | Canonical repository-relative POSIX path to one directory |
| Three `source*` fields | Lowercase 64-hex predecessor trust tuple |
| Three `*Sha256` intake fields | Lowercase 64-hex raw file hashes |

Paths must be nonblank, use `/`, contain no `.` or `..` segment, contain no drive, UNC prefix, URI scheme, NUL, wildcard, or case-ambiguous alias, and resolve beneath the verified repository root. The registry, every journal, every proof root, every intake/progress file, and every referenced proof must pass component-by-component reparse checks. Canonical path identities are compared after Windows case folding so lexical aliases cannot bypass uniqueness.

### Canonical descriptors

Wave 1 retains the paths and exact trust tuple shown above.

Wave 2 has these fixed paths and a Stage-A-derived trust tuple:

| Field | Wave-2 value or deterministic source |
|---|---|
| `waveId` | `wave-0002` |
| `ordinal` | `2` |
| `journalPath` | `verification/structural-repair-waves/wave-0002/journal.jsonl` |
| `proofRoot` | `.superpowers/sdd/structural-repair-waves/wave-0002` |
| `sourceBaselineId` | Stage-A v2 baseline `baselineId` |
| `sourceLedgerPayloadSha256` | Stage-A v2 ledger payload SHA-256 selected by the existing intake-freeze procedure |
| `sourceMetricsSemanticHash` | Stage-A v2 metrics `semanticArtifactHash` |
| `intakeSummarySha256` | Raw SHA-256 of wave-2 `intake/intake-summary.json` |
| `eligibleGroupsSha256` | Raw SHA-256 of wave-2 `intake/eligible-groups.jsonl` |
| `targetPreimagesSha256` | Raw SHA-256 of wave-2 `intake/target-preimages.json` |

The Stage-A baseline is historical predecessor identity. It is not required to equal later baselines after the wave-2 descriptor, registry pin, progress rows, source repairs, or public bundle change.

## Wave-Local Contract

Each registered proof root has the existing v1 layout:

```text
<proofRoot>/
  intake/
    intake-summary.json
    eligible-groups.jsonl
    target-preimages.json
  repair-progress.jsonl
  proofs/
    <rootCauseGroupId>/
      red-summary.json
      green-summary.json
      event-baseline.json
```

Angle-bracket tokens in this layout are grammar metavariables, not unresolved filenames. The concrete group ID is always lowercase 64-hex.

The existing schemas remain unchanged:

- `awx.structural-repair-intake.v1`
- `awx.structural-repair-target-preimages.v1`
- `awx.structural-repair-progress.v1`
- `awx.structural-repair-proof-summary.v1`
- `awx.structural-repair-event-baseline.v1`
- `awx.structural-repair-closure-event.v1`

The descriptor's three predecessor fields must equal the intake summary. Its three intake hashes must equal the raw bytes of the named files. The intake summary's ordered issue IDs, group IDs, and evidence fingerprints must match the canonical intake rows. Target preimages must use canonical repository-relative paths, contain the declared owner/test targets, and match current bytes at freeze time.

A registered wave always validates its intake, including when its journal is empty. Therefore:

- the journal must exist as canonical NDJSON; a new empty journal is a zero-byte file;
- the proof root and all three intake files must exist and validate;
- `repair-progress.jsonl` must exist as canonical NDJSON; it may be zero bytes;
- proof files are required only when referenced by an event or progress row;
- unreferenced files grant no credit and do not enter the aggregate proof-set hash.

`gate-evidence.json`, `red-hold-summary.json`, and `restored-control-summary.json` remain optional private reporting evidence. They may be registered as Gradle inputs through the exact filename allowlist, but the terminal-event loader does not infer a gate result from their presence.

## Loader Interfaces And Data Flow

The implementation introduces these pure ownership seams in `scripts/dynamic_rag_quant_audit.py`:

```python
@dataclass(frozen=True)
class ClosureWaveDescriptor:
    wave_id: str
    ordinal: int
    journal_path: Path
    proof_root: Path
    source_baseline_id: str
    source_ledger_payload_sha256: str
    source_metrics_semantic_hash: str
    intake_summary_sha256: str
    eligible_groups_sha256: str
    target_preimages_sha256: str

@dataclass(frozen=True)
class ClosureRegistry:
    path: Path
    payload_sha256: str
    waves: tuple[ClosureWaveDescriptor, ...]

@dataclass(frozen=True)
class ClosureWaveHistory:
    descriptor: ClosureWaveDescriptor
    predecessor_rows: tuple[dict[str, Any], ...]
    all_events: tuple[dict[str, Any], ...]
    active_events: tuple[dict[str, Any], ...]
    journal_payload_sha256: str
    proof_pairs: tuple[tuple[str, str], ...]

def load_closure_registry(*, root: Path, registry_path: Path) -> ClosureRegistry: ...

def load_closure_wave(
    *, root: Path, descriptor: ClosureWaveDescriptor
) -> ClosureWaveHistory: ...

def load_closure_history(
    *, root: Path, registry: ClosureRegistry
) -> ClosureHistory: ...
```

Names may use the repository's underscore conventions, but these responsibility boundaries are normative:

- `load_closure_registry` alone parses and pins the registry and resolves its safe path identities.
- `load_closure_wave` reuses the current intake, progress, proof, event, and active-tip validators for exactly one descriptor.
- `load_closure_history` performs only cross-wave checks, deterministic aggregation, and aggregate hash/count construction.
- `apply_closure_overlay` continues to consume one `ClosureHistory`; it does not learn wave-specific path rules.

`AuditInputs` replaces `closure_journal` and `closure_proof_root` with `closure_wave_registry`. `AuditBundle` carries the validated registry input needed for repeat validation. There is no compatibility mode that silently falls back to the legacy hard-coded paths when the registry is missing.

The build order is:

1. Resolve and validate the repository root and the fixed registry argument.
2. Load and byte-pin the registry before building the source-baseline manifest.
3. Derive the exact registry/journal baseline-exclusion set from validated descriptors.
4. Build the source baseline and validate the ordinary harmony, test-tree, duplicate-FQCN, and Git inputs.
5. Build the current detection rows.
6. Load and validate each registered wave in ascending ordinal order.
7. Enforce global cross-wave invariants.
8. Aggregate predecessor rows, all events, active events, hashes, and counts.
9. Apply the existing historical overlay to current rows.
10. Sort, terminal-preserving-cap, summarize, link, stage, validate, and transactionally publish the one public bundle.

Filesystem enumeration order never affects registry order, event order, proof order, hashes, or output rows.

## Cross-Wave Invariants

The aggregate loader rejects the complete closure input when any of these invariants fails:

1. Wave IDs are unique.
2. Ordinals are unique, start at one, are contiguous, and appear in ascending order.
3. `waveId` and `ordinal` encode the same number.
4. Journal paths are pairwise distinct by canonical Windows path identity.
5. Proof roots are pairwise distinct and do not contain or alias another proof root.
6. No journal path is inside any proof root.
7. No `issueId` is owned by more than one wave.
8. No `rootCauseGroupId` is owned by more than one wave.
9. One wave cannot split different issues from the same root-cause group across descriptors.
10. Event IDs are globally unique, even if the duplicate bytes appear in different journals.
11. A progress ID or referenced proof identity resolves only inside its event's wave proof root.
12. `supersedesEventId` names an earlier event in the same wave and same root-cause group.
13. Cross-wave supersession is forbidden.
14. Supersession graphs are acyclic.
15. Each group with terminal history has exactly one active terminal tip.
16. An event's predecessor issue and group belong to that event's descriptor.
17. Current target postimages continue to match every active terminal event unless a valid later same-wave event supersedes it.
18. A `VERIFIED_CLOSED` event still requires the existing matching `PATCHED_UNVERIFIED` row and nonzero changed-target/scoped-diff hashes.
19. A `REJECTED_FALSE_POSITIVE` event still requires identical target pre/post hashes, a zero scoped diff, and no patch-state proof.
20. An active original fingerprint cannot remain detector-active for a claimed verified closure.

All event field sets, canonical event IDs, disposition tuples, proof schemas, proof commands, redaction checks, gate values, and terminal-row overlay rules from the predecessor design remain unchanged.

## Deterministic Aggregate History

The nested public summary schema becomes `awx.structural-repair-history-summary.v2` and has exactly these fields:

```json
{
  "schemaVersion": "awx.structural-repair-history-summary.v2",
  "waveRegistrySha256": "<lowercase-64-hex>",
  "waveCount": 2,
  "journalSetSha256": "<lowercase-64-hex>",
  "proofSetSha256": "<lowercase-64-hex>",
  "eventCount": 10,
  "rejectedFalsePositiveRootCauseGroups": 5,
  "verifiedClosedRootCauseGroups": 5
}
```

The angle-bracket values in this schema example denote validated lowercase 64-hex values; they are not implementation placeholders.

The hashes are defined exactly:

```text
journalRows = [
  [waveId, journalPath, sha256(rawJournalBytes)]
  for descriptors in ascending ordinal order
]
journalSetSha256 = sha256(canonical_json_bytes(journalRows))

proofRows = sorted([
  [waveId, proofId, verifiedFileSha256]
  for every proof or patch-state row referenced by a valid event
])
proofSetSha256 = sha256(canonical_json_bytes(proofRows))
```

An empty registered journal contributes its raw empty-file SHA-256 to `journalRows`. An empty proof set contributes the canonical empty list. `eventCount` counts all valid journal rows, including superseded rows. The rejected and verified counts use only active tips and count distinct root-cause groups.

Aggregate sequences are deterministic:

- predecessor rows: descriptor ordinal, then existing canonical intake-row order;
- all events: descriptor ordinal, then journal line order;
- active events: the same order filtered to active IDs;
- public ledger rows: the existing ledger sort key after overlay;
- proof rows: lexical tuple order shown above.

## Public Schema And Identity Changes

The following schemas explicitly bump:

| Surface | New schema |
|---|---|
| Audit envelope | `awx.structural-design-audit.v2` |
| Quantitative metrics | `awx.dynamic-rag-quant-audit-metrics.v2` |
| Nested closure summary | `awx.structural-repair-history-summary.v2` |

These schemas remain v1:

- `awx.structural-design-baseline.v1`
- `awx.structural-design-debt-ledger-row.v1`
- all event, progress, intake, target-preimage, proof-summary, and event-baseline schemas listed above.

The existing semantic hash chain remains intact. `semanticArtifactHash` includes the entire v2 closure summary. `auditRunId` retains every current component and replaces the single-wave history components with:

```text
waveRegistrySha256 | journalSetSha256 | proofSetSha256
```

`source_health_scorecard.py` reconstructs the same summary and the same run ID with `load_closure_registry` plus `load_closure_history`. A stale v1 public metrics file fails schema validation after the v2 producer is installed; Gradle ordering must regenerate the dynamic audit before source health runs.

The existing ledger arithmetic remains:

- `verifiedClosedRootCauseGroups` counts distinct `VERIFIED_CLOSED` overlay rows;
- `eligibleRootCauseGroups` counts only `ELIGIBLE / OPEN` groups;
- `targetGap = max(0, 900 - distinct(open eligible groups union verified groups))`;
- rejected groups earn zero closure credit;
- the wave report separately records `closureTargetGap = max(0, 900 - verifiedClosedRootCauseGroups)`.

## Baseline Recursion And Exact Exclusions

The registry and journals are mutable evidence inputs whose content includes or binds historical baseline identities. Including them in the source-baseline manifest would create recursive or irrelevant baseline churn.

After the registry passes raw-byte, schema, pin, and path validation—but before source files are enumerated—the baseline builder adds exactly these paths to its generated-verification exclusion set:

- `verification/structural-repair-waves/registry.json`;
- every validated descriptor `journalPath`.

The existing exact exclusions for the public audit outputs and legacy wave-1 journal are deduplicated. The implementation must not exclude `verification/`, `verification/structural-repair-waves/`, or another broad prefix. A sibling file not explicitly named by the validated registry remains visible to the baseline policy.

Private `.superpowers/sdd/**` material continues under the existing private/generated evidence policy. The registry cannot add arbitrary source exclusions through `proofRoot`; proof roots are validated only for closure evidence and never interpreted as active-source roots.

Tests must prove that:

- changing only a journal or the registry changes history/audit identity but not the baseline ID;
- changing an unregistered sibling under `verification/structural-repair-waves/` remains baseline-visible if the current baseline rules otherwise include it;
- an invalid registry cannot influence exclusions because validation fails before enumeration;
- no path alias can make a source file look like a journal.

## CLI And Gradle Contract

The quantitative CLI removes:

```text
--closure-journal
--closure-proof-root
```

and requires:

```text
--closure-wave-registry verification/structural-repair-waves/registry.json
```

The argument uses the same fixed-output discipline as public destinations: absolute paths, traversal, aliases, reparse components, and any path other than the exact canonical registry destination fail before input or output I/O.

`dynamicRagQuantAudit` declares, with relative path sensitivity:

- the registry file;
- every registered journal;
- each wave's three intake files;
- each wave's `repair-progress.jsonl`;
- referenced `red-summary.json`, `green-summary.json`, and `event-baseline.json` files;
- private `gate-evidence.json`, `red-hold-summary.json`, and `restored-control-summary.json` files only when they match the exact wave-root and filename allowlist.

Gradle may use descriptor-derived file collections or explicit wave-root file trees, but it must not register unrestricted build outputs, logs, caches, Markdown reports, raw command logs, or all of `.superpowers`. The Python loader remains authoritative; Gradle input discovery never grants wave admission.

Task dependency order remains:

```text
harmonyPressureReport
testTreeContaminationReport
:app:generateDupFqcnExcludes
  -> dynamicRagQuantAudit
  -> sourceHealthScorecard
```

The Python publication order remains baseline, ledger, then metrics as the final commit record. Any registry or wave failure preserves every prior public output byte.

## Two-Phase Bootstrap

The migration is deliberately split so wave 2 cannot define its own predecessor trust tuple.

### Phase A: wave-1-only registry migration

1. Freeze the current infrastructure, wave-1 journal, proof set, public bundle, branch, HEAD, staged count, lock/lease state, and focused v1 tests.
2. Add the registry loader, aggregate model, v2 history summary, audit/metrics schema bumps, source-health integration, CLI/Gradle wiring, and tests.
3. Create a canonical registry containing only the exact wave-1 descriptor.
4. Compute its raw SHA-256 and set `FROZEN_CLOSURE_WAVE_REGISTRY_SHA256` to that exact value in the same reviewed patch.
5. Run the v1 migration-parity gate. It must preserve:
   - 11 predecessor rows;
   - all 10 existing event IDs and journal line order;
   - five rejected and five verified active groups;
   - the current journal SHA-256 `a2ee50ae166dd338e6b09f512193747b28312a52d32d5be44ffa6452d6cfc3c4`;
   - the current proof-set SHA-256 `1c677676c78a99b85c8f23ea2e0467574fbaa1d3a7dabd19d9cdee75de16205c`;
   - identical historical ledger core rows and supersession identities.
6. Generate and validate the first v2 public bundle using wave 1 only.
7. Retain that bundle as the sole Stage-A predecessor transaction for wave 2.

No application-source owner changes in Phase A.

### Phase B: freeze and register wave 2

1. Recheck that the Stage-A baseline, ledger, metrics, registry, relevant owner/test bytes, and source authority have not drifted.
2. Select exactly the four current `OPEN / ELIGIBLE` rows listed below from the Stage-A ledger.
3. Write canonical wave-2 intake rows without changing their issue IDs, group IDs, fingerprints, numeric evidence, status, eligibility, severity, paths, symbols, or proof commands.
4. Freeze exactly eight owner/test target preimages.
5. Create an empty canonical wave-2 `repair-progress.jsonl` and journal.
6. Create the two-descriptor canonical registry, compute its raw SHA-256, and update the code pin.
7. Validate the empty registered wave on every run. It contributes one wave and one empty journal hash but no event, terminal row, rejection, or closure credit.
8. Regenerate the public bundle. Before the first source repair it must still report 10 events, five rejected groups, and five verified groups.
9. Run a repeat pass and require identical ordered semantic projections and semantic hashes.

The registry-pin code change may change the current source baseline after Stage A. Wave 2 continues to reference the Stage-A predecessor tuple by design; a source wave is rooted in its frozen predecessor, not in a later current baseline.

## Wave-2 Intake

Wave 2 owns exactly these four groups:

| Group ID | Frozen issue ID | Owner and current detector manifestation | Focused test target | Initial risk |
|---|---|---|---|---|
| `aff3634c5998adbc4667142ad0aa0d25b961b30f6e397660969cd13c544410c2` | `e0faa1f2bc3d7103cc429519a44a5edbdc95271c0de838464ed3939fa58769ca` | `main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java`, catch near line 562 | `src/test/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModelTest.java` | Medium-high provider/gateway boundary |
| `de40f8321c18138a01c12dd9d689212cc86c5ce8aac103e89fd4b6bb157d0167` | `38b485f0f5a3316f765efc4995aeaeca919331ef40605ee4b9167bc8b9b5c9ec` | `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java`, catch near line 165 | `src/test/java/com/example/lms/llm/gateway/FallbackAwareChatModelTest.java` | Medium, narrow first candidate |
| `b1e957458036eaf44a7cfa9d414be560ccb7292826f242212a8f4b8495e05410` | `e576616b3fb2ce43ef12bbcca6586f10f87116602a9b951b3e6213e5a4b96db2` | `main/java/com/example/lms/llm/OllamaNativeChatModel.java`, catches near lines 491 and 605 | `src/test/java/com/example/lms/llm/OllamaNativeChatModelTest.java` | High, two native-provider manifestations |
| `4c4af8e43e2d5c5f9349cad1cdd419da07b18c13405a1f02a86783f2da0087f2` | `6a84150ab5f5fd78ddcff82b35e42f2725607c30725573ae058f85cb6748d1b7` | `main/java/com/example/lms/storage/LocalFileStorageService.java`, catch near line 227 | `src/test/java/com/example/lms/storage/LocalFileStorageRealRootTest.java` | Medium-high storage safety boundary |

The target-preimage file contains exactly those four owner paths and four focused test paths. It contains no audit script, registry, public output, Gradle file, report, cache, log, or unrelated dirty target.

`RagControlRuntimeAdapter` group `f490f7592ba6be09d596a5a547914960b0c67a9f2a2e1412f47700564222e239` is explicitly excluded. Its ownership remains in wave 1, and its existing contract HOLD is not reclassified, duplicated, or bypassed.

If any of the four Stage-A rows is no longer exactly `OPEN / ELIGIBLE`, any issue/group/fingerprint changes, or any of the eight target bytes drift before freeze, Phase B stops with no wave-2 registration. The current detector and public ledger, not this prose, are authoritative at freeze time.

## First Application Repair: FallbackAwareChatModel

### Root cause and behavioral boundary

`FallbackAwareChatModel.fallbackRoute()` invokes `fallbackRouteSupplier.get()`. If route-metadata resolution throws `RuntimeException`, the method deliberately returns `null` so the fallback model response can proceed without claiming a route-specific health attempt. The current catch has no local diagnostic, so the shared catch detector emits `BROAD_CATCH_NO_BREADCRUMB`.

The repair preserves the fail-soft sentinel and adds only redacted, request-local TraceStore evidence inside the catch:

| Key | Exact value contract |
|---|---|
| `llm.gateway.fallbackAware.routeResolutionFailureReason` | Constant string `route_supplier_runtime_exception` |
| `llm.gateway.fallbackAware.routeResolutionFailureCount` | Nonnegative `long`, incremented once for each caught supplier failure in the current TraceStore context |

For the count, absent or nonnumeric prior state means zero, addition saturates at `Long.MAX_VALUE`, and a successful or absent route supplier does not increment or clear the value. `TraceStore.clear()` retains its existing test/request cleanup behavior.

The catch still returns `null`. It must not log, trace, persist, or expose:

- the exception object, type name, message, stack trace, or cause;
- route, endpoint, host, model, provider, request, response, prompt, session, timeline, credential, or token values;
- a fabricated endpoint attempt, fallback result, or health state.

### RED contract

Add one focused test to the existing `FallbackAwareChatModelTest`:

1. The primary model fails with the existing fallback-eligible classification.
2. The fallback model returns a successful fixed response.
3. The fallback route supplier throws a runtime exception containing a sentinel value that must never reach TraceStore.
4. Before the source patch, the fallback response still succeeds but the two required diagnostic keys are absent; the new assertion is RED for the intended missing-breadcrumb reason.
5. The test also proves that no route-specific fallback health attempt is claimed and no TraceStore value contains the sentinel.

The RED proof is invalid if fallback construction, provider invocation, timeline setup, classifier behavior, or another unrelated assertion fails first.

### Minimal GREEN contract

Change only the existing catch and the smallest local counter helper, if needed. The GREEN test must prove:

- the fallback response bytes and fallback call count are unchanged;
- the reason key equals the exact allowlisted constant;
- the count is `1L` after one caught supplier failure and increments deterministically on a second invocation in the same trace context;
- no sensitive sentinel appears in TraceStore;
- no fallback endpoint attempt is recorded when the route is unavailable;
- null supplier and successful route supplier paths do not create the failure evidence;
- the shared catch projection no longer reports the original no-breadcrumb fingerprint.

No new dependency, component, wrapper, route abstraction, retry, provider call, or background task is allowed.

### Source-edit authority and isolation

The Markdown design and later implementation-plan work do not trigger an application-source gate. Immediately before the first Java mutation, however, the implementation must:

1. use `demo1-source-edit-three-way-preflight`;
2. freeze one redacted EvidenceSnapshot;
3. run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`;
4. require a stable `APPLY`;
5. verify Java 17, active source sets, current branch/HEAD, target tracking and hashes, index lock, PatchDrop queue, source-edit leases, and overlapping writers;
6. enter the existing source-owner guard with only the owner/test pair declared;
7. recheck both preimages immediately before `apply_patch`.

The current design-time evidence hashes are `0738edfc3f11feef67b6050d5834841f1161dd93a8dfaede9510e9257a975c42` for the owner and `59a10a763cd5c8fec9b116ce94cc11c7bb31f0902fdd45203c107dfd8f0b7647` for the focused test. They are evidence snapshots, not write authorization. Phase B and the source-edit guard must fail on drift rather than overwriting existing dirty hunks.

### Closure publication

After GREEN and all gates:

1. capture the owner/test postimages and the owner-only scoped diff;
2. generate redacted RED, GREEN, and event-baseline proof JSON;
3. append one matching `PATCHED_UNVERIFIED` row to wave 2 progress;
4. validate a prospective `VERIFIED_CLOSED` event against the wave-2 loader;
5. append that event to the wave-2 journal without modifying prior rows;
6. regenerate the one public bundle;
7. require exactly one new verified group and no other group/status drift;
8. run a second unchanged regeneration and compare the normalized semantic projection.

An invalid or unpublished progress row earns zero credit. An appended event that cannot validate blocks public regeneration and earns zero public credit. Existing public outputs remain unchanged on producer failure.

## Failure And HOLD Contract

Add these allowlisted reason codes:

| Reason code | First failing boundary |
|---|---|
| `closure-registry-missing` | Fixed registry file is absent |
| `closure-registry-malformed` | Decode, canonical bytes, schema, ordering, or field format fails |
| `closure-registry-mismatch` | Raw registry SHA-256 differs from the code pin |
| `closure-wave-conflict` | Wave IDs, ordinals, paths, issue/group ownership, global event IDs, or supersession cross wave boundaries |

Existing codes continue to classify wave-local failures:

- `closure-journal-missing`
- `closure-journal-malformed`
- `closure-intake-mismatch`
- `closure-proof-invalid`
- `closure-event-conflict`
- `closure-fingerprint-active`
- `closure-progress-mismatch`

Failure ordering is deterministic: fixed registry path, registry bytes/pin/schema, descriptor paths/order, wave intake/progress/journal/proofs, cross-wave invariants, current fingerprint/postimage checks, overlay, bundle validation, then publication. A malformed later wave cannot partially publish earlier-wave results.

| Condition | Result |
|---|---|
| Registry missing, wrong path, noncanonical, or hash-mismatched | Abort audit; preserve all public output bytes |
| Descriptor path escape, alias, overlap, or reparse component | Abort audit with registry/wave conflict |
| Empty wave with missing or invalid intake | Abort audit; empty does not mean unvalidated |
| Duplicate issue/group ownership | Abort aggregate; do not choose a winner |
| Duplicate global event ID | Abort aggregate |
| Cross-wave supersession | Abort aggregate |
| Active event target postimage drift | Abort with proof invalid; do not silently reopen or retain credit |
| Claimed closure fingerprint still active | Keep the cycle at `PATCHED_UNVERIFIED`; zero credit |
| Focused RED fails for another reason | Repair test setup or HOLD the candidate lane |
| GREEN, compile, dependency, source-set, duplicate, or secret gate fails | Keep `PATCHED_UNVERIFIED`; do not append closure event |
| Registry/journal input is valid but public replace fails | Roll back all three public outputs; metrics never becomes a false commit record |
| `FallbackAware` target preimage drifts | HOLD only that source-edit cycle; continue independent read-only infrastructure checks |

An invalid closure registry blocks quantitative closure publication, not every repository activity. Unless another rule makes all work unsafe, `repositoryWideHold=false` and read-only diagnosis or unrelated authorized work may continue.

## Privacy And Security

- Registry, intake, progress, journal, and proof files contain only repository-relative paths, hashes, counts, enum-like reason codes, allowlisted command tokens, and redacted branch/HEAD evidence already permitted by the predecessor contract.
- No raw source snippet, exception text, prompt, response, endpoint, model name, provider payload, credential, authorization header, cookie, environment value, or absolute filesystem path enters public artifacts.
- The registry cannot redirect loading outside the repository and cannot use a symlink, junction, mount point, or lexical alias to evade containment.
- Proof discovery is reference-driven. The loader never recursively trusts arbitrary JSON merely because it exists below a proof root.
- Public secret scans compare changed-target preimage and postimage hits and require `secretNewHitCount=0`; pre-existing pattern counts are not mislabeled as newly introduced secrets.
- The FallbackAware diagnostic uses one constant reason and one count. It never derives a value from the caught exception.
- Browser, Computer, Supabase, external providers, and production systems remain unobserved and unused for this design and first repair.

## TDD And Verification Ladder

### Registry and aggregation RED/GREEN tests

Add focused tests before production changes for:

1. exact canonical wave-1 registry acceptance;
2. registry missing, wrong fixed path, BOM, whitespace/noncanonical bytes, extra/missing fields, invalid hash pin, and invalid hex;
3. absolute, traversal, alias, case-collision, sibling escape, and reparse registry/descriptor paths;
4. duplicate/gapped/out-of-order ordinals and mismatched `waveId`;
5. distinct journal/proof-root requirements and nested/aliased roots;
6. exact wave-1 migration parity for 11 predecessors, 10 event IDs, five rejected, five verified, journal hash, proof-set hash, and historical core rows;
7. empty wave with valid intake acceptance;
8. empty wave with modified/missing intake rejection;
9. duplicate issue or group ownership across waves;
10. duplicate event ID across waves;
11. cross-wave supersession rejection and same-wave supersession acceptance;
12. referenced proof confinement to its own wave root;
13. deterministic registry, journal-set, proof-set, event, count, run-ID, and semantic hashes;
14. exact registry/journal baseline exclusions without a broad `verification/` exclusion;
15. aggregate overlay with five rejected and five verified before the first repair;
16. aggregate overlay with five rejected and six verified after the first repair;
17. output rollback when any later wave fails after earlier waves validate;
18. metrics-last transactional replacement;
19. source-health reconstruction through the same registry;
20. Gradle task inputs and the sole `--closure-wave-registry` argument.

Retain and update the current focused v1 tests rather than replacing their assertions with weaker aggregate-only checks.

### FallbackAware TDD tests

Run the new focused RED test first, then the smallest owner patch, then:

1. the new route-supplier diagnostic test;
2. all `FallbackAwareChatModelTest` methods;
3. the shared catch-contract projection test;
4. the dynamic audit aggregate tests;
5. the source-health linked-bundle test.

### Broader gates

After the focused surfaces are green, run in this order with isolated Desktop Gradle cache/output settings where applicable:

1. Java 17 proof;
2. `gradlew.bat projects`;
3. `gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene`;
4. focused root tests for the changed Java class;
5. root `compileJava`;
6. `:app:classes`;
7. duplicate-FQCN evidence generation and validation;
8. `dynamicRagQuantAudit`;
9. `sourceHealthScorecard`;
10. owner/test scoped diff and postimage checks;
11. count-only changed-target secret scan;
12. two unchanged audit regenerations with identical normalized semantic projections;
13. final staged-count, index-lock, PatchDrop, and source-lease check.

A known unrelated compile or topology failure is recorded with its first error and isolated from the candidate lane; it is never relabeled as a passing compile gate. A terminal closure event requires an actual applicable compile `PASS` under the predecessor proof contract.

## Transaction And Concurrency Rules

- Registry and journal changes are inputs, not outputs of `publish_bundle`.
- Registry creation/update uses preimage comparison and an atomic same-directory replacement after canonical-byte validation.
- Journal and progress updates are append-only, use an active source/evidence lease when required by the execution plan, and compare the full file preimage immediately before append.
- No worker may write the same registry, journal, progress file, proof group, public output, owner, or test concurrently.
- Infrastructure writes finish and verify before any Java source mutation begins.
- Application repair cycles are serial. Only one group may be `PATCHED_UNVERIFIED` in the active execution lane.
- Existing unrelated dirty files and hunks are preserved.
- No cleanup, reset, checkout, broad formatter, or generated-file purge is part of this design.

## Changed-File Responsibility Map

### Infrastructure phase

| Path | Responsibility |
|---|---|
| `scripts/dynamic_rag_quant_audit.py` | Registry owner, wave loader, aggregate history, v2 schemas, exact exclusions, CLI |
| `scripts/test_dynamic_rag_quant_audit.py` | Registry, aggregation, determinism, path-security, rollback, migration tests |
| `scripts/source_health_scorecard.py` | Same-loader reconstruction of v2 history/run identity |
| `scripts/test_source_health_scorecard.py` | v2 linked-bundle and failure tests |
| `build.gradle.kts` | Registry argument and allowlisted relative inputs |
| `verification/structural-repair-waves/registry.json` | Canonical code-pinned wave registry |
| `verification/structural-repair-waves/wave-0002/journal.jsonl` | Empty then append-only wave-2 terminal journal |
| `.superpowers/sdd/structural-repair-waves/wave-0002/intake/*` | Frozen Stage-A wave-2 intake |
| `.superpowers/sdd/structural-repair-waves/wave-0002/repair-progress.jsonl` | Empty then append-only private progress |
| Public baseline/ledger/metrics and source-health outputs | Transactionally regenerated evidence, never hand-edited |

### First application cycle

| Path | Responsibility |
|---|---|
| `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java` | Constant redacted reason/count inside the existing fail-soft catch |
| `src/test/java/com/example/lms/llm/gateway/FallbackAwareChatModelTest.java` | Focused RED/GREEN behavior, no-leak, and no-false-attempt proof |
| Wave-2 group proof files | Redacted RED, GREEN, and event-baseline proof |
| Wave-2 progress and journal | One patch-state row and one verified terminal event after all gates |

No other Java owner or test is authorized by the first-cycle scope.

## Acceptance Criteria

The architecture and first cycle are accepted only when all of the following are true:

1. The exact registry file is canonical, code-pinned, path-safe, and contains wave 1 then wave 2 with no other descriptor.
2. Wave 1 retains its existing paths, three predecessor trust values, three intake hashes, 11 predecessor identities, 10 event IDs, journal bytes at migration, proof-set identity at migration, five rejected groups, and five verified groups.
3. No wave-1 intake, journal row, progress row, or proof byte is rewritten during migration.
4. Wave 2 has a valid frozen Stage-A trust tuple, exactly four intake groups, exactly eight owner/test preimages, an isolated proof root, and its own journal.
5. `RagControlRuntimeAdapter` remains exclusively wave-1-owned and on HOLD unless a later approved wave-1 cycle proves otherwise.
6. Empty wave 2 validates on every run and grants zero credit.
7. Cross-wave issue/group overlaps, event duplicates, and supersession links fail closed.
8. Registry, journal-set, and proof-set hashes are deterministic and enter both metrics semantic identity and `auditRunId`.
9. The audit and metrics schemas are explicitly v2; baseline, ledger-row, event, progress, intake, and proof schemas remain v1.
10. Source health reproduces the same v2 summary and run ID through the shared loader.
11. The source baseline excludes only the exact validated registry and journal paths added by this design, not a broad verification directory.
12. Any registry or wave validation failure leaves all prior public output bytes unchanged.
13. Two unchanged runs produce identical ordered core rows, group/status pairs, event IDs, aggregate summaries, artifact links, and semantic hashes.
14. The first FallbackAware RED fails only because the two new redacted diagnostic values are absent.
15. The minimal GREEN preserves fallback success and no-route/no-health-attempt semantics while emitting only the constant reason and nonnegative count.
16. No caught exception, route, endpoint, model, provider, request, response, prompt, session, credential, or sentinel value leaks.
17. The original FallbackAware detector fingerprint disappears or becomes an accepted bounded transformation under the shared catch contract.
18. The matching patch-state row precedes the terminal event and all closure gates pass with zero new secret hits.
19. The public aggregate changes from 10 to 11 events, remains at five rejected groups, and changes from five to six verified groups, with no unrelated terminal-row drift.
20. Assuming no independent detector-universe drift, open eligible groups change from five to four, `closureTargetGap` changes from 895 to 894, and the candidate-supply `targetGap` remains 890 because `open eligible union verified` remains ten groups.
21. Staged count remains zero unless separate staging authority is granted; no commit, push, deploy, provider call, Browser action, Computer action, database call, or production mutation is performed by this work.
22. The parent approximately-1,000-repair goal remains unresolved and is not marked complete after this cycle; six verified closures are not represented as approximately 1,000 repairs.

## Deliverables

1. This approved design and a later bite-sized implementation plan.
2. Canonical multi-wave registry infrastructure and focused tests.
3. Preserved wave-1 history with migration-parity evidence.
4. Frozen, isolated wave-2 intake and empty-history evidence.
5. Regenerated v2 public audit bundle and source-health validation.
6. One source-owner/test RED-GREEN repair for `FallbackAwareChatModel`.
7. One wave-2 `PATCHED_UNVERIFIED` row and one validated `VERIFIED_CLOSED` event.
8. A final evidence report with exact hashes, commands, exit codes, test counts, group/status arithmetic, diff scope, lock/lease/staged state, privacy counts, and unobserved external lanes.

## Execution Gate After Written-Spec Approval

After the user approves these exact written bytes, the only next design-workflow step is `superpowers:writing-plans`. The implementation plan must split Phase A, Phase B, and the FallbackAware source cycle into reviewable TDD tasks with exact files, commands, expected RED/GREEN results, preimage gates, and stop conditions.

No infrastructure or application-source implementation begins from architecture approval alone. The written specification must be approved first, then the plan must be written and approved, and the application-source cycle must still pass the repository's three-way source-edit preflight immediately before mutation.

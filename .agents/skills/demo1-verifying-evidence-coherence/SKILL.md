---
name: demo1-verifying-evidence-coherence
description: "Use when an exact original claim and normalized evidence rows are ready"
---
# Demo1 Verifying Evidence Coherence
## Core Rule
Decide from coherent, comparable evidence, not from the largest score or the
loudest stakeholder. Preserve the original claim throughout the analysis. A
deadline changes action cost, not evidence authority.

An unresolved hard contradiction can never produce `APPROVE` or a confirmed
claim. Do not silently repair the claim to make the evidence fit.
## Activation and Ownership
This skill owns the evidence matrix and deterministic verdict. It does not
generate candidate worlds or execute queries.
- `RUN` when `originalClaim` is exact and evidence rows have provenance, time,
  directness, authority, independence, relation, and applicable coverage.
- `REUSE` a prior verdict only when every consumed packet version is unchanged.
- `DEFER` when the matrix is incomplete; DEFER emits no VerdictPacket.
  Return `deferDecision={coherenceStatus: UNDERDETERMINED, releaseStatus: HOLD, requiredNextEvidence: [...]}` only.
If the required claim artifact is missing, or `originalClaim` is absent, null, blank, or malformed, return `DEFER` with no `VerdictPacket`.
A present literal `unknown` value is data, not absence; with an otherwise
complete matrix it may produce `UNDERDETERMINED` and `HOLD`.
Direct invocation with a complete matrix may bypass hypothesis and retrieval.
## Input Contract
Require these inputs; record missing values as `unknown` rather than inventing
them:

- `decisionQuestion`: the exact confirmation or release decision requested.
- `originalClaim`: the exact claim under review, including model, variant, and
  capacity when present.
- `evidence[]`: atomic observations with the matrix fields below.
- `officialConstraints[]`: authoritative rules and their effective dates.
- `allowedReleaseStatuses`: normally `APPROVE`, `REJECT`, and `HOLD`.
- `queryTraceRefs[]`: zero when no retrieval ran, otherwise the exact three
  canonical trace references from the retrieval packet.
- `weakSignals[]`: probe-routing signals and their evidence links, if any.

For each evidence item, retain its artifact identity. Crops, classifier outputs,
captions, and human descriptions derived from one image share one provenance
family unless an independent observation proves otherwise.
## Evidence Matrix
Build one row per atomic observation.

Each row requires one `claim`, `source/provenance`, `observedAt/validAt`, `direct
vs inferred`, `authority`, `independenceGroup`, `independence`, `coverage`, and
`supports/conflicts`. Preserve its stable evidence `id` and producing `traceRef`.

Classify each proposition before weighting it:

A hard constraint must be true for the original claim: an applicable official
specification, readable code, signed record, or direct item property. Visual
similarity, seller confidence, inferred labels, and uncalibrated scores are soft.

## Deterministic Decision Rules
1. If a present `originalClaim` is literal `unknown` or is substantively too
   ambiguous to identify the exact model/variant claim, return
   `UNDERDETERMINED` and `HOLD`; request exact identifying evidence.
2. Freeze `originalClaim`; split compound wording into atomic subclaims without
   replacing it.
3. Build the matrix and identify hard constraints before examining score size.
4. Collapse every correlated provenance family to one independent evidence
   group. Repeated crops, repeated reports of one event, and generated or
   virtual samples do not increase independence.
5. Compare only evidence whose `observedAt/validAt` windows apply to the same
   item state. Keep stale evidence visible as `NOT_COMPARABLE`.
6. Return `CONTRADICTED` when at least one applicable, reliable hard constraint
   conflicts with the original claim and no stronger evidence resolves it.
   If comparable applicable hard constraints disagree and neither has documented
   scope, time, or source-version precedence, return `UNDERDETERMINED` and
   `HOLD`; request reconciliation instead of choosing by score or authority label.
7. Return `CONSISTENT` only when all applicable hard constraints support the
   claim and either a direct unique identifier has an applicable authoritative
   mapping, or two independent groups include DIRECT and PRIMARY evidence.
8. Return `UNDERDETERMINED` for missing authority, missing identity evidence,
   unresolved provenance, or conflicts across incomparable time windows.
9. Map `CONTRADICTED` to `REJECT`, `UNDERDETERMINED` to `HOLD`, and
   `CONSISTENT` to `APPROVE`. Apply the same gate before saying "confirmed."
10. An `observed` or `probe_selected` weak signal has no verdict weight. A
    `resolved` signal contributes only through independent normalized evidence
    rows; its ID cannot enter `decisiveEvidenceIds` or determine the verdict.

If a current hard contradiction exists alongside a stale photo, the stale photo
does not erase the contradiction. If the stale photo is the only apparent hard
conflict and item continuity is unknown, use `UNDERDETERMINED` and `HOLD`.

## VerdictPacket Output Contract
Produce this packet before rendering any caller-required short format:

```yaml
schemaVersion: demo1.verdict-packet.v1
packetRef: <stable artifact ref containing version or content hash>
coherenceStatus: CONSISTENT | CONTRADICTED | UNDERDETERMINED
releaseStatus: APPROVE | REJECT | HOLD
originalClaim: <verbatim claim>
consumedPacketRefs: [<hypothesis/counter packet refs actually consumed>]
consumedQueryTraceRefs: [<zero or exactly three refs>]
evidenceMatrix:
  - id: <stable evidence id>
    traceRef: <producing query trace or null for preexisting evidence>
    claim: <atomic proposition>
    source/provenance: <artifact and derivation>
    observedAt/validAt: <times or unknown>
    direct vs inferred: DIRECT | INFERRED | UNKNOWN
    authority: PRIMARY | SECONDARY | SELF_ASSERTED
    independenceGroup: <lineage group id>
    independence: independent | same_lineage | copy
    coverage: complete | partial | unknown
    supports/conflicts: SUPPORTS | CONFLICTS | NEUTRAL | NOT_COMPARABLE
hardConstraintFindings: [<compact findings>]
decisiveEvidenceIds: [<matrix row ids that determine the status>]
unresolvedConflictIds: [<stable unresolved conflict ids>]
verificationGatePassed: true | false
confidenceRange: LOW..LOW | LOW..MEDIUM | MEDIUM..HIGH | HIGH..HIGH
uncertaintyDrivers: [<specific drivers>]
requiredNextEvidence: [<artifact, source, and applicable time>]
revisedHypothesis:
  value: <alternative or null>
  basis: <evidence groups or none>
  status: SUGGESTION_ONLY
weakSignals: [<canonical objects or refs; no field renaming>]
decisionRationale: <one or two sentences>
```

Use `confidenceRange` only for the factual correctness of `originalClaim`, not
for confidence in the status classification. Apply these bounds:

- Missing or `unknown` original claim: `LOW..LOW`.
- `CONTRADICTED`: at most `LOW..MEDIUM`.
- `UNDERDETERMINED`: at most `LOW..MEDIUM`.
- `CONSISTENT`: `MEDIUM..HIGH` or `HIGH..HIGH` according to directness,
  authority, and independent coverage.

Never use a pseudo-precise scalar. Set `requiredNextEvidence` to an empty list
only when the decision is sufficiently resolved. A revised hypothesis is a
labeled suggestion; it never mutates `originalClaim`. If the caller requires
exactly a status and one sentence, render only `releaseStatus` and
`decisionRationale` from the completed packet.
Set `verificationGatePassed=true` only for rule 7; all other statuses use false.
Zero query refs means retrieval was SKIP/DEFER with no packet; a packet whose
three slots are blocked still contributes exactly three consumed refs.

`requiredNextEvidence` names artifacts, sources, and applicable times. It is not
an executable query trace; only the retrieval skill owns those traces.

## Compact Worked Example
For `Phone X Ultra 128GB`, a current complete spec lists only 256/512GB, a 0.96
photo classifier supports X, and an old port photo is not comparable. Return
`CONTRADICTED`, `REJECT`, `LOW..MEDIUM`; request a current model code and keep
`Phone W Ultra 128GB` only `SUGGESTION_ONLY`.

## Red Flags
Never choose the largest score, count one event repeatedly, force temporal
comparability, treat generated samples as observations, let urgency change truth,
auto-confirm an alternative, or emit pseudo-precise confidence.

Before answering, verify that every hard conflict is named, correlated evidence
is counted once, weak signals have no independent verdict weight, time windows
are explicit, and the next evidence would resolve the remaining uncertainty.

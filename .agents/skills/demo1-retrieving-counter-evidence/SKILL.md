---
name: demo1-retrieving-counter-evidence
description: "Use when a reusable hypothesis packet has a bounded, decision-changing evidence gap"
---
# Demo1 Retrieving Counter Evidence

## Overview
Treat converged retrieval as hypothesis generation, not a truth vote.
Similarity is not authority, independence, temporal validity, or identity.

## Activation and Ownership
This skill alone owns query traces and evidence normalization. It does not
regenerate candidate worlds or issue a final verdict.

- `RUN` only when a valid hypothesis packet names the leader, strongest
  alternative or unknown, a `decisionChanging` gap, and `hardBudget`.
- `REUSE` an existing packet when its three query slots and source versions still
  cover the same gap.
- `SKIP` when normalized evidence is already sufficient for coherence, the only
  results are repeated support lineage, or no bounded query can change a decision.

## Core Contract
1. Keep `currentHypothesis` unconfirmed and search its strongest alternative.
2. Reserve `ceil(B/2)` document slots and, when separate, `ceil(Q/2)` query
   attempts for counter or discriminating evidence.
3. Collapse copied origin lineages to one evidence unit and keep similarity,
   authority, independence, freshness, and direction separate.
4. Treat absence as unknown unless an authoritative source completely covers
   the applicable model line, region, generation, and time.
5. Stop only on decisive contradiction, decision-changing handoff, or bounded exhaustion.

## Input Contract
Require or derive these fields before retrieval:

- `question`: the exact identity or claim to decide.
- `currentHypothesis`: a candidate statement, always `unconfirmed` initially.
- `hardBudget`: exact document slots, query attempts, and deadline if supplied.
- `observedDiscriminators`: model code, connector, capacity, dimensions, label,
  serial family, image features, region, and relevant dates.
- `strongestAlternative`: the best competing identity currently visible, or
  `unknown` with a query planned to discover it.
- `coverageNeeded`: model line, region, generation, and comparable time window
  required for any negative claim.
- `candidateLineage`: known publishers, upstream feeds, reposts, and quotations.
- `hypothesisPacketRef`: packet or artifact version that owns candidate worlds.
- `weakSignals[]`: registered signals with state, lineage, and decision delta.

If a field is missing, record it in `evidenceNeeded`; do not silently infer it.
Missing `hardBudget` means `DEFER` before traces or a packet are emitted.

## Exactly Three Query Recipe
Plan exactly three orthogonal slots, in this order, with one family occurrence
each. Authoritative specifications belong inside the first slot, not a fourth:

| Family | Build the query around |
| --- | --- |
| `authoritative_constraint` | A complete applicable manufacturer, regulator, certification, service, capacity, compatibility, dimension, connector, region, release, or exclusion rule that could permit or reject the claim |
| `alternative_or_unknown` | The strongest competing identity or `none/unknown`, using model code, label, port, physical feature, serial prefix, and eligible weak-signal discriminators |
| `provenance_and_time` | Original lineage, derivation, edit history, source version, `observedAt/validAt`, and comparable before/after windows |

Every packet contains exactly three query entries with stable `slotId=family`, a
versioned `traceId`, and `supersedesTraceId`. Revisions keep the slot, increment
the trace, set `supersedesTraceId` to that slot's immediate prior trace (or null
on first use), and never append a fourth query. `queryTraceRefs` contains the latest
trace per slot in canonical order. Every nonblank query records `ran | blocked |
not_applicable`; the latter two require a concrete reason.

## Weak-Signal Promotion
Use the shared lifecycle `observed -> probe_selected -> resolved`:

1. Deduplicate by provenance cluster; correlated repetitions count once.
2. This skill alone selects a probe. Require medium/high specificity, independent
   lineage, `decisionChanging=true`, distinct decision impacts, and one budgeted slot.
3. Write its `slotId`/`traceRef`, then resolve as `corroborated`, `disconfirmed`,
   or `inconclusive`; returned evidence becomes a separate normalized row.
4. A weak-signal ID never enters `decisiveEvidenceIds` or creates a verdict.
Retain ineligible and `decisionChanging=false` signals unchanged as `observed`.

## Evidence Normalization and Ranking
Normalize each independent evidence unit before ranking:

Keep one atomic claim and one derivation lineage per `selectedEvidence` row.
Split photo observations, seller metadata, and model outputs even when they
refer to one listing; relate them with `independenceGroup` and `independence`.

Required fields are `source/provenance`, `observedAt/validAt`, `direct vs
inferred`, `similarity`, `authority`, `independence`, `freshness`, `direction`,
`coverage`, and `constraintStrength`. Raw similarity remains discovery-only.
Set `relationTarget=originalClaim`; direction is closed to `supports`,
`counters`, `neutral`, or `not_comparable`. Use `reserveRole`, not direction,
to mark evidence gathered by a discriminating query.

Rank by evidence class: applicable hard contradiction; authoritative identity
mapping; independent provenance with a comparable window; independent
corroboration; correlated repetition. Within a class, prefer authority,
independence, freshness, and coverage before marginal similarity. Do not average
opposite directions into an unexplained composite score.

A hard contradiction is decisive only when its source is authoritative, its
rule applies to the observed discriminator, and its coverage is complete for
the relevant scope. One edit event, one screenshot, or one high-authority page
with unknown applicability is not decisive by itself.

## CounterEvidencePacket Output Contract
Return this structure, keeping claims concise and source-addressable:

```yaml
schemaVersion: demo1.counter-evidence-packet.v1
packetRef: <stable artifact ref containing version or content hash>
hypothesisPacketRef: <consumed hypothesis packet ref>
question: "..."
currentHypothesis: { text: "...", status: "unconfirmed|weakened" }
strongestAlternative: { text: "...", status: "unconfirmed|strengthened" }
budget: { totalDocs: 0, counterReserved: 0, counterUsed: 0, counterEvidenceIds: ["..."], queryAttempts: 0, counterQueryReserved: 0, counterQueryUsed: 0 }
queries: [{ id: "<traceId alias>", slotId: "...", traceId: "...", supersedesTraceId: "...|null", family: "...", query: "...", result: "ran|blocked|not_applicable", reason: "..." }]
queryTraceRefs: [<latest traceId for each canonical slot, in order>]
weakSignals:
  - { id: "...", atomicObservation: "...", provenanceCluster: "...",
      collapsedFrom: ["..."], specificity: "low|medium|high", decisionChanging: true | false,
      decisionImpact: { ifConfirmed: "...", ifDisconfirmed: "..." },
      family: "authoritative_constraint|alternative_or_unknown|provenance_and_time",
      decisionAuthority: probe_only, lifecycle: "observed|probe_selected|resolved",
      probe: { maxAttempts: 1, slotId: "...|null", traceRef: "...|null" },
      resolution: { outcome: "corroborated|disconfirmed|inconclusive|null", evidenceIds: ["..."] } }
selectedEvidence:
  - { id: "...", claim: "...", "source/provenance": "...",
      "observedAt/validAt": "...", "direct vs inferred": "DIRECT|INFERRED|UNKNOWN",
      relationTarget: "originalClaim", direction: "supports|counters|neutral|not_comparable",
      traceRef: "<producing trace or null for preexisting evidence>", similarity: 0.0, authority: "PRIMARY|SECONDARY|SELF_ASSERTED",
      independenceGroup: "...", independence: "independent|same_lineage|copy",
      freshness: "...", coverage: "complete|partial|unknown", constraintStrength: "...", reserveRole: "counter|discriminating|none" }
dedupe: [{ group: "...", kept: "...", collapsed: ["..."] }]
decision: { stage: retrieval_handoff, evidenceState: "decisive_conflict|decision_changing|bounded_exhaustion", decisiveEvidenceIds: ["..."], limits: ["..."] }
stop: { reason: "hard_contradiction|handoff_ready|bounded_exhaustion", exhaustedFamilies: ["..."] }
evidenceNeeded: ["..."]
handoff: { packetStatus: "evidence_ready|bounded_exhaustion", decisionChangingEvidenceIds: ["..."] }
```

Terminal fields use this closed mapping:

| `evidenceState` | `stop.reason` | `packetStatus` | ID and exhaustion invariant |
| --- | --- | --- | --- |
| `decisive_conflict` | `hard_contradiction` | `evidence_ready` | decisive nonempty; exhausted families empty |
| `decision_changing` | `handoff_ready` | `evidence_ready` | decisive empty, decision-changing nonempty; exhausted empty |
| `bounded_exhaustion` | `bounded_exhaustion` | `bounded_exhaustion` | both ID sets empty; all three families exhausted |

Keep `selectedEvidence` within `totalDocs`; reserve both half-budgets. Make
`counterUsed` equal the independent `counterEvidenceIds` rows whose `reserveRole`
is counter/discriminating; zero is valid after bounded zero-result searches, and
absence is never content. `counterQueryUsed` counts `ran`; count `blocked` only
when charged and name that limit. Always retain three current slots/traces. On
decisive early stop, mark unrun slots `not_applicable` with reason
`decisive_conflict_stop`, record actual use/limit, and never fabricate usage.
A blocked slot is exhausted for this packet only after a charged budgeted
attempt with no retry left; otherwise DEFER without emitting a packet.

## Red Flags
The packet is incomplete if it counts copies, turns absence or weak signals into
facts, hides conflict, waives reserve, or appends rather than supersedes a slot.

---
name: demo1-triangulating-counter-evidence
description: "Use when an anchored RAG or agent claim needs two or more counter-evidence stages"
---

# Demo1 Triangulating Counter Evidence

## Core Rule
Similarity nominates hypotheses; it never verifies them. Preserve the original
claim, search for evidence that could make it false, and return unresolved
conflicts instead of silently repairing the claim.

## Conditional Sub-Skills
Route the three owners; do not run all three by default:

| Skill | `RUN` when | Otherwise |
| --- | --- | --- |
| `demo1-generating-falsifiable-hypotheses` | Anchor risk exists and no reusable packet has mutually exclusive candidates, `none/unknown`, provenance, and falsifiers | `REUSE` a valid packet or `SKIP` when a complete evidence matrix is already ready |
| `demo1-retrieving-counter-evidence` | A valid candidate packet has a bounded `decisionChanging` gap | `REUSE` its versioned three-query packet or `SKIP` when evidence is sufficient/no query can change the decision |
| `demo1-verifying-evidence-coherence` | Exact `originalClaim` plus normalized evidence rows are ready | `REUSE` a version-stable verdict packet; otherwise `DEFER` and name the smallest missing artifact |

If an exact claim and complete normalized matrix already exist, the verifier may
run directly. Do not replace a running stage with a similarity rerank, majority
vote, or free-form answer.
Stage precedence: a complete normalized matrix can run the verifier despite
anchor risk. When `forcedChoice=true` needs a ranked fallback, the generator
must RUN/REUSE first; retrieval still runs only for a budgeted changing gap.

## Input Contract
Require exact `decisionQuestion`/`originalClaim`, separate `rawObservations[]`,
`candidateHits[]` with lineage/score semantics, applicable `hardConstraints[]`,
`decisionConstraint` (`forcedChoice`, unknown policy, allowed values), tool
cost/mutation risk, and versioned `existingPacketRefs`.
Retrieval action `RUN` requires a new bounded `retrievalBudget`.
`REUSE` uses the referenced packet's budget metadata and requires no new budget.
Direct verifier, retrieval `SKIP`, and retrieval `DEFER` accept `retrievalBudget: null | not_required`.

Never copy a prior answer into `rawObservations`. If its artifact is unavailable, keep it as a claim and add `evidence_needed`.

## Activation Plan
Before doing stage work, emit one compact row per owner:

```yaml
activationPlan:
  - { skill: <owner>, action: RUN | REUSE | SKIP | DEFER,
      trigger: <readiness fact>, dependsOn: [<upstream owners>], reason: <why sufficient>, packetRef: <ref or null> }
```

A downstream `RUN` is planned; recheck its handoff before execution and switch
to `DEFER` if unmet. Never recompute a valid packet merely to preserve order.

## Triangulation Workflow
### 1. Generate or Reuse Candidate Worlds

When its activation row is `RUN`, generate 2-5 mutually exclusive candidates
plus `none/unknown`, provenance, conflicts, weak signals, and one falsifier
intent per candidate. Otherwise reuse or skip it. The leader remains
`leading_not_verified`.
Map generator `original_claim -> originalClaim` verbatim; never promote its leader.

### 2. Retrieve Against the Anchor

Run retrieval only for a named `decisionChanging` gap. It owns exactly three
query slots: `authoritative_constraint`, `alternative_or_unknown`, and
`provenance_and_time`. Reserve at least half of the bounded budget for counter
or discriminating evidence. Collapse copied pages and repeated outputs to one
lineage. A revision supersedes a family trace rather than appending a fourth.
Map `decisionQuestion -> question`, set `currentHypothesis=originalClaim`, and
take `strongestAlternative` from the packet; its leader need not be the anchor.

An absent item is not a contradiction unless an authoritative source completely covers the relevant model line, region, generation, and time.

### 3. Verify When Ready

Pass normalized evidence rows to `demo1-verifying-evidence-coherence`, which
alone builds and owns the matrix. Keep `originalClaim` verbatim. Record
directness, authority, independence, observed/valid time, and relation for every
row. Each row must be one atomic claim from one derivation lineage. If a row
mixes lineages or lacks provenance, time, directness, authority, independence,
or coverage, fail closed. Require `direction` or `supports/conflicts`; never
invent a missing relation. Preserve `NOT_COMPARABLE` for incomparable time.
Preserve each evidence `id`, `traceRef`, `independenceGroup`, and classification.
For `relationTarget=originalClaim`, map supports -> SUPPORTS, counters -> CONFLICTS,
neutral -> NEUTRAL, and not_comparable -> NOT_COMPARABLE.
When rows are already complete, verification may be the first and only stage.
When they are incomplete, mark it `DEFER`; do not let the verifier issue queries.
### 4. Revise Without Re-Anchoring

If contradicted, reject the original claim but keep any alternative as
`SUGGESTION_ONLY` until independently verified. If underdetermined, route an
eligible `observed` weak signal to retrieval. Retrieval alone owns lifecycle
transitions; the signal selects a probe and only normalized evidence changes truth.

Allow at most two revision cycles. Before each cycle, name the expected change
to verdict, decisive evidence, or blocking uncertainty. Continue only when new
independent evidence can cause that change; otherwise stop with `evidence_needed`.

## Final Decision Gate
Use `verdictPacket.verificationGatePassed` as the sole confirmation gate:
`CONSISTENT + true -> VERIFIED`, `CONTRADICTED -> REJECTED`, and every other
case -> `EVIDENCE_NEEDED`. Rejecting the original never confirms an alternative.
Map verifier `revisedHypothesis -> alternativeSuggestion` without confirmation.
Deduplicate upstream evidence needs plus verifier `requiredNextEvidence` into exact actions.
On DEFER use `verdictPacket=null`, `confidenceRange=null`, `decisiveEvidenceIds=[]`,
`unresolvedConflicts=[]`, null suggestion, and the verifier `deferDecision`.
Map generator `forced_decision` to `forcedDecision` without semantic change.
If forced but generator input is insufficient, DEFER with null/`not_available`;
otherwise forced is non-null/`not_verified`, and unforced is null/`not_required`.
Never average similarity, authority, freshness, and contradiction into one opaque score. Use the verifier's qualitative `confidenceRange`; retain a numeric hypothesis cap only as an audit guard.
## TriangulationPacket Output Contract
Return these fields in order:

```yaml
schemaVersion: demo1.triangulation-packet.v1
packetRef: <stable artifact ref containing version or content hash>
decisionQuestion: <exact question>
originalClaim: <verbatim claim>
anchorRisk: [<high similarity, repeated lineage, prior conclusion, pressure>]
iterations: <0..2>
activationPlan: [<three RUN|REUSE|SKIP|DEFER rows>]
hypothesisSet: <packet, artifact reference, or null when skipped>
counterEvidencePackets: [<packet or artifact references; empty when skipped>]
queryTraceRefs: [<empty on SKIP/DEFER without packet; otherwise exactly three latest refs>]
verdictPacket: <complete packet, null on DEFER, or exact verifier packetRef on REUSE>
deferDecision: <verifier deferDecision on DEFER, otherwise null>
integratedStatus: VERIFIED | REJECTED | EVIDENCE_NEEDED
verifiedIdentity: <original claim or null>
alternativeSuggestion: { value: <candidate or null>, status: SUGGESTION_ONLY }
confidenceRange: <verdict range or null on DEFER>
decisiveEvidenceIds: [<exact verdictPacket.decisiveEvidenceIds>]
unresolvedConflicts: [<exact verdictPacket.unresolvedConflictIds>]
forcedDecision: { value: <ranked allowed value or null>, verificationStatus: <not_verified|not_required|not_available> }
evidenceNeeded: [<missing artifact and exact acquisition action>]
downstreamContext:
  originalClaimStatus: <verified|rejected|unresolved>
  alternativesVerified: false
  similarityIsTruth: false
```

Store large packets by artifact reference. Do not inline raw logs, source dumps, sensitive prompts, credentials, headers, cookies, or database URLs.

## Evidence Acquisition Boundaries
- Browser is optional and read-only; use it only for current official pages,
  edit history, image state, or DOM evidence that can change the verdict.
- Computer is optional and read-only; use it only when Windows UI state is itself evidence.
- Supabase remains read-only and project-scoped. Without project ref and auth,
  make no SQL or schema claim and return `evidence_needed`.
- Superpowers is supporting process only; repo evidence and these owner contracts remain authoritative.
- Desktop files and command output remain final repo evidence. External agents
  and repeated RAG hits are supporting sources, not truth authorities.

## Validation
After editing any family skill, run `python -X utf8 .agents/skills/demo1-triangulating-counter-evidence/scripts/validate_counter_evidence_skill_family.py`.
## Red Flags
Do not pass the leader as fact, run all owners without need, count copies as
independent, treat partial/stale pages as exhaustive, auto-confirm alternatives,
reopen broad retrieval, give weak signals verdict weight, or hide a hold/reject.

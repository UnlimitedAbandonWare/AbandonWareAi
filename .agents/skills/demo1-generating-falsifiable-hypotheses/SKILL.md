---
name: demo1-generating-falsifiable-hypotheses
description: "Use when a high-similarity RAG hit or prior-agent conclusion creates anchor risk"
---
# Demo1 Generating Falsifiable Hypotheses

## Overview
Treat identification as a contest among explicit, disprovable candidate worlds.
Similarity scores, titles, and prior conclusions nominate candidates; they are
not observations and are never truth by themselves.

## Activation and Ownership
This skill owns the evidence ledger, candidate worlds, falsifier intents, and
weak-signal registry. It does not execute searches or issue the final verdict.

- `RUN` when anchor risk exists and no reusable hypothesis packet contains
  mutually exclusive candidates, `none/unknown`, provenance, and falsifiers.
- `REUSE` a complete packet when only a decision-changing evidence gap remains.
- `SKIP` when the exact claim and complete normalized matrix are verifier-ready,
  unless `forcedChoice=true` needs a ranked fallback and no reusable packet exists.

## Input Contract
Gather or mark missing:

- `decision_target`: the exact entity and granularity to identify.
- `original_claim`: the exact anchored claim, or `null` when none exists.
- `decision_constraint`: deadline, forced-choice API, `allowed_values`, and unknown policy.
- `observations[]`: directly measured features with modality, time, and provenance.
- `claims[]`: seller, user, metadata, retrieval, or prior-agent assertions.
- `candidate_hits[]`: candidate identifiers, similarity scores, and score semantics.
- `hard_constraints[]`: authoritative SKU, compatibility, physical, or version rules.
- `available_tests[]`: allowed queries or inspections with cost and latency.
- `existing_hypothesis_packet`: reusable packet or `null`, with artifact version.

Do not silently fill a missing field. Record it as missing evidence.

## Evidence Ledger
Classify each item as `observation` (directly visible/measured), `claim`
(person, title, metadata, retrieval, or agent assertion), or `constraint`
(authoritative rule that can make a candidate impossible). Attach source, time,
acquisition method, and reliability note. Group
items derived from the same photo, text, model output, or RAG passage into one
provenance cluster. Correlated evidence receives one evidentiary contribution,
even when it yields several cues.

## Weak-Signal Registry
A weak signal is a specific anomaly that may expose a missed candidate; it is
not evidence authority. Register it once per provenance cluster:

```yaml
- id: W1
  atomicObservation: <observed anomaly>
  provenanceCluster: <one origin group>
  collapsedFrom: [<correlated signal ids>]
  specificity: low | medium | high
  decisionChanging: true | false
  decisionImpact: { ifConfirmed: <change>, ifDisconfirmed: <change> }
  family: authoritative_constraint | alternative_or_unknown | provenance_and_time
  decisionAuthority: probe_only
  lifecycle: observed
  probe: { maxAttempts: 1, slotId: null, traceRef: null }
  resolution: { outcome: corroborated | disconfirmed | inconclusive | null, evidenceIds: [] }
```

Always emit `lifecycle: observed`; this skill never selects or resolves a probe.
Retrieval alone may transition to `probe_selected` and `resolved`. Eligibility
requires a specific, independent, answerable signal with `decisionChanging=true`
and distinct confirmed/disconfirmed effects. Correlated signals count once. A
weak-signal ID never appears in `decisiveEvidenceIds`; only resulting evidence can.
Keep the observed connector/label feature and its model mapping as separate rows.

## Hypothesis Contract
Generate 2-5 mutually exclusive named candidate worlds, then add one mutually
exclusive `none/unknown` world. Keep unknown in the reasoning record even when
the final API forbids it. Each world must be complete at the decision target's
granularity; express a stale photo or bad metadata as part of a candidate world
rather than using it to erase conflicting evidence.

For every candidate, provide all fields:

```yaml
- id: H1
  candidate: <complete candidate world>
  support: [<evidence ids>]
  conflicts: [<evidence or constraint ids>]
  missing_evidence: [<facts needed but absent>]
  provenance: [<independent source clusters>]
  family: authoritative_constraint | alternative_or_unknown | provenance_and_time
  falsifier_query: <one answerable query whose result could reject this candidate>
  discriminating_test: <test and outcomes that separate it from its closest rival>
```

Support and conflicts must cite ledger IDs. `falsifier_query` is a compatibility
field for a planning intent, not an executable query trace; the retrieval skill
deduplicates all intents into exactly three canonical queries.
Order `ranking` after caps: the leader is `ranking[0]`. `anchorCandidate` matches
`original_claim`; `strongestAlternative` is the highest-ranked different world.

## Decision Rules
1. Build the ledger before ranking; a prior conclusion without its artifact is a claim.
2. Use similarity only to nominate tests; it is not truth, probability, or confidence.
3. Collapse correlated cues to one provenance contribution.
4. Test official capacity, connector, part, compatibility, and size constraints
   before soft resemblance.
5. Apply confidence caps to the leading named candidate:
   - Decisive evidence has disputed, stale, or unknown provenance: at most 80.
   - One unresolved independent hard-constraint conflict: at most 65.
   - Two or more unresolved independent hard-constraint conflicts: at most 40.
   - No named candidate satisfies them: unknown leads; a forced named value is at most 30.
   Use the lowest cap and cite its trigger.
6. Prefer the highest-discrimination `decisionChanging` test; seller reputation
   changes claim reliability, not identity.
7. Forced choice maps the highest-ranked allowed value to `not_verified`; if
   none exists use null/`not_available`. Unforced uses null/`not_required`.

## Output Contract
Return these sections in order:

```yaml
schemaVersion: demo1.hypothesis-packet.v1
packetRef: <stable artifact ref containing version or content hash>
decision_target: <target>
original_claim: <verbatim anchored claim or null>
observations: [<ledger entries>]
claims: [<ledger entries>]
constraints: [<ledger entries>]
provenance_clusters: [<cluster ids and members>]
hypotheses: [<2-5 candidate dossiers>]
none_or_unknown: <candidate dossier>
weakSignals: [<registered signals and states>]
ranking: [<candidate id, confidence, cap, cap reason>]
anchorCandidate: { candidateId: <claim-matching id or null>, text: <candidate or null>, status: unconfirmed }
leader: { candidateId: <ranking[0] id>, text: <candidate>, status: unconfirmed }
strongestAlternative: { candidateId: <best non-anchor id>, text: <candidate>, status: unconfirmed }
unresolved_conflicts: [<conflict ids>]
next_discriminating_test: <test>
forced_decision: { value: <highest-ranked allowed value or null>, verificationStatus: <not_verified|not_available|not_required> }
evidenceNeeded: [<missing inputs and unresolved decision-changing gap ids>]
handoff: { packetStatus: candidate_set_ready, retrievalGapIds: [<gaps>], coherenceReady: true | false }
downstream_context:
  status: leading_not_verified
  candidates: [<ids>]
  unresolved_conflicts: [<ids>]
```

Confidence is calibrated decision confidence, never copied from similarity. If
the caller requests a shorter response, preserve at minimum the leader, applied
cap, unresolved conflicts, unknown candidate, and next falsifier.
`handoff.coherenceReady` is advisory; the triangulator rechecks matrix readiness.

## Compact Worked Example
A title and three copies favor `Camera A`; one image cluster suggests `Camera B`.
Produce A/B/unknown. Register the connector once as `observed`; if it could
change ranking, route it to retrieval's `alternative_or_unknown` slot.

## Red Flags
Do not copy claims into observations, convert similarity to confidence, count
one lineage repeatedly, remove unknown, omit a hard conflict, emit the leader as
fact, or execute per-candidate falsifiers outside the three-query owner.

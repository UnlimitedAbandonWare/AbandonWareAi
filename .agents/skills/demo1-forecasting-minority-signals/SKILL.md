---
name: demo1-forecasting-minority-signals
description: "Use when a correlated RAG or agent majority may be wrong and one independent"
---

# Demo1 Forecasting Minority Signals

## Overview

Preserve a falsifiable minority forecast until its observation window opens.
Own the time-bounded prediction ledger only; do not generate candidate worlds,
run a hidden fourth retrieval query, issue a verdict, or authorize a source patch.

## Activation And Ownership

Choose one action before writing a ledger:

| Action | Observable condition |
| --- | --- |
| `RUN` | The signal has independent provenance, distinct confirm/disconfirm effects, and a bounded future observation window. |
| `RESOLVE` | A versioned `PENDING` entry is inside or past its window and normalized evidence is available. |
| `REUSE` | The entry, source versions, and time window are unchanged. |
| `SKIP` | Current evidence is already verifier-ready or no future observation could change the decision. |
| `DEFER` | The prediction, falsifier, provenance, or window is missing. |

`demo1-generating-falsifiable-hypotheses` owns candidates and weak signals.
`demo1-retrieving-counter-evidence` owns exactly three query slots and traces.
`demo1-verifying-evidence-coherence` alone owns `verificationGatePassed` and the
terminal verdict. `demo1-triangulating-counter-evidence` owns stage routing.

## Input Contract

Require or mark missing:

- exact `decisionTarget` and verbatim `originalClaim`;
- `counterClaim`, `predictedObservation`, and one outcome that falsifies it;
- ISO-8601 `validAfter` and `validUntil`, with `validAfter < validUntil`;
- `provenanceGroup`, source packet/artifact references, and observation method;
- `parentInstruction`, bounded `childAssignment`, and any `instructionConflict`;
- distinct `ifCorroborated` and `ifDisconfirmed` decision impacts;
- read-only probe cost, mutation risk, and any existing ledger version.

Collapse workers derived from one prompt, document set, model output, or copied
report into one provenance group. Worker count is metadata, never evidence weight.
Do not store hidden reasoning, raw prompts, raw queries, credentials, or payloads.

## Lifecycle

1. Create an eligible entry as `PENDING` with `decisionAuthority: probe_only`.
2. Before `validAfter`, take no probe and emit `nextAction: none_before_validAfter`.
3. Inside the window, allow one bounded read-only observation. Route web retrieval
   through an existing counter-evidence slot; never append a fourth query.
4. Set `CORROBORATED` or `DISCONFIRMED` only from independent normalized evidence.
5. Set `EXPIRED` after `validUntil` when the required observation was not obtained.
6. Version every transition, preserve the prior entry, and hand resulting evidence
   to the coherence verifier. No status confirms an alternative by itself.

## Output Contract

```yaml
schemaVersion: demo1.minority-forecast-ledger.v1
packetType: minority_forecast_ledger
action: RUN | RESOLVE | REUSE | SKIP | DEFER
ledgerRef: <version or content hash>
ledgerEligible: <true only when every required field and bound is proven>
decisionTarget: <exact target>
originalClaim: <verbatim claim>
sourcePacketRefs: [<versioned refs>]
delegation:
  parentInstruction: <parent scope summary>
  childAssignment: <bounded read-only assignment>
  instructionConflict: <none or exact conflict>
  conflictAction: report_conflict_and_run_bounded_read_only_probe | none
  inheritedConsent: false
  inheritedToolScope: false
correlatedMajority:
  workerCount: <count>
  provenanceGroups: [<groups>]
  effectiveIndependentGroups: <count>
  similarityIsTruth: false
forecasts:
  - id: F1
    counterClaim: <alternative or counter claim>
    predictedObservation: <specific future observation>
    falsifier: <specific disconfirming outcome>
    validAfter: <ISO-8601>
    validUntil: <ISO-8601>
    provenanceGroup: <independent lineage>
    observationMethod: <bounded read-only method>
    observationBudget: { maxAttempts: 1, maxDurationMs: <positive bound>, mutationAllowed: false }
    decisionChanging: true
    decisionImpact: { ifCorroborated: <change>, ifDisconfirmed: <change> }
    status: PENDING | CORROBORATED | DISCONFIRMED | EXPIRED
    decisionAuthority: probe_only
    evidenceIds: [<normalized evidence ids>]
nextCheckAt: <validAfter or null>
nextAction: none_before_validAfter | run_bounded_observation | handoff_to_verifier | none
verificationGatePassed: false
evidenceNeeded: [<missing artifact and exact acquisition action>]
compactReportLine: <action, status, next check, and blocker without raw evidence>
```

Keep `verificationGatePassed=false`; copy the verifier's decision only in a
separate downstream packet owned by the verifier.

## Cost And External Boundaries

Reuse current agent reports before dispatching more workers. Model diversity does
not prove evidence independence. When the user explicitly requests the documented
GPT-5.6 topology and runtime availability is verified (slugs below are July-era —
confirm against current config + official provider docs first), use `gpt-5.6-sol`
with `reasoning.mode=pro` for coordination, `gpt-5.6-luna` for high-volume
exploration, and one `gpt-5.6-terra` reviewer. The ledger contract remains the
authority.

Browser and Computer are optional read-only observation lanes. Supabase remains
read-only and project-scoped; missing project ref or auth yields `evidence_needed`.

## Compact Example

Nineteen workers copied one source lineage; one independent reviewer predicts a
specific failure after deployment. Record one `PENDING` forecast, effective
majority groups `1`, and no patch authority. At `validAfter`, run one bounded
observation, resolve the entry, and hand its normalized evidence to the verifier.

## Common Mistakes

- Treating 19 correlated workers as 19 independent confirmations.
- Recording a vague concern without a time window or falsifier.
- Probing before the window, repeating probes, or adding a fourth retrieval query.
- Turning `CORROBORATED` into `VERIFIED` without the coherence gate.
- Persisting raw prompts, queries, logs, secrets, or model reasoning.

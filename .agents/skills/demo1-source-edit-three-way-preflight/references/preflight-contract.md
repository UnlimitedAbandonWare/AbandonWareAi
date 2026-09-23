# Source-Edit Three-Way Preflight Contract

## Bounds

```text
canonicalQueryCount=3
evidenceRowCountMax=20
evidenceSummaryCharsMax=6000
positivePacketCharsMax=2400
negativePacketCharsMax=2400
neutralPacketCharsMax=1800
preflightWallClockMaxSeconds=120
preflightWallClockMaxSeconds exceeded=tri-preflight-timeout=HOLD
```

All packets carry the same `evidenceSnapshotHash`. No role writes files, calls a
provider, dispatches a tool, or fills missing evidence.

## POSITIVE_QUERY

```text
packetType=POSITIVE_QUERY
evidenceSnapshotHash
candidateGoal
scenarioWorlds[2..4]: scenarioId, premise, causalMechanism,
expectedObservation, evidenceNeeded, falsifier
validatedAssumptions
reusableAssets
expectedUserValue
minimalVerification
evidenceIds
unknowns
```

## NEGATIVE_QUERY

```text
packetType=NEGATIVE_QUERY
evidenceSnapshotHash
challengedGoal
scenarioAttacks: scenarioId, counterExample, alternativeCause,
boundaryOrAuthorityRisk, costAndBlastRadius, smallestDisconfirmingProbe,
evidenceIds
falsifiers
missingEvidence
safetyRisks
```

The Negative scenario-ID set must exactly equal the Positive set. A mismatch is
`scenario-coverage-mismatch` and forces HOLD.

## NEUTRAL_QUERY

```text
packetType=NEUTRAL_QUERY
evidenceSnapshotHash
forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict
reverseVerdict
forwardDecisiveEvidenceIds
reverseDecisiveEvidenceIds
orderStable
verdict=APPLY|HOLD|REJECT
selectedOrRewrittenGoal
scoreInputs: evidenceStrength, causalStrength, verificationFeasibility, userValue,
reversibility, costEfficiency, timeFit, blastRadius, ambiguity,
authorityOrSafetyExpansion
goalScore
decisiveEvidence
rejectedClaims
nextSingleProof
confidence=L|M|H
```

Compute and clamp:

```text
100 * (0.25*evidenceStrength + 0.20*causalStrength
+ 0.15*verificationFeasibility + 0.15*userValue + 0.10*reversibility
+ 0.10*costEfficiency + 0.05*timeFit - 0.20*blastRadius
- 0.15*ambiguity - 0.20*authorityOrSafetyExpansion)
```

`order-unstable`, score below 50, `source-owner-unproven`, missing verification,
or any safety-gate failure forces HOLD. Redaction failure or undeclared source
write forces REJECT. APPLY requires `forwardVerdict=reverseVerdict=verdict=APPLY`;
stable non-APPLY verdicts never route to `existing-source-owner-guard`.

## Failure classes

```text
automatic-trigger-missing=report hook absence; do not claim automatic coverage
hook-trust-missing=HOLD automatic-coverage claim until Desktop trusts exact hash
source-edit-classifier-timeout=no hook authorization; source mutation remains gated
snapshot-unfrozen=HOLD
canonical-query-count-invalid=HOLD
packet-schema-invalid=HOLD
scenario-coverage-mismatch=HOLD
order-unstable=HOLD
tri-preflight-timeout=HOLD
source-owner-unproven=HOLD
verification-unproven=HOLD
safety-gate-failed=HOLD
metric-input-invalid=HOLD
goal-score-mismatch=HOLD
goal-score-below-threshold=HOLD
packet-bound-exceeded=HOLD
neutral-verdict-mismatch=HOLD
redaction-failed=REJECT and discard unsafe artifact
undeclared-source-write=REJECT
token-regression=keep rollout demo-1-local; do not expand
```

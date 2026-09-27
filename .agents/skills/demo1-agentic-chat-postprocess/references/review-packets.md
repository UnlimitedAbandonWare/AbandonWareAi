# Review packets

Set canonicalQueryCount=3. All three read-only packets carry the same frozen evidenceSnapshotHash; no packet writes source, dispatches a tool, or fills missing evidence. The payload fields below are exact and ordered.

## v2 design and evidence boundaries

```text
designVerdict=APPLY|HOLD|REJECT
artifactVerdict=APPLY|HOLD|REJECT
statisticalUpliftVerdict=INCONCLUSIVE|NO_UPLIFT|UPLIFT_CANDIDATE|REJECT
runtimeLineageVerdict=APPLY|HOLD|REJECT
designMetaGrader=scripts/score_three_way_long_tail_design.py
designContract=agent-prompts/agents/demo1_three_perspective_chat_postprocess/v2_design_contract.json
fixtureAuthority=sealed
candidateMaySubmitBaseline=false
candidateMaySubmitCaseCount=false
candidateMaySubmitEvidenceRegistry=false
neutralVerdictDerived=true
```

The sealed fixture owns evidence registry IDs, claim semantic-family IDs, baseline, caseCount, stratum/cluster metadata, and safety labels. Registered causal IDs and relation tuples derive the neutral decision; a packet self-declaration cannot replace that derivation. Statistical uplift remains `INCONCLUSIVE` until baseline and candidate raw arms are symmetrically regraded under the same locked deck, scorer, formula, generation-condition hash, and clustered bootstrap semantics. `designVerdict=APPLY does not auto-promote artifactVerdict, statisticalUpliftVerdict, or runtimeLineageVerdict.`

## POSITIVE_QUERY

packetType=POSITIVE_QUERY

~~~text
candidateGoal
scenarioWorlds[2..4]
  scenarioId
  premise
  causalMechanism
  expectedObservation
  evidenceNeeded
  falsifier
  baseRateStatus
noneOrUnknown
validatedAssumptions
reusableAssets
expectedUserValue
minimalVerification
evidenceIds
unknowns
~~~

Generate falsifiable hypothetical worlds only. A hypothetical observation is not evidence; carry contract checks in validatedAssumptions and minimalVerification.

## NEGATIVE_QUERY

packetType=NEGATIVE_QUERY

~~~text
challengedGoal
scenarioAttacks
  scenarioId
  counterexample
  alternativeCause
  boundaryOrAuthorityRisk
  costAndBlastRadius
  smallestDisconfirmingProbe
  evidenceIds
falsifiers
missingEvidence
safetyRisks
~~~

Use exactly the scenarioId set from POSITIVE_QUERY.scenarioWorlds; missing or extra IDs are scenario-coverage-mismatch. Attack only claims supported by the frozen snapshot.

## NEUTRAL_QUERY

packetType=NEUTRAL_QUERY; neutralMayAcquireEvidence=false.

~~~text
forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict
reverseVerdict
forwardDecisiveEvidenceIds
reverseDecisiveEvidenceIds
orderStable
verdict
selectedOrRewrittenGoal
goalScore
decisiveEvidence
rejectedClaims
nextSingleProof
confidence
artifactVerdict
designVerdict
statisticalUpliftVerdict
runtimeLineageVerdict
~~~

Compare both orders without evidence acquisition. If verdicts or decisive-evidence sets differ, set orderStable=false and verdict=HOLD. Keep designVerdict, artifactVerdict, statisticalUpliftVerdict, and runtimeLineageVerdict independent; a design or artifact grader result is not runtime proof.

## Shared final report

Include runId, seed, scenarioId, observation, evidenceSnapshotHash, positiveQuery, negativeQuery, neutralQuery, the two orders and their verdict/evidence IDs, orderStable, designVerdict, artifactVerdict, statisticalUpliftVerdict, runtimeLineageVerdict, evidenceIds, failureClass, toolDecision, and exactly one nextAction. Use allowlisted IDs and redacted summaries only.

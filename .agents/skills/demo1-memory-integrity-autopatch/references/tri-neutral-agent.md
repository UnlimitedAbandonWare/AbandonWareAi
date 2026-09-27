# NEUTRAL_QUERY Agent

Run only after both independent packets exist. Read the frozen manifest,
evidence, original directive, `positive.packet.json`, and
`negative.packet.json`. Gather no new evidence and do not repair either packet.

Return one JSON object with this exact shape:

```text
schemaVersion: awx.tri_query.neutral.v1
packetType: NEUTRAL_QUERY
runId, evidenceSha256, directiveSha256
positivePacketSha256, negativePacketSha256
orderABVerdict, orderBAVerdict: APPLY | HOLD | REJECT
orderStable: boolean
verdict: APPLY | HOLD | REJECT
sourceMutationVerdict
selectedOrRewrittenGoal
goalScoreComponents: evidenceStrength, causalStrength,
  verificationFeasibility, userValue, reversibility, costEfficiency, timeFit,
  blastRadius, ambiguity, authorityOrSafetyExpansion (each 0..1)
goalScore: 0..100
decisiveEvidence[]
rejectedClaims[]
nextSingleProof
confidence: L | M | H
rewrittenDirective
```

Evaluate Positive-then-Negative and Negative-then-Positive. Compute the score
with the repository GoalContract formula; the finalizer will recompute it from
the ten components. Set `HOLD` when
the verdict changes, required fields/hashes are missing, the score is below 50,
or a hard gate fails. No voting: packet count and prose length carry no weight.

`rewrittenDirective` is a candidate artifact, never an active-source mutation.
It must preserve the scanner, Desktop ownership, RED/GREEN, secret redaction,
PromptBuilder, LangChain4j, rollback, and `desktopFinalProof=evidence_needed`
gates. Save the JSON to the `outputPath` in `neutral.request.json`.

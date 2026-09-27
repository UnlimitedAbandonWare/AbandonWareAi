# Decision Contract

## Input and evidence

Accept a natural-language request or an existing GoalContract. Extract only
decision-relevant fields; unknown is not false or zero.

```yaml
goal: {outcome, target, unit, deadline, benefit}
scope: {owner, affectedUsers, authorizedActions, explicitTradeoffs}
horizon: {nextRelevantUse, end, rationale}
assets:
  - {id, owner, functions, dependents, lastUsed, activationConditions,
     nextNeed, uniqueValue, substitute, recoveryTime, costEvidence, evidenceIds}
baseline: {retainedCapabilities, obligations, costsOfKeeping, risksOfInaction}
candidateActions: [{id, affectedAssets, intendedGain, sideEffects}]
```

An asset row should answer: What does it enable? For whom? Under what
conditions? What fails when it disappears? What actual alternative is ready?
Record evidence as `{id, source, observedAt, claim, status}` where status is
`supported | refuted | unknown | conflicting`. Label synthetic assumptions.
Do not use an empty call log or an unverified copy as proof of dispensability.

## Output and existing GoalContract extension

Use a compact decision record; omit irrelevant asset details for narrow work.

```yaml
protectedAssets: [{id, reason, evidenceIds}]
protectedCapabilities:
  - {id, minimumService, neededBy, permittedOutage, users, evidenceIds}
preservationInvariants: [{condition, evidenceIds, verification}]
acceptedTradeoffs: [{loss, bound, explicitAuthorizationEvidence}]
candidateDecisions:
  - id:
    assetVerdict: PROTECTED | NEED_EVIDENCE | ELIGIBLE
    conditions: [{name, status: pass | fail | unknown, evidenceIds}]
    netGoalContribution: {value, unit, assumptions, committedCosts}
    noncashEffects:
    goalStatus: insufficient | projected | achieved
    executionAuthorization: established | missing | not-requested
    nextAction: investigate | reversible-trial | execute-existing-scope | omit
    falsifier:
    nextSingleProof:
chosenAction:
verification: {goalEvidence, capabilityEvidence, capabilityStatus: verified | pending | violated, observedAt}
overallStatus: complete | pending | insufficient | blocked
rollback: {steps, feasibilityEvidence, time, cost, knownIrreversibleLoss}
```

Use PROTECTED for an evidenced violation, and NEED_EVIDENCE for unresolved
material facts. For example, a stated absence of any suitable substitute is
stronger than an untested possible copy: the latter is NEED_EVIDENCE, without
asserting that every copy is unusable. Both prevent that consequential action
until the condition is resolved. State the precise action and reason.

`PROTECTED` means the proposed action violates a protected condition, not that
the object must be kept forever. `NEED_EVIDENCE` is action-local uncertainty.
`ELIGIBLE` means the preservation criteria permit consideration; it can still
fall short of the target or lack execution authorization. A reversible trial
is an action, not a fourth truth state. It must itself satisfy the capability
conditions. Never trial an essential failure by disabling its only support.

Add these fields to existing intake instead of replacing it. In demo-1, reuse
the same EvidenceSnapshot and its existing positive, negative, neutral review
when that route applies. Assess admissibility before the unchanged goalScore;
a high score cannot reverse fail/unknown on a material preservation condition.
Do not launch extra reviewers merely because this contract was attached.

## The order of comparison

Let F contain only actions with established preservation conditions. Evaluate F for the combined action set and the transition between states;
two separately safe disposals may jointly remove the last backup or standby.
Within F, compare goal contribution, costs, timeliness, and preferences. If F has no
solution, state that the current constraints do not admit the target. Do not
silently relax essential needs, invent a target achievement, or substitute a
new goal. Present the smallest useful alternative or required fact.

Protect capabilities, not all objects or all hypothetical future options.
Normal authorized expenditure, wear, disposal, and deliberate bounded
tradeoffs may be appropriate. Count costs of keeping and inaction as well as
costs of change. The reference scenario must not assume that doing nothing is
automatically harmless. Do not self-assign authority over another person's
property or block their unrelated actions to preserve optionality.

## Cash example and ledger discipline

All following amounts are hypothetical KRW, not market quotations.

`free funds = realized proceeds - transaction/disposal costs - required provision`

Required provision is the unpaid, unfunded, non-overlapping cost of preserving
the identified needed function within the horizon. A replacement already paid
from these proceeds and its reserved amount are the SAME commitment: deduct
once. If funded elsewhere, disclose that source and cost instead of hiding a
loss on the wider household balance. A current cash balance already net of
fees must not have those fees subtracted again. Report both uncommitted cash
and material wider costs when they differ.

Example:80000 proceeds -10000 fee -50000 required replacement =20000 usable.
A50000 goal remains30000 short. Paying the replacement next week does not
make its committed funds freely spendable today. If the function is no longer
needed and there is no replacement obligation, the50000 is not deducted.

Possible resale price is not received cash. A listing, offer, or planned sale
is projected contribution; payment plus fulfilled handover conditions supports
achieved contribution. Overall completion also requires verified retained
capabilities: cash may be achieved while capability verification is pending.
For estimates, use explicit ranges and decision-changing
sensitivity, not fabricated precise probabilities. Do not assign money values
to rights, unique information, or essential capability merely to make a sum.

## Medicine organizer and seasonal AC

- An organizer needed for ongoing daily use, with no suitable ready substitute:
  exclude its sale even when it exactly meets a cash target. This is about
  preserving its stated function; it makes no medical treatment recommendation.
- AC unused for three winter months: establish the next warm-season requirement,
  users, and ready substitute. The unused interval alone leaves disposal
  NEED_EVIDENCE. Do not require waiting through a full year if other current
  evidence already resolves the need.
- A redundant AC whose cooling function is already adequately provided, with
  ownership and future need resolved: eligible for authorized disposal, subject
  to actual net proceeds and handover conditions. Do not invent a replacement.

## Desktop/software equivalents

| Metric target | Capability to preserve | Smallest relevant proof |
| --- | --- | --- |
| Free disk space | Recover original data | Independent readable copy and a relevant restoration check |
| Make tests green | Tested behavior and detection coverage | Reproduction, corrected cause, original assertions still checked |
| Reduce API cost | Needed quality, access, latency, privacy | Task-representative results and actual provider identity/cost |
| Delete apparently unused code | Seasonal, error, reflection/config paths | Active sourceSet plus callers/activation evidence, relevant focused test |
| Faster boot | Authentication, required checks, readiness | Required capability remains exercised and failure visible |

Reuse the repository's source-owner/lease/preimage/verification gate. The asset
contract neither bypasses it nor introduces another mutation protocol. For
hidden activation or dependencies, use the existing invisible-eye evidence
contract once. Counts, hashes, HTTP200, or metadata alone do not prove restored
data, semantic correctness, provider lineage, or Desktop execution.

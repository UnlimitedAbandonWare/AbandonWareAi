---
name: demo1-adaptive-verifier-escalation
description: Use when a bounded worker stalls, repeats failures, produces no new evidence
---

# Demo1 Adaptive Verifier Escalation

Route failure evidence without treating agent count or confidence as truth. Keep the
existing owner, coherence verifier, and source-mutation gates authoritative.

## Non-triggers

Do not invoke for ordinary success, generic three-way review, raw verbal
confidence, a source-mutation request, or future-window observations. Route
future-window signals to `demo1-forecasting-minority-signals`; leave source work
to its existing owner and preflight.

## Workflow

1. Select mode `ROUTE` for one bounded run or `EVALUATE` for sealed labeled fixtures.
2. Honor `workflowOwner`; return `SKIP_OWNER_CONTROLLED` for protected triads.
3. Freeze the redacted health envelope: identifiers or hashes, lifecycle, policy,
   state/attempt/evidence/budget signals, normalized outcome, references, and
   redaction result. Exclude prompts, queries, secrets, headers, and environment dumps.
4. Separate checkable `operationalSignals` from claim-to-evidence `semanticSignals`.
   Do not infer semantic failure from tier, entropy, verbosity, or confidence.
5. Run one local deterministic probe with `maxAttempts=1`, `mutationAllowed=false`,
   and `timeBudgetMs>0 or tokenBudget>0`. If neither budget is present and strictly
   positive, return `HOLD` with `probe-budget-missing-or-nonpositive` and do not
   probe. Re-read one known artifact, validate one invariant, retry one idempotent
   read, or compare one hash. Delegate semantic retrieval to the existing three-slot
   counter-evidence owner; do not create a fourth query.
6. Delegate any semantic retrieval to the existing three-slot counter-evidence owner.
7. Dispatch `blind_solver` and `falsifier` only after the post-probe gate. Hide the
   worker conclusion from `blind_solver`; give `falsifier` one target and smallest
   falsifying test. Set `independenceProven=false` for shared model, prompt,
   retrieval, or tool provenance.
8. Collapse correlated provenance; never majority-vote. Count correlated packets as
   one lineage and swap packet order before accepting an aggregation decision.
9. Select a candidate or `HOLD` with `decisionAuthority=route_only`. A correction
   needs a checkable falsifier result plus independent support and remains
   `SUGGESTION_ONLY`; it cannot establish factual truth.
10. Require the existing coherence verifier before factual confirmation. A changed
    order, malformed packet, missing evidence, or correlated-only support is `HOLD`.
11. Report `productionRecallStatus=unobservable` without a valid non-triggered audit.

## Route limits

Require a named detector policy or sealed fixture; otherwise return `HOLD` with
`calibration-policy-missing`. Allow at most one probe, two verifier calls, and one
aggregator call. Permit `HANDOFF_STRONG_JUDGE` only for high impact with explicit
authorization and a separate positive budget; never dispatch it automatically.
`routeGatePassed` proves route packet completeness, redaction, provenance handling,
budget accounting, and order stability only. It is not a factual verification gate.

## Output and evaluation

Emit `demo1.adaptive-verifier-escalation.v1` with action, post-probe action,
terminal route, reason codes, probe, role packet references, provenance groups,
call counts, `independenceProven`, `orderStable`, `routeGatePassed`, evidence needs,
and a redacted compact line. Read [the evaluation contract](references/evaluation-contract.md)
before schema or metric work. Run `scripts/evaluate_failure_routes.py` only for
sealed offline `EVALUATE` fixtures; it never dispatches agents or authorizes mutation.

## Common mistakes

- Do not turn a timeout or unavailable tool into semantic failure.
- Do not use `HOLD` to hide an abstention from metrics.
- Do not report fixture recall as production recall.
- Do not replace an owner-controlled triad or coherence verdict.

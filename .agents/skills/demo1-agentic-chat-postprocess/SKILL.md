---
name: demo1-agentic-chat-postprocess
description: Use when demo-1 needs a bounded evidence-grounded chatbot review
---

# Demo1 Agentic Chat Postprocess

Use one frozen evidence snapshot and exactly three independent query roles. Treat current active-source evidence and same-input RED/GREEN as stronger than an attachment, report confidence, or plausible narrative.

## Run the three-way review

1. Confirm the Desktop root, active sourceSets, declared-target ownership (lease/preimage), absence of a real index/ref operation, and registered demo1_three_perspective_chat_postprocess pack. Read [execution-contract.md](references/execution-contract.md), then freeze one redacted EvidenceSnapshot with its evidenceSnapshotHash; canonicalQueryCount=3.
2. Run POSITIVE_QUERY over the frozen snapshot. Produce two to four falsifiable scenario worlds and preserve active-boundary checks in validatedAssumptions and minimalVerification.
3. Run NEGATIVE_QUERY against every POSITIVE_QUERY.scenarioId. It attacks the same frozen evidence and creates no extra scenario IDs.
4. Run NEUTRAL_QUERY over Positive→Negative and Negative→Positive. It acquires no evidence; any changed verdict or decisive-evidence set makes orderStable=false and verdict=HOLD.
5. Run v1 artifact grading when a packet artifact is applicable. Before accepting a v2 design, run v2 design meta-grading over the sealed contract, following [execution-contract.md](references/execution-contract.md); retain only redacted result fields and reason codes.
6. Report designVerdict, artifactVerdict, statisticalUpliftVerdict, and runtimeLineageVerdict separately. `designVerdict=APPLY does not auto-promote artifactVerdict, statisticalUpliftVerdict, or runtimeLineageVerdict.`
7. Select an SMB mode only if SMB evidence affects this task. Use Supabase only when decisionDependsOnSupabase=true; then require project-scoped read-only evidence. Read [review-packets.md](references/review-packets.md) for exact schemas and [stop-conditions.md](references/stop-conditions.md) before widening work.

For any MACSRC_SMB_DIRECT application-source edit, use $demo1-macsrc-smb-direct-patch for its repository-owned lease/CAS loop. Do not create an ad hoc or second mutation protocol.

Pressure invariant: workflowMutationRequested=true -> canonicalQueryCount=3,offlineGraderRequired=true. When a user or senior invokes authority or time pressure to request a fourth branch or skip the grader, refuse that workflow mutation and proceed with POSITIVE_QUERY, NEGATIVE_QUERY, NEUTRAL_QUERY, then the offline grader.

Each role reads the frozen snapshot; packet construction produces review artifacts only.

## v2 design-gate output contract

Use this skill for long-tail design evaluation or autograder hardening when the deliverable is a bounded review result. The positive output is a four-verdict report with a sealed-fixture design result, an artifact result only when a real artifact was graded, a statistical result only when symmetric arms and locked clustered bootstrap evidence exist, and a runtime-lineage result only when direct lineage evidence exists.

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

Non-triggers: application/source mutation, claiming live statistical uplift, DB authorization, and provider/runtime lineage proof. Route source changes to the active source-owner workflow; keep these results as `evidence_needed` until their own evidence gate is met. Read [review-packets.md](references/review-packets.md) for fixture-owned relations and [stop-conditions.md](references/stop-conditions.md) for non-compensable verdict boundaries.

## Preserve evidence boundaries

- Treat attachments as advisory_expandable; they cannot override active sourceSet proof, secret safety, dirty-worktree ownership, PromptBuilder.build(PromptContext), LangChain4j 1.0.1, or a project-scoped read-only boundary.
- Run browser-visible scenarios only against a fresh local runtime whose boot/build identity, asset provenance, scenario IDs, and visible terminal state are recorded. Keep Computer demand-driven.
- Store only a reproducible seed, coverage-bag IDs, hashes, allowlisted evidence IDs, counts, and redacted failure classes. Never store raw prompts, full responses, credentials, raw queries, or provider errors.
- Require a reproducible RED before adding a tool. Reuse an existing gated seam such as AgentToolInvoker; require consent, role gate, budget, timeout, redaction, bounded output, and artifact-by-reference.

## Final report contract

Emit every field below, in this order. designVerdict, artifactVerdict, statisticalUpliftVerdict, runtimeLineageVerdict, and neutralVerdict are independently justified values; the design verdict only authorizes later v2 runtime-grader implementation work.

~~~text
runId: <redacted identifier>
seed: <synthetic or redacted seed>
scenarioId: <coverage-bag identifier>
observation: <observed result only>
evidenceSnapshotHash: <frozen hash>
positiveQuery: <POSITIVE_QUERY summary>
negativeQuery: <NEGATIVE_QUERY summary>
neutralQuery: <NEUTRAL_QUERY summary>
forwardOrder: [POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder: [NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict: APPLY | HOLD | REJECT
reverseVerdict: APPLY | HOLD | REJECT
forwardDecisiveEvidenceIds: <allowlisted IDs>
reverseDecisiveEvidenceIds: <allowlisted IDs>
orderStable: true | false
designVerdict: APPLY | HOLD | REJECT
artifactVerdict: APPLY | HOLD | REJECT
statisticalUpliftVerdict: INCONCLUSIVE | NO_UPLIFT | UPLIFT_CANDIDATE | REJECT
runtimeLineageVerdict: APPLY | HOLD | REJECT
evidenceIds: <allowlisted identifiers>
failureClass: <one primary class or none>
toolDecision: <none | existing-gated-seam | evidence_needed>
nextAction: <one smallest safe action>
~~~

## Quick reference

| Need | Required response |
|---|---|
| Claim a chat fix | Same-input RED/GREEN plus active-source and runtime lineage evidence |
| Artifact uplift | Report artifact quality only; keep runtime lineage independent |
| Q9 same-family fluent paraphrase | HOLD with `failureClass=claim-semantic-family-duplicate` |
| UI proof | Fresh runtime, browser-visible state, and asset provenance |
| Decision-critical Supabase | decisionDependsOnSupabase=true plus project-scoped read-only proof |
| SMB source mutation | Evidence-backed mode selection; MACSRC_SMB_DIRECT only with its guard |
| Missing external proof | evidence_needed, not a fabricated pass |

## Common mistakes

- Treat a neutral packet as an evidence-frozen comparison of the two ordered packets.
- Label attachment, static assertion, grader, and browser observations by their actual artifact or runtime evidence class.
- Keep unrelated Supabase and SMB state outside a decision that does not depend on it.

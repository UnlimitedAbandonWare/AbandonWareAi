# Minority Signal Forecast Design

Date: 2026-07-14  
Status: approved by the user's instruction to continue from the attached `goal-objective.md`  
Scope: repo-local skill and standalone execution directive, plus the smallest MLA breadcrumb telemetry correction proven by a focused test

## Problem

The counter-evidence family can generate alternatives, retrieve three bounded counter-evidence queries, and make a terminal coherence decision. It does not own a durable, time-bounded record for the rarer case where many workers share one provenance lineage and one independent reviewer predicts a later observation that could change the decision.

Counting workers as votes would reward correlation. Re-running broad retrieval or dispatching more agents would increase Codex usage without creating independent evidence. The missing seam is a compact `PredictionLedger` contract that preserves the minority forecast until its observation window opens, then permits one bounded read-only observation and hands normalized evidence to the existing coherence verifier.

## Decision

Add the repo-local `$demo1-forecasting-minority-signals` skill and a standalone 9-hour Safe Patch directive. The new skill owns only the forecast ledger lifecycle; it does not replace the four existing counter-evidence skills and it cannot authorize a source patch.

The runtime model topology is optional supporting configuration, not a source-of-truth vote. When current account and tool availability are verified, GPT-5.6 Sol with pro reasoning may coordinate, Luna may run bounded exploration, and Terra may provide an independent review. Worker count never increases evidence weight. Current model names and pro-mode semantics must be rechecked against the [official OpenAI model guidance](https://developers.openai.com/api/docs/guides/latest-model) before a future dispatch.

## Ownership

| Concern | Owner |
| --- | --- |
| Mutually exclusive hypotheses | `demo1-generating-falsifiable-hypotheses` |
| Exactly three counter-evidence queries | `demo1-retrieving-counter-evidence` |
| Terminal coherence verdict | `demo1-verifying-evidence-coherence` |
| Conditional multi-stage routing | `demo1-triangulating-counter-evidence` |
| Time-bounded minority forecast ledger | `demo1-forecasting-minority-signals` |
| Final source decision and verification | Desktop canonical-root verifier |

## Ledger Contract

The machine-readable schema is `demo1.minority-forecast-ledger.v1`. A ledger entry is eligible only when all of these fields are supplied from versioned evidence rather than invented by the agent:

- `decisionTarget`, exact `originalClaim`, and `sourcePacketRefs`
- `counterClaim`, `predictedObservation`, and one explicit `falsifier`
- bounded ISO-8601 `validAfter` and `validUntil`
- `provenanceGroup` and an evidence reference supporting independence
- bounded read-only `observationMethod` and `observationBudget` with one attempt,
  a positive duration limit, and `mutationAllowed=false`
- distinct `ifCorroborated` and `ifDisconfirmed` decision impacts
- `decisionAuthority=probe_only`

The root packet also reports `packetType`, `action`, `ledgerEligible`,
`correlatedMajority.provenanceGroups`, `effectiveIndependentGroups`,
`similarityIsTruth=false`, `nextCheckAt`, `nextAction`,
`verificationGatePassed=false`, `compactReportLine`, and explicit
`evidenceNeeded` rows. Its delegation packet records `parentInstruction`,
bounded `childAssignment`, `instructionConflict`,
`conflictAction=report_conflict_and_run_bounded_read_only_probe|none`,
`inheritedConsent=false`, and `inheritedToolScope=false`.

## Lifecycle

1. `RUN`: create one `PENDING` entry when every eligibility field and the observation cost are known.
2. Before `validAfter`, perform no probe and reuse the same ledger reference.
3. During the window, perform at most one bounded read-only observation.
4. Resolve to `CORROBORATED` or `DISCONFIRMED`; if no valid observation exists by `validUntil`, resolve to `EXPIRED`.
5. Preserve the prior ledger version and hand normalized evidence to the coherence verifier.
6. A forecast status never becomes a terminal verdict and never sets `verificationGatePassed=true`.

Use `DEFER` when the exact claim, time window, independence, method, or cost is missing. Use `SKIP` when the forecast cannot change the decision. Use `REUSE` when an unresolved ledger already covers the same claim and window.

## Cost and External-Evidence Boundaries

- Treat correlated workers as one provenance group; do not majority-vote.
- Do not dispatch another worker merely to increase confidence.
- Reuse cached summaries and existing ledger versions before rescanning.
- Browser and Computer are demand-driven supporting proof.
- Supabase is read-only and remains `evidence_needed` without current project-scoped authorization.
- PatchDrop is manual-only for an explicit external bundle.
- Desktop source, focused tests, Gradle gates, and redacted diagnostics remain final authority.

## MLA Breadcrumb Integration

The forecast artifacts do not add a second telemetry system. Existing MLA/CIH-RAG telemetry remains under `MlaBreadcrumb`, `TraceFilter`, `TraceSnapshot`, and their tests. A breadcrumb append operation must update `cihRag.mlaBreadcrumbCount` immediately so SSE and reward-only traces do not expose a breadcrumb list with a stale or absent aggregate count.

The permitted runtime patch is therefore limited to centralizing the aggregate-count refresh after the existing append operations. No new prompt builder, retriever, storage layer, or raw query field is introduced.

## Acceptance Criteria

- The baseline pressure scenario shows why the old family lacks durable forecast fields.
- The new skill returns `DEFER` for incomplete evidence and names every missing artifact.
- `quick_validate.py` passes for the new skill.
- The existing four-skill counter-evidence validator remains green.
- The skill-family postprocessor reports the new skill without secret or scaffold findings.
- Focused MLA tests prove immediate aggregate-count updates for SSE and LLM reward breadcrumbs.
- LangChain4j purity, active sourceSet hygiene, compile, targeted tests, and count-only secret checks pass before completion is claimed.
- Browser or Computer proof is supporting only; it cannot replace the source and test gates.

## Rollback

Delete the new standalone skill, prompt, and design files to remove the forecast workflow. Revert only the aggregate-count helper calls in `MlaBreadcrumb` to roll back the runtime correction. No schema migration, service, daemon, provider configuration, or secret is created.

# Evidence-Grounded Triadic Debug Adjudicator Design

## Objective

Add an admin-only, demand-driven debug adjudicator that evaluates the hottest redacted `DebugEventStore` fingerprints with three model roles: existing SUPPORT and FALSIFY sampling plus a neutral HOLD verifier. The public decision is `APPLY`, `HOLD`, or `REJECT`; it is advisory and never applies a source patch by itself.

## Boundaries

- Reuse `DiverseSamplingOrchestrator.sampleDualHypotheses(...)` for exactly two evidence-scored dossiers.
- Extend `EnsembleJudgeService` with a debug-specific neutral verifier contract. Keep the legacy `judge(...)` rollback path unchanged.
- Keep all model prompts behind `PromptBuilder.build(PromptContext)`.
- Invoke the triadic path only from an admin-gated diagnostics POST. `DebugCopilotService.maybeEnrichTrace()` remains non-blocking and does not start model calls.
- Default the feature to disabled with `DEBUG_COPILOT_TRIADIC_ENABLED=false`.
- Reuse `DebugEventStore`; add no event store, daemon, broker, SMB dependency, or external producer requirement.
- Expose only safe scalars and hashes: decision, reason code, confidence, candidate hash, evidence count, role count, and grounding scores. Never expose or persist raw prompts, queries, dossiers, snippets, headers, tokens, or full errors.

## Data Flow

1. `POST /api/diagnostics/debug/triadic-adjudication` calls `DebugCopilotService.adjudicateLatestPatchCandidate()`.
2. `DebugCopilotService` supplies the recent redacted fingerprint summary from `DebugEventStore` to `EvidenceGroundedTriadicDebugAdjudicator`.
3. The adjudicator normalizes at most six non-triadic fingerprints into bounded `RagEvidenceMetadata` rows. Fewer than two rows returns `HOLD` without a model call.
4. Existing dual sampling produces SUPPORT and FALSIFY dossiers.
5. `EnsembleJudgeService.judgeDebugPatch(...)` performs one low-variance HOLD-role verification call and returns a strictly parsed advisory vote.
6. Pure code computes the final decision. `APPLY` or `REJECT` requires a matching neutral vote, a `SUFFICIENT` winning dossier, grounding at least `0.70`, and a score gap of at least `0.05`; every other state becomes `HOLD`.
7. The safe result is stored as the latest snapshot, written to low-cardinality `TraceStore` keys, and emitted once through `DebugEventStore`.
8. `GET /api/diagnostics/debug/triadic-adjudication` and `/admin/debug-events` display the latest safe result.

## Failure Contract

- Disabled, insufficient fingerprints, missing dual roles, blank or malformed judge output, model absence, timeout, exception, unresolved score gap, or invalid evidence IDs all return `HOLD` with a stable reason code.
- Caller cancellation remains cancellation and is not converted to success.
- The normal chat/answer ensemble path and its call counts are unchanged.
- `APPLY` is a recommendation only. Existing Safe Patch, source ownership, test, and Desktop final-proof gates still decide whether code may be changed.

## Verification Contract

- TDD decision-table tests cover disabled, APPLY, REJECT, HOLD, exact `0.05`, below-gap, incomplete roles, invalid judge IDs, and exceptions.
- Judge tests prove one neutral call, canonical prompt-builder ownership, strict parsing, and fail-soft HOLD.
- controller/template tests prove GET/POST wiring, admin POST boundary, DOM ids, and absence of raw prompt/query/dossier fields.
- Focused Gradle tests precede `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, `:app:classes`, and `bootJar` in an isolated Desktop cache.
- Runtime proof checks the GET endpoint and `/admin/debug-events`; the POST path may remain disabled in the proof run to avoid unrequested provider calls.


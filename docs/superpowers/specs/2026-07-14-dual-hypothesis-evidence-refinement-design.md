# Dual-Hypothesis Evidence Refinement Design

## Context

The Desktop active runtime currently samples three mutually exclusive dossiers
(`cooperative`, `base_rate`, and `opportunistic`) for PromptContext refinement.
All three receive the same source-list-derived `citationScore`, so the score does
not measure whether an individual candidate grounded its own claims. The live
ChatWorkflow already treats these candidates as untrusted references, skips the
ensemble judge, and performs one logical primary-final wrapper call.

The approved `staps.txt` design changes the active refinement lane to two
directional hypotheses: `SUPPORT` and `FALSIFY`. It requires code-owned evidence
scoring, no forced winner for a score gap below `0.05`, and continued final
ownership by the primary model through `PromptBuilder.build(PromptContext)`.

## Approaches Considered

1. Replace every triad and judge contract with the new pair. This most literally
   changes the old ensemble, but it breaks the isolated legacy judge rollback
   seam and expands the diff across already-dirty contract files.
2. Add a dual-hypothesis refinement entry point while preserving the legacy
   triad for `tryGenerate()`. This changes the active ChatWorkflow behavior,
   reduces auxiliary calls from three to two, and keeps the rollback seam. This
   is the selected approach.
3. Keep three candidates and only change prompt wording. This leaves the shared
   citation score defect and does not reduce model-call cost, so it is rejected.

## Runtime Design

`EnsembleFinalAnswerService.sampleCandidatesForRefinement(...)` calls a new
dual-hypothesis method on `DiverseSamplingOrchestrator`. The new method runs two
bounded workers under the existing shared deadline and cancellation contract:

- `support`: requested temperature `0.85`, top-p `0.90`.
- `falsify`: requested temperature `0.00`, top-p `0.40`.

The dynamic model factory remains the single provider/model boundary and applies
`ModelCapabilities` sanitization. No new model provider, agent broker, daemon,
or background service is introduced.

The existing `sample(...)` triad remains available only for the compatibility
`tryGenerate()`/legacy judge path. ChatWorkflow continues to attach the two
refinement candidates to `PromptContext` before the canonical prompt builder and
does not call the ensemble judge.

## Candidate Contract And Scoring

Each directional model returns bounded claim rows:

```text
DIRECTION: SUPPORT|FALSIFY
CLAIM: <bounded claim> | EVIDENCE: <comma-separated ev1 ids or NONE> | STATUS: SUPPORTED|CONTRADICTED|UNSUPPORTED
CONCLUSION: <bounded uncertainty-aware conclusion>
```

The prompt includes the existing redacted `EnsembleEvidenceMatrix`, which maps
opaque `ev1:*` identifiers to public source markers. Raw queries, snippets,
credentials, and full external payloads are not added to traces.

A pure scorer ignores model self-ratings and computes:

```text
evidenceRate = claims with at least one valid matrix evidence id / total claims
sourceDiversity = distinct cited provenance groups / available provenance groups
contradictionRate = contradicted claims / total claims
groundingScore = 0.70*evidenceRate + 0.20*sourceDiversity + 0.10*(1-contradictionRate)
```

If no valid evidence ID is cited, `groundingScore` is exactly `0`. IDs absent
from the matrix are ignored. `EvidenceStatus` is `SUFFICIENT` only when every
claim has valid evidence and no claim is contradicted; otherwise it is
`INSUFFICIENT_OR_CONTRADICTED`.

`SampledCandidate` gains direction, evidence status, and score components while
retaining its seven-argument compatibility constructor for existing triad/judge
tests and callers.

## Selection And Final Ownership

Both safe candidates are attached to the final `PromptContext`. The service may
record the higher-scoring direction for diagnostics, but when the absolute score
gap is below `0.05` it records `underdetermined` and does not force a winner.
Candidate text is never returned directly as the final answer.

`StandardPromptBuilder` renders the code-owned score components inside the
existing escaped `UNTRUSTED HYPOTHESIS REFERENCES` block. The primary model must
verify candidate claims against citable evidence.

## MLA And External Evidence Boundaries

`MlaBreadcrumb` already emits `cihRag.breadcrumb.queryRedacted=true` and bounded,
count-only interaction-policy facts. No MLA source edit is justified by this
defect. Browser and Computer remain supporting verification lanes; Supabase and
Mac mini/Notebook evidence are not required for this Desktop-only backend patch.

## Failure Handling And Rollback

Malformed output, missing valid evidence IDs, unsafe risk, cancellation, timeout,
or provider failure keeps the existing fail-soft primary answer path. Cancellation
continues to propagate. Rollback consists of routing refinement back to the
legacy `sample(...)` call and removing the new scorer/metadata fields; no schema,
secret, or external-service rollback is required.

## Verification Design

- RED/GREEN pure scorer tests for valid IDs, fake IDs, diversity, contradiction,
  and zero-evidence behavior.
- RED/GREEN sampler test for exactly two roles and requested parameter profiles.
- Service test for two untrusted candidates, zero judge calls, and tie handling.
- Prompt rendering test for bounded score metadata and untrusted framing.
- Existing ChatWorkflow source-boundary test for judge `0` and primary logical
  final wrapper `1`.
- Focused Gradle tests, then LangChain4j/sourceSet/compile/package gates and a
  count-only secret scan.


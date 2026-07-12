# Counter-Evidence Harmony Postprocess Design

Date: 2026-07-12

Status: design approved in chat; implementation pending written-spec review

## Goal

Improve semantic consistency across the counter-evidence skill family while preserving its bounded three-query protocol, conditional routing, single-verdict ownership, and weak-signal safety boundary. Subtract only redundant entrypoint prose whose removal cannot change routing or packet contracts.

The intended behavior is a cooperative pipeline: hypothesis generation creates falsifiable alternatives, retrieval tests them with exactly three counter-evidence queries, coherence verification alone owns the terminal verdict, and the triangulator conditionally routes or reuses those stages without inventing evidence.

## Current evidence

- Canonical root: `C:\\AbandonWare\\demo-1\\demo-1\\src`
- Branch: `main`
- Index lock: absent at design intake
- Active PatchDrop top-level patches: 0; one nested producer artifact remains supporting evidence
- Source-scan secret-pattern hits: 0
- Compact family validation: 7 valid skills, 0 prompt, metadata, trigger, coverage, discovery, or budget errors
- Targeted counter-evidence validation: 4 skills, 0 reported errors
- The four counter-evidence entrypoints total 636 lines and 4,549 words
- Current trim mode is `opportunistic_only` with no budget-pressure candidates
- Supabase project-scoped proof is unavailable; its configured lane is read-only and not required for this source-only change
- Browser and Computer evidence are optional because this patch does not alter a UI or external integration

These values are an intake snapshot. Implementation must rerun the relevant read-only checks before editing.

## Scope

### In scope

1. Distinguish an absent original claim from a present literal `unknown` claim in the verifier contract.
2. Make `retrievalBudget` requirements conditional on the triangulator route decision.
3. Add validator assertions that fail when either semantic distinction regresses.
4. Remove a small amount of redundant triangulator prose only where the executable contract remains fully present in the entrypoint.
5. Revalidate the complete skill family and run a read-only postfix review.

### Out of scope

- Adding, removing, renaming, or reordering the three query slots
- A fourth query, retry cascade, open-ended search, or model-generated query expansion
- Moving output schemas or routing contracts into references
- Editing the generator or retriever unless a RED validator proves an unanticipated direct defect
- Changing verdict ownership, evidence schema ownership, or packet field names
- Promoting weak signals into verdict evidence or decisive conflict
- Java, Spring, PromptBuilder, database, Supabase, Browser, Computer, PatchDrop, or runtime behavior changes
- Broad skill-family compression or artifact deletion

## Architecture and ownership

The family keeps four distinct owners:

1. `demo1-generating-falsifiable-hypotheses` owns mutually exclusive, falsifiable candidate hypotheses and emits a reusable hypothesis packet.
2. `demo1-retrieving-counter-evidence` owns bounded retrieval and normalized evidence rows. It may change a weak-signal lifecycle only from `observed` to `probe_selected` or `resolved`.
3. `demo1-verifying-evidence-coherence` is the sole owner of the terminal coherence verdict and next action.
4. `demo1-triangulating-counter-evidence` owns conditional routing and packet reuse. It does not create a second verdict or reinterpret evidence content.

Downstream stages consume upstream packets by reference when their schema and freshness gates pass. A revision supersedes the prior packet rather than appending an unbounded reasoning trace.

## Design

### 1. Original-claim state split

The verifier will define two non-overlapping states:

- **Absent artifact or field:** if the required claim artifact is missing, or the `originalClaim` field is absent, the stage returns `DEFER`, emits no `VerdictPacket`, and records only the existing defer decision structure.
- **Present literal unknown:** if `originalClaim` exists and its explicit value is the literal state `unknown`, the verifier may run. With otherwise valid inputs it can produce `UNDERDETERMINED` and `HOLD` through the normal verdict contract.

Whitespace-only, null, malformed, or structurally absent values remain absent rather than being normalized into the literal `unknown` state. This prevents a missing prerequisite from being mistaken for a substantive but unresolved claim.

The validator will enforce both clauses in the verifier entrypoint so that a future edit cannot retain one side while deleting the other.

### 2. Conditional retrieval-budget contract

The triangulator will tie `retrievalBudget` to its route decision:

- `RUN` retrieval: a bounded retrieval budget is required.
- `REUSE` retrieval: the stage uses the referenced packet's existing bounded budget or budget metadata and does not require a new budget allocation.
- Direct verifier routing, `SKIP`, or `DEFER`: `retrievalBudget` may be null or explicitly `not_required`.

This preserves the existing direct-verifier path for already normalized evidence and prevents the input contract from unconditionally blocking it. Missing budget still fails closed whenever the route actually requests new retrieval.

The validator will require all three conditional branches to be represented in the triangulator entrypoint.

### 3. Fixed three-query protocol

Every new retrieval run continues to use exactly these three query slots:

1. `authoritative_constraint`
2. `alternative_or_unknown`
3. `provenance_and_time`

The slots are complementary rather than interchangeable:

- `authoritative_constraint` tests the anchored claim against primary constraints or specifications.
- `alternative_or_unknown` seeks a mutually exclusive explanation or evidence that the identity remains unresolved.
- `provenance_and_time` checks source lineage, temporal validity, and version drift.

No fourth slot is allowed. Rewording a query updates its existing slot and supersedes the earlier query trace. Reuse does not consume another query run.

### 4. Weak-signal amplification boundary

Weak signals receive additional attention, not additional authority. Their only permitted lifecycle is:

```text
observed -> probe_selected -> resolved
```

Selection may increase a signal's probe priority once, with `maxAttempts=1`. It must not increase the signal's evidence strength, confidence, verdict weight, or decisive-conflict status. A weak-signal identifier alone can never support the final verdict; only normalized evidence returned by the bounded probe may enter coherence verification under the ordinary evidence rules.

If the probe remains inconclusive, the signal is resolved as inconclusive or retained as an explicit limitation according to the existing packet contract. It does not trigger another search loop.

### 5. Low-risk trace subtraction

The triangulator entrypoint is the only prose-reduction target in this pass. Implementation may remove duplicated overview sentences, a redundant worked example, and red-flag text that repeats an existing normative rule.

The following material must remain directly visible in the main `SKILL.md`:

- conditional `RUN | REUSE | SKIP | DEFER` routing table;
- complete input and output packet skeletons;
- all three fixed query-slot names;
- weak-signal lifecycle and ownership boundary;
- sole verifier verdict ownership;
- final `verificationGatePassed` completion gate.

No output schema is moved into a reference because the current family validator extracts the contract from the main entrypoint. This avoids coupling a prose cleanup to validator architecture changes.

## File map

Implementation is limited to these files unless a new RED result proves the design incomplete:

- `.agents/skills/demo1-verifying-evidence-coherence/SKILL.md`: clarify absent versus present literal-unknown behavior.
- `.agents/skills/demo1-triangulating-counter-evidence/SKILL.md`: make the retrieval budget conditional and perform bounded prose subtraction.
- `.agents/skills/demo1-triangulating-counter-evidence/scripts/validate_counter_evidence_skill_family.py`: add semantic regression assertions before skill edits.

The generator and retriever are read-only control surfaces for this pass.

## Error handling and rollback

- An index lock, active top-level PatchDrop patch, source-set ambiguity, or unsafe overlap stops implementation.
- Validator failures unrelated to the two designed semantics are classified separately and do not authorize broader cleanup.
- A route that requests retrieval without a bounded budget fails closed.
- Missing claim input returns a defer decision without fabricating a verdict.
- Missing Supabase project scope remains `evidence_needed` in its own read-only lane and does not block this local skill patch.
- Rollback is the reversal of the three-file minimal diff; unrelated dirty or untracked files are never reset, deleted, or staged.
- Validation output remains count-only or schema-level and must not expose raw prompts, queries, credentials, headers, or environment values.

## RED, GREEN, and postfix verification

Implementation follows this order:

1. Rerun preflight, PatchDrop inventory, source scan, compact family validation, and the targeted family validator.
2. Add RED validator fixtures or assertions for:
   - absent `originalClaim` means `DEFER` with no `VerdictPacket`;
   - present literal `unknown` may yield `UNDERDETERMINED` and `HOLD`;
   - `RUN` retrieval requires a bounded budget;
   - `REUSE` requires no new budget;
   - direct verifier, `SKIP`, and `DEFER` accept null or `not_required` budget.
3. Prove the new assertions fail against the pre-patch skill wording for the intended reasons.
4. Apply the two minimal skill edits and bounded triangulator prose subtraction.
5. Run the targeted counter-evidence validator until GREEN.
6. Run each affected skill's quick validation and the compact family `SummaryJson` validation.
7. Run static checks confirming exactly three query slots, one verifier verdict owner, `maxAttempts=1`, and no weak-signal decisive authority.
8. Run a changed-file count-only secret-pattern scan and `git diff --check`.
9. Perform a fresh read-only postfix review for contract conflicts, schema drift, conditional-routing gaps, and unintended scope expansion.

Browser, Computer, Supabase mutation, Gradle, boot, and UI smoke are intentionally omitted because no runtime or external surface changes. If implementation evidence unexpectedly crosses one of those boundaries, work stops for a new scope decision.

## Acceptance criteria

- The verifier unambiguously separates absent claim input from a present literal `unknown` claim.
- The triangulator requires a new bounded `retrievalBudget` only when it routes to new retrieval.
- Direct verifier and packet-reuse paths remain reachable without fabricated budget data.
- Every retrieval run has exactly the three named query slots and no fourth query path.
- Weak signals can receive one bounded probe but never direct verdict authority.
- The verifier remains the only terminal verdict owner.
- The main triangulator entrypoint retains every contract element consumed by the validator.
- Targeted and compact family validators report zero errors after the patch.
- The final diff is limited to the three approved files, contains no secret-pattern hits, and does not modify unrelated worktree state.

## Known limitation

This pass improves contract clarity and removes low-risk repetition; it does not attempt the larger 1,120-1,305 word reference split identified by the audit. That larger compression would require changing how the validator discovers output contracts and therefore needs a separate design and regression surface.

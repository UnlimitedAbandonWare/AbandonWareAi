# Evidence Release State Design

Date: 2026-08-04  
Status: architecture approved; written specification awaiting user review  
Scope: normal `ChatWorkflow` final-return path in the Desktop canonical checkout

## Problem

The browser reproduction requested retrieval and rendered `Evidence 0`, but the
visible answer remained an ordinary unsupported candidate instead of the exact
`evidence_needed` token requested by the user.

The immediate symptom is in `ChatHarmonyTracePostprocessor`: it can enforce the
token only when controller-owned `resolvedUseRag` trace metadata survives. That
metadata is written before `ChatWorkflow.continueChat` and then removed by the
workflow's request-start `TraceStore.clear()`. Synthetic postprocessor tests
populate the missing metadata directly, so they do not exercise the real
controller-to-workflow lifetime.

Repairing only the trace key would still leave semantic release after verifier
and memory decisions. The semantic decision therefore belongs in the existing
workflow release boundary, while the postprocessor remains a formatting-only
component.

A second ambiguity exists upstream. `RagEvidenceAttributionService` currently
returns the same empty `List` for a completed zero-promotion decision and for a
gate exception. `ChatWorkflow` also converts a missing service or outer failure
to an empty list. Empty-list truth is therefore insufficient for a fail-closed
release decision.

## Current evidence

- Active backend owner: `main/java` and `main/resources`.
- `RagEvidenceAttributionService.promoteForPrompt` has one production caller:
  `ChatWorkflow`.
- The working tree already has one canonical
  `FinalVerificationReleaseDecision`; this design extends that owner rather
  than adding a second release framework.
- Official-source filtering can reduce a successful promotion to zero.
- A late detour retry can retrieve new documents after initial promotion, but
  it does not re-run attribution or update `citableEvidence`.
- The existing post-verifier ordering is release decision, memory candidate,
  appendix packaging, final postprocessor, durable writers, and `ChatResult`.
- An isolated baseline run completed 5 focused test classes and 77 tests with
  zero failures, errors, or skips.

### Frozen mutation manifest

Every existing target is pinned to its approval-time SHA-256 and owned hunk.
Every new target is pinned to an explicit nonexistence precondition. The gate is
recomputed immediately before the first test or production edit; a mismatch,
an unexpectedly existing new target, or a required target outside this manifest
is `HOLD` and requires design/hunk re-review.

| Target | Approval precondition | Owned hunk |
| --- | --- | --- |
| `main/java/com/example/lms/service/ChatWorkflow.java` | SHA-256 `397AEC4B37B2346D10A4C85A32D9E7176F5C45B8FF81CDDF5C1EB313D75EB6CA` | Existing attribution call/result carrier, sole detour caller/result carrier, existing final release decision/state composition, and bounded release trace writes only. |
| `main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java` | SHA-256 `B8A5D694A7FC6029127C8B44E22CAC567A0A20410FE8A16D7F84D5B09FC6BA07` | Existing `promoteForPrompt` flow plus the detailed delegate and its nested immutable result types only. |
| `main/java/com/example/lms/api/ChatHarmonyTracePostprocessor.java` | SHA-256 `2F3642304D59219A99B66DF183F5F91BE5BC8ADB5865B1B9CED074005B71B93F` | Remove only the evidence-semantic branch and parser that move upstream; retain formatting and diagnostic cleanup. |
| `main/java/com/example/lms/service/EvidenceNeededDirectivePolicy.java` | Must not exist. | Entire new pure policy file; no state, trace, or raw-query retention. |
| `src/test/java/com/example/lms/service/rag/RagEvidenceAttributionServiceTest.java` | SHA-256 `A964ACDE839839D360F8FF8E106F8FB35ABE14D24A3DC085A975D32F7A618996` | Add typed detailed-result, failure-distinction, immutability, and legacy-delegate assertions only. |
| `src/test/java/com/example/lms/service/ChatWorkflowFinalVerificationReleaseGateTest.java` | SHA-256 `4E815E7CADDCD773421926C6870F76334F9C781006985277C0FE686788A0C2FE` | Extend the existing release matrix with evidence-state, precedence, appendix/memory, and late-addition cases only. |
| `src/test/java/com/example/lms/api/ChatHarmonyTracePostprocessorTest.java` | SHA-256 `AED35DF7EA3766F65B6073A913BB94C571F6774BFBE71671CE5802E2EA83EBE2` | Replace semantic-shaping assertions with formatting-only preservation assertions; migrate directive cases without changing unrelated fixtures. |
| `src/test/java/com/example/lms/api/ChatHarmonyFinalVerificationReleaseBoundaryTest.java` | SHA-256 `FEF96795316C03BD05F570B8ECF5D2089994FCAB1C047437C1CE7214F9A603DA` | Replace the postprocessor-owned semantic expectation with upstream release-boundary precedence assertions only. |
| `src/test/java/com/example/lms/service/EvidenceNeededDirectivePolicyTest.java` | Must not exist. | Entire new positive/synonym/negated/meta/quoted/cross-clause policy matrix. |

`ChatWorkflowS8PromptBoundaryContractTest` and other controller boundary tests
not named in the table remain verification-only unless a new failing result
proves this manifest incomplete; they are not pre-authorized mutation targets.

## Goals

1. Distinguish confirmed absence of promoted/citable evidence from failed or
   incomplete attribution.
2. Honor a positive, non-meta instruction to return exact `evidence_needed`
   only when final citable evidence is confirmed empty.
3. Fail closed without leaking an unsupported draft when evidence state is
   failed, unavailable, policy-disabled, or invalidated by late unattributed
   recovery.
4. Preserve verifier, safety fallback, explicit retrieval-off, S7/S8, memory,
   privacy, and appendix ownership contracts.
5. Remove semantic evidence inference from `ChatHarmonyTracePostprocessor`.

## Non-goals

- No changes to `PromptBuilder`, prompt assembly, provider clients, search
  algorithms, Supabase, database schema, or persistent DB/file trace schema.
  A bounded allowlisted in-memory trace tuple extension is permitted.
- No behavior change to these seven post-promotion early-return path IDs:
  `agent_debug_direct_answer`, `chat_draft_config_breaker`,
  `chat_draft_open_circuit`, `request_budget_exhausted`, `cancellation`,
  `llm_configuration_error`, and `llm_failure_after_retries`.
- No claim-level citation relevance engine or new orchestration framework.
- No raw query, answer, evidence body, exception body, credential, or exact
  private value in trace output.
- No staging or commit in the dirty canonical checkout without separate user
  authorization.

## Design

### 1. Typed attribution result with List compatibility

Add `promoteForPromptDetailed(...)` to `RagEvidenceAttributionService`. The
existing `promoteForPrompt(...)` remains source-compatible and delegates to the
detailed method's immutable evidence list. `PromotionResult`,
`PromotionStatus`, and `PromotionReason` are public nested immutable types so
the single production caller and focused tests can consume the typed contract
without adding a package-level framework.

The detailed result contains:

- `PromotionStatus`: `PROMOTED`, `CONFIRMED_EMPTY`, `FAILED`, or
  `UNAVAILABLE`. The service emits the first three; the workflow uses
  `UNAVAILABLE` only when the optional service is absent.
- `PromotionReason`: `PROMOTED`, `EVIDENCE_GATE_BLOCKED`,
  `NO_CITABLE_LOCATOR`, `CITATION_GATE_BLOCKED`, `GATE_EXCEPTION`,
  `SERVICE_UNAVAILABLE`, or `CALLER_FAILURE`.
- `List<RagEvidenceMetadata> evidence`, defensively copied and never null.

Completed evidence-gate block, citation-gate block, or no-locator evaluation is
`CONFIRMED_EMPTY`: the user-facing contract concerns promoted/citable evidence,
not the existence of raw retrieved text. An exception is `FAILED`, never empty
success. The existing exception trace reason is also normalized to
`gate_exception`; dynamic exception class names do not enter the typed result
or the new/public trace contract.

### 2. Pure directive policy

Move the existing positive/synonym/negated/meta/quoted/cross-clause parser into
a pure public `EvidenceNeededDirectivePolicy`. It returns one boolean and does
not retain or trace the query.

The parser accepts direct conditional requests such as "If evidence is
missing, return evidence_needed." It rejects negation, explanation, quotation,
and a token governed by a different conditional clause. The behavioral matrix
is migrated before the postprocessor branch is removed.

### 3. Final workflow evidence state

`ChatWorkflow` carries the decision in four local, typed values rather than in
`TraceStore`:

- `PromotionResult promotionResult`, initialized with the bounded
  `UNAVAILABLE` factory when the optional service is absent and changed to
  `FAILED/CALLER_FAILURE` on an outer caller exception.
- The final official-filtered `citableEvidence` immutable list.
- `boolean lateUnattributedEvidenceAdded`, initialized `false` before
  attribution.
- `boolean retrievalContractRequested`, derived from explicit Web/RAG request,
  `FORCE_LIGHT`, or a positive evidence-needed directive.

Change the private `tryDetourCheapRetry` return from `String` to an immutable
`DetourRetryResult(content, unattributedEvidenceAdded)`. The boolean becomes
true whenever that retry adds post-attribution documents, even when recovered
content is null. The sole caller copies the boolean before deciding whether to
use recovered content. No trace lookup participates in this authority decision.

After official filtering and every late recovery, a pure helper derives the
final bounded state in this exact order:

1. Explicit direct retrieval off, or a state in which neither requested nor
   effective retrieval exists, is `NOT_APPLICABLE`.
2. `FAILED`, `UNAVAILABLE`, requested-but-policy/config-disabled retrieval, or
   `lateUnattributedEvidenceAdded=true` is `METADATA_INCOMPLETE`.
3. A non-empty final official-filtered list is `EVIDENCE_PRESENT`.
4. The remaining completed attribution result is `CONFIRMED_EMPTY`.

Directive polarity is kept in the separate `evidenceReleaseRequired` boolean;
it never changes evidence lineage state.

| State | Authoritative meaning |
| --- | --- |
| `EVIDENCE_PRESENT` | Final official-filtered citable metadata is non-empty. |
| `CONFIRMED_EMPTY` | Attribution completed, but final official-filtered citable metadata is empty and no late unattributed recovery occurred. |
| `METADATA_INCOMPLETE` | Attribution failed, service was unavailable, the caller failed, requested retrieval was policy/config disabled, or late recovery added evidence without re-attribution. |
| `NOT_APPLICABLE` | The user explicitly selected direct retrieval-off mode, or neither requested nor effective retrieval exists. |

If any detour attempt introduces new documents after promotion, it invalidates
a prior empty result even when no recovered content is returned. Because those
documents are not re-attributed in the bounded patch, the final state is
`METADATA_INCOMPLETE`, not `EVIDENCE_PRESENT` and not `CONFIRMED_EMPTY`.

Explicit direct retrieval off uses the existing `directRetrievalOffMode`
authority. A request that did not explicitly opt out but requested retrieval
and becomes fully disabled by policy or configuration is incomplete evidence
state rather than successful direct mode. An ordinary direct chat with no
retrieval contract remains `NOT_APPLICABLE`.

### 4. Compose into the existing release decision

Do not add a second final release owner. Extend the existing
`FinalVerificationReleaseDecision` composition after the verifier and before
the memory candidate.

Precedence is deterministic:

1. The seven stable early-return path IDs listed in Non-goals keep their current
   bounded result and continue to bypass this normal-return composition.
2. Existing verifier `HOLD` or `REJECT` is preserved exactly.
3. An existing safety or fallback response is preserved and keeps its memory
   denial; the evidence policy does not overwrite it. The bounded predicate is
   `finalAnswerFallbackApplied`. A generic memory-policy denial alone is not
   treated as proof that visible content is already a safe fallback.
4. Explicit direct retrieval off preserves direct-mode behavior.
5. `EVIDENCE_PRESENT` preserves the verifier-approved candidate.
6. `METADATA_INCOMPLETE` emits
   `evidence_needed: attribution unavailable / verify retrieval evidence`,
   returns `HOLD`, denies memory, and never releases the original candidate,
   regardless of directive polarity.
7. Positive directive plus effective retrieval plus `CONFIRMED_EMPTY` emits
   exact `evidence_needed`, returns `HOLD`, denies memory, and marks the evidence
   policy as applied.
   `CONFIRMED_EMPTY` without a positive directive preserves the base decision.
8. Applicable S7/S8 output-contract policy remains downstream and may replace
   content with its stronger `HOLD` contract.

Extend `FinalVerificationReleaseDecision` with an
`evidencePolicyApplied` boolean. An applied decision skips evidence appendix
packaging so exact output cannot acquire a trailing `### Sources` block. The
existing final postprocessor still sanitizes the result. The caller sets both
fallback and memory-denial signals before `FinalAnswerPostProcessor`,
guaranteeing that no durable writer receives the replaced draft.

Both the confirmed-empty and metadata-incomplete replacements set
`evidencePolicyApplied=true` and skip appendix packaging. `releaseAllowed=false`
means the original candidate is denied release; the bounded replacement stored
in `content` remains the deliverable response.

| Winning condition after base verification | Content | Release status/reason | `releaseAllowed` | `evidencePolicyApplied` | Fallback / memory denied |
| --- | --- | --- | --- | --- | --- |
| Base verifier denies | Base verifier content | Base `HOLD` or `REJECT` / base reason | `false` | `false` | Unchanged |
| Prior fallback | Existing fallback content | Base status / base reason | Base value | `false` | `true` / existing value |
| Explicit direct off or no retrieval contract | Base content | Base status / base reason | Base value | `false` | Unchanged |
| `EVIDENCE_PRESENT` | Base content | Base status / base reason | Base value | `false` | Unchanged |
| `METADATA_INCOMPLETE` | `evidence_needed: attribution unavailable / verify retrieval evidence` | `HOLD` / `evidence_release_metadata_incomplete` | `false` | `true` | `true` / `true` |
| `CONFIRMED_EMPTY` + positive directive | `evidence_needed` | `HOLD` / `evidence_required_empty` | `false` | `true` | `true` / `true` |
| `CONFIRMED_EMPTY` + no positive directive | Base content | Base status / base reason | Base value | `false` | Unchanged |

Exact `evidence_needed` is guaranteed only when S7/S8 is non-applicable and
sanitization preserves the literal. When S7/S8 is applicable, its downstream
contract deliberately produces its stronger `HOLD` output.

### 5. Formatting-only harmony postprocessor

Remove the empty-evidence semantic branch, its evidence-state inference, and
the migrated directive parser from `ChatHarmonyTracePostprocessor`. Retain its
formatting, diagnostic cleanup, citation-marker cleanup, and existing
`releaseAllowed=false` preservation behavior.

Metadata alone must no longer cause this postprocessor to manufacture
`evidence_needed`.

## Bounded telemetry

Reuse the existing final release tuple and add only scalar allowlisted state:

- `finalAnswer.evidenceReleaseRequired`: boolean
- `finalAnswer.evidenceReleaseState`:
  `not_applicable|evidence_present|confirmed_empty|metadata_incomplete`
- `finalAnswer.evidenceReleaseApplied`: boolean
- existing `finalAnswer.releaseStatus`, `finalAnswer.releaseReason`, and
  `finalAnswer.releaseAllowed`
- existing output hash and memory-decision fields

Allowed new release reasons are `evidence_required_empty` and
`evidence_release_metadata_incomplete`. No release content, raw query, raw
answer, evidence item, or dynamic exception name is stored.

## Test-driven implementation

Production code is not changed until each relevant RED test fails for the
expected missing behavior.

### Attribution RED tests

- A completed zero-promotion evaluation returns `CONFIRMED_EMPTY` with its
  bounded reason.
- A throwing `EvidenceGate` returns `FAILED`, not `CONFIRMED_EMPTY`.
- A successful promotion returns `PROMOTED` and an immutable evidence list.
- The legacy List API preserves current behavior by delegating.

### Release RED matrix

- Confirmed empty + effective retrieval + positive directive + verifier allow
  + no prior fallback returns exact `evidence_needed`.
- Failed, unavailable, policy-disabled, and late-unattributed states return the
  bounded attribution-unavailable result and deny memory for both positive and
  ordinary queries.
- A detour that adds post-attribution documents but returns null content still
  sets the late-addition carrier and produces `METADATA_INCOMPLETE`.
- Explicit direct off, evidence present, verifier `HOLD/REJECT`, and prior
  safety fallback preserve their current content.
- Negated/meta/quoted/cross-clause intent preserves current content for
  `CONFIRMED_EMPTY` or another non-incomplete state; it never overrides the
  unconditional fail-close rule for `METADATA_INCOMPLETE`.
- Both applied evidence-policy replacements skip the appendix and cannot
  persist the draft.
- Applicable and non-applicable S7/S8 cases prove their documented precedence.
- The seven named early-return path IDs retain their current result.

### Postprocessor RED test

A metadata fixture that previously triggered the semantic branch must now
preserve the incoming answer. This test is RED before removing the branch and
GREEN only when semantic ownership has moved upstream.

The SearchMode.OFF S8 fixture is not used to claim enabled-retrieval coverage.
Mocks are limited to throwing or external boundaries; assertions target real
policy results rather than mock invocation counts.

## Verification ladder

1. Immediately before any test or production mutation, recompute every
   existing-target hash and every new-target nonexistence precondition in the
   frozen mutation manifest. Any mismatch, unexpected existence, or extra
   required target is `HOLD` and requires design/hunk re-review.
2. Modify only the declared test hunks and watch each new focused test fail for
   its intended missing contract.
3. Apply the smallest declared production change needed for GREEN.
4. Run attribution, final release, harmony postprocessor, final-postprocess,
   S8 boundary, and controller release-boundary tests.
5. Run root `compileJava` and `:app:classes` with host-specific build output,
   Gradle user home, and project cache.
6. Run count-only secret and raw-telemetry scans on the exact changed hunks.
7. Compare every postimage and owned hunk with the frozen preimage, then inspect
   the diff to prove every unrelated dirty hunk was preserved and no undeclared
   file changed.
8. Build a fresh JAR, record its SHA-256 and start identity, and issue exactly
   one new browser request on a newly bound localhost runtime.
9. Require terminal UI completion, exact answer semantics, Evidence count,
   bounded release telemetry, no raw values, and no memory write for the same
   request identity.

## Rollback

The patch is reversible by removing the detailed delegate and its public nested
types, restoring the single legacy attribution call, restoring the private
detour retry's `String` result, removing `EvidenceNeededDirectivePolicy`, the
final evidence-state helper, the added release-decision field, the three trace
keys, and the migrated tests, then restoring the postprocessor branch. No
schema migration, provider change, or external state mutation is involved.

## Acceptance criteria

- Completed zero and attribution failure are observably distinct.
- The eligible confirmed-empty scenario returns exact `evidence_needed` before
  memory writers and without an appendix when S7/S8 is non-applicable and
  sanitization preserves the literal; an applicable S7/S8 contract returns its
  stronger `HOLD`.
- Failed/incomplete evidence never releases the original draft or exact-empty
  token and cannot write memory.
- Verifier denial, prior safety fallback, explicit retrieval off, evidence
  present, S7/S8, and early-return behavior are preserved. Excluded intent
  preserves current behavior only for `CONFIRMED_EMPTY` or another
  non-incomplete state; `METADATA_INCOMPLETE` always fails closed.
- `ChatHarmonyTracePostprocessor` no longer owns evidence semantics.
- Focused and related tests, compilation, app classes, count-only safety scans,
  fresh-JAR identity, and one browser request all provide current evidence.
- Unrelated working-tree changes remain untouched. This specification does not
  authorize staging, committing, reverting, deleting `.git/index.lock`, or
  mutating PatchDrop state.

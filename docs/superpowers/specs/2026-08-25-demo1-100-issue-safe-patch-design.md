# demo-1 100-Issue Safe Patch Design

**Status:** Approved design A and written specification
**Date:** 2026-08-25
**Workspace:** `C:\AbandonWare\demo-1\demo-1\src`
**Maximum execution budget:** 9 hours
**Source authority:** active Desktop sourceSets only
**Commit, push, deploy, database mutation:** not authorized

## 1. Purpose

Improve every item in the 100-issue audit without flattening the work into one
large rewrite. The implementation will group issues by root cause, prove each
behavior with a focused RED test, apply the smallest compatible source change,
and broaden verification only after the focused surface is GREEN.

The audit is a risk map, not proof that every original hypothesis still holds.
Every issue must receive a current-state disposition with direct evidence.

## 2. Completion states

Every audit ID must end in exactly one state:

- `PATCHED`: current behavior failed a focused contract, the smallest patch was
  applied, and focused plus affected-boundary verification passed.
- `MERGED_WITH:<id>`: the same root-cause patch and test close this item. The
  ledger must name the owning issue and explain the shared causal mechanism.
- `NO_PATCH_NEEDED`: current source or runtime evidence contradicts the audit
  claim or proves that an existing implementation already satisfies it.
- `HOLD:<reason>`: changing the item safely requires a missing owner decision,
  external contract, runtime, credential, or non-overlapping preimage. A HOLD
  entry must contain one exact verification or authority action.

`SKIP`, silent omission, a green build alone, and a source-text search alone are
not completion states.

## 3. Non-negotiable repository constraints

- Preserve all unrelated dirty-tree files and hunks.
- Re-read and hash every declared target immediately before a source patch.
- Use `apply_patch` for edits; never replace a broad dirty file wholesale.
- Keep root runtime ownership in `main/java` and `main/resources`.
- Keep `:app` ownership in `app/src/main/java_clean` and
  `app/src/main/resources`.
- Treat `app/src/main/java` and other mirrors as inactive unless fresh Gradle
  evidence changes the sourceSet map.
- Keep Java 17, Spring Boot's current repository version, and all
  `dev.langchain4j` dependencies exactly at `1.0.1`.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)`.
- Preserve existing property names, secret flow, `openssl`, and `opnessl` keys.
- Add no production dependency.
- Store no raw prompt, response, credential, cookie, authorization header,
  idempotency key, user query, attachment content, or provider error body in
  traces or reports.
- Browser and Computer are proof lanes only when a changed UI or Windows
  runtime surface requires them.

## 4. Patch architecture

The implementation has eight waves. A wave is a queue, not a broad commit. Each
cycle normally changes one behavior and at most three files.

### W1. Durable storage and lifecycle

Owns `FileDegradedStorage`, n8n job acceptance, attachment physical retention,
archive staging, and bounded job/storage cardinality.

Durability rules:

1. Persist the destination before removing the source.
2. If destination persistence fails, retain the source and return/trace a
   failure; never claim success.
3. Stale recovery removes only records whose destination append succeeded.
4. A claim batch deduplicates identical envelope IDs locally while preserving
   redelivery after an ACK failure.
5. Conflicting payloads under one envelope ID are quarantined with hashes and
   reason codes, never raw payloads.

### W2. Ownership and public mutation

Owns attachment principal/session binding, feedback mutation, Kakao trigger
authorization, owner-key continuity, and request validation.

Authorization rules:

- A client-supplied `sessionId` is never authority by itself.
- Attachment upload, inspect, archive, attach, and delete must bind the current
  principal/owner identity to the target chat session.
- A pre-session attachment must be bound to the current owner identity and may
  later be attached only to a session accessible by that same identity.
- Missing, malformed, cross-owner, or non-existent numeric session IDs fail
  before storage, parsing, provider, or database side effects.
- Kakao trigger operations require the existing administrator authority. No
  request may borrow administrator provider credentials merely because it is
  authenticated.

### W3. Deadlines, cancellation, and executor admission

Owns Nova/Hybrid search, model calls, graph execution, SSE scheduling, ensemble
sampling, query analysis, and Ollama/local-process lifecycle.

Deadline rules:

- A request gets one monotonic whole-operation deadline.
- Every fallback and per-store wait receives only the remaining duration.
- Timeout code requests interruption where safe, but never claims that
  interruption killed a blocking provider.
- Interrupt-ignoring work is isolated behind bounded shared admission; no
  request creates an unbounded executor or permanent worker population.
- Caller interruption is restored or propagated; it is never consumed and
  converted into ordinary success.
- Cancellation checkpoints precede verifier, postprocessor, learning, memory,
  and persistence side effects.

### W4. RAG, fusion, CFVM, and vector consistency

Owns top-K validation, final trimming, score domains, DPP identity, CFVM
snapshots, temperature cache consistency, vector DLQ, federated search/write,
and plan parsing.

Consistency rules:

- Reject negative top-K at admission.
- Define zero top-K as zero results, never all candidates.
- Apply a final top-K trim after memory, repair, and rerank augmentation.
- Do not normalize calibrated `[0,1]` scores using an unrelated raw-score max.
- Keep candidate identity attached through DPP/RRF reorder operations.
- Reject non-finite snapshot values and restore each value into the same domain
  from which it was captured.
- Recompute cached CFVM weights immediately after temperature changes.
- Federated search and write obey one total deadline and report partial success
  explicitly.

### W5. Session, memory, trace, and health truthfulness

Owns EPHEMERAL memory reads, session deletion/cancellation, owner bootstrap,
trace pointers, fingerprints, health scoping, feedback semantics, and bounded
trace maps.

Truthfulness rules:

- `EPHEMERAL` performs no historical-memory read or write.
- Deleting a session first cancels its active run and then deletes durable
  history; a late worker cannot recreate deleted state.
- A durable record does not point exclusively to an evictable memory ring.
- Public and durable traces retain only allowlisted hashes, lengths, counts,
  timings, and reason codes.
- Model health is scoped by endpoint identity, model, and relevant runtime
  context; success on one route cannot mask another route.
- Capacity overflow evicts bounded entries; it never clears every request's
  state at once.

### W6. Provider edge contracts

Owns Brave/Naver/SerpApi timeouts, top-K, cooldown reporting, shared-future
cancellation, OpenAI fallback budget, Kakao pagination, and provider error
redaction.

Provider rules:

- Request-level top-K is clamped before the outbound call and after parsing.
- A configured timeout is never inflated beyond remaining request time.
- One waiter cannot cancel a single-flight future shared by other waiters.
- Trace cooldown equals the effective enforced cooldown.
- Provider status and body text are reduced to allowlisted status family,
  hashed identity, length, and reason code.

### W7. Public UI and scoring tools

Owns heartbeat disclosure/cost, status-rail layout, scorecard side effects,
missing-metric handling, and `ScoringRunner` false greens.

Tool and UI rules:

- `source_health_scorecard.py --output X` writes only `X`. Canonical artifact
  writes require a separate explicit flag.
- A required missing metric yields `metric-input-invalid`; it never defaults to
  a value that earns points.
- Correctness categories cannot earn points from source-string or class-name
  presence alone. They require structured task/test evidence or remain
  `evidence_needed`.
- Public heartbeat payloads contain only user-actionable health bands and
  redacted reason codes, use a bounded cache, and do not expose credential,
  provider-route, model, MCP, or environment topology.
- The desktop status rail wraps or responsively collapses without a nested
  horizontal scrollbar at the browser proof viewport.

### W8. Structural hotspot reduction

Owns audit IDs 89-100. This wave does not authorize wholesale class rewrites.
For each hotspot, one of the following must be proven:

1. A responsibility touched by W1-W7 was extracted behind an existing or
   narrowly introduced interface and its focused tests remain green.
2. Current evidence shows the hotspot claim is stale and the item is
   `NO_PATCH_NEEDED`.
3. A safe extraction cannot fit the budget or has an overlapping dirty hunk;
   record `HOLD` with the exact class/method and the next isolated extraction.

Merely adding a file-size baseline is not `PATCHED`. An accepted structural
patch must reduce responsibility, branch/catch concentration, or dependency
fan-in on the touched behavior.

## 5. Issue-to-wave acceptance ledger

The IDs below preserve the original 100-item audit numbering.

| ID | Priority | Wave | Required observable outcome |
|---:|:---:|:---:|---|
| 1 | P1 | W3 | Planned-search fallback cannot exceed the original whole-operation deadline. |
| 2 | P1 | W3 | Timed-out Nova work cannot accumulate unbounded shared-executor occupancy. |
| 3 | P1 | W3 | Hybrid fallback, including blocking providers, is bounded by one deadline. |
| 4 | P1 | W3 | Hybrid timeout records unfinished work truthfully and enforces bounded admission. |
| 5 | P1 | W1 | Failed inflight append leaves pending intact and returns no successful claim. |
| 6 | P1 | W1 | Failed NACK destination write leaves the inflight source recoverable. |
| 7 | P1 | W1 | One claim batch processes one envelope ID while retaining later redelivery semantics. |
| 8 | P1 | W4 | Default vector DLQ health configuration permits a valid redrive or states an explicit disabled reason. |
| 9 | P1 | W3 | Timed-out graph work cannot starve the JVM common pool. |
| 10 | P1 | W3 | Public Harmony SSE has shared scheduling, a connection cap, and reliable cleanup. |
| 11 | P1 | W2 | Attachment operations reject a session not owned by the current principal/owner. |
| 12 | P1 | W2 | Upload and delete cannot traverse a symlink, junction, or reparse point outside the real root. |
| 13 | P1 | W5 | EPHEMERAL mode cannot return remembered values from any fallback path. |
| 14 | P1 | W2 | Replayed feedback cannot repeatedly mutate global strategy or hyperparameters. |
| 15 | P1 | W2 | Null or invalid rating returns validation failure and performs no negative tuning. |
| 16 | P1 | W2 | Memory OFF feedback performs no persistence or vector write. |
| 17 | P1 | W5 | First cookie-less and subsequent cookie requests resolve one stable owner identity. |
| 18 | P1 | W3 | Legacy public RAG query obeys request cancellation and a wall-clock deadline. |
| 19 | P1 | W2 | Non-admin callers cannot trigger Kakao provider credentials or arbitrary recipients. |
| 20 | P1 | W4 | A stuck vector store cannot permanently consume every federated-search slot. |
| 21 | P1 | W3 | Ensemble requests use bounded shared admission; surviving provider calls cannot grow per request. |
| 22 | P2 | W4 | Negative web top-K is rejected before planning or provider execution. |
| 23 | P2 | W3 | Routing-plan exceptions enter the defined fail-soft result and trace path. |
| 24 | P2 | W4 | All-negative fusion inputs yield ordered, non-inverted finite guard bands. |
| 25 | P2 | W4 | Mixed score domains retain their calibrated ordering and scale. |
| 26 | P2 | W4 | A non-cancel downgrade does not mutate CFVM cancellation/failure state. |
| 27 | P2 | W4 | CFVM snapshot restore rejects non-finite values and preserves score domains. |
| 28 | P2 | W4 | Temperature change immediately updates the effective cached weight vector. |
| 29 | P2 | W3 | Cache-only remerge does not sleep/poll a request thread beyond remaining time. |
| 30 | P2 | W5 | Session deletion cancels its active run before history removal and blocks late persistence. |
| 31 | P2 | W3 | Four interrupt-ignoring model calls cannot permanently disable all future model calls. |
| 32 | P2 | W1 | Stale-inflight recovery removes only records successfully persisted to pending. |
| 33 | P2 | W3 | Cancelled HALF_OPEN Ollama probe releases its single-probe gate. |
| 34 | P2 | W3 | A healthy foreign Ollama PID is never marked manager-owned. |
| 35 | P2 | W3 | Shutdown does not claim cleanup of a detached process it cannot prove it owns. |
| 36 | P2 | W3 | Query-analysis timeout cannot accumulate unbounded model tasks. |
| 37 | P2 | W3 | Query-analysis interruption is restored or propagated, not cleared as success. |
| 38 | P2 | W3 | Thumbnail work uses bounded admission and observable completion/failure. |
| 39 | P2 | W3 | Brain-state work propagates approved context and has bounded durable completion. |
| 40 | P2 | W6 | Brave timeout never exceeds the caller's remaining budget. |
| 41 | P2 | W6 | Naver outbound and returned results obey the request top-K. |
| 42 | P2 | W6 | Timing out one Naver waiter cannot cancel another waiter's shared computation. |
| 43 | P2 | W6 | Brave remote status text cannot reach raw application logs or public traces. |
| 44 | P2 | W1 | Attachment delete and TTL eviction delete the corresponding safe-root file. |
| 45 | P2 | W2 | Upload rejects request file-count or aggregate-byte excess before writing any file. |
| 46 | P2 | W1 | Invalid archive input publishes no early chunk; retry cannot duplicate a prefix. |
| 47 | P2 | W1 | Archive parsing has a cumulative decompressed-byte upper bound. |
| 48 | P2 | W5 | Cancellation before postprocessing prevents verifier/learning/memory/persistence side effects. |
| 49 | P2 | W5 | Feedback affects the rated assistant memory or explicitly reports that no rated record exists. |
| 50 | P2 | W2 | Feedback body and fields have pre-binding/request-level bounds and bean validation. |
| 51 | P2 | W5 | Untrusted forwarding headers cannot become an owner identity without a trusted-proxy boundary. |
| 52 | P2 | W2 | Session create/list has owner quota and bounded pagination. |
| 53 | P2 | W4 | Seed-only top-K zero returns zero candidates. |
| 54 | P2 | W4 | Retrieval output receives a final top-K trim after repair/memory augmentation. |
| 55 | P2 | W5 | Debug-event fingerprint is hashed/allowlisted before log, NDJSON, JSON, and SSE storage. |
| 56 | P2 | W5 | Durable chat trace references remain resolvable after restart or ring eviction. |
| 57 | P2 | W2 | Kakao provider failure produces a non-success HTTP/result contract. |
| 58 | P2 | W6 | OpenAI fallback uses no more than the request's remaining time. |
| 59 | P2 | W6 | SerpApi outbound and returned result counts are clamped to a configured maximum. |
| 60 | P2 | W4 | Federated search latency is bounded by one total deadline, not N times timeout. |
| 61 | P2 | W4 | Federated write has a deadline and reports per-store partial outcomes. |
| 62 | P2 | W1 | Same n8n key and body returns the original job ID; key/body conflict returns 409. |
| 63 | P2 | W1 | In-memory job status, including PENDING, has bounded TTL/capacity without fabricated execution. |
| 64 | P2 | W2 | `/api/chat/cancel` and `/ack` receive the same bounded body/deadline admission as chat writes. |
| 65 | P2 | W2 | Chat session list/detail use explicit page/window limits and bounded responses. |
| 66 | P2 | W2 | Anonymous chat has per-owner and global concurrent-work admission with a 429 contract. |
| 67 | P2 | W7 | Public heartbeat omits provider, credential, model, environment, MCP, and internal-topology details. |
| 68 | P2 | W7 | Heartbeat polling uses a bounded cache and does not repeat DB/filesystem walks per tab. |
| 69 | P2 | W7 | Scorecard temp output produces no undeclared canonical repository write. |
| 70 | P2 | W7 | Missing quantitative inputs fail closed and earn no maintainability points. |
| 71 | P2 | W7 | SourceSet/version purity score consumes actual structured Gradle/task evidence. |
| 72 | P2 | W7 | CFVM/ArtPlate/HYPERNOVA score consumes executable behavior evidence, not keywords. |
| 73 | P2 | W7 | PII score consumes redaction behavior evidence, not marker presence. |
| 74 | P2 | W7 | Citation ownership score proves the response call path, not an unrelated string. |
| 75 | P2 | W7 | Prompt-boundary score proves build and trace on the same active call path. |
| 76 | P3 | W4 | Candidate identity survives RRF bridge and DPP reorder or the inactive path is proved. |
| 77 | P3 | W4 | Active plan fields `topk`, `budget_ms`, and guards are parsed or explicitly rejected. |
| 78 | P3 | W5 | Recent local-model success is scoped by endpoint, model, and context. |
| 79 | P3 | W6 | Kakao page input is bounded and overflow-safe before offset calculation. |
| 80 | P3 | W6 | Brave reported cooldown equals the enforced clamped cooldown. |
| 81 | P3 | W5 | Provider health success is recorded only after required semantic gates. |
| 82 | P3 | W2 | Feedback authorization is consistent for an accessible administrator-owned session. |
| 83 | P3 | W2 | Session title validation fits the 120-character persistence contract. |
| 84 | P3 | W5 | Invalid debug max-size configuration fails closed with an explicit reason, not silent zero capacity. |
| 85 | P3 | W5 | Trace-budget overflow evicts bounded oldest entries instead of global clear. |
| 86 | P3 | W7 | Status rail has no nested horizontal scrollbar at the browser acceptance viewport. |
| 87 | P3 | W7 | Silent-catch score penalizes every real silent variant; no threshold grants a perfect score. |
| 88 | P3 | W7 | Large-file concentration changes the score and cannot coexist with an unconditional 100. |
| 89 | P3 | W8 | `ChatWorkflow` touched responsibility is extracted or held with an exact isolated seam. |
| 90 | P3 | W8 | `WebFailSoftSearchAspect` touched responsibility is extracted or held with an exact seam. |
| 91 | P3 | W8 | `NaverSearchService` touched responsibility is extracted or held with an exact seam. |
| 92 | P3 | W8 | `ChatApiController` touched responsibility is extracted or held with an exact seam. |
| 93 | P3 | W8 | `HybridWebSearchProvider` touched responsibility is extracted or held with an exact seam. |
| 94 | P3 | W8 | `UnifiedRagOrchestrator` touched responsibility is extracted or held with an exact seam. |
| 95 | P3 | W8 | `AgentPipelineHealthController` heartbeat responsibility is extracted and cached or held. |
| 96 | P3 | W8 | `HybridRetriever` touched responsibility is extracted or held with an exact seam. |
| 97 | P3 | W8 | `EvidenceAwareGuard` touched responsibility is extracted or held with an exact seam. |
| 98 | P3 | W8 | `ModelRuntimeHealthTracker` route-scoped state is extracted or held with an exact seam. |
| 99 | P3 | W8 | `SelfAskWebSearchRetriever` touched responsibility is extracted or held with an exact seam. |
| 100 | P3 | W8 | `DynamicContextCompressor` touched responsibility is extracted or held with an exact seam. |

## 6. Concrete public-contract defaults

Unless a closer existing property already supplies a stricter value, use these
bounded defaults without adding a dependency:

- Attachment upload: at most 16 files and at most 25 MiB aggregate request
  content, in addition to the existing per-file limit.
- Conversation archive: at most 500 entries, 2 MiB per entry, and 64 MiB total
  decompressed content. Validate/stage the full archive before publishing any
  chunk.
- Session list: default 50 and maximum 100 sessions per page.
- Session detail: default and maximum 200 most recent messages, returned in
  chronological order within the selected window.
- Feedback comment/correction: maximum 4,000 characters; identifiers maximum
  128 characters; invalid rating is HTTP 400.
- Heartbeat cache: 30 seconds, matching the current UI polling interval.
- Harmony SSE: maximum 32 concurrent public connections per JVM unless an
  existing stricter property is present.
- n8n idempotency: process-local bounded entries, maximum 4,096 and 24-hour TTL;
  store only a hash of the key plus body hash and job ID. Missing keys preserve
  current at-least-once acceptance. A repeated key with a different body is
  HTTP 409. Do not claim cross-restart idempotency.
- In-memory jobs: all statuses, including PENDING, participate in bounded TTL
  and capacity. Expiry returns `NOT_FOUND` or an existing neutral equivalent;
  no fake `SUCCEEDED` state is introduced.

Properties may expose these values only when the repository already follows a
property-driven pattern at that seam. Existing property names are reused where
available.

## 7. Source-edit preflight per patch cycle

Before the first application-source write in each independent cycle:

1. Confirm canonical root, branch, HEAD, sourceSet, index lock, PatchDrop queue,
   source lease, target status, and verification command.
2. Freeze at most 20 redacted evidence rows and one snapshot hash.
3. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` as
   single-agent logical roles over that same snapshot.
4. Require the same scenario IDs in positive and negative packets.
5. Require stable `APPLY` under positive-negative and negative-positive order,
   a score of at least 50, proven target ownership, and focused verification.
6. Acquire the existing source-owner lease and re-check target preimages.
7. On changed preimage, overlapping unreviewable hunk, lock, or unstable
   adjudication, do not write; record a lane-local HOLD.

## 8. TDD and verification ladder

Every behavior change follows this order:

1. Name the production mutation that the test must catch.
2. Add one focused behavior test using real code and hand-derived expectations.
3. Run it and observe the expected RED failure.
4. Apply the minimal production change.
5. Run the focused test and observe GREEN.
6. Run related package/subsystem tests.
7. Run `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and
   `compileJava -x test` after each risk-bearing cluster.
8. Run `:app:classes -x test` when shared types, configuration, or resources
   cross the root/app boundary.
9. Before completion, run the full test refresh, `bootJar -x test`, changed-file
   secret scan, `git diff --check`, PatchDrop janitor guards, and final diff
   inspection.
10. Run isolated runtime/Browser proof for changed chat UI, heartbeat, SSE, or
    HTTP public contracts. A rendered page does not prove provider/model output.

The build uses Desktop-specific `AWX_SPLIT_BUILD_OUTPUTS`, a stable patch-run
host ID, isolated `GRADLE_USER_HOME`, and isolated `--project-cache-dir`.

## 9. Error handling and rollback

- Storage transitions remain recoverable after every individual failed write.
- Public validation failures produce bounded 4xx responses with stable reason
  codes and no raw body echo.
- Timeout results distinguish timeout, cancellation, provider-disabled,
  admission-rejected, and unfinished-worker states.
- Optional providers continue to fail soft without fake results.
- If a focused patch fails broader verification, repair only the introduced
  regression. If the safe repair is unclear, restore only the agent-authored
  hunk with `apply_patch`; never reset or overwrite the user's dirty file.
- No material deletion, dependency addition, commit, push, deployment,
  credential mutation, database mutation, or external message is part of this
  design.

## 10. Final evidence ledger

The final report must include all 100 IDs with:

- completion state;
- current source path and line/symbol;
- root cause or contradiction;
- RED command and observed failure for `PATCHED` items;
- GREEN and affected-boundary commands;
- changed files and postimage hashes;
- remaining external or authority evidence;
- Browser/Computer status only where freshly observed.

The goal is complete only when every ID has one valid completion state, every
`PATCHED` item has RED/GREEN proof, every `MERGED_WITH` item names its owning
patch, every HOLD has one unblock action, and the broad completion gates pass.

## 11. Approval and execution handoff

The user approved design A and this written specification in chat on
2026-08-25. No Git commit was created because commit authority was not granted.
The next artifact is the implementation plan; execution begins only after the
Superpowers execution-mode handoff, starting with W1 `FileDegradedStorage`
filesystem-failure RED tests.

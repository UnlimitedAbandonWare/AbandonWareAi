# SelectionEntropy Runtime Replay Design

## Status

- Date: 2026-08-29
- Request class: architectural application-source feature
- Design path: Superpowers architectural brainstorming
- Chat approval: design sections 1 through 5 approved
- Written-spec state: approved by the user on 2026-08-29
- Execution budget: up to nine hours, with early stop on complete proof or a decisive lane-local blocker
- Source mutation state: not started
- Commit state: not authorized; this document remains uncommitted unless the user grants operation-level commit authority

## Executive summary

The active runtime contains several agent-selection behaviors whose results may vary because of intentional exploration, map or input ordering, exact-score ties, and concurrent completion order. The feature will preserve useful live exploration while adding a request-scoped replay mode that makes application-owned choices reproducible.

The common boundary is SelectionEntropy:

- standard mode delegates to current live randomness;
- replay mode derives each draw from a private seed plus a stable selection coordinate;
- exact score ties use stable model, strategy, node, tool, or document keys rather than randomness;
- immutable selection semantics travel with GuardContext and existing ContextPropagation;
- an owner/admin-authorized ephemeral header enables replay for synchronous and streaming chat;
- raw seeds, tokens, prompts, responses, candidate bodies, and mutable RNG state are never persisted or exposed;
- TraceStore, snapshots, agent-visible evidence, SSE, and chat UI receive only allowlisted counts, hashes, booleans, modes, and reason codes;
- Browser proves DOM and stream rendering, while Computer proves Windows-visible layout; neither substitutes for server-side replay, provider, or wire evidence.

Replay covers application-owned agent selection. It does not promise byte-identical model prose, deterministic provider latency, deterministic network scheduling, or a deterministic first terminal failure among parallel workers.

## Live source evidence

The design is grounded in the active root main/java and main/resources source sets.

### Stochastic ensemble sampling

- main/java/com/example/lms/ensemble/StochasticParamSampler.java uses ThreadLocalRandom for live draws and a shuffle.
- main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java applies those draws to opportunistic ensemble profiles.
- main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java reaches the sampler on creative-triad paths.
- main/java/com/example/lms/service/ChatWorkflow.java reaches ensemble refinement.
- Existing tests can inject a DoubleSupplier, but there is no request-scoped runtime seed or replay contract.

### LLM router exploration and order dependence

- main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java performs cold-start exploration, UCB selection, and weighted random fallback.
- main/java/ai/abandonware/nova/config/LlmRouterProperties.java stores models in a HashMap.
- Candidate collection and exact-score ties do not currently use a stable key comparator.
- application-llm.yaml enables the LLM router by default, subject to profile overrides.

### Retrieval strategy exploration

- main/java/com/example/lms/strategy/StrategySelectorService.java performs softmax roulette and epsilon-greedy exploration with ThreadLocalRandom.
- main/java/com/example/lms/strategy/RetrievalOrderService.java calls the selector only in dynamic mode.
- The default retrieval order is fixed, so this stochastic path is gated unless an override enables it.

### Parallel completion order

- DiverseSamplingOrchestrator consumes futures through ExecutorCompletionService.
- The first observed malformed, duplicate, or terminal failure can cancel outstanding workers.
- Successful results are reconstructed in node-spec order.
- Existing tests intentionally approve first-observed failure semantics.

### RRF and DPP tie order

- main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java sorts RRF results by score without a total stable document-key tie-break.
- main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java keeps the first input candidate for equal determinant scores.
- main/java/com/nova/protocol/fusion/NovaNextFusionService.java already has an ID tie-break and must not receive a duplicate implementation.

## Relationship to Stochastic Tool Lab

The following existing untracked user artifacts are related but do not implement this runtime feature:

- docs/superpowers/specs/2026-07-31-stochastic-tool-lab-design.md
- docs/superpowers/specs/2026-07-31-stochastic-tool-lab-v2-amendment.md

Stochastic Tool Lab owns bounded candidate generation, sealed scoring, tournament execution, artifact-quality promotion, and review-only patches for tools, scripts, and skills. It explicitly prohibits modifications under main, app, active runtime source sets, providers, and production behavior.

SelectionEntropy owns application-runtime choice reproducibility in LLM routing, retrieval strategy selection, ensemble parameter selection, and ranking tie-breaks. It modifies active application source only after the repository source-edit gate returns a stable APPLY.

The systems are complementary:

- Tool Lab may later evaluate a review-only patch that changes a SelectionEntropy policy or test fixture.
- SelectionEntropy does not replace Tool Lab scoring, promotion, sentinel packs, three-query adjudication, or artifact publication.
- Tool Lab seeds describe experiment repetitions; SelectionEntropy seeds describe application-owned runtime choices.
- Neither system may claim that its result proves provider token sampling or live provider lineage.

## Goals

1. Preserve existing live exploration when replay is not requested.
2. Reproduce application-owned agent selections for the same algorithm version, seed, selection coordinate, and canonical candidate set.
3. Remove HashMap, input-order, and exact-score tie ambiguity with stable total ordering.
4. Keep selection results independent of unrelated draw call order and asynchronous worker scheduling.
5. Reuse GuardContext and ContextPropagation rather than adding a servlet-only request scope or a new entropy context ThreadLocal.
6. Fail closed when an explicit replay contract cannot be honored.
7. Continue fail-soft only through deterministic fallback rules.
8. Provide bounded, redacted, request-scoped trace and UI evidence.
9. Cover synchronous chat, streaming chat, non-web boot compatibility, and agent fan-out.
10. Preserve unrelated dirty-tree changes and make every patch reversible.

## Non-goals

- Byte-identical natural-language answers.
- Provider-side seed forwarding without installed-version and wire evidence.
- Deterministic network latency, executor scheduling, timeouts, or first terminal failure.
- A replay manifest that stores full candidate lists or full decisions.
- New database tables, persistence schemas, cookies, local storage, or session storage.
- A replay seed input control in chat UI.
- A global production replay seed in application configuration or environment.
- New production dependencies.
- A new authentication or authorization system.
- Changes to LangChain4j version, provider routing contracts, existing secret flow, or immutable configuration keys.
- Replacing Stochastic Tool Lab or adding a second stochastic tournament framework.

## Core invariants

1. Header absence preserves current standard behavior.
2. Replay authorization success never silently degrades to live randomness.
3. Raw seed material has no getter, string representation, DTO field, trace field, persistence field, UI field, or log field.
4. Selection results are functions of stable coordinates rather than a global mutable draw counter.
5. Candidate canonicalization precedes selection.
6. Stable exact-score tie-breaks never consume entropy.
7. Selection replay status does not imply provider generation success.
8. Completion-order determinism is reported separately from selection determinism.
9. Context attachment occurs before asynchronous fan-out and cannot be replaced with a different carrier afterward.
10. Every public entropy field is explicitly allowlisted and type-checked.

## Architecture

### Component flow

~~~text
HTTP chat, stream, or non-web orchestration
                     |
                     v
          SelectionEntropyFactory
           /                   \
          /                     \
 LiveSelectionEntropy     ReplaySelectionEntropy
 current exploration      keyed deterministic derivation
          \                     /
           \                   /
                     v
        GuardContext attach-once carrier
                     |
                     v
          existing ContextPropagation
                     |
       +-------------+-------------+
       |             |             |
       v             v             v
   LLM router    strategy       ensemble sampler
       |             |             |
       +-------------+-------------+
                     |
                     v
       stable tie-breaks and bounded ledger
                     |
                     v
 TraceStore -> snapshot -> typed SSE -> read-only UI
~~~

### Planned neutral package

The common implementation belongs in this exact neutral active package:

~~~text
main/java/com/example/lms/infra/selection/
~~~

Planned types:

- SelectionEntropy
- SelectionCoordinate
- SelectionEntropyMode
- LiveSelectionEntropy
- ReplaySelectionEntropy
- SelectionEntropyFactory
- SelectionReplaySpec
- SelectionDecisionLedger
- SelectionEntropyCoherence
- SelectionEntropyReason

The package must not depend on ChatApiController, an individual agent, a provider model, servlet APIs, TraceStore storage shape, or chat UI.

### Selection coordinate

A draw coordinate contains:

~~~text
decisionKey
actorKey
attemptOrdinal
drawOrdinal
~~~

Examples:

~~~text
decisionKey    = ensemble.profile.temperature
actorKey       = node:support
attemptOrdinal = 0
drawOrdinal    = 0
~~~

~~~text
decisionKey    = llm-router.weighted-exploration
actorKey       = route:auto
attemptOrdinal = 1
drawOrdinal    = 0
~~~

All strings are bounded, nonblank, stable identifiers supplied by application code. Ordinals are non-negative.

Coordinate encoding is fixed:

- decisionKey uses lowercase ASCII and matches [a-z][a-z0-9._-]{0,95};
- actorKey is a code-owned canonical identifier, is lowercased with Locale.ROOT, and matches [a-z0-9][a-z0-9._:/-]{0,127};
- attemptOrdinal and drawOrdinal are signed Java long values restricted to zero through Long.MAX_VALUE;
- every string is encoded as a four-byte unsigned big-endian byte length followed by UTF-8 bytes;
- ordinals are encoded as signed eight-byte big-endian values after validation has proved they are non-negative;
- delimiter concatenation is prohibited because it can create ambiguous coordinates;
- invalid coordinates fail with selection_entropy_coordinate_invalid.

### SelectionEntropy API

The public contract is:

~~~java
interface SelectionEntropy {
    SelectionEntropyMode mode();
    String algorithmVersion();
    double unitInterval(SelectionCoordinate coordinate);
    int boundedIndex(SelectionCoordinate coordinate, int bound);
}
~~~

SelectionEntropy does not expose a java.util.Random, raw bytes, HMAC state, a global counter, or a mutable candidate list.

### Standard mode

LiveSelectionEntropy:

- delegates each live draw to ThreadLocalRandom;
- preserves current exploration distributions and feature gates;
- uses the same coordinate API for consistent consumer code;
- does not claim replay or expose a seed fingerprint;
- is the default for existing contexts and non-web execution.

### Replay mode

ReplaySelectionEntropy:

- defensively copies decoded seed bytes;
- accepts 16 through 64 bytes;
- derives values with JDK 17 HmacSHA256;
- domain-separates algorithm version, decision key, actor key, attempt ordinal, and draw ordinal;
- creates independent local Mac state per derivation rather than sharing a non-thread-safe Mac;
- maps derived bits to a unit interval or bounded index with the fixed rules below;
- exposes only algorithm version and a short fingerprint;
- does not store a mutable RNG state.

The algorithm label is selection-entropy-v1. A golden-vector test freezes exact behavior.

Derivation and numeric mapping are exact:

1. Use the private seed bytes as the HmacSHA256 key.
2. The HMAC message is the length-prefixed algorithm label, the SelectionCoordinate encoding, and a four-byte unsigned big-endian block ordinal.
3. The initial block ordinal is zero.
4. unitInterval reads the first 53 digest bits in big-endian order as an unsigned integer and divides by 2^53, yielding a value in the half-open interval [0, 1).
5. boundedIndex accepts bounds from 1 through 1,000,000.
6. boundedIndex interprets the full digest in big-endian order as a positive 256-bit integer.
7. Compute limit as 2^256 minus the remainder of 2^256 divided by bound.
8. Values below limit map by remainder; values at or above limit increment the block ordinal and derive again.
9. Four rejected blocks yield selection_entropy_derivation_invalid rather than a biased or live-random fallback.

The replay fingerprint is the first six bytes, rendered as twelve lowercase hexadecimal characters, of:

~~~text
SHA-256(
  ASCII awx-selection-fingerprint-v1
  + one zero byte
  + private seed bytes
)
~~~

The minimum 128-bit seed requirement keeps the short reference suitable for correlation without exposing the raw seed. The fingerprint is not an authorization credential.

### Factory

SelectionEntropyFactory provides servlet-independent construction:

~~~text
standard()
replay(SelectionReplaySpec)
~~~

It validates the supported algorithm and verifies HmacSHA256 availability before an accepted replay enters application execution.

### Decision ledger

SelectionEntropy remains a pure selection boundary. A separate per-request SelectionDecisionLedger records bounded observation metadata:

- coordinate hash;
- candidate-set hash;
- selected index;
- deterministic fallback reason;
- lane;
- tie-break occurrence;
- total and lane counts;
- coherence state.

Ledger hashes use fixed canonical inputs:

- coordinateHash is the lowercase 64-character SHA-256 of the exact SelectionCoordinate encoding defined above;
- each candidate stable key is first hashed as SHA-256 of ASCII awx-selection-candidate-key-v1, one zero byte, and the canonical UTF-8 stable key;
- candidateSetHash is SHA-256 of a four-byte unsigned big-endian candidate count followed by the ordered 32-byte candidate-key hashes;
- candidate order is the total canonical order for that consumer, not arrival or map iteration order;
- neither coordinateHash nor candidateSetHash is used as an authorization credential.

The ledger:

- is thread-safe;
- records at most 10,000 coordinates;
- stores no raw seed, candidate body, model key, document text, prompt, or response;
- marks coherence partial with selection_entropy_decision_cap_reached on capacity exhaustion;
- never changes selection behavior because observability is degraded.

At terminal projection, ledger rows are sorted by coordinate hash and encoded as canonical UTF-8 lines containing only:

~~~text
coordinateHash|candidateSetHash|selectedIndex|fallbackReason
~~~

Rows use a total sort by coordinate hash, candidate-set hash, selected index, and fallback reason. The decision digest is the lowercase 64-character SHA-256 of those lines, including a final newline for every row. Empty ledgers hash the empty byte sequence. Hash inputs contain no raw decision key, actor key, candidate body, model ID, document ID, prompt, or response.

## Context propagation

### GuardContext

GuardContext receives a typed SelectionEntropy field and a typed SelectionDecisionLedger reference.

Rules:

- existing contexts with no explicit carrier resolve to standard mode;
- replay is attached once before application work;
- attaching the same reference is idempotent;
- replacing it with a different carrier fails;
- copy preserves the same immutable selection semantics and request ledger;
- planOverrides is not used for seed material or entropy state.

### Async propagation

ContextPropagation already transports GuardContext, MDC, TraceStore, and budget state. No entropy-specific context ThreadLocal is added.

At an asynchronous selection boundary, the parent orchestration thread resolves SelectionEntropy once and captures the immutable reference into each worker closure. ContextPropagation remains responsible for the rest of the request context. This avoids a live-random fallback if a worker resolves context too late.

No closure captures:

- HttpServletRequest;
- a replay header string;
- owner or admin token;
- decoded temporary input;
- provider response or prompt content.

### Non-web compatibility

Servlet filters and Spring request scope are not initialization requirements.

- non-web boot uses standard entropy;
- UAW scheduled cycles use standard entropy;
- soak and batch runners use standard entropy unless a test or explicitly protected harness passes a ReplaySpec programmatically;
- v1 adds no global replay environment variable or application property;
- internal autolearn run-once receives no new replay input in v1.

## Replay ingress and authorization

### Header contract

Replay is accepted only from a single ephemeral header:

~~~http
X-AWX-Selection-Replay: v1:<base64url-without-padding>
~~~

Constraints:

- exactly one header;
- maximum 96 characters;
- version exactly v1;
- URL-safe Base64 without padding;
- decoded length 16 through 64 bytes;
- no whitespace;
- no query, cookie, DTO, persistence, local storage, or session storage representation.

### Existing authorization

The controller reuses AdminTokenGuardInterceptor.isPresentedHeaderTokenAuthorized and existing:

- X-Admin-Token
- X-Owner-Token

Replay absence does not make normal chat administrator-only. Replay presence requires explicit owner/admin authorization.

The probe-only X-Probe-Token is not accepted for chat replay.

### Authorization and parsing order

1. Detect replay-header presence without parsing its content.
2. Authorize with the existing owner/admin guard.
3. Return a fixed 403 response on authorization failure.
4. Parse only after authorization.
5. Create ReplaySelectionEntropy.
6. Capture only the immutable carrier for sync or stream worker execution.
7. Release request-scoped references at terminal cleanup.

Fixed authorization failure:

~~~json
{"code":"selection_entropy_replay_forbidden"}
~~~

The response reveals no seed syntax validity, expected token configuration, token source, or fingerprint.

### Sync flow

~~~text
POST /api/chat
 -> detect replay request
 -> authorize if present
 -> parse ReplaySpec
 -> create SelectionEntropy
 -> create GuardContext
 -> attach once
 -> invoke ChatWorkflow
 -> project redacted metadata
 -> finally restore or clear context
~~~

### Stream flow

The servlet thread authorizes and constructs SelectionEntropy. The background Mono or Runnable receives only that immutable carrier, attaches it to its newly created GuardContext, and then starts workflow and fan-out.

## Candidate canonicalization and stable ties

Replay requires the same algorithm version, coordinate, and canonical candidate set.

### LLM routes

Total order:

~~~text
normalized model key
 -> provider key
 -> route key
~~~

Cold-start and exact-UCB ties use this order. Weighted exploration uses SelectionEntropy only after canonicalization.

### Retrieval strategies and tools

Total order:

~~~text
explicit enum or strategy name
 -> canonical tool ID
~~~

Fixed retrieval mode performs no entropy draw. Dynamic mode uses separate coordinates for epsilon branching and softmax roulette.

### Ensemble nodes and profiles

Total order:

~~~text
node ID
 -> role ID
 -> profile ID
~~~

Temperature, top-p, profile, and shuffle draws have distinct decision keys.

### Retrieval documents

Stable key priority:

~~~text
explicit document ID
 -> canonical URL or source ID
 -> normalized internal content hash
 -> exclude a completely empty candidate
~~~

Public trace never includes the ID, URL, content, or full key.

### Candidate drift

The ledger binds the first candidate-set hash to each coordinate hash.

The same coordinate with a different candidate-set hash:

- records selection_entropy_candidate_drift;
- marks coherence partial;
- remains visible to strict tests and Browser comparison;
- never triggers a live-random fallback;
- does not claim a matched replay.

## Error and concurrency semantics

### Fail closed

The following errors fail the request or affected replay lane:

| Condition | HTTP or lane result | Reason code |
|---|---|---|
| Replay authorization fails | 403 | selection_entropy_replay_forbidden |
| Header syntax, size, or encoding is invalid | 400 | selection_entropy_replay_invalid |
| Algorithm version is unsupported | 400 | selection_entropy_algorithm_unsupported |
| Factory or HMAC initialization fails | 500 | selection_entropy_replay_init_failed |
| Accepted replay carrier is absent at selection | lane/request failure | selection_entropy_context_missing |
| Coordinate is invalid | lane/request failure | selection_entropy_coordinate_invalid |
| Replay tie lacks a recoverable stable key | lane failure | selection_entropy_stable_key_missing |
| Derived value violates the API range | lane/request failure | selection_entropy_derivation_invalid |

Accepted replay never silently falls back to LiveSelectionEntropy.

### Deterministic fail soft

Domain problems may continue only through deterministic rules:

- no candidates: existing empty-candidate contract, no draw;
- one candidate: select it without a draw;
- some invalid weights: exclude invalid candidates;
- all valid weights sum to zero: choose the first stable candidate;
- missing document ID: canonical URL, source, or internal content hash;
- completely empty candidate: exclude it;
- all candidates excluded: existing no-result fail-soft contract.

Random fallback is prohibited in replay mode.

### Parallel completion

Selection replay does not alter first-observed failure semantics in DiverseSamplingOrchestrator.

It guarantees:

- parameter and profile selections;
- router and strategy choices;
- stable exact ties;
- coordinate-derived values.

It does not guarantee:

- provider latency;
- worker scheduling;
- timeout timing;
- first observed terminal node;
- natural-language output;
- provider token sampling.

Metadata reports completionOrderDeterministic=false when appropriate.

### Retry and cancellation

- retries retain the same carrier;
- each retry uses a distinct attempt ordinal;
- fallback providers use stable actor keys;
- no entropy failure creates another provider call;
- request budget and ChatUsageLedger retry contracts remain unchanged;
- interrupt status is preserved;
- cancellation does not cause a random fallback;
- selection metadata and cancellation reason remain separate.

## Observability schema

### TraceStore

Only these flat keys are public:

~~~text
selectionEntropy.schema
selectionEntropy.mode
selectionEntropy.algorithmVersion
selectionEntropy.replayAccepted
selectionEntropy.coherenceStatus
selectionEntropy.seedFingerprint
selectionEntropy.decisionDigest
selectionEntropy.decisionCount
selectionEntropy.drawCount
selectionEntropy.stableTieBreakCount
selectionEntropy.candidateDriftCount
selectionEntropy.routerDrawCount
selectionEntropy.strategyDrawCount
selectionEntropy.ensembleDrawCount
selectionEntropy.completionOrderDeterministic
selectionEntropy.reasonCode
~~~

Allowed values:

- schema: awx.selection-entropy.v1;
- mode: standard or replay;
- algorithm: selection-entropy-v1;
- coherence: not_requested, accepted, matched, partial, failed, forbidden, or invalid;
- fingerprint: twelve lowercase hexadecimal characters, replay only;
- digest: a fixed-format lowercase hash;
- counts: actual non-negative integers capped at 10,000;
- completion-order flag: boolean;
- reason: closed-set code.

### Snapshot sanitizer

TraceSnapshotStore receives dedicated key and value sanitizers. It must not pass all selectionEntropy-prefixed values. Unknown fields, invalid enums, malformed hashes, negative counts, oversized counts, strings masquerading as booleans, raw seed names, and authorization-like values are removed or replaced with a safe redaction marker.

Raw seed is not placed in TraceStore.putInternal.

### Agent-visible projection

AgentVisibleDebugEvidenceBuilder gets a dedicated safe projection containing mode, algorithm, coherence, counts, completion-order flag, and reason code. Seed fingerprint is omitted by default. The projection is a debug breadcrumb and is not inserted automatically into the user answer or provider prompt.

### SSE

Chat stream transport receives a typed nullable SelectionEntropySignal rather than an arbitrary metadata map. Existing entropy-free events remain compatible through a delegating constructor and missing-field tolerance.

The typed projection contains only the allowlisted scalar subset.

## Chat UI

The existing trace-card renderer adds explicit read-only rows:

- mode;
- replay status;
- coherence;
- short replay reference;
- algorithm;
- decisions;
- draws;
- stable ties;
- router, strategy, and ensemble draw counts;
- candidate drift;
- completion-order status;
- safe reason label.

The UI:

- adds no seed input;
- adds no replay toggle;
- adds no local or session storage key;
- renders only the typed entropy object;
- does not enumerate arbitrary event fields;
- does not rely on color alone;
- uses accessible label/value rows;
- never shows raw seed, token, prompt, response, or candidate identifiers;
- removes stale entropy metadata on new request, cancel terminal, and session reload.

Example:

~~~text
Selection replay
Mode              REPLAY
Coherence         MATCHED
Replay reference  8f31c04a7d29
Algorithm         selection-entropy-v1
Decisions         5
Draws             4
Stable ties       2
Candidate drift   0
Completion order  Not deterministic
~~~

## Integration plan

### LLM router

- canonicalize candidate model routes;
- choose the first never-tried candidate by stable key;
- resolve exact UCB ties by stable key;
- replace only weighted fallback randomness with SelectionEntropy;
- preserve disabled routes, weights, cooldown behavior, and response-model verification changes.

### Strategy selector

- preserve fixed mode without draws;
- use distinct coordinates for epsilon and softmax;
- deterministically handle invalid distributions;
- preserve existing mode and temperature properties.

### Stochastic parameter sampler

- preserve the existing DoubleSupplier test seam through a compatibility adapter;
- replace all direct live draws and shuffle calls through the common API;
- assign stable node or role actor keys;
- preserve profile bounds and clamp behavior.

### Diverse ensemble orchestration

- resolve the carrier before fan-out;
- capture it in worker tasks;
- preserve first-observed terminal semantics;
- record terminal node and cancellation reason separately;
- retain all current provider-evidence and budget behavior.

### RRF and DPP

- add total stable document-key tie-breaks;
- keep current relevance and diversity formulas;
- avoid duplicating NovaNextFusionService ID ordering;
- add permutation-equivalence tests.

## TDD strategy

### Core RED tests

1. Golden vector for fixed seed, algorithm, coordinate, and exact result.
2. Same coordinate returns the same value across instances.
3. A-B-C and C-A-B evaluation orders produce the same coordinate map.
4. Parallel shuffled submission produces the same coordinate map.
5. Different decision, actor, attempt, and draw coordinates are domain-separated.
6. Invalid coordinate and bound inputs yield fixed reason codes.
7. Raw seed has no string, serialization, or trace representation.
8. Decision-ledger capacity marks partial without changing selection.

### Context tests

1. Existing empty context resolves to standard mode.
2. Replay attach succeeds once.
3. Same reference attach is idempotent.
4. Different carrier replacement fails.
5. Copy preserves carrier and ledger.
6. Runnable and Callable propagation preserve replay.
7. Nested restore returns the outer context.
8. Exception and cancellation clear request state.
9. A later request cannot observe the previous carrier.
10. Non-web boot remains valid.

### Ingress tests

1. Header absence preserves existing chat and avoids replay authorization.
2. Existing admin token authorizes replay.
3. Existing owner token authorizes replay.
4. Missing, wrong, or unconfigured authorization yields 403.
5. Duplicate, malformed, short, long, padded, or unknown-version input yields 400.
6. Unauthorized and invalid requests invoke ChatWorkflow and providers zero times.
7. Sync and stream attach equivalent carriers.
8. Raw seed and token sentinels do not appear in DTOs, errors, traces, SSE, or persistence.

### Consumer tests

LLM router:

- HashMap insertion permutations produce the same cold-start choice;
- exact UCB ties select the same stable key;
- same replay seed and candidate set produce the same weighted choice;
- a bounded fixed seed deck exercises more than one eligible route;
- standard mode retains existing live exploration.

Strategy:

- fixed mode performs no draw;
- replay epsilon and softmax choices repeat;
- invalid weights use deterministic fallback;
- candidate permutations do not change a matched result.

Ensemble:

- temperature, top-p, profile, and shuffle repeat;
- nodes use independent actor coordinates;
- existing profile clamp tests remain green;
- parameter replay is independent of completion order;
- first-observed terminal tests remain green.

RRF and DPP:

- candidate permutations produce the same exact-tie result;
- stable IDs, canonical source keys, and internal hashes follow the defined priority;
- empty candidates are excluded deterministically;
- source caps and diversity formulas remain intact.

### Privacy, trace, and UI tests

- TraceSnapshotRedactionTest covers positive and negative entropy fields.
- AgentVisibleDebugEvidenceBuilderTest covers safe scalar projection and absence of raw sentinels.
- ChatApiControllerTraceMetaTest covers typed sync and stream serialization.
- UI Node contracts cover standard, matched, partial, failed, and missing entropy objects.
- Source contracts prove no replay input, toggle, or storage key exists.
- Cancel and reload contracts prove stale metadata removal.

No test uses an unseeded randomized assertion. Permutation and fuzz-like decks use fixed fixtures.

## Verification ladder

### Preflight

Before application-source mutation:

1. verify Java 17;
2. reconfirm Gradle projects and active source sets;
3. inspect branch, HEAD, worktrees, dirty targets, index lock, PatchDrop queue, and source leases;
4. declare the exact target set;
5. capture target preimage hashes;
6. freeze one redacted EvidenceSnapshot;
7. run exactly POSITIVE_QUERY, NEGATIVE_QUERY, and NEUTRAL_QUERY;
8. check A-B and B-A order stability;
9. require a stable APPLY;
10. enter the existing source-owner and preimage guard.

### Focused verification

Use Desktop-specific build and cache isolation:

- AWX_SPLIT_BUILD_OUTPUTS=1;
- a task-specific AWX_BUILD_HOST_ID;
- a task-specific GRADLE_USER_HOME;
- a task-specific project cache;
- no daemon;
- one worker.

Run in this order:

1. SelectionEntropy core tests;
2. context propagation tests;
3. replay authorization tests;
4. router tests;
5. strategy tests;
6. ensemble tests;
7. RRF and DPP tests;
8. trace and redaction tests;
9. SSE and UI Node contracts;
10. checkLangchain4jVersionPurity;
11. checkSourceSetHygiene;
12. compileJava;
13. relevant root tests;
14. app classes;
15. bootJar;
16. secret scan;
17. git diff check.

A focused failure is diagnosed before running broader tests. If broad tests show the repository's stale-class pattern, scripts/verify_full_test_refresh.ps1 separates cache contamination from real source failure.

## Runtime, Browser, and Computer proof

### Runtime gates

- re-check target and asset preimages;
- verify port ownership;
- use the first available isolated port from 18182 through 18192;
- start only a task-owned process;
- verify the fresh JAR timestamp and classes;
- match source, JAR-embedded, and served chat.js hashes;
- verify health before chat requests;
- stop only the task-owned process.

### Runtime replay proof

Run the same fixed request and candidate fixture twice with the same process-local seed. Retain:

- algorithm version;
- seed fingerprint;
- decision digest;
- draw count;
- tie count;
- drift count;
- coherence;
- terminal state;
- response length and hash only when needed.

Do not retain raw prompts or responses. A second bounded fixed-seed deck proves exploration variation without relying on a probabilistic one-shot difference.

### Browser

Use the explicitly requested in-app Browser after the fresh runtime is ready.

Verify:

- standard mode shows live exploration and not requested;
- authorized replay shows matched metadata;
- same-seed runs show equal visible fingerprints and decision digests;
- candidate drift shows partial;
- invalid replay never renders a success card;
- cancel reaches its terminal state without late tokens;
- reload removes stale entropy metadata;
- 1280 by 720 and 390 by 844 viewports have no horizontal overflow;
- console warning and error counts are recorded;
- a rendered answer is recorded separately from replay selection status.

Process-local PowerShell sends protected replay requests without printing raw token or seed. Browser reads the resulting safe trace card.

### Computer

Read the Computer Use guidance and confirmation policy before Windows control.

Verify:

- the trace card is visibly present in the Windows application;
- labels are not clipped;
- scrolling, overlays, chat input, and cancel controls remain usable;
- narrow-window layout does not overflow;
- reload restores a clean visible state;
- screenshots contain no raw seed or owner/admin token.

Computer evidence proves visible layout only. It does not prove authorization, HMAC derivation, propagation, redaction, provider generation, or wire attempts.

## Nine-hour execution budget

The budget is a maximum:

| Budget window | Work |
|---|---|
| 00:00-00:40 | Java, source-set, Git, lease, PatchDrop, preimage, and three-query preflight |
| 00:40-02:00 | core entropy and ledger RED to GREEN |
| 02:00-03:20 | GuardContext, propagation, sync and stream ingress |
| 03:20-05:10 | router, strategy, ensemble, RRF, and DPP integration |
| 05:10-06:20 | trace, snapshot, SSE, and chat UI |
| 06:20-07:30 | focused and broader verification |
| 07:30-08:30 | isolated runtime, Browser, and Computer proof |
| 08:30-09:00 | diff, secret, rollback, and requirement audit buffer |

The run stops early when all acceptance evidence is complete, every safe lane is exhausted by a decisive blocker, or a global source-edit gate prevents all authorized source work. Unchanged external blockers are not probed repeatedly.

## Dirty-tree and ownership policy

Existing user changes are preserved. Current known overlap includes:

- StochasticParamSampler;
- DiverseSamplingOrchestrator;
- LlmRouterBandit;
- LlmRouterProperties;
- GuardContext;
- ContextPropagation;
- ChatApiController;
- TraceStore;
- TraceSnapshotStore;
- UnifiedRagOrchestrator;
- chat.js.

For each file:

- confirm exact current preimage immediately before patching;
- avoid unrelated hunks and formatting;
- do not stage or reset other changes;
- hold only the overlapping lane on an owner, lease, or changed-preimage conflict;
- continue independent safe work;
- do not declare the whole objective complete while a required integration lane remains unresolved.

## Privacy and security

- raw seeds, tokens, prompts, responses, environment values, and provider errors are prohibited from evidence;
- only counts, hashes, fixed enums, booleans, timings, and reason codes are retained;
- unknown trace keys fail closed at the public projection boundary;
- replay authorization reuses existing constant-time guard behavior;
- replay failure does not disclose token configuration;
- no database, credential, ACL, plugin permission, provider, or deployment mutation is required;
- Plugin Management remains installed and enabled with no new dependency or permission change;
- no owner token is entered through Browser or Computer UI.

## Compatibility and rollback

Compatibility:

- standard mode is the default;
- ChatRequestDto and every existing ChatResponseDto constructor remain unchanged; typed sync evidence is an additive nullable response field only;
- UI input and storage are unchanged;
- non-web boot remains valid;
- existing first-observed terminal behavior remains valid;
- existing provider budgets and usage accounting remain valid;
- existing property names, secrets, Spring Boot version, and LangChain4j 1.0.1 remain unchanged.

Rollback:

- remove the new neutral selection package;
- remove exact GuardContext, consumer, trace, SSE, and UI integration hunks;
- remove focused tests created for this feature;
- leave unrelated dirty-tree changes untouched.

No database, persistence, provider, credential, dependency, or deployment rollback is required.

## Acceptance criteria

The feature is complete only when every row is proved with current evidence:

| Requirement | Authoritative evidence |
|---|---|
| Active nondeterministic surfaces identified | live call paths and focused test boundaries |
| Live exploration preserved | header-absent standard regression tests |
| Request-scoped replay | golden vector, same-seed, and order-independent tests |
| Async safety | executor and context propagation tests |
| LLM router improvement | stable cold-start, UCB tie, and seeded exploration tests |
| Strategy improvement | fixed-mode zero draw and replay epsilon/softmax tests |
| Ensemble improvement | profile and shuffle replay tests |
| RRF and DPP improvement | permutation-equivalence tests |
| Parallel meaning preserved | first-observed and cancellation regression tests |
| Authorization protection | owner/admin positive and negative tests |
| Privacy | sentinel-negative tests and secret scan |
| Observability | TraceStore, snapshot, agent, and typed SSE contracts |
| UI | Node contract and fresh Browser DOM |
| Windows visibility | Computer-visible proof |
| Build integrity | version, source-set, compile, tests, classes, and bootJar |
| Plugin boundary | installed state verified and no new dependency |
| Existing changes preserved | final diff plus target preimage and postimage evidence |
| Full objective closed | no required missing or HOLD lane |

The following are not sufficient completion evidence:

- one unit test;
- HTTP 200;
- terminal SSE;
- a trace badge;
- a seed fingerprint match alone;
- a response hash match;
- a Browser screenshot;
- a provider availability endpoint;
- unobserved provider or wire attempts.

## Completion language

Allowed claims:

- selection replay matched;
- candidate coherence matched, partial, or failed;
- UI rendering verified;
- Browser DOM verified;
- Computer-visible rendering verified;
- provider attempt observed or not_observed;
- completion order deterministic=false.

Prohibited claims:

- the whole answer is deterministic;
- the model is guaranteed to emit the same prose;
- Browser rendering proves server seed derivation;
- HTTP success proves replay success;
- Computer proof establishes server authorization or redaction;
- local selection proof establishes provider or wire success.

## Planned implementation order

After written-spec approval:

1. use Superpowers writing-plans to produce the detailed implementation plan;
2. read and execute the repository source-edit three-way preflight;
3. obtain stable APPLY and file ownership evidence;
4. write focused RED tests;
5. implement the neutral core and ledger;
6. integrate context and ingress;
7. integrate router, strategy, ensemble, RRF, and DPP;
8. integrate trace, SSE, and UI;
9. run the verification ladder;
10. run fresh Browser and Computer proof;
11. perform a requirement-by-requirement completion audit;
12. mark the active goal complete only if every acceptance criterion is proved.

No commit, push, deployment, credential change, plugin permission change, or database mutation is authorized by this design.

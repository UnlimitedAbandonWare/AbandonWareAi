<!-- BEGIN SUBAGENT-RESILIENCE-AND-COMMON-VERIFIER (demo-1, 2026-10-01) -->
# P6 복원력·검증·데이터 정합성 5대 지침 (SSOT)

Source: P6 / MA3212IN 종합 분석 (2026-10-01). Agents.md summary block:
`DEMO1-P6-RESILIENCE-RULES`. These rules govern agent verification, subagent
execution boundaries, vector-ingest data integrity, front classification, and
Jev/OAuth credit use. They are tooling/guidance rules — product source edits
remain gated by the existing lease + checkpoint machinery and (for this audit)
are owned by the Codex lane.

## 원칙 1 — 에이전트 독립 공통 검증기 (Common Verifier)

- An agent's own "PASS"/"Done"/success JSON is a **claim**. The only evidence
  is what `scripts/common_verifier.py` ran and observed itself.
- Blocking conditions (in order): non-zero exit / launch failure → `FAIL`;
  exit 0 with zero executed tests → `INCOMPLETE`; required suite 100% skipped
  → `INCOMPLETE`; `@Disabled`/`@Ignore`/skip markers added or assertions
  removed versus the recorded preimage → `REJECTED`; any pinned file digest
  changed after the run → `INVALIDATED`.
- All-clear emits `VERIFIED_PENDING_APPROVAL` — approval stays a separate
  step; the verifier never approves.
- Verify results are bound to base revision, candidate digests, spec digest,
  policy digest, actual command, exit code, and test counts.
- A green `exit=0` from the agent's CLI alone never counts as verification.
  Git hooks are convenience, not the enforcement boundary.

## 원칙 2 — 서브에이전트 무한 대기 차단 및 삼중 경계

- Three execution boundaries: pre-dispatch checks (blocked provider, no
  consent, exhausted budget, expired parent, saturated slots), in-flight
  limits (queue cap, concurrency cap, send timeout, parent deadline), and
  result-acceptance checks (question revision, current consent, settings
  generation, result format, arrival time, route committed).
- The parent deadline is inherited: fallback/retry spends only the remaining
  budget — never a fresh clock per stage. No recursive fallback-to-fallback.
- `CallerRunsPolicy` is forbidden at this boundary: saturation must not move
  a child wait back onto the main thread. Queues need explicit caps —
  `newFixedThreadPool` gives an unbounded `LinkedBlockingQueue`.
- `CompletableFuture.cancel(true)` ≠ actual task termination. The worker's
  slot is released on real termination only; late results are tracked then
  discarded, never adopted. Two finals for one request are blocked.
- Producing a typed failure result never requires waiting on another LLM
  response (no LLM chaining for error handling).

## 원칙 3 — 데이터 영수증 없는 Checkpoint 전진 금지

- `VectorFlushOutcome.durable()` is the contract: `backoff`, `store_failure`,
  `source_rejected` mean the batch is **not** durable — no accepted-count
  increase and no `saveState` advance for that batch.
- The shared queue's final `flush()` return cannot confirm a specific batch;
  per-source-record receipts (input id → derived vector ids →
  APPLIED / RETRYABLE_FAILURE / QUARANTINED / REJECTED) are required before a
  record's processing is committed. Rejected/quarantined items are counted
  separately from successful vector writes.
- `saveState` failures (including `ATOMIC_MOVE` failure) must propagate —
  never `catch (Exception ignore)`. A `.tmp` residue or unreadable checkpoint
  is a defect trace, not normal state.
- `readOffset` (position attempted) and `committedOffset` (contiguous
  confirmed-complete boundary) are different values. An unterminated JSONL
  tail is re-read next run; checkpoint only moves to the last complete record
  boundary. `FileChannel.write` must loop on short writes.
- Diagnostic: `scripts/check_vector_checkpoint_receipt.py` (snapshot
  violations R1/R2/R3 + static defect signatures; read-only).
- Success counts and run success are separate fields: zero-failure ≠
  processed, partial store ≠ complete, cancel/no-work are not store failures.

## 원칙 4 — 1차 분류 및 역할 분리

- Classification uses five axes — nature of the request, complexity, context
  need, evidence need, feasibility — not output-token caps. `max_output_tokens`
  alone must never promote a request to the high-tier model.
- One gate, one ruleset: `QueryComplexityGate` (injected classifier) and
  `RouterPolicy.complexMainRequest()` must evaluate the same rules for the
  same input — a `new QueryComplexityGate()` inside the policy is the known
  divergence defect (probe rule `R-GATE-DIVERGENCE`).
- A verdict that flips on whether the model-path file merely exists is a
  fragility defect (`R-MODEL-FILE-FLIP`); the classifier must be rule-version
  consistent.
- Clear requests resolve by local rules; ambiguous ones may call one
  classifier once; complex requests go straight to the allowed high-tier
  path — never "fail a small model first, then promote".
- Explicit user model/search/scope selections are immutable; a scorer never
  overrides them. Scorer failure keeps the existing route without hiding the
  error. Complexity and webNeed are independent axes — SIMPLE never drops
  required retrieval.
- Probe: `scripts/probe_front_router_consistency.py` (static rules +
  protected-candidate rerank mock; read-only).

## 원칙 5 — Jev 검색 후보 선별 한정 및 OAuth 크레딧 활용

- Jev is a judgement/rerank assistant on bounded options — a post-search
  candidate-selection (rerank/filter) aid — not a body generator and not a
  mandatory stage. Pre-search `webNeed` handle and post-search rerank handle
  are separate stages; protected candidates (the sole counter-example, the
  deciding sentence) must survive rerank even when their scores are low.
- The 70 ms figure was a TypeSafe US-west benchmark, not a global production
  guarantee — do not hard-code it as a hard timeout; measure local rule
  p50/p95/p99, scorer round-trip, first-content arrival, final p95
  separately.
- `confidenceAccepted` (probability vs threshold) and scorer confidence are
  different quantities; `LlmRouteScorer` path scores include config/fault
  penalties — never auto-approve on a merged number.
- ChatGPT-plan OAuth credit uses the Responses API with scope
  `chatgpt.tokens.use.direct`: `store:false`, `stream:true` are mandatory and
  unsupported fields must be isolated from the request. It is a different
  auth/billing lane than `OPENAI_API_KEY` platform billing — keep them
  separate.
<!-- END SUBAGENT-RESILIENCE-AND-COMMON-VERIFIER -->

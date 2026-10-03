# mdasain WP2 closeout — revalidation after Codex WP0/WP1/WP3/WP4

Task: `mdasain-wp2-closeout-f8d2d60a` · root: `C:/AbandonWare/demo-1/demo-1/src` · evidence checked: 2026-09-27 11:33–11:47 UTC (20:33–20:47 KST).

This packet is a **fresh re-execution**, not a copy of `mdasain-wp0-wp2-safety-84bf9ff0/final-report.json` (that claim predates Codex's WP0/WP1/WP3/WP4 landing). All commands, exits, and timestamps below are new.

## Preflight facts

- Git (read-only record, no commit): branch `codex/owned-runtime-browser-restart`, HEAD `2d18b143beec7b5c68a3ed2e2931caa0dc26f6c6`. Working tree was already dirty before this task (WP2/Codex files modified, uncommitted); no foreign staging touched.
- Codex `docs/diagnostics/mdasain-core-20260927/WP1-WP3-WP4-verification.md` confirms: "WP2 belongs to Devin… No live flag ON or online documentation question was attempted" and "revalidate Devin WP2's exact current evidence" is the named next activation step.
- **WP2-owned files unchanged by Codex**: `ApiRoutingDebug.java` sha256 `0e03e382…`, `KeyResolver.java` `9752f15b…`, `ApiRoutingInventoryLogger.java` `5b4dfc5d…`, `LlmGatewayBreadcrumbPublisher.java` `b3296367…` — identical to the prior run's recorded `sourceIdentity`. Codex diff on WP2-owned files for this task: **0**.

## B. Re-verification (new run, not prior claims)

| Run | Command | Exit | Result (this checkout, 2026-09-27 ~11:34–11:37 UTC) |
|---|---|---|---|
| verify-compile-java | `gradlew.bat :compileJava -x test` | 0 | runId `7066136b-99ed-4310-9dab-6d5fe07af34f` |
| verify-wp2-tests | `gradlew.bat test --tests <8 classes, same selectors as prior run>` | 0 | runId `6adf9ae1-4323-4a0b-8471-1fa971652564`; JUnit XML ts `2026-09-27T11:35:30–32`: **73 tests, 0 failures, 0 errors, 0 skipped** |

Per-class: ApiRoutingDebugRedactionTest 7/7 · ApiRoutingInventoryLoggerTest 5/5 · RoutingDebugEventBridgeTest 7/7 · LlmGatewayBreadcrumbPublisherTest 3/3 · KeyResolverProviderKeyTest 13/13 · DebugEventTracePromotionServiceTest 25/25 · AgentApiSpendGuardWiringTest 5/5 · DebugEventRedactionTest 8/8.

Note: the prior task's *recorded* run showed `RoutingDebugEventBridgeTest` 2 failures before its final fix; this fresh run is clean — the reported post-fix green is confirmed on post-Codex source.

## C. Live observation (web.search stayed OFF — flag never toggled)

Live dev runtime on 18180/18181 (Codex-started run `20260927-201725-1ae48ab5`; reused, not restarted). Endpoint used: `GET /api/diagnostics/debug/events` (HTTP 200).

| Item | Live evidence |
|---|---|
| Inventory runtime env count | Boot event `where=api.credential.resolved`, "inventory resolved (credential presence only)": `parsedCount=39, presentCount=25, absentCount=14` — **env names parsed > 0**, names/counts only, no values |
| credential ≠ route | 10 KeyResolver/boot lookups → only `api.credential.resolved` ("credential resolved (lookup only)"). **Zero** `api.route.*`/`api.spend.*` fabricated from lookups |
| route/spend → DebugEventStore (live) | Two minimal local `/api/chat/sync` requests (`useWebSearch=false, useRag=false, maxTokens=8`) produced **`where=api.route.attempt`** WARN ×2, distinct hashed requestIds. Data = allowlisted fields only: `{purpose=llm, provider=local, model=gemma4:26b, endpointClass=127.0.0.1:11434, attempt=1, errorClass=timeout_soft}`. No `api.spend.decision` (no paid/agent spend boundary reached). Baseline 12 events → 23 |
| INFO-off Store path | Store holds INFO-level events (e.g. `api.credential.resolved`) via direct `emitDebugEvent` → `DebugEventStore`, not logger-mediated. Strict "logger OFF" live state: covered by `RoutingDebugEventBridgeTest` (7/7 this run); live store shows INFO events persist independent of console level |
| Double-emit / conflict with Codex gateway/spend/bandit | None observed — exactly 1 route event per request boundary; no duplicate Store rows, no fake route events |

**Runtime lane fact (not a WP2 defect):** both chat requests returned HTTP 503 — local `gemma4:26b` on `127.0.0.1:11434` hit `timeout_soft` (attempt 1 each). This is an Ollama-lane latency/availability observation; it does not block WP2 AC (the route event is the evidence, and failure-path emission is itself the WP2 contract working). `verify/report verdict separation` applies: HTTP 503 ≠ target down.

## D. WP8 — debug-events.html

`eventSignalSummary` present in source (lines ~444–474: `why:<why_code>`, `route:<provider>><model>><fallbackTo>`) and in the **live served page**: `GET /admin/debug-events` → HTTP 200, 45141 bytes, `eventSignalSummary` + `why:` literal present. No active foreign lease/dirty on the file. → **NO_CHANGE_REQUIRED**.

## E. Activation gate statement

- **WP2_AC = green** (focused suite green post-Codex + live inventory count + live route-event observation closed).
- **live web.search stays OFF.** This task did not and does not enable it. Per the standing gate, ON requires the user's (or a separate approved Codex run's) explicit instruction; at that point at most one approved documentation question is in scope.
- Codex WP3 spend boundary: IMPLEMENTED per its verification.md — re-confirmed only, no edits made (Codex-owned file diff = 0).

## Disposition table

| 항목 | Verdict | 증거 |
|---|---|---|
| mask-before-truncate | IMPLEMENTED (re-verified) | ApiRoutingDebugRedactionTest 7/7, run `6adf9ae1` |
| inventory inline+block + runtime count>0 | IMPLEMENTED | ApiRoutingInventoryLoggerTest 5/5 + live `parsedCount=39` |
| credential≠route | IMPLEMENTED | KeyResolverProviderKeyTest 13/13 + live: lookups emit only `api.credential.resolved` |
| route/spend → DebugEventStore (focused + live) | IMPLEMENTED | bridge 7/7 + publisher 3/3 + promotion 25/25 + live `api.route.attempt` ×2 |
| safe-label preservation | IMPLEMENTED (re-verified) | DebugEventRedactionTest 8/8 |
| INFO-off observability | IMPLEMENTED (test) / live-partial | bridge test 7/7; INFO-level events live in Store |
| WP8 UI | NO_CHANGE_REQUIRED | `/admin/debug-events` 200 + `eventSignalSummary` why:/route: live |
| WP2_AC handoff | **green** | this packet + `live-observation.json` |

## Out of scope (recorded, not chased)

- Broad `:test` 76-failure baseline and `LlmRouterRequestTimelineTest` 2 adjacent failures — Codex-adjacent seam, not WP2; NOT_RUN/out-of-scope per brief. Not classified as pre-existing without per-failure evidence — simply outside this revalidation.
- H2 DDL duplicate-index/constraint warnings at boot — known partial-DDL baseline, recorded only.
- No commit, no push, no flag ON, no paid provider call, no source edit (this task changed **only** the two files in this diagnostics dir).

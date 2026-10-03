# CODEX_HANDOFF — one blocked seam

## Blocked seam

Live source-edit lease topic `devin-timeout-5min-cap-0928`.

| Field | Observed at preflight 2026-09-28 |
|---|---|
| taskId | `devin-timeout-5min-cap-0928-9b92923b` |
| leaseId | `88c65a3ab58549b5b332db327e984f7d` |
| status | active |
| expiresAtUtc | `2026-09-28T03:16:51.8110880+00:00` |
| purpose (journal) | rewrite soft-only timeout wording and wire a ~300s web/RAG/long-tool wall clock onto the existing request-budget keys |

Target paths as the preflight listed them:

- `configs/agent-api-spend-guard.yaml`
- `configs/api-routing.yaml`
- `main/java/com/example/lms/api/publicrequestbudgetguard.java`
- `main/java/com/example/lms/web/pagecontroller.java`
- `main/resources/application.yml`
- `main/resources/application-llm.yaml`
- `main/resources/configs/agent-api-spend-guard.yaml`
- `main/resources/configs/api-routing.yaml`

Why it blocks Track NW: NW3's fallback reserve and the seconds-vs-5-minute split both touch `api-routing.yaml` `chat_wait` and `public.request-budget.max-time-budget-ms`. During this assist, `application-llm.yaml` line 848 already defaulted that key to `300000`, with a comment that 300s covers a 180s transport timeout plus fallback. That file is leased. Do not edit it to "help" NW. Re-read the lease immediately before any later edit; a live lease is not stolen.

Unleased NW files at that same preflight (still re-check): `chat.js`, `RequestedModelTimeoutPolicy.java`, `LlmGatewayFailureClassifier.java`, `OllamaNativeChatModel.java`, `ChatApiController.java`, `CancelShieldFuture.java`.

## Next focused tests

Codex fills exits in `VERIFY_NW.md`.

1. `node scripts/chat_ui_stream_contract_tests.js` — change the assertion at lines 5853–5857 that today requires `streamClientDeadlineMs === null`.
2. `.\gradlew.bat test --tests com.example.lms.llm.gateway.LlmGatewayFailureClassifierTest` plus the bare `1429` / `vram=` cases.
3. Add `NoWaitFakeProviderContractTest` only after those two are the RED/GREEN pair. Do not boil the ocean of T01–T15 in one patch.

## Video log checklist (not a second seam)

Recording marks from `ma312in_evidence_2026-09-28.md`. UI milliseconds are not server durations.

| Mark | Look for | Do not conclude |
|---|---|---|
| 01:15 | `client-wait:`, `next:stop_or_wait`, a preparing string with a different elapsed than the route line | that 70003 or 60009 is the server timeout |
| 03:05 | still `client-wait:` and pending | that 160015ms is the server budget |
| 03:25 | `message_failed`, `rate_limited`, `wait_then_retry`, `Sync not_attempted` | that the origin was HTTP 429. The screen label can come from `contains("429")` or from a real status. Logs have to say which |

RAG was requested OFF in that video. Do not claim the vector store is empty, corrupt, or healthy from this recording.

Browser "the wait is gone" is Codex-owned. Admin login, HOLD, and `backend_unavailable` scenes are **NOT_RUN**.

## evidence_needed

- Java reader of `caller_timeout_releases_capacity` (not searched beyond seeing the yaml key).
- Whether `primary_timeout_division_for_fallback` is read from a generated or non-yaml source. None in `*.java` / `*.yaml` / `*.yml` at grep time.
- Worker-exit versus GPU permit return on the native Ollama path.
- Server log lines for the 01:15 / 03:05 / 03:25 recording (not opened).
- LangChain4j **1.0.1** streaming types versus the 2026-09-28 tutorial (`StreamingHandle` may be absent in the jar).
- Tavily parameter call site inside Acme.
- `RECENT_ONLY` vector calls outside `ConversateApiCueService`.

## NOT_RUN

Gradle, Node tests, Verify-RAG, live web search ON, HOLD/admin Browser, commit, push, AWX mine (no failed log).

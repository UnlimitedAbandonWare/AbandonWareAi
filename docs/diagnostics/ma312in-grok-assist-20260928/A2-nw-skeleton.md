# A2 — Track NW skeleton (tests and maps only)

Codex implements. Line numbers are a 2026-09-28 live read of Project Root. Re-read before editing. Devin may still be writing the leased budget files; those lines can move.

## Time layers (do not mix)

| Layer | Clock | Live observation | Leave alone |
|---|---|---|---|
| Ordinary `/chat` model call and fallback | seconds: first public answer or terminal about 5s; generation at most 30s. These are the NW directive's proposed starter values, not current bindings | `streamClientDeadlineMs` returns `null` (`main/resources/static/js/chat.js` 6770–6772). Heartbeat text is `client-wait:${elapsed}ms` (6819–6821). Legacy server budgets if the meta tag is absent: model 90000, web 30000, evidence 120000 (278–280, 6774–6782) | Do not copy the 300s request cap onto this layer. Do not restore 180/240 as "wait longer" |
| Web search, RAG, long agent tool loop | about 5 minutes wall clock | `main/resources/application-llm.yaml` 848: `public.request-budget.max-time-budget-ms` default **300000**. Comment at 845 says 300s covers a 180s transport timeout plus fallback. `application.yml` 25–29 comments that this key lives only in the llm yaml; the key is not redeclared there in this read | Do not raise a single Brave/Naver call to 300s. Do not delete the cap to mean "no timeout" |
| One provider I/O | seconds | `AcmeAICoreGateway` blocks with `Duration.ofMillis(remainingMillis())` | same |
| Agent spend guard | not a chat timer | `configs/agent-api-spend-guard.yaml` line 9 and the resources copy: `production_hard_caps: false`. Header comment: the guard does not hard-cap production traffic | `false` does not mean ordinary chat may wait forever |

`scripts/chat_ui_stream_contract_tests.js` 5853–5857 currently **requires** `streamClientDeadlineMs(...) === null` for both a slow local message and `useRag`/`FORCE_DEEP`, and 5839–5846 requires a stream still pending at 95000ms with `client-wait:95000ms` and `next:stop_or_wait`. That assertion is the RED seed for NW1. Inverting it for ordinary chat must keep the evidence/web layer on its own clock.

## NW1 — `chat.js` deadline

File: `main/resources/static/js/chat.js`. Existing runner file: `scripts/chat_ui_stream_contract_tests.js` (Node `vm`, throws on failed `assert`). Not executed here.

Proposed test name (Codex adds or revises the existing script; do not add a second harness): `ordinaryChatDeadlineIsFiniteAndSplit`.

Assertions to aim at:

- Ordinary payload: `streamClientDeadlineMs` is a finite first-answer watchdog (server cap plus a small transfer margin). It is not `null`.
- `useRag: true` / `searchMode: FORCE_DEEP` does not inherit the ordinary 5s first-answer cap.
- A heartbeat tick does not extend the first-answer deadline.
- Three clocks stay distinct: first public answer, silence after answer progress, total run. One 1.5s timer must not abort a stream that is already emitting answer text.
- UI release does not automatically POST a new generation. Reconnect stays `attach=true` on the same run id.

## NW2 — streaming vs finished 60-char SSE

| Check | Live fact |
|---|---|
| Finished text, then 60-char slices | `ChatApiController` 2646: `for (String c : chunk(visibleFinalText, 60))` runs after `visibleFinalText` already exists (2489, 2645). `chunk` is at 3868 |
| Early `started` / status | not proof of token streaming |
| Native Ollama call | `OllamaNativeChatModel` builds `/api/chat` (1330) and uses `interruptibleCall("ollama_native")` (518). No method whose name matches `stream(` was found in that file |
| What "during generation" means | Ollama `stream: true` (docs default) yields `message.content` deltas and a final `done`. `thinking` and `tool_calls` stay out of the user answer |
| UTF-8 / NDJSON | split Korean code points, several JSON lines in one read, a partial JSON line, final `done`, and an error object each have a fixture |
| LC4j API | pin is 1.0.1. The 2026-09-28 streaming tutorial shows `StreamingHandle` only after a partial callback. If 1.0.1 matches that, a silent primary still needs a lower HTTP cancel. Do not import the tutorial's experimental `Flow.Publisher` unless the jar has it |

Proposed test name: `nativeChatEmitsContentDeltaBeforeDone`.

## NW3 — fallback reserve map (read)

| Setting or call | Where | What the read showed |
|---|---|---|
| `primary_timeout_division_for_fallback: false` | `configs/api-routing.yaml:15` and `main/resources/configs/api-routing.yaml:15` | grep of `*.java`, `*.yaml`, `*.yml` found **no Java consumer** |
| `caller_timeout_releases_capacity: false` | same yaml files, line 14 | seen in yaml. Java consumption was **not** searched separately |
| `request_cap_property` | yaml line 11 points at `public.request-budget.max-time-budget-ms` | the live default observed in `application-llm.yaml` is 300000 |
| `RequestedModelTimeoutPolicy` | `DEFAULT_REQUESTED_TIMEOUT_SECONDS = 180` (line 4). `timeoutSeconds` returns `Math.max(base, requested or 180)` for chat candidates (25–27) | base fallback when non-positive is 12 (line 14) |
| Callers | `PolicyBasedModelRouter` 417 and 498 | pass tier timeout and requested timeout into that policy |
| `localPrimaryTimeout` | `DynamicChatModelFactory` 737–742 | without shared failover, the full configured duration. With shared failover, `min(configured, budget.remainingMillis())`. No pre-reserved fallback slice |

Flipping the yaml flag to `true` without a Java reader does not reserve fallback time. Both yaml copies and `application-llm.yaml` are on the live Devin lease in `CODEX_HANDOFF.md`. `RequestedModelTimeoutPolicy.java` was not on that lease list.

Proposed test name: `primaryAttemptLeavesFallbackReserveInsideTheSameDeadline`.

- Fake primary emits only heartbeats. Independent fallback returns a content delta.
- Heartbeats do not move the first-answer deadline.
- Fallback does not reset the 5s admission clock.
- At most two real generation attempts (primary + one fallback).
- `caller_timeout_releases_capacity` stays false: timeout is not permit release.

## NW4 — classifier

File: `main/java/com/example/lms/llm/gateway/LlmGatewayFailureClassifier.java` `classifyMessage` (209–265).

Order inside the lowered 2048-char message: store/IO, then `contains("cancel")` (220), then `contains("429")` among other rate phrases (223–224) **before** `timeout` (226), then later `contains("vram")` (245).

So `elapsed=1429ms` contains `429`, and `vram=8192MiB` contains `vram`. The evidence note says this mis-label is confirmed and is **not** proof that the video's `rate_limited` was this bug.

Existing `src/test/java/com/example/lms/llm/gateway/LlmGatewayFailureClassifierTest.java` covers structured HTTP 429 quota bodies. It does not cover the bare substring cases.

Proposed test name: `bareDigit429AndVramTelemetryAreNotRateLimitOrOom`.

Fixtures: `elapsed=1429ms`, `trace=abc429def`, `HTTP_429` / status 429 as a typed HTTP exception, `TimeoutException` wrapped in a message that also contains `1429`, `GPU telemetry vram=8192MiB` versus a real OOM type. Typed HTTP status wins over the outer string. The word `cancel` alone is not a user Stop.

## NW5 — CancelShield vs interruptibleCall

| Mechanism | Live fact |
|---|---|
| `CancelShieldFuture.cancel` | `main/java/ai/abandonware/nova/boot/exec/CancelShieldFuture.java` 67–96. Always `delegate.cancel(false)` (93). A requested interrupt that did not cancel the delegate sets `softCancelled` and returns true (94–96). Comment: do not interrupt the worker |
| `interruptibleCall` | `ChatRunExecutionContext` 67–69. Opens `BlockingCall`, which registers an observer that `caller.interrupt()`s (81–85). Comment: scope is the blocking caller, not a shared I/O loop |
| Native use | `OllamaNativeChatModel` 518: `interruptibleCall("ollama_native")` |
| Permit | worker-exit before GPU/endpoint permit return was **not** traced. `evidence_needed` |

Proposed test name: `timeoutDoesNotReturnPermitBeforeWorkerExit`.

- User Stop and attempt deadline are different events.
- Cancelling the primary attempt does not cancel the fallback run.
- Soft-cancel true is not recorded as worker exit.
- A late primary token does not overwrite the fallback answer or the saved row.
- Same GPU UUID does not load a second large model while the first worker exit is unobserved. Different ports 11434/11435 are not that proof.

## Fake-provider sketch (Codex writes the Java)

One class is enough, next to the existing classifier test, using the current JUnit style. No new framework.

`com.example.lms.llm.NoWaitFakeProviderContractTest` (name only):

| Id | Inject | Pass |
|---|---|---|
| T01 | primary heartbeats only; fallback content at 100ms | fallback answer starts; calls <= 2; deadline not extended by heartbeat |
| T02 | primary COLD/OPEN/BUSY; fallback READY | primary generation calls = 0; queue wait 0 |
| T03 | every candidate unavailable | terminal by 5s; no automatic second POST; not counted as success TTFT |
| T05 | the NW4 fixtures | class and retry policy differ by origin |

T04 and T06–T15 stay in the NW directive section 7. Implement them only after T01–T03 and T05 are green. Full `:test` is out of scope.

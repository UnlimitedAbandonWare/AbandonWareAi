# A1 — Codex plugin loadout (ma312in NW → API)

Work type: ordinary chat wait removal, then a small API orchestration seam.
Matrix source: `.agents/skills/demo1-codex-plugin-roles/SKILL.md`, narrowed by `PASTE_GROK_ma312in_assist.txt`.
This session did not enable plugins. The table is the allowlist Codex should follow.

| Lane | Allow | This session |
|---|---|---|
| Superpowers | `systematic-debugging` and `verification-before-completion` only. One cause. A failing focused test before the patch. | not invoked |
| GitHub | read `status` / HEAD / diff | read-only: HEAD `2d18b143`, origin `AbandonWareAi` only. No commit, no push |
| Exa | official docs only: Spring MVC async, Ollama `/api/chat`, LangChain4j response streaming, Resilience4j circuit breaker and bulkhead | fetched 2026-09-28: LangChain4j streaming page and Ollama chat page. Spring/Resilience4j pages not re-fetched |
| AWX | `build_error_mine` only, and only on a failed compile/test log (`log_path` required) | tool schema confirmed; not called (no failure log) |
| Computer | localhost or a console only when Browser cannot show that fact | not used |
| glm_worker | one rebuttal prompt after Codex's first patch. Agreement is not verification | draft below; not sent |

## Forbid

Supabase, Data, Wolfram, SciSpace, Sites, Meta Wearables, Ads, a new SuperRouter, a new vector DB.

Browser HOLD, `backend_unavailable`, admin login, and protected-URL harden are **not** Done. PROTO_OPEN. An older Browser acceptance list that requires those scenes is **NOT_RUN**.

Video no-wait repro (the 01:15 / 03:05 / 03:25 timeline) stays Codex-owned. Grok only lists log keywords in `CODEX_HANDOFF.md`.

Also off: live `agent.tools.web-search.enabled` forced ON, paid fan-out, `add -A`, push, unapproved commit.

## Official-doc contrast (do not paste newer APIs into 1.0.1)

Repo pin: `build.gradle.kts` `dev.langchain4j:langchain4j:1.0.1` and `langchain4j-open-ai:1.0.1` (lines 134–135). Resolution rejects any other LangChain4j version (line 774).

Fetched `https://docs.langchain4j.dev/tutorials/response-streaming/` on 2026-09-28. That page documents `StreamingChatModel`, `StreamingChatResponseHandler` (`onPartialResponse`, `onPartialThinking`, `onPartialToolCall`, `onCompleteResponse`, `onError`), experimental `Flow.Publisher`, and `StreamingHandle.cancel()` which only appears on the partial-response context. Those names are **current site docs**. They are not proof that 1.0.1 contains them. Codex checks the pinned jar before calling them.

Fetched `https://docs.ollama.com/api/chat` on 2026-09-28. `POST /api/chat` body field `stream` is boolean, default **true**. The response object includes `message.content`, `message.thinking`, `message.tool_calls`, and `done`. Thinking and tool calls are not the user-visible answer.

Directive URLs not re-opened this session:

- https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
- https://resilience4j.readme.io/docs/circuitbreaker
- https://resilience4j.readme.io/docs/bulkhead
- https://docs.ollama.com/api/ps

## glm_worker rebuttal draft (do not treat agreement as a pass)

```text
Rebuttal only. The patch claims ordinary /chat no longer waits without a first answer,
while web/RAG/long-tool wall clock stays on its own ~5 minute cap.
Check: (1) streamClientDeadlineMs null is gone for ordinary chat but a healthy
stream is not killed at 1.5s, (2) 60-char slices of a finished string are not
called streaming, (3) Math.max(..., 180) no longer eats the fallback reserve,
(4) "1429" and "vram=" are not classified as HTTP 429 / OOM, (5) CancelShield
soft-cancel is not reported as worker exit, (6) leased api-routing.yaml and
request-budget files were not edited. Name the focused test and exit code.
If any item lacks a command exit, say evidence_needed. Do not agree from the diff alone.
```

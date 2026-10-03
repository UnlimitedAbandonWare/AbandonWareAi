# TRACE_COST_GUARD — visible ≠ `debug=true` (Devin assist rail, track TRACE only)

- Codex contract: `DEMO1-CODEX-TRACE-DOCK-ALWAYS-ON-20260929`
- Devin contract: `DEMO1-DEVIN-ASSIST-F01B-TRACE-RAILS-20260929` — **never merge with
  F01B**; completing this UI work is not F01-B approval.
- Devin does **not** edit product JS/HTML/Java — this file is the checklist + coupling map.

## 1. The F09 cost trap (regression to prevent)

```
OLD chain: toggle ON → withDebugQuery → /stream?debug=true → buildSplitPanel (eager HTML)
NEW chain: visible ON → structured events (traceSignal/pipelineSnapshot/turnTraces)
           + allowed summary GET only; detail view → existing scoped HTML path
```

UI `visible` and server `fullDebugHtml` are separate concerns. Panel ON alone must add
**+0** `debug=true` requests, `buildSplitPanel` calls, provider/LLM/embedding calls.

## 2. Coupling-point map (LIVE-verified 2026-09-29 — re-check before patching)

| seam | LIVE site | hazard |
|---|---|---|
| toggle → debug flag | `chat-trace-ui.js:19-30` `enabled()`+`withDebugQuery()` appends `debug=true` (:30) | UI-state → request flag coupling point |
| toggle listener | `chat-trace-ui.js:611`, export `:632` | multi-control single-store rule |
| restore/probe URLs | `chat.js:1199` `chatTraceRequestUrl`, `:1245/:1279/:1709/:1776/:6177` | all go through the same debug flag |
| stream URL assembly | `chat.js:6882` `chatTraceRequestUrl(payload?.attach…)` | **visible ON must NOT append debug=true here** |
| structured events | `chat.js:6019-6034` trace/transformer → `traceSignal`+`pipelineSnapshot` | reuse — no new logging system |
| diagnostic hiding | `chat.js:2848-2851` `markChatDiagnosticNode` → `aria-hidden=true` (call sites :2052/:3383/:3694/:6089…) | **do not reuse for the dock** |
| summary API slot | `ChatApiController.java:5057-5062` `getSessionTraceHtml` accepts `html|bundle` only | `format=summary` = NEW branch, same authz |
| eager-HTML gate | `ChatApiController.java:2239,:2568,:4599` `buildSplitPanel` under `debug\|\|exposeTrace` (:933 field, :1580 gate) | keep summary path off `TraceHtmlBuilder` |

## 3. Read-only static checks (rg — product files are NOT edited by Devin)

```powershell
# post-impl GREEN expectation: dock-visible state never forces debug=true
rg -n "debug=true|withDebugQuery|chatTraceRequestUrl|traceDock" main/resources/static/js/chat.js main/resources/static/js/chat-trace-ui.js
rg -n "buildSplitPanel|exposeTrace|format" main/java/com/example/lms/api/ChatApiController.java
# preference key hygiene — only version+visible under chat.traceDock.*
rg -n "localStorage|sessionStorage|traceDock" main/resources/static/js/
```

## 4. Storage rule

`chat.traceDock.v1:<serverDerivedOwnerScope>` stores **version + visible only**.
Never localStorage: trace bodies, memory bodies, snapshot JSON, cookies, tokens,
API keys. ownerScope discriminates accounts — it is not an access token and never
decides authorization. Storage blocked → in-page state only, chat keeps working.

## 5. Summary API contract notes

`GET /api/chat/sessions/{sessionId}/traces/{snapshotId}/html?format=summary` —
JSON projection (`Cache-Control: no-store`): schemaVersion, ids, capturedAt,
storageSource, completeness/missingFields, sanitized fields, verified runId only.
Same owner/admin/assistant/single-pointer checks as html|bundle. No HTML renderer,
no search/model/embedding calls. Never mask 401/403/404/5xx as empty success JSON.

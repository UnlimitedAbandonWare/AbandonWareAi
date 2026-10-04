---
name: demo1-chat-session-debug
description: >-
  Use when Grok/Devin/Codex/Cline must answer "왜 이 모델/이 경고?" for a chat or
  Meta Display session: read the durable per-run trace under
  var/debug/chat-session-traces via scripts/chat_session_debug_export.py and
  share only the export path
---

# demo1-chat-session-debug

## Why
Chat Debug FX is UI-only and request-scoped `TraceStore`/`debug-events*.ndjson`
are hard to correlate by session. The server now appends one sanitized JSON
record per finished chat run (success **or** failure/cancel) to:

```
var/debug/chat-session-traces/YYYYMMDD/<s|x-hash>.json   # JSONL, one run per line
```

Fields: `ts, sessionId, runId (hash:<sha256-12> only), surface(chat|display),
requestedModel, effectiveModel, baseUrlClass, ragEnabled,
agentDbContextEnabled, harmonyWarn, harmonyDecision, cfvmQueued, outcome,
errorClass, fallbackCount, traceKeys[]`.
No prompt bodies, response bodies, tokens, or API keys — by construction the
record stores key *names* only, never trace values.

## Do
1. Project root: `<repo>`
2. Find the session/run:
```powershell
python -B scripts/chat_session_debug_export.py status            # JSON: counts/days/latest pointer
python -B scripts/chat_session_debug_export.py list --since-hours 24
python -B scripts/chat_session_debug_export.py show <sessionId|runId>
```
   `show`/`export` accept a raw sessionId, a raw run token, a `hash:` value, a
   bare 12-hex hash, or a file stem — raw ids are hashed client-side to match.
3. Hand off a shared bundle:
```powershell
python -B scripts/chat_session_debug_export.py export <id>
# -> var/debug/chat-session-traces/export/export-<16hex>/ (records.json + manifest.json)
#    + refreshes export/latest.json; v2 metadata uses queryHash/queryForm, never raw query
```
4. Share **only the export path** in the handoff. `manifest.json.related`
   points at the existing Meta Display DB lane
   (`scripts/meta_display_db_export.py`, `var/meta-display-db/export/<runId>/`)
   for conversation rows — never open the live H2 file while the JVM holds it.
5. Cross-check `agentDbContextEnabled`, `effectiveModel`, `harmonyWarn`,
   `cfvmQueued`, `fallbackCount` against the chat UI Debug FX panel; the record
   must agree with it for the same run.

## Don't
- Do not paste record contents into prompts when a path suffices.
- Do not read `.secrets/` or add token values anywhere; `runId` is hashed
  because the raw token grants attach/cancel on the run.
- Do not enable `agent.db-context` outside local/wear profiles
  (`application-local.yml`, `application-meta-display.yml`); public/prod stays
  `false`. Kill switch: `agent.db-context.enabled=false`.
- Writer kill switch: `abandonware.debug.chat-session-traces.enabled=false`.

## Ownership check
- Anonymous session ownership = the `ownerKey` cookie only (`ClientOwnerKeyResolver`;
  IP and `X-Owner-Key` headers are never an ownership basis — a tunnel puts all
  clients on one IP).
- "남의 세션/run이 보인다" repro: two curl cookie jars (A/B) — `GET /api/chat/sessions`
  must list only A's rows for A; `state`/`cancel`/`ack`/`stream`/`sessions/{id}` on
  B's id must be denied for A (neutral/`session_forbidden`/403, not data).
- proto-open (`demo.auth.proto-open`) stamps every request ROLE_ADMIN name
  `proto-open` — ambient-admin is owner-scoped like a regular user for
  ownership checks, in every mode. Real admin = `isAdmin` && principal ≠
  `proto-open` (presented admin token → name `admin-token`, or a real admin
  login); only a real admin, and only in main mode, keeps the cross-owner
  read exception (`getAllSessionsForAdmin` list, foreign `state`/`cancel`/
  `sessions/{id}`). Interview mode grants no admin exception at all.
- Rule SSOT: `.windsurf/rules/demo1-session-ownership.md`; regression tests:
  `ChatApiControllerMainModeOwnershipTest`,
  `ChatApiControllerInterviewOwnershipTest`.

## Related
- Writer: `main/java/com/example/lms/debug/ChatSessionTraceRecorder.java`
  (wired in `ChatApiController` stream + sync terminal paths, exactly-once).
- Tests: `src/test/java/com/example/lms/debug/ChatSessionTraceRecorderTest.java`,
  `scripts/test_chat_session_debug_export.py`.
- Meta Display conversation DB: `$demo1-meta-display-db-export`.
- Agent-session (tool) health, not end-user chat: `$agent-session-watchdog`.
- Query-flow notepad (chrono viewer joining these traces + DB export +
  ndjson tails + journals): `python -B scripts/query_flow_notepad_bundle.py`
  -> `var/debug/query-flow/<stamp>/bundle.json`, open
  `docs/debug-ui/query-flow-notepad.html`; optional background refresh via
  `scripts/query_flow_watch.ps1 -Action start` (opt-in).

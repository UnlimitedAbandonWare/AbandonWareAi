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
1. Project root: `C:\AbandonWare\demo-1\demo-1\src`
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
# -> var/debug/chat-session-traces/export/<id>/  (records.json + manifest.json)
#    + refreshes export/latest.json (same convention as meta_display_db_export)
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

## Related
- Writer: `main/java/com/example/lms/debug/ChatSessionTraceRecorder.java`
  (wired in `ChatApiController` stream + sync terminal paths, exactly-once).
- Tests: `src/test/java/com/example/lms/debug/ChatSessionTraceRecorderTest.java`,
  `scripts/test_chat_session_debug_export.py`.
- Meta Display conversation DB: `$demo1-meta-display-db-export`.
- Agent-session (tool) health, not end-user chat: `$agent-session-watchdog`.

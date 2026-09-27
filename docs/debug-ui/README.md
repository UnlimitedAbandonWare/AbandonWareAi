# docs/debug-ui — Query Flow Notepad

Static, dependency-free viewer for the existing query/debug evidence lanes.
It renders **one redacted bundle** produced by
`scripts/query_flow_notepad_bundle.py` — it is not a second SSOT and does not
replace `var/rag-launcher/LATEST.json` or the `chat-session-traces` writer.

## Build the bundle

```powershell
python -B scripts/query_flow_notepad_bundle.py --since-hours 24
# -> var/debug/query-flow/<stamp>/bundle.json + NO_SECRETS
# -> var/debug/query-flow/latest.json  (this lane's own pointer)
```

Inputs (all read-only): `var/debug/chat-session-traces/**`,
`var/rag-launcher/LATEST.json` (via `read_rag_debug_trail.ps1`),
`var/meta-display-db/export/<runId>/export.sqlite` (never the live H2),
`logs/debug-events*.ndjson`, `logs/trace*.ndjson`, active `work_journal` entries.

Redaction contract: prompt/response bodies and secret values are never copied —
sensitive DB columns are `preview(<=80 chars)+len+sha12`, secret-looking
assignments are masked, and `NO_SECRETS` is written only after a post-write
pattern scan comes back clean.

## Open the notepad

- `python -m http.server <port>` at the repo root, then open
  `http://127.0.0.1:<port>/docs/debug-ui/query-flow-notepad.html` — it
  auto-loads `var/debug/query-flow/latest.json` -> `bundle.json`.
- Or open `docs/debug-ui/query-flow-notepad.html` directly (`file://`) and pick
  a `bundle.json` via the file input.

## Panes

- **Sessions** — chronological per-run records; filter by sessionId /
  surface / outcome; free-text search; click a row for the joined ndjson
  events (`sid` match) + an analyzer command hint.
- **DB export** — table list + tail rows of the latest `export.sqlite`
  (truncated/hashed cells).
- **Timeline** — `debug-events`/`trace` ndjson tails joined by
  `sid`/`requestId` when a session filter is set.
- **RAG trail** — latest launcher status/stage/runId + newest `var/debug`
  status digest + evidence excerpts (already redacted upstream).

Analyze: `python -B scripts/request_trace_analyze.py <file>` expects
`seq|t+<ms>|stage|event|fields|remain=` lines — sanitized bundles carry ids
(`sid`/`requestId`), not bodies; paste an exported payload to run it.

## Optional watch

```powershell
powershell -NoProfile -File scripts/query_flow_watch.ps1 -Action start
# polls var/debug/chat-session-traces for new files -> refreshes the bundle
powershell -NoProfile -File scripts/query_flow_watch.ps1 -Action status
powershell -NoProfile -File scripts/query_flow_watch.ps1 -Action stop
```

Opt-in only; defaults to `--since-hours 24` bundles. State lives under
`var/debug/query-flow/_watch/` (watch.json, watcher.out/err.log, `_stop.request`).

---
name: demo1-live-test-window
description: Use when the user declares a live device test (Fold6/Meta Ray-Ban Display) — no 18180 restart/compile while the marker is active; symptom reports map to logs by time only
---

# demo1-live-test-window

## Why this exists
During a user live test (~90 min, walking/cycling, Fold6 + Ray-Ban Display), a
second launcher restart or DevWatch compile kills the session mid-test. The
marker is a single file every agent can check cheaply, and the launcher +
DevWatch already enforce it.

## Marker: `var/live-test/active.json`
Written atomically by `scripts/live_test_window.py` (stdlib only):
```json
{"schemaVersion":"awx.live-test-window.v1","owner":"devin","startedAtKst":"...","untilKst":"...","devices":["fold6","glasses"],"note":"..."}
```
```powershell
python -B scripts/live_test_window.py start --minutes 90 [--owner NAME] [--devices fold6,glasses] [--note TEXT]
python -B scripts/live_test_window.py status   # absent|active|expired|corrupt
python -B scripts/live_test_window.py end
```
Default = 90 minutes, devices = fold6+glasses. `untilKst` in the past = inactive
(auto-expire, no cleanup needed). A marker that exists but cannot be fully
evaluated stays *active* while the file is < 24 h old — a possibly-live test
keeps protection, but stale corruption cannot block restarts forever.

## Rule A — while the marker is active, hold all server churn
- No agent restarts/compiles 18180: no `start_rag_stack.ps1`, no `-ForceRestart`,
  no DevWatch arm. Pending changes go into the ledger QUEUED list and apply
  after `end` or expiry.
- Enforced at the seams (do not duplicate):
  - `scripts/start_rag_stack.ps1` PREFLIGHT → refuses with
    `reason=live-test-window-active` unless `-UserRequestedRestart` is passed.
  - `scripts/dev_reload_watch.ps1` → `live-test-window-active` is a
    `DeferredRestartReasons` entry; the cycle defers before compile, fail streak
    stays 0 (`scripts/dev_reload_watch_tests.ps1` covers active/expired/absent).
- Only two exceptions let a restart through:
  1. The user explicitly asked for it → pass `-UserRequestedRestart`.
  2. The server is actually down AND nobody else is launching: `--ready` fails,
     launcher mutex is free, latest `var/rag-launcher/*/result.json` is not a
     running run. Then the user should be told first.
- `-CheckOnly` is exempt (read-only status probe).

## Rule B — symptom reports come in as time + device + symptom
The user reports only "around 16:0x, glasses/fold, X didn't work". Do not
restart anything to reproduce. Match the report ±2 min against:
- `logs/debug-events.ndjson` — capture/question/answer/focus lifecycle events
- `var/rag-launcher/<latest run>/chat-ui-vibe-listener-18180.out.log` — server log
- `var/rag-launcher/<latest run>/result.json`, `var/rag-launcher/LATEST.json` — which run was live
- `python -B scripts/model_default_probe.py --restarts-since <test start KST>` —
  prove no foreign restart coincided with the symptom (R8 in
  `$demo1-reachability-first-debug`)
Answer with a one-line cause + evidence `file:line` / log line. If the symptom
needs a fix that requires restart, record it QUEUED in the ledger — do not apply.

## Rule C — single user, latest capture start always wins
One user only; there is no "disabled for same owner" state. Server-side a new
capture start from the *same owner* reclaims the producer
(`producer_reclaimed`, `DisplayConversateController.java` `phoneTest`);
a different owner gets `producer_changed`. Client-side capture requires
`relay.eventOwner === 'THIS DEVICE'` (`app.js` `canCapture`) — `clientId` is
fresh per page load (`display-conversate.js`), so after a refresh the new page
must send its own activate to become `THIS DEVICE`. If a same-owner session is
stuck disabled, that is a bug — reproduce it in a test first, never just
force-enable the button.

## Related
- `$demo1-dev-reload` — restart mechanics this skill suspends during a window.
- `$demo1-reachability-first-debug` — R7/R8/R10 ready-gate and restart evidence.

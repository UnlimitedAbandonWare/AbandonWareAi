---
name: demo1-dev-reload
description: Use when demo-1 Java/Spring/Display sources change; compile+ForceRestart to live
---

# demo1-dev-reload

## Project Root

- Default: `<repo>`. All relative launcher/script paths are from here.

## Why this exists
Spring Boot keeps the old classpath in a running JVM. Codex must not "restart the same process" or serve previous `build/` artifacts and claim the edit is live. Official Boot hot-swapping expects **recompile then restart** (or DevTools restart classloader). This project uses **Start-RAG ForceRestart + DevWatch** instead of adding `spring-boot-devtools`.

## Launcher facts
- `Start-RAG.bat` → `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser`
- Ports: `18180` / `18181` / `18182`; profile `local,meta-display`
- **ForceRestart**: stop meta-display Spring on those ports, then start fresh (picks up new classes after Gradle compile inside the launcher path)
- **DevWatch**: after READY, arms `scripts/dev_reload_watch.ps1` (single-instance mutex)
- Logs: `[DEV-RELOAD] ...` in `var/dev-reload/dev-reload.log` (also `watcher.out.log` / `last-compile.*` / `last-restart.*`)
- AGENTS.md section: `DEMO1-SPRING-VIBE-RELOAD`

## Required sequence after Java/config edits
1. Confirm DevWatch is armed (`armed MetaDisplay=True port=18180` in the log) **or** run Start-RAG with ForceRestart.
2. Save/patch sources under watched roots (`main/java`, `main/resources`, `app/src/main/java`, `app/src/main/resources`).
3. Wait for tiered reload:
   - static → no Spring restart; refresh/reconnect client if needed
   - java/yml/properties → `source changed → rebuild → Spring restart` then `socket ready → client reconnected`
4. Only then hit Fold/Meta URLs or APIs for proof.

## Do
- Use one Start-RAG + DevWatch session for vibe loops.
- After Java edits, wait for `[DEV-RELOAD] socket ready` (or a completed ForceRestart READY) before verification claims.
- Prefer root `main/java` owners per AGENTS Runtime Boundary unless Gradle proves another active set.
- Keep Ollama reuse; do not kill unrelated Java (MCP helpers, etc.).

## Don't
- Don't restart only the old PID / reuse stale `bootRun` without compile.
- Don't treat `-CheckOnly` or an already-up port as proof new bytecode is loaded.
- Don't add `spring-boot-devtools` unless the user explicitly asks (duplicates DevWatch; multi-module classloader risk).
- Don't watch `build/` or `var/` (restart loops).
- Don't start a second watcher or second Spring on 18180–18182.

## Manual commands
```powershell
# full recycle + arm watcher
.\Start-RAG.bat

# alive only (no rebuild claim)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack.ps1 -MetaDisplay -CheckOnly
```

## Runtime-reloaded check (grokbot 2026-10-09)

- Before any test after a change, confirm the running server took it: `var/dev-reload/watch.state.json` / `last-restart.out.log` newer than the edit, `/api/chat/models` reflects config, and `python C:\Users\nninn\grokbot-tools\served_asset_check.py <static file>` shows served sha = disk sha.
- A reverted config with no restart since leaves the old state live (2026-10-09 19:09 revert vs 19:03:58 restart). See `$demo1-reachability-first-debug` R5.

## Ready gate + restart notice (grokbot3 2026-10-09)
- Before saying "서버 준비/테스트해도 됩니다": `python -B scripts\model_default_probe.py --ready` must print `NOVA_READY=READY`; otherwise relay its `한 줄:`.
- User asked for restart notice → announce BEFORE saving into a watched root, then report `--restarts-since <HH:MM>` ("재시작 감지 HH:MM") and the `--ready` result.
- `launcher-already-running` = run refused, JVM untouched → do not relaunch; run `--ready` and tell the user to close the new window. Details: `$demo1-reachability-first-debug` R7-R10.
- `live-test-window-active` = user live-test marker (`var/live-test/active.json`) is active → launcher refuses without `-UserRequestedRestart`, DevWatch defers before compile; do not work around it. SSOT: `$demo1-live-test-window`.

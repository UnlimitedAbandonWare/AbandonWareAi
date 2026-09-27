---
name: demo1-dev-reload
description: Use when demo-1 Java/Spring/Display sources change; compile+ForceRestart to live
---

# demo1-dev-reload

## Project Root

- Default: `C:\AbandonWare\demo-1\demo-1\src`. All relative launcher/script paths are from here.

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

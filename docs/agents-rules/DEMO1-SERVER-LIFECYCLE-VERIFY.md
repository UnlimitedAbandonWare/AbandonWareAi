<!-- moved-from: AGENTS.md L193-L206 sha256=aa6f835989cf1f84baad0aaac418f62f15b48b38b0559490acdecd7c2120d435 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-SERVER-LIFECYCLE-VERIFY -->
## Server start/stop + post-edit live verification
| BAT | invokes | controls |
|---|---|---|
| `Start-RAG.bat` | `scripts/start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser` | dev runtime 18180/18181/18182 (`local,meta-display`) |
| `Close-RAG.bat` | `scripts/stop_rag_stack.ps1 -MetaDisplay` | dev runtime + restart watchers only |
| `Start-Meta-Display.bat` | `scripts/start_rag_stack.ps1 -MetaDisplay -Wear -OpenBrowser` | wear runtime (own role; no ForceRestart/DevWatch) |
| `Close-Meta-Display.bat` | `scripts/stop_rag_stack.ps1 -MetaDisplay -Wear` | wear runtime only |
| `Verify-RAG.bat` | `scripts/debug_rag_stack.ps1 -Role dev -Action verify -WithCompile` | post-edit proof: Gradle compile + runtime/ports/HTTP/freshness/DevWatch (0/3/6/1) |
| `Verify-Meta-Display.bat` | `scripts/debug_rag_stack.ps1 -Role wear -Action verify -WithCompile` | same verify checks on wear runtime |
| `Status-RAG.bat` | `scripts/debug_rag_stack.ps1 -Role dev -Action status` | alive-only observation; NOT freshness proof |
- **Autonomous recycle:** without asking, run the Close/Start pair of the task's target server to prove edits live; recycle only affected roles (never restart wear for a RAG-only edit). BAT-managed servers are task-managed even when user-started; explicit "keep running"/"no restart" wins. Close BATs keep the other role, siblings, shared Ollama, unproven processes — never kill java/node by name or port. `Start-Meta-Display.bat` reuses a running wear runtime (`springReused=true`) — `Close-Meta-Display.bat` first for wear-side proof.
- **Freshness proof:** `var/rag-launcher/<ts>-*/result.json` (`status=ready`, `springReused=false`), `spring-owned.json`, armed `[DEV-RELOAD] socket ready`, real endpoint responses — compile success, BAT exit, changed PID, `-CheckOnly`, or old-process HTTP 200 prove nothing. Protected-runtime refusals (`meta-display-wear-runtime-protected`, `launcher-already-running`) are not failures — check port ownership first. One-call proof: `Verify-RAG.bat` (dev) / `Verify-Meta-Display.bat` (wear); `Status-RAG.bat` is alive-only and never counts as proof.
- 운영/검증/회수 BAT(세션 진입 다이제스트·lease 회수·오프라인 경계 검증·클린 재기동)는 `docs/agent-tooling/DEMO1-BAT-TOOLKIT.md` 참조.
<!-- END DEMO1-SERVER-LIFECYCLE-VERIFY -->

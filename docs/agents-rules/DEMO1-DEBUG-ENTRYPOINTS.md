<!-- moved-from: AGENTS.md L201-L206 sha256=4fd3492e42d628a16071f3d50019c60ef97d15b12567956308ba254dd74f6096 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-DEBUG-ENTRYPOINTS -->
## Debug entry points (Debug-RAG / Debug-Meta-Display)
Thin wrappers over `scripts/debug_rag_stack.ps1` (`-Role dev|wear`); same targets as the matching Start/Close pair, never a second runtime path. Default `status` is read-only; no verbose without a symptom. Skill-free loop: `Status-RAG.bat` (alive-only) -> `Verify-RAG.bat`/`Verify-Meta-Display.bat` (post-edit proof) -> `Read-RAG-Debug.bat` (failure trail); `$demo1-evidence-debugging`/`demo1-toolchain-auto-select` stay optional archives, not required.
- `-Action verify` = one-call post-edit judgement (exit 0/3/6; schema `awx.debug.verify.v2` separates tool-ran vs target-verified — unrun checks stay `skipped|blocked|not_observed`, never a pass). `tail`/`threads`/`jfr`/`restart -Loggers` per `-Action help`; restore normal settings after (matching Close BAT; `status` shows `verboseLogging=absent`).
- **Wear-test:** `capture --role wear --invoke` (DEMO1-DEVIN-SOURCE-ORCHESTRATOR) — timestamps/counts/hashes only, never transcript text or API keys. Debug-started servers close with the matching Close BAT; shared Ollama is never stopped.
<!-- END DEMO1-DEBUG-ENTRYPOINTS -->

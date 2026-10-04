<!-- moved-from: AGENTS.md L213-L220 sha256=10acb0bca5ad8c305051e89ab0d4e3fdcc68b12f955c4cc0e1b8d397797f3070 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-RAG-DEBUG-TRAIL -->
## RAG/LLM debug trail (skill-free first read)
- RAG/LLM 기동·디버깅 조사 시 스킬 없이 먼저: `Read-RAG-Debug.bat` 또는 `powershell -File scripts/read_rag_debug_trail.ps1` → `var/rag-launcher/LATEST.json` 이 SSOT. 스킬 조회 불필요.
- Read-only: prints latest run summary + failurePoint + evidencePaths + re-read error excerpt + nextCommand hint (exit 0 ready / 3 no-latest / 4 degraded / 1 tool error). Never starts/stops a server.
- `[TRAIL CLASS]` 분류(`ready` | `not_running` | `degraded_verify` | `compile_or_build` | `launcher_stage_fail` | `meta_wear` | `unknown`)가 `[TRAIL NEXT]`/`[TRAIL NEXT-ALT]`/`[TRAIL RATIONALE]`를 결정한다: not_running→`Status-RAG.bat`, degraded_verify/unknown→`Debug-RAG.bat -Action verify`, compile_or_build→`Verify-RAG.bat`, launcher_stage_fail→`Read-RAG-Debug.bat`(evidencePaths 수동 확인), meta_wear→`Debug-Meta-Display.bat`. exit 코드는 계속 LATEST verdict가 소유(0/3/4/1) — 분류는 출력 힌트만 바꾼다.
- `-AiAssist`(`Read-RAG-Debug.bat -AiAssist`도 그대로 전달)는 `failureClass=compile_or_build`일 때만 `python -B tools/ai_debug_assist.py --log <launcher.log> --out <var/debug/<ts>-ai-debug.json> --mcp off --ai off`를 **출력만** 한다 — 실행하지 않고, Java/Spring 소스를 읽지 않고, Spring을 재기동하지 않고, `--out`은 항상 새로 생성되는 타임스탬프 파일. 다른 분류에서는 `status=skipped`.
- 같은 skill-free 경로: `Status-RAG.bat`(alive-only) → `Verify-RAG.bat`(post-edit verify) → 실패 시 `Read-RAG-Debug.bat`. 스킬 조회 불필요.
<!-- END DEMO1-RAG-DEBUG-TRAIL -->

# Devin 지시서 — Query Flow Notepad + 백그라운드 탐침 (2026-09-26)

## Goal
컨텍스트가 없어도 **기존 쿼리 로그·DB export·디버그 ndjson·RAG trail**로 작업 흐름을 어느 정도 복원·열람할 수 있게 한다.
메모장형 뷰어(시간순·세션·검색/필터) + 디버그 타임라인 + 탐침 스크립트를 **기존 seam에 연동**.
제2 SSOT / 제품 chat-trace(mgain) 복제 금지. **push 금지.**

완료 = Acceptance 전부 PASS. “파일만 읽음” ≠ 완료.

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## 복원 가능 여부 (답)
**partial.** 오프라인으로 복원 가능:
- `var/debug/chat-session-traces/**` + `chat_session_debug_export.py` (메타/outcome/traceKeys; **본문·secret 없음**)
- `var/meta-display-db/export/<runId>/` (unlock 시 export된 대화 행)
- `var/rag-launcher/LATEST.json` + `Read-RAG-Debug.bat`
- `logs/debug-events*.ndjson`, `logs/trace*.ndjson`
- `work_journal` / `PROJECT_STATUS` (에이전트 작업 흐름)
약한 점: 풀 프롬프트/응답 본문, live admin DebugEventStore, 자동 join 뷰어(지금 없음)

## Reuse (포크 금지)
| Seam | Path |
|------|------|
| RAG trail | `Read-RAG-Debug.bat` → `scripts/read_rag_debug_trail.ps1` → `LATEST.json` |
| Chat query log | `var/debug/chat-session-traces/` + `scripts/chat_session_debug_export.py` + `$demo1-chat-session-debug` |
| Meta DB export | `scripts/meta_display_db_export.py` (export.sqlite only; live JDBC while locked 금지) |
| Request analyze | `scripts/request_trace_analyze.py` |
| Session watch | `scripts/debug_session_watch.ps1` |
| Agent vibe watch | `scripts/agent_session_watch.py` / `Watch-Agents.bat` |
| Journals | `scripts/work_journal.py` |

## THE ONE — `query-flow-notepad-compose`

### A. Bundle (redacted)
NEW `scripts/query_flow_notepad_bundle.py` (+ unittest):
- 입력(기본 read-only): LATEST trail 요약, chat-session list slice, meta-db export tails(해시/truncate), ndjson tail, related paths, `work_journal list --active` (taskId/purpose만)
- 출력: `var/debug/query-flow/<stamp>/` + `NO_SECRETS` 마커
- 선택: `chat_session_debug_export.py`에 `bundle`/`--related-flow` 플래그만 확장 (포크 금지)

### B. Notepad UI
NEW `docs/debug-ui/query-flow-notepad.html` + `docs/debug-ui/README.md`:
- 시간순 목록 + sessionId/surface/outcome 필터 + 검색
- 사이드: RAG trail 요약 (status/stage/runId)
- 탭 DB: export.sqlite 결과만
- 탭 Timeline: debug-events + trace ndjson을 requestId/sid로 join (있으면)
- Analyze: `request_trace_analyze.py` stdout → pre
- Footer: active journals taskId/purpose
- file:// 또는 `python -m http.server`로 열기. JSON dump만 로드.

### C. Optional background watch
NEW `scripts/query_flow_watch.ps1` (mirror `debug_session_watch` detach):
- `chat-session-traces` 신규 파일 poll → bundle refresh
- 장시간 바이브용; 기본은 opt-in

### D. Skill/AGENTS pointer (최소)
`$demo1-chat-session-debug` 또는 짧은 skill 1줄: notepad + bundle 진입점. AGENTS에 DEMO1-QUERY-FLOW-NOTEPAD 포인터 optional.

## Must NOT
- `main/java/**`, `chat*.js`, TraceHtml*, mgain product lease 수정
- live-JDBC H2 while Spring lock; secret/token/prompt body를 UI/bundle에
- `LATEST.json` / chat-session-traces writer 제2 SSOT 대체
- diagnostics permitAll / SafeRedactor 약화
- Display-reconnect / SelfAsk / peer-signal 등 다른 THE ONE 혼입
- push / add -A / force-push / secrets print

## Soft-auto git
conditional_local_git, foreign staging 보존, selective path. push 없음.

## Acceptance
```
python -B scripts/chat_session_debug_export.py status
cmd /c "set AWX_RAG_NO_PAUSE=1&& Read-RAG-Debug.bat"
python -B scripts/meta_display_db_export.py status
python -B scripts/query_flow_notepad_bundle.py --since-hours 24
python -B -m unittest scripts.test_query_flow_notepad_bundle -v
# open docs/debug-ui/query-flow-notepad.html against bundle
# assert: bundle/HTML에 api_key|bearer|password|token= 값 없음
```
- [ ] notepad: chrono + session filter + search 동작
- [ ] timeline 탭이 ndjson tail 표시
- [ ] watch.ps1은 opt-in (있으면 detach OK)
- [ ] push/secrets/product chat-trace 변경 없음

## SSOT
`agent-prompts/query-flow-notepad-20260926/brief.md`

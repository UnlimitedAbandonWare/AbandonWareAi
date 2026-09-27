# Devin 지시서 — 장시간 바이브: 교차 탐침 + 3-Way Self-Ask + 최소 패치 (압축, 2026-09-26)

출처: GPT Pro 장문 정제. 유효 규칙만. 수사/중복 삭제.

## Goal
`C:\AbandonWare\demo-1\demo-1\src`에서 장시간 자율 탐침.
유저가 매번 플랜을 안 짜도, **목적만** 주면:
1) working tree + 실제 실행 경로 먼저 읽기  
2) **탐침 → 가설 → 반례 → 중립 판정 → 최소 패치 → 검증** 반복  
3) 새 세션도 기존 흔적으로 맥락 복원 (`evidence needed`는 추측으로 채우지 않음)

완료 = 검증된 최소 패치 + handoff. “읽기만 / 플랜만” ≠ 완료. **push 금지** (유저 명시 전).

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## 교차 탐침 레인 (정답은 하나 아님 — 교차검증)
| 레인 | 진입점 |
|------|--------|
| 소스/active sourceSet | `build.gradle.kts`, 실제 scan/boot 경로 |
| 지침 | `AGENTS.md`, `.agents/skills/*`, Prototype Light |
| 쿼리 로그 | `scripts/chat_session_debug_export.py`, `var/debug/chat-session-traces/` |
| RAG trail | `Read-RAG-Debug.bat` / `var/rag-launcher/LATEST.json` |
| DB | `scripts/meta_display_db_export.py` (**export.sqlite only**; live JDBC while lock 금지) |
| 디버그 | `logs/debug-events*.ndjson`, `logs/trace*.ndjson`, `request_trace_analyze.py` |
| 흐름 뷰어(있으면) | `docs/debug-ui/query-flow-notepad.html` / `query_flow_notepad_bundle.py` |
| Git | `status`/`diff`/`log`/`blame` — **증거**, 정답 아님 |
| Handoff | `work_journal.py`, `data/agent-handoff/`, `docs/PROJECT_STATUS.md` |
| 3-Way | `$demo1-triad-deliberation` / `$positive-negative-neutral-judge` |
| Self-Ask rewrite | `$self-ask-query-rewrite-safe-patch` |

Git·과거 보고서만으로 확정 금지. **실제 실행 > focused test > 현재 코드 > DB/trace > Git > 문서/추정**.

## 자동 탐침 체크 (새 증거 있을 때만 계획 갱신; 범위 무단 확대 금지)
- request → controller/service/router/retriever 실제 연결
- 선택 모델 vs 실제 provider/model
- rewrite → Self-Ask → retrieval → RRF/rerank → evidence gate → prompt 조립 연결 여부
- 세션 전환 시 memory/context/settings 유실 지점
- DB에 있는데 앱이 안 읽는지
- UI 디버그: 미생성 vs 미전달/미표시
- fallback/legacy가 정상 경로 가로채는지
- dead configuration
- 최근 변경 ↔ 장애 시점 인과

DB/로그 = **read-only** 우선. 스키마 확인 전 임의 테이블 가정 금지.

## 3-Way-Long Tail Self-Ask (복잡할 때만)
1. **긍정:** 정상 경로 + 최소 wiring/호출 복구 가설  
2. **부정:** runtime sourceSet 미포함 / 로그만 있고 미실행 / 세션 미독 / fallback 위장 / 패치 전에도 동일 장애  
3. **중립:** APPLY | HOLD | REJECT — **APPLY만** 패치 후보

같은 증거 말만 바꿔 무한루프 금지. 수렴·새 증거 없으면 정지.  
원인 이미 재현되면 3-Way 생략 → 최소 수정+검증.

## 패치 선택 (많은 코드 ≠ 좋은 패치)
1 직접 인과 → 2 구조 우회 없음 → 3 정상 동작 최소 침해 → 4 되돌리기 쉬움 → 5 focused test → 6 회귀 → 7 중복 로직 없음  
→ **최소 diff로 최대 원인 제거**

## 검증
수정 전: RED/이상 상태 특성화(가능하면).  
수정 후: compile → 관련 unit → 관련 integration → 실제 요청 경로 → 필요 시 UI/DB/trace.  
안 돌렸으면 돌렸다고 말하지 말 것. 테스트 실패 시 테스트 억지 맞춤 금지 → 가설 재검토.

## Hard stops
- Java 17, LangChain4j 1.0.1 유지  
- 외부 계약/프로퍼티명 임의 변경 금지  
- 대형 리팩터·무관 파일·대규모 삭제(승인 전) 금지  
- secrets 출력/커밋 금지; raw 질의/민감 prompt를 디버그 로그에 추가 금지  
- DB destructive / 임의 migration 금지  
- dirty hunk / foreign staging 보존; soft-auto git만 (`conditional_local_git`)  
- AbandonWare3 원격 재등록 금지; push는 유저 명시 전 금지  
- proto-open 임의 강화 금지 (Prototype Light)

## 최종 산출 (handoff에 이 8줄만 명확히)
1. 실제 문제  
2. 원인 증거(경로/명령)  
3. 반례로 기각한 가설  
4. 최종 채택 원인  
5. 수정 파일 + 최소 diff  
6. 검증 명령 + 결과 (NOT_RUN 명시)  
7. evidence needed  
8. 다음 세션용 짧은 handoff (`work_journal` note / status_doc 가능하면)

## Must NOT
플랜-only 종료 · 추측으로 빈칸 채우기 · Git만으로 APPLY · mgain/다른 lease 무단 침범 · `add -A`/force-push/--no-verify

## SSOT
`agent-prompts/vibe-longrun-triad-probe-20260926/brief.md`

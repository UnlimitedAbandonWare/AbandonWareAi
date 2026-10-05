# Codex CONTINUE — 전체 강제 진행 (초반 PARTIAL 거부)
# 사용자 의도: 이전 directive-v2-sequential 세션은 "매우 초반"으로만 본다. 이번에는 API+트레이스 전체를 많이·끝까지 수행한다.
# 작성: 2026-09-27 Asia/Seoul · Project Root 고정

## 0) 한 줄
이전 보고의 HOLD/SKIP 다수를 종료로 받아들이지 마라.
`docs/diagnostics/directive-v2-sequential-20260927.md`는 출발 상태일 뿐이다.
첨부 mxasain_trace_porting_directive + audit + probes + DIRECTIVE_V2를 합쳐, API-01~03을 실제로 열고, WP0→WP8(트레이스 A→D)을 순서대로 끝까지 밀어라.
단위마다 체크포인트는 남기되 사용자확인대기·조기 종료 금지. 거대 단일 패치는 금지(단위 직렬).

## 1) 정본·경로
- 수정 대상: `C:\AbandonWare\demo-1\demo-1\src`
- 참고 ZIP: mxasain.zip(현재 스냅샷), src111_mergex15.zip(로직만, 통복사 금지)
- SSOT 기술: 첨부 `mxasain_trace_porting_directive_2026-09-27.md`, `mxasain_trace_audit_evidence_2026-09-27.md`, probes tgz, DIRECTIVE_V2 단위 정의
- 이전 세션 산출: `docs/diagnostics/directive-v2-sequential-20260927.md`, handoff `data/agent-handoff/codex-autonomy/directive-v2-sequential-0927-4cc36641`
- sole remote AbandonWareAi. 커밋·push·배포·add -A·reset·clean 금지(별도 승인 전).
- 타 세션 lease/foreign staging/build wipe/프로세스 kill 금지. PROTO_OPEN 유지(admin fail-close·로그인 harden를 완료조건으로 넣지 말 것).

## 2) 이전 세션을 거부하는 규칙 (중요)
아래는 완료가 아니다. 재개·재검증·필요 시 재패치 대상이다.

| 이전 주장 | 이번 요구 |
|---|---|
| API-01 HOLD (공통 allow-remote 켜면 openai-economy도 열림) | HOLD로 끝내지 말 것. 공통 스위치만 켜지 말고 단일 경로(우선 api3)만 카탈로그·strict 선택에 들어가게 경로 스코프 allowlist/manifest/route 최소 수정. 미승인 경로가 같이 열리면 실패·롤백. |
| API-02/03 HOLD (API-01 미완) | API-01 DONE 후 반드시 저장·재접속 보존(API-02) → 그 경로만 자동 후보(API-03). Self-Ask/판정/배치/Display로 API 폭증하면 API-03만 HOLD. |
| TRACE-01 SKIP / TRACE-02 "제품 이미 수정·fixture만" | LIVE 재검증 필수. F01·F02 다시 확인. `chat-trace-ui.js`에 `table.querySelectorAll("tr")`+slice 제거가 제품 코드에 남아 있으면 TRACE-02 미완으로 보고 제품 JS를 고쳐라(fixture만으로 DONE 금지). SafeRedactor의 token/prompt 과잉 정제도 재현되면 TRACE-01/WP1 재패치. probes PASS=재현 성공≠수정 완료. |
| TRACE-03 DONE but live qtx/detail 404 NOT_OBSERVED | 실서버에서 답변 상세/스냅샷 HTML에 Query Transformation(qtx) 그룹이 보이는지 확인. list 후 detail 404면 원인 수정까지 TRACE-04/WP3 범위로 포함. |
| TRACE-05 HOLD (공개 계약 확장 필요) | 이번 세션에서 승인한다. 기존 DebugEventStore/diagnostics API 안에서 동일 request/trace 해시 서버 필터·페이지를 최소 추가. 무제한 tail·새 파일 log reader 플랫폼은 WP7에서 기존 출력 통합만. |
| TRACE-06 일부 HOLD (링 밖 이력) | 시계 역전 수정은 유지. gap/eviction 명시 표시·재접속 계약을 WP5까지 밀고, 가능하면 WP6 기존 snapshot 영속으로 보완. |
| VECTOR-00만 / VECTOR wipe | wipe·removeAll·전체 재임베딩 계속 금지. 오염 근본원인이 재현되면 VECTOR-01 최소 수정만. |

"설정으로 안 되면 HOLD" 금지. 기존 Java/프로퍼티 심볼로 경로 스코프 해가 있으면 최소 diff로 구현하라.
"이미 있다" SKIP은 실패 테스트가 초록이고 LIVE도 통과할 때만. 정적 추측 SKIP 금지.

## 3) 강제 순서 (많이 · 전체)
각 단위: 실패 재현(또는 LIVE 재검증) → 최소 diff → 회귀 → 체크포인트(파일·명령·exit·실API여부·rollback) → 즉시 다음.

### Phase A — API 메인 실사용 (이전 HOLD 청산)
1. BASE — root/build/sourceSet/리스/저널. 이전 handoff 읽기.
2. API-01 — 단일 승인 경로(우선 조사 후 확정된 하나, 후보에 api3)를 UI 카탈로그+strictModelSelection+ChatWorkflow+DynamicChatModelFactory/LlmRouterAspect로 실제 선택·생성까지.
   - 공통 `app.ai.allow-remote-model-selection=true`만으로 전부 개방하지 말 것.
   - 전후 카탈로그 diff: 미승인(openai-economy 등) ready=false 유지.
   - Groq evidence TTL 90d 이미 있으면 유지, ExactRequestedModel 기대값 약화 금지.
   - 실원격 생성 1회 이상(예산·기존 키 범위). mock만으로 DONE 금지. 실패 시 선택 ID·실패 사유 보존, 몰래 로컬 교체 금지.
3. API-02 — 저장·재접속 후 동일 선택 ID/허용 판단.
4. API-03 — 그 경로만 auto 후보. 부작용 있으면 HOLD+근거.

### Phase B — 트레이스 A (WP0→WP2) 재검증·미완 패치
5. WP0 — baseline: F01/F02/F03… 현재 파일·줄·재현 명령. LIVE 대조표와 불일치하면 LIVE를 우선.
6. WP1 / TRACE-01 — typed 숫자·불리언·token count 보존 vs 비밀 문자열. 미완이면 패치.
7. WP2 / TRACE-02 — 제품 `chat-trace-ui.js` 중첩 표가 뒤 그룹을 지우지 않게. DOMPurify·생략 수 유지. Chromium 검증 필수.

### Phase C — 트레이스 B (WP3→WP5)
8. WP3 / TRACE-04 — assistantMessageId/v2 pointer, 캡처 상태, 종료 경계, snapshot list→detail 404 수정, latest 전역 대체 금지.
9. WP4 / TRACE-05 — 서버 exact 동일 요청 조회·안전한 딥링크(승인된 계약 확장). 최신 N 클라 필터만으로 DONE 금지.
10. WP5 / TRACE-06 — SSE cursor replay/dedup/gap 명시, 시각 역전·대량 이벤트·worker 회수.

### Phase D — 표시 연결·설명력 (WP8 + TRACE-03 확장)
11. TRACE-03 / WP8 일부 — 이미 생산되는 진단(qtx, keywordSelection, embed/vector/orch 중 확인된 것부터)을 TraceHtmlBuilder·화면에 여러 그룹으로 연결. 펼치기 때문에 모델/검색/기억 추가 실행 = 0. 원문·secret 화면 금지.
    LIVE에서 새 그룹이 실제로 보이는지 브라우저 확인(NOT_OBSERVED로 DONE 금지).

### Phase E — 보관·로그 가시성 (WP6→WP7) — 기존 저장소만
12. WP6 — 기존 TraceSnapshotStore/영속 포인터 계약 안에서 상세 보관·이 답변 증거 묶음. 새 snapshot 플랫폼·무관 bundle 서비스 신설 금지. 기존으로 안 되면 최소 엔드포인트 1개를 같은 모듈에 추가하고 카드에 적는다.
13. WP7 — 이미 나가는 로그/진단 출력을 웹에 안전하게 더 보이게(기존 appender/reader 재사용). 무제한 disk tail·임의 경로 read 금지. OTel 구조 변경은 하지 말고 기존 연결 검증만.

### Phase F — 벡터
14. VECTOR-00 읽기 전용 재확인 → 오염 재현 시에만 VECTOR-01 최소. wipe 금지.

## 4) 금지 (하드)
- 구형 ZIP 통복사, 새 RAG 엔진, major dependency upgrade
- 비밀 값 로그/보고서 출력, 진단 원문 지식벡터 적재
- 로그 보려고 LLM/검색/probe를 답변 경로에 추가 실행
- 보호 테스트·fixture 기대값 약화/skip으로 통과
- AbandonWare3 remote, 승인 없는 커밋/push/배포
- VECTOR removeAll / namespace 초기화 / 전체 재임베딩
- admin 로그인 harden / PROTO_OPEN 끄기

## 5) 검증·종료
- 단위마다 명령·exit·실API YES/NO·NOT_RUN 명시.
- 브라우저: 로컬 모델 회귀 + 승인 API 경로 1회 + admin debug-events LIVE + qtx/관련 그룹 실표시 + snapshot detail 200.
- Verify-RAG partial/DDL은 기록하되, 그것만으로 전체 DONE 주장 금지.
- 최종 보고: 단위별 DONE/SKIP/HOLD(진짜 불가만), 변경 파일, rollback(handoff), 이전 세션 대비 새로 연 것.
- API-01이 실생성까지 안 되면 세션을 성공으로 보고하지 말 것.
- WP0–WP8·API-01~03 중 재현된 미완이 남으면 계속. 전부 해소되거나 하드 금지가 막으면 그때 종료.

## 6) 시작 문장
이전 sequential 보고는 초반 PARTIAL이다. mxasain_trace_porting_directive WP0→WP8과 API-01~03을 Project Root에서 사용자확인대기 없이 순서대로 끝까지 수행한다. API는 단일 경로만 실개방·실생성하고, F01/F02는 제품 코드 LIVE 재검증 후 미완이면 고친다. TRACE-05 서버 필터와 snapshot 404·qtx 실화면까지 포함한다.

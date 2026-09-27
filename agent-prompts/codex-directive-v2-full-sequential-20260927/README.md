# Codex CONTINUE — DIRECTIVE_V2 전체 순차 실행 (초기→끝)
# 사용자 승인: 한 단위 후 사용자확인대기 중단을 해제하고, 초기부터 승인된 전체 단위를 순서대로 끝까지 수행한다.
# 작성일: 2026-09-27 (Asia/Seoul) · Project Root 고정

## 0) 이번 세션 한 줄
DIRECTIVE_V2의 **API-01→API-02→API-03→TRACE-01…06(+QUERY-01)→VECTOR-00** 을 **처음부터 순서대로** 끝까지 진행한다. 단위마다 국소검증·체크포인트는 남기되, **사용자 확인을 기다리지 말고 다음 단위로 자동 진행**한다. 거대 단일 패치로 묶지 말고, 단위별로 실패 재현→최소 diff→회귀만 한다.

## 1) 정본·경로·입력
- **Project Root (제품 수정 대상):** `C:\AbandonWare\demo-1\demo-1\src`
- **현재 정본 스냅샷(참고):** mxasain.zip — `main/` 오버레이. 독립 Gradle 루트라고 가정하지 말 것. build root/sourceSet/실행 main은 실제 트리에서 확인.
- **구형 로직 제공본(덮어쓰기 금지):** src111_mergex15.zip
- **범위·순서·중단 계약 SSOT:** 첨부 `DIRECTIVE_V2.md` (+ `SOURCE_EVIDENCE.md` 정적 근거)
- **이전 `mxasain_trace_porting_directive_2026-09-27.md` / audit:** 기술 근거·재현 출발점만. **실행 순서·중단은 이 CONTINUE + DIRECTIVE_V2 단위표**를 따른다. 이전 WP 전체를 한 방에 묶지 말 것.
- **sole remote:** `https://github.com/UnlimitedAbandonWare/AbandonWareAi` (AbandonWare3 폐기). push/`add -A`/reset·clean·강제 push·승인 없는 커밋·배포 금지.
- 시작 전: Git 상태, 기존 리스·저널, 타 세션 dirty/foreign staging 확인. 남의 diff·리스를 덮지 말 것. 공유 `build/` 전량 wipe·타 세션 프로세스 kill 금지.

## 2) 사용자 OVERRIDE (DIRECTIVE_V2 §5·§7.4·§11·§14와 충돌 시 이쪽 우선)
DIRECTIVE_V2 원문은 “한 단위 → 사용자확인대기 → 다음 단위 자동 시작 없음”이다. **이번 사용자 지시는 그 중단을 해제한다.**

| 원문 | 이번 세션 |
|---|---|
| API-01 후 종료 / API-02·TRACE 연달아 금지 | **해제.** 아래 순서표대로 자동 진행 |
| 테스트 통과 = 사용자 승인 | **여전히 아님.** 통과해도 체크포인트만 찍고 다음 단위 |
| 한 번에 여러 단위 거대 패치 | **계속 금지.** 단위 직렬, 카드 밖 변경 금지 |
| WP6 새 snapshot repo·bundle API / WP7 일반 log appender·file reader / OTel 구조 변경 | **여전히 OUT.** 별도 승인 전까지 구현하지 말 것 |
| VECTOR removeAll / namespace 초기화 / 전체 재임베딩 | **여전히 OUT** |
| 구형 통복사·새 RAG 엔진·major dep upgrade | **여전히 OUT** |

소프트 중단(그 단위만 HOLD/보류 기록 후 **가능한 다음 단위는 계속**):
- 해당 단위에 공개 인터페이스·새 의존성·권한 모델 확장이 필수인데 기존 계약으로 수용 불가
- 실패 재현이 안 되어 “이미 해결” — 근거(파일/테스트/명령)와 함께 SKIP 후 다음
- unrelated 테스트 실패 — 범위 확장하지 말고 별도 기록; 보호 테스트 약화·skip으로 통과 금지

하드 중단(세션 전체 STOP, 사용자 보고):
- 비밀 값 출력 위험, 승인 없는 유료 API 대량 호출, 타 세션 작업 파괴, Git 히스토리 개서, AbandonWare3를 origin으로 취급

## 3) 순서표 (초기→끝) — 이 순서만
각 단위: **이식 카드 기록 → 실패 재현(또는 SKIP 근거) → 최소 수정 → 해당 회귀만 → 체크포인트(단위 ID/파일·심볼/diff 요약/명령·exit/실API여부/위험/되돌림) → 즉시 다음**

0. **BASE** — Project Root·build root·allow-remote·manifest·`llmrouter.models`·메인 ChatWorkflow/DynamicChatModelFactory/LlmRouterAspect 경로 확인. 기존 API-01 HOLD/TTL 이슈가 있으면 여기 반영.
1. **API-01** — 등록된 API 경로 **한 개**를 메인 대화에서 직접 선택 가능하게. 우선 조사 후보에 `api3` 포함하되 무조건 활성화 금지. 설정으로 되면 Java/JS 최소. `app.ai.allow-remote-model-selection`·cloud manifest·route 정합. 공통 원격 허용 전후 카탈로그 비교(미승인 API 동반 개방 검사). fallback-only ≠ 직접선택 차단. strict 선택 실패 시 몰래 로컬/타 API 교체 금지. apiAttempt JSON/1024·Jev를 일반 생성으로 이식 금지.
   - **Groq 등 증빙 TTL:** 키 만료 ≠ evidence TTL. `GroqFreeTierGuard`류 `evidence-max-age`가 24h로 HOLD면 **기본을 문서 `reviewAfterDays: 90`에 맞게 ~90일(ms)로 설정 가능하게** 최소 수정. plan/keyHash/limits·ExactRequestedModelTest 기대값 약화 금지. 계정 근거 없으면 무료라고 조작·가드 전역 해제 금지.
2. **API-02** — 저장·재접속 후에도 선택 ID·허용 판단 보존(세션/settings 기존 계약 안).
3. **API-03** — API-01에서 검증한 **그 경로만** 자동 메인 후보 허용. `LLMROUTER_API3_FALLBACK_ONLY` / weight 등 실소비 설정만. Self-Ask/판정/배치/Display로 API 호출이 새면 채택 금지 → 그 단위 HOLD.
4. **TRACE-01** — SafeRedactor 등 안전 숫자·불리언·token count 타입 보존(비밀 문자열·중첩 map·반복 정제 손실 반례 포함).
5. **TRACE-02** — `chat-trace-ui.js` 중첩 표 행 제한이 뒤 진단 그룹을 지우지 않음. DOMPurify·정렬·생략 수 유지.
6. **TRACE-03** — 현재 생산되는 진단 그룹 **하나**(`qtx.*` 또는 `keywordSelection.*` 중 확인된 것)를 TraceHtmlBuilder/화면에 연결. 펼치기 때문에 모델·검색·기억 추가 실행 0.
7. **QUERY-01** — 구형 질의 변환 **작은 동작 하나**만, fixture로 이점이 확인될 때만. 새 재검색 루프·3모델 강제 금지. 실패 시 원질문 유지.
8. **TRACE-04** — 같은 답변 캡처/누락·pointer/snapshot 연결 유지. latest 전역 대체 금지.
9. **TRACE-05** — DebugEventStore 조회·관리자 소비자 범위 내 동일 요청 로그 개선(권한·필터 순서 보존).
10. **TRACE-06** — 기존 SSE 커서·재접속 계약 안 중복·누락 표시 보강. worker 누수·timestamp 역전 주의.
11. **VECTOR-00** — **읽기 전용 조사만.** 메타데이터·잔재 분류 기록. DB wipe/삭제 구현 금지.
12. **VECTOR-01** — VECTOR-00에서 **오염 유입 또는 검색 포함 근본 원인 하나**가 재현·확인된 경우에만 최소 수정 카드. 원인 없으면 SKIP으로 종료.

## 4) 보존 / PROTO_OPEN
- Prototype Light / **proto-open 유지.** 관리자 fail-close·CSRF/permitAll 조이기·admin 로그인 harden를 완료 조건으로 넣지 말 것.
- Java 17 · LangChain4j 1.0.1 · 기존 프로퍼티/환경변수 이름 유지.
- 파이프라인 모듈·공개 인터페이스·빈 범위·세션·기억·권한·검색 선택·임베딩 계약은 카드 승인 없이는 변경 금지.
- 비밀·동의·비용 상한·멱등성 유지. 실 API는 기존 승인·테스트 예산만; mock 성공과 구분 보고.
- 진단 때문에 검색·모델·기억 저장을 추가 실행하거나 진단 원문을 지식 벡터에 적재하지 말 것.

## 5) 검증·보고
- 단위마다: 실행 명령, exit code, 실 API 호출 여부(YES/NO), NOT_RUN 목록.
- 보호 테스트·fixture 기대값 약화/skip으로 통과 금지.
- 최종 보고(세션 끝): 단위별 DONE/SKIP/HOLD 표, 변경 파일 목록, 핵심 명령, 남은 위험, 단위별 rollback, OUT-of-scope(WP6/7 등) 미실시 명시.
- 전부 DONE 또는 남은 것이 HOLD/SKIP뿐이면 **임의 고도화 없이 종료.**

## 6) 시작 문장 (그대로 수행)
“DIRECTIVE_V2와 SOURCE_EVIDENCE를 읽고, Project Root `C:\AbandonWare\demo-1\demo-1\src`에서 BASE 확인 후 API-01부터 VECTOR-00(필요 시 VECTOR-01)까지 **사용자확인대기 없이** 순서대로 전부 수행한다. 단위별 체크포인트만 남기고 다음으로 간다.”

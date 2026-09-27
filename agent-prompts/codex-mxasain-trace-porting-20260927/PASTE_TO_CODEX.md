# Codex 붙여넣기 — mxasain 웹 트레이스·디버깅 선별 이식 (A단계)

작성: 2026-09-27 Asia/Seoul · Grok Bot (근거: GPT Pro 패키지 + LIVE Project Root 대조)

## 0. 역할

너는 **구현 담당(Codex)**이다. 분석·지시서는 이미 있다. 새 설계서를 쓰지 말고, **LIVE 소스에서 재현 → 최소 패치 → 검증**만 한다.

- **정본(수정 대상):** `C:\AbandonWare\demo-1\demo-1\src` (Project Root). ZIP/`main/` 오버레이보다 LIVE가 우선.
- **참고(덮어쓰기 금지):** `src111_mergex15.zip` / 구형 `docs/TRACESTORE_FLOW.md`, `50_DEBUG_PRESERVE.md` — 설계 의도만.
- **증거 패키지(읽기 전용):** 이 폴더 `evidence/` 및 사용자 첨부 `mxasain_trace_porting_pack_*`.
- **v2 범위 규칙:** 이전 지시서 WP 전체를 병렬·연속 승인으로 해석하지 말 것. **한 활성 단위만.** 이번 세션 = **A (WP0–WP2)**.

## 1. Goal (한 문장)

이미 생성 중인 디버깅 정보가 **정제·표 제한·저장·조회·화면**에서 소실되지 않게 하고, 같은 답변의 검색/문맥 조립/라우팅/가드/실패를 웹에서 연결해 보게 한다. 구형 로그 UI 통이식이 아니다.

## 2. Self-Ask (시작 전 5줄만)

1. 요청 동작: typed 진단값 보존 + 중첩 표가 뒤 그룹을 지우지 않음.
2. 현재 증거: LIVE `SafeRedactor` / `TraceHtmlBuilder.sanitizeMeta` / `chat-trace-ui.js` + evidence E05–E07.
3. 모호: ZIP 줄번호 vs LIVE 이동 — 메서드명·해시로 재매핑.
4. 바꾸면 안 됨: assistantMessageId/v2 pointer, DOMPurify, secret/raw prompt 차단, bounded SSE, session/routing/budgets, proto-open, foreign leases.
5. 최소 seam: allowlist/projection for safe bool/count/token **exact keys** + direct-row table cap.

## 3. 보존 / 금지

### 보존
- 답변별 `assistantMessageId` + `?TRACESNAP?v2`
- metadata-only snapshot 표시
- DOMPurify + textContent/replaceChildren 안전 경로
- trace/score dedup, 안전 정렬·필터
- bounded SSE runtime / worker / heartbeat
- 기존 session·settings·history·routing·budget
- `orch.events.v1` 의미를 새 규격으로 갈아엎지 않음

### 금지
- 구형 파일 통복사 / `src/main` → `src/src/main` 복사
- 못 찾은 스냅샷을 **최신 전역 snapshot**으로 대체
- secret·API key·raw prompt·ownerToken·clientToken 허용 확대
- 로그 보려 **LLM/검색/probe 재실행**
- 임의 path log tail / root DEBUG 상시
- 새 RAG 엔진·major dependency·관측 SaaS
- `demo.auth.proto-open=false`, SecurityConfig harden, Admin 대량 삭제, CSRF-off
- push / `git add -A` / history rewrite / 타인 staging 덮기
- 실패 테스트 skip·기대값 완화로 “통과” 만들기

## 4. 결함 우선순위 (이번 세션)

| ID | 상태(패키지) | 할 일 |
|---|---|---|
| **F01** | 격리 재현 E05–E06 | SafeRedactor/sanitizeMeta가 `prompt.*` bool/count·일부 `*Tokens`까지 죽임 → **exact key+type allowlist** (+ optional `TraceDiagnosticProjection`) |
| **F02** | 브라우저 재현 E07 | `table.querySelectorAll("tr")`가 중첩 표·뒤 그룹 삭제 → **direct rows only** + `total/displayed/omitted` |
| F03–F10 | E08–E18 | A에서 **재분류만** (`still_present/already_fixed/moved/not_reproduced`). 구현은 B+ 세션 |

결함으로 **취급하지 말 것**(이미 있음): v2 바인딩, metadata-only, 정렬/필터, dedup, bounded SSE, “웹 OFF면 sync trace 없음” 가설, OTel bridge 존재 자체.

## 5. Work packages — 이번 세션만

### WP0 — 기준점 (소스 변경 없음)
- build root / wrapper / JDK / 테스트 태스크 확인. ZIP에 없다고 LIVE에 없다고 단정 금지.
- E01–E18 핵심 파일을 LIVE에서 메서드 단위 대조 → `docs/diagnostics/trace-porting-baseline.md` 1개.
- 각 항목: `still_present | already_fixed | moved | not_reproduced`.
- foreign dirty/staging 보존. 이 문서 전체 파일 lease 독점 금지.

### WP1 — 타입 보존 [A 최우선]
**터치(예상):**
- `main/java/com/example/lms/trace/SafeRedactor.java` (실제 패키지 경로 LIVE 확인)
- `.../service/trace/TraceHtmlBuilder.java` (`sanitizeMeta`, `safeMetaKey`)
- `.../trace/TraceSnapshotStore.java` (standard event 정제 공통화 시)
- 필요 시 추가: `TraceDiagnosticProjection.java` — 동등 클래스 있으면 통합, 복제 금지

**테스트(제안명, LIVE runner에 맞춤):**
- `TraceDiagnosticProjectionTest` / `TraceHtmlDiagnosticContractTest`
- 실패 먼저: `prompt.historyRendered=true`, `prompt.events.webCount=4`, `llm.call.approxInputTokens`, `llm.ollamaNative.maxTokens`, `memory.session.tokenEstimate`, `orch.events.v1.phase`가 **정확한 타입**으로 HTML/JSON 동일 라벨.
- 멱등: 같은 safe projection 재처리해도 변형 없음. untrusted `{present,len,hash12}`를 trusted DTO로 가장하는 입력은 거부.
- 음성: secret/raw prompt/URI credential/owner token 미누출.

**수정 원칙:** SafeRedactor 전체 교체 금지. exact registry + 기존 standardEvent whitelist 재사용. `prompt.*` 광역 허용·모든 `*Tokens` 허용 금지.

**완료:** E06 현상이 프로젝트 테스트로 “타입 보존 PASS”로 대체되고 raw는 여전히 차단.
**체크포인트:** WP1만 commit 후보 (conditional_local_git / selective; push 금지).

### WP2 — 중첩 표 손실 [A]
**터치:**
- `main/resources/static/js/chat-trace-ui.js`
- 필요 시 `chat-trace.css`, TraceHtmlBuilder 중첩 표 출력

**테스트:**
- 내부 이벤트 120행 + 뒤에 바깥 진단 그룹 fixture → 그룹 생존 + omitted 정확.
- 헤더 비포함, 표별 cap, 60k 경계, 0/1/200행, 한글/emoji, metadata-only.
- DOMPurify·script 제거·정렬/필터/dedup 회귀. `node --check`는 syntax만 — DOM 대용 금지.

**수정:** `row.closest('table') === table`인 직접 행만 예산. 레이아웃 표 vs 데이터 표 구분. terminal/failure 요약은 이벤트 표 밖 보존. 상한 무작정 확대 금지.

**완료:** E07 fixture에서 뒤 그룹 보임 + omitted 표시 + 보안 경로 유지.
**체크포인트 후 STOP.** WP3+ 시작하지 말 것.

## 6. B/C/D (다음 세션 — 이번엔 구현 금지, baseline에만 메모)

| 단계 | WP | 요지 |
|---|---|---|
| B | WP3–5 | 종료 캡처 상태, 서버 exact 조회(`/events/query` additive), SSE seq cursor·gap |
| C | WP6–7 | 선택적 상세 영속·bundle, 구조화 로그 adapter (임의 tail 금지) |
| D | WP8 | producer→projection→section (DebugCopilot/ablation/qtx/keyword) — 새 엔진 금지 |

## 7. 검증·보고

```text
CODEX_TRACE_PORTING_A: DONE|PARTIAL
baseline: docs/diagnostics/trace-porting-baseline.md
changed: (파일 목록)
commands: (실행한 test / node --check / Verify-RAG 등)
pass/fail:
NOT_RUN: (전체 suite / 서버 E2E / 실 SSE 네트워크 등)
rollback: (WP1 projection off / WP2 JS revert — DOMPurify·secret mask off 금지)
next: 사용자 승인 후 WP3 또는 stop
```

성능: 관측 UI가 추가 유료 모델/검색을 부르면 실패. mock 호출 수 전후 동일 확인.

## 8. 스택·도구

- Java/Spring 기존 버전 유지. Gradle wrapper는 LIVE build root에서 발견.
- Prefer local Ollama; `AWX_AGENT_SPEND_GUARD` — 유료 fanout 금지 unless `AWX_AGENT_ALLOW_PAID_MODELS=1`.
- Git: `scripts/conditional_local_git.py` / soft-auto selective. Preferred `F:\git\cmd\git.exe`. secrets 출력 금지.
- Sole remote: `https://github.com/UnlimitedAbandonWare/AbandonWareAi`.

## 9. 교차 충돌

- **madasin design recovery:** admin Browser login / logout-block PASS를 이 작업 완료 조건에 넣지 말 것. proto-open 유지.
- **vibe auth-relax / low-admin:** 진단 API는 기존 `/api/diagnostics/**` 경계 재사용. 신규 무인증 공개·Ops X-Token 빈값 경로를 UI에 바로 연결하지 말 것.
- **primitive debug-AI / loadout:** Read-RAG-Debug·placement-scan과 충돌 시 관측 계약만 고치고 도구 empire 신설 금지.


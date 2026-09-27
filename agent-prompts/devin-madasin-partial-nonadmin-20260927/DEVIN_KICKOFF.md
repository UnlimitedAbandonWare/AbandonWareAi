# Devin 지시서 — madasin recovery PARTIAL, **운영자 admin 제외** 잔여 문제 해결
날짜: 2026-09-27 KST  
수신: **Devin**  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`  
패키지 SSOT(읽기): `C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\`  
관련: `agent-prompts/madasin-codex-design-recovery-continue-20260927/` (Codex CONTINUE)  
**범위 밖:** admin 로그인 / 로그아웃 후 차단 / proto-open 변경 / SecurityConfig harden / `/admin/**` UX (별도 vibe-admin 트랙)

## 사용자 의도
직전 Codex 보고(do09 PARTIAL)를 보고 **운영자 admin 빼고** 문제될 만한 것만 골라 Devin이 해결.

## Self-Ask
1. **요청:** admin 제외 잔여 결함 수정.
2. **이미 된 것 (다시 하지 말 것):** F01–F04, do05–do07 본문, 집중 fence 277+, Plan fixture 368/368, JS 31/31, bootJar, Verify-RAG `20260927-010101-13ec9646`, HOLD/backend_unavailable 미관측(해당 세션), A/B 세션 복원, trace 필터·정렬.
3. **문제 (admin 제외):** 아래 Top 문제.
4. **금지:** 통째 이식, 새 트레이스/RAG 엔진, secrets, push/dd -A, budget_skip 오류화, foreign staging 훼손, proto-open=false.
5. **THE ONE:** 전체 `test` 116 + `chatUiTest` 6 **분류→원인 하나→최소 패치→해당 묶음 green**. 그다음 evidence_needed 런타임 실관측.

---

## admin 제외 — 문제될 만한 것 (우선순위)

| P | 문제 | 왜 위험한지 | 해결 방향 |
|---|---|---|---|
| **P0** | 전체 `test` **116 fail** / exit 1 | do09 종료 PASS 불가; “전부 기존 실패” 단정 불가(preimage 없음) | 실패 목록 확보 → 클래스 분류(이번 diff 회귀 / 정적 fixture / EMF 인프라 / 무관) → **한 원인씩** 패치+재실행 |
| **P0** | `chatUiTest` **6 fail** / exit 1 | F01–F04 UI 회귀 가능 | chat-trace-ui / chat.js와 연결되면 우선; 테스트+브라우저 최소 재현 |
| **P1** | evidence_needed: metadata-only snapshot ↔ `assistantMessageId` **실런타임** 미관측 | F04는 코드에 있으나 브라우저/실요청 증거 없음 | 새 세션으로 저장·복원 관측; 모호하면 임의 연결 금지 유지 |
| **P1** | `attach=true` SSE 재연결 미관측 | “보존했다”만으로는 회귀 증명 아님 | Browser 실재현; 실패 시 failing test 먼저 |
| **P1** | memory 소유권·영속성 / attachment ready 미관측 | do08 fence≠DB 실상태 | 가능하면 실요청; 불가 시 evidence_needed로 **명시 잔여** 후 정지 |
| **P2** | 조사분 19건 중 **15 EMF 누락**(본문 전 실패) | “제품 버그”로 오인 → 엉뚱한 패치 | 테스트 인프라/컨텍스트 로딩을 제품 RAG와 분리 분류; 맹목적으로 프로덕션 코드 확장 금지 |
| **P2** | 로컬 HEAD `847d323` vs remote `b2eaba4` 관계 불명 | GitHub CI를 현재 근거로 쓰면 오도 | `git status`/merge-base 확인; 불명이면 remote **비근거** (직전 do00과 동일) |
| **P2** | 플러그인 과사용 유혹 | 범위 폭주 | Superpowers→해당 fail만; Browser는 P1 런타임만; AWX는 빌드/테스트 실패 로그 분류만; glm_worker는 1차 후 반박만; Vercel=Jev일 때만; admin Browser 시나리오 **삭제** |

### 문제로 치지 말 것 (이번 Devin 범위)
- 로그아웃 후 admin 200 / proto-open 유지 → **정책 OK**, 고치지 말 것.
- do05 admin 진단 UI·trace-snapshots 500→200 (이미 됨; admin 트랙과 겹치면 스킵).
- “부정: 로그아웃 후 관리자 차단 미충족”을 이번 백로그에 넣지 말 것.

---

## 작업 순서

### D0 인수인계
1. Project Root, `git status`, HEAD, branch, remote SHA (관계 불명 시 CI 비사용).
2. 직전 `run.json` / 실패 목록이 있으면 재사용; 없으면  
   `.\gradlew.bat test` / `chatUiTest` **각 1회**로 목록 → `agent-prompts/devin-madasin-partial-nonadmin-20260927/FAILURE_LIST.md`.
3. F01–F04 / chat-trace-ui.js / chat.js / ChatTraceSnapshotPointerPersister / ChatWorkflow / UnifiedRagOrchestrator — **회귀만**, 재설계 금지.

### D1 THE ONE — 실패 분류 & 수정
Superpowers systematic-debugging:
- 원인 후보 **한 번에 하나**
- 재현 테스트/구조 탐침 먼저
- 패치 후 해당 회귀 + verification-before-completion
- 중복 서비스·새 계층 금지

분류 라벨 (FAILURE_LIST에 필수):
`REGRESSION_FROM_MADASIN` | `STALE_FIXTURE` | `EMF_INFRA` | `UNRELATED` | `UNKNOWN`

`REGRESSION_FROM_MADASIN` / 명확한 `STALE_FIXTURE`만 패치.  
EMF_INFRA는 인프라 픽스 또는 evidence_needed — 제품 오케스트레이터에 새 계층 금지.

### D2 chatUiTest 6
동일. UI면 JS 테스트+최소 Browser.

### D3 P1 런타임 (Browser, admin 시나리오 없음)
새 세션만:
1. metadata-only snapshot ↔ assistantMessageId  
2. attach=true SSE 재연결  
3. (가능 시) memory/attachment — 불가 시 evidence_needed  
쿠키/토큰 Git·공개 금지. HTTP·reasonCode·요청 id 연결.

### D4 정지 조건
- 목표: do09에 해당하는 **비admin** 종료 근거 = 전체 test·chatUiTest exit 0 **또는** 잔여를 라벨+evidence_needed로 정직 분리한 PARTIAL 보고 후 **새 기능 없이 정지**.
- admin 조건을 PASS로 만들려고 proto-open 건드리지 말 것.

---

## 플러그인 (역할 제한, admin Browser 항목 제거)
| 허용 | 용도 |
|---|---|
| Superpowers | D1/D2 systematic-debugging |
| Browser | D3만 (HOLD/backend_unavailable 재확인은 선택; **admin 로그인/차단 시나리오 금지**) |
| GitHub | status/HEAD 보조만; 승인 없이 commit/push 금지 |
| Exa | 공식 규격만 |
| AWX | 실패 시 정제 로그→build_error_mine |
| Computer | localhost/콘솔 필요할 때만 |
| glm_worker | 1차 후 반박 (동의≠green); “권한 과확장” 검사는 **harden 제안 금지**로 해석 |
| Vercel | Jev일 때만 |
| 나머지 | 미사용 |

## 하드 스톱
secrets · push/dd -A/force-push · dirty/foreign staging 훼손 · Jev budget_skip 오류화 · 과거 소스 통째 이식 · 새 트레이스/RAG 엔진 · proto-open=false · CSRF off

## 보고 형식 (Abandon 유사)
`	ext
DEVIN_MADASIN_NONADMIN: DONE|PARTIAL
P0 test: fail_before=N fail_after=M labels=...
P0 chatUiTest: ...
P1 runtime: observed|evidence_needed
admin_scope: EXCLUDED
files: ...
tests: exits...
`

## Done when
- [ ] FAILURE_LIST 분류
- [ ] REGRESSION/STALE 패치 + 해당 테스트 green (또는 정직 PARTIAL)
- [ ] P1 관측 또는 evidence_needed
- [ ] admin/proto-open 미변경
- [ ] 새 엔진/통째 이식 없음
# Codex CONTINUE — madasin design recovery (do09 PARTIAL → 종료 PASS)
날짜: 2026-09-27 KST  
수신: **Codex**  
모드: **이어서** (do00부터 전면 재시작 금지)  
Project Root: `C:\AbandonWare\demo-1\demo-1\src`

패키지 SSOT (원본, 읽기):
`C:\Users\nninn\Downloads\madasin_Codex_design_recovery_2026-09-26\`
- CODEX_KICKOFF.md / Abandon.txt / ANALYSIS_REPORT.md
- evidence/EVIDENCE.md, browser_probe_results.json, source_inventory.json

미러·이번 CONTINUE:
- `agent-prompts/madasin-codex-design-recovery-20260926\`
- **이번 지시 SSOT:** `agent-prompts/madasin-codex-design-recovery-continue-20260927\CODEX_CONTINUE.md` (본 문서)

---

## 왜 CONTINUE인가 (스터프1 요약 — 이미 한 일, 다시 하지 말 것)

직전 실행은 **do09 PARTIAL / 종료 조건 FAIL**로 닫혔다. 아래는 **완료로 인정할 수 있는 범위**다. no-op 확인 + 회귀만 돌리고 **F01–F04·do05–do07 본문 재작성 금지**.

### 이미 적용·보존된 것
- Desktop 소스 기준 F01–F04 재현 간극 최소 수정 (저장소 rewrite / 새 트레이스·RAG 엔진 없음)
- 답변별 트레이스·DOMPurify·fail-closed·SSE 재연결 경로 **유지**
- do05: 관리자 진단 UI / non-admin 투영 / `/admin/trace-snapshots` 순환 view 500→200
- do06: Plan UNKNOWN ≠ 확장 승인; typed stage snapshot
- do07: ArtPlate 가상·휴리스틱만으로 승격 금지; 선택→요청별 trace 귀속
- do08: memory/attach/graph/embed/Jev fence 집중 검증 통과; `budget_skip` 오류화 안 함
- do09: 계약 표·프로젝트 상태 기록은 남겼으나 **종료 PASS 아님**

### 이미 통과한 fresh 증거 (재실행은 회귀용만)
| 항목 | 결과 |
|---|---|
| JS 회귀 chat-trace-ui | 31/31 exit 0 |
| Plan fixture 정렬 | 368/368 exit 0 |
| do08 집중 fence | 277/277 (+추가 통과분) |
| bootJar | exit 0 |
| Verify-RAG.bat | exit 0; DevWatch run `20260927-010101-13ec9646`, springReused=false |
| 브라우저 stream “안녕?”/일반질문 | HTTP 200, HOLD·backend_unavailable 미관측 (해당 세션) |
| A/B 세션 복원 | 각자 user1+assistant1 |
| 정제 trace 필터·정렬 | 화면 동작, 금지 DOM 미관측 |

### 명시적 미충족 (이번 CONTINUE의 본체)
1. **전체 `test`**: 11,647 중 **116 실패**, 12 skip, exit 1 — preimage 전체 baseline 없음 → **전부 기존 실패로 단정 금지**. 실패 분류 → 수정 범위 확정 → 해당 묶음만 green.
2. **전체 `chatUiTest`**: 144 중 **6 실패**, exit 1 — 동일.
3. **인증 종료 조건**: 사용자가 **proto-open 유지** 선택 → 로그아웃 후 관리자 차단은 **이번에도 불충족으로 보고**. `demo.auth.proto-open`을 fail-closed로 바꾸지 말 것 (사용자가 다시 지시하기 전).
4. **evidence_needed 런타임**: metadata-only snapshot의 실제 `assistantMessageId` 연결, `attach=true` SSE 재연결, DB memory 소유권·영속성, attachment 준비 상태 — **별도 실제 요청으로 관측**하거나 `evidence_needed`로 남겨라. 테스트 통과만으로 승격 금지.

### 직전 주요 터치 파일 (회귀 시 우선)
`chat-trace-ui.js`, `chat.js`, `ChatTraceSnapshotPointerPersister.java`, `ChatWorkflow.java`, `UnifiedRagOrchestrator.java`, `src/test/js/chat-trace-ui.test.cjs` + 관련 테스트·문서.  
작업 소유 경로 postimage drift 0이었음 — foreign/dirty staging·기존 staged 1개 **보존**.

---

## 스터프2 계승 — 플러그인 역할 제한 (전부 쓰지 말 것)

이번 작업에서 플러그인을 **역할을 제한**해서만 사용:

| 플러그인 | 허용 | 금지 |
|---|---|---|
| **Superpowers** | 가장 먼저 systematic-debugging으로 **남은 116/6 실패·evidence_needed**를 증상↔소스 연결. 원인 후보 **한 번에 하나**. 수정 후 해당 회귀+ verification-before-completion. 중복 서비스·새 계층 금지. | F01–F04를 “처음부터” 다시 설계 |
| **Browser** | 새 세션으로 **미관측 경계만**: SSE attach 재연결, metadata-only snapshot 복원 귀속, (proto-open 유지 전제) 로그인/잘못된계정/로그아웃 **동작 기록은 하되** “로그아웃 후 차단 PASS”로 끝내지 말 것. HTTP·reasonCode·요청 id 연결. | 저장된 admin 세션만으로 로그인 성공 증명; 쿠키/토큰 Git·공개 |
| **GitHub** | `git status`/HEAD/branch vs remote SHA. 관계 명확할 때만 보조. RagControl/ChatWorkflow/SecurityConfig/AdminTokenGuard/chat.js diff 우선. | remote를 Desktop보다 우선; 승인 없이 commit/push/merge/branch 삭제 |
| **Exa** | 공식 규격만 (docs.spring.io, playwright.dev, docs.github.com, 사용 provider). 소스만으로 못 정할 때. 확인일·버전 기록. | 블로그→Java17/LC4j 1.0.1 이식 |
| **AWX Control Tower** | compile/test/기동 실패 시 **정제 빌드 로그 경로만** → build_error_mine 분류. 후보 축소용. 원본 stack+코드 재확인. | 기본 AWX 정상인데 복구 AWX 사용 |
| **Computer** | Browser로 안 되는 localhost/콘솔/로컬 UI만. | 소스 탐색·수정 목적 |
| **glm_worker** | 1차 수정 후 독립 반박 검토만 (증상 은폐? 권한 과확장? 검증불필요↔실패 혼동?). | glm 동의=검증 성공 |
| **Vercel** | Jev/AI Gateway일 때만 env 존재·401/쿼터. | 키 출력, 배포/설정 변경, env pull 커밋, live ON 무단 배선 |
| Meta Wearables / Visualize / Supabase / Sites / Data / Plugin Mgmt / 기타 | **이번 CONTINUE 기본 미사용** | 건드리지 말 것 |

---

## CONTINUE 작업 순서 (do00 재시작 아님)

### C0 — 인수인계 게이트 (필수, 짧게)
1. Project Root 확인. `git status` / HEAD / branch / remote SHA (직전과 같이 remote 관계 불명확하면 CI/diff를 현재 근거로 쓰지 말 것).
2. 직전 보고·`run.json` 경로·실패한 테스트 **목록 파일**이 있으면 읽고, 없으면 `.\gradlew.bat test` / `chatUiTest`를 **한 번** 돌려 실패 리스트를 새로 뽑아 `evidence/continue-failure-list-20260927.md`에 저장.
3. Abandon.txt do09 종료 조건을 다시 읽고, **PASS에 필요한 잔여 항목만** 체크리스트화. “Abandon만 읽음”은 완료 아님.

### C1 — 전체 test 116 분류 (THE ONE for exit)
한 묶음씩:
1. Superpowers systematic-debugging: 실패 클래스별 **1 원인 후보**
2. preimage/고정 기대 vs 이번 F01–F04 변경으로 깨진 fixture vs EMF 등 인프라 vs 무관 flaky
3. **수정 범위에 넣는 것**만 패치 + 해당 테스트만 재실행 → green
4. 범위 밖은 `evidence_needed` 또는 `known-unrelated`로 분리 (전부 기존 실패라고 쓰지 말 것)

목표: do09가 요구하는 “fresh 전체 검증”에 도달하거나, Abandon 종료 조건이 **집중 회귀+명시적 잔여 목록**을 허용하는지 문서와 대조해 **정직한 PASS/PARTIAL**.

### C2 — chatUiTest 6
동일 절차. chat-trace-ui / chat.js 회귀와 연결되면 우선.

### C3 — evidence_needed 런타임 (가능한 것만 Browser/실요청)
우선순위:
1. metadata-only snapshot ↔ `assistantMessageId` 실연결 관측
2. `attach=true` SSE 재연결
3. (가능하면) memory 소유권·attachment ready — 불가 시 evidence_needed 유지

### C4 — 인증 조항 (정책 고정)
- `demo.auth.proto-open=true` **유지**
- 브라우저에서 로그인 전/오계정/로그아웃 후 admin URL·진단 API가 200인 것은 **불충족으로 보고**
- SecurityConfig를 닫아 “차단 PASS”를 만들지 말 것

### C5 — do09 재판정 + 정지
- 종료 조건 PASS면 **새 기능 붙이지 말고 정지**
- 여전히 PARTIAL이면 Abandon 형식으로 잔여만 남겨 보고하고 정지 (무한 확장 금지)
- commit/push/merge 금지 (사용자 명시 전). dirty/foreign staging·기존 staged 보존. soft-auto만.

---

## 하드 스톱 (스터프2와 동일)
- 과거 소스 통째 이식 · 새 트레이스/RAG 엔진 금지
- secrets 출력 금지 · push/dd -A/force-push 금지
- Jev `budget_skip` 오류화 금지
- Prototype Light: proto-open 유지 (이번 CONTINUE)
- AbandonWare3 폐기 · 단독 원격 AbandonWareAi (원격 변경 금지)

## 완료 정의
`do09 종료 조건 PASS` + fresh 테스트/빌드 증거  
**또는** Abandon이 허용하는 범위 안에서 잔여를 `evidence_needed`로 명시한 **정직한 최종 보고 후 정지**.  
“CONTINUE 문서만 읽음” / “goal만 읽음” ≠ 완료.

## 최종 보고 형식
Abandon.txt 형식: 요약 / doXX·CXX / 검증 exit / evidence_needed / 종료 판단(PASS|PARTIAL).

## 하지 말 것
- F01–F04 재구현 여행
- proto-open 무단 변경으로 인증 조항 “통과” 연출
- 플러그인 전부 가동
- glm_worker 동의 = green
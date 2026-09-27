# RECOMMENDATION — 바이브용 admin 표면: 없애기 vs 열어두기

날짜: 2026-09-27 KST · 작성: Devin · taskId: `devin-vibe-admin-surface-0927-291dc515`
역할: 감안·옵션 비교·권고 (코드 삭제/harden 미적용 — 문서-only)
SSOT 지시: `DEVIN_KICKOFF.md` (같은 폴더) · 교차 작업: `clean-vibe-low-admin-guardrail-20260927` (Clean이 룰/AGENTS seam 담당)

---

## THE ONE

> **Opt E 채택 — `demo.auth.proto-open` 유지 + `/chat`의 admin 크롬(운영 도구 메뉴·운영자 로그인·diagnostics nav)만 `hidden` 처리 + 바이브/에이전트 검증에서 "로그아웃 후 admin 차단"을 PASS도 FAIL도 아닌 `N/A-proto-open`으로 격하.** Admin 라우트·가드·Java·CSRF는 전부 그대로 두고, "proto-open 켠 채 공개 배포/push" 하드스톱만 유지한다.

이유 (한 줄): 사용자의 두 표현 — "차라리 없는 게 낫다"(= **눈에 안 띔**)와 "자유 접근"(= **PROTO_OPEN**) — 을 동시에 만족하는 유일한 옵션이 Opt E이며, 이미 존재하는 admin 코드 제국·ADMIN-gated 에이전트 경로·madasin 검증 자산을 건드리지 않는 최소 diff다.

---

## "admin 없다"의 정의 (이 권고에서)

- **있음(유지):** `/admin/**` 라우트, `AdminTokenGuard*` 이중 가드, `hasRole("ADMIN")` 매처, Admin* Java — 전부 존재하고 proto-open 하에서 **열림**. 직접 URL 입력도 열린다 (의도된 동작, `docs/PROTOTYPE_AUTH.md` §"의도적으로 받아들이는 위험").
- **없음(숨김):** vibe 경로(`/chat`, `/`)에서 **보이는 admin 표면**만 제거 — 운영 도구 드롭다운, 운영자 로그인 링크, 진단 내비 링크.
- **N/A(격하):** "로그인 성공 / 로그아웃 후 차단"은 vibe Done 조건·회귀 검증 항목에서 제외. proto-open에서는 가드가 열어두는 것이 **정책상 정상**이며 버그도 미충족도 아니다.

즉 "없다" = **보이지 않고 요구되지 않는다**이지 "서버가 404"가 아니다.

## 옵션 비교

| 옵션 | 정의 | 바이브 마찰 | 회귀 위험 | 공개배포 위험 | 판정 |
|---|---|---|---|---|---|
| **Opt A — 그냥 열어두기** | 현행 유지 (proto-open + admin UI 노출) | 없음. SSOT와 이미 일치 | 없음 | 중 (UI에 admin 흔적이 보여 오해 유발; 하드스톱으로 완화) | 부분 채택 (E의 바닥) |
| **Opt B — UI만 숨김** | admin 크롬 숨김, proto-open·API 유지 | 없음 | 낮음 (템플릿 컨테이너에 `hidden`만; 아래 테스트 주의) | Opt A와 동일 | 부분 채택 (E의 표면) |
| **Opt C — 라우트 비활성** | `/admin/**` 404/리다이렉트 (프로필 조건부) | **높음** — ADMIN-gated 표를 잘못 넓히면 에이전트 경로까지 죽임 | **높음** — madasin do05가 `/admin/trace-snapshots` 500→200으로 고친 자산을 404로 되돌림; `/agent/db-context`,`/api/diagnostics`,`/api/settings` POST 인접 게이트와 충돌 | 오히려 혼란 증가 | **기각** (vibe 한정) |
| **Opt D — harden** | 로그인 필수·로그아웃 차단·fail-closed | 최악 — 데모 재차단 | 프로토타입 정책 위반 | 낮음(보안상)이나 목적 상실 | **기각** — 사용자가 "harden" 명시 전 채택 금지 (kickoff + AGENTS 일치) |
| **Opt E — A+B 하이브리드** | proto-open 유지 + admin 크롬 숨김 + 인증 종료조건 N/A화 | 없음 | 낮음 | 중 (문서/체크리스트로 완화) | **채택 (THE ONE)** |

### 반례 검토 (부정 심판 결과)
- "proto-open이면 admin이 열려 있는데 왜 숨기나?" → 숨김은 보안이 아니라 **DX(혼란 제거)** 목적. 보안 가짜 신호(닫힌 척)를 만들지 않기 위해 문서에 "URL 직접 접근은 여전히 열림"을 명시한다.
- "숨기면 나중에 harden할 때 되돌려야 하지 않나?" → `hidden`은 컨테이너 수준 3줄 추가이며 가드와 무관. harden 시에도 크롬 숨김은 무해 (또는 같은 diff로 되돌림 가능).
- "madasin이 로그아웃 차단을 '불충족'으로 보고하라는데 충돌 아닌가?" → madasin C4는 **proto-open을 끄지 말라 + 차단 PASS 연출 금지**가 본질. Abandon 종료조항상의 "불충족" 기록은 그 프레임의 잔재이고, vibe 검증에서는 아예 항목을 두지 않는 것(N/A)이 같은 정책의 더 정직한 표현. `logout-block`을 PASS 조건으로 **복원하는 것과 반대 방향**이므로 kickoff 금지와 일치.

## 에이전트 경로 (ADMIN-gated) 영향 표 — Opt C를 기각하는 근거

`AppSecurityConfig.java:142-179` 기준 ADMIN 매처와 실제 소비자 (사실 = 이 체크아웃에서 확인):

| 경로 | 소비자 / 근거 | Opt C(비활성) 시 |
|---|---|---|
| `/admin/**` (dashboard, brain-state, pipeline-status, rag-ops-cockpit, vector-diagnostics, trace-snapshots, debug-events, learning-data) | 운영 콘솔 템플릿 8종 (`templates/*.html` 상호 링크), madasin do05 trace-snapshots 뷰 | 진단 콘솔 전멸 + do05 회귀 |
| `/agent/db-context/**` | `chat.js:3` `PIPELINE_HEALTH_API`(상태 레일 heartbeat), Vibe-Max-Agency 에이전트 읽기 경로, `agent.db-context.enabled` 플래그와 별개로 ADMIN 게이트 | 상태 레일·에이전트 디버그 레인 사망 |
| `/api/diagnostics/**` (GET+POST) | 진단 API + `AdminTokenGuardFilter.isDiagnosticRequest` | 채팅/진단 경로 붕괴 |
| `/api/settings` POST | 설정 저장(렌즈 설정 포함) 경로 | **Fold `lensSettings` 저장 계열 위험** — 절대 건드리지 않음 |
| `/internal/**`, `/api/internal/**`, `/v1/tasks`, `/flows`, `/api/router/**`, `/api/agent/report`, `/api/admin/**`, 운영 POST(`/api/rag/probe`,`/api/nova/outbox`,`/api/train`,`/api/translate/train-now`,`/webhooks/channel`,`/messages/trigger`) | 에이전트/ops 레인 (`PROTOTYPE_AUTH.md` 위험 목록과 동일) | 에이전트 작업 표면 축소, 무관 회귀 |
| `/model-settings/**` | chat-ui 운영 도구 메뉴 | 메뉴 링크 사망 |

결론: Opt C는 "admin이 없는" 체감을 주는 대가로 **인증이 아니라 진단/에이전트 인프라**를 끊는다. `/admin/**`만 좁게 404시켜도 이미 검증된 trace-snapshots 뷰를 깬다.

## 최소 패치 화이트리스트 (Opt B/E의 UI 숨김만, ≤5 파일, 구현자용)

원칙: **컨테이너에 `hidden`만 추가**, 내부 `<a>` 마크업은 byte-동일 유지 — `ChatFrontendSecurityTest`/`ChatUiOperatorNavContractTest`가 `Files.readString`으로 `data-menu-action`/`data-admin-surface` 속성 문자열을 고정 검증하므로 텍스트 유지가 합약상 안전 (추정 — 구현 후 아래 테스트로 확정).

| # | 파일 | 변경 | 필수? |
|---|---|---|---|
| 1 | `main/resources/templates/chat-ui.html` | `<details class="admin-tools">`(:27), `<a class="sign-in-link">`(:37), `<nav class="diagnostics-nav">`(:213)에 `hidden` 추가 (3줄) | **필수** — `/chat` vibe 경로의 admin 크롬 |
| 2 | `main/resources/templates/index.html` | Development Ops Center 링크카드 블록(:150,:165,:226-238) `hidden` | 선택 — `demo.interview.enabled=false`면 `/`→`/chat`라 랜딩 아님 |
| 3 | `main/resources/templates/fragments/header.html` + `fragments/layout.html` | Logout 링크 `hidden` | 선택 — 레거시 페이지용 |
| — | `main/resources/static/js/chat.js` | **변경 없음** — `protectedSurface` 차단(`admin_sign_in_required`)은 숨김과 무관하게 유지 | 해당 없음 |
| — | SecurityConfig/AdminTokenGuard*/Admin* Java | **변경 없음** | 해당 없음 |

구현 후 집중 검증: `ChatFrontendSecurityTest`, `ChatUiOperatorNavContractTest`, `ChatUiViewConfigTest` + `/chat` 로드 시 admin 링크 부재 DOM 확인. **"로그아웃 후 차단"을 목표로 테스트하지 말 것.**

## 참고한 근거 (사실 = 이 체크아웃 확인 / 추정 = 미실측)

- 사실: `application-meta-display.yml:140` `demo.auth.proto-open: ${DEMO_AUTH_PROTO_OPEN:true}` (local,meta-display 프로파일), 코드 기본값 `false` (`AdminTokenGuardInterceptor.java:87`).
- 사실: proto-open이면 `AdminTokenGuardInterceptor.preHandle` 즉시 통과(:96-98), `AdminTokenGuardFilter`가 전 경로에 `ROLE_ADMIN` 설치(:32-34, :87-93).
- 사실: `chat-ui.html` 운영 도구 메뉴 5링크는 `data-admin-surface="protected"`이며 `chat.js:6994-7020`이 클릭을 이미 차단(`admin_sign_in_required` 표시, 미이동). 즉 **/chat의 admin 메뉴는 지금도 시각만 있고 동작은 막혀 있음** — 숨김은 실질 변화가 아니라 표면 정리.
- 사실: madasin `CODEX_CONTINUE.md` C4 = proto-open 유지, 로그아웃 후 admin 200은 불충족 보고, fail-closed 금지.
- 사실: `docs/PROTOTYPE_AUTH.md`가 열린 위험 목록과 harden TODO를 이미 문서화.
- 추정: `hidden` 추가만으로는 기존 합약 테스트가 깨지지 않는다 — 구현자가 위 3개 테스트로 확정할 것.

## Codex/Clean에게 넘길 문장 1개

> **"proto-open 하에서 '로그아웃 후 admin 접근 차단'은 PASS도 FAIL도 아닌 `N/A-proto-open`이다 — vibe 검증 항목에서 제외하고, madasin에 logout-block을 성공조건으로 복원하지 말 것."**

---

## 2026-09-27 ADDENDUM 반영 — 적용 상태 + Clean 핸드오프 초안

적용됨 (Devin, `devin-vibe-admin-addendum-0927-7eab3723`):

- `docs/superpowers/specs/2026-09-04-decision-first-evidence-console-design.md` — "authentication acceptance"/"logout-block" unmet 구절에 proto-open `N/A` 노트 추가 (harden 금지 명시).
- `main/resources/templates/chat-ui.html` — `운영 도구` summary → `고급 도구 (선택)`, `sign-in-link`·`diagnostics-nav`에 `hidden`. `<details>` 본체는 geometry mobile 계약(`adminMenuRect`) 때문에 숨기지 않고 문구 완화로 대체.
- `main/resources/templates/index.html` — nav 주석 + Operations Links 최상단에 `/chat` link-card (디버그 1순위 = `/chat` + Read-RAG-Debug).
- 테스트 3종(클래스 상단 주석만, 기대값 0 변경): `AdminTokenForceHttpsSecurityIntegrationTest`, `AdminTokenGuardInterceptorTest`, `TraceSnapshotsDiagnosticsSecurityIntegrationTest` — harden-path/`proto-open=false` 전용임을 명시.
- `.devin/PROMPTS/chat-ux-3layer-3device-20260923.md` — Layer3 ADMIN/diagnostics harden 권고에 `OUT OF SCOPE / PROTO_OPEN` 배너.
- `.agents/skills/demo1-meta-display-db-export/SKILL.md` — proto-open에서 토큰 없이 200 가능, 403을 무조건 막힘으로 고치지 말 것.

Clean(`clean-vibe-low-admin-guardrail-20260927`)에게 넘기는 초안 (Devin은 적용하지 않음):

- AGENTS.md `DEMO1-PROTOTYPE-AUTH-LIGHT` 불릿 초안: "바이브 Done/PASS ≠ admin 로그인 성공 ≠ 로그아웃 후 `/admin` 차단; proto-open에서 admin/diagnostics 200 = 정상이며 'unmet logout-block'을 결함으로 승격 금지; harden 명시 전 추가 AdminToken·fail-closed matcher·CSRF-off 금지; 운영 디버그 1순위 = Read-RAG-Debug / var/rag-launcher/LATEST.json".
- 옛 지시 상단 스탬프 (본문 rewrite 금지, `SUPERSEDED for vibe: see DEMO1-PROTOTYPE-AUTH-LIGHT` 한 줄):
  - `agent-prompts/gpt_pro_demo1_source_patch_directives_50_20260823.md` (:203 RED anonymous 401/403)
  - `agent-prompts/agents/demo1_agent_tools_library_patch_9h/system_ko.md` (:256 ATL-03 — missing admin token 을 P0로 분류)
  - UAW 계열 fail-closed token 구간 (`UAW.txt` 해당 절 — Clean addendum §P1-2 목록 기준)
- 선택 지연(P2): `AppSecurityConfigContractTest` hasRole 소스 고정, `*FailsClosedWhenAdminToken*` — 동일한 harden-path 주석 패턴 적용 가능하나 이번 diff에서 제외.

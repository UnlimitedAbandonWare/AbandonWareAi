# DEVIN_VERIFY — settings-routing v3 (interim, WAIT_CODEX)

작성: 2026-10-02 (KST) / Devin assist lease `devin-settings-assist-3ac8ae5f`
대상: Codex `codex-settings-page-routing-v3-20261002` (/settings 페이지 + 여섯 역할 라우팅)

## 1) 외부/실행 오류와 원인

- 08:30경 로컬 18180·kro.kr 모두 무응답(연결 거부/502). ~08:45 서버 복구 —
  실행 중인 JVM이 Codex 최신 클래스 이전 빌드: `/settings` → HTTP 500,
  `/api/settings/routing/read|preview` → 404. 서버 재기동은 Codex 몫(HOLD),
  Devin은 사실만 기록.
- PowerShell `>` 리다이렉트가 UTF-16을 쓰는 환경 — JSON 증거는 `Out-File
  -Encoding utf8`로 재저장. 도구 자체 오류 아님.

## 2) 판정: PARTIAL (WAIT_CODEX)

Codex 진행 중 — Phase A 파일(08:20 생성)·Phase B WP-B1 파일이 작업트리에
존재하고 Codex 보고서 없음. Acceptance 중 Codex 완료 의존 항목은 NOT_RUN.

| WP | 상태 | 근거 |
|---|---|---|
| WP1 baseline | DONE | `var/settings-guard/baseline-20261002-0821.json` (2,103,281 B) |
| WP2 guard | DONE | `--check --scan-codex` → verdict PASS (G1~G8) |
| WP3 matrix | DONE | 7/16 클래스, 1/3 노드, specs covered 8/96 (V3-01~07, T15) |
| WP4 probe | DONE(부분) | 실행됨; SETTINGS_MISSING(서버 빌드 구버전) |
| WP5 tests+룰+스킬 | DONE | pytest 21건 PASS; 룰·스킬·SSOT 포인터 기록 |
| WP6 Codex 검증 | WAIT_CODEX | Codex 보고서·완료 선언 없음; 재검증 예약 |

## 3) Acceptance D1~D8

- **D1 PASS** — baseline JSON 생성. 8개 M 파일 sha256+hunk 기록:
  chat-ui.html(19+/2-), PolicyBasedModelRouter(57/8), ChatWorkflow(218/33),
  ChatStreamSignalBuilder(16/3), ChatRunRegistry(125/28),
  DynamicChatModelFactory(82/4), LlmRouterAspect(70/25),
  ChatApiController(289/92). `baselineAfterCodexStart=true` (Codex Phase A
  파일 5개가 08:20:41에 이미 생성돼 있었음 — 숨기지 않고 명시).
  forbidden 지문 91개, 전체 diffIndex(추가/삭제 멀티셋), porcelain 스냅,
  `/api/settings` 키 집합 {FINE_TUNED_MODEL, FREQUENCY_PENALTY,
  OPENAI_MODEL, PRESENCE_PENALTY, TEMPERATURE, TOP_P}.
- **D2 PASS** — `settings_routing_guard.py --check --scan-codex`:
  G1 PASS(chat.js 104+/12- 기준선 그대로 — 기준선 자체가 비어있지 않음을
  명시), G2 PASS(chat-ui 신규 추가 0줄; Codex 2줄은 기준선 안),
  G3 PASS(7개 M 파일 외부 hunk 보존), G4 PASS(금지 파일 91개 불변),
  G5 PASS(`application.properties:976 chat.settings.routing.enabled=false`
  — 명시적 false), G6 PASS(허용 매핑만: `/settings`,
  `/api/settings/routing/{read,preview,save}`), G7 PASS, G8 PASS.
- **D3 PASS** — `settings_test_matrix_check.py` 표 생성:
  존재 클래스 7(SettingsCapabilityProjection 4, SettingsPlanProjection 4,
  SettingsPageController 1, RoutingSettingsController 3,
  RoutingSettingsPersistence 4, RoutingRunSnapshot 4,
  SettingsSnapshotCompatibility 5 @Test), 노드 1(settings-core 8 test).
  build/test-results XML 읽기 포함(SettingsCapabilityProjectionTest 직전
  실행 4t/4f — Codex 진행 중 상태일 수 있음, 재실행은 Codex 몫).
- **D4 NOT_RUN(부분 실행)** — 프로브 실행됨: local /chat 200 MAIN_OK
  "AbandonWare AI"(/settings 링크·bridge 스크립트 포함 여부는 chat 파트에
  기록), /settings 500, /api/settings 200(키 집합 drift 없음),
  kro.kr /chat 200 MAIN_OK·/settings 500(동일 백엔드 일치). routing
  read/preview POST 각 1회 → 404(서버 빌드 구버전). save 미호출.
  최종 verdict: **SETTINGS_MISSING** — Codex가 서버 재기동·빌드 후 재실행
  필요(서버 재기동은 Devin HOLD 영역).
- **D5 PASS** — `pytest scripts/test_settings_routing_guard.py
  test_settings_test_matrix_check.py test_settings_page_probe.py`
  → 21 passed (PASS·FAIL 양쪽 커버, 전부 fixture/fake, 네트워크 0).
- **D6 PASS** — `.windsurf/rules/demo1-settings-routing-guard.md`(2,270 B),
  `.agents/skills/demo1-settings-routing-assist/SKILL.md`(3,024 B),
  `.devin/RULES_SSOT.md` 포인터 1줄 추가.
- **D7 PASS** — `git status --porcelain` 대상 스코프 확인: Devin 신규
  `??` = scripts/settings_*.py 3 + scripts/test_settings_*.py 3 +
  .windsurf 룰 + .agents 스킬 dir + var/settings-guard 산출물 +
  docs/diagnostics 본 폴더; `M` = .devin/RULES_SSOT.md(포인터 1줄)뿐.
  `main/` 아래 Devin 변경 0 (모든 `M main/*`·`??` main/* 은 기준선에
  이미 있던 다른 세션·Codex 작업).
- **D8 DONE(WAIT_CODEX)** — 본 문서. Codex 보고서/완료 후
  `settings_routing_guard.py --check --scan-codex` →
  `settings_test_matrix_check.py` → `settings_page_probe.py` 순으로 재실행해
  최종 검증으로 갱신한다.

## 4) 가드 FAIL 항목 → Codex 수정 요청

없음 (현 시점 G1~G8 전부 PASS). 참고 사실 2건 — FAIL 아님:

- 기준선 자체에서 chat.js·chat-ui.html·ChatOpenSecurityConfig·
  interview/index.html 등이 이미 `M`(다른 세션 hunk). G1/G4는 "기준선 이후
  drift 0"을 PASS로 판정 — "HEAD 대비 clean"은 이 가드의 판정 범위가 아님.
- `/settings` 500·routing POST 404는 실행 중 JVM이 최신 빌드가 아니라는
  신호(코드 리뷰 이슈로 단정하지 않음).

## 5) 만든 파일 목록과 크기

| 파일 | 크기 |
|---|---|
| scripts/settings_routing_guard.py | 26,157 B |
| scripts/settings_test_matrix_check.py | 10,610 B |
| scripts/settings_page_probe.py | 12,537 B |
| scripts/test_settings_routing_guard.py | 10,951 B |
| scripts/test_settings_test_matrix_check.py | 4,230 B |
| scripts/test_settings_page_probe.py | 6,425 B |
| .windsurf/rules/demo1-settings-routing-guard.md | 2,270 B |
| .agents/skills/demo1-settings-routing-assist/SKILL.md | 3,024 B |
| .devin/RULES_SSOT.md | +1줄 |
| var/settings-guard/baseline-20261002-0821.json | 2,103,281 B |
| var/settings-guard/check-latest.json, matrix-latest.json, probe-latest.json | 증거 JSON |

## 남은 일 / 다음 한 걸음

Codex 완료 선언 → `--check --scan-codex`(G1~G8 재판정) → matrix 재스캔
→ probe 재실행(서버 재기동 후 SETTINGS_OK 기대) → 본 문서 최종판 갱신.

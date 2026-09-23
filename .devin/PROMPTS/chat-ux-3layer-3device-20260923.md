# `/chat` 화면 UX 3계층 × 3디바이스 재구성 설계/감사 보고서 (Devin 분석 결과, 소스수정 요청)

## 0. 분석 근거 및 한계 (검증 상태)

| 항목 | 상태 |
|---|---|
| 백엔드 소스 (`PageController`, `ChatApiController`, 보안 설정, 진단 컨트롤러) | worktree에서 직접 확인 (active sourceSet = `main/java`, `main/resources` — `build.gradle.kts:92-100` 확인) |
| 프론트엔드 소스 (`chat-ui.html`, `chat.js`, CSS) | Desktop canonical root `main/resources/...`에서 읽기 전용 확인 |
| ⚠️ 위 UI 파일들은 git untracked 상태 (분석 worktree 기준) | `git ls-files`에 `main/resources/templates/*`, `static/js/*`, `static/css/*` 미존재 주장 — canonical root에서 재확인 필요 |
| Ray-Ban Meta Display 스펙 | 외부 문서 기반. Wearables Device Access Toolkit 가용 범위 미검증 → `evidence_needed` |
| `java/com/example/lms/**` (루트 사본 트리) | 비활성/중복 트리 판단. `main/java`만 기준 |

## 0-1. 교차검증 결과 (2026-09-23, `report-crosscheck-diag-touchpoints-0b3d83b1`)

본 brief의 "주장된 결함 3건"을 live source와 대조한 결과:

| 결함 주장 | 09-23 판정 | 근거 |
|---|---|---|
| ① `GET /api/chat/ui-heartbeat` 부재 | **stale — 이미 구현됨** | `main/java/com/example/lms/web/ChatUiHeartbeatController.java`가 `/api/chat/ui-heartbeat` 매핑 보유, `src/test/java/com/example/lms/web/ChatUiHeartbeatControllerTest.java` 존재. 비관리자 영구 WARN 시나리오는 현재 소스 기준 성립하지 않음 |
| ② cytoscape dead weight | **사실 — 단 활성 lease로 타 task 범위** | `chat-ui.html`이 cytoscape 3.33.4 CDN을 로드하나 사용처 없음 확인. 단 `chat-ui.html`+`BrainStateFrontendContractTest`(`cytoscape` 존재 assert)가 활성 lease `chat-ux-layered-surfaces` 하 → 제거+테스트 동반 수정은 해당 owner 작업에 위임 |
| ③ `GET /api/diagnostics/**` permitAll | **stale — 이미 ADMIN 게이트** | `AppSecurityConfig`에서 `GET /api/diagnostics/**` → ADMIN, `POST` → ADMIN. 비인가 읽기 경로 현재 없음 |

§4~§9의 3계층 설계/우선순위는 여전히 설계 제안으로 유효하나, 실행은 `chat-ux-layered-surfaces` owner의 범위다. §8 표의 순서 0(git 추적)은 canonical tree 상태 재확인 필요 — 본 task에서 미검증.

## 1. 현재 `/chat` 구조 (실측 주장 — live tree에서 재검증 필요)

**서버 사이드**
- `GET /chat`, `/chat-ui` → `PageController.chatUi()` (`main/java/com/example/lms/web/PageController.java:295`) → Thymeleaf `chat-ui.html` 렌더. 모델 속성: `models`, `currentModel`, `username`, CSRF 메타.
- 채팅 API: `ChatApiController` (`@RequestMapping("/api/chat")`)
  - `POST /api/chat/stream` — SSE, `attach` 재접속/리플레이, `X-Session-Id`, `Idempotency-Key`, `X-Budget-Ms`
  - `POST /api/chat`, `POST /sync`, `POST /cancel`, `POST /ack`, `GET /state`, `GET/DELETE /sessions[/{id}]`
  - `ChatApiControllerExtra`가 `/api/chat-extra/sessions`로 유사 세션 API 중복 노출
- 진단 API: `DebugEventsDiagnosticsController` (`/api/diagnostics/debug/events`, `/events/stream` SSE), `SseTelemetryDiagnosticsController`, 외 다수
- 보안 (`AppSecurityConfig`): `/admin/**` = ADMIN + `AdminTokenGuard`; `GET /api/diagnostics/**` = permitAll, POST = ADMIN; `GET /api/settings` permitAll / POST ADMIN; `/agent/db-context/**` = ADMIN; `/api/chat/**` CSRF 예외

**프론트엔드**
- `chat-ui.html` (299라인) 한 페이지에: 헤더(Admin tools 메뉴), 오케스트레이션 배지, `<details class="response-settings">`(Model/Search/RAG), 상태 레일 7 pill + 6단계 Decision ribbon, 세션 이력+모드 진단 리스트, `<details class="diagnostics-disclosure">`(런타임 툴킷 + 하트비트 카드 ~40개 + 디버그 매트릭스/콕핏/미션/플로우/프루프 5레일 + brain-state 패널), 채팅 트랜스크립트+컴포저+빈 상태 퀵 프롬프트, 정적 어사이드.
- `chat.js` 6,250라인/295KB 단일 파일: 세션 CRUD·복원, 커스텀 SSE 파서(128KB 상한), 스트림 렌더, 취소/ack, 설정 저장, 30초 진단 폴링, 진단 코드→라벨 매핑, 이미지 플러그인 폴링.
- 보조 JS: `orchestration-ui.js`, `brain-state-ui.js`, `evidence-ui.js`, `fetch-wrapper.js`. `model-strategy.js`는 chat 페이지에 미로드.
- CSS: `chat-style.css` 1,735라인, `@media (max-width:760px)` 등 3블록. 디바이스 감지 로직 없음.

**주장된 결함 3건** — 09-23 교차검증 판정은 §0-1 표 참조
1. `GET /api/chat/ui-heartbeat` 백엔드 매핑 부재 — `chat.js:5159`가 30초마다 폴링, 실패 시 `/agent/db-context/pipeline-health`(ADMIN 전용) 폴백 → 비관리자에게 진단 카드 영구 WARN → **stale**(`ChatUiHeartbeatController` 존재)
2. cytoscape CDN을 chat 페이지에서 항상 로드(`chat-ui.html:13`)하나 사용처 0건 → dead weight → **사실, 단 활성 lease `chat-ux-layered-surfaces` 소유**
3. `GET /api/diagnostics/**` permitAll — 진단 데이터 비인가 읽기 가능 → **stale**(현재 ADMIN 게이트)

## 2. UX/디자인 문제점

1. 한 화면 3역할 혼재(대화/설정/개발자 진단), 트랜스크립트가 DOM 순서상 최하단
2. 죽은 진단 UI가 장애처럼 보임(비관리자 ~40개 카드 영구 WARN)
3. 초기 로드 비용(295KB JS + 42KB CSS + cytoscape)
4. 언어/용어 혼재(내부 코드명 DPP/CFVM/QTX/Noether/PatchDrop 노출)
5. 설정 개념 파편화(`/chat` 3개 컨트롤 vs `/model-settings`)
6. 소형 디스플레이 대응 전무(600×600 HUD급 불가)
7. 버전관리 밖 UI(분석 worktree 기준, canonical 재확인 필요)
8. `PageController.java` 주석 인코딩 깨짐

## 3. 기능/진단 혼재 지점

| 혼재 지점 | 위치(주장) | 성격 |
|---|---|---|
| 진단 disclosure가 채팅 섹션 내부 | `chat-ui.html:127-282` | 개발자 전용 |
| 하트비트/매트릭스/콕핏/미션/플로우/프루프 6레일 | `chat-ui.html:134-273` | 진단 |
| brain-state 패널 | `chat-ui.html:275-280` | 진단 |
| 오케스트레이션 배지+상태 레일+decision ribbon | `chat-ui.html:44-124` | 경계선 |
| 세션 이력+mode diagnostics 한 리스트 | `chat-ui.html:125` + `chat.js` | 혼재 |
| 모델 선택 `response-settings`와 `/model-settings` 양쪽 | 템플릿+`PageController:306` | 설정 파편 |
| 스트림 메타 이벤트가 답변 렌더+디버그 카드 동시 갱신 | `chat.js` `setDebugHeartbeatField` | 프레젠테이션 결합 |

## 4. 화면/역할 분리 계획 (3계층) — 백엔드 RAG/SSE/세션 공유, 표현만 분리

- **Layer 1 Basic Chat**: 트랜스크립트/컴포저/퀵 프롬프트/새 채팅/모델명 1개/최소 상태(Route·Quality·Health 3 pill). diagnostics disclosure·디버그 레일·brain-state·cytoscape 제거. 기본값.
- **Layer 2 Settings**: `response-settings`+세션 이력을 설정 패널로. 모델 상세는 `/model-settings` 링크(중복 UI 금지). `/api/settings`·세션 API 재사용.
- **Layer 3 Developer Diagnostics**: diagnostics disclosure+디버그 레일+brain-state+런타임 툴킷을 ADMIN 서피스로 이동(`/admin/*` 병합 또는 `/chat?view=diagnostics` ADMIN 게이트). 진단 JS는 dynamic import로 ADMIN 판정 후 로드.
- 구현: `templates/fragments/` 활용 Thymeleaf fragment 분리 + 서버측 role 모델 속성.

## 5. 실시간 디버깅/관측성 개선

1. `/api/chat/ui-heartbeat` 해결(최우선): (A) read-only 집계 스냅샷 엔드포인트 신설(기존 store 집계, redaction 준수, ADMIN 전용) 권장, (B) 프론트 폴링을 `/api/diagnostics/debug/events`+`pipeline-health` 조합으로 재지정
2. 폴링→SSE 전환: `/api/diagnostics/debug/events/stream` 기존 SSE 구독(인프라 추가 0)
3. 진단 API 접근 강화: `GET /api/diagnostics/**` permitAll → 인증/ADMIN 검토(계약 테스트 `AppSecurityConfigContractTest` 동반 수정)
4. 스트림 중 라이브 갱신(`setDebugHeartbeatField`)은 Layer 3에서만 구독

## 6. Ray-Ban Meta Display / 웹 / 관리자 분리

- 디바이스 사실: 600×600 LCOS HUD, 20° FoV, Neural Band+음성+터치패드, 폰 컴패니언 필수. 서드파티 온글래스 앱은 Wearables Device Access Toolkit 경유 — 가용 범위 미검증(`evidence_needed`).
- 서피스 프로필: `?surface=compact`(글래스/폰: 답변 본문+신뢰도 1줄+증거 1개, 진단/설정/세션 목록 없음, 음성→`POST /api/chat` 재사용), `surface=web`(기본, Layer1+2), `surface=admin`(role 게이트, Layer3+`/admin/*`).
- 구현: `PageController.chatUi`에 surface 파라미터 추가, fragment 세트만 교체. 신규 컨트롤러/엔진 없음.

## 7. 재사용 가능 코드

- `fetch-wrapper.js`, `orchestration-ui.js`(Layer1 pill), `evidence-ui.js`(compact 증거 1개), `brain-state-ui.js`의 `safe()` 마스킹
- `chat.js` 내부: SSE 파서, `apiCall`, 세션 복원/attach, 멱등 헤더 — 공용 모듈로
- 백엔드: `ChatStreamEvent`, `ChatSessionAccessGuard`, `ChatRunRegistry`, `SafeRedactor`, 기존 진단 컨트롤러
- 금지: RAG 엔진/`PromptBuilder` 복제

## 8. 변경 최소 파일 + 범위

| 순서 | 파일 | 변경 범위 |
|---|---|---|
| 0 | (선행) untracked UI 파일 git 추적 | 신규 add만 — canonical tree git 상태 재확인 필요 |
| 1 | ~~`main/java/.../api/` 신규 `ChatUiHeartbeatController`~~ | 결함 1·3 모두 stale — 불필요 |
| 2 | `templates/chat-ui.html` → `fragments/chat-basic.html`/`chat-settings.html`/`chat-diagnostics.html` 분리 + cytoscape 제거 | 템플릿 재배치 |
| 3 | `static/js/chat.js` → `chat-core.js`/`chat-settings.js`/`chat-diagnostics.js` 분리(dynamic import), 버전 쿼리스트링 갱신 | 대형 — 최대 리스크, 별도 단계 |
| 4 | `PageController.chatUi` surface/role 모델 속성 | ~20라인 |
| 5 | `chat-style.css`에 `surface-compact` 블록 추가 | 추가만 |
| 6 | 계약 테스트 동반 수정: `ChatPageContractTest`, `ChatPageStaticContractTest`, `ChatUiBaseline*`, `AppSecurityConfigContractTest`, `ChatRecoverySafePatchContractTest` | 테스트 |

범위 밖: RAG 파이프라인, 검색 provider, 세션 저장소, `/api/chat/stream` 프로토콜, LangChain4j 버전, 신규 프레임워크.

## 9. 우선순위

- **P0**: ① UI 소스 git 추적(canonical 재확인 후), ~~② ui-heartbeat 엔드포인트~~(stale — 이미 존재), ~~③ diagnostics GET 보안 강화~~(stale — 이미 ADMIN), ④ cytoscape 제거(`chat-ux-layered-surfaces` owner 작업)
- **P1**: Thymeleaf fragment 3계층 분리 + ADMIN 게이트
- **P2**: chat.js 모듈 분리, `/api/chat-extra` 중복 정리 검토
- **P3**: `surface=compact` 프로필 + 글래스 플로우(Toolkit 확인 후)

## 10. 검증 방법

1. `gradlew.bat compileJava` + 포커스 테스트(`ChatPage*ContractTest`, `ChatUiBaseline*Test`, `AppSecurityConfigContractTest`, `ChatRecoverySafePatchContractTest`)
2. 런타임 스모크 `scripts\smoke_chat_debug_fx_sse.ps1`, `smoke_chat_debug_events_readback.ps1`; `GET /api/chat/ui-heartbeat` 200
3. 비관리자 `/chat` 디버그 DOM 미렌더 확인 / ADMIN 정상
4. `?surface=compact` 페이로드 크기·DOM 계층
5. 스트림 attach/재접속, cancel, 세션 복원 회귀

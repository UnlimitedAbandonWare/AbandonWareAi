# Codex 실행 지시서 — /chat 풀대기 제거 + 장애내성·RAG/웹서치 복구

- Date: 2026-09-28
- Session: Codex 소스수정 (live demo-1, Project Root `C:\AbandonWare\demo-1\demo-1\src`)
- Mode: 진단 확정 + 최소 패치 + 회귀 테스트 (항목당 "증거 → 재현 → 최소 수정 → 회귀 테스트 → 완료 조건")
- 전제 영상: `F:\CAM\2026-09-28 08-45-57.mkv` (3분36초, 1280×720, h264+aac,
  sha256 `23D1830AB57CE660E78F79835C1AD073654776C69CBCC1B34E59CEF5F78BBC23`)
- 선행 맥락: `docs/video-analysis-report-20260925-093149.md` (동일 증상 09-25 판, P3 미해결),
  `docs/codex/SESSION56_MEMORY_CONTEXT_FIX_DIRECTIVE_2026-09-25.md`

## Goal / Spec / Execution

- Goal: 사용자 질문이 (1) 대기 예산을 **끝까지 다 기다리다 실패**하지 않고,
  (2) 한도/시간 **초과가 발생해도 실행 상태·질문·부분 답변이 유지**되며 재접속으로 결과를 받고,
  (3) 실패 사유가 사용자에게 **정직하게** 표기되고,
  (4) RAG/웹서치가 설정된 프로바이더로 실제 동작하거나 비활성 사유를 보여주게 한다.
- Spec: 기존 심 재사용(`streamChat`/heartbeat/`chatFailureMeta`, `ChatRunRegistry.attachInteractiveExact`,
  `TimeBudget`, `PublicChatAdmissionGuard`, `ChatGenerationAdmissionFilter`, `ModelSelectionException`,
  `HybridWebSearchProvider`/`BraveSearchService`/`NaverSearchService`, `ChatUiCoreHeartbeatProbe`).
  신규 검색/큐 서브시스템 병렬 추가 금지.
- Execution: P1 → P2 → P3. 미발견 원인을 추정으로 확정 표기 금지(`사실`/`추정`/`근거 부족` 분리).

## 0. 확정 사실 vs 미확정

### 사실 (영상 프레임 + 소스로 검증됨)

| # | 사실 | 근거 |
|---|---|---|
| F1 | 영상 타임라인: 질문 "아이리 킨나가 뭐냐" 전송 → `model-pending` → `client-wait:30002`→`70003`→`120011`→`135007ms` → `message_failed` + `Stream rate_limited` + `Next wait_then_retry` + `Sync not_attempted`. 본문 "일정 시간이 요청 한도에 도달했습니다…". 답변 미도착 | 영상 프레임 t≈0/40/80/120/140/160/200/215s |
| F2 | 클라이언트 자체 데드라인 부재: `streamClientDeadlineMs()` → `return null`. `STREAM_STALE_WAIT_MS=60000` 경과 후에도 라벨만 `model_wait`/`next:stop_or_wait`로 바뀌고 계속 대기 | `main/resources/static/js/chat.js:277,6770-6772,6822-6864,3049-3064` |
| F3 | 서버 예산 캡 120s: `public.request-budget.max-time-budget-ms=120000`, `X-Budget-Ms`→`TimeBudget`; 클라 헤더 = meta `chat-request-budget-ms` 또는 90s(모델)/30s(웹)/120s(RAG) | `main/java/com/example/lms/api/PublicRequestBudgetGuard.java:78-79,214-218,351-378`, `chat.js:277-280,6774-6788,6874-6876` |
| F4 | 라우팅 룰이 풀대기 유지: `policy.chat_wait` — `caller_timeout_releases_capacity:false`, `primary_timeout_division_for_fallback:false`, cap SSOT는 application-llm.yaml | `configs/api-routing.yaml:10-15` |
| F5 | 종단 오분류: `ModelSelectionException.failure()`가 `RATE_LIMIT_COOLDOWN`→`"rate_limited"`로 매핑 → SSE `ChatStreamEvent.error` → chat.js `wait_then_retry`+"요청 한도" 문구. 진단상 선택 모델은 로컬 `smtek/Qwen3.8-27B:Q3_K_XL`이며 원격 429 관측 없음 | `main/java/com/example/lms/llm/ModelSelectionException.java:41-56`, `main/java/com/example/lms/api/ChatApiController.java:2969,2989`, `chat.js:456-457,475,530` |
| F6 | Sync 폴백 부재: 실패 레일 `Sync not_attempted`; `/api/chat/sync` 호출은 `assets/display/display-core.js:117`뿐. /chat UI는 스트림 단일 경로 | `chat.js:5748-5754`, `main/resources/static/assets/display/display-core.js:117` |
| F7 | 영구 멱등 fence: 클라이언트 중도 이탈/실패 → `Claim.markUnknown()`→`OUTCOME_UNKNOWN`,`expires_at=NULL`(만료 없음,"never retry inference"). 동일 Idempotency-Key 재제출 → 409 `idempotency_duplicate` 영구 | `main/java/com/example/lms/api/ChatGenerationAdmissionFilter.java:159-172,239-247`, 새 페이로드는 신규 키 `chat.js:6790-6801` |
| F8 | 웹서치 프로바이더 0: `gpt-search.brave` api-key 필드가 `${BRAVE_API_KEY:__MISSING__}` 센티널 → env 부재 시 비활성; heartbeat `webProviders` hybrid DISABLED + `supplemental=0` ↔ 진단 `providers:none` | `main/resources/application-llm.yaml:274-289`, `main/java/com/example/lms/web/ChatUiCoreHeartbeatProbe.java:177-193` |
| F9 | 웹서치 라우팅 순서: brave/naver=free_local → tavily/serpapi=low_cost | `configs/api-routing.yaml:66-83` |
| F10 | 영상에서 `RAG: 요청 OFF` — 요청 `useRag=false`; 검색 레인 `search:WARN`,`sources 0 recent 0 anchors 0`, `stream:none`, `Model route:unknown` | 영상 진단 패널 t≈120s |
| F11 | GPU0=RTX 3090 전용 메모리 21.5/24.0GB 점유, 후반 사용률 100% — 로컬 모델 레인 포화 정황 | 영상 작업 관리자 t≈120/200s |
| F12 | 동일 증상 재발: 09-25 영상도 ~60s client-wait(그때는 127s 후 폴백 답변); 이번엔 폴백 대신 실패 종료 | `docs/video-analysis-report-20260925-093149.md` §1.1, P3 |
| F13 | 폴백/실패도 `ChatSessionTraceRecorder`에 `outcome=completed,errorClass=none`으로 기록돼 왔음(관측성 약점) | `docs/video-analysis-report-20260925-093149.md` F9 (재확인 요망) |
| F14 | `run_active` 게이트: 비소유 세션 run → `ChatStreamEvent.error("run_active")` 즉시 종료 | `ChatApiController.java:1682-1685` |
| F15 | 세션당 동시 실행 상한: per-owner 2 / global 64, 초과 시 429 `chat_admission_exceeded`(→UI `concurrency_limited`, rate_limited와 다른 코드) | `main/java/com/example/lms/api/PublicChatAdmissionGuard.java:24-37,166-178`, `chat.js:445` |

### 미확정 (실행 에이전트가 값을 열어 확정)

- H1: 120~135s의 정확한 소비 지점 — `llm.error.*`/`llm.gateway.failure.*`/`llm.requestTimeline`/`llm.ollamaNative.*` 미열람.
  `scripts/chat_session_debug_export.py`로 영상 시각대(08:45–08:50 KST) run 레코드 확정. VRAM 경합 vs Ollama 엔드포인트 불능 구분 필수(GPU 란 증거 체인 규칙 적용 — 선택 모델 id vs 실제 타임아웃 모델 분리 기록).
- H2: `rate_limited` 발생 층 — (a) `FallbackAwareChatModel` 폴백 체인 최종 `RATE_LIMIT_COOLDOWN` 전파,
  (b) `NightmareBreaker` rate-limit open(`application.yml:279-283`), (c) `JevDecisionAdvisor` defer 등.
  어느 경로든 사용자 문구는 현재 "요청 한도"로 오표기된다(수정 대상은 분류 정직성).
- H3: 영상 오디오 트랙 미분석(ASR 미실행). 화면 증거만 사용.
- H4: 영상 요청이 웹서치를 실제 요청했는지(`useWebSearch`) 미확정 — `providers:none`은 레인/키 상태 표기이지 해당 요청의 실행 결과가 아닐 수 있음.
- H5: `web.search` 조건부 OFF 상태 — PROJECT_STATUS mdasain 행 "online activation pending user approval". 본 지시 = 활성화 승인으로 간주하되, free_local 우선·유료는 spend guard 하에서만.

## 1. 변경 금지 경계

- Git 전면 금지(읽기 포함). 시크릿 값·`.secrets/`·포트·네임스페이스·모델 allowlist·openssl 관련 불변.
- 어드미션 한도(`userCapacity/userPerMinute/ipCapacity/ipPerMinute`, per-owner 2)와 바디/토큰 상한 **완화로 "대기 제거"를 흉내내지 말 것** — 한도는 유지하고 대기·전달 경로를 고친다.
- `/api/chat/stream?attach`는 read-only 재생 — attach가 생성을 시작하게 하지 말 것.
- `lensSettings`/`display-ttl-ms` 등 렌즈 표시 주기 knobs와 `hint`/`focus` 필드 계약 불건드리기. 저장된 설정값을 YAML 기본값으로 조용히 클램프 금지.
- 동일 의미 요청을 서버에서 두 번 추론하지 말 것(멱등 의미 보존). `awx_chat_requests` 스키마 변경은 최소·기존 컬럼 의미 유지.
- 유료 검색/LLM 호출은 `api-routing.yaml` tier 순서 + spend guard(`AWX_AGENT_ALLOW_PAID_MODELS=1` 없으면 무료 레인만).
- 진행 중 지시와 충돌 주의: `rate_limited` 오분류는 `agent-prompts/devin-debug-events-misclassify-fix-20260927`와 겹침 — Debug Events 분류는 그 지시서 범위를 따르고, 본 지시서는 **사용자 노출 경로(SSE 종단 코드 → UI 문구)** 만 고친다.

## 2. 우선순위

| 우선순위 | 항목 | 상태 |
|---|---|---|
| P1-A | 클라이언트 데드라인 구현 + 초과 시 "실패" 대신 "서버 지속·재접속 가능" 전환 | confirmed |
| P1-B | 종단 실패 분류 정직화(로컬 쿨다운/브레이커 ≠ "요청 한도") | confirmed |
| P1-C | `OUTCOME_UNKNOWN` 영구 fence 회수 경로 | confirmed |
| P1-D | TimeBudget 초과 시 run 유지 + attach 후속 배달 | confirmed |
| P2-A | 웹서치 프로바이더 활성화(env 감지·순서·disabledReason 노출) | confirmed(키 존재 여부는 라이브 확인 필요) |
| P2-B | RAG/검색 빈 결과 내성·사유 노출 | confirmed |
| P3-A | `policy.chat_wait` 룰·스펙 문서 정합 갱신 | confirmed |
| P3-B | 실패/폴백 outcome 관측성 정직화 | conditional — F13 재확인 후 |
| — | 모델 레인 포화 원인 자체 수정 | conditional — H1 확정 후 별도 지시; 본 지시 범위 아님 |

## 3. 수정 항목 상세

### P1-A. 클라이언트 대기 상한 + 초과 시 유지 전환 (F1,F2,F3)

- 증거: `streamClientDeadlineMs()` null(`chat.js:6770-6772`) → 서버가 끝낼 때까지 무제한 대기.
- 재현: 모델 미응답 조건에서 client-wait가 120s+로 상승 후 `message_failed`/`rate_limited` 종료(영상 재현).
- 최소 수정:
  1. `streamClientDeadlineMs` 실 구현 — `streamServerBudgetMs(payload)` + 소폭 마진(예: +10s, 단 3600000 cap 안)을 상한으로.
  2. 데드라인 도달 시 `cancelActiveStream`→`message_failed` 대신 **대기 전환 상태**로: SSE만 닫고 "서버에서 계속 생성 중" 표기 + runToken 있으면 `?attach=true` 재접속 예약(지수 백오프, 예산 잔여까지). runToken 없으면 기존 실패 경로.
  3. `STREAM_STALE_WAIT_MS` 경과 시점부터 라벨에 "남은 예산/재접속 예정" 표기 추가(기존 heartbeat 카운터 재사용, 새 폴링 루프 추가 금지).
- 통제 사례: (a) 60s 내 정상 응답 → 기존 경로 무변경. (b) 사용자 Stop → 여전히 즉시 `cancelled`. (c) attach=true 요청은 생성 미시작 유지.
- 회귀 테스트: `scripts/chat_ui_stream_contract_tests.js` 계열 — 데드라인 도달 시 `message_failed`가 아닌 재접속 대기 상태, attach 재생 성공 시 답변 렌더.
- 완료 조건: 영상 시나리오 재현 시 client-wait가 상한에서 멈추고 `message_failed` 대신 "생성 지속·재접속" 상태가 되며, 서버 완료 후 attach로 답변이 도착.

### P1-B. 종단 실패 분류 정직화 (F1,F5)

- 증거: `ModelSelectionException.java:41-56` — `RATE_LIMIT_COOLDOWN`→`rate_limited`→UI "요청 한도 도달"/`wait_then_retry`.
- 재현: 로컬 모델 쿨다운/브레이커 오픈 조건에서 SSE 종단 코드 `rate_limited` 관측(영상 F1).
- 최소 수정:
  1. HTTP 429/`chat_rate_limited`(`ChatGenerationAdmissionFilter` 발생분)만 `rate_limited`로 통과.
  2. 모델 레인 `RATE_LIMIT_COOLDOWN`(httpStatus=0/로컬 추정)은 별도 공개 코드(예: `provider_cooldown` 또는 기존 `model_circuit_open` 계열)로 분리하고 `chatFailureMessage` 문구 분리(예: "모델 호출이 잠시 제한되어 재시도 중"류 — "요청 한도" 문구는 실제 429에만).
  3. `SOFT_CIRCUIT_OPEN`/`TIMEOUT_SOFT`가 같은 "한도" 문구로 떨어지지 않는지 함께 확인.
- 회귀 테스트: `ModelSelectionException`/`chatFailureMeta` 단위 — 429→`rate_limited` 유지, 로컬 쿨다운→"요청 한도" 문구 미노출.
- 완료 조건: 로컬 쿨다운 종단에서 사용자 문구가 실제 사유 계열로 표시.

### P1-C. `OUTCOME_UNKNOWN` 영구 fence 회수 경로 (F7)

- 증거: `ChatGenerationAdmissionFilter.java:239-247` — 만료 없는 fence + "never retry inference"; `rejectExisting` 409 영구.
- 재현: 스트림 중도 종료 후 동일 Idempotency-Key 재제출 → 영구 `idempotency_duplicate`.
- 최소 수정(택1, 더 작은 쪽):
  1. `OUTCOME_UNKNOWN`에 TTL 부여(예: `RETENTION_MS`와 동일 창 또는 더 짧은 고정값) — 만료 후 동일키 재제출 허용.
  2. 동일키+fingerprint 일치 재제출 시, 연결된 run이 아직 살아 있으면 409 대신 attach/대기 안내 응답 — run 상태 조회는 기존 `ChatRunRegistry` 계약 사용, 신규 조회 서브시스템 금지.
- 주의: 완료된 결과(`COMPLETED`+result_json)의 멱등 재생 동작(:164-169)은 그대로 유지.
- 회귀 테스트: 중도 실패 후 동일키 재제출 → 영구 409가 아닌 (a) TTL 만료 후 정상 수용 또는 (b) attach 안내.
- 완료 조건: "초과/이탈해도 유지" — 사용자가 같은 질문을 다시 보내면 영구 거절 대신 기존 run에 붙거나 정상 재실행.

### P1-D. TimeBudget 초과 시 run 유지 + 후속 배달 (F3,F4)

- 증거: `TimeBudget` 소진 → 요청 종료 경로가 실패로 수렴(`PublicRequestBudgetGuard`/`ChatApiController` 종단). `caller_timeout_releases_capacity:false`는 "레인 유지"를 이미 선언하지만 클라이언트에는 결과 배달 경로가 없음.
- 최소 수정:
  1. 예산 소진 시점에 run을 kill하지 말고 백그라운드 지속(이미 runRegistry detach 내성 있음 — `resumePreserved` 추적 활용, `ChatApiController.java:3053-3064`) + 완료 결과 durable 저장.
  2. 클라이언트에는 종단 `budget_exceeded_resumable`(신규 공개 코드 후보) + "생성 계속됨·재접속하면 받을 수 있음" 안내 — P1-A의 attach 재접속이 수신 경로.
  3. `run_active` 반환 시에도 동일하게 attach 안내(F14 seam).
- 회귀 테스트: 예산 초과 후 동일 세션 attach → 완료된 답변 수신; kill되지 않음을 runRegistry 상태로 확인.
- 완료 조건: "초과돼도 유지" — 예산 초과 요청이 서버에서 완주하고, 재접속 시 결과가 도착.

### P2-A. 웹서치 프로바이더 활성화 (F8,F9,H4,H5)

- 증거: `application-llm.yaml:281` `__MISSING__` 센티널; `providers:none` ↔ heartbeat `hybrid DISABLED + supplemental=0`.
- 조사: 라이브 env에 `BRAVE_API_KEY_FREE`/`BRAVE_API_KEY`/`NAVER_*`/`TAVILY_API_KEY` 존재 여부 확인(값 출력 금지 — 존재 여부만). 없으면 프로바이더 비활성이 "사실상 의도된 상태"인지 여부를 기록하고 사용자에게 env 필요를 보고.
- 최소 수정:
  1. 키가 있는 프로바이더부터 `api-routing.yaml` tier 순서로 활성(free_local 우선).
  2. `web.search` 툴의 조건부 OFF를 이번 승인으로 해제 — 단 활성화는 env/플래그 경로로, 결제 티어는 spend guard 유지.
  3. `providers:none`/disabledReason이 UI 진단에 그대로 노출되는지 확인하고, 요청 시 프로바이더가 0이면 칩에 "키 없음·비활성" 사유 표기(heartbeat의 `disabledReason` 재사용).
- 회귀 테스트: 프로바이더 키 유/무 각각 — 있으면 검색 호출 시도+결과 카운트, 없으면 honest WARN 사유.
- 완료 조건: 웹서치 요청 시 `sources ≥1` 경로가 열리거나, 비활성이면 사유가 칩/trace에 명시.

### P2-B. RAG/검색 빈 결과 내성 (F10,F8)

- 증거: `sources 0`+`search:WARN` 상태에서도 답변 경로가 진행돼야 함(현재 종단 실패로 귀결된 정황). 검색 실패 사유 허용 목록 이미 존재(`ConversateApiCueService.java:307,348`).
- 최소 수정:
  1. useRag/useWebSearch 요청에서 검색 0건·실패 시 파이프라인이 종단 실패 대신 "검색 근거 없음" 명시 후 모델/폴백 경로 진행(degraded honest).
  2. `search:WARN`의 원인 분리 표기 — `core_beans_missing`(빈 부재) vs 키 부재 vs cooldown/rate-limit — heartbeat `providerStatusRow`의 `failureReason/errorClass` 재사용.
- 회귀 테스트: 검색 0건 주입 시 답변은 도착 + 칩이 `search 0·사유` 표기.
- 완료 조건: 검색 실패가 답변 실패로 전이되지 않음.

### P3-A. 룰·스펙 정합 갱신 (F4)

- `configs/api-routing.yaml:10-15` `policy.chat_wait`:
  - `caller_timeout_releases_capacity:false`의 의미를 "capacity 유지 + 결과 재접속 배달"로 명문화(값 유지 또는 `on_exceed: keep_resumable`류 주석/키 추가 — 스펙 구조는 `docs/API_ROUTING_SPEC.md`와 합의).
  - `primary_timeout_division_for_fallback:false` 재검토: primary 소진 전 폴백에 예산을 배분해 조기 저하 응답을 허용할지 — 채택 시 스펙 문서 동기 갱신 필수.
  - mutable-spec-policy 준수: 값은 SSOT 재확인 후 변경, 문서·YAML 동시 갱신.
- 완료 조건: YAML과 SPEC 문서가 같은 의미를 말하고, P1-D 구현이 이 룰을 어기지 않음.

### P3-B. 실패/폴백 outcome 관측성 (F13, conditional)

- `ChatSessionTraceRecorder`가 폴백/실패를 `completed`로 기록하는지 재확인 — 사실이면 outcome 분류(`fallback_delivered`/`failed_terminal` 등) 추가. 별도 파이프라인 금지, 기존 레코더 필드만.
- Debug FX가 "선택 모델"과 "실제 실패 모델"을 구분 표기(GPU 란 타임아웃 귀속 규칙).
- 완료 조건: 영상 같은 실패 재발 시 trace outcome이 `completed`로 위장되지 않음.

## 4. 검증·보고

- 영상 시각대 run 확정: `scripts/chat_session_debug_export.py`(chat-session-debug 스킬 절차)로 08:45–08:50 KST run 레코드 — H1/H2 확정에 필수. 선행.
- 각 항목: focused 단위/계약 테스트 → `gradlew compileJava` → 필요 시 Start-RAG ForceRestart 후 라이브 1건.
- 보고 표: mock / build / live boot / real API / browser 증거를 분리 기재. 실행 명령은 `run_verified_command.py`로 기록, 변경 파일은 sha256과 함께.
- 미실행 필수 확인은 `run=skipped|blocked|not_observed`로 보고하고 PASS 집계에서 제외.

## 5. 참고 — 관련 기존 지시서

- `agent-prompts/devin-debug-events-misclassify-fix-20260927` — Debug Events의 rate_limit 오분류(서버측 분류 계층). 본 지시는 사용자 노출 경로만 다룸.
- `docs/video-analysis-report-20260925-093149.md` — 동일 대기 증상의 선행 분석(메모리/폴백 맥락은 SESSION56 지시서가 커버).

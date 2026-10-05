# OLLAMA_CONCURRENCY_PROPERTIES_SPEC — 로컬 슬롯 스필오버 프로퍼티 명세

- 문서 ID: PASTE_DEVIN_OLLAMA_CONCURRENCY_API_FALLBACK_20261005-WP3
- 작성: 2026-10-05, Devin(SWE-2) — 적용은 Codex가 `main/resources/application-llm.yaml`과 라우터 코드에 반영
- 원칙: mutable spec — 수치는 배포 시점 SSOT에서 재확인 가능한 변수이며, 아래 기본값은 제안값.

## 1. 신규/기존 프로퍼티 표

| 프로퍼티 | env | 기본값 | 상태 | 의미 |
|---|---|---|---|---|
| `llm.local-admission.enabled` | `LLM_LOCAL_ADMISSION_ENABLED` | `true` | **기존** (`LlmRouterAspect.java:756-758`) | 로컬 슬롯 admission 전체 on/off. false면 슬롯 검사 없이 로컬 직행(현재 테스트 `disabledAdmissionIgnoresSaturatedSlot`이 보장) |
| `llm.local-admission.permits-per-model` | `LLM_LOCAL_ADMISSION_PERMITS_PER_MODEL` | `1` | **기존** (`LlmRouterAspect.java:760-773`) | (endpoint, model) 슬롯 수. U-1 결정=1: 다중 접속 시 VRAM 경합·속도 저하를 원천 차단, 2번째 요청부터 즉시 스필오버 |
| `llm.local-admission.eager-cloud-spillover` | `LLM_LOCAL_ADMISSION_EAGER_CLOUD_SPILLOVER` | `true` | **제안** | true면 포화 감지 시 `repickUncontendedLocalArm`(로컬↔로컬 hop)을 건너뛰고 `nextEligibleSelection(cloudOnly=true)`로 즉시 진입. false면 현행 순서(preferCloud 판정 → 로컬 hop 우선 가능) 유지 |
| `llm.local-admission.first-token-timeout-ms` | `LLM_LOCAL_ADMISSION_FIRST_TOKEN_TIMEOUT_MS` | `2500` | **제안** | 로컬 호출 후 첫 토큰 미생성 상한. 초과 시 Zero-Token Abort & Switch. U-2 결정=2500. Mid-Stream(토큰 방출 후)에는 적용 금지 — `CONCURRENCY_RESILIENCE_PLAN §6` 경계 |
| `llm.local-admission.local-retry-on-5xx` | `LLM_LOCAL_ADMISSION_LOCAL_RETRY_ON_5XX` | `0` | **제안** | 로컬 503/conn-refused 시 동일 로컬 arm 재시도 횟수 상한. 0=즉시 클라우드. 엔드포인트 건강 쿨다운은 `llm.gateway.local-device-failover.*`가 별도로 관리 |
| `llm.local-admission.fallback-ladder` | `LLM_LOCAL_ADMISSION_FALLBACK_LADDER` | `chatgpt_oauth,groq,gemini,openai,anthropic` | **제안** | 스필오버 시 시도할 클라우드 route 순서. `configs/api-routing.yaml` `routes.llm`의 tier(subscription→low_cost→paid_quality)와 일치 유지. `chatgpt_oauth`는 owner 등록 세션 한정(`ownerOAuthRoute` 게이트) |

## 2. 상호작용 (기존 키와의 정합)

| 관련 키 | 위치 | 관계 |
|---|---|---|
| `llm.gateway.cloud.enabled` / `route-key=api3` | `application-llm.yaml:43-45` | 스필오버가 도달할 클라우드 게이트 — 이미 true. `nextEligibleSelection(cloudOnly)`는 `apiFirstEnabled()` 또는 `cloudFallbackEnabled()`가 필요(`LlmRouterAspect.java:893`) — 사다리 도달 전에 이 게이트가 닫혀 있으면 스필오버 무의미 → **배포 시 cloudFallbackEnabled 확인이 선행 조건** |
| `llm.gateway.local-device-failover.*` | `application-llm.yaml:30-42` | 엔드포인트 건강(ENFORCE, transient 3회/60s 쿨다운). 스필오버는 per-request 즉시 우회, failover는 엔드포인트 수명 — 둘은 상호보완, 값 공유 없음 |
| `llmrouter.api-first.enabled` | `application-llm.yaml:483-485` (기본 false) | api-first는 **전체 요청**의 선호 순서, 본 스펙은 **로컬 선택 후 포화** 시 탈출 순서 — orthgonal. 둘 다 켜면 api-first가 우선 |
| `public.chat-admission.*` | `PublicChatAdmissionGuard.java:32-37` | 세션 수용(64/2). 스필오버는 수용된 세션의 모델 경로 문제 — admission 거절(429)과 혼동 금지 |
| `chatgpt.oauth.main-model` | `LlmRouterAspect.java:721-724` | OAuth 사다리 1순위 route slug 결정 — 미설정이면 owner 세션도 2순위로 진입 |
| `OLLAMA_NUM_PARALLEL` / `OLLAMA_MAX_QUEUE` | Ollama 데몬 env | **추정(라이브 미확인)**: 데몬 측 슬롯/큐. 앱 슬롯(permits=1)이 이를 하한으로 압축 — 데몬 값이 커도 앱이 1로 제한하므로 앱 측 방어가 우선한다 |

## 3. 읽기 규칙 / 런타임 정합성 가이드

- 라우터의 프로퍼티 해석은 `firstNonBlank(get(prop), get(ENV), System.getenv(ENV))` 패턴(`LlmRouterAspect.java:760-764`)을 따른다 → **env 우선, YAML 기본값 fallback**. 신규 키도 동일 패턴을 재사용해 일관성 유지.
- Spring relaxed binding: `llm.local-admission.first-token-timeout-ms` ↔ `LLM_LOCAL_ADMISSION_FIRST_TOKEN_TIMEOUT_MS` ↔ `llm.localAdmission.firstTokenTimeoutMs`.
- 적용 경로: 값 변경은 `application-llm.yaml` 또는 env → **DevWatch/ForceRestart로 JVM 재기동 후** 라이브 반영(DEMO1-SPRING-VIBE-RELOAD). 저장 즉시 반영 주장은 stale JVM 증거 없이는 불가.
- 검증 매트릭스(도입 후): `permits=1 + eager=true`에서 2번째 동시 요청이 `local_contended` 없이 클라우드 route로 가는지 `llm.gateway.preselectionReason`/`llm.localAdmission.contended`/`fallbackReason=concurrency_spillover` 트레이스로 확인.

## 4. YAML 반영 예시 (Codex 적용 블록)

```yaml
llm:
  local-admission:
    enabled: ${LLM_LOCAL_ADMISSION_ENABLED:true}
    permits-per-model: ${LLM_LOCAL_ADMISSION_PERMITS_PER_MODEL:1}
    eager-cloud-spillover: ${LLM_LOCAL_ADMISSION_EAGER_CLOUD_SPILLOVER:true}
    first-token-timeout-ms: ${LLM_LOCAL_ADMISSION_FIRST_TOKEN_TIMEOUT_MS:2500}
    local-retry-on-5xx: ${LLM_LOCAL_ADMISSION_LOCAL_RETRY_ON_5XX:0}
    fallback-ladder: ${LLM_LOCAL_ADMISSION_FALLBACK_LADDER:chatgpt_oauth,groq,gemini,openai,anthropic}
```

- 배치 위치 제안: `application-llm.yaml`의 `llm:` 블록 내 `gateway:` 형제 레벨.
- `fallback-ladder` 항목명은 `api-routing.yaml` `routes.llm[].id`와 문자열 일치시켜 라우터가 route id로 해석하게 한다.

# GPU Pressure Cloud Offload Design v2

**Status:** 사용자가 위임한 시스템 판단으로 설계 승인 (`APPLY`, goalScore `79.55`)

**Scope:** Desktop 구현 명세. Notebook에서는 이 문서와 지시서만 수정하며 애플리케이션 소스는 수정하지 않는다.

## 1. 목표

게임 또는 스트리밍처럼 GPU 지연에 민감한 작업이 진행 중일 때, RTX 3060/3090을 사용하는 **새 로컬 채팅 요청**이 해당 GPU와 경쟁하지 않도록 한다. 실제 장치 압력이 확인되면 기존 LLM 라우터가 로컬 모델을 만들기 전에 기존 클라우드 fallback route를 단 한 번 선택한다.

설계 우선순위는 다음과 같다.

1. 게임·스트리밍 프레임 안정성
2. 요청당 물리 LLM 시도 1회
3. 명시적인 비용 상한과 credential fail-closed
4. 정상 상태에서 로컬 실행과 기존 fallback 동작 보존
5. 요청별 provider/model/protocol 계보 증명

## 2. 확인된 현재 경계

- `main/java/com/example/lms/health/GpuHardwareDiagnostics.java`는 `nvidia-smi`를 최대 1초의 제한 시간으로 실행하고 장치별 `role`, GPU 사용률, VRAM 사용률을 반환한다.
- 역할은 현재 `RTX 3090 -> primary-chat-rerank-heavy`, `RTX 3060 -> fast-helper-embedding`으로 정규화된다.
- `main/java/com/example/lms/LmsApplication.java`에 `@EnableScheduling`이 있으므로 별도 스케줄러 프레임워크가 필요 없다.
- `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`의 `routeWithGateway(...)`가 logical route 선택과 fallback의 단일 권한 경계다.
- `HybridLlmGatewayProbeService`는 cloud enablement, route enablement, credential, model metadata, runtime health를 검사한다.
- `ModelRuntimeHealthTracker`는 `promptHash`, `optionsHash`, provider/model/protocol, 직접 HTTP 교환·응답 여부를 보존한다.
- 직접 HTTP 구현에서는 정상 요청이어도 `providerAttemptObserved=false`, `wireAttemptObserved=false`일 수 있다. 따라서 이 두 필드를 성공의 필수 조건으로 사용하면 안 된다.
- `application-llm.yaml`에서 gateway enforcement 기본값은 `observe`, cloud 기본값은 `false`, cloud route 기본값은 `api3`다.
- 활성 백엔드 sourceSet은 root `main/java`, `main/resources`다. 같은 이름의 `java/.../LlmRouterAspect.java`는 비활성 mirror이므로 수정하지 않는다.

## 3. 접근안 비교

### A. 요청 시 캐시 갱신

구현은 가장 작지만 첫 요청 전에 샘플이 없고, 요청 수가 hysteresis 표본 수로 잘못 계산될 수 있다. 동시 요청이 `nvidia-smi` 프로세스를 여러 개 만들 위험도 있다. 단독 방식으로 채택하지 않는다.

### B. 예약 샘플러 + 요청 경계 정책 가드 — 채택

기존 스케줄러가 요청과 무관하게 GPU를 한 번씩 측정한다. 상태 전이는 **요청 횟수가 아니라 고유 sample sequence**로만 진행한다. 라우터는 불변 snapshot을 읽기만 하므로 결정 비용과 동시성 위험이 작다.

첫 샘플이 아직 없을 때만 single-flight 동기 샘플을 한 번 허용한다. 따라서 첫 요청 보호와 프로세스 실행 상한을 함께 만족시킨다.

### C. 게임 프로세스명·창 제목 감지

게임 목록 누락, 브라우저 스트리밍, overlay, 이름 변경, 개인정보 노출 때문에 제외한다. 원인은 프로세스 정체가 아니라 장치 압력이다.

### D. 외부 PowerShell/Task Scheduler로 환경변수 전환

이미 실행 중인 JVM이 변경된 환경변수를 상속하지 않고 요청별 계보도 남기지 못한다. 라우팅 권한이 둘로 갈라지므로 제외한다.

## 4. 컴포넌트와 책임

### 4.1 `GpuPressureMonitor`

새 파일:

`main/java/com/example/lms/llm/gateway/GpuPressureMonitor.java`

책임:

- `@Scheduled`로 `GpuHardwareDiagnostics.snapshot(Environment)` 호출
- 최신 raw-safe snapshot을 `AtomicReference`로 게시
- 역할별 상태 머신 갱신
- 고유 `sampleSequence` 부여
- 첫 샘플 부재 시 single-flight 동기 갱신
- stale/unavailable/role-missing/role-ambiguous 분류

비책임:

- route 선택, credential 검사, cloud 호출, retry, 모델 생성, 비용 계산

권장 내부 계약:

```java
record RolePressureSnapshot(
        String role,
        long sampleSequence,
        long sampledAtNanos,
        PressureState state,
        String cause,
        Integer utilizationPct,
        Double memoryUsedRatio,
        int enterCount,
        int recoveryCount,
        long latchedAtNanos) {
}

enum PressureState { UNOBSERVED, NORMAL, PRESSURED }
```

시간 계산은 wall clock이 아니라 주입 가능한 monotonic `LongSupplier`/`Clock` 계약을 사용한다. 테스트가 실제 sleep에 의존하면 안 된다.

예약 실행 기본값:

- `initialDelay = 0`
- `sampleIntervalMs = 2_000`
- 기존 `nvidia-smi` 제한 시간 = `1_000 ms`
- `staleAfterMs = 6_000`

첫 요청 처리:

1. 최신 snapshot이 있으면 즉시 반환한다.
2. 없으면 하나의 `CompletableFuture` 또는 동등한 single-flight를 CAS로 설치한다.
3. 최초 요청만 진단을 실행하고, 동시 요청은 같은 결과를 최대 `firstSampleWaitMs=1_100` 동안 기다린다.
4. 제한 시간 후에도 결과가 없으면 `gpu_telemetry_unavailable_observe`로 로컬을 유지한다.
5. 어떤 경우에도 요청당 별도 `nvidia-smi` 프로세스를 만들지 않는다.

### 4.2 `GpuPressureRouteGuard`

새 파일:

`main/java/com/example/lms/llm/gateway/GpuPressureRouteGuard.java`

책임:

- route/provider/stage 적용 범위 확인
- route의 명시적 GPU role 조회
- monitor snapshot을 라우팅 결정으로 변환
- observe/enforce 모드 분리

권장 계약:

```java
RoutePressureDecision decide(String routeKey, String provider, String stage);

enum Action {
    LOCAL_ALLOWED,
    CLOUD_REQUIRED,
    CLOUD_WOULD_BE_REQUIRED,
    NOT_APPLICABLE
}

record RoutePressureDecision(
        Action action,
        String reason,
        String deviceRole,
        long sampleSequence,
        long sampleAgeMs,
        int enterCount,
        int recoveryCount,
        long latchAgeMs) {
}
```

적용 조건은 모두 만족해야 한다.

- `llm.gateway.gpu-pressure.enabled=true`
- provider가 `local` 또는 `ollama`
- stage가 `embedding`이 아님
- route가 설정의 `routes` map에 존재
- 전체 gateway가 활성화됨

`observe`에서는 `CLOUD_WOULD_BE_REQUIRED`만 기록하고 기존 로컬 경로를 유지한다. 실제 전환은 `enforce`에서만 `CLOUD_REQUIRED`를 반환한다.

### 4.3 `CloudOffloadQuotaGuard`

새 파일:

`main/java/com/example/lms/llm/gateway/CloudOffloadQuotaGuard.java`

목적은 비용을 추정하는 것이 아니라 자동 전환 횟수에 결정적 상한을 두는 것이다.

- 기본 route별 최대 `60`회
- 고정 시간창 기본 `3_600_000 ms`
- route별 `(windowStart, used)`만 유지
- cloud model을 만들기 직전에 보수적으로 1회를 소비
- 선택 후 호출되지 않아도 환불하지 않음
- 재시작 시 초기화되는 in-memory 정책
- 동시성에서 최대값을 초과하지 않는 원자적 `tryAcquire(routeKey)` 제공

금액·token 비용을 주장하지 않는다. 그 기능은 provider billing 증거가 생긴 뒤 별도 목표로 다룬다.

## 5. GPU 역할과 상태 머신

### 5.1 route-to-role은 명시적이다

기본 map:

| route | GPU role |
| --- | --- |
| `light` | `fast-helper-embedding` |
| `vision` | `fast-helper-embedding` |
| `gemma` | `primary-chat-rerank-heavy` |
| `judge` | `primary-chat-rerank-heavy` |
| `coder` | `primary-chat-rerank-heavy` |

`embedding`, `api3`, `openai-*`, `gemini-*`, `mistral-*`, `external`, `macmini`는 mapping 대상이 아니다.

GPU index, endpoint port, 모델명 substring으로 역할을 추정하지 않는다. 한 역할에 장치가 정확히 한 장일 때만 평가한다.

- 0장: `gpu_role_missing`
- 2장 이상: `gpu_role_ambiguous`

두 경우 모두 latch 전에는 로컬 observe, latch 후에는 cloud preference 유지다. 장치 식별 불능이 기존 압력 latch를 해제하면 안 된다.

### 5.2 진입

**하드 진입:** 하나의 fresh sample에서 아래 중 하나면 즉시 `PRESSURED`다.

- GPU utilization `>= 95%`
- VRAM used ratio `>= 0.92`

**소프트 진입:** 아래 중 하나가 서로 다른 fresh sample `3`개에서 연속이면 `PRESSURED`다.

- GPU utilization `>= 85%`
- VRAM used ratio `>= 0.82`

동일한 `sampleSequence`를 여러 요청이 읽어도 counter는 한 번만 증가한다.

### 5.3 회복

다음 두 조건이 모두 fresh sample `5`개에서 연속이어야 한다.

- GPU utilization `<= 60%`
- VRAM used ratio `<= 0.70`

또한 pressure 진입 후 최소 cloud dwell `30초`가 지나야 `NORMAL`로 복귀한다. 중간값은 회복 counter를 초기화한다. 압력값은 진입 counter를 유지·증가시키고 회복 counter를 초기화한다.

### 5.4 stale/unavailable

- sample age가 `6초`를 넘으면 stale이다.
- latch 전 stale/unavailable은 `LOCAL_ALLOWED` + `gpu_telemetry_unavailable_observe`다. 진단 장애만으로 유료 traffic을 만들지 않는다.
- latch 후 stale/unavailable은 `CLOUD_REQUIRED` 상태를 유지한다. 유효한 5개 회복 sample 없이는 해제하지 않는다.
- JVM 재시작은 latch를 초기화한다. 영속화는 이 목표의 범위가 아니다.

## 6. 설정

`LlmGatewayProperties` 아래에 `GpuPressure`를 추가하고 `application-llm.yaml`에 다음 기본값을 둔다.

```yaml
llm:
  gateway:
    gpu-pressure:
      enabled: ${LLM_GATEWAY_GPU_PRESSURE_ENABLED:false}
      sample-interval-ms: ${LLM_GATEWAY_GPU_PRESSURE_SAMPLE_INTERVAL_MS:2000}
      stale-after-ms: ${LLM_GATEWAY_GPU_PRESSURE_STALE_AFTER_MS:6000}
      first-sample-wait-ms: ${LLM_GATEWAY_GPU_PRESSURE_FIRST_SAMPLE_WAIT_MS:1100}
      hard-enter-utilization-pct: ${LLM_GATEWAY_GPU_PRESSURE_HARD_UTILIZATION_PCT:95}
      hard-enter-memory-ratio: ${LLM_GATEWAY_GPU_PRESSURE_HARD_MEMORY_RATIO:0.92}
      soft-enter-utilization-pct: ${LLM_GATEWAY_GPU_PRESSURE_SOFT_UTILIZATION_PCT:85}
      soft-enter-memory-ratio: ${LLM_GATEWAY_GPU_PRESSURE_SOFT_MEMORY_RATIO:0.82}
      exit-utilization-pct: ${LLM_GATEWAY_GPU_PRESSURE_EXIT_UTILIZATION_PCT:60}
      exit-memory-ratio: ${LLM_GATEWAY_GPU_PRESSURE_EXIT_MEMORY_RATIO:0.70}
      enter-samples: ${LLM_GATEWAY_GPU_PRESSURE_ENTER_SAMPLES:3}
      exit-samples: ${LLM_GATEWAY_GPU_PRESSURE_EXIT_SAMPLES:5}
      min-cloud-dwell-ms: ${LLM_GATEWAY_GPU_PRESSURE_MIN_CLOUD_DWELL_MS:30000}
      max-offloads-per-window: ${LLM_GATEWAY_GPU_PRESSURE_MAX_OFFLOADS_PER_WINDOW:60}
      quota-window-ms: ${LLM_GATEWAY_GPU_PRESSURE_QUOTA_WINDOW_MS:3600000}
      routes:
        light: fast-helper-embedding
        vision: fast-helper-embedding
        gemma: primary-chat-rerank-heavy
        judge: primary-chat-rerank-heavy
        coder: primary-chat-rerank-heavy
```

검증 및 clamp 규칙:

- interval `500..10_000 ms`
- stale는 interval의 `2..10배`
- first wait `100..5_000 ms`
- utilization `1..100`
- memory ratio `0.01..0.999`
- sample count `1..20`
- dwell `0..600_000 ms`
- quota `1..10_000`, window `60_000..86_400_000 ms`
- `exit < soft < hard`가 아니면 안전 기본값으로 복구하고 redacted reason을 기록

실제 자동 전환에는 다음이 모두 필요하다.

- `AWX_GPU_HARDWARE_TELEMETRY_ENABLED=true`
- `LLM_GATEWAY_GPU_PRESSURE_ENABLED=true`
- `LLM_GATEWAY_CLOUD_ENABLED=true`
- `LLM_GATEWAY_ENFORCEMENT=enforce`
- cloud route 활성화와 유효한 credential

credential 값은 설정·trace·문서·테스트 출력에 포함하지 않는다.

## 7. 라우터 통합 순서

`LlmRouterAspect.routeWithGateway(...)`만 실제 route를 바꾼다.

1. 기존 primary eligibility를 계산한다.
2. 선택 route에 대해 `GpuPressureRouteGuard.decide(...)`를 호출한다.
3. `NOT_APPLICABLE`, `LOCAL_ALLOWED`, `CLOUD_WOULD_BE_REQUIRED`면 현재 로직을 유지한다.
4. `CLOUD_REQUIRED`면 `fallbackSelection(sel)`로 기존 cloud 후보를 찾는다.
5. 후보를 `HybridLlmGatewayProbeService.evaluate(...)`로 다시 검사한다.
6. 후보가 없거나 ineligible이면 local model을 만들지 않고 유형화된 `ExpectedFailureChatModel`을 반환한다.
7. quota를 획득하지 못하면 같은 방식으로 `gpu_pressure_cloud_budget_exhausted`를 반환한다.
8. 후보가 eligible이고 quota가 있으면 `buildRoutedModel(cloud, ca, false)`만 호출한다.
9. 이 모델에는 `FallbackAwareChatModel`을 씌우지 않는다. cloud 401/429/timeout/5xx 이후 같은 요청을 local로 재시도하지 않는다.
10. 이미 실행 중인 local 요청을 취소·이동하지 않는다. 정책은 새 요청에만 적용한다.

유형화된 결과:

| 조건 | 결과 | local HTTP | cloud HTTP |
| --- | --- | ---: | ---: |
| 정상/미지속 압력 | 기존 local | 기존과 동일 | 0 |
| observe에서 압력 | 기존 local + would-offload trace | 기존과 동일 | 0 |
| enforce 압력 + cloud eligible + quota | cloud only | 0 | 1 |
| cloud disabled/credential missing/ineligible | `gpu_pressure_cloud_unavailable` | 0 | 0 |
| quota 소진 | `gpu_pressure_cloud_budget_exhausted` | 0 | 0 |
| cloud 401/429/timeout/5xx | 기존 cloud 실패 분류 | 0 | 1 |
| latch 전 telemetry unavailable | local + observe reason | 기존과 동일 | 0 |
| latch 후 telemetry unavailable | cloud preference 유지 | 0 | 0 또는 1 |

## 8. 관측성과 런타임 계보

`LlmGatewayBreadcrumbPublisher`에 provider 실패와 구분되는 `publishPolicyRedirect(...)`를 추가한다. GPU 압력을 `VRAM_OOM`으로 위장하지 않는다.

허용 trace key:

- `llm.gateway.gpuPressure.mode`
- `llm.gateway.gpuPressure.routeKey`
- `llm.gateway.gpuPressure.deviceRole`
- `llm.gateway.gpuPressure.state`
- `llm.gateway.gpuPressure.action`
- `llm.gateway.gpuPressure.reason`
- `llm.gateway.gpuPressure.sampleSequence`
- `llm.gateway.gpuPressure.sampleAgeMs`
- `llm.gateway.gpuPressure.enterCount`
- `llm.gateway.gpuPressure.recoveryCount`
- `llm.gateway.gpuPressure.latchAgeMs`
- `llm.gateway.gpuPressure.quotaRemaining`
- `llm.gateway.policyRedirect.fromKey`
- `llm.gateway.policyRedirect.toKey`
- `llm.gateway.policyRedirect.reason`

성공한 cloud offload의 `runtimeLineageVerdict=PASS` 조건:

1. `promptHash`, `optionsHash`가 비어 있지 않다.
2. cloud provider/model/protocol/endpoint label이 존재한다.
3. 해당 request timeline에 cloud 물리 시도 row가 정확히 1개다.
4. local endpoint의 `clientHttpExchangeObserved=true` row가 0개다.
5. 직접 HTTP 경로는 `clientHttpExchangeObserved=true`, `clientHttpResponseObserved=true`, `responseObserved=true`다.
6. adapter/wire 경로라면 그 경계가 소유한 provider/wire attempt와 response 증거를 사용한다.
7. `providerAttemptObserved=true`는 직접 HTTP 경로의 필수 조건이 아니다.

UI 응답, model construction, eligibility 성공만으로 lineage PASS를 선언하지 않는다.

금지 출력:

- raw `nvidia-smi` stdout/stderr
- 프로세스 목록 또는 창 제목
- prompt/response body
- API key, authorization header, cookie, 전체 environment dump

## 9. 성능·동시성 목표

- cached route decision p99 `< 2 ms` (가짜 monitor 단위 테스트 기준)
- `nvidia-smi` 실행은 정상 상태에서 interval당 최대 1회
- 동시에 시작한 첫 요청 20개도 single-flight 실행 최대 1회
- request reader는 monitor state lock을 기다리지 않고 immutable snapshot을 읽음
- pressure 상태 전환은 sample sequence당 최대 1회
- cloud/local 방향 전환은 최소 dwell 때문에 30초보다 자주 발생하지 않음
- monitor 예외는 scheduler thread를 종료시키지 않고 reason code만 남김

## 10. 테스트 전략

### 10.1 `GpuPressureMonitorTest`

- hard utilization 또는 hard VRAM 한 번으로 즉시 latch
- soft threshold 2회는 정상, 3회째 latch
- 동일 sequence 반복 조회는 counter 불변
- recovery 4회는 latch, 5회 + dwell 뒤 정상
- dwell 전 5회 회복해도 latch
- stale/unavailable이 latch를 해제하지 않음
- role missing/ambiguous 분류
- 3060/3090 상태 분리
- 동시 첫 요청 20개에서 runner 1회
- scheduler exception 뒤 다음 sample 처리

### 10.2 `GpuPressureRouteGuardTest`

- disabled/non-local/embedding/unmapped는 `NOT_APPLICABLE`
- observe pressure는 `CLOUD_WOULD_BE_REQUIRED`
- enforce pressure는 `CLOUD_REQUIRED`
- latch 전 telemetry unavailable은 local
- latch 후 telemetry unavailable은 cloud required
- 포트·모델명으로 role을 추정하지 않음

### 10.3 `CloudOffloadQuotaGuardTest`

- 60회 허용, 61회 거부
- window 경계 후 복구
- route별 독립
- 동시 획득이 상한을 넘지 않음
- 음수/과대 설정 clamp

### 10.4 라우터·계보 테스트

`LlmRouterRequestTimelineTest`에 루프백 서버와 fake guard를 사용한다.

- hard-pressure 첫 요청: local server 0회, cloud server 1회
- cloud route ineligible: local 0회, cloud 0회, expected failure 1개
- quota exhausted: local 0회, cloud 0회
- cloud 429/timeout: local 0회, cloud 1회, 두 번째 attempt 없음
- observe: 기존 local 호출 유지
- 직접 HTTP 성공: client exchange/response true, provider attempt false 허용
- prompt/options hash와 provider/model/protocol 보존
- 기존 primary-failure lazy fallback 회귀 테스트 유지

### 10.5 Desktop runtime proof

Desktop에서 한 번의 순차 smoke로 다음을 증명한다.

1. 정상 sample의 local baseline
2. synthetic 또는 실제 hard-pressure 첫 요청의 cloud-only 전환
3. cloud 오류 시 local 재시도 0회
4. 5개 회복 sample + 30초 dwell 뒤 local 복귀
5. trace/log count-only secret scan 0건

Notebook Java 런타임 부재로 이 증명은 현재 `evidence_needed`다.

## 11. 비목표

- 게임 프로세스, 창 제목, foreground app 감지
- Ollama/vLLM 프로세스 종료·unload·재설정
- 실행 중 요청의 중단 또는 mid-stream migration
- embedding cloud 전환이나 dimension 변경
- 금액/token 기반 billing
- 공개 API, DB, Supabase, credential, 기존 환경변수 이름 변경
- 새 LLM provider 또는 LangChain4j dependency 추가
- 두 번째 라우터·스케줄러·gateway framework 생성

## 12. 롤백

운영 롤백은 `LLM_GATEWAY_GPU_PRESSURE_ENABLED=false` 한 가지다. 이때 monitor는 외부 프로세스를 실행하지 않고 라우터는 기존 동작을 그대로 수행해야 한다.

소스 롤백은 세 새 클래스, `LlmGatewayProperties`/YAML 블록, `LlmRouterAspect`/auto-configuration/breadcrumb의 최소 wiring, 관련 테스트만 되돌린다. provider 설정, credential 이름, 기존 fallback 구현은 건드리지 않는다.

## 13. 완료 기준

1. 첫 hard-pressure 요청에서 local HTTP `0`, cloud HTTP `1`.
2. soft 3개 sample 진입, recovery 5개 sample + 30초 dwell 해제.
3. 3060과 3090 상태가 route role별로 독립.
4. telemetry failure가 유료 전환을 새로 만들거나 기존 latch를 지우지 않음.
5. cloud unavailable/quota exhausted/error에서 local 재시도 `0`.
6. observe 모드는 라우팅을 바꾸지 않음.
7. 직접 HTTP 계보가 `providerAttemptObserved=false`여도 정확한 exchange/response/hash로 PASS 가능.
8. 동시 첫 요청 20개에서 `nvidia-smi` 실행 최대 `1`.
9. focused tests, `compileJava`, `:app:classes`, `bootJar` PASS.
10. 변경 파일과 검증 로그의 credential-value secret scan `0`.
11. Desktop 최종 런타임 계보 증거가 없으면 완료가 아니라 `HOLD`.

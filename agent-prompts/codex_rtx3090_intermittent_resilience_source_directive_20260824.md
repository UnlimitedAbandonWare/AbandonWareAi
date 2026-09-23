# RTX 3090 간헐 장치 이탈 대응 소스수정 지시서

## 0. 실행 판정

```text
directiveId=desktop-rtx3090-intermittent-device-loss-resilience-20260824-v1
requestClass=source_modification_directive
currentTurnMutationClass=markdown_only
currentApplicationSourceMutation=false
sourceModificationNeeded=true
rootCauseClass=external_gpu_device_loss_plus_application_endpoint_isolation_gap
implementationVerdict=HOLD_UNTIL_OWNERSHIP_AND_PREIMAGE_PROVEN
canonicalWorkspace=C:\AbandonWare\demo-1\demo-1\src
branch=codex/owned-runtime-browser-restart
head=0796a3c5b29bbb08c3314bd40649d856d4a7bce6
observedAt=2026-08-24T13:42:07+09:00
```

이 지시서의 결론은 두 부분이다.

1. 현재 RTX 3090의 실제 `GPU is lost` 상태는 Java 소스로 복구할 수 있는 문제가 아니다. 드라이버·장치·전원·PCIe·열·시스템 재시작 영역은 운영자가 별도로 해결해야 한다.
2. 애플리케이션에는 이 외부 장애를 반복 요청마다 다시 밟지 않도록 만드는 보완이 필요하다. 현재 구현은 한 요청 안에서 같은 Ollama 엔드포인트에 `num_gpu=0` CPU 재시도를 할 수 있지만, 실패한 GPU/엔드포인트를 다음 요청부터 격리하거나 다른 건강한 로컬 route를 선택하는 상태를 보존하지 않는다.

따라서 소스 변경은 **하드웨어 복구가 아니라 endpoint/device 단위 격리, bounded half-open 복구, 명시적 local fallback 선택**에만 한정한다.

## 1. 권위와 실행 경계

- 현재 파일과 실제 명령 출력이 이 문서보다 우선한다.
- 이 문서는 애플리케이션 소스를 수정하지 않는다. 구현 세션은 별도로 시작한다.
- 구현 전 `$demo1-source-edit-three-way-preflight`를 실행해 정확히 `POSITIVE_QUERY`, `NEGATIVE_QUERY`, `NEUTRAL_QUERY`를 수행하고 양 순서에서 안정적인 `APPLY`를 받아야 한다.
- 현재 대상 파일 다수가 이미 수정 상태다. 명시적인 소유권·lease·preimage가 없으면 해당 구현 lane은 `target-dirty-overlap`으로 `HOLD`한다.
- `GpuHardwareDiagnostics`, 기존 `LlmRouterBandit` cooldown, `HybridLlmGatewayProbeService`, `ModelRuntimeHealthTracker`, request-attempt ledger를 재사용한다. 새 gateway stack이나 두 번째 범용 circuit-breaker 프레임워크를 만들지 않는다.
- `dev.langchain4j:*`는 전부 `1.0.1`을 유지한다. 새 dependency를 추가하지 않는다.
- Browser·Computer·provider 성공을 서로 대신하는 증거로 사용하지 않는다.

## 2. 고정 EvidenceSnapshot

### 2.1 저장소 상태

| evidenceId | 현재 관측 |
| --- | --- |
| E01 | canonical root `C:\AbandonWare\demo-1\demo-1\src` |
| E02 | branch `codex/owned-runtime-browser-restart`, HEAD `0796a3c5b29b` |
| E03 | 활성 sourceSet: root `main/java`, `main/resources`, `src/test/java`; `app/src/main/java_clean`, `app/src/main/resources` |
| E04 | `git status --short` 총 1,654행; 기존 변경은 사용자 소유로 보존 |
| E05 | `.git/index.lock=false`, top-level PatchDrop patch count `0`, source-edit lease file count `0` |
| E06 | `main/resources/application-llm.yaml`과 핵심 LLM/router 파일 다수가 이미 수정 상태 |

### 2.2 Computer/Windows/GPU 현재 증거

| evidenceId | 현재 관측 |
| --- | --- |
| E10 | `nvidia-smi -L` exit `15`; RTX 3060은 보이고 RTX 3090은 `GPU is lost` |
| E11 | 5회, 5초 간격 query 모두 RTX 3090 device handle 실패; 3060만 정상 telemetry row 반환 |
| E12 | PnP device 표면은 RTX 3090을 `OK/CM_PROB_NONE`으로 표시했지만 NVML은 device handle을 얻지 못함 |
| E13 | 최근 14일 System log에 `Display/4101`이 4회; 메시지는 `nvlddmkm` 응답 중지 후 복구 |
| E14 | 최근 14일 `nvlddmkm/153` 오류가 2회 관측됨 |

해석:

- Windows PnP의 `OK`는 CUDA/NVML 생성 가능성을 증명하지 않는다.
- 반복된 `Display/4101`과 현재 NVML device loss는 소스 버그보다 드라이버/장치 계층 이상을 지지한다.
- 애플리케이션은 이 장애를 고칠 수 없지만, 이 장애가 지속되는 동안 잘못된 endpoint를 반복 선택하지 않도록 해야 한다.

### 2.3 Ollama 현재 증거

| evidenceId | 현재 관측 |
| --- | --- |
| E20 | 11434와 11435는 각각 별도 `ollama serve` PID가 listen 중 |
| E21 | 양쪽 `/api/version`은 Ollama `0.32.13` |
| E22 | 양쪽 `/api/tags`는 HTTP 성공, 각각 19개 설치 모델을 보고 |
| E23 | 최초 `/api/ps`는 양쪽 loaded model count `0` |
| E24 | 11435에 한 번만 보낸 최소 `/api/generate`가 3,476ms 뒤 HTTP `500` |
| E25 | 최소 probe의 request body hash `4d83a58b3a5fa3bb853f4c180642d7b9935f329d7e5b7eb8c12e171c1750c849`; prompt/response body 저장·출력 안 함 |

해석:

- `/api/version`, `/api/tags`, open TCP port는 생성 건강성 증거가 아니다.
- E24는 11435 생성 실패를 증명하지만 그 하나만으로 11435가 물리 RTX 3090 소유 endpoint라고 단정하지 않는다.
- 실행 프로세스의 실제 `CUDA_VISIBLE_DEVICES`/device-role 매핑은 현재 `evidence_needed`다.

### 2.4 현재 소스 동작

| evidenceId | 코드 근거 | 관측 |
| --- | --- | --- |
| E30 | `DynamicChatModelFactory.java:221`, `:294-308`, `:707-731` | 모델 문자열로 fast/high/local base URL을 고르고 `OllamaNativeChatModel`을 생성 |
| E31 | `OllamaNativeChatModel.java:145-163` | 인식된 5xx에서 같은 endpoint에 `num_gpu=0`으로 한 번 CPU 재시도 |
| E32 | `OllamaNativeChatModel.java:467-499` | `available devices: 0`, runner 종료만 device-shaped CPU retry reason으로 인식 |
| E33 | `OllamaNativeChatModel.java:598-607` | 실패를 provider=`local`, model, protocol, generic reason으로 기록 |
| E34 | `ModelRuntimeHealthTracker.java:892-970` | health key가 `(provider, model)`뿐이며 endpoint/device가 없음 |
| E35 | `ModelRuntimeHealthTracker.java:1020-1051` | generic blocking reason을 `llm_route_degrade`로 투영 |
| E36 | `LlmRouterBandit.java:108-192` | route-key 실패 cooldown은 이미 존재하지만 모든 route가 cooldown이면 cooldown을 무시하고 재선택 가능 |
| E37 | `LlmRouterAspect.java:127-170` | `llmrouter.*`만 router 경로로 들어가며 일반 모델 ID는 factory 원래 경로로 진행 |
| E38 | `LlmRouterAspect.java:189-264` | gateway fallback은 현재 cloud fallback enabled일 때만 생성 |
| E39 | `HybridLlmGatewayProbeService.java:57-142` | eligibility가 provider/model health를 보지만 endpoint health를 보지 않음 |
| E40 | `GpuHardwareDiagnostics` 호출자 | UAW diagnostics/autolearn/rerank gate에는 연결되지만 chat route 선택에는 직접 연결되지 않음 |

### 2.5 focused baseline

다음 5개 suite를 isolated Gradle user/project cache와 `AWX_BUILD_HOST_ID=desktop-rtx3090-directive`로 실행했다.

- `OllamaNativeChatModelTest`
- `ModelRuntimeHealthTrackerTest`
- `GpuHardwareDiagnosticsTest`
- `HybridLlmGatewayProbeServiceTest`
- `LlmRouterBanditTraceTest`

결과:

```text
suiteCount=5
tests=42
failures=0
errors=0
skipped=0
BUILD SUCCESSFUL in 2m 33s
```

이 GREEN은 현재 CPU fallback과 generic route cooldown이 의도대로 존재함을 증명한다. **endpoint/device quarantine이 이미 존재한다는 증거는 아니다.**

## 3. 현재 preimage와 중첩 상태

다음 해시는 2026-08-24 현재 working-tree 파일의 SHA-256이다.

| 파일 | SHA-256 | 현재 상태 |
| --- | --- | --- |
| `main/java/com/example/lms/llm/DynamicChatModelFactory.java` | `f2f6815cf1488c115d359eb14e24e70e29807418dde4d0a91a43a816bf244327` | modified |
| `main/java/com/example/lms/llm/OllamaNativeChatModel.java` | `b5bf8c2d786a2a99a1a5ec9cf59cdfd741e6515abb3b744d3a173ed71a03b3f9` | modified |
| `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java` | `a352ac0f7330f6361df1e9c46266e025d10a68c08263b2f0bcdd3a051c5b631e` | modified |
| `main/java/com/example/lms/health/GpuHardwareDiagnostics.java` | `5bfb5946ed0d63cc53869a647b8a1188a5c1e7e9e8ca9d144cc0d72f06ed630a` | clean; reuse only |
| `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` | `d63f8633e5a348c97e4e54ad0d4533399d8e24b3b4a4e0903379d6d419ae550d` | modified |
| `main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java` | `3cf1452dc68db546a128f98dfad8c4f250866c2e9d43d19438daf80f30de05b7` | clean |
| `main/resources/application-llm.yaml` | `943ab31cffeaa8a166087a27f3e4905e608a26bb16164356e59d350418b0f832` | modified |
| `src/test/java/com/example/lms/llm/OllamaNativeChatModelTest.java` | `33a139a564a6d338971502fb94e87cc37639921dbac4967d362f4feb7c31ce1c` | modified |
| `src/test/java/com/example/lms/llm/ModelRuntimeHealthTrackerTest.java` | `ff532f0fbdc80f69d3fa2c2a7eff79fc886fda83bf96a734784f2057c8e254be` | clean |

추가로 다음 구현 후보도 modified 또는 untracked다.

- `main/java/ai/abandonware/nova/config/LlmRouterProperties.java`
- `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java`
- `main/java/com/example/lms/llm/gateway/LlmFailureClass.java`
- `main/java/com/example/lms/llm/gateway/LlmGatewayFailureClassifier.java`
- `src/test/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeServiceTest.java`
- `src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java`는 untracked

구현자는 이 표의 해시를 무조건 적용 기준으로 사용하지 않는다. 실행 직전에 다시 해시를 계산하고, 파일이 바뀌었으면 이 문서를 stale로 판정한다. 현재 dirty hunk 소유권을 증명하지 못하면 소스 변경 없이 `HOLD`한다.

## 4. 결함 정의

### 4.1 이미 있는 보호

- native Ollama GPU 오류 일부를 한 요청 안에서 CPU retry로 복구한다.
- router auto route에는 route-key 단위 cooldown이 있다.
- generic model health와 route eligibility가 있다.
- request attempt ledger는 primary/fallback 물리 시도를 분리할 수 있다.

### 4.2 빠진 보호

1. health key에 endpoint/device identity가 없다.
2. CPU fallback이 성공하면 전체 호출이 성공으로 끝나, GPU primary failure가 다음 route 선택에 남지 않을 수 있다.
3. 같은 endpoint를 공유하는 여러 route key가 함께 격리되지 않는다.
4. direct `llmrouter.<key>`와 router를 통과하지 않는 raw model ID 경로는 auto cooldown의 이점을 받지 못한다.
5. `/api/version` 성공이 generation health로 오인될 여지가 있다.
6. cooldown 뒤 half-open probe single-flight와 GPU-primary 성공 기반 복구 조건이 없다.
7. `main_gpu_no_devices`, runner termination, generic 5xx, VRAM OOM이 서로 다른 정책으로 유지되지 않는다.
8. `LLM_3090_BASE_URL`과 `LLM_3060_BASE_URL`이 같은 정규화 endpoint로 수렴하는 구성 오류를 fail-closed로 막지 않는다.

## 5. 수정 목표

새로운 logical request부터 다음 계약을 만족한다.

```text
recognized hard device loss
  -> failed endpointHash OPEN
  -> current request may use existing one-time CPU fallback
  -> CPU fallback success does not clear GPU endpoint OPEN
  -> subsequent auto request excludes every route sharing endpointHash
  -> healthy explicitly configured local route may be selected
  -> no healthy/eligible local route means existing cloud policy only when already enabled
  -> otherwise typed expected failure, no retry storm
  -> cooldown expiry permits exactly one half-open GPU-primary probe
  -> two consecutive GPU-primary successes close quarantine
```

## 6. 설계 계약

### 6.1 실패 분류

`LlmFailureClass`에 `GPU_DEVICE_LOST`를 추가한다.

분류 규칙:

| 입력 | 판정 | endpoint quarantine |
| --- | --- | --- |
| `main_gpu`와 `available devices: 0`가 함께 있는 5xx | `GPU_DEVICE_LOST` hard | 즉시 OPEN |
| `runner process has terminated` 또는 `llama-server process has terminated` | `GPU_RUNNER_TERMINATED` 내부 reason | 60초 내 2회 또는 fresh hardware-missing signal과 결합될 때 OPEN |
| generic HTTP 500/502/503 | 기존 `HEALTH_DOWN` | endpoint device-loss OPEN 금지; 기존 route/model health만 사용 |
| `oom`, `out of memory`, `vram` | 기존 `VRAM_OOM` | device-loss OPEN 금지 |
| connection refused | 기존 `HEALTH_DOWN` | endpoint availability cooldown은 가능하나 GPU device-loss와 분리 |
| timeout | 기존 `TIMEOUT_SOFT` | hard quarantine 금지 |

raw 응답 본문은 저장하거나 trace하지 않는다. 분류 뒤에는 allowlisted reason code, status, body length, body hash만 남긴다.

### 6.2 endpoint health key

`ModelRuntimeHealthTracker`의 기존 `(provider, model)` snapshot을 깨지 않는다. 별도의 bounded endpoint map을 같은 소유자 안에 추가한다.

```text
EndpointKey(provider, normalizedEndpointHash)
EndpointSnapshot(
  state=CLOSED|OPEN|HALF_OPEN,
  lastReason,
  failureCount,
  openedAtEpochMs,
  retryAfterEpochMs,
  consecutiveGpuPrimarySuccesses,
  halfOpenProbeInFlight
)
```

규칙:

- raw URL, API key, owner token, model response를 map이나 trace에 저장하지 않는다.
- normalize는 scheme/host/port와 기존 endpoint compatibility 규칙만 사용하고 query/userinfo를 제거한다.
- map capacity를 제한하고 오래된 CLOSED entry를 제거한다.
- 같은 normalized endpoint를 쓰는 route는 같은 OPEN 상태를 본다.
- CPU fallback success는 endpoint의 GPU-primary 성공으로 계산하지 않는다.
- `/api/version`, `/api/tags`, TCP listener 성공도 quarantine 해제 조건이 아니다.
- GPU-primary 생성 성공만 recovery counter를 올린다.
- half-open probe는 endpoint당 동시에 하나만 허용한다.

### 6.3 상태 전이

기본값은 다음과 같이 구성 가능하게 만들되 disabled/observe-first를 유지한다.

```yaml
llm:
  gateway:
    local-device-failover:
      enabled: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENABLED:false}
      enforcement: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENFORCEMENT:OBSERVE}
      hard-cooldown-ms: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_HARD_COOLDOWN_MS:600000}
      runner-failure-window-ms: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_RUNNER_WINDOW_MS:60000}
      runner-failure-threshold: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_RUNNER_THRESHOLD:2}
      recovery-successes: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_RECOVERY_SUCCESSES:2}
      max-endpoints: ${LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_MAX_ENDPOINTS:16}
```

- `enabled=false`: 상태를 만들거나 route를 바꾸지 않는다.
- `enabled=true`, `OBSERVE`: 상태와 would-block/would-fallback만 기록하고 route를 바꾸지 않는다.
- `enabled=true`, `ENFORCE`: OPEN endpoint를 auto candidate에서 제외한다.
- hard cooldown이 끝나면 `HALF_OPEN`이지만 single-flight permit을 얻은 한 요청만 GPU-primary probe를 한다.
- probe 실패는 OPEN과 cooldown을 갱신한다.
- 두 번의 연속 GPU-primary 생성 성공 뒤 CLOSED로 전환한다.
- process restart는 in-memory 상태를 비운다. 영구 저장/DB/Supabase를 추가하지 않는다.

### 6.4 route 선택

`LlmRouterBandit`의 기존 route-key cooldown을 교체하지 않는다. `HybridLlmGatewayProbeService.evaluate(...)`가 endpoint quarantine을 추가 eligibility 신호로 제공한다.

- `llmrouter.auto`: OPEN endpoint를 공유하는 모든 route를 candidate에서 제외한다.
- direct `llmrouter.<key>`: 정책이 ENFORCE이고 endpoint가 OPEN이면 묵시적 모델 교체를 하지 않는다.
- direct route가 명시적 `device-fallback-key`를 가질 때만 그 local route를 평가한다.
- `device-fallback-key`는 cloud `fallback-key`와 별개다.
- fallback route는 enabled, local provider, 다른 normalized endpoint, stage-compatible, gateway-eligible여야 한다.
- 3090/3060 role endpoint가 동일하거나 어느 쪽인지 증명되지 않으면 `gpu_role_ambiguous`로 fail closed한다.
- raw model ID가 router를 통과하지 않을 때 `DynamicChatModelFactory`는 OPEN endpoint를 반복 생성하지 않는다. 명시적 local device fallback이 없으면 typed `gpu_device_lost` expected failure를 반환해 상위 기존 정책이 처리하게 한다.
- cloud 호출은 기존 `llm.gateway.cloud.enabled`, credential guard, quota, single-attempt 정책이 모두 만족될 때만 기존 경로에서 허용한다. 이 기능이 cloud를 자동 활성화하지 않는다.
- embedding route는 변경하지 않는다.

### 6.5 current-request CPU fallback 보존

현재 `OllamaNativeChatModel`의 한 번짜리 `num_gpu=0` fallback은 유지한다.

- primary GPU attempt가 `GPU_DEVICE_LOST`이면 endpoint OPEN을 먼저 기록한다.
- CPU retry success는 logical response success와 request attempt success로 기록할 수 있다.
- 그러나 endpoint GPU state는 OPEN으로 남긴다.
- CPU retry도 실패하면 primary/fallback 두 물리 시도를 각각 남기고 추가 local retry를 하지 않는다.
- `maxRetries=0`은 transport retry를 0으로 유지하되 이 한 번의 operational CPU fallback만 별도 계약으로 보존한다.

### 6.6 trace와 privacy

추가 허용 trace key 예시:

```text
llm.localEndpoint.state
llm.localEndpoint.endpointHash
llm.localEndpoint.failureClass
llm.localEndpoint.opened
llm.localEndpoint.retryAfterMs
llm.localEndpoint.halfOpenPermit
llm.localEndpoint.gpuPrimarySuccessCount
llm.localEndpoint.selectionDecision
llm.localEndpoint.fallbackRouteKey
llm.localEndpoint.roleMapping
```

허용 값은 enum, boolean, bounded count/duration, hash, allowlisted route key뿐이다.

금지:

- raw prompt/response/error body
- raw API key/owner token/header/cookie/environment dump
- full process command line
- raw GPU UUID 또는 전체 PCI device path
- 사용자 query나 model output 재구성이 가능한 데이터

## 7. 대상 파일

### 7.1 최소 수정 후보

1. `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java`
   - bounded endpoint state와 single-flight half-open permit 추가
   - 기존 provider/model API와 snapshot 의미 보존
2. `main/java/com/example/lms/llm/OllamaNativeChatModel.java`
   - recognized device-loss를 endpoint tracker에 기록
   - CPU fallback success와 GPU-primary health success 분리
3. `main/java/com/example/lms/llm/gateway/LlmFailureClass.java`
   - `GPU_DEVICE_LOST` 추가
4. `main/java/com/example/lms/llm/gateway/LlmGatewayFailureClassifier.java`
   - allowlisted native failure reason을 새 class로 투영
5. `main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java`
   - `local-device-failover` disabled/observe-first 속성 추가
6. `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java`
   - endpoint quarantine를 route eligibility에 반영
7. `main/java/ai/abandonware/nova/config/LlmRouterProperties.java`
   - `ModelConfig.deviceFallbackKey` 추가
8. `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
   - auto exclusion과 explicit local device fallback 연결
9. `main/java/com/example/lms/llm/DynamicChatModelFactory.java`
   - router를 통과하지 않는 raw local model 경로의 OPEN endpoint guard
10. `main/resources/application-llm.yaml`
    - disabled/observe-first 기본값과 선택적 route mapping 계약 추가

### 7.2 테스트 후보

- `src/test/java/com/example/lms/llm/OllamaNativeChatModelTest.java`
- `src/test/java/com/example/lms/llm/ModelRuntimeHealthTrackerTest.java`
- `src/test/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeServiceTest.java`
- `src/test/java/ai/abandonware/nova/orch/router/LlmRouterBanditTraceTest.java`
- `src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java`
- 필요할 때 새 `src/test/java/com/example/lms/llm/LocalEndpointDeviceLossContractTest.java`

### 7.3 수정 금지

- `main/java/com/example/lms/health/GpuHardwareDiagnostics.java`는 첫 구현에서 재사용만 한다. per-request `nvidia-smi` 호출을 넣지 않는다.
- `main/java/com/example/lms/prompt/PromptBuilder.java`
- embeddings와 vector index
- `app/src/main/java`, `project/src/main/java`, `demo-1`, `lms-core`, archive/backup/generated output
- provider credentials, key names, public API, DB, Supabase
- 기존 GPU-pressure/cloud-offload 구현을 복제하는 새 scheduler/monitor/router stack

구현 시 `GpuPressureMonitor` 또는 `GpuPressureRouteGuard`가 이미 live source에 추가돼 있으면 별도 endpoint state를 중복 생성하지 말고 그 단일 소유자에 `DEVICE_LOST` 상태를 통합한 뒤 preflight를 다시 수행한다.

## 8. RED 계약

구현 전에 다음 테스트가 현재 소스에서 실패해야 한다.

1. `gpuDeviceLossOpensOnlyFailedEndpointEvenWhenCpuRetrySucceeds`
   - primary: device-loss 500
   - fallback: same endpoint CPU 200
   - logical answer success
   - endpoint state OPEN
2. `cpuFallbackSuccessDoesNotClearGpuEndpointQuarantine`
3. `secondAutoRequestSkipsAllRoutesSharingQuarantinedEndpoint`
4. `secondAutoRequestUsesHealthyDifferentEndpointExactlyOnce`
5. `generic500DoesNotBecomeGpuDeviceLostWithoutSignature`
6. `vramOomDoesNotQuarantinePhysicalDevice`
7. `runnerTerminationRequiresBoundedThresholdUnlessHardwareMissing`
8. `directRouteDoesNotSilentlySubstituteWithoutDeviceFallbackKey`
9. `explicitDeviceFallbackKeyMustBeLocalHealthyDifferentEndpoint`
10. `sameNormalized3090And3060EndpointFailsGpuRoleAmbiguous`
11. `allLocalEndpointsOpenReturnsTypedFailureWithZeroNewHttpAttempts`
12. `cooldownAllowsOneHalfOpenProbeAcrossTwentyConcurrentRequests`
13. `apiVersionAndTagsSuccessDoNotCloseGenerationQuarantine`
14. `twoGpuPrimarySuccessesCloseHalfOpenState`
15. `rawModelFactoryPathDoesNotRetryOpenEndpoint`
16. `endpointEvidenceStoresHashAndReasonButNoRawUrlPromptResponseOrGpuUuid`
17. 기존 `configuredTransportRetryZeroKeepsOneOperationalCpuFallbackWithoutRequestOverride`는 계속 GREEN
18. 기존 request attempt ledger의 primary/fallback physical count 계약은 계속 GREEN

## 9. GREEN 수용 표

| 시나리오 | 기대 결과 |
| --- | --- |
| 3090 hard device loss + CPU fallback success | 현재 답변 가능, endpoint OPEN 유지 |
| 다음 `llmrouter.auto` 요청 | 3090 endpoint 공유 route 0회, 건강한 3060 route 1회 |
| direct route, fallback 미설정 | typed `gpu_device_lost`, 다른 모델/클라우드 호출 0회 |
| direct route, valid `device-fallback-key` | 선언된 다른 local endpoint만 1회 |
| 모든 local endpoint OPEN | local HTTP 0회; cloud는 기존 정책 enabled일 때만 최대 1회 |
| generic 500 | 기존 `HEALTH_DOWN`; GPU device quarantine 아님 |
| VRAM OOM | 기존 `VRAM_OOM`; device identity quarantine 아님 |
| cooldown 전 | GPU-primary probe 0회 |
| cooldown 후 동시 20요청 | half-open GPU-primary probe 최대 1회 |
| CPU success | recovery success count 증가 0 |
| GPU-primary success 2회 | endpoint CLOSED |
| role URL collision | `gpu_role_ambiguous`, cross-device success 주장 금지 |
| observe mode | selection mutation 0, would-block evidence만 기록 |

## 10. 구현 순서

1. 아래 preflight를 실행하고 target ownership을 증명한다.
2. exact current preimage hash를 별도 evidence artifact에 기록한다.
3. RED tests만 먼저 추가하고 focused failure를 캡처한다.
4. `LlmFailureClass`와 endpoint state를 최소 구현한다.
5. `OllamaNativeChatModel`의 primary failure/CPU success 분리를 연결한다.
6. `HybridLlmGatewayProbeService`에 endpoint eligibility를 연결한다.
7. router auto와 explicit device fallback을 연결한다.
8. raw model factory path에 OPEN endpoint guard를 연결한다.
9. configuration은 disabled/observe-first로 추가한다.
10. focused GREEN 뒤 boundary verification을 넓힌다.
11. fake loopback two-endpoint runtime proof를 먼저 수행한다.
12. 실제 RTX 3090 lane은 hardware owner가 복구 또는 현재 device-loss 상태 사용을 승인한 뒤 한 번만 검증한다.

## 11. 구현 전 exact preflight

PowerShell에서 실행한다.

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git worktree list --porcelain
git status --short
Test-Path -LiteralPath (git rev-parse --git-path index.lock)
Get-ChildItem -LiteralPath '.\__patch_drop__' -File -Filter '*.patch' -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath '.\data\agent-handoff\source-edit-leases' -File -ErrorAction SilentlyContinue
```

그 뒤 `$demo1-source-edit-three-way-preflight`의 immutable EvidenceSnapshot과 정확히 세 query를 실행한다.

첫 blocking rule:

```text
target-dirty-overlap
```

현재 modified/untracked target의 소유권 또는 clean isolated worktree가 없으면:

```text
holdScope=rtx3090-device-loss-source-implementation
firstBlockingRule=target-dirty-overlap
blockingEvidence=<changed target list plus fresh hashes>
independentWorkCompleted=runtime diagnosis and source directive
repositoryWideHold=false
```

## 12. focused verification 명령

구현 세션은 고유한 host-local cache를 사용한다.

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-rtx3090-device-loss'
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'awx-gradle-user-rtx3090-device-loss'
$projectCache = Join-Path $env:TEMP 'awx-gradle-project-rtx3090-device-loss'

.\gradlew.bat test `
  --tests 'com.example.lms.llm.LocalEndpointDeviceLossContractTest' `
  --tests 'com.example.lms.llm.OllamaNativeChatModelTest' `
  --tests 'com.example.lms.llm.ModelRuntimeHealthTrackerTest' `
  --tests 'com.example.lms.llm.gateway.HybridLlmGatewayProbeServiceTest' `
  --tests 'ai.abandonware.nova.orch.router.LlmRouterBanditTraceTest' `
  --tests 'ai.abandonware.nova.orch.aop.LlmRouterRequestTimelineTest' `
  --project-cache-dir $projectCache `
  --no-daemon --console=plain
```

새 test class를 만들지 않고 기존 class에 계약을 배치했다면 존재하는 exact FQCN만 사용하며, 존재하지 않는 `--tests` 패턴을 성공 명령처럼 보고하지 않는다.

경계 검증:

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test `
  --project-cache-dir $projectCache --no-daemon --console=plain

.\gradlew.bat :app:classes `
  --project-cache-dir $projectCache --no-daemon --console=plain

.\gradlew.bat bootJar -x test `
  --project-cache-dir $projectCache --no-daemon --console=plain
```

## 13. runtime proof

### 13.1 결정적 loopback proof

실제 GPU보다 먼저 두 개의 controlled loopback server로 검증한다.

- endpoint A, primary attempt: device-loss-shaped HTTP 500
- endpoint A, CPU fallback: HTTP 200
- endpoint B: HTTP 200
- 첫 logical request: A physical attempts `2`, B `0`, logical success `1`
- 두 번째 auto request: A physical attempts 추가 `0`, B physical attempts `1`
- raw prompt/response 저장 `false`
- endpoint A OPEN, endpoint B CLOSED
- CPU success가 A recovery counter를 올리지 않음

### 13.2 실제 Desktop proof

실제 endpoint/GPU 역할 매핑을 먼저 증명한다.

```text
evidence_needed: effective LLM_3090_BASE_URL and LLM_3060_BASE_URL role ownership
verify with: process-owner-approved launch configuration plus one device-isolated generation per endpoint
```

사용자 또는 process owner 승인 없이 Ollama 프로세스를 종료·재시작·reset하지 않는다. 실제 proof는 같은 입력을 재전송하지 않고 한 번의 bounded request로 수행한다.

완료 증거:

- Browser가 필요하면 fresh JAR와 request timeline hash를 같은 요청으로 상관한다.
- `fallback:local`, HTTP 200, rendered answer만으로 3090 generation success를 주장하지 않는다.
- provider/wire attempt는 observed physical row가 있을 때만 성공으로 말한다.
- device role이 불명확하면 `runtimeRoleMapping=evidence_needed`를 유지한다.

## 14. 금지와 중단 조건

다음 중 하나면 구현을 중단한다.

- target dirty hunk ownership 또는 preimage 불명
- index lock, source lease 충돌, top-level PatchDrop pending
- 3090/3060 role URL이 동일하거나 owner mapping 불명인데 cross-device fallback을 강행하려 함
- per-request `nvidia-smi` 또는 무제한 external process 실행
- CPU fallback success를 GPU recovery로 기록
- generic 5xx/timeout/OOM을 `GPU_DEVICE_LOST`로 뭉침
- cloud fallback을 자동 활성화하거나 usable credential 없이 provider 호출
- embeddings, model strings, LangChain4j version, secrets, public API, DB까지 범위 확대
- raw prompt/response/error body/credential/GPU UUID 노출
- 한 logical request에서 local endpoint bounce 또는 cloud/local 재귀 fallback
- loopback contract에서 failed endpoint의 두 번째 요청 count가 0이 아님

## 15. 롤백

1. `LLM_GATEWAY_LOCAL_DEVICE_FAILOVER_ENABLED=false`
2. observe/disabled regression으로 route mutation `0` 확인
3. 그래도 문제가 남으면 이 지시서의 declared source files와 exact hunks만 revert
4. 기존 CPU fallback, generic route cooldown, provider guard, cloud policy는 보존
5. DB, credential, model manifest, embedding index는 롤백 대상이 아니다

## 16. 완료 조건

다음이 모두 fresh evidence로 증명돼야 구현 완료다.

- RED가 구현 전 실패하고 GREEN이 구현 후 통과
- CPU fallback success 후에도 failed endpoint OPEN
- 다음 auto request가 failed endpoint를 호출하지 않음
- 건강한 다른 local endpoint가 있을 때 정확히 한 번 선택
- direct route는 명시적 fallback이 없으면 묵시적 모델 교체 없음
- cooldown/half-open single-flight 동시성 계약 통과
- endpoint role collision fail-closed
- focused tests, LangChain4j purity, sourceSet hygiene, compileJava, `:app:classes`, bootJar 통과
- count-only secret scan 0
- final diff가 declared files/hunks에 한정
- 실제 GPU/endpoint proof가 없으면 `desktopFinalProof=evidence_needed`로 정직하게 유지

## 17. 현재 남은 evidence_needed

```text
evidence_needed: effective endpoint-to-GPU role ownership
verify with: owner-approved launch configuration and one device-isolated generation per endpoint

evidence_needed: target dirty hunk ownership and fresh preimages
verify with: source-edit three-way preflight plus exact target hashes in a clean owned worktree or explicit lease

evidence_needed: post-patch real request lineage
verify with: one fresh request showing primary/fallback endpoint hashes, physical attempt counts, and no raw content
```

현재 목표에 대한 판정:

```text
hardwareRepair=outside_source_scope
applicationResilienceGap=proven
sourceDirectiveWritten=true
applicationSourceChanged=false
```

[DONE]

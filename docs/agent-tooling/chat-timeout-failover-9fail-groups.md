# LlmRouterRuntimeDeviceFailoverTest 9실패 분류 (추정 — 확정은 코덱스 RED 몫)

근거 XML: `data/agent-handoff/codex-autonomy/codex-plan5-core-truth-e63ed263/verify/runtime-failover-observation/TEST-ai.abandonware.nova.orch.aop.LlmRouterRuntimeDeviceFailoverTest.xml` (tests=15, failures=9, errors=0, skipped=0).
집계 명령: `python -B scripts\junit_owned_summary.py --xml-dir <dir> --class ai.abandonware.nova.orch.aop.LlmRouterRuntimeDeviceFailoverTest --json`

## 공통 분기점 (정적 추정)

`FallbackAwareChatModel.chat(...)` catch 블록 `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java:169-198`:

1. `sameRequestFallbackAllowed(ex, failureClass)` (`:473-480`)가 `false`면 같은 요청 fallback 금지.
   허용 조합은 두 가지뿐: `GPU_DEVICE_LOST + local_endpoint_open`, `RATE_LIMIT_COOLDOWN + local_backend_busy`
   (주석: "HTTP errors, timeouts, blank responses and zero received tokens do not prove non-execution").
2. `:187-197` fallback 미시도 + failureClass ∈ {HEALTH_DOWN, PROVIDER_ERROR, TIMEOUT_SOFT, STREAM_ERROR, RESPONSE_MODEL_UNVERIFIED} 이면
   `LlmGatewayException("Provider execution is unconfirmed", failureClass, "provider_execution_uncertain")`으로 래핑.
3. 위 wrap 집합 밖이면 원본 예외 `throw ex` → InternalServerException 등이 그대로 전파.

## 묶음 (예외 타입 + 메시지 첫 줄 기준)

| 그룹 | 테스트 | 예외 첫 줄 | 추정 failureClass | 추정 차단 지점 |
|---|---|---|---|---|
| A. unconfirmed 래핑 (2) | `nonGpuRuntimeFailureKeepsConfiguredCloudFallbackPrecedence`, `allAutoLocalEndpointsOpenStillTriesTheSecondCloud` | `LlmGatewayException: Provider execution is unconfirmed` | PROVIDER_ERROR/HEALTH_DOWN 계열 | `:193-196` wrap — 기대: 다음 클라우드로 진행 |
| B. "GPU is lost" 원본 전파 (3) | `stalledFirstCloudLeavesTimeForSecondCloudInsideOriginalDeadline`, `fallbackKeepsTheOriginalContextCapacityFloor`, `firstCloudFailureContinuesToTheNextRegisteredCloudExactlyOnce` | `InternalServerException: {"error":"GPU is lost"}` | GPU_DEVICE_LOST (`LlmGatewayFailureClassifier.java:272-276`) | sameRequestFallbackAllowed=false(GPU_DEVICE_LOST지만 reason≠`local_endpoint_open`) + wrap 집합 밖 → `:198` 원본 throw |
| C. "invalid main_gpu" 원본 전파 (2) | `runtimeGpuLossUsesExplicitDifferentLocalDeviceFallbackExactlyOnce`, `runtimeDeviceFallbackEligibilityIsCheckedOnlyAfterPrimaryFailure` | `InternalServerException: {"error":"invalid main_gpu selection (available devices: 0)"}` | GPU_DEVICE_LOST (`:272` main_gpu + available devices: 0) | B와 동일 |
| D. vram_oom 원본 전파 (1) | `runtimeVramExhaustionUsesExplicitDifferentLocalDeviceFallbackExactlyOnce` | `InternalServerException: {"error":{"code":"vram_oom","message":"CUDA out of memory"}}` | VRAM_OOM (`:278-279`) | allowed 조합 아님 + wrap 집합 밖 → `:198` 원본 throw |
| E. 네이티브 분류 실패 (1) | `http200GpuErrorEnvelopeUsesNativeAdapterAndDeviceFallbackExactlyOnce` | `LlmGatewayException: Ollama native response reported a classified failure` | HTTP 200 본문의 classified failure (어댑터 단에서 먼저 LlmGatewayException) | fallback 미도달 — 추정 |

## "Provider execution is unconfirmed" 연결 수

- **직접 연결 2/9** — 그룹 A (메시지 자체가 unconfirmed).
- **같은 게이트 공유 9/9(추정)** — 전부 `sameRequestFallbackAllowed=false` 분기에서 멈췄을 가능성이 높다. 다만 B/C/D는 wrap 집합 밖 failureClass라 원본이 전파되고 E는 어댑터 예외이므로, "unconfirmed" 문자열이 직접 나오는 건 A의 2건뿐이다.

## 판정

- 위 분류는 **추정(estimate)** — 정적 읽기 + XML 첫 줄 기준. 테스트 기대(fallback 진입·횟수·다음 클라우드)와 실제 차단 지점의 대응은 코덱스 RED→GREEN 작업에서 확정한다.
- 반례/한계: `LlmRouterAspect` 측(재시도 횟수·라우트 순서)이 같은 증상을 만들 수 있고, `fallbackSupplier`/`nextFallbackResolver` 구성이 테스트별로 다르면 그룹 경계가 달라진다. XML assertion 본문(기대값 vs 실제 호출 횟수)을 코덱스가 대조해야 한다.

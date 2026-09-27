# GPU Pressure Cloud Offload Implementation Plan v2

> **Desktop executor:** source mutation 전에 `demo1-source-edit-three-way-preflight`를 실행하고, 구현에는 `superpowers:test-driven-development`, 단계 실행에는 `superpowers:executing-plans`, 완료 선언 전에는 `superpowers:verification-before-completion`을 사용한다.

**Goal:** RTX 3060/3090의 역할별 GPU 압력을 요청과 독립적으로 측정하고, 새 로컬 채팅 요청을 기존 cloud fallback route로 선제 전환하되 비용·재시도·계보를 제한한다.

**Architecture:** `GpuPressureMonitor`가 고유 sample sequence로 hysteresis를 계산하고 immutable snapshot을 게시한다. `GpuPressureRouteGuard`는 snapshot을 순수 route decision으로 바꾼다. `CloudOffloadQuotaGuard`는 route별 자동 전환 횟수를 제한한다. 실제 route 변경은 기존 `LlmRouterAspect.routeWithGateway(...)`만 수행한다.

**Stack:** Java 21/Spring Boot, Spring `@Scheduled`, existing LangChain4j `1.0.1`, JUnit 5, Mockito, existing loopback `HttpServer`, Gradle wrapper.

**Mutation owner:** Desktop only. 이 계획 작성 자체는 애플리케이션 소스를 수정하지 않는다.

## Global invariants

- 활성 backend 파일은 root `main/java`, `main/resources`, `src/test/java`만 사용한다.
- `java/ai/.../LlmRouterAspect.java`, `app/src/main/java`, `project/src/main/java`, archives, generated output은 수정하지 않는다.
- route 변경 권한은 `LlmRouterAspect`에만 남긴다.
- sample counter는 요청 횟수가 아니라 고유 `sampleSequence`당 한 번만 증가한다.
- pressure latch 중 cloud 오류는 local retry로 이어지지 않는다.
- disabled 또는 observe 모드는 기존 route 결과를 바꾸지 않는다.
- provider/API key 이름과 값, public HTTP API, DB, embedding, LangChain4j dependency를 바꾸지 않는다.
- RED를 먼저 확인하고 최소 구현으로 GREEN을 만든 뒤 다음 task로 이동한다.
- 실제 출력에는 raw GPU process data, prompt, response body, key, header, environment dump를 남기지 않는다.

## Desktop verification environment

모든 Gradle 명령 전에 같은 PowerShell 세션에서 실행한다.

```powershell
Set-Location 'C:\AbandonWare\demo-1\demo-1\src'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-gpu-pressure'
$env:GRADLE_USER_HOME = 'C:\AbandonWare\.gradle\desktop-gpu-pressure'
$gpuPressureProjectCache = 'C:\AbandonWare\.gradle-project-cache\desktop-gpu-pressure'
```

`bootRun`은 동일 host/cache에서 병렬 실행하지 않는다.

## Task 0: Freeze Desktop preflight and preimages

**Read:**

- `AGENTS.md`
- `main/java/com/example/lms/health/GpuHardwareDiagnostics.java`
- `main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java`
- `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
- `main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java`
- `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java`
- `main/resources/application-llm.yaml`

**Step 1: Run mutation gates**

```powershell
git worktree list
git branch --show-current
git status --short
Test-Path .git\index.lock
```

Expected: Desktop-owned branch/root, target-file overlap 없음, index lock `False`. 하나라도 다르면 `HOLD`.

**Step 2: Prove active source and version purity**

```powershell
rg -n 'srcDirs|java_clean|main/java|main/resources' build.gradle settings.gradle app\build.gradle
rg -n 'dev\.langchain4j|1\.0\.1|beta' build.gradle app\build.gradle gradle\libs.versions.toml
```

Expected: root backend source가 활성이고 LangChain4j가 모두 `1.0.1`; mixed/beta이면 `REJECT`.

**Step 3: Record preimage hashes without source writes**

```powershell
$gpuPressureTargets = @(
  'main\java\com\example\lms\llm\gateway\LlmGatewayProperties.java',
  'main\java\com\example\lms\llm\gateway\LlmGatewayBreadcrumbPublisher.java',
  'main\java\ai\abandonware\nova\orch\aop\LlmRouterAspect.java',
  'main\java\ai\abandonware\nova\autoconfig\NovaOrchestrationAutoConfiguration.java',
  'main\resources\application-llm.yaml',
  'src\test\java\ai\abandonware\nova\orch\aop\LlmRouterRequestTimelineTest.java'
)
$gpuPressureTargets | ForEach-Object { Get-FileHash -Algorithm SHA256 -LiteralPath $_ }
```

Expected: 선언한 파일만 기록. preimage가 지시서 baseline과 달라졌으면 소스를 다시 읽고 계획을 재평가한다.

## Task 1: Bind and validate the policy configuration

**Files:**

- Modify: `main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java`
- Modify: `main/resources/application-llm.yaml`
- Create: `src/test/java/com/example/lms/llm/gateway/LlmGatewayPropertiesGpuPressureTest.java`

**Step 1: Write RED binding tests**

테스트는 다음 기본값을 정확히 요구한다.

```text
enabled=false
sampleIntervalMs=2000
staleAfterMs=6000
firstSampleWaitMs=1100
hardUtilizationPct=95
hardMemoryRatio=0.92
softUtilizationPct=85
softMemoryRatio=0.82
exitUtilizationPct=60
exitMemoryRatio=0.70
enterSamples=3
exitSamples=5
minCloudDwellMs=30000
maxOffloadsPerWindow=60
quotaWindowMs=3600000
light/vision -> fast-helper-embedding
gemma/judge/coder -> primary-chat-rerank-heavy
```

추가 RED assertions:

- invalid ordering `exit >= soft` 또는 `soft >= hard`는 안전 기본값으로 복구된다.
- interval/stale/count/dwell/quota가 명세 범위로 clamp된다.
- 설정되지 않은 route는 mapping에 자동 추가되지 않는다.
- 기존 `cloud`, `probe`, `specRegistry` 기본값은 변하지 않는다.

**Step 2: Confirm RED**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'com.example.lms.llm.gateway.LlmGatewayPropertiesGpuPressureTest'
```

Expected: 새 nested property가 없어서 실패.

**Step 3: Add the smallest binding implementation**

- `LlmGatewayProperties.GpuPressure` nested class와 `getGpuPressure()/setGpuPressure(...)`만 추가한다.
- 기본 route map은 `LinkedHashMap`의 명시적 key/value로 만든다.
- effective getter에서 clamp와 threshold ordering을 적용한다.
- YAML은 기존 `llm.gateway` 아래에만 추가한다.
- 기존 property 이름을 rename하지 않는다.

**Step 4: Confirm GREEN**

같은 focused command가 PASS해야 한다.

## Task 2: Implement request-independent sampling and hysteresis

**Files:**

- Create: `main/java/com/example/lms/llm/gateway/GpuPressureMonitor.java`
- Create: `src/test/java/com/example/lms/llm/gateway/GpuPressureMonitorTest.java`

**Step 1: Write RED state-machine tests**

가짜 `Supplier<Map<String,Object>>`와 monotonic clock을 주입한다. 실제 `nvidia-smi`나 sleep을 사용하지 않는다.

필수 테스트 이름/계약:

```text
hardUtilizationLatchesOnFirstFreshSample
hardMemoryLatchesOnFirstFreshSample
softPressureNeedsThreeDistinctSequences
repeatedReadsDoNotAdvanceCounters
recoveryNeedsFiveFreshSamplesAndMinimumDwell
unavailableBeforeLatchStaysLocalObserve
unavailableAfterLatchCannotClearPressure
missingAndAmbiguousRolesAreNotGuessed
rolesTransitionIndependently
twentyConcurrentFirstReadersShareOneSample
schedulerFailureDoesNotPoisonNextSample
```

**Step 2: Confirm RED**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'com.example.lms.llm.gateway.GpuPressureMonitorTest'
```

Expected: class absent.

**Step 3: Implement the monitor**

- production constructor는 `Environment`, `LlmGatewayProperties`를 받고 `GpuHardwareDiagnostics.snapshot(env)`를 사용한다.
- package-private test constructor는 snapshot supplier와 monotonic time supplier를 받는다.
- `@Scheduled(fixedDelayString=..., initialDelayString="0")` method는 disabled이면 즉시 return한다.
- raw map을 외부로 반환하지 않고 immutable `RolePressureSnapshot`으로 축소한다.
- one role에서 device count가 정확히 1이 아니면 missing/ambiguous reason을 설정한다.
- `lastProcessedSequence`로 동일 sample 재처리를 차단한다.
- 최초 sample만 `AtomicReference<CompletableFuture<...>>` single-flight로 공유한다.
- monitor 예외는 state를 unavailable로 기록하고 scheduler 밖으로 던지지 않는다.

상태 전이 순서:

```text
hard hot -> immediate PRESSURED
soft hot -> enterCount + 1; third distinct sample -> PRESSURED
latched + cool -> recoveryCount + 1
latched + five cool + dwell met -> NORMAL
latched + stale/unavailable/missing/ambiguous -> remain PRESSURED
unlatched + stale/unavailable/missing/ambiguous -> UNOBSERVED/NORMAL observe
```

**Step 4: Confirm GREEN and run diagnostics regression**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'com.example.lms.llm.gateway.GpuPressureMonitorTest' --tests 'com.example.lms.health.GpuHardwareDiagnosticsTest'
```

Expected: PASS, `GpuHardwareDiagnostics.java` source change 없음.

## Task 3: Convert pressure into bounded route decisions

**Files:**

- Create: `main/java/com/example/lms/llm/gateway/GpuPressureRouteGuard.java`
- Create: `main/java/com/example/lms/llm/gateway/CloudOffloadQuotaGuard.java`
- Create: `src/test/java/com/example/lms/llm/gateway/GpuPressureRouteGuardTest.java`
- Create: `src/test/java/com/example/lms/llm/gateway/CloudOffloadQuotaGuardTest.java`

**Step 1: Write RED guard tests**

```text
disabledIsNotApplicable
nonLocalProviderIsNotApplicable
embeddingStageIsNotApplicable
unmappedRouteIsNotApplicable
observeReturnsWouldOffloadWithoutMutation
enforceReturnsCloudRequired
staleBeforeLatchAllowsLocal
staleAfterLatchRequiresCloud
routeUsesDeclaredRoleNotEndpointPort
```

**Step 2: Write RED quota tests**

```text
allowsExactlyConfiguredMaximum
rejectsAttemptAfterMaximum
newWindowRestoresAllowance
routesHaveIndependentCounters
concurrentAcquireNeverExceedsMaximum
```

**Step 3: Confirm RED**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'com.example.lms.llm.gateway.GpuPressureRouteGuardTest' --tests 'com.example.lms.llm.gateway.CloudOffloadQuotaGuardTest'
```

**Step 4: Implement pure policy classes**

- guard는 provider/stage/route map과 monitor snapshot만 읽는다.
- `LOCAL_ALLOWED`, `CLOUD_REQUIRED`, `CLOUD_WOULD_BE_REQUIRED`, `NOT_APPLICABLE` 외 action을 만들지 않는다.
- quota는 route별 fixed window `(startNanos, used)`를 atomic update한다.
- quota는 token/금액을 계산하거나 provider를 호출하지 않는다.
- 모든 reason은 `SafeRedactor.traceLabelOrFallback`에 안전한 allowlisted label이다.

**Step 5: Confirm GREEN**

같은 focused command가 PASS해야 한다.

## Task 4: Add policy breadcrumbs without changing failure semantics

**Files:**

- Modify: `main/java/com/example/lms/llm/gateway/LlmGatewayBreadcrumbPublisher.java`
- Modify: `src/test/java/com/example/lms/llm/gateway/LlmGatewayBreadcrumbPublisherTest.java`

**Step 1: Write RED test**

`publishPolicyRedirect(fromKey, toKey, reason, decision)`가 다음만 trace/SSE에 넣도록 요구한다.

```text
fromKey, toKey, reason, mode, deviceRole, sampleSequence,
sampleAgeMs, enterCount, recoveryCount, latchAgeMs, quotaRemaining
```

prompt, model response, raw GPU name, raw stdout, credential-like value는 payload에 없어야 한다.

**Step 2: Confirm RED**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'com.example.lms.llm.gateway.LlmGatewayBreadcrumbPublisherTest'
```

**Step 3: Add one publisher method**

- existing `safePayload`, `trace`, `emit`을 재사용한다.
- type은 `llm.gateway.policyRedirect`로 고정한다.
- 기존 `publishFallback`과 failure class를 사용하지 않는다.

**Step 4: Confirm GREEN**

같은 focused command가 PASS해야 한다.

## Task 5: Integrate cloud-only preemption at the single router boundary

**Files:**

- Modify: `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`
- Modify: `main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java`
- Modify: `src/test/java/ai/abandonware/nova/orch/aop/LlmRouterRequestTimelineTest.java`

**Step 1: Write RED loopback tests**

fake pressure guard와 local/cloud `HttpServer` counter를 사용한다.

```text
hardPressureFirstRequestUsesCloudWithoutLocalExchange
pressureCloudIneligibleReturnsExpectedFailureWithoutAnyExchange
pressureQuotaExhaustedReturnsExpectedFailureWithoutAnyExchange
pressureCloud429NeverRetriesLocal
observePressurePreservesLocalRoute
directHttpCloudLineageDoesNotRequireProviderAttemptFlag
```

각 테스트는 request timeline row count, local/cloud exchange count, prompt/options hash presence를 명시적으로 assert한다.

**Step 2: Confirm RED**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'ai.abandonware.nova.orch.aop.LlmRouterRequestTimelineTest'
```

Expected: 새 pressure 계약이 없어서 신규 cases 실패; 기존 cases는 계속 PASS.

**Step 3: Preserve constructor compatibility**

- `GpuPressureRouteGuard`와 `CloudOffloadQuotaGuard` nullable fields를 추가한다.
- 현재 constructor overload는 그대로 유지하고 마지막 확장 constructor로 `null`을 위임한다.
- auto-configuration만 `ObjectProvider`로 두 새 bean을 주입한다.
- pressure feature가 disabled이면 bean 부재 또는 `NOT_APPLICABLE`로 기존 behavior와 동등해야 한다.

**Step 4: Add one preemptive branch after primary eligibility**

`routeWithGateway(...)`에서 selected local route의 기존 eligibility 처리 뒤, local model을 만들기 전에 다음 순서를 구현한다.

```text
decision = pressureGuard.decide(sel.key, sel.cfg.provider, stage)
if action != CLOUD_REQUIRED: existing path
fallback = existing fallbackSelection(sel)
if absent: expected failure gpu_pressure_cloud_unavailable
cloudEligibility = gatewayProbeService.evaluate(fallback)
if ineligible: expected failure gpu_pressure_cloud_unavailable
if !quota.tryAcquire(sel.key): expected failure gpu_pressure_cloud_budget_exhausted
publishPolicyRedirect(...)
return recordOutcomes(buildRoutedModel(fallback, ca, false), fallback.key)
```

불변식:

- preemptive cloud 모델을 `FallbackAwareChatModel`로 감싸지 않는다.
- local model builder를 먼저 호출하지 않는다.
- pressure expected failure는 HTTP attempt marker를 만들지 않는다.
- cloud physical attempt가 요청의 첫/유일 시도이므로 ledger role은 `primary`여도 정상이다. policy redirect trace가 원래 local route를 보존한다.
- 기존 provider-failure lazy fallback branch는 pressure가 적용되지 않은 요청에 대해 그대로 둔다.

**Step 5: Implement typed expected failures**

기존 `ExpectedFailureChatModel`과 `ModelRuntimeHealthTracker`의 no-exchange evidence 형식을 재사용한다.

```text
gpu_pressure_cloud_unavailable
gpu_pressure_cloud_budget_exhausted
```

사용자 메시지는 retryable하고 credential/provider 세부를 노출하지 않는다. 예: `Local GPU is reserved for an interactive workload; the cloud route is unavailable. Retry shortly.`

**Step 6: Confirm GREEN and regress fallback**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test --tests 'ai.abandonware.nova.orch.aop.LlmRouterRequestTimelineTest' --tests 'com.example.lms.llm.gateway.FallbackAwareChatModelTest' --tests 'com.example.lms.llm.gateway.HybridLlmGatewayProbeServiceTest'
```

Expected: 신규 pressure cases와 기존 lazy fallback cases 모두 PASS.

## Task 6: Focused build, lineage, recovery, and rollback proof

**Step 1: Run all changed-surface tests**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache test `
  --tests 'com.example.lms.llm.gateway.LlmGatewayPropertiesGpuPressureTest' `
  --tests 'com.example.lms.llm.gateway.GpuPressureMonitorTest' `
  --tests 'com.example.lms.llm.gateway.GpuPressureRouteGuardTest' `
  --tests 'com.example.lms.llm.gateway.CloudOffloadQuotaGuardTest' `
  --tests 'com.example.lms.llm.gateway.LlmGatewayBreadcrumbPublisherTest' `
  --tests 'ai.abandonware.nova.orch.aop.LlmRouterRequestTimelineTest' `
  --tests 'com.example.lms.llm.gateway.FallbackAwareChatModelTest' `
  --tests 'com.example.lms.llm.gateway.HybridLlmGatewayProbeServiceTest'
```

Expected: `BUILD SUCCESSFUL`, failure `0`.

**Step 2: Compile active modules**

```powershell
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache compileJava
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache :app:classes
.\gradlew.bat --project-cache-dir $gpuPressureProjectCache bootJar -x test
```

Expected: 각 명령 `BUILD SUCCESSFUL`.

**Step 3: Prove runtime lineage**

Desktop에서 credential 값은 출력하지 않고 presence boolean만 확인한 뒤, 실제 또는 승인된 loopback cloud route로 요청 한 건을 실행한다.

PASS row predicate:

```text
promptHash present = true
optionsHash present = true
cloud provider/model/protocol/endpoint labels present = true
cloud attempt rows = 1
local clientHttpExchangeObserved=true rows = 0
cloud clientHttpExchangeObserved = true
cloud clientHttpResponseObserved = true
responseObserved = true
providerAttemptObserved may be false for direct HTTP
```

UI 응답만 있거나 row가 누락되면 `runtimeLineageVerdict=HOLD`.

**Step 4: Prove live device isolation and recovery**

- 3060 role pressure에서 `light/vision`만 pressure action을 받는지 확인한다.
- 3090 role pressure에서 `gemma/judge/coder`만 받는지 확인한다.
- synthetic sequence로 5회 clean + 30초 monotonic dwell 후 local 복귀를 기록한다.
- 실제 게임 중 프레임타임 개선은 별도 측정값이며 없으면 과장하지 않는다.

**Step 5: Prove rollback**

```powershell
$env:LLM_GATEWAY_GPU_PRESSURE_ENABLED = 'false'
```

같은 pressure fixture에서 route decision이 `NOT_APPLICABLE`, `nvidia-smi` 추가 실행 `0`, 기존 local/fallback 테스트 PASS여야 한다.

**Step 6: Count-only secret scan**

```powershell
$gpuPressureChanged = @(
  'main\java\com\example\lms\llm\gateway\GpuPressureMonitor.java',
  'main\java\com\example\lms\llm\gateway\GpuPressureRouteGuard.java',
  'main\java\com\example\lms\llm\gateway\CloudOffloadQuotaGuard.java',
  'main\java\com\example\lms\llm\gateway\LlmGatewayProperties.java',
  'main\java\com\example\lms\llm\gateway\LlmGatewayBreadcrumbPublisher.java',
  'main\java\ai\abandonware\nova\orch\aop\LlmRouterAspect.java',
  'main\java\ai\abandonware\nova\autoconfig\NovaOrchestrationAutoConfiguration.java',
  'main\resources\application-llm.yaml'
) | Where-Object { Test-Path -LiteralPath $_ }
$gpuPressureSecretPattern = '(?i)(authorization\s*[:=]\s*bearer\s+\S+|api[-_]?key\s*[:=]\s*["''][^${][^"'']{8,}|sk-[a-z0-9_-]{12,})'
@($gpuPressureChanged | Select-String -Pattern $gpuPressureSecretPattern).Count
```

Expected: `0`. 값이나 matching line을 출력하지 않는다.

**Step 7: Record postimages and diff scope**

```powershell
git diff --check
git diff --name-only
$gpuPressureChanged | ForEach-Object { Get-FileHash -Algorithm SHA256 -LiteralPath $_ }
```

Expected: declared files only, whitespace error 없음. commit/push/deploy는 별도 승인 없이는 수행하지 않는다.

## Stop conditions and failure classes

즉시 `HOLD`:

- `index-lock-present`, dirty target overlap, source lease conflict, changed preimage
- active sourceSet 불일치 또는 비활성 mirror 수정
- mixed/non-`1.0.1` LangChain4j
- `nvidia-smi`가 interval/first-flight 상한보다 많이 실행됨
- cloud ineligible/quota/error 뒤 local HTTP exchange 발생
- observe/disabled가 route를 변경함
- raw prompt, response, process list, key/header가 trace/log에 나타남
- 정확한 verification command를 재현할 수 없음

구현 테스트·build는 PASS했지만 실제 Desktop provider attempt/response lineage가 없으면 source 결과를 완료로 부르지 않고 `runtimeLineageVerdict=HOLD`, `desktopFinalProof=evidence_needed`를 유지한다.

## Plan self-review

- 첫 요청 공백은 scheduled warm sample + bounded single-flight로 닫았다.
- 다중 GPU 오판은 route-to-role exact map과 0/2+ device fail-closed로 닫았다.
- 플래핑은 3-sample 진입, 5-sample 회복, 30초 dwell로 제한했다.
- 비용 폭증은 route별 fixed-window quota로 제한했다.
- cloud 오류의 local 재시도는 preemptive cloud 모델에 fallback wrapper를 적용하지 않는 것으로 차단했다.
- 직접 HTTP 경로의 `providerAttemptObserved=false`를 정상 계보로 허용하고 실제 exchange/response/hash를 필수화했다.
- 새 전역 skill 대신 기존 `demo1-local-llm-gpu-gateway`와 검증 가능한 GoalContract/run artifact를 확장한다.

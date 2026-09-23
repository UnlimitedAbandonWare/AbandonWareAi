# demo1 AI Debug Metrics Ledger Source Patch Directive v1.0

Generated: 2026-06-08 Asia/Seoul
Workspace: `C:\AbandonWare\demo-1\demo-1\src`

이 지시서는 `demo-1`의 기존 디버깅 기능에 AI 보조 계층을 넣기 위한 소스수정 지시서다.
목표는 디버깅 자체를 정량지표화하고, 실제 사용된 디버그 도구/경로를 기록하고, 운영자가 볼 수 있는
read-only 화면/API로 보여주는 것이다.

이 문서는 런타임 코드를 대신하지 않는다. 현재 레포 파일과 실제 명령 출력이 항상 우선이다.

## 0. 목표

구현할 기능:

1. `DebugEventStore`와 `TraceStore`에 이미 쌓이는 redacted 이벤트를 기반으로 디버깅 상태를 정량화한다.
2. 실패 패턴을 `Failure Pattern Analysis` 방식으로 요약하되, 원문 로그/질문/secret은 저장하지 않는다.
3. 디버깅에 사용된 도구, API, probe, source scanner, patch candidate, verification command를 usage ledger로 남긴다.
4. `/admin/debug-events` 또는 `/admin/dashboard`에서 최근 AI 디버그 지표와 사용 이력을 볼 수 있게 한다.
5. agent tool 경로에서는 `debug.trace.lookup`, `ops.snapshot`이 같은 요약을 읽을 수 있게 한다.

하지 않을 것:

- 새 RAG 아키텍처, 새 DebugEventStore, 새 TraceStore, 새 벡터 DB, 새 provider framework 생성.
- 원문 query, prompt, log body, API key, Authorization header, cookie, ownerToken 저장.
- 자동 패치/자동 실행을 기본 ON으로 켜기.
- `PromptBuilder.build(PromptContext)` 우회.
- LangChain4j `1.0.1` 순수성 변경.

## 1. 현재 레포 근거로 시작하기

패치 전 PowerShell에서 확인한다.

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
Get-Location
git rev-parse --show-toplevel 2>$null
git branch --show-current 2>$null
git status --short 2>$null
git worktree list 2>$null
if (Test-Path ".git\index.lock") { Write-Error "[AWX][debug-ai] index-lock-present - STOP"; exit 1 }

Get-ChildItem -Recurse -File -Depth 4 -Include settings.gradle,settings.gradle.kts,build.gradle,build.gradle.kts,gradlew.bat |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName

Get-ChildItem -Recurse -Directory -Depth 6 |
  Where-Object { $_.FullName -match '(src\\main\\java_clean|src\\main\\java|main\\java|project\\src\\main\\java)$' } |
  Sort-Object FullName |
  Select-Object -ExpandProperty FullName
```

기본 sourceSet bias:

- root runtime: `main/java`, `main/resources`
- root tests: `src/test/java`, `src/test/resources`
- `:app`: `app/src/main/java_clean`, `app/src/main/resources`
- inactive/reference unless proven: `app/src/main/java`, `project/src/main/java`, `demo-1`, `lms-core`, archives, backups

## 2. 기존 seam

먼저 아래 파일을 읽고, 없는 파일은 `evidence_needed`로 보고한다.

Core:

- `main/java/com/example/lms/debug/DebugEventStore.java`
- `main/java/com/example/lms/debug/DebugEvent.java`
- `main/java/com/example/lms/debug/DebugProbeType.java`
- `main/java/com/example/lms/debug/DebugEventSanitizer.java`
- `main/java/com/example/lms/search/TraceStore.java`
- `main/java/com/example/lms/trace/SafeRedactor.java`

API/UI:

- `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java`
- `main/java/com/example/lms/web/DebugEventsPageController.java`
- `main/resources/templates/debug-events.html`
- `main/resources/templates/dashboard.html`
- `main/resources/logback-spring.xml`

Agent tools:

- `main/java/com/abandonware/ai/agent/tool/impl/ops/DebugTraceLookupTool.java`
- `main/java/com/abandonware/ai/agent/tool/impl/ops/OpsSnapshotTool.java`
- `main/java/com/abandonware/ai/agent/tool/AgentToolInvoker.java`
- `main/resources/tool_manifest__kchat_gpt_pro.json`

RAG/debug producers:

- `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`
- `main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`
- `main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`
- `main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`
- `main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorServicePostProcessor.java`
- `main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java`
- `main/java/com/example/lms/learning/ops/RagLearningOpsDashboardService.java`

## 3. 설계 내용을 repo 언어로 변환

첨부 설계의 용어는 아래처럼 구현 지시로 치환한다.

| 설계 용어 | repo 구현 의미 | 주의 |
|---|---|---|
| Failure Pattern Analysis | DebugEvent/TraceStore 기반 실패 패턴 집계 서비스 | raw log 학습 금지 |
| RawSlotExtractor | 이벤트 하나에서 layer/probe/failureClass/fingerprint/timing을 뽑는 pure mapper | 원문 query 대신 hash/count |
| RawMatrixBuffer | bounded rolling aggregate, time bucket, fingerprint bucket | 새 DB 금지, 메모리 + NDJSON summary |
| 9 RawTile / CFVM RawTile | 9개 고정 tile DTO, 실패 가족별 압축 요약 | scalar/JSON-friendly |
| JB(Job Behavior) | 처리시간, retry, tool count, queue/timeout, verification state | 낮은 cardinality |
| CB(Chain Breakdown) | first failing layer, stage drop, breaker, provider disabled, starvation | reason code만 |
| MoE Strategy Selector | 다음 디버그 action 추천기 | 자동 패치 실행 기본 OFF |
| Plan DSL | 사용된 debug plan/tool/verification command 기록 | plan id와 result만 |
| MLA/SSE breadcrumb | UI/SSE에 보이는 redacted debug flow | `EventSource`, text node rendering |

## 4. 데이터 계약

새 DTO는 `main/java/com/example/lms/debug/ai/` 아래에 두는 것을 기본값으로 한다.
단, 기존 패키지가 다르면 현재 레포 스타일을 따른다.

### 4.1 DebugAiMetricSnapshot

필드 예시:

```text
schemaVersion: 1
generatedAt
windowMs
totalEvents
warnEvents
errorEvents
probeCounts
layerCounts
failureClassCounts
fingerprintHotspots
usedDebugTools
planUsage
tiles
scorecard
recommendations
```

### 4.2 DebugAiRawSlot

이벤트 1개에서 추출한 최소 단위다.

```text
eventId
tsMs
probe
layer
failureClass
fingerprintHash
sidHash
traceIdHash
requestIdHash
where
latencyMs
toolId
planId
verificationCommandHash
result
severity
```

허용 값:

- `queryHash`, `bodyHash`, `fingerprintHash`, `sidHash`, `traceIdHash`
- `count`, `bucket`, `durationMs`, `timeoutMs`, `returnedCount`, `afterFilterCount`
- `failureClass`, `disabledReason`, `stage`, `probe`, `toolId`

금지 값:

- raw query
- raw prompt
- raw full error body
- raw stacktrace 전체
- raw file content
- API key/client secret/token/password/cookie/Authorization header
- full env dump

### 4.3 DebugAiRawTile

9개 tile로 고정한다. tile index는 안정적인 enum/mapper로 결정한다.

```text
0 SPRING_CONTEXT
1 QUERY_TRANSFORMER
2 WEB_SEARCH
3 VECTOR_RETRIEVAL
4 KG_GRAPH
5 LLM_MODEL_GUARD
6 EVIDENCE_OUTPUT
7 AGENT_TOOL_USAGE
8 VERIFICATION_BUILD
```

필드 예시:

```text
tileIndex
tileName
eventCount
warnCount
errorCount
topFailureClass
topProbe
hotFingerprintHash
jbScore
cbScore
severityScore
confidence
recommendedAction
lastSeenTs
```

### 4.4 DebugAiUsageRecord

디버깅에 실제 사용한 경로를 기록한다.

```text
id
ts
actorType: user|agent|system
source: admin-ui|api|agent-tool|probe|gradle|patchdrop
toolId
endpoint
planId
targetHash
result
failureClass
verificationCommandHash
durationMs
sidHash
traceIdHash
```

사용 예:

- `/api/diagnostics/debug/events` 조회
- `/api/diagnostics/debug/fingerprints` 조회
- `/api/diagnostics/debug/ai-metrics` 조회
- `debug.trace.lookup` tool 호출
- `ops.snapshot` tool 호출
- `gradlew.bat compileJava -x test` verification 요약

## 5. 점수 산식

산식은 단순하고 설명 가능해야 한다.

### 5.1 JB Score

Job Behavior 점수는 실행 부담과 도구 사용 흐름을 나타낸다.

```text
jbScore = clamp01(
  latencyBucketWeight
  + retryWeight
  + toolUsageWeight
  + timeoutWeight
  + verificationWeight
)
```

입력 예:

- `durationMs` bucket
- `timeout=true`
- `cancel.suppressed`
- `toolId` count
- `verificationCommandHash` present
- `compile/test/bootJar` result bucket

### 5.2 CB Score

Chain Breakdown 점수는 파이프라인 붕괴 신호를 나타낸다.

```text
cbScore = clamp01(
  firstFailingLayerWeight
  + stageDropWeight
  + starvationWeight
  + breakerWeight
  + providerDisabledWeight
  + modelGuardWeight
)
```

입력 예:

- `outCount=0`
- `afterFilterStarved=true`
- `breaker_open`
- `provider-disabled`
- `EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH`
- `EvidenceList empty`

### 5.3 Debug Health Score

운영자에게 보여줄 최종 건강도다.

```text
debugHealthScore = clamp(100
  - errorEventsPenalty
  - repeatedFingerprintPenalty
  - starvationPenalty
  - secretLeakRiskPenalty
  - unclassifiedFailurePenalty
  + verifiedRecoveryBonus
)
```

### 5.4 Debug Usefulness Score

디버깅이 얼마나 쓸모 있었는지 측정한다.

```text
debugUsefulnessScore = clamp01(
  failureClassPresent
  + owningLayerPresent
  + recommendedActionPresent
  + verificationCommandPresent
  + redactionPass
  - evidenceNeededPenalty
)
```

## 6. 구현 범위

### P0: Passive metrics and ledger

파일 후보:

- `main/java/com/example/lms/debug/ai/DebugAiRawSlot.java`
- `main/java/com/example/lms/debug/ai/DebugAiRawTile.java`
- `main/java/com/example/lms/debug/ai/DebugAiMetricSnapshot.java`
- `main/java/com/example/lms/debug/ai/DebugAiUsageRecord.java`
- `main/java/com/example/lms/debug/ai/DebugAiMetricsService.java`
- `main/java/com/example/lms/api/DebugAiMetricsDiagnosticsController.java`

요구:

- `DebugEventStore.list(limit)`와 `listFingerprints(limit)`를 읽어서 snapshot을 만든다.
- `TraceStore.getAll()`은 allowlist key만 읽는다.
- usage ledger는 bounded in-memory ring + optional NDJSON summary로 시작한다.
- NDJSON은 `abandonware.debug.ndjson-dir` 아래 별도 파일을 쓰되, raw 값은 쓰지 않는다.
- DebugEventStore가 없거나 disabled면 API는 `available=false`와 reason만 반환한다.

### P1: UI viewer

파일 후보:

- `main/resources/templates/debug-events.html`
- `main/resources/templates/dashboard.html`
- `src/test/java/com/example/lms/web/*`

요구:

- `/admin/debug-events`에 `AI Metrics` 패널 추가.
- `/admin/dashboard`에는 compact card만 추가.
- JS는 `textContent`, `replaceChildren`, `createElement`를 사용한다.
- `innerHTML`로 untrusted event data를 렌더링하지 않는다.
- 긴 map/list는 count, first N, hash, bucket으로 clipping한다.

### P1: Agent tool usage visibility

파일 후보:

- `main/java/com/abandonware/ai/agent/tool/impl/ops/DebugTraceLookupTool.java`
- `main/java/com/abandonware/ai/agent/tool/impl/ops/OpsSnapshotTool.java`
- `main/java/com/example/lms/config/AgentToolOpsConfig.java`
- `main/resources/tool_manifest__kchat_gpt_pro.json`

요구:

- 기존 `debug.trace.lookup` 응답에 `aiMetricsSummary`를 optional로 추가하거나 새 read-only tool을 추가한다.
- 새 tool이 필요하면 id는 `debug.ai.metrics.lookup`으로 한다.
- tool response에는 raw DebugEvent 전체를 넣지 말고 DTO 요약만 넣는다.
- tool 호출 자체는 `DebugAiUsageRecord`로 기록한다.

### P2: Recommendation only

파일 후보:

- `main/java/com/example/lms/debug/ai/DebugAiRecommendationService.java`

요구:

- 자동 patch, 자동 provider call, 자동 Plan DSL 실행 금지.
- 추천은 reason code와 next command만 반환한다.
- 예: `run_focused_test`, `inspect_provider_disabled`, `check_prompt_boundary`, `run_bootjar`, `open_debug_events`.
- confidence는 `LOW|MEDIUM|HIGH` 중 하나.

## 7. Configuration

`main/resources/application.yml` 또는 기존 debug config 파일에 최소 속성을 추가한다.

```yaml
lms:
  debug:
    ai-metrics:
      enabled: ${LMS_DEBUG_AI_METRICS_ENABLED:true}
      persist-enabled: ${LMS_DEBUG_AI_METRICS_PERSIST_ENABLED:true}
      max-events: ${LMS_DEBUG_AI_METRICS_MAX_EVENTS:300}
      usage-ring-size: ${LMS_DEBUG_AI_USAGE_RING_SIZE:200}
      window-ms: ${LMS_DEBUG_AI_METRICS_WINDOW_MS:900000}
      recommendations-enabled: ${LMS_DEBUG_AI_RECOMMENDATIONS_ENABLED:false}
```

주의:

- 기존 `lms.debug.events.*`와 `abandonware.debug.ndjson.*` 의미를 바꾸지 않는다.
- production strict profile이 따로 있으면 read-only metrics는 켜도 자동 action은 끈다.
- secret/env 파일은 수정하지 않는다.

## 8. API 계약

새 controller는 admin/security 기존 규칙을 따른다.

```text
GET /api/diagnostics/debug/ai-metrics?limit=120
GET /api/diagnostics/debug/ai-metrics/usage?limit=50
GET /api/diagnostics/debug/ai-metrics/tiles
GET /api/diagnostics/debug/ai-metrics/stream?limit=50   # optional SSE
```

응답 예:

```json
{
  "available": true,
  "schemaVersion": 1,
  "debugHealthScore": 86,
  "debugUsefulnessScore": 0.78,
  "totalEvents": 42,
  "tiles": [
    {
      "tileIndex": 2,
      "tileName": "WEB_SEARCH",
      "eventCount": 11,
      "topFailureClass": "provider-disabled",
      "jbScore": 0.34,
      "cbScore": 0.61,
      "recommendedAction": "inspect_provider_disabled"
    }
  ],
  "usedDebugTools": [
    {"toolId": "debug.trace.lookup", "count": 3}
  ]
}
```

## 9. Test-first patch requirements

가능하면 RED test를 먼저 작성한다.

필수 테스트 후보:

- `DebugAiMetricsServiceTest`
  - DebugEventStore 이벤트에서 9개 tile을 만든다.
  - provider-disabled, timeout, starvation, modelGuard, evidence-empty를 분류한다.
  - raw query/secret 문자열이 snapshot에 남지 않는다.
  - repeated fingerprint가 score에 반영된다.

- `DebugAiMetricsDiagnosticsControllerTest`
  - `/api/diagnostics/debug/ai-metrics`가 bounded JSON을 반환한다.
  - store unavailable/disabled일 때 fail-soft 응답을 반환한다.
  - limit이 clamp된다.

- `DebugAiMetricsToolTest` 또는 기존 tool test 확장
  - `debug.trace.lookup` 또는 `debug.ai.metrics.lookup`이 요약만 반환한다.
  - raw event data를 그대로 노출하지 않는다.

- `DebugEventsTemplateTest` 또는 기존 web contract test 확장
  - `/api/diagnostics/debug/ai-metrics` 문자열이 템플릿에 있다.
  - `innerHTML` 사용이 늘지 않는다.
  - `textContent` 또는 `replaceChildren` 기반 렌더링이 있다.

## 10. Verification

Desktop PowerShell 기준:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Push-Location $Root
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = "desktop"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$ProjectCache = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null

.\gradlew.bat test --tests "*DebugAiMetrics*" --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat test --tests "*DebugEvent*" --tests "*AgentTool*" --tests "*LearningDataTemplateTest*" --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat processResources --no-daemon --project-cache-dir "$ProjectCache"
.\gradlew.bat bootJar -x test --no-daemon --project-cache-dir "$ProjectCache"

$secretHits = Get-ChildItem .\main\java,.\main\resources,.\src\test\java -Recurse -File -Include *.java,*.yml,*.yaml,*.html,*.js |
  Select-String -Pattern "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization:\s*Bearer\s+\S+" -ErrorAction SilentlyContinue
Write-Host "[AWX][debug-ai][security] secretHits=$($secretHits.Count)"
```

브라우저 smoke는 boot가 실제로 떠 있을 때만 수행한다.

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/diagnostics/debug/ai-metrics?limit=20" -ErrorAction Stop |
  ConvertTo-Json -Depth 8
```

통과 기준:

- focused tests pass.
- `secretHits=0`.
- 새 DTO/API/UI 응답에 raw query/prompt/secret이 없다.
- DebugEventStore가 비어 있어도 `available=true`, `totalEvents=0`, empty tiles summary로 fail-soft.
- DebugEventStore disabled/unavailable이면 `available=false`, `disabledReason`만 반환.
- compile/processResources/bootJar pass.

## 11. Failure classifier

주 failure class는 하나만 고른다.

```text
wrong-sourceset
index-lock-conflict
patch-drop-pending
secret-leak-risk
debug-ai-redaction
debug-ai-schema
debug-ai-score-drift
debug-ai-ui-render
debug-ai-usage-ledger
spring-bean
spring-bind
cannot-find-symbol
template-contract
langchain4j-version-purity
gradle-cache-collision
other
```

## 12. Final report format

```md
## 요약
- 실제 수정 범위, 검증 상태, 남은 blocker만 2~5줄.

## Observation
- 실행한 명령
- repo root / active sourceSets
- 기존 DebugEventStore/API/UI/tool seam 확인 결과
- Decomposition decision
- evidence_needed

## Patch
파일별:
- Observation
- Before/After 요약
- Minimal diff 요약
- 왜 이 파일만 수정했는지
- secret masking method
- rollback note

## Metrics Contract
- 새 snapshot/slot/tile/usage schema
- score 산식
- redaction allowlist

## Verification
각 명령별:
- Command
- Expected
- Observed
- Failure classification
- Retry decision

## Risks & Next
- 최대 5개
- counterexample/limitation 1개
- confidence: L/M/H
- next single most urgent patch
```

## 13. One-shot task for a source modifier

이번 패스의 기본 목표:

1. 현재 root/sourceSet과 기존 debug API/UI/tool seam을 확인한다.
2. `DebugAiMetricsService`와 DTO를 test-first로 추가한다.
3. `DebugEventsDiagnosticsController` 옆에 read-only AI metrics endpoint를 추가한다.
4. `/admin/debug-events`에 AI Metrics 패널을 추가한다.
5. agent tool에는 optional summary만 추가한다.
6. 저장은 bounded ring + redacted NDJSON summary만 허용한다.
7. 추천 기능은 `recommendations-enabled=false` 기본값으로 reason-only 응답만 만든다.
8. focused tests -> hygiene -> compileJava -> processResources -> bootJar 순서로 검증한다.

Decomposition decision:

```text
mode: 3-way
reason: sourceSet/API/UI/tool seams가 모두 걸려 있고 redaction/security가 핵심이므로 분리 조사 필요
branches:
  A: DebugEventStore/TraceStore/schema branch
  B: API/UI/agent-tool usage ledger branch
  C: verification/redaction/dashboard contract branch
```

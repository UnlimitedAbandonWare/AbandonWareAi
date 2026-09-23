# demo1 — GraphRAG Brain State / MoE / Anchor / CFVM / ExtremeZ / HYPERNOVA
# Desktop Canonical Codex Safe Patch Directive

> **대상**: Codex / OpenCode / Antigravity (Desktop 기준)
> **범위**: `C:\AbandonWare\demo-1\demo-1\src` — 데스크탑 캐노니컬 루트
> **모드**: Safe Patch — 대규모 리팩터링 금지. 최소 diff + 체크포인트 로그만.
> **선행 필독**: `AGENTS.md` (동 디렉터리) → `Abandon.md` (user_global 규칙)

---

## 0. 시작 전 필수 체크리스트 (Desktop Pre-flight)

```powershell
# 1. 워크트리 / 브랜치 / 락 확인
git worktree list
git branch --show-current
git status --short
Test-Path .git\index.lock          # true면 STOP → index-lock-conflict 분류

# 2. PatchDrop 미적용 번들 확인
Get-ChildItem __patch_drop__\*.patch | Where-Object { $_.Name -notmatch 'applied|rejected|orphan|superseded' }

# 3. 포트 충돌
netstat -ano | findstr ":8080 :8081"

# 4. 소스셋 근거 확인 (빌드 후 activeSourceSet 기준)
.\gradlew.bat :dependencies --configuration runtimeClasspath | Select-String "langchain4j"
```

**중단 조건**: `index-lock-conflict` / `worktree-overlap` / `patch-drop-pending` / `port-conflict` 발생 시 즉시 멈추고 사유를 보고한다.

---

## 1. 이 지시서의 목적 (What & Why)

사용자가 포트폴리오/인터뷰용으로 설명한 6가지 아키텍처 확장을 **기존 소스를 최소한으로 건드리고** 운영 품질 기준(Abandon.md P0~P2)을 충족하는 수준으로 wiring하거나 hardening하는 것이 목표다.

| 코드명 | 핵심 역할 | 관련 파일 |
|---|---|---|
| **GraphRAG Brain State** | 대화 → 벡터+KG 이중 인덱싱, LangGraph shadow 상태 목록화 | `RagGraphExecutor`, `Neo4jKnowledgeGraphClient`, `UawThumbnailService` |
| **Anchor-Based Compression (Overdrive)** | 정보 희소/저권위/모순 → 앵커 다단계 압축 | `OverdriveGuard`, `DynamicContextCompressor` |
| **CFVM / Failure Pattern Analysis** | 오류 패턴 볼츠만 학습 → CFVM RawTile 슈퍼토큰 | `RawSlotExtractor`, `RawMatrixBuffer`, `RetrievalOrderService` |
| **MoE Strategy Selector** | 9-플레이트 동적 선택 → Critic Loop → ArtPlateEvolver | `ArtPlateSelector`, `ArtPlateEvolver`, `LlmRouterService` |
| **Matryoshka Slicing** | 4096d → 1536d 슬라이싱 어댑터 | `OllamaEmbeddingModel` |
| **ExtremeZ / HYPERNOVA** | 위기 감지 → 12~24 병렬 쿼리 / 꼬리 가중 멱평균 융합 | `ExtremeZSystemHandler`, `TailWeightedPowerMeanFuser` |

---

## 2. 비협상 규칙 (이 지시서에도 동일 적용)

- 기존 클래스 파일 전체 재작성 **금지**. 최소 diff (메서드/블록 단위) 만 허용.
- `PromptBuilder.build(ctx)` 우회 **금지** — ExtremeZ/HYPERNOVA 포함.
- `dev.langchain4j:*` → `1.0.1` 순수성 유지. beta/RC 혼입 시 즉시 중단 보고.
- `@Autowired(required=false)` 사용 시 startup log에 `disabledReason` 반드시 남김.
- 실제 API key/secret 값 로그·trace·debug event 출력 **금지**.
- `Future.cancel(true)` 신규 사용 **금지** → `CancelShield` 패턴 또는 `cancel(false)`.
- FQCN 기준으로만 클래스를 식별. simple name만 보고 병합 **금지**.
- `proceed()` 한 번만 호출 (AOP aspect 내).
- 새 Spring Bean 등록 시 `AutoConfiguration.imports` 또는 `@Configuration` + 기존 scan 범위 내에 등록하고 중복 등록 **금지**.

---

## 3. P0 — GraphRAG 대화 인덱싱 + Brain State 목록화

### 목표
- 기존 `VectorStoreService` + `DocumentChunkingService` 경로를 **그대로** 유지하면서,
  대화 입력 후 KG(Neo4j) 노드/엣지 적재와 `RagGraphState` Brain Snapshot 목록화를 shadow 모드로 추가한다.
- `UawThumbnailService`의 사전 예열(도메인 썸네일) 기능을 **기존 호출 경로 변경 없이** Brain Snapshot에 연결한다.

### 관련 파일 (FQCN 확인 후 적용)

```
com.example.lms.service.VectorStoreService           (기존 — 수정 최소화)
com.example.lms.service.DocumentChunkingService      (기존 — 수정 최소화)
ai.abandonware.nova.orch.kg.Neo4jKnowledgeGraphClient (기존 — 수정 최소화)
com.example.lms.service.kg.KnowledgeGraphHandler     (기존 — 수정 최소화)
ai.abandonware.nova.orch.graph.RagGraphExecutor      (기존 — shadow 호출 추가)
ai.abandonware.nova.orch.graph.RagGraphState         (기존 — BrainSnapshot 필드 추가)
com.example.lms.service.uaw.UawThumbnailService      (기존 — 수정 최소화)
[NEW] com.example.lms.service.brain.BrainStateIndexer   (신규 — 이벤트 수신 전용)
[NEW] com.example.lms.event.ConversationIndexedEvent    (신규 — ApplicationEvent)
```

### Patch Block

1. **ConversationIndexedEvent** 신규 생성
   - 필드: `String sessionId`, `String rid`, `List<String> chunkIds`, `String rawQuery`
   - `extends ApplicationEvent`

2. **ChatService / ChatController 기존 경로 끝** (응답 전송 완료 후)
   - `applicationEventPublisher.publishEvent(new ConversationIndexedEvent(this, sessionId, rid, chunkIds, rawQuery))`
   - **기존 흐름 변경 없음** — publish만 추가.

3. **BrainStateIndexer** `@EventListener` 구현
   ```java
   @EventListener
   @Async("novaTaskExecutor")   // 기존 executor 재사용
   public void onConversationIndexed(ConversationIndexedEvent e) {
       // 1. KG 적재 (엔티티/관계 추출) — Neo4jKnowledgeGraphClient 재사용
       // 2. RagGraphState에 BrainSnapshot 추가 (shadow, 응답 흐름 비간섭)
       // 3. UawThumbnailService.prewarm() — 도메인 키워드 기반 optional
       // 실패 시: log.warn + traceStore.put("brain.index.skipped", reason) — 전파 금지
   }
   ```

4. **RagGraphState** — `Map<String, Object> brainSnapshot` 필드 추가 (기존 필드 유지)

5. **AutoConfiguration 등록** — `BrainStateIndexer`를 기존 scan 패키지 내에 `@Component` 등록.
   `AutoConfiguration.imports` 직접 추가가 필요하면 중복 확인 후 1줄만 추가.

### TraceStore Keys (필수)
```
brain.index.sessionId
brain.index.chunkCount
brain.index.kgNodesWritten
brain.index.snapshotSize
brain.index.skipped        (실패 시 reason)
brain.thumbnail.prewarmed  (UawThumbnailService 호출 시)
```

### Verification
```powershell
.\gradlew.bat test --tests "*BrainState*" --tests "*ConversationIndexed*"
# smoke: 대화 후 Neo4j 노드 수 증가, RagGraphState.brainSnapshot 비어있지 않음
```

---

## 4. P0 — Anchor-Based Context Compression (Overdrive) 강화

### 목표
- `OverdriveGuard`의 트리거 조건(희소성/저권위/모순성)이 올바르게 평가되는지 확인.
- `DynamicContextCompressor`의 다단계 K 감소 로직(48→32→16→8)이 실제로 실행되는지 확인.
- try-catch fail-soft: 예외 시 기존 흐름으로 **반드시** 복귀.

### 관련 파일
```
com.example.lms.service.overdrive.OverdriveGuard         (기존)
com.example.lms.service.overdrive.DynamicContextCompressor (기존)
com.example.lms.service.ContextOrchestrator              (기존 — 연결 확인)
```

### Patch Block

```java
// OverdriveGuard.evaluate() — 기존 로직 유지, trace key 추가만
traceStore.put("overdrive.triggered", triggered);
traceStore.put("overdrive.reason", "sparsity=" + sparsity + ",authority=" + authority + ",contradiction=" + contradiction);

// DynamicContextCompressor.compress() — 기존 로직 유지, trace key 추가
traceStore.put("overdrive.compression.stages", stagesExecuted);
traceStore.put("overdrive.compression.finalK", finalK);
traceStore.put("overdrive.compression.anchor", anchorTerm);   // 실제 값 아닌 hash or redacted

// try-catch wrap (기존 없으면 추가)
try {
    return compressor.compress(candidates, anchor);
} catch (Exception ex) {
    traceStore.put("overdrive.compression.skipped", ex.getMessage());
    log.warn("[overdrive] compression failed, falling back. reason={}", ex.getMessage());
    return candidates;  // 원본 candidates 반환
}
```

### Verification
```powershell
.\gradlew.bat test --tests "*Overdrive*" --tests "*DynamicContextCompressor*"
```

---

## 5. P0 — CFVM / Failure Pattern Analysis 안전화

### 목표
- `RawMatrixBuffer`가 세션 스코프를 실제로 격리하는지 확인 (thread-safe compound op).
- Boltzmann weight 계산이 NPE 없이 동작하는지 확인.
- `RetrievalOrderService`의 스크립트 조정이 `PromptBuilder` 우회 없이 `PromptContext` 경유인지 확인.
- CFVM RawTile이 임베딩 경로로 올바르게 전달되는지 확인.

### 관련 파일
```
com.example.lms.cfvm.RawSlotExtractor          (기존)
com.example.lms.cfvm.RawMatrixBuffer           (기존)
com.example.lms.service.RetrievalOrderService  (기존)
com.example.lms.cfvm.CfvmRawTile              (기존)
```

### Patch Block

```java
// RawMatrixBuffer — compound op 안전화 (기존 ConcurrentHashMap 사용 확인)
// 문제: sessionMap.computeIfAbsent() 후 별도 put 패턴이 있으면 merge로 교체
sessionMap.merge(sessionId, newSlot, (existing, incoming) -> existing.append(incoming));

// Boltzmann weight — NPE guard
double temperature = properties.getBoltzmannTemperature();
if (temperature <= 0.0) {
    log.warn("[cfvm] invalid temperature={}, using default 1.0", temperature);
    temperature = 1.0;
}

// TraceStore keys
traceStore.put("cfvm.rawSlots.extracted", slotCount);
traceStore.put("cfvm.boltzmann.temperature", temperature);
traceStore.put("cfvm.rawTile.size", tileByteSize);
traceStore.put("cfvm.retrievalOrder.adjusted", adjusted);
```

### Verification
```powershell
.\gradlew.bat test --tests "*Cfvm*" --tests "*RawMatrix*" --tests "*RetrievalOrder*"
```

---

## 6. P0 — MoE Strategy Selector — Gate Chain + Evolver 정합성

### 목표
- 9개 플레이트(AP1~AP9)가 `ArtPlateSelector`에 모두 등록되어 있는지 확인.
- `ArtPlateEvolver`가 생성한 동적 플레이트(10번+)가 라우팅 테이블에 추가되는지 확인.
- Critic Loop 재시도가 최대 3회 후 circuit break되는지 확인.
- HYPERNOVA/Overdrive/ExtremeZ 세 모드의 **상호 배제** 로직이 존재하는지 확인. 없으면 priority enum 추가.

### 관련 파일
```
com.abandonwareai.moe.ArtPlateSelector        (기존)
com.abandonwareai.moe.ArtPlateEvolver         (기존)
com.example.lms.service.LlmRouterService      (기존 — UCB1 bandit)
[CONFIRM] 기존 critic loop 위치 (ChatService 또는 별도 CriticNode)
```

### Patch Block

```java
// ArtPlateSelector — evolver plate 등록 확인
// 기존 Map<String, ArtPlate> 에 evolver output을 put하는 경로가 없으면:
public void registerDynamicPlate(ArtPlate plate) {
    if (plateRegistry.containsKey(plate.getId())) {
        log.warn("[moe] plate already registered: {}", plate.getId());
        return;
    }
    plateRegistry.put(plate.getId(), plate);
    traceStore.put("moe.plate.registered", plate.getId());
}

// 상호 배제 priority (기존 없으면 추가)
public enum SpecialModepriority { EXTREME_Z, HYPERNOVA, OVERDRIVE, NONE }
// 한 요청 당 하나의 모드만 활성화. 우선순위: EXTREME_Z > HYPERNOVA > OVERDRIVE

// Critic Loop circuit break
private static final int MAX_CRITIC_RETRIES = 3;
// 기존 retry counter 없으면 AtomicInteger로 추가 (session-scoped or request-scoped)

// UCB1 thread safety — compound op 교체
// 기존: counts.get(k); counts.put(k, v+1);
// 수정: counts.merge(k, 1, Integer::sum);
```

### TraceStore Keys
```
moe.plate.selected
moe.plate.criticRetries
moe.plate.circuitBreak
moe.evolver.newPlateId
moe.specialMode          (EXTREME_Z|HYPERNOVA|OVERDRIVE|NONE)
```

### Verification
```powershell
.\gradlew.bat test --tests "*ArtPlate*" --tests "*MoE*" --tests "*LlmRouter*"
```

---

## 7. P1 — Matryoshka Slicing 어댑터 강화

### 목표
- `OllamaEmbeddingModel`의 슬라이싱 로직(4096d → 1536d)이 올바르게 동작하는지 확인.
- 슬라이싱 후 L2 정규화를 수행하는지 확인 (코사인 유사도 정확도 유지).
- 차원 불일치 시 NPE가 아닌 명시적 오류 로그를 남기는지 확인.

### 관련 파일
```
com.example.lms.embedding.OllamaEmbeddingModel   (기존)
```

### Patch Block

```java
// 슬라이싱 + L2 정규화 (기존 슬라이싱 로직 이후)
float[] sliced = Arrays.copyOf(raw, TARGET_DIM);  // TARGET_DIM = 1536

// L2 normalize
double norm = 0.0;
for (float v : sliced) norm += v * v;
norm = Math.sqrt(norm);
if (norm < 1e-9) {
    log.warn("[embedding] near-zero norm after slicing, returning unnormalized");
    traceStore.put("embedding.slice.zeroNorm", true);
} else {
    for (int i = 0; i < sliced.length; i++) sliced[i] /= (float) norm;
}

// 차원 불일치 guard
if (raw.length < TARGET_DIM) {
    log.error("[embedding] raw dim={} < target={}, skipping slice", raw.length, TARGET_DIM);
    traceStore.put("embedding.slice.skipped.reason", "dim_too_small:" + raw.length);
    return raw;  // 원본 반환
}

traceStore.put("embedding.slice.rawDim", raw.length);
traceStore.put("embedding.slice.targetDim", TARGET_DIM);
```

### Verification
```powershell
.\gradlew.bat test --tests "*OllamaEmbedding*" --tests "*Matryoshka*"
```

---

## 8. P1 — ExtremeZ (Massive Parallel Query Expansion) — PromptBuilder 준수 확인

### 목표
- `ExtremeZSystemHandler`가 최종 프롬프트를 **직접 조립하지 않고** `PromptContext`에 데이터를 담아 `PromptBuilder.build(ctx)`에 전달하는지 확인.
- SelfAskPlanner / SmartQueryPlanner 재활용 시 결과가 `PromptContext.subQuestions` 또는 동등 필드에 담기는지 확인.
- 12~24개 병렬 검색이 CancelShield를 사용하는지 또는 최소한 `cancel(false)` 패턴인지 확인.
- 위기 트리거(ExtremeZTrigger) 조건이 TraceStore에 기록되는지 확인.

### 관련 파일
```
com.abandonwareai.extreme.ExtremeZSystemHandler   (기존)
com.abandonwareai.extreme.ExtremeZTrigger         (기존)
com.example.lms.service.SelfAskPlanner            (기존 — 재사용)
com.example.lms.service.SmartQueryPlanner         (기존 — 재사용)
com.example.lms.transform.PromptBuilder           (기존 — 우회 금지)
```

### Patch Block

```java
// ExtremeZSystemHandler — PromptBuilder 준수 확인 패턴
// 위반 패턴 (금지):
// String prompt = "Query: " + query + "\nContext: " + context;

// 올바른 패턴:
PromptContext ctx = PromptContext.builder()
    .originalQuery(originalQuery)
    .subQuestions(expandedQueries)          // 12~24개
    .retrievedChunks(mergedChunks)
    .build();
String finalPrompt = promptBuilder.build(ctx);   // 단 한 곳에서만

// ExtremeZTrigger — TraceStore keys
traceStore.put("extremeZ.triggered", triggered);
traceStore.put("extremeZ.trigger.reason", reason);  // sparsity|contradiction|urgency
traceStore.put("extremeZ.subQueryCount", expandedQueries.size());
traceStore.put("extremeZ.parallelSearchCount", parallelResults.size());

// 병렬 검색 — cancel 독성 차단
// invokeAll timeout 후 cancel(false) 사용 또는 CancelShieldExecutorService 래핑
List<Future<List<RetrievedChunk>>> futures = cancelShieldExecutor.invokeAll(tasks, timeoutMs, MILLISECONDS);
```

### Verification
```powershell
.\gradlew.bat test --tests "*ExtremeZ*" --tests "*MassiveParallel*"
# PromptBuilder 우회 grep 확인
grep -rn "String.*prompt.*=.*query\|String.*prompt.*\+" src\main\java\com\abandonwareai\extreme\
```

---

## 9. P1 — HYPERNOVA (TailWeightedPowerMean) — 상호 배제 + proceed() 단일 호출

### 목표
- `TailWeightedPowerMeanFuser.fuse()`의 동적 지수(p) 계산이 NPE 없이 동작하는지 확인.
- HYPERNOVA 모드가 Overdrive/ExtremeZ와 동시에 활성화되지 않는지 확인 (§6의 priority enum 활용).
- AOP aspect가 있다면 `proceed()` 단일 호출인지 확인.

### 관련 파일
```
com.abandonwareai.hypernova.TailWeightedPowerMeanFuser   (기존)
com.abandonwareai.hypernova.HypernovaOrchestrator        (기존, 있는 경우)
```

### Patch Block

```java
// TailWeightedPowerMeanFuser.fuse() — NPE guard
if (scores == null || scores.isEmpty()) {
    traceStore.put("hypernova.fuse.skipped", "empty_scores");
    return Collections.emptyList();
}

// 동적 p 계산 — CVaR@α tail 신호 기반
double tailSignal = computeCvar(scores, alpha);
double p = BASE_POWER + (tailSignal > TAIL_THRESHOLD ? TAIL_BOOST : 0.0);

traceStore.put("hypernova.fuse.p", p);
traceStore.put("hypernova.fuse.tailSignal", tailSignal);
traceStore.put("hypernova.fuse.inputCount", scores.size());

// 상호 배제 확인 (HypernovaOrchestrator 또는 상위 라우터에서)
SpecialModepriority activePriority = determineSpecialMode(ctx);
if (activePriority != SpecialModepriority.HYPERNOVA) {
    traceStore.put("hypernova.skipped.reason", "lower_priority:" + activePriority);
    return delegate.handle(ctx);
}
```

### Verification
```powershell
.\gradlew.bat test --tests "*Hypernova*" --tests "*TailWeighted*" --tests "*PowerMean*"
```

---

## 10. P0 — Spring Bean Wiring 확인 (신규 Bean 등록)

신규 추가된 Bean 목록 (이 지시서에서 추가):

| Bean | 등록 방법 |
|---|---|
| `BrainStateIndexer` | `@Component` — 기존 scan 패키지 내 |
| `ConversationIndexedEvent` | 이벤트 클래스 (Bean 등록 불필요) |
| `SpecialModepriority` enum | enum (Bean 등록 불필요) |

**기존 AutoConfiguration.imports 확인 대상** (추가/수정 없이 존재 여부만 검증):
```
ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration
ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration
ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration
ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration
```

```powershell
# 확인 명령
.\gradlew.bat bootRun --args="--spring.main.web-application-type=none --nova.orch.enabled=true" 2>&1 | Select-String "BrainStateIndexer|ArtPlateSelector|TailWeightedPowerMeanFuser|ExtremeZSystemHandler"
```

---

## 11. 전체 검증 순서

```powershell
# Step 1 — 컴파일
.\gradlew.bat clean compileJava

# Step 2 — 유닛 테스트 (신규 Bean 포함)
.\gradlew.bat test --tests "*BrainState*" `
                   --tests "*Overdrive*" `
                   --tests "*Cfvm*" `
                   --tests "*ArtPlate*" `
                   --tests "*OllamaEmbedding*" `
                   --tests "*ExtremeZ*" `
                   --tests "*Hypernova*"

# Step 3 — 전체 체크
.\gradlew.bat check

# Step 4 — LangChain4j 순수성
.\gradlew.bat dependencies --configuration runtimeClasspath | Select-String "langchain4j" | Select-String -NotMatch "1\.0\.1"
# 출력이 없어야 통과

# Step 5 — secret scan
rg -n "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}" logs\ build\ | Measure-Object -Line
# count = 0 이어야 통과

# Step 6 — PromptBuilder 우회 grep
rg -n 'String\s+\w*[Pp]rompt\w*\s*=\s*.*\+' src\main\java\ | Select-String "ExtremeZ|Hypernova|extreme|hypernova"
# 출력이 없어야 통과

# Step 7 — smoke (앱 부팅 후)
curl -s -H "Content-Type: application/json" `
     -H "X-Request-Id: smoke-graphrag-001" `
     -H "X-Session-Id: smoke-sid-001" `
     -X POST http://localhost:8080/api/chat `
     -d '{\"message\":\"RAG 파이프라인 brain state 테스트\"}'
```

---

## 12. 완료 산출물 형식

패치 결과는 아래 형식으로 보고한다.

```markdown
## 요약
- 변경 목적 2~5줄

## 핵심 답변
- 수정 파일 목록 (FQCN 기준)
- 실제 적용한 최소 diff 요약 (파일:라인 범위)

## 근거/로그/파일경로
- TraceStore key 목록
- Gradle test 통과 증거 (명령 + 출력 일부)

## 한계/다음 단계
- 검증 불가한 항목 (evidence_needed 형식)
- P1/P2 후속 후보
```

---

## 13. 참조 — 핵심 파일 맵 (이 지시서 기준)

```
신규:
  src/main/java/com/example/lms/event/ConversationIndexedEvent.java
  src/main/java/com/example/lms/service/brain/BrainStateIndexer.java

기존 (수정 최소화):
  src/main/java/ai/abandonware/nova/orch/graph/RagGraphExecutor.java
  src/main/java/ai/abandonware/nova/orch/graph/RagGraphState.java
  src/main/java/com/example/lms/service/overdrive/OverdriveGuard.java
  src/main/java/com/example/lms/service/overdrive/DynamicContextCompressor.java
  src/main/java/com/example/lms/cfvm/RawSlotExtractor.java
  src/main/java/com/example/lms/cfvm/RawMatrixBuffer.java
  src/main/java/com/example/lms/service/RetrievalOrderService.java
  src/main/java/com/abandonwareai/moe/ArtPlateSelector.java
  src/main/java/com/abandonwareai/moe/ArtPlateEvolver.java
  src/main/java/com/example/lms/service/LlmRouterService.java
  src/main/java/com/example/lms/embedding/OllamaEmbeddingModel.java
  src/main/java/com/abandonwareai/extreme/ExtremeZSystemHandler.java
  src/main/java/com/abandonwareai/extreme/ExtremeZTrigger.java
  src/main/java/com/abandonwareai/hypernova/TailWeightedPowerMeanFuser.java
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

> **주의**: 위 경로는 best-effort 추정이다. 실제 소스셋에서 FQCN으로 검색 후 실제 경로를 우선한다.
> ```powershell
> Get-ChildItem -Recurse -Filter "BrainStateIndexer.java" src\main\java\
> Get-ChildItem -Recurse -Filter "TailWeightedPowerMeanFuser.java" src\main\java\
> ```

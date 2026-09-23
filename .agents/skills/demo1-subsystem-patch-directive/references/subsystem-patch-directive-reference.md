# Demo1 Subsystem Patch Directive Reference

This file preserves the detailed per-subsystem directive content moved out of SKILL.md so the skill entrypoint can stay small. Read the relevant subsystem section only when the requested patch touches that subsystem.

---
name: demo1-subsystem-patch-directive
description: Use when modifying or auditing demo-1 subsystem implementations for Overdrive/Anchor Compression, CFVM/Failure Pattern Analysis, MoE Strategy Selector, Matryoshka embedding slicing, ExtremeZ, HYPERNOVA TWPM/CVaR/Risk-K, CIH-RAG/IQR/MLA Breadcrumb, or OpenAI adapter/version purity.
---

# Dynamic RAG Orchestration Platform — Subsystem Patch Directive

> **Safe Patch 원칙**: 최소 diff. 파일 전체 재작성 금지. FQCN 기준 적용.
> PromptBuilder 우회 금지. 이 문서는 설계 의도(포트폴리오 기술서)를 코덱스가 직접
> 실행할 수 있는 패치 지시로 압축한다.

---

## 0. 공통 비협상 규칙

| 항목 | 규칙 |
|------|------|
| 버전 | `dev.langchain4j:*` = `1.0.1` 고정. beta/RC 혼입 금지 |
| 프롬프트 | `PromptBuilder.build(PromptContext)` 경유만 허용. 문자열 결합 금지 |
| 시크릿 | API key/token/secret 값을 로그·trace·SSE에 출력 금지 |
| 빈 catch | `catch(Exception e){}` 금지. 반드시 `rid` + reason breadcrumb 남김 |
| FQCN 중복 | simple name만 보고 병합 금지. package + FQCN 기준으로 확인 후 적용 |
| fail-soft | 모든 부스터 모드는 실패 시 기존 RAG 경로로 복귀, bypassReason 기록 |
| Spring | `@Component`/`@Service`/`@ConditionalOnBean` 등 Bean 등록 상태 확인 |
| 소스루트 | `main/java`, `main/resources` 우선. 다른 루트는 Gradle 증거 필요 |

---

## S01 — Anchor-Based Context Compression (Overdrive)

### 설계 의도
OverdriveGuard가 후보군 희소성·저권위·모순성 세 조건을 종합해 "위기 상황"을 감지하면,
DynamicContextCompressor가 앵커 단어 기준으로 K를 48→32→16→8로 단계 축소하고
마지막에 exact-phrase 집중 탐침을 수행. 실패 시 try-catch로 원래 흐름에 복귀.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| 위기 감지 | `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java` |
| 앵커 압축 | `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java` |
| Overdrive 클래스들 | `main/java/com/example/lms/service/rag/overdrive/*.java` |
| 설정 | `main/resources/application.yml` → `overdrive.*` 키 블록 |

### 트리거 조건

```java
// OverdriveGuard.shouldActivate(List<Candidate> candidates)
// 세 조건 중 2개 이상이면 true
boolean sparse       = candidates.size() < props.getMinCandidates();      // 기본 3
boolean lowAuth      = avgAuthorityScore(candidates) < props.getAuthThr(); // 기본 0.4
boolean contradicted = contradictionScore(candidates) > props.getContraThr(); // 기본 0.6
```

### K 단계 축소 로직

```java
// DynamicContextCompressor.compress(query, candidates, anchors)
int[] kStages = props.getKStages(); // 기본 [48, 32, 16, 8]
for (int k : kStages) {
    List<Candidate> filtered = filterByAnchorRelevance(candidates, anchors, k);
    if (filtered.size() >= props.getMinSurvivors()) { candidates = filtered; break; }
}
// 마지막: exact-phrase 집중 탐침
candidates = exactPhraseProbeDeduplicate(candidates, anchors);
```

### 필수 TraceStore 키

```
overdrive.activated=true/false
overdrive.triggerReasons=[sparse|lowAuth|contradicted]
overdrive.stagesApplied=N
overdrive.finalCandidateCount=N
overdrive.exactPhraseProbeUsed=true/false
overdrive.bypassReason=...  (예외 발생 시)
```

### 검증

```bash
./gradlew test --tests '*Overdrive*' --tests '*DynamicContextCompress*'
# 위기 조건 충족 → overdrive.activated=true
# 예외 발생 → overdrive.bypassReason 기록, 파이프라인 미중단
```

---

## S02 — Failure Pattern Analysis (CFVM)

### 설계 의도
오류 발생 시 RawSlotExtractor가 체인 단계별 실행 패턴을 비압축 추출 →
RawMatrixBuffer에 세션 스코프로 저장 → JB/CB 두 지표로 Lissajous-like 궤적 형성 →
3×3=9-타일 볼츠만 학습 → CFVM RawTile(슈퍼토큰) → RetrievalOrderService 회복 경로 조정.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| 패턴 추출 | `main/java/com/example/lms/cfvm/RawSlotExtractor.java` |
| 버퍼 저장 | `main/java/com/example/lms/cfvm/RawMatrixBuffer.java` |
| 오류 기록 | `main/java/com/example/lms/cfvm/CfvmFailureRecorder.java` |
| 회복 경로 조정 | `main/java/com/example/lms/service/RetrievalOrderService.java` |
| 슈퍼토큰 빌더 | `main/java/com/example/lms/cfvm/CfvmRawTileBuilder.java` |

### JB / CB 지표 정의

```
JB (Job Behavior)  = 성공 체인 단계 수 / 전체 체인 단계 수  ∈ [0, 1]
CB (Chain Breakdown) = 오류 전파 다운스트림 단계 수 / 전체 단계 수  ∈ [0, 1]
→ (JB, CB) 시계열 포인트를 쌓으면 상태공간 궤적 (Lissajous-like) 형성
```

### 9-타일 분할 및 볼츠만 학습

```java
// 3×3 그리드: tile = (jbBin * 3) + cbBin
//   where bin = Math.min((int)(val * 3), 2)
// 볼츠만 가중치: w_i = exp(-E_i / T) / Z
//   E_i = 누적 오류 비용 (timeout=1.0, cancel=0.5, rateLimit=0.3)
//   T   = 볼츠만 온도, 기본 1.0 (props로 외부화)
// CFVM RawTile: 응축(Condense) + 융합(Fuse) 후 벡터 스토어 pre-aggregation 레이어로 전달
```

### 필수 TraceStore 키

```
cfvm.triggered=true/false
cfvm.jb=0.xx  cfvm.cb=0.xx
cfvm.activeTile=N        (0~8)
cfvm.rawTileId=redacted-uuid-or-hash
cfvm.boltzmannWeight=0.xx
cfvm.retrievalOrderAdjusted=true/false
cfvm.recoveryPath=[analyze,web,vector,kg]
```

### 검증

```bash
./gradlew test --tests '*Cfvm*' --tests '*RawMatrix*' --tests '*RawSlot*' --tests '*RetrievalOrder*'
# 오류 임계치 초과 → cfvm.triggered=true, activeTile 기록
# 슈퍼토큰 생성 → cfvm.rawTileId 존재
```

---

## S03 — MoE Strategy Selector

### 설계 의도
9가지 AP 플레이트 중 쿼리 신호(최신성·내부DB·이전대화·복잡도)를 분석해 최적 플레이트 선택.
품질 미달 시 Critic 루프로 최대 3회 재시도(CircuitBreaker). ArtPlateEvolver가 실패 패턴
학습 후 새 플레이트 생성, 트래픽 5% A/B 테스트 후 승격.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| 플레이트 YAML | `main/resources/plans/ap1_auth_web.v1.yaml` 외 ap3, ap9, ap11 |
| 게이트/선택기 | `main/java/com/example/lms/artplate/NineArtPlateGate.java`, `main/java/com/example/lms/moe/RgbStrategySelector.java`, `main/java/com/example/lms/ai/moe/MoeCandidateRouter.java` |
| Critic 루프 | `main/java/com/abandonware/ai/agent/orchestrator/nodes/CriticNode.java` |
| 플레이트 진화 | `main/java/com/example/lms/artplate/ArtPlateEvolver.java` |
| A/B 라우팅 | `main/java/com/example/lms/artplate/NineArtPlateGate.java` + `ArtPlateEvolver.RolloutDecision` |

### 쿼리 신호 → 플레이트 매핑

| 신호 | 우선 플레이트 |
|------|--------------|
| `signal.freshness=HIGH` | AP1_AUTH_WEB |
| `signal.internalMemory=HIGH` | AP3_VEC_DENSE |
| `signal.complexity=LOW` | AP9_COST_SAVER |
| `signal.domainSparse=HIGH` | Hypernova or Brave |
| `signal.conversational=HIGH` | AP3 or AP9 |

### Critic 루프 CircuitBreaker

```java
// CriticNode.evaluate(answer, ctx) — maxRetry = 3 (props로 외부화)
// 재시도 트리거: citationCount < 3 OR faithfulnessScore < 0.6
// 재시도 명령 예: "source.minAuthority=0.8, searchTarget=WEB_ONLY"
// 최종 실패: ExpectedFailure("정보 없음") 반환, trace에 critic.exhausted=true
```

### ArtPlateEvolver 조건

```
승격 기준: 20회 시도 후 품질 게이트 통과율 > 기존 플레이트 + 5%
A/B 슬롯: 트래픽 5% (PlateAbRouter)
승격 시: PlannerNexus 가중치 갱신, 기존 플레이트 deprecated 태깅
```

### 필수 TraceStore 키

```
moe.selectedPlate=AP1|AP3|AP9|...
moe.signalVector={freshness:H,internalMemory:L,...}
moe.criticAttempts=N
moe.criticExhausted=true/false
moe.criticLastReason=...
moe.evolverCandidatePlateId=...  (신규 후보 있을 때)
moe.abSlot=control|experiment
```

### 검증

```bash
./gradlew test --tests '*PlannerNexus*' --tests '*CriticNode*' --tests '*ArtPlate*'
# Critic 3회 소진 → criticExhausted=true, 빈 SSE 없이 ExpectedFailure 반환 확인
```

---

## S04 — Matryoshka Slicing (임베딩 차원 최적화)

### 설계 의도
Ollama GPU가 4096차원 생성 → Java 레이어에서 앞 1536차원만 슬라이싱(copyOf).
Qwen3-Embedding MRL 구조 덕분에 핵심 정보 손실 < 2%.
벡터 DB 62% 절약, 검색 속도 3배, OpenAI 1536d 표준 호환.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| 슬라이싱 어댑터 | `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java` |
| 설정 | `main/resources/application-llm.yaml` → `embedding.*` 키 블록 |
| 벡터 인덱스 | `main/java/com/example/lms/service/VectorStoreService.java` |

### 슬라이싱 코드 패턴

```java
// OllamaEmbeddingModel.embed(String text)
float[] raw = callOllamaEmbedApi(text);  // length = 4096
float[] sliced = Arrays.copyOf(raw, targetDim);  // targetDim = 1536
if (props.isNormalize()) sliced = l2Normalize(sliced);
traceStore.put("embed.sourceDim", raw.length);
traceStore.put("embed.targetDim", sliced.length);
traceStore.put("embed.sliceReason", "MRL_PREFIX");
// 절대 금지: embedding 값 자체를 로그에 출력
return sliced;
```

### 설정 YAML 블록

```yaml
embedding:
  source-dim: ${EMBEDDING_SOURCE_DIM:4096}
  target-dim:  ${EMBEDDING_TARGET_DIM:1536}
  model:       ${EMBEDDING_MODEL:qwen3-embedding:4b}
  slice-method: MRL_PREFIX   # MRL_PREFIX | NONE
  normalize: true
```

### 필수 TraceStore 키

```
embed.sourceDim=4096
embed.targetDim=1536
embed.sliceMethod=MRL_PREFIX
embed.normalizeApplied=true/false
embed.sliceReason=MRL|NONE|FALLBACK
```

### 검증

```bash
./gradlew test --tests '*Embedding*' --tests '*Matryoshka*' --tests '*OllamaEmbedding*'
# 반환 벡터 length == 1536  (4096이면 슬라이싱 누락)
# 벡터 DB 인덱스 차원과 일치 확인
```

---

## S05 — ExtremeZ / Massive Parallel Query Expansion

### 설계 의도
ExtremeZTrigger가 초기 결과 부족·모순·긴급어 감지 시 극-Z 모드 발동.
ExtremeZSystemHandler가 SelfAskPlanner 재활용해 12~24개 질문 생성 →
AnalyzeWebSearchRetriever + LangChainRAGService에 병렬 투하 →
RRF + AuthorityScorer로 정제 → PromptContext 표준 상자 → PromptBuilder.build(ctx).
PromptContext.setFinalPromptText() 직접 호출 절대 금지.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| 위기 감지/실행 | `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java` |
| 쿼리 버스터 | `main/java/com/nova/protocol/rag/explore/QueryBurstExpander.java` |
| 병렬 검색 | `main/java/com/example/lms/service/rag/burst/*.java` |
| 결과 정제 | `main/java/com/example/lms/service/rag/fusion/*.java` (RRF, GRANDAS) |

### 트리거 조건

```java
// ExtremeZTrigger.shouldActivate(InitialSearchResult r, UserQuery q)
boolean lowRecall  = r.getDocCount() < props.getMinDocThreshold();    // 기본 2
boolean highContra = r.contradictionScore() > props.getContraThreshold(); // 기본 0.7
boolean urgency    = q.containsUrgencyKeyword();  // "지금 당장", "즉시" 등
return (lowRecall && highContra) || urgency;
```

### 병렬 탐색 설계

```java
// ExtremeZSystemHandler.execute(ctx)
List<String> subQueries = queryBurstExpander.expand(ctx.getQuery(), 12, 24);
List<CompletableFuture<List<Doc>>> futures = subQueries.stream()
    .map(q -> CompletableFuture.supplyAsync(() -> searchBoth(q)))  // web + vector
    .collect(toList());
List<Doc> merged = allOf(futures, props.getParallelTimeoutMs())
    .stream().flatMap(List::stream).collect(toList());
List<Doc> refined = rrfFuser.fuse(merged, authorityScorer);
ctx.setEvidenceList(refined.subList(0, Math.min(refined.size(), props.getTopN())));
// 절대 금지: ctx.setFinalPromptText(...)
```

### 필수 TraceStore 키

```
extremeZ.activated=true/false
extremeZ.triggerReasons=[lowRecall|highContra|urgency]
extremeZ.subQueryCount=N
extremeZ.parallelBranchCount=N
extremeZ.mergedDocCount=N
extremeZ.rrfApplied=true
extremeZ.timeoutMs=N
extremeZ.bypassReason=...  (예외 발생 시)
```

### 검증

```bash
./gradlew test --tests '*ExtremeZ*' --tests '*QueryBurst*'
# 트리거 충족 → subQueryCount >= 12, rrfApplied=true
# PromptContext에 evidenceList만, rawPromptText 없음
```

---

## S06 — HYPERNOVA (Dynamic RAG Routing System)

### 설계 의도
꼬리 가중 멱평균(TWPM) + CVaR@α + Risk-K 할당으로 희소 신호에 과감하게 베팅.
ZCA 화이트닝으로 쿼리 임베딩 전처리. DPP 다양성 재랭킹으로 편향 제어.
BodeClamp/tanh로 증폭값 안전 클램핑. 최종 CitationGate → PIISanitizer → FinalSigmoidGate.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| TWPM 융합 | `main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java` |
| CVaR 집계 | `main/java/com/nova/protocol/fusion/CvarAggregator.java` |
| Risk-K 할당 | `main/java/com/nova/protocol/alloc/RiskKAllocator.java`, `main/java/com/nova/protocol/alloc/SimpleRiskKAllocator.java` |
| ZCA 화이트닝 | `main/java/com/example/lms/llm/LowRankWhiteningTransform.java`, `main/java/com/example/lms/service/rag/mp/LowRankWhiteningStats.java` |
| DPP 재랭킹 | `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java` |
| 안전 클램프 | `main/java/com/nova/protocol/fusion/BodeClamp.java` |
| Plan YAML | `main/resources/plans/hyper_nova.v1.yaml` |

### 핵심 알고리즘

```java
// 1. TWPM: TailWeightedPowerMeanFuser.fuse(scores)
//    상위 꼬리 돌출도 감지 → p 동적 증가 (기본 p=2, max p=8)
//    result = powerMean(scores, p)  with dynamic p

// 2. CVaR: CvarAggregator.cvar(scores, alpha=0.1)
//    상위 10% 점수 평균 (꼬리 평균)
//    fuse(base, cvarVal) = base*(1-phi) + cvarVal*phi  // phi ≈ 0.618

// 3. Risk-K: RiskKAllocator.alloc(sourceScores, totalK)
//    softmax(sourceScores) → 소스별 K 비례 배분
//    각 소스별 min(allocated, maxKPerSource) 상한 적용

// 4. 안전 클램프 (모든 증폭 후 필수):
//    BodeClamp.clamp(val)  또는  Math.tanh(val)
//    정상 범위: [0.0, 1.0]
```

### 안전 게이트 순서

```
HYPERNOVA 점수 증폭
  → BodeClamp (수치 안정)
  → DppDiversityReranker (다양성)
  → CitationGate (citation >= 3)
  → PIISanitizer
  → FinalSigmoidGate (score >= 0.9)
  → 최종 답변
```

### 필수 TraceStore 키

```
hypernova.activated=true/false
hypernova.twpmP=N.N
hypernova.cvarAlpha=0.1
hypernova.cvarFusedScore=0.xx
hypernova.riskKAlloc={web:N,vector:N}
hypernova.whitening.applied=true/false
hypernova.dppApplied=true/false
hypernova.clampApplied=true/false
hypernova.finalGatePassed=true/false
```

### 검증

```bash
./gradlew test --tests '*Hypernova*' --tests '*TailWeighted*' --tests '*CvarAgg*' --tests '*RiskK*'
# 증폭 후 모든 최종 점수 [0,1] 범위 검증
# CitationGate 미통과 → 답변 억제 확인
```

---

## S07 — CIH-RAG / 집중 탐침 (Abara)

### 설계 의도
IQR(반복 조회) → GRANDAS+RRF(1차 융합) → Bi-Encoder(1차) + ONNX Cross-Encoder(2차)
→ DPP(다양성) → MLA Breadcrumb(이중 역할: 상위모델 제어신호 + SSE 실시간 스트리밍).
AttachmentService 상태 머신(UPLOADING→INDEXING→ACTIVE): ACTIVE만 검색 대상.
LlmRouterService UCB1 밴딧: 전략가(대형 모델)와 실행자(경량 모델) 동적 배정.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| IQR 로직 | `main/java/com/example/lms/service/rag/chain/AttachmentContextHandler.java` |
| 파일 상태 머신 | `main/java/com/example/lms/service/AttachmentService.java` |
| SSE 브레드크럼 | `main/java/com/example/lms/telemetry/LoggingSseEventPublisher.java` |
| LLM 라우터 | `main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java`, `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` |
| ONNX 재랭킹 | `main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java` |
| MLA 메타데이터 | `main/java/ai/abandonware/nova/orch/trace/MlaOtelBridge.java`, `main/java/com/example/lms/web/TraceFilter.java` |

### IQR 흐름

```java
// AttachmentContextHandler.searchAllFiles(query, sessionFiles)
// 1. ACTIVE 상태 파일만 필터 (UPLOADING/INDEXING 제외)
// 2. 각 파일에 대해 iterativeQuery(query, file, maxIter=3)
//    - iter마다 검색 결과 불충분이면 query refinement 후 재시도
// 3. GRANDAS + Weighted-RRF 융합
// 4. Bi-Encoder → ONNX Cross-Encoder 2-Pass 재랭킹
// 5. DPP 다양성 필터
// 6. MlaBreadcrumb 생성 → LoggingSseEventPublisher 스트리밍
```

### MLA Breadcrumb 이중 역할

```
역할 1 (제어): relevanceScore < 0.3 → 상위 모델에 "A경로 중단, B경로 전환" 신호
역할 2 (관측): LoggingSseEventPublisher.publish(breadcrumb) → 대시보드 실시간 스트리밍
필드: relevanceScore, sourceFile(파일명만), rank, chunkId, failReason
절대 금지: breadcrumb에 raw query 전체 또는 파일 내용 포함
```

### UCB1 밴딧 라우팅

```java
// LlmRouterService.selectModel(taskType)
// STRATEGY  → UCB1 최고 점수 대형 모델 (gemma4:31b 또는 mistral)
// EXECUTION → UCB1 최고 점수 경량 모델 (qwen3.5:9b 또는 gemma-small)
// UCB1 갱신: qualityGatePass=1 reward, fail=0 reward
// 절대 금지: 원격 키 미설정 시 외부 OpenAI 자동 폴백
```

### 필수 TraceStore 키

```
cihRag.iqrIterations=N
cihRag.activeFileCount=N
cihRag.skippedFileCount=N
cihRag.biEncoderApplied=true
cihRag.onnxRerankApplied=true/false
cihRag.dppApplied=true
cihRag.mlaBreadcrumbCount=N
cihRag.routedModel=gemma4|qwen3.5:9b|...
cihRag.ucb1Reward=0/1
```

### 검증

```bash
./gradlew test --tests '*AttachmentContext*' --tests '*LlmRouter*' --tests '*OnnxCrossEncoder*'
# INDEXING 파일 → 검색 제외 확인
# UCB1 보상 → 점수 갱신 확인
# ONNX disabled → onnxRerankApplied=false, 파이프라인 미중단
```

---

## S08 — Version Purity / OpenAI Adapter (로컬 LLM 통합)

### 설계 의도
LangChain4j 1.0.1 순수성 유지를 위해 Ollama 전용 베타 라이브러리 불사용.
OpenAiChatModel의 base-url을 로컬 Ollama /v1/chat/completions로 리디렉션.
RTX 3090(port 11434): 대형 추론 모델. RTX 3060(port 11435): 경량 모델 + 임베딩.
ProviderGuard가 단일 검증 로직으로 로컬/원격 모두 처리.

### 파일 매핑

| 역할 | 정규 파일 경로 |
|------|---------------|
| LLM 빈 설정 | `main/java/com/example/lms/config/LlmConfig.java` |
| 프로바이더 가드 | `main/java/com/example/lms/config/ProviderGuardConfig.java`, `main/java/com/example/lms/config/ConfigValueGuards.java` |
| 키 리졸버 | `main/java/com/example/lms/guard/KeyResolver.java` |
| Responses 어댑터 | `main/java/ai/abandonware/nova/orch/llm/OpenAiResponsesChatModel.java`, `main/java/ai/abandonware/nova/orch/llm/ModelGuardSupport.java` |
| YAML 설정 | `main/resources/application-llm.yaml` |
| 버전 헬스 | `main/java/com/example/lms/boot/VersionPurityHealthIndicator.java` |

### YAML 설정 패턴

```yaml
llm:
  primary:
    base-url: ${LLM_PRIMARY_BASE_URL:http://127.0.0.1:11434/v1}
    model:    ${LLM_PRIMARY_MODEL:gemma4:31b}
    api-key:  ${LLM_PRIMARY_API_KEY:local-no-key}   # 로컬 플레이스홀더
  fast:
    base-url: ${LLM_FAST_BASE_URL:http://127.0.0.1:11435/v1}
    model:    ${LLM_FAST_MODEL:qwen3.5:9b}
    api-key:  ${LLM_FAST_API_KEY:local-no-key}
  embedding:
    base-url: ${LLM_EMBED_BASE_URL:http://127.0.0.1:11435/v1}
    model:    ${LLM_EMBED_MODEL:qwen3-embedding:4b}
```

### ProviderGuard 검증 로직

```java
// local 판별: baseUrl.contains("127.0.0.1") || contains("localhost")
// 로컬이면: api-key가 "local-*" 또는 비어있어도 통과
// 원격이면: api-key 반드시 존재, "sk-local|test|changeme|${" 패턴이면 fail-fast
// 로그: "provider=local|remote, keyPresent=true/false" — 키 값 절대 출력 금지
```

### 필수 TraceStore 키

```
llm.primary.provider=local|remote
llm.primary.port=11434            (IP 전체 URL 금지)
llm.fast.provider=local|remote
llm.versionPurity=PASS|FAIL
llm.versionPurity.mismatch=...   (FAIL 시 버전 정보)
```

### 검증

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -i langchain4j
# dev.langchain4j 1.0.1 외 버전 → VersionPurityHealthIndicator DOWN
./gradlew checkLangchain4jVersionPurity
./gradlew test --tests '*ProviderGuard*' --tests '*VersionPurity*' --tests '*LlmConfig*'
```

---

## 서브시스템 연동 순서 (통합 파이프라인)

```
사용자 쿼리
  │
  ├─ S03 MoE: 플레이트 선택, 모델 배정 (UCB1)
  ├─ S04 Matryoshka: 쿼리 임베딩 슬라이싱 (4096→1536d)
  ├─ S06 HYPERNOVA: ZCA 화이트닝 (조건부)
  ├─ S01 Overdrive: 위기 감지 → K 단계 압축 (조건부)
  ├─ S05 ExtremeZ: 12~24개 병렬 질문 폭주 (조건부)
  └─ S07 CIH-RAG: IQR + 3중 정제 + MLA Breadcrumb
        │
        └─ S06 HYPERNOVA: TWPM + CVaR + Risk-K 융합
              │
              └─ PromptBuilder.build(PromptContext)  ← 유일한 프롬프트 조립점
                    │
                    └─ CitationGate → PIISanitizer → FinalSigmoidGate
                          │
                          └─ SSE 스트리밍 (LoggingSseEventPublisher)

  [오류 발생 시] → S02 CFVM: 패턴 학습 + 볼츠만 가중치 + 회복 경로 조정
  [품질 미달 시] → S03 Critic 루프 (max 3회, CircuitBreaker)
  [버전 오염 시] → S08 VersionPurityHealthIndicator DOWN
```

---

## 전체 검증 명령 세트

Windows Desktop에서는 `gradlew.bat`와 host-local project cache를 우선 사용한다.
아래 focused test 패턴이 현재 레포에 없으면 `missing-task`로 분류하고,
없는 테스트를 통과했다고 보고하지 않는다.

```powershell
# 1. 정적 검사
$pcd = "$Env:LOCALAPPDATA\awx-gradle-project-cache\desktop"
New-Item -ItemType Directory -Force -Path $pcd | Out-Null
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
.\gradlew.bat compileJava -x test --no-daemon --project-cache-dir $pcd

# 2. 서브시스템별 유닛 테스트
.\gradlew.bat test --no-daemon --project-cache-dir $pcd `
  --tests '*Overdrive*' `
  --tests '*DynamicContextCompress*' `
  --tests '*Cfvm*' `
  --tests '*RawMatrix*' `
  --tests '*NineArtPlateGate*' `
  --tests '*RgbStrategySelector*' `
  --tests '*MoeCandidateRouter*' `
  --tests '*CriticNode*' `
  --tests '*ArtPlate*' `
  --tests '*Embedding*' `
  --tests '*Matryoshka*' `
  --tests '*ExtremeZ*' `
  --tests '*QueryBurst*' `
  --tests '*TailWeighted*' `
  --tests '*CvarAgg*' `
  --tests '*RiskK*' `
  --tests '*AttachmentContext*' `
  --tests '*LlmRouter*' `
  --tests '*OnnxCrossEncoder*' `
  --tests '*ProviderGuard*' `
  --tests '*VersionPurity*'

# 3. TraceStore 키 smoke
$headers = @{
  'Content-Type' = 'application/json'
  'X-Request-Id' = 'directive-smoke-001'
  'X-Session-Id' = 'directive-sid-001'
}
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/probe/search' `
  -Headers $headers `
  -Body '{"q":"RAG orchestration subsystem trace verification"}'

# 4. 시크릿 스캔 (count만, 값 출력 금지)
$hits = rg -n 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}' logs build 2>$null
Write-Host "[AWX][security] secretPatternHits=$(@($hits).Count)"
```

---

## 최종 보고 / 완료 산출물 형식

```markdown
## 요약
- Modified subsystem: S0N - subsystem name
- 변경 목적 2~3줄

## 핵심 답변
- 수정 파일 경로 + 최소 diff 요약 (라인 단위)

## TraceStore 키 확인
- trace.key=observed-or-evidence-needed

## 검증 결과
- 명령: ./gradlew test --tests '...'
- 결과: PASS / 잔존 이슈: ...

## 잔존 blind spot
- 직접 측정하지 못한 부분과 추론 근거
```


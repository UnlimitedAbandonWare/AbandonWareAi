# Strategy Map

## Concepts To Existing Seams

- Self-Ask 3-way decomposition:
  - `main/java/com/example/lms/service/rag/SelfAskPlanner.java`
  - `main/java/com/example/lms/service/rag/selfask/*`
  - Trace prefix: `selfask.3way.*`
- Query burst / Massive Parallel Query Expansion:
  - `main/java/com/nova/protocol/rag/explore/QueryBurstExpander.java`
  - `main/java/com/example/lms/service/rag/burst/*`
  - `main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`
- Anchor-Based Context Compression:
  - `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`
  - `main/java/com/example/lms/service/rag/overdrive/*`
  - `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`
- Plan DSL / Planner Nexus:
  - `main/java/com/example/lms/service/rag/plan/PlanDslLoader.java`
  - `main/java/com/example/lms/service/rag/orchestrator/PlanDslExecutor.java`
  - `main/java/com/nova/protocol/plan/PlanApplier.java`
  - `main/resources/plans/*.yaml`
- Brave, RuleBreak, Hypernova, Zero Break, Zero-100:
  - `main/resources/plans/brave.v1.yaml`
  - `main/resources/plans/rulebreak.v1.yaml`
  - `main/resources/plans/hyper_nova.v1.yaml`
  - `main/resources/plans/zero_break.v1.yaml`
  - `main/resources/plans/zero100.v1.yaml`
- GRANDAS / fusion / score calibration:
  - `main/java/com/example/lms/service/rag/fusion/*`
  - `main/java/com/nova/protocol/fusion/*`
- DPP diversity and two-pass rerank:
  - `main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java`
  - `main/java/com/nova/protocol/rerank/Dpp.java`
  - `main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java`
- AP strategy plates:
  - `main/resources/plans/ap1_auth_web.v1.yaml`
  - `main/resources/plans/ap3_vec_dense.v1.yaml`
  - `main/resources/plans/ap9_cost_saver.v1.yaml`
  - `main/resources/plans/ap11_finance_special.v1.yaml`
- UAW thumbnail:
  - `main/resources/plans/UAW_thumbnail.v1.yaml`
- Prediction tree / scenario analysis:
  - Search for `PredictTree`, `ScenarioExpander`, `TreeSerializer`, and `ProbabilityEngine`; verify active source before patching.
- MoE Strategy Selector / ArtPlate Evolver:
  - `main/java/com/example/lms/artplate/NineArtPlateGate.java` (gate + plate selector)
  - `main/java/com/example/lms/moe/RgbStrategySelector.java` (MoE signal strategy)
  - `main/java/com/example/lms/ai/moe/MoeCandidateRouter.java` (candidate routing)
  - `main/java/com/abandonware/ai/agent/orchestrator/nodes/CriticNode.java` (critic loop, max 3 retries)
  - `main/java/com/example/lms/artplate/ArtPlateEvolver.java` (plate evolution + rollout decision)
  - Trace prefix: `moe.*`
- HYPERNOVA scoring chain:
  - `main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java` (TWPM, dynamic p)
  - `main/java/com/nova/protocol/fusion/CvarAggregator.java` (CVaR alpha=0.1, golden-ratio fuse)
  - `main/java/com/nova/protocol/alloc/RiskKAllocator.java` (softmax K-budget contract)
  - `main/java/com/nova/protocol/alloc/SimpleRiskKAllocator.java` (default K allocator)
  - `main/java/com/example/lms/llm/LowRankWhiteningTransform.java` (ZCA transform)
  - `main/java/com/example/lms/service/rag/mp/LowRankWhiteningStats.java` (low-rank whitening stats)
  - `main/java/com/nova/protocol/fusion/BodeClamp.java` (output clamp to [0,1])
  - Trace prefix: `hypernova.*`
- CIH-RAG / IQR / MLA Breadcrumb:
  - `main/java/com/example/lms/service/rag/chain/AttachmentContextHandler.java` (IQR loop, maxIter=3)
  - `main/java/com/example/lms/service/AttachmentService.java` (UPLOADING→INDEXING→ACTIVE FSM)
  - `main/java/com/example/lms/telemetry/LoggingSseEventPublisher.java` (breadcrumb SSE stream)
  - `main/java/ai/abandonware/nova/orch/router/LlmRouterBandit.java` (UCB1 bandit)
  - `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java` (STRATEGY/EXECUTION routing)
  - `main/java/ai/abandonware/nova/orch/trace/MlaOtelBridge.java` (MLA telemetry bridge)
  - `main/java/com/example/lms/web/TraceFilter.java` (request breadcrumb source)
  - Trace prefix: `cihRag.*`
- Matryoshka Slicing / embedding:
  - `main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java` (4096→1536 Arrays.copyOf)
  - `main/resources/application-llm.yaml` key `embedding.target-dim`, `embedding.slice-method`
  - Trace prefix: `embed.*`

## Mode Policy

| Mode | Use When | Main Action | Safety Requirement |
| --- | --- | --- | --- |
| Safe | normal query | balanced Web/Vector/RRF | default citation and PII gates |
| AP1_AUTH_WEB | authority critical | official web first | domain authority scoring |
| AP3_VEC_DENSE | internal memory critical | vector first | vector poison/context purity guard |
| AP9_COST_SAVER | simple low-risk query | fast/local model, small K | no hidden external fallback |
| Brave | low recall, hard query | SelfAsk + QueryBurst + Overdrive | stronger citation threshold |
| RuleBreak | admin emergency | temporary policy relaxation | audit + notice + token check |
| FullScale | approved maximum search | all boosters under one plan | strict final gates and audit |
| Hypernova | long-tail sparse signal | TWPM/CVaR/Risk-K | score clamp and contradiction check |
| Side Train | irregular or risky query | narrow high-authority probe | replace unsafe regular answer |
| Zero-100 | latency unstable query | timeboxed 3-branch rotation | budget propagation and cancellation |
| ExtremeZ | doc count < 2 AND contradiction > 0.7, OR urgency keyword | 12-24 parallel sub-queries via QueryBurstExpander | PromptContext only, no direct prompt text |
| Overdrive | candidates < 3 AND (lowAuth OR contradiction) | K stages [48,32,16,8] anchor filter + exact-phrase probe | try-catch fail-soft, bypassReason trace |
| MoE | every query | plate selection + UCB1 model assignment + critic loop | max 3 critic retries, ExpectedFailure on exhaustion |

## Subsystem Trigger Thresholds (defaults, all overridable via properties)

| Subsystem | Property Key | Default |
|-----------|-------------|----------|
| Overdrive minCandidates | `overdrive.min-candidates` | 3 |
| Overdrive authThreshold | `overdrive.auth-threshold` | 0.4 |
| Overdrive contraThreshold | `overdrive.contra-threshold` | 0.6 |
| Overdrive kStages | `overdrive.k-stages` | [48,32,16,8] |
| ExtremeZ minDoc | `extreme-z.min-doc-threshold` | 2 |
| ExtremeZ contra | `extreme-z.contra-threshold` | 0.7 |
| ExtremeZ parallelTimeout | `extreme-z.parallel-timeout-ms` | 8000 |
| ExtremeZ subQueries | `extreme-z.sub-query-range` | [12,24] |
| CFVM boltzmannTemp | `cfvm.boltzmann-temp` | 1.0 |
| CFVM errorCost.timeout | `cfvm.error-cost.timeout` | 1.0 |
| CFVM errorCost.cancel | `cfvm.error-cost.cancel` | 0.5 |
| CFVM errorCost.rateLimit | `cfvm.error-cost.rate-limit` | 0.3 |
| CVaR alpha | `hypernova.cvar-alpha` | 0.1 |
| CVaR phi | `hypernova.cvar-phi` | 0.618 |
| TWPM maxP | `hypernova.twpm-max-p` | 8 |
| Matryoshka targetDim | `embedding.target-dim` | 1536 |
| MoE criticMaxRetry | `moe.critic-max-retry` | 3 |
| MoE faithfulness | `moe.critic-faithfulness-thr` | 0.6 |
| MoE abTrafficPct | `moe.ab-traffic-pct` | 5 |
| CIH-RAG iqrMaxIter | `cih-rag.iqr-max-iter` | 3 |
| CIH-RAG breadcrumbLowThr | `cih-rag.breadcrumb-low-threshold` | 0.3 |

## Runtime Boundary And Non-Strategy Canonical Seams

- Active sourceSets: backend `main/java` + `main/resources`; `:app` `app/src/main/java_clean` + `app/src/main/resources`. `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups, archives, generated outputs = inactive/reference unless Gradle evidence proves otherwise.
- Application entry: `main/java/com/example/lms/LmsApplication.java`. Non-web boot must stay valid: servlet request helpers tolerate no current `HttpServletRequest`; servlet `SecurityFilterChain` beans guarded `@ConditionalOnWebApplication(type = SERVLET)`.
- Final prompt assembly: `main/java/com/example/lms/prompt/PromptBuilder.java` — keep final prompt construction on the builder/context boundary.
- CFVM failure-pattern memory: `main/java/com/example/lms/cfvm` (`RawMatrixBuffer`, `RawSlotExtractor`, `CfvmFailureRecorder`).
- Time-budget surfaces split: addon request budget context `main/java/com/abandonware/ai/addons/budget` vs orchestration decisions `main/java/ai/abandonware/nova/orch/timebudget/TimeBudgetGuard.java`; similarly named `TimeBudget` stubs elsewhere are local adapters until live call evidence proves otherwise.
- PII guard naming is case-sensitive: `main/java/com/example/lms/service/guard/PIISanitizer.java` (service-layer) vs `main/java/com/example/lms/guard/PiiSanitizer.java` (legacy) — do not merge by class name.
- Fail-soft/provider seams: `HybridWebSearchProvider`, `NaverSearchService`, `BraveSearchService`, `SerpApiProvider`, `NightmareBreaker`, `QueryTransformer`, `TraceStore`, `DebugEventStore` — patch only after live failure evidence.
- Alias warnings: files under `com/example/lms/extreme`, `com/example/lms/nova/extremez`, `com/abandonware/ai/service/rag/**` are aliases/adapters unless live evidence proves otherwise — never Spring components.

## Cross-Reference

For implementation-level trigger logic, code snippets, and per-subsystem trace keys,
read `$demo1-subsystem-patch-directive`.

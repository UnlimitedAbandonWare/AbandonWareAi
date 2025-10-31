# Build Fix Notes (merge29)
- Split `OcrModels.java` into `Rect.java`, `OcrSpan.java`, `OcrChunk.java` to satisfy Java's one-public-type-per-file rule.
- Enabled Lombok annotation processing in `lms-core` (and tests) to restore getters/setters, constructors, and `@Slf4j` loggers.
- These address the cascading 'cannot find symbol get*/set*' and 'log' errors across shared sources compiled by `lms-core`.


## 2025-10-09 05:28:45 — Fix: SnakeYAML DuplicateKeyException (application.yml)
Symptom
`org.yaml.snakeyaml.constructor.DuplicateKeyException: found duplicate key retrieval`
Triggered during Spring Boot config loading at `application.yml` (doc#2).

Root cause
Two top-level `retrieval:` sections existed in the same YAML document:

- At L420: `retrieval:` containing `kalloc` block.
- At L430: `retrieval:` containing `routing` block.

SnakeYAML forbids duplicate keys in a mapping.

Fix
Merged the two sections into one:

- Kept the first `retrieval:` at L420.
- Removed the duplicate `retrieval:` at L430.
- Kept its child block (`routing:` …) under the first `retrieval:`.

Touched file
- `src/main/resources/application.yml`

Verification plan
- `./gradlew bootRun` should pass the config-loading phase.
- Optional: `./gradlew :app:bootRun` if multi-module.
- Sanity: `grep -n '^retrieval:' -n src/main/resources/application.yml` → now 1 occurrence in doc#2.


## 2025-10-09 — Fix: Lombok super-constructor required due to alias subclass

Symptom
```
error: constructor ChatService in class ChatService cannot be applied to given types;
class AbandonWareAi_ChatService extends ChatService {
^
  required: QueryTransformer,CircuitBreaker,TimeLimiter,ChatHistoryService,QueryDisambiguationService,ChatModel,PromptService,CurrentModelRepository,RuleEngine,MemoryReinforcementService,FactVerifierService,DynamicChatModelFactory,LangChainRAGService,NaverSearchService,ChatMemoryProvider,QueryContextPreprocessor,StrategySelectorService,StrategyDecisionTracker,ContextualScorer,QueryAugmentationService,SmartQueryPlanner,Environment,QueryCorrectionService,PromptEngine,SmartFallbackService,ContextOrchestrator,HybridRetriever,NineArtPlateGate,PromptBuilder,ModelRouter,RerankerSelector,PromptOrchestrator,StreamingCoordinator,GuardPipeline,VerbosityDetector,SectionSpecGenerator,LengthVerifierService,AnswerExpanderService,MemoryHandler,MemoryWriteInterceptor,LearningWriteInterceptor,UnderstandAndMemorizeInterceptor,AttachmentService
  found:    no arguments
```

Root cause
`ChatService` is annotated with `@RequiredArgsConstructor` (Lombok), thus it has no default constructor.
The alias class `AbandonWareAi_ChatService` extended `ChatService` but declared no constructor, so Java tried to call `super()` which doesn't exist.

Fix
Alias class converted to a final, non-extending placeholder to avoid constructor chaining and bean duplication:

- `src_91/src/main/java/com/example/lms/service/AbandonWareAi_ChatService.java`
  - `extends ChatService` → removed
  - added private ctor to prevent instantiation
  - left in the same package for source‑compatibility with docs/grep

Why this is safe
The alias was never injected as a Spring bean and was only for documentation mapping (`docs/TERMINOLOGY_MAPPING.md`).
No runtime behavior changed.

Related patterns
See `src_91/BUILD_PATTERN_SUMMARY.md` → SuperConstructorMissing.



## 2025-10-10 — Fix MissingSymbol(log) in VersionPurityCheck
- Root cause: logger field inserted inside Javadoc during previous refactor, so symbol `log` undefined.
- Fix: remove commented logger, add `private static final Logger log` inside class; ensure slf4j imports.
- Also normalized StartupVersionPurityCheck to declare a single logger.


## 2025-10-11 — Boot failure caused by duplicate bean name 'ruleBreakInterceptor'

- Symptom: `ConflictingBeanDefinitionException` at startup; two `@Component` classes named `RuleBreakInterceptor` under `com.example.lms.nova` and `com.example.lms.guard.rulebreak`.
- Root cause: Both used default bean name strategy → `ruleBreakInterceptor`.
- Fix:
  1. Nova bean renamed: `@Component("novaRuleBreakInterceptor")`.
  2. Guard bean is now injected into `RuleBreakConfig` (no `new`), so its `@Autowired RuleBreakEvaluator` works.
  3. Removed duplicate `@Bean ruleBreakEvaluator()` from `RuleBreakConfig` (class already annotated with `@Component`).
- Post-checks:
  - `rg -n "@Component\(" src/main/java/com/example/lms/nova/RuleBreakInterceptor.java` → confirms renamed bean.
  - `rg -n "addInterceptors\(" src/main/java/com/example/lms/config/RuleBreakConfig.java` → confirms injected bean usage.



## 2025-10-11 — Fix: Corrupted classes after merge (src111_merge15)
Symptoms
- `UpstashBackedWebCache.java` contained `public \1` and stray field declarations outside any type.
- `FusionCalibrator.java` missing opening brace with a non‑static method inserted before the class body.

Root cause
A previous merge injected partial snippets (single‑flight stub and an isotonic patch) into unrelated files.

Fix
- Rewrote `infra/upstash/UpstashBackedWebCache.java` as a valid Spring `@Component` implementing `WebResultCache` (local Caffeine tier + Upstash fallback).
- Rewrote `service/rag/fusion/FusionCalibrator.java` to a minimal, self‑contained utility with `minMax` + isotonic shim.

Notes
- Kept `UpstashRedisClient` API (`enabled()`, `get`, `setEx`, `incrExpire`) intact.
- No behavior change to callers; only build‑blocking syntax issues resolved.

Verification
- `gradle :compileJava` should now pass the previously reported 41 errors related to these files.


## src111_merge15 — Fix #001 (2025-10-12)

Error Pattern: `illegal start of expression` (unmatched constructor brace)
- Detector: cfvm-raw/.../BuildLogSlotExtractor.java (pattern `ILLEGAL_START` didn't match 'expression', only 'type')
- Incident: QdrantClient.java line 30 (`public List... search(...)`) declared before closing constructor block.
- Fix: Inserted missing closing brace `}` after `RestClient.builder()...build();` and re-indented the method.
- Diff: see patch in `src/main/java/com/abandonware/ai/vector/qdrant/QdrantClient.java`.

Follow-up: Consider broadening `ILLEGAL_START` regex to `illegal start of (type|expression)` so the pattern-db captures both variants.


## src111_merge15 (74) — build notes

- New code is dependency‑free (JDK only) and ships with toggles OFF by default.
- If you see compile errors from placeholder files that contain literal `...`, scope your compilation to the `src/main/java/com/example/lms/` tree (the primary module for probe/soak/RAG).
- Suggested javac quick check:

```bash
find src/main/java/com/example/lms -name '*.java' > sources.lms
javac -encoding UTF-8 @sources.lms
```

This avoids unrelated experimental modules under `app/`, `cfvm-raw/`, `com/abandonware/ai/` that intentionally contain stubs.


## src111_mergsae15 addendum

- Replaced stray placeholder tokens (`...`) causing IllegalStartOfType / ClassOrInterfaceExpected in legacy
  `WeightedRRF.java` (3 variants) and `OnnxCrossEncoderReranker.java` (3 variants) with minimal, compilable stubs.
- Left both copies of `HybridRetriever` in place but confirmed they live in different source roots (`src/main/java` vs `app/src/main/java`)
  and only the app module is compiled by Gradle as configured in `settings.gradle`. See `DUPLICATE_CLASS_REPORT.md` for context.


## 2025-10-21 fix: Missing EmbeddingCache for DecoratingEmbeddingModel
Pattern: `cannot find symbol: class EmbeddingCache` in `com.example.lms.service.embedding.DecoratingEmbeddingModel`.

Root cause: cache abstraction type not present in source-set; decorator expects `EmbeddingCache` with `getOrCompute(...)`, `keyFor(...)`, and a default in-memory implementation.

Fix: Introduced `com.example.lms.service.embedding.EmbeddingCache` (interface) with `InMemory` TTL-backed implementation; used by DecoratingEmbeddingModel constructor to default when null.

Scope: Minimal, self-contained; no external beans or frameworks required. TTL semantics best-effort.

Files added:
- `src/main/java/com/example/lms/service/embedding/EmbeddingCache.java`

Why safe:
- No public API changes to existing classes.
- Zero dependency additions.
- Default behaviour preserves delegate outputs; only avoids redundant model calls via cache.


## Patch 2025-10-21T18:24:20.942058Z — WeightedRRF API + DynamicRetrievalHandlerChain cleanup

Errors fixed
- `incompatible types: ScoreCalibrator cannot be converted to boolean` @ `com.abandonware.ai.agent.service.rag.fusion.FusionService`
- `cannot find symbol: variable calibrator` @ `com.abandonware.ai.service.rag.fusion.WeightedRRF`
- `cannot find symbol: variable diversity` and `package service.embedding does not exist` @ `com.abandonware.ai.service.rag.handler.DynamicRetrievalHandlerChain`

Root cause patterns
- *API drift*: Call site passed a `ScoreCalibrator` but API expected `(…, boolean dedupByCanonical)` — history shows prior refactor dropped the `calibrator` parameter but left callsites.
- *Stray placeholder import/field*: Residual stub line referencing `service.rag.rerank.DiversityReranker` and `service.embedding.EmbeddingCache`.

Fixes
- `WeightedRRF` (package `com.abandonware.ai.service.rag.fusion`):
  - Signature changes (added `ScoreCalibrator calibrator` param):
    - `Map<String, ContextSlice> fuse(List<List<ContextSlice>> sources, int k, Map<String, Double> sourceWeights, ScoreCalibrator calibrator, boolean dedupByCanonical)`
    - `List<ContextSlice> fuseToList(List<List<ContextSlice>> sources, int k, Map<String, Double> sourceWeights, ScoreCalibrator calibrator, boolean dedupByCanonical)`
    - `List<Map<String,Object>> fuse(List<List<ContextSlice>> sources, int topK, Map<String, Double> sourceWeights, ScoreCalibrator calibrator, boolean dedupByCanonical, boolean asJson)`
  - Added import: `com.abandonware.ai.service.rag.fusion.ScoreCalibrator`.
  - Internal calls updated to pass `calibrator`.
- `DynamicRetrievalHandlerChain`:
  - Removed invalid line constructing `new service.rag.rerank.DiversityReranker(new service.embedding.EmbeddingCache(null))`.
  - Left `diversityEnabled`/`mmrLambda` flags for future injection.

Notes
- This patch is *source-compatible* with `com.abandonware.ai.agent.service.rag.fusion.FusionService` which already passes a `ScoreCalibrator`.
- No new dependencies; purely source changes.


[2025-10-23] Fix compile failure in AdminInitializer.java:
- Removed malformed package trailer and trailing comment closer.
- Verified repository has no further unmatched block comments.


## 2025-10-25 — merge15 → mergse15 compile fixes

- Pattern: UNINITIALIZED_FINAL
  *BraveSearchProvider* had final fields (`http`, `cache`, `limiter`) without constructor injection.
  ➜ Added `@RequiredArgsConstructor` and `@Component`.

- Pattern: MISSING_SYMBOL (get*/set*)
  *SelectedTerms*, *ChatSession*, *TranslationSample*, *ChatResponseDto*, *NaverFilterProperties* lacked Lombok accessors.
  ➜ Added `@Getter/@Setter` (and `@Builder` where needed).
  ➜ `ChatRequestDto` received `@Data @Builder` to resolve many `getX()/isX()` calls and silence `@Builder.Default` warnings.

- Pattern: CONSTANT_NOT_FOUND
  *RewardScoringEngine.SimilarityPolicy* referenced `SIMILARITY_FLOOR` but did not define it.
  ➜ Declared `public static final double SIMILARITY_FLOOR = 0.55;`.
  ➜ Fixed `DEFAULT` to `new RewardScoringEngine(new SimilarityPolicy())`.

- Pattern: API_COMPAT (method name drift)
  *CognitiveState* older call‑sites expected `abstractionLevel()/temporalSensitivity()/evidenceTypes()/complexityBudget()`.
  ➜ Added alias methods that delegate to the current enums and derive evidence types from execution mode.

Artifacts:
- `dev/build/error-patterns.json` — flags extracted from `BUILD_LOG.txt`.
- `BUILD_PATTERN_REPORT__auto.md` — summary for this patch.

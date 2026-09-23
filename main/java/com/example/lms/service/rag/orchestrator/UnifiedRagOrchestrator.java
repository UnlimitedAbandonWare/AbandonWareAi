package com.example.lms.service.rag.orchestrator;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.config.RagProperties;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.moe.NormalizedRagMetrics;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.offline.OfflineTextureSnapshotWriter;
import com.example.lms.service.rag.query.QueryAnalysisResult;
import com.example.lms.service.rag.query.QueryAnalysisService;
import com.example.lms.service.rag.filter.ContextConsistencyFilter;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

/**
 * UnifiedRagOrchestrator
 *
 * Goal:
 * - Wire Web / Vector / KG / BM25 retrievers into a single pipeline.
 * - Fuse results via Weighted-RRF.
 * - Two-pass rerank: Bi-Encoder prefilter -> ONNX Cross-Encoder.
 * - Domain whitelist gating, caching, hedging-aware fallbacks.
 * - Optional Self-Ask (3-way) + Plan DSL execution hooks.
 *
 * NOTE: This is a thin orchestration layer that delegates to existing services
 * if present.
 * It is intentionally defensive (null-safe) to avoid build breaks in
 * heterogeneous src trees.
 */
@org.springframework.stereotype.Component
public class UnifiedRagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(UnifiedRagOrchestrator.class);
    private static final String ONNX_DOC_INDEX_META = "_awx.onnxDocIndex";
    // SelfAsk exec bounds: planner wall-time cap, max merged sub-queries, and
    // per-sub-query leg K. Values stay local — the feature is flag-gated.
    private static final long SELFASK_PLANNER_MAX_MS = 4_000L;
    private static final int SELFASK_MAX_SUBQUERIES = 3;
    private static final int SELFASK_LEG_TOPK = 6;
    private static final Consumer<String> INVALID_NUMBER_SUPPRESSOR = stage -> {
        switch (stage) {
            case "toDouble" -> {
                TraceStore.put("rag.orchestrator.suppressed.toDouble", true); TraceStore.put("rag.orchestrator.suppressed.toDouble.errorType", "invalid_number");
            }
            case "traceInt" -> {
                TraceStore.put("rag.orchestrator.suppressed.traceInt", true); TraceStore.put("rag.orchestrator.suppressed.traceInt.errorType", "invalid_number");
            }
            case "onnxDocIndex" -> {
                TraceStore.put("rag.orchestrator.suppressed.onnxDocIndex", true); TraceStore.put("rag.orchestrator.suppressed.onnxDocIndex.errorType", "invalid_number");
            }
            case "kgRelationThumbnailInt" -> {
                TraceStore.put("rag.orchestrator.suppressed.kgRelationThumbnailInt", true); TraceStore.put("rag.orchestrator.suppressed.kgRelationThumbnailInt.errorType", "invalid_number");
            }
            default -> {
                TraceStore.put("rag.orchestrator.suppressed." + stage, true); TraceStore.put("rag.orchestrator.suppressed." + stage + ".errorType", "invalid_number");
            }
        }
    };

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryAnalysisService queryAnalysisService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ContextConsistencyFilter contextConsistencyFilter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OfflineTextureSnapshotWriter offlineTextureSnapshotWriter;

    @org.springframework.beans.factory.annotation.Value("${rag.eval.debug-events.enabled:false}")
    private boolean ragEvalDebugEventsEnabled;

    @org.springframework.beans.factory.annotation.Value("${retrieval.web.required:false}")
    private boolean webRequired;

    @org.springframework.beans.factory.annotation.Value("${retrieval.vector.required:false}")
    private boolean vectorRequired;

    @org.springframework.beans.factory.annotation.Value("${retrieval.kg.required:false}")
    private boolean kgRequired;

    // RRF weights/constant are configuration-driven (rag.rrf.*).
    // Initialized with defaults to keep non-Spring smoke tests compiling/running.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RagProperties ragProperties = new RagProperties();

    public static class QueryRequest implements Serializable {
        private static final long serialVersionUID = 1L;

        public boolean enableDiversity = true; // DPP diversity rerank toggle
        public double diversityLambda = 0.7; // relevance vs novelty trade-off
        public String query;
        public boolean useWeb = true;
        public boolean useVector = true;
        public boolean useKg = true;
        public boolean useBm25 = true;
        public boolean enableSelfAsk = false;
        /** SelfAsk 플래너 산출 하위 질의 — exec 단계가 채우고 bounded 로컬 레그가 소비한다. */
        public List<String> selfAskSubQueries;
        public boolean enableQueryAnalysis = true; // Preclassified real-time callers may skip the extra LLM analysis.
        public boolean webQueryAlreadyPlanned = false;
        public String planId = "safe_autorun.v1";
        public String threadId;
        public int topK = 8;
        public Integer webTopK;
        public Integer vectorTopK;
        public Integer kgTopK;
        public boolean enableOnnx = true;
        public boolean enableBiEncoder = true;
        // Guard / Jammini projection
        public String jamminiMode; // safe/brave/zero_break/free
        public String memoryProfile; // MEMORY / NONE
        public boolean entityQuery; // whether this is an entity-centric question
        public boolean deepResearch = false; // deeper exploration / flush mode
        public boolean whitelistOnly = false;

        // [NEW] 공격적 모드 플래그 (자동으로 켜지지 않음: 호출자가 명시적으로 제어)
        public boolean aggressive = false;

        // [NEW] 타입 고정 옵션 (options Map 사용 금지)
        public List<String> noiseDomains;
        public String expectedDomain;

        // [NEW] 기존 Context/RAG 결과 메타(웹/벡터 검색 결과)를 오케스트레이터에
        // "씨앗(seed)"으로 주입할 때 사용합니다.
        // - seedWeb/seedVector: dev.langchain4j Content 리스트를 그대로 전달하면
        //   WEB/VECTOR 단계 결과처럼 취급하여 재-퓨전/재랭크를 수행합니다.
        // - seedCandidates: 이미 Doc 형태로 구성된 후보 풀을 직접 주입할 때 사용합니다.
        public List<Content> seedWeb;
        public List<Content> seedVector;
        public List<Doc> seedCandidates;

        /** web | vector | candidates (default=candidates) */
        public String seedMode = "candidates";
        /** true면 실제 검색(retrieval)을 스킵하고 seed만으로 재현한다. */
        public boolean seedOnly = false;
    }

    public static class Doc implements Serializable {
        private static final long serialVersionUID = 1L;

        public String id;
        public String title;
        public String snippet;
        public String source;
        public double score;
        public int rank;
        public Map<String, Object> meta;
    }

    public static class QueryResponse implements Serializable {
        private static final long serialVersionUID = 1L;

        public String requestId;
        public String planApplied;
        public List<Doc> results = new ArrayList<>();
        public Map<String, Object> debug = new LinkedHashMap<>();
    }

    /**
     * Probe/Soak/디버깅용: 오케스트레이션 단계별 후보를 그대로 노출하기 위한 Trace 컨테이너.
     * (운영 답변 생성 경로와 분리되어 있어도 동일 파이프라인으로 재현 가능)
     */
    public static class QueryTrace implements Serializable {
        private static final long serialVersionUID = 1L;

        public QueryResponse response;
        public List<Doc> seed = new ArrayList<>();
        public List<Doc> web = new ArrayList<>();
        public List<Doc> vector = new ArrayList<>();
        public List<Doc> kg = new ArrayList<>();
        public List<Doc> bm25 = new ArrayList<>();
        public List<Doc> pool = new ArrayList<>();
        public List<Doc> fused = new ArrayList<>();
        public List<Doc> biencoder = new ArrayList<>();
        public List<Doc> dpp = new ArrayList<>();
        public List<Doc> onnx = new ArrayList<>();
        public List<Doc> finalResults = new ArrayList<>();
    }

    // --- Dependencies (migrated to DI; no more Class.forName for main retrievers)
    // ---
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("analyzeWebSearchRetriever")
    private ContentRetriever webRetriever; // 웹 검색용

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("vectorRetriever")
    private ContentRetriever vectorRetriever; // 벡터/하이브리드 검색용 (일반 채팅 하이브리드 경로)

    // Unified VECTOR 축은 순수 벡터 리프만 사용한다. LangChainRAGService가 없으면
    // 해당 단계는 unavailable로 기록되며, 웹/SelfAsk 부수효과가 있는 레거시
    // vectorRetriever(HybridRetriever) 빈으로 폴백하지 않는다.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.LangChainRAGService langChainRAGService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("knowledgeGraphHandler")
    private ContentRetriever kgRetriever; // 지식 그래프 검색용

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.service.rag.bm25.Bm25Index bm25Index;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("crossEncoderReranker")
    private com.example.lms.service.rag.rerank.CrossEncoderReranker biEncoder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("onnxCrossEncoderReranker")
    private com.example.lms.service.rag.rerank.CrossEncoderReranker onnxReranker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.auth.DomainWhitelist domainWhitelist;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.SelfAskPlanner selfAskPlanner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanHintApplier planHintApplier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlanDslExecutor planDslExecutor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.rerank.DppDiversityReranker dppDiversityReranker;

    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.nova.protocol.fusion.NovaNextFusionService novaNextFusionService;
    public UnifiedRagOrchestrator() {
        // Dependencies are injected by Spring; default constructor kept for
        // frameworks/tests.
    }

    @jakarta.annotation.PostConstruct
    void validateRequiredDependencies() {
        // Master switch reuses the existing rag.eval.debug-events.enabled
        // property; per-request override is request.trace.enabled in TraceStore.
        com.example.lms.search.RequestTrace.setMasterEnabled(ragEvalDebugEventsEnabled);
        requireDependency("web", webRequired, webRetriever, "analyzeWebSearchRetriever");
        requireDependency("vector", vectorRequired, vectorLeaf(), "langChainRAGService.unified-vector");
        requireDependency("kg", kgRequired, kgRetriever, "knowledgeGraphHandler");
    }

    private static void requireDependency(String axis, boolean required, Object bean, String beanName) {
        if (required && bean == null) {
            throw new IllegalStateException("retrieval." + axis + ".required=true but required bean '" + beanName
                    + "' is missing");
        }
    }

    private com.example.lms.plan.PlanExecutionSpec applyPlanHints(QueryRequest req, QueryResponse resp, Map<String, Object> dbg) {
        if (req == null || dbg == null) {
            return null;
        }
        boolean callerEnableSelfAsk = req.enableSelfAsk;
        String requestedPlan = safePlanId(req), requestedPlanDiagnostic = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(requestedPlan, "safe_autorun.v1");
        req.planId = requestedPlan;
        resp.planApplied = requestedPlanDiagnostic;
        if (planHintApplier == null) {
            dbg.putIfAbsent("plan.source", planDslExecutor == null ? "none" : "PlanDslExecutor");
            dbg.putIfAbsent("plan.applied", false);
            dbg.putIfAbsent("plan.disabledReason",
                    planDslExecutor == null ? "missing_plan_hint_applier" : "legacy_opaque_executor");
            return null;
        }

        try {
            PlanHints plan = planHintApplier.load(requestedPlan);
            if (plan == null) {
                dbg.put("plan.source", "PlanHintApplier");
                dbg.put("plan.id", requestedPlanDiagnostic);
                dbg.put("plan.applied", false);
                dbg.put("plan.disabledReason", "empty_plan");
                return null;
            }

            req.planId = safePlanId(plan.planId());
            String appliedPlanDiagnostic = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(req.planId, "safe_autorun.v1");
            resp.planApplied = appliedPlanDiagnostic;

            OrchestrationHints orchestrationHints = OrchestrationHints.defaults();
            orchestrationHints.setAllowWeb(req.useWeb);
            orchestrationHints.setAllowRag(req.useVector || req.useKg);
            orchestrationHints.setEnableSelfAsk(req.enableSelfAsk);
            orchestrationHints.setEnableCrossEncoder(req.enableOnnx);
            Map<String, Object> meta = new LinkedHashMap<>();
            planHintApplier.applyToHintsAndMeta(plan, orchestrationHints, meta);

            if (Boolean.FALSE.equals(plan.allowWeb())) {
                req.useWeb = false;
            }
            if (Boolean.FALSE.equals(plan.allowRag())) {
                req.useVector = false;
                req.useKg = false;
            }
            if (Boolean.FALSE.equals(meta.get("retrieval.web.enabled"))) {
                req.useWeb = false;
            }
            if (Boolean.FALSE.equals(meta.get("retrieval.vector.enabled"))) {
                req.useVector = false;
            }
            if (req.webTopK == null && positive(plan.webTopK())) {
                req.webTopK = plan.webTopK();
            } else if (req.webTopK == null && plan.kSchedule() != null && !plan.kSchedule().isEmpty()
                    && positive(plan.kSchedule().get(0))) {
                req.webTopK = plan.kSchedule().get(0);
            }
            if (req.vectorTopK == null && positive(plan.vecTopK())) {
                req.vectorTopK = plan.vecTopK();
            }
            if (req.kgTopK == null && positive(plan.kgTopK())) {
                req.kgTopK = plan.kgTopK();
            }
            if (Boolean.TRUE.equals(plan.officialSourcesOnly())) {
                req.whitelistOnly = true;
            }
            if (plan.onnxEnabled() != null) {
                req.enableOnnx = plan.onnxEnabled();
            }
            if (plan.useCrossEncoder() != null) {
                req.enableOnnx = plan.useCrossEncoder();
            }
            if (Boolean.FALSE.equals(meta.get("diversity.dpp.enabled"))) {
                req.enableDiversity = false;
            }
            if (orchestrationHints.isEnableSelfAsk()) {
                req.enableSelfAsk = true;
            }

            // plan.when gate: the plan's own activation condition is evaluated
            // against observable request scope. A conclusively FALSE verdict
            // suppresses plan-driven expansion (caller-set flags are kept);
            // UNKNOWN never fabricates metric evidence and preserves selection.
            com.example.lms.plan.PlanExecutionSpec execSpec = planHintApplier.loadExecutionSpec(requestedPlan);
            com.example.lms.plan.PlanExecutionSpec.WhenVerdict whenVerdict =
                    execSpec.evaluateWhen(planRequestScope(req));
            dbg.put("plan.when", whenVerdict.state().name().toLowerCase(Locale.ROOT));
            dbg.put("plan.when.conditions", whenVerdict.debugView());
            if (!execSpec.pipeline().isEmpty()) {
                dbg.put("plan.pipeline.declared", execSpec.pipeline());
            }
            if (!execSpec.diagnostics().isEmpty()) {
                dbg.put("plan.spec.diagnostics", execSpec.diagnostics());
            }
            if (!callerEnableSelfAsk && req.enableSelfAsk
                    && whenVerdict.state() == com.example.lms.plan.PlanExecutionSpec.TriState.FALSE
                    && execSpec.declaresExpansion()) {
                req.enableSelfAsk = false;
                dbg.put("plan.selfAsk.gated", "when_false");
                TraceStore.put("plan.selfAsk.gated", "when_false");
                com.example.lms.search.RequestTrace.emit("rag.selfask", "gated", "reason=when_false");
            }

            List<String> planDslUnwiredKeys = PlanHintApplier.dslUnwiredKeys(plan);
            dbg.put("plan.source", "PlanHintApplier");
            dbg.put("planHints", "applied:" + appliedPlanDiagnostic); dbg.put("planDsl", "not_used:plan_hint_applier"); dbg.put("planDsl.status", "not_used"); dbg.put("planDsl.loaded", false); dbg.put("planDsl.unwiredKeys", planDslUnwiredKeys);
            TraceStore.put("planHints.status", "applied"); TraceStore.put("planDsl.status", "not_used"); TraceStore.put("planDsl.loaded", false); TraceStore.put("planDsl.unwiredKeys", planDslUnwiredKeys);
            dbg.put("plan.id", appliedPlanDiagnostic);
            dbg.put("plan.applied", !plan.isEmpty());
            if (plan.isEmpty()) {
                dbg.put("plan.disabledReason", "empty_or_missing_plan");
            }
            dbg.put("plan.allowWeb", req.useWeb);
            dbg.put("plan.allowRag", req.useVector || req.useKg);
            dbg.put("plan.webTopK", effectiveWebTopK(req));
            dbg.put("plan.vectorTopK", effectiveVectorTopK(req));
            dbg.put("plan.kgTopK", effectiveKgTopK(req));
            dbg.put("plan.selfAsk.enabled", req.enableSelfAsk);
            dbg.put("plan.onnx.enabled", req.enableOnnx);
            dbg.put("plan.officialOnly", req.whitelistOnly);
            TraceStore.put("plan.id", requestedPlanDiagnostic.startsWith("hash:") ? requestedPlanDiagnostic : appliedPlanDiagnostic);
            TraceStore.put("plan.source", "PlanHintApplier");
            TraceStore.put("plan.webTopK", effectiveWebTopK(req));
            TraceStore.put("plan.vectorTopK", effectiveVectorTopK(req));
            TraceStore.put("plan.kgTopK", effectiveKgTopK(req));
            return execSpec;
        } catch (Exception e) {
            dbg.put("plan.source", "PlanHintApplier");
            dbg.put("plan.id", requestedPlanDiagnostic);
            dbg.put("plan.applied", false);
            dbg.put("plan.disabledReason", "plan_apply_failed");
            TraceStore.put("plan.apply.error", "plan_apply_failed");
            return null;
        }
    }

    /**
     * Request-side scope observable before retrieval: header/context channels
     * backed by the real request interceptors. Metrics are added post-retrieval
     * by {@link #planObservedMetrics}; unobserved keys stay UNKNOWN.
     */
    private static Map<String, Object> planRequestScope(QueryRequest req) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("request.header.x-brave-mode", braveModeOn() ? "on" : "off");
        if (req != null) {
            scope.put("request.plan.id", req.planId);
            scope.put("request.jammini_mode", req.jamminiMode);
            scope.put("request.memory_profile", req.memoryProfile);
            scope.put("request.aggressive", req.aggressive);
            scope.put("request.deep_research", req.deepResearch);
            scope.put("request.entity_query", req.entityQuery);
        }
        return scope;
    }

    private static boolean braveModeOn() {
        if (com.example.lms.nova.NovaRequestContext.isBrave()) {
            return true;
        }
        return trace.TraceContext.isBrave();
    }

    /**
     * Post-retrieval metrics mapped to plan-eval scope. Only genuinely observed
     * values are exposed; {@code metrics.initial_recall} has no producer and is
     * deliberately unmapped so it evaluates UNKNOWN instead of a fabricated 0.
     */
    private static Map<String, Object> planObservedMetrics(Map<String, Object> debug) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (debug != null) {
            putMetricAlias(debug, metrics, "rag.eval.resultCount", "metrics.result_count");
            putMetricAlias(debug, metrics, "rag.eval.distinctSourceCount", "metrics.distinct_sources");
            putMetricAlias(debug, metrics, "rag.eval.emptyResult", "metrics.empty_result");
        }
        Object confidence = TraceStore.get("rag.answerQuality.confidence");
        if (confidence != null) {
            metrics.put("metrics.retrieval_confidence", confidence);
        }
        return metrics;
    }

    private static void putMetricAlias(Map<String, Object> debug,
                                       Map<String, Object> metrics,
                                       String debugKey,
                                       String metricKey) {
        Object value = debug.get(debugKey);
        if (value != null) {
            metrics.put(metricKey, value);
        }
    }

    private static String safePlanId(QueryRequest req) {
        return req == null ? "safe_autorun.v1" : safePlanId(req.planId);
    }

    private static String safePlanId(String planId) {
        return planId == null || planId.isBlank() ? "safe_autorun.v1" : planId.trim();
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static int effectiveWebTopK(QueryRequest req) {
        return effectiveTopK(req == null ? null : req.webTopK, req == null ? 8 : req.topK);
    }

    private static int effectiveVectorTopK(QueryRequest req) {
        return effectiveTopK(req == null ? null : req.vectorTopK, req == null ? 8 : req.topK);
    }

    private static int effectiveKgTopK(QueryRequest req) {
        if (req != null && req.kgTopK != null) {
            return req.kgTopK;
        }
        int base = req == null ? 8 : req.topK;
        return Math.min(50, Math.max(12, base * 2));
    }

    private static int effectiveTopK(Integer override, int fallback) {
        return override != null ? override : fallback;
    }

    /**
     * Bounded requested/applied-settings summary for RequestTrace. Primitive
     * boolean flags cannot distinguish caller-set-false from field-default-false
     * (recorded as flags_src=primitive); nullable Integer topK overrides can.
     * planId/memoryProfile pass through SafeRedactor's label path — raw query
     * text and user identifiers are never included.
     */
    private static String settingsSummary(QueryRequest req) {
        if (req == null) {
            return "req=null";
        }
        return "useWeb=" + req.useWeb
                + ";useVector=" + req.useVector
                + ";useKg=" + req.useKg
                + ";useBm25=" + req.useBm25
                + ";seedOnly=" + req.seedOnly
                + ";whitelistOnly=" + req.whitelistOnly
                + ";selfAsk=" + req.enableSelfAsk
                + ";flags_src=primitive"
                + ";topK=" + req.topK
                + ";webTopK=" + topKSource(req.webTopK)
                + ";vectorTopK=" + topKSource(req.vectorTopK)
                + ";kgTopK=" + topKSource(req.kgTopK)
                + ";memoryProfile=" + com.example.lms.trace.SafeRedactor
                        .traceLabelOrFallback(req.memoryProfile, "unset")
                + ";planId=" + com.example.lms.trace.SafeRedactor
                        .traceLabelOrFallback(req.planId, "safe_autorun.v1");
    }

    private static String topKSource(Integer override) {
        return override == null ? "default" : "explicit:" + override;
    }

    private static int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static void validateTopKRequest(QueryRequest req) {
        if (req == null) {
            return;
        }
        requireNonNegativeTopK("topK", req.topK);
        requireNonNegativeTopK("webTopK", req.webTopK);
        requireNonNegativeTopK("vectorTopK", req.vectorTopK);
        requireNonNegativeTopK("kgTopK", req.kgTopK);
    }

    private static void requireNonNegativeTopK(String field, Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be greater than or equal to zero");
        }
    }

    // NOTE:
    // - All optional components are injected explicitly via Spring.
    // - Reflection/"tryResolve" is intentionally removed to keep dependencies compile-time visible.
    /**
     * Entry point. This method keeps side effects minimal to remain safe to adopt.
     */
    public QueryResponse query(QueryRequest req) {
        return queryInternal(req, new QueryTrace());
    }

    /**
     * Probe/Soak 등에서 단계별 후보를 재현/관찰할 수 있도록 Trace를 함께 반환합니다.
     */
    public QueryTrace queryWithTrace(QueryRequest req) {
        QueryTrace trace = new QueryTrace();
        trace.response = queryInternal(req, trace);
        if (trace.response != null && trace.response.results != null) {
            trace.finalResults = snapshotDocs(trace.response.results);
        }
        return trace;
    }

    private QueryResponse queryInternal(QueryRequest req, QueryTrace trace) {
        long runStartedNs = System.nanoTime();
        Map<String, Long> stageMs = new LinkedHashMap<>();
        if (req == null) {
            req = new QueryRequest();
            req.query = "";
        }
        validateTopKRequest(req);
        String requestId = UUID.randomUUID().toString();
        QueryResponse resp = new QueryResponse();
        resp.requestId = requestId;
        resp.planApplied = req.planId;
        com.example.lms.search.RequestTrace.markRequestStart();
        TraceStore.put(com.example.lms.search.RequestTrace.ID_KEY, requestId);
        // Requested settings are captured BEFORE applyPlanHints mutates req;
        // primitive flags cannot distinguish explicit-vs-default (recorded
        // as flags_src=primitive), nullable topK overrides can.
        com.example.lms.search.RequestTrace.emit("rag.request", "received", settingsSummary(req));

        Map<String, Object> dbg = resp.debug;

        QueryAnalysisResult analysis = null;
        boolean isEntityQuery = req.entityQuery;
        long analysisStartedNs = System.nanoTime();
        dbg.put("analysis.skipped", !req.enableQueryAnalysis);
        if (req.enableQueryAnalysis && queryAnalysisService != null && req.query != null && !req.query.isBlank()) {
            try {
                analysis = queryAnalysisService.analyze(req.query);
                if (analysis != null) {
                    // Type-safe analysis outputs (callers can also set these directly on QueryRequest)
                    if (analysis.expectedDomain() != null && !analysis.expectedDomain().isBlank()) {
                        req.expectedDomain = analysis.expectedDomain();
                        dbg.put("analysis.expectedDomain", safeDiagString(req.expectedDomain));
                    }
                    if (analysis.noiseDomains() != null && !analysis.noiseDomains().isEmpty()) {
                        req.noiseDomains = new ArrayList<>(analysis.noiseDomains());
                        dbg.put("analysis.noiseDomains", req.noiseDomains.stream().map(UnifiedRagOrchestrator::safeDiagString).filter(s -> !s.isBlank()).toList());
                    }

                    if (analysis.isEntityQuery()) {
                        isEntityQuery = true;
                        req.entityQuery = true;
                        dbg.put("analysis.intent", analysis.intent().name());
                        dbg.put("analysis.entities", analysis.entities().stream().map(UnifiedRagOrchestrator::safeDiagString).filter(s -> !s.isBlank()).toList());
                        dbg.put("analysis.isEntityQuery", true);
                    }
                }
            } catch (Exception e) {
                log.warn("[AWX][rag][orchestrator] query analysis failed failureReason={} errorType={} queryHash12={} queryLength={}", "query-analysis-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.hash12(req.query), req.query == null ? 0 : req.query.length());
            }
        }

        dbg.put("analysis.elapsedMs", elapsedMs(analysisStartedNs));

        // [Anti-Gravity] Memory Injection Hook
        // - An explicit memoryProfile=NONE is a caller restriction: brave/aggressive
        //   modes and fallbacks must not flip it back to MEMORY.
        if (("brave".equalsIgnoreCase(String.valueOf(req.jamminiMode)) || req.aggressive)
                && "NONE".equalsIgnoreCase(String.valueOf(req.memoryProfile))) {
            dbg.put("memory.inject", "explicit_none_preserved");
        }

        com.example.lms.plan.PlanExecutionSpec planSpec = applyPlanHints(req, resp, dbg);
        com.example.lms.search.RequestTrace.emit("rag.settings", "applied", settingsSummary(req));

        // 0) Optional planning: flag-gated + request-budget-gated SelfAsk
        // 3-lane expansion. The planner emits selfask.3way.* TraceStore keys
        // (consumed by autolearn/debug/copilot); produced sub-queries merge
        // into the bounded local legs inside retrieveCandidates below. Every
        // skip path records a stable reason; no raw query values are logged.
        String selfAskExec = "not_requested";
        List<String> selfAskQueries = List.of();
        if (req.enableSelfAsk && selfAskPlanner == null) {
            selfAskExec = "planner_missing";
            dbg.putIfAbsent("selfAsk", "missing_selfAskPlanner");
        } else if (req.enableSelfAsk && req.seedOnly) {
            selfAskExec = "skipped:seed_only";
        } else if (req.enableSelfAsk) {
            com.abandonware.ai.addons.budget.TimeBudget selfAskBudget =
                    com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            if (selfAskBudget != null && selfAskBudget.expired()) {
                selfAskExec = "skipped:request_budget_exhausted";
                com.example.lms.search.DeadlineProbe.skip("selfask.3way", "request_budget_exhausted");
            } else {
                long selfAskStartedNs = System.nanoTime();
                try {
                    long plannerBudgetMs = selfAskBudget == null
                            ? SELFASK_PLANNER_MAX_MS
                            : Math.min(Math.max(0L, selfAskBudget.remainingMillis()), SELFASK_PLANNER_MAX_MS);
                    java.util.List<com.example.lms.service.rag.SelfAskPlanner.SubQuestion> lanes =
                            selfAskPlanner.generateThreeLanes(req.query, plannerBudgetMs);
                    java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>();
                    java.util.List<String> laneTypes = new java.util.ArrayList<>();
                    if (lanes != null) {
                        for (com.example.lms.service.rag.SelfAskPlanner.SubQuestion lane : lanes) {
                            if (lane != null && lane.text != null && !lane.text.isBlank()) {
                                dedup.add(lane.text.trim());
                                if (lane.type != null) {
                                    laneTypes.add(lane.type.name());
                                }
                            }
                            if (dedup.size() >= SELFASK_MAX_SUBQUERIES) {
                                break;
                            }
                        }
                    }
                    selfAskQueries = List.copyOf(dedup);
                    selfAskExec = "executed:lanes=" + selfAskQueries.size();
                    dbg.put("selfAsk", selfAskExec);
                    if (!laneTypes.isEmpty()) {
                        dbg.put("selfAsk.laneTypes", laneTypes);
                    }
                    com.example.lms.search.RequestTrace.emit("selfask.3way", "exec",
                            "lanes=" + selfAskQueries.size()
                                    + ";elapsedMs=" + elapsedMs(selfAskStartedNs)
                                    + ";budget=" + (selfAskBudget == null ? "absent" : "present"));
                } catch (Exception e) {
                    selfAskExec = "failed:" + e.getClass().getSimpleName();
                    dbg.put("selfAsk", selfAskExec);
                    com.example.lms.search.RequestTrace.emit("selfask.3way", "error",
                            "type=" + e.getClass().getSimpleName()
                                    + ";elapsedMs=" + elapsedMs(selfAskStartedNs));
                }
            }
        }
        req.selfAskSubQueries = selfAskQueries;
        dbg.put("selfAsk.exec", selfAskExec);
        com.example.lms.search.RequestTrace.emit("rag.selfask", "decision",
                "requested=" + req.enableSelfAsk
                        + ";planner=" + (selfAskPlanner != null ? "present" : "missing")
                        + ";exec=" + selfAskExec);

        // 1) 통합 검색 후보 수집
        long stageStartedNs = System.nanoTime();
        List<Doc> pool = retrieveCandidates(req, dbg, false, trace, stageMs);
        recordStageMs(stageMs, "pool", stageStartedNs);
        if (trace != null && pool != null) {
            trace.pool = snapshotDocs(pool);
        }
        com.example.lms.search.RequestTrace.emit("rag.stages", "collect",
                "seed=" + sizeOf(trace == null ? null : trace.seed)
                        + ";web=" + sizeOf(trace == null ? null : trace.web)
                        + ";vector=" + sizeOf(trace == null ? null : trace.vector)
                        + ";kg=" + sizeOf(trace == null ? null : trace.kg)
                        + ";bm25=" + sizeOf(trace == null ? null : trace.bm25)
                        + ";pool=" + (pool == null ? 0 : pool.size()));

        // NOTE: Auto-Flush(재검색/공격적 확장)는 오케스트레이터가 자동으로 결정하지 않는다.
        // 필요하면 호출자가 req.aggressive/deepResearch/topK 등을 명시적으로 설정한다.
        // [FIX-D3] 빈 결과 시 상세 진단 로그 + emergency vector-only retrieval
        if (pool == null || pool.isEmpty()) {
            dbg.put("retrieval", "empty_initial");
            log.warn("[Orchestrator] Initial retrieval pool is empty. Web={}, Vector={}, KG={}, BM25={} queryHash={}",
                    req.useWeb, req.useVector, req.useKg, req.useBm25,
                    req.query == null ? "" : DigestUtils.sha256Hex(req.query));

            // 마지막 수단: vector-only 재시도 (fail-soft)
            // seedOnly 요청은 어떤 live retrieval도 허용하지 않으므로 emergency도 건너뛴다.
            boolean emergencyEligible = req.useVector && !req.seedOnly && vectorLeaf() != null
                    && (req.seedVector == null || req.seedVector.isEmpty());
            com.example.lms.search.RequestTrace.emit("rag.emergency", "decision",
                    "eligible=" + emergencyEligible + ";reason=empty_pool;useVector=" + req.useVector
                            + ";seedOnly=" + req.seedOnly + ";leaf=" + (vectorLeaf() != null));
            if (emergencyEligible) {
                // The emergency leg is still governed by the request deadline:
                // an exhausted or cancelled budget must not start a new call.
                com.abandonware.ai.addons.budget.TimeBudget requestBudget =
                        com.abandonware.ai.addons.budget.TimeBudgetContext.get();
                if (requestBudget != null && requestBudget.expired()) {
                    com.example.lms.search.DeadlineProbe.skip("rag.emergency", "request_budget_exhausted");
                    com.example.lms.search.RequestTrace.emit("rag.emergency", "skip",
                            "reason=request_budget_exhausted");
                    dbg.put("retrieval.emergency", "skipped:request_budget_exhausted");
                    emergencyEligible = false;
                }
            }
            if (emergencyEligible) {
                log.info("[Orchestrator] Attempting emergency vector-only retrieval");
                stageStartedNs = System.nanoTime();
                int emergencyK = effectiveVectorTopK(req);
                Query emergencyQuery = com.example.lms.service.rag.QueryUtils.buildQuery(
                        req.query, null, null, java.util.Map.of("vectorTopK", emergencyK));
                com.example.lms.search.RequestTrace.emit("rag.emergency", "exec",
                        "k=" + emergencyK + ";prior_attempts=1;max_calls=2");
                List<Doc> emergencyDocs = toDocsOrEmpty(vectorLeaf(), emergencyQuery, emergencyK, "VECTOR-EMERGENCY");
                com.example.lms.search.RequestTrace.emit("rag.emergency", "result",
                        "count=" + (emergencyDocs == null ? 0 : emergencyDocs.size()));
                recordStageMs(stageMs, "vector", stageStartedNs);
                if (emergencyDocs != null && !emergencyDocs.isEmpty()) {
                    pool = new ArrayList<>(emergencyDocs);
                    dbg.put("retrieval", "emergency_vector:" + pool.size());
                    if (trace != null) {
                        trace.pool = snapshotDocs(pool);
                    }
                }
            }
        }
        // 엔티티 노이즈 도메인 필터 적용 (fuseRrf 전에 수행)
        if (contextConsistencyFilter != null && pool != null && !pool.isEmpty()) {
            List<String> noiseList = req.noiseDomains;
            String expectedDomain = req.expectedDomain;
            if (noiseList != null && !noiseList.isEmpty()) {
                int beforeSize = pool.size();
                pool = contextConsistencyFilter.filter(pool, expectedDomain, noiseList);
                int afterSize = pool != null ? pool.size() : 0;
                dbg.put("consistency_filtered_count", beforeSize - afterSize);
                com.example.lms.search.RequestTrace.emit("rag.filter", "consistency",
                        "in=" + beforeSize + ";out=" + afterSize);
                log.info("[Orchestrator] ContextConsistencyFilter: {} -> {} docs (expectedDomainHash={})",
                        beforeSize, afterSize, safeHash(expectedDomain));
                if (trace != null && pool != null) {
                    trace.pool = snapshotDocs(pool);
                }
            }
        }

        // 2) Fuse via Weighted-RRF. The fusion window is the candidate set
        // (wider than topK so downstream rerankers can promote candidates
        // beyond the final cut); the public result cap is applied by
        // finalTopK after every rerank/policy stage.
        stageStartedNs = System.nanoTime();
        int candidateK = (int) Math.min((long) (pool == null ? 0 : pool.size()),
                Math.max((long) req.topK, req.topK * 3L));
        List<Doc> fused = fuseRrf(pool, candidateK, req);
        recordStageMs(stageMs, "fused", stageStartedNs);
        dbg.put("fuse.candidateK", candidateK);
        com.example.lms.search.RequestTrace.emit("rag.fuse", "window",
                "in=" + (pool == null ? 0 : pool.size()) + ";candidateK=" + candidateK
                        + ";out=" + (fused == null ? 0 : fused.size()));
        if (trace != null && fused != null) {
            trace.fused = snapshotDocs(fused);
        }
        dbg.put("stage.fuse", fused.size());
        emitRagPipelineEvent(
                "fusion",
                "fuse",
                "complete",
                "UnifiedRagOrchestrator",
                fused.isEmpty() ? "empty" : "ok",
                mapOf("queryHash", safeHash(req == null ? null : req.query),
                        "requestedTopK", req == null ? 0 : req.topK,
                        "planId", req == null ? "" : req.planId,
                        "mode", req == null ? "" : firstNonBlank(req.jamminiMode, req.memoryProfile)),
                mapOf("returnedCount", pool == null ? 0 : pool.size(),
                        "selectedCount", fused.size(),
                        "stageMs", stageMs == null ? 0L : stageMs.getOrDefault("fused", 0L)),
                fused.isEmpty() ? mapOf("reasonCode", "zero_result", "failureClass", "zero_result") : Map.of(),
                fused.isEmpty() ? mapOf("action", "fail_soft_fallback", "applied", true, "reasonCode", "zero_result") : Map.of());
        if (fused.isEmpty()) {
            dbg.put("retrieval", "empty");
            dbg.put("fallback", "model_knowledge");
        }

        // 3) Bi-Encoder prefilter
        if (req.enableBiEncoder && biEncoder != null) {
            stageStartedNs = System.nanoTime();
            int filterK;
            if ("NONE".equalsIgnoreCase(req.memoryProfile)
                    || "brave".equalsIgnoreCase(String.valueOf(req.jamminiMode))) {
                filterK = Math.max(20, req.topK * 3); // S2: recall 중시
            } else if (req.aggressive) {
                filterK = Math.max(15, req.topK * 2);
            } else {
                filterK = Math.max(10, req.topK);
            }
            int preBi = fused.size();
            fused = topK(fused, filterK);
            recordStageMs(stageMs, "biencoder", stageStartedNs);
            dbg.put("stage.biencoder", fused.size());
            com.example.lms.search.RequestTrace.emit("rag.rerank", "biencoder",
                    "in=" + preBi + ";out=" + fused.size() + ";filterK=" + filterK);
            emitRagPipelineEvent(
                    "rerank",
                    "biencoder",
                    "complete",
                    "UnifiedRagOrchestrator",
                    "ok",
                    mapOf("queryHash", safeHash(req == null ? null : req.query), "requestedTopK", filterK),
                    mapOf("selectedCount", fused.size(), "stageMs", stageMs == null ? 0L : stageMs.getOrDefault("biencoder", 0L)),
                    Map.of(),
                    Map.of());
            if (trace != null) {
                trace.biencoder = snapshotDocs(fused);
            }
        }

        // 3.5) DPP diversity rerank (between bi-encoder and cross-encoder)
        if (req.enableDiversity) {
            com.example.lms.service.rag.rerank.DppDiversityReranker dpp = this.dppDiversityReranker;
            if (dpp != null) {
                try {
                    stageStartedNs = System.nanoTime();
                    com.example.lms.service.rag.rerank.DppDiversityReranker.Config dppConfig =
                            new com.example.lms.service.rag.rerank.DppDiversityReranker.Config(
                                    req.diversityLambda,
                                    Math.max(10, req.topK));
                    int preDpp = fused == null ? 0 : fused.size();
                    fused = dpp.rerank(dppConfig, fused, req.query, Math.max(10, req.topK),
                            UnifiedRagOrchestrator::docText,
                            UnifiedRagOrchestrator::docRelevance,
                            UnifiedRagOrchestrator::stableDocumentKey);
                    TraceStore.put("rag.orchestrator.dpp.source", "spring_managed_reranker");
                    recordStageMs(stageMs, "dpp", stageStartedNs);
                    dbg.put("stage.dpp", fused.size());
                    com.example.lms.search.RequestTrace.emit("rag.rerank", "dpp",
                            "in=" + preDpp + ";out=" + fused.size());
                    emitRagPipelineEvent(
                            "rerank",
                            "dpp",
                            "complete",
                            "UnifiedRagOrchestrator",
                            "ok",
                            mapOf("queryHash", safeHash(req == null ? null : req.query),
                                    "requestedTopK", Math.max(10, req.topK),
                                    "mode", "diversity"),
                            mapOf("selectedCount", fused.size(), "stageMs", stageMs == null ? 0L : stageMs.getOrDefault("dpp", 0L)),
                            Map.of(),
                            Map.of());
                } catch (Throwable t) {
                    TraceStore.put("rag.orchestrator.suppressed.dpp", true); dbg.put("stage.dpp", "error: " + com.example.lms.trace.SafeRedactor.traceLabelOrFallback(t.getMessage(), ""));
                    emitRagPipelineEvent(
                            "rerank",
                            "dpp",
                            "complete",
                            "UnifiedRagOrchestrator",
                            "failed",
                            mapOf("queryHash", safeHash(req == null ? null : req.query),
                                    "requestedTopK", req == null ? 0 : req.topK,
                                    "mode", "diversity"),
                            mapOf("selectedCount", fused == null ? 0 : fused.size()),
                        mapOf("reasonCode", "silent_failure", "failureClass", "silent_failure",
                                "exceptionType", "dpp_rerank_failed"),
                            mapOf("action", "fail_soft_fallback", "applied", true, "reasonCode", "silent_failure"));
                }
            } else {
                dbg.put("stage.dpp", "disabled:missing_dpp_reranker");
                com.example.lms.search.RequestTrace.emit("rag.rerank", "dpp",
                        "skip=missing_dpp_reranker");
                emitRagPipelineEvent(
                        "rerank",
                        "dpp",
                        "complete",
                        "UnifiedRagOrchestrator",
                        "skipped",
                        mapOf("queryHash", safeHash(req == null ? null : req.query), "mode", "diversity"),
                        mapOf("selectedCount", fused == null ? 0 : fused.size()),
                        mapOf("reasonCode", "missing_dependency", "failureClass", "missing_dependency"),
                        mapOf("action", "fail_soft_fallback", "applied", true, "reasonCode", "missing_dependency"));
            }

            if (trace != null) {
                trace.dpp = snapshotDocs(fused);
            }
        }
        // 4) ONNX Cross-Encoder final rerank
        TraceStore.put("rerank.onnx.requested", req.enableOnnx);
        TraceStore.put("rerank.onnx.skipReason", null);
        TraceStore.put("rerank.onnx.orchestrator.failureClass", null);
        if (req.enableOnnx) {
            stageStartedNs = System.nanoTime();
            List<Doc> beforeOnnx = fused == null ? List.of() : fused;
            int onnxInputCount = beforeOnnx.size();
            boolean onnxExecuted = false;
            String onnxStatus = "ok";
            Map<String, Object> onnxFailure = Map.of();
            Map<String, Object> onnxControl = Map.of();

            if (onnxReranker == null) {
                fused = topK(beforeOnnx, req.topK);
                onnxStatus = "fallback";
                TraceStore.put("rerank.onnx.skipReason", "missing_dependency");
                dbg.put("stage.onnx", "skipped:missing_dependency");
                onnxFailure = mapOf("reasonCode", "missing_dependency", "failureClass", "missing_dependency");
                onnxControl = mapOf("action", "fail_soft_fallback", "applied", true,
                        "reasonCode", "missing_dependency");
            } else {
                try {
                    List<Content> candidates = toOnnxContents(beforeOnnx);
                    onnxInputCount = candidates.size();
                    if (candidates.isEmpty()) {
                        fused = topK(beforeOnnx, req.topK);
                        onnxStatus = "fallback";
                        TraceStore.put("rerank.onnx.skipReason", "empty_candidates");
                        dbg.put("stage.onnx", "skipped:empty_candidates");
                        onnxFailure = mapOf("reasonCode", "empty_candidates", "failureClass", "empty_candidates");
                        onnxControl = mapOf("action", "fail_soft_fallback", "applied", true,
                                "reasonCode", "empty_candidates");
                    } else {
                        List<Content> reranked = onnxReranker.rerank(req.query, candidates, req.topK);
                        fused = docsInOnnxOrder(reranked, beforeOnnx, req.topK);
                        onnxExecuted = true;
                    }
                } catch (Throwable t) {
                    fused = topK(beforeOnnx, req.topK);
                    onnxStatus = "failed";
                    String failureClass = (t instanceof CancellationException || t instanceof InterruptedException || t.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("cancel") || t.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("interrupt") || String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT).contains("cancel") || String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT).contains("interrupt")) ? "cancelled" : "onnx_rerank_failed";
                    TraceStore.put("rerank.onnx.skipReason", failureClass);
                    TraceStore.put("rerank.onnx.orchestrator.failureClass", failureClass);
                    dbg.put("stage.onnx.failureClass", failureClass);
                    dbg.put("stage.onnx", "error: " + com.example.lms.trace.SafeRedactor.traceLabelOrFallback(t.getMessage(), ""));
                    onnxFailure = mapOf("reasonCode", failureClass, "failureClass", failureClass,
                            "exceptionType", failureClass);
                    onnxControl = mapOf("action", "fail_soft_fallback", "applied", true,
                            "reasonCode", failureClass);
                }
            }

            TraceStore.put("rerank.onnx.executed", onnxExecuted);
            TraceStore.put("rerank.onnx.input.count", onnxInputCount);
            TraceStore.put("rerank.onnx.output.count", fused.size());
            TraceStore.put("rerank.onnx.orchestrator.executed", onnxExecuted);
            TraceStore.put("rerank.onnx.orchestrator.candidateCount", onnxInputCount);
            TraceStore.put("rerank.onnx.orchestrator.selectedCount", fused.size());
            recordStageMs(stageMs, "onnx", stageStartedNs);
            dbg.putIfAbsent("stage.onnx", fused.size());
            com.example.lms.search.RequestTrace.emit("rag.rerank", "onnx",
                    "in=" + onnxInputCount + ";out=" + fused.size()
                            + ";status=" + onnxStatus + ";executed=" + onnxExecuted);
            emitRagPipelineEvent(
                    "rerank",
                    "onnx",
                    "complete",
                    "UnifiedRagOrchestrator",
                    onnxStatus,
                    mapOf("queryHash", safeHash(req == null ? null : req.query), "requestedTopK", req.topK),
                    mapOf("selectedCount", fused.size(), "stageMs", stageMs == null ? 0L : stageMs.getOrDefault("onnx", 0L)),
                    onnxFailure,
                    onnxControl);
            if (trace != null) {
                trace.onnx = snapshotDocs(fused);
            }
        } else {
            TraceStore.put("rerank.onnx.executed", false);
            TraceStore.put("rerank.onnx.input.count", 0);
            TraceStore.put("rerank.onnx.output.count", 0);
            TraceStore.put("rerank.onnx.skipReason", "disabled");
            TraceStore.put("rerank.onnx.orchestrator.executed", false);
            TraceStore.put("rerank.onnx.orchestrator.candidateCount", 0);
            TraceStore.put("rerank.onnx.orchestrator.selectedCount", 0);
            com.example.lms.search.RequestTrace.emit("rag.rerank", "onnx", "skip=disabled");
        }

        // 5) Explicit source restrictions apply independently of recall/memory mode.
        if (req.whitelistOnly && domainWhitelist != null) {
            int before = fused.size();
            fused = fused.stream()
                    .filter(this::isWhitelistedDoc)
                    .collect(Collectors.toList());
            dbg.put("stage.whitelist.filtered", Math.max(0, before - fused.size()));
            dbg.put("stage.whitelist", fused.size());
            com.example.lms.search.RequestTrace.emit("rag.filter", "whitelist",
                    "in=" + before + ";out=" + fused.size());
        } else if (req.whitelistOnly) {
            // Fail-closed: 엄격 출처 제한이 요청됐지만 정책 빈이 없으면
            // 미검증 결과를 통과시키지 않고 비운다.
            int before = fused.size();
            fused = new ArrayList<>();
            dbg.put("stage.whitelist.filtered", before);
            dbg.put("stage.whitelist", "policy_unavailable");
            TraceStore.put("rag.whitelist.policy_unavailable", true);
            com.example.lms.search.RequestTrace.emit("rag.filter", "whitelist",
                    "in=" + before + ";out=0;reason=policy_unavailable");
            log.warn("[Orchestrator] whitelistOnly requested but DomainWhitelist bean unavailable; failing closed");
        }

        // Enforce the public result cap after every optional augmentation/rerank.
        int preFinal = fused == null ? 0 : fused.size();
        fused = finalTopK(fused, req.topK);
        com.example.lms.search.RequestTrace.emit("rag.final", "cut",
                "in=" + preFinal + ";topK=" + req.topK + ";out=" + fused.size());

        // Finalize ranks
        stageStartedNs = System.nanoTime();
        for (int i = 0; i < fused.size(); i++) {
            fused.get(i).rank = i + 1;
        }
        recordStageMs(stageMs, "final", stageStartedNs);
        resp.results = fused;
        if (com.example.lms.search.RequestTrace.docsEnabled()) {
            for (int i = 0; i < fused.size(); i++) {
                Doc d = fused.get(i);
                com.example.lms.search.RequestTrace.emitDoc("rag.final.doc",
                        "rank=" + (i + 1)
                                + ";key=" + com.example.lms.trace.SafeRedactor.hash12(stableDocumentKey(d))
                                + ";src=" + (d.source == null ? "?" : d.source));
            }
        }
        attachRagEvalSnapshot(req, resp, trace, stageMs, elapsedMs(runStartedNs), analysis, planSpec);
        return resp;
    }

    private void attachRagEvalSnapshot(QueryRequest req,
                                       QueryResponse resp,
                                       QueryTrace trace,
                                       Map<String, Long> stageMs,
                                       long totalMs,
                                       QueryAnalysisResult analysis,
                                       com.example.lms.plan.PlanExecutionSpec planSpec) {
        if (resp == null) {
            return;
        }
        Map<String, Object> debug = resp.debug == null ? new LinkedHashMap<>() : resp.debug;
        resp.debug = debug;
        List<String> deadlineTimeline = com.example.lms.search.DeadlineProbe.timeline();
        if (!deadlineTimeline.isEmpty()) {
            debug.put("deadline.timeline", List.copyOf(deadlineTimeline));
        }
        // Honest per-request feature status: fingerprint counts come from the
        // existing vector.fp.* TraceStore keys (absent key = not observed on
        // this path, never filled with 0); SHADOW is an offline DLQ, not a
        // request-path comparator; the SelfAsk exec state is whatever the
        // planning stage recorded on this request (executed/skipped/failed).
        Object fpDropped = TraceStore.get("vector.fp.dropped");
        Object fpBypassed = TraceStore.get("vector.fp.bypassed");
        Object fpReason = TraceStore.get("vector.fp.blockedReason");
        com.example.lms.search.RequestTrace.emit("rag.features", "status",
                "selfask=" + (req != null && req.enableSelfAsk
                        ? "requested:" + String.valueOf(debug.getOrDefault("selfAsk.exec", "unresolved"))
                        : "not_requested")
                        + ";fingerprint=" + (fpDropped == null && fpBypassed == null
                                ? "not_observed_on_path"
                                : "observed:dropped=" + fpDropped + ",bypassed=" + fpBypassed
                                        + ",reason=" + fpReason)
                        + ";shadow=not_wired:offline_dlq");
        List<String> requestEvents = com.example.lms.search.RequestTrace.events();
        if (!requestEvents.isEmpty()) {
            debug.put("request.events", List.copyOf(requestEvents));
        }
        long droppedEvents = TraceStore.getLong(com.example.lms.search.RequestTrace.DROPPED_KEY);
        if (droppedEvents > 0) {
            debug.put("request.events.dropped", droppedEvents);
            debug.put("request.events.incomplete", true);
        }
        long droppedDocEvents = TraceStore.getLong(com.example.lms.search.RequestTrace.DOC_DROPPED_KEY);
        if (droppedDocEvents > 0) {
            debug.put("request.events.docs.dropped", droppedDocEvents);
            debug.put("request.events.incomplete", true);
        }

        List<Doc> results = resp.results == null ? List.of() : resp.results;
        Map<String, Integer> stageCounts = stageCounts(trace, results);
        Map<String, Integer> sourceDiversity = sourceDiversity(results);
        Map<String, Map<String, Integer>> stageSourceDiversity = stageSourceDiversity(trace, results);
        List<String> providerDisabledSignals = providerDisabledSignals(req, debug);
        List<String> zeroResultSignals = zeroResultSignals(req, trace, results, stageCounts);
        List<String> afterFilterStarvationSignals = afterFilterStarvationSignals();

        int resultCount = results.size();
        int distinctSourceCount = sourceDiversity.size();
        boolean emptyResult = resultCount == 0;
        int evidenceCount = evidenceCount(results);
        double fallbackCost = debug.containsKey("fallback") ? 1.0d : 0.0d;
        NormalizedRagMetrics normalized = NormalizedRagMetrics.from(
                1,
                emptyResult ? 0 : 1,
                resultCount,
                evidenceCount,
                sourceDiversity,
                resultCount,
                req == null ? 1 : Math.max(1, req.topK),
                Math.max(0L, totalMs),
                fallbackCost);
        normalized.putTrace();
        List<Map<String, Object>> thresholdBreaks = new ArrayList<>(
                NormalizedRagMetrics.thresholdBreaks(normalized, resultCount));
        addExternalSignalBreaks(thresholdBreaks, providerDisabledSignals, zeroResultSignals, afterFilterStarvationSignals);
        addStageDropBreaks(thresholdBreaks, stageCounts);
        Map<String, Object> kgAxis = kgAxis(req, results, stageCounts, sourceDiversity, stageMs, totalMs);
        Map<String, Object> logicDag = logicDagTrace();
        addKgAxisBreaks(thresholdBreaks, kgAxis);
        addOperationalBreaks(thresholdBreaks);
        addThresholdScope(thresholdBreaks, "request", 1, "single-request");
        Map<String, Object> bottleneck = bottleneck(stageMs, totalMs, stageCounts, thresholdBreaks);
        List<String> goodSignals = goodSignals(normalized, thresholdBreaks);
        List<String> contaminationSignals = contaminationSignals(normalized, providerDisabledSignals);
        Map<String, Object> normalizedMap = normalizedMap(normalized);
        Map<String, Object> queryFingerprint = queryFingerprint(req, analysis);
        Map<String, Double> stageDropMap = stageDrop(stageCounts);
        String reasonCode = dominantReason(thresholdBreaks, bottleneck, emptyResult);
        Map<String, Object> scorecard = ragEvalScorecard(
                resultCount,
                stageCounts,
                stageDropMap,
                sourceDiversity,
                normalizedMap,
                kgAxis,
                thresholdBreaks,
                bottleneck,
                reasonCode);

        debug.put("rag.eval.resultCount", resultCount);
        debug.put("rag.eval.distinctSourceCount", distinctSourceCount);
        debug.put("rag.eval.emptyResult", emptyResult);
        debug.put("rag.eval.stageCounts", stageCounts);
        debug.put("rag.eval.stageMs", stageMs == null ? Map.of() : new LinkedHashMap<>(stageMs));
        debug.put("rag.eval.stageDrop", stageDropMap);
        debug.put("rag.eval.sourceDiversity", sourceDiversity);
        debug.put("rag.eval.stageSourceDiversity", stageSourceDiversity);
        debug.put("rag.eval.kgAxis", kgAxis);
        debug.put("rag.eval.logicDag", logicDag);
        debug.put("rag.eval.providerDisabledSignals", providerDisabledSignals);
        debug.put("rag.eval.zeroResultSignals", zeroResultSignals);
        debug.put("rag.eval.afterFilterStarvationSignals", afterFilterStarvationSignals);
        debug.put("rag.eval.queryFingerprint", queryFingerprint);
        debug.put("rag.eval.normalized", normalizedMap);
        debug.put("rag.eval.thresholdBreaks", thresholdBreaks);
        debug.put("rag.eval.goodSignals", goodSignals);
        debug.put("rag.eval.contaminationSignals", contaminationSignals);
        debug.put("rag.eval.bottleneck", bottleneck);
        debug.put("rag.eval.scorecard", scorecard);

        TraceStore.put("rag.eval.queryFingerprint", queryFingerprint);
        TraceStore.put("rag.eval.resultCount", resultCount);
        TraceStore.put("rag.eval.sourceDiversity", sourceDiversity);
        TraceStore.put("rag.eval.stageSourceDiversity", stageSourceDiversity);
        TraceStore.put("rag.eval.stageDrop", stageDropMap);
        TraceStore.put("rag.eval.kgAxis", kgAxis);
        TraceStore.put("rag.eval.logicDag", logicDag);
        traceKgAxisAliases(kgAxis);
        TraceStore.put("rag.eval.providerDisabledSignals", providerDisabledSignals);
        TraceStore.put("rag.eval.zeroResultSignals", zeroResultSignals);
        TraceStore.put("rag.eval.afterFilterStarvationSignals", afterFilterStarvationSignals);
        TraceStore.put("rag.eval.normalized", normalizedMap);
        TraceStore.put("rag.eval.thresholdBreaks", thresholdBreaks);
        TraceStore.put("rag.eval.goodSignals", goodSignals);
        TraceStore.put("rag.eval.contaminationSignals", contaminationSignals);
        TraceStore.put("rag.eval.bottleneck", bottleneck);
        TraceStore.put("rag.eval.scorecard", scorecard);
        TraceStore.put("rag.metrics.kgRetention", round4(toDouble(kgAxis.get("finalRetention"), 0.0d)));

        writeOfflineTextureSnapshot(queryFingerprint);

        Map<String, Object> failure = reasonCode.isBlank()
                ? Map.of()
                : mapOf("reasonCode", reasonCode, "failureClass", reasonCode);
        emitRagPipelineEvent(
                "guard",
                "rag_eval",
                "complete",
                "UnifiedRagOrchestrator.attachRagEvalSnapshot",
                reasonCode.isBlank() ? "ok" : "warn",
                mapOf("queryHash", queryFingerprint == null ? "" : queryFingerprint.get("queryHash"),
                        "requestedTopK", req == null ? 0 : req.topK,
                        "planId", req == null ? "" : req.planId,
                        "mode", req == null ? "" : firstNonBlank(req.jamminiMode, req.memoryProfile)),
                mapOf("selectedCount", resultCount,
                        "returnedCount", stageCounts.getOrDefault("pool", 0),
                        "sourceDiversity", sourceDiversity,
                        "stageMs", totalMs),
                failure,
                reasonCode.isBlank() ? Map.of() : mapOf("reasonCode", reasonCode, "applied", true));

        boolean degradedEval = emptyResult
                || !providerDisabledSignals.isEmpty()
                || !zeroResultSignals.isEmpty()
                || !afterFilterStarvationSignals.isEmpty()
                || !thresholdBreaks.isEmpty();
        if (debugEventStore != null && (ragEvalDebugEventsEnabled || degradedEval)) {
            emitRagEvalEvent(req, resp, stageCounts, stageMs, sourceDiversity, stageSourceDiversity,
                    kgAxis, logicDag, providerDisabledSignals, zeroResultSignals, afterFilterStarvationSignals,
                    queryFingerprint, normalizedMap, thresholdBreaks, bottleneck,
                    scorecard, goodSignals, contaminationSignals, emptyResult);
        }

        // plan.pipeline stage ledger: each declared stage is mapped to real
        // runtime evidence; late metric-driven activation is reported honestly
        // (expansion stages cannot retroactively re-run for this request).
        if (planSpec != null && !planSpec.isEmpty()) {
            Map<String, Object> postScope = planRequestScope(req);
            postScope.putAll(planObservedMetrics(debug));
            com.example.lms.plan.PlanExecutionSpec.WhenVerdict postWhen = planSpec.evaluateWhen(postScope);
            debug.put("plan.when.post", postWhen.state().name().toLowerCase(Locale.ROOT));
            Object preWhen = debug.get("plan.when");
            if ("unknown".equals(preWhen) && postWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.TRUE) {
                debug.put("plan.when.lateActivation", true);
            }
            boolean expansionEligible = !"false".equals(String.valueOf(preWhen));
            com.example.lms.plan.PlanExecutionSpec.StageFlags stageFlags =
                    new com.example.lms.plan.PlanExecutionSpec.StageFlags(
                            req != null && req.enableSelfAsk,
                            req == null || req.enableBiEncoder,
                            req == null || req.enableOnnx,
                            req == null || req.enableDiversity,
                            expansionEligible);
            List<Map<String, Object>> ledger = planSpec.stageLedger(debug, stageFlags).stream()
                    .map(com.example.lms.plan.PlanExecutionSpec.StageEntry::debugView)
                    .toList();
            debug.put("plan.stageLedger", ledger);
            TraceStore.put("plan.stageLedger", ledger);
        }
    }

    private void writeOfflineTextureSnapshot(Map<String, Object> queryFingerprint) {
        if (offlineTextureSnapshotWriter == null) {
            return;
        }
        try {
            Object queryHash = queryFingerprint == null ? "" : queryFingerprint.get("queryHash");
            offlineTextureSnapshotWriter.writeFrom(String.valueOf(queryHash), null, offlineTextureTraceProjection());
        } catch (Throwable e) {
            TraceStore.put("rag.offlineTexture.write.status", "fail_soft:offline_texture_write_failed");
        }
    }

    private static Map<String, Object> offlineTextureTraceProjection() {
        Map<String, Object> trace = TraceStore.getAll();
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : trace.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            if (key.equals("rag.eval.kgAxis")
                    || key.equals("rag.eval.thresholdBreaks")
                    || key.equals("rag.eval.queryFingerprint")
                    || key.equals("rag.eval.sourceDiversity")
                    || key.equals("langgraph.node.quality_gate.reason")
                    || key.equals("langgraph.qualityGate.failureReason")
                    || key.startsWith("rag.fusion.sizes.")
                    || key.startsWith("rag.fusion.final.")
                    || key.startsWith("rag.budget.")
                    || key.startsWith("rag.metrics.")) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    private void emitRagEvalEvent(QueryRequest req,
                                  QueryResponse resp,
                                  Map<String, Integer> stageCounts,
                                  Map<String, Long> stageMs,
                                  Map<String, Integer> sourceDiversity,
                                  Map<String, Map<String, Integer>> stageSourceDiversity,
                                  Map<String, Object> kgAxis,
                                  Map<String, Object> logicDag,
                                  List<String> providerDisabledSignals,
                                  List<String> zeroResultSignals,
                                  List<String> afterFilterStarvationSignals,
                                  Map<String, Object> queryFingerprint,
                                  Map<String, Object> normalized,
                                  List<Map<String, Object>> thresholdBreaks,
                                  Map<String, Object> bottleneck,
                                  Map<String, Object> scorecard,
                                  List<String> goodSignals,
                                  List<String> contaminationSignals,
                                  boolean emptyResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestIdHash", com.example.lms.trace.SafeRedactor.hashValue(resp.requestId));
        data.put("planApplied", resp.planApplied);
        data.put("queryHash", req == null || req.query == null ? "" : DigestUtils.sha256Hex(req.query));
        data.put("topK", req == null ? null : req.topK);
        data.put("webTopK", req == null ? null : effectiveWebTopK(req));
        data.put("vectorTopK", req == null ? null : effectiveVectorTopK(req));
        data.put("kgTopK", req == null ? null : effectiveKgTopK(req));
        data.put("queryFingerprint", queryFingerprint == null ? Map.of() : queryFingerprint);
        data.put("resultCount", resp.results == null ? 0 : resp.results.size());
        data.put("distinctSourceCount", sourceDiversity == null ? 0 : sourceDiversity.size());
        data.put("emptyResult", emptyResult);
        data.put("stageCounts", stageCounts);
        data.put("stageMs", stageMs == null ? Map.of() : new LinkedHashMap<>(stageMs));
        data.put("sourceDiversity", sourceDiversity);
        data.put("stageSourceDiversity", stageSourceDiversity);
        data.put("kgAxis", kgAxis);
        data.put("logicDag", logicDag == null ? Map.of() : logicDag);
        data.put("providerDisabledSignals", providerDisabledSignals);
        data.put("zeroResultSignals", zeroResultSignals);
        data.put("afterFilterStarvationSignals", afterFilterStarvationSignals);
        data.put("normalized", normalized);
        data.put("thresholdBreaks", thresholdBreaks);
        data.put("goodSignals", goodSignals);
        data.put("contaminationSignals", contaminationSignals);
        data.put("bottleneck", bottleneck);
        data.put("scorecard", scorecard == null ? Map.of() : scorecard);

        DebugEventLevel level = emptyResult
                || !providerDisabledSignals.isEmpty()
                || !zeroResultSignals.isEmpty()
                || !afterFilterStarvationSignals.isEmpty()
                || !thresholdBreaks.isEmpty()
                ? DebugEventLevel.WARN
                : DebugEventLevel.INFO;
        String fingerprint = emptyResult ? "rag.eval.empty" : "rag.eval.ok";
        debugEventStore.emit(
                DebugProbeType.ORCHESTRATION,
                level,
                fingerprint,
                emptyResult ? "[AWX2AF2][rag][starvation] RAG eval snapshot" : "[AWX2AF2][rag] RAG eval snapshot",
                "UnifiedRagOrchestrator.ragEval",
                data,
                null);
    }

    private static Map<String, Object> ragEvalScorecard(int resultCount,
                                                        Map<String, Integer> stageCounts,
                                                        Map<String, Double> stageDrop,
                                                        Map<String, Integer> sourceDiversity,
                                                        Map<String, Object> normalized,
                                                        Map<String, Object> kgAxis,
                                                        List<Map<String, Object>> thresholdBreaks,
                                                        Map<String, Object> bottleneck,
                                                        String reasonCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        String normalizedReason = reasonCode == null ? "" : reasonCode.trim();
        out.put("schemaVersion", NormalizedRagMetrics.SCHEMA_VERSION);
        out.put("status", normalizedReason.isBlank() ? "ok" : "degraded");
        out.put("reasonCode", normalizedReason);
        out.put("resultCount", Math.max(0, resultCount));
        out.put("stageCounts", stageCounts == null ? Map.of() : new LinkedHashMap<>(stageCounts));
        out.put("stageDrop", stageDrop == null ? Map.of() : new LinkedHashMap<>(stageDrop));
        out.put("sourceDiversity", sourceDiversity == null ? Map.of() : new LinkedHashMap<>(sourceDiversity));
        out.put("normalized", normalized == null ? Map.of() : new LinkedHashMap<>(normalized));
        out.put("kgAxis", kgAxis == null ? Map.of() : new LinkedHashMap<>(kgAxis));
        out.put("thresholdLabels", thresholdLabels(thresholdBreaks));
        out.put("bottleneck", bottleneck == null ? Map.of() : new LinkedHashMap<>(bottleneck));
        return out;
    }

    private static List<String> thresholdLabels(List<Map<String, Object>> thresholdBreaks) {
        if (thresholdBreaks == null || thresholdBreaks.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : thresholdBreaks) {
            if (row == null) {
                continue;
            }
            Object raw = row.get("label");
            if (raw == null) {
                raw = row.get("reasonCode");
            }
            String label = raw == null ? "" : String.valueOf(raw).trim();
            if (!label.isBlank()) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }

    private static Map<String, Object> normalizedMap(NormalizedRagMetrics metrics) {
        if (metrics == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", NormalizedRagMetrics.SCHEMA_VERSION);
        out.put("retrievalHitRate", metrics.retrievalHitRate());
        out.put("evidenceCoverage", metrics.evidenceCoverage());
        out.put("sourceDiversity", metrics.sourceDiversity());
        out.put("resultDepth", metrics.resultDepth());
        out.put("latencyCost", metrics.latencyCost());
        out.put("latencyQuality", metrics.latencyQuality());
        out.put("fallbackCost", metrics.fallbackCost());
        out.put("fallbackStability", metrics.fallbackStability());
        out.put("balancedScore", metrics.balancedScore());
        return out;
    }

    private static void addExternalSignalBreaks(List<Map<String, Object>> thresholdBreaks,
                                                List<String> providerDisabledSignals,
                                                List<String> zeroResultSignals,
                                                List<String> afterFilterStarvationSignals) {
        if (thresholdBreaks == null) {
            return;
        }
        if (providerDisabledSignals != null && !providerDisabledSignals.isEmpty()) {
            thresholdBreaks.add(NormalizedRagMetrics.thresholdBreak(
                    "provider_disabled", "providerDisabledSignals", providerDisabledSignals.size(),
                    0.0d, "<=", 1.0d, "provider"));
        }
        if (zeroResultSignals != null && !zeroResultSignals.isEmpty()) {
            thresholdBreaks.add(NormalizedRagMetrics.thresholdBreak(
                    "zero_result", "zeroResultSignals", zeroResultSignals.size(),
                    0.0d, "<=", 1.0d, "retrieval"));
        }
        if (afterFilterStarvationSignals != null && !afterFilterStarvationSignals.isEmpty()) {
            thresholdBreaks.add(NormalizedRagMetrics.thresholdBreak(
                    "after_filter_starvation", "afterFilterStarvationSignals", afterFilterStarvationSignals.size(),
                    0.0d, "<=", 1.0d, "filter"));
        }
    }

    private static void addStageDropBreaks(List<Map<String, Object>> thresholdBreaks,
                                           Map<String, Integer> stageCounts) {
        if (thresholdBreaks == null || stageCounts == null || stageCounts.isEmpty()) {
            return;
        }
        List<String> order = List.of("pool", "fused", "biencoder", "dpp", "onnx", "final");
        for (int i = 1; i < order.size(); i++) {
            String fromStage = order.get(i - 1);
            String toStage = order.get(i);
            int before = Math.max(0, stageCounts.getOrDefault(fromStage, 0));
            int after = Math.max(0, stageCounts.getOrDefault(toStage, 0));
            if (before < 3) {
                continue;
            }
            double dropRatio = Math.max(0.0d, 1.0d - (after / (double) before));
            if (dropRatio < 0.50d) {
                continue;
            }
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "stage_drop_high",
                    "stageDrop",
                    dropRatio,
                    0.50d,
                    "<=",
                    dropRatio - 0.50d,
                    toStage);
            row.put("fromStage", fromStage);
            row.put("toStage", toStage);
            row.put("beforeCount", before);
            row.put("afterCount", after);
            thresholdBreaks.add(row);
        }
    }

    private static void addKgAxisBreaks(List<Map<String, Object>> thresholdBreaks,
                                        Map<String, Object> kgAxis) {
        if (thresholdBreaks == null || kgAxis == null || kgAxis.isEmpty()) {
            return;
        }
        if (!Boolean.TRUE.equals(kgAxis.get("requested"))) {
            return;
        }
        boolean retrieverPresent = Boolean.TRUE.equals(kgAxis.get("retrieverPresent"));
        int retrievedCount = (int) toDouble(kgAxis.get("retrievedCount"), 0.0d);
        double finalRetention = toDouble(kgAxis.get("finalRetention"), 0.0d);
        String status = safeDiagString(kgAxis.get("status"));
        addKgNeo4jBreak(thresholdBreaks, kgAxis, status);
        if (!retrieverPresent) {
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "kg_missing_retriever", "kgAxis.retrieverPresent", 0.0d,
                    1.0d, ">=", 1.0d, "kg");
            row.put("status", status);
            thresholdBreaks.add(row);
            return;
        }
        if (retrievedCount <= 0) {
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "kg_starvation", "kgAxis.retrievedCount", retrievedCount,
                    1.0d, ">=", 1.0d, "kg");
            row.put("status", status);
            row.put("emptyReason", safeDiagString(kgAxis.get("emptyReason")));
            thresholdBreaks.add(row);
            return;
        }
        if (finalRetention <= 0.0d) {
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "kg_final_drop", "kgAxis.finalRetention", finalRetention,
                    0.01d, ">", 0.01d, "kg");
            row.put("retrievedCount", retrievedCount);
            row.put("finalCount", (int) toDouble(kgAxis.get("finalCount"), 0.0d));
            thresholdBreaks.add(row);
        }
    }

    private static void addKgNeo4jBreak(List<Map<String, Object>> thresholdBreaks,
                                        Map<String, Object> kgAxis,
                                        String kgStatus) {
        String neo4jStatus = safeDiagString(kgAxis.get("neo4jStatus"));
        String neo4jDisabledReason = safeDiagString(kgAxis.get("neo4jDisabledReason"));
        String neo4jFailureClass = safeDiagString(kgAxis.get("neo4jFailureClass"));
        if (!hasKgNeo4jDegradationSignal(kgAxis, neo4jStatus, neo4jDisabledReason, neo4jFailureClass)) {
            return;
        }
        String metric = !neo4jStatus.isBlank() ? "kgAxis.neo4jStatus" : "kgAxis.neo4jFailureClass";
        Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                "kg_neo4j_degraded", metric, 0.0d,
                1.0d, ">=", 1.0d, "kg");
        row.put("category", "kg");
        row.put("status", safeDiagString(kgStatus));
        row.put("neo4jStatus", neo4jStatus);
        row.put("neo4jDisabledReason", neo4jDisabledReason);
        row.put("neo4jFailureClass", neo4jFailureClass);
        thresholdBreaks.add(row);
    }

    private static boolean hasKgNeo4jDegradationSignal(Map<String, Object> kgAxis,
                                                       String neo4jStatus,
                                                       String neo4jDisabledReason,
                                                       String neo4jFailureClass) {
        Object rawSignals = kgAxis.get("signals");
        if (rawSignals instanceof Iterable<?> signals) {
            for (Object signal : signals) {
                String label = normalizedSignalLabel(String.valueOf(signal));
                if ("kg_neo4j_failed".equals(label)
                        || "kg_neo4j_disabled".equals(label)
                        || "kg_neo4j_degraded".equals(label)) {
                    return true;
                }
            }
        }
        LinkedHashSet<String> derivedSignals = new LinkedHashSet<>();
        addNeo4jSignals(derivedSignals, neo4jStatus, neo4jDisabledReason, neo4jFailureClass);
        return derivedSignals.contains("kg_neo4j_failed")
                || derivedSignals.contains("kg_neo4j_disabled")
                || derivedSignals.contains("kg_neo4j_degraded");
    }

    private static void addOperationalBreaks(List<Map<String, Object>> thresholdBreaks) {
        if (thresholdBreaks == null) {
            return;
        }
        double anchorDrift = toDouble(TraceStore.get("rag.anchor.driftScore"), 0.0d);
        if (anchorDrift >= 0.65d) {
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "anchor_drift_high", "rag.anchor.driftScore", anchorDrift,
                    0.65d, "<", anchorDrift - 0.65d, "anchor");
            row.put("status", safeDiagString(TraceStore.get("rag.anchor.reason")));
            thresholdBreaks.add(row);
        }

        double burstReduction = toDouble(TraceStore.get("rag.metrics.burstReductionRate"), 0.0d);
        Object budgetReason = TraceStore.get("rag.budget.reason");
        boolean budgetClamped = burstReduction > 0.0d
                || (budgetReason != null && String.valueOf(budgetReason).contains("anchor_drift_high"));
        if (budgetClamped) {
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "budget_overexpanded", "rag.metrics.burstReductionRate", burstReduction,
                    0.0d, "=", burstReduction, "budget");
            row.put("status", safeDiagString(budgetReason));
            thresholdBreaks.add(row);
        }

        boolean stale = Boolean.TRUE.equals(TraceStore.get("rag.offlineTexture.stale"));
        boolean onlineNeeded = Boolean.TRUE.equals(TraceStore.get("rag.offlineTexture.onlineSearchNeeded"));
        if (stale && onlineNeeded) {
            Map<String, Object> row = NormalizedRagMetrics.thresholdBreak(
                    "offline_texture_stale", "rag.offlineTexture.stale", 1.0d,
                    0.0d, "=", 1.0d, "offline-texture");
            row.put("status", safeDiagString(TraceStore.get("rag.offlineTexture.reason")));
            thresholdBreaks.add(row);
        }
    }

    private static void traceKgAxisAliases(Map<String, Object> kgAxis) {
        if (kgAxis == null || kgAxis.isEmpty()) {
            return;
        }
        for (String key : List.of(
                "schemaVersion",
                "kgPoolCount",
                "kgFinalCount",
                "kgScoreMean",
                "kgScoreP95",
                "kgFinalRetention",
                "graphScore",
                "graphOpportunity",
                "neo4jStatus",
                "neo4jDisabledReason",
                "neo4jFailureClass",
                "sparseNodeStatus",
                "sparseNodeDisabledReason",
                "sparseNodeFailureClass",
                "sparseNodePortMappingCount",
                "sparseNodePortMappingHashes",
                "sparseNodeTransitivePathCount",
                "sparseNodeQueryAnchorMapApplied",
                "sparseNodeQueryAnchorMapSeedCount",
                "sparseNodeQueryAnchorMapSeedHashes",
                "sparseNodeQueryAnchorMapReason",
                "relationThumbnailStatus",
                "relationThumbnailCandidateCount",
                "relationThumbnailSelectedCount",
                "relationThumbnailDroppedCount",
                "relationThumbnailSelectionRate",
                "relationThumbnailDropRate",
                "relationThumbnailSelectionReason",
                "relationThumbnailAnchorSeedUsed",
                "relationThumbnailTopHash",
                "relationThumbnailContextLayers",
                "relationThumbnailContextLayerCounts",
                "uawRelationThumbnailInputAnchorCount",
                "uawRelationThumbnailSelectedAnchorCount",
                "uawRelationThumbnailAnchorBudget",
                "uawRelationThumbnailPairBudget",
                "uawRelationThumbnailEmittedPairCount",
                "uawRelationThumbnailSliced",
                "signals")) {
            if (kgAxis.containsKey(key)) {
                TraceStore.put("rag.eval.kgAxis." + key, kgAxis.get(key));
            }
        }
    }

    private static void addThresholdScope(List<Map<String, Object>> thresholdBreaks,
                                          String scope,
                                          int sampleCount,
                                          String aggregationWindow) {
        if (thresholdBreaks == null) {
            return;
        }
        for (Map<String, Object> row : thresholdBreaks) {
            if (row == null) {
                continue;
            }
            row.putIfAbsent("scope", scope);
            row.putIfAbsent("sampleCount", Math.max(0, sampleCount));
            row.putIfAbsent("aggregationWindow", aggregationWindow);
        }
    }

    private static Map<String, Object> queryFingerprint(QueryRequest req, QueryAnalysisResult analysis) {
        Map<String, Object> out = new LinkedHashMap<>();
        String query = req == null ? null : req.query;
        out.put("queryHash", query == null ? "" : DigestUtils.sha256Hex(query));
        out.put("length", query == null ? 0 : query.length());
        out.put("tokenBucket", queryTokenBucket(query));
        out.put("hasKorean", hasKorean(query));
        out.put("hasUrl", hasUrl(query));
        out.put("topK", req == null ? 0 : Math.max(0, req.topK));
        if (analysis != null && analysis.intent() != null) {
            out.put("intent", analysis.intent().name());
            out.put("queryClass", analysis.isEntityQuery() ? "entity" : "intent:" + analysis.intent().name());
        } else if (req != null && req.entityQuery) {
            out.put("queryClass", "entity");
        } else {
            out.put("queryClass", "unknown");
        }
        return out;
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    private static boolean hasKorean(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return query.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
    }

    private static boolean hasUrl(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("http://") || lower.contains("https://") || lower.contains("www.");
    }

    private static List<String> goodSignals(NormalizedRagMetrics metrics, List<Map<String, Object>> thresholdBreaks) {
        if (metrics == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (metrics.balancedScore() >= 0.75d) out.add("balanced_score_ok");
        if (thresholdBreaks == null || thresholdBreaks.isEmpty()) out.add("thresholds_clear");
        if (metrics.fallbackCost() <= NormalizedRagMetrics.MAX_FALLBACK_COST) out.add("fallback_cost_ok");
        if (metrics.sourceDiversity() >= NormalizedRagMetrics.MIN_SOURCE_DIVERSITY) out.add("source_diversity_ok");
        double contextContamination = toDouble(TraceStore.get("learning.validation.contextContaminationScore"), 0.0d);
        double contextContaminationMax = toDouble(TraceStore.get("learning.threshold.contextContaminationMax"), 0.40d);
        if (contextContamination < contextContaminationMax) out.add("context_contamination_ok");
        return List.copyOf(out);
    }

    private static List<String> contaminationSignals(NormalizedRagMetrics metrics,
                                                     List<String> providerDisabledSignals) {
        List<String> out = new ArrayList<>();
        double contextContamination = toDouble(TraceStore.get("learning.validation.contextContaminationScore"), 0.0d);
        double contextContaminationMax = toDouble(TraceStore.get("learning.threshold.contextContaminationMax"), 0.40d);
        double legacyContextScore = toDouble(TraceStore.get("learning.validation.legacyContextScore"), 0.0d);
        if (contextContamination >= contextContaminationMax) out.add("context_contamination_threshold");
        if (legacyContextScore > 0.40d) out.add("legacy_context_risk");
        Object vectorDecision = TraceStore.get("learning.feedback.vectorDecision");
        if ("QUARANTINE".equalsIgnoreCase(String.valueOf(vectorDecision))) out.add("vector_quarantine");
        Object rejectReasons = TraceStore.get("learning.validation.rejectReasons");
        if (String.valueOf(rejectReasons).contains("contamination_risk")) out.add("contamination_risk");
        if (String.valueOf(rejectReasons).contains("provider_disabled")) out.add("provider_disabled");
        if (providerDisabledSignals != null && !providerDisabledSignals.isEmpty()) out.add("provider_disabled_fallback");
        if (metrics != null && metrics.sourceDiversity() < NormalizedRagMetrics.MIN_SOURCE_DIVERSITY) {
            out.add("low_source_diversity");
        }
        return out.stream().distinct().toList();
    }

    private static Map<String, Double> stageDrop(Map<String, Integer> stageCounts) {
        if (stageCounts == null || stageCounts.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> drops = new LinkedHashMap<>();
        List<String> order = List.of("pool", "fused", "biencoder", "dpp", "onnx", "final");
        for (int i = 1; i < order.size(); i++) {
            String beforeStage = order.get(i - 1);
            String afterStage = order.get(i);
            int before = Math.max(0, stageCounts.getOrDefault(beforeStage, 0));
            int after = Math.max(0, stageCounts.getOrDefault(afterStage, 0));
            if (before > 0) {
                drops.put(beforeStage + "->" + afterStage, Math.max(0.0d, 1.0d - (after / (double) before)));
            }
        }
        return drops;
    }

    private static Map<String, Object> bottleneck(Map<String, Long> stageMs,
                                                  long totalMs,
                                                  Map<String, Integer> stageCounts,
                                                  List<Map<String, Object>> thresholdBreaks) {
        Map<String, Object> out = new LinkedHashMap<>();
        String stage = "none";
        long maxMs = 0L;
        if (stageMs != null) {
            for (Map.Entry<String, Long> entry : stageMs.entrySet()) {
                long value = entry.getValue() == null ? 0L : Math.max(0L, entry.getValue());
                if (value > maxMs) {
                    maxMs = value;
                    stage = entry.getKey();
                }
            }
        }
        double share = totalMs <= 0 ? 0.0d : Math.min(1.0d, maxMs / (double) totalMs);
        String label = "none";
        double severity = 0.0d;
        for (Map<String, Object> thresholdBreak : thresholdBreaks == null ? List.<Map<String, Object>>of() : thresholdBreaks) {
            String breakLabel = String.valueOf(thresholdBreak.get("label"));
            double breakSeverity = toDouble(thresholdBreak.get("severity"), 0.0d);
            if (breakSeverity > severity) {
                label = breakLabel;
                severity = breakSeverity;
            }
        }
        Map<String, Double> drops = stageDrop(stageCounts);
        double maxDrop = 0.0d;
        String dropStage = "";
        for (Map.Entry<String, Double> entry : drops.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > maxDrop) {
                maxDrop = entry.getValue();
                dropStage = entry.getKey();
            }
        }
        if (maxDrop >= 0.50d && severity < maxDrop) {
            label = "drop_bottleneck";
            severity = maxDrop;
            stage = dropStage;
        } else if (share >= 0.50d && maxMs > 0L && "none".equals(label)) {
            label = "latency_bottleneck";
            severity = share;
        }
        out.put("stage", stage);
        out.put("stageMs", maxMs);
        out.put("share", share);
        out.put("label", label);
        out.put("severity", severity);
        out.put("totalMs", Math.max(0L, totalMs));
        return out;
    }

    private Map<String, Integer> stageCounts(QueryTrace trace, List<Doc> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("seed", size(trace == null ? null : trace.seed));
        counts.put("web", size(trace == null ? null : trace.web));
        counts.put("vector", size(trace == null ? null : trace.vector));
        counts.put("kg", size(trace == null ? null : trace.kg));
        counts.put("bm25", size(trace == null ? null : trace.bm25));
        counts.put("pool", size(trace == null ? null : trace.pool));
        counts.put("fused", size(trace == null ? null : trace.fused));
        counts.put("biencoder", size(trace == null ? null : trace.biencoder));
        counts.put("dpp", size(trace == null ? null : trace.dpp));
        counts.put("onnx", size(trace == null ? null : trace.onnx));
        counts.put("final", size(results));
        return counts;
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private Map<String, Integer> sourceDiversity(List<Doc> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (results == null) {
            return counts;
        }
        for (Doc doc : results) {
            if (doc == null) {
                continue;
            }
            String source = doc.source == null || doc.source.isBlank()
                    ? "UNKNOWN"
                    : doc.source.trim().toUpperCase(Locale.ROOT);
            counts.merge(source, 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Map<String, Integer>> stageSourceDiversity(QueryTrace trace, List<Doc> results) {
        Map<String, Map<String, Integer>> stages = new LinkedHashMap<>();
        stages.put("pool", sourceDiversity(trace == null ? null : trace.pool));
        stages.put("fused", sourceDiversity(trace == null ? null : trace.fused));
        stages.put("final", sourceDiversity(results));
        return stages;
    }

    private Map<String, Object> kgAxis(QueryRequest req,
                                       List<Doc> results,
                                       Map<String, Integer> stageCounts,
                                       Map<String, Integer> sourceDiversity,
                                       Map<String, Long> stageMs,
                                       long totalMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean requested = req == null || req.useKg;
        boolean retrieverPresent = kgRetriever != null;
        int requestedTopK = req == null ? 0 : effectiveKgTopK(req);
        int retrievedCount = stageCounts == null ? 0 : Math.max(0, stageCounts.getOrDefault("kg", 0));
        int kgFinalCount = countKgDocs(results);
        int finalCount = kgFinalCount;
        int poolCount = stageCounts == null ? 0 : Math.max(0, stageCounts.getOrDefault("pool", 0));
        int resultCount = results == null ? 0 : results.size();
        long latencyMs = stageMs == null ? 0L : Math.max(0L, stageMs.getOrDefault("kg", 0L));
        double retrievalHitRate = requested && retrieverPresent && retrievedCount > 0 ? 1.0d : 0.0d;
        double finalRetention = ratio(finalCount, retrievedCount);
        double dropRate = retrievedCount <= 0 ? 0.0d : clamp01(1.0d - finalRetention);
        double poolShare = ratio(retrievedCount, poolCount);
        double finalShare = ratio(finalCount, resultCount);
        double latencyShare = totalMs <= 0L ? 0.0d : clamp01(latencyMs / (double) Math.max(1L, totalMs));
        double latencyQuality = retrievedCount > 0 ? clamp01(1.0d - latencyShare) : 0.0d;
        double axisScore = requested && retrieverPresent
                ? clamp01((0.45d * retrievalHitRate) + (0.35d * finalRetention)
                + (0.10d * poolShare) + (0.10d * latencyQuality))
                : 0.0d;
        List<Double> kgScores = kgScoreValues(results);
        double kgScoreMean = mean(kgScores);
        double kgScoreP95 = percentile(kgScores, 0.95d);
        double sourceDiversityKgSignal = finalCount > 0 ? 1.0d : 0.0d;
        double graphScore = requested && retrieverPresent
                ? clamp01((0.40d * kgScoreMean) + (0.35d * finalRetention) + (0.25d * sourceDiversityKgSignal))
                : 0.0d;
        boolean graphOpportunity = requested && retrieverPresent && (retrievedCount <= 0 || (retrievedCount > 0 && finalCount <= 0));
        String neo4jStatus = safeDiagString(TraceStore.get("retrieval.kg.neo4j.status"));
        String neo4jDisabledReason = safeDiagString(TraceStore.get("retrieval.kg.neo4j.disabledReason"));
        String neo4jFailureClass = safeDiagString(TraceStore.get("retrieval.kg.neo4j.failureClass"));
        String sparseNodeStatus = firstNonBlank(
                safeDiagString(TraceStore.get("retrieval.kg.brainState.status")),
                safeDiagString(TraceStore.get("retrieval.kg.sparsePath.status")));
        String sparseNodeDisabledReason = firstNonBlank(
                safeDiagString(TraceStore.get("retrieval.kg.brainState.disabledReason")),
                safeDiagString(TraceStore.get("retrieval.kg.brainState.reason")));
        String sparseNodeFailureClass = firstNonBlank(
                safeDiagString(TraceStore.get("retrieval.kg.brainState.failureClass")),
                safeDiagString(TraceStore.get("retrieval.kg.sparsePath.failureClass")));
        int sparseNodePortMappingCount = traceInt("retrieval.kg.brainState.portMappingCount");
        List<String> sparseNodePortMappingHashes =
                traceHashList("retrieval.kg.brainState.portMappingHashes");
        int sparseNodeTransitivePathCount = traceInt("retrieval.kg.sparsePath.transitivePathCount");
        boolean sparseNodeQueryAnchorMapApplied =
                Boolean.TRUE.equals(TraceStore.get("retrieval.kg.brainState.queryAnchorMap.applied"));
        int sparseNodeQueryAnchorMapSeedCount = traceInt("retrieval.kg.brainState.queryAnchorMap.seedCount");
        List<String> sparseNodeQueryAnchorMapSeedHashes =
                traceHashList("retrieval.kg.brainState.queryAnchorMap.seedHashes");
        String sparseNodeQueryAnchorMapReason =
                safeDiagString(TraceStore.get("retrieval.kg.brainState.queryAnchorMap.reason"));
        String relationThumbnailStatus =
                safeDiagString(TraceStore.get("retrieval.kg.relationThumbnail.status"));
        int relationThumbnailCandidateCount =
                traceInt("retrieval.kg.relationThumbnail.candidateCount");
        int relationThumbnailSelectedCount =
                traceInt("retrieval.kg.relationThumbnail.selectedCount");
        int relationThumbnailDroppedCount =
                traceInt("retrieval.kg.relationThumbnail.droppedCount");
        String relationThumbnailSelectionReason =
                safeDiagString(TraceStore.get("retrieval.kg.relationThumbnail.selectionReason"));
        String relationThumbnailTopHash =
                safeHash12(TraceStore.get("retrieval.kg.relationThumbnail.topHash"));
        Map<String, Integer> relationThumbnailContextLayerCounts =
                relationThumbnailContextLayerCountsFromTrace();
        List<String> relationThumbnailContextLayers = relationThumbnailContextLayerCounts.isEmpty()
                ? List.of()
                : List.copyOf(relationThumbnailContextLayerCounts.keySet());
        double relationThumbnailSelectionRate =
                ratio(relationThumbnailSelectedCount, relationThumbnailCandidateCount);
        double relationThumbnailDropRate =
                ratio(relationThumbnailDroppedCount, relationThumbnailCandidateCount);
        String uawPrefix = "uaw.thumbnail.relationThumbnail.";
        int uawRelationThumbnailInputAnchorCount =
                traceInt(uawPrefix + "inputAnchorCount");
        int uawRelationThumbnailSelectedAnchorCount =
                traceInt(uawPrefix + "selectedAnchorCount");
        int uawRelationThumbnailAnchorBudget =
                traceInt(uawPrefix + "anchorBudget");
        int uawRelationThumbnailPairBudget =
                traceInt(uawPrefix + "pairBudget");
        int uawRelationThumbnailEmittedPairCount =
                traceInt(uawPrefix + "emittedPairCount");
        boolean uawRelationThumbnailSliced =
                Boolean.TRUE.equals(TraceStore.get(uawPrefix + "sliced"));
        List<String> signals = kgAxisSignals(requested, retrieverPresent, retrievedCount, finalCount, resultCount,
                sourceDiversity, kgScores, neo4jStatus, neo4jDisabledReason, neo4jFailureClass,
                sparseNodeStatus, sparseNodeDisabledReason, sparseNodeFailureClass,
                sparseNodeTransitivePathCount, sparseNodePortMappingCount,
                relationThumbnailStatus, relationThumbnailSelectionReason,
                relationThumbnailCandidateCount, relationThumbnailSelectedCount, relationThumbnailDroppedCount);
        if (!relationThumbnailContextLayers.isEmpty()) {
            LinkedHashSet<String> enrichedSignals = new LinkedHashSet<>(signals);
            for (String layer : relationThumbnailContextLayers) {
                String safeLayer = normalizedSignalLabel(layer);
                if (!safeLayer.isBlank()) {
                    enrichedSignals.add("kg_relation_thumbnail_" + safeLayer + "_layer");
                }
            }
            signals = List.copyOf(enrichedSignals);
        }
        String dependencyStatus = safeDiagString(TraceStore.get("retrieval.dependency.kg.status"));
        String status = kgStatus(requested, retrieverPresent, retrievedCount, finalCount, dependencyStatus);

        out.put("schemaVersion", 2);
        out.put("requested", requested);
        out.put("retrieverPresent", retrieverPresent);
        out.put("requestedTopK", requestedTopK);
        out.put("retrievedCount", retrievedCount);
        out.put("finalCount", finalCount);
        out.put("kgPoolCount", retrievedCount);
        out.put("kgFinalCount", kgFinalCount);
        out.put("kgScoreMean", round4(kgScoreMean));
        out.put("kgScoreP95", round4(kgScoreP95));
        out.put("kgFinalRetention", round4(finalRetention));
        out.put("graphScore", round4(graphScore));
        out.put("graphOpportunity", graphOpportunity);
        out.put("signals", signals);
        out.put("poolShare", round4(poolShare));
        out.put("finalShare", round4(finalShare));
        out.put("retrievalHitRate", round4(retrievalHitRate));
        out.put("finalRetention", round4(finalRetention));
        out.put("dropRate", round4(dropRate));
        out.put("latencyMs", latencyMs);
        out.put("latencyShare", round4(latencyShare));
        out.put("latencyQuality", round4(latencyQuality));
        out.put("score", round4(axisScore));
        out.put("status", status);
        out.put("emptyReason", kgEmptyReason(requested, retrieverPresent, retrievedCount, finalCount, dependencyStatus));
        out.put("dependencyStatus", dependencyStatus);
        out.put("dependencyFallbackUsed", Boolean.TRUE.equals(TraceStore.get("retrieval.dependency.kg.fallbackUsed")));
        out.put("neo4jStatus", neo4jStatus);
        out.put("neo4jDisabledReason", neo4jDisabledReason);
        out.put("neo4jFailureClass", neo4jFailureClass);
        out.put("neo4jReturnedCount", traceInt("retrieval.kg.neo4j.returnedCount"));
        out.put("jpaStatus", safeDiagString(TraceStore.get("retrieval.kg.jpa.status")));
        out.put("jpaReturnedCount", traceInt("retrieval.kg.jpa.returnedCount"));
        out.put("sparsePathStatus", safeDiagString(TraceStore.get("retrieval.kg.sparsePath.status")));
        out.put("sparsePathTransitiveCount", traceInt("retrieval.kg.sparsePath.transitivePathCount"));
        out.put("sparseNodeStatus", sparseNodeStatus);
        out.put("sparseNodeDisabledReason", sparseNodeDisabledReason);
        out.put("sparseNodeFailureClass", sparseNodeFailureClass);
        out.put("sparseNodePortMappingCount", sparseNodePortMappingCount);
        out.put("sparseNodePortMappingHashes", sparseNodePortMappingHashes);
        out.put("sparseNodeTransitivePathCount", sparseNodeTransitivePathCount);
        out.put("sparseNodeQueryAnchorMapApplied", sparseNodeQueryAnchorMapApplied);
        out.put("sparseNodeQueryAnchorMapSeedCount", sparseNodeQueryAnchorMapSeedCount);
        out.put("sparseNodeQueryAnchorMapSeedHashes", sparseNodeQueryAnchorMapSeedHashes);
        out.put("sparseNodeQueryAnchorMapReason", sparseNodeQueryAnchorMapReason);
        out.put("relationThumbnailStatus", relationThumbnailStatus);
        out.put("relationThumbnailCandidateCount", relationThumbnailCandidateCount);
        out.put("relationThumbnailSelectedCount", relationThumbnailSelectedCount);
        out.put("relationThumbnailDroppedCount", relationThumbnailDroppedCount);
        out.put("relationThumbnailSelectionRate", round4(relationThumbnailSelectionRate));
        out.put("relationThumbnailDropRate", round4(relationThumbnailDropRate));
        out.put("relationThumbnailSelectionReason", relationThumbnailSelectionReason);
        out.put("relationThumbnailAnchorSeedUsed",
                "anchor_seed".equalsIgnoreCase(relationThumbnailSelectionReason));
        out.put("relationThumbnailTopHash", relationThumbnailTopHash);
        out.put("relationThumbnailContextLayers", relationThumbnailContextLayers);
        out.put("relationThumbnailContextLayerCounts", relationThumbnailContextLayerCounts);
        out.put("uawRelationThumbnailInputAnchorCount", uawRelationThumbnailInputAnchorCount);
        out.put("uawRelationThumbnailSelectedAnchorCount", uawRelationThumbnailSelectedAnchorCount);
        out.put("uawRelationThumbnailAnchorBudget", uawRelationThumbnailAnchorBudget);
        out.put("uawRelationThumbnailPairBudget", uawRelationThumbnailPairBudget);
        out.put("uawRelationThumbnailEmittedPairCount", uawRelationThumbnailEmittedPairCount);
        out.put("uawRelationThumbnailSliced", uawRelationThumbnailSliced);
        return out;
    }

    private static int countKgDocs(List<Doc> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Doc doc : results) {
            if (isKgDoc(doc)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isKgDoc(Doc doc) {
        if (doc == null) {
            return false;
        }
        if (doc.source != null && "KG".equalsIgnoreCase(doc.source.trim())) {
            return true;
        }
        Map<String, Object> meta = doc.meta == null ? Map.of() : doc.meta;
        if (firstNonBlank(meta, "kg_score", "kgScore", "graph_score", "graphScore", "kg_path_score", "kgPathScore") != null) {
            return true;
        }
        Object source = firstNonBlank(meta, "retrieval_source", "retrievalSource", "source");
        return source != null && "kg".equalsIgnoreCase(String.valueOf(source).trim());
    }

    private static List<Double> kgScoreValues(List<Doc> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        for (Doc doc : results) {
            if (!isKgDoc(doc)) {
                continue;
            }
            Object raw = firstNonBlank(doc.meta,
                    "kg_score", "kgScore", "graph_score", "graphScore", "kg_path_score", "kgPathScore",
                    "score", "rrf_score", "rrfScore", "vector_score", "vectorScore", "similarityScore");
            double value = toDouble(raw, Double.NaN);
            if (Double.isFinite(value)) {
                values.add(clamp01(value));
            }
        }
        return values;
    }

    private static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        double sum = 0.0d;
        int count = 0;
        for (Double value : values) {
            if (value != null && Double.isFinite(value)) {
                sum += value;
                count++;
            }
        }
        return count <= 0 ? 0.0d : clamp01(sum / count);
    }

    private static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        List<Double> sorted = values.stream()
                .filter(Objects::nonNull)
                .filter(Double::isFinite)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        double clampedP = clamp01(p);
        int index = (int) Math.ceil(clampedP * sorted.size()) - 1;
        return clamp01(sorted.get(Math.max(0, Math.min(sorted.size() - 1, index))));
    }

    private static List<String> kgAxisSignals(boolean requested,
                                              boolean retrieverPresent,
                                              int retrievedCount,
                                              int finalCount,
                                              int resultCount,
                                              Map<String, Integer> sourceDiversity,
                                              List<Double> kgScores,
                                              String neo4jStatus,
                                              String neo4jDisabledReason,
                                              String neo4jFailureClass,
                                              String sparseNodeStatus,
                                              String sparseNodeDisabledReason,
                                              String sparseNodeFailureClass,
                                              int sparseNodeTransitivePathCount,
                                              int sparseNodePortMappingCount,
                                              String relationThumbnailStatus,
                                              String relationThumbnailSelectionReason,
                                              int relationThumbnailCandidateCount,
                                              int relationThumbnailSelectedCount,
                                              int relationThumbnailDroppedCount) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        if (!requested) {
            return List.of();
        }
        if (!retrieverPresent) {
            signals.add("kg_missing_retriever");
            addNeo4jSignals(signals, neo4jStatus, neo4jDisabledReason, neo4jFailureClass);
            addSparseNodeSignals(signals, sparseNodeStatus, sparseNodeDisabledReason, sparseNodeFailureClass,
                    sparseNodeTransitivePathCount, sparseNodePortMappingCount);
            addRelationThumbnailSignals(signals, relationThumbnailStatus, relationThumbnailSelectionReason,
                    relationThumbnailCandidateCount, relationThumbnailSelectedCount, relationThumbnailDroppedCount);
            return List.copyOf(signals);
        }
        if (retrievedCount <= 0) {
            signals.add("kg_starvation");
        } else if (finalCount <= 0) {
            signals.add("kg_final_drop");
        }
        if (finalCount > 0 && (kgScores == null || kgScores.isEmpty())) {
            signals.add("kg_score_missing");
        }
        if (resultCount > 1 && sourceDiversity != null && sourceDiversity.size() <= 1) {
            signals.add("source_collapse");
        }
        addNeo4jSignals(signals, neo4jStatus, neo4jDisabledReason, neo4jFailureClass);
        addSparseNodeSignals(signals, sparseNodeStatus, sparseNodeDisabledReason, sparseNodeFailureClass,
                sparseNodeTransitivePathCount, sparseNodePortMappingCount);
        addRelationThumbnailSignals(signals, relationThumbnailStatus, relationThumbnailSelectionReason,
                relationThumbnailCandidateCount, relationThumbnailSelectedCount, relationThumbnailDroppedCount);
        return List.copyOf(signals);
    }

    private static void addRelationThumbnailSignals(LinkedHashSet<String> signals,
                                                    String status,
                                                    String selectionReason,
                                                    int candidateCount,
                                                    int selectedCount,
                                                    int droppedCount) {
        String safeStatus = normalizedSignalLabel(status);
        String safeReason = normalizedSignalLabel(selectionReason);
        if ("applied".equals(safeStatus)) {
            signals.add("kg_relation_thumbnail_applied");
        }
        if (candidateCount > 0 && selectedCount <= 0) {
            signals.add("kg_relation_thumbnail_no_selection");
        }
        if (droppedCount > 0) {
            signals.add("kg_relation_thumbnail_sliced");
        }
        if ("anchor_seed".equals(safeReason)) {
            signals.add("kg_relation_thumbnail_anchor_seed");
        }
    }

    private static void addSparseNodeSignals(LinkedHashSet<String> signals,
                                             String status,
                                             String disabledReason,
                                             String failureClass,
                                             int transitivePathCount,
                                             int portMappingCount) {
        String safeStatus = normalizedSignalLabel(status);
        String safeReason = normalizedSignalLabel(disabledReason);
        String safeFailureClass = normalizedSignalLabel(failureClass);
        boolean failed = "failed".equals(safeStatus)
                || safeStatus.contains("failure")
                || safeStatus.contains("error")
                || hasFailureClass(safeFailureClass);
        boolean noGraphPath = "no_graph_path".equals(safeStatus) || "no_graph_path".equals(safeReason);
        if (failed) {
            signals.add("kg_sparse_node_failed");
        }
        if (noGraphPath) {
            signals.add("kg_sparse_node_no_graph_path");
        }
        if (transitivePathCount > 0) {
            signals.add("kg_sparse_node_transitive_path");
        }
        if (portMappingCount > 0) {
            signals.add("kg_sparse_node_port_mapping");
        }
    }

    private static void addNeo4jSignals(LinkedHashSet<String> signals,
                                        String status,
                                        String disabledReason,
                                        String failureClass) {
        String safeStatus = normalizedSignalLabel(status);
        String safeReason = normalizedSignalLabel(disabledReason);
        String safeFailureClass = normalizedSignalLabel(failureClass);
        boolean failed = "failed".equals(safeStatus)
                || safeStatus.contains("failure")
                || safeStatus.contains("error")
                || (safeReason.startsWith("neo4j_") && safeReason.endsWith("_failed"))
                || hasFailureClass(safeFailureClass);
        boolean disabled = "disabled".equals(safeStatus)
                || safeReason.contains("disabled")
                || safeReason.startsWith("missing_")
                || "unsafe_default_credentials".equals(safeReason);
        boolean degraded = failed || disabled || (!safeStatus.isBlank()
                && !Set.of("ok", "ready", "success", "enabled", "configured").contains(safeStatus));
        if (failed) {
            signals.add("kg_neo4j_failed");
        }
        if (disabled) {
            signals.add("kg_neo4j_disabled");
        }
        if (degraded) {
            signals.add("kg_neo4j_degraded");
        }
    }

    private static boolean hasFailureClass(String value) {
        return value != null && !value.isBlank()
                && !Set.of("none", "ok", "success", "enabled", "configured").contains(value);
    }

    private static String normalizedSignalLabel(String value) {
        String safe = safeDiagString(value);
        if (safe.isBlank() || "redacted".equals(safe)) {
            return "";
        }
        return safe.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private List<String> providerDisabledSignals(QueryRequest req, Map<String, Object> debug) {
        List<String> signals = new ArrayList<>();
        if (req != null) {
            if (req.useWeb && webRetriever == null) signals.add("web:missing_webRetriever");
            if (req.useVector && vectorLeaf() == null) signals.add("vector:unavailable_pure_vector_leaf");
            if (req.useKg && kgRetriever == null) signals.add("kg:missing_kgRetriever");
            if (req.useBm25 && bm25Index == null) signals.add("bm25:missing_bm25Index");
        }
        collectTraceFlag(signals, "web.naver.providerDisabled", "web.naver.disabledReason", "naver");
        collectTraceFlag(signals, "web.brave.providerDisabled", "web.brave.disabledReason", "brave");
        collectTraceFlag(signals, "web.serpapi.providerDisabled", "web.serpapi.disabledReason", "serpapi");
        collectTraceFlag(signals, "web.tavily.providerDisabled", "web.tavily.disabledReason", "tavily");
        if (debug != null) {
            for (Map.Entry<String, Object> e : debug.entrySet()) {
                String key = e.getKey();
                String value = String.valueOf(e.getValue());
                String lower = value.toLowerCase(Locale.ROOT);
                if (key != null && key.startsWith("stage.")
                        && (lower.contains("missing") || lower.contains("disabled"))) {
                    signals.add(key + ":" + (lower.contains("missing") ? "missing" : "disabled"));
                }
            }
        }
        return signals.stream().distinct().toList();
    }

    private static Map<String, Object> logicDagTrace() {
        Map<String, Object> out = new LinkedHashMap<>();
        copyLogicDagTrace(out, "selfask.logicDag.enabled", "enabled");
        copyLogicDagTrace(out, "selfask.logicDag.dependencyMode", "dependencyMode");
        copyLogicDagTrace(out, "selfask.logicDag.nodeCount", "nodeCount");
        copyLogicDagTrace(out, "selfask.logicDag.edgeCount", "edgeCount");
        copyLogicDagTrace(out, "selfask.logicDag.topologicalOrder", "topologicalOrder");
        copyLogicDagTrace(out, "selfask.logicDag.prunedDuplicateCount", "prunedDuplicateCount");
        copyLogicDagTrace(out, "selfask.logicDag.failureClass", "failureClass");
        return out.isEmpty() ? Map.of() : out;
    }

    private static void copyLogicDagTrace(Map<String, Object> out, String traceKey, String outKey) {
        Object value = TraceStore.get(traceKey);
        if (value == null) {
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            out.put(outKey, value);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                String safe = safeDiagString(item);
                if (!safe.isBlank()) {
                    values.add(safe.matches("[A-Za-z0-9_.:-]{1,32}") ? safe : "hash:" + safeHash(safe));
                }
            }
            out.put(outKey, List.copyOf(values));
            return;
        }
        out.put(outKey, safeDiagString(value));
    }

    private List<String> zeroResultSignals(QueryRequest req,
                                           QueryTrace trace,
                                           List<Doc> results,
                                           Map<String, Integer> stageCounts) {
        List<String> signals = new ArrayList<>();
        if (results == null || results.isEmpty()) {
            signals.add("final:empty");
        }
        if (req != null && req.useWeb && stageCounts.getOrDefault("web", 0) == 0) {
            signals.add("web:empty");
        }
        if (req != null && req.useVector && stageCounts.getOrDefault("vector", 0) == 0) {
            signals.add("vector:empty");
        }
        if (trace != null && stageCounts.getOrDefault("pool", 0) == 0) {
            signals.add("pool:empty");
        }
        collectTraceFlag(signals, "web.naver.parse.itemsEmpty", "web.naver.parse.rawSize", "naver:items_empty");
        collectTraceFlag(signals, "web.naver.zeroResults", "web.naver.afterFilterCount", "naver:zero_results");
        collectTraceFlag(signals, "web.serpapi.zeroResults", "web.serpapi.returnedCount", "serpapi:zero_results");
        collectTraceFlag(signals, "web.brave.zeroResults", "web.brave.returnedCount", "brave:zero_results");
        collectTraceFlag(signals, "web.tavily.zeroResults", "web.tavily.returnedCount", "tavily:zero_results");
        collectTraceFlag(signals, "webSearch.zeroResults", "webSearch.returnedCount", "webSearch:zero_results");
        collectTraceFlag(signals, "rag.zeroResults", "rag.returnedCount", "rag:zero_results");
        return signals.stream().distinct().toList();
    }

    private List<String> afterFilterStarvationSignals() {
        List<String> signals = new ArrayList<>();
        collectAfterFilterStarvation(signals, "web.naver.returnedCount", "web.naver.afterFilterCount", "naver");
        collectAfterFilterStarvation(signals, "web.naver.filter.rawCount", "web.naver.filter.afterStrictCount", "naver:legacy_filter");
        collectAfterFilterStarvation(signals, "web.naver.parse.rawSize", "web.naver.afterFilterCount", "naver:legacy_parse");
        collectAfterFilterStarvation(signals, "web.brave.returnedCount", "web.brave.afterFilterCount", "brave");
        collectAfterFilterStarvation(signals, "web.serpapi.returnedCount", "web.serpapi.afterFilterCount", "serpapi");
        collectAfterFilterStarvation(signals, "web.tavily.returnedCount", "web.tavily.afterFilterCount", "tavily");
        collectAfterFilterStarvation(signals, "webSearch.returnedCount", "webSearch.afterFilterCount", "webSearch");
        collectAfterFilterStarvation(signals, "rag.returnedCount", "rag.afterFilterCount", "rag");
        return signals.stream().distinct().toList();
    }

    private static void collectAfterFilterStarvation(List<String> signals,
                                                     String returnedKey,
                                                     String afterFilterKey,
                                                     String label) {
        double returned = toDouble(TraceStore.get(returnedKey), -1.0d);
        double afterFilter = toDouble(TraceStore.get(afterFilterKey), -1.0d);
        if (returned > 0.0d && afterFilter == 0.0d) {
            signals.add(label + ":after_filter_starvation");
        }
    }

    private static void collectTraceFlag(List<String> signals, String flagKey, String detailKey, String label) {
        Object flag = TraceStore.get(flagKey);
        if (!Boolean.TRUE.equals(flag)) {
            return;
        }
        Object detail = TraceStore.get(detailKey);
        signals.add(detail == null ? label : label + ":" + com.example.lms.trace.SafeRedactor.traceFlagDetail(detail));
    }

    private static int evidenceCount(List<Doc> results) {
        if (results == null || results.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Doc doc : results) {
            if (doc == null) {
                continue;
            }
            boolean hasSnippet = doc.snippet != null && !doc.snippet.isBlank();
            boolean hasUrl = doc.meta != null && firstNonBlank(doc.meta,
                    "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical", "permalink") != null;
            if (hasSnippet || hasUrl) {
                count++;
            }
        }
        return count;
    }

    private static void recordStageMs(Map<String, Long> stageMs, String stage, long startedNs) {
        if (stageMs == null || stage == null || stage.isBlank()) {
            return;
        }
        stageMs.merge(stage, elapsedMs(startedNs), Long::sum);
    }

    private static long elapsedMs(long startedNs) {
        if (startedNs <= 0L) {
            return 0L;
        }
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs));
    }

    private static double toDouble(Object value, double fallback) {
        try {
            return OrchestratorTraceNumbers.parseDouble(value, fallback, "toDouble");
        } catch (NumberFormatException ignored) {
            INVALID_NUMBER_SUPPRESSOR.accept("toDouble");
            return fallback;
        }
    }

    private static int traceInt(String key) {
        try {
            return OrchestratorTraceNumbers.parseNonNegativeInt(TraceStore.get(key), "traceInt");
        } catch (NumberFormatException ignored) {
            INVALID_NUMBER_SUPPRESSOR.accept("traceInt");
            return 0;
        }
    }

    private static List<String> traceHashList(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                String hash = safeHash12(item);
                if (!hash.isBlank() && !out.contains(hash)) {
                    out.add(hash);
                }
            }
            return List.copyOf(out);
        }
        String hash = safeHash12(value);
        return hash.isBlank() ? List.of() : List.of(hash);
    }

    private static String safeHash12(Object value) {
        if (value == null) {
            return "";
        }
        String hex = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
        return hex.length() < 12 ? "" : hex.substring(0, 12);
    }

    private static double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return clamp01(numerator / (double) denominator);
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static int countSource(List<Doc> results, String source) {
        if (results == null || source == null) {
            return 0;
        }
        int count = 0;
        for (Doc doc : results) {
            if (doc != null && source.equalsIgnoreCase(String.valueOf(doc.source))) {
                count++;
            }
        }
        return count;
    }

    private static String kgStatus(boolean requested,
                                   boolean retrieverPresent,
                                   int retrievedCount,
                                   int finalCount,
                                   String dependencyStatus) {
        if (!requested) {
            return "disabled";
        }
        if (!retrieverPresent) {
            return "missing_kgRetriever";
        }
        if (retrievedCount > 0) {
            return finalCount > 0 ? "contributed" : "retrieved_dropped";
        }
        if (dependencyStatus != null && !dependencyStatus.isBlank()
                && !"ready".equalsIgnoreCase(dependencyStatus)) {
            return dependencyStatus;
        }
        return "empty";
    }

    private static String kgEmptyReason(boolean requested,
                                        boolean retrieverPresent,
                                        int retrievedCount,
                                        int finalCount,
                                        String dependencyStatus) {
        if (!requested) {
            return "disabled_by_config";
        }
        if (!retrieverPresent) {
            return "missing_kgRetriever";
        }
        if (retrievedCount > 0 && finalCount <= 0) {
            return "final_drop";
        }
        if (retrievedCount <= 0) {
            return dependencyStatus == null || dependencyStatus.isBlank() || "ready".equalsIgnoreCase(dependencyStatus)
                    ? "kg_empty"
                    : dependencyStatus;
        }
        return "";
    }

    private static String safeDiagString(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization") || lower.contains("owner-token")
                || lower.contains("api_key") || lower.contains("client-secret")
                || lower.contains("secret=") || lower.contains("token" + "=")) {
            return "redacted";
        }
        return text.matches("[A-Za-z0-9_.:-]{1,80}") ? text : "hash:" + safeHash(text);
    }


    private java.util.List<Doc> toDocsFromContents(java.util.List<Content> contents, int topK, String sourceTag, boolean seed) {
        requireNonNegativeTopK("topK", topK);
        if (contents == null || contents.isEmpty() || topK == 0) {
            return java.util.List.of();
        }
        java.util.List<Doc> docs = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size() && docs.size() < topK; i++) {
            Content c = contents.get(i);
            if (c == null) continue;
            Doc d = new Doc();
            d.meta = extractMetadata(c);
            d.title = extractTitle(d.meta, sourceTag + " Result #" + (i + 1));
            d.snippet = buildSnippet(c);
            d.source = sourceTag;
            d.score = scoreFromMetadata(d.meta, 1.0 - (i * 0.01));
            d.id = stableId(sourceTag, d.meta, i);
            if (d.meta == null) d.meta = new java.util.HashMap<>();
            if (seed) {
                d.meta.put("_seed", true);
            }
            docs.add(d);
        }
        return docs;
    }

    /**
     * Unified VECTOR axis leaf resolver. The axis must perform pure vector
     * retrieval only: when LangChainRAGService is present we use its
     * asContentRetriever() adapter (vector budget + SID/global scope, and no
     * web/SelfAsk/Tavily side-effects). When the service is absent the axis is
     * unavailable; we never fall back to the legacy 'vectorRetriever' bean,
     * which is a HybridRetriever with web/SelfAsk/Tavily side-effects.
     */
    private ContentRetriever vectorLeaf() {
        return langChainRAGService != null
                ? langChainRAGService.asContentRetriever("unified-vector")
                : null;
    }

    private List<Doc> retrieveCandidates(QueryRequest req,
                                         Map<String, Object> dbg,
                                         boolean retry,
                                         QueryTrace trace,
                                         Map<String, Long> stageMs) {
        List<Doc> pool = new ArrayList<>();
        List<Doc> seedOnly = new ArrayList<>();

        // 0) Seed injection (existing Context/RAG results)
        long stageStartedNs = System.nanoTime();
        try {
            if (req.seedCandidates != null && !req.seedCandidates.isEmpty()) {
                for (Doc d : req.seedCandidates) {
                    if (d == null) continue;
                    Doc copy = new Doc();
                    copy.id = d.id;
                    copy.title = d.title;
                    copy.snippet = d.snippet;
                    copy.source = d.source;
                    copy.score = d.score;
                    copy.rank = d.rank;
                    copy.meta = (d.meta != null) ? new HashMap<>(d.meta) : new HashMap<>();
                    copy.meta.put("_seed", true);
                    if (copy.id == null || copy.id.isBlank()) {
                        copy.id = stableId(copy.source != null ? copy.source : "SEED", copy.meta, seedOnly.size());
                    }
                    pool.add(copy);
                    seedOnly.add(copy);
                }
                dbg.put("seed.candidates", seedOnly.size());
            }
            if (req.seedWeb != null && !req.seedWeb.isEmpty()) {
                java.util.List<Doc> seeded = toDocsFromContents(req.seedWeb, effectiveWebTopK(req), "WEB", true);
                pool.addAll(seeded);
                dbg.put("seed.web", seeded.size());
                seedOnly.addAll(seeded);
                if (trace != null) trace.web = snapshotDocs(seeded);
            }
            if (req.seedVector != null && !req.seedVector.isEmpty()) {
                java.util.List<Doc> seeded = toDocsFromContents(req.seedVector, effectiveVectorTopK(req), "VECTOR", true);
                pool.addAll(seeded);
                dbg.put("seed.vector", seeded.size());
                seedOnly.addAll(seeded);
                if (trace != null) trace.vector = snapshotDocs(seeded);
            }
        } catch (Exception ignore) {
            TraceStore.put("rag.orchestrator.suppressed.seed", true);
        } finally {
            recordStageMs(stageMs, "seed", stageStartedNs);
        }

        if (trace != null && !seedOnly.isEmpty()) {
            trace.seed = snapshotDocs(seedOnly);
        }

        if (req.seedOnly) {
            dbg.put("seed.only", true);
            com.example.lms.search.RequestTrace.emit("rag.retrieve", "seed_only", "pool=" + pool.size());
            return pool;
        }
        if (retry) {
            dbg.put("retrieval_retry", Boolean.TRUE);
        }
        // [PATCH] Sequential Fallback Logic (Web -> Vector -> KG/BM25)
        // 1) Web Search (Primary)
        boolean webSuccess = false;
        boolean webAttempted = false;
        boolean webEmpty = false;

        if (!req.useWeb) {
            markDependency("web", "disabled", webRequired, false, false, "disabled_by_config", null, dbg);
            dbg.put("stage.web", "disabled");
            com.example.lms.search.RequestTrace.emit("axis.web", "state", "state=disabled_by_config");
        } else if (webRetriever == null) {
            IllegalStateException missing = new IllegalStateException("web retriever bean missing");
            markDependency("web", "missing_bean", webRequired, true, true, "missing-dependency", missing, dbg);
            dbg.put("stage.web", "missing_webRetriever");
            com.example.lms.search.RequestTrace.emit("axis.web", "state", "state=missing_bean");
        } else {
            markDependency("web", "ready", webRequired, false, false, "", null, dbg);
            com.example.lms.search.RequestTrace.emit("axis.web", "state", "state=ready");
        }
        if (req.useWeb && webRetriever != null && (req.seedWeb == null || req.seedWeb.isEmpty())) {
            webAttempted = true;
            List<Doc> webDocs = new ArrayList<>();
            stageStartedNs = System.nanoTime();
            try {
                markDependency("web", "ready", webRequired, true, false, "", null, dbg);
                String webOwner = org.springframework.aop.support.AopUtils.getTargetClass(webRetriever).getName();
                dbg.put("web.retriever", webOwner.equals("com.example.lms.service.rag.AnalyzeWebSearchRetriever") ? "base"
                        : webOwner.equals("ai.abandonware.nova.orch.adapters.NovaAnalyzeWebSearchRetriever") ? "nova" : "other");
                Query webQuery = req.webQueryAlreadyPlanned
                        ? com.example.lms.service.rag.QueryUtils.buildQuery(req.query,
                                Map.of("webQueryAlreadyPlanned", true, "webTopK", effectiveWebTopK(req)))
                        : new Query(req.query);
                com.example.lms.search.RequestTrace.emit("leg.web", "call",
                        "k=" + effectiveWebTopK(req) + ";owner=" + String.valueOf(dbg.get("web.retriever")));
                List<Content> contents = webRetriever.retrieve(webQuery);
                if (contents == null) {
                    contents = Collections.emptyList();
                    log.warn("[Orchestrator] Web retriever returned null, treating as empty");
                }

                int webK = effectiveWebTopK(req);
                for (int i = 0; i < contents.size() && i < webK; i++) {
                    Content c = contents.get(i);
                    if (c == null) {
                        continue;
                    }

                    Doc d = new Doc();
                    d.meta = extractMetadata(c);
                    d.title = extractTitle(d.meta, "Web Result #" + (i + 1));
                    d.snippet = buildSnippet(c);
                    d.source = "WEB";
                    d.score = scoreFromMetadata(d.meta, 1.0 - (i * 0.01));
                    d.id = stableId("WEB", d.meta, i);

                    webDocs.add(d);
                }

                dbg.put("stage.web", webDocs.isEmpty() ? "empty_result" : "success:" + webDocs.size());
                com.example.lms.search.RequestTrace.emit("leg.web", "result", "count=" + webDocs.size());
            } catch (Exception e) {
                com.example.lms.search.RequestTrace.emit("leg.web", "error",
                        "type=" + e.getClass().getSimpleName());
                // [FIX-D1] Fail-soft: keep pipeline alive and continue to vector/KG/BM25
                log.warn("[AWX][rag][orchestrator] web retrieval failed failureReason={} errorType={} queryHash12={} queryLength={}", "web-retrieval-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.hash12(req.query), req.query == null ? 0 : req.query.length());
                markDependency("web", "failed", webRequired, true, true, classifyDependencyFailure(e), e, dbg);
                dbg.put("stage.web", "failed:web_retrieval_failed");
            } finally {
                recordStageMs(stageMs, "web", stageStartedNs);
            }

            if (trace != null) {
                trace.web = snapshotDocs(webDocs);
            }

            if (!webDocs.isEmpty()) {
                pool.addAll(webDocs);
                webSuccess = true;
            } else {
                webEmpty = true;
            }
        }

        // 2) Vector Search (Conditional)
        if (!req.useVector) {
            markDependency("vector", "disabled", vectorRequired, false, false, "disabled_by_config", null, dbg);
            dbg.put("stage.vector", "disabled");
            com.example.lms.search.RequestTrace.emit("axis.vector", "state", "state=disabled_by_config");
        } else if (vectorLeaf() == null) {
            IllegalStateException missing = new IllegalStateException("pure vector leaf unavailable (LangChainRAGService bean missing)");
            markDependency("vector", "missing_bean", vectorRequired, true, true, "missing-dependency", missing, dbg);
            dbg.put("stage.vector", "unavailable_pure_vector_leaf");
            com.example.lms.search.RequestTrace.emit("axis.vector", "state", "state=unavailable_pure_vector_leaf");
        } else {
            markDependency("vector", "ready", vectorRequired, false, false, "", null, dbg);
            com.example.lms.search.RequestTrace.emit("axis.vector", "state", "state=ready;leaf=pure_vector");
        }
        if (req.useVector && vectorLeaf() != null && (req.seedVector == null || req.seedVector.isEmpty())) {
            int vectorK = effectiveVectorTopK(req);
            String vectorSource = "VECTOR";

            // [FIX-D2] Web 실패/empty 시 Vector 2배 확장 fallback
            if (webAttempted && (webEmpty || !webSuccess)) {
                int expandedK = positive(req.vectorTopK) ? vectorK : Math.max(vectorK * 2, 10);
                vectorK = expandedK;
                vectorSource = "VECTOR-FALLBACK";
                com.example.lms.search.RequestTrace.emit("leg.vector", "expand",
                        "reason=" + (webEmpty ? "web_empty" : "web_unsuccessful") + ";k=" + expandedK);
                dbg.put("stage.vector.fallback", "triggered"); TraceStore.put("vectorFallback.used", true); TraceStore.put("retrieval.vectorFallback.used", true); TraceStore.put("retrieval.vectorFallback.reason", webEmpty ? "web_empty" : "web_unsuccessful"); TraceStore.put("retrieval.vectorFallback.effectiveTopK", expandedK); TraceStore.put("retrieval.vectorFallback.queryHash12", com.example.lms.trace.SafeRedactor.hash12(req.query)); TraceStore.put("retrieval.vectorFallback.queryLength", req.query == null ? 0 : req.query.length());
                log.info("[Orchestrator] Web search empty, triggering vector fallback with expanded topK={}", expandedK);
            }

            if (!webSuccess) {
                // 웹 검색 실패 시: 엄격 모드 (Score >= 0.8 가정)
                // 오염된 저품질 데이터 유입 차단
                dbg.put("stage.vector.mode", "strict_fallback");
            } else {
                // 웹 검색 성공 시: 보조 모드
                dbg.put("stage.vector.mode", "augment");
            }
            stageStartedNs = System.nanoTime();
            java.util.List<Doc> vectorDocs = java.util.List.of();
            boolean vectorFailed = false;
            try {
                markDependency("vector", "ready", vectorRequired, true, false, "", null, dbg);
                com.example.lms.search.RequestTrace.emit("leg.vector", "call",
                        "k=" + vectorK + ";src=" + vectorSource);
                vectorDocs = toDocs(vectorLeaf(),
                        com.example.lms.service.rag.QueryUtils.buildQuery(req.query, null, null, java.util.Map.of("vectorTopK", vectorK)),
                        vectorK, vectorSource);
                com.example.lms.search.RequestTrace.emit("leg.vector", "result",
                        "count=" + vectorDocs.size());
            } catch (Exception e) {
                com.example.lms.search.RequestTrace.emit("leg.vector", "error",
                        "type=" + e.getClass().getSimpleName());
                vectorFailed = true;
                log.warn("[AWX][rag][orchestrator] vector retrieval failed failureReason={} errorType={} queryHash12={} queryLength={}", "vector-retrieval-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.hash12(req.query), req.query == null ? 0 : req.query.length());
                markDependency("vector", "failed", vectorRequired, true, true, classifyDependencyFailure(e), e, dbg);
                dbg.put("stage.vector", "failed:vector_retrieval_failed");
            } finally {
                recordStageMs(stageMs, "vector", stageStartedNs);
            }
            if (trace != null) {
                trace.vector = snapshotDocs(vectorDocs);
            }
            if (vectorFailed) {
                // stage/debug already records the dependency failure above.
            } else if (vectorDocs.isEmpty()) {
                dbg.put("stage.vector", "empty_result");
            } else {
                dbg.put("stage.vector", "success:" + vectorDocs.size());
                pool.addAll(vectorDocs);
            }
        }

        // 3) KG & BM25 (Supplementary)
        if (!req.useKg) {
            markDependency("kg", "disabled", kgRequired, false, false, "disabled_by_config", null, dbg);
            dbg.put("stage.kg", "disabled");
            com.example.lms.search.RequestTrace.emit("axis.kg", "state", "state=disabled_by_config");
        } else if (kgRetriever == null) {
            IllegalStateException missing = new IllegalStateException("kg retriever bean missing");
            markDependency("kg", "missing_bean", kgRequired, true, true, "missing-dependency", missing, dbg);
            dbg.put("stage.kg", "missing_kgRetriever");
            com.example.lms.search.RequestTrace.emit("axis.kg", "state", "state=missing_bean");
        } else {
            markDependency("kg", "ready", kgRequired, false, false, "", null, dbg);
            com.example.lms.search.RequestTrace.emit("axis.kg", "state", "state=ready");
        }
        if (req.useKg && kgRetriever != null) {
            stageStartedNs = System.nanoTime();
            java.util.List<Doc> kgDocs = java.util.List.of();
            boolean kgFailed = false;
            try {
                markDependency("kg", "ready", kgRequired, true, false, "", null, dbg);
                int kgPrefetchK = effectiveKgTopK(req);
                dbg.put("retrieval.kg.relationThumbnail.prefetchK", kgPrefetchK);
                TraceStore.put("retrieval.kg.relationThumbnail.prefetchK", kgPrefetchK);
                com.example.lms.search.RequestTrace.emit("leg.kg", "call", "k=" + kgPrefetchK);
                kgDocs = toDocs(kgRetriever, req.query, kgPrefetchK, "KG");
                kgDocs = rerankKgRelationThumbnails(kgDocs, req.query, Math.max(1, req.topK), dbg);
                com.example.lms.search.RequestTrace.emit("leg.kg", "result", "count=" + kgDocs.size());
            } catch (Exception e) {
                com.example.lms.search.RequestTrace.emit("leg.kg", "error",
                        "type=" + e.getClass().getSimpleName());
                kgFailed = true;
                log.warn("[AWX][rag][orchestrator] kg retrieval failed failureReason={} errorType={} queryHash12={} queryLength={}", "kg-retrieval-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.hash12(req.query), req.query == null ? 0 : req.query.length());
                markDependency("kg", "failed", kgRequired, true, true, classifyDependencyFailure(e), e, dbg);
                dbg.put("stage.kg", "failed:kg_retrieval_failed");
            } finally {
                recordStageMs(stageMs, "kg", stageStartedNs);
            }
            if (trace != null) {
                trace.kg = snapshotDocs(kgDocs);
            }
            if (kgFailed) {
                // stage/debug already records the dependency failure above.
            } else if (kgDocs.isEmpty()) {
                dbg.put("stage.kg", "empty");
            } else {
                dbg.put("stage.kg", "success:" + kgDocs.size());
                pool.addAll(kgDocs);
            }
        }
        if (!req.useBm25) {
            com.example.lms.search.RequestTrace.emit("axis.bm25", "state", "state=disabled_by_config");
        } else if (bm25Index == null) {
            dbg.putIfAbsent("stage.bm25", "missing_bm25Index");
            com.example.lms.search.RequestTrace.emit("axis.bm25", "state", "state=missing_bean");
        }
        if (req.useBm25 && bm25Index != null) {
            stageStartedNs = System.nanoTime();
            com.example.lms.search.RequestTrace.emit("leg.bm25", "call", "k=" + req.topK);
            java.util.List<Doc> bm25Docs = toDocsOrEmpty(bm25Index, req.query, req.topK, "BM25");
            com.example.lms.search.RequestTrace.emit("leg.bm25", "result", "count=" + bm25Docs.size());
            recordStageMs(stageMs, "bm25", stageStartedNs);
            if (trace != null) {
                trace.bm25 = snapshotDocs(bm25Docs);
            }
            if (bm25Docs.isEmpty()) {
                dbg.put("stage.bm25", "empty");
            } else {
                dbg.put("stage.bm25", "ok:" + bm25Docs.size());
                pool.addAll(bm25Docs);
            }
        }

        // 4) SelfAsk sub-query legs (planner-produced): bounded local axes
        // only (pure vector leaf + BM25 — never web, so expansion cannot
        // multiply paid calls). Each leg honors the same request-budget gate
        // as the emergency retry.
        if (req.selfAskSubQueries != null && !req.selfAskSubQueries.isEmpty()) {
            com.abandonware.ai.addons.budget.TimeBudget selfAskLegBudget =
                    com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            int selfAskK = Math.max(2, Math.min(req.topK, SELFASK_LEG_TOPK));
            int executedLegs = 0;
            for (String subQuery : req.selfAskSubQueries) {
                if (executedLegs >= SELFASK_MAX_SUBQUERIES) {
                    break;
                }
                if (subQuery == null || subQuery.isBlank()) {
                    continue;
                }
                if (selfAskLegBudget != null && selfAskLegBudget.expired()) {
                    com.example.lms.search.DeadlineProbe.skip("leg.selfask", "request_budget_exhausted");
                    com.example.lms.search.RequestTrace.emit("leg.selfask", "skip",
                            "reason=request_budget_exhausted;executed=" + executedLegs);
                    break;
                }
                executedLegs++;
                if (req.useVector && vectorLeaf() != null) {
                    stageStartedNs = System.nanoTime();
                    java.util.List<Doc> selfAskDocs =
                            toDocsOrEmpty(vectorLeaf(), subQuery, selfAskK, "SELFASK-VECTOR");
                    for (Doc d : selfAskDocs) {
                        if (d.meta == null) {
                            d.meta = new java.util.HashMap<>();
                        }
                        d.meta.put("_selfask", true);
                    }
                    recordStageMs(stageMs, "selfask.vector", stageStartedNs);
                    com.example.lms.search.RequestTrace.emit("leg.selfask.vector", "result",
                            "count=" + selfAskDocs.size() + ";lane=" + executedLegs);
                    pool.addAll(selfAskDocs);
                }
                if (req.useBm25 && bm25Index != null) {
                    stageStartedNs = System.nanoTime();
                    java.util.List<Doc> selfAskDocs =
                            toDocsOrEmpty(bm25Index, subQuery, selfAskK, "SELFASK-BM25");
                    for (Doc d : selfAskDocs) {
                        if (d.meta == null) {
                            d.meta = new java.util.HashMap<>();
                        }
                        d.meta.put("_selfask", true);
                    }
                    recordStageMs(stageMs, "selfask.bm25", stageStartedNs);
                    com.example.lms.search.RequestTrace.emit("leg.selfask.bm25", "result",
                            "count=" + selfAskDocs.size() + ";lane=" + executedLegs);
                    pool.addAll(selfAskDocs);
                }
            }
            dbg.put("stage.selfask.legs", executedLegs);
        }

        return pool;
    }

    private List<Doc> fuseRrf(List<Doc> pool, int k, QueryRequest req) {
        requireNonNegativeTopK("topK", k);
        if (pool == null || pool.isEmpty() || k == 0)
            return List.of();
        Map<Doc, String> stableKeys = new IdentityHashMap<>();
        List<Doc> eligible = new ArrayList<>();
        int droppedNoKey = 0;
        for (Doc doc : pool) {
            String stableKey = stableDocumentKey(doc);
            if (stableKey == null || stableKey.isBlank()) {
                droppedNoKey++;
                continue;
            }
            stableKeys.put(doc, stableKey);
            eligible.add(doc);
        }
        if (eligible.isEmpty()) {
            return List.of();
        }
        // RRF weights/constant are configured via RagProperties (rag.rrf.*).
        // No runtime if-else branching by memoryProfile/aggressive here (reproducibility).
        double wWeb = 1.0;
        double wVector = 0.8;
        double wBm25 = 0.9;
        double wKg = 0.7;
        int k0 = 60;

        try {
            if (ragProperties != null && ragProperties.getRrf() != null) {
                k0 = ragProperties.getRrf().getConstant();
                if (ragProperties.getRrf().getWeight() != null) {
                    wWeb = ragProperties.getRrf().getWeight().getWeb();
                    wVector = ragProperties.getRrf().getWeight().getVector();
                    wBm25 = ragProperties.getRrf().getWeight().getBm25();
                    wKg = ragProperties.getRrf().getWeight().getKg();
                }
            }
        } catch (Exception ignored) {
            TraceStore.put("rag.orchestrator.suppressed.rrf.defaults", true);
        }



        // [FUTURE_TECH FIX] If web results are abundant, down-weight Vector/BM25 to protect
        // the latest info from being overridden by stale embeddings.
        try {
            if (ragProperties != null && ragProperties.getRrf() != null) {
                int threshold = ragProperties.getRrf().getWebRichThreshold();
                long webCount = eligible.stream()
                        .filter(d -> d.source != null && "WEB".equalsIgnoreCase(d.source))
                        .count();
                if (webCount >= threshold) {
                    wVector = ragProperties.getRrf().getVectorWeightWhenWebRich();
                    wBm25 = ragProperties.getRrf().getBm25WeightWhenWebRich();
                    wWeb = ragProperties.getRrf().getWebWeightWhenWebRich();
                    log.debug("[RRF] Web-rich mode enabled: webCount={}, wWeb={}, wVector={}, wBm25={}",
                            webCount, wWeb, wVector, wBm25);
                }
            }
        } catch (Exception ignored) {
            TraceStore.put("rag.orchestrator.suppressed.rrf.webRich", true);
        }
        // 1) Per-list ordinal rank + cross-list contribution aggregation.
        //    Rank is the document's declared rank when one was recorded,
        //    else its ordinal list position (never inferred from score).
        //    The same stable key across different
        //    source lists sums contributions and is returned once; a repeated
        //    key inside one list consumes its rank slot but adds no extra vote.
        //    Seed-injected lists (_seed) stay distinct from live legs so a live
        //    WEB leg is not pushed down by seed WEB entries.
        // 0.5) Content-equality fold: entries whose stable key is only
        //    namespace-local (stableId TAG:value / TAG-index, source:, or
        //    content: keys) but whose normalized title+snippet are identical
        //    are the same evidence and must aggregate once. An asserted key
        //    (caller id or url) is the preferred fold target; two different
        //    asserted identities sharing content are contested and stay split.
        Map<String, String> foldTargetByContent = new HashMap<>();
        Map<String, Boolean> foldTargetAsserted = new HashMap<>();
        Map<Doc, String> contentKeys = new IdentityHashMap<>();
        for (Doc d : eligible) {
            String contentKey = contentDocumentKey(d);
            contentKeys.put(d, contentKey);
            if (contentKey == null) {
                continue;
            }
            String stableKey = stableKeys.get(d);
            boolean asserted = hasAssertedIdentity(d, stableKey);
            if (!foldTargetAsserted.containsKey(contentKey)) {
                foldTargetByContent.put(contentKey, stableKey);
                foldTargetAsserted.put(contentKey, asserted);
            } else if (asserted) {
                String currentTarget = foldTargetByContent.get(contentKey);
                if (Boolean.TRUE.equals(foldTargetAsserted.get(contentKey))) {
                    if (!stableKey.equals(currentTarget)) {
                        foldTargetByContent.put(contentKey, null);
                    }
                } else {
                    foldTargetByContent.put(contentKey, stableKey);
                    foldTargetAsserted.put(contentKey, true);
                }
            }
        }

        Map<String, Double> keyScores = new HashMap<>();
        Map<String, Doc> keyRepresentative = new HashMap<>();
        Map<String, Double> keyBestContribution = new HashMap<>();
        Map<String, Integer> perListSeq = new HashMap<>();
        Set<String> seenInList = new HashSet<>();
        Map<Doc, String> repKeys = new IdentityHashMap<>();
        Map<Doc, Integer> repDeclRank = new IdentityHashMap<>();
        Map<Doc, Integer> repRankUsed = new IdentityHashMap<>();
        int contentFolded = 0;
        Map<String, LinkedHashSet<String>> keySources = new HashMap<>();
        Map<String, Integer> keyEntries = new HashMap<>();
        Map<String, LinkedHashSet<String>> keySnippets = new HashMap<>();
        for (Doc d : eligible) {
            String src = d.source != null ? d.source.toUpperCase(Locale.ROOT) : "";
            boolean seededDoc = d.meta != null && Boolean.TRUE.equals(d.meta.get("_seed"));
            String listKey = src + (seededDoc ? "seed" : "live");
            int position = perListSeq.merge(listKey, 1, Integer::sum);
            int rank = d.rank > 0 ? d.rank : position;
            String stableKey = stableKeys.get(d);
            if (!hasAssertedIdentity(d, stableKey)) {
                String folded = foldTargetByContent.get(contentKeys.get(d));
                if (folded != null) {
                    stableKey = folded;
                    contentFolded++;
                }
            }
            if (!seenInList.add(listKey + " " + stableKey)) {
                continue;
            }
            double w = switch (src) {
                case "WEB" -> wWeb;
                case "VECTOR", "VECTOR-FALLBACK", "VECTOR-EMERGENCY", "VECTOR-OD", "SELFASK-VECTOR" -> wVector;
                case "BM25", "SELFASK-BM25" -> wBm25;
                case "KG" -> wKg;
                default -> 0.8;
            };
            double contribution = w / (k0 + rank);
            keyScores.merge(stableKey, contribution, Double::sum);
            keySources.computeIfAbsent(stableKey, kk -> new LinkedHashSet<>())
                    .add(src.isBlank() ? "UNKNOWN" : src);
            keyEntries.merge(stableKey, 1, Integer::sum);
            if (d.snippet != null && !d.snippet.isBlank()) {
                keySnippets.computeIfAbsent(stableKey, kk -> new LinkedHashSet<>()).add(d.snippet);
            }
            if (contribution > keyBestContribution.getOrDefault(stableKey, -1.0d)) {
                keyBestContribution.put(stableKey, contribution);
                keyRepresentative.put(stableKey, d);
                repKeys.put(d, stableKey);
                repDeclRank.put(d, d.rank);
                repRankUsed.put(d, rank);
            }
        }
        if (keyRepresentative.isEmpty()) {
            return List.of();
        }

        // 2) Aggregate RRF score sort (representative doc per stable key)
        List<Doc> sorted = new ArrayList<>(keyRepresentative.values());
        Map<Doc, Double> rrfScores = new HashMap<>();
        for (Map.Entry<String, Doc> e : keyRepresentative.entrySet()) {
            rrfScores.put(e.getValue(), keyScores.getOrDefault(e.getKey(), 0.0d));
        }
        sorted.sort(Comparator
                .<Doc>comparingDouble(doc -> rrfScores.getOrDefault(doc, 0.0d))
                .reversed()
                .thenComparing(repKeys::get));
        recordRrfStableTies(sorted, rrfScores, repKeys);

        // 2.5) Provenance: a merged representative records which sources
        //    contributed, how many entries folded in, and merged-away chunk
        //    evidence, so document-level aggregation does not silently drop
        //    the losing contribution's provenance.
        for (Map.Entry<String, Doc> e : keyRepresentative.entrySet()) {
            Doc rep = e.getValue();
            if (rep == null) {
                continue;
            }
            int merged = keyEntries.getOrDefault(e.getKey(), 0);
            LinkedHashSet<String> contributing = keySources.get(e.getKey());
            if (merged <= 1 && (contributing == null || contributing.size() <= 1)) {
                continue;
            }
            if (rep.meta == null) {
                rep.meta = new HashMap<>();
            }
            if (merged > 1) {
                rep.meta.put("rrfMerged", merged);
            }
            if (contributing != null && contributing.size() > 1) {
                rep.meta.put("rrfSources", new ArrayList<>(contributing));
            }
            LinkedHashSet<String> snips = keySnippets.get(e.getKey());
            if (snips != null) {
                List<String> alt = new ArrayList<>();
                for (String s : snips) {
                    if (!s.equals(rep.snippet)) {
                        alt.add(s);
                    }
                    if (alt.size() >= 2) {
                        break;
                    }
                }
                if (!alt.isEmpty()) {
                    rep.meta.put("rrfAltSnippets", alt);
                }
            }
        }

        // 3) 소스 다양성 유지: soft cap은 선호 순서를 비추되, 적격 후보가 남아
        //    있으면 top-up으로 채워 k(=candidate window)까지 반환한다.
        Map<String, Integer> srcCount = new HashMap<>();
        List<Doc> out = new ArrayList<>();
        List<Doc> deferred = new ArrayList<>();
        int cap = Math.max(3, (int) (k * 0.75));
        for (Doc d : sorted) {
            String src = d.source != null ? d.source : "UNKNOWN";
            int c = srcCount.getOrDefault(src, 0);
            if (c >= cap) {
                deferred.add(d);
                continue;
            }
            srcCount.put(src, c + 1);
            out.add(d);
            if (out.size() >= k)
                break;
        }
        int topupCount = 0;
        for (Doc d : deferred) {
            if (out.size() >= k)
                break;
            out.add(d);
            topupCount++;
        }
        for (int i = 0; i < out.size(); i++) {
            out.get(i).rank = i + 1;
        }
        // intra-list dedup skips = eligible minus accepted contributions
        // (keyEntries only counts entries that passed the seenInList gate);
        // computed rather than counted inline because the dedup line itself
        // is left untouched.
        int intraDup = eligible.size()
                - keyEntries.values().stream().mapToInt(Integer::intValue).sum();
        com.example.lms.search.RequestTrace.emit("rrf", "stages",
                "in=" + pool.size() + ";eligible=" + eligible.size()
                        + ";no_key=" + droppedNoKey + ";content_fold=" + contentFolded
                        + ";intra_dup=" + intraDup + ";keys=" + keyRepresentative.size()
                        + ";window=" + out.size() + ";deferred=" + deferred.size()
                        + ";topup=" + topupCount);
        if (com.example.lms.search.RequestTrace.docsEnabled()) {
            Map<Doc, Integer> poolIndex = new IdentityHashMap<>();
            for (int i = 0; i < pool.size(); i++) {
                poolIndex.putIfAbsent(pool.get(i), i);
            }
            for (int i = 0; i < out.size(); i++) {
                Doc d = out.get(i);
                String key = repKeys.get(d);
                com.example.lms.search.RequestTrace.emitDoc("rrf.doc",
                        "i=" + i
                                + ";poolIdx=" + poolIndex.getOrDefault(d, -1)
                                + ";src=" + (d.source == null ? "?" : d.source)
                                + ";key=" + com.example.lms.trace.SafeRedactor
                                        .hash12(key == null ? "" : key)
                                + ";declRank=" + repDeclRank.getOrDefault(d, 0)
                                + ";rankUsed=" + repRankUsed.getOrDefault(d, -1)
                                + ";merged=" + keyEntries.getOrDefault(key, 0)
                                + ";prov=" + keySources
                                        .getOrDefault(key, new LinkedHashSet<>()).size());
            }
        }
        return OrchestratorHypernovaFusionBridge.apply(out, rrfScores, novaNextFusionService);
    }

    private static void recordRrfStableTies(
            List<Doc> sorted,
            Map<Doc, Double> rrfScores,
            Map<Doc, String> stableKeys) {
        SelectionDecisionLedger ledger =
                GuardContextHolder.getOrDefault().selectionDecisionLedger();
        int groupStart = 0;
        long tieOrdinal = 0L;
        while (groupStart < sorted.size()) {
            double groupScore = rrfScores.getOrDefault(sorted.get(groupStart), 0.0d);
            int groupEnd = groupStart + 1;
            while (groupEnd < sorted.size()
                    && Double.compare(
                            groupScore,
                            rrfScores.getOrDefault(sorted.get(groupEnd), 0.0d)) == 0) {
                groupEnd++;
            }
            if (groupEnd - groupStart > 1) {
                List<String> groupKeys = sorted.subList(groupStart, groupEnd).stream()
                        .map(stableKeys::get)
                        .toList();
                ledger.record(
                        SelectionDecisionLedger.Lane.RANKING,
                        new SelectionCoordinate(
                                "ranking.rrf.tie", "rag:rrf", 0L, tieOrdinal),
                        groupKeys,
                        0,
                        "exact_score_stable_key",
                        false,
                        true);
                tieOrdinal++;
            }
            groupStart = groupEnd;
        }
    }

    private List<Doc> topK(List<Doc> L, int k) {
        if (L.size() <= k)
            return L;
        return new ArrayList<>(L.subList(0, k));
    }

    private static List<Doc> finalTopK(List<Doc> docs, int topK) {
        requireNonNegativeTopK("topK", topK);
        if (docs == null || docs.isEmpty() || topK == 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(docs.subList(0, Math.min(topK, docs.size())));
    }

    private static List<Content> toOnnxContents(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Content> out = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            if (doc == null) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            if (doc.meta != null) {
                meta.putAll(doc.meta);
            }
            meta.put(ONNX_DOC_INDEX_META, i);
            putIfPresent(meta, "doc_id", doc.id);
            putIfPresent(meta, "title", doc.title);
            putIfPresent(meta, "source", doc.source);
            if (Double.isFinite(doc.score)) {
                meta.putIfAbsent("score", doc.score);
            }
            out.add(Content.from(TextSegment.from(docText(doc), Metadata.from(langchainMetadata(meta)))));
        }
        return out;
    }

    private static List<Doc> docsInOnnxOrder(List<Content> reranked, List<Doc> originalDocs, int topN) {
        if (originalDocs == null || originalDocs.isEmpty()) {
            return List.of();
        }
        int limit = topN <= 0 ? originalDocs.size() : Math.min(topN, originalDocs.size());
        if (limit <= 0) {
            return List.of();
        }
        List<Doc> out = new ArrayList<>(limit);
        boolean[] used = new boolean[originalDocs.size()];
        if (reranked != null) {
            for (Content content : reranked) {
                Integer index = onnxDocIndex(content);
                if (index == null || index < 0 || index >= originalDocs.size() || used[index]) {
                    continue;
                }
                Doc doc = originalDocs.get(index);
                if (doc == null) {
                    continue;
                }
                used[index] = true;
                out.add(doc);
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        for (int i = 0; i < originalDocs.size() && out.size() < limit; i++) {
            if (!used[i] && originalDocs.get(i) != null) {
                out.add(originalDocs.get(i));
            }
        }
        return out;
    }

    private static Integer onnxDocIndex(Content content) {
        if (content == null) {
            return null;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        copyMetadata(content.metadata(), meta);
        if (content.textSegment() != null) {
            copyMetadata(content.textSegment().metadata(), meta);
        }
        try {
            return OrchestratorTraceNumbers.parseNullableInt(meta.get(ONNX_DOC_INDEX_META), "onnxDocIndex");
        } catch (NumberFormatException ignored) {
            INVALID_NUMBER_SUPPRESSOR.accept("onnxDocIndex");
            return null;
        }
    }

    private static void putIfPresent(Map<String, Object> meta, String key, String value) {
        if (meta != null && key != null && value != null && !value.isBlank()) {
            meta.putIfAbsent(key, value);
        }
    }

    private static Map<String, Object> langchainMetadata(Map<String, Object> meta) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (meta == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String
                    || value instanceof UUID
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof Double) {
                out.put(key, value);
            } else {
                out.put(key, String.valueOf(value));
            }
        }
        return out;
    }

    private static String docText(Doc doc) {
        if (doc == null) {
            return "";
        }
        String title = doc.title == null ? "" : doc.title;
        String snippet = doc.snippet == null ? "" : doc.snippet;
        String text = (title + " " + snippet).trim();
        return text.isBlank() ? String.valueOf(doc.id) : text;
    }

    private static String stableDocumentKey(Doc doc) {
        if (doc == null) {
            return null;
        }
        if (doc.id != null && !doc.id.isBlank()) {
            return "id:" + doc.id.trim();
        }
        Object urlValue = firstNonBlank(doc.meta,
                "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical", "permalink");
        String normalizedUrl = normalizeStableUrl(urlValue == null ? null : String.valueOf(urlValue));
        if (normalizedUrl != null) {
            return "url:" + normalizedUrl;
        }
        Object sourceIdValue = firstNonBlank(doc.meta,
                "sourceId", "source_id", "documentId", "docId");
        String sourceId = normalizeStableText(
                sourceIdValue == null ? null : String.valueOf(sourceIdValue));
        if (!sourceId.isBlank()) {
            String source = normalizeStableText(doc.source);
            return source.isBlank()
                    ? "source:" + sourceId
                    : "source:" + source + ":" + sourceId;
        }

        return contentDocumentKey(doc);
    }

    /**
     * Content-derived identity: framed SHA-256 over normalized title+snippet.
     * Used as the lowest-priority stable key AND as a secondary fold signal for
     * entries whose primary key is only namespace-local (see fuseRrf).
     */
    private static String contentDocumentKey(Doc doc) {
        if (doc == null) {
            return null;
        }
        String title = normalizeStableText(doc.title);
        String snippet = normalizeStableText(doc.snippet);
        if (title.isBlank() && snippet.isBlank()) {
            return null;
        }
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_8);
        byte[] snippetBytes = snippet.getBytes(StandardCharsets.UTF_8);
        ByteBuffer framed = ByteBuffer.allocate(8 + titleBytes.length + snippetBytes.length);
        framed.putInt(titleBytes.length).put(titleBytes);
        framed.putInt(snippetBytes.length).put(snippetBytes);
        return "content:" + DigestUtils.sha256Hex(framed.array());
    }

    /**
     * Whether a doc's stable key is caller/url-asserted identity (explicit id or
     * URL), as opposed to a namespace-local id assigned by stableId
     * ({@code TAG:value} / {@code TAG-index}) or a content/source-derived key.
     * Only non-asserted keys are eligible for the content-equality fold.
     */
    private static boolean hasAssertedIdentity(Doc doc, String stableKey) {
        if (doc == null || stableKey == null) {
            return false;
        }
        if (stableKey.startsWith("url:")) {
            return true;
        }
        if (!stableKey.startsWith("id:")) {
            return false;
        }
        String id = doc.id == null ? "" : doc.id.trim();
        String src = doc.source == null ? "" : doc.source.trim();
        if (src.isBlank()) {
            return true;
        }
        return !(id.startsWith(src + ":")
                || id.matches("^" + java.util.regex.Pattern.quote(src) + "-\\d+$"));
    }

    static String stableDocumentKeyForTest(Doc doc) {
        return stableDocumentKey(doc);
    }

    private static String normalizeStableUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI normalized = new URI(rawUrl.trim()).normalize();
            String scheme = normalized.getScheme();
            String host = normalized.getHost();
            if (scheme == null || host == null || normalized.isOpaque()) {
                return null;
            }
            StringBuilder canonical = new StringBuilder()
                    .append(scheme.toLowerCase(Locale.ROOT))
                    .append("://");
            if (normalized.getRawUserInfo() != null) {
                canonical.append(normalized.getRawUserInfo()).append('@');
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (lowerHost.indexOf(':') >= 0 && !lowerHost.startsWith("[")) {
                canonical.append('[').append(lowerHost).append(']');
            } else {
                canonical.append(lowerHost);
            }
            if (normalized.getPort() >= 0) {
                canonical.append(':').append(normalized.getPort());
            }
            if (normalized.getRawPath() != null) {
                canonical.append(normalized.getRawPath());
            }
            if (normalized.getRawQuery() != null) {
                canonical.append('?').append(normalized.getRawQuery());
            }
            return new URI(canonical.toString()).toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeStableText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static double docRelevance(Doc doc) {
        if (doc == null) {
            return 0.0d;
        }
        if (Double.isFinite(doc.score) && doc.score > 0.0d) {
            return clamp01(doc.score);
        }
        if (doc.rank > 0) {
            return clamp01(1.0d / doc.rank);
        }
        return 0.5d;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }


    /**
     * Doc.id를 안정적으로 구성하기 위한 헬퍼.
     * - Web/Vector 결과는 메타에 url이 있는 경우가 많으므로 url을 우선 id로 사용한다.
     * - url이 없으면 meta에 존재하는 식별자 키(id/docId/...)를 사용한다.
     * - 아무 것도 없으면 sourceTag-index 형태로 폴백한다.
     *
     * Soak/Probe에서 '결과가 바뀌었는지'를 추적하려면 id가 매 요청마다 변하면 안 된다.
     */
    private static String stableId(String sourceTag, java.util.Map<String, Object> meta, int fallbackIndex) {
        if (meta != null && !meta.isEmpty()) {
            Object url = firstNonBlank(meta,
                    "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical", "permalink");
            if (url != null) {
                String u = String.valueOf(url).trim();
                if (!u.isBlank()) {
                    return u;
                }
            }
            Object id = firstNonBlank(meta,
                    "id", "docId", "documentId", "document_id", "sourceId", "source_id", "uuid", "hash");
            if (id != null) {
                String v = String.valueOf(id).trim();
                if (!v.isBlank()) {
                    return sourceTag + ":" + v;
                }
            }
        }
        return sourceTag + "-" + fallbackIndex;
    }

    private static Object firstNonBlank(java.util.Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null) continue;
            Object v = meta.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) {
                return v;
            }
        }
        // fallback: case-insensitive scan
        for (java.util.Map.Entry<String, Object> e : meta.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String key = e.getKey();
            for (String k : keys) {
                if (k != null && key.equalsIgnoreCase(k)) {
                    String s = String.valueOf(e.getValue()).trim();
                    if (!s.isBlank()) {
                        return e.getValue();
                    }
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safeHash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return DigestUtils.sha256Hex(value).substring(0, 12);
    }

    private static String dominantReason(List<Map<String, Object>> thresholdBreaks,
                                         Map<String, Object> bottleneck,
                                         boolean emptyResult) {
        if (emptyResult) {
            return "zero_result";
        }
        String label = "";
        double severity = -1.0d;
        if (thresholdBreaks != null) {
            for (Map<String, Object> row : thresholdBreaks) {
                if (row == null) {
                    continue;
                }
                String candidate = String.valueOf(row.getOrDefault("label", ""));
                double rowSeverity = toDouble(row.get("severity"), 0.0d);
                if (!candidate.isBlank() && rowSeverity >= severity) {
                    label = candidate;
                    severity = rowSeverity;
                }
            }
        }
        if (label.isBlank() && bottleneck != null) {
            label = String.valueOf(bottleneck.getOrDefault("label", ""));
        }
        return "none".equalsIgnoreCase(label) ? "" : label;
    }

    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (keyValues == null) {
            return out;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key == null) {
                continue;
            }
            Object value = keyValues[i + 1];
            if (value != null) {
                out.put(String.valueOf(key), value);
            }
        }
        return out;
    }

    private static void emitRagPipelineEvent(
            String phase,
            String stage,
            String step,
            String component,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            Map<String, Object> failure,
            Map<String, Object> control) {
        try {
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    phase,
                    stage,
                    step,
                    component,
                    status,
                    input,
                    output,
                    failure,
                    control);
        } catch (Throwable ignore) {
            log.debug("[AWX][rag][orchestrator] suppressed stage=ragPipelineEvent err=trace-failure");
        }
    }



    private List<Doc> rerankKgRelationThumbnails(List<Doc> kgDocs,
                                                 String query,
                                                 int requestedTopK,
                                                 Map<String, Object> dbg) {
        int inputCount = kgDocs == null ? 0 : kgDocs.size();
        if (inputCount <= 0) {
            recordKgRelationThumbnailTrace(dbg, "empty", 0, 0, query, "", requestedTopK);
            return List.of();
        }

        Set<String> queryTokens = relationThumbnailTokens(query, 24);
        Set<String> anchorSeedHashes = relationThumbnailAnchorSeedHashes();
        if (queryTokens.isEmpty() && anchorSeedHashes.isEmpty()) {
            List<Doc> retained = new ArrayList<>();
            int candidateCount = 0;
            int droppedCount = 0;
            for (Doc doc : kgDocs) {
                if (isRelationThumbnailCandidate(doc)) {
                    candidateCount++;
                    droppedCount++;
                    continue;
                }
                retained.add(doc);
            }
            recordKgRelationThumbnailTrace(dbg, "skipped:no_query_tokens", candidateCount, 0, query, "", requestedTopK);
            recordKgRelationThumbnailDropped(dbg, droppedCount);
            return retained;
        }

        Map<Doc, Integer> originalIndex = new IdentityHashMap<>();
        Map<Doc, Integer> overlaps = new IdentityHashMap<>();
        Map<Doc, Double> rerankScores = new IdentityHashMap<>();
        int candidateCount = 0;
        int selectedCount = 0;
        int minOverlap = queryTokens.size() >= 2 ? 2 : 1;
        String selectedReason = "";

        for (int i = 0; i < kgDocs.size(); i++) {
            Doc doc = kgDocs.get(i);
            if (doc == null) {
                continue;
            }
            originalIndex.put(doc, i);
            if (!isRelationThumbnailCandidate(doc)) {
                rerankScores.put(doc, doc.score);
                continue;
            }
            candidateCount++;
            int overlap = relationThumbnailOverlap(queryTokens, doc);
            boolean tokenSelected = overlap >= minOverlap;
            boolean anchorSeedSelected = !tokenSelected && relationThumbnailAnchorSeedMatch(anchorSeedHashes, doc);
            boolean selected = tokenSelected || anchorSeedSelected;
            String selectionReason = tokenSelected ? "token_overlap" : anchorSeedSelected ? "anchor_seed" : "none";
            if (doc.meta == null) {
                doc.meta = new HashMap<>();
            }
            doc.meta.put("kg_relation_thumbnail_overlap", overlap);
            doc.meta.put("kg_relation_thumbnail_selected", selected);
            doc.meta.put("kg_relation_thumbnail_selection_reason", selectionReason);
            if (selected) {
                selectedCount++;
                if (selectedReason.isBlank() || "anchor_seed".equals(selectionReason)) {
                    selectedReason = selectionReason;
                }
                applyRelationThumbnailPromptContext(doc);
            }
            overlaps.put(doc, overlap);
            double baseScore = Double.isFinite(doc.score) ? doc.score : 0.0d;
            rerankScores.put(doc, selected ? 10.0d + overlap + clamp01(baseScore) : clamp01(baseScore));
        }

        if (candidateCount <= 0) {
            recordKgRelationThumbnailTrace(dbg, "skipped:no_relation_thumbnail", inputCount, 0, query, "", requestedTopK);
            return kgDocs;
        }
        if (selectedCount <= 0) {
            List<Doc> retained = new ArrayList<>();
            int droppedCount = 0;
            for (Doc doc : kgDocs) {
                if (isRelationThumbnailCandidate(doc)) {
                    droppedCount++;
                    continue;
                }
                retained.add(doc);
            }
            recordKgRelationThumbnailTrace(dbg, "no_overlap", candidateCount, 0, query, "", requestedTopK);
            recordKgRelationThumbnailDropped(dbg, droppedCount);
            return retained;
        }

        List<Doc> sliced = new ArrayList<>(kgDocs.size());
        int droppedCount = 0;
        for (Doc doc : kgDocs) {
            if (isRelationThumbnailCandidate(doc)
                    && !Boolean.TRUE.equals(doc.meta.get("kg_relation_thumbnail_selected"))) {
                droppedCount++;
                continue;
            }
            sliced.add(doc);
        }

        List<Doc> sorted = new ArrayList<>(sliced);
        sorted.sort((a, b) -> {
            int aSelected = Boolean.TRUE.equals(a != null && a.meta != null
                    ? a.meta.get("kg_relation_thumbnail_selected")
                    : null) ? 1 : 0;
            int bSelected = Boolean.TRUE.equals(b != null && b.meta != null
                    ? b.meta.get("kg_relation_thumbnail_selected")
                    : null) ? 1 : 0;
            if (aSelected != bSelected) {
                return Integer.compare(bSelected, aSelected);
            }
            int overlapCompare = Integer.compare(overlaps.getOrDefault(b, 0), overlaps.getOrDefault(a, 0));
            if (overlapCompare != 0) {
                return overlapCompare;
            }
            int scoreCompare = Double.compare(rerankScores.getOrDefault(b, 0.0d), rerankScores.getOrDefault(a, 0.0d));
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return Integer.compare(originalIndex.getOrDefault(a, Integer.MAX_VALUE),
                    originalIndex.getOrDefault(b, Integer.MAX_VALUE));
        });
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).rank = i + 1;
        }
        String topHash = relationThumbnailTopHash(sorted);
        List<Map<String, Object>> sliceMap = relationThumbnailSliceMap(sorted);
        recordKgRelationThumbnailTrace(dbg, "applied", candidateCount, selectedCount, query, topHash, requestedTopK);
        recordKgRelationThumbnailDropped(dbg, droppedCount);
        recordKgRelationThumbnailSelectionReason(dbg, selectedReason);
        recordKgRelationThumbnailSliceMap(dbg, sliceMap);
        emitRagPipelineEvent(
                "rerank",
                "kg_relation_thumbnail",
                "complete",
                "UnifiedRagOrchestrator",
                "ok",
                mapOf("queryHash", safeHash(query), "requestedTopK", requestedTopK),
                mapOf("candidateCount", candidateCount, "selectedCount", selectedCount, "droppedCount", droppedCount,
                        "selectionReason", selectedReason, "topHash", topHash, "sliceMapCount", sliceMap.size()),
                Map.of(),
                Map.of("action", "relation_thumbnail_rerank", "applied", true));
        return sorted;
    }

    private static boolean isRelationThumbnailCandidate(Doc doc) {
        if (doc == null || doc.meta == null || doc.meta.isEmpty()) {
            return false;
        }
        Object mode = firstNonBlank(doc.meta, "kg_relation_thumbnail_mode", "kgRelationThumbnailMode");
        if (mode != null && "relation_thumbnail_v1".equalsIgnoreCase(String.valueOf(mode).trim())) {
            return true;
        }
        return firstNonBlank(doc.meta,
                "kg_relation_thumbnail_hash",
                "kgRelationThumbnailHash",
                "kg_relation_breadcrumb_hashes",
                "kgRelationBreadcrumbHashes",
                "kg_relation_anchor_hash12",
                "kgRelationAnchorHash12") != null;
    }

    private static void applyRelationThumbnailPromptContext(Doc doc) {
        if (doc == null) {
            return;
        }
        RelationThumbnailSlice slice = relationThumbnailPromptContext(doc);
        if (slice.text().isBlank()) {
            return;
        }
        if (doc.meta == null) {
            doc.meta = new HashMap<>();
        }
        doc.meta.put("kg_relation_prompt_context_mode", "relation_thumbnail_context_v1");
        doc.meta.put("kg_relation_prompt_context_layer", slice.layer());
        doc.meta.put("kg_relation_prompt_context_hash", safeHash(slice.text()));
        doc.snippet = "relationThumbnailContext: " + slice.text();
    }

    private static RelationThumbnailSlice relationThumbnailPromptContext(Doc doc) {
        if (doc == null) {
            return RelationThumbnailSlice.empty();
        }
        String breadcrumb = relationBreadcrumbLine(doc.snippet);
        if (breadcrumb.isBlank()) {
            breadcrumb = relationThumbnailContextLine(doc.snippet);
        }
        if (breadcrumb.isBlank() && doc.meta != null && !doc.meta.isEmpty()) {
            Object breadcrumbValue = firstNonBlank(
                    doc.meta,
                    "kg_relation_breadcrumb",
                    "kgRelationBreadcrumb");
            if (breadcrumbValue != null) {
                breadcrumb = clipRelationContext(String.valueOf(breadcrumbValue), 360);
            }
        }
        if (!breadcrumb.isBlank()) {
            return new RelationThumbnailSlice("breadcrumb", breadcrumb);
        }
        String summary = relationSummaryLine(doc.snippet);
        if (doc.meta != null && !doc.meta.isEmpty()) {
            Object summaryValue = firstNonBlank(
                    doc.meta,
                    "kg_relation_summary",
                    "kgRelationSummary");
            summary = firstNonBlank(
                    summary,
                    summaryValue == null ? "" : clipRelationContext(String.valueOf(summaryValue), 360));
        }
        if (!summary.isBlank()) {
            return new RelationThumbnailSlice("summary", summary);
        }
        String anchor = "";
        if (doc.meta != null && !doc.meta.isEmpty()) {
            Object anchorValue = firstNonBlank(
                    doc.meta,
                    "kg_relation_anchor",
                    "kgRelationAnchor",
                    "kg_entity",
                    "kgEntity",
                    "entity",
                    "entityName");
            if (anchorValue != null) {
                anchor = clipRelationContext(String.valueOf(anchorValue), 240);
            }
        }
        if (!anchor.isBlank()) {
            return new RelationThumbnailSlice("anchor", anchor);
        }
        String title = clipRelationContext(doc.title, 240);
        return title.isBlank() ? RelationThumbnailSlice.empty() : new RelationThumbnailSlice("title", title);
    }

    private record RelationThumbnailSlice(String layer, String text) {
        private RelationThumbnailSlice {
            layer = layer == null || layer.isBlank() ? "unknown" : layer;
            text = text == null ? "" : text;
        }

        private static RelationThumbnailSlice empty() {
            return new RelationThumbnailSlice("none", "");
        }
    }

    private static String relationBreadcrumbLine(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        for (String line : snippet.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.regionMatches(true, 0, "relationBreadcrumbs:", 0, "relationBreadcrumbs:".length())) {
                String value = trimmed.substring("relationBreadcrumbs:".length()).trim();
                return clipRelationContext(value, 360);
            }
        }
        return "";
    }

    private static void addContextPart(List<String> parts, Object value) {
        if (parts == null || value == null) {
            return;
        }
        String text = clipRelationContext(String.valueOf(value), 360);
        if (!text.isBlank()) {
            parts.add(text);
        }
    }

    private static String clipRelationContext(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String text = value.trim().replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ");
        if (text.isBlank()) {
            return "";
        }
        int limit = Math.max(80, Math.min(maxChars <= 0 ? 240 : maxChars, 600));
        return text.length() <= limit ? text : text.substring(0, limit).trim();
    }

    private static int relationThumbnailOverlap(Set<String> queryTokens, Doc doc) {
        if (queryTokens == null || queryTokens.isEmpty() || doc == null) {
            return 0;
        }
        Set<String> docTokens = relationThumbnailTokens(relationThumbnailText(doc), 128);
        int overlap = 0;
        for (String token : queryTokens) {
            if (docTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    private static Set<String> relationThumbnailAnchorSeedHashes() {
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        hashes.addAll(traceHashList("retrieval.kg.brainState.queryAnchorMap.seedHashes"));
        hashes.addAll(traceHashList("retrieval.kg.brainState.portMappingHashes"));
        hashes.addAll(traceHashList("rag.eval.kgAxis.sparseNodeQueryAnchorMapSeedHashes"));
        hashes.addAll(traceHashList("rag.eval.kgAxis.sparseNodePortMappingHashes"));
        return hashes.isEmpty() ? Set.of() : Set.copyOf(hashes);
    }

    private static boolean relationThumbnailAnchorSeedMatch(Set<String> seedHashes, Doc doc) {
        if (seedHashes == null || seedHashes.isEmpty() || doc == null || doc.meta == null || doc.meta.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> docHashes = new LinkedHashSet<>();
        addRelationThumbnailHashes(docHashes, firstNonBlank(doc.meta,
                "kg_relation_anchor_hash12",
                "kgRelationAnchorHash12",
                "kg_relation_thumbnail_hash",
                "kgRelationThumbnailHash",
                "kg_relation_breadcrumb_hashes",
                "kgRelationBreadcrumbHashes",
                "kg_query_anchor_map_seed_hashes",
                "kgQueryAnchorMapSeedHashes"));
        for (String hash : docHashes) {
            if (seedHashes.contains(hash)) {
                return true;
            }
        }
        return false;
    }

    private static void addRelationThumbnailHashes(Set<String> out, Object value) {
        if (out == null || value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addRelationThumbnailHashes(out, item);
            }
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return;
        }
        for (String part : text.split("[^A-Fa-f0-9]+")) {
            String hash = safeHash12(part);
            if (!hash.isBlank()) {
                out.add(hash);
            }
        }
    }

    private static String relationThumbnailText(Doc doc) {
        if (doc == null) {
            return "";
        }
        if (isRelationThumbnailCandidate(doc)) {
            String focused = relationThumbnailFocusedText(doc);
            if (!focused.isBlank()) {
                return focused;
            }
        }
        StringBuilder out = new StringBuilder();
        appendText(out, doc.title);
        appendText(out, doc.snippet);
        if (doc.meta != null && !doc.meta.isEmpty()) {
            appendText(out, firstNonBlank(doc.meta, "entity", "entityName", "kg_entity", "kgEntity"));
            appendText(out, firstNonBlank(doc.meta, "relation", "relationType", "kg_relation", "kgRelation"));
            appendText(out, firstNonBlank(doc.meta, "kg_relation_summary", "kgRelationSummary"));
            appendText(out, firstNonBlank(doc.meta, "kg_relation_breadcrumb", "kgRelationBreadcrumb"));
        }
        return out.toString();
    }

    private static String relationThumbnailFocusedText(Doc doc) {
        if (doc == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (doc.meta != null && !doc.meta.isEmpty()) {
            appendText(out, firstNonBlank(doc.meta, "kg_relation_breadcrumb", "kgRelationBreadcrumb"));
            appendText(out, firstNonBlank(doc.meta, "kg_relation_summary", "kgRelationSummary"));
            appendText(out, firstNonBlank(doc.meta,
                    "kg_relation_anchor",
                    "kgRelationAnchor",
                    "kg_entity",
                    "kgEntity",
                    "entity",
                    "entityName"));
            appendText(out, firstNonBlank(doc.meta,
                    "kg_relation_kind",
                    "kgRelationKind",
                    "kg_relation_type",
                    "kgRelationType",
                    "relationType",
                    "relation",
                    "kg_relation",
                    "kgRelation"));
        }
        appendText(out, relationBreadcrumbLine(doc.snippet));
        appendText(out, relationThumbnailContextLine(doc.snippet));
        appendText(out, relationSummaryLine(doc.snippet));
        return out.toString();
    }

    private static void appendText(StringBuilder out, Object value) {
        if (out == null || value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(text);
    }

    private static Set<String> relationThumbnailTokens(String text, int limit) {
        if (text == null || text.isBlank() || limit <= 0) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        StringBuilder buf = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                buf.appendCodePoint(Character.toLowerCase(cp));
            } else {
                flushRelationToken(out, buf, limit);
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        flushRelationToken(out, buf, limit);
        return out;
    }

    private static void flushRelationToken(Set<String> out, StringBuilder token, int limit) {
        if (out == null || token == null || out.size() >= limit) {
            if (token != null) {
                token.setLength(0);
            }
            return;
        }
        String value = token.toString().trim();
        token.setLength(0);
        if (value.length() < 3 || relationThumbnailStopToken(value)) {
            return;
        }
        out.add(value);
    }

    private static boolean relationThumbnailStopToken(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        return switch (token) {
            case "the", "and", "for", "with", "from", "into", "about", "this", "that",
                    "relation", "relations", "relationship", "relationships", "related",
                    "관계", "관련", "질문", "요약", "근거" -> true;
            default -> false;
        };
    }

    private static String relationThumbnailTopHash(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        for (Doc doc : docs) {
            if (doc == null || doc.meta == null) {
                continue;
            }
            if (!Boolean.TRUE.equals(doc.meta.get("kg_relation_thumbnail_selected"))) {
                continue;
            }
            return relationThumbnailMapHash(doc);
        }
        return "";
    }

    private static List<Map<String, Object>> relationThumbnailSliceMap(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Doc doc : docs) {
            if (doc == null || doc.meta == null || !Boolean.TRUE.equals(doc.meta.get("kg_relation_thumbnail_selected"))) {
                continue;
            }
            String hash = relationThumbnailMapHash(doc);
            if (hash.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", Math.max(0, doc.rank));
            row.put("hash", hash);
            row.put("relationKind", relationThumbnailRelationKind(doc));
            row.put("selectionReason", firstNonBlank(
                    safeDiagString(doc.meta.get("kg_relation_thumbnail_selection_reason")),
                    "unknown"));
            row.put("contextLayer", firstNonBlank(
                    safeDiagString(doc.meta.get("kg_relation_prompt_context_layer")),
                    "unknown"));
            row.put("overlap", relationThumbnailInt(doc.meta.get("kg_relation_thumbnail_overlap")));
            out.add(Map.copyOf(row));
            if (out.size() >= 8) {
                break;
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static Map<String, Integer> relationThumbnailContextLayerCountsFromTrace() {
        Object raw = TraceStore.get("retrieval.kg.relationThumbnail.sliceMap");
        if (!(raw instanceof Iterable<?> rows)) {
            return Map.of();
        }
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String layer = normalizedSignalLabel(String.valueOf(map.get("contextLayer")));
            if (layer.isBlank()) {
                continue;
            }
            counts.merge(layer, 1, Integer::sum);
            if (counts.size() >= 8) {
                break;
            }
        }
        return counts.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    private static String relationThumbnailMapHash(Doc doc) {
        if (doc == null || doc.meta == null || doc.meta.isEmpty()) {
            return "";
        }
        String hash = safeHash12(firstNonBlank(doc.meta,
                "kg_relation_thumbnail_hash",
                "kgRelationThumbnailHash",
                "kg_relation_breadcrumb_hashes",
                "kgRelationBreadcrumbHashes",
                "kg_relation_anchor_hash12",
                "kgRelationAnchorHash12"));
        return hash.isBlank() ? safeHash(doc.id) : hash;
    }

    private static String relationThumbnailRelationKind(Doc doc) {
        if (doc == null) {
            return "unknown";
        }
        if (doc.meta != null && !doc.meta.isEmpty()) {
            String kind = safeRelationKind(firstNonBlank(doc.meta,
                    "kg_relation_kind",
                    "kgRelationKind",
                    "kg_relation_type",
                    "kgRelationType",
                    "relationType"));
            if (!kind.isBlank()) {
                return kind;
            }
        }
        String breadcrumb = relationBreadcrumbLine(doc.snippet);
        if (breadcrumb.isBlank()) {
            breadcrumb = relationThumbnailContextLine(doc.snippet);
        }
        if (breadcrumb.isBlank() && doc.meta != null && !doc.meta.isEmpty()) {
            breadcrumb = clipRelationContext(String.valueOf(firstNonBlank(
                    doc.meta,
                    "kg_relation_breadcrumb",
                    "kgRelationBreadcrumb")), 360);
        }
        int start = breadcrumb.indexOf("--");
        int end = start < 0 ? -1 : breadcrumb.indexOf("-->", start + 2);
        if (start >= 0 && end > start) {
            String kind = safeRelationKind(breadcrumb.substring(start + 2, end));
            if (!kind.isBlank()) {
                return kind;
            }
        }
        return "unknown";
    }

    private static String relationThumbnailContextLine(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        for (String line : snippet.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.regionMatches(true, 0, "relationThumbnailContext:", 0,
                    "relationThumbnailContext:".length())) {
                return clipRelationContext(trimmed.substring("relationThumbnailContext:".length()).trim(), 360);
            }
        }
        return "";
    }

    private static String relationSummaryLine(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return "";
        }
        for (String line : snippet.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.regionMatches(true, 0, "relationSummary:", 0, "relationSummary:".length())) {
                String value = trimmed.substring("relationSummary:".length()).trim();
                return clipRelationContext(value, 360);
            }
        }
        return "";
    }

    private static String safeRelationKind(Object value) {
        String text = safeDiagString(value);
        if (text.isBlank()) {
            return "";
        }
        String safe = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]+", "_");
        safe = safe.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private static int relationThumbnailInt(Object value) {
        try {
            return OrchestratorTraceNumbers.parseNonNegativeInt(value, "kgRelationThumbnailInt");
        } catch (NumberFormatException ignored) {
            INVALID_NUMBER_SUPPRESSOR.accept("kgRelationThumbnailInt");
            return 0;
        }
    }

    private static void recordKgRelationThumbnailTrace(Map<String, Object> dbg,
                                                       String status,
                                                       int candidateCount,
                                                       int selectedCount,
                                                       String query,
                                                       String topHash,
                                                       int requestedTopK) {
        String prefix = "retrieval.kg.relationThumbnail.";
        String safeStatus = status == null || status.isBlank() ? "unknown" : status;
        String queryHash = safeHash(query);
        if (dbg != null) {
            dbg.put(prefix + "status", safeStatus);
            dbg.put(prefix + "candidateCount", Math.max(0, candidateCount));
            dbg.put(prefix + "selectedCount", Math.max(0, selectedCount));
            dbg.put(prefix + "queryHash12", queryHash);
            dbg.put(prefix + "requestedTopK", Math.max(0, requestedTopK));
            if (topHash != null && !topHash.isBlank()) {
                dbg.put(prefix + "topHash", topHash);
            } else {
                dbg.put(prefix + "sliceMap", List.of());
                dbg.put(prefix + "sliceMapCount", 0);
            }
        }
        try {
            TraceStore.put(prefix + "status", safeStatus);
            TraceStore.put(prefix + "candidateCount", Math.max(0, candidateCount));
            TraceStore.put(prefix + "selectedCount", Math.max(0, selectedCount));
            TraceStore.put(prefix + "queryHash12", queryHash);
            TraceStore.put(prefix + "requestedTopK", Math.max(0, requestedTopK));
            if (topHash != null && !topHash.isBlank()) {
                TraceStore.put(prefix + "topHash", topHash);
            } else {
                TraceStore.put(prefix + "topHash", null);
                TraceStore.put(prefix + "sliceMap", List.of());
                TraceStore.put(prefix + "sliceMapCount", 0);
            }
            TraceStore.append(prefix + "events", mapOf(
                    "status", safeStatus,
                    "candidateCount", Math.max(0, candidateCount),
                    "selectedCount", Math.max(0, selectedCount),
                    "queryHash12", queryHash,
                    "requestedTopK", Math.max(0, requestedTopK),
                    "topHash", topHash == null ? "" : topHash));
        } catch (Throwable ignored) {
            log.debug("[AWX][rag][orchestrator] suppressed stage=kgRelationThumbnail.trace err=trace-failure");
        }
    }

    private static void recordKgRelationThumbnailSliceMap(Map<String, Object> dbg,
                                                          List<Map<String, Object>> sliceMap) {
        String key = "retrieval.kg.relationThumbnail.sliceMap";
        String countKey = "retrieval.kg.relationThumbnail.sliceMapCount";
        List<Map<String, Object>> safeMap = sliceMap == null ? List.of() : List.copyOf(sliceMap);
        if (dbg != null) {
            dbg.put(key, safeMap);
            dbg.put(countKey, safeMap.size());
        }
        try {
            TraceStore.put(key, safeMap);
            TraceStore.put(countKey, safeMap.size());
        } catch (Throwable ignored) {
            log.debug("[AWX][rag][orchestrator] suppressed stage=kgRelationThumbnail.sliceMap err=trace-failure");
        }
    }

    private static void recordKgRelationThumbnailDropped(Map<String, Object> dbg, int droppedCount) {
        String key = "retrieval.kg.relationThumbnail.droppedCount";
        int safeCount = Math.max(0, droppedCount);
        if (dbg != null) {
            dbg.put(key, safeCount);
        }
        try {
            TraceStore.put(key, safeCount);
        } catch (Throwable ignored) {
            log.debug("[AWX][rag][orchestrator] suppressed stage=kgRelationThumbnail.dropped err=trace-failure");
        }
    }

    private static void recordKgRelationThumbnailSelectionReason(Map<String, Object> dbg, String selectionReason) {
        String key = "retrieval.kg.relationThumbnail.selectionReason";
        String safeReason = safeDiagString(selectionReason);
        if (safeReason.isBlank()) {
            safeReason = "unknown";
        }
        if (dbg != null) {
            dbg.put(key, safeReason);
        }
        try {
            TraceStore.put(key, safeReason);
        } catch (Throwable ignored) {
            log.debug("[AWX][rag][orchestrator] suppressed stage=kgRelationThumbnail.selectionReason err=trace-failure");
        }
    }

    private boolean isWhitelistedDoc(Doc d) {
        if (d == null) {
            return false;
        }
        if (domainWhitelist == null) {
            return true;
        }
        String url = null;
        if (d.meta != null && !d.meta.isEmpty()) {
            Object v = firstNonBlank(d.meta, "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical", "permalink");
            if (v != null) {
                url = String.valueOf(v);
            }
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        String host = domainWhitelist.extractHost(url);
        if (host == null || host.isBlank()) {
            // URL 파싱이 안 되면 allowlist 판단 불가 → false
            return false;
        }
        java.util.List<String> allow = domainWhitelist.getDomainAllowlist();
        if (allow == null || allow.isEmpty()) {
            // allowlist가 비어있으면 DomainWhitelist의 기본 정책을 따른다(운영 설정 의존).
            return domainWhitelist.isOfficial(url);
        }
        for (String suf : allow) {
            if (com.example.lms.service.rag.auth.DomainWhitelist.matchesAllowlistHost(host, suf)) {
                return true;
            }
        }
        return false;
    }
    private String extractTitle(Map<?, ?> meta, String fallback) {
        if (meta == null)
            return fallback;
        Object title = meta.get("title");
        if (title != null) {
            String t = title.toString();
            if (!t.isBlank()) {
                return t;
            }
        }
        return fallback;
    }

    private String buildSnippet(Content c) {
        String text = c.textSegment() != null
                ? c.textSegment().text()
                : String.valueOf(c);

        if (c.metadata() != null) {
            Object url = c.metadata().get("url");
            if (url != null) {
                String u = url.toString();
                if (!u.isBlank() && !text.contains("URL:")) {
                    text = text + "\nURL: " + u;
                }
            }
        }
        return text;
    }

    private Map<String, Object> extractMetadata(Content c) {
        Map<String, Object> safe = new HashMap<>();
        if (c == null) {
            return safe;
        }
        copyMetadata(c.metadata(), safe);
        if (c.textSegment() != null) {
            copyMetadata(c.textSegment().metadata(), safe);
        }
        return safe;
    }

    private static void copyMetadata(Object rawMeta, Map<String, Object> target) {
        if (rawMeta == null || target == null) {
            return;
        }
        if (rawMeta instanceof Metadata metadata) {
            for (Map.Entry<String, Object> e : metadata.toMap().entrySet()) {
                DocMetadataSanitizer.copyEntry(e.getKey(), e.getValue(), target);
            }
            return;
        }
        if (rawMeta instanceof Map<?, ?> meta) {
            for (Map.Entry<?, ?> e : meta.entrySet()) {
                DocMetadataSanitizer.copyEntry(e.getKey(), e.getValue(), target);
            }
        }
    }

    private static double scoreFromMetadata(Map<String, Object> meta, double fallback) {
        Object score = firstNonBlank(meta,
                "kg_score", "kgScore", "graph_score", "graphScore", "kg_path_score", "kgPathScore",
                "score", "rrf_score", "rrfScore", "vector_score", "vectorScore", "similarityScore");
        double value = toDouble(score, fallback);
        return Double.isFinite(value) && value >= 0.0d ? value : fallback;
    }

    private java.util.List<Doc> toDocsOrEmpty(ContentRetriever retriever, String query, int topK, String sourceTag) {
        try {
            return toDocs(retriever, query, topK, sourceTag);
        } catch (Exception e) {
            log.warn("[AWX][rag][orchestrator] source retrieval failed failureReason={} errorType={} sourceTag={} queryHash12={} queryLength={}", "source-retrieval-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.traceLabelOrFallback(String.valueOf(sourceTag), "unknown"), com.example.lms.trace.SafeRedactor.hash12(query), query == null ? 0 : query.length());
            return java.util.List.of();
        }
    }

    private java.util.List<Doc> toDocsOrEmpty(ContentRetriever retriever, Query query, int topK, String sourceTag) {
        try {
            return toDocs(retriever, query, topK, sourceTag);
        } catch (Exception e) {
            String queryText = query == null ? null : query.text();
            log.warn("[AWX][rag][orchestrator] source retrieval failed failureReason={} errorType={} sourceTag={} queryHash12={} queryLength={}", "source-retrieval-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.traceLabelOrFallback(String.valueOf(sourceTag), "unknown"), com.example.lms.trace.SafeRedactor.hash12(queryText), queryText == null ? 0 : queryText.length());
            return java.util.List.of();
        }
    }

    private java.util.List<Doc> toDocs(ContentRetriever retriever, String query, int topK, String sourceTag) {
        return toDocs(retriever, new Query(query), topK, sourceTag);
    }

    private java.util.List<Doc> toDocs(ContentRetriever retriever, Query query, int topK, String sourceTag) {
        if (retriever == null) {
            return java.util.List.of();
        }
        java.util.List<dev.langchain4j.rag.content.Content> contents = retriever.retrieve(query);
        if (contents == null) {
            contents = java.util.Collections.emptyList();
        }
        java.util.List<Doc> docs = new java.util.ArrayList<>();
        for (int i = 0; i < contents.size() && docs.size() < topK; i++) {
            dev.langchain4j.rag.content.Content c = contents.get(i);
            if (c == null) {
                continue;
            }
            Doc d = new Doc();
            d.meta = extractMetadata(c);
            d.title = extractTitle(d.meta, sourceTag + " Result #" + (i + 1));
            d.snippet = buildSnippet(c);
            d.source = sourceTag;
            d.score = scoreFromMetadata(d.meta, 1.0 - (i * 0.01));
            d.id = stableId(sourceTag, d.meta, i);
            docs.add(d);
        }
        return docs;
    }

    private void markDependency(String axis, String status, boolean required, boolean attempted,
            boolean fallbackUsed, String failureClass, Throwable error, Map<String, Object> dbg) {
        String safeAxis = (axis == null || axis.isBlank()) ? "unknown" : axis.trim().toLowerCase(Locale.ROOT);
        String safeStatus = (status == null || status.isBlank()) ? "unknown" : status;
        String safeFailure = failureClass == null ? "" : failureClass;
        try {
            TraceStore.put("retrieval.dependency." + safeAxis + ".status", safeStatus);
            TraceStore.put("retrieval.dependency." + safeAxis + ".required", required);
            TraceStore.put("retrieval.dependency." + safeAxis + ".attempted", attempted);
            TraceStore.put("retrieval.dependency." + safeAxis + ".failureClass", safeFailure);
            TraceStore.put("retrieval.dependency." + safeAxis + ".fallbackUsed", fallbackUsed);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("axis", safeAxis);
            ev.put("status", safeStatus);
            ev.put("required", required);
            ev.put("attempted", attempted);
            ev.put("failureClass", safeFailure);
            ev.put("fallbackUsed", fallbackUsed);
            TraceStore.append("retrieval.dependency.events", ev);
            emitRagPipelineEvent(
                    "retrieval",
                    safeAxis,
                    "dependency",
                    "UnifiedRagOrchestrator.retrieveCandidates",
                    attempted ? ("failed".equals(safeStatus) ? "failed" : "ok") : safeStatus,
                    mapOf("requestedTopK", 0, "mode", "dependency"),
                    mapOf("selectedCount", 0),
                    safeFailure.isBlank() ? Map.of() : mapOf("reasonCode", safeFailure, "failureClass", safeFailure,
                            "exceptionType", safeFailure),
                    fallbackUsed || !safeFailure.isBlank()
                            ? mapOf("action", fallbackUsed ? "fail_soft_fallback" : "",
                                    "applied", fallbackUsed,
                                    "reasonCode", safeFailure.isBlank() ? safeStatus : safeFailure)
                            : Map.of());
        } catch (Exception ignore) {
            log.debug("[AWX][rag][orchestrator] suppressed stage=dependency.trace err=trace-failure");
        }
        if (dbg != null) {
            dbg.put("retrieval.dependency." + safeAxis + ".status", safeStatus);
            dbg.put("retrieval.dependency." + safeAxis + ".required", required);
            dbg.put("retrieval.dependency." + safeAxis + ".failureClass", safeFailure);
            dbg.put("retrieval.dependency." + safeAxis + ".fallbackUsed", fallbackUsed);
        }
        if (faultMaskingLayerMonitor != null && (error != null || fallbackUsed)) {
            try {
                faultMaskingLayerMonitor.record(
                        "retrieval.dependency." + safeAxis,
                        error == null ? new IllegalStateException(safeStatus) : error,
                        "axis=" + safeAxis,
                        safeStatus);
            } catch (Exception ignore) {
                log.debug("[AWX][rag][orchestrator] suppressed stage=dependency.faultMask err=diagnostic-failure");
            }
        }
        if (debugEventStore != null && (error != null || fallbackUsed || required)) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("axis", safeAxis);
                data.put("status", safeStatus);
                data.put("required", required);
                data.put("attempted", attempted);
                data.put("failureClass", safeFailure);
                data.put("fallbackUsed", fallbackUsed);
                debugEventStore.emit(
                        (error != null || fallbackUsed) ? DebugProbeType.FAULT_MASK : DebugProbeType.ORCHESTRATION,
                        (error != null || fallbackUsed) ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                        "retrieval.dependency." + safeAxis + "." + safeStatus,
                        "Retrieval dependency state recorded",
                        "UnifiedRagOrchestrator.retrieveCandidates",
                        data,
                        error);
            } catch (Exception ignore) {
                log.debug("[AWX][rag][orchestrator] suppressed stage=dependency.debugEvent err=diagnostic-failure");
            }
        }
    }

    private static String classifyDependencyFailure(Throwable error) {
        if (error == null) {
            return "";
        }
        String name = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String msg = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (name.contains("timeout") || msg.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("api key") || msg.contains("credential") || msg.contains("unauthorized")) {
            return "provider-disabled";
        }
        return "silent-failure";
    }

    private java.util.List<Doc> toDocsOrEmpty(com.example.lms.service.service.rag.bm25.Bm25Index index,
                                             String query,
                                             int topK,
                                             String sourceTag) {
        if (index == null) {
            return java.util.List.of();
        }
        try {
            java.util.List<java.util.Map.Entry<String, Double>> hits = index.search(query, Math.max(1, topK));
            java.util.List<Doc> docs = new java.util.ArrayList<>();
            int rank = 1;
            for (java.util.Map.Entry<String, Double> e : hits) {
                Doc d = new Doc();
                d.id = sourceTag + ":" + e.getKey();
                d.title = e.getKey();
                d.snippet = "";
                d.source = sourceTag;
                d.score = e.getValue() == null ? 0.0 : e.getValue();
                d.rank = rank++;
                d.meta = new java.util.HashMap<>();
                d.meta.put("bm25.docId", e.getKey());
                d.meta.put("bm25.score", d.score);
                docs.add(d);
                if (docs.size() >= topK) {
                    break;
                }
            }
            return docs;
        } catch (Exception e) {
            log.warn("[AWX][rag][orchestrator] source retrieval failed failureReason={} errorType={} sourceTag={} queryHash12={} queryLength={}", "source-retrieval-error", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), com.example.lms.trace.SafeRedactor.traceLabelOrFallback(String.valueOf(sourceTag), "unknown"), com.example.lms.trace.SafeRedactor.hash12(query), query == null ? 0 : query.length());
            return java.util.List.of();
        }
    }

    /** Deep clone for trace snapshot (mutation 방지) */
    private static List<Doc> snapshotDocs(List<Doc> src) {
        if (src == null) {
            return new ArrayList<>();
        }
        return src.stream()
                .filter(Objects::nonNull)
                .map(d -> {
                    Doc copy = new Doc();
                    copy.id = d.id;
                    copy.title = d.title;
                    copy.snippet = d.snippet;
                    copy.source = d.source;
                    copy.score = d.score;
                    copy.rank = d.rank;
                    copy.meta = (d.meta != null) ? new HashMap<>(d.meta) : null;
                    return copy;
                })
                .collect(Collectors.toList());
    }

}

package com.example.lms.service.rag;

import static com.example.lms.service.rag.HybridRetrieverMetadata.*;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.util.SoftmaxUtil;
import org.springframework.beans.factory.annotation.Autowired;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.QueryUtils;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import com.example.lms.service.rag.rerank.LightWeightRanker;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.transform.QueryTransformerBypassReason;
import com.example.lms.prompt.PromptContext;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ForkJoinPool;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.util.MLCalibrationUtil;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.learning.NeuralPathFormationService;
import com.example.lms.service.rag.rerank.RerankGate;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.trace.SafeRedactor;

import java.util.Map; // [HARDENING]
// imports
import com.example.lms.service.rag.rerank.ElementConstraintScorer; //  ???ル㎦?????饔낃동??

import com.example.lms.service.config.HyperparameterService; // ??NEW
import com.example.lms.search.TraceStore;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import org.springframework.beans.factory.annotation.Qualifier; // - FIX: ???湲깅룪 ??癲ル슢?꾤땟???????됰쐳??@Qualifier
import jakarta.annotation.PostConstruct; // + ??좊즵獒뺣돀?? ??ш끽維곩ㅇ???紐껎룂 ??れ삀??뫢??袁⑸즲??援?????ャ뀕??癲ル슣????

@Component("vectorRetriever")
@RequiredArgsConstructor
public class HybridRetriever implements ContentRetriever {

    @Value("${selfask.enabled:true}")
    private boolean selfAskEnabled;

    @Value("${abandonware.reranker.onnx.runtime-enabled:false}")
    private boolean onnxRuntimeEnabled;

    @Value("${abandonware.reranker.onnx.allow-auto:false}")
    private boolean onnxAutoSelectionEnabled;

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    // fields (????렺?final ??ш끽維????됰씧????좊즵?? ??ш끽維??
    private final LightWeightRanker lightWeightRanker;
    // Gate controlling invocation of the expensive cross-encoder reranker.
    private final com.example.lms.service.rag.rerank.RerankGate rerankGate;
    private final AuthorityScorer authorityScorer;
    private static final double GAME_SIM_THRESHOLD = 0.3;
    // 癲ル슢?????(??ш끽維????Query.metadata?????怨쀪퐨 ??ш끽維??
    private static final String META_ALLOWED_DOMAINS = "allowedDomains"; // List<String>
    private static final String META_MAX_PARALLEL = "maxParallel"; // Integer
    private static final String META_DEDUPE_KEY = "dedupeKey"; // "text" | "url" | "hash"
    private static final String META_OFFICIAL_DOMAINS = "officialDomains"; // List<String>
    @Value("${rag.search.top-k:5}")
    private int topK;

    // 癲ル슪????& ??????
    private final RetrievalHandler handlerChain;
    private final ReciprocalRankFuser fuser;
    // Optional weighted RRF fuser. When present and the fusionMode is set
    // appropriately (e.g. "weighted-rrf"), the hybrid retriever will use it
    // instead of the standard RRF fuser. The WeightedReciprocalRankFuser
    // supports per-source weights tuned at runtime via the HyperparameterService.
    @Autowired(required = false)
    private com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser weightedFuser;
    private final AnswerQualityEvaluator qualityEvaluator;
    private final SelfAskPlanner selfAskPlanner;
    private final RelevanceScoringService relevanceScoringService;
    private final HyperparameterService hp; // ??NEW: ????깆뱾 ??좊읈?濚욌꼬?댄꺍???棺??짆??
    private final ElementConstraintScorer elementConstraintScorer; // ??NEW: ???????筌??????饔낃동??
    private final QueryTransformer queryTransformer; // ??NEW: ???ㅺ컼????れ삀??뫢?癲ル슣??????獄쏅똻??
    private final AdaptiveScoringService scoring;
    private final KnowledgeBaseService kb;
    // Path formation service used to reinforce high-consistency entity pairs.
    private final NeuralPathFormationService pathFormation;

    /**
     * Optional Redis-backed cooldown service used to guard expensive
     * operations such as cross-encoder reranking. When configured this
     * service attempts to acquire a short-lived lock prior to invoking
     * the reranker. If the lock is unavailable the reranking step is
     * skipped, allowing the system to fall back to the first pass
     * ranking. The field may be null when no Redis instance is
     * available or when cooldown gating is disabled.
     */
    @Autowired(required = false)
    private com.example.lms.service.redis.RedisCooldownService cooldownService;

    // ???NEW: ?????뤆??釉먯뒮筌????れ삀??뫢?????????⑤챶?뺧┼????袁⑤툞)
    @Autowired(required = false)
    @Qualifier("noopCrossEncoderReranker") // - FIX: ??3??onnx/noop/embedding) ?野껊챶爾??????れ삀???noop??癲ル슢?뤸뤃??
    private com.example.lms.service.rag.rerank.CrossEncoderReranker crossEncoderReranker;

    @Autowired(required = false)
    private Map<String, com.example.lms.service.rag.rerank.CrossEncoderReranker> rerankers = java.util.Collections
            .emptyMap(); // + ??좊즵獒뺣돀?? ?????ш끽維???袁⑸즲??援?????源낆춭????좊읈???

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Value("${abandonware.reranker.backend:embedding-model}")
    private String rerankerBackend; // + ??좊즵獒뺣돀?? ??ш끽維곩ㅇ???紐껎룂??onnx/embedding/noop ???ャ뀕??

    // ?域밸Ŧ肉ヨキ?域밸Ŧ留???
    private final SelfAskWebSearchRetriever selfAskRetriever;
    private final AnalyzeWebSearchRetriever analyzeRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final QueryComplexityGate gate;

    // (????? ?????濡ろ떟??????- ???源끹걬癲???딅텑??釉뚰????怨뚮옖?????????
    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("tavilyWebSearchRetriever")
    private ContentRetriever tavilyWebSearchRetriever;
    // RAG/??ш끽維???
    private final LangChainRAGService ragService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> gameEmbeddingStore;

    // ---------------------------------------------------------------------
    // Domain detector for selecting the appropriate Pinecone index. When the
    // domain is GENERAL a dedicated general index may be used (configured via
    // pinecone.index.general). When null the default index (pinecone.index.name)
    // will be used for all domains.
    private final com.example.lms.service.rag.detector.GameDomainDetector domainDetector;

    /**
     * Name of the Pinecone index used for GENERAL domain queries. When this
     * property is blank or undefined the default pineconeIndexName will be
     * used instead. Configure via application.yml: pinecone.index.general.
     */
    @org.springframework.beans.factory.annotation.Value("${pinecone.index.general:}")
    private String pineconeIndexGeneral;

    /**
     * Choose the appropriate index name based on the detected domain. If
     * the domain is GENERAL and a general index has been configured via
     * pinecone.index.general then that index is returned; otherwise the
     * default pineconeIndexName is used.
     *
     * @param domain the detected domain (case-insensitive)
     * @
     * return the name of the pinecone index to query
     */
    private String chooseIndex(String domain) {
        if (domain != null && "GENERAL".equalsIgnoreCase(domain)) {
            if (pineconeIndexGeneral != null && !pineconeIndexGeneral.isBlank()) {
                return pineconeIndexGeneral;
            }
        }
        return pineconeIndexName;
    }

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${hybrid.debug.sequential:false}")
    private boolean debugSequential;
    @Value("${hybrid.progressive.quality-min-docs:1}")
    private int qualityMinDocs;
    @Value("${hybrid.progressive.quality-min-score:0.45}")
    private double qualityMinScore;
    @Value("${hybrid.max-parallel:3}")
    private int maxParallel;
    @Value("${hybrid.executor.queue-capacity:64}")
    private int executorQueueCapacity = 64;
    @Value("${hybrid.executor.shutdown-wait-ms:1000}")
    private long executorShutdownWaitMs = 1_000L;
    @Value("${hybrid.request-timeout-ms:120000}")
    private long hybridRequestTimeoutMs = 120_000L;
    @Value("${hybrid.max-branches:64}")
    private int maxBranches = 64;
    private static final java.util.concurrent.atomic.AtomicLong EXECUTOR_THREAD_IDS =
            new java.util.concurrent.atomic.AtomicLong();
    private final Object retrievalExecutorLock = new Object();
    private volatile java.util.concurrent.ThreadPoolExecutor retrievalExecutor;

    @Value("${hybrid.min-relatedness:0.01}") // ???굿????닳뵣 ??ш낄援?????????
    private double minRelatedness;
    @Value("${hybrid.relatedness-filter.fail-soft:false}")
    private boolean relatednessFilterFailSoft;
    // ?????? 癲ル슢?꾤땟??? rrf(??れ삀??? | softmax
    @Value("${retrieval.fusion.mode:rrf}")
    private String fusionMode;
    // ??softmax ???? ????닳뵣
    @Value("${retrieval.fusion.softmax.temperature:1.0}")
    private double fusionTemperature;

    /**
     * Calibration mode for softmax fusion. Supported values are
     * {@code minmax}, {@code isotonic} and {@code none}. When set to
     * {@code none} or any unsupported value the softmax fusion pathway is
     * disabled and the system will fall back to RRF. This value is
     * configurable via application.yml (retrieval.fusion.softmax.calibration).
     */
    @Value("${retrieval.fusion.softmax.calibration:none}")
    private String softmaxCalibration;

    /**
     * The number of candidates that will be sent to the cross-encoder reranker.
     * This value is used by the rerank gate to decide whether or not to invoke
     * the expensive cross-encoder reordering step. When the first pass
     * candidate set contains fewer than this number of elements the reranker
     * is skipped. Defaults to 12 if unspecified.
     * <p>
     * Config key drift-safe lookup:
     * <ul>
     * <li>{@code ranking.rerank.ce.topK} (legacy)</li>
     * <li>{@code rerank.ce.topK} (canonical)</li>
     * </ul>
     */
    @Value("${ranking.rerank.ce.topK:${rerank.ce.topK:12}}")
    private int rerankCeTopK;
    @Value("${retrieval.rank.use-ml-correction:true}")
    private boolean useMlCorrection; // ??NEW: ML ?怨뚮옖?????????덈뭷

    /** ?濡ろ떟????????????釉먮릍???좊즴甕????ш낄猷?嚥▲렞??*/
    @Value("${retrieval.consistency.threshold:0.8}")
    private double consistencyThreshold;

    @Value("${retrieval.implicit-consistency.persist-enabled:false}")
    private boolean implicitConsistencyPersistEnabled;

    @PostConstruct
    private void selectRerankerByProperty() {
        // application.yml ??abandonware.reranker.backend ??좊즴??????ㅻ깹???袁⑸즲??援?????筌????ャ뀕??
        try {
            if (rerankerBackend == null || rerankerBackend.isBlank()) {
                return;
            }

            String backend = rerankerBackend.trim().toLowerCase();
            String key;
            switch (backend) {
                case "onnx-runtime":
                case "onnx":
                    if (onnxRuntimeEnabled) {
                        key = "onnxCrossEncoderReranker";
                    } else {
                        log.warn("[AWX2AF2][gpu][reranker] backend={} requested at startup but onnxRuntimeEnabled=false; using embedding-model",
                                backend);
                        key = "embeddingCrossEncoderReranker";
                    }
                    break;
                case "embedding-model":
                case "embedding":
                    key = "embeddingCrossEncoderReranker";
                    break;
                case "noop":
                case "none":
                case "disabled":
                    key = "noopCrossEncoderReranker";
                    break;
                default:
                    // legacy pattern: "<backend>CrossEncoderReranker"
                    key = backend + "CrossEncoderReranker";
                    break;
            }

            com.example.lms.service.rag.rerank.CrossEncoderReranker chosen = rerankers.get(key);

            if (chosen != null) {
                this.crossEncoderReranker = chosen;
                log.info("[AWX2AF2][gpu][reranker] backend={} bean={} onnxRuntimeEnabled={} autoOnnx={}",
                        rerankerBackend, key, onnxRuntimeEnabled, onnxAutoSelectionEnabled);
            } else if (rerankers.containsKey("embeddingCrossEncoderReranker")) {
                // Startup fallback is explicit: embedding -> noop. Do not pick an arbitrary bean.
                com.example.lms.service.rag.rerank.CrossEncoderReranker fallback =
                        rerankers.get("embeddingCrossEncoderReranker");
                this.crossEncoderReranker = fallback;
                log.warn("[Hybrid] Reranker beanHash={} beanLength={} not found for backendHash={} backendLength={} ??using {}",
                        SafeRedactor.hashValue(key), key == null ? 0 : key.length(),
                        SafeRedactor.hashValue(rerankerBackend), rerankerBackend == null ? 0 : rerankerBackend.length(),
                        fallback.getClass().getSimpleName());
            } else if (rerankers.containsKey("noopCrossEncoderReranker")) {
                this.crossEncoderReranker = rerankers.get("noopCrossEncoderReranker");
                log.warn("[Hybrid] Reranker beanHash={} beanLength={} not found for backendHash={} backendLength={}; using noopCrossEncoderReranker",
                        SafeRedactor.hashValue(key), key == null ? 0 : key.length(),
                        SafeRedactor.hashValue(rerankerBackend), rerankerBackend == null ? 0 : rerankerBackend.length());
            } else {
                log.info("[Hybrid] No reranker beans registered; keeping injected default: {}",
                        (crossEncoderReranker != null ? crossEncoderReranker.getClass().getSimpleName() : "none"));
            }
        } catch (Exception e) {
            log.warn("[Hybrid] reranker backend selection failed; preserving injected default errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            // ???源놁벁: ???ャ뀕??????됰꽡???????れ삀?????낆뒩??????
        }
    }

    /**
     * Resolve the CrossEncoderReranker for the current request.
     *
     * Supports plan/meta overrides via:
     * - rerank.backend | rerank_backend | rerankBackend (string)
     * - onnx.enabled (bool) to disallow ONNX at plan level
     *
     * Also supports an "auto" backend that selects ONNX when available & allowed,
     * otherwise falls back to the embedding reranker.
     */
    private com.example.lms.service.rag.rerank.CrossEncoderReranker resolveCrossEncoderReranker(
            java.util.Map<String, Object> metaMap,
            String backendOverride,
            Boolean onnxEnabledOverride,
            boolean crossEncoderEnabled) {

        if (rerankers == null || rerankers.isEmpty()) {
            traceRerankBackend("none", "noopCrossEncoderReranker", "no_registered_rerankers", true);
            return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
        }

        String backend = backendOverride;
        if (backend == null || backend.isBlank())
            backend = rerankerBackend;
        backend = backend == null ? "" : backend.trim().toLowerCase(java.util.Locale.ROOT);

        boolean onnxAllowed = onnxRuntimeEnabled && onnxEnabledOverride != Boolean.FALSE
                && metaBool(metaMap, "onnx.enabled", true);
        boolean onnxBreakerOpen = false;
        try {
            onnxBreakerOpen = (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RERANK_ONNX));
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "rerank.onnxBreaker");
            // fail-soft
        }

        boolean hasOnnx = rerankers.containsKey("onnxCrossEncoderReranker");
        boolean hasEmbedding = rerankers.containsKey("embeddingCrossEncoderReranker");
        boolean hasNoop = rerankers.containsKey("noopCrossEncoderReranker");

        boolean onnxUsable = crossEncoderEnabled && onnxAllowed && hasOnnx && !onnxBreakerOpen;
        boolean autoOnnxUsable = onnxAutoSelectionEnabled && onnxUsable;

        String key;
        String fallbackReason = "requested";
        switch (backend) {
            case "", "auto" -> {
                if (!crossEncoderEnabled) {
                    key = "noopCrossEncoderReranker";
                    fallbackReason = "cross_encoder_disabled";
                } else if (autoOnnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else if (hasEmbedding) {
                    key = "embeddingCrossEncoderReranker";
                    fallbackReason = hasOnnx ? "onnx_unusable" : "onnx_missing";
                } else {
                    key = "noopCrossEncoderReranker";
                    fallbackReason = "embedding_missing";
                }
            }
            case "onnx-runtime", "onnx" -> {
                if (!crossEncoderEnabled) {
                    key = "noopCrossEncoderReranker";
                    fallbackReason = "cross_encoder_disabled";
                } else if (onnxUsable) {
                    key = "onnxCrossEncoderReranker";
                } else {
                    key = hasEmbedding ? "embeddingCrossEncoderReranker" : "noopCrossEncoderReranker";
                    fallbackReason = onnxBreakerOpen ? "onnx_breaker_open"
                            : (!onnxAllowed ? "onnx_not_allowed" : (!hasOnnx ? "onnx_missing" : "onnx_unusable"));
                }
            }
            case "embedding-model", "embedding", "bi-encoder", "biencoder" -> key = "embeddingCrossEncoderReranker";
            case "noop", "none", "disabled" -> key = "noopCrossEncoderReranker";
            default -> {
                key = "embeddingCrossEncoderReranker";
                fallbackReason = "unknown_backend";
            }
        }

        com.example.lms.service.rag.rerank.CrossEncoderReranker r = rerankers.get(key);
        if (r != null) {
            traceRerankBackend(backend, key, fallbackReason, "noopCrossEncoderReranker".equals(key));
            return r;
        }

        // final fallback order: embedding -> noop
        if (hasEmbedding) {
            traceRerankBackend(backend, "embeddingCrossEncoderReranker", "selected_missing", false);
            return rerankers.get("embeddingCrossEncoderReranker");
        }
        if (hasNoop) {
            traceRerankBackend(backend, "noopCrossEncoderReranker", "embedding_missing", true);
            return rerankers.get("noopCrossEncoderReranker");
        }
        traceRerankBackend(backend, "noopCrossEncoderReranker", "no_safe_registered_fallback", true);
        return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
    }

    private void traceRerankBackend(String requested, String selected, String fallbackReason, boolean noopSelected) {
        try {
            TraceStore.put("rerank.backend.requested", safeTraceValue(requested));
            TraceStore.put("rerank.backend.selected", safeTraceValue(selected));
            TraceStore.put("rerank.backend.fallbackReason", safeTraceValue(fallbackReason));
            TraceStore.put("rerank.backend.noopSelected", noopSelected);
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "rerank.backend.trace");
            // tracing is best-effort
        }
        if (debugEventStore != null && (noopSelected || !"requested".equals(fallbackReason))) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("requested", safeTraceValue(requested));
                data.put("selected", safeTraceValue(selected));
                data.put("fallbackReason", safeTraceValue(fallbackReason));
                data.put("noopSelected", noopSelected);
                debugEventStore.emit(
                        DebugProbeType.ORCHESTRATION,
                        noopSelected ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                        "rerank.backend." + safeTraceValue(fallbackReason),
                        "Rerank backend resolved",
                        "HybridRetriever.resolveCrossEncoderReranker",
                        data,
                        null);
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "rerank.backend.debugEvent");
                // diagnostics must not affect retrieval
            }
        }
    }

    private static String safeTraceValue(String value) {
        return (value == null || value.isBlank()) ? "none" : value.trim();
    }

    private void recordHybridBranchFailure(int branchIndex, String query, Throwable error, String failureClass) {
        String cls = (failureClass == null || failureClass.isBlank()) ? "silent-failure" : failureClass;
        String errType = (error == null) ? "none" : error.getClass().getSimpleName();
        String queryHash12 = SafeRedactor.hash12(query);
        try {
            TraceStore.inc("hybrid.branch.failure.count");
            TraceStore.put("hybrid.branch.failureClass", cls);
            TraceStore.put("hybrid.branch.failure.branchIndex", branchIndex);
            TraceStore.put("hybrid.branch.failure.queryHash12", queryHash12);
            TraceStore.put("hybrid.branch.failure.errorType", errType);
            TraceStore.put("missing_future", "missing_future".equals(cls));
            TraceStore.append("hybrid.branch.failure.events", Map.of(
                    "branchIndex", branchIndex,
                    "queryHash12", queryHash12,
                    "failureClass", cls,
                    "errorType", errType));
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "branchFailure.trace");
            // tracing is best-effort
        }
        if (debugEventStore != null) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("branchIndex", branchIndex);
                data.put("queryHash12", queryHash12);
                data.put("failureClass", cls);
                data.put("errorType", errType);
                debugEventStore.emit(
                        DebugProbeType.FAULT_MASK,
                        DebugEventLevel.WARN,
                        "hybrid.branch.failure." + cls,
                        "Hybrid retriever branch failed and returned an empty fail-soft result",
                        "HybridRetriever.retrieveAll",
                        data,
                        error);
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "branchFailure.debugEvent");
                // diagnostics must not affect retrieval
            }
        }
    }

    @Override
    public List<Content> retrieve(Query query) {

        // Parse query metadata fail-soft.

        String sessionKey = null;
        Map<String, Object> md = Map.of();
        try {
            sessionKey = Optional.ofNullable(query)
                    .map(Query::metadata)
                    .map(HybridRetrieverMetadata::toMap)
                    .map(meta -> meta.get(LangChainRAGService.META_SID))
                    .map(Object::toString)
                    .orElse(null);

            md = Optional.ofNullable(query)
                    .map(QueryUtils::metadata)
                    .orElse(Map.of());
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "retrieve.metadata");
            // fail-soft
        }

        final String q = Optional.ofNullable(query)
                .map(Query::text)
                .orElse("");
        final String dedupeKey = Optional.ofNullable(md.get(META_DEDUPE_KEY))
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .orElse("text");
        final List<String> officialDomains = new ArrayList<>();
        Object officialDomainsRaw = md.get(META_OFFICIAL_DOMAINS);
        if (officialDomainsRaw instanceof Collection<?> values) {
            for (Object value : values) {
                String domain = String.valueOf(value).trim();
                if (!domain.isEmpty()) {
                    officialDomains.add(domain);
                }
            }
        } else if (officialDomainsRaw != null) {
            for (String domain : String.valueOf(officialDomainsRaw).split(",")) {
                String trimmed = domain.trim();
                if (!trimmed.isEmpty()) {
                    officialDomains.add(trimmed);
                }
            }
        }

        int prefuseCap = -1;
        try {
            int keepN = metaInt(md, "rerank.topK", -1);
            if (keepN <= 0) {
                keepN = metaInt(md, "rerank_top_k", -1);
            }
            if (keepN <= 0) {
                keepN = metaInt(md, "rerankTopK", -1);
            }

            int candidateCap = metaInt(md, "rerank.ce.topK", -1);
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerank.ceTopK", -1);
            }
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerank_ce_top_k", -1);
            }
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerankCeTopK", -1);
            }
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerank.candidateK", -1);
            }
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerank.candidate_k", -1);
            }
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerank_candidate_k", -1);
            }
            if (candidateCap <= 0) {
                candidateCap = metaInt(md, "rerankCandidateK", -1);
            }

            if (candidateCap <= 0 && keepN > 0) {
                candidateCap = Math.max(keepN * 2, Math.max(keepN, topK));
            }
            if (candidateCap > 0) {
                prefuseCap = (keepN > 0) ? Math.max(candidateCap, keepN) : candidateCap;
            }
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "prefuseCap.parse");
            // fail-soft
        }

        final LinkedHashSet<Content> mergedContents = new LinkedHashSet<>();
        final boolean applyPrefuseCap = prefuseCap > 0;
        final int maxCandidates = applyPrefuseCap ? Math.max(1, prefuseCap) : Integer.MAX_VALUE;
        final int minNeed = applyPrefuseCap ? Math.max(1, Math.min(topK, maxCandidates)) : topK;

        if (applyPrefuseCap) {
            try {
                TraceStore.put("rerank.prefuse.cap", maxCandidates);
                TraceStore.put("rerank.prefuse.minNeed", minNeed);
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "prefuseCap.trace");
                HybridRetrieverTraceSuppressions.trace("prefuseCap.trace", ignore);
            }

            // Cap lane topKs only downward (never increase defaults).
            // Rebuild Query so downstream retrievers observe caps consistently.
            try {
                java.util.Map<String, Object> md2 = new java.util.HashMap<>();
                if (md != null && !md.isEmpty()) {
                    md2.putAll(md);
                }

                int w = metaInt(md2, "webTopK", -1);
                if (w > maxCandidates)
                    md2.put("webTopK", String.valueOf(maxCandidates));

                int v = metaInt(md2, "vecTopK", -1);
                if (v > maxCandidates)
                    md2.put("vecTopK", String.valueOf(maxCandidates));

                int vt = metaInt(md2, "vectorTopK", -1);
                if (vt > maxCandidates)
                    md2.put("vectorTopK", String.valueOf(maxCandidates));

                int kg = metaInt(md2, "kgTopK", -1);
                if (kg > maxCandidates)
                    md2.put("kgTopK", String.valueOf(maxCandidates));

                query = QueryUtils.buildQuery(q, sessionKey, null, md2);
                md = md2;
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "prefuseCap.queryRebuild");
                // fail-soft
            }
        }

        // Determine the query domain once up front. When the domain detector is
        // unavailable default to GENERAL. The domain is used when selecting
        // which Pinecone index to query via chooseIndex().
        String detectedDomain;
        try {
            detectedDomain = (domainDetector != null) ? domainDetector.detect(q) : "GENERAL";
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "domain.detect");
            detectedDomain = "GENERAL";
        }
        final String chosenIndex = chooseIndex(detectedDomain);

        // ???? ?釉뚰???쨨?븐눊猿? ??????ш끽維곲?? ??????????깆（ Intent ??れ삀??뫢??類?뺨??щ빝??濡ろ떟???癲ル슢?꾤땟???????????????????????????????????????
        try {
            boolean isEducationIntent = "EDU".equalsIgnoreCase(detectedDomain)
                    || "EDUCATION".equalsIgnoreCase(detectedDomain);

            // Query metadata?????intent ???⑤베肄???熬곣뱿逾?(???ャ뀖筌?Preprocessor??좊읈? 癲??????됰텑????좊읈???
            String intentFromMeta = null;
            try {
                if (md != null) {
                    Object raw = md.get("intent");
                    if (raw instanceof String s) {
                        intentFromMeta = s;
                    }
                }
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "intent.metadata");
                // 癲ル슢??? ????????믩눀?????뺤깓????寃뗏???れ삀?????ш끽維곮?嶺뚮ㅎ?붺빊?????
            }
            if ("education".equalsIgnoreCase(intentFromMeta)
                    || "training".equalsIgnoreCase(intentFromMeta)) {
                isEducationIntent = true;
            }

            if (isEducationIntent) {
                log.debug("[Hybrid] Education intent ??vector-only retrieval (index={})", chosenIndex);
                ContentRetriever pineRetriever = ragService.asContentRetriever(chosenIndex);
                List<Content> vectResults = pineRetriever.retrieve(query);
                List<Content> filteredVectResults = (vectResults == null) ? java.util.Collections.emptyList()
                        : vectResults.stream().filter(this::allowVectorChunk).collect(Collectors.toList());
                // deduplicate results while preserving order
                LinkedHashSet<Content> unique = new LinkedHashSet<>(filteredVectResults);
                List<Content> deduped = new ArrayList<>(unique);
                // rank by cosine similarity.
                try {
                    deduped.sort((c1, c2) -> {
                        String t1 = java.util.Optional.ofNullable(c1.textSegment())
                                .map(dev.langchain4j.data.segment.TextSegment::text)
                                .orElse(c1.toString());
                        String t2 = java.util.Optional.ofNullable(c2.textSegment())
                                .map(dev.langchain4j.data.segment.TextSegment::text)
                                .orElse(c2.toString());
                        double s1 = cosineSimilarity(q, t1);
                        double s2 = cosineSimilarity(q, t2);
                        return Double.compare(s2, s1);
                    });
                } catch (Exception ignore) {
                    log.debug("[HybridRetriever] fail-soft stage={}", "education.sort");
                    // if ranking fails, maintain original order
                }
                // limit to topK
                List<Content> topList = deduped.size() > topK ? deduped.subList(0, topK) : deduped;
                // finalise and return
                return finalizeResults(new ArrayList<>(topList), dedupeKey, officialDomains, q, md);
            }
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "education.shortCircuit");
            // on error continue with default behaviour
        }

        QueryComplexityGate.Level level = gate.assess(q);
        log.debug("[Hybrid] level={} queryHash12={} queryLength={}",
                level, SafeRedactor.hash12(q), q == null ? 0 : q.length());

        switch (level) {
            case SIMPLE -> {
                // ??縕??癲ル슣???? WebSearchRetriever ?沃섅굥??, ???⑤챶?뺧┼?Vector??fallback.
                List<Content> webResults = Collections.emptyList();
                try {
                    webResults = webSearchRetriever.retrieve(query);
                } catch (Exception e) {
                    log.warn("[HybridRetriever] Web search failed errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                }
                HybridRetrieverTraceSuppressions.traceHybridWebRollup(webResults);

                if (webResults != null && !webResults.isEmpty()) {
                    addCapped(mergedContents, webResults, maxCandidates);
                } else {
                    ContentRetriever pine = ragService.asContentRetriever(chosenIndex);
                    List<Content> raw = pine.retrieve(query);
                    if (raw != null) {
                        addCapped(
                                mergedContents,
                                raw.stream().filter(this::allowVectorChunk).collect(Collectors.toList()),
                                maxCandidates);
                    }
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates
                        && tavilyWebSearchRetriever != null) {
                    try {
                        addCapped(mergedContents, tavilyWebSearchRetriever.retrieve(query), maxCandidates);
                    } catch (Exception e) {
                        log.debug("[Hybrid] Tavily fallback skipped errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                    }
                }
            }
            case AMBIGUOUS -> {
                addCapped(mergedContents, analyzeRetriever.retrieve(query), maxCandidates);

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    List<Content> webResults = webSearchRetriever.retrieve(query);
                    HybridRetrieverTraceSuppressions.traceHybridWebRollup(webResults);
                    addCapped(mergedContents, webResults, maxCandidates);
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    ContentRetriever pine = ragService.asContentRetriever(chosenIndex);
                    List<Content> raw = pine.retrieve(query);
                    if (raw != null) {
                        addCapped(
                                mergedContents,
                                raw.stream().filter(this::allowVectorChunk).collect(Collectors.toList()),
                                maxCandidates);
                    }
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates
                        && tavilyWebSearchRetriever != null) {
                    try {
                        addCapped(mergedContents, tavilyWebSearchRetriever.retrieve(query), maxCandidates);
                    } catch (Exception e) {
                        log.debug("[Hybrid] Tavily fallback skipped errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                    }
                }
            }
            case COMPLEX -> {
                addCapped(mergedContents, selfAskRetriever.retrieve(query), maxCandidates);

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    addCapped(mergedContents, analyzeRetriever.retrieve(query), maxCandidates);
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    List<Content> webResults = webSearchRetriever.retrieve(query);
                    HybridRetrieverTraceSuppressions.traceHybridWebRollup(webResults);
                    addCapped(mergedContents, webResults, maxCandidates);
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates) {
                    ContentRetriever pine = ragService.asContentRetriever(chosenIndex);
                    List<Content> raw = pine.retrieve(query);
                    if (raw != null) {
                        addCapped(
                                mergedContents,
                                raw.stream().filter(this::allowVectorChunk).collect(Collectors.toList()),
                                maxCandidates);
                    }
                }

                if (mergedContents.size() < minNeed && mergedContents.size() < maxCandidates
                        && tavilyWebSearchRetriever != null) {
                    try {
                        addCapped(mergedContents, tavilyWebSearchRetriever.retrieve(query), maxCandidates);
                    } catch (Exception e) {
                        log.debug("[Hybrid] Tavily fallback skipped errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                    }
                }
            }
        }

        // 癲ル슔?됭짆?륂렭??嶺뚮Ĳ???
        List<Content> out = finalizeResults(new ArrayList<>(mergedContents), dedupeKey, officialDomains, q, md);

        // ?? ??釉먮릍????⑤벚????濡ろ떟???????? ?袁⑸즵???
        try {
            maybeRecordImplicitConsistency(q, out, officialDomains);
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "implicitConsistency.trace");
            HybridRetrieverTraceSuppressions.trace("implicitConsistency.trace", ignore);
        }

        return out;
    }

    /**
     * ?類?뺨??щ빝??濡ろ떟????濡ろ뜏??????????怨쀬굯 癲?????ш낄援?轅곗땡?(fail-soft).
     *
     * <ul>
     * <li>癲ル슢??? ???⑤챶?뺧┼????亦?(???類κ뭅???嶺뚮ㅏ援??</li>
     * <li>ASSISTANT + verified=false ??癲ル슓堉곁땟???/li>
     * </ul>
     */
    private boolean allowVectorChunk(Content c) {
        if (c == null)
            return false;

        java.util.Map<?, ?> md = null;
        try {
            var seg = c.textSegment();
            if (seg != null && seg.metadata() != null) {
                md = seg.metadata().toMap();
            }
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "officialDomain.metadata");
            HybridRetrieverTraceSuppressions.trace("officialDomain.metadata", ignore);
        }

        if (md == null || md.isEmpty()) {
            return true; // ???類κ뭅?????Β?????ㅻ쿋獒????亦?
        }

        Object sourceTagRaw = md.get(VectorMetaKeys.META_SOURCE_TAG);
        String sourceTag = sourceTagRaw != null ? String.valueOf(sourceTagRaw) : "";
        Object originRaw = md.get(VectorMetaKeys.META_ORIGIN);
        String origin = originRaw != null ? String.valueOf(originRaw) : "";
        Object verifiedObj = md.get(VectorMetaKeys.META_VERIFIED);

        boolean verified = (verifiedObj instanceof Boolean b)
                ? b
                : "true".equalsIgnoreCase(String.valueOf(verifiedObj));

        boolean isAssistant = "ASSISTANT".equalsIgnoreCase(sourceTag)
                || "LLM".equalsIgnoreCase(origin);

        if (isAssistant && !verified) {
            log.debug("[Hybrid] Filtering out unverified ASSISTANT chunk");
            return false;
        }

        return true;
    }

    private static final String[] SYNERGY_CUES = {
            "\uc2dc\ub108\uc9c0", "\uc870\ud569", "\uacb0\ud569",
            "\ud611\ub825", "\uc5b4\uc6b8", "\ucf64\ubcf4"
    };

    private void maybeRecordImplicitConsistency(String queryText, List<Content> contents,
            List<String> officialDomains) {
        if (scoring == null || kb == null || contents == null || contents.isEmpty())
            return;
        String domain = kb.inferDomain(queryText);
        var ents = kb.findMentionedEntities(domain, queryText);
        if (ents == null || ents.size() < 2)
            return;
        var it = ents.iterator();
        String subject = it.next();
        String partner = it.next();
        int total = 0, hit = 0;
        for (Content c : contents) {
            String text = java.util.Optional.ofNullable(c.textSegment())
                    .map(dev.langchain4j.data.segment.TextSegment::text)
                    .orElse(c.toString());
            String url = HybridRetrieverSupport.extractUrl(text);
            boolean both = text != null
                    && text.toLowerCase(java.util.Locale.ROOT).contains(subject.toLowerCase(java.util.Locale.ROOT))
                    && text.toLowerCase(java.util.Locale.ROOT).contains(partner.toLowerCase(java.util.Locale.ROOT));
            if (both) {
                total++;
                double w = containsAny(text, SYNERGY_CUES) ? 1.0 : 0.6; // ??筌?瑗몌┼??넊? ??鰲???怨뚮옖????
                if (HybridRetrieverSupport.isOfficial(url, officialDomains))
                    w += 0.1; // ???살쓴????ш끽維곮???怨뚮옖????
                if (w >= 0.9)
                    hit++; // ??좊즴甕겹끃??癲ル슣??癲ル슣?????怨멸텭????
            }
        }
        if (total <= 0)
            return;
        double consistency = hit / (double) total;
        boolean shouldPersist = implicitConsistencyPersistEnabled && consistency >= boundedConsistencyThreshold();
        traceImplicitConsistency(domain, subject, partner, total, hit, consistency, shouldPersist);
        if (!shouldPersist) {
            return;
        }
        scoring.applyImplicitPositive(domain, subject, partner, consistency);
        // If the consistency score is high enough, attempt to persist the path for
        // future alignment.
        try {
            if (pathFormation != null) {
                pathFormation.maybeFormPath(subject + "->" + partner, consistency);
            }
        } catch (Throwable ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "implicitConsistency.pathFormation");
            // path reinforcement failures should not break retrieval
        }
    }

    private void traceImplicitConsistency(String domain,
            String subject,
            String partner,
            int total,
            int hit,
            double consistency,
            boolean persisted) {
        try {
            TraceStore.put("retrieval.implicitConsistency.persistEnabled", implicitConsistencyPersistEnabled);
            TraceStore.put("retrieval.implicitConsistency.persisted", persisted);
            TraceStore.put("retrieval.implicitConsistency.total", Math.max(0, total));
            TraceStore.put("retrieval.implicitConsistency.hit", Math.max(0, hit));
            TraceStore.put("retrieval.implicitConsistency.score", bounded01(consistency));
            TraceStore.put("retrieval.implicitConsistency.threshold", boundedConsistencyThreshold());
            TraceStore.put("retrieval.implicitConsistency.domainHash12", SafeRedactor.hash12(String.valueOf(domain)));
            TraceStore.put("retrieval.implicitConsistency.subjectHash12", SafeRedactor.hash12(String.valueOf(subject)));
            TraceStore.put("retrieval.implicitConsistency.partnerHash12", SafeRedactor.hash12(String.valueOf(partner)));
            TraceStore.put("retrieval.implicitConsistency.status", persisted ? "persisted" : "auxiliary_only");
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "implicitConsistency.traceStore");
            // advisory diagnostics must not affect retrieval
        }
    }

    private double boundedConsistencyThreshold() {
        return bounded01(consistencyThreshold);
    }

    /**
     * Progressive retrieval:
     * 1) Local RAG ???Β?띾쭡 ?????源녿뼥 ?野껊챶爾?????釉뚰??㏓뎨????ろ꼤嶺?
     * 2) 雅?퍔瑗띰㎖????Self-Ask(1~2?????嶺뚮Ĳ???????濡ろ떟????⑥щ뼰 ???얜Ŧ類?
     */
    @Deprecated // ????????濡ろ떟????????濚밸Ŧ遊???濡ろ뜑?灌鍮???影?낅궢??????嶺뚮ㅎ???? ???)
    public List<Content> retrieveProgressive(String question, String sessionKey, int limit) {
        long integrityStarted = System.nanoTime();
        if (question == null || question.isBlank()) {
            return emptyEvidence("hybrid_progressive", "invalid_input", integrityStarted);
        }
        final int top = Math.max(1, limit);

        try {
            // 1) ?棺??짆?쏆춾?RAG ???Β?띾쭡
            // Detect the domain of the question and select the appropriate pinecone index.
            String domain;
            try {
                domain = (domainDetector != null) ? domainDetector.detect(question) : "GENERAL";
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "progressive.domain.detect");
                domain = "GENERAL";
            }
            String idx = chooseIndex(domain);
            ContentRetriever pine = ragService.asContentRetriever(idx);
            // [HARDENING] build query with metadata for session isolation
            String sidForQuery = (sessionKey == null || sessionKey.isBlank()) ? "__TRANSIENT__" : sessionKey;
            dev.langchain4j.rag.query.Query qObj = QueryUtils.buildQuery(question, sidForQuery, null);
            Map<String, Object> md0 = toMap(qObj.metadata());

            // ???? pre-fuse candidate cap (retrieveAll/retrieve?? ????곕럡????嚥▲꺃?? ??ш끽維亦???獄쏅똻???????モ뵲)
            // ??????????????????????
            int prefuseCap = -1;
            int fuseLimit = top;
            try {
                int keepN = metaInt(md0, "rerank.topK", -1);
                if (keepN <= 0)
                    keepN = metaInt(md0, "rerank_top_k", -1);
                if (keepN <= 0)
                    keepN = metaInt(md0, "rerankTopK", -1);

                int candidateCap = metaInt(md0, "rerank.ce.topK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerank.ceTopK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerank_ce_top_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerankCeTopK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerank.candidateK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerank.candidate_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerank_candidate_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(md0, "rerankCandidateK", -1);

                if (candidateCap <= 0 && keepN > 0) {
                    candidateCap = Math.max(keepN * 2, Math.max(keepN, top));
                }

                if (candidateCap > 0) {
                    prefuseCap = (keepN > 0) ? Math.max(candidateCap, keepN) : candidateCap;
                    fuseLimit = Math.min(fuseLimit, Math.max(1, prefuseCap));
                    TraceStore.put("rerank.prefuse.cap", prefuseCap);
                    TraceStore.put("rerank.prefuse.limit", fuseLimit);
                }
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "progressive.prefuseCap.parse");
                // fail-soft
            }
            final boolean applyPrefuseCap = prefuseCap > 0;

            List<Content> local = pine.retrieve(qObj);

            if (applyPrefuseCap && local != null && local.size() > fuseLimit) {
                local = new java.util.ArrayList<>(local.subList(0, fuseLimit));
            }

            if (qualityEvaluator != null
                    && qualityEvaluator.isSufficient(question, local, qualityMinDocs, qualityMinScore)) {
                log.info("[Hybrid] Local RAG sufficient -> skip web (sidHash={}, queryHash12={}, queryLength={})",
                        SafeRedactor.hashValue(sessionKey), SafeRedactor.hash12(question), question == null ? 0 : question.length());
                List<Content> out = finalizeResults(new ArrayList<>(local), "text", java.util.Collections.emptyList(),
                        question, md0);
                return out.size() > top ? out.subList(0, top) : out;
            }

            // 2) Self-Ask??1~2???????癲ル슣??????獄쏅똻??????ш끽維뺠눧???ш낄援??
            List<String> planned;
            if (!selfAskEnabled || selfAskPlanner == null) {
                planned = List.of(question);
            } else {
                try {
                    planned = selfAskPlanner.plan(question, 2);
                } catch (Exception e) {
                    log.warn("[Hybrid] SelfAskPlanner failed (sidHash={}, queryHash12={}, queryLength={}): errorHash={} errorLength={} -> fallback to raw query",
                            SafeRedactor.hashValue(sessionKey), SafeRedactor.hash12(question), question == null ? 0 : question.length(),
                            SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                    planned = List.of(question);
                }
            }
            List<String> queries = QueryHygieneFilter.sanitize(planned, 2, 0.80);

            if (queries.isEmpty())
                queries = List.of(question);
            if (queries.isEmpty())
                queries = List.of(question);

            // 3) ??ш끽維?????얜????⑥궡異???筌?鍮?癲ル슪?ｇ몭????????
            List<List<Content>> buckets = new ArrayList<>();
            for (String q : queries) {
                List<Content> acc = new ArrayList<>();
                try {
                    // [HARDENING] build a query with session metadata using QueryUtils
                    java.util.Map<String, Object> mdSub = new java.util.HashMap<>();
                    if (md0 != null && !md0.isEmpty()) {
                        mdSub.putAll(md0);
                    }

                    // pre-fuse ??影?됀?????lane topK??cap (retriever ?????????ル춪)
                    if (applyPrefuseCap) {
                        int w = metaInt(mdSub, "webTopK", -1);
                        if (w > fuseLimit)
                            mdSub.put("webTopK", String.valueOf(fuseLimit));
                        int v = metaInt(mdSub, "vecTopK", -1);
                        if (v > fuseLimit)
                            mdSub.put("vecTopK", String.valueOf(fuseLimit));
                        int vt = metaInt(mdSub, "vectorTopK", -1);
                        if (vt > fuseLimit)
                            mdSub.put("vectorTopK", String.valueOf(fuseLimit));
                        int kg = metaInt(mdSub, "kgTopK", -1);
                        if (kg > fuseLimit)
                            mdSub.put("kgTopK", String.valueOf(fuseLimit));
                    }

                    mdSub.put("subQuery", "true");
                    dev.langchain4j.rag.query.Query subQ = QueryUtils.buildQuery(q, sidForQuery, null, mdSub);
                    handlerChain.handle(subQ, acc);

                    if (applyPrefuseCap && acc.size() > fuseLimit) {
                        acc = new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                    }
                } catch (Exception e) {
                    log.warn("[Hybrid] handler ????됰꽡 errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                }
                buckets.add(acc);
            }

            // ???? ??癲ル슔?됭짆?륂렭??嶺뚮Ĳ????????ㅼ굣筌?top ?袁⑸즵???
            // Select the fusion strategy. Softmax fusion is enabled only when
            // the mode is set to 'softmax' and a valid calibration is provided.
            List<Content> fused;
            boolean useSoftmax = "softmax".equalsIgnoreCase(fusionMode)
                    && ("minmax".equalsIgnoreCase(softmaxCalibration)
                            || "isotonic".equalsIgnoreCase(softmaxCalibration));
            final int fuseK = applyPrefuseCap ? Math.max(1, Math.min(fuseLimit, top)) : top;
            if (useSoftmax) {
                fused = fuseWithSoftmax(buckets, fuseK, question);
            } else {
                // Weighted RRF support: if the fusion mode is marked as weighted
                // and a weighted fuser is available, prefer it over the
                // unweighted RRF. Recognised values include "weighted-rrf",
                // "rrf-weighted" and "weighted".
                boolean useWeighted = weightedFuser != null &&
                        ("weighted-rrf".equalsIgnoreCase(fusionMode) ||
                                "rrf-weighted".equalsIgnoreCase(fusionMode) ||
                                "weighted".equalsIgnoreCase(fusionMode));
                if (useWeighted) {
                    fused = weightedFuser.fuse(buckets, fuseK);
                } else {
                    fused = fuser.fuse(buckets, fuseK);
                }
            }
            List<Content> combined = new ArrayList<>(local); // 'local'?? ??癲ル슢????????ㅿ폍?????????? ?嶺뚮Ĳ?됭린??筌뚯슦苑????怨쀪퐨????筌뤾퍓???
            combined.addAll(fused);

            List<Content> out = finalizeResults(combined, "text", java.util.Collections.emptyList(), question, md0);
            return out.size() > top ? out.subList(0, top) : out;

        } catch (Exception e) {
            log.error("[Hybrid] retrieveProgressive failed (sidHash={}, queryHash12={}, queryLength={}) errHash={} errLength={}",
                    SafeRedactor.hashValue(sessionKey), SafeRedactor.hash12(question), question == null ? 0 : question.length(), SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return emptyEvidence("hybrid_progressive", "provider_hard_failure", integrityStarted);
        }
    }

    /**
     * Progressive retrieval with optional routing hints. This overload accepts a
     * map of
     * metadata hints (precision search, depth, webTopK, etc.) which will be
     * embedded into
     * the Query metadata. When hints are provided the downstream web search handler
     * can
     * adjust its behaviour accordingly (e.g. precision scanning). When no hints are
     * provided the default behaviour is equivalent to the legacy
     * retrieveProgressive
     * method.
     *
     * @param question   the user question
     * @param sessionKey unique session identifier for isolation
     * @param limit      number of items to return
     * @param metaHints  optional metadata hints to embed into the query
     * @return list of retrieved content
     */
    public java.util.List<Content> retrieveProgressive(String question, String sessionKey, int limit,
            java.util.Map<String, Object> metaHints) {
        long integrityStarted = System.nanoTime();
        if (question == null || question.isBlank()) {
            return emptyEvidence("hybrid_progressive", "invalid_input", integrityStarted);
        }
        final int top = Math.max(1, limit);

        try {
            // 1) Local RAG first
            String domain;
            try {
                domain = (domainDetector != null) ? domainDetector.detect(question) : "GENERAL";
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "progressiveHints.domain.detect");
                domain = "GENERAL";
            }
            String idx = chooseIndex(domain);
            ContentRetriever pine = ragService.asContentRetriever(idx);
            String sidForQuery = (sessionKey == null || sessionKey.isBlank()) ? "__TRANSIENT__" : sessionKey;
            // Merge default metadata with hints and SID
            java.util.Map<String, Object> mdMap = new java.util.HashMap<>();
            mdMap.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sidForQuery);
            if (metaHints != null)
                mdMap.putAll(metaHints);
            mdMap.putIfAbsent("depth", "LIGHT");
            mdMap.putIfAbsent("webTopK", top);

            // ???? pre-fuse candidate cap (retrieveAll/retrieve?? ????곕럡????嚥▲꺃?? ??ш끽維亦???獄쏅똻???????モ뵲)
            // ??????????????????????
            // rerank_ce_top_k / rerank_candidate_k???????CE ????곸죷 ??ш끽維亦?????筌뤾퍔?ｏ┼??넊?癲?
            // progressive ?濡ろ뜑?灌鍮??????"fuse ???⑤챷????ш끽維亦???獄쏅똻???????좊즵???????モ뵲??嚥????????癲ル슣?????Β?援???濚욌꼬釉먮쳮????????덊렡.
            int prefuseCap = -1;
            int fuseLimit = top;
            try {
                int keepN = metaInt(mdMap, "rerank.topK", -1);
                if (keepN <= 0)
                    keepN = metaInt(mdMap, "rerank_top_k", -1);
                if (keepN <= 0)
                    keepN = metaInt(mdMap, "rerankTopK", -1);

                int candidateCap = metaInt(mdMap, "rerank.ce.topK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerank.ceTopK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerank_ce_top_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerankCeTopK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerank.candidateK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerank.candidate_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerank_candidate_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(mdMap, "rerankCandidateK", -1);

                if (candidateCap <= 0 && keepN > 0) {
                    candidateCap = Math.max(keepN * 2, Math.max(keepN, top));
                }

                if (candidateCap > 0) {
                    prefuseCap = (keepN > 0) ? Math.max(candidateCap, keepN) : candidateCap;
                    fuseLimit = Math.min(fuseLimit, Math.max(1, prefuseCap));
                    TraceStore.put("rerank.prefuse.cap", prefuseCap);
                    TraceStore.put("rerank.prefuse.limit", fuseLimit);
                }
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "progressiveHints.prefuseCap.parse");
                // fail-soft
            }
            final boolean applyPrefuseCap = prefuseCap > 0;

            // pre-fuse ??影?됀?????lane topK??cap (retriever ?????????ル춪)
            if (applyPrefuseCap) {
                try {
                    int w = metaInt(mdMap, "webTopK", -1);
                    if (w > fuseLimit)
                        mdMap.put("webTopK", String.valueOf(fuseLimit));
                    int v = metaInt(mdMap, "vecTopK", -1);
                    if (v > fuseLimit)
                        mdMap.put("vecTopK", String.valueOf(fuseLimit));
                    int vt = metaInt(mdMap, "vectorTopK", -1);
                    if (vt > fuseLimit)
                        mdMap.put("vectorTopK", String.valueOf(fuseLimit));
                    int kg = metaInt(mdMap, "kgTopK", -1);
                    if (kg > fuseLimit)
                        mdMap.put("kgTopK", String.valueOf(fuseLimit));
                } catch (Exception ignore) {
                    log.debug("[HybridRetriever] fail-soft stage={}", "progressiveHints.prefuseCap.metadata");
                    // fail-soft
                }
            }

            dev.langchain4j.rag.query.Query qObj = QueryUtils.buildQuery(question, mdMap);
            java.util.List<Content> local = pine.retrieve(qObj);
            if (applyPrefuseCap && local != null && local.size() > fuseLimit) {
                local = new java.util.ArrayList<>(local.subList(0, fuseLimit));
            }
            if (qualityEvaluator != null
                    && qualityEvaluator.isSufficient(question, local, qualityMinDocs, qualityMinScore)) {
                java.util.List<Content> out = finalizeResults(new java.util.ArrayList<>(local), "text",
                        java.util.Collections.emptyList(), question, mdMap);
                return out.size() > top ? out.subList(0, top) : out;
            }
            // Self-Ask / hygiene filter
            java.util.List<String> planned;
            if (!selfAskEnabled || selfAskPlanner == null) {
                planned = java.util.List.of(question);
            } else {
                try {
                    planned = selfAskPlanner.plan(question, 2);
                } catch (Exception e) {
                    log.warn("[Hybrid] SelfAskPlanner failed (sidHash={}, queryHash12={}, queryLength={}): errorHash={} errorLength={} -> fallback to raw query",
                            SafeRedactor.hashValue(sessionKey), SafeRedactor.hash12(question), question == null ? 0 : question.length(),
                            SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                    planned = java.util.List.of(question);
                }
            }
            java.util.List<String> queries = com.example.lms.search.QueryHygieneFilter.sanitize(planned, 2, 0.80);
            if (queries.isEmpty())
                queries = java.util.List.of(question);
            if (queries.isEmpty())
                queries = java.util.List.of(question);
            java.util.List<Integer> kSchedule = HybridRetrieverSupport.toIntList(mdMap.get("kSchedule"));
            java.util.List<java.util.List<Content>> buckets = new java.util.ArrayList<>();
            for (int qi = 0; qi < queries.size(); qi++) {
                String q = queries.get(qi);
                java.util.List<Content> acc = new java.util.ArrayList<>();
                try {
                    java.util.Map<String, Object> subMd = new java.util.HashMap<>(mdMap);
                    if (kSchedule != null && !kSchedule.isEmpty()) {
                        int idx2 = Math.min(qi, kSchedule.size() - 1);
                        Integer k = kSchedule.get(idx2);
                        if (k != null && k > 0) {
                            int kk = k;
                            if (applyPrefuseCap && kk > fuseLimit)
                                kk = fuseLimit;
                            subMd.put("webTopK", String.valueOf(kk));
                        }
                    }

                    // pre-fuse ??影?됀?????lane topK??cap (retriever ?????????ル춪)
                    if (applyPrefuseCap) {
                        int w = metaInt(subMd, "webTopK", -1);
                        if (w > fuseLimit)
                            subMd.put("webTopK", String.valueOf(fuseLimit));
                        int v = metaInt(subMd, "vecTopK", -1);
                        if (v > fuseLimit)
                            subMd.put("vecTopK", String.valueOf(fuseLimit));
                        int vt = metaInt(subMd, "vectorTopK", -1);
                        if (vt > fuseLimit)
                            subMd.put("vectorTopK", String.valueOf(fuseLimit));
                        int kg = metaInt(subMd, "kgTopK", -1);
                        if (kg > fuseLimit)
                            subMd.put("kgTopK", String.valueOf(fuseLimit));
                    }

                    // LangChain4j v1.0.1 does not support Boolean metadata values.
                    // Encode booleans as strings to avoid IllegalArgumentException at runtime.
                    subMd.put("subQuery", "true");
                    dev.langchain4j.rag.query.Query subQ = QueryUtils.buildQuery(q, subMd);
                    handlerChain.handle(subQ, acc);

                    if (applyPrefuseCap && acc.size() > fuseLimit) {
                        acc = new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                    }
                } catch (Exception e) {
                    log.warn("[Hybrid] handler ????됰꽡 errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                }
                buckets.add(acc);
            }
            // Fusion and finalization
            java.util.List<Content> fused;
            boolean useSoftmax = "softmax".equalsIgnoreCase(fusionMode)
                    && ("minmax".equalsIgnoreCase(softmaxCalibration)
                            || "isotonic".equalsIgnoreCase(softmaxCalibration));
            final int fuseK = applyPrefuseCap ? Math.max(1, Math.min(fuseLimit, top)) : top;
            if (useSoftmax) {
                fused = fuseWithSoftmax(buckets, fuseK, question);
            } else {
                boolean useWeighted = weightedFuser != null &&
                        ("weighted-rrf".equalsIgnoreCase(fusionMode) ||
                                "rrf-weighted".equalsIgnoreCase(fusionMode) ||
                                "weighted".equalsIgnoreCase(fusionMode));
                if (useWeighted) {
                    fused = weightedFuser.fuse(buckets, fuseK);
                } else {
                    fused = fuser.fuse(buckets, fuseK);
                }
            }
            java.util.List<Content> combined = new java.util.ArrayList<>(local);
            combined.addAll(fused);
            java.util.List<Content> out = finalizeResults(combined, "text", java.util.Collections.emptyList(), question,
                    mdMap);
            return out.size() > top ? out.subList(0, top) : out;
        } catch (Exception e) {
            log.error("[Hybrid] retrieveProgressive failed (sidHash={}, queryHash12={}, queryLength={}) errHash={} errLength={}",
                    SafeRedactor.hashValue(sessionKey), SafeRedactor.hash12(question), question == null ? 0 : question.length(), SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return emptyEvidence("hybrid_progressive", "provider_hard_failure", integrityStarted);
        }
    }

    /**
     * ???湲깅룪 ??얜?????怨뚮옖筌???濡ろ떟???+ RRF ????
     */
    public List<Content> retrieveAll(List<String> queries, int limit) {
        return retrieveAll(queries, limit, "__TRANSIENT__", null);
    }

    private java.util.concurrent.ThreadPoolExecutor retrievalExecutor() {
        java.util.concurrent.ThreadPoolExecutor current = retrievalExecutor;
        if (current != null && !current.isShutdown() && !current.isTerminated()) return current;
        synchronized (retrievalExecutorLock) {
            current = retrievalExecutor;
            if (current == null || current.isShutdown() || current.isTerminated()) {
                int workers = Math.max(1, Math.min(32, maxParallel));
                int queueCapacity = Math.max(1, Math.min(1_024, executorQueueCapacity));
                java.util.concurrent.ThreadFactory factory = task -> {
                    Thread thread = new Thread(
                            null,
                            task,
                            "awx-hybrid-retrieval-" + EXECUTOR_THREAD_IDS.incrementAndGet(),
                            0L,
                            false);
                    thread.setDaemon(true);
                    return thread;
                };
                retrievalExecutor = current = new java.util.concurrent.ThreadPoolExecutor(
                        workers,
                        workers,
                        0L,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity),
                        factory,
                        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
            }
            return current;
        }
    }

    @PreDestroy
    void shutdownRetrievalExecutor() {
        shutdownRetrievalExecutor(executorShutdownWaitMs);
    }

    private boolean shutdownRetrievalExecutor(long awaitMs) {
        java.util.concurrent.ThreadPoolExecutor current;
        synchronized (retrievalExecutorLock) {
            current = retrievalExecutor;
            retrievalExecutor = null;
        }
        if (current == null) return true;
        current.shutdown();
        boolean terminated = awaitExecutorTermination(current, awaitMs);
        if (!terminated) {
            current.shutdownNow();
            terminated = awaitExecutorTermination(current, awaitMs);
        }
        TraceStore.put("hybrid.executor.lifecycle.terminated", terminated);
        return terminated;
    }

    private static boolean awaitExecutorTermination(
            java.util.concurrent.ThreadPoolExecutor executor,
            long awaitMs) {
        try {
            return executor.awaitTermination(Math.max(1L, awaitMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long remainingBudgetMillis(
            com.abandonware.ai.addons.budget.TimeBudget requestBudget,
            long localDeadlineNanos) {
        long remainingNanos = localDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) return 0L;
        long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        long localRemainingMs = Math.max(1L, millis);
        return requestBudget == null
                ? localRemainingMs
                : Math.min(requestBudget.remainingMillis(), localRemainingMs);
    }

    private static String timeoutReason(boolean requestDeadlineDominates) {
        return requestDeadlineDominates
                ? "request_deadline_exhausted"
                : "provider_timeout";
    }

    private int retrievalBranchLimit() {
        return Math.max(1, Math.min(256, maxBranches));
    }

    private static long deadlineNanos(long timeoutMs) {
        long now = System.nanoTime();
        long delta = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        if (delta > 0L && now > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return now + delta;
    }

    private static java.util.List<Content> flattenWithinLimit(
            java.util.List<java.util.List<Content>> buckets,
            int limit) {
        java.util.List<Content> out = new java.util.ArrayList<>();
        if (buckets == null) return out;
        int boundedLimit = Math.max(1, limit);
        for (java.util.List<Content> bucket : buckets) {
            if (bucket == null) continue;
            for (Content content : bucket) {
                if (content == null) continue;
                out.add(content);
                if (out.size() >= boundedLimit) return out;
            }
        }
        return out;
    }

    private static void traceHybridExecutor(
            String terminalReason,
            java.util.concurrent.ThreadPoolExecutor executor,
            int submitted,
            int completed,
            int providerFailures) {
        TraceStore.put("hybrid.executor.terminalReason", terminalReason);
        TraceStore.put("hybrid.executor.submittedCount", submitted);
        TraceStore.put("hybrid.executor.completedCount", completed);
        TraceStore.put("hybrid.executor.providerHardFailureCount", providerFailures);
        TraceStore.put("hybrid.executor.poolSize", executor == null ? 0 : executor.getPoolSize());
        TraceStore.put("hybrid.executor.activeCount", executor == null ? 0 : executor.getActiveCount());
        TraceStore.put("hybrid.executor.queueSize", executor == null ? 0 : executor.getQueue().size());
        TraceStore.put("hybrid.executor.queueCapacity", executor == null ? 0
                : executor.getQueue().size() + executor.getQueue().remainingCapacity());
    }

    private void resetRetrievalExecutorForTest(int workers, int queueCapacity) {
        shutdownRetrievalExecutor(1_000L);
        this.maxParallel = Math.max(1, workers);
        this.executorQueueCapacity = Math.max(1, queueCapacity);
    }

    private boolean shutdownRetrievalExecutorForTest(long awaitMs) {
        return shutdownRetrievalExecutor(awaitMs);
    }

    private java.util.Map<String, Integer> retrievalExecutorMetricsForTest() {
        java.util.concurrent.ThreadPoolExecutor current = retrievalExecutor;
        return java.util.Map.of(
                "poolSize", current == null ? 0 : current.getPoolSize(),
                "active", current == null ? 0 : current.getActiveCount(),
                "queued", current == null ? 0 : current.getQueue().size(),
                "queueCapacity", current == null ? Math.max(1, executorQueueCapacity)
                        : current.getQueue().size() + current.getQueue().remainingCapacity());
    }

    private record BranchResult(int index, java.util.List<Content> contents) {}

    private static final class BranchFuture
            extends java.util.concurrent.FutureTask<BranchResult> {
        private final java.util.concurrent.BlockingQueue<Integer> completed;
        private final int branchIndex;

        private BranchFuture(
                java.util.concurrent.Callable<BranchResult> task,
                java.util.concurrent.BlockingQueue<Integer> completed,
                int branchIndex) {
            super(task);
            this.completed = completed;
            this.branchIndex = branchIndex;
        }

        @Override
        protected void done() {
            completed.offer(branchIndex);
        }
    }

    private java.util.concurrent.Callable<BranchResult> branchTask(
            String query,
            Object sessionKey,
            java.util.Map<String, Object> metaHints,
            int branchIndex,
            boolean applyPrefuseCap,
            int fuseLimit,
            com.abandonware.ai.addons.budget.TimeBudget requestBudget,
            long localDeadlineNanos,
            java.util.concurrent.atomic.AtomicBoolean deadlineHit,
            java.util.concurrent.atomic.AtomicInteger providerFailures) {
        return com.example.lms.infra.exec.ContextPropagation.wrapCallable(() -> {
            Thread.interrupted();
            try {
                if (remainingBudgetMillis(requestBudget, localDeadlineNanos) <= 0L) {
                    deadlineHit.set(true);
                    return new BranchResult(branchIndex, java.util.List.of());
                }
                java.util.List<Content> acc = new java.util.ArrayList<>();
                try {
                    java.util.Map<String, Object> md = new java.util.HashMap<>();
                    if (metaHints != null && !metaHints.isEmpty()) md.putAll(metaHints);
                    if (applyPrefuseCap) {
                        int web = metaInt(md, "webTopK", -1);
                        if (web > fuseLimit) md.put("webTopK", String.valueOf(fuseLimit));
                        int vector = metaInt(md, "vecTopK", -1);
                        if (vector > fuseLimit) md.put("vecTopK", String.valueOf(fuseLimit));
                        int vectorAlias = metaInt(md, "vectorTopK", -1);
                        if (vectorAlias > fuseLimit) md.put("vectorTopK", String.valueOf(fuseLimit));
                        int kg = metaInt(md, "kgTopK", -1);
                        if (kg > fuseLimit) md.put("kgTopK", String.valueOf(fuseLimit));
                    }
                    md.put("subQuery", "true");
                    md.put("branchIndex", branchIndex);
                    md.put("queryHash12", SafeRedactor.hash12(query));
                    dev.langchain4j.rag.query.Query subQuery =
                            QueryUtils.buildQuery(query, sessionKey, null, md);
                    handlerChain.handle(subQuery, acc);
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw cancelled;
                } catch (Exception failure) {
                    providerFailures.incrementAndGet();
                    recordHybridBranchFailure(branchIndex, query, failure, "handler");
                    log.warn("[Hybrid] handler branch failed queryHash12={} errorHash={} errorLength={}",
                            SafeRedactor.hash12(query), SafeRedactor.hashValue(String.valueOf(failure)),
                            String.valueOf(failure).length());
                }
                if (applyPrefuseCap && acc.size() > fuseLimit) {
                    return new BranchResult(
                            branchIndex,
                            new java.util.ArrayList<>(acc.subList(0, fuseLimit)));
                }
                return new BranchResult(branchIndex, acc);
            } finally {
                Thread.interrupted();
            }
        });
    }

    /**
     * ??釉먯뒜?????쒙쭕????⑤베肄?plate/topK/budget ????癲ル슢??????Β?????ㅻ깹鸚???ш끽維???嚥▲꺂痢?????곷츉?棺??짆?삠궘?
     *
     * <p>
     * 濚욌꼬?댄꺍?? ??貫????熬곣뫀????싷㎗? __TRANSIENT__?棺??짆?띤맪?Query??癲ル슢???????떵??⑤똾留?plate??좊읈? ???源놁졆 ?濡ろ떟???????앗꾩쒀?濡?뎄???
     * ?袁⑸즵????? ????낆툗 ???뽮덫?影?놁씀? ?????? ??癲ル슢??袁λ빝??筌먲퐢痢?metaHints??Query metadata????낆뒩????
     * WebSearch/SelfAsk/Analyze ??影?됀嚥♂쇱씀? ???源놁졆??????援???????щ쿊 ??筌먲퐢??
     */
    public List<Content> retrieveAll(List<String> queries, int limit, Object sessionKey,
            java.util.Map<String, Object> metaHints) {
        long integrityStarted = System.nanoTime();
        if (queries == null || queries.isEmpty()) {
            return emptyEvidence("hybrid_all", "invalid_input", integrityStarted);
        }
        int branchLimit = retrievalBranchLimit();
        if (queries.size() > branchLimit) {
            TraceStore.put("hybrid.executor.branchCount", queries.size());
            TraceStore.put("hybrid.executor.branchLimit", branchLimit);
            traceHybridExecutor("branch_budget_exceeded", null, 0, 0, 0);
            return emptyEvidence("hybrid_all", "branch_budget_exceeded", integrityStarted);
        }

        final Object sid = (sessionKey != null ? sessionKey : "__TRANSIENT__");

        // pre-fuse candidate cap:
        // rerank_ce_top_k / rerank_candidate_k???????CE ????곸죷 ??ш끽維亦?????筌뤾퍔?ｏ┼??넊?癲?
        // fuse ???⑤챷??"??ш끽維亦???獄쏅똻??limit)"????좊즵???????モ뵲??嚥????????癲ル슣?????Β?援???濚욌꼬釉먮쳮????????덊렡.
        int effectiveLimit = Math.max(1, limit);
        int prefuseCap = -1;
        try {
            if (metaHints != null && !metaHints.isEmpty()) {
                int keepN = metaInt(metaHints, "rerank.topK", -1);
                if (keepN <= 0)
                    keepN = metaInt(metaHints, "rerank_top_k", -1);
                if (keepN <= 0)
                    keepN = metaInt(metaHints, "rerankTopK", -1);

                int candidateCap = metaInt(metaHints, "rerank.ce.topK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(metaHints, "rerank_ce_top_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(metaHints, "rerankCeTopK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(metaHints, "rerank.candidateK", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(metaHints, "rerank_candidate_k", -1);
                if (candidateCap <= 0)
                    candidateCap = metaInt(metaHints, "rerankCandidateK", -1);

                // keepN癲?????덉툗 ?濡ろ뜑??? ??れ삀?????⑥??2x keepN 癲ル슢???移???ш끽維亦??癲ル슢?????琉뮻?fuse ??嚥▲꺃??????ル늅筌?
                if (candidateCap <= 0 && keepN > 0) {
                    candidateCap = Math.max(keepN * 2, keepN);
                }

                if (candidateCap > 0) {
                    prefuseCap = (keepN > 0) ? Math.max(candidateCap, keepN) : candidateCap;
                    effectiveLimit = Math.min(effectiveLimit, Math.max(1, prefuseCap));
                    TraceStore.put("rerank.prefuse.cap", prefuseCap);
                    TraceStore.put("rerank.prefuse.limit", effectiveLimit);
                }
            }
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "retrieveAll.prefuseCap.parse");
            // fail-soft: keep original limit
        }

        final int fuseLimit = Math.max(1, effectiveLimit);
        final boolean applyPrefuseCap = prefuseCap > 0;
        final com.abandonware.ai.addons.budget.TimeBudget requestBudget =
                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        final long initialRequestRemainingMs = requestBudget == null
                ? Long.MAX_VALUE
                : requestBudget.remainingMillis();
        final boolean requestDeadlineDominates = requestBudget != null
                && initialRequestRemainingMs <= Math.max(1L, hybridRequestTimeoutMs);
        final long localDeadlineNanos = deadlineNanos(hybridRequestTimeoutMs);
        final java.util.concurrent.atomic.AtomicBoolean deadlineHit =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger providerFailures =
                new java.util.concurrent.atomic.AtomicInteger();
        String terminalReason = "success";
        int submittedCount = 0;
        int completedCount = 0;
        java.util.concurrent.ThreadPoolExecutor executorForTrace = null;

        try {
            java.util.List<java.util.List<Content>> results;
            if (debugSequential) {
                log.warn("[Hybrid] debug.sequential=true ??handlerChain ??筌?鍮?????덈틖");
                results = new java.util.ArrayList<>();
                for (int branchIndex = 0; branchIndex < queries.size(); branchIndex++) {
                    if (remainingBudgetMillis(requestBudget, localDeadlineNanos) <= 0L) {
                        deadlineHit.set(true);
                        terminalReason = timeoutReason(requestDeadlineDominates);
                        break;
                    }
                    String q = queries.get(branchIndex);
                    java.util.List<Content> acc = new java.util.ArrayList<>();
                    try {
                        java.util.Map<String, Object> md = new java.util.HashMap<>();
                        if (metaHints != null && !metaHints.isEmpty())
                            md.putAll(metaHints);

                        // pre-fuse ??影?됀?????lane topK??cap (retriever ?????????ル춪)
                        if (applyPrefuseCap) {
                            int w = metaInt(md, "webTopK", -1);
                            if (w > fuseLimit)
                                md.put("webTopK", String.valueOf(fuseLimit));
                            int v = metaInt(md, "vecTopK", -1);
                            if (v > fuseLimit)
                                md.put("vecTopK", String.valueOf(fuseLimit));
                            int vt = metaInt(md, "vectorTopK", -1);
                            if (vt > fuseLimit)
                                md.put("vectorTopK", String.valueOf(fuseLimit));
                            int kg = metaInt(md, "kgTopK", -1);
                            if (kg > fuseLimit)
                                md.put("kgTopK", String.valueOf(fuseLimit));
                        }

                        md.put("subQuery", "true");
                        md.put("branchIndex", branchIndex);
                        md.put("queryHash12", SafeRedactor.hash12(q));
                        dev.langchain4j.rag.query.Query subQ = QueryUtils.buildQuery(q, sid, null, md);
                        java.util.concurrent.ThreadPoolExecutor pool = retrievalExecutor();
                        executorForTrace = pool;
                        java.util.concurrent.BlockingQueue<Integer> completion =
                                new java.util.concurrent.ArrayBlockingQueue<>(1);
                        java.util.List<Content> branchAccumulator = acc;
                        final int sequentialBranchIndex = branchIndex;
                        BranchFuture future = new BranchFuture(
                                com.example.lms.infra.exec.ContextPropagation.wrapCallable(() -> {
                                    Thread.interrupted();
                                    try {
                                        handlerChain.handle(subQ, branchAccumulator);
                                        return new BranchResult(sequentialBranchIndex, branchAccumulator);
                                    } finally {
                                        Thread.interrupted();
                                    }
                                }),
                                completion,
                                sequentialBranchIndex);
                        try {
                            pool.execute(future);
                            submittedCount++;
                        } catch (java.util.concurrent.RejectedExecutionException saturated) {
                            terminalReason = "executor_saturated";
                            traceHybridExecutor(
                                    terminalReason, pool, submittedCount, completedCount,
                                    providerFailures.get());
                            return emptyEvidence("hybrid_all", terminalReason, integrityStarted);
                        }
                        long remainingMs = remainingBudgetMillis(requestBudget, localDeadlineNanos);
                        Integer completedIndex;
                        try {
                            completedIndex = completion.poll(
                                    remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (InterruptedException interrupted) {
                            future.cancel(false);
                            pool.purge();
                            Thread.currentThread().interrupt();
                            traceHybridExecutor(
                                    "caller_cancelled", pool, submittedCount, completedCount,
                                    providerFailures.get());
                            java.util.concurrent.CancellationException cancelled =
                                    new java.util.concurrent.CancellationException("caller_cancelled");
                            cancelled.initCause(interrupted);
                            throw cancelled;
                        }
                        if (completedIndex == null) {
                            deadlineHit.set(true);
                            terminalReason = timeoutReason(requestDeadlineDominates);
                            future.cancel(false);
                            pool.purge();
                            break;
                        }
                        try {
                            acc = future.get().contents();
                        } catch (java.util.concurrent.ExecutionException completedFailure) {
                            if (completedFailure.getCause()
                                    instanceof java.util.concurrent.CancellationException cancelled) {
                                traceHybridExecutor(
                                        "provider_cancelled", pool, submittedCount, completedCount,
                                        providerFailures.get());
                                throw cancelled;
                            }
                            throw completedFailure;
                        }

                        if (applyPrefuseCap && acc.size() > fuseLimit) {
                            acc = new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                        }
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (Exception e) {
                        providerFailures.incrementAndGet();
                        recordHybridBranchFailure(branchIndex, q, e, "handler");
                        log.warn("[Hybrid] handler branch failed queryHash12={} errorHash={} errorLength={}", SafeRedactor.hash12(q), SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                    }
                    results.add(acc);
                    completedCount++;
                }
            } else {
                // ??れ삀??? ????モ뵲 ?怨뚮옖筌??????덈틖 (???살쓴???? ??????ヂ???)
                //
                // UAW: ?怨뚮옖筌????獄쏅똻???딅텑??????MDC/GuardContext/TraceStore ??ш낄援?怨ル쨬??쎛 ??熬곣뱿逾쏉┼?
                // handlerChain??"pass癲? ?袁⑸즵????嚥▲꺂???좊읈? ?濡ろ뜏????뽰씀? 0???⑥????嚥?竊??嚥▲꺂痢????읐???⑤８痢??좊읈? ????덊렡.
                // ContextPropagation???⑥??task????좊즴?????釉먯뒜?????쒙쭕????爾?????덉쉐???????筌먲퐢??
                java.util.concurrent.ThreadPoolExecutor pool = retrievalExecutor();
                executorForTrace = pool;
                java.util.concurrent.BlockingQueue<Integer> completionOrder =
                        new java.util.concurrent.ArrayBlockingQueue<>(Math.max(1, queries.size()));
                try {
                    java.util.List<BranchFuture> futures =
                            new java.util.ArrayList<>(queries.size());

                    for (int branchIndex = 0; branchIndex < queries.size(); branchIndex++) {
                        if (remainingBudgetMillis(requestBudget, localDeadlineNanos) <= 0L) {
                            deadlineHit.set(true);
                            terminalReason = timeoutReason(requestDeadlineDominates);
                            break;
                        }
                        String q = queries.get(branchIndex);
                        final int branchIdx = branchIndex;
                        try {
                            java.util.function.Supplier<java.util.List<Content>> branchSupplier =
                                com.example.lms.infra.exec.ContextPropagation.wrapSupplier(() -> {
                                    Thread.interrupted();
                                    if (remainingBudgetMillis(requestBudget, localDeadlineNanos) <= 0L) {
                                        deadlineHit.set(true);
                                        return java.util.List.of();
                                    }
                                    java.util.List<Content> acc = new java.util.ArrayList<>();
                                    try {
                                        java.util.Map<String, Object> md = new java.util.HashMap<>();
                                        if (metaHints != null && !metaHints.isEmpty())
                                            md.putAll(metaHints);

                                        // pre-fuse ??影?됀?????lane topK??cap (retriever ?????????ル춪)
                                        if (applyPrefuseCap) {
                                            int w = metaInt(md, "webTopK", -1);
                                            if (w > fuseLimit)
                                                md.put("webTopK", String.valueOf(fuseLimit));
                                            int v = metaInt(md, "vecTopK", -1);
                                            if (v > fuseLimit)
                                                md.put("vecTopK", String.valueOf(fuseLimit));
                                            int vt = metaInt(md, "vectorTopK", -1);
                                            if (vt > fuseLimit)
                                                md.put("vectorTopK", String.valueOf(fuseLimit));
                                            int kg = metaInt(md, "kgTopK", -1);
                                            if (kg > fuseLimit)
                                                md.put("kgTopK", String.valueOf(fuseLimit));
                                        }

                                        md.put("subQuery", "true");
                                        md.put("branchIndex", branchIdx);
                                        md.put("queryHash12", SafeRedactor.hash12(q));
                                        dev.langchain4j.rag.query.Query subQ = QueryUtils.buildQuery(q, sid, null, md);
                                        handlerChain.handle(subQ, acc);
                                    } catch (java.util.concurrent.CancellationException cancelled) {
                                        throw cancelled;
                                    } catch (Exception e) {
                                        providerFailures.incrementAndGet();
                                        log.debug("[HybridRetriever] fail-soft stage={}", "retrieveAll.async.handler");
                                        recordHybridBranchFailure(branchIdx, q, e, "handler");
                                        log.warn("[Hybrid] handler branch failed queryHash12={} errorHash={} errorLength={}", SafeRedactor.hash12(q), SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                                    }

                                    if (applyPrefuseCap && acc.size() > fuseLimit) {
                                        Thread.interrupted();
                                        return new java.util.ArrayList<>(acc.subList(0, fuseLimit));
                                    }
                                    Thread.interrupted();
                                    return acc;
                                });
                            BranchFuture submitted = new BranchFuture(
                                    () -> {
                                        Thread.interrupted();
                                        try {
                                            return new BranchResult(branchIdx, branchSupplier.get());
                                        } finally {
                                            Thread.interrupted();
                                        }
                                    },
                                    completionOrder,
                                    branchIdx);
                            pool.execute(submitted);
                            futures.add(submitted);
                            submittedCount++;
                        } catch (java.util.concurrent.RejectedExecutionException saturated) {
                            futures.forEach(future -> future.cancel(false));
                            pool.purge();
                            terminalReason = "executor_saturated";
                            traceHybridExecutor(
                                    terminalReason, pool, submittedCount, completedCount,
                                    providerFailures.get());
                            return emptyEvidence("hybrid_all", terminalReason, integrityStarted);
                        }
                    }

                    java.util.List<java.util.List<Content>> joined = new java.util.ArrayList<>(
                            java.util.Collections.nCopies(queries.size(), java.util.List.of()));
                    while (completedCount < submittedCount) {
                        long remainingMs = remainingBudgetMillis(requestBudget, localDeadlineNanos);
                        if (remainingMs <= 0L) {
                            deadlineHit.set(true);
                            terminalReason = timeoutReason(requestDeadlineDominates);
                            break;
                        }
                        Integer completedIndex;
                        try {
                            completedIndex = completionOrder.poll(
                                    remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (InterruptedException interrupted) {
                            futures.forEach(future -> future.cancel(false));
                            pool.purge();
                            Thread.currentThread().interrupt();
                            traceHybridExecutor(
                                    "caller_cancelled", pool, submittedCount, completedCount,
                                    providerFailures.get());
                            java.util.concurrent.CancellationException cancelled =
                                    new java.util.concurrent.CancellationException("caller_cancelled");
                            cancelled.initCause(interrupted);
                            throw cancelled;
                        }
                        if (completedIndex == null) {
                            deadlineHit.set(true);
                            terminalReason = timeoutReason(requestDeadlineDominates);
                            break;
                        }
                        completedCount++;
                        try {
                            joined.set(completedIndex, futures.get(completedIndex).get().contents());
                        } catch (java.util.concurrent.CancellationException cancelled) {
                            if ("success".equals(terminalReason)) terminalReason = "provider_cancelled";
                        } catch (InterruptedException interrupted) {
                            futures.forEach(future -> future.cancel(false));
                            pool.purge();
                            Thread.currentThread().interrupt();
                            traceHybridExecutor(
                                    "caller_cancelled", pool, submittedCount, completedCount,
                                    providerFailures.get());
                            java.util.concurrent.CancellationException cancelled =
                                    new java.util.concurrent.CancellationException("caller_cancelled");
                            cancelled.initCause(interrupted);
                            throw cancelled;
                        } catch (java.util.concurrent.ExecutionException e) {
                            if (e.getCause() instanceof java.util.concurrent.CancellationException cancelled) {
                                futures.forEach(future -> future.cancel(false));
                                pool.purge();
                                traceHybridExecutor(
                                        "provider_cancelled", pool, submittedCount, completedCount,
                                        providerFailures.get());
                                throw cancelled;
                            }
                            log.debug("[HybridRetriever] fail-soft stage={}", "retrieveAll.async.join");
                            String branchQuery = completedIndex < queries.size()
                                    ? queries.get(completedIndex) : "";
                            providerFailures.incrementAndGet();
                            recordHybridBranchFailure(completedIndex, branchQuery, e, "missing_future");
                        }
                    }
                    if (deadlineHit.get()) {
                        terminalReason = timeoutReason(requestDeadlineDominates);
                        futures.forEach(future -> {
                            if (!future.isDone()) future.cancel(false);
                        });
                        pool.purge();
                    }
                    results = joined;
                } finally {
                    pool.purge();
                }
            }

            // RRF or Softmax ???? ?????ㅼ굣筌?limit ?袁⑸즵???
            boolean useSoftmax = "softmax".equalsIgnoreCase(fusionMode)
                    && ("minmax".equalsIgnoreCase(softmaxCalibration)
                            || "isotonic".equalsIgnoreCase(softmaxCalibration));
            java.util.List<Content> fused = java.util.List.of();
            long fusionRemainingMs = remainingBudgetMillis(requestBudget, localDeadlineNanos);
            if (deadlineHit.get() || fusionRemainingMs <= 0L) {
                deadlineHit.set(true);
                terminalReason = timeoutReason(requestDeadlineDominates);
                fused = flattenWithinLimit(results, fuseLimit);
            } else {
                boolean useWeighted = weightedFuser != null &&
                        ("weighted-rrf".equalsIgnoreCase(fusionMode) ||
                                "rrf-weighted".equalsIgnoreCase(fusionMode) ||
                                "weighted".equalsIgnoreCase(fusionMode));
                java.util.List<java.util.List<Content>> fusionInput = results;
                java.util.concurrent.ThreadPoolExecutor pool = retrievalExecutor();
                executorForTrace = pool;
                java.util.concurrent.Future<java.util.List<Content>> fusionFuture;
                try {
                    fusionFuture = pool.submit(com.example.lms.infra.exec.ContextPropagation.wrapCallable(() -> {
                        Thread.interrupted();
                        try {
                            if (useSoftmax) {
                                return fuseWithSoftmax(fusionInput, fuseLimit, queries.get(0));
                            }
                            return useWeighted
                                    ? weightedFuser.fuse(fusionInput, fuseLimit)
                                    : fuser.fuse(fusionInput, fuseLimit);
                        } finally {
                            Thread.interrupted();
                        }
                    }));
                    TraceStore.put("hybrid.executor.fusionSubmitted", true);
                } catch (java.util.concurrent.RejectedExecutionException saturated) {
                    terminalReason = "executor_saturated";
                    fused = flattenWithinLimit(fusionInput, fuseLimit);
                    fusionFuture = null;
                }
                if (fusionFuture != null) {
                    try {
                        fused = fusionFuture.get(
                                fusionRemainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException timeout) {
                        deadlineHit.set(true);
                        terminalReason = timeoutReason(requestDeadlineDominates);
                        fusionFuture.cancel(false);
                        pool.purge();
                        fused = flattenWithinLimit(fusionInput, fuseLimit);
                    } catch (InterruptedException interrupted) {
                        fusionFuture.cancel(false);
                        pool.purge();
                        Thread.currentThread().interrupt();
                        traceHybridExecutor(
                                "caller_cancelled", pool, submittedCount, completedCount,
                                providerFailures.get());
                        java.util.concurrent.CancellationException cancelled =
                                new java.util.concurrent.CancellationException("caller_cancelled");
                        cancelled.initCause(interrupted);
                        throw cancelled;
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        traceHybridExecutor(
                                "provider_cancelled", pool, submittedCount, completedCount,
                                providerFailures.get());
                        throw cancelled;
                    } catch (java.util.concurrent.ExecutionException failed) {
                        Throwable cause = failed.getCause();
                        if (cause instanceof java.util.concurrent.CancellationException cancelled) {
                            traceHybridExecutor(
                                    "provider_cancelled", pool, submittedCount, completedCount,
                                    providerFailures.get());
                            throw cancelled;
                        }
                        if (cause instanceof Exception exception) throw exception;
                        if (cause instanceof Error error) throw error;
                        throw new RuntimeException(cause);
                    }
                }
            }
            if ("success".equals(terminalReason) && providerFailures.get() > 0) {
                terminalReason = (fused == null || fused.isEmpty())
                        ? "provider_hard_failure"
                        : "partial_provider_hard_failure";
            }
            traceHybridExecutor(
                    terminalReason, executorForTrace, submittedCount, completedCount,
                    providerFailures.get());
            if ((fused == null || fused.isEmpty()) && !"success".equals(terminalReason)) {
                return emptyEvidence("hybrid_all", terminalReason, integrityStarted);
            }
            return fused == null ? java.util.List.of() : fused;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.debug("[HybridRetriever] fail-soft stage={}", "retrieveAll.outer");
            log.error("[Hybrid] retrieveAll ????됰꽡 errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            String reason = deadlineHit.get()
                    ? timeoutReason(requestDeadlineDominates)
                    : "provider_hard_failure";
            traceHybridExecutor(
                    reason, executorForTrace, submittedCount, completedCount,
                    providerFailures.get() + (deadlineHit.get() ? 0 : 1));
            return emptyEvidence("hybrid_all", reason, integrityStarted);
        }
    } // retrieveAll ??

    private static List<Content> emptyEvidence(String source, String reason, long started) {
        TraceStore.put("retrieval.integrity.source", source);
        TraceStore.put("retrieval.integrity.inputCount", 0);
        TraceStore.put("retrieval.integrity.filteredCount", 0);
        TraceStore.put("retrieval.integrity.finalUsedCount", 0);
        TraceStore.put("retrieval.integrity.fallbackStage", "none");
        TraceStore.put("retrieval.integrity.emptyReason", reason);
        TraceStore.put("retrieval.integrity.elapsedMs",
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        return List.of();
    }

    // ??????????????????????????????????????????????????????????????????????????????????????????
    // ???ㅺ컼????れ삀??뫢??濡ろ떟??? CognitiveState/PromptContext???袁⑸즵??????얜?????嶺뚮Ĳ??????怨뚮옖筌???濡ろ떟???
    // ??????????????????????????????????????????????????????????????????????????????????????????
    public List<Content> retrieveStateDriven(PromptContext ctx, int limit) {
        String userQ = Optional.ofNullable(ctx.userQuery()).orElse("");
        String lastA = ctx.lastAssistantAnswer();
        String subject = ctx.subject();
        // QueryTransformer???嶺뚮Ĳ???API ??筌믨퀡裕?
        List<String> queries;
        String qtxReason = null;
        try {
            queries = queryTransformer.transformEnhanced(userQ, lastA, subject);
            int beforeFilter = queries == null ? 0 : queries.size();
            queries = queries == null ? List.of() : queries.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
            if (beforeFilter > queries.size()) {
                TraceStore.put("queryTransformer.blankFilteredCount", beforeFilter - queries.size());
            }
            if (queries.isEmpty()) {
                qtxReason = "empty_result";
                queries = List.of(userQ);
            }
        } catch (Exception e) {
            log.debug("[HybridRetriever] fail-soft stage={}", "stateDriven.queryTransformer");
            qtxReason = QueryTransformerBypassReason.classify(e);
            queries = List.of(userQ);
        }
        if (qtxReason != null) {
            TraceStore.put("queryTransformer.bypassed", true);
            TraceStore.put("queryTransformer.reason", qtxReason);
            TraceStore.put("aux.queryTransformer.degraded", true);
            TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", qtxReason);
        }
        return retrieveAll(queries, Math.max(1, limit));
    }

    // ?????????????????????????????????????????????????????????? ???????????????????????????????????????????????????????????????

    /**
     * (????? ?熬곣뫀毓쇌벧?????モ???- ??ш끽維????????
     */
    private double cosineSimilarity(String q, String doc) {
        try {
            var qVec = embeddingModel.embed(q).content().vector();
            var dVec = embeddingModel.embed(doc).content().vector();
            if (qVec.length != dVec.length) {
                throw new IllegalArgumentException("Embedding dimension mismatch");
            }
            double dot = 0, nq = 0, nd = 0;
            for (int i = 0; i < qVec.length; i++) {
                dot += qVec[i] * dVec[i];
                nq += qVec[i] * qVec[i];
                nd += dVec[i] * dVec[i];
            }
            if (nq == 0 || nd == 0)
                return 0d;
            return dot / (Math.sqrt(nq) * Math.sqrt(nd) + 1e-9);
        } catch (Exception e) {
            log.debug("[HybridRetriever] fail-soft stage={}", "cosineSimilarity");
            return 0d;
        }
    }

    /**
     * 癲ル슔?됭짆?륂렭??嶺뚮Ĳ???
     * - dedupeKey ??れ삀?? 濚욌꼬?댄꺇????癰귙끋源?
     * - ???살쓴????ш끽維곮???怨뚮옖????+0.20)
     * - ???????????뉖윾?縕???嶺뚮㉡?ｈ????topK ?袁⑸즵???
     */
    private List<Content> finalizeResults(List<Content> raw,
            String dedupeKey,
            List<String> officialDomains,
            String queryText,
            Map<String, Object> meta) {

        Map<String, Object> metaMap = (meta != null) ? meta : Map.of();

        // 1) 濚욌꼬?댄꺇????癰귙끋源?+ ?????굿????ш낄援??
        Map<String, Content> uniq = new LinkedHashMap<>();
        List<Content> dropped = new ArrayList<>(); // ????곕럢?????뽮덫???怨뚮옖????
        for (Content c : raw) {
            if (c == null)
                continue;

            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());

            double rel = 0.0;
            try {
                rel = relevanceScoringService.relatedness(
                        Optional.ofNullable(queryText).orElse(""),
                        text);
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "relatedness.prefilter");
                HybridRetrieverTraceSuppressions.trace("relatedness.prefilter", ignore);
            }
            if (rel < minRelatedness) {
                dropped.add(c);
                continue;
            }

            String key;
            switch (dedupeKey) {
                case "url" -> key = Optional.ofNullable(HybridRetrieverSupport.extractUrl(text)).orElse(text);
                case "hash" -> key = Integer.toHexString(text.hashCode());
                default -> key = text; // "text"
            }
            uniq.putIfAbsent(key, c);
        }
        // Compatibility safety net: restore only under an explicit operator policy.
        if (uniq.isEmpty() && !dropped.isEmpty() && relatednessFilterFailSoft) {
            log.warn("[Hybrid] 癲ル슢?꾤땟????濡ろ떟????濡ろ뜏????뽰씀? minRelatedness({}) 雅?퍔瑗띰㎖?뱁맪???⑥????ш낄援?轅곗땡??녠텤嶺? Safety Net ?袁⑸즵獒뺣끆???筌뚯슦肉????ㅼ굣筌?{}???怨뚮옖甕걔?? queryHash12={} queryLength={}",
                    minRelatedness, Math.min(topK, dropped.size()),
                    SafeRedactor.hash12(queryText), queryText == null ? 0 : queryText.length());
            for (Content c : dropped) {
                String key;
                if (dedupeKey != null && !dedupeKey.isBlank()) {
                    key = buildDedupeKey(dedupeKey, c);
                } else if (c.textSegment() != null) {
                    key = c.textSegment().text();
                } else {
                    key = null;
                }
                if (key == null || key.isBlank()) {
                    continue;
                }
                uniq.putIfAbsent(key, c);
                if (uniq.size() >= topK) {
                    break;
                }
            }
            TraceStore.put("retrieval.integrity.relatednessFailSoft", true);
            TraceStore.put("retrieval.integrity.relatednessRestoredCount", uniq.size());
        } else if (uniq.isEmpty() && !dropped.isEmpty()) {
            TraceStore.put("retrieval.integrity.relatednessRejectedCount", dropped.size());
            TraceStore.put("retrieval.integrity.emptyReason", "relatedness_filtered_empty");
        }

        // 2) ?濡ろ뜑???1癲???雅?(???⑤챶?뺧┼?candidates ??숆강筌???????
        List<Content> candidates = new ArrayList<>(uniq.values());
        List<Content> firstPass = (lightWeightRanker != null)
                ? lightWeightRanker.rank(
                        candidates,
                        Optional.ofNullable(queryText).orElse(""),
                        Math.max(topK * 2, 20))
                : candidates;

        // ???????筌?????れ삀??뫢??怨뚮옖?????⑤베毓????嚥▲꺃?ｉ툣??筌???? ??ш끽維??域밸Ŧ??????????ル늅筌?
        if (elementConstraintScorer != null) {
            try {
                firstPass = elementConstraintScorer.rescore(
                        Optional.ofNullable(queryText).orElse(""),
                        firstPass);
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "elementConstraint.rescore");
                /* ???源놁벁 ???뺤깓??*/ }
        }

        // 2-B) ???(????? ?????뤆??釉먯뒮筌??????? 癲ル슣??袁ｋ즵??貫?????? ???モ????嶺? ?????
        // - ??좊즵獒뺣돀?? ??ш끽維亦????源껎롦뤃짰??????ш끽維???????늄????좊읈??濚왿몾?????饔낃동???濡ろ뜐???嶺뚮ㅎ?????ш끽維쀨굢??筌뚯슦肉?????덈틖 ??????濡ろ뜏????筌뤾퍓???
        // - Drift removal: plan/meta can disable CE entirely (enableCrossEncoder=false)
        // - Drift removal: plan/meta can override backend (rerank_backend) and top-k
        // (rerank_top_k)
        if (!firstPass.isEmpty()) {
            boolean shouldRerank = true;
            try {
                if (rerankGate != null) {
                    shouldRerank = rerankGate.shouldRerank(firstPass);
                }
            } catch (Exception e) {
                // Fail-soft: if the gate fails, fall back to original size check
                log.debug("[HybridRetriever] fail-soft stage={}", "rerankGate.shouldRerank");
                shouldRerank = firstPass.size() >= rerankCeTopK;
                log.debug("[Hybrid] rerankGate errorHash={} errorLength={}; falling back to size check", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            }
            // Orchestration meta gating: allow disabling expensive CE rerank.
            boolean ceEnabled = metaBool(metaMap, "enableCrossEncoder", true);
            boolean auxSuppressed = metaBool(metaMap, "nightmareMode", false)
                    || metaBool(metaMap, "auxLlmDown", false)
                    || metaBool(metaMap, "auxDegraded", false)
                    || metaBool(metaMap, "auxHardDown", false)
                    || metaBool(metaMap, "strikeMode", false)
                    || metaBool(metaMap, "compressionMode", false)
                    || metaBool(metaMap, "bypassMode", false);
            if (!ceEnabled || auxSuppressed) {
                shouldRerank = false;
                log.debug("[Hybrid] cross-encoder rerank suppressed by orchestration meta");
                try {
                    TraceStore.put("rerank.ce.skipped", true);
                    String reason = !ceEnabled ? "disabled" : "suppressed";
                    TraceStore.put("rerank.ce.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
                } catch (Exception ignore) {
                    log.debug("[HybridRetriever] fail-soft stage={}", "rerank.skippedSuppressedTrace");
                    HybridRetrieverTraceSuppressions.trace("rerank.skippedSuppressedTrace", ignore);
                }
            }
            // Select reranker per request (supports plan override + auto).
            String backendOverride = metaString(metaMap, "rerank.backend");
            if (backendOverride == null)
                backendOverride = metaString(metaMap, "rerank_backend");
            if (backendOverride == null)
                backendOverride = metaString(metaMap, "rerankBackend");
            Boolean onnxOverride = metaMap.containsKey("onnx.enabled") ? metaBool(metaMap, "onnx.enabled", true) : null;
            com.example.lms.service.rag.rerank.CrossEncoderReranker activeReranker = resolveCrossEncoderReranker(
                    metaMap, backendOverride, onnxOverride, ceEnabled);
            if (activeReranker == null) {
                shouldRerank = false;
                try {
                    TraceStore.put("rerank.ce.skipped", true);
                    TraceStore.put("rerank.ce.skipReason", "no_reranker");
                } catch (Exception ignore) {
                    log.debug("[HybridRetriever] fail-soft stage={}", "rerank.noRerankerTrace");
                    HybridRetrieverTraceSuppressions.trace("rerank.noRerankerTrace", ignore);
                }
            }

            if (shouldRerank) {
                boolean allowed = true;
                // Acquire a short cooldown lock to prevent thundering herd rerank calls. When
                // the lock cannot be obtained the expensive cross-encoder rerank is skipped.
                if (cooldownService != null) {
                    try {
                        String baseKey = Optional.ofNullable(queryText).orElse("");
                        String digest = org.apache.commons.codec.digest.DigestUtils.md5Hex(baseKey);
                        String key = "ce:rerank:" + digest;
                        allowed = cooldownService.setNxEx(key, "1", 1);
                        if (!allowed) {
                            log.debug("[Hybrid] cross-encoder rerank skipped due to cooldown lock");
                            try {
                                TraceStore.put("rerank.ce.skipped", true);
                                TraceStore.put("rerank.ce.skipReason", "cooldown");
                            } catch (Exception ignore) {
                                log.debug("[HybridRetriever] fail-soft stage={}", "rerank.cooldownTrace");
                                HybridRetrieverTraceSuppressions.trace("rerank.cooldownTrace", ignore);
                            }
                        }
                    } catch (Exception ignore) {
                        log.debug("[HybridRetriever] fail-soft stage={}", "rerank.cooldownLock");
                        // fallback to allow rerank if lock acquisition fails
                        allowed = true;
                    }
                }
                if (allowed) {
                    try {
                        // Candidate cap: when rerank_top_k is provided by the plan, score fewer docs.
                        // Strong cost saving:
                        // - rerank_ce_top_k / rerank_candidate_k: explicit candidate cap
                        // - rerank_top_k: keepN override; if candidate cap is absent, derive cap (~2x
                        // keepN)
                        int keepK = metaInt(metaMap, "rerank.topK", -1);
                        if (keepK <= 0)
                            keepK = metaInt(metaMap, "rerank_top_k", -1);
                        if (keepK <= 0)
                            keepK = metaInt(metaMap, "rerankTopK", -1);

                        int candidateOverride = metaInt(metaMap, "rerank.ce.topK", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerank.ceTopK", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerank_ce_top_k", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerankCeTopK", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerank.candidateK", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerank.candidate_k", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerank_candidate_k", -1);
                        if (candidateOverride <= 0)
                            candidateOverride = metaInt(metaMap, "rerankCandidateK", -1);

                        int candidateCap;
                        if (candidateOverride > 0) {
                            candidateCap = candidateOverride;
                        } else if (keepK > 0) {
                            candidateCap = Math.max(keepK * 2, Math.max(keepK, topK));
                        } else {
                            candidateCap = Math.max(topK * 2, 20);
                        }
                        candidateCap = Math.min(candidateCap, firstPass.size());
                        // Ensure enough candidates for downstream topK/keepK needs
                        int minNeed = Math.min(topK, firstPass.size());
                        if (keepK > 0) {
                            minNeed = Math.max(minNeed, Math.min(keepK, firstPass.size()));
                        }
                        if (candidateCap < minNeed) {
                            candidateCap = minNeed;
                        }

                        List<Content> ceInput = firstPass;
                        if (candidateCap < firstPass.size()) {
                            ceInput = firstPass.subList(0, candidateCap);
                        }

                        int topN = candidateCap;
                        if (keepK > 0) {
                            topN = Math.min(keepK, candidateCap);
                        }

                        try {
                            TraceStore.put("rerank.ce.executed", true);
                            TraceStore.put("rerank.ce.candidateCap", candidateCap);
                            TraceStore.put("rerank.ce.keepN", topN);
                            if (candidateOverride > 0)
                                TraceStore.put("rerank.ce.candidateCap.override", candidateOverride);
                        } catch (Exception ignore) {
                            log.debug("[HybridRetriever] fail-soft stage={}", "rerank.executedTrace");
                            HybridRetrieverTraceSuppressions.trace("rerank.executedTrace", ignore);
                        }

                        log.debug("[Hybrid] cross-encoder candidateCap={} keepN={} (plan keep={}, cand={})",
                                candidateCap,
                                topN,
                                keepK,
                                candidateOverride);

                        firstPass = activeReranker.rerank(
                                Optional.ofNullable(queryText).orElse(""),
                                ceInput,
                                Math.max(1, Math.min(topN, ceInput.size())));
                        try {
                            TraceStore.put("rerank.ce.executed", true);
                        } catch (Exception ignore) {
                            log.debug("[HybridRetriever] fail-soft stage={}", "rerank.executedFallbackTrace");
                            HybridRetrieverTraceSuppressions.trace("rerank.executedFallbackTrace", ignore);
                        }
                    } catch (Exception e) {
                        log.debug("[Hybrid] cross-encoder rerank skipped errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                        try {
                            TraceStore.put("rerank.ce.skipped", true);
                            TraceStore.put("rerank.ce.skipReason", "error");
                        } catch (Exception ignore) {
                            log.debug("[HybridRetriever] fail-soft stage={}", "rerank.errorTrace");
                            HybridRetrieverTraceSuppressions.trace("rerank.errorTrace", ignore);
                        }
                    }
                }
            } else {
                log.debug("[Hybrid] cross-encoder rerank skipped by gate");
                try {
                    TraceStore.putIfAbsent("rerank.ce.skipped", true);
                    TraceStore.putIfAbsent("rerank.ce.skipReason", "gate");
                } catch (Exception ignore) {
                    log.debug("[HybridRetriever] fail-soft stage={}", "rerank.gateTrace");
                    HybridRetrieverTraceSuppressions.trace("rerank.gateTrace", ignore);
                }
            }
        }

        // 3) ?嶺? ????몄????鶯?+ ?嶺뚮㉡?ｈ??
        class Scored {
            final Content content;
            final double score;

            Scored(Content content, double score) {
                this.content = content;
                this.score = score;
            }
        }
        List<Scored> scored = new ArrayList<>();
        int rank = 0;
        // ??NEW: ????깆뱾 ??雅???좊읈?濚욌꼬?댄꺍???怨뚮옖????
        final double wRel = hp.getDouble("retrieval.rank.w.rel", 0.60);
        final double wBase = hp.getDouble("retrieval.rank.w.base", 0.30);
        final double wAuth = hp.getDouble("retrieval.rank.w.auth", 0.10);
        final double bonusOfficial = hp.getDouble("retrieval.rank.bonus.official", 0.20);

        // ??NEW: ML ?怨뚮옖?????節뚮쳮??
        final double alpha = hp.getDouble("ml.correction.alpha", 0.0);
        final double beta = hp.getDouble("ml.correction.beta", 0.0);
        final double gamma = hp.getDouble("ml.correction.gamma", 0.0);
        final double d0 = hp.getDouble("ml.correction.d0", 0.0);
        final double mu = hp.getDouble("ml.correction.mu", 0.0);
        final double lambda = hp.getDouble("ml.correction.lambda", 1.0);

        for (Content c : firstPass) {
            rank++;
            double base = 1.0 / rank;

            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());

            String url = HybridRetrieverSupport.extractUrl(text);

            double authority = authorityScorer != null ? authorityScorer.weightFor(url) : 0.5;

            double rel = 0.0;
            try {
                rel = relevanceScoringService.relatedness(
                        Optional.ofNullable(queryText).orElse(""),
                        text);
            } catch (Exception ignore) {
                log.debug("[HybridRetriever] fail-soft stage={}", "relatedness.scoreFusion");
                HybridRetrieverTraceSuppressions.trace("relatedness.scoreFusion", ignore);
            }

            // ??NEW: 癲ル슔?됭짆?륂렭??????= wRel*???굿????닳뵣 + wBase*??れ삀??????+ wAuth*Authority (+???살쓴???ш끽維곮???怨뚮옖????
            double score0 = (wRel * rel) + (wBase * base) + (wAuth * authority);
            if (HybridRetrieverSupport.isOfficial(url, officialDomains)) {
                score0 += bonusOfficial;
            }
            // ??NEW: ML ????븍빝???怨뚮옖???????? - ??좊즴????怨뚮옖?????tail ??筌?苑?
            double finalScore = useMlCorrection
                    ? MLCalibrationUtil.finalCorrection(score0, alpha, beta, gamma, d0, mu, lambda, true)
                    : score0;
            scored.add(new Scored(c, finalScore));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream()
                .limit(topK)
                .map(s -> s.content)
                .collect(Collectors.toList());
    }

    // ?????????????????????????????????????????????????????????? NEW: Softmax ????(???쒒??嶺뚮Ĳ?됭린管異????)
    // ??????????????????????????????????????????????????????????
    /** ?????類???앹쒜???濡ろ뜏???醫듽걫???嚥▲굥猷??癲ル슢?꾤땟怨뀀눀??????logit)??癲ル슢?????琉뮻?softmax???嶺?????????????ㅼ굣筌?N????關履??? */
    private List<Content> fuseWithSoftmax(List<List<Content>> buckets, int limit, String queryText) {

        // Softmax fusion weights (externalised via HyperparameterService)
        double wRelated = 0.6;
        double wAuthority = 0.1;
        double wRank = 0.3;
        try {
            if (hp != null) {
                wRelated = hp.getDouble("retrieval.fusion.softmax.w-related", wRelated);
                wAuthority = hp.getDouble("retrieval.fusion.softmax.w-authority", wAuthority);
                wRank = hp.getDouble("retrieval.fusion.softmax.w-rank", wRank);
            }
        } catch (Exception ignore) {
            log.debug("[HybridRetriever] fail-soft stage={}", "softmax.weights");
            // fallback to defaults on any error
        }

        Map<String, Content> keeper = new LinkedHashMap<>();
        Map<String, Double> logit = new LinkedHashMap<>();

        int bIdx = 0;
        for (List<Content> bucket : buckets) {
            if (bucket == null)
                continue;
            int rank = 0;
            for (Content c : bucket) {
                rank++;
                String text = Optional.ofNullable(c.textSegment()).map(TextSegment::text).orElse(c.toString());
                String key = Integer.toHexString(text.hashCode()); // ??좊즲???dedupe
                String url = HybridRetrieverSupport.extractUrl(text);
                double authority = (authorityScorer != null) ? authorityScorer.weightFor(url) : 0.5;
                double related = 0.0;
                try {
                    related = relevanceScoringService.relatedness(Optional.ofNullable(queryText).orElse(""), text);
                } catch (Exception ignore) {
                    log.debug("[HybridRetriever] fail-soft stage={}", "relatedness.bucketFusion");
                    HybridRetrieverTraceSuppressions.trace("relatedness.bucketFusion", ignore);
                }
                double base = 1.0 / (rank + 0.0); // ???ㅼ굣筌???????좊읈?濚?
                double bucketW = 1.0 / (bIdx + 1.0); // ??嚥▲굧留??類???앹쒜???熬곣뫚?????
                double l = (wRelated * related) + (wAuthority * authority) + (wRank * base * bucketW);

                keeper.putIfAbsent(key, c);
                logit.merge(key, l, Math::max); // ??좊즵?? ???뽮덫?????좊읈????亦? logit癲????
            }
            bIdx++;
        }
        if (logit.isEmpty())
            return List.of();

        // softmax ?嶺???????쒓랜萸????源놁젳?????? ???嶺뚮㉡????亦? ??筌믨퀡????嶺뚮㉡?ｈ??
        String[] keys = logit.keySet().toArray(new String[0]);
        // Extract logits as a primitive array. These values will be calibrated
        // before applying softmax. Calibration helps ensure the logits occupy
        // a comparable range across different queries, improving the softmax
        // distribution. When calibration is disabled the original values are
        // passed through unchanged.
        double[] scores = logit.values().stream().mapToDouble(Double::doubleValue).toArray();
        try {
            if ("minmax".equalsIgnoreCase(softmaxCalibration)) {
                scores = com.example.lms.service.rag.fusion.FusionCalibrator.minMax(scores);
            } else if ("isotonic".equalsIgnoreCase(softmaxCalibration)) {
                // shim for isotonic regression. Fall back to minmax
                // scaling until an isotonic calibrator is implemented.
                scores = com.example.lms.service.rag.fusion.FusionCalibrator.minMax(scores);
            }
        } catch (Exception e) {
            log.debug("[Hybrid] softmax calibration failed errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
        }
        // Compute softmax probabilities with the calibrated scores.
        double[] p = SoftmaxUtil.softmax(scores, fusionTemperature);

        // ?嶺뚮㉡?????????뉖윾?縕?????ㅼ굣筌?limit
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < p.length; i++)
            idx.add(i);
        idx.sort((i, j) -> Double.compare(p[j], p[i]));

        java.util.List<Content> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(limit, idx.size()); i++) {
            out.add(keeper.get(keys[idx.get(i)]));
        }
        return out;
    }

    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original,
            String sessionKey) {
        return QueryUtils.buildQuery(original.text(), sessionKey, null);
    }
}
